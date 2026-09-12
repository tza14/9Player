package moe.tekuza.m9player

import android.graphics.Canvas
import android.graphics.RectF
import android.text.TextPaint
import kotlin.math.ceil
import kotlin.math.floor

internal data class VerticalSubtitleTapResult(
    val sourceOffset: Int,
    val logical: Int,
    val row: Int,
    val column: Int,
    val rect: RectF
)

internal data class VerticalSubtitleCell(
    val sourceOffset: Int,
    val sourceEndExclusive: Int,
    val logical: Int,
    val row: Int,
    val column: Int,
    val char: String,
    val rowSpan: Int = 1
)

internal data class VerticalSubtitleLayout(
    val cells: List<VerticalSubtitleCell>,
    val columnCount: Int,
    val maxRows: Int,
    val cellWidth: Float,
    val cellHeight: Float
) {
    fun contentWidth(): Float = columnCount * cellWidth
    fun contentHeight(): Float = maxRows * cellHeight
}

internal object VerticalSubtitleLayoutEngine {
    fun build(
        text: String,
        paint: TextPaint,
        viewHeight: Int,
        lineHeightPx: Float = paint.textSize,
        singleColumn: Boolean = false,
        cellWidthPx: Float = VerticalTextGlyphEngine.estimateCellWidth(paint)
    ): VerticalSubtitleLayout? {
        if (text.isBlank() || viewHeight <= 0) return null

        val cellHeight = lineHeightPx.coerceAtLeast(paint.textSize).coerceAtLeast(1f)
        val cellWidth = cellWidthPx.coerceAtLeast(1f)
        val rows = if (singleColumn) {
            text.count { it != '\r' && it != '\n' }.coerceAtLeast(1)
        } else {
            floor(viewHeight / cellHeight).toInt().coerceAtLeast(1)
        }
        val cells = ArrayList<VerticalSubtitleCell>(text.length)
        var row = 0
        var column = 0
        var logical = 0
        var maxColumn = 0
        var maxRow = 0

        fun moveToNextColumn() {
            column += 1
            row = 0
        }

        fun moveToNextColumnForLineBreak(nextChar: Char?) {
            val previous = cells.lastOrNull()
            val startsWithForbidden = nextChar?.let(VerticalTextGlyphEngine::isNoColumnStart) == true
            if (startsWithForbidden && previous != null && previous.column == column) {
                cells.removeAt(cells.lastIndex)
                moveToNextColumn()
                val carried = previous.copy(row = 0, column = column)
                cells += carried
                row = carried.row + carried.rowSpan
                maxColumn = maxOf(maxColumn, column)
                maxRow = maxOf(maxRow, carried.row + carried.rowSpan - 1)
            } else {
                moveToNextColumn()
            }
        }

        fun rowSpanFor(tokenText: String): Int {
            if (!isSidewaysAsciiText(tokenText) ||
                VerticalTextGlyphEngine.isTateChuYokoToken(tokenText)
            ) {
                return 1
            }
            return ceil(
                (paint.measureText(tokenText.trim()) + paint.textSize * 0.22f) / cellHeight
            ).toInt().coerceAtLeast(1)
        }

        fun addToken(token: VerticalTextToken) {
            val rowSpan = rowSpanFor(token.text)
            val startsWithForbidden = token.text.firstOrNull()
                ?.let(VerticalTextGlyphEngine::isNoColumnStart) == true
            val previous = cells.lastOrNull()
            val overflowing = row > 0 && row + rowSpan > rows
            val previousNeedsCarry = previous != null && (
                VerticalTextGlyphEngine.isNoColumnEnd(previous.char.lastOrNull() ?: '\u0000') ||
                    startsWithForbidden
                )

            if ((overflowing || (row == 0 && startsWithForbidden && previous != null)) && previousNeedsCarry) {
                cells.removeAt(cells.lastIndex)
                moveToNextColumn()
                val carried = previous.copy(row = 0, column = column)
                cells += carried
                row = carried.row + carried.rowSpan
                maxColumn = maxOf(maxColumn, column)
                maxRow = maxOf(maxRow, carried.row + carried.rowSpan - 1)
            } else if (overflowing || (row == 0 && startsWithForbidden && previous != null)) {
                moveToNextColumn()
            }

            if (row > 0 && row + rowSpan > rows) {
                moveToNextColumn()
            }
            maxColumn = maxOf(maxColumn, column)
            maxRow = maxOf(maxRow, row + rowSpan - 1)
            cells += VerticalSubtitleCell(
                sourceOffset = token.sourceOffset,
                sourceEndExclusive = token.sourceEndExclusive,
                logical = logical++,
                row = row,
                column = column,
                char = token.text,
                rowSpan = rowSpan
            )
            row += rowSpan
        }

        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (ch == '\r') {
                index += 1
                continue
            }
            if (ch == '\n') {
                if (singleColumn) {
                    row += 1
                } else if (row > 0 || cells.isNotEmpty()) {
                    moveToNextColumnForLineBreak(text.getOrNull(index + 1))
                }
                index += 1
                continue
            }
            if (isAsciiRunSpaceChar(ch)) {
                index += 1
                continue
            }
            val token = VerticalTextGlyphEngine.nextVerticalTextToken(text, index)
            if (token.sourceEndExclusive <= index) {
                index += 1
            } else {
                addToken(token)
                index = token.sourceEndExclusive
            }
        }

        if (cells.isEmpty()) return null
        return VerticalSubtitleLayout(
            cells = cells,
            columnCount = maxColumn + 1,
            maxRows = maxRow + 1,
            cellWidth = cellWidth,
            cellHeight = cellHeight
        )
    }

    private fun layoutRightEdge(viewWidth: Int, layout: VerticalSubtitleLayout): Float {
        val contentWidth = layout.contentWidth()
        return if (contentWidth < viewWidth) {
            viewWidth - ((viewWidth - contentWidth) * 0.5f)
        } else {
            viewWidth.toFloat()
        }
    }

    private fun cellRectUnclipped(
        layout: VerticalSubtitleLayout,
        cell: VerticalSubtitleCell,
        rightEdge: Float,
        scrollY: Float
    ): RectF {
        val left = (rightEdge - (cell.column + 1) * layout.cellWidth)
        val top = (cell.row * layout.cellHeight) - scrollY.coerceAtLeast(0f)
        return RectF(
            left,
            top,
            left + layout.cellWidth,
            top + layout.cellHeight * cell.rowSpan
        )
    }

    fun hitTest(
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
        layout: VerticalSubtitleLayout,
        paint: TextPaint? = null
    ): VerticalSubtitleTapResult? {
        return hitTest(x, y, viewWidth, viewHeight, layout, paint, scrollY = 0f)
    }

    fun hitTest(
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
        layout: VerticalSubtitleLayout,
        paint: TextPaint? = null,
        scrollY: Float
    ): VerticalSubtitleTapResult? {
        val rightEdge = layoutRightEdge(viewWidth, layout)
        for (cell in layout.cells) {
            val rect = cellRectUnclipped(layout, cell, rightEdge, scrollY)
            if (rect.right <= 0f || rect.left >= viewWidth.toFloat()) continue
            if (rect.bottom <= 0f || rect.top >= viewHeight.toFloat()) continue
            if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) {
                val resolvedRect = paint?.let { inkRectForCell(it, cell.char, rect) } ?: rect
                return VerticalSubtitleTapResult(
                    sourceOffset = cell.sourceOffset,
                    logical = cell.logical,
                    row = cell.row,
                    column = cell.column,
                    rect = resolvedRect
                )
            }
        }
        return null
    }

    fun selectionRects(
        range: IntRange,
        viewWidth: Int,
        viewHeight: Int,
        layout: VerticalSubtitleLayout,
        paint: TextPaint? = null
    ): List<RectF> {
        return selectionRects(range, viewWidth, viewHeight, layout, paint, scrollY = 0f)
    }

    fun selectionRects(
        range: IntRange,
        viewWidth: Int,
        viewHeight: Int,
        layout: VerticalSubtitleLayout,
        paint: TextPaint? = null,
        scrollY: Float
    ): List<RectF> {
        val start = minOf(range.first, range.last)
        val end = maxOf(range.first, range.last)
        val selectedRectsByColumn = linkedMapOf<Int, MutableList<Pair<Int, RectF>>>()
        val rightEdge = layoutRightEdge(viewWidth, layout)
        for (cell in layout.cells) {
            if (cell.sourceEndExclusive <= start || cell.sourceOffset > end) continue
            val cellRect = cellRectUnclipped(layout, cell, rightEdge, scrollY)
            if (cellRect.right <= 0f || cellRect.left >= viewWidth.toFloat()) continue
            if (cellRect.bottom <= 0f || cellRect.top >= viewHeight.toFloat()) continue
            val rect = paint?.let { inkRectForCell(it, cell.char, cellRect) } ?: cellRect
            selectedRectsByColumn.getOrPut(cell.column) { ArrayList(4) }.add(cell.row to rect)
        }
        if (selectedRectsByColumn.isEmpty()) return emptyList()

        val rects = ArrayList<RectF>(selectedRectsByColumn.size)
        selectedRectsByColumn.forEach { (_, rowsInColumn) ->
            if (rowsInColumn.isEmpty()) return@forEach
            var previousRow = rowsInColumn.first().first
            val runRects = ArrayList<RectF>()
            runRects += rowsInColumn.first().second

            fun flushRun() {
                if (runRects.isEmpty()) return
                rects += mergeRectFs(runRects)
                runRects.clear()
            }

            for (i in 1 until rowsInColumn.size) {
                val (row, rect) = rowsInColumn[i]
                if (row == previousRow + 1) {
                    previousRow = row
                    runRects += rect
                } else {
                    flushRun()
                    previousRow = row
                    runRects += rect
                }
            }
            flushRun()
        }
        return rects
    }

    fun draw(
        canvas: Canvas,
        textPaint: TextPaint,
        layout: VerticalSubtitleLayout,
        viewWidth: Int,
        viewHeight: Int
    ) {
        draw(canvas, textPaint, layout, viewWidth, viewHeight, scrollY = 0f)
    }

    fun draw(
        canvas: Canvas,
        textPaint: TextPaint,
        layout: VerticalSubtitleLayout,
        viewWidth: Int,
        viewHeight: Int,
        scrollY: Float
    ) {
        val rightEdge = layoutRightEdge(viewWidth, layout)
        for (cell in layout.cells) {
            val rect = cellRectUnclipped(layout, cell, rightEdge, scrollY)
            if (rect.right <= 0f || rect.left >= viewWidth.toFloat()) continue
            if (rect.bottom <= 0f || rect.top >= viewHeight.toFloat()) continue
            when {
                VerticalTextGlyphEngine.isTateChuYokoToken(cell.char) -> {
                    VerticalTextGlyphEngine.drawTateChuYoko(canvas, textPaint, cell.char, rect)
                }
                isSidewaysAsciiText(cell.char) -> {
                    VerticalTextGlyphEngine.drawLatinRun(canvas, textPaint, cell.char, rect)
                }
                else -> VerticalTextGlyphEngine.draw(canvas, textPaint, cell.char, rect)
            }
        }
    }

    private fun inkRectForCell(
        paint: TextPaint,
        text: String,
        rect: RectF
    ): RectF {
        return VerticalTextGlyphEngine.inkRect(paint, text, rect)
    }

    private fun mergeRectFs(rects: List<RectF>): RectF {
        val first = rects.first()
        var left = first.left
        var top = first.top
        var right = first.right
        var bottom = first.bottom
        for (i in 1 until rects.size) {
            val rect = rects[i]
            left = minOf(left, rect.left)
            top = minOf(top, rect.top)
            right = maxOf(right, rect.right)
            bottom = maxOf(bottom, rect.bottom)
        }
        return RectF(left, top, right, bottom)
    }
}
