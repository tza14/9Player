package moe.tekuza.m9player.legado.reader.page

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import moe.tekuza.m9player.EbookImageRef
import moe.tekuza.m9player.VerticalTextGlyphEngine
import moe.tekuza.m9player.isSidewaysAsciiText
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.M9TextWeight
import moe.tekuza.m9player.legado.reader.READER_TITLE_SCALE
import moe.tekuza.m9player.legado.reader.applyM9TextWeight
import moe.tekuza.m9player.legado.reader.entities.ImageColumn
import moe.tekuza.m9player.legado.reader.entities.TextColumn
import moe.tekuza.m9player.legado.reader.entities.TextLine
import moe.tekuza.m9player.legado.reader.entities.TextPage
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** 竖排英文气泡最多显示多少个字符；整段没有句读时围绕落点截断，避免气泡铺满整屏。 */
private const val MAX_ASSIST_SENTENCE_CHARS = 200

/** 句子边界：句读与换行（换行只在没有句读时兜底，防止把整章吞进一句）。 */
private fun isAssistSentenceBreak(ch: Char): Boolean =
    ch == '\n' || ch in "。！？…‥.!?"

/**
 * [start, end) 所在的整句范围（含句读符在内）：向两侧扩到句读/换行为止。
 * 整段没有句读、结果长过 [MAX_ASSIST_SENTENCE_CHARS] 时，围绕落点截断。
 */
internal fun assistSentenceRange(text: String, start: Int, end: Int): IntRange? {
    if (text.isEmpty()) return null
    val from = start.coerceIn(0, text.length - 1)
    val to = end.coerceIn(from + 1, text.length)
    var begin = from
    while (begin > 0 && !isAssistSentenceBreak(text[begin - 1])) begin -= 1
    var stop = to
    while (stop < text.length && !isAssistSentenceBreak(text[stop])) stop += 1
    // 句读符本身算在句内；换行是段界，不纳入
    if (stop < text.length && text[stop] != '\n') stop += 1
    while (begin < stop && text[begin].isWhitespace()) begin += 1
    while (stop > begin && text[stop - 1].isWhitespace()) stop -= 1
    if (stop <= begin) return null
    if (stop - begin > MAX_ASSIST_SENTENCE_CHARS) {
        // 截断成宽度 ≤ MAX 的窗口：以落点为中心，夹在 [begin, stop] 内
        val center = ((from + to) / 2).coerceIn(begin, stop)
        val truncatedBegin = (center - MAX_ASSIST_SENTENCE_CHARS / 2)
            .coerceIn(begin, (stop - MAX_ASSIST_SENTENCE_CHARS).coerceAtLeast(begin))
        begin = truncatedBegin
        stop = (truncatedBegin + MAX_ASSIST_SENTENCE_CHARS).coerceAtMost(stop)
    }
    return begin until stop
}

internal class ContentTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    internal data class AssistToken(
        val text: String,
        val rect: RectF,
        val sourceStart: Int,
        val sourceEnd: Int,
        /** 被点中的那一列（单词）的起点，用于"再点同一个词=收起气泡"的判断。 */
        val hitSourceStart: Int
    )

    internal data class TextHit(
        val sourceStart: Int,
        val sourceEnd: Int,
        val lineIndex: Int,
        val columnIndex: Int,
        val rect: RectF
    )

    internal data class SelectionBounds(
        val startRect: RectF,
        val endRect: RectF,
        val startLineTop: Float
    )

    internal enum class SelectionHandleRole {
        START,
        END
    }

    private data class AssistColumnRef(
        val lineIndex: Int,
        val columnIndex: Int,
        val column: TextColumn,
        val rect: RectF
    )

    private data class SelectionColumnAnchor(
        val rect: RectF,
        val lineTop: Float
    )

    val contentPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    /** 卷/标题行画笔：比正文大一号 + 加粗（与布局层 READER_TITLE_SCALE 一致） */
    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val searchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val selectionBackgroundInsetPx: Float get() = resources.displayMetrics.density * 2f
    private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    var textColor: Int = 0xFF2C241B.toInt()
        private set
    var highlightTextColor: Int = 0xFF2C241B.toInt()
        private set
    private var textSizePxValue: Float = spToPx(20f)
    private var readerTypeface: Typeface? = null
    private var textWeight: M9TextWeight = M9TextWeight.NORMAL
    private var page: TextPage? = null
    private var selectionRange: IntRange? = null
    private var selectionStartSource: Int? = null
    private var selectionEndSource: Int? = null
    private var pendingSelectionHandleRole: SelectionHandleRole? = null
    private var highlightRange: IntRange? = null
    private var searchRange: IntRange? = null
    private var textUnderline: Boolean = false
    private var scrollPages: List<TextPage?> = emptyList()
    private var scrollCenterIndex: Int = 0
    private var scrollOffset: Float = 0f
    private var scrollHorizontal: Boolean = false
    private var scrollReverse: Boolean = false
    val textSizePx: Float get() = textSizePxValue

    init {
        selectionPaint.color = 0x63858585
        highlightPaint.color = 0x66E53935
        searchPaint.color = 0xAAFFD54F.toInt()
        underlinePaint.style = Paint.Style.STROKE
        underlinePaint.strokeCap = Paint.Cap.SQUARE
        applyTitlePaint()
    }

    fun setTextColor(color: Int) {
        textColor = color
        invalidate()
    }

    fun setHighlightTextColor(color: Int) {
        highlightTextColor = color
        invalidate()
    }

    fun setHighlightBackgroundColor(color: Int) {
        highlightPaint.color = color
        invalidate()
    }

    fun setTextSizePx(sizePx: Float) {
        textSizePxValue = sizePx
        contentPaint.textSize = sizePx
        applyTitlePaint()
        invalidate()
    }

    fun setTextWeight(weight: M9TextWeight) {
        textWeight = weight
        applyTextTypeface()
        invalidate()
    }

    fun setTextUnderline(enabled: Boolean) {
        textUnderline = enabled
        invalidate()
    }

    fun setReaderTypeface(typeface: Typeface?) {
        readerTypeface = typeface
        applyTextTypeface()
        invalidate()
    }

    private fun applyTextTypeface() {
        contentPaint.applyM9TextWeight(textWeight, readerTypeface)
        applyTitlePaint()
    }

    /** 标题画笔：跟随正文的字重/字体，字号放大 + 强制加粗。 */
    private fun applyTitlePaint() {
        titlePaint.set(contentPaint)
        titlePaint.textSize = contentPaint.textSize * READER_TITLE_SCALE
        titlePaint.typeface = Typeface.create(readerTypeface ?: Typeface.DEFAULT, Typeface.BOLD)
    }

    fun setPage(page: TextPage?, highlight: IntRange?, search: IntRange?) {
        this.page = page
        selectionRange = null
        selectionStartSource = null
        selectionEndSource = null
        pendingSelectionHandleRole = null
        highlightRange = highlight
        searchRange = search
        clearScrollContext()
        invalidate()
    }

    fun setHighlight(highlight: IntRange?) {
        highlightRange = highlight
        invalidate()
    }

    fun setScrollContext(
        pages: List<TextPage?>,
        centerIndex: Int,
        offset: Float,
        horizontal: Boolean,
        reverse: Boolean
    ) {
        scrollPages = pages
        scrollCenterIndex = centerIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        scrollOffset = offset
        scrollHorizontal = horizontal
        scrollReverse = reverse
        invalidate()
    }

    fun clearScrollContext() {
        scrollPages = emptyList()
        scrollCenterIndex = 0
        scrollOffset = 0f
        scrollHorizontal = false
        scrollReverse = false
        invalidate()
    }

    fun clickScrollDistance(horizontal: Boolean, direction: Int, fallback: Float): Float {
        val current = page ?: return fallback
        val viewportWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        val viewportHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1).toFloat()
        if (current.hasImageColumn()) {
            return if (horizontal) viewportWidth else viewportHeight
        }
        val distance = if (horizontal) {
            val line = if (direction > 0) current.lines.lastOrNull() else current.lines.firstOrNull()
            val keep = line?.width?.takeIf { it > 0f } ?: textSizePxValue * 2f
            viewportWidth - keep
        } else {
            val line = if (direction > 0) current.lines.lastOrNull() else current.lines.firstOrNull()
            if (direction > 0) {
                line?.crossStart ?: fallback
            } else {
                line?.let { viewportHeight - it.crossEnd } ?: fallback
            }
        }
        return distance.coerceIn(
            (if (horizontal) viewportWidth else viewportHeight) * 0.45f,
            (if (horizontal) viewportWidth else viewportHeight) * 0.95f
        )
    }

    fun pageScrollExtent(page: TextPage?, horizontal: Boolean): Float {
        val viewportWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        val viewportHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1).toFloat()
        if (page == null) return if (horizontal) viewportWidth else viewportHeight
        val contentExtent = if (horizontal) {
            maxOf(
                page.width,
                page.lines.maxOfOrNull { it.lineBottom } ?: 0f
            )
        } else {
            maxOf(
                page.height,
                page.lines.maxOfOrNull { it.crossEnd } ?: 0f
            )
        }
        return contentExtent.coerceAtLeast(if (horizontal) viewportWidth else viewportHeight)
    }

    private fun TextPage.hasImageColumn(): Boolean {
        return lines.any { line -> line.columns.any { it is ImageColumn } }
    }

    fun findImageAt(x: Float, y: Float): EbookImageRef? {
        val current = page ?: return null
        val localX = x - paddingLeft
        val localY = y - paddingTop
        current.lines.forEach { line ->
            val inLineBounds = when (line.layoutMode) {
                M9LayoutMode.HORIZONTAL -> localY >= line.lineTop && localY <= line.lineBottom
                M9LayoutMode.VERTICAL -> localX >= line.lineTop && localX <= line.lineBottom
            }
            if (!inLineBounds) return@forEach
            line.columns.forEach { column ->
                if (column !is ImageColumn) return@forEach
                val hit = when (line.layoutMode) {
                    M9LayoutMode.HORIZONTAL -> localX >= column.start && localX <= column.end
                    M9LayoutMode.VERTICAL -> localY >= column.start && localY <= column.end
                }
                if (hit) {
                    return column.image
                }
            }
        }
        return null
    }

    fun findAssistTokenAt(x: Float, y: Float): AssistToken? {
        val current = page ?: return null
        val localX = x - paddingLeft
        val localY = y - paddingTop
        current.lines.forEachIndexed { lineIndex, line ->
            if (line.layoutMode != M9LayoutMode.VERTICAL) return@forEachIndexed
            val inLineBounds = localX >= line.lineTop && localX <= line.lineBottom
            if (!inLineBounds) return@forEachIndexed
            line.columns.forEachIndexed { columnIndex, column ->
                if (column !is TextColumn) return@forEachIndexed
                if (!isSidewaysAsciiText(column.charData)) return@forEachIndexed
                if (localY < column.start || localY > column.end) return@forEachIndexed
                return assistTokenAround(current, lineIndex, columnIndex)
            }
        }
        return null
    }

    /**
     * 竖排中英文按单词切列、横倒绘制；点中某一列时返回**整句**（而不是那个单词）。
     *
     * 相邻单词列的 source 之间隔着空格、并不连续，所以按"source 连续"合并只能覆盖被切断
     * 的长串，永远合不成一句。这里改为按正文句子范围取值：以句读或换行为界，把落点所在的
     * 整句取出来（跨列、跨页都能覆盖），气泡只覆盖本页可见的那几列用于定位。
     */
    private fun assistTokenAround(page: TextPage, lineIndex: Int, columnIndex: Int): AssistToken? {
        val refs = buildList {
            page.lines.forEachIndexed { pageLineIndex, line ->
                if (line.layoutMode != M9LayoutMode.VERTICAL) return@forEachIndexed
                line.columns.forEachIndexed { pageColumnIndex, column ->
                    if (column is TextColumn && isSidewaysAsciiText(column.charData)) {
                        add(
                            AssistColumnRef(
                                lineIndex = pageLineIndex,
                                columnIndex = pageColumnIndex,
                                column = column,
                                rect = columnRect(line, column)
                            )
                        )
                    }
                }
            }
        }
        val hitIndex = refs.indexOfFirst { it.lineIndex == lineIndex && it.columnIndex == columnIndex }
        if (hitIndex < 0) return null
        val hit = refs[hitIndex]
        val range = assistSentenceRange(page.text, hit.column.sourceStart, hit.column.sourceEnd)
        val sentence = range?.let { page.text.substring(it.first, it.last + 1).trim() }
        val text = sentence?.takeIf { it.isNotBlank() } ?: hit.column.charData.trim()
        if (text.isBlank()) return null
        val visible = if (range == null) {
            listOf(hit)
        } else {
            refs.filter { it.column.sourceEnd > range.first && it.column.sourceStart <= range.last }
                .ifEmpty { listOf(hit) }
        }
        val rect = RectF(visible.first().rect)
        visible.drop(1).forEach { rect.union(it.rect) }
        return AssistToken(
            text = text,
            rect = rect,
            sourceStart = range?.first ?: hit.column.sourceStart,
            sourceEnd = range?.last?.plus(1) ?: hit.column.sourceEnd,
            hitSourceStart = hit.column.sourceStart
        )
    }

    fun beginSelectionAt(x: Float, y: Float): Boolean {
        val hit = findTextHitAt(x, y) ?: return false
        val current = page ?: return false
        val wordRange = wordRangeAround(current, hit.lineIndex, hit.columnIndex)
        val range = wordRange ?: orderedRange(hit.sourceStart, hit.sourceEnd)
        selectionRange = range
        selectionStartSource = range.first
        selectionEndSource = range.last
        pendingSelectionHandleRole = null
        invalidate()
        return true
    }

    fun updateSelectionStartAt(x: Float, y: Float): Boolean {
        val hit = findTextHitAt(x, y) ?: return false
        val currentEnd = selectionEndSource ?: selectionRange?.last ?: hit.sourceEnd - 1
        if (hit.sourceStart <= currentEnd) {
            selectionStartSource = hit.sourceStart
            selectionEndSource = currentEnd
            pendingSelectionHandleRole = null
        } else {
            selectionStartSource = currentEnd
            selectionEndSource = hit.sourceEnd - 1
            pendingSelectionHandleRole = SelectionHandleRole.END
        }
        selectionRange = orderedRange(
            selectionStartSource ?: hit.sourceStart,
            (selectionEndSource ?: currentEnd) + 1
        )
        invalidate()
        return true
    }

    fun updateSelectionEndAt(x: Float, y: Float): Boolean {
        val hit = findTextHitAt(x, y) ?: return false
        val currentStart = selectionStartSource ?: selectionRange?.first ?: hit.sourceStart
        val hitEnd = hit.sourceEnd - 1
        if (hitEnd >= currentStart) {
            selectionStartSource = currentStart
            selectionEndSource = hitEnd
            pendingSelectionHandleRole = null
        } else {
            selectionStartSource = hit.sourceStart
            selectionEndSource = currentStart
            pendingSelectionHandleRole = SelectionHandleRole.START
        }
        selectionRange = orderedRange(
            selectionStartSource ?: currentStart,
            (selectionEndSource ?: hitEnd) + 1
        )
        invalidate()
        return true
    }

    fun clearSelection() {
        val hadSelection = selectionRange != null
        selectionRange = null
        selectionStartSource = null
        selectionEndSource = null
        pendingSelectionHandleRole = null
        if (hadSelection) invalidate()
    }

    fun consumePendingSelectionHandleRole(): SelectionHandleRole? {
        val role = pendingSelectionHandleRole
        pendingSelectionHandleRole = null
        return role
    }

    fun selectedText(): String {
        val range = selectionRange ?: return ""
        val current = page ?: return ""
        return current.lines.joinToString(separator = "\n") { line ->
            line.columns
                .filterIsInstance<TextColumn>()
                .filter { it.sourceStart < range.last + 1 && it.sourceEnd > range.first }
                .joinToString(separator = "") { it.charData }
        }.trim()
    }

    fun selectedSourceRange(): IntRange? = selectionRange

    fun selectionBounds(): SelectionBounds? {
        val range = selectionRange ?: return null
        val startSource = selectionStartSource ?: range.first
        val endSource = selectionEndSource ?: range.last
        val start = findColumnAnchor(startSource, preferStart = true) ?: return null
        val end = findColumnAnchor(endSource, preferStart = false) ?: start
        return SelectionBounds(
            startRect = start.rect,
            endRect = end.rect,
            startLineTop = start.lineTop
        )
    }

    fun rangeBounds(range: IntRange): RectF? {
        val current = page ?: return null
        val endExclusive = range.last + 1
        var bounds: RectF? = null
        current.lines.forEach { line ->
            line.columns.filterIsInstance<TextColumn>()
                .filter { column -> column.sourceStart < endExclusive && column.sourceEnd > range.first }
                .forEach { column ->
                    val rect = columnRect(line, column)
                    bounds = bounds?.apply { union(rect) } ?: RectF(rect)
                }
        }
        return bounds
    }

    fun findTextHitAt(x: Float, y: Float): TextHit? {
        val current = page ?: return null
        val localX = x - paddingLeft
        val localY = y - paddingTop
        current.lines.forEachIndexed { lineIndex, line ->
            val inLineBounds = when (line.layoutMode) {
                M9LayoutMode.HORIZONTAL -> localY >= line.lineTop && localY <= line.lineBottom
                M9LayoutMode.VERTICAL -> localX >= line.lineTop && localX <= line.lineBottom
            }
            if (!inLineBounds) return@forEachIndexed
            line.columns.forEachIndexed { columnIndex, column ->
                if (column !is TextColumn) return@forEachIndexed
                val hit = when (line.layoutMode) {
                    M9LayoutMode.HORIZONTAL -> localX >= column.start && localX <= column.end
                    M9LayoutMode.VERTICAL -> localY >= column.start && localY <= column.end
                }
                if (hit) {
                    return TextHit(
                        sourceStart = column.sourceStart,
                        sourceEnd = column.sourceEnd,
                        lineIndex = lineIndex,
                        columnIndex = columnIndex,
                        rect = columnRect(line, column)
                    )
                }
            }
        }
        return null
    }

    private fun wordRangeAround(page: TextPage, lineIndex: Int, columnIndex: Int): IntRange? {
        val paragraphText = StringBuilder()
        var paragraphStartLine = lineIndex
        var paragraphEndLine = lineIndex
        var hitOffset = columnIndex.coerceAtLeast(0)
        for (index in lineIndex - 1 downTo 0) {
            val line = page.getLine(index)
            if (line.isParagraphEnd) break
            paragraphText.insert(0, line.text)
            paragraphStartLine = index
            hitOffset += line.charSize
        }
        for (index in lineIndex until page.lineSize) {
            val line = page.getLine(index)
            paragraphText.append(line.text)
            paragraphEndLine = index
            if (line.isParagraphEnd) break
        }
        val localRange = wordRangeAt(paragraphText.toString(), hitOffset) ?: return null
        var local = 0
        var startSource: Int? = null
        var endSource: Int? = null
        for (index in paragraphStartLine..paragraphEndLine) {
            val line = page.getLine(index)
            line.columns.filterIsInstance<TextColumn>().forEach { column ->
                val nextLocal = local + column.charData.length
                if (startSource == null && localRange.first in local until nextLocal) {
                    startSource = column.sourceStart
                }
                if (localRange.last in local until nextLocal) {
                    endSource = column.sourceEnd
                }
                local = nextLocal
            }
        }
        val start = startSource ?: return null
        val end = endSource ?: return null
        return orderedRange(start, end)
    }

    private fun wordRangeAt(text: String, index: Int): IntRange? {
        if (text.isBlank()) return null
        val safeIndex = index.coerceIn(0, (text.length - 1).coerceAtLeast(0))
        val iterator = BreakIterator.getWordInstance(Locale.getDefault())
        iterator.setText(text)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            if (safeIndex in start until end) {
                val candidate = text.substring(start, end)
                if (candidate.isNotBlank()) {
                    return start until end
                }
            }
            start = end
            end = iterator.next()
        }
        return null
    }

    private fun findColumnAnchor(source: Int, preferStart: Boolean): SelectionColumnAnchor? {
        val current = page ?: return null
        val range = selectionRange
        current.lines.forEach { line ->
            line.columns.filterIsInstance<TextColumn>().forEach { column ->
                val hit = if (preferStart) {
                    column.sourceStart <= source && column.sourceEnd > source
                } else {
                    column.sourceStart <= source && column.sourceEnd > source ||
                        range != null && column.sourceEnd == range.last + 1
                }
                if (hit) {
                    return SelectionColumnAnchor(
                        rect = columnRect(line, column),
                        lineTop = line.lineTop + paddingTop
                    )
                }
            }
        }
        return null
    }

    private fun columnRect(line: TextLine, column: TextColumn): RectF {
        return when (line.layoutMode) {
            M9LayoutMode.HORIZONTAL -> RectF(
                column.start + paddingLeft,
                line.lineTop + paddingTop,
                column.end + paddingLeft,
                line.lineBottom + paddingTop
            )
            M9LayoutMode.VERTICAL -> RectF(
                line.lineTop + paddingLeft,
                column.start + paddingTop,
                line.lineBottom + paddingLeft,
                column.end + paddingTop
            )
        }
    }

    private fun orderedRange(startInclusive: Int, endExclusive: Int): IntRange {
        val start = minOf(startInclusive, endExclusive - 1)
        val end = maxOf(startInclusive, endExclusive - 1)
        return start..end
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        contentPaint.textSize = textSizePxValue
        val current = page ?: return
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        canvas.clipRect(
            0f,
            0f,
            (width - paddingLeft - paddingRight).coerceAtLeast(0).toFloat(),
            (height - paddingTop - paddingBottom).coerceAtLeast(0).toFloat()
        )
        if (current.lines.isEmpty()) {
            drawFallbackText(canvas, current.text)
            canvas.restore()
            return
        }
        if (scrollPages.isNotEmpty()) {
            drawScrollPages(canvas)
        } else {
            drawPageLines(canvas, current, 0f, 0f, highlightRange, searchRange)
        }
        canvas.restore()
    }

    private fun drawScrollPages(canvas: Canvas) {
        scrollPages.forEachIndexed { index, textPage ->
            if (textPage == null) return@forEachIndexed
            val offset = scrollOffsetForIndex(index)
            val offsetX = if (scrollHorizontal) offset else 0f
            val offsetY = if (scrollHorizontal) 0f else offset
            drawPageLines(
                canvas = canvas,
                current = textPage,
                offsetX = offsetX,
                offsetY = offsetY,
                highlight = if (index == scrollCenterIndex) highlightRange else null,
                search = if (index == scrollCenterIndex) searchRange else null
            )
        }
    }

    private fun scrollOffsetForIndex(index: Int): Float {
        if (index == scrollCenterIndex) return scrollOffset
        val stepSign = if (scrollReverse) -1f else 1f
        var offset = scrollOffset
        if (index > scrollCenterIndex) {
            for (pageIndex in scrollCenterIndex until index) {
                offset += stepSign * pageScrollExtent(scrollPages.getOrNull(pageIndex), scrollHorizontal)
            }
        } else {
            for (pageIndex in index until scrollCenterIndex) {
                offset -= stepSign * pageScrollExtent(scrollPages.getOrNull(pageIndex), scrollHorizontal)
            }
        }
        return offset
    }

    private fun drawPageLines(
        canvas: Canvas,
        current: TextPage,
        offsetX: Float,
        offsetY: Float,
        highlight: IntRange?,
        search: IntRange?
    ) {
        canvas.save()
        canvas.translate(offsetX, offsetY)
        current.lines.forEach { line ->
            when (line.layoutMode) {
                M9LayoutMode.HORIZONTAL -> {
                    if (line.crossEnd + offsetY < 0f || line.crossStart + offsetY > height - paddingTop - paddingBottom) return@forEach
                    line.draw(this, canvas, selectionRange, highlight, search)
                    drawHorizontalUnderline(canvas, line)
                }
                M9LayoutMode.VERTICAL -> {
                    if (line.lineBottom + offsetX < 0f || line.lineTop + offsetX > width - paddingLeft - paddingRight) return@forEach
                    line.draw(this, canvas, selectionRange, highlight, search)
                }
            }
        }
        canvas.restore()
    }

    private fun drawHorizontalUnderline(canvas: Canvas, line: TextLine) {
        if (!textUnderline || line.layoutMode != M9LayoutMode.HORIZONTAL) return
        val textColumns = line.columns.filterIsInstance<TextColumn>()
        if (textColumns.isEmpty()) return
        val stroke = max(1f, resources.displayMetrics.density)
        val y = line.lineBottom - stroke
        underlinePaint.color = textColor
        underlinePaint.strokeWidth = stroke
        canvas.drawLine(
            line.lineStart,
            y,
            line.lineEnd,
            y,
            underlinePaint
        )
    }

    private fun drawFallbackText(canvas: Canvas, text: String) {
        if (text.isBlank()) return
        contentPaint.color = textColor
        val fontMetrics = contentPaint.fontMetrics
        val lineHeight = (fontMetrics.descent - fontMetrics.ascent) * 1.4f
        var start = 0
        var y = -fontMetrics.ascent
        val maxWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        while (start < text.length && y < height - paddingBottom) {
            val count = contentPaint.breakText(text, start, text.length, true, maxWidth, null)
                .coerceAtLeast(1)
            val end = (start + count).coerceAtMost(text.length)
            canvas.drawText(text, start, end, 0f, y, contentPaint)
            y += lineHeight
            start = end
        }
    }

    private fun spToPx(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    }
}
