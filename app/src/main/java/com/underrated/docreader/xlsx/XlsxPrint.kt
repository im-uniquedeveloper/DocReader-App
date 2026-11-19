package com.underrated.docreader.xlsx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/**
 * Print layout for spreadsheets.
 *
 * A spreadsheet has no inherent pagination, so this builds one:
 *
 *  1. Lay the sheet out at 96 dpi (the same [SheetLayout] the viewer uses).
 *  2. Try to fit the full sheet width onto A4 portrait. If the shrink needed drops
 *     below [MIN_FIT], rotate to landscape and try again.
 *  3. If landscape still cannot hold it, split the columns into **blocks that break
 *     on column boundaries** — so a column is never sliced down the middle.
 *  4. Fill each block down the rows, breaking on row boundaries, repeating the
 *     sheet's frozen rows at the top of every page as a header.
 *
 * The same [SheetPainter] that draws the screen draws the page, so the export cannot
 * drift from what the viewer shows.
 */
object XlsxPrint {

    private const val PT_PER_PX = 72f / 96f
    private const val MARGIN_PT = 36f          // 0.5 inch
    private const val FOOTER_PT = 18f
    private const val MIN_FIT = 0.45f          // below this, prefer landscape / blocks

    private const val A4_SHORT_PT = 595
    private const val A4_LONG_PT = 842

    data class PagePlan(
        val sheetIndex: Int,
        val sheetName: String,
        val firstColumn: Int,
        val lastColumn: Int,
        val firstRow: Int,
        val lastRow: Int,
        val landscape: Boolean,
        val scale: Float,
        val headerRows: Int,
    )

    /** Builds the page plan for one sheet without drawing anything. */
    fun planSheet(sheet: Sheet, sheetIndex: Int): List<PagePlan> {
        val layout = SheetLayout(sheet, 1f)
        if (layout.totalWidth <= 0f || layout.rowCount == 0) return emptyList()

        // --- orientation and scale -------------------------------------------------
        var landscape = false
        var pageW = A4_SHORT_PT.toFloat()
        var pageH = A4_LONG_PT.toFloat()
        var usableWpt = pageW - 2 * MARGIN_PT
        var fit = (usableWpt / (layout.totalWidth * PT_PER_PX)).coerceAtMost(1f)

        if (fit < MIN_FIT) {
            landscape = true
            pageW = A4_LONG_PT.toFloat()
            pageH = A4_SHORT_PT.toFloat()
            usableWpt = pageW - 2 * MARGIN_PT
            fit = (usableWpt / (layout.totalWidth * PT_PER_PX)).coerceAtMost(1f)
        }
        val scale = fit.coerceAtLeast(MIN_FIT)
        val k = PT_PER_PX * scale
        val usableWpx = usableWpt / k
        val usableHpx = (pageH - 2 * MARGIN_PT - FOOTER_PT) / k

        // --- column blocks ---------------------------------------------------------
        val blocks = ArrayList<Pair<Int, Int>>()
        var start = 0
        while (start < layout.columnCount) {
            var end = start
            var width = 0f
            while (end < layout.columnCount) {
                val cw = layout.columnWidth(end)
                if (width + cw > usableWpx && end > start) break
                width += cw
                end++
            }
            blocks.add(start to (end - 1).coerceAtLeast(start))
            start = end
        }

        // --- rows ------------------------------------------------------------------
        val headerRows = sheet.frozen.rows.coerceAtMost(4)
        val headerHeight = if (headerRows > 0) layout.rowY(headerRows) else 0f

        val pages = ArrayList<PagePlan>()
        for ((firstCol, lastCol) in blocks) {
            var row = headerRows
            if (layout.rowCount <= headerRows) {
                pages.add(
                    PagePlan(sheetIndex, sheet.name, firstCol, lastCol, 0, layout.rowCount - 1, landscape, scale, 0),
                )
                continue
            }
            while (row < layout.rowCount) {
                var used = headerHeight
                val firstRow = row
                while (row < layout.rowCount) {
                    val rh = layout.rowHeight(row)
                    if (used + rh > usableHpx && row > firstRow) break
                    used += rh
                    row++
                }
                pages.add(
                    PagePlan(
                        sheetIndex, sheet.name, firstCol, lastCol,
                        firstRow, row - 1, landscape, scale, headerRows,
                    ),
                )
            }
        }
        return pages
    }

    /** Draws one planned page onto [canvas], whose size must match the plan. */
    fun drawPage(
        canvas: Canvas,
        sheet: Sheet,
        styles: (Int) -> CellStyle,
        plan: PagePlan,
        pageNumber: Int,
        pageTotal: Int,
        deviceScale: Float = 1f,
    ) {
        val layout = SheetLayout(sheet, 1f)
        val painter = SheetPainter(sheet, styles, layout)

        val pageW = (if (plan.landscape) A4_LONG_PT else A4_SHORT_PT) * deviceScale
        val pageH = (if (plan.landscape) A4_SHORT_PT else A4_LONG_PT) * deviceScale

        canvas.drawColor(Color.WHITE)

        val k = PT_PER_PX * plan.scale * deviceScale
        val marginPx = MARGIN_PT * deviceScale / k
        val usableWpx = (pageW - 2 * MARGIN_PT * deviceScale) / k
        val usableHpx = (pageH - (2 * MARGIN_PT + FOOTER_PT) * deviceScale) / k

        canvas.save()
        canvas.scale(k, k)

        val blockX = layout.columnX(plan.firstColumn)
        val blockW = layout.columnX(plan.lastColumn + 1) - blockX
        val headerH = if (plan.headerRows > 0) layout.rowY(plan.headerRows) else 0f

        if (plan.headerRows > 0) {
            painter.paintCells(
                canvas,
                originX = blockX, originY = 0f,
                left = marginPx, top = marginPx,
                width = minOf(blockW, usableWpx), height = headerH,
                scale = 1f,
            )
        }

        val bodyY = layout.rowY(plan.firstRow)
        val bodyH = layout.rowY(plan.lastRow + 1) - bodyY
        painter.paintCells(
            canvas,
            originX = blockX, originY = bodyY,
            left = marginPx, top = marginPx + headerH,
            width = minOf(blockW, usableWpx), height = minOf(bodyH, usableHpx - headerH),
            scale = 1f,
        )
        canvas.restore()

        // Footer in page units, outside the content scale.
        val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF5F6368.toInt()
            textSize = 9f * deviceScale
        }
        val label = "${plan.sheetName}  ·  columns ${columnLabel(plan.firstColumn)}–" +
            "${columnLabel(plan.lastColumn)}  ·  rows ${plan.firstRow + 1}–${plan.lastRow + 1}"
        canvas.drawText(label, MARGIN_PT * deviceScale, pageH - 14f * deviceScale, footer)
        footer.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            "Page $pageNumber of $pageTotal",
            pageW - MARGIN_PT * deviceScale,
            pageH - 14f * deviceScale,
            footer,
        )
    }

    /** Writes every sheet of the workbook to [out] as a single PDF. */
    fun writePdf(
        workbook: XlsxWorkbook,
        out: OutputStream,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        val document = PdfDocument()
        val pageCounts = workbook.sheets.indices.map { index ->
            planSheet(workbook.loadSheet(index), index).size
        }
        val total = pageCounts.sum()
        if (total == 0) {
            document.close()
            error("This workbook has no printable content.")
        }
        var pageNumber = 0
        try {
            for (index in workbook.sheets.indices) {
                val sheet = workbook.loadSheet(index)
                for (plan in planSheet(sheet, index)) {
                    pageNumber++
                    val w = if (plan.landscape) A4_LONG_PT else A4_SHORT_PT
                    val h = if (plan.landscape) A4_SHORT_PT else A4_LONG_PT
                    val page = document.startPage(PdfDocument.PageInfo.Builder(w, h, pageNumber).create())
                    drawPage(page.canvas, sheet, workbook::styleAt, plan, pageNumber, total)
                    document.finishPage(page)
                    onProgress(pageNumber, total)
                }
            }
            document.writeTo(out)
        } finally {
            document.close()
        }
        return total
    }

    /** Renders planned pages to bitmaps for PNG export. */
    fun renderPage(
        workbook: XlsxWorkbook,
        sheet: Sheet,
        plan: PagePlan,
        pageNumber: Int,
        pageTotal: Int,
        targetLongEdge: Int = 2000,
    ): Bitmap {
        val wPt = if (plan.landscape) A4_LONG_PT else A4_SHORT_PT
        val hPt = if (plan.landscape) A4_SHORT_PT else A4_LONG_PT
        val deviceScale = targetLongEdge.toFloat() / maxOf(wPt, hPt).toFloat()
        val bitmap = Bitmap.createBitmap(
            (wPt * deviceScale).toInt().coerceAtLeast(1),
            (hPt * deviceScale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        drawPage(Canvas(bitmap), sheet, workbook::styleAt, plan, pageNumber, pageTotal, deviceScale)
        return bitmap
    }
}
