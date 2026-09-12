package moe.tekuza.m9player.legado.reader.page

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Scroller
import android.widget.TextView
import android.widget.Toast
import android.view.VelocityTracker
import android.view.ViewGroup
import moe.tekuza.m9player.EbookImageRef
import moe.tekuza.m9player.R
import moe.tekuza.m9player.ReaderBodyTitleMode
import moe.tekuza.m9player.ReaderFooterMode
import moe.tekuza.m9player.ReaderHeaderMode
import moe.tekuza.m9player.ReaderInfoSlot
import moe.tekuza.m9player.ReaderTipContent
import moe.tekuza.m9player.dp
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.M9PageAnim
import moe.tekuza.m9player.legado.reader.M9TextWeight
import moe.tekuza.m9player.legado.reader.entities.TextPage
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import java.util.Locale
import moe.tekuza.m9player.logDebug

private const val M9_PAGE_SIMULATION_LOG_TAG = "M9PageSimulation"
private const val M9_SELECTION_LOG_TAG = "M9Selection"
private const val SELECTION_MENU_OPEN_SPACE_THRESHOLD_PX = 500
private const val CROSS_PAGE_OVERLAY_FADE_MS = 220L
private const val CROSS_PAGE_OVERLAY_TAIL_MARGIN_DP = 16

private fun m9PageSimulationFormat(value: Float): String {
    return String.format(Locale.US, "%.1f", value)
}

internal class ReadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    data class SelectionInfo(
        val text: String,
        val chapterIndex: Int? = null,
        val chapterRange: IntRange? = null
    )

    data class SelectionPrimaryActionOption(
        val key: String,
        val label: String
    )

    private val targetPageView = PageView(context).apply {
        visibility = GONE
    }
    private val pageView = PageView(context)
    private val assistOverlay = TextView(context)
    private val crossPageCuePageOverlay = PageView(context)
    private val selectionStartHandle = SelectionHandleView(context)
    private val selectionEndHandle = SelectionHandleView(context)
    private val selectionActionMenu = SelectionActionMenu(
        context = context,
        onAction = { action -> performSelectionAction(action) },
        onProcessText = { intent -> performSelectionProcessText(intent) }
    )
    var onPrevPage: (() -> Unit)? = null
    var onNextPage: (() -> Unit)? = null
    var onMovePages: ((Int) -> Unit)? = null
    var onMenu: (() -> Unit)? = null
    var onTapAction: ((TapAction) -> Unit)? = null
    var onSelectionAction: ((SelectionAction, SelectionInfo) -> Unit)? = null
    var onSelectionProcessText: ((Intent, String) -> Unit)? = null
    var onTextSelectionStateChanged: ((Boolean) -> Unit)? = null
    var onImageClick: ((EbookImageRef) -> Unit)? = null
    var onPagePreview: ((Int) -> TextPage?)? = null
    var onDisplayedPageCommitted: ((TextPage) -> Unit)? = null
    var canJumpSelectionToCue: ((SelectionInfo) -> Boolean)? = null
    var selectionJumpToCueEnabled: Boolean
        get() = selectionActionMenu.jumpToCueEnabled
        set(value) {
            selectionActionMenu.jumpToCueEnabled = value
        }
    private var layoutMode: M9LayoutMode = M9LayoutMode.HORIZONTAL
    private var pageAnim: M9PageAnim = M9PageAnim.NONE
    private var clickRegionActions: List<TapAction> = defaultClickRegionActions()
    private var assistToken: ContentTextView.AssistToken? = null
    private var overlayTextColor: Int = 0xFFFFFFFF.toInt()
    private var overlayBgColor: Int = 0xCC1E1E1E.toInt()
    private var noAnimScrollPage: Boolean = false
    private var suppressNextSetAnimation: Boolean = false
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var dragDirection: Int = 0
    private var isDraggingPage: Boolean = false
    private var isGestureAnimating: Boolean = false
    private var longPressTriggered: Boolean = false
    private var isTextSelected: Boolean = false
    private var activeSelectionHandle: SelectionHandle = SelectionHandle.NONE
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        handleLongPressSelection()
    }
    private var consumedByDragProbe: Boolean = false
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private val pageInterpolator = DecelerateInterpolator(1.45f)
    private val linearInterpolator = LinearInterpolator()
    private val scrollScroller = Scroller(context, linearInterpolator)
    private val velocityTracker: VelocityTracker = VelocityTracker.obtain()
    private var scrollLastScrollerX: Int = 0
    private var scrollLastScrollerY: Int = 0
    private var scrollContentOffset: Float = 0f
    private var scrollStartOffset: Float = 0f
    private var scrollPageStep: Float = 1f
    private var scrollAnchorDelta: Int = 0
    private var simulationAnimator: ValueAnimator? = null
    private var simulationCurrentBitmap: Bitmap? = null
    private var simulationTargetBitmap: Bitmap? = null
    private var simulationTouchX: Float = 0.1f
    private var simulationTouchY: Float = 0.1f
    private var simulationCornerX: Int = 0
    private var simulationCornerY: Int = 0
    private var simulationCurlSide: Int = 1
    private var simulationCommitAfterAnim: Boolean = false
    private val simulationRenderer = SimulationCurlRenderer()

    val contentWidth: Int get() = pageView.contentView.width
    val contentHeight: Int
        get() = (
            pageView.contentView.height -
                pageView.contentView.paddingTop -
                pageView.contentView.paddingBottom
            ).coerceAtLeast(0)
    val textSizePx: Float get() = pageView.contentView.textSizePx

    /** 章节标题占用高度（含边距），供分页预留；标题隐藏时返回 0。 */
    fun bodyTitleReserveFor(title: String, contentWidthPx: Int): Int {
        return pageView.bodyTitleReserveFor(title, contentWidthPx)
    }

    init {
        addView(targetPageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(pageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(assistOverlay.apply {
            visibility = GONE
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { hideAssistOverlay() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(crossPageCuePageOverlay.apply {
            visibility = GONE
            setShowHeaderFooter(false)
            setReaderPadding(0, 0, 0, 0)
            contentView.setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { hideCrossPageCueOverlay() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(selectionStartHandle.apply {
            visibility = GONE
        }, LayoutParams(dp(24), dp(24)))
        addView(selectionEndHandle.apply {
            visibility = GONE
        }, LayoutParams(dp(24), dp(24)))
        updateAssistOverlayStyle()
    }

    fun setPage(page: TextPage, highlight: IntRange? = null, search: IntRange? = null, forward: Boolean = true) {
        hideAssistOverlay()
        pageView.setPage(page, highlight, search)
        if (suppressNextSetAnimation) {
            suppressNextSetAnimation = false
            resetPageLayers()
        } else {
            runPageAnimation(forward)
        }
    }

    fun setCueHighlight(highlight: IntRange?) {
        pageView.setHighlight(highlight)
    }

    fun showCrossPageCueOverlay(text: String, range: IntRange?, verticalPage: TextPage? = null) {
        if (text.isBlank()) {
            hideCrossPageCueOverlay()
            return
        }
        if (verticalPage != null) {
            showCrossPageCuePageOverlay(verticalPage, range)
            return
        }
        assistToken = null
        crossPageCuePageOverlay.visibility = GONE
        assistOverlay.text = text
        updateAssistOverlayStyle()
        assistOverlay.post {
            assistOverlay.measure(
                MeasureSpec.makeMeasureSpec((width - dp(24)).coerceAtLeast(0), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec((height - dp(24)).coerceAtLeast(0), MeasureSpec.AT_MOST)
            )
            val anchor = range?.let { pageView.rangeBounds(it) }
                ?: RectF(width / 2f, height / 2f, width / 2f, height / 2f)
            val target = computeCrossPageOverlayBounds(
                anchor = anchor,
                overlayWidth = assistOverlay.measuredWidth,
                overlayHeight = assistOverlay.measuredHeight,
                vertical = false
            )
            val params = assistOverlay.layoutParams as LayoutParams
            params.leftMargin = target.left.toInt()
            params.topMargin = target.top.toInt()
            assistOverlay.layoutParams = params
            assistOverlay.visibility = VISIBLE
            assistOverlay.bringToFront()
        }
    }

    fun hideCrossPageCueOverlay() {
        if (assistToken == null) {
            assistOverlay.visibility = GONE
        }
        if (crossPageCuePageOverlay.visibility == VISIBLE) {
            crossPageCuePageOverlay.animate().cancel()
            crossPageCuePageOverlay.animate()
                .alpha(0f)
                .setDuration(CROSS_PAGE_OVERLAY_FADE_MS)
                .withEndAction {
                    crossPageCuePageOverlay.visibility = GONE
                    crossPageCuePageOverlay.alpha = 1f
                }
                .start()
        } else {
            crossPageCuePageOverlay.visibility = GONE
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
        setBackgroundColor(bg)
        forEachPageView { it.setReaderColors(bg, text, tip, bgAssetName, bgImageUri, bgAlpha) }
        overlayTextColor = text
        overlayBgColor = if (isDarkColor(bg)) 0xCC2D2D2D.toInt() else 0xF4FFF8EC.toInt()
        updateAssistOverlayStyle()
        updateCrossPageCuePageOverlayStyle()
    }

    fun setCueHighlightColor(color: Int) {
        forEachPageView { it.setCueHighlightColor(color) }
        crossPageCuePageOverlay.setCueHighlightColor(color)
    }

    fun setTextSizeSp(sizeSp: Float) {
        forEachPageView { it.setTextSizeSp(sizeSp) }
        assistOverlay.textSize = sizeSp
        crossPageCuePageOverlay.setTextSizeSp(sizeSp)
    }

    fun setTextWeight(weight: M9TextWeight) {
        forEachPageView { it.setTextWeight(weight) }
        crossPageCuePageOverlay.setTextWeight(weight)
    }

    fun setTextUnderline(enabled: Boolean) {
        forEachPageView { it.setTextUnderline(enabled) }
        crossPageCuePageOverlay.setTextUnderline(enabled)
    }

    fun setReaderTypeface(typeface: Typeface?) {
        forEachPageView { it.setReaderTypeface(typeface) }
        assistOverlay.typeface = typeface ?: Typeface.DEFAULT
        crossPageCuePageOverlay.setReaderTypeface(typeface)
    }

    fun setReaderPadding(left: Int, top: Int, right: Int, bottom: Int) {
        forEachPageView { it.setReaderPadding(left, top, right, bottom) }
        requestLayout()
    }

    fun setBookTitle(title: String) {
        forEachPageView { it.setBookTitle(title) }
        crossPageCuePageOverlay.setBookTitle(title)
    }

    fun setOnReaderInfoClick(listener: ((ReaderInfoSlot) -> Unit)?) {
        forEachPageView { it.onReaderInfoClick = listener }
    }

    fun setDisplayedChapterTitle(title: String?) {
        forEachPageView { it.setDisplayedChapterTitle(title) }
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
        forEachPageView {
            it.setReaderInfoConfig(
                bodyTitleMode = bodyTitleMode,
                bodyTitleSizeAddSp = bodyTitleSizeAddSp,
                bodyTitleTopSpacingDp = bodyTitleTopSpacingDp,
                bodyTitleBottomSpacingDp = bodyTitleBottomSpacingDp,
                headerMode = headerMode,
                footerMode = footerMode,
                headerLeft = headerLeft,
                headerMiddle = headerMiddle,
                headerRight = headerRight,
                footerLeft = footerLeft,
                footerMiddle = footerMiddle,
                footerRight = footerRight,
                statusBarHidden = statusBarHidden,
                dividerColor = dividerColor,
                headerPaddingTopDp = headerPaddingTopDp,
                headerPaddingBottomDp = headerPaddingBottomDp,
                headerPaddingLeftDp = headerPaddingLeftDp,
                headerPaddingRightDp = headerPaddingRightDp,
                footerPaddingTopDp = footerPaddingTopDp,
                footerPaddingBottomDp = footerPaddingBottomDp,
                footerPaddingLeftDp = footerPaddingLeftDp,
                footerPaddingRightDp = footerPaddingRightDp,
                showHeaderLine = showHeaderLine,
                showFooterLine = showFooterLine,
                alternateInfoSlots = alternateInfoSlots
            )
        }
        requestLayout()
    }

    fun setPageAnim(anim: M9PageAnim) {
        pageAnim = anim
        resetPageLayers()
    }

    fun setLayoutMode(mode: M9LayoutMode) {
        layoutMode = mode
        resetPageLayers()
    }

    fun setNoAnimScrollPage(enabled: Boolean) {
        noAnimScrollPage = enabled
    }

    fun setClickRegionActions(actions: List<TapAction>) {
        clickRegionActions = actions.takeIf { it.size == CLICK_REGION_COUNT } ?: defaultClickRegionActions()
    }

    fun setSelectionPrimaryActionKey(key: String) {
        selectionActionMenu.setPrimaryActionKey(key)
    }

    fun selectionPrimaryActionOptions(): List<SelectionPrimaryActionOption> {
        return selectionActionMenu.primaryActionOptions()
    }

    override fun computeScroll() {
        if (!scrollScroller.computeScrollOffset()) {
            if (isGestureAnimating && pageAnim == M9PageAnim.SCROLL) {
                finishScrollMotion()
            }
            return
        }
        val deltaX = scrollScroller.currX - scrollLastScrollerX
        val deltaY = scrollScroller.currY - scrollLastScrollerY
        scrollLastScrollerX = scrollScroller.currX
        scrollLastScrollerY = scrollScroller.currY
        if (isDraggingPage || isGestureAnimating) {
            if (pageAnim == M9PageAnim.SCROLL) {
                updateScrollDragFromScroller(deltaX.toFloat(), deltaY.toFloat())
            }
            postInvalidateOnAnimation()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (pageAnim == M9PageAnim.SIMULATION && (isDraggingPage || isGestureAnimating)) {
            drawSimulationPage(canvas)
            drawOverlayChildren(canvas)
            return
        }
        super.dispatchDraw(canvas)
    }

    private fun runPageAnimation(forward: Boolean) {
        resetPageLayers()
        if (pageAnim == M9PageAnim.NONE || width <= 0 || height <= 0) return
        val direction = if (forward) 1 else -1
        when (pageAnim) {
            M9PageAnim.COVER -> {
                pageView.translationX = horizontalPageSide(direction) * width.toFloat()
                pageView.animate()
                    .translationX(0f)
                    .setInterpolator(pageInterpolator)
                    .setDuration(PROGRAMMATIC_PAGE_ANIM_MS)
                    .start()
            }
            M9PageAnim.SLIDE -> {
                pageView.translationX = horizontalPageSide(direction) * width.toFloat()
                pageView.animate()
                    .translationX(0f)
                    .setInterpolator(pageInterpolator)
                    .setDuration(PROGRAMMATIC_PAGE_ANIM_MS)
                    .start()
            }
            M9PageAnim.SIMULATION -> {
                pageView.translationX = horizontalPageSide(direction) * width.toFloat()
                pageView.animate()
                    .translationX(0f)
                    .setInterpolator(pageInterpolator)
                    .setDuration(PROGRAMMATIC_PAGE_ANIM_MS)
                    .start()
            }
            M9PageAnim.SCROLL -> {
                val distance = clickScrollDistance(direction)
                if (isScrollHorizontalAxis()) {
                    val start = horizontalPageSide(direction) * distance
                    pageView.translationX = start
                } else {
                    val start = direction * distance
                    pageView.translationY = start
                }
                pageView.animate()
                    .translationX(0f)
                    .translationY(0f)
                    .setInterpolator(pageInterpolator)
                    .setDuration(if (noAnimScrollPage) 1L else PROGRAMMATIC_PAGE_ANIM_MS)
                    .start()
            }
            M9PageAnim.NONE -> Unit
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                hideAssistOverlay()
                abortGestureAnimation()
                velocityTracker.clear()
                velocityTracker.addMovement(event)
                if (!scrollScroller.isFinished) scrollScroller.abortAnimation()
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                dragDirection = 0
                isDraggingPage = false
                longPressTriggered = false
                activeSelectionHandle = selectionHandleAt(event.x, event.y)
                if (activeSelectionHandle != SelectionHandle.NONE) {
                    hideSelectionMenu()
                    return true
                }
                consumedByDragProbe = false
                if (!isTextSelected) {
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                downX = event.getX(index)
                downY = event.getY(index)
                lastTouchX = downX
                lastTouchY = downY
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker.addMovement(event)
                if (activeSelectionHandle != SelectionHandle.NONE) {
                    updateSelectionHandle(event.x, event.y)
                    return true
                }
                if (longPressTriggered) return true
                handleMove(event)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                activeSelectionHandle = SelectionHandle.NONE
                if (isDraggingPage) {
                    finishDrag(commit = false)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                velocityTracker.addMovement(event)
                velocityTracker.computeCurrentVelocity(1000)
                if (activeSelectionHandle != SelectionHandle.NONE) {
                    activeSelectionHandle = SelectionHandle.NONE
                    showSelectionMenu()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                if (longPressTriggered) {
                    longPressTriggered = false
                    if (isTextSelected) {
                        showSelectionMenu()
                    }
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                if (isDraggingPage) {
                    finishDrag(commit = if (pageAnim == M9PageAnim.SCROLL) true else shouldCommitDrag())
                } else if (consumedByDragProbe && dragDirection != 0) {
                    if (shouldCommitPreviewlessTurn(event)) {
                        invokeTurnCallback(direction = dragDirection)
                    }
                    resetPageLayers()
                } else if (!consumedByDragProbe) {
                    handleTap(event)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun handleMove(event: MotionEvent) {
        if (isGestureAnimating) return
        val dx = event.x - downX
        val dy = event.y - downY
        if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
            removeCallbacks(longPressRunnable)
        }
        if (!isDraggingPage) {
            val direction = dragDirectionFor(dx, dy) ?: return
            val preview = onPagePreview?.invoke(direction)
            if (preview == null) {
                dragDirection = direction
                consumedByDragProbe = true
                return
            }
            beginDrag(direction, preview)
        }
        if (pageAnim == M9PageAnim.SIMULATION) {
            updateSimulationDrag(event.x, event.y)
            return
        }
        if (pageAnim == M9PageAnim.SCROLL) {
            val axisDelta = if (isScrollHorizontalAxis()) {
                event.x - lastTouchX
            } else {
                event.y - lastTouchY
            }
            lastTouchX = event.x
            lastTouchY = event.y
            updateScrollDrag(axisDelta)
            return
        }
        updateDrag(dx, dy)
    }

    private fun dragDirectionFor(dx: Float, dy: Float): Int? {
        return if (pageAnim == M9PageAnim.SCROLL) {
            if (isScrollHorizontalAxis()) {
                if (abs(dx) <= touchSlop || abs(dx) < abs(dy) * 0.65f) return null
                horizontalDragDirection(dx)
            } else {
                if (abs(dy) <= touchSlop || abs(dy) < abs(dx) * 0.65f) return null
                if (dy < 0f) 1 else -1
            }
        } else {
            if (abs(dx) <= touchSlop || abs(dx) < abs(dy) * 0.65f) return null
            horizontalDragDirection(dx)
        }
    }

    private fun beginDrag(direction: Int, preview: TextPage) {
        removeCallbacks(longPressRunnable)
        clearTextSelection()
        dragDirection = direction
        isDraggingPage = true
        consumedByDragProbe = true
        if (pageAnim == M9PageAnim.SCROLL) {
            targetPageView.visibility = GONE
            targetPageView.translationX = 0f
            targetPageView.translationY = 0f
            pageView.bringToFront()
            assistOverlay.bringToFront()
            scrollStartOffset = scrollContentOffset
            lastTouchX = downX
            lastTouchY = downY
            scrollPageStep = currentScrollExtent()
            updateScrollContentContext(scrollContentOffset)
            return
        }
        targetPageView.setPage(preview, null, null)
        targetPageView.visibility = VISIBLE
        targetPageView.alpha = 1f
        targetPageView.rotationY = 0f
        targetPageView.bringToFront()
        pageView.bringToFront()
        assistOverlay.bringToFront()
        when (pageAnim) {
            M9PageAnim.SLIDE -> {
                targetPageView.translationX = horizontalPageSide(direction) * width.toFloat()
                targetPageView.translationY = 0f
            }
            M9PageAnim.SIMULATION -> {
                targetPageView.translationX = 0f
                targetPageView.translationY = 0f
                beginSimulationCurl(direction)
            }
            else -> {
                targetPageView.translationX = 0f
                targetPageView.translationY = 0f
            }
        }
    }

    private fun updateDrag(dx: Float, dy: Float) {
        when (pageAnim) {
            M9PageAnim.SCROLL -> updateScrollDrag(if (isScrollHorizontalAxis()) dx else dy)
            M9PageAnim.COVER -> updateCoverDrag(dx)
            M9PageAnim.SLIDE -> updateSlideDrag(dx)
            M9PageAnim.SIMULATION -> updateSimulationDrag(downX + dx, downY + dy)
            M9PageAnim.NONE -> Unit
        }
    }

    private fun updateCoverDrag(dx: Float) {
        val offset = clampPageOffset(dx, horizontalPageSide(dragDirection), width.toFloat())
        pageView.translationX = offset
        pageView.alpha = 1f - (abs(offset) / width.coerceAtLeast(1).toFloat()) * 0.08f
    }

    private fun updateSlideDrag(dx: Float) {
        val side = horizontalPageSide(dragDirection)
        val offset = clampPageOffset(dx, side, width.toFloat())
        pageView.translationX = offset
        targetPageView.translationX = side * width.toFloat() + offset
    }

    private fun beginSimulationCurl(direction: Int) {
        simulationAnimator?.cancel()
        simulationCurlSide = horizontalPageSide(direction)
        simulationCornerX = if (simulationCurlSide > 0) width else 0
        simulationCornerY = simulationCornerYForStart(direction)
        simulationTouchX = downX.coerceIn(0.1f, (width - 0.1f).coerceAtLeast(0.1f))
        simulationTouchY = simulationTouchYForDrag(direction, downY)
        simulationCommitAfterAnim = false
        simulationCurrentBitmap = pageView.captureToBitmap(simulationCurrentBitmap)
        simulationTargetBitmap = targetPageView.captureToBitmap(simulationTargetBitmap)
        targetPageView.visibility = INVISIBLE
        logDebug(M9_PAGE_SIMULATION_LOG_TAG) {
            "begin direction=$direction layout=$layoutMode side=$simulationCurlSide " +
            "corner=($simulationCornerX,$simulationCornerY) " +
            "down=(${m9PageSimulationFormat(downX)},${m9PageSimulationFormat(downY)}) " +
            "touch=(${m9PageSimulationFormat(simulationTouchX)},${m9PageSimulationFormat(simulationTouchY)}) " +
            "curlCurrentPage=true"
        }
        postInvalidateOnAnimation()
    }

    private fun updateSimulationDrag(x: Float, y: Float) {
        simulationTouchX = x.coerceIn(0.1f, (width - 0.1f).coerceAtLeast(0.1f))
        simulationTouchY = simulationTouchYForDrag(dragDirection, y)
        logDebug(M9_PAGE_SIMULATION_LOG_TAG) {
            "drag direction=$dragDirection side=$simulationCurlSide " +
            "raw=(${m9PageSimulationFormat(x)},${m9PageSimulationFormat(y)}) " +
            "touch=(${m9PageSimulationFormat(simulationTouchX)},${m9PageSimulationFormat(simulationTouchY)})"
        }
        postInvalidateOnAnimation()
    }

    private fun simulationCornerYForStart(direction: Int): Int {
        if (direction < 0) return height
        return when {
            downY > height / 3f && downY < height / 2f -> 0
            downY > height / 3f && downY < height * 2f / 3f -> height
            downY <= height / 2f -> 0
            else -> height
        }
    }

    private fun simulationTouchYForDrag(direction: Int, y: Float): Float {
        val safeBottom = (height - 0.1f).coerceAtLeast(0.1f)
        return when {
            direction < 0 -> safeBottom
            downY > height / 3f && downY < height / 2f -> 0.1f
            downY > height / 3f && downY < height * 2f / 3f -> safeBottom
            else -> y.coerceIn(0.1f, safeBottom)
        }
    }

    private fun updateScrollDrag(axisDelta: Float) {
        scrollPageStep = currentScrollExtent()
        updateScrollContentContext(scrollContentOffset + axisDelta)
    }

    private fun updateScrollDragFromScroller(deltaX: Float, deltaY: Float) {
        if (isScrollHorizontalAxis()) {
            updateScrollContentContext(scrollContentOffset + deltaX)
        } else {
            updateScrollContentContext(scrollContentOffset + deltaY)
        }
    }

    private fun clampPageOffset(value: Float, pageSide: Int, size: Float): Float {
        val safeSize = size.coerceAtLeast(1f)
        return if (pageSide > 0) {
            value.coerceIn(-safeSize, 0f)
        } else {
            value.coerceIn(0f, safeSize)
        }
    }

    private fun shouldCommitDrag(): Boolean {
        val distance = if (pageAnim == M9PageAnim.SCROLL && !isScrollHorizontalAxis()) {
            abs(scrollContentOffset)
        } else if (pageAnim == M9PageAnim.SCROLL) {
            abs(scrollContentOffset)
        } else if (pageAnim == M9PageAnim.SIMULATION) {
            abs(simulationTouchX - downX)
        } else {
            abs(pageView.translationX)
        }
        val size = if (pageAnim == M9PageAnim.SCROLL) {
            clickScrollDistance(dragDirection).toInt()
        } else {
            width
        }
        val velocity = if (pageAnim == M9PageAnim.SCROLL && !isScrollHorizontalAxis()) {
            abs(velocityTracker.yVelocity)
        } else {
            abs(velocityTracker.xVelocity)
        }
        return shouldCommitTurn(distance, size, velocity)
    }

    private fun shouldCommitPreviewlessTurn(event: MotionEvent): Boolean {
        val distance = if (pageAnim == M9PageAnim.SCROLL && !isScrollHorizontalAxis()) {
            abs(event.y - downY)
        } else {
            abs(event.x - downX)
        }
        val size = if (pageAnim == M9PageAnim.SCROLL && !isScrollHorizontalAxis()) height else width
        val velocity = if (pageAnim == M9PageAnim.SCROLL && !isScrollHorizontalAxis()) {
            abs(velocityTracker.yVelocity)
        } else {
            abs(velocityTracker.xVelocity)
        }
        return shouldCommitTurn(distance, size, velocity)
    }

    private fun shouldCommitTurn(distance: Float, size: Int, velocity: Float): Boolean {
        return distance >= size.coerceAtLeast(1) * 0.22f || velocity > MIN_FLING_VELOCITY
    }

    private fun finishDrag(commit: Boolean) {
        if (pageAnim == M9PageAnim.NONE) {
            if (commit) {
                suppressNextSetAnimation = true
                invokeTurnCallback()
            }
            resetPageLayers()
            return
        }
        if (pageAnim == M9PageAnim.SCROLL && noAnimScrollPage) {
            if (commit && abs(scrollContentOffset) <= 1f) {
                scrollPageStep = currentScrollExtent()
                updateScrollContentContext(
                    scrollContentOffset + scrollOffsetForDirection(dragDirection) * clickScrollDistance(dragDirection)
                )
            }
            finishScrollMotion()
            return
        }
        isGestureAnimating = true
        if (pageAnim == M9PageAnim.SCROLL) {
            finishScrollDrag(commit)
            return
        }
        if (pageAnim == M9PageAnim.SIMULATION) {
            finishSimulationDrag(commit)
            return
        }
        val size = width.toFloat()
        val currentOffset = pageView.translationX
        val progress = abs(currentOffset) / size.coerceAtLeast(1f)
        val duration = (PAGE_DRAG_ANIM_MS * (1f - progress).coerceIn(0.25f, 1f)).toLong()
        val side = horizontalPageSide(dragDirection)
        val currentEnd = if (commit) -side * size else 0f
        val targetEnd = if (commit) 0f else side * size
        animateDragLayer(pageView, x = currentEnd, y = 0f, duration = duration)
        animateDragLayer(targetPageView, x = targetEnd, y = 0f, duration = duration) {
            if (commit) {
                suppressNextSetAnimation = true
                invokeTurnCallback()
            }
            resetPageLayers()
        }
    }

    private fun finishScrollDrag(commit: Boolean) {
        val clickDistance = clickScrollDistance(dragDirection).coerceAtLeast(1f)
        scrollPageStep = currentScrollExtent()
        if (!commit) {
            startScrollSettle(scrollStartOffset, PAGE_DRAG_ANIM_MS)
            return
        }
        val velocity = if (isScrollHorizontalAxis()) velocityTracker.xVelocity else velocityTracker.yVelocity
        if (abs(velocity) < MIN_FLING_VELOCITY) {
            if (abs(scrollContentOffset) <= 1f) {
                startScrollSettle(
                    scrollContentOffset + scrollOffsetForDirection(dragDirection) * clickDistance,
                    if (noAnimScrollPage) 1L else PROGRAMMATIC_PAGE_ANIM_MS
                )
                return
            }
            finishScrollMotion()
            return
        }
        val flingRange = if (isScrollHorizontalAxis()) width else height
        val maxOffset = flingRange.coerceAtLeast(1) * 10
        if (isScrollHorizontalAxis()) {
            scrollLastScrollerX = scrollContentOffset.toInt()
            scrollLastScrollerY = 0
            scrollScroller.fling(
                scrollLastScrollerX,
                0,
                velocity.toInt(),
                0,
                -maxOffset,
                maxOffset,
                0,
                0
            )
        } else {
            scrollLastScrollerX = 0
            scrollLastScrollerY = scrollContentOffset.toInt()
            scrollScroller.fling(
                0,
                scrollLastScrollerY,
                0,
                velocity.toInt(),
                0,
                0,
                -maxOffset,
                maxOffset
            )
        }
        postInvalidateOnAnimation()
    }

    private fun finishSimulationDrag(commit: Boolean) {
        val startX = simulationTouchX
        val startY = simulationTouchY
        val targetX = if (commit) {
            if (simulationCurlSide > 0) -width.toFloat() else width.toFloat() * 2f
        } else {
            simulationCornerX.toFloat()
        }
        val targetY = if (commit) {
            if (simulationCornerY > 0) height.toFloat() else 0.1f
        } else {
            simulationCornerY.toFloat().coerceIn(0.1f, (height - 0.1f).coerceAtLeast(0.1f))
        }
        val progress = (abs(startX - downX) / width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        val duration = (PAGE_DRAG_ANIM_MS * (1f - progress).coerceIn(0.25f, 1f)).toLong()
        logDebug(M9_PAGE_SIMULATION_LOG_TAG) {
            "finishDrag commit=$commit direction=$dragDirection side=$simulationCurlSide " +
            "start=(${m9PageSimulationFormat(startX)},${m9PageSimulationFormat(startY)}) " +
            "target=(${m9PageSimulationFormat(targetX)},${m9PageSimulationFormat(targetY)}) " +
            "progress=${m9PageSimulationFormat(progress)} duration=$duration"
        }
        simulationCommitAfterAnim = commit
        simulationAnimator?.cancel()
        simulationAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = pageInterpolator
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                simulationTouchX = startX + (targetX - startX) * fraction
                simulationTouchY = startY + (targetY - startY) * fraction
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false

                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!canceled) {
                        finishSimulationMotion()
                    }
                }
            })
            start()
        }
    }

    private fun finishSimulationMotion() {
        val committedPage = if (simulationCommitAfterAnim) targetPageView.currentPage else null
        logDebug(M9_PAGE_SIMULATION_LOG_TAG) {
            "finishMotion commit=$simulationCommitAfterAnim direction=$dragDirection side=$simulationCurlSide " +
            "targetPage=${committedPage?.globalIndex ?: committedPage?.index}"
        }
        if (simulationCommitAfterAnim) {
            suppressNextSetAnimation = true
            invokeTurnCallback()
            committedPage?.let {
                pageView.setPage(it, null, null)
                onDisplayedPageCommitted?.invoke(it)
            }
        }
        simulationCommitAfterAnim = false
        resetPageLayers()
    }

    private fun startScrollSettle(targetOffset: Float, duration: Long) {
        if (isScrollHorizontalAxis()) {
            scrollLastScrollerX = scrollContentOffset.toInt()
            scrollLastScrollerY = 0
            scrollScroller.startScroll(
                scrollLastScrollerX,
                0,
                (targetOffset - scrollContentOffset).toInt(),
                0,
                duration.toInt()
            )
        } else {
            scrollLastScrollerX = 0
            scrollLastScrollerY = scrollContentOffset.toInt()
            scrollScroller.startScroll(
                0,
                scrollLastScrollerY,
                0,
                (targetOffset - scrollContentOffset).toInt(),
                duration.toInt()
            )
        }
        postInvalidateOnAnimation()
    }

    private fun finishScrollMotion() {
        val committedDelta = scrollAnchorDelta
        val remainder = scrollContentOffset
        if (committedDelta != 0) {
            val committedDirection = if (committedDelta > 0) 1 else -1
            suppressNextSetAnimation = true
            invokeTurnCallback(abs(committedDelta), committedDirection)
            scrollAnchorDelta = 0
            scrollContentOffset = remainder
            scrollStartOffset = remainder
            scrollPageStep = currentScrollExtent()
            if (abs(remainder) > 1f) {
                updateScrollContentContext(remainder)
            } else {
                pageView.clearScrollContext()
            }
        } else if (abs(scrollContentOffset) > 1f) {
            scrollStartOffset = scrollContentOffset
            updateScrollContentContext(scrollContentOffset)
        } else {
            pageView.clearScrollContext()
            scrollContentOffset = 0f
            scrollStartOffset = 0f
        }
        isGestureAnimating = false
        isDraggingPage = false
        dragDirection = 0
        targetPageView.visibility = GONE
    }

    private fun scrollDirectionForOffset(offset: Float): Int {
        if (abs(offset) <= 1f) return 0
        return if (isScrollHorizontalAxis()) {
            if (offset > 0f) 1 else -1
        } else {
            if (offset < 0f) 1 else -1
        }
    }

    private fun scrollOffsetForDirection(direction: Int): Int {
        return if (isScrollHorizontalAxis()) {
            if (direction > 0) 1 else -1
        } else {
            if (direction > 0) -1 else 1
        }
    }

    private fun currentReaderMeanColor(): Int {
        return pageView.solidBackgroundColor ?: Color.TRANSPARENT
    }

    private fun animateDragLayer(
        view: View,
        x: Float,
        y: Float,
        duration: Long,
        endAction: (() -> Unit)? = null
    ) {
        view.animate()
            .translationX(x)
            .translationY(y)
            .rotationY(0f)
            .alpha(1f)
            .setInterpolator(pageInterpolator)
            .setDuration(duration)
            .withEndAction { endAction?.invoke() }
            .start()
    }

    private fun invokeTurnCallback(pageCount: Int = 1, direction: Int = dragDirection) {
        val count = pageCount.coerceAtLeast(1)
        onMovePages?.let { move ->
            move(direction * count)
            return
        }
        repeat(count) {
            if (direction > 0) {
                onNextPage?.invoke()
            } else {
                onPrevPage?.invoke()
            }
        }
    }

    private fun clickScrollDistance(direction: Int): Float {
        val retain = retainedScrollOverlap()
        val fallback = if (isScrollHorizontalAxis()) {
            (width - retain).coerceAtLeast(width * 0.55f)
        } else {
            (height - retain).coerceAtLeast(height * 0.55f)
        }
        return pageView.clickScrollDistance(
            horizontal = isScrollHorizontalAxis(),
            direction = direction,
            fallback = fallback
        )
    }

    private fun currentScrollExtent(): Float {
        return pageView.pageScrollExtent(pageForScrollDelta(scrollAnchorDelta), isScrollHorizontalAxis())
            .coerceAtLeast(1f)
    }

    private fun retainedScrollOverlap(): Float {
        return (textSizePx * if (isScrollHorizontalAxis()) 1.8f else 2.2f)
            .coerceIn(dp(24).toFloat(), if (isScrollHorizontalAxis()) width * 0.28f else height * 0.22f)
    }

    private fun abortGestureAnimation() {
        pageView.animate().cancel()
        targetPageView.animate().cancel()
        simulationAnimator?.cancel()
        simulationAnimator = null
        if (!scrollScroller.isFinished) scrollScroller.abortAnimation()
        isGestureAnimating = false
    }

    private fun resetPageLayers() {
        abortGestureAnimation()
        isDraggingPage = false
        dragDirection = 0
        targetPageView.visibility = GONE
        targetPageView.alpha = 1f
        targetPageView.translationX = 0f
        targetPageView.translationY = 0f
        targetPageView.rotationY = 0f
        pageView.clearScrollContext()
        scrollContentOffset = 0f
        scrollStartOffset = 0f
        scrollPageStep = 1f
        scrollAnchorDelta = 0
        pageView.visibility = VISIBLE
        pageView.alpha = 1f
        pageView.translationX = 0f
        pageView.translationY = 0f
        pageView.rotationY = 0f
    }

    private fun drawSimulationPage(canvas: Canvas) {
        val current = simulationCurrentBitmap
        val target = simulationTargetBitmap
        if (current == null || target == null) {
            super.dispatchDraw(canvas)
            return
        }
        simulationRenderer.draw(
            canvas = canvas,
            current = current,
            target = target,
            touchX = simulationTouchX,
            touchY = simulationTouchY,
            cornerX = simulationCornerX,
            cornerY = simulationCornerY,
            width = width,
            height = height,
            backgroundColor = currentReaderMeanColor(),
            curlCurrentPage = true
        )
    }

    private fun drawOverlayChildren(canvas: Canvas) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child !== pageView && child !== targetPageView && child.visibility == VISIBLE) {
                drawChild(canvas, child, drawingTime)
            }
        }
    }

    private fun View.captureToBitmap(reuse: Bitmap?): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val bitmap = if (reuse != null && !reuse.isRecycled && reuse.width == width && reuse.height == height) {
            reuse.eraseColor(Color.TRANSPARENT)
            reuse
        } else {
            reuse?.recycle()
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        Canvas(bitmap).also { canvas ->
            canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
            draw(canvas)
        }
        bitmap.prepareToDraw()
        return bitmap
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        simulationAnimator?.cancel()
        simulationAnimator = null
        simulationCurrentBitmap?.recycle()
        simulationCurrentBitmap = null
        simulationTargetBitmap?.recycle()
        simulationTargetBitmap = null
    }

    private fun handleTap(event: MotionEvent) {
        if (isTextSelected) {
            clearTextSelection()
            return
        }
        pageView.findImageAt(event.x, event.y)?.let { image ->
            onImageClick?.invoke(image)
            if (onImageClick != null) return
        }
        pageView.findAssistTokenAt(event.x, event.y)?.let { token ->
            toggleAssistOverlay(token)
            return
        }
        if (assistOverlay.visibility == VISIBLE) {
            hideAssistOverlay()
            return
        }
        val regionIndex = tapRegionIndex(event.x, event.y)
        performTapAction(clickRegionActions.getOrElse(regionIndex) { TapAction.MENU })
    }

    private fun handleLongPressSelection() {
        if (isDraggingPage || isGestureAnimating) return
        if (!pageView.beginTextSelectionAt(downX, downY)) return
        setTextSelectionActive(true)
        updateSelectionOverlays()
    }

    private fun copySelectedTextAndClear() {
        val text = pageView.selectedText().trim()
        if (text.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("reader_text", text))
        Toast.makeText(context, context.getString(R.string.reader_selection_copied), Toast.LENGTH_SHORT).show()
        clearTextSelection()
    }

    private fun performSelectionAction(action: SelectionAction) {
        val selection = currentSelectionInfo() ?: return
        when (action) {
            SelectionAction.COPY -> copySelectedTextAndClear()
            SelectionAction.PROCESS_TEXT -> {
                selectionActionMenu.dismiss()
                onSelectionAction?.invoke(action, selection)
            }
            else -> {
                onSelectionAction?.invoke(action, selection)
                clearTextSelection()
            }
        }
    }

    private fun performSelectionProcessText(intent: Intent) {
        val text = pageView.selectedText().trim()
        if (text.isBlank()) return
        onSelectionProcessText?.invoke(intent, text)
        clearTextSelection()
    }

    private fun currentSelectionInfo(): SelectionInfo? {
        val text = pageView.selectedText().trim()
        if (text.isBlank()) return null
        val snapshot = pageView.selectedTextSnapshot()
        return SelectionInfo(
            text = text,
            chapterIndex = snapshot?.chapterIndex,
            chapterRange = snapshot?.chapterRange
        )
    }

    private fun updateSelectionHandle(x: Float, y: Float) {
        val changed = when (activeSelectionHandle) {
            SelectionHandle.START -> pageView.updateSelectionStartAt(x, y)
            SelectionHandle.END -> pageView.updateSelectionEndAt(x, y)
            SelectionHandle.NONE -> false
        }
        if (changed) {
            pageView.consumePendingSelectionHandleRole()?.let { role ->
                activeSelectionHandle = when (role) {
                    ContentTextView.SelectionHandleRole.START -> SelectionHandle.START
                    ContentTextView.SelectionHandleRole.END -> SelectionHandle.END
                }
            }
            updateSelectionOverlays()
        }
    }

    private fun updateSelectionOverlays(): SelectionOverlayFrames? {
        val bounds = pageView.selectionBounds() ?: return null
        currentSelectionInfo()?.let { selection ->
            selectionActionMenu.jumpToCueEnabled = canJumpSelectionToCue?.invoke(selection) == true
        }
        val startHandleFrame = positionHandle(selectionStartHandle, bounds.startRect, start = true)
        val endHandleFrame = positionHandle(selectionEndHandle, bounds.endRect, start = false)
        selectionStartHandle.visibility = VISIBLE
        selectionEndHandle.visibility = VISIBLE
        selectionStartHandle.bringToFront()
        selectionEndHandle.bringToFront()
        return SelectionOverlayFrames(
            startTextRect = bounds.startRect,
            endTextRect = bounds.endRect,
            startLineTop = bounds.startLineTop,
            startHandleFrame = startHandleFrame,
            endHandleFrame = endHandleFrame
        )
    }

    private fun positionHandle(handle: View, rect: RectF, start: Boolean): RectF {
        val params = handle.layoutParams as LayoutParams
        val size = dp(24)
        val sizeFloat = size.toFloat()
        val handleView = handle as? SelectionHandleView
        val (targetX, targetY) = if (layoutMode == M9LayoutMode.VERTICAL) {
            handleView?.mode = if (start) {
                SelectionHandleMode.VERTICAL_START_RTL
            } else {
                SelectionHandleMode.VERTICAL_END_RTL
            }
            val visibleInset = selectionHandleVisibleInset()
            val selectionInset = pageView.selectionBackgroundInsetPx
            val grayLeft = rect.left + selectionInset
            val grayRight = rect.right - selectionInset
            val x = if (start) {
                grayRight - sizeFloat + visibleInset
            } else {
                grayLeft - visibleInset
            }
            val y = if (start) rect.top - visibleInset else rect.bottom - visibleInset
            x to y
        } else {
            handleView?.mode = if (start) {
                SelectionHandleMode.HORIZONTAL_START
            } else {
                SelectionHandleMode.HORIZONTAL_END
            }
            val x = if (start) rect.left - sizeFloat else rect.right
            val y = rect.bottom
            x to y
        }
        val left = targetX.toInt().coerceIn(0, (width - size).coerceAtLeast(0))
        val top = targetY.toInt().coerceIn(0, (height - size).coerceAtLeast(0))
        params.leftMargin = left
        params.topMargin = top
        handle.layoutParams = params
        return RectF(left.toFloat(), top.toFloat(), (left + size).toFloat(), (top + size).toFloat())
    }

    private fun showSelectionMenu() {
        val frames = updateSelectionOverlays() ?: return
        val anchors = selectionMenuAnchors(frames)
        val startVisible = visibleSelectionHandleFrame(frames.startHandleFrame)
        val endVisible = visibleSelectionHandleFrame(frames.endHandleFrame)
        logDebug(M9_SELECTION_LOG_TAG) {
            "showSelectionMenu layout=$layoutMode " +
            "startText=${formatRect(frames.startTextRect)} " +
            "startHandle=${formatRect(frames.startHandleFrame)} " +
            "startVisible=${formatRect(startVisible)} " +
            "endText=${formatRect(frames.endTextRect)} " +
            "endHandle=${formatRect(frames.endHandleFrame)} " +
            "endVisible=${formatRect(endVisible)} " +
            "anchors=$anchors " +
            "view=${width}x$height"
        }
        selectionActionMenu.show(
            anchor = this,
            startX = anchors.startX,
            startTopY = anchors.startTopY,
            startBottomY = anchors.startBottomY,
            endX = anchors.endX,
            endBottomY = anchors.endBottomY
        )
    }

    private fun selectionMenuAnchors(frames: SelectionOverlayFrames): SelectionMenuAnchors {
        val startVisible = visibleSelectionHandleFrame(frames.startHandleFrame)
        val endVisible = visibleSelectionHandleFrame(frames.endHandleFrame)
        return if (layoutMode == M9LayoutMode.HORIZONTAL) {
            SelectionMenuAnchors(
                startX = frames.startTextRect.left.toInt(),
                startTopY = frames.startLineTop.toInt(),
                startBottomY = frames.startHandleFrame.bottom.toInt(),
                endX = frames.endHandleFrame.left.toInt(),
                endBottomY = frames.endHandleFrame.bottom.toInt()
            )
        } else {
            SelectionMenuAnchors(
                startX = startVisible.centerX().toInt(),
                startTopY = startVisible.top.toInt(),
                startBottomY = startVisible.bottom.toInt(),
                endX = endVisible.centerX().toInt(),
                endBottomY = endVisible.bottom.toInt()
            )
        }
    }

    private fun visibleSelectionHandleFrame(frame: RectF): RectF {
        val inset = selectionHandleVisibleInset()
        return RectF(
            frame.left + inset,
            frame.top + inset,
            frame.right - inset,
            frame.bottom - inset
        )
    }

    private fun selectionHandleVisibleInset(): Float = dp(4).toFloat()

    private fun formatRect(rect: RectF): String {
        return "[${rect.left.toInt()},${rect.top.toInt()}-${rect.right.toInt()},${rect.bottom.toInt()}]"
    }

    private data class SelectionOverlayFrames(
        val startTextRect: RectF,
        val endTextRect: RectF,
        val startLineTop: Float,
        val startHandleFrame: RectF,
        val endHandleFrame: RectF
    )

    private data class SelectionMenuAnchors(
        val startX: Int,
        val startTopY: Int,
        val startBottomY: Int,
        val endX: Int,
        val endBottomY: Int
    )

    private fun hideSelectionMenu() {
        selectionActionMenu.dismiss()
    }

    private fun clearTextSelection() {
        if (!isTextSelected) return
        pageView.clearTextSelection()
        setTextSelectionActive(false)
        activeSelectionHandle = SelectionHandle.NONE
        selectionActionMenu.dismiss()
        selectionStartHandle.visibility = GONE
        selectionEndHandle.visibility = GONE
    }

    private fun setTextSelectionActive(active: Boolean) {
        if (isTextSelected == active) return
        isTextSelected = active
        onTextSelectionStateChanged?.invoke(active)
    }

    private fun selectionHandleAt(x: Float, y: Float): SelectionHandle {
        if (!isTextSelected) return SelectionHandle.NONE
        val touch = dp(28).toFloat()
        fun hit(view: View): Boolean {
            if (view.visibility != VISIBLE) return false
            val cx = view.left + view.width / 2f
            val cy = view.top + view.height / 2f
            return abs(x - cx) <= touch && abs(y - cy) <= touch
        }
        return when {
            hit(selectionStartHandle) -> SelectionHandle.START
            hit(selectionEndHandle) -> SelectionHandle.END
            else -> SelectionHandle.NONE
        }
    }

    private fun turnPageByTap(direction: Int) {
        if (isGestureAnimating || width <= 0 || height <= 0) return
        val preview = onPagePreview?.invoke(direction)
        if (preview == null) {
            invokeTurnCallback(direction = direction)
            return
        }
        abortGestureAnimation()
        beginDrag(direction, preview)
        if (pageAnim == M9PageAnim.SCROLL) {
            isGestureAnimating = true
            startScrollSettle(
                scrollContentOffset + scrollOffsetForDirection(direction) * clickScrollDistance(direction),
                if (noAnimScrollPage) 1L else PROGRAMMATIC_PAGE_ANIM_MS
            )
            return
        }
        finishDrag(commit = true)
    }

    private fun tapRegionIndex(x: Float, y: Float): Int {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val column = (x / (safeWidth / 3f)).toInt().coerceIn(0, 2)
        val row = (y / (safeHeight / 3f)).toInt().coerceIn(0, 2)
        return row * 3 + column
    }

    private fun performTapAction(action: TapAction) {
        when (action) {
            TapAction.NONE -> Unit
            TapAction.MENU -> onMenu?.invoke()
            TapAction.NEXT_PAGE -> turnPageByTap(1)
            TapAction.PREV_PAGE -> turnPageByTap(-1)
            else -> onTapAction?.invoke(action)
        }
    }

    enum class TapAction {
        NONE,
        MENU,
        NEXT_PAGE,
        PREV_PAGE,
        NEXT_CHAPTER,
        PREV_CHAPTER,
        PREV_AUDIO_CUE,
        NEXT_AUDIO_CUE,
        ADD_BOOKMARK,
        TOGGLE_CONVERT,
        CATALOG,
        SEARCH,
        TOGGLE_REPEAT
    }

    enum class SelectionAction {
        PROCESS_TEXT,
        COPY,
        SHARE,
        SEARCH,
        ADD_BOOKMARK,
        JUMP_TO_CUE,
        BROWSER
    }

    private enum class SelectionHandle {
        NONE,
        START,
        END
    }

    private enum class SelectionHandleMode {
        HORIZONTAL_START,
        HORIZONTAL_END,
        VERTICAL_START_RTL,
        VERTICAL_END_RTL
    }

    private class SelectionHandleView(context: Context) : View(context) {
        var mode: SelectionHandleMode = SelectionHandleMode.HORIZONTAL_START
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF858585.toInt()
            style = Paint.Style.FILL
        }
        private val stemRect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = minOf(width, height).toFloat() / 24f
            val radius = 8f * scale
            when (mode) {
                SelectionHandleMode.HORIZONTAL_START,
                SelectionHandleMode.VERTICAL_START_RTL -> drawHandle(
                    canvas = canvas,
                    cx = 12f * scale,
                    cy = 12f * scale,
                    radius = radius,
                    tabLeft = 12f * scale,
                    tabTop = 4f * scale,
                    tabRight = 20f * scale,
                    tabBottom = 12f * scale
                )
                SelectionHandleMode.HORIZONTAL_END,
                SelectionHandleMode.VERTICAL_END_RTL -> drawHandle(
                    canvas = canvas,
                    cx = 12f * scale,
                    cy = 12f * scale,
                    radius = radius,
                    tabLeft = 4f * scale,
                    tabTop = 4f * scale,
                    tabRight = 12f * scale,
                    tabBottom = 12f * scale
                )
            }
        }

        private fun drawHandle(
            canvas: Canvas,
            cx: Float,
            cy: Float,
            radius: Float,
            tabLeft: Float,
            tabTop: Float,
            tabRight: Float,
            tabBottom: Float
        ) {
            stemRect.set(tabLeft, tabTop, tabRight, tabBottom)
            canvas.drawRect(stemRect, fillPaint)
            canvas.drawCircle(cx, cy, radius, fillPaint)
        }

    }

    private fun toggleAssistOverlay(token: ContentTextView.AssistToken) {
        crossPageCuePageOverlay.visibility = GONE
        val current = assistToken
        if (current != null &&
            current.hitSourceStart == token.hitSourceStart &&
            current.sourceStart == token.sourceStart &&
            current.sourceEnd == token.sourceEnd &&
            current.text == token.text
        ) {
            hideAssistOverlay()
            return
        }
        assistToken = token
        assistOverlay.text = token.text
        updateAssistOverlayStyle()
        assistOverlay.post {
            assistOverlay.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
            )
            val target = computeOverlayBounds(
                anchor = token.rect,
                overlayWidth = assistOverlay.measuredWidth,
                overlayHeight = assistOverlay.measuredHeight
            )
            val params = assistOverlay.layoutParams as LayoutParams
            params.leftMargin = target.left.toInt()
            params.topMargin = target.top.toInt()
            assistOverlay.layoutParams = params
            assistOverlay.visibility = VISIBLE
            assistOverlay.bringToFront()
        }
    }

    private fun hideAssistOverlay() {
        assistToken = null
        assistOverlay.visibility = GONE
        crossPageCuePageOverlay.visibility = GONE
    }

    private fun computeOverlayBounds(anchor: RectF, overlayWidth: Int, overlayHeight: Int): RectF {
        val margin = dp(12).toFloat()
        val maxLeft = (width - overlayWidth - margin).coerceAtLeast(margin)
        val maxTop = (height - overlayHeight - margin).coerceAtLeast(margin)
        val left = when {
            anchor.left - overlayWidth - margin >= margin -> anchor.left - overlayWidth - margin
            anchor.right + overlayWidth + margin <= width - margin -> anchor.right + margin
            else -> (anchor.centerX() - overlayWidth / 2f).coerceIn(margin, maxLeft)
        }
        val top = (anchor.centerY() - overlayHeight / 2f).coerceIn(margin, maxTop)
        return RectF(left, top, left + overlayWidth, top + overlayHeight)
    }

    private fun computeCrossPageOverlayBounds(
        anchor: RectF,
        overlayWidth: Int,
        overlayHeight: Int,
        vertical: Boolean
    ): RectF {
        val content = readableContentBounds()
        val targetWidth = overlayWidth.toFloat().coerceAtMost(content.width().coerceAtLeast(1f))
        val targetHeight = overlayHeight.toFloat().coerceAtMost(content.height().coerceAtLeast(1f))
        val left: Float
        val top: Float
        if (vertical) {
            val right = anchor.right.coerceIn(content.left + targetWidth, content.right)
            left = (right - targetWidth).coerceIn(content.left, (content.right - targetWidth).coerceAtLeast(content.left))
            top = clampCoveringAnchor(
                preferredStart = anchor.top,
                targetSize = targetHeight,
                minStart = content.top,
                maxEnd = content.bottom,
                anchorStart = anchor.top,
                anchorEnd = anchor.bottom
            )
        } else {
            left = clampCoveringAnchor(
                preferredStart = anchor.left,
                targetSize = targetWidth,
                minStart = content.left,
                maxEnd = content.right,
                anchorStart = anchor.left,
                anchorEnd = anchor.right
            )
            top = clampCoveringAnchor(
                preferredStart = anchor.top,
                targetSize = targetHeight,
                minStart = content.top,
                maxEnd = content.bottom,
                anchorStart = anchor.top,
                anchorEnd = anchor.bottom
            )
        }
        return RectF(left, top, left + targetWidth, top + targetHeight)
    }

    private fun clampCoveringAnchor(
        preferredStart: Float,
        targetSize: Float,
        minStart: Float,
        maxEnd: Float,
        anchorStart: Float,
        anchorEnd: Float
    ): Float {
        val maxStart = (maxEnd - targetSize).coerceAtLeast(minStart)
        var start = preferredStart.coerceIn(minStart, maxStart)
        if (anchorEnd > start + targetSize) {
            start = (anchorEnd - targetSize).coerceIn(minStart, maxStart)
        }
        if (anchorStart < start) {
            start = anchorStart.coerceIn(minStart, maxStart)
        }
        return start
    }

    private fun readableContentBounds(): RectF {
        val content = pageView.contentView
        return RectF(
            content.left + content.paddingLeft.toFloat(),
            content.top + content.paddingTop.toFloat(),
            content.right - content.paddingRight.toFloat(),
            content.bottom - content.paddingBottom.toFloat()
        )
    }

    private fun updateAssistOverlayStyle() {
        assistOverlay.setTextColor(overlayTextColor)
        assistOverlay.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(overlayBgColor)
            setStroke(dp(1), if (isDarkColor(overlayBgColor)) 0x33FFFFFF else 0x22000000)
        }
    }

    internal fun showCrossPageCuePageOverlay(page: TextPage, range: IntRange?, fullPage: Boolean = false) {
        assistToken = null
        assistOverlay.visibility = GONE
        updateCrossPageCuePageOverlayStyle(fullPage = fullPage)
        // 整页模式：range 作为最后一句的播放高亮范围（与正常页面的 cue 高亮一致）；
        // 小窗模式：range 仅用于定位锚点。
        crossPageCuePageOverlay.setPage(page, if (fullPage) range else null, null)
        crossPageCuePageOverlay.post {
            val params = crossPageCuePageOverlay.layoutParams as LayoutParams
            if (fullPage) {
                // 临时页：像正常页面一样铺满正文区（页眉页脚之外），无边框
                val content = pageView.contentView
                params.width = content.width
                params.height = content.height
                params.leftMargin = pageView.left + content.left
                params.topMargin = pageView.top + content.top
            } else {
                val overlayWidth = page.width.toInt().coerceAtLeast(dp(48)) + dp(16)
                val overlayHeight = page.height.toInt().coerceAtLeast(dp(48)) + dp(16)
                val anchor = range?.let { pageView.rangeBounds(it) }
                    ?: RectF(width / 2f, height / 2f, width / 2f, height / 2f)
                val target = computeCrossPageOverlayBounds(
                    anchor = anchor,
                    overlayWidth = overlayWidth,
                    overlayHeight = overlayHeight,
                    vertical = true
                )
                params.width = target.width().toInt()
                params.height = target.height().toInt()
                params.leftMargin = target.left.toInt()
                params.topMargin = target.top.toInt()
            }
            crossPageCuePageOverlay.layoutParams = params
            // 淡入显示（隐藏路径在 hideCrossPageCueOverlay 中淡出）。
            // 已显示时直接换内容，避免句子切换时反复闪烁。
            val wasVisible = crossPageCuePageOverlay.visibility == VISIBLE
            if (!wasVisible) {
                crossPageCuePageOverlay.animate().cancel()
                crossPageCuePageOverlay.alpha = 0f
                crossPageCuePageOverlay.visibility = VISIBLE
                crossPageCuePageOverlay.bringToFront()
                crossPageCuePageOverlay.animate()
                    .alpha(1f)
                    .setDuration(CROSS_PAGE_OVERLAY_FADE_MS)
                    .start()
            }
        }
    }

    private fun updateCrossPageCuePageOverlayStyle(fullPage: Boolean = false) {
        if (fullPage) {
            // 临时页：使用阅读器页面背景色（setReaderColors 会设置纯色背景），无边框圆角；
            // 内容边距与正常页面完全一致（PageView 的 contentView 为 0/18/0/18），
            // 保证临时页文字位置与本页完全重合，不会右移或被边距压缩。
            crossPageCuePageOverlay.setReaderColors(
                pageView.solidBackgroundColor ?: overlayBgColor,
                overlayTextColor,
                overlayTextColor
            )
            crossPageCuePageOverlay.contentView.setPadding(0, dp(18), 0, dp(18))
            return
        }
        crossPageCuePageOverlay.setReaderColors(overlayBgColor, overlayTextColor, overlayTextColor)
        crossPageCuePageOverlay.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(overlayBgColor)
            setStroke(dp(1), if (isDarkColor(overlayBgColor)) 0x33FFFFFF else 0x22000000)
        }
        crossPageCuePageOverlay.contentView.setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    private fun isDarkColor(color: Int): Boolean {
        val darkness = 1.0 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
        return darkness >= 0.45
    }

    private inline fun forEachPageView(block: (PageView) -> Unit) {
        block(pageView)
        block(targetPageView)
    }

    private fun horizontalDragDirection(dx: Float): Int {
        return if (layoutMode == M9LayoutMode.VERTICAL) {
            if (dx > 0f) 1 else -1
        } else {
            if (dx < 0f) 1 else -1
        }
    }

    private fun horizontalPageSide(direction: Int): Int {
        return if (layoutMode == M9LayoutMode.VERTICAL) {
            -direction
        } else {
            direction
        }
    }

    private fun isScrollHorizontalAxis(): Boolean {
        return pageAnim == M9PageAnim.SCROLL && layoutMode == M9LayoutMode.VERTICAL
    }

    private fun updateScrollContentContext(offset: Float) {
        val normalizedOffset = normalizeScrollOffset(offset)
        scrollContentOffset = normalizedOffset
        val radius = SCROLL_CONTEXT_RADIUS
        val pages = (-radius..radius).map { delta ->
            pageForScrollDelta(scrollAnchorDelta + delta)
        }
        pageView.translationX = 0f
        pageView.translationY = 0f
        targetPageView.visibility = GONE
        pageView.setScrollContext(
            pages = pages,
            centerIndex = radius,
            offset = normalizedOffset,
            horizontal = isScrollHorizontalAxis(),
            reverse = isScrollHorizontalAxis()
        )
    }

    private fun normalizeScrollOffset(rawOffset: Float): Float {
        var offset = rawOffset
        var step = currentScrollExtent()
        scrollPageStep = step
        while (abs(offset) >= step) {
            val direction = scrollDirectionForOffset(offset)
            if (direction == 0) return 0f
            val nextAnchor = scrollAnchorDelta + direction
            val offsetSign = scrollOffsetForDirection(direction)
            if (pageForScrollDelta(nextAnchor) == null) {
                if (!scrollScroller.isFinished) {
                    scrollScroller.abortAnimation()
                }
                return 0f
            }
            scrollAnchorDelta = nextAnchor
            offset -= offsetSign * step
            step = currentScrollExtent()
            scrollPageStep = step
        }
        return offset
    }

    private fun pageForScrollDelta(delta: Int): TextPage? {
        return if (delta == 0) pageView.currentPage else onPagePreview?.invoke(delta)
    }

    private fun dp(value: Int): Int = value.dp(context)

    private class SimulationCurlRenderer {
        private val path0 = Path()
        private val path1 = Path()
        private val bezierStart1 = PointF()
        private val bezierControl1 = PointF()
        private val bezierVertex1 = PointF()
        private var bezierEnd1 = PointF()
        private val bezierStart2 = PointF()
        private val bezierControl2 = PointF()
        private val bezierVertex2 = PointF()
        private var bezierEnd2 = PointF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.TRANSPARENT
        }
        private val matrix = Matrix()
        private val matrixArray = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)
        private val frontShadowVlr = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x80FFFFFF.toInt(), 0x111111)
        )
        private val frontShadowVrl = GradientDrawable(
            GradientDrawable.Orientation.RIGHT_LEFT,
            intArrayOf(0x80FFFFFF.toInt(), 0x111111)
        )
        private val frontShadowHtb = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x80FFFFFF.toInt(), 0x111111)
        )
        private val frontShadowHbt = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0x80FFFFFF.toInt(), 0x111111)
        )
        private val backShadowLr = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x11FFFFFF, 0x66000000)
        )
        private val backShadowRl = GradientDrawable(
            GradientDrawable.Orientation.RIGHT_LEFT,
            intArrayOf(0x11FFFFFF, 0x66000000)
        )
        private val foldShadowLr = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x44333333, 0x00CCCCCC)
        )
        private val foldShadowRl = GradientDrawable(
            GradientDrawable.Orientation.RIGHT_LEFT,
            intArrayOf(0x44333333, 0x00CCCCCC)
        )
        private var touchX = 0.1f
        private var touchY = 0.1f
        private var cornerX = 0
        private var cornerY = 0
        private var viewWidth = 0
        private var viewHeight = 0
        private var middleX = 0f
        private var middleY = 0f
        private var degrees = 0f
        private var touchToCornerDistance = 0f
        private var maxLength = 1f
        private var isRtOrLb = false

        fun draw(
            canvas: Canvas,
            current: Bitmap,
            target: Bitmap,
            touchX: Float,
            touchY: Float,
            cornerX: Int,
            cornerY: Int,
            width: Int,
            height: Int,
            backgroundColor: Int,
            curlCurrentPage: Boolean
        ) {
            if (width <= 0 || height <= 0) return
            val leftCurl = cornerX == 0
            this.viewWidth = width
            this.viewHeight = height
            this.cornerX = if (leftCurl) width else cornerX
            this.cornerY = cornerY
            this.touchX = (if (leftCurl) width - touchX else touchX)
                .coerceIn(-width.toFloat(), width * 2f)
            this.touchY = touchY.coerceIn(0.1f, (height - 0.1f).coerceAtLeast(0.1f))
            this.maxLength = hypot(width.toDouble(), height.toDouble()).toFloat()
            this.isRtOrLb = (this.cornerX == 0 && cornerY == height) || (cornerY == 0 && this.cornerX == width)

            calcPoints()
            if (leftCurl) {
                mirrorCalculatedGeometry()
                this.cornerX = 0
                this.touchX = touchX.coerceIn(-width.toFloat(), width * 2f)
                this.isRtOrLb = (cornerY == height)
            }
            logDebug(M9_PAGE_SIMULATION_LOG_TAG) {
                "geometry left=$leftCurl corner=($cornerX,$cornerY) " +
                "touch=(${m9PageSimulationFormat(this.touchX)},${m9PageSimulationFormat(this.touchY)}) " +
                "s1=(${m9PageSimulationFormat(bezierStart1.x)},${m9PageSimulationFormat(bezierStart1.y)}) " +
                "s2=(${m9PageSimulationFormat(bezierStart2.x)},${m9PageSimulationFormat(bezierStart2.y)}) " +
                "c1=(${m9PageSimulationFormat(bezierControl1.x)},${m9PageSimulationFormat(bezierControl1.y)}) " +
                "c2=(${m9PageSimulationFormat(bezierControl2.x)},${m9PageSimulationFormat(bezierControl2.y)}) " +
                "curlCurrentPage=$curlCurrentPage"
            }
            canvas.save()
            val curlBitmap = if (curlCurrentPage) current else target
            val baseBitmap = if (curlCurrentPage) target else current
            drawCurrentPageArea(canvas, curlBitmap)
            drawTargetPageAreaAndShadow(canvas, baseBitmap)
            drawCurrentPageShadow(canvas)
            drawCurrentBackArea(canvas, curlBitmap, backgroundColor)
            canvas.restore()
        }

        private fun mirrorCalculatedGeometry() {
            fun mirror(point: PointF) {
                point.x = viewWidth - point.x
            }
            mirror(bezierStart1)
            mirror(bezierControl1)
            mirror(bezierVertex1)
            mirror(bezierEnd1)
            mirror(bezierStart2)
            mirror(bezierControl2)
            mirror(bezierVertex2)
            mirror(bezierEnd2)
            middleX = viewWidth - middleX
        }

        private fun drawCurrentPageArea(canvas: Canvas, bitmap: Bitmap) {
            path0.reset()
            path0.moveTo(bezierStart1.x, bezierStart1.y)
            path0.quadTo(bezierControl1.x, bezierControl1.y, bezierEnd1.x, bezierEnd1.y)
            path0.lineTo(touchX, touchY)
            path0.lineTo(bezierEnd2.x, bezierEnd2.y)
            path0.quadTo(bezierControl2.x, bezierControl2.y, bezierStart2.x, bezierStart2.y)
            path0.lineTo(cornerX.toFloat(), cornerY.toFloat())
            path0.close()

            canvas.save()
            canvas.clipOutPath(path0)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            canvas.restore()
        }

        private fun drawTargetPageAreaAndShadow(canvas: Canvas, bitmap: Bitmap) {
            path1.reset()
            path1.moveTo(bezierStart1.x, bezierStart1.y)
            path1.lineTo(bezierVertex1.x, bezierVertex1.y)
            path1.lineTo(bezierVertex2.x, bezierVertex2.y)
            path1.lineTo(bezierStart2.x, bezierStart2.y)
            path1.lineTo(cornerX.toFloat(), cornerY.toFloat())
            path1.close()

            degrees = Math.toDegrees(
                atan2(
                    (bezierControl1.x - cornerX).toDouble(),
                    bezierControl2.y - cornerY.toDouble()
                )
            ).toFloat()
            val leftX: Int
            val rightX: Int
            val shadow: GradientDrawable
            if (isRtOrLb) {
                leftX = bezierStart1.x.toInt()
                rightX = (bezierStart1.x + touchToCornerDistance / 4).toInt()
                shadow = backShadowLr
            } else {
                leftX = (bezierStart1.x - touchToCornerDistance / 4).toInt()
                rightX = bezierStart1.x.toInt()
                shadow = backShadowRl
            }

            canvas.save()
            canvas.clipPath(path0)
            @Suppress("DEPRECATION")
            canvas.clipPath(path1, android.graphics.Region.Op.INTERSECT)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            canvas.rotate(degrees, bezierStart1.x, bezierStart1.y)
            shadow.setBounds(leftX, bezierStart1.y.toInt(), rightX, (maxLength + bezierStart1.y).toInt())
            shadow.draw(canvas)
            canvas.restore()
        }

        private fun drawCurrentPageShadow(canvas: Canvas) {
            val degree = if (isRtOrLb) {
                Math.PI / 4 - atan2(bezierControl1.y - touchY, touchX - bezierControl1.x)
            } else {
                Math.PI / 4 - atan2(touchY - bezierControl1.y, touchX - bezierControl1.x)
            }
            val d1 = (25f * 1.414f * cos(degree)).toFloat()
            val d2 = (25f * 1.414f * sin(degree)).toFloat()
            val x = touchX + d1
            val y = if (isRtOrLb) touchY + d2 else touchY - d2

            path1.reset()
            path1.moveTo(x, y)
            path1.lineTo(touchX, touchY)
            path1.lineTo(bezierControl1.x, bezierControl1.y)
            path1.lineTo(bezierStart1.x, bezierStart1.y)
            path1.close()
            canvas.save()
            canvas.clipOutPath(path0)
            @Suppress("DEPRECATION")
            canvas.clipPath(path1, android.graphics.Region.Op.INTERSECT)
            val verticalShadow = if (isRtOrLb) frontShadowVlr else frontShadowVrl
            val left = if (isRtOrLb) bezierControl1.x.toInt() else (bezierControl1.x - 25).toInt()
            val right = if (isRtOrLb) (bezierControl1.x + 25).toInt() else (bezierControl1.x + 1).toInt()
            val rotateDegrees = Math.toDegrees(
                atan2(touchX - bezierControl1.x, bezierControl1.y - touchY).toDouble()
            ).toFloat()
            canvas.rotate(rotateDegrees, bezierControl1.x, bezierControl1.y)
            verticalShadow.setBounds(left, (bezierControl1.y - maxLength).toInt(), right, bezierControl1.y.toInt())
            verticalShadow.draw(canvas)
            canvas.restore()

            path1.reset()
            path1.moveTo(x, y)
            path1.lineTo(touchX, touchY)
            path1.lineTo(bezierControl2.x, bezierControl2.y)
            path1.lineTo(bezierStart2.x, bezierStart2.y)
            path1.close()
            canvas.save()
            canvas.clipOutPath(path0)
            canvas.clipPath(path1)
            val horizontalShadow = if (isRtOrLb) frontShadowHtb else frontShadowHbt
            val top = if (isRtOrLb) bezierControl2.y.toInt() else (bezierControl2.y - 25).toInt()
            val bottom = if (isRtOrLb) (bezierControl2.y + 25).toInt() else (bezierControl2.y + 1).toInt()
            val rotateDegrees2 = Math.toDegrees(
                atan2(bezierControl2.y - touchY, bezierControl2.x - touchX).toDouble()
            ).toFloat()
            canvas.rotate(rotateDegrees2, bezierControl2.x, bezierControl2.y)
            val temp = if (bezierControl2.y < 0) bezierControl2.y - viewHeight else bezierControl2.y
            val hmg = hypot(bezierControl2.x.toDouble(), temp.toDouble())
            if (hmg > maxLength) {
                horizontalShadow.setBounds(
                    (bezierControl2.x - 25 - hmg).toInt(),
                    top,
                    (bezierControl2.x + maxLength - hmg).toInt(),
                    bottom
                )
            } else {
                horizontalShadow.setBounds((bezierControl2.x - maxLength).toInt(), top, bezierControl2.x.toInt(), bottom)
            }
            horizontalShadow.draw(canvas)
            canvas.restore()
        }

        private fun drawCurrentBackArea(canvas: Canvas, bitmap: Bitmap, backgroundColor: Int) {
            val i = ((bezierStart1.x + bezierControl1.x) / 2).toInt()
            val f1 = abs(i - bezierControl1.x)
            val i1 = ((bezierStart2.y + bezierControl2.y) / 2).toInt()
            val f2 = abs(i1 - bezierControl2.y)
            val f3 = min(f1, f2)
            path1.reset()
            path1.moveTo(bezierVertex2.x, bezierVertex2.y)
            path1.lineTo(bezierVertex1.x, bezierVertex1.y)
            path1.lineTo(bezierEnd1.x, bezierEnd1.y)
            path1.lineTo(touchX, touchY)
            path1.lineTo(bezierEnd2.x, bezierEnd2.y)
            path1.close()

            val left: Int
            val right: Int
            val foldShadow: GradientDrawable
            if (isRtOrLb) {
                left = (bezierStart1.x - 1).toInt()
                right = (bezierStart1.x + f3 + 1).toInt()
                foldShadow = foldShadowLr
            } else {
                left = (bezierStart1.x - f3 - 1).toInt()
                right = (bezierStart1.x + 1).toInt()
                foldShadow = foldShadowRl
            }

            canvas.save()
            canvas.clipPath(path0)
            @Suppress("DEPRECATION")
            canvas.clipPath(path1, android.graphics.Region.Op.INTERSECT)
            val dis = hypot(
                cornerX - bezierControl1.x.toDouble(),
                bezierControl2.y - cornerY.toDouble()
            ).toFloat().coerceAtLeast(0.1f)
            val f8 = (cornerX - bezierControl1.x) / dis
            val f9 = (bezierControl2.y - cornerY) / dis
            matrixArray[0] = 1 - 2 * f9 * f9
            matrixArray[1] = 2 * f8 * f9
            matrixArray[3] = matrixArray[1]
            matrixArray[4] = 1 - 2 * f8 * f8
            matrix.reset()
            matrix.setValues(matrixArray)
            matrix.preTranslate(-bezierControl1.x, -bezierControl1.y)
            matrix.postTranslate(bezierControl1.x, bezierControl1.y)
            canvas.drawColor(backgroundColor)
            paint.alpha = 150
            canvas.drawBitmap(bitmap, matrix, paint)
            paint.alpha = 255
            canvas.rotate(degrees, bezierStart1.x, bezierStart1.y)
            foldShadow.setBounds(left, bezierStart1.y.toInt(), right, (bezierStart1.y + maxLength).toInt())
            foldShadow.draw(canvas)
            canvas.restore()
        }

        private fun calcPoints() {
            middleX = (touchX + cornerX) / 2
            middleY = (touchY + cornerY) / 2
            val control1Denominator = (cornerX - middleX).let {
                if (abs(it) < 0.1f) if (it < 0f) -0.1f else 0.1f else it
            }
            bezierControl1.x = middleX - (cornerY - middleY) * (cornerY - middleY) / control1Denominator
            bezierControl1.y = cornerY.toFloat()
            bezierControl2.x = cornerX.toFloat()
            val control2Denominator = (cornerY - middleY).let {
                if (abs(it) < 0.1f) if (it < 0f) -0.1f else 0.1f else it
            }
            bezierControl2.y = middleY - (cornerX - middleX) * (cornerX - middleX) / control2Denominator
            bezierStart1.x = bezierControl1.x - (cornerX - bezierControl1.x) / 2
            bezierStart1.y = cornerY.toFloat()

            if (touchX > 0 && touchX < viewWidth && (bezierStart1.x < 0 || bezierStart1.x > viewWidth)) {
                if (bezierStart1.x < 0) {
                    bezierStart1.x = viewWidth - bezierStart1.x
                }
                val f1 = abs(cornerX - touchX).coerceAtLeast(0.1f)
                val f2 = viewWidth * f1 / bezierStart1.x.coerceAtLeast(0.1f)
                touchX = abs(cornerX - f2)
                val f3 = abs(cornerX - touchX) * abs(cornerY - touchY) / f1
                touchY = abs(cornerY - f3).coerceIn(0.1f, (viewHeight - 0.1f).coerceAtLeast(0.1f))
                middleX = (touchX + cornerX) / 2
                middleY = (touchY + cornerY) / 2
                val d1 = (cornerX - middleX).let {
                    if (abs(it) < 0.1f) if (it < 0f) -0.1f else 0.1f else it
                }
                val d2 = (cornerY - middleY).let {
                    if (abs(it) < 0.1f) if (it < 0f) -0.1f else 0.1f else it
                }
                bezierControl1.x = middleX - (cornerY - middleY) * (cornerY - middleY) / d1
                bezierControl1.y = cornerY.toFloat()
                bezierControl2.x = cornerX.toFloat()
                bezierControl2.y = middleY - (cornerX - middleX) * (cornerX - middleX) / d2
                bezierStart1.x = bezierControl1.x - (cornerX - bezierControl1.x) / 2
            }

            bezierStart2.x = cornerX.toFloat()
            bezierStart2.y = bezierControl2.y - (cornerY - bezierControl2.y) / 2
            touchToCornerDistance = hypot((touchX - cornerX).toDouble(), (touchY - cornerY).toDouble()).toFloat()
            bezierEnd1 = getCross(PointF(touchX, touchY), bezierControl1, bezierStart1, bezierStart2)
            bezierEnd2 = getCross(PointF(touchX, touchY), bezierControl2, bezierStart1, bezierStart2)
            bezierVertex1.x = (bezierStart1.x + 2 * bezierControl1.x + bezierEnd1.x) / 4
            bezierVertex1.y = (2 * bezierControl1.y + bezierStart1.y + bezierEnd1.y) / 4
            bezierVertex2.x = (bezierStart2.x + 2 * bezierControl2.x + bezierEnd2.x) / 4
            bezierVertex2.y = (2 * bezierControl2.y + bezierStart2.y + bezierEnd2.y) / 4
        }

        private fun getCross(p1: PointF, p2: PointF, p3: PointF, p4: PointF): PointF {
            val a1 = (p2.y - p1.y) / (p2.x - p1.x).let {
                if (abs(it) < 0.1f) if (it < 0f) -0.1f else 0.1f else it
            }
            val b1 = (p1.x * p2.y - p2.x * p1.y) / (p1.x - p2.x).let {
                if (abs(it) < 0.1f) if (it < 0f) -0.1f else 0.1f else it
            }
            val a2 = (p4.y - p3.y) / (p4.x - p3.x).let {
                if (abs(it) < 0.1f) if (it < 0f) -0.1f else 0.1f else it
            }
            val b2 = (p3.x * p4.y - p4.x * p3.y) / (p3.x - p4.x).let {
                if (abs(it) < 0.1f) if (it < 0f) -0.1f else 0.1f else it
            }
            val denominator = (a1 - a2).let {
                if (abs(it) < 0.1f) if (it < 0f) -0.1f else 0.1f else it
            }
            val x = (b2 - b1) / denominator
            return PointF(x, a1 * x + b1)
        }
    }

    companion object {
        const val CLICK_REGION_COUNT: Int = 9
        const val DEFAULT_SELECTION_PRIMARY_ACTION_KEY: String = "default"
        private const val MIN_FLING_VELOCITY = 900f
        private const val PAGE_DRAG_ANIM_MS = 260L
        private const val PROGRAMMATIC_PAGE_ANIM_MS = 220L
        private const val SCROLL_CONTEXT_RADIUS = 4

        fun defaultClickRegionActions(layoutMode: M9LayoutMode = M9LayoutMode.HORIZONTAL): List<TapAction> {
            return if (layoutMode == M9LayoutMode.VERTICAL) {
                listOf(
                    TapAction.NEXT_PAGE,
                    TapAction.NEXT_PAGE,
                    TapAction.PREV_PAGE,
                    TapAction.NEXT_PAGE,
                    TapAction.MENU,
                    TapAction.PREV_PAGE,
                    TapAction.NEXT_PAGE,
                    TapAction.PREV_PAGE,
                    TapAction.PREV_PAGE
                )
            } else {
                listOf(
                    TapAction.PREV_PAGE,
                    TapAction.PREV_PAGE,
                    TapAction.NEXT_PAGE,
                    TapAction.PREV_PAGE,
                    TapAction.MENU,
                    TapAction.NEXT_PAGE,
                    TapAction.PREV_PAGE,
                    TapAction.NEXT_PAGE,
                    TapAction.NEXT_PAGE
                )
            }
        }
    }

    private class SelectionActionMenu(
        context: Context,
        private val onAction: (SelectionAction) -> Unit,
        private val onProcessText: (Intent) -> Unit
    ) {
        private data class MenuActionItem(
            val key: String,
            val label: String,
            val action: SelectionAction? = null,
            val intent: Intent? = null
        )

        private val popupWindow: PopupWindow
        private val rootView: LinearLayout
        private val primaryRow: LinearLayout
        private val morePage: LinearLayout
        private val moreList: LinearLayout
        private val moreScrollView: ScrollView
        private val moreButton: TextView
        private val moreMenuItems: List<MenuActionItem>
        private var primaryActionKey: String = DEFAULT_SELECTION_PRIMARY_ACTION_KEY
        var jumpToCueEnabled: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                rebuildMenu(rootView.context)
            }
        private var moreExpanded = false
        private var lastAnchor: View? = null
        private var lastStartX: Int = 0
        private var lastStartTopY: Int = 0
        private var lastStartBottomY: Int = 0
        private var lastEndX: Int = 0
        private var lastEndBottomY: Int = 0

        init {
            primaryRow = row(context)
            moreList = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            morePage = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                addView(row(context).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    addView(backButton(context, context.getString(R.string.reader_selection_more)) { collapseMorePage() })
                })
                moreScrollView = ScrollView(context).apply {
                    isFillViewport = false
                    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    addView(moreList)
                }
                addView(moreScrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(context, 240)))
            }
            moreButton = overflowButton(context) {
                toggleMoreExpanded()
            }
            moreMenuItems = buildBaseMoreMenuItems(context)
            primaryRow.addView(buildPrimaryActionButton(context))
            rebuildPrimaryButtons(context)
            moreMenuItems.forEach { item ->
                moreList.addView(moreActionButton(context, item.label) {
                    when {
                        item.intent != null -> onProcessText(item.intent)
                        item.action != null -> onAction(item.action)
                    }
                })
            }

            rootView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 6), dp(context, 5), dp(context, 6), dp(context, 5))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 12).toFloat()
                    setColor(0xFFF7F0E2.toInt())
                    setStroke(dp(context, 1), 0x1F000000)
                }
                elevation = dp(context, 8).toFloat()
                addView(primaryRow)
                addView(morePage)
            }
            popupWindow = PopupWindow(
                rootView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                false
            ).apply {
                isTouchable = true
                isFocusable = false
                isOutsideTouchable = false
                setBackgroundDrawable(null)
            }
        }

        private fun buildBaseMoreMenuItems(context: Context): List<MenuActionItem> {
            return buildList {
                add(
                    MenuActionItem(
                        key = "share",
                        label = context.getString(R.string.reader_selection_share),
                        action = SelectionAction.SHARE
                    )
                )
                add(
                    MenuActionItem(
                        key = "browser",
                        label = context.getString(R.string.reader_selection_browser_search),
                        action = SelectionAction.BROWSER
                    )
                )
                processTextIntents(context).forEach { item ->
                    add(
                        MenuActionItem(
                            key = item.intent.component?.flattenToString()?.let { "process:$it" }
                                ?: "process:${item.label}",
                            label = item.label,
                            intent = item.intent
                        )
                    )
                }
            }
        }

        fun setPrimaryActionKey(key: String) {
            primaryActionKey = key
            rebuildPrimaryActionButton(rootView.context)
        }

        fun primaryActionOptions(): List<SelectionPrimaryActionOption> {
            return buildList {
                add(
                    SelectionPrimaryActionOption(
                        key = DEFAULT_SELECTION_PRIMARY_ACTION_KEY,
                        label = rootView.context.getString(R.string.reader_selection_primary_default)
                    )
                )
                moreMenuItems.forEach { item ->
                    add(SelectionPrimaryActionOption(key = item.key, label = item.label))
                }
            }
        }

        fun show(
            anchor: View,
            startX: Int,
            startTopY: Int,
            startBottomY: Int,
            endX: Int,
            endBottomY: Int,
            resetToDefault: Boolean = true
        ) {
            if (anchor.width <= 0 || anchor.height <= 0) return
            if (resetToDefault) {
                restoreDefaultPage()
            }
            lastAnchor = anchor
            lastStartX = startX
            lastStartTopY = startTopY
            lastStartBottomY = startBottomY
            lastEndX = endX
            lastEndBottomY = endBottomY
            rootView.requestLayout()
            rootView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(anchor.height, View.MeasureSpec.AT_MOST)
            )
            val margin = dp(anchor.context, 8)
            val popupWidth = rootView.measuredWidth.coerceAtMost(anchor.width - margin * 2)
            val popupHeight = rootView.measuredHeight
            val maxY = (anchor.height - popupHeight - margin).coerceAtLeast(margin)
            val anchorInWindow = IntArray(2)
            anchor.getLocationInWindow(anchorInWindow)
            val openSpaceThreshold = SELECTION_MENU_OPEN_SPACE_THRESHOLD_PX
            val (targetX, targetY) = when {
                startBottomY > openSpaceThreshold -> {
                    startX to startTopY - popupHeight
                }
                endBottomY - startBottomY > openSpaceThreshold -> {
                    startX to startBottomY
                }
                else -> {
                    endX to endBottomY
                }
            }
            val clampedX = targetX.coerceIn(margin, (anchor.width - popupWidth - margin).coerceAtLeast(margin))
            val clampedY = targetY.coerceIn(margin, maxY)
            val windowX = anchorInWindow[0] + clampedX
            val windowY = anchorInWindow[1] + clampedY
            logDebug(M9_SELECTION_LOG_TAG) {
                "menuShow anchor=${anchor.width}x${anchor.height} " +
                "anchorWindow=(${anchorInWindow[0]},${anchorInWindow[1]}) " +
                "popup=${popupWidth}x$popupHeight margin=$margin " +
                "input=start($startX,$startTopY,$startBottomY) end($endX,$endBottomY) " +
                "target=($targetX,$targetY) clamped=($clampedX,$clampedY) " +
                "window=($windowX,$windowY)"
            }
            if (popupWindow.isShowing) {
                popupWindow.update(windowX, windowY, popupWidth, popupHeight)
            } else {
                popupWindow.width = popupWidth
                popupWindow.height = popupHeight
                popupWindow.showAtLocation(anchor, Gravity.TOP or Gravity.START, windowX, windowY)
            }
        }

        fun dismiss() {
            restoreDefaultPage()
            popupWindow.dismiss()
        }

        private fun restoreDefaultPage() {
            moreExpanded = false
            primaryRow.visibility = View.VISIBLE
            morePage.visibility = View.GONE
            moreScrollView.scrollTo(0, 0)
        }

        private fun toggleMoreExpanded() {
            moreExpanded = !moreExpanded
            primaryRow.visibility = if (moreExpanded) View.GONE else View.VISIBLE
            morePage.visibility = if (moreExpanded) View.VISIBLE else View.GONE
            updateLastPopupPosition()
        }

        private fun collapseMorePage() {
            restoreDefaultPage()
            updateLastPopupPosition()
        }

        private fun updateLastPopupPosition() {
            val anchor = lastAnchor ?: return
            show(
                anchor = anchor,
                startX = lastStartX,
                startTopY = lastStartTopY,
                startBottomY = lastStartBottomY,
                endX = lastEndX,
                endBottomY = lastEndBottomY,
                resetToDefault = false
            )
        }

        private fun rebuildPrimaryActionButton(context: Context) {
            if (primaryRow.childCount <= 0) return
            primaryRow.removeViewAt(0)
            primaryRow.addView(buildPrimaryActionButton(context), 0)
        }

        private fun rebuildMenu(context: Context) {
            rebuildPrimaryButtons(context)
            rebuildPrimaryActionButton(context)
            if (moreExpanded) {
                updateLastPopupPosition()
            }
        }

        private fun rebuildPrimaryButtons(context: Context) {
            while (primaryRow.childCount > 1) {
                primaryRow.removeViewAt(1)
            }
            primaryRow.addView(actionButton(context, context.getString(android.R.string.copy)) { onAction(SelectionAction.COPY) })
            primaryRow.addView(actionButton(context, context.getString(R.string.reader_menu_add_bookmark)) { onAction(SelectionAction.ADD_BOOKMARK) })
            if (jumpToCueEnabled) {
                primaryRow.addView(actionButton(context, context.getString(R.string.bookreader_jump)) { onAction(SelectionAction.JUMP_TO_CUE) })
            }
            primaryRow.addView(actionButton(context, context.getString(R.string.search_content)) { onAction(SelectionAction.SEARCH) })
            primaryRow.addView(moreButton)
        }

        private fun buildPrimaryActionButton(context: Context): View {
            val item = moreMenuItems.firstOrNull { it.key == primaryActionKey }
            if (primaryActionKey == DEFAULT_SELECTION_PRIMARY_ACTION_KEY || item == null) {
                return processTextButton(context) { onAction(SelectionAction.PROCESS_TEXT) }
            }
            return processTextButton(
                context = context,
                text = primaryActionGlyph(item.label),
                description = item.label
            ) {
                when {
                    item.intent != null -> onProcessText(item.intent)
                    item.action != null -> onAction(item.action)
                }
            }
        }

        private fun row(context: Context): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
        }

        private fun actionButton(context: Context, label: String, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setSingleLine(true)
                maxLines = 1
                setTextColor(0xFF2C241B.toInt())
                textSize = 13f
                minWidth = dp(context, 56)
                setPadding(dp(context, 10), dp(context, 7), dp(context, 10), dp(context, 7))
                setOnClickListener { onClick() }
            }
        }

        private fun processTextButton(
            context: Context,
            text: String = "▽",
            description: String = context.getString(R.string.reader_selection_process_text),
            onClick: () -> Unit
        ): TextView {
            return TextView(context).apply {
                this.text = text
                gravity = Gravity.CENTER
                includeFontPadding = false
                setSingleLine(true)
                setTextColor(0xFF8A837A.toInt())
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                contentDescription = description
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(context, 1), 0x668A837A)
                }
                setPadding(0, 0, 0, dp(context, 1))
                layoutParams = LinearLayout.LayoutParams(dp(context, 20), dp(context, 20)).apply {
                    marginEnd = dp(context, 6)
                }
                setOnClickListener { onClick() }
            }
        }

        private fun primaryActionGlyph(label: String): String {
            val trimmed = label.trim()
            if (trimmed.isEmpty()) return "▽"
            return trimmed.take(1)
        }

        private fun moreActionButton(context: Context, label: String, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = label
                gravity = Gravity.CENTER_VERTICAL
                setSingleLine(true)
                setTextColor(0xFF2C241B.toInt())
                textSize = 14f
                minWidth = dp(context, 160)
                setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
                setOnClickListener { onClick() }
            }
        }

        private fun backButton(context: Context, description: String, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = "‹"
                gravity = Gravity.CENTER
                setTextColor(0xFF2C241B.toInt())
                textSize = 22f
                setPadding(dp(context, 10), dp(context, 2), dp(context, 10), dp(context, 2))
                contentDescription = description
                setOnClickListener { onClick() }
            }
        }

        private fun overflowButton(context: Context, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = "⋮"
                gravity = Gravity.CENTER
                setSingleLine(true)
                setTextColor(0xFF2C241B.toInt())
                textSize = 16f
                setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
                contentDescription = context.getString(R.string.reader_selection_more)
                setOnClickListener { onClick() }
            }
        }

        private fun dp(context: Context, value: Int): Int = value.dp(context)

        private fun processTextIntents(context: Context): List<ProcessTextItem> {
            val baseIntent = Intent()
                .setAction(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
            val packageManager = context.packageManager
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    baseIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(baseIntent, PackageManager.MATCH_ALL)
            }
            return resolveInfos
                .mapNotNull { info ->
                    val activity = info.activityInfo ?: return@mapNotNull null
                    val label = info.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    ProcessTextItem(
                        label = label,
                        intent = Intent(baseIntent).setClassName(activity.packageName, activity.name)
                    )
                }
                .distinctBy { item -> item.intent.component?.flattenToString() }
        }

        private data class ProcessTextItem(
            val label: String,
            val intent: Intent
        )
    }

}
