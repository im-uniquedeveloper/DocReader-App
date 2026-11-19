package com.underrated.docreader.xlsx

/**
 * Column/row geometry for a sheet, shared by the on-screen canvas and the PDF
 * export so printed pages use exactly the geometry the viewer shows.
 *
 * Excel stores column widths in "characters" and row heights in points. The
 * conversions below are the standard ones for the default 11pt font.
 */
class SheetLayout(
    private val sheet: Sheet,
    /** Device pixels per CSS/Excel pixel; 1f for print at 96dpi. */
    val scale: Float = 1f,
) {
    val columnCount = sheet.columnCount.coerceAtLeast(1)
    val rowCount = sheet.rowCount.coerceAtLeast(1)

    private val columnStarts = FloatArray(columnCount + 1)
    private val rowStarts = FloatArray(rowCount + 1)

    init {
        var x = 0f
        for (c in 0 until columnCount) {
            columnStarts[c] = x
            x += rawColumnWidth(c)
        }
        columnStarts[columnCount] = x

        var y = 0f
        for (r in 0 until rowCount) {
            rowStarts[r] = y
            y += rawRowHeight(r)
        }
        rowStarts[rowCount] = y
    }

    /** Excel width units -> pixels (Calibri 11: 7px per character plus padding). */
    private fun rawColumnWidth(column: Int): Float {
        val units = sheet.columnWidths[column] ?: sheet.defaultColumnWidth
        return ((units * 7f) + 5f) * scale
    }

    /** Points -> pixels at 96 dpi. */
    private fun rawRowHeight(row: Int): Float {
        val points = sheet.rowHeights[row] ?: sheet.defaultRowHeightPt
        return (points * 96f / 72f) * scale
    }

    fun columnWidth(column: Int): Float =
        if (column in 0 until columnCount) columnStarts[column + 1] - columnStarts[column]
        else rawColumnWidth(column)

    fun rowHeight(row: Int): Float =
        if (row in 0 until rowCount) rowStarts[row + 1] - rowStarts[row]
        else rawRowHeight(row)

    fun columnX(column: Int): Float = when {
        column <= 0 -> 0f
        column >= columnCount -> columnStarts[columnCount]
        else -> columnStarts[column]
    }

    fun rowY(row: Int): Float = when {
        row <= 0 -> 0f
        row >= rowCount -> rowStarts[rowCount]
        else -> rowStarts[row]
    }

    val totalWidth: Float get() = columnStarts[columnCount]
    val totalHeight: Float get() = rowStarts[rowCount]

    /** First column whose right edge is past [x]. */
    fun columnAt(x: Float): Int {
        if (x <= 0f) return 0
        var lo = 0
        var hi = columnCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (columnStarts[mid] <= x) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun rowAt(y: Float): Int {
        if (y <= 0f) return 0
        var lo = 0
        var hi = rowCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (rowStarts[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }
}
