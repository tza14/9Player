package moe.tekuza.m9player.legado.reader.entities

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.Cap
import android.graphics.Paint.Style
import android.graphics.RectF
import moe.tekuza.m9player.EbookRubyKind
import moe.tekuza.m9player.EbookRubySpan
import moe.tekuza.m9player.VerticalTextGlyphEngine
import moe.tekuza.m9player.isSidewaysAsciiText
import moe.tekuza.m9player.legado.reader.page.ContentTextView
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import kotlin.math.max
import kotlin.math.min

internal data class TextColumn(
    override var start: Float,
    override var end: Float,
    val charData: String,
    override var sourceStart: Int,
    override var sourceEnd: Int,
    val rubyText: String? = null,
    var rubySourceStart: Int = sourceStart,
    var rubySourceEnd: Int = sourceEnd,
    val rubySpan: EbookRubySpan? = null
) : BaseColumn {
    var selected: Boolean = false
    var isSearchResult: Boolean = false

    override fun draw(view: ContentTextView, canvas: Canvas, line: TextLine, selected: Boolean) {
        // 卷/标题行用标题画笔（大一号 + 加粗）绘制
        val paint = if (line.isTitle) view.titlePaint else view.contentPaint
        paint.color = when {
            selected || this.selected || isSearchResult -> view.highlightTextColor
            line.isReadAloud -> view.highlightTextColor
            else -> view.textColor
        }
        when (line.layoutMode) {
            M9LayoutMode.HORIZONTAL -> {
                // 破折号用自定义细线统一绘制（不依赖字体字形：不同 CJK 字体的
                // —/―/─/━/⸺ 渲染差异大，legado 直接画字形会过粗或位置不稳）。
                // 线宽按字号比例取细线，BUTT 帽精确落在列边界内，不碰相邻文字。
                if (isHorizontalDash(charData)) {
                    if (isFirstHorizontalDashInRun(line)) {
                        drawHorizontalDashRun(canvas, line, paint)
                    }
                } else {
                    canvas.drawText(charData, start, line.lineBase, paint)
                }
                drawHorizontalRuby(view, canvas, line)
            }
            M9LayoutMode.VERTICAL -> {
                val glyphRight = (line.lineBottom - line.rubyReservePx).coerceAtLeast(line.lineTop)
                val rect = RectF(line.lineTop, start, glyphRight, end)
                if (isVerticalDash(charData)) {
                    if (isFirstVerticalDashInRun(line)) {
                        drawVerticalDashRun(canvas, line, glyphRight, paint)
                    }
                } else if (VerticalTextGlyphEngine.isTateChuYokoToken(charData)) {
                    VerticalTextGlyphEngine.drawTateChuYoko(
                        canvas = canvas,
                        sourcePaint = paint,
                        text = charData,
                        rect = rect
                    )
                } else if (isSidewaysAsciiText(charData)) {
                    VerticalTextGlyphEngine.drawLatinRun(
                        canvas = canvas,
                        sourcePaint = paint,
                        text = charData,
                        rect = rect
                    )
                } else {
                    VerticalTextGlyphEngine.draw(
                        canvas = canvas,
                        sourcePaint = paint,
                        text = charData,
                        rect = rect
                    )
                }
                drawVerticalRuby(view, canvas, line)
            }
        }
    }

    private fun isFirstHorizontalDashInRun(line: TextLine): Boolean {
        val previous = line.columns
            .filterIsInstance<TextColumn>()
            .lastOrNull { it !== this && it.end <= start }
            ?: return true
        if (!isHorizontalDash(previous.charData)) return true
        if (previous.sourceEnd != sourceStart) return true
        val gap = (start - previous.end).coerceAtLeast(0f)
        return gap > max(1f, (end - start) * 0.25f)
    }

    private fun isFirstVerticalDashInRun(line: TextLine): Boolean {
        val previous = line.columns
            .filterIsInstance<TextColumn>()
            .lastOrNull { it !== this && it.end <= start }
            ?: return true
        if (!isVerticalDash(previous.charData)) return true
        if (previous.sourceEnd != sourceStart) return true
        val gap = (start - previous.end).coerceAtLeast(0f)
        return gap > max(1f, (end - start) * 0.25f)
    }

    private fun horizontalDashRunColumns(line: TextLine): List<TextColumn> {
        val columns = line.columns.filterIsInstance<TextColumn>()
        val startIndex = columns.indexOfFirst { it === this }
        if (startIndex < 0) return listOf(this)
        val run = arrayListOf<TextColumn>()
        var previous: TextColumn? = null
        for (index in startIndex until columns.size) {
            val column = columns[index]
            if (!isHorizontalDash(column.charData)) break
            val previousColumn = previous
            if (previousColumn != null) {
                val sourceContinuous = previousColumn.sourceEnd == column.sourceStart
                val visualGap = (column.start - previousColumn.end).coerceAtLeast(0f)
                val visualContinuous = visualGap <= max(1f, (column.end - column.start) * 0.25f)
                if (!sourceContinuous || !visualContinuous) break
            }
            run += column
            previous = column
        }
        return run.takeIf { it.isNotEmpty() } ?: listOf(this)
    }

    private fun verticalDashRunColumns(line: TextLine): List<TextColumn> {
        val columns = line.columns.filterIsInstance<TextColumn>()
        val startIndex = columns.indexOfFirst { it === this }
        if (startIndex < 0) return listOf(this)
        val run = arrayListOf<TextColumn>()
        var previous: TextColumn? = null
        for (index in startIndex until columns.size) {
            val column = columns[index]
            if (!isVerticalDash(column.charData)) break
            val previousColumn = previous
            if (previousColumn != null) {
                val sourceContinuous = previousColumn.sourceEnd == column.sourceStart
                val visualGap = (column.start - previousColumn.end).coerceAtLeast(0f)
                val visualContinuous = visualGap <= max(1f, (column.end - column.start) * 0.25f)
                if (!sourceContinuous || !visualContinuous) break
            }
            run += column
            previous = column
        }
        return run.takeIf { it.isNotEmpty() } ?: listOf(this)
    }

    /**
     * 破折号线宽：按字号比例取细线，并随当前字重微调（加粗 5.5% /
     * 常规 4.5% / 细体 3.8%），接近所用字体自身破折号的笔画粗细。
     */
    private fun dashStrokeWidth(paint: Paint): Float {
        val weight = paint.typeface?.weight ?: 400
        val ratio = when {
            weight >= 700 -> 0.055f
            weight <= 300 -> 0.038f
            else -> 0.045f
        }
        return (paint.textSize * ratio).coerceAtLeast(1f)
    }

    private fun drawHorizontalDashRun(
        canvas: Canvas,
        line: TextLine,
        paint: Paint
    ) {
        val run = horizontalDashRunColumns(line)
        val left = run.minOf { it.start }
        val right = run.maxOf { it.end }
        val metrics = paint.fontMetrics
        val y = line.lineBase + (metrics.ascent + metrics.descent) * 0.5f
        // 线宽按字号比例取细线并随字重微调，接近字体自身破折号笔画粗细；
        // 不随行高变化（避免被 ruby 预留撑粗）；BUTT 帽 + 精确列边界，
        // 不伸出边界碰到相邻文字。
        val strokeWidth = dashStrokeWidth(paint)
        val oldStyle = paint.style
        val oldStrokeWidth = paint.strokeWidth
        val oldStrokeCap = paint.strokeCap
        paint.style = Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.strokeCap = Cap.BUTT
        canvas.drawLine(left, y, right, y, paint)
        paint.style = oldStyle
        paint.strokeWidth = oldStrokeWidth
        paint.strokeCap = oldStrokeCap
    }

    private fun drawVerticalDashRun(
        canvas: Canvas,
        line: TextLine,
        glyphRight: Float,
        paint: Paint
    ) {
        val run = verticalDashRunColumns(line)
        val top = run.minOf { it.start }
        val bottom = run.maxOf { it.end }
        val x = (line.lineTop + glyphRight) * 0.5f
        // 线宽按字号比例取细线并随字重微调，接近字体自身破折号笔画粗细；
        // 不随行高/栏宽变化（避免被 ruby 预留撑粗）；BUTT 帽 + 精确边界，
        // 不伸出列边界碰到相邻文字。
        val strokeWidth = dashStrokeWidth(paint)
        val oldStyle = paint.style
        val oldStrokeWidth = paint.strokeWidth
        val oldStrokeCap = paint.strokeCap
        paint.style = Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.strokeCap = Cap.BUTT
        canvas.drawLine(x, top, x, bottom, paint)
        paint.style = oldStyle
        paint.strokeWidth = oldStrokeWidth
        paint.strokeCap = oldStrokeCap
    }

    private fun drawHorizontalRuby(view: ContentTextView, canvas: Canvas, line: TextLine) {
        val annotation = rubyText?.takeIf { it.isNotBlank() } ?: return
        if (sourceStart != rubySourceStart) return
        val columns = rubyGroupColumns(line).takeIf { it.isNotEmpty() } ?: return
        val left = columns.minOf { it.start }
        val right = columns.maxOf { it.end }
        val paint = view.contentPaint
        val oldSize = paint.textSize
        val oldAlign = paint.textAlign
        val oldBold = paint.isFakeBoldText
        paint.textSize = RubyLayoutEngine.rubyTextSize(oldSize)
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = false
        RubyLayoutEngine.fitHorizontalRubyText(
            paint = paint,
            annotation = annotation,
            baseWidth = (right - left).coerceAtLeast(1f),
            beforeOverhang = rubyOverhangBefore(line, paint.textSize),
            afterOverhang = rubyOverhangAfter(line, paint.textSize),
            originalSize = oldSize
        )
        val baseline = line.lineTop + line.rubyReservePx.coerceAtLeast(paint.textSize) -
            (paint.ascent() + paint.descent()) * 0.5f - paint.textSize * 0.18f
        if (RubyLayoutEngine.shouldDistributeHorizontal(annotation, (right - left).coerceAtLeast(1f), paint)) {
            drawDistributedHorizontalRuby(canvas, paint, annotation, left, right, baseline)
        } else {
            canvas.drawText(annotation, (left + right) / 2f, baseline, paint)
        }
        paint.textSize = oldSize
        paint.textAlign = oldAlign
        paint.isFakeBoldText = oldBold
    }

    private fun drawVerticalRuby(view: ContentTextView, canvas: Canvas, line: TextLine) {
        val annotation = rubyText?.takeIf { it.isNotBlank() } ?: return
        if (sourceStart != rubySourceStart) return
        val columns = rubyGroupColumns(line).takeIf { it.isNotEmpty() } ?: return
        val top = columns.minOf { it.start }
        val bottom = columns.maxOf { it.end }
        val paint = view.contentPaint
        val oldSize = paint.textSize
        val oldAlign = paint.textAlign
        val oldBold = paint.isFakeBoldText
        paint.textSize = RubyLayoutEngine.rubyTextSize(oldSize)
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = false
        val stripWidth = line.rubyReservePx.coerceAtLeast(paint.textSize)
        val glyphRight = (line.lineBottom - line.rubyReservePx).coerceAtLeast(line.lineTop)
        val gap = (paint.textSize * RubyLayoutEngine.GAP_EM).coerceAtLeast(1f)
        val left = (glyphRight + gap).coerceAtMost(line.lineBottom - paint.textSize)
        val right = (left + stripWidth).coerceAtMost(line.lineBottom)
        val boxes = RubyLayoutEngine.verticalGlyphBoxes(
            annotation = annotation,
            baseColumns = columns,
            top = top,
            bottom = bottom,
            rubySize = paint.textSize,
            beforeOverhang = rubyOverhangBefore(line, paint.textSize),
            afterOverhang = rubyOverhangAfter(line, paint.textSize),
            rubyKind = rubySpan?.kind ?: EbookRubyKind.UNKNOWN,
            segmented = rubySpan?.segments?.isNotEmpty() == true
        )
        boxes.forEach { box ->
            VerticalTextGlyphEngine.draw(
                canvas = canvas,
                sourcePaint = paint,
                text = box.text,
                rect = RectF(left, box.start, right, box.end)
            )
        }
        paint.textSize = oldSize
        paint.textAlign = oldAlign
        paint.isFakeBoldText = oldBold
    }

    private fun rubyGroupColumns(line: TextLine): List<TextColumn> {
        return line.columns
            .filterIsInstance<TextColumn>()
            .filter { column ->
                column.sourceStart >= rubySourceStart && column.sourceEnd <= rubySourceEnd
            }
    }

    private fun drawDistributedHorizontalRuby(
        canvas: Canvas,
        paint: Paint,
        annotation: String,
        left: Float,
        right: Float,
        baseline: Float
    ) {
        val chars = RubyLayoutEngine.codePointStrings(annotation)
        if (chars.isEmpty()) return
        val width = (right - left).coerceAtLeast(1f)
        chars.forEachIndexed { index, char ->
            val x = left + width * (index + 0.5f) / chars.size
            canvas.drawText(char, x, baseline, paint)
        }
    }

    private fun rubyOverhangBefore(line: TextLine, rubySize: Float): Float {
        val previous = line.columns
            .filterIsInstance<TextColumn>()
            .lastOrNull { it !== this && it.end <= start }
            ?: return rubySize * edgeRubyOverhangRatio()
        return RubyLayoutEngine.rubyOverhang(
            adjacent = previous.charData,
            rubySize = rubySize,
            segmented = isSegmentedRuby(),
            insideBaseGroup = isInsideRubyBase(previous),
            adjacentHasRuby = previous.hasRubyAnnotation()
        )
    }

    private fun rubyOverhangAfter(line: TextLine, rubySize: Float): Float {
        val next = line.columns
            .filterIsInstance<TextColumn>()
            .firstOrNull { it !== this && it.start >= end }
            ?: return rubySize * edgeRubyOverhangRatio()
        return RubyLayoutEngine.rubyOverhang(
            adjacent = next.charData,
            rubySize = rubySize,
            segmented = isSegmentedRuby(),
            insideBaseGroup = isInsideRubyBase(next),
            adjacentHasRuby = next.hasRubyAnnotation()
        )
    }

    private fun isSegmentedRuby(): Boolean = rubySpan?.segments?.isNotEmpty() == true

    /** 欄（行）の端で、掛かり先の文字が存在しないときの掛かり量。 */
    private fun edgeRubyOverhangRatio(): Float = if (isSegmentedRuby()) {
        RubyLayoutEngine.SEGMENT_OVERHANG_EM
    } else {
        RubyLayoutEngine.EDGE_OVERHANG_EM
    }

    /** 隣の字がこのルビの親文字群の内側か（＝熟語ルビの内部か）。 */
    private fun isInsideRubyBase(column: TextColumn): Boolean {
        return rubySourceEnd > rubySourceStart &&
            column.sourceStart >= rubySourceStart &&
            column.sourceEnd <= rubySourceEnd
    }

    /** 隣の字自身にもルビが付いているか。 */
    private fun TextColumn.hasRubyAnnotation(): Boolean = !rubyText.isNullOrBlank()

    private companion object {
        private fun isHorizontalDash(value: String): Boolean {
            return value.length == 1 && value[0] in DASH_CHARS
        }

        private fun isVerticalDash(value: String): Boolean {
            return value.length == 1 && value[0] in DASH_CHARS
        }

        private val DASH_CHARS = setOf(
            '\u2014',
            '\u2015',
            '\u2212',
            '\u2500',
            '\u2501',
            '\u2E3A',
            '\u2E3B'
        )
    }
}
