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
import moe.tekuza.m9player.logDebug
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
 *
 * **只对西文句扩**：夹在日语等 CJK 正文里的英文词（竖排里很常见，例如
 * 「…自分はHSPだとかADHDだとか…」）若也按整句给，气泡里会塞进一整段日语
 * （实测 150 字、铺满屏幕）。这种情况只返回被点中的那一段。
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
    // 含 CJK 的句子只给落点那一段，不整句
    if ((begin until stop).any { isCjkChar(text[it]) }) return from until to
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

/** 判定"整句是不是西文"用：假名/汉字/CJK 标点/全角形/谚文都算 CJK。 */
private fun isCjkChar(ch: Char): Boolean = when (Character.UnicodeBlock.of(ch)) {
    Character.UnicodeBlock.HIRAGANA,
    Character.UnicodeBlock.KATAKANA,
    Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS,
    Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
    Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
    Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
    Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
    Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
    Character.UnicodeBlock.HANGUL_SYLLABLES -> true
    else -> false
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
        val rect: RectF,
        /**
         * 命中页在章节原文里的起始下标（= page.charStart）。加上它就能把"页内下标"换算成
         * **章节坐标**，跨页选区的端点才可比、才能取到整句。
         */
        val chapterStartOffset: Int = 0
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

    /**
     * 同一段选区在**章节坐标**下的表示（跨页选择的唯一真相）。
     * 页内区间只能表达一页；章节区间才能让相邻页各自画出自己那一段、并把整句交给取词/Anki。
     * 切换期间两份状态同时维护：章节区间是权威，页内区间由 [syncPageLocalSelection] 派生。
     */
    private var selectionChapterRange: IntRange? = null
    private var selectionStartChapter: Int? = null
    private var selectionEndChapter: Int? = null
    private var selectionChapterIndex: Int = -1
    private var pendingSelectionHandleRole: SelectionHandleRole? = null
    private var highlightRange: IntRange? = null
    private var searchRange: IntRange? = null
    private var textUnderline: Boolean = false
    private var scrollPages: List<TextPage?> = emptyList()
    private var scrollCenterIndex: Int = 0
    private var scrollOffset: Float = 0f

    /** `pageScrollExtent` 诊断日志的时间节流（毫秒）。 */
    private var lastExtentLogAtMs: Long = 0L
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

    /**
     * 把"当前页"复位到滚动上下文的中心页。
     * 命中邻页时会临时把当前页切过去（这样手柄/菜单用对几何），手势结束后要复位，
     * 否则下一次没有命中的点击会拿上一页的偏移去算几何。
     */
    fun syncCurrentPageToScrollCenter() {
        scrollPages.getOrNull(scrollCenterIndex)?.let { page = it }
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
        // 让"当前页"跟着上下文中心走：否则滚动之后命中/选区几何还指着上一页（两个来源）。
        pages.getOrNull(scrollCenterIndex)?.let { page = it }
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
        // 图片页/空页：一整屏（照 legado 的**点击**语义 hasImageOrEmpty → visibleHeight。
        // 注意 legado 的"滚动"语义对图片页用的是图片内容高，两者本来就分开）。
        if (current.hasImageColumn() || current.lines.isEmpty()) {
            return if (horizontal) viewportWidth else viewportHeight
        }
        // 其余页面：点击距离 = 本页沿滚动轴的真实跨度（与 pageScrollExtent 同一个函数）⇒
        // "点一次正好落到下一页页首"，页码/进度与所见同步、静止余量回到 0。
        // 不照搬 legado 的"保留一行"：legado 的页之间有重叠内容，我们的页是离散位图，
        // 减去一行等于**跳过一行**。
        return pageScrollExtent(current, horizontal)
    }

    fun pageScrollExtent(page: TextPage?, horizontal: Boolean): Float {
        // 跨度只算一次就缓存在页上：绘制一帧要对上下文里每页反复取它（scrollOffsetForIndex 还要累加），
        // 而布局早于绘制完成、页对象在重新分页时整体重建 ⇒ 缓存不需要失效逻辑。
        if (page != null && page.scrollSpanCache > 0f) return page.scrollSpanCache
        return computePageScrollExtent(page, horizontal)
    }

    private fun computePageScrollExtent(page: TextPage?, horizontal: Boolean): Float {
        val viewportWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        val viewportHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1).toFloat()
        if (page == null) return if (horizontal) viewportWidth else viewportHeight
        // 图片页/空页按**整屏一步**（照 legado ScrollPageDelegate.calcNextPageOffset：
        // book.isImage 以及 !isTextStyle && visiblePage.hasImageOrEmpty() 都直接返回 -visibleHeight）。
        // 否则图片页要按"图片内容高度"滑完才跨得过去，表现就是滑不动/一直是同一页。
        // 诊断日志按时间节流（≤2 行/秒）：logDebug 是 inline + VERBOSE_LOGS 门控（release 零成本），
        // 但 debug 包里原来每次调用都要扫全页找 max（滚动时每帧 20+ 次）⇒ 复现问题时反而加重卡顿。
        // 不用"页身份"做键：滚动上下文里的临时页/邻页对象每次都可能不同，那样等于没节流。
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastExtentLogAtMs >= 500L) {
            lastExtentLogAtMs = nowMs
            logDebug("M9PageScroll") {
                "extent page=${page.chapterIndex}:${page.pageInChapter} horizontal=$horizontal " +
                    "viewport=${if (horizontal) viewportWidth else viewportHeight} pageHeight=${page.height} " +
                    "pageWidth=${page.width} maxCrossEnd=${page.lines.maxOfOrNull { it.crossEnd } ?: 0f} " +
                    "maxLineBottom=${page.lines.maxOfOrNull { it.lineBottom } ?: 0f} lines=${page.lines.size}"
            }
        }
        if (page.lines.isEmpty()) {
            // 空页占满一屏，避免滚动上下文里出现"没内容也没高度"的洞。
            return if (horizontal) viewportWidth else viewportHeight
        }
        val contentExtent = if (horizontal) {
            // 竖排（滚动轴 = X）：本页**实际消耗的列宽**。lineBottom 是"列右边缘的绝对坐标"，
            // 首列右边缘恒等于视口宽 ⇒ 再取 max 就永远等于整屏，内容没占满时会多滑一段空白。
            page.width
        } else {
            maxOf(
                page.height,
                page.lines.maxOfOrNull { it.crossEnd } ?: 0f,
                // 末尾行/段距可能没有计入 crossEnd（实测同一页 crossEnd=1805.8 而 lineBottom=1928.0），
                // 少算这 122px 就会让相邻页画近一截，两页的行交错 ⇒ 正文"字叠字"。
                page.lines.maxOfOrNull { it.lineBottom } ?: 0f
            )
        }
        // 页边界 = 该页沿滚动轴的**真实跨度**（照 legado 的紧贴拼接，不再向上取整到整屏、不留空白带）。
        // 只保一个很小的下限：滚动上下文固定 ±4 页（ReadView.SCROLL_CONTEXT_RADIUS），
        // 9 × 下限必须 ≥ 视口，否则屏幕边缘会露出没画到的空白 ⇒ 下限取 视口 / 4。
        val minSpan = (if (horizontal) viewportWidth else viewportHeight) / 4f
        return contentExtent.coerceAtLeast(minSpan).also { page.scrollSpanCache = it }
    }

    private fun TextPage.hasImageColumn(): Boolean {
        return lines.any { line -> line.columns.any { it is ImageColumn } }
    }

    /**
     * 视图坐标 → 当前页内容坐标：扣掉 padding，并扣掉滚动模式下的滚动偏移。
     * 正文是按 `canvas.translate(offsetX/offsetY = scrollOffset)` 平移绘制的（drawPageLines），
     * 命中测试（点图/选区/手柄）如果不减这一段，点选与手柄、菜单就会整体错位——滚动余量越大越明显。
     */
    /** 当前"命中页"的滚动偏移：当前页用 [scrollOffset]，命中邻页时用那一页的偏移。 */
    private fun offsetForPage(target: TextPage?): Float {
        if (target == null) return scrollOffset
        val center = scrollPages.getOrNull(scrollCenterIndex)
        if (center == null || target === center) return scrollOffset
        val index = scrollPages.indexOfFirst { it === target }
        return if (index >= 0) scrollOffsetForIndex(index) else scrollOffset
    }

    /**
     * 手指可能落在的页：当前页 → 后页 → 前页，**偏移已超出视口就停**（照 legado 的遍历规则，
     * 不按固定 ±N）。命中测试用它替代"只看当前页"——滚动模式下屏面常有一部分属于相邻页。
     */
    private fun hitCandidates(): List<Pair<TextPage, Float>> {
        val center = scrollPages.getOrNull(scrollCenterIndex)
        if (center == null) return page?.let { listOf(it to scrollOffset) } ?: emptyList()
        val viewport = if (scrollHorizontal) {
            (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        } else {
            (height - paddingTop - paddingBottom).coerceAtLeast(1).toFloat()
        }
        val out = ArrayList<Pair<TextPage, Float>>(3)
        for (index in scrollCenterIndex until scrollPages.size) {
            val candidate = scrollPages[index] ?: continue
            // 占位页（邻章未排好时的标题页）不是真实内容：不可命中，选中/取词都不该落在它上面。
            if (candidate.isPlaceholder) continue
            val offset = scrollOffsetForIndex(index)
            if (index > scrollCenterIndex && offset >= viewport) break
            out += candidate to offset
        }
        for (index in scrollCenterIndex - 1 downTo 0) {
            val candidate = scrollPages[index] ?: continue
            if (candidate.isPlaceholder) continue
            val offset = scrollOffsetForIndex(index)
            if (offset + pageScrollExtent(candidate, scrollHorizontal) <= 0f) continue
            out += candidate to offset
        }
        return out
    }

    private fun contentX(x: Float): Float = x - paddingLeft - (if (scrollHorizontal) offsetForPage(page) else 0f)

    private fun contentY(y: Float): Float = y - paddingTop - (if (scrollHorizontal) 0f else offsetForPage(page))

    /** 内容坐标 → 视图坐标（[contentX]/[contentY] 的反函数）：画手柄/选区菜单要用。 */
    private fun viewX(x: Float): Float = x + paddingLeft + (if (scrollHorizontal) offsetForPage(page) else 0f)

    private fun viewY(y: Float): Float = y + paddingTop + (if (scrollHorizontal) 0f else offsetForPage(page))

    fun findImageAt(x: Float, y: Float): EbookImageRef? {
        for ((candidate, offset) in hitCandidates()) {
            val localX = x - paddingLeft - (if (scrollHorizontal) offset else 0f)
            val localY = y - paddingTop - (if (scrollHorizontal) 0f else offset)
            candidate.lines.forEach { line ->
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
                        // 命中邻页：把"当前页"对齐过去，后续 rect/手柄/菜单都用这一页的几何与偏移。
                        page = candidate
                        return column.image
                    }
                }
            }
        }
        return null
    }

    fun findAssistTokenAt(x: Float, y: Float): AssistToken? {
        for ((candidate, offset) in hitCandidates()) {
            val localX = x - paddingLeft - (if (scrollHorizontal) offset else 0f)
            val localY = y - paddingTop - (if (scrollHorizontal) 0f else offset)
            candidate.lines.forEachIndexed { lineIndex, line ->
                if (line.layoutMode != M9LayoutMode.VERTICAL) return@forEachIndexed
                val inLineBounds = localX >= line.lineTop && localX <= line.lineBottom
                if (!inLineBounds) return@forEachIndexed
                line.columns.forEachIndexed { columnIndex, column ->
                    if (column !is TextColumn) return@forEachIndexed
                    if (!isSidewaysAsciiText(column.charData)) return@forEachIndexed
                    if (localY < column.start || localY > column.end) return@forEachIndexed
                    // 命中邻页：先对齐"当前页"，气泡 rect（columnRect）才会用那一页的偏移。
                    page = candidate
                    return assistTokenAround(candidate, lineIndex, columnIndex)
                }
            }
        }
        return null
    }

    /**
     * 竖排中英文按单词切列、横倒绘制；点中某一列时取出气泡要显示的文本。
     *
     * 相邻单词列的 source 之间隔着空格、并不连续，所以按"source 连续"合并只能覆盖被切断
     * 的长串，永远合不成一句 —— 所以改为按正文范围取值（见 [assistSentenceRange]）：
     * 整句是西文才给整句（跨列跨页都覆盖），含 CJK 只给落点那一段，免得整段日语铺满气泡。
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

    /** 用章节坐标记录选区（含章号），并把"当前页那一部分"派生回页内区间。 */
    private fun setChapterSelection(chapterIndex: Int, startChapter: Int, endChapter: Int) {
        val from = minOf(startChapter, endChapter)
        val to = maxOf(startChapter, endChapter)
        selectionChapterIndex = chapterIndex
        selectionStartChapter = from
        selectionEndChapter = to
        selectionChapterRange = from..to
        syncPageLocalSelection()
    }

    /**
     * 把章节区间裁剪到**当前页**，写回 selectionRange/StartSource/EndSource。
     * 高亮、手柄、取词仍读页内区间，所以现有消费者不需要改；页不在区间内时清空。
     */
    private fun syncPageLocalSelection() {
        val chapterRange = selectionChapterRange ?: return
        val current = page ?: return
        val start = maxOf(chapterRange.first, current.charStart) - current.charStart
        val end = minOf(chapterRange.last, current.charEnd - 1) - current.charStart
        if (start <= end) {
            selectionRange = start..end
            selectionStartSource = start
            selectionEndSource = end
        } else {
            selectionRange = null
            selectionStartSource = null
            selectionEndSource = null
        }
        invalidate()
    }

    /** 章节坐标下的选区（跨页选择的权威值），供宿主取整句用。 */
    fun selectedChapterRange(): IntRange? = selectionChapterRange

    fun beginSelectionAt(x: Float, y: Float): Boolean {
        val hit = findTextHitAt(x, y) ?: return false
        val current = page ?: return false
        val wordRange = wordRangeAround(current, hit.lineIndex, hit.columnIndex)
        val range = wordRange ?: orderedRange(hit.sourceStart, hit.sourceEnd)
        // 以命中页为基准换算成章节坐标（跨页唯一可比），页内区间由 syncPageLocalSelection 派生。
        setChapterSelection(
            chapterIndex = current.chapterIndex,
            startChapter = hit.chapterStartOffset + range.first,
            endChapter = hit.chapterStartOffset + range.last
        )
        pendingSelectionHandleRole = null
        invalidate()
        return true
    }

    fun updateSelectionStartAt(x: Float, y: Float): Boolean {
        val hit = findTextHitAt(x, y) ?: return false
        val current = page ?: return false
        // 选区不跨章：命中落到别的章就忽略（章节区间跨章没有意义）。
        if (current.chapterIndex != selectionChapterIndex) return false
        val hitChapterStart = current.charStart + hit.sourceStart
        val hitChapterEnd = current.charStart + hit.sourceEnd - 1
        val keepEnd = selectionEndChapter ?: hitChapterEnd
        if (hitChapterStart <= keepEnd) {
            setChapterSelection(current.chapterIndex, hitChapterStart, keepEnd)
            pendingSelectionHandleRole = null
        } else {
            // 起点被拖到终点之后：交换两端，手柄角色跟着翻转
            setChapterSelection(current.chapterIndex, keepEnd, hitChapterEnd)
            pendingSelectionHandleRole = SelectionHandleRole.END
        }
        return true
    }

    fun updateSelectionEndAt(x: Float, y: Float): Boolean {
        val hit = findTextHitAt(x, y) ?: return false
        val current = page ?: return false
        if (current.chapterIndex != selectionChapterIndex) return false
        val hitChapterStart = current.charStart + hit.sourceStart
        val hitChapterEnd = current.charStart + hit.sourceEnd - 1
        val keepStart = selectionStartChapter ?: hitChapterStart
        if (hitChapterEnd >= keepStart) {
            setChapterSelection(current.chapterIndex, keepStart, hitChapterEnd)
            pendingSelectionHandleRole = null
        } else {
            setChapterSelection(current.chapterIndex, hitChapterStart, keepStart)
            pendingSelectionHandleRole = SelectionHandleRole.START
        }
        return true
    }

    fun clearSelection() {
        val hadSelection = selectionRange != null || selectionChapterRange != null
        selectionRange = null
        selectionStartSource = null
        selectionEndSource = null
        selectionChapterRange = null
        selectionStartChapter = null
        selectionEndChapter = null
        selectionChapterIndex = -1
        pendingSelectionHandleRole = null
        if (hadSelection) invalidate()
    }

    fun consumePendingSelectionHandleRole(): SelectionHandleRole? {
        val role = pendingSelectionHandleRole
        pendingSelectionHandleRole = null
        return role
    }

    /**
     * 宿主提供的**章节原文**（按章节下标取）。跨页选区要拿整句，页内文本不够用；
     * 不设置时退回页内拼接，行为与以前一致。
     */
    var chapterTextProvider: ((Int) -> String?)? = null

    /**
     * 占位页中央的一行提示（例如"排版中…"）。为空时占位页就是空白 —— 那是 legado 标题占位页的
     * 原样行为；设置了就照 legado 的"加载消息页"那样显示一行居中文字，免得用户看到白屏。
     */
    var placeholderMessage: String? = null

    /** 画占位页提示用：尺寸/颜色取自视图自身（textSizePxValue / currentTextColor），不依赖主题字段。 */
    private val placeholderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
    }

    fun selectedText(): String {
        // 章节坐标选区优先：直接取章节原文的子串 ⇒ 跨页选中拿到的就是完整一句。
        val chapterRange = selectionChapterRange
        val provider = chapterTextProvider
        if (chapterRange != null && provider != null) {
            val chapterText = provider(selectionChapterIndex)
            if (chapterText != null) {
                val from = chapterRange.first.coerceIn(0, chapterText.length)
                val to = (chapterRange.last + 1).coerceIn(from, chapterText.length)
                return chapterText.substring(from, to).trim()
            }
        }
        val range = selectionRange ?: return ""
        val current = page ?: return ""
        return current.lines.joinToString(separator = "\n") { line ->
            line.columns
                .filterIsInstance<TextColumn>()
                .filter { it.sourceStart < range.last + 1 && it.sourceEnd > range.first }
                .joinToString(separator = "") { it.charData }
        }.trim()
    }

    /** 选区所在章号（章节坐标口径，配合 selectedChapterRange() 使用）。 */
    fun selectedChapterIndex(): Int? = selectionChapterIndex.takeIf { it >= 0 }

    /**
     * 章节坐标 → 该位置所在页的（列矩形, 起始行顶）。
     * 跨页选区的两个端点可能落在不同页上，必须各自按**那一页**的偏移算，手柄才贴得住。
     */
    private fun chapterPositionRect(chapterPosition: Int): Pair<RectF, Float>? {
        val contextPages = if (scrollPages.isNotEmpty()) scrollPages else listOf(page)
        contextPages.forEachIndexed { index, candidate ->
            val target = candidate ?: return@forEachIndexed
            if (chapterPosition < target.charStart || chapterPosition >= target.charEnd) return@forEachIndexed
            val local = chapterPosition - target.charStart
            val offset = if (scrollPages.isNotEmpty()) scrollOffsetForIndex(index) else scrollOffset
            target.lines.forEach { line ->
                line.columns.filterIsInstance<TextColumn>().forEach { column ->
                    if (local >= column.sourceStart && local < column.sourceEnd) {
                        val rect = columnRectWithOffset(line, column, offset)
                        val lineTop = line.lineTop + paddingTop + (if (scrollHorizontal) 0f else offset)
                        return rect to lineTop
                    }
                }
            }
        }
        return null
    }

    fun selectionBounds(): SelectionBounds? {
        val chapterRange = selectionChapterRange
        if (chapterRange != null) {
            val start = chapterPositionRect(chapterRange.first)
            val end = chapterPositionRect(chapterRange.last) ?: start
            // 两端都在绘制上下文里 ⇒ 手柄贴真实端点页。
            if (start != null && end != null) {
                return SelectionBounds(
                    startRect = start.first,
                    endRect = end.first,
                    startLineTop = start.second
                )
            }
            // 端点页已被滚出上下文（视图坐标只对上下文内的页有意义）：退回"当前页那一段"的端点，
            // 手柄宁可位置不精确也不能凭空消失 —— 章节坐标仍然精确，滚回去就重新贴回真实端点。
        }
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
        for ((candidate, offset) in hitCandidates()) {
            val localX = x - paddingLeft - (if (scrollHorizontal) offset else 0f)
            val localY = y - paddingTop - (if (scrollHorizontal) 0f else offset)
            candidate.lines.forEachIndexed { lineIndex, line ->
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
                        // 命中邻页：先对齐"当前页"，再算 rect —— 否则手柄/菜单会用上一页的偏移。
                        page = candidate
                        return TextHit(
                            sourceStart = column.sourceStart,
                            sourceEnd = column.sourceEnd,
                            lineIndex = lineIndex,
                            columnIndex = columnIndex,
                            rect = columnRect(line, column),
                            chapterStartOffset = candidate.charStart
                        )
                    }
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
                        // 菜单/手柄的锚点同样要带滚动偏移，否则滚动出余量后菜单会整体下移一截。
                        lineTop = viewY(line.lineTop)
                    )
                }
            }
        }
        return null
    }

    private fun columnRect(line: TextLine, column: TextColumn): RectF =
        columnRectWithOffset(line, column, offsetForPage(page))

    /** 用**显式偏移**算列矩形：跨页端点的手柄必须按"端点所在那一页"的偏移算，否则会差一页。 */
    private fun columnRectWithOffset(line: TextLine, column: TextColumn, offset: Float): RectF {
        val dx = if (scrollHorizontal) offset else 0f
        val dy = if (scrollHorizontal) 0f else offset
        return when (line.layoutMode) {
            M9LayoutMode.HORIZONTAL -> RectF(
                column.start + paddingLeft + dx,
                line.lineTop + paddingTop + dy,
                column.end + paddingLeft + dx,
                line.lineBottom + paddingTop + dy
            )
            M9LayoutMode.VERTICAL -> RectF(
                line.lineTop + paddingLeft + dx,
                column.start + paddingTop + dy,
                line.lineBottom + paddingLeft + dx,
                column.end + paddingTop + dy
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
        // 占位页（邻章还没排好）：画一行居中提示，别让用户看到白屏（照 legado 的加载消息页）。
        if (current.isPlaceholder) {
            placeholderMessage?.let { message ->
                placeholderPaint.textSize = textSizePxValue
                // 本视图继承 View（没有 currentTextColor），正文颜色由各行自带画笔决定 ⇒ 提示用中性灰。
                // 待办：让宿主把阅读正文色传进来，跟随主题/夜间模式。
                placeholderPaint.color = 0xFF808080.toInt()
                canvas.drawText(
                    message,
                    (width - paddingLeft - paddingRight) / 2f,
                    (height - paddingTop - paddingBottom) / 2f,
                    placeholderPaint
                )
            }
        }
        // 跨页选区：这一页只画"本页 ∩ 章节区间"的那一段（页内区间只覆盖当前页）。
        val pageSelection = selectionChapterRange?.let { chapterRange ->
            val start = maxOf(chapterRange.first, current.charStart) - current.charStart
            val end = minOf(chapterRange.last, current.charEnd - 1) - current.charStart
            if (start <= end) start..end else null
        } ?: selectionRange
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
