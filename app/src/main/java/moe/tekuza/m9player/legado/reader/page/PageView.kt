package moe.tekuza.m9player.legado.reader.page

import android.content.Context
import android.view.Gravity
import android.view.View
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.Typeface
import android.graphics.RectF
import android.net.Uri
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue
import moe.tekuza.m9player.EbookImageRef
import moe.tekuza.m9player.dp
import moe.tekuza.m9player.ReaderBodyTitleMode
import moe.tekuza.m9player.ReaderFooterMode
import moe.tekuza.m9player.ReaderHeaderMode
import moe.tekuza.m9player.ReaderInfoSlot
import moe.tekuza.m9player.ReaderTipContent
import moe.tekuza.m9player.legado.reader.M9TextWeight
import moe.tekuza.m9player.legado.reader.entities.TextPage
import moe.tekuza.m9player.readerBatteryPercent
import moe.tekuza.m9player.readerClockText

internal class PageView(context: Context) : LinearLayout(context) {
    data class SelectedTextSnapshot(
        val text: String,
        val chapterIndex: Int,
        val chapterRange: IntRange
    )

    private val headerLeftView = ReaderBatteryTextView(context)
    private val headerMiddleView = ReaderBatteryTextView(context)
    private val headerRightView = ReaderBatteryTextView(context)
    private val bodyTitleView = TextView(context)
    val contentView = ContentTextView(context)
    private val footerLeftView = ReaderBatteryTextView(context)
    private val footerMiddleView = ReaderBatteryTextView(context)
    private val footerRightView = ReaderBatteryTextView(context)
    private val headerDividerView = View(context)
    private val footerDividerView = View(context)
    var solidBackgroundColor: Int? = null
        private set
    var currentPage: TextPage? = null
        private set
    private lateinit var headerView: LinearLayout
    private lateinit var footerView: LinearLayout
    private var showHeaderFooter: Boolean = true
    private var bookTitle: String = ""
    private var displayedChapterTitle: String? = null
    private var headerMode: ReaderHeaderMode = ReaderHeaderMode.HIDE_WHEN_STATUS_BAR_SHOW
    private var footerMode: ReaderFooterMode = ReaderFooterMode.SHOW
    private var bodyTitleMode: ReaderBodyTitleMode = ReaderBodyTitleMode.LEFT
    private var bodyTitleSizeAddSp: Int = 0
    private var readerTitleTypeface: Typeface? = null
    /** 与 bodyTitleView 同款样式的测量用视图（分页时计算标题预留高度，不改动显示中的标题） */
    private val titleMeasureView = TextView(context).apply {
        includeFontPadding = true
        maxLines = 2
        // 离屏视图没有父容器，layoutParams 为 null 时 setText 内部
        // checkForRelayout 读 mLayoutParams.width 会 NPE，先赋一个占位参数。
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
    private var bodyTitleTopSpacingDp: Int = 0
    private var bodyTitleBottomSpacingDp: Int = 0
    private var contentTextSizeSp: Float = 20f
    private var tipHeaderLeft: ReaderTipContent = ReaderTipContent.CHAPTER_TITLE
    private var tipHeaderMiddle: ReaderTipContent = ReaderTipContent.NONE
    private var tipHeaderRight: ReaderTipContent = ReaderTipContent.TIME
    private var tipFooterLeft: ReaderTipContent = ReaderTipContent.BOOK_NAME
    private var tipFooterMiddle: ReaderTipContent = ReaderTipContent.NONE
    private var tipFooterRight: ReaderTipContent = ReaderTipContent.PAGE_AND_TOTAL
    private var statusBarHidden: Boolean = false
    private var dividerColor: Int? = null
    private var showHeaderLine: Boolean = false
    private var showFooterLine: Boolean = true
    private var alternateInfoSlots: Set<ReaderInfoSlot> = emptySet()

    /** 提示条上时钟/电量的按秒缓存（见 [updateTipText]）。 */
    private var tipInfoUpdatedAtMs: Long = 0L
    private var cachedClockText: String = ""
    private var cachedBatteryPercent: Int = 0
    var onReaderInfoClick: ((ReaderInfoSlot) -> Unit)? = null

    init {
        orientation = VERTICAL
        setReaderPadding(dp(22), dp(34), dp(22), dp(22))
        addView(LinearLayout(context).apply {
            headerView = this
            gravity = Gravity.CENTER_VERTICAL
            addTipView(headerLeftView, Gravity.START or Gravity.CENTER_VERTICAL)
            addTipView(headerMiddleView, Gravity.CENTER)
            addTipView(headerRightView, Gravity.END or Gravity.CENTER_VERTICAL)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(headerDividerView, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))
        addView(bodyTitleView.apply {
            includeFontPadding = true
            maxLines = 2
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(contentView.apply {
            setPadding(0, dp(18), 0, dp(18))
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(footerDividerView, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))
        addView(LinearLayout(context).apply {
            footerView = this
            gravity = Gravity.CENTER_VERTICAL
            addTipView(footerLeftView, Gravity.START or Gravity.CENTER_VERTICAL)
            addTipView(footerMiddleView, Gravity.CENTER)
            addTipView(footerRightView, Gravity.END or Gravity.CENTER_VERTICAL)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun LinearLayout.addTipView(view: TextView, viewGravity: Int) {
        addView(view.apply {
            textSize = 12f
            maxLines = 1
            // 单行 + 固定高（dp(36)）本来就不会被撑开，但没有省略号时过长文本会直接截断；
            // 加 END 省略号与 legado 的 singleLine+ellipsize=end 观感一致。
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = viewGravity
            includeFontPadding = true
        }, LayoutParams(0, dp(36), 1f))
    }

    fun setPage(page: TextPage, highlight: IntRange?, search: IntRange?) {
        currentPage = page
        contentView.setPage(page, highlight, search)
        updateTipText()
    }

    fun setHighlight(highlight: IntRange?) {
        contentView.setHighlight(highlight)
    }

    fun setScrollContext(
        pages: List<TextPage?>,
        centerIndex: Int,
        offset: Float,
        horizontal: Boolean,
        reverse: Boolean
    ) {
        pages.getOrNull(centerIndex)?.let { page ->
            if (currentPage !== page) {
                currentPage = page
                updateTipText()
            }
        }
        contentView.setScrollContext(pages, centerIndex, offset, horizontal, reverse)
    }

    fun clearScrollContext() {
        contentView.clearScrollContext()
    }

    /** 命中邻页后把"当前页"复位到上下文中心（见 ContentTextView.syncCurrentPageToScrollCenter）。 */
    fun syncCurrentPageToScrollCenter() {
        contentView.syncCurrentPageToScrollCenter()
    }

    fun clickScrollDistance(horizontal: Boolean, direction: Int, fallback: Float): Float {
        return contentView.clickScrollDistance(horizontal, direction, fallback)
    }

    fun pageScrollExtent(page: TextPage?, horizontal: Boolean): Float {
        return contentView.pageScrollExtent(page, horizontal)
    }

    fun findImageAt(x: Float, y: Float): EbookImageRef? {
        return contentView.findImageAt(
            x = x - contentView.left,
            y = y - contentView.top
        )
    }

    fun findAssistTokenAt(x: Float, y: Float): ContentTextView.AssistToken? {
        val token = contentView.findAssistTokenAt(
            x = x - contentView.left,
            y = y - contentView.top
        ) ?: return null
        return token.copy(
            rect = RectF(token.rect).apply {
                offset(contentView.left.toFloat(), contentView.top.toFloat())
            }
        )
    }

    fun beginTextSelectionAt(x: Float, y: Float): Boolean {
        return contentView.beginSelectionAt(
            x = x - contentView.left,
            y = y - contentView.top
        )
    }

    fun updateSelectionStartAt(x: Float, y: Float): Boolean {
        return contentView.updateSelectionStartAt(
            x = x - contentView.left,
            y = y - contentView.top
        )
    }

    fun updateSelectionEndAt(x: Float, y: Float): Boolean {
        return contentView.updateSelectionEndAt(
            x = x - contentView.left,
            y = y - contentView.top
        )
    }

    fun clearTextSelection() {
        contentView.clearSelection()
    }

    fun selectedText(): String = contentView.selectedText()

    /** 章节原文提供者（跨页选区取整句用），转发到内容视图。 */
    var chapterTextProvider: ((Int) -> String?)?
        get() = contentView.chapterTextProvider
        set(value) {
            contentView.chapterTextProvider = value
        }

    fun selectedTextSnapshot(): SelectedTextSnapshot? {
        val page = currentPage ?: return null
        val text = contentView.selectedText().trim()
        if (text.isBlank()) return null
        val sourceRange = contentView.selectedSourceRange() ?: return null
        return SelectedTextSnapshot(
            text = text,
            chapterIndex = page.chapterIndex,
            chapterRange = (page.charStart + sourceRange.first)..(page.charStart + sourceRange.last)
        )
    }

    val selectionBackgroundInsetPx: Float
        get() = contentView.selectionBackgroundInsetPx

    fun consumePendingSelectionHandleRole(): ContentTextView.SelectionHandleRole? {
        return contentView.consumePendingSelectionHandleRole()
    }

    fun selectionBounds(): ContentTextView.SelectionBounds? {
        val bounds = contentView.selectionBounds() ?: return null
        val contentLeft = contentView.left.toFloat()
        val contentTop = contentView.top.toFloat()
        bounds.startRect.offset(contentLeft, contentTop)
        bounds.endRect.offset(contentLeft, contentTop)
        return bounds.copy(
            startLineTop = bounds.startLineTop + contentTop
        )
    }

    fun rangeBounds(range: IntRange): RectF? {
        return contentView.rangeBounds(range)?.apply {
            offset(contentView.left.toFloat(), contentView.top.toFloat())
        }
    }

    fun setReaderColors(
        bg: Int,
        text: Int,
        tip: Int,
        bgAssetName: String? = null,
        bgImageUri: String? = null,
        bgAlpha: Int = 100
    ) {
        solidBackgroundColor = bg
        background = readerBackground(bg, bgAssetName, bgImageUri, bgAlpha)
        contentView.setTextColor(text)
        contentView.setHighlightTextColor(text)
        listOf(
            headerLeftView,
            headerMiddleView,
            headerRightView,
            bodyTitleView,
            footerLeftView,
            footerMiddleView,
            footerRightView
        ).forEach { it.setTextColor(tip) }
        headerDividerView.setBackgroundColor(dividerColor ?: withAlpha(text, 0.12f))
        footerDividerView.setBackgroundColor(dividerColor ?: withAlpha(text, 0.12f))
    }

    fun setCueHighlightColor(color: Int) {
        contentView.setHighlightBackgroundColor(color)
    }

    private fun readerBackground(bg: Int, bgAssetName: String?, bgImageUri: String?, bgAlpha: Int): Drawable {
        val alpha = (bgAlpha.coerceIn(0, 100) * 255 / 100)
        val drawable = when {
            !bgImageUri.isNullOrBlank() -> runCatching {
                context.contentResolver.openInputStream(Uri.parse(bgImageUri))?.use { input ->
                    Drawable.createFromStream(input, bgImageUri)
                }
            }.getOrNull() ?: ColorDrawable(bg)
            !bgAssetName.isNullOrBlank() -> runCatching {
                context.assets.open("legado_bg/$bgAssetName").use { input ->
                    Drawable.createFromStream(input, bgAssetName)
                }
            }.getOrNull() ?: ColorDrawable(bg)
            else -> ColorDrawable(bg)
        }
        drawable.alpha = alpha
        return drawable
    }

    fun setTextSizeSp(sizeSp: Float) {
        contentTextSizeSp = sizeSp
        contentView.setTextSizePx(
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
        )
        applyBodyTitleStyle()
    }

    fun setTextWeight(weight: M9TextWeight) {
        contentView.setTextWeight(weight)
    }

    fun setTextUnderline(enabled: Boolean) {
        contentView.setTextUnderline(enabled)
    }

    fun setReaderTypeface(typeface: Typeface?) {
        contentView.setReaderTypeface(typeface)
        readerTitleTypeface = typeface
        applyBodyTitleStyle()
    }

    fun setReaderPadding(left: Int, top: Int, right: Int, bottom: Int) {
        setPadding(left, top, right, bottom)
    }

    fun setShowHeaderFooter(show: Boolean) {
        showHeaderFooter = show
        applyHeaderFooterVisibility()
    }

    fun setBookTitle(title: String) {
        bookTitle = title
        updateTipText()
    }

    fun setDisplayedChapterTitle(title: String?) {
        val nextTitle = title?.takeIf { it.isNotBlank() }
        if (displayedChapterTitle == nextTitle) return
        displayedChapterTitle = nextTitle
        updateTipText()
    }

    fun setReaderInfoConfig(
        bodyTitleMode: ReaderBodyTitleMode,
        bodyTitleSizeAddSp: Int,
        bodyTitleTopSpacingDp: Int,
        bodyTitleBottomSpacingDp: Int,
        headerMode: ReaderHeaderMode,
        footerMode: ReaderFooterMode,
        headerLeft: ReaderTipContent,
        headerMiddle: ReaderTipContent,
        headerRight: ReaderTipContent,
        footerLeft: ReaderTipContent,
        footerMiddle: ReaderTipContent,
        footerRight: ReaderTipContent,
        statusBarHidden: Boolean,
        dividerColor: Int?,
        headerPaddingTopDp: Int,
        headerPaddingBottomDp: Int,
        headerPaddingLeftDp: Int,
        headerPaddingRightDp: Int,
        footerPaddingTopDp: Int,
        footerPaddingBottomDp: Int,
        footerPaddingLeftDp: Int,
        footerPaddingRightDp: Int,
        showHeaderLine: Boolean,
        showFooterLine: Boolean,
        alternateInfoSlots: Set<ReaderInfoSlot>
    ) {
        this.bodyTitleMode = bodyTitleMode
        this.bodyTitleSizeAddSp = bodyTitleSizeAddSp
        this.bodyTitleTopSpacingDp = bodyTitleTopSpacingDp
        this.bodyTitleBottomSpacingDp = bodyTitleBottomSpacingDp
        this.headerMode = headerMode
        this.footerMode = footerMode
        tipHeaderLeft = headerLeft
        tipHeaderMiddle = headerMiddle
        tipHeaderRight = headerRight
        tipFooterLeft = footerLeft
        tipFooterMiddle = footerMiddle
        tipFooterRight = footerRight
        this.statusBarHidden = statusBarHidden
        this.dividerColor = dividerColor
        this.showHeaderLine = showHeaderLine
        this.showFooterLine = showFooterLine
        this.alternateInfoSlots = alternateInfoSlots
        headerView.setPadding(
            dp(headerPaddingLeftDp),
            dp(headerPaddingTopDp),
            dp(headerPaddingRightDp),
            dp(headerPaddingBottomDp)
        )
        footerView.setPadding(
            dp(footerPaddingLeftDp),
            dp(footerPaddingTopDp),
            dp(footerPaddingRightDp),
            dp(footerPaddingBottomDp)
        )
        updateTipText()
        applyHeaderFooterVisibility()
        applyBodyTitleStyle()
    }

    private fun applyHeaderFooterVisibility() {
        val showHeader = showHeaderFooter && when (headerMode) {
            ReaderHeaderMode.SHOW -> true
            ReaderHeaderMode.HIDE -> false
            ReaderHeaderMode.HIDE_WHEN_STATUS_BAR_SHOW -> statusBarHidden
        }
        val showFooter = showHeaderFooter && footerMode == ReaderFooterMode.SHOW
        headerLeftView.visibility = if (showHeader && tipHeaderLeft != ReaderTipContent.NONE) View.VISIBLE else View.INVISIBLE
        headerMiddleView.visibility = if (showHeader && tipHeaderMiddle != ReaderTipContent.NONE) View.VISIBLE else View.INVISIBLE
        headerRightView.visibility = if (showHeader && tipHeaderRight != ReaderTipContent.NONE) View.VISIBLE else View.INVISIBLE
        footerLeftView.visibility = if (showFooter && tipFooterLeft != ReaderTipContent.NONE) View.VISIBLE else View.INVISIBLE
        footerMiddleView.visibility = if (showFooter && tipFooterMiddle != ReaderTipContent.NONE) View.VISIBLE else View.INVISIBLE
        footerRightView.visibility = if (showFooter && tipFooterRight != ReaderTipContent.NONE) View.VISIBLE else View.INVISIBLE
        headerView.visibility = if (showHeader) View.VISIBLE else View.GONE
        footerView.visibility = if (showFooter) View.VISIBLE else View.GONE
        headerDividerView.visibility = if (showHeader && showHeaderLine && dividerColor != null) View.VISIBLE else View.GONE
        footerDividerView.visibility = if (showFooter && showFooterLine && dividerColor != null) View.VISIBLE else View.GONE
    }

    private fun updateTipText() {
        // 时钟/电量按秒缓存：原来每次 updateTipText 都各算一次（电量是一次 BatteryManager binder、
        // 时钟是一个 SimpleDateFormat），而换页、滚动跨页都会触发；用户可见信息只到分钟，1 秒足够。
        val nowMs = System.currentTimeMillis()
        if (nowMs - tipInfoUpdatedAtMs >= 1000L) {
            tipInfoUpdatedAtMs = nowMs
            cachedClockText = readerClockText()
            cachedBatteryPercent = readerBatteryPercent(context)
        }
        val clockText = cachedClockText
        val batteryPercent = cachedBatteryPercent
        val page = currentPage
        updateTipView(headerLeftView, tipHeaderLeft, page, ReaderInfoSlot.HEADER_LEFT, clockText, batteryPercent)
        updateTipView(headerMiddleView, tipHeaderMiddle, page, ReaderInfoSlot.HEADER_MIDDLE, clockText, batteryPercent)
        updateTipView(headerRightView, tipHeaderRight, page, ReaderInfoSlot.HEADER_RIGHT, clockText, batteryPercent)
        updateTipView(footerLeftView, tipFooterLeft, page, ReaderInfoSlot.FOOTER_LEFT, clockText, batteryPercent)
        updateTipView(footerMiddleView, tipFooterMiddle, page, ReaderInfoSlot.FOOTER_MIDDLE, clockText, batteryPercent)
        updateTipView(footerRightView, tipFooterRight, page, ReaderInfoSlot.FOOTER_RIGHT, clockText, batteryPercent)
        bindTipClick(headerLeftView, ReaderInfoSlot.HEADER_LEFT, tipHeaderLeft)
        bindTipClick(headerMiddleView, ReaderInfoSlot.HEADER_MIDDLE, tipHeaderMiddle)
        bindTipClick(headerRightView, ReaderInfoSlot.HEADER_RIGHT, tipHeaderRight)
        bindTipClick(footerLeftView, ReaderInfoSlot.FOOTER_LEFT, tipFooterLeft)
        bindTipClick(footerMiddleView, ReaderInfoSlot.FOOTER_MIDDLE, tipFooterMiddle)
        bindTipClick(footerRightView, ReaderInfoSlot.FOOTER_RIGHT, tipFooterRight)
        bodyTitleView.text = page?.title.orEmpty()
        applyBodyTitleStyle()
    }

    private fun updateTipView(
        view: ReaderBatteryTextView,
        content: ReaderTipContent,
        page: TextPage?,
        slot: ReaderInfoSlot,
        clockText: String,
        batteryPercent: Int
    ) {
        if (ReaderTipFormatter.isBatteryGraphicTip(content) && page != null) {
            view.setBatteryValue(
                value = batteryPercent,
                prefix = if (content == ReaderTipContent.TIME_BATTERY) clockText else null
            )
        } else {
            view.setPlainText(tipText(content, page, slot, clockText, batteryPercent))
        }
    }

    private fun applyBodyTitleStyle() {
        val page = currentPage
        // 卷页标题改由正文区居中渲染（isVolume），头部标题不再显示，避免重复。
        val show = page != null && page.pageInChapter == 0 &&
            bodyTitleMode != ReaderBodyTitleMode.HIDE && page.isVolume != true
        bodyTitleView.visibility = if (show) View.VISIBLE else View.GONE
        bodyTitleView.gravity = when (bodyTitleMode) {
            ReaderBodyTitleMode.LEFT -> Gravity.START
            ReaderBodyTitleMode.CENTER -> Gravity.CENTER
            ReaderBodyTitleMode.HIDE -> Gravity.START
        }
        // 标题格式：加粗 + 可配置字号加成（bodyTitleSizeAddSp），
        // 与参考实现 legado 用 titlePaint 渲染章节标题的观感对齐。
        bodyTitleView.typeface = Typeface.create(readerTitleTypeface ?: Typeface.DEFAULT, Typeface.BOLD)
        bodyTitleView.textSize = contentTextSizeSp + bodyTitleSizeAddSp
        // 注意：TextView.textSize 属性读写不对称（读返回 px、写按 sp 解释），
        // 不能把 bodyTitleView.textSize 读回来再赋给测量视图，必须用同一个 sp 值。
        titleMeasureView.typeface = bodyTitleView.typeface
        titleMeasureView.textSize = contentTextSizeSp + bodyTitleSizeAddSp
        (bodyTitleView.layoutParams as? LayoutParams)?.let { params ->
            val top = dp(bodyTitleTopSpacingDp)
            val bottom = dp(bodyTitleBottomSpacingDp)
            // 只在真的变了时写：无条件赋值会每次换页多触发一趟 requestLayout
            //（滚动模式每跨一页都会跑一次 updateTipText）。
            if (params.topMargin != top || params.bottomMargin != bottom) {
                params.topMargin = top
                params.bottomMargin = bottom
                bodyTitleView.layoutParams = params
            }
        }
    }

    /**
     * 计算某章节标题占用的垂直空间（含上下边距），供分页时预留：
     * 标题显示时正文区会被压缩这么多，首页排版必须按此扣除，否则最下面
     * 一行会被裁掉（长标题换行成两行时尤其明显）。
     */
    fun bodyTitleReserveFor(title: String, contentWidthPx: Int): Int {
        if (bodyTitleMode == ReaderBodyTitleMode.HIDE) return 0
        titleMeasureView.text = title
        titleMeasureView.measure(
            MeasureSpec.makeMeasureSpec(contentWidthPx.coerceAtLeast(1), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        return titleMeasureView.measuredHeight + dp(bodyTitleTopSpacingDp) + dp(bodyTitleBottomSpacingDp)
    }

    /**
     * 当前**已显示**的正文标题实际吃掉的高度（未显示为 0）。分页基准要用它把正文区还原成
     * "无标题"高度，而且必须问这个视图本身：用"当前页的标题"重算，只要与真实渲染的折行差一行
     * （≈72px），分页高度就会抖，进而把整章分页缓存冲掉（表现为滚动模式下快速滑动"一直重复"）。
     */
    val appliedBodyTitleReservePx: Int
        get() = if (bodyTitleView.visibility == VISIBLE) {
            val params = bodyTitleView.layoutParams as? LayoutParams
            bodyTitleView.measuredHeight + (params?.topMargin ?: 0) + (params?.bottomMargin ?: 0)
        } else {
            0
        }

    private fun tipText(
        content: ReaderTipContent,
        page: TextPage?,
        slot: ReaderInfoSlot,
        clockText: String = readerClockText(),
        batteryPercent: Int = readerBatteryPercent(context)
    ): String {
        return ReaderTipFormatter.text(
            content = content,
            page = page,
            alternateProgress = slot in alternateInfoSlots,
            bookTitle = bookTitle,
            chapterTitle = displayedChapterTitle ?: page?.title.orEmpty(),
            clockText = clockText,
            batteryPercent = batteryPercent
        )
    }

    private fun bindTipClick(view: TextView, slot: ReaderInfoSlot, content: ReaderTipContent) {
        val clickable = ReaderTipFormatter.isProgressTip(content)
        view.isClickable = clickable
        view.setOnClickListener(
            if (clickable) {
                View.OnClickListener { onReaderInfoClick?.invoke(slot) }
            } else {
                null
            }
        )
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val safeAlpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return (color and 0x00FFFFFF) or (safeAlpha shl 24)
    }

    private fun dp(value: Int): Int = value.dp(context)
}
