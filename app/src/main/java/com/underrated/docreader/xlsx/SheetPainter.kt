package com.underrated.docreader.xlsx

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface

/**
 * Draws a sheet onto an android.graphics.Canvas.
 *
 * Deliberately framework-level rather than Compose-level so the identical code paints
 * the interactive viewer, the exported PDF pages and the exported PNGs — one renderer,
 * so what you see is what you export.
 */
class SheetPainter(
    private val sheet: Sheet,
    private val styles: (Int) -> CellStyle,
    private val layout: SheetLayout,
) {
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint()
    private val gridPaint = Paint().apply {
        color = 0xFFD6D8DC.toInt()
        strokeWidth = 1f
    }
    private val borderPaint = Paint().apply { strokeWidth = 1.6f }
    private val headerFill = Paint().apply { color = 0xFFF1F3F4.toInt() }
    private val headerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5F6368.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val bounds = Rect()

    /** Covered (non-anchor) cells of a merge are skipped when painting. */
    private fun mergeAt(row: Int, column: Int): MergedRegion? =
        sheet.merges.firstOrNull { it.contains(row, column) }

    /**
     * Paints the cell grid for the window [originX]/[originY] (sheet pixels) into a
     * viewport of [width] x [height], offset on the canvas by [left]/[top].
     */
    fun paintCells(
        canvas: Canvas,
        originX: Float,
        originY: Float,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        scale: Float,
        drawGrid: Boolean = true,
    ) {
        if (width <= 0f || height <= 0f) return
        canvas.save()
        canvas.clipRect(left, top, left + width, top + height)

        val firstCol = layout.columnAt(originX)
        val lastCol = layout.columnAt(originX + width).coerceAtMost(layout.columnCount - 1)
        val firstRow = layout.rowAt(originY)
        val lastRow = layout.rowAt(originY + height).coerceAtMost(layout.rowCount - 1)

        if (drawGrid) {
            for (c in firstCol..lastCol + 1) {
                val x = left + layout.columnX(c) - originX
                canvas.drawLine(x, top, x, top + height, gridPaint)
            }
            for (r in firstRow..lastRow + 1) {
                val y = top + layout.rowY(r) - originY
                canvas.drawLine(left, y, left + width, y, gridPaint)
            }
        }

        val painted = HashSet<Long>()
        for (r in firstRow..lastRow) {
            for (c in firstCol..lastCol) {
                val merge = mergeAt(r, c)
                val anchorRow: Int
                val anchorCol: Int
                val spanRight: Int
                val spanBottom: Int
                if (merge != null) {
                    anchorRow = merge.firstRow
                    anchorCol = merge.firstColumn
                    spanRight = merge.lastColumn
                    spanBottom = merge.lastRow
                    if (!painted.add(Sheet.key(anchorRow, anchorCol))) continue
                } else {
                    anchorRow = r
                    anchorCol = c
                    spanRight = c
                    spanBottom = r
                }

                val cell = sheet.cellAt(anchorRow, anchorCol)
                val style = if (cell != null && cell.styleIndex >= 0) styles(cell.styleIndex) else null
                if (cell == null && style?.fillColor == null) continue

                val x0 = left + layout.columnX(anchorCol) - originX
                val y0 = top + layout.rowY(anchorRow) - originY
                val x1 = left + layout.columnX(spanRight + 1) - originX
                val y1 = top + layout.rowY(spanBottom + 1) - originY

                style?.fillColor?.let {
                    fillPaint.color = it
                    canvas.drawRect(x0, y0, x1, y1, fillPaint)
                }

                style?.borders?.let { b ->
                    if (b.any) {
                        if (b.left.present) {
                            borderPaint.color = b.left.color; canvas.drawLine(x0, y0, x0, y1, borderPaint)
                        }
                        if (b.right.present) {
                            borderPaint.color = b.right.color; canvas.drawLine(x1, y0, x1, y1, borderPaint)
                        }
                        if (b.top.present) {
                            borderPaint.color = b.top.color; canvas.drawLine(x0, y0, x1, y0, borderPaint)
                        }
                        if (b.bottom.present) {
                            borderPaint.color = b.bottom.color; canvas.drawLine(x0, y1, x1, y1, borderPaint)
                        }
                    }
                }

                if (cell == null || cell.display.isEmpty()) continue
                drawCellText(canvas, cell, style ?: CellStyle.Default, x0, y0, x1, y1, scale)
            }
        }
        canvas.restore()
    }

    private fun drawCellText(
        canvas: Canvas,
        cell: Cell,
        style: CellStyle,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        scale: Float,
    ) {
        textPaint.color = style.fontColor
        textPaint.textSize = style.fontSizePt * 96f / 72f * scale * 0.92f
        textPaint.typeface = when {
            style.bold && style.italic -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            style.bold -> Typeface.DEFAULT_BOLD
            style.italic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            else -> Typeface.DEFAULT
        }
        textPaint.isUnderlineText = style.underline

        // General alignment follows the value: numbers and dates right, text left —
        // the same rule Excel applies when no explicit alignment is set.
        val align = when (style.hAlign) {
            HAlign.GENERAL -> when (cell.kind) {
                CellKind.NUMBER, CellKind.DATE -> HAlign.RIGHT
                CellKind.BOOLEAN, CellKind.ERROR -> HAlign.CENTER
                else -> HAlign.LEFT
            }
            else -> style.hAlign
        }

        val padding = 4f * scale
        val text = cell.display
        textPaint.getTextBounds(text, 0, text.length, bounds)

        val baseline = when (style.vAlign) {
            VAlign.TOP -> y0 + padding - textPaint.ascent()
            VAlign.CENTER -> (y0 + y1) / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
            VAlign.BOTTOM -> y1 - padding - textPaint.descent()
        }

        canvas.save()
        canvas.clipRect(x0, y0, x1, y1)
        textPaint.textAlign = when (align) {
            HAlign.RIGHT -> Paint.Align.RIGHT
            HAlign.CENTER -> Paint.Align.CENTER
            else -> Paint.Align.LEFT
        }
        val x = when (align) {
            HAlign.RIGHT -> x1 - padding
            HAlign.CENTER -> (x0 + x1) / 2f
            else -> x0 + padding
        }
        canvas.drawText(text, x, baseline, textPaint)
        canvas.restore()
    }

    /** Column letter strip across the top. */
    fun paintColumnHeaders(
        canvas: Canvas,
        originX: Float,
        left: Float,
        top: Float,
        width: Float,
        headerHeight: Float,
        scale: Float,
    ) {
        canvas.save()
        canvas.clipRect(left, top, left + width, top + headerHeight)
        canvas.drawRect(left, top, left + width, top + headerHeight, headerFill)
        headerText.textSize = 11f * scale
        val firstCol = layout.columnAt(originX)
        val lastCol = layout.columnAt(originX + width).coerceAtMost(layout.columnCount - 1)
        for (c in firstCol..lastCol) {
            val x0 = left + layout.columnX(c) - originX
            val x1 = left + layout.columnX(c + 1) - originX
            canvas.drawLine(x1, top, x1, top + headerHeight, gridPaint)
            canvas.drawText(
                columnLabel(c),
                (x0 + x1) / 2f,
                top + headerHeight / 2f - (headerText.ascent() + headerText.descent()) / 2f,
                headerText,
            )
        }
        canvas.drawLine(left, top + headerHeight, left + width, top + headerHeight, gridPaint)
        canvas.restore()
    }

    /** Row number strip down the left. */
    fun paintRowHeaders(
        canvas: Canvas,
        originY: Float,
        left: Float,
        top: Float,
        headerWidth: Float,
        height: Float,
        scale: Float,
    ) {
        canvas.save()
        canvas.clipRect(left, top, left + headerWidth, top + height)
        canvas.drawRect(left, top, left + headerWidth, top + height, headerFill)
        headerText.textSize = 11f * scale
        val firstRow = layout.rowAt(originY)
        val lastRow = layout.rowAt(originY + height).coerceAtMost(layout.rowCount - 1)
        for (r in firstRow..lastRow) {
            val y0 = top + layout.rowY(r) - originY
            val y1 = top + layout.rowY(r + 1) - originY
            canvas.drawLine(left, y1, left + headerWidth, y1, gridPaint)
            canvas.drawText(
                (r + 1).toString(),
                left + headerWidth / 2f,
                (y0 + y1) / 2f - (headerText.ascent() + headerText.descent()) / 2f,
                headerText,
            )
        }
        canvas.drawLine(left + headerWidth, top, left + headerWidth, top + height, gridPaint)
        canvas.restore()
    }

    fun paintCorner(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        canvas.drawRect(left, top, left + w, top + h, headerFill)
        canvas.drawLine(left + w, top, left + w, top + h, gridPaint)
        canvas.drawLine(left, top + h, left + w, top + h, gridPaint)
    }

    fun background(canvas: Canvas, width: Float, height: Float) {
        fillPaint.color = Color.WHITE
        canvas.drawRect(0f, 0f, width, height, fillPaint)
    }
}
