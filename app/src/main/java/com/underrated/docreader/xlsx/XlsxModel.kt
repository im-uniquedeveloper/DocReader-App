package com.underrated.docreader.xlsx

/**
 * The slice of SpreadsheetML this viewer understands.
 *
 * Everything here is read from the .xlsx package directly — see [XlsxParser] for why
 * there is no third-party parser behind it.
 */

/** Horizontal alignment as declared in a cellXf, or inferred from the value type. */
enum class HAlign { GENERAL, LEFT, CENTER, RIGHT }

enum class VAlign { TOP, CENTER, BOTTOM }

/** One edge of a cell border. Only presence and colour are modelled, not dash style. */
data class BorderEdge(val present: Boolean = false, val color: Int = 0xFF9E9E9E.toInt())

data class CellBorders(
    val left: BorderEdge = BorderEdge(),
    val right: BorderEdge = BorderEdge(),
    val top: BorderEdge = BorderEdge(),
    val bottom: BorderEdge = BorderEdge(),
) {
    val any: Boolean get() = left.present || right.present || top.present || bottom.present
}

/** A resolved cellXf: font, fill, borders, alignment and number format. */
data class CellStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val fontSizePt: Float = 11f,
    val fontColor: Int = 0xFF202124.toInt(),
    val fillColor: Int? = null,
    val borders: CellBorders = CellBorders(),
    val hAlign: HAlign = HAlign.GENERAL,
    val vAlign: VAlign = VAlign.BOTTOM,
    val wrapText: Boolean = false,
    val numberFormat: String = "General",
) {
    companion object {
        val Default = CellStyle()
    }
}

enum class CellKind { EMPTY, NUMBER, TEXT, BOOLEAN, ERROR, DATE }

/**
 * A single populated cell.
 *
 * [display] is what the viewer draws: for a formula cell this is the *cached result*
 * Excel stored in the file, because nothing here evaluates formulas.
 */
data class Cell(
    val row: Int,
    val column: Int,
    val kind: CellKind,
    val display: String,
    val numeric: Double? = null,
    val styleIndex: Int = -1,
    val isFormula: Boolean = false,
)

/** An A1:D1 style merge, as zero-based inclusive bounds. */
data class MergedRegion(
    val firstRow: Int,
    val lastRow: Int,
    val firstColumn: Int,
    val lastColumn: Int,
) {
    fun contains(row: Int, column: Int) =
        row in firstRow..lastRow && column in firstColumn..lastColumn

    val isAnchor: (Int, Int) -> Boolean get() = { r, c -> r == firstRow && c == firstColumn }
}

/** Frozen rows/columns from a sheetView pane element. */
data class FrozenPane(val rows: Int = 0, val columns: Int = 0) {
    val any: Boolean get() = rows > 0 || columns > 0
}

/** One worksheet, fully parsed. Sheets are parsed on demand, never all at once. */
class Sheet(
    val name: String,
    val rowCount: Int,
    val columnCount: Int,
    /** Row-major, sparse: only populated cells are present. */
    val cells: Map<Long, Cell>,
    val merges: List<MergedRegion>,
    val columnWidths: Map<Int, Float>,
    val rowHeights: Map<Int, Float>,
    val defaultColumnWidth: Float,
    val defaultRowHeightPt: Float,
    val frozen: FrozenPane,
    /** True when parsing stopped early because the sheet exceeded the cell budget. */
    val truncated: Boolean = false,
) {
    fun cellAt(row: Int, column: Int): Cell? = cells[key(row, column)]

    companion object {
        fun key(row: Int, column: Int): Long = (row.toLong() shl 20) or column.toLong()
    }
}

/** Sheet names and where to find them, read from the workbook part. */
data class SheetRef(val name: String, val path: String)
