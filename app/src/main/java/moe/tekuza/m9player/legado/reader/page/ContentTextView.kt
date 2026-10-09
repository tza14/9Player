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
         * 命中页在章节原文里的起始下标（= 该页的 charStart）。
         * 有它，调用方就不必依赖"命中顺手改写当前页"这种副作用来换算章节坐标
         *（那个副作用会让命中结果依赖上一次命中 ⇒ 页边界处来回摆动）。
         */
        val chapterStartOffset: Int = 0,
        /** 命中所在页对象（算词范围、建端点都要用它，不能拿"当前页"顶替）。 */
        val page: TextPage? = null
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

    /**
     * 选区端点 —— **唯一真源**（照 legado 的 `TextPos`，但页身份是**绝对**的：chapterIndex + pageInChapter）。
     *
     * 以前是"章节区间 + 一份页内镜像"两份状态：页边界处命中一抖，两份表示就会互相打脸，
     * 表现为端点翻转、手柄消失、切片忽有忽无。现在页内区间/章节区间全部**派生**，没有第二份可失同步。
     */
    private var selectStart: SelectionPosition? = null
    private var selectEnd: SelectionPosition? = null

    /** 端点覆盖的章节区间（派生）：取词、Anki、按页求交都读它。 */
    private val selectionChapterRange: IntRange?
        get() = selectStart?.let { start ->
            selectEnd?.let { end -> positionsChapterRange(start, end) }
        }

    /** 宿主页那一段（页内下标，派生）：高亮/菜单/命中读它，不再单独保存。 */
    private val selectionRange: IntRange?
        get() = pageLocalSelectionSlice(selectionChapterRange, page)

    private val selectionStartChapter: Int?
        get() = selectStart?.chapterOffset

    private val selectionEndChapter: Int?
        get() = selectEnd?.chapterOffset

    private val selectionChapterIndex: Int
        get() = selectStart?.chapterIndex ?: -1

    /** M9Selection 诊断日志的限流时间戳（拖动手柄时每帧都会走到 setChapterSelection）。 */
    private var lastSelectionLogAtMs: Long = 0L

    /** "一次取点耗时"日志的时间戳（自己的，不与上面那条共用 ⇒ 免得互相顶掉）。 */
    private var lastHitCostLogAtMs: Long = 0L

    /** 手柄拖动时的几何诊断时间戳（自己一份 ⇒ 不会与别的日志互相顶掉）。 */
    private var lastGeometryLogAtMs: Long = 0L

    /** 绘制越界诊断的时间戳（同上，自己一份）。 */
    private var lastDrawGeomLogAtMs: Long = 0L

    /**
     * 章首正文标题（由**本视图自己绘制**，不再是 PageView 里的独立 TextView）。
     *
     * 为什么搬进来：独立视图是 LinearLayout 里的 `WRAP_CONTENT` 子项，而正文视图是 `weight=1`
     * ⇒ 标题一显隐，正文视图的高度就变 ⇒ "分页高度基准"随之变化。真机上抓到过渡态
     *（标题已 VISIBLE、正文区还没缩）导致基准多出 131px，于是每页末尾多排一行被裁掉 —— "页底吞字"。
     * 搬进来后正文视图高度与标题无关 ⇒ 高度只有一个来源。
     */
    var bodyTitleText: String? = null
        set(value) {
            if (field == value) return
            field = value
            bodyTitleLayout = null
            invalidate()
        }

    /** 标题字号（px）、对齐、上留白（px），由宿主按设置传入。 */
    var bodyTitleTextSizePx: Float = 0f
    var bodyTitleAlignCenter: Boolean = false
    var bodyTitleTopSpacingPx: Float = 0f

    private val bodyTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(readerTypeface ?: Typeface.DEFAULT, Typeface.BOLD)
    }
    private var bodyTitleLayout: android.text.StaticLayout? = null

    /**
     * 画章首标题（只在该章第一页 ✓）。用 StaticLayout ⇒ 折行/字距与旧 TextView 同引擎，
     * 最多两行、超出省略，避免长标题把版式挤歪。
     */
    private fun drawBodyTitle(
        canvas: Canvas,
        offset: Float,
        verticalText: Boolean,
        bodyRightEdge: Float,
        overrideText: String? = null,
        pageTitleStyle: Boolean = false,
        forceCenter: Boolean = false,
        screenCentered: Boolean = false
    ) {
        val text = (overrideText ?: bodyTitleText)?.takeIf { it.isNotBlank() } ?: return
        // 居中与否：章首标题跟随阅读设置（bodyTitleAlignCenter）；**纯标题页（卷页）恒定居中**，
        // 与设置解耦（照 legado：卷页标题就是居中的，旧注释也写着"卷页标题改由正文区居中渲染"）。
        val centered = forceCenter || bodyTitleAlignCenter
        // 样式来源：章首标题用宿主下发的 bodyTitle 字号；纯标题页（卷名页）用卷标题的大字号
        //（= 正文 × READER_TITLE_SCALE，加粗）——这是卷页标题**唯一**的字号来源，
        // 排版那边已经不再为它排任何行（删掉的那套逐字排版是第二套近似）。
        bodyTitleTextSizePx = if (pageTitleStyle) textSizePxValue * READER_TITLE_SCALE else bodyTitleTextSizePx
        // 颜色由宿主注入的正文色决定（夜间模式/主题）：不设就会退回 Paint 默认黑 ⇒ 黑底黑字。
        bodyTitlePaint.color = textColor
        bodyTitlePaint.textSize = bodyTitleTextSizePx
        // 竖排：标题是**一列（或多列）竖着写的字**，占最右侧（允许占多列，超出可用宽度省略）。
        // 横排：一条横带，用 StaticLayout 与旧 TextView 同引擎。
        if (verticalText) {
            drawVerticalBodyTitle(canvas, offset, bodyRightEdge, text, centered, screenCentered)
            return
        }
        // 横排交给 StaticLayout：画笔必须是 LEFT 对齐（StaticLayout 自己按 setAlignment 排）。
        bodyTitlePaint.textAlign = Paint.Align.LEFT
        val width = (this.width - paddingLeft - paddingRight).coerceAtLeast(1)
        var layout = bodyTitleLayout
        if (layout == null || layout.width != width) {
            bodyTitlePaint.textSize = bodyTitleTextSizePx
            layout = android.text.StaticLayout.Builder
                .obtain(text, 0, text.length, bodyTitlePaint, width)
                .setAlignment(
                    if (centered) android.text.Layout.Alignment.ALIGN_CENTER
                    else android.text.Layout.Alignment.ALIGN_NORMAL
                )                .setMaxLines(2)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .setIncludePad(true)
                .build()
            bodyTitleLayout = layout
        }
        canvas.save()
        // 画布在 onDraw 里已经按 (paddingLeft, paddingTop) 平移过 ⇒ 这里只加标题留白与该页的滚动偏移。
        // 卷页（screenCentered）在正文区**垂直居中**：整块文字的中心落在可用高度中点。
        val yOffset = if (screenCentered) {
            ((height - paddingTop - paddingBottom) - layout.height) / 2f
        } else {
            0f
        }
        canvas.translate(0f, bodyTitleTopSpacingPx + offset + yOffset)
        layout.draw(canvas)
        canvas.restore()
    }

    /**
     * 竖排标题：字从上往下叠，写完一列往左开一列（列从右往左）。
     *
     * 绘制**交给正文同一个引擎**（VerticalTextGlyphEngine）：逐**token**（一个字 / 一整段西文 / 拗音）
     * 走它的 `draw` / `drawLatinRun`，西文整段占多个 cell 一起旋转 ⇒ 不会再出现 `functio` 换列、`ns` 接行。
     * 手写逐字绘制做不到这件事（它只看得到单个字符），那是上一版把 Functions 拆开的根因。
     */
    /**
     * 规划一列：从 [index] 起、在 [perColumn] 行内能放下的 token 与它们占的行数。
     * 返回 `(下一个 index, 本列占用的行数)`；居中与绘制共用这一次规划 ⇒ 不会出现"两套算法"。
     * 西文 run 占多行（放不下就整段留给下一列 ⇒ 不拆词）。
     */
    private fun planVerticalTitleColumn(
        text: String,
        index: Int,
        perColumn: Int,
        cell: Float
    ): Pair<Int, Int> {
        var cursor = index
        var rows = 0
        while (rows < perColumn && cursor < text.length) {
            val token = VerticalTextGlyphEngine.nextVerticalTextToken(text, cursor, text.length)
            if (token.text.isBlank()) {
                cursor = token.sourceEndExclusive.coerceAtLeast(cursor + 1)
                continue
            }
            val span = if (isSidewaysAsciiText(token.text)) {
                kotlin.math.ceil(bodyTitlePaint.measureText(token.text) / cell).toInt().coerceAtLeast(1)
            } else {
                1
            }
            if (rows + span > perColumn) break
            cursor = token.sourceEndExclusive.coerceAtLeast(cursor + 1)
            rows += span
        }
        return cursor to rows
    }

    private fun drawVerticalBodyTitle(
        canvas: Canvas,
        offset: Float,
        bodyRightEdge: Float,
        title: String,
        centered: Boolean,
        screenCentered: Boolean
    ) {
        val text = title.takeIf { it.isNotBlank() } ?: return
        bodyTitlePaint.textSize = bodyTitleTextSizePx
        bodyTitlePaint.color = textColor
        bodyTitlePaint.textAlign = Paint.Align.CENTER
        val metrics = bodyTitlePaint.fontMetrics
        val step = (metrics.descent - metrics.ascent).coerceAtLeast(1f)
        val cell = VerticalTextGlyphEngine.estimateCellWidth(bodyTitlePaint)
        val columnTop = paddingTop.toFloat() + bodyTitleTopSpacingPx
        val bottom = (height - paddingBottom).toFloat()
        val perColumn = ((bottom - columnTop) / step).toInt().coerceAtLeast(1)
        // 边界来自实际布局：正文最右一列的右缘（竖排里 lineBottom 是 x 坐标）⇒ 标题不可能压到正文。
        val limitX = if (bodyRightEdge > paddingLeft) bodyRightEdge else paddingLeft.toFloat()
        // 卷页标题要落在**正文区水平中点**（不是一个字一个字地贴在右边）；章首标题仍贴右（竖排书的首列位置）。
        val centerX = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val rect = RectF()
        var index = 0
        var column = 0
        canvas.save()
        canvas.translate(offset, 0f)
        while (index < text.length) {
            val cellCenterX = if (screenCentered) {
                centerX - column * cell
            } else {
                width - paddingRight - column * cell - cell * 0.5f
            }
            if (!screenCentered && cellCenterX - cell * 0.5f < limitX) {
                canvas.drawText("…", cellCenterX, columnTop + step, bodyTitlePaint)
                break
            }
            // 先规划这一列：居中要看它用了几行；绘制再按同一次规划走（同一事实，不重复算）。
            val plan = planVerticalTitleColumn(text, index, perColumn, cell)
            val columnStartY = if (centered && plan.second < perColumn) {
                columnTop + (perColumn - plan.second) * step * 0.5f
            } else {
                columnTop
            }
            var row = 0
            while (row < perColumn && index < text.length) {
                val token = VerticalTextGlyphEngine.nextVerticalTextToken(text, index, text.length)
                if (token.text.isBlank()) {
                    index = token.sourceEndExclusive.coerceAtLeast(index + 1)
                    continue
                }
                // 西文整段按需要的 cell 数占位（一起旋转；放不下就整段换列，绝不拆词）
                val span = if (isSidewaysAsciiText(token.text)) {
                    kotlin.math.ceil(bodyTitlePaint.measureText(token.text) / cell).toInt().coerceAtLeast(1)
                } else {
                    1
                }
                if (row + span > perColumn) break
                if (index >= plan.first) break
                rect.set(
                    cellCenterX - cell * 0.5f,
                    columnStartY + row * step,
                    cellCenterX + cell * 0.5f,
                    columnStartY + (row + span) * step
                )
                if (isSidewaysAsciiText(token.text)) {
                    VerticalTextGlyphEngine.drawLatinRun(canvas, bodyTitlePaint, token.text, rect)
                } else {
                    VerticalTextGlyphEngine.draw(canvas, bodyTitlePaint, token.text, rect)
                }
                index = token.sourceEndExclusive.coerceAtLeast(index + 1)
                row += span
            }
            column++
        }
        canvas.restore()
    }

    /**
     * 手柄拖动时的几何诊断（限流 500ms）：逐页列出"跨度 span / 布局高度 h / 内容真实底 maxBottom"。
     *
     * 目的只有一个：证明或否证"相邻页的行带在屏幕上重叠"（那时两页都能精确命中 ⇒ 命中在页间摆动、
     * 选区停在手指前面）。`exceeds=true` 就是越界（span < 内容底）⇒ 页带区必然与下一页交叠。
     */
    private fun logHandleDragGeometry(x: Float, y: Float) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastGeometryLogAtMs < 500L) return
        lastGeometryLogAtMs = nowMs
        val pages = if (scrollPages.isNotEmpty()) scrollPages else listOfNotNull(page)
        logDebug("M9Selection") {
            val summary = pages.mapIndexedNotNull { index, target ->
                target?.let {
                    val span = pageScrollExtent(it, scrollHorizontal)
                    val maxBottom = it.lines.maxOfOrNull { line -> line.lineBottom } ?: 0f
                    val maxCross = it.lines.maxOfOrNull { line -> line.crossEnd } ?: 0f
                    "p${it.pageInChapter} ${it.charStart}..${it.charEnd} off=${scrollOffsetForIndex(index)} " +
                        "span=$span h=${it.height} maxBottom=$maxBottom maxCross=$maxCross " +
                        "exceeds=${maxBottom > span + 0.5f || maxCross > span + 0.5f}"
                }
            }.joinToString(" | ")
            "geomDrag touch=(${x.toInt()},${y.toInt()}) center=$scrollCenterIndex $summary"
        }
    }

    /**
     * 手指是否在**向下**移动 —— **已删除**：槽位归属唯一之后没有平局可裁（见 `resolveTextHit` 的 KDoc）。
     * 保留这段说明只为避免以后有人再把"方向裁决"当需求加回来。
     */
    private var pendingSelectionHandleRole: SelectionHandleRole? = null
    private var highlightRange: IntRange? = null
    private var searchRange: IntRange? = null
    private var textUnderline: Boolean = false
    private var scrollPages: List<TextPage?> = emptyList()
    private var scrollCenterIndex: Int = 0
    private var scrollOffset: Float = 0f

    /** `pageScrollExtent` 诊断日志的时间节流（毫秒）。 */
    private var lastExtentLogAtMs: Long = 0L

    /** 坐标漂移守卫自己的时间戳：与跨度日志共用会让守卫恒不可达（那条日志先跑并刷新它）。 */
    private var lastDriftLogAtMs: Long = 0L
    private var scrollHorizontal: Boolean = false
    private var scrollReverse: Boolean = false
    val textSizePx: Float get() = textSizePxValue

    init {
        selectionPaint.color = 0x63858585
        highlightPaint.color = 0x66E53935
        searchPaint.color = 0xAAFFD54F.toInt()
        underlinePaint.style = Paint.Style.STROKE
        underlinePaint.strokeCap = Paint.Cap.SQUARE
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
    }

    fun setPage(page: TextPage?, highlight: IntRange?, search: IntRange?) {
        this.page = page
        // 程序化换页一并撤销选区：否则 selectedText()/isTextSelected 报的还是旧章区间（菜单/手柄不撤）。
        selectStart = null
        selectEnd = null
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
        // 跨度只算一次就缓存在页上：绘制一帧要对上下文里每页反复取它（scrollOffsetForIndex 还要累加）。
        // 有效性除了"页对象"还包含**视口**：跨度里含"视口/4"的下限，视口变了必须重算。
        val viewport = if (horizontal) {
            (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        } else {
            (height - paddingTop - paddingBottom).coerceAtLeast(1).toFloat()
        }
        if (page != null && page.scrollSpanCache > 0f && page.scrollSpanViewport == viewport) {
            return page.scrollSpanCache
        }
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
            val maxCrossEnd = page.lines.maxOfOrNull { it.crossEnd } ?: 0f
            val maxLineBottom = page.lines.maxOfOrNull { it.lineBottom } ?: 0f
            // 守卫：line* 与 cross* 是两套平行坐标，历史上漂过 122px（page.height=1805.8 / lineBottom=1928.0），
            // 一漂就会让相邻页画近、正文"字叠字"。release 下 logDebug 整段消失（VERBOSE_LOGS 门控），
            // debug 下留一条限流的痕迹（用**自己的**时间戳，否则会被跨度日志的刷新压成恒不可达）。
            val nowMs = System.currentTimeMillis()
            if (kotlin.math.abs(maxCrossEnd - maxLineBottom) > 1f && nowMs - lastDriftLogAtMs >= 500L) {
                lastDriftLogAtMs = nowMs
                logDebug("M9PageScroll") {
                    "coordDrift pageHeight=${page.height} maxCrossEnd=$maxCrossEnd maxLineBottom=$maxLineBottom"
                }
            }
            maxOf(page.height, maxCrossEnd, maxLineBottom)
        }
        // 页边界 = 该页沿滚动轴的**真实跨度**（照 legado 的紧贴拼接，不再向上取整到整屏、不留空白带）。
        // 只保一个很小的下限：滚动上下文固定 ±4 页（ReadView.SCROLL_CONTEXT_RADIUS），
        // 9 × 下限必须 ≥ 视口，否则屏幕边缘会露出没画到的空白 ⇒ 下限取 视口 / 4。
        val minSpan = (if (horizontal) viewportWidth else viewportHeight) / 4f
        return contentExtent.coerceAtLeast(minSpan).also {
            page.scrollSpanCache = it
            page.scrollSpanViewport = if (horizontal) viewportWidth else viewportHeight
        }
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

    /** 内容坐标 → 视图坐标：画手柄/选区菜单要用（横轴的反函数已无调用点，只留仍在用的 [viewY]）。 */
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
                        // 不改写"当前页"：调用方（ReadView.handleTap）只用返回的 image 去开预览，
                        // 不需要页对齐的几何 —— 留着这个副作用只会再养出"命中依赖上一次命中"的坑。
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
                    // 气泡 rect 用**命中页自己的偏移**算（把偏移传进去），不再改写"当前页"。
                    return assistTokenAround(candidate, lineIndex, columnIndex, offset)
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
    private fun assistTokenAround(
        page: TextPage,
        lineIndex: Int,
        columnIndex: Int,
        offset: Float
    ): AssistToken? {
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
                                rect = columnRectWithOffset(line, column, offset)
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

    /** 用两个**章节下标**创建选区（适配层：把下标解析成端点，端点自带页身份）。 */
    private fun setChapterSelection(startChapter: Int, endChapter: Int) {
        val from = minOf(startChapter, endChapter)
        val to = maxOf(startChapter, endChapter)
        val start = positionAt(from) ?: return
        val end = positionAt(to) ?: start
        selectStart = start
        selectEnd = end
        // 重绘由调用方负责（唯一调用点 beginSelectionAt 紧接着就 invalidate()）——
        // updateSelection*At 那两路各自重绘，因为它们没有别的刷新点。
        // 诊断（限流 500ms —— 拖动手柄时这里每帧都会跑到，不限流会刷屏）。
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSelectionLogAtMs >= 500L) {
            lastSelectionLogAtMs = nowMs
            logDebug("M9Selection") {
                val cols = page?.lines
                    ?.flatMap { line -> line.columns.filterIsInstance<TextColumn>() }
                    .orEmpty()
                "setChapterSelection chapter=${selectStart?.chapterIndex} range=$from..$to " +
                    "pageCharStart=${page?.charStart} pageCharEnd=${page?.charEnd} " +
                    "pageInChapter=${page?.pageInChapter} lines=${page?.lineSize} " +
                    "colSource=${if (cols.isEmpty()) "-" else "${cols.first().sourceStart}..${cols.last().sourceEnd}"} " +
                    "derived=${selectionRange}"
            }
        }
    }

    /**
     * 章节下标 → 端点（页身份由页表决定）。
     * 页内位置取"包含该下标的那个字"；落在行尾/空白就退回该页的最后一个字。
     * 解析不到页时返回 null（**绝不**夹到当前页造一个错误端点）。
     */
    private fun positionAt(chapterOffset: Int): SelectionPosition? {
        val pages = if (scrollPages.isNotEmpty()) scrollPages else listOf(page)
        val target = pageForChapterPosition(pages, chapterOffset)
            ?: pageForChapterPosition(listOf(page), chapterOffset)
            ?: return null
        val local = (chapterOffset - target.charStart).coerceAtLeast(0)
        var fallback: SelectionPosition? = null
        target.lines.forEachIndexed { lineIndex, line ->
            line.columns.forEachIndexed { columnIndex, column ->
                if (column !is TextColumn) return@forEachIndexed
                fallback = selectionPositionOf(target, lineIndex, columnIndex, column.sourceStart)
                if (local >= column.sourceStart && local < column.sourceEnd) {
                    return selectionPositionOf(target, lineIndex, columnIndex, column.sourceStart)
                }
            }
        }
        return fallback
    }

    /** 章节坐标下的选区（跨页选择的权威值），供宿主取整句用。 */
    fun selectedChapterRange(): IntRange? = selectionChapterRange

    fun beginSelectionAt(x: Float, y: Float): Boolean {
        val hit = findTextHitAt(x, y)
        // 入口即打日志：有这行=手势到了；没有=长按没被识别。hit=null 说明命中了空白。
        logDebug("M9Selection") {
            "beginSelectionAt x=${x.toInt()} y=${y.toInt()} hit=${hit != null} " +
                "pageInChapter=${page?.pageInChapter} pageCharStart=${page?.charStart} " +
                "scrollPages=${scrollPages.size} center=$scrollCenterIndex offset=$scrollOffset " +
                "horizontal=$scrollHorizontal"
        }
        if (hit == null) return false
        val hitPage = hit.page ?: return false
        val wordRange = wordRangeAround(hitPage, hit.lineIndex, hit.columnIndex)
        val range = wordRange ?: orderedRange(hit.sourceStart, hit.sourceEnd)
        // 章节坐标 = 命中页的 charStart + 页内下标（列的下标是页内下标 ✓）。
        // 换算基准一律取**命中结果自带**的页，不再依赖"命中顺手改写当前页"的副作用。
        setChapterSelection(
            startChapter = hit.chapterStartOffset + range.first,
            endChapter = hit.chapterStartOffset + range.last
        )
        pendingSelectionHandleRole = null
        invalidate()
        return true
    }

    fun updateSelectionStartAt(x: Float, y: Float): Boolean {
        // 平局不需要裁决：一个触点只属于一页（resolveTextHit 先按槽位定页）。
        val hit = findTextHitAt(x, y) ?: return false
        logHandleDragGeometry(x, y)
        val hitPage = hit.page ?: return false
        val end = selectEnd ?: return false
        // 选区不跨章：命中落到别的章就忽略。
        if (hitPage.chapterIndex != end.chapterIndex) return false
        val hitPosition = selectionPositionOf(hitPage, hit.lineIndex, hit.columnIndex, hit.sourceStart)
        // 命中没换字（手指停在同一列内）⇒ 状态不会变，跳过这次整屏重绘（照 legado 的 compare == 0 早退）。
        if (hitPosition.sameAs(end)) return true
        // 保序（legado 的 selectStartMove）：只动起点；被拖过终点则两端交换、手柄角色翻转。
        if (hitPosition.compareTo(end) <= 0) {
            selectStart = hitPosition
            pendingSelectionHandleRole = null
        } else {
            selectStart = end
            selectEnd = hitPosition
            pendingSelectionHandleRole = SelectionHandleRole.END
        }
        invalidate()
        return true
    }

    fun updateSelectionEndAt(x: Float, y: Float): Boolean {
        val hit = findTextHitAt(x, y) ?: return false
        logHandleDragGeometry(x, y)
        val hitPage = hit.page ?: return false
        val start = selectStart ?: return false
        if (hitPage.chapterIndex != start.chapterIndex) return false
        val hitPosition = selectionPositionOf(hitPage, hit.lineIndex, hit.columnIndex, hit.sourceStart)
        // 命中没换字 ⇒ 跳过这次整屏重绘（同上）。
        if (hitPosition.sameAs(start)) return true
        // 保序（legado 的 selectEndMove）：只动终点；被拖过起点则两端交换。
        if (hitPosition.compareTo(start) >= 0) {
            selectEnd = hitPosition
            pendingSelectionHandleRole = null
        } else {
            selectEnd = start
            selectStart = hitPosition
            pendingSelectionHandleRole = SelectionHandleRole.START
        }
        invalidate()
        return true
    }

    fun clearSelection() {
        val hadSelection = selectStart != null || selectEnd != null
        selectStart = null
        selectEnd = null
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

    fun selectionBounds(): SelectionBounds? {
        // 端点**自带页身份**（照 legado 的 TextPos）：跨页选区时起点/终点可能不在"当前页"上，
        // 拿当前页解析另一个端点必然失败 ⇒ 手柄被隐藏（真机症状："拖到下一页后手柄消失"）。
        val chapterRange = selectionChapterRange
        if (chapterRange != null) {
            val contextPages = if (scrollPages.isNotEmpty()) scrollPages else listOf(page)
            val startPage = pageForChapterPosition(contextPages, chapterRange.first)
            val endPage = pageForChapterPosition(contextPages, chapterRange.last) ?: startPage
            val start = endpointAnchorFor(startPage, chapterRange.first, preferStart = true)
            val end = endpointAnchorFor(endPage, chapterRange.last, preferStart = false) ?: start
            if (start != null && end != null) {
                return SelectionBounds(
                    startRect = start.rect,
                    endRect = end.rect,
                    startLineTop = start.lineTop
                )
            }
            // 端点页不在绘制上下文里（滚走了）⇒ 退回下面"当前页那一段"的端点：
            // 手柄宁可位置不精确，也不能凭空消失。
        }
        val host = page ?: return null
        val range = pageLocalSelectionSlice(selectionChapterRange, host) ?: return null
        val startLocal = ((selectStart?.chapterOffset ?: range.first) - host.charStart)
            .coerceIn(range.first, range.last)
        val endLocal = ((selectEnd?.chapterOffset ?: range.last) - host.charStart)
            .coerceIn(range.first, range.last)
        val start = findColumnAnchor(startLocal, preferStart = true, target = host) ?: return null
        val end = findColumnAnchor(endLocal, preferStart = false, target = host) ?: start
        return SelectionBounds(
            startRect = start.rect,
            endRect = end.rect,
            startLineTop = start.lineTop
        )
    }

    /**
     * 章节位置在**指定页**上的锚点（列矩形 + 起始行顶），用该页自己的滚动偏移算。
     * 端点解析靠它：每个端点用自己所在的那一页，而不是"当前页"。
     */
    private fun endpointAnchorFor(
        target: TextPage?,
        chapterPosition: Int,
        preferStart: Boolean
    ): SelectionColumnAnchor? {
        val current = target ?: return null
        val local = chapterPosition - current.charStart
        val offset = offsetForPage(current)
        current.lines.forEach { line ->
            line.columns.filterIsInstance<TextColumn>().forEach { column ->
                val contains = local >= column.sourceStart && local < column.sourceEnd
                val hit = if (preferStart) contains else contains || column.sourceEnd == local + 1
                if (hit) {
                    return SelectionColumnAnchor(
                        rect = columnRectWithOffset(line, column, offset),
                        lineTop = line.lineTop + paddingTop + (if (scrollHorizontal) 0f else offset)
                    )
                }
            }
        }
        return null
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
        // 取点逻辑全在 resolveTextHit（纯函数、有单测）；这里只负责把结果包成带 rect 的 TextHit。
        val candidates = hitCandidates()
        val startedAtNs = System.nanoTime()
        val point = resolveTextHit(
            candidates = candidates,
            x = x,
            y = y,
            paddingLeft = paddingLeft.toFloat(),
            paddingTop = paddingTop.toFloat(),
            horizontal = scrollHorizontal
        ) ?: return null
        // 诊断（限流 500ms）：一次取点扫了几页、耗时多少微秒 —— 用来判断"收窄候选"这类优化值不值得。
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastHitCostLogAtMs >= 500L) {
            lastHitCostLogAtMs = nowMs
            logDebug("M9Selection") {
                "hitCost candidates=${candidates.size} us=${(System.nanoTime() - startedAtNs) / 1000} " +
                    "page=${point.page.pageInChapter} line=${point.lineIndex} col=${point.columnIndex}"
            }
        }
        val line = point.page.lines.getOrNull(point.lineIndex) ?: return null
        val column = line.columns.getOrNull(point.columnIndex) as? TextColumn ?: return null
        return TextHit(
            sourceStart = column.sourceStart,
            sourceEnd = column.sourceEnd,
            lineIndex = point.lineIndex,
            columnIndex = point.columnIndex,
            rect = columnRectWithOffset(line, column, offsetForPage(point.page)),
            chapterStartOffset = point.page.charStart,
            page = point.page
        )
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

    private fun findColumnAnchor(
        source: Int,
        preferStart: Boolean,
        target: TextPage? = page
    ): SelectionColumnAnchor? {
        val current = target ?: return null
        val range = pageLocalSelectionSlice(selectionChapterRange, current)
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
                        rect = columnRectWithOffset(line, column, offsetForPage(current)),
                        // 菜单/手柄的锚点同样要带滚动偏移，否则滚动出余量后菜单会整体下移一截。
                        lineTop = line.lineTop + paddingTop + (if (scrollHorizontal) 0f else offsetForPage(current))
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
        if (current.lines.isEmpty() && !current.isVolume) {
            // 占位页正是"没有 lines"这一支：提示必须在这里画，否则它永远不会出现。
            // 卷页也是"没有 lines"，但它不是占位页：标题要交给标题渲染器画（见下），不能在这里兜底成
            // "居中提示 + 横排 fallback 正文"，否则卷页标题会被画成横排小字。
            drawPlaceholderMessage(canvas, current)
            drawFallbackText(canvas, current.text)
            canvas.restore()
            return
        }
        if (scrollPages.isNotEmpty()) {
            drawScrollPages(canvas)
        } else {
            val verticalText = current.isVerticalText()
            if (current.isVolume) {
                drawBodyTitle(canvas, 0f, verticalText = verticalText, bodyRightEdge = paddingLeft.toFloat(), overrideText = current.title, pageTitleStyle = true, forceCenter = true, screenCentered = true)
            } else if (current.pageInChapter == 0) {
                val bodyRightEdge = current.lines.maxOfOrNull { it.lineBottom } ?: paddingLeft.toFloat()
                drawBodyTitle(canvas, 0f, verticalText = verticalText, bodyRightEdge = bodyRightEdge)
            }
            drawPageLines(canvas, current, 0f, 0f, highlightRange, searchRange)
        }
        canvas.restore()
    }

    /**
     * 这一页的文字是不是竖排。
     *
     * 有行时**由行说了算**（同一页的行只会是一种排版模式）；卷页一行都没有（标题由标题渲染器画）
     * ⇒ 退回排版在页上盖的章 [TextPage.layoutMode]，否则竖排卷页的标题会被当成横排去排。
     */
    private fun TextPage.isVerticalText(): Boolean {
        val lineMode = lines.firstOrNull()?.layoutMode ?: layoutMode
        return lineMode == M9LayoutMode.VERTICAL
    }

    private fun drawScrollPages(canvas: Canvas) {
        scrollPages.forEachIndexed { index, textPage ->
            if (textPage == null) return@forEachIndexed
            val offset = scrollOffsetForIndex(index)
            val offsetX = if (scrollHorizontal) offset else 0f
            val offsetY = if (scrollHorizontal) 0f else offset
            // 章首标题由本视图绘制（跟着第 0 页滚动，不占额外布局高度）。
            // 纯标题页（卷名页 / 只有标题的章）整页交给标题渲染器：竖排时走竖排引擎 ⇒
            // 西文整段旋转、不再一个字母一行。
            val verticalText = textPage.isVerticalText()
            val bodyRightEdge = textPage.lines.maxOfOrNull { it.lineBottom } ?: paddingLeft.toFloat()
            if (textPage.isVolume) {
                drawBodyTitle(
                    canvas = canvas,
                    offset = if (verticalText) offsetX else offsetY,
                    verticalText = verticalText,
                    bodyRightEdge = paddingLeft.toFloat(),
                    overrideText = textPage.title,
                    pageTitleStyle = true,
                    forceCenter = true,
                    screenCentered = true
                )
            } else if (textPage.pageInChapter == 0) {
                drawBodyTitle(
                    canvas = canvas,
                    offset = if (verticalText) offsetX else offsetY,
                    verticalText = verticalText,
                    bodyRightEdge = bodyRightEdge
                )
            }
            // 绘制侧几何诊断：**只在真的越出可视区时**打一行（限流 500ms）。
            // 用来分开"布局算错"与"绘制用了别的高度/起点"：布局侧自检是 M9Layout bottomOverflow，
            // 这里是决定性的那一条（viewBottom = 视图高 − 底部内边距）。
            val lastCross = textPage.lines.maxOfOrNull { it.crossEnd } ?: 0f
            val lastBottom = textPage.lines.maxOfOrNull { it.lineBottom } ?: 0f
            val viewBottom = height - paddingBottom
            if (offsetY + lastCross > viewBottom + 0.5f) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastDrawGeomLogAtMs >= 500L) {
                    lastDrawGeomLogAtMs = nowMs
                    logDebug("M9Layout") {
                        "drawOverflow page=${textPage.pageInChapter} ${textPage.charStart}..${textPage.charEnd} " +
                            "h=${textPage.height} lines=${textPage.lines.size} offsetY=$offsetY " +
                            "lastBottom=$lastBottom lastCross=$lastCross viewH=$height " +
                            "padTop=$paddingTop padBottom=$paddingBottom viewBottom=$viewBottom"
                    }
                }
            }
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
        drawPlaceholderMessage(canvas, current)
        // 跨页选区：这一页只画"本页 ∩ 章节区间"那一段；交集为空 ⇒ 不画。
        // 端点制之后这里只有一个口径：按页求交（端点自带页身份，不需要任何回退分支）。
        val pageSelection = pageLocalSelectionSlice(selectionChapterRange, current)
        // 纯标题页（卷页）：标题已由标题渲染器画过，而且排版本来就不再为它生成任何行
        //（卷页没有 isTitle 行这回事了）⇒ 这里直接返回。
        // 判定用 page.isVolume（排版自己打的标记），不要用"行是不是空的"——占位页也没有行。
        if (current.isVolume) {
            canvas.restore()
            return
        }
        // 诊断：只打**真的有内容**的页（有切片、或带 cue 高亮）。
        // 不能按"每 500ms 打第一页"——drawPageLines 是按上下文顺序逐页调用的，那样永远只打最上面那页。
        if (pageSelection != null || highlight != null) {
            logDebug("M9Selection") {
                "drawPage pageInChapter=${current.pageInChapter} charStart=${current.charStart} " +
                    "charEnd=${current.charEnd} slice=$pageSelection cue=${highlight != null} " +
                    "hostPage=${page?.pageInChapter} hostSel=$selectionRange"
            }
        }
        current.lines.forEach { line ->
            when (line.layoutMode) {
                M9LayoutMode.HORIZONTAL -> {
                    if (line.crossEnd + offsetY < 0f || line.crossStart + offsetY > height - paddingTop - paddingBottom) return@forEach
                    line.draw(this, canvas, pageSelection, highlight, search)
                    drawHorizontalUnderline(canvas, line)
                }
                M9LayoutMode.VERTICAL -> {
                    if (line.lineBottom + offsetX < 0f || line.lineTop + offsetX > width - paddingLeft - paddingRight) return@forEach
                    line.draw(this, canvas, pageSelection, highlight, search)
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

    /**
     * 占位页中央的一行提示（邻章还没排好时用，见 [placeholderMessage]）。
     * 两条绘制路径都要调用：有 lines 的走 [drawPageLines]，占位页（无 lines）走 [onDraw] 的兜底分支。
     */
    private fun drawPlaceholderMessage(canvas: Canvas, target: TextPage) {
        if (!target.isPlaceholder) return
        val message = placeholderMessage ?: return
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
