package com.underrated.docreader.xlsx

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

private const val TAG = "XlsxParser"

/** Hard ceiling per sheet so a pathological workbook cannot exhaust the heap. */
private const val MAX_CELLS_PER_SHEET = 300_000
private const val MAX_LAYOUT_ROWS = 100_000
private const val MAX_LAYOUT_COLUMNS = 512
private const val MAX_WORKBOOK_BYTES = 100L * 1024 * 1024
private const val MAX_ARCHIVE_ENTRIES = 20_000
private const val MAX_TOTAL_EXPANDED_BYTES = 256L * 1024 * 1024
private const val MAX_PART_EXPANDED_BYTES = 64L * 1024 * 1024
private const val MAX_WORKSHEETS = 1_024
private const val MAX_RELATIONSHIPS = 4_096
private const val MAX_MERGED_REGIONS = 100_000
private const val MAX_SHARED_STRINGS = 1_000_000
private const val MAX_SHARED_STRING_CHARS = 32L * 1024 * 1024
private const val MAX_STYLE_RECORDS = 10_000

/**
 * Element name without its namespace prefix.
 *
 * Namespace processing is off (it is faster and avoids prefix bookkeeping), which
 * means [XmlPullParser.getName] returns whatever prefix the writer chose. Most tools
 * put SpreadsheetML in the default namespace and emit `<sheet>`, but POI and several
 * .NET writers emit `<x:sheet>`. Comparing local names makes the parser indifferent
 * to the writer's prefix choice.
 */
private fun XmlPullParser.local(): String = name?.substringAfterLast(':') ?: ""

/** Attribute lookup by local name, so `r:id`, `rel:id` and `id` all resolve. */
private fun XmlPullParser.attr(localName: String): String? {
    for (i in 0 until attributeCount) {
        if (getAttributeName(i).substringAfterLast(':') == localName) return getAttributeValue(i)
    }
    return null
}

/**
 * Zip lookup that tolerates how different writers store entry names: exact first,
 * then without a leading slash, then a case-insensitive scan.
 */
private fun ZipFile.entryFor(path: String): java.util.zip.ZipEntry? {
    val normalised = path.removePrefix("/")
    getEntry(normalised)?.let { return it }
    getEntry(path)?.let { return it }
    val wanted = normalised.lowercase()
    val entries = entries()
    while (entries.hasMoreElements()) {
        val candidate = entries.nextElement()
        if (candidate.name.removePrefix("/").lowercase() == wanted) return candidate
    }
    return null
}

/** Reject oversized archives before parsing any workbook XML. */
private fun ZipFile.validateSizeBudget() {
    val allEntries = entries()
    var count = 0
    var expandedBytes = 0L
    while (allEntries.hasMoreElements()) {
        val entry = allEntries.nextElement()
        count++
        if (count > MAX_ARCHIVE_ENTRIES) error("This workbook has too many ZIP entries.")
        val size = entry.size
        if (size < 0 || size > MAX_PART_EXPANDED_BYTES) {
            error("This workbook contains an oversized component.")
        }
        if (expandedBytes > MAX_TOTAL_EXPANDED_BYTES - size) {
            error("This workbook expands beyond the supported size limit.")
        }
        expandedBytes += size
    }
}

/** Enforce a hard limit while decompressing, even if ZIP metadata is malformed. */
private class LimitedEntryInputStream(input: InputStream) : FilterInputStream(input) {
    private var bytesRead = 0L

    override fun read(): Int {
        val value = `in`.read()
        if (value >= 0) record(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val remaining = MAX_PART_EXPANDED_BYTES - bytesRead
        if (remaining <= 0) {
            if (`in`.read() < 0) return -1
            throw IOException("This workbook contains an oversized component.")
        }
        val count = `in`.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (count > 0) record(count.toLong())
        return count
    }

    private fun record(count: Long) {
        bytesRead += count
        if (bytesRead > MAX_PART_EXPANDED_BYTES) {
            throw IOException("This workbook contains an oversized component.")
        }
    }
}

private fun ZipFile.openLimited(entry: java.util.zip.ZipEntry): InputStream {
    if (entry.size < 0 || entry.size > MAX_PART_EXPANDED_BYTES) {
        error("This workbook contains an oversized component.")
    }
    return LimitedEntryInputStream(getInputStream(entry))
}

/**
 * Resolves a relationship Target against the directory of the part that declared it,
 * per the OPC rules. Absolute targets ("/xl/...") are package-rooted.
 */
private fun resolveTarget(ownerPart: String, target: String): String {
    if (target.startsWith("/")) return target.removePrefix("/")
    val dir = ownerPart.substringBeforeLast('/', "")
    val joined = if (dir.isEmpty()) target else "$dir/$target"
    // collapse ./ and ../
    val parts = ArrayList<String>()
    for (segment in joined.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts.add(segment)
        }
    }
    return parts.joinToString("/")
}

/** The _rels part that describes [part]. */
private fun relsPathFor(part: String): String {
    val dir = part.substringBeforeLast('/', "")
    val file = part.substringAfterLast('/')
    return if (dir.isEmpty()) "_rels/$file.rels" else "$dir/_rels/$file.rels"
}

/** Result of opening a workbook. */
sealed interface WorkbookResult {
    data class Success(val workbook: XlsxWorkbook) : WorkbookResult
    data class Failure(val message: String) : WorkbookResult
}

/**
 * A workbook read straight from the .xlsx package.
 *
 * Why no library: Apache POI does not dex at minSdk 24 (`MethodHandle.invoke`
 * requires API 26) and brings ~12.7 MB of jars; fastexcel-reader is built on StAX
 * (`javax.xml.stream`), which Android does not ship; SheetJS's community build does
 * not read cell styles. An .xlsx is a zip of XML, and Android already has a pull
 * parser, so this reads the parts directly: no dependency, no APK cost, no licence
 * question, and full control over styling and memory.
 *
 * Memory model: the package stays on disk and is read through [ZipFile]. Shared
 * strings and styles are small and loaded once; **worksheets are parsed on demand,
 * one at a time**, so switching tabs releases the previous sheet.
 */
class XlsxWorkbook private constructor(
    private val zip: ZipFile,
    private val backing: File,
    val sheets: List<SheetRef>,
    private val sharedStrings: List<String>,
    private val styles: List<CellStyle>,
) : Closeable {

    fun styleAt(index: Int): CellStyle =
        styles.getOrElse(index) { CellStyle.Default }

    /** Parses one worksheet. Callers should hold at most one sheet at a time. */
    fun loadSheet(index: Int): Sheet {
        require(index in sheets.indices) { "Worksheet index is outside the workbook." }
        val ref = sheets[index]
        val entry = zip.entryFor(ref.path)
        if (entry == null) {
            Log.w(TAG, "worksheet part missing: '${ref.path}' for sheet '${ref.name}'")
            return Sheet(ref.name, 0, 0, emptyMap(), emptyList(), emptyMap(), emptyMap(), 8.43f, 15f, FrozenPane())
        }
        zip.openLimited(entry).use { stream ->
            return parseSheet(ref.name, stream)
        }
    }

    private fun parseSheet(name: String, stream: InputStream): Sheet {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)

        val cells = HashMap<Long, Cell>()
        val merges = ArrayList<MergedRegion>()
        val columnWidths = HashMap<Int, Float>()
        val rowHeights = HashMap<Int, Float>()
        var defaultColumnWidth = 8.43f
        var defaultRowHeight = 15f
        var frozen = FrozenPane()
        var maxRow = 0
        var maxCol = 0
        var truncated = false

        // current cell state
        var cellRow = 0
        var cellCol = 0
        var cellType = ""
        var cellStyleIndex = -1
        var isFormula = false
        var inValue = false
        var inInlineString = false
        var valueText = StringBuilder()
        var currentRow = 0

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.local()) {
                    "sheetFormatPr" -> {
                        parser.attr("defaultColWidth")?.toFloatOrNull()
                            ?.let { defaultColumnWidth = it }
                        parser.attr("defaultRowHeight")?.toFloatOrNull()
                            ?.let { defaultRowHeight = it }
                    }

                    "col" -> {
                        val min = parser.attr("min")?.toIntOrNull() ?: 0
                        val max = parser.attr("max")?.toIntOrNull() ?: 0
                        val width = parser.attr("width")?.toFloatOrNull()
                        if (width != null && min > 0) {
                            // Guard against the "whole row" col spans some writers emit.
                            val upper = minOf(max, min + 512)
                            for (c in min..upper) columnWidths[c - 1] = width
                        }
                    }

                    "pane" -> {
                        if (parser.attr("state") == "frozen") {
                            val x = parser.attr("xSplit")?.toFloatOrNull()?.toInt() ?: 0
                            val y = parser.attr("ySplit")?.toFloatOrNull()?.toInt() ?: 0
                            frozen = FrozenPane(rows = y, columns = x)
                        }
                    }

                    "row" -> {
                        currentRow = (parser.attr("r")?.toIntOrNull() ?: (currentRow + 1)) - 1
                        if (currentRow !in 0 until MAX_LAYOUT_ROWS) {
                            error("This worksheet exceeds the supported row limit.")
                        }
                        parser.attr("ht")?.toFloatOrNull()
                            ?.let { rowHeights[currentRow] = it }
                        if (currentRow > maxRow) maxRow = currentRow
                    }

                    "c" -> {
                        val ref = parser.attr("r")
                        val parsed = ref?.let { parseCellRef(it) }
                        if (parsed != null) {
                            cellRow = parsed.first
                            cellCol = parsed.second
                            if (cellRow >= MAX_LAYOUT_ROWS || cellCol >= MAX_LAYOUT_COLUMNS) {
                                error("This worksheet exceeds the supported row or column limit.")
                            }
                        } else {
                            cellRow = currentRow
                            cellCol += 1
                        }
                        cellType = parser.attr("t") ?: ""
                        cellStyleIndex = parser.attr("s")?.toIntOrNull() ?: -1
                        isFormula = false
                        valueText = StringBuilder()
                    }

                    "f" -> isFormula = true
                    "v" -> inValue = true
                    "is" -> inInlineString = true
                    "t" -> if (inInlineString) inValue = true

                    "mergeCell" -> {
                        val ref = parser.attr("ref") ?: ""
                        val parts = ref.split(':')
                        if (parts.size == 2) {
                            val a = parseCellRef(parts[0])
                            val b = parseCellRef(parts[1])
                            if (a != null && b != null) {
                                if (maxOf(a.first, b.first) >= MAX_LAYOUT_ROWS ||
                                    maxOf(a.second, b.second) >= MAX_LAYOUT_COLUMNS
                                ) error("This worksheet contains a merge outside the supported limits.")
                                if (merges.size >= MAX_MERGED_REGIONS) {
                                    error("This worksheet contains too many merged regions.")
                                }
                                merges.add(
                                    MergedRegion(
                                        minOf(a.first, b.first), maxOf(a.first, b.first),
                                        minOf(a.second, b.second), maxOf(a.second, b.second),
                                    ),
                                )
                            }
                        }
                    }
                }

                XmlPullParser.TEXT -> if (inValue) valueText.append(parser.text)

                XmlPullParser.END_TAG -> when (parser.local()) {
                    "v" -> inValue = false
                    "t" -> if (inInlineString) inValue = false
                    "is" -> inInlineString = false
                    "c" -> {
                        if (cells.size < MAX_CELLS_PER_SHEET) {
                            buildCell(cellRow, cellCol, cellType, cellStyleIndex, isFormula, valueText.toString())
                                ?.let {
                                    cells[Sheet.key(cellRow, cellCol)] = it
                                    if (cellRow > maxRow) maxRow = cellRow
                                    if (cellCol > maxCol) maxCol = cellCol
                                }
                        } else {
                            truncated = true
                        }
                    }
                }
            }
            event = parser.next()
        }

        return Sheet(
            name = name,
            rowCount = maxRow + 1,
            columnCount = maxCol + 1,
            cells = cells,
            merges = merges,
            columnWidths = columnWidths,
            rowHeights = rowHeights,
            defaultColumnWidth = defaultColumnWidth,
            defaultRowHeightPt = defaultRowHeight,
            frozen = frozen,
            truncated = truncated,
        )
    }

    private fun buildCell(
        row: Int,
        column: Int,
        type: String,
        styleIndex: Int,
        formula: Boolean,
        raw: String,
    ): Cell? {
        if (raw.isEmpty()) return null
        val style = styleAt(styleIndex)

        return when (type) {
            "s" -> {
                val idx = raw.trim().toIntOrNull() ?: return null
                val text = sharedStrings.getOrElse(idx) { "" }
                if (text.isEmpty()) null
                else Cell(row, column, CellKind.TEXT, text, null, styleIndex, formula)
            }

            "str", "inlineStr" ->
                Cell(row, column, CellKind.TEXT, raw, null, styleIndex, formula)

            "b" -> Cell(
                row, column, CellKind.BOOLEAN,
                if (raw.trim() == "1") "TRUE" else "FALSE", null, styleIndex, formula,
            )

            "e" -> Cell(row, column, CellKind.ERROR, raw, null, styleIndex, formula)

            else -> {
                val number = raw.trim().toDoubleOrNull()
                    ?: return Cell(row, column, CellKind.TEXT, raw, null, styleIndex, formula)
                val code = style.numberFormat
                if (CellFormatting.isDateFormat(code)) {
                    Cell(
                        row, column, CellKind.DATE,
                        CellFormatting.formatDate(number, code), number, styleIndex, formula,
                    )
                } else {
                    Cell(
                        row, column, CellKind.NUMBER,
                        CellFormatting.formatNumber(number, code), number, styleIndex, formula,
                    )
                }
            }
        }
    }

    override fun close() {
        runCatching { zip.close() }
        runCatching { backing.delete() }
    }

    companion object {

        fun open(context: Context, uri: Uri): WorkbookResult {
            val cache = File(context.cacheDir, "xlsx").apply { mkdirs() }
            cache.listFiles()?.forEach { it.delete() }
            val local = File(cache, "workbook.xlsx")

            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(local).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied += read
                            if (copied > MAX_WORKBOOK_BYTES) error("Workbook exceeds the 100 MB limit.")
                            output.write(buffer, 0, read)
                        }
                    }
                } ?: return WorkbookResult.Failure("The file could not be read.")
            } catch (t: Throwable) {
                return WorkbookResult.Failure(t.message ?: "The file could not be read.")
            }

            val zip = try {
                ZipFile(local)
            } catch (t: Throwable) {
                local.delete()
                return WorkbookResult.Failure("This is not a valid .xlsx file (not a zip archive).")
            }

            return try {
                zip.validateSizeBudget()
                val bookPart = findWorkbookPart(zip)
                if (bookPart == null) {
                    Log.w(TAG, "no workbook part; entries=${entryNames(zip, 25)}")
                    zip.close()
                    local.delete()
                    return WorkbookResult.Failure(
                        "This file does not contain a spreadsheet part, so it is not a workbook.",
                    )
                }
                Log.i(TAG, "workbook part: $bookPart")

                val relations = parseRelationships(zip, bookPart)
                Log.i(TAG, "workbook relationships: ${relations.size}")

                val sheets = parseWorkbook(zip, bookPart, relations)
                if (sheets.isEmpty()) {
                    Log.w(
                        TAG,
                        "no <sheet> elements in '$bookPart'; rels=${relations.keys} " +
                            "entries=${entryNames(zip, 25)}",
                    )
                    zip.close()
                    local.delete()
                    return WorkbookResult.Failure("No worksheets were found in this workbook.")
                }
                Log.i(TAG, "sheets: " + sheets.joinToString { "${it.name}->${it.path}" })

                val shared = parseSharedStrings(zip, bookPart, relations)
                val styles = parseStyles(zip, bookPart, relations)
                Log.i(TAG, "sharedStrings=${shared.size} cellXfs=${styles.size}")
                WorkbookResult.Success(XlsxWorkbook(zip, local, sheets, shared, styles))
            } catch (t: Throwable) {
                Log.w(TAG, "open failed", t)
                runCatching { zip.close() }
                local.delete()
                WorkbookResult.Failure(t.message ?: "This workbook could not be read.")
            }
        }

        private fun entryNames(zip: ZipFile, limit: Int): String {
            val names = ArrayList<String>()
            val e = zip.entries()
            while (e.hasMoreElements() && names.size < limit) names.add(e.nextElement().name)
            return names.joinToString()
        }

        /**
         * Finds the workbook part the way the OPC spec says to: follow the
         * officeDocument relationship in `_rels/.rels`.
         *
         * The previous version assumed `xl/workbook.xml`. That holds for Excel and
         * openpyxl but not for every writer, and when it does not hold the package
         * looks empty â€” which is exactly the "No worksheets were found" report.
         */
        private fun findWorkbookPart(zip: ZipFile): String? {
            zip.entryFor("_rels/.rels")?.let { entry ->
                zip.openLimited(entry).use { stream ->
                    val parser = Xml.newPullParser()
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    parser.setInput(stream, null)
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.START_TAG && parser.local() == "Relationship") {
                            val type = parser.attr("Type").orEmpty()
                            val target = parser.attr("Target")
                            if (target != null && type.endsWith("/officeDocument")) {
                                val resolved = resolveTarget("", target)
                                if (zip.entryFor(resolved) != null) return resolved
                                Log.w(TAG, "officeDocument target '$resolved' not in package")
                            }
                        }
                        event = parser.next()
                    }
                }
            }
            // Conventional location, then a last-resort scan.
            zip.entryFor("xl/workbook.xml")?.let { return "xl/workbook.xml" }
            val e = zip.entries()
            while (e.hasMoreElements()) {
                val name = e.nextElement().name.removePrefix("/")
                if (name.endsWith("workbook.xml") && !name.contains("_rels")) return name
            }
            return null
        }

        /** rId -> resolved part path, from the rels of [ownerPart]. */
        private fun parseRelationships(zip: ZipFile, ownerPart: String): Map<String, String> {
            val relsPath = relsPathFor(ownerPart)
            val entry = zip.entryFor(relsPath)
            if (entry == null) {
                Log.w(TAG, "no relationships part at '$relsPath'")
                return emptyMap()
            }
            val out = HashMap<String, String>()
            zip.openLimited(entry).use { stream ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(stream, null)
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.local() == "Relationship") {
                        val id = parser.attr("Id")
                        val target = parser.attr("Target")
                        val type = parser.attr("Type").orEmpty()
                        // External targets point outside the package; skip them.
                        val external = parser.attr("TargetMode").equals("External", true)
                        if (id != null && target != null && !external) {
                            if (out.size >= MAX_RELATIONSHIPS * 2) {
                                error("This workbook has too many relationships.")
                            }
                            out[id] = resolveTarget(ownerPart, target)
                            if (type.isNotEmpty()) out["type:$id"] = type
                        }
                    }
                    event = parser.next()
                }
            }
            return out
        }

        private fun parseWorkbook(
            zip: ZipFile,
            bookPart: String,
            relations: Map<String, String>,
        ): List<SheetRef> {
            val entry = zip.entryFor(bookPart) ?: return emptyList()
            val bookDir = bookPart.substringBeforeLast('/', "")
            val out = ArrayList<SheetRef>()
            zip.openLimited(entry).use { stream ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(stream, null)
                var event = parser.eventType
                var index = 0
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.local() == "sheet") {
                        index++
                        val name = parser.attr("name") ?: "Sheet $index"
                        // attr() matches on local name, so r:id / rel:id / id all work.
                        val rid = parser.attr("id")
                        val fallback = if (bookDir.isEmpty()) {
                            "worksheets/sheet$index.xml"
                        } else {
                            "$bookDir/worksheets/sheet$index.xml"
                        }
                        val path = relations[rid] ?: fallback
                        if (rid == null) Log.w(TAG, "sheet '$name' has no r:id; assuming $fallback")
                        if (out.size >= MAX_WORKSHEETS) {
                            error("This workbook has too many worksheets.")
                        }
                        out.add(SheetRef(name, path))
                    }
                    event = parser.next()
                }
            }
            return out
        }

        /** Finds a part by relationship type, falling back to the conventional name. */
        private fun partFor(
            zip: ZipFile,
            bookPart: String,
            relations: Map<String, String>,
            typeSuffix: String,
            conventional: String,
        ): java.util.zip.ZipEntry? {
            for ((key, type) in relations) {
                if (key.startsWith("type:") && type.endsWith(typeSuffix)) {
                    val id = key.removePrefix("type:")
                    relations[id]?.let { path ->
                        zip.entryFor(path)?.let { return it }
                    }
                }
            }
            val dir = bookPart.substringBeforeLast('/', "")
            val path = if (dir.isEmpty()) conventional else "$dir/$conventional"
            return zip.entryFor(path)
        }

        private fun parseSharedStrings(
            zip: ZipFile,
            bookPart: String,
            relations: Map<String, String>,
        ): List<String> {
            val entry = partFor(zip, bookPart, relations, "/sharedStrings", "sharedStrings.xml")
                ?: return emptyList()
            val out = ArrayList<String>()
            var totalChars = 0L
            zip.openLimited(entry).use { stream ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(stream, null)
                var event = parser.eventType
                var inItem = false
                var inText = false
                val current = StringBuilder()
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> when (parser.local()) {
                            "si" -> { inItem = true; current.setLength(0) }
                            "t" -> if (inItem) inText = true
                        }
                        XmlPullParser.TEXT -> if (inText) {
                            if (totalChars + current.length + parser.text.length > MAX_SHARED_STRING_CHARS) {
                                error("This workbook contains too much shared text.")
                            }
                            current.append(parser.text)
                        }
                        XmlPullParser.END_TAG -> when (parser.local()) {
                            "t" -> inText = false
                            "si" -> {
                                if (out.size >= MAX_SHARED_STRINGS) {
                                    error("This workbook has too many shared strings.")
                                }
                                totalChars += current.length
                                out.add(current.toString())
                                inItem = false
                            }
                        }
                    }
                    event = parser.next()
                }
            }
            return out
        }

        /** Resolves fonts/fills/borders/numFmts into a flat list indexed by cellXf. */
        private fun parseStyles(
            zip: ZipFile,
            bookPart: String,
            relations: Map<String, String>,
        ): List<CellStyle> {
            val entry = partFor(zip, bookPart, relations, "/styles", "styles.xml")
                ?: return emptyList()

            val numFmts = HashMap<Int, String>()
            data class FontDef(
                var bold: Boolean = false, var italic: Boolean = false,
                var underline: Boolean = false, var size: Float = 11f, var color: Int = 0xFF202124.toInt(),
            )
            val fonts = ArrayList<FontDef>()
            val fills = ArrayList<Int?>()
            val borders = ArrayList<CellBorders>()
            val result = ArrayList<CellStyle>()

            zip.openLimited(entry).use { stream ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(stream, null)

                var section = ""
                var font: FontDef? = null
                var fillColor: Int? = null
                var inPatternFill = false
                var edges = HashMap<String, BorderEdge>()
                var currentEdge = ""
                var inCellXfs = false

                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> when (parser.local()) {
                            "numFmt" -> {
                                val id = parser.attr("numFmtId")?.toIntOrNull()
                                val code = parser.attr("formatCode")
                                if (id != null && code != null) {
                                    if (numFmts.size >= MAX_STYLE_RECORDS && id !in numFmts) {
                                        error("This workbook has too many styles.")
                                    }
                                    numFmts[id] = code
                                }
                            }
                            "fonts" -> section = "fonts"
                            "fills" -> section = "fills"
                            "borders" -> section = "borders"
                            "cellXfs" -> { section = "cellXfs"; inCellXfs = true }

                            "font" -> if (section == "fonts") font = FontDef()
                            "b" -> if (section == "fonts") font?.bold = true
                            "i" -> if (section == "fonts") font?.italic = true
                            "u" -> if (section == "fonts") font?.underline = true
                            "sz" -> if (section == "fonts") {
                                parser.attr("val")?.toFloatOrNull()?.let { font?.size = it }
                            }

                            "fill" -> if (section == "fills") fillColor = null
                            "patternFill" -> if (section == "fills") {
                                inPatternFill = parser.attr("patternType") == "solid"
                            }

                            "color" -> {
                                val argb = parseArgb(parser.attr("rgb"))
                                when {
                                    section == "fonts" && argb != null -> font?.color = argb
                                    section == "fills" && inPatternFill && argb != null -> fillColor = argb
                                    section == "borders" && argb != null && currentEdge.isNotEmpty() ->
                                        edges[currentEdge] = BorderEdge(true, argb)
                                }
                            }
                            "fgColor" -> if (section == "fills" && inPatternFill) {
                                parseArgb(parser.attr("rgb"))?.let { fillColor = it }
                            }

                            "border" -> if (section == "borders") edges = HashMap()
                            "left", "right", "top", "bottom" -> if (section == "borders") {
                                currentEdge = parser.local()
                                val style = parser.attr("style")
                                if (style != null && style != "none") {
                                    edges[currentEdge] = BorderEdge(true)
                                }
                            }

                            "xf" -> if (inCellXfs) {
                                if (result.size >= MAX_STYLE_RECORDS) {
                                    error("This workbook has too many styles.")
                                }
                                val numFmtId = parser.attr("numFmtId")?.toIntOrNull() ?: 0
                                val fontId = parser.attr("fontId")?.toIntOrNull() ?: 0
                                val fillId = parser.attr("fillId")?.toIntOrNull() ?: 0
                                val borderId = parser.attr("borderId")?.toIntOrNull() ?: 0
                                val f = fonts.getOrNull(fontId)
                                val code = numFmts[numFmtId]
                                    ?: CellFormatting.builtinFormat(numFmtId)
                                    ?: "General"
                                result.add(
                                    CellStyle(
                                        bold = f?.bold ?: false,
                                        italic = f?.italic ?: false,
                                        underline = f?.underline ?: false,
                                        fontSizePt = f?.size ?: 11f,
                                        fontColor = f?.color ?: 0xFF202124.toInt(),
                                        fillColor = fills.getOrNull(fillId),
                                        borders = borders.getOrNull(borderId) ?: CellBorders(),
                                        numberFormat = code,
                                    ),
                                )
                            }

                            "alignment" -> if (inCellXfs && result.isNotEmpty()) {
                                val h = when (parser.attr("horizontal")) {
                                    "center", "centerContinuous" -> HAlign.CENTER
                                    "right" -> HAlign.RIGHT
                                    "left" -> HAlign.LEFT
                                    else -> HAlign.GENERAL
                                }
                                val v = when (parser.attr("vertical")) {
                                    "top" -> VAlign.TOP
                                    "center" -> VAlign.CENTER
                                    else -> VAlign.BOTTOM
                                }
                                val wrap = parser.attr("wrapText") == "1"
                                val last = result.removeAt(result.size - 1)
                                result.add(last.copy(hAlign = h, vAlign = v, wrapText = wrap))
                            }
                        }

                        XmlPullParser.END_TAG -> when (parser.local()) {
                            "font" -> if (section == "fonts") {
                                if (fonts.size >= MAX_STYLE_RECORDS) error("This workbook has too many styles.")
                                font?.let { fonts.add(it) }
                                font = null
                            }
                            "fill" -> if (section == "fills") {
                                if (fills.size >= MAX_STYLE_RECORDS) error("This workbook has too many styles.")
                                fills.add(fillColor)
                                inPatternFill = false
                            }
                            "patternFill" -> inPatternFill = false
                            "border" -> if (section == "borders") {
                                if (borders.size >= MAX_STYLE_RECORDS) error("This workbook has too many styles.")
                                borders.add(
                                    CellBorders(
                                        left = edges["left"] ?: BorderEdge(),
                                        right = edges["right"] ?: BorderEdge(),
                                        top = edges["top"] ?: BorderEdge(),
                                        bottom = edges["bottom"] ?: BorderEdge(),
                                    ),
                                )
                            }
                            "left", "right", "top", "bottom" -> if (section == "borders") currentEdge = ""
                            "cellXfs" -> inCellXfs = false
                            "fonts", "fills", "borders" -> section = ""
                        }
                    }
                    event = parser.next()
                }
            }
            return result
        }

        /** "FFRRGGBB" or "RRGGBB" to an Android colour int; null when absent/auto. */
        private fun parseArgb(rgb: String?): Int? {
            if (rgb.isNullOrBlank()) return null
            val hex = rgb.trim()
            return try {
                when (hex.length) {
                    8 -> hex.toLong(16).toInt()
                    6 -> (0xFF000000L or hex.toLong(16)).toInt()
                    else -> null
                }
            } catch (t: Throwable) {
                null
            }
        }
    }
}
