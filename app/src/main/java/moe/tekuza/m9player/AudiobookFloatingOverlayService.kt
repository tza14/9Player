package moe.tekuza.m9player

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.text.TextPaint
import android.view.View.MeasureSpec
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.appcompat.widget.AppCompatTextView
import java.util.Locale
import android.webkit.WebView
import android.widget.Toast
import de.manhhao.hoshi.LookupResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupAssets
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupHtml
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupOptions
import moe.tekuza.m9player.hoshi.features.dictionary.PopupContentReadyGate
import moe.tekuza.m9player.hoshi.features.dictionary.PopupLookupResultsHolder
import moe.tekuza.m9player.hoshi.features.dictionary.PopupMessageWebViewClient
import moe.tekuza.m9player.hoshi.features.dictionary.PopupWebViewBridge
import moe.tekuza.m9player.hoshi.features.dictionary.PopupWebViewCallbackHolder
import moe.tekuza.m9player.hoshi.features.dictionary.PopupWebViewCallbacks
import moe.tekuza.m9player.hoshi.features.dictionary.currentDictionaryStyles
import moe.tekuza.m9player.hoshi.features.dictionary.loadDictionarySettings
import moe.tekuza.m9player.hoshi.features.dictionary.openPopupExternalLink
import moe.tekuza.m9player.hoshi.features.dictionary.openHoshiImagePreview
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionData
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionRect
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private fun hasOverlayPermission(context: Context): Boolean {
    return Settings.canDrawOverlays(context)
}

private const val FLOATING_OVERLAY_LOG_TAG = "FloatingOverlay"

private fun Context.isSystemDarkMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

internal fun startAudiobookFloatingOverlayService(context: Context) {
    val hasPermission = hasOverlayPermission(context)
    if (!hasPermission) {
        return
    }
    val settings = loadAudiobookSettingsConfig(context)
    if (!settings.floatingOverlayEnabled && !settings.floatingOverlaySubtitleEnabled) {
        return
    }
    val intent = Intent(context, AudiobookFloatingOverlayService::class.java).apply {
        action = AudiobookFloatingOverlayService.ACTION_SHOW
    }
    startAudiobookFloatingOverlayServiceSafely(context, intent, "show")
}

internal fun refreshAudiobookFloatingOverlayService(context: Context) {
    if (!hasOverlayPermission(context)) {
        return
    }
    val intent = Intent(context, AudiobookFloatingOverlayService::class.java).apply {
        action = AudiobookFloatingOverlayService.ACTION_REFRESH
    }
    startAudiobookFloatingOverlayServiceSafely(context, intent, "refresh")
}

internal fun stopAudiobookFloatingOverlayService(context: Context) {
    val intent = Intent(context, AudiobookFloatingOverlayService::class.java).apply {
        action = AudiobookFloatingOverlayService.ACTION_HIDE
    }
    startAudiobookFloatingOverlayServiceSafely(context, intent, "hide")
}

private fun startAudiobookFloatingOverlayServiceSafely(
    context: Context,
    intent: Intent,
    reason: String
): Boolean {
    return runCatching {
        context.startService(intent)
        true
    }.onFailure { error ->
        Log.w(
            FLOATING_OVERLAY_LOG_TAG,
            "start overlay service failed reason=$reason: ${error.message ?: error.javaClass.simpleName}",
            error
        )
    }.getOrDefault(false)
}

class AudiobookFloatingOverlayService : Service() {
companion object {
        const val ACTION_SHOW = "moe.tekuza.m9player.action.SHOW_FLOATING_OVERLAY"
        const val ACTION_HIDE = "moe.tekuza.m9player.action.HIDE_FLOATING_OVERLAY"
        const val ACTION_REFRESH = "moe.tekuza.m9player.action.REFRESH_FLOATING_OVERLAY"
        private const val FLOATING_LOOKUP_LOG_TAG = "FloatingLookupPos"
        private const val FLOATING_LOOKUP_TAP_LOG_TAG = "FloatingLookupTap"
        private const val FLOATING_BUBBLE_LOG_TAG = "FloatingBubblePos"
        private const val FLOATING_SUBTITLE_SCROLL_LOG_TAG = "FloatingSubtitleScroll"
        private const val FLOATING_SUBTITLE_RENDER_LOG_TAG = "FloatingSubtitleRender"
        private const val FLOATING_SUBTITLE_HIT_LOG_TAG = "FloatingSubtitleHit"
        private const val SINGLE_FLOATING_HOST_KEY = -1
}

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var bubbleRootView: FrameLayout? = null
    private var subtitlePanelView: LinearLayout? = null
    private var bubbleRow: LinearLayout? = null
    private var bubbleButton: ImageButton? = null
    private var bubbleControlsRow: LinearLayout? = null
    private var bubbleLockButton: ImageButton? = null
    private var subtitleFrameView: FrameLayout? = null
    private var subtitleTextView: HorizontalSubtitleTextView? = null
    private var subtitleOutlineTextView: TextView? = null
    private var subtitleVerticalCanvasView: FloatingVerticalSubtitleCanvasView? = null
    private var subtitleControlsRow: LinearLayout? = null
    private var subtitleSettingsPanel: LinearLayout? = null
    private var subtitleSettingsInlineHost: LinearLayout? = null
    private var subtitleLockButton: ImageButton? = null
    private var subtitleSettingsButton: ImageButton? = null
    private var subtitleSettingsFloatingView: FrameLayout? = null
    private var subtitleSettingsWindowLayoutParams: WindowManager.LayoutParams? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null
    private var bubbleWindowLayoutParams: WindowManager.LayoutParams? = null
    private var subtitleControlsVisible: Boolean = true
    private var subtitleSettingsExpanded: Boolean = false
    private var bubbleControlsVisible: Boolean = false
    private var subtitleOverlayLocked: Boolean = false
    private var bubbleOverlayLocked: Boolean = false
    private var playbackPausedByFloatingLookup: Boolean = false
    private var lastSubtitleScrollLogAtMs: Long = 0L
    private var subtitleTickerRunning: Boolean = false
    private var subtitleTickerBasePositionMs: Long = 0L
    private var subtitleTickerBaseRealtimeMs: Long = 0L
    private var subtitlePlaybackSpeed: Float = 1f
    private var lookupRequestNonce: Long = 0L
    private var floatingLookupWarmupJob: Job? = null
    private var cachedLookupDictionaries: List<LoadedDictionary>? = null
    private var cachedLookupDictionariesVersion: Long = -1L
    private val floatingLookupSession = ReaderLookupSession()
    private val floatingLookupCardPositions = mutableMapOf<Int, IntOffset>()
    private val floatingLookupHostViews = mutableMapOf<Int, View>()
    private val floatingLookupHostSignatures = mutableMapOf<Int, Int>()
    private val floatingLookupRepositionJobs = mutableMapOf<Int, Job>()
    private val floatingLookupHostSizeListeners = mutableMapOf<Int, View.OnLayoutChangeListener>()
    private val floatingHoshiLookupWebViews = mutableMapOf<Int, WebView>()
    private val floatingHoshiLookupHtmlByLayer = mutableMapOf<Int, String>()
    private val floatingHoshiLookupAssets: LookupPopupAssets by lazy { LookupPopupAssets.load(this) }
    private val floatingHoshiLookupSession: HoshiLookupSession by lazy {
        HoshiLookupSession(this, dictionariesProvider = { loadFloatingLookupDictionaries() })
    }

    private class FloatingHoshiLookupWebView(context: Context) : WebView(context) {
        var maxLookupHeightPx: Int = 1

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(maxLookupHeightPx, MeasureSpec.EXACTLY)
            )
        }
    }

    private class HorizontalSubtitleTextView(context: Context) : AppCompatTextView(context) {
        private val selectionPaint = Paint().apply {
            color = 0x33A1A1AA
            style = Paint.Style.FILL
        }
        private val selectionPath = Path()
        private var selectedRange: IntRange? = null

        fun setSelectedRange(range: IntRange?) {
            if (selectedRange == range) return
            selectedRange = range
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val textLength = text?.length ?: 0
            val range = selectedRange
            val textLayout = layout
            if (range != null && textLayout != null && range.first in 0 until textLength) {
                val endExclusive = (range.last + 1).coerceIn(range.first + 1, textLength)
                selectionPath.reset()
                textLayout.getSelectionPath(range.first, endExclusive, selectionPath)
                val saveCount = canvas.save()
                canvas.translate(
                    compoundPaddingLeft.toFloat() - scrollX,
                    compoundPaddingTop.toFloat() - scrollY,
                )
                canvas.drawPath(selectionPath, selectionPaint)
                canvas.restoreToCount(saveCount)
            }
            super.onDraw(canvas)
        }
    }

    private class HorizontalSubtitleOutlineTextView(context: Context) : AppCompatTextView(context) {
        private var outlineStrokeWidth: Float = 1f

        fun configureOutline(strokeWidth: Float) {
            outlineStrokeWidth = strokeWidth.coerceAtLeast(1f)
            invalidate()
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val previousStyle = paint.style
            val previousStrokeWidth = paint.strokeWidth
            val previousStrokeJoin = paint.strokeJoin
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = outlineStrokeWidth
            paint.strokeJoin = Paint.Join.ROUND
            super.onDraw(canvas)
            paint.style = previousStyle
            paint.strokeWidth = previousStrokeWidth
            paint.strokeJoin = previousStrokeJoin
        }
    }

    private data class FloatingHoshiLookupWebViewTag(
        val callbackHolder: PopupWebViewCallbackHolder,
        val resultsHolder: PopupLookupResultsHolder,
        val contentReadyGate: PopupContentReadyGate = PopupContentReadyGate(),
        var shellReady: Boolean = false,
        var contentReady: Boolean = false,
        var pendingResults: List<LookupResult> = emptyList(),
        var appliedResults: List<LookupResult>? = null,
    )

    private inner class FloatingVerticalSubtitleCanvasView(context: Context) : View(context) {
        private var content: String = ""
        private var selectedRange: IntRange? = null
        private var lastAnchorRect: Rect? = null
        private var cachedLayout: VerticalSubtitleLayout? = null
        private var cachedHeight: Int = -1
        private var cachedText: String = ""
        private var cachedTextSize: Float = Float.NaN
        private var cachedTypeface: Typeface? = null
        private var cachedSingleColumn: Boolean = false
        private var singleColumnLayout: Boolean = false
        private var contentScrollY: Float = 0f
        private val screenLocation = IntArray(2)
        private val paint = TextPaint().apply {
            isAntiAlias = true
            color = Color.WHITE
            textAlign = Paint.Align.LEFT
        }
        private val outlinePaint = TextPaint(paint).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
        private val selectionPaint = Paint().apply {
            color = Color.argb(0x66, 0xA1, 0xA1, 0xAA)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        init {
            setBackgroundColor(Color.TRANSPARENT)
            isLongClickable = false
            isHapticFeedbackEnabled = false
            isSoundEffectsEnabled = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun bindText(text: String, color: Int, sizeSp: Float, typeface: Typeface? = paint.typeface) {
            val sizePx = sizeSpToPx(sizeSp)
            val changed = content != text ||
                paint.color != color ||
                paint.textSize != sizePx ||
                paint.typeface != typeface
            content = text
            paint.color = color
            paint.textSize = sizePx
            paint.typeface = typeface
            outlinePaint.textSize = sizePx
            outlinePaint.typeface = typeface
            outlinePaint.color = Color.argb(0x59, 0, 0, 0)
            outlinePaint.strokeWidth = (resources.displayMetrics.density * 1.2f).coerceAtLeast(1f)
            if (changed) {
                lastAnchorRect = null
                contentScrollY = 0f
                clearLayoutCache()
                requestLayout()
                invalidate()
            }
        }

        fun applyTypography(color: Int, sizeSp: Float, typeface: Typeface? = paint.typeface) {
            bindText(content, color, sizeSp, typeface)
        }

        fun setSelectedSourceRange(range: IntRange?) {
            selectedRange = range
            invalidate()
        }

        fun setSingleColumnLayout(enabled: Boolean) {
            if (singleColumnLayout == enabled) return
            singleColumnLayout = enabled
            lastAnchorRect = null
            contentScrollY = 0f
            clearLayoutCache()
            requestLayout()
            invalidate()
        }

        fun setContentScrollY(scrollY: Float) {
            val target = scrollY.coerceIn(0f, maxContentScrollY())
            if (abs(contentScrollY - target) <= 0.5f) return
            contentScrollY = target
            lastAnchorRect = null
            invalidate()
        }

        fun maxContentScrollY(): Float {
            val layout = obtainLayout(height) ?: return 0f
            return (layout.contentHeight() - height.toFloat()).coerceAtLeast(0f)
        }

        fun selectAt(x: Float, y: Float) {
            val layout = obtainLayout(height) ?: run {
                logDebug(FLOATING_SUBTITLE_HIT_LOG_TAG) {
                    "verticalCanvasTap miss reason=no_layout x=$x y=$y view=${width}x$height"
                }
                return
            }
            val hit = VerticalSubtitleLayoutEngine.hitTest(
                x = x,
                y = y,
                viewWidth = width,
                viewHeight = height,
                layout = layout,
                paint = paint,
                scrollY = contentScrollY
            ) ?: run {
                logDebug(FLOATING_SUBTITLE_HIT_LOG_TAG) {
                    "verticalCanvasTap miss x=$x y=$y view=${width}x$height"
                }
                return
            }
            val anchor = toScreenRect(hit.rect)
            lastAnchorRect = anchor
            logDebug(FLOATING_SUBTITLE_HIT_LOG_TAG) {
                "verticalCanvasTap offset=${hit.sourceOffset} row=${hit.row} col=${hit.column} rect=${anchor.left},${anchor.top},${anchor.right},${anchor.bottom} view=${width}x$height"
            }
            performFloatingLookup(hit.sourceOffset.coerceAtLeast(0), anchor)
        }

        fun resolveAnchorRectsForRange(range: IntRange, callback: (List<Rect>) -> Unit) {
            callback(resolveAnchorRectsForRange(range))
        }

        private fun resolveAnchorRectsForRange(range: IntRange): List<Rect> {
            if (range.first < 0 || range.last < range.first) return emptyList()
            val layout = obtainLayout(height) ?: return emptyList()
            return VerticalSubtitleLayoutEngine.selectionRects(
                range = range,
                viewWidth = width,
                viewHeight = height,
                layout = layout,
                paint = paint,
                scrollY = contentScrollY
            ).let { rects ->
                if (rects.isEmpty()) return emptyList()
                getLocationOnScreen(screenLocation)
                rects.map { rect -> toScreenRect(rect, screenLocation) }
            }
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val layout = obtainLayout(height) ?: return
            selectedRange?.takeIf { it.first >= 0 && it.last >= it.first }?.let { range ->
                VerticalSubtitleLayoutEngine.selectionRects(
                    range = range,
                    viewWidth = width,
                    viewHeight = height,
                    layout = layout,
                    paint = paint,
                    scrollY = contentScrollY
                )
                    .forEach { rect -> canvas.drawRect(rect, selectionPaint) }
            }
            VerticalSubtitleLayoutEngine.draw(canvas, outlinePaint, layout, width, height, scrollY = contentScrollY)
            VerticalSubtitleLayoutEngine.draw(canvas, paint, layout, width, height, scrollY = contentScrollY)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val metrics = resources.displayMetrics
            val density = metrics.density.coerceAtLeast(0.1f)
            val maxHeight = (metrics.heightPixels * 0.72f).roundToInt().coerceAtLeast((96f * density).roundToInt())
            val measuredHeight = resolveSize(maxHeight, heightMeasureSpec)
            val layout = obtainLayout(measuredHeight)
            val desiredWidth = layout
                ?.contentWidth()
                ?.let { ceil(it.toDouble()).toInt() + (20f * density).roundToInt() }
                ?: (44f * density).roundToInt()
            val maxWidth = (metrics.widthPixels * 0.82f).roundToInt().coerceAtLeast(1)
            val measuredWidth = resolveSize(
                desiredWidth.coerceIn((44f * density).roundToInt(), maxWidth),
                widthMeasureSpec
            )
            setMeasuredDimension(measuredWidth, measuredHeight)
        }

        private fun obtainLayout(targetHeight: Int): VerticalSubtitleLayout? {
            if (content.isBlank() || targetHeight <= 0) return null
            if (cachedLayout != null &&
                cachedHeight == targetHeight &&
                cachedText == content &&
                cachedTextSize == paint.textSize &&
                cachedTypeface == paint.typeface &&
                cachedSingleColumn == singleColumnLayout
            ) {
                return cachedLayout
            }
            cachedLayout = VerticalSubtitleLayoutEngine.build(
                content,
                paint,
                targetHeight,
                effectiveCellHeightPx(),
                singleColumn = singleColumnLayout
            )
            cachedHeight = targetHeight
            cachedText = content
            cachedTextSize = paint.textSize
            cachedTypeface = paint.typeface
            cachedSingleColumn = singleColumnLayout
            return cachedLayout
        }

        private fun toScreenRect(rect: RectF): Rect {
            getLocationOnScreen(screenLocation)
            return toScreenRect(rect, screenLocation)
        }

        private fun toScreenRect(rect: RectF, location: IntArray): Rect {
            return Rect(
                left = location[0] + rect.left,
                top = location[1] + rect.top,
                right = location[0] + rect.right,
                bottom = location[1] + rect.bottom
            )
        }

        private fun clearLayoutCache() {
            cachedLayout = null
            cachedHeight = -1
            cachedText = ""
            cachedTextSize = Float.NaN
            cachedTypeface = null
            cachedSingleColumn = false
        }

        private fun effectiveCellHeightPx(): Float =
            (paint.textSize * 1.08f).coerceAtLeast(1f)

        private fun sizeSpToPx(sizeSp: Float): Float =
            sizeSp.coerceIn(8f, 96f) * resources.displayMetrics.scaledDensity.coerceAtLeast(0.1f)
    }

    // Measure only the toolbar child (index 0). Overlay children can extend visually
    // without changing parent layout size, so subtitle won't be pushed when settings shows.
    private class OverlayAnchorLayout(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val anchor = getChildAt(0)
            if (anchor == null) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            // 1) Measure anchor with parent specs (defines host size).
            measureChild(anchor, widthMeasureSpec, heightMeasureSpec)
            // 2) Measure overlay children unconstrained so popup panel keeps full size.
            val unconstrainedW = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            val unconstrainedH = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            for (i in 1 until childCount) {
                measureChild(getChildAt(i), unconstrainedW, unconstrainedH)
            }
            setMeasuredDimension(
                anchor.measuredWidth.coerceAtLeast(suggestedMinimumWidth),
                anchor.measuredHeight.coerceAtLeast(suggestedMinimumHeight)
            )
        }
    }

    private val playbackListener = object : BookReaderFloatingBridge.PlaybackStateListener {
        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            updateBubbleIcon(isPlaying)
            updatePlayPauseIcon(isPlaying)
            updateSubtitleAutoScroll()
            if (isPlaying) startSubtitleTicker() else stopSubtitleTicker()
        }
    }

    private val playbackPositionListener = object : BookReaderFloatingBridge.PlaybackPositionListener {
        override fun onPlaybackPositionChanged(positionMs: Long) {
            subtitleTickerBasePositionMs = positionMs
            subtitleTickerBaseRealtimeMs = SystemClock.uptimeMillis()
            updateSubtitleAutoScroll(positionMs)
        }
    }

    private val playbackSpeedListener = object : BookReaderFloatingBridge.PlaybackSpeedListener {
        override fun onPlaybackSpeedChanged(speed: Float) {
            subtitlePlaybackSpeed = if (speed.isFinite() && speed > 0f) speed else 1f
        }
    }

    private val subtitleFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!subtitleTickerRunning) return
            if (!BookReaderFloatingBridge.isPlaying()) {
                subtitleTickerRunning = false
                return
            }
            val now = SystemClock.uptimeMillis()
            val elapsed = (now - subtitleTickerBaseRealtimeMs).coerceAtLeast(0L)
            val extrapolated = subtitleTickerBasePositionMs + (elapsed * subtitlePlaybackSpeed).toLong()
            BookReaderFloatingBridge.refreshSubtitleForCurrentPlaybackPosition()
            updateSubtitleAutoScroll(extrapolated)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val subtitleListener = object : BookReaderFloatingBridge.SubtitleStateListener {
        override fun onSubtitleChanged(text: String?) {
            updateSubtitleText(text)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        BookReaderFloatingBridge.addPlaybackStateListener(playbackListener)
        BookReaderFloatingBridge.addSubtitleStateListener(subtitleListener)
        BookReaderFloatingBridge.addPlaybackPositionListener(playbackPositionListener)
        BookReaderFloatingBridge.addPlaybackSpeedListener(playbackSpeedListener)
        BookReaderFloatingBridge.refreshSubtitleForCurrentPlaybackPosition()
        subtitlePlaybackSpeed = BookReaderFloatingBridge.currentPlaybackSpeed()
        subtitleTickerBasePositionMs = BookReaderFloatingBridge.currentPlaybackPositionMs()
        subtitleTickerBaseRealtimeMs = SystemClock.uptimeMillis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                logDebug(FLOATING_OVERLAY_LOG_TAG) { "onStartCommand hide" }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW, null -> {
                logDebug(FLOATING_OVERLAY_LOG_TAG) { "onStartCommand show" }
                ensureOverlayVisible()
                return START_STICKY
            }
            ACTION_REFRESH -> {
                logDebug(FLOATING_OVERLAY_LOG_TAG) { "onStartCommand refresh" }
                rebuildOverlay()
                return START_STICKY
            }
            else -> {
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        BookReaderFloatingBridge.removePlaybackStateListener(playbackListener)
        BookReaderFloatingBridge.removeSubtitleStateListener(subtitleListener)
        BookReaderFloatingBridge.removePlaybackPositionListener(playbackPositionListener)
        BookReaderFloatingBridge.removePlaybackSpeedListener(playbackSpeedListener)
        stopSubtitleTicker()
        serviceScope.cancel()
        removeOverlay()
        destroyFloatingHoshiLookupWebViews()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasSubtitleTimeline(): Boolean = BookReaderFloatingBridge.hasSubtitleTrack()

    private fun hasSubtitleData(text: String? = BookReaderFloatingBridge.currentSubtitle()): Boolean {
        return hasSubtitleTimeline() || !text.isNullOrBlank()
    }

    private fun ensureOverlayVisible() {
        if (!hasOverlayPermission(this)) {
            stopSelf()
            return
        }
        val settings = loadAudiobookSettingsConfig(this)
        if (!settings.floatingOverlayEnabled && !settings.floatingOverlaySubtitleEnabled) {
            stopSelf()
            return
        }
        val wm = windowManager ?: run {
            stopSelf()
            return
        }
        val hasTimeline = hasSubtitleTimeline()
        val subtitleText = BookReaderFloatingBridge.currentSubtitle()
        val hasSubtitleData = hasSubtitleData(subtitleText)
        val subtitleEnabledByData = settings.floatingOverlaySubtitleEnabled && hasSubtitleData
        if (!settings.floatingOverlayEnabled && !subtitleEnabledByData) {
            stopSelf()
            return
        }
        val density = resources.displayMetrics.density
        val bubbleSizeDp = settings.floatingOverlaySizeDp
        val bubbleSizePx = (bubbleSizeDp * density).toInt()
        if (subtitleEnabledByData) {
            if (rootView == null) {
                val container = FrameLayout(this).apply {
                    clipChildren = false
                    clipToPadding = false
                    setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                    addView(buildSubtitlePanel(settings))
                }
                val params = createOverlayLayoutParams(
                    x = settings.floatingOverlaySubtitleX.coerceAtLeast(0),
                    y = initialSubtitleOverlayY(settings, density)
                )
                runCatching { wm.addView(container, params) }
                    .onFailure { error ->
                        Log.w(FLOATING_OVERLAY_LOG_TAG, "subtitle addView failed: ${error.message}", error)
                        stopSelf()
                        return
                    }
                rootView = container
                windowLayoutParams = params
                container.post { alignOverlayWindow(force = true) }
            } else {
                applySubtitleTypography(settings)
                updateSubtitleText(BookReaderFloatingBridge.currentSubtitle())
                updateSubtitleControlsVisibility(settings)
            }
        } else {
            removeSubtitleOverlay()
        }
        if (settings.floatingOverlayEnabled) {
            if (bubbleRootView == null) {
                val bubblePanel = buildBubblePanel(bubbleSizePx)
                val params = createOverlayLayoutParams(
                    x = settings.floatingOverlayBubbleX.coerceAtLeast(0),
                    y = settings.floatingOverlayBubbleY.coerceAtLeast(0)
                )
                runCatching { wm.addView(bubblePanel, params) }
                    .onFailure { error ->
                        Log.w(FLOATING_OVERLAY_LOG_TAG, "bubble addView failed: ${error.message}", error)
                        stopSelf()
                        return
                    }
                bubbleRootView = bubblePanel
                bubbleWindowLayoutParams = params
            }
        } else {
            removeBubbleOverlay()
        }
        updateBubbleIcon(BookReaderFloatingBridge.isPlaying())
        updatePlayPauseIcon(BookReaderFloatingBridge.isPlaying())
        if (subtitleEnabledByData) {
            updateSubtitleText(BookReaderFloatingBridge.currentSubtitle())
            updateSubtitleControlsVisibility(settings)
        }
        updateFloatingLookupPanelPosition()
        prewarmFloatingLookup()
    }

    private fun prewarmFloatingLookup() {
        if (floatingLookupWarmupJob?.isActive == true || floatingLookupHostViews.containsKey(0)) return
        floatingLookupWarmupJob = serviceScope.launch {
            val styles = withContext(Dispatchers.Default) {
                runCatching {
                    floatingHoshiLookupSession.ensurePrepared()
                    floatingHoshiLookupSession.dictionaryStyles()
                }.getOrNull()
            } ?: return@launch
            ensureFloatingWarmRoot(styles)
        }
    }

    private fun ensureFloatingWarmRoot(dictionaryStyles: Map<String, String>) {
        if (floatingLookupHostViews.containsKey(0)) return
        val wm = windowManager ?: return
        val layer = buildFloatingLookupLayer(
            term = "",
            hoshiDictionaryStyles = dictionaryStyles
        )
        val sizeSpec = computeFloatingLookupPopupSizeSpec(
            windowSize = IntSize(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels),
            anchor = null,
            placeBelow = true,
            preferSidePlacement = false
        )
        val card = createFloatingLookupCard(
            layerIndex = 0,
            layer = layer,
            mode = FloatingCardMode.Results,
            maxLookupHeightPx = sizeSpec.contentMaxHeightPx
        ).apply {
            layoutParams = FrameLayout.LayoutParams(
                sizeSpec.widthPx,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val host = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            alpha = 0f
            addView(card)
        }
        ensureFloatingHostMeasured(host, sizeSpec.widthPx, force = true)
        val position = IntOffset(0, 0)
        runCatching {
            wm.addView(host, createFloatingLookupWindowLayoutParams(position, touchable = false))
        }.onSuccess {
            floatingLookupHostViews[0] = host
        }
    }

    private fun createOverlayLayoutParams(x: Int, y: Int): WindowManager.LayoutParams {
        val windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            this.x = x
            this.y = y
        }
    }

    private fun initialSubtitleOverlayY(settings: AudiobookSettingsConfig, density: Float): Int {
        val savedY = settings.floatingOverlaySubtitleY.coerceAtLeast(0)
        if (settings.floatingOverlaySubtitleWritingMode != FloatingSubtitleWritingMode.VERTICAL_RTL) {
            return savedY
        }
        val upperY = (36f * density).roundToInt().coerceAtLeast(0)
        return savedY.coerceAtMost(upperY)
    }

    private fun buildSubtitlePanel(settings: AudiobookSettingsConfig): LinearLayout {
        val density = resources.displayMetrics.density
        val verticalWriting = settings.floatingOverlaySubtitleWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL
        val subtitleWidth = if (verticalWriting) {
            LinearLayout.LayoutParams.WRAP_CONTENT
        } else {
            (320 * density).toInt()
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (verticalWriting) Gravity.START else Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        subtitlePanelView = panel

        val subtitleGestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    logDebug(FLOATING_SUBTITLE_HIT_LOG_TAG) {
                        "subtitleGesture onDown x=${e.x} y=${e.y} visible=${subtitleTextView?.visibility == View.VISIBLE}"
                    }
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    logDebug(FLOATING_SUBTITLE_HIT_LOG_TAG) {
                        "subtitleGesture onSingleTapConfirmed x=${e.x} y=${e.y} visible=${subtitleTextView?.visibility == View.VISIBLE}"
                    }
                    handleSubtitleSingleTap(e)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    logDebug(FLOATING_SUBTITLE_HIT_LOG_TAG) {
                        "subtitleGesture onDoubleTap x=${e.x} y=${e.y} visible=${subtitleTextView?.visibility == View.VISIBLE}"
                    }
                    subtitleControlsVisible = !subtitleControlsVisible
                    subtitleSettingsExpanded = false
                    updateSubtitleControlsVisibility(loadAudiobookSettingsConfig(this@AudiobookFloatingOverlayService))
                    return true
                }
            }
        )

        val subtitleOutlineText = HorizontalSubtitleOutlineTextView(this).apply {
            setLineSpacing(0f, 1.08f)
            maxLines = 3
            ellipsize = null
            gravity = Gravity.CENTER_HORIZONTAL
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                subtitleWidth,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = false
            isFocusable = false
        }
        subtitleOutlineTextView = subtitleOutlineText

        val subtitleText = HorizontalSubtitleTextView(this).apply {
            setLineSpacing(0f, 1.08f)
            maxLines = 3
            ellipsize = null
            gravity = Gravity.CENTER_HORIZONTAL
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                subtitleWidth,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnTouchListener(
                OverlayDragTouchListener(
                    gestureDetector = subtitleGestureDetector,
                    dragAllowed = { !subtitleOverlayLocked },
                    verticalOnly = !verticalWriting,
                    horizontalOnly = false,
                    layoutParamsProvider = { windowLayoutParams },
                    hostViewProvider = { rootView },
                    horizontalBoundsProvider = { floatingSubtitleHorizontalBounds() },
                    onPersistPosition = { x, y ->
                        saveAudiobookFloatingOverlaySubtitlePosition(this@AudiobookFloatingOverlayService, x, y)
                    }
                )
            )
        }
        subtitleTextView = subtitleText
        val subtitleVerticalCanvas = FloatingVerticalSubtitleCanvasView(this).apply {
            visibility = if (verticalWriting) View.VISIBLE else View.GONE
            setOnTouchListener(
                OverlayDragTouchListener(
                    gestureDetector = subtitleGestureDetector,
                    dragAllowed = { !subtitleOverlayLocked },
                    verticalOnly = false,
                    horizontalOnly = false,
                    layoutParamsProvider = { windowLayoutParams },
                    hostViewProvider = { rootView },
                    horizontalBoundsProvider = { floatingSubtitleHorizontalBounds() },
                    onPersistPosition = { x, y ->
                        saveAudiobookFloatingOverlaySubtitlePosition(this@AudiobookFloatingOverlayService, x, y)
                    }
                )
            )
        }
        subtitleVerticalCanvasView = subtitleVerticalCanvas
        applySubtitleTypography(settings)
        val subtitleFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                subtitleWidth,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            elevation = 0f
            addView(
                subtitleOutlineText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                subtitleText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(subtitleVerticalCanvas)
        }
        subtitleFrameView = subtitleFrame
        val controls = LinearLayout(this).apply {
            orientation = if (verticalWriting) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (verticalWriting) {
                    topMargin = 0
                } else {
                    topMargin = (6 * density).toInt()
                }
            }
            background = createRoundedBackground(0x33151515, cornerDp = 18f, strokeColor = 0x22FFFFFF)
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            setOnTouchListener(
                OverlayDragTouchListener(
                    gestureDetector = null,
                    dragAllowed = { !subtitleOverlayLocked },
                    verticalOnly = !verticalWriting,
                    horizontalOnly = false,
                    layoutParamsProvider = { windowLayoutParams },
                    hostViewProvider = { rootView },
                    horizontalBoundsProvider = { floatingSubtitleHorizontalBounds() },
                    onPersistPosition = { x, y ->
                        saveAudiobookFloatingOverlaySubtitlePosition(this@AudiobookFloatingOverlayService, x, y)
                    }
                )
            )
        }
        subtitleControlsRow = controls

        val lockButton = createControlButton(R.drawable.ic_overlay_lock) {
            subtitleOverlayLocked = !subtitleOverlayLocked
            if (subtitleOverlayLocked) {
                subtitleControlsVisible = false
                subtitleSettingsExpanded = false
            }
            updateSubtitleLockIcon()
            updateSubtitleControlsVisibility(loadAudiobookSettingsConfig(this))
        }
        subtitleLockButton = lockButton
        controls.addView(lockButton)
        controls.addView(createControlButton(R.drawable.ic_overlay_previous) {
            BookReaderFloatingBridge.seekAdjacent(this@AudiobookFloatingOverlayService, -1)
        })
        controls.addView(createControlButton(R.drawable.ic_overlay_pause) {
            BookReaderFloatingBridge.togglePlayPause()
            updateBubbleIcon(BookReaderFloatingBridge.isPlaying())
            updatePlayPauseIcon(BookReaderFloatingBridge.isPlaying())
        }.also { it.tag = "playPause" })
        controls.addView(createControlButton(R.drawable.ic_overlay_next) {
            BookReaderFloatingBridge.seekAdjacent(this@AudiobookFloatingOverlayService, 1)
        })
        controls.addView(createControlButton(R.drawable.ic_overlay_settings) {
            subtitleSettingsExpanded = !subtitleSettingsExpanded
            updateSubtitleControlsVisibility(loadAudiobookSettingsConfig(this@AudiobookFloatingOverlayService))
        }.also { subtitleSettingsButton = it })
        val settingsPanel = buildSubtitleSettingsPanel(settings)
        subtitleSettingsPanel = settingsPanel
        updateSubtitleLockIcon()
        val controlsOverlayHost = OverlayAnchorLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            clipChildren = false
            clipToPadding = false
            elevation = 48f * resources.displayMetrics.density
            addView(controls)
        }
        if (verticalWriting) {
            subtitleSettingsInlineHost = null
            val verticalRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                addView(controlsOverlayHost)
                addView(subtitleFrame)
            }
            panel.addView(FrameLayout(this).apply {
                clipChildren = false
                clipToPadding = false
                addView(verticalRow)
            })
        } else {
            subtitleSettingsInlineHost = panel
            panel.addView(subtitleFrame)
            panel.addView(controlsOverlayHost)
            panel.addView(
                settingsPanel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (8 * resources.displayMetrics.density).toInt()
                }
            )
        }

        return panel
    }

    private fun buildSubtitleSettingsPanel(settings: AudiobookSettingsConfig): LinearLayout {
        val density = resources.displayMetrics.density
        val customColorHolder = intArrayOf(settings.floatingOverlaySubtitleCustomColor)
        var currentR = Color.red(customColorHolder[0])
        var currentG = Color.green(customColorHolder[0])
        var currentB = Color.blue(customColorHolder[0])
        val presetColors = listOf(
            FLOATING_OVERLAY_SUBTITLE_COLOR_WHITE,
            FLOATING_OVERLAY_SUBTITLE_COLOR_YELLOW,
            FLOATING_OVERLAY_SUBTITLE_COLOR_GREEN,
            FLOATING_OVERLAY_SUBTITLE_COLOR_CYAN
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = createRoundedBackground(0xCC151515.toInt(), cornerDp = 18f, strokeColor = 0x33FFFFFF)
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            addView(TextView(this@AudiobookFloatingOverlayService).apply {
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 12f
                text = getOverlayLocalizedString(R.string.audiobook_overlay_subtitle_style)
            })
            val customInfo = TextView(this@AudiobookFloatingOverlayService).apply {
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 11f
                text = "RGB($currentR,$currentG,$currentB)"
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
            }

            lateinit var customColorButton: View
            val presetColorButtons = linkedMapOf<Int, View>()
            val customPanel = LinearLayout(this@AudiobookFloatingOverlayService).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
            }

            val refreshCustomUi = {
                customInfo.text = "RGB($currentR,$currentG,$currentB)"
                val selectedColor = loadAudiobookSettingsConfig(context).floatingOverlaySubtitleColor
                presetColorButtons.forEach { (color, button) ->
                    button.background = createSubtitleColorSwatchDrawable(
                        color = color,
                        selected = selectedColor == color,
                        density = density
                    )
                }
                val selected = selectedColor == customColorHolder[0]
                customColorButton.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(customColorHolder[0])
                    setStroke(
                        (if (selected) 3 else 1) * density.toInt().coerceAtLeast(1),
                        if (selected) 0xFFFFFFFF.toInt() else 0x44FFFFFF
                    )
                }
            }

            addView(LinearLayout(this@AudiobookFloatingOverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
                presetColors.forEach { color ->
                    val button = createColorButton(
                        color = color,
                        currentColor = settings.floatingOverlaySubtitleColor,
                        onColorSelected = { refreshCustomUi() }
                    )
                    presetColorButtons[color] = button
                    addView(button)
                }
                customColorButton = createCustomColorButton(
                    color = customColorHolder[0],
                    selected = settings.floatingOverlaySubtitleColor == customColorHolder[0]
                ) {
                    subtitleSettingsExpanded = true
                    customPanel.visibility = if (customPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    customInfo.visibility = customPanel.visibility
                    saveAudiobookFloatingOverlaySubtitleColor(context, customColorHolder[0])
                    applySubtitleTypography(loadAudiobookSettingsConfig(context))
                    refreshCustomUi()
                }
                addView(customColorButton)
            })
            customPanel.addView(createRgbSliderRow(
                label = "R",
                initial = currentR,
                onLiveChange = { v ->
                    currentR = v
                    customColorHolder[0] = Color.rgb(currentR, currentG, currentB)
                    saveAudiobookFloatingOverlaySubtitleColor(context, customColorHolder[0])
                    applySubtitleTypography(loadAudiobookSettingsConfig(context))
                    refreshCustomUi()
                },
                onFinalChange = {
                    saveAudiobookFloatingOverlaySubtitleCustomColor(context, customColorHolder[0])
                    saveAudiobookFloatingOverlaySubtitleColor(context, customColorHolder[0])
                    refreshCustomUi()
                }
            ))
            customPanel.addView(createRgbSliderRow(
                label = "G",
                initial = currentG,
                onLiveChange = { v ->
                    currentG = v
                    customColorHolder[0] = Color.rgb(currentR, currentG, currentB)
                    saveAudiobookFloatingOverlaySubtitleColor(context, customColorHolder[0])
                    applySubtitleTypography(loadAudiobookSettingsConfig(context))
                    refreshCustomUi()
                },
                onFinalChange = {
                    saveAudiobookFloatingOverlaySubtitleCustomColor(context, customColorHolder[0])
                    saveAudiobookFloatingOverlaySubtitleColor(context, customColorHolder[0])
                    refreshCustomUi()
                }
            ))
            customPanel.addView(createRgbSliderRow(
                label = "B",
                initial = currentB,
                onLiveChange = { v ->
                    currentB = v
                    customColorHolder[0] = Color.rgb(currentR, currentG, currentB)
                    saveAudiobookFloatingOverlaySubtitleColor(context, customColorHolder[0])
                    applySubtitleTypography(loadAudiobookSettingsConfig(context))
                    refreshCustomUi()
                },
                onFinalChange = {
                    saveAudiobookFloatingOverlaySubtitleCustomColor(context, customColorHolder[0])
                    saveAudiobookFloatingOverlaySubtitleColor(context, customColorHolder[0])
                    refreshCustomUi()
                }
            ))
            addView(customInfo)
            addView(customPanel)
            addView(LinearLayout(this@AudiobookFloatingOverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * density).toInt() }
                addView(TextView(this@AudiobookFloatingOverlayService).apply {
                    text = getOverlayLocalizedString(R.string.audiobook_overlay_subtitle_scroll)
                    setTextColor(0xCCFFFFFF.toInt())
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                })
                addView(Switch(this@AudiobookFloatingOverlayService).apply {
                    isChecked = settings.floatingOverlaySubtitleScrollEnabled
                    setOnCheckedChangeListener { _, checked ->
                        saveAudiobookFloatingOverlaySubtitleScrollEnabled(context, checked)
                        updateSubtitleAutoScroll()
                    }
                })
            })
            addView(LinearLayout(this@AudiobookFloatingOverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * density).toInt() }
                addView(createTextButton("A-") {
                    val next = (loadAudiobookSettingsConfig(context).floatingOverlaySubtitleSizeSp - 2)
                        .coerceAtLeast(MIN_FLOATING_OVERLAY_SUBTITLE_SIZE_SP)
                    saveAudiobookFloatingOverlaySubtitleSizeSp(context, next)
                    applySubtitleTypography(loadAudiobookSettingsConfig(context))
                    rootView?.post { alignOverlayWindow(force = true) }
                })
                addView(createTextButton("A+") {
                    val next = (loadAudiobookSettingsConfig(context).floatingOverlaySubtitleSizeSp + 2)
                        .coerceAtMost(MAX_FLOATING_OVERLAY_SUBTITLE_SIZE_SP)
                    saveAudiobookFloatingOverlaySubtitleSizeSp(context, next)
                    applySubtitleTypography(loadAudiobookSettingsConfig(context))
                    rootView?.post { alignOverlayWindow(force = true) }
                }.apply {
                    (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0
                })
            })
            refreshCustomUi()
        }
    }

    private fun buildBubblePanel(bubbleSizePx: Int): FrameLayout {
        val density = resources.displayMetrics.density
        val bubbleScale = (bubbleSizePx.toFloat() / (DEFAULT_FLOATING_OVERLAY_SIZE_DP * density))
            .coerceIn(0.62f, 1.4f)
        val host = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        bubbleRow = row

        val bubble = ImageButton(this).apply {
            setImageResource(R.drawable.ic_overlay_pause)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xDD151515.toInt())
            }
            setColorFilter(0xFFFFFFFF.toInt())
            layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(bubbleSizePx / 4, bubbleSizePx / 4, bubbleSizePx / 4, bubbleSizePx / 4)
            setOnTouchListener(
                OverlayDragTouchListener(
                    gestureDetector = GestureDetector(
                        this@AudiobookFloatingOverlayService,
                        object : GestureDetector.SimpleOnGestureListener() {
                            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                                BookReaderFloatingBridge.togglePlayPause()
                                updateBubbleIcon(BookReaderFloatingBridge.isPlaying())
                                updatePlayPauseIcon(BookReaderFloatingBridge.isPlaying())
                                return true
                            }

                            override fun onDoubleTap(e: MotionEvent): Boolean {
                                toggleBubbleControlsVisibility(animated = true)
                                return true
                            }

                            override fun onDown(e: MotionEvent): Boolean = true
                        }
                    ),
                    dragAllowed = { !bubbleOverlayLocked },
                    layoutParamsProvider = { bubbleWindowLayoutParams },
                    hostViewProvider = { bubbleRootView },
                    horizontalBoundsProvider = {
                        val screenWidth = resources.displayMetrics.widthPixels
                        val bubbleWidth = bubbleButton?.width
                            ?.takeIf { it > 0 }
                            ?: (bubbleButton?.layoutParams?.width ?: bubbleSizePx)
                        0..(screenWidth - bubbleWidth).coerceAtLeast(0)
                    },
                    onPersistPosition = { x, y ->
                        saveAudiobookFloatingOverlayBubblePosition(
                            this@AudiobookFloatingOverlayService,
                            x,
                            y
                        )
                    }
                )
            )
        }
        bubbleButton = bubble

        val controlsTemplate = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = 0
            background = createRoundedBackground(
                0x55151515,
                cornerDp = 24f * bubbleScale,
                strokeColor = 0x22FFFFFF
            )
            setPadding(
                (10 * density * bubbleScale).toInt(),
                (5 * density * bubbleScale).toInt(),
                (16 * density * bubbleScale).toInt(),
                (5 * density * bubbleScale).toInt()
            )
            addView(createControlButton(R.drawable.ic_overlay_lock, bubbleScale) {
                bubbleOverlayLocked = !bubbleOverlayLocked
                if (bubbleOverlayLocked) {
                    bubbleControlsVisible = false
                    bubbleControlsRow?.animate()?.cancel()
                    bubbleControlsRow?.visibility = View.GONE
                    bubbleControlsRow?.alpha = 0f
                }
                updateBubbleLockIcon()
            }.also { bubbleLockButton = it })
            addView(createControlButton(R.drawable.ic_overlay_previous, bubbleScale) {
                BookReaderFloatingBridge.seekAdjacent(this@AudiobookFloatingOverlayService, -1)
            })
            addView(createControlButton(R.drawable.ic_overlay_next, bubbleScale) {
                BookReaderFloatingBridge.seekAdjacent(this@AudiobookFloatingOverlayService, 1)
            })
        }
        val bubbleParams = LinearLayout.LayoutParams(bubbleSizePx, bubbleSizePx).apply {
            marginEnd = (8 * density * bubbleScale).toInt()
        }
        bubble.layoutParams = bubbleParams

        val controls = controlsTemplate.apply {
            visibility = View.GONE
            alpha = 1f
        }
        bubbleControlsRow = controls
        updateBubbleLockIcon()
        row.addView(bubble)
        row.addView(controls)
        host.addView(row)
        return host
    }

    private fun toggleBubbleControlsVisibility(animated: Boolean) {
        val controls = bubbleControlsRow ?: return
        val targetVisible = !bubbleControlsVisible
        logDebug(FLOATING_BUBBLE_LOG_TAG) {
            "toggle targetVisible=$targetVisible"
        }
        bubbleControlsVisible = targetVisible
        if (!animated) {
            controls.animate().cancel()
            controls.visibility = if (bubbleControlsVisible) View.VISIBLE else View.GONE
            controls.alpha = if (bubbleControlsVisible) 1f else 0f
            return
        }
        controls.animate().cancel()
        if (bubbleControlsVisible) {
            controls.visibility = View.VISIBLE
            controls.alpha = 0f
            controls.animate()
                .alpha(1f)
                .setDuration(160L)
                .start()
        } else {
            controls.animate()
                .alpha(0f)
                .setDuration(140L)
                .withEndAction {
                    controls.visibility = View.GONE
                }
                .start()
        }
    }

    private fun updateSubtitleControlsVisibility(settings: AudiobookSettingsConfig) {
        val controls = subtitleControlsRow ?: return
        val panel = subtitleSettingsPanel ?: return
        if (!settings.floatingOverlaySubtitleEnabled) {
            controls.visibility = View.GONE
            panel.visibility = View.GONE
            hideSubtitleSettingsOverlay()
            return
        }
        val verticalWriting = settings.floatingOverlaySubtitleWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL
        controls.visibility = when {
            subtitleControlsVisible -> View.VISIBLE
            verticalWriting -> View.INVISIBLE
            else -> View.GONE
        }
        if (verticalWriting) {
            // Vertical mode uses floating settings panel anchored above settings button.
            panel.visibility = View.VISIBLE
            if (subtitleControlsVisible && subtitleSettingsExpanded) {
                showOrUpdateSubtitleSettingsOverlay(settings)
            } else {
                hideSubtitleSettingsOverlay()
            }
        } else {
            // Horizontal mode keeps settings inline under toolbar.
            hideSubtitleSettingsOverlay()
            if (panel.parent == null) {
                subtitleSettingsInlineHost?.addView(panel)
            }
            panel.visibility = if (subtitleControlsVisible && subtitleSettingsExpanded) View.VISIBLE else View.GONE
        }
    }

    private fun showOrUpdateSubtitleSettingsOverlay(settings: AudiobookSettingsConfig) {
        if (settings.floatingOverlaySubtitleWritingMode != FloatingSubtitleWritingMode.VERTICAL_RTL) {
            hideSubtitleSettingsOverlay()
            return
        }
        val panel = subtitleSettingsPanel ?: return
        val settingsButton = subtitleSettingsButton
        val wm = windowManager ?: return
        val margin = (8f * resources.displayMetrics.density)
        val overlay = subtitleSettingsFloatingView ?: FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            if (panel.parent != null) {
                (panel.parent as? android.view.ViewGroup)?.removeView(panel)
            }
            addView(
                panel,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }.also { subtitleSettingsFloatingView = it }

        panel.visibility = View.VISIBLE
        panel.translationX = 0f
        panel.translationY = 0f
        panel.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val panelW = panel.measuredWidth.coerceAtLeast(1)
        val panelH = panel.measuredHeight.coerceAtLeast(1)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val loc = IntArray(2)
        settingsButton?.getLocationOnScreen(loc)
        val anchorX = if (settingsButton != null) loc[0] + (settingsButton.width / 2) else (screenW / 2)
        val anchorY = if (settingsButton != null) loc[1] else (screenH / 2)
        val targetX = (anchorX - (panelW / 2)).coerceIn(0, (screenW - panelW).coerceAtLeast(0))
        val targetY = (anchorY - panelH - margin.toInt()).coerceIn(0, (screenH - panelH).coerceAtLeast(0))

        val lp = subtitleSettingsWindowLayoutParams
        if (lp == null) {
            val params = createOverlayLayoutParams(targetX, targetY).apply {
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
            }
            subtitleSettingsWindowLayoutParams = params
            runCatching { wm.addView(overlay, params) }
        } else {
            lp.x = targetX
            lp.y = targetY
            runCatching { wm.updateViewLayout(overlay, lp) }
        }
    }

    private fun hideSubtitleSettingsOverlay() {
        val wm = windowManager ?: return
        val overlay = subtitleSettingsFloatingView ?: return
        runCatching { wm.removeView(overlay) }
        subtitleSettingsFloatingView = null
        subtitleSettingsWindowLayoutParams = null
    }

    private fun handleSubtitleSingleTap(event: MotionEvent) {
        val settings = loadAudiobookSettingsConfig(this)
        val verticalWriting = settings.floatingOverlaySubtitleWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL
        val verticalSubtitle = subtitleVerticalCanvasView
        val subtitle = subtitleTextView
        logDebug(FLOATING_SUBTITLE_HIT_LOG_TAG) {
            "subtitleTap vertical=$verticalWriting x=${event.x} y=${event.y} subtitleVisible=${subtitle?.visibility == View.VISIBLE || verticalSubtitle?.visibility == View.VISIBLE}"
        }
        if (verticalWriting && verticalSubtitle != null) {
            verticalSubtitle.selectAt(event.x, event.y)
            return
        }
        if (subtitle == null) return
        val layout = subtitle.layout ?: return
        val x = (event.x - subtitle.totalPaddingLeft).coerceAtLeast(0f)
        val y = (event.y - subtitle.totalPaddingTop + subtitle.scrollY).coerceAtLeast(0f)
        val line = layout.getLineForVertical(y.toInt().coerceAtLeast(0))
        val offset = layout.getOffsetForHorizontal(line, x)
        val initialRange = IntRange(offset, offset)
        val initialAnchorRect = computeSubtitleAnchorRects(subtitle, initialRange).firstOrNull()
        logDebug(FLOATING_SUBTITLE_HIT_LOG_TAG) {
            "subtitleAnchor initialRange=${initialRange.first}..${initialRange.last} initialAnchor=${initialAnchorRect?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: "none"} layoutLines=${layout.lineCount} subtitleSize=${subtitle.width}x${subtitle.height}"
        }
        performFloatingLookup(offset, initialAnchorRect)
    }

    private fun setSubtitleTextWidthMode(matchParent: Boolean) {
        val widthMode = if (matchParent) FrameLayout.LayoutParams.MATCH_PARENT else FrameLayout.LayoutParams.WRAP_CONTENT
        listOfNotNull<View>(subtitleTextView, subtitleOutlineTextView).forEach { tv ->
            val lp = (tv.layoutParams as? FrameLayout.LayoutParams) ?: return@forEach
            if (lp.width != widthMode) {
                lp.width = widthMode
                tv.layoutParams = lp
            }
        }
    }

    private fun setSubtitleTextExactWidth(widthPx: Int) {
        val safeWidth = widthPx.coerceAtLeast(1)
        listOfNotNull<View>(subtitleTextView, subtitleOutlineTextView).forEach { tv ->
            val lp = (tv.layoutParams as? FrameLayout.LayoutParams) ?: return@forEach
            if (lp.width != safeWidth) {
                lp.width = safeWidth
                tv.layoutParams = lp
            }
        }
    }

    private fun setSubtitleTranslationX(dx: Float) {
        subtitleTextView?.translationX = dx
        subtitleOutlineTextView?.translationX = dx
        subtitleVerticalCanvasView?.translationX = dx
    }

    private fun setSubtitleTranslationY(dy: Float) {
        subtitleTextView?.translationY = dy
        subtitleOutlineTextView?.translationY = dy
        subtitleVerticalCanvasView?.translationY = dy
    }

    private fun setSubtitleFrameHeight(heightPx: Int?) {
        val frame = subtitleFrameView ?: return
        val lp = (frame.layoutParams as? LinearLayout.LayoutParams) ?: return
        val targetHeight = heightPx ?: LinearLayout.LayoutParams.WRAP_CONTENT
        if (lp.height != targetHeight) {
            lp.height = targetHeight
            frame.layoutParams = lp
        }
    }

    private fun applyPunctuationPause(linear: Float, text: String): Float {
        val normalized = linear.coerceIn(0f, 1f)
        if (normalized <= 0f) return 0f
        val compact = text.filter { it != '\n' && it != '\r' }
        if (compact.length <= 1) return normalized

        val marks = compact.withIndex()
            .filter { it.value == '、' }
            .map { it.index.toFloat() / (compact.length - 1).toFloat() }
        if (marks.isEmpty()) return normalized

        // Smooth pause model:
        // Each punctuation contributes a cumulative delay via sigmoid, creating
        // smooth deceleration near punctuation and smooth recovery afterwards.
        val perHold = 0.06f
        val totalHold = (marks.size * perHold).coerceAtMost(0.45f)
        val holdEach = (totalHold / marks.size).coerceAtLeast(0.01f)
        val sigma = 0.03f
        val startDelay = 0.02f

        var delay = 0f
        for (p in marks) {
            val center = (p + startDelay).coerceIn(0f, 1f)
            val z = ((normalized - center) / sigma).coerceIn(-12f, 12f)
            val s = (1f / (1f + kotlin.math.exp(-z)))
            delay += holdEach * s
        }
        // Re-normalize to keep fixed endpoints: f(0)=0, f(1)=1.
        fun rawAt(t: Float): Float {
            var d = 0f
            for (p in marks) {
                val center = (p + startDelay).coerceIn(0f, 1f)
                val z = ((t - center) / sigma).coerceIn(-12f, 12f)
                val s = (1f / (1f + kotlin.math.exp(-z)))
                d += holdEach * s
            }
            return (t - d)
        }

        val raw0 = rawAt(0f)
        val raw1 = rawAt(1f)
        val denom = (raw1 - raw0)
        if (denom <= 1e-5f) return normalized
        val raw = (normalized - delay)
        return ((raw - raw0) / denom).coerceIn(0f, 1f)
    }

    private fun updateSubtitleText(text: String?) {
        val subtitle = subtitleTextView ?: return
        val outline = subtitleOutlineTextView
        val verticalSubtitle = subtitleVerticalCanvasView
        val settings = loadAudiobookSettingsConfig(this)
        val verticalWriting = settings.floatingOverlaySubtitleWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL
        val normalized = text?.trim()?.takeIf { it.isNotEmpty() }
        val subtitleEnabledByData = settings.floatingOverlaySubtitleEnabled && hasSubtitleData(normalized)
        val displayText = normalized
        if (!subtitleEnabledByData || normalized == null) {
            subtitle.setSelectedRange(null)
            subtitle.animate().cancel()
            subtitle.text = ""
            subtitle.visibility = View.GONE
            subtitle.scrollTo(0, 0)
            subtitle.translationX = 0f
            subtitle.translationY = 0f
            outline?.text = ""
            outline?.visibility = View.GONE
            outline?.scrollTo(0, 0)
            outline?.translationX = 0f
            outline?.translationY = 0f
            verticalSubtitle?.visibility = View.GONE
            verticalSubtitle?.translationX = 0f
            verticalSubtitle?.translationY = 0f
            verticalSubtitle?.setSelectedSourceRange(null)
            stopSubtitleTicker()
            hideFloatingLookup()
            rootView?.post { alignOverlayWindow(force = true) }
            return
        }
        if (verticalWriting && verticalSubtitle != null) {
            subtitle.setSelectedRange(null)
            subtitle.animate().cancel()
            subtitle.text = ""
            subtitle.visibility = View.GONE
            outline?.text = ""
            outline?.visibility = View.GONE
            verticalSubtitle.bindText(
                normalized,
                settings.floatingOverlaySubtitleColor,
                settings.floatingOverlaySubtitleSizeSp.toFloat(),
                resolveSubtitleTypeface(this, settings.subtitleCustomFontUri),
            )
            verticalSubtitle.visibility = View.VISIBLE
            verticalSubtitle.alpha = 1f
            verticalSubtitle.translationX = 0f
            verticalSubtitle.translationY = 0f
            verticalSubtitle.setSelectedSourceRange(floatingLookupSession.getOrNull(0)?.selectedRange)
            subtitleTickerBasePositionMs = BookReaderFloatingBridge.currentPlaybackPositionMs()
            subtitleTickerBaseRealtimeMs = SystemClock.uptimeMillis()
            verticalSubtitle.post {
                alignOverlayWindow(force = true)
                updateSubtitleAutoScroll()
                if (BookReaderFloatingBridge.isPlaying()) {
                    startSubtitleTicker()
                }
            }
            return
        }
        verticalSubtitle?.visibility = View.GONE
        if (subtitle.text?.toString() == displayText && subtitle.visibility == View.VISIBLE) {
            // Same cue text can still require a different scroll offset (playhead moved, pause/resume,
            // or overlay refresh). Recompute instead of keeping stale translated position.
            subtitle.post {
                alignOverlayWindow(force = true)
                updateSubtitleAutoScroll()
                if (BookReaderFloatingBridge.isPlaying()) {
                    startSubtitleTicker()
                } else {
                    stopSubtitleTicker()
                }
            }
            return
        }
        subtitle.animate().cancel()
        subtitle.setSelectedRange(null)
        subtitle.alpha = 1f
        subtitle.text = displayText
        subtitle.visibility = View.VISIBLE
        subtitle.scrollTo(0, 0)
        subtitle.translationX = 0f
        subtitle.translationY = 0f
        outline?.text = displayText
        outline?.visibility = if (verticalWriting) View.GONE else View.VISIBLE
        outline?.alpha = if (verticalWriting) 0f else 1f
        outline?.scrollTo(0, 0)
        outline?.translationX = 0f
        outline?.translationY = 0f
        subtitleTickerBasePositionMs = BookReaderFloatingBridge.currentPlaybackPositionMs()
        subtitleTickerBaseRealtimeMs = SystemClock.uptimeMillis()
        subtitle.post {
            alignOverlayWindow(force = true)
            updateSubtitleAutoScroll()
            if (BookReaderFloatingBridge.isPlaying()) {
                startSubtitleTicker()
            }
        }
    }

    private fun updateSubtitleAutoScroll(positionMs: Long = BookReaderFloatingBridge.currentPlaybackPositionMs()) {
        val subtitle = subtitleTextView ?: return
        val outline = subtitleOutlineTextView
        val settings = loadAudiobookSettingsConfig(this)
        if (!settings.floatingOverlaySubtitleEnabled) return
        val verticalWriting = settings.floatingOverlaySubtitleWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL
        val now = System.currentTimeMillis()
        val shouldLog = now - lastSubtitleScrollLogAtMs >= 300L

        if (verticalWriting) {
            val verticalSubtitleView = subtitleVerticalCanvasView
            if (verticalSubtitleView?.visibility != View.VISIBLE) return
            setSubtitleTranslationX(0f)
            setSubtitleFrameHeight(null)
            setSubtitleTextWidthMode(matchParent = false)
            setSubtitleTranslationY(0f)
            if (!settings.floatingOverlaySubtitleScrollEnabled) {
                verticalSubtitleView.setSingleColumnLayout(false)
                verticalSubtitleView.setContentScrollY(0f)
                if (shouldLog) {
                    logDebug(FLOATING_SUBTITLE_SCROLL_LOG_TAG) {
                        "vertical-off pos=$positionMs view=${verticalSubtitleView.width}x${verticalSubtitleView.height}"
                    }
                    lastSubtitleScrollLogAtMs = now
                }
                return
            }
            verticalSubtitleView.setSingleColumnLayout(true)
            val maxScroll = verticalSubtitleView.maxContentScrollY()
            if (maxScroll <= 1f) {
                verticalSubtitleView.setContentScrollY(0f)
                if (shouldLog) {
                    logDebug(FLOATING_SUBTITLE_SCROLL_LOG_TAG) {
                        "vertical-fit pos=$positionMs view=${verticalSubtitleView.width}x${verticalSubtitleView.height} max=${"%.1f".format(maxScroll)}"
                    }
                    lastSubtitleScrollLogAtMs = now
                }
                return
            }
            if (!BookReaderFloatingBridge.isPlaying()) {
                if (shouldLog) {
                    logDebug(FLOATING_SUBTITLE_SCROLL_LOG_TAG) {
                        "vertical-pause pos=$positionMs view=${verticalSubtitleView.width}x${verticalSubtitleView.height} max=${"%.1f".format(maxScroll)}"
                    }
                    lastSubtitleScrollLogAtMs = now
                }
                return
            }
            val cue = BookReaderFloatingBridge.currentCue() ?: return
            val duration = (cue.endMs - cue.startMs).coerceAtLeast(1L)
            val linear = ((positionMs - cue.startMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            val text = BookReaderFloatingBridge.currentSubtitle()?.trim().orEmpty()
            val mapped = applyPunctuationPause(linear, text)
            val targetScroll = maxScroll * mapped
            verticalSubtitleView.setContentScrollY(targetScroll)
            if (shouldLog) {
                logDebug(FLOATING_SUBTITLE_SCROLL_LOG_TAG) {
                    "vertical-tick pos=$positionMs linear=${"%.3f".format(linear)} mapped=${"%.3f".format(mapped)} view=${verticalSubtitleView.width}x${verticalSubtitleView.height} scrollY=${"%.1f".format(targetScroll)} max=${"%.1f".format(maxScroll)} playing=${BookReaderFloatingBridge.isPlaying()} speed=${"%.2f".format(subtitlePlaybackSpeed)}"
                }
                lastSubtitleScrollLogAtMs = now
            }
            return
        }

        if (subtitle.visibility != View.VISIBLE) return
        val text = subtitle.text?.toString().orEmpty()
        if (text.isBlank()) return

        if (!settings.floatingOverlaySubtitleScrollEnabled) {
            setSubtitleTextWidthMode(matchParent = true)
            subtitle.setSingleLine(false)
            subtitle.maxLines = Int.MAX_VALUE
            subtitle.ellipsize = null
            subtitle.setHorizontallyScrolling(false)
            subtitle.gravity = Gravity.CENTER_HORIZONTAL
            subtitle.textAlignment = View.TEXT_ALIGNMENT_CENTER
            outline?.setSingleLine(false)
            outline?.maxLines = Int.MAX_VALUE
            outline?.ellipsize = null
            outline?.setHorizontallyScrolling(false)
            outline?.gravity = Gravity.CENTER_HORIZONTAL
            outline?.textAlignment = View.TEXT_ALIGNMENT_CENTER
            if (subtitle.scrollX != 0) subtitle.scrollTo(0, 0)
            if (outline != null && outline.scrollX != 0) outline.scrollTo(0, 0)
            setSubtitleTranslationX(0f)
            setSubtitleTranslationY(0f)
            if (shouldLog) {
                logDebug(FLOATING_SUBTITLE_SCROLL_LOG_TAG) {
                    "horizontal-off pos=$positionMs textLen=${text.length} scrollX=${subtitle.scrollX}"
                }
                lastSubtitleScrollLogAtMs = now
            }
            return
        }

        setSubtitleTextWidthMode(matchParent = false)
        val viewportWidth = ((subtitle.parent as? View)?.width ?: subtitle.width).toFloat()
        if (viewportWidth <= 1f) return

        subtitle.setSingleLine(true)
        subtitle.maxLines = 1
        subtitle.ellipsize = null
        subtitle.setHorizontallyScrolling(true)
        outline?.setSingleLine(true)
        outline?.maxLines = 1
        outline?.ellipsize = null
        outline?.setHorizontallyScrolling(true)
        val textWidth = subtitle.paint.measureText(text.ifEmpty { " " })
        val contentWidth = textWidth + subtitle.totalPaddingLeft + subtitle.totalPaddingRight
        val maxScroll = (contentWidth - viewportWidth).coerceAtLeast(0f)
        if (maxScroll <= 1f) {
            setSubtitleTextWidthMode(matchParent = true)
            subtitle.gravity = Gravity.START
            subtitle.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            outline?.gravity = Gravity.START
            outline?.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            if (subtitle.scrollX != 0) subtitle.scrollTo(0, 0)
            if (outline != null && outline.scrollX != 0) outline.scrollTo(0, 0)
            val centeredDx = ((viewportWidth - contentWidth) / 2f).coerceAtLeast(0f)
            setSubtitleTranslationX(centeredDx)
            if (shouldLog) {
                logDebug(FLOATING_SUBTITLE_SCROLL_LOG_TAG) {
                    "fit pos=$positionMs viewportWidth=${"%.1f".format(viewportWidth)} textWidth=${"%.1f".format(textWidth)} dx=${"%.1f".format(centeredDx)} max=${"%.1f".format(maxScroll)}"
                }
                lastSubtitleScrollLogAtMs = now
            }
            return
        }

        val contentWidthPx = kotlin.math.ceil(contentWidth.toDouble()).toInt()
        setSubtitleTextExactWidth(contentWidthPx)

        subtitle.gravity = Gravity.START
        subtitle.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        outline?.gravity = Gravity.START
        outline?.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        if (!BookReaderFloatingBridge.isPlaying()) {
            if (shouldLog) {
                logDebug(FLOATING_SUBTITLE_SCROLL_LOG_TAG) {
                    "pause pos=$positionMs width=${"%.1f".format(textWidth)} viewport=${"%.1f".format(viewportWidth)} max=${"%.1f".format(maxScroll)} scrollX=${subtitle.scrollX}"
                }
                lastSubtitleScrollLogAtMs = now
            }
            return
        }
        val cue = BookReaderFloatingBridge.currentCue() ?: return
        val duration = (cue.endMs - cue.startMs).coerceAtLeast(1L)
        val linear = ((positionMs - cue.startMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        // Slow down near punctuation while preserving exact end progress.
        val mapped = applyPunctuationPause(linear, text)
        val minDx = viewportWidth - contentWidth
        val dx = (minDx * mapped).coerceIn(minDx, 0f)
        setSubtitleTranslationX(dx)
        val target = (-dx).toInt().coerceAtLeast(0)
        if (shouldLog) {
            logDebug(FLOATING_SUBTITLE_SCROLL_LOG_TAG) {
                "tick pos=$positionMs linear=${"%.3f".format(linear)} mapped=${"%.3f".format(mapped)} viewportWidth=${"%.1f".format(viewportWidth)} textWidth=${"%.1f".format(textWidth)} dx=${"%.1f".format(dx)} max=${"%.1f".format(maxScroll)} target=$target scrollX=${subtitle.scrollX} transX=${"%.1f".format(subtitle.translationX)} contentW=$contentWidthPx playing=${BookReaderFloatingBridge.isPlaying()} speed=${"%.2f".format(subtitlePlaybackSpeed)}"
            }
            lastSubtitleScrollLogAtMs = now
        }
    }

    private fun startSubtitleTicker() {
        if (subtitleTickerRunning) return
        subtitleTickerRunning = true
        subtitleTickerBasePositionMs = BookReaderFloatingBridge.currentPlaybackPositionMs()
        subtitleTickerBaseRealtimeMs = SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(subtitleFrameCallback)
    }

    private fun stopSubtitleTicker() {
        if (!subtitleTickerRunning) return
        subtitleTickerRunning = false
        Choreographer.getInstance().removeFrameCallback(subtitleFrameCallback)
    }

    private fun applySubtitleSelectionHighlight(selectedRange: IntRange?) {
        val subtitle = subtitleTextView
        val outline = subtitleOutlineTextView
        val verticalSubtitle = subtitleVerticalCanvasView
        val settings = loadAudiobookSettingsConfig(this)
        val baseText = BookReaderFloatingBridge.currentSubtitle()?.trim().orEmpty()
        if (baseText.isBlank()) {
            subtitle?.setSelectedRange(null)
            subtitle?.text = ""
            outline?.text = ""
            verticalSubtitle?.setSelectedSourceRange(null)
            return
        }
        if (settings.floatingOverlaySubtitleWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL) {
            subtitle?.setSelectedRange(null)
            verticalSubtitle?.bindText(
                baseText,
                settings.floatingOverlaySubtitleColor,
                settings.floatingOverlaySubtitleSizeSp.toFloat(),
                resolveSubtitleTypeface(this, settings.subtitleCustomFontUri),
            )
            verticalSubtitle?.setSelectedSourceRange(selectedRange)
            verticalSubtitle?.visibility = View.VISIBLE
            subtitle?.text = ""
            subtitle?.visibility = View.GONE
            outline?.text = ""
            outline?.visibility = View.GONE
            return
        }
        if (subtitle == null) return
        verticalSubtitle?.setSelectedSourceRange(null)
        verticalSubtitle?.visibility = View.GONE
        if (selectedRange == null || selectedRange.first !in baseText.indices) {
            subtitle.text = baseText
            subtitle.setSelectedRange(null)
            outline?.text = baseText
            return
        }
        val endExclusive = (selectedRange.last + 1).coerceAtMost(baseText.length)
        if (endExclusive <= selectedRange.first) {
            subtitle.text = baseText
            subtitle.setSelectedRange(null)
            outline?.text = baseText
            return
        }
        subtitle.text = baseText
        subtitle.setSelectedRange(selectedRange.first until endExclusive)
        outline?.text = baseText
    }

    private fun updateFloatingLookupPanelPosition() {
        rootView?.post { alignOverlayWindow(force = false) }
        val settings = loadAudiobookSettingsConfig(this)
        if (subtitleControlsVisible && subtitleSettingsExpanded && settings.floatingOverlaySubtitleEnabled) {
            showOrUpdateSubtitleSettingsOverlay(settings)
        }
    }

    private fun alignOverlayWindow(force: Boolean) {
        val root = rootView ?: return
        val params = windowLayoutParams ?: return
        val wm = windowManager ?: return
        val settings = loadAudiobookSettingsConfig(this)
        if (!settings.floatingOverlaySubtitleEnabled) return
        if (root.measuredWidth <= 0 || root.measuredHeight <= 0) {
            root.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }
        val centeredX = ((resources.displayMetrics.widthPixels - root.measuredWidth) / 2).coerceAtLeast(0)
        val shouldKeepX = settings.floatingOverlaySubtitleWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL
        val targetX = if (shouldKeepX) params.x else centeredX
        if (force || params.x != targetX) {
            params.x = targetX
            runCatching { wm.updateViewLayout(root, params) }
            saveAudiobookFloatingOverlaySubtitlePosition(this, params.x.coerceAtLeast(0), params.y.coerceAtLeast(0))
        }
    }

    private fun floatingSubtitleHorizontalBounds(): IntRange {
        val screenWidth = resources.displayMetrics.widthPixels
        val audiobookSettings = loadAudiobookSettingsConfig(this)
        if (audiobookSettings.floatingOverlaySubtitleWritingMode != FloatingSubtitleWritingMode.VERTICAL_RTL) {
            val rootWidth = rootView?.width?.takeIf { it > 0 } ?: rootView?.measuredWidth?.takeIf { it > 0 } ?: 0
            return 0..(screenWidth - rootWidth).coerceAtLeast(0)
        }
        val controlsWidth = subtitleControlsRow?.width?.takeIf { it > 0 }
            ?: subtitleControlsRow?.measuredWidth?.takeIf { it > 0 }
            ?: 0
        val subtitleWidth = subtitleVerticalCanvasView?.width?.takeIf { it > 0 }
            ?: subtitleVerticalCanvasView?.measuredWidth?.takeIf { it > 0 }
            ?: subtitleFrameView?.width?.takeIf { it > 0 }
            ?: subtitleFrameView?.measuredWidth?.takeIf { it > 0 }
            ?: 0
        val visibleWidth = (controlsWidth + subtitleWidth)
            .takeIf { it > 0 }
            ?: rootView?.width?.takeIf { it > 0 }
            ?: rootView?.measuredWidth?.takeIf { it > 0 }
            ?: 0
        return 0..(screenWidth - visibleWidth).coerceAtLeast(0)
    }

    private fun computeSubtitleAnchorRects(subtitle: TextView, range: IntRange): List<Rect> {
        val layout = subtitle.layout ?: return emptyList()
        val text = subtitle.text ?: return emptyList()
        if (text.isEmpty()) return emptyList()
        val safeStart = range.first.coerceIn(0, text.length - 1)
        val safeEndExclusive = (range.last + 1).coerceIn(safeStart + 1, text.length)
        val startLine = layout.getLineForOffset(safeStart)
        val endLine = layout.getLineForOffset((safeEndExclusive - 1).coerceAtLeast(safeStart))
        val location = IntArray(2)
        subtitle.getLocationOnScreen(location)
        val leftPadding = location[0].toFloat() + subtitle.totalPaddingLeft
        val topPadding = location[1].toFloat() + subtitle.totalPaddingTop
        val rects = buildList {
            for (line in startLine..endLine) {
                val lineStart = maxOf(safeStart, layout.getLineStart(line))
                val lineEndExclusive = minOf(safeEndExclusive, layout.getLineEnd(line))
                if (lineEndExclusive <= lineStart) continue
                val left = layout.getPrimaryHorizontal(lineStart)
                val right = if (lineEndExclusive < text.length) {
                    layout.getPrimaryHorizontal(lineEndExclusive)
                } else {
                    val lastChar = text[(lineEndExclusive - 1).coerceAtLeast(lineStart)]
                    layout.getPrimaryHorizontal(lineEndExclusive - 1) + subtitle.paint.measureText(lastChar.toString())
                }
                add(
                    Rect(
                        left = leftPadding + minOf(left, right),
                        top = topPadding + layout.getLineTop(line),
                        right = leftPadding + maxOf(left, right),
                        bottom = topPadding + layout.getLineBottom(line)
                    )
                )
            }
        }
        logDebug(FLOATING_SUBTITLE_HIT_LOG_TAG) {
            "subtitleAnchor horizontal range=${range.first}..${range.last} safe=${safeStart}..${safeEndExclusive - 1} lines=${startLine}..${endLine} rects=${rects.size} layoutLines=${layout.lineCount} subtitleSize=${subtitle.width}x${subtitle.height}"
        }
        rects.forEachIndexed { index, rect ->
            logDebug(FLOATING_SUBTITLE_HIT_LOG_TAG) {
                "subtitleAnchor rect[$index]=${rect.left},${rect.top},${rect.right},${rect.bottom}"
            }
        }
        return rects
    }

    private fun performFloatingLookup(offset: Int, initialAnchorRect: Rect?) {
        val subtitleText = BookReaderFloatingBridge.currentSubtitle()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val audiobookSettings = loadAudiobookSettingsConfig(this)
        pausePlaybackForFloatingLookupIfNeeded(audiobookSettings)
        logDebug(FLOATING_LOOKUP_TAP_LOG_TAG) {
            "floating lookup request offset=$offset subtitleLen=${subtitleText.length} anchor=${initialAnchorRect?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: "none"}"
        }
        val selection = selectLookupScanText(
            text = subtitleText,
            charOffset = offset,
            stopAtParticleBoundary = false
        ) ?: run {
            logDebug(FLOATING_LOOKUP_TAP_LOG_TAG) {
                "floating lookup aborted no_selection offset=$offset subtitleLen=${subtitleText.length}"
            }
            hideFloatingLookup()
            return
        }
        val term = selection.text.trim().takeIf { it.isNotBlank() } ?: run {
            logDebug(FLOATING_LOOKUP_TAP_LOG_TAG) {
                "floating lookup aborted blank_term offset=$offset subtitleLen=${subtitleText.length}"
            }
            hideFloatingLookup()
            return
        }
        logDebug(FLOATING_LOOKUP_TAP_LOG_TAG) {
            "floating lookup start offset=$offset term='${term.take(24)}' range=${selection.range.first}..${selection.range.last} subtitleLen=${subtitleText.length}"
        }
        val requestNonce = lookupRequestNonce + 1L
        lookupRequestNonce = requestNonce
        serviceScope.launch {
            val anchorForSelection = initialAnchorRect ?: Rect(
                left = resources.displayMetrics.widthPixels * 0.5f,
                top = resources.displayMetrics.heightPixels * 0.45f,
                right = resources.displayMetrics.widthPixels * 0.5f + 1f,
                bottom = resources.displayMetrics.heightPixels * 0.45f + 1f
            )
            val hoshiSelection = createFloatingHoshiSelection(
                selectedText = term,
                subtitleText = subtitleText,
                selectedRange = selection.range,
                anchorRect = anchorForSelection
            )
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    floatingHoshiLookupSession.createPopup(
                        selection = hoshiSelection,
                        options = floatingHoshiLookupOptions(audiobookSettings),
                    )
                }
            }
            if (lookupRequestNonce != requestNonce) return@launch
            result.onSuccess { popup ->
                val hoshiResults = popup?.first?.state?.results.orEmpty()
                logDebug(FLOATING_LOOKUP_TAP_LOG_TAG) {
                    "floating hoshi lookup result hits=${hoshiResults.size} requestNonce=$requestNonce"
                }
                if (hoshiResults.isEmpty()) {
                    logDebug(FLOATING_LOOKUP_TAP_LOG_TAG) {
                        "floating lookup result empty term='${term.take(24)}' requestNonce=$requestNonce"
                    }
                    applySubtitleSelectionHighlight(null)
                    hideFloatingLookup()
                    return@onSuccess
                }
                val matchedLength = popup?.second
                    ?: hoshiResults.firstOrNull()?.matched?.length
                    ?: term.length
                    ?: 1
                val trimmedRange = trimSelectionRangeByMatchedLength(selection.range, matchedLength) ?: selection.range
                val finalSelectionText = subtitleText.substring(trimmedRange.first, trimmedRange.last + 1)
                val verticalWriting = loadAudiobookSettingsConfig(this@AudiobookFloatingOverlayService)
                    .floatingOverlaySubtitleWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL
                fun finishRender(selectionRects: List<Rect>) {
                    if (lookupRequestNonce != requestNonce) return
                    val anchorRects = selectionRects.takeIf { it.isNotEmpty() }
                        ?: initialAnchorRect?.let { listOf(it) }
                        ?: emptyList()
                    logDebug(FLOATING_LOOKUP_TAP_LOG_TAG) {
                        "floating lookup anchor resolved trimmed=${trimmedRange.first}..${trimmedRange.last} anchorRects=${anchorRects.size} avoidRects=${selectionRects.size} subtitleView=${subtitleTextView?.width ?: -1}x${subtitleTextView?.height ?: -1}"
                    }
                    val dictionaryStyles = popup?.first?.state?.dictionaryStyles ?: currentDictionaryStyles()
                    val estimatedAnchorY = anchorRects.maxOfOrNull { it.bottom } ?: (resources.displayMetrics.heightPixels * 0.56f)
                    val shouldPlaceBelow = estimatedAnchorY <= (resources.displayMetrics.heightPixels / 2f)
                    val layer = buildFloatingLookupLayer(
                        term = finalSelectionText,
                        sourceTerm = null,
                        selectedRange = trimmedRange,
                        anchor = anchorRects.takeIf { it.isNotEmpty() }?.let { ReaderLookupAnchor(rects = it) },
                        avoidAnchor = selectionRects.takeIf { it.isNotEmpty() }?.let { ReaderLookupAnchor(rects = it) },
                        placeBelow = shouldPlaceBelow,
                        preferSidePlacement = false,
                        hoshiResults = hoshiResults,
                        hoshiDictionaryStyles = dictionaryStyles
                    )
                    // New root lookup after scrolling should not reuse stale card positions.
                    floatingLookupCardPositions.clear()
                    floatingLookupSession.clear()
                    floatingLookupSession.push(layer)
                    renderFloatingLookupResults(layer)
                    serviceScope.launch(Dispatchers.IO) {
                        recordStatisticsLookup(
                            this@AudiobookFloatingOverlayService,
                            BookReaderFloatingBridge.currentBookKey()
                        )
                    }
                }
                if (verticalWriting) {
                    val verticalSubtitle = subtitleVerticalCanvasView
                    if (verticalSubtitle != null) {
                        verticalSubtitle.resolveAnchorRectsForRange(trimmedRange) { resolvedRects ->
                            finishRender(
                                resolvedRects.takeIf { it.isNotEmpty() }
                                    ?: initialAnchorRect?.let { listOf(it) }
                                    ?: emptyList()
                            )
                        }
                        return@onSuccess
                    }
                }
                val anchorRects = subtitleTextView
                    ?.let { computeSubtitleAnchorRects(it, trimmedRange) }
                    ?.takeIf { it.isNotEmpty() }
                    ?: initialAnchorRect?.let { listOf(it) }
                    ?: emptyList()
                finishRender(anchorRects)
            }.onFailure {
                logDebug(FLOATING_LOOKUP_TAP_LOG_TAG) {
                    "floating hoshi lookup failed term='${term.take(24)}' error='${it.message.orEmpty().take(80)}'"
                }
                renderFloatingLookupError(it.message ?: getString(R.string.bookreader_lookup_failed))
            }
        }
    }

    private fun renderFloatingLookupError(message: String) {
        applySubtitleSelectionHighlight(floatingLookupSession.getOrNull(0)?.selectedRange)
        clearFloatingLookupHosts()
        val layerIndex = floatingLookupSession.lastIndex.coerceAtLeast(0)
        val layer = buildFloatingLookupLayer(term = "")
        val sizeSpec = computeFloatingLookupPopupSizeSpec(
            windowSize = IntSize(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels),
            anchor = layer.anchor,
            placeBelow = layer.placeBelow,
            preferSidePlacement = layer.preferSidePlacement
        )
        val card = createFloatingLookupCard(layerIndex, layer, FloatingCardMode.Error(message), sizeSpec.contentMaxHeightPx).apply {
            layoutParams = FrameLayout.LayoutParams(sizeSpec.widthPx, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        showFloatingLookupHost(layerIndex, layer, card)
    }

    private fun floatingHoshiLookupOptions(settings: AudiobookSettingsConfig): LookupPopupOptions =
        LookupPopupOptions(
            isVertical = false,
            isFullWidth = false,
            width = 320,
            height = 250,
            swipeToDismiss = true,
            swipeThreshold = 40,
            topInset = 0.0,
            bottomInset = 0.0,
            dictionarySettings = loadDictionarySettings(this),
            darkMode = isSystemDarkMode(),
            eInkMode = false,
            audioSettings = settings,
            showRangeSelection = settings.lookupRangeSelectionEnabled,
            showPlayAudio = settings.lookupPlaybackAudioEnabled,
            popupActionBar = true,
        )

    private fun createFloatingHoshiSelection(
        selectedText: String,
        subtitleText: String,
        selectedRange: IntRange,
        anchorRect: Rect,
        sentenceOverride: String? = null
    ): ReaderSelectionData {
        val densityScale = resources.displayMetrics.density.coerceAtLeast(0.1f)
        val sourceText = subtitleText.trim().ifBlank { selectedText.trim() }
        val safeStart = selectedRange.first.coerceIn(0, sourceText.length.coerceAtLeast(1) - 1)
        val safeEndExclusive = (selectedRange.last + 1).coerceIn(safeStart + 1, sourceText.length.coerceAtLeast(safeStart + 1))
        val cueSnapshot = BookReaderFloatingBridge.currentCue()
        val fullSentence = sentenceOverride
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: cueSnapshot?.fullSentenceText?.trim()?.takeIf { it.isNotBlank() }
            ?: sourceText
        val sentenceOffset = when {
            fullSentence === sourceText -> safeStart
            fullSentence == sourceText -> safeStart
            else -> {
                val inSentence = fullSentence.indexOf(sourceText).takeIf { it >= 0 } ?: 0
                (inSentence + safeStart).coerceIn(0, fullSentence.length.coerceAtLeast(1) - 1)
            }
        }
        val scanText = selectedText.trim().ifBlank {
            sourceText.substring(safeStart, safeEndExclusive).trim()
        }.ifBlank { sourceText }
        return ReaderSelectionData(
            text = scanText,
            sentence = fullSentence,
            rect = ReaderSelectionRect(
                x = (anchorRect.left / densityScale).toDouble(),
                y = (anchorRect.top / densityScale).toDouble(),
                width = ((anchorRect.right - anchorRect.left) / densityScale).coerceAtLeast(1f).toDouble(),
                height = ((anchorRect.bottom - anchorRect.top) / densityScale).coerceAtLeast(1f).toDouble()
            ),
            normalizedOffset = 0,
            sentenceOffset = sentenceOffset
        )
    }

    private fun renderFloatingLookupResults(layer: ReaderLookupLayer) {
        if (layer.hoshiResults.isEmpty()) {
            applySubtitleSelectionHighlight(floatingLookupSession.getOrNull(0)?.selectedRange)
        }
        if (floatingLookupSession.size == 0) {
            logDebug(FLOATING_LOOKUP_LOG_TAG) {
                "renderFloatingLookupResults skipped reason=empty_session"
            }
            clearFloatingLookupHosts()
            return
        }
        val windowSize = IntSize(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val activeIndex = floatingLookupSession.lastIndex
        val activeLayer = floatingLookupSession.getOrNull(activeIndex) ?: return
        logDebug(FLOATING_LOOKUP_LOG_TAG) {
            "renderFloatingLookupResults start activeIndex=$activeIndex sessionSize=${floatingLookupSession.size} anchor=${activeLayer.anchor?.rects?.size ?: 0} placeBelow=${activeLayer.placeBelow} preferSide=${activeLayer.preferSidePlacement}"
        }
        val cards = floatingLookupSession.layers.mapIndexed { index, item ->
            val sizeSpec = computeFloatingLookupPopupSizeSpec(
                windowSize = windowSize,
                anchor = item.anchor,
                placeBelow = item.placeBelow,
                preferSidePlacement = item.preferSidePlacement
            )
            FloatingLookupRenderItem(index, item, sizeSpec)
        }
        logDebug(FLOATING_LOOKUP_LOG_TAG) {
            "renderFloatingLookupResults cards=${cards.size} activeIndex=$activeIndex"
        }
        renderFloatingLookupWindows(cards)
    }

    private fun renderFloatingLookupWindows(
        cards: List<FloatingLookupRenderItem>
    ) {
        if (cards.isEmpty()) return
        val layout = floatingLayoutConfig()
        val activeIndex = floatingLookupSession.lastIndex
        val orderedCards = cards.sortedBy { it.layerIndex }
        val visibleLayers = orderedCards.map { it.layerIndex }.toSet()
        floatingLookupHostViews.keys
            .filterNot { it in visibleLayers }
            .toList()
            .forEach { removeFloatingLookupHost(it) }

        orderedCards.forEach { item ->
            val layerIndex = item.layerIndex
            val layer = item.layer
            val signature = floatingHostSignature(layer)
            val existingHost = floatingLookupHostViews[layerIndex]
            val existingSignature = floatingLookupHostSignatures[layerIndex]
            val reuseWarmRoot = layerIndex == 0 &&
                existingHost != null &&
                layer.hoshiResults.isNotEmpty() &&
                floatingHoshiLookupWebViews[layerIndex]?.parent != null
            if (reuseWarmRoot) {
                createFloatingHoshiLookupWebView(
                    layerIndex = layerIndex,
                    layer = layer,
                    maxLookupHeightPx = item.sizeSpec.contentMaxHeightPx,
                    detachFromParent = false
                )
                (existingHost as? ViewGroup)?.getChildAt(0)?.let { card ->
                    card.layoutParams = (card.layoutParams ?: FrameLayout.LayoutParams(
                        item.sizeSpec.widthPx,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )).apply {
                        width = item.sizeSpec.widthPx
                    }
                }
            }
            val reuseHost = existingHost != null && (existingSignature == signature || reuseWarmRoot)
            val host = if (reuseHost) existingHost else {
                val card = createFloatingLookupCard(
                    layerIndex = layerIndex,
                    layer = layer,
                    mode = FloatingCardMode.Results,
                    maxLookupHeightPx = item.sizeSpec.contentMaxHeightPx
                ).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        item.sizeSpec.widthPx,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                FrameLayout(this).apply {
                    clipChildren = false
                    clipToPadding = false
                    addView(card)
                }
            }
            val preferredWidth = item.sizeSpec.widthPx.takeIf { it > 0 }
                ?: (320 * resources.displayMetrics.density).toInt()
            ensureFloatingHostMeasured(host, preferredWidth, force = reuseWarmRoot)
            val popupSize = IntSize(host.measuredWidth, host.measuredHeight)
            val sourceRects = layerAnchorRects(layer)
            val avoidRects = buildFloatingAvoidRects(layerIndex, layout.gapPx)
            logDebug(FLOATING_LOOKUP_LOG_TAG) {
                "avoid layer=$layerIndex rects=${formatFloatingRectsForLog(avoidRects)}"
            }
            val sizeSpec = computeFloatingLookupPopupSizeSpec(
                windowSize = layout.panelSize,
                anchor = layer.anchor,
                placeBelow = layer.placeBelow,
                preferSidePlacement = layer.preferSidePlacement
            )
            val candidate = calculateFloatingCardPosition(
                sourceRects = sourceRects,
                avoidRects = avoidRects,
                windowSize = layout.panelSize,
                popupContentSize = popupSize,
                placeBelow = layer.placeBelow,
                preferSidePlacement = layer.preferSidePlacement,
                preferredDirection = sizeSpec.preferredDirection,
                strictNoOverlap = layerIndex > 0 || avoidRects.isNotEmpty(),
                gapPx = layout.gapPx,
                screenPaddingPx = layout.screenPaddingPx
            ) ?: run {
                logDebug(FLOATING_LOOKUP_LOG_TAG) {
                    "rejectShow layer=$layerIndex reason=no_candidate"
                }
                if (layerIndex == activeIndex && activeIndex > 0) {
                    truncateFloatingLookupLayersTo(activeIndex - 1)
                }
                return@forEach
            }
            logDebug(FLOATING_LOOKUP_LOG_TAG) {
                "showCandidate layer=$layerIndex popupSize=${popupSize.width}x${popupSize.height} anchor=${layer.anchor?.rects?.size ?: 0} placeBelow=${layer.placeBelow} preferSide=${layer.preferSidePlacement} candidate=${candidate.x},${candidate.y}"
            }
            host.alpha = if (isFloatingLookupHostContentReady(layerIndex, layer)) 1f else 0f
            val shown = showFloatingLookupHost(
                layerIndex = layerIndex,
                layer = layer,
                host = host,
                position = candidate,
                signature = signature,
                sourceRectsOverride = sourceRects,
                avoidRectsOverride = avoidRects
            )
            if (!shown && layerIndex == activeIndex) {
                if (activeIndex > 0) {
                    truncateFloatingLookupLayersTo(activeIndex - 1)
                }
                return
            }
            host.visibility = View.VISIBLE
        }
    }

    private fun isFloatingLookupHostContentReady(layerIndex: Int, layer: ReaderLookupLayer): Boolean {
        if (layer.hoshiResults.isEmpty()) return true
        val tag = floatingHoshiLookupWebViews[layerIndex]?.tag as? FloatingHoshiLookupWebViewTag
        return tag?.contentReady == true
    }

    private fun floatingHostSignature(layer: ReaderLookupLayer): Int {
        val base = layer.copy(
            anchor = null,
            selectedRange = null
        ).hashCode()
        return base
    }

    private fun showFloatingLookupHost(
        layerIndex: Int,
        layer: ReaderLookupLayer,
        host: View,
        position: IntOffset? = null,
        signature: Int? = null,
        sourceRectsOverride: List<IntRect>? = null,
        avoidRectsOverride: List<IntRect>? = null
    ): Boolean {
        val wm = windowManager ?: return false
        val layout = floatingLayoutConfig()
        val storageKey = layerIndex
        val hostPosition = position ?: floatingLookupCardPositions[storageKey]
            ?: IntOffset((24 * resources.displayMetrics.density).toInt(), (120 * resources.displayMetrics.density).toInt())
        val sourceRects = sourceRectsOverride ?: layerAnchorRects(layer)
        val avoidRects = avoidRectsOverride ?: buildFloatingAvoidRects(layerIndex, layout.gapPx)
        val showEvaluation = evaluateFloatingPlacement(
            layerIndex = layerIndex,
            sourceRects = sourceRects,
            avoidRects = avoidRects,
            popupPosition = hostPosition,
            popupSize = IntSize(host.measuredWidth, host.measuredHeight),
            gapPx = layout.gapPx,
            screenPaddingPx = layout.screenPaddingPx
        )
        if (!showEvaluation.acceptable) {
            logDebug(FLOATING_LOOKUP_LOG_TAG) {
                "rejectShow layer=$layerIndex pos=${hostPosition.x},${hostPosition.y} guardOverlap=${showEvaluation.metrics.guardOverlap} avoidOverlap=${showEvaluation.metrics.avoidOverlap} distance=${showEvaluation.sourceDistance} maxDistance=${showEvaluation.maxAcceptDistancePx}"
            }
            return false
        }
        val overlapMetrics = computeFloatingLookupOverlapMetrics(
            sourceRects = sourceRects,
            avoidRects = avoidRects,
            popupPosition = hostPosition,
            popupSize = IntSize(host.measuredWidth, host.measuredHeight),
            gapPx = layout.gapPx,
            screenPaddingPx = layout.screenPaddingPx
        )
        val params = createFloatingLookupWindowLayoutParams(hostPosition, touchable = true)
        val currentHost = floatingLookupHostViews[storageKey]
        val isExistingHost = currentHost === host
        if (isExistingHost) {
            runCatching { wm.updateViewLayout(host, params) }
        } else {
            if (currentHost != null) {
                floatingLookupHostSizeListeners.remove(storageKey)?.let { listener ->
                    currentHost.removeOnLayoutChangeListener(listener)
                }
                runCatching { wm.removeView(currentHost) }
            }
            runCatching { wm.addView(host, params) }
            floatingLookupHostViews[storageKey] = host
        }
        attachFloatingLookupHostSizeObserver(layerIndex, host)
        floatingLookupCardPositions[storageKey] = hostPosition
        if (signature != null) {
            floatingLookupHostSignatures[storageKey] = signature
        }
        val popupRect = IntRect(
            left = hostPosition.x,
            top = hostPosition.y,
            right = hostPosition.x + host.measuredWidth,
            bottom = hostPosition.y + host.measuredHeight
        )
        val sourceBounds = sourceRects.takeIf { it.isNotEmpty() }?.let {
            IntRect(
                left = it.minOf { rect -> rect.left },
                top = it.minOf { rect -> rect.top },
                right = it.maxOf { rect -> rect.right },
                bottom = it.maxOf { rect -> rect.bottom }
            )
        }
        val guardPx = computeFloatingLookupGuardPadding(layout.gapPx, layout.screenPaddingPx)
        val guardBounds = sourceRects.takeIf { it.isNotEmpty() }?.map {
            expandFloatingRect(it, guardPx)
        }?.let {
            IntRect(
                left = it.minOf { rect -> rect.left },
                top = it.minOf { rect -> rect.top },
                right = it.maxOf { rect -> rect.right },
                bottom = it.maxOf { rect -> rect.bottom }
            )
        }
        logDebug(FLOATING_LOOKUP_LOG_TAG) {
            "show layer=$layerIndex pos=${hostPosition.x},${hostPosition.y} source=${layer.sourceTerm.orEmpty()} placeBelow=${layer.placeBelow} side=${layer.preferSidePlacement} sourceOverlap=${overlapMetrics.sourceOverlap} guardOverlap=${overlapMetrics.guardOverlap} avoidOverlap=${overlapMetrics.avoidOverlap}"
        }
        logDebug(FLOATING_LOOKUP_LOG_TAG) {
            "rects layer=$layerIndex sourceRects=${formatFloatingRectsForLog(sourceRects)} sourceBounds=${formatFloatingRectForLog(sourceBounds)} guardBounds=${formatFloatingRectForLog(guardBounds)} popupRect=${formatFloatingRectForLog(popupRect)}"
        }
        return true
    }

    private fun formatFloatingRectsForLog(rects: List<IntRect>): String {
        if (rects.isEmpty()) return "[]"
        return rects.joinToString(prefix = "[", postfix = "]") { rect ->
            "${rect.left},${rect.top},${rect.right},${rect.bottom}"
        }
    }

    private fun layerAnchorRects(layer: ReaderLookupLayer): List<IntRect> {
        return layer.anchor
            ?.rects
            ?.filter { !it.isEmpty }
            ?.map { rect ->
                IntRect(
                    left = rect.left.toInt(),
                    top = rect.top.toInt(),
                    right = rect.right.toInt(),
                    bottom = rect.bottom.toInt()
                )
            }
            .orEmpty()
    }

    private fun formatFloatingRectForLog(rect: IntRect?): String {
        if (rect == null) return "null"
        return "${rect.left},${rect.top},${rect.right},${rect.bottom}"
    }

    private fun computeFloatingPopupSourceDistance(
        sourceRects: List<IntRect>,
        popupPosition: IntOffset,
        popupSize: IntSize
    ): Int {
        if (sourceRects.isEmpty()) return 0
        val popupRect = IntRect(
            left = popupPosition.x,
            top = popupPosition.y,
            right = popupPosition.x + popupSize.width,
            bottom = popupPosition.y + popupSize.height
        )
        return sourceRects.minOf { sourceRect -> floatingRectDistance(sourceRect, popupRect) }
    }

    private fun computeFloatingLookupOverlapMetrics(
        sourceRects: List<IntRect>,
        avoidRects: List<IntRect>,
        popupPosition: IntOffset,
        popupSize: IntSize,
        gapPx: Int,
        screenPaddingPx: Int
    ): FloatingLookupOverlapMetrics {
        val popupRect = IntRect(
            left = popupPosition.x,
            top = popupPosition.y,
            right = popupPosition.x + popupSize.width,
            bottom = popupPosition.y + popupSize.height
        )
        val sourceOverlap = sourceRects.maxOfOrNull { rect ->
            floatingRectOverlapArea(rect, popupRect)
        } ?: 0
        val guardPadding = computeFloatingLookupGuardPadding(gapPx, screenPaddingPx)
        val guardOverlap = sourceRects.maxOfOrNull { rect ->
            floatingRectOverlapArea(expandFloatingRect(rect, guardPadding), popupRect)
        } ?: 0
        val avoidOverlap = avoidRects.maxOfOrNull { rect ->
            floatingRectOverlapArea(rect, popupRect)
        } ?: 0
        return FloatingLookupOverlapMetrics(
            sourceOverlap = sourceOverlap,
            guardOverlap = guardOverlap,
            avoidOverlap = avoidOverlap
        )
    }

    private data class FloatingLookupOverlapMetrics(
        val sourceOverlap: Int,
        val guardOverlap: Int,
        val avoidOverlap: Int
    )

    private data class FloatingLayoutConfig(
        val panelSize: IntSize,
        val gapPx: Int,
        val screenPaddingPx: Int
    )

    private data class FloatingPlacementEvaluation(
        val acceptable: Boolean,
        val metrics: FloatingLookupOverlapMetrics,
        val sourceDistance: Int,
        val maxAcceptDistancePx: Int
    )

    private fun floatingLayoutConfig(): FloatingLayoutConfig {
        val density = resources.displayMetrics.density
        return FloatingLayoutConfig(
            panelSize = IntSize(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels),
            gapPx = (4 * density).toInt(),
            screenPaddingPx = (12 * density).toInt()
        )
    }

    private fun buildFloatingAvoidRects(layerIndex: Int, gapPx: Int): List<IntRect> {
        return floatingLookupSession.getOrNull(layerIndex)
            ?.avoidAnchor
            ?.rects
            ?.filter { !it.isEmpty }
            ?.map { rect ->
                IntRect(
                    left = rect.left.toInt(),
                    top = rect.top.toInt(),
                    right = rect.right.toInt(),
                    bottom = rect.bottom.toInt()
                )
            }
            .orEmpty()
    }

    private fun evaluateFloatingPlacement(
        layerIndex: Int,
        sourceRects: List<IntRect>,
        avoidRects: List<IntRect>,
        popupPosition: IntOffset,
        popupSize: IntSize,
        gapPx: Int,
        screenPaddingPx: Int
    ): FloatingPlacementEvaluation {
        val metrics = computeFloatingLookupOverlapMetrics(
            sourceRects = sourceRects,
            avoidRects = avoidRects,
            popupPosition = popupPosition,
            popupSize = popupSize,
            gapPx = gapPx,
            screenPaddingPx = screenPaddingPx
        )
        val sourceDistance = computeFloatingPopupSourceDistance(
            sourceRects = sourceRects,
            popupPosition = popupPosition,
            popupSize = popupSize
        )
        val maxAcceptDistancePx = (resources.displayMetrics.density * 220f).toInt()
        val acceptable = layerIndex <= 0 || (
            metrics.guardOverlap <= 0 &&
                metrics.avoidOverlap <= 0
            )
        return FloatingPlacementEvaluation(
            acceptable = acceptable,
            metrics = metrics,
            sourceDistance = sourceDistance,
            maxAcceptDistancePx = maxAcceptDistancePx
        )
    }

    private fun createFloatingLookupWindowLayoutParams(
        position: IntOffset,
        touchable: Boolean = true
    ): WindowManager.LayoutParams {
        val windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = position.x
            y = position.y
        }
    }

    private fun clearFloatingLookupHosts() {
        logDebug(FLOATING_LOOKUP_LOG_TAG) {
            "clearFloatingLookupHosts active=${floatingLookupHostViews.size} positions=${floatingLookupCardPositions.size}"
        }
        val wm = windowManager
        if (wm != null) {
            floatingLookupHostViews.values.forEach { host ->
                runCatching { wm.removeView(host) }
            }
        }
        floatingLookupHostViews.forEach { (layerIndex, host) ->
            floatingLookupHostSizeListeners.remove(layerIndex)?.let { listener ->
                host.removeOnLayoutChangeListener(listener)
            }
        }
        floatingLookupHostViews.clear()
        floatingLookupHostSignatures.clear()
        floatingLookupHostSizeListeners.clear()
    }

    private fun hideFloatingLookupHostsKeepingWarmRoot() {
        floatingLookupHostViews.keys.filter { it != 0 }.toList().forEach(::removeFloatingLookupHost)
        val rootHost = floatingLookupHostViews[0] ?: return
        floatingLookupRepositionJobs.remove(0)?.cancel()
        floatingLookupHostSizeListeners.remove(0)?.let(rootHost::removeOnLayoutChangeListener)
        rootHost.alpha = 0f
        rootHost.visibility = View.VISIBLE
        val position = floatingLookupCardPositions[0] ?: IntOffset(0, 0)
        windowManager?.let { wm ->
            runCatching {
                wm.updateViewLayout(
                    rootHost,
                    createFloatingLookupWindowLayoutParams(position, touchable = false)
                )
            }
        }
        floatingLookupHostSignatures.remove(0)
    }

    private fun loadFloatingLookupDictionaries(): List<LoadedDictionary> {
        val version = loadDictionaryDataVersion(this)
        val cached = cachedLookupDictionaries
        if (cached != null && cachedLookupDictionariesVersion == version) {
            return cached
        }
        return loadAvailableDictionaries(this).also {
            cachedLookupDictionaries = it
            cachedLookupDictionariesVersion = version
        }
    }

    private fun destroyFloatingHoshiLookupWebViews() {
        floatingHoshiLookupWebViews.values.forEach { webView ->
            (webView.tag as? FloatingHoshiLookupWebViewTag)?.contentReadyGate?.close()
            (webView.parent as? ViewGroup)?.removeView(webView)
            runCatching { webView.destroy() }
        }
        floatingHoshiLookupWebViews.clear()
        floatingHoshiLookupHtmlByLayer.clear()
    }

    private fun removeFloatingLookupHost(layerIndex: Int) {
        val targetKey = if (floatingLookupHostViews.containsKey(layerIndex)) layerIndex else SINGLE_FLOATING_HOST_KEY
        floatingLookupRepositionJobs.remove(targetKey)?.cancel()
        val wm = windowManager
        val host = floatingLookupHostViews.remove(targetKey)
        if (host != null) {
            floatingLookupHostSizeListeners.remove(targetKey)?.let { listener ->
                host.removeOnLayoutChangeListener(listener)
            }
        }
        if (wm != null && host != null) {
            runCatching { wm.removeView(host) }
        }
        floatingLookupHostSignatures.remove(targetKey)
    }

    private fun attachFloatingLookupHostSizeObserver(layerIndex: Int, host: View) {
        val storageKey = layerIndex
        floatingLookupHostSizeListeners.remove(storageKey)?.let { existing ->
            host.removeOnLayoutChangeListener(existing)
        }
        val listener = View.OnLayoutChangeListener { _, _, _, right, bottom, _, _, oldRight, oldBottom ->
            val newWidth = right.coerceAtLeast(0)
            val newHeight = bottom.coerceAtLeast(0)
            val oldWidth = oldRight.coerceAtLeast(0)
            val oldHeight = oldBottom.coerceAtLeast(0)
            if (newWidth != oldWidth || newHeight != oldHeight) {
                logDebug(FLOATING_LOOKUP_LOG_TAG) {
                    "sizeChanged layer=$layerIndex old=${oldWidth}x$oldHeight new=${newWidth}x$newHeight"
                }
                scheduleFloatingLookupHostReposition(layerIndex, reason = "sizeChanged")
            }
        }
        host.addOnLayoutChangeListener(listener)
        floatingLookupHostSizeListeners[storageKey] = listener
    }

    private fun scheduleFloatingLookupHostReposition(
        layerIndex: Int,
        delayMs: Long = 48L,
        reason: String = "unspecified"
    ) {
        if (layerIndex < 0) return
        val storageKey = layerIndex
        floatingLookupRepositionJobs.remove(storageKey)?.cancel()
        floatingLookupRepositionJobs[storageKey] = serviceScope.launch {
            delay(delayMs)
            logDebug(FLOATING_LOOKUP_LOG_TAG) { "repositionRequest layer=$layerIndex reason=$reason delayMs=$delayMs" }
            repositionFloatingLookupHost(layerIndex)
            floatingLookupRepositionJobs.remove(storageKey)
        }
    }

    private fun repositionFloatingLookupHost(layerIndex: Int) {
        val wm = windowManager ?: return
        val layer = floatingLookupSession.getOrNull(layerIndex) ?: return
        val storageKey = layerIndex
        val host = floatingLookupHostViews[storageKey] ?: return
        val layout = floatingLayoutConfig()
        val density = resources.displayMetrics.density
        val measureWidth = host.measuredWidth.takeIf { it > 0 }
            ?: host.width.takeIf { it > 0 }
            ?: ((320 * density).toInt())
        ensureFloatingHostMeasured(host, measureWidth, force = true)
        val popupSize = IntSize(host.measuredWidth, host.measuredHeight)
        val sourceRects = layerAnchorRects(layer)
        val finalAvoidRects = buildFloatingAvoidRects(layerIndex, layout.gapPx)
        val candidate = calculateFloatingCardPosition(
            sourceRects = sourceRects,
            avoidRects = finalAvoidRects,
            windowSize = layout.panelSize,
            popupContentSize = popupSize,
            placeBelow = layer.placeBelow,
            preferSidePlacement = layer.preferSidePlacement,
            preferredDirection = computeFloatingLookupPopupSizeSpec(
                windowSize = layout.panelSize,
                anchor = layer.anchor,
                placeBelow = layer.placeBelow,
                preferSidePlacement = layer.preferSidePlacement
            ).preferredDirection,
            strictNoOverlap = layerIndex > 0 || finalAvoidRects.isNotEmpty(),
            gapPx = layout.gapPx,
            screenPaddingPx = layout.screenPaddingPx
        ) ?: run {
            logDebug(FLOATING_LOOKUP_LOG_TAG) {
                "skipRelayout layer=$layerIndex reason=no_candidate_keep_current"
            }
            return
        }
        val relayoutEvaluation = evaluateFloatingPlacement(
            layerIndex = layerIndex,
            sourceRects = sourceRects,
            avoidRects = finalAvoidRects,
            popupPosition = candidate,
            popupSize = popupSize,
            gapPx = layout.gapPx,
            screenPaddingPx = layout.screenPaddingPx
        )
        if (!relayoutEvaluation.acceptable) {
            logDebug(FLOATING_LOOKUP_LOG_TAG) {
                "skipRelayout layer=$layerIndex pos=${candidate.x},${candidate.y} guardOverlap=${relayoutEvaluation.metrics.guardOverlap} avoidOverlap=${relayoutEvaluation.metrics.avoidOverlap} distance=${relayoutEvaluation.sourceDistance} maxDistance=${relayoutEvaluation.maxAcceptDistancePx}"
            }
            return
        }
        val currentPosition = floatingLookupCardPositions[storageKey]
        if (currentPosition == candidate) return
        runCatching { wm.updateViewLayout(host, createFloatingLookupWindowLayoutParams(candidate, touchable = true)) }
        floatingLookupCardPositions[storageKey] = candidate
        logDebug(FLOATING_LOOKUP_LOG_TAG) { "relayout layer=$layerIndex pos=${candidate.x},${candidate.y}" }
    }

    private fun ensureFloatingHostMeasured(host: View, width: Int, force: Boolean = false) {
        if (!force && host.measuredWidth > 0 && host.measuredHeight > 0) return
        host.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
    }

    private fun calculateFloatingCardPosition(
        sourceRects: List<IntRect>,
        avoidRects: List<IntRect>,
        windowSize: IntSize,
        popupContentSize: IntSize,
        placeBelow: Boolean,
        preferSidePlacement: Boolean,
        preferredDirection: FloatingPopupDirection,
        strictNoOverlap: Boolean = false,
        gapPx: Int,
        screenPaddingPx: Int
    ): IntOffset? {
        val safeSourceRects = sourceRects.takeIf { it.isNotEmpty() } ?: listOf(
            IntRect(
                left = (windowSize.width * 0.4f).toInt(),
                top = (windowSize.height * 0.36f).toInt(),
                right = (windowSize.width * 0.6f).toInt(),
                bottom = (windowSize.height * 0.46f).toInt()
            )
        )
        val guardPx = computeFloatingLookupGuardPadding(gapPx, screenPaddingPx)
        val guardRects = safeSourceRects.map { rect -> expandFloatingRect(rect, guardPx) }
        val sourceBounds = IntRect(
            left = safeSourceRects.minOf { it.left },
            top = safeSourceRects.minOf { it.top },
            right = safeSourceRects.maxOf { it.right },
            bottom = safeSourceRects.maxOf { it.bottom }
        )
        val guardBounds = IntRect(
            left = guardRects.minOf { it.left },
            top = guardRects.minOf { it.top },
            right = guardRects.maxOf { it.right },
            bottom = guardRects.maxOf { it.bottom }
        )
        val maxX = (windowSize.width - popupContentSize.width - screenPaddingPx).coerceAtLeast(screenPaddingPx)
        val maxY = (windowSize.height - popupContentSize.height - screenPaddingPx).coerceAtLeast(screenPaddingPx)
        val preferredX = ((sourceBounds.left + sourceBounds.right) / 2 - popupContentSize.width / 2)
            .coerceIn(screenPaddingPx, maxX)
        val preferredY = ((sourceBounds.top + sourceBounds.bottom) / 2 - popupContentSize.height / 2)
            .coerceIn(screenPaddingPx, maxY)
        val lowerY = (guardBounds.bottom + gapPx).coerceIn(screenPaddingPx, maxY)
        val upperY = (guardBounds.top - popupContentSize.height - gapPx).coerceIn(screenPaddingPx, maxY)
        val yOrder = if (placeBelow) listOf(lowerY, upperY) else listOf(upperY, lowerY)
        val rightX = (guardBounds.right + gapPx).coerceIn(screenPaddingPx, maxX)
        val leftX = (guardBounds.left - popupContentSize.width - gapPx).coerceIn(screenPaddingPx, maxX)
        val sideXOrder = when (preferredDirection) {
            FloatingPopupDirection.RIGHT -> listOf(rightX, leftX)
            FloatingPopupDirection.LEFT -> listOf(leftX, rightX)
            else -> if (preferSidePlacement) listOf(rightX, leftX) else listOf(leftX, rightX)
        }
        val forbiddenRects = (guardRects + avoidRects).distinctBy { rect ->
            "${rect.left},${rect.top},${rect.right},${rect.bottom}"
        }

        fun verticalCandidate(): IntOffset? {
            yOrder.forEach { y ->
                findFloatingNonOverlappingXAtY(
                    y = y,
                    preferredX = preferredX,
                    minX = screenPaddingPx,
                    maxX = maxX,
                    popupWidth = popupContentSize.width,
                    popupHeight = popupContentSize.height,
                    forbiddenRects = forbiddenRects
                )?.let { x ->
                    return IntOffset(x, y)
                }
            }
            return null
        }

        fun sideCandidate(): IntOffset? {
            sideXOrder.forEach { x ->
                findFloatingNonOverlappingYAtX(
                    x = x,
                    preferredY = preferredY,
                    minY = screenPaddingPx,
                    maxY = maxY,
                    popupWidth = popupContentSize.width,
                    popupHeight = popupContentSize.height,
                    forbiddenRects = forbiddenRects
                )?.let { y ->
                    return IntOffset(x, y)
                }
            }
            return null
        }

        val preferSide = preferSidePlacement ||
            preferredDirection == FloatingPopupDirection.LEFT ||
            preferredDirection == FloatingPopupDirection.RIGHT
        val candidate = if (preferSide) {
            sideCandidate() ?: verticalCandidate()
        } else {
            verticalCandidate() ?: sideCandidate()
        }
        if (candidate != null) return candidate

        if (strictNoOverlap) return null
        return yOrder.firstOrNull()?.let { y ->
            IntOffset(preferredX, y)
        }
    }

    private data class FloatingXSegment(
        val start: Int,
        val end: Int
    )

    private fun findFloatingNonOverlappingXAtY(
        y: Int,
        preferredX: Int,
        minX: Int,
        maxX: Int,
        popupWidth: Int,
        popupHeight: Int,
        forbiddenRects: List<IntRect>
    ): Int? {
        if (minX > maxX) return null
        var segments = mutableListOf(FloatingXSegment(minX, maxX))
        val popupTop = y
        val popupBottom = y + popupHeight

        forbiddenRects.forEach { rect ->
            val verticalOverlap = popupBottom > rect.top && popupTop < rect.bottom
            if (!verticalOverlap) return@forEach
            val blockStart = rect.left - popupWidth + 1
            val blockEnd = rect.right - 1
            segments = subtractFloatingBlockedX(
                segments = segments,
                blockStart = blockStart,
                blockEnd = blockEnd
            ).toMutableList()
            if (segments.isEmpty()) return null
        }

        var bestX: Int? = null
        var bestDistance = Int.MAX_VALUE
        segments.forEach { seg ->
            val candidate = preferredX.coerceIn(seg.start, seg.end)
            val distance = kotlin.math.abs(candidate - preferredX)
            if (distance < bestDistance) {
                bestDistance = distance
                bestX = candidate
            }
        }
        return bestX
    }

    private fun findFloatingNonOverlappingYAtX(
        x: Int,
        preferredY: Int,
        minY: Int,
        maxY: Int,
        popupWidth: Int,
        popupHeight: Int,
        forbiddenRects: List<IntRect>
    ): Int? {
        if (minY > maxY) return null
        var segments = mutableListOf(FloatingXSegment(minY, maxY))
        val popupLeft = x
        val popupRight = x + popupWidth

        forbiddenRects.forEach { rect ->
            val horizontalOverlap = popupRight > rect.left && popupLeft < rect.right
            if (!horizontalOverlap) return@forEach
            val blockStart = rect.top - popupHeight + 1
            val blockEnd = rect.bottom - 1
            segments = subtractFloatingBlockedX(
                segments = segments,
                blockStart = blockStart,
                blockEnd = blockEnd
            ).toMutableList()
            if (segments.isEmpty()) return null
        }

        var bestY: Int? = null
        var bestDistance = Int.MAX_VALUE
        segments.forEach { seg ->
            val candidate = preferredY.coerceIn(seg.start, seg.end)
            val distance = kotlin.math.abs(candidate - preferredY)
            if (distance < bestDistance) {
                bestDistance = distance
                bestY = candidate
            }
        }
        return bestY
    }

    private fun subtractFloatingBlockedX(
        segments: List<FloatingXSegment>,
        blockStart: Int,
        blockEnd: Int
    ): List<FloatingXSegment> {
        if (blockStart > blockEnd) return segments
        val result = mutableListOf<FloatingXSegment>()
        segments.forEach { seg ->
            if (blockEnd < seg.start || blockStart > seg.end) {
                result += seg
                return@forEach
            }
            if (blockStart > seg.start) {
                result += FloatingXSegment(seg.start, blockStart - 1)
            }
            if (blockEnd < seg.end) {
                result += FloatingXSegment(blockEnd + 1, seg.end)
            }
        }
        return result.filter { it.start <= it.end }
    }

    private fun computeFloatingLookupGuardPadding(
        gapPx: Int,
        screenPaddingPx: Int
    ): Int {
        return 0
    }

    private fun expandFloatingRect(rect: IntRect, padding: Int): IntRect {
        return IntRect(
            left = rect.left - padding,
            top = rect.top - padding,
            right = rect.right + padding,
            bottom = rect.bottom + padding
        )
    }

    private fun floatingRectDistance(a: IntRect, b: IntRect): Int {
        val dx = when {
            b.left >= a.right -> b.left - a.right
            a.left >= b.right -> a.left - b.right
            else -> 0
        }
        val dy = when {
            b.top >= a.bottom -> b.top - a.bottom
            a.top >= b.bottom -> a.top - b.bottom
            else -> 0
        }
        return dx + dy
    }

    private fun floatingRectOverlapArea(a: IntRect, b: IntRect): Int {
        val overlapWidth = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        val overlapHeight = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        return overlapWidth * overlapHeight
    }

    private fun computeFloatingLookupPopupSizeSpec(
        windowSize: IntSize,
        anchor: ReaderLookupAnchor?,
        placeBelow: Boolean,
        preferSidePlacement: Boolean
    ): FloatingLookupPopupSizeSpec {
        val density = resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val screenWidthDp = (windowSize.width / density).coerceAtLeast(1f)
        val screenHeightDp = (windowSize.height / density).coerceAtLeast(1f)
        val anchorBounds = anchor?.rects
            ?.filter { !it.isEmpty }
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                Rect(
                    left = it.minOf { rect -> rect.left },
                    top = it.minOf { rect -> rect.top },
                    right = it.maxOf { rect -> rect.right },
                    bottom = it.maxOf { rect -> rect.bottom }
                )
            }
        val anchorLeftDp = ((anchorBounds?.left ?: windowSize.width * 0.4f) / density).coerceIn(0f, screenWidthDp)
        val anchorRightDp = ((anchorBounds?.right ?: windowSize.width * 0.6f) / density).coerceIn(0f, screenWidthDp)
        val anchorTopDp = ((anchorBounds?.top ?: windowSize.height * 0.46f) / density).coerceIn(0f, screenHeightDp)
        val anchorBottomDp = ((anchorBounds?.bottom ?: windowSize.height * 0.56f) / density).coerceIn(0f, screenHeightDp)
        val screenPaddingDp = 12f
        val guardDp = 24f
        val preferredMinWidthDp = 220f
        val maxWidthDp = 320f
        val preferredMinContentHeightDp = 96f
        val maxContentHeightDp = 260f
        val chromeReserveDp = 112f

        data class DirectionCap(val direction: FloatingPopupDirection, val widthCap: Float, val contentHeightCap: Float)

        val fullWidthCap = (screenWidthDp - screenPaddingDp * 2f).coerceAtLeast(0f)
        val fullHeightCap = (screenHeightDp - screenPaddingDp * 2f - chromeReserveDp).coerceAtLeast(0f)
        val belowCap = DirectionCap(
            direction = FloatingPopupDirection.BELOW,
            widthCap = fullWidthCap,
            contentHeightCap = (screenHeightDp - anchorBottomDp - guardDp - screenPaddingDp - chromeReserveDp).coerceAtLeast(0f)
        )
        val aboveCap = DirectionCap(
            direction = FloatingPopupDirection.ABOVE,
            widthCap = fullWidthCap,
            contentHeightCap = (anchorTopDp - guardDp - screenPaddingDp - chromeReserveDp).coerceAtLeast(0f)
        )
        val rightCap = DirectionCap(
            direction = FloatingPopupDirection.RIGHT,
            widthCap = (screenWidthDp - anchorRightDp - guardDp - screenPaddingDp).coerceAtLeast(0f),
            contentHeightCap = fullHeightCap
        )
        val leftCap = DirectionCap(
            direction = FloatingPopupDirection.LEFT,
            widthCap = (anchorLeftDp - guardDp - screenPaddingDp).coerceAtLeast(0f),
            contentHeightCap = fullHeightCap
        )

        val verticalCaps = if (placeBelow) listOf(belowCap, aboveCap) else listOf(aboveCap, belowCap)
        val sideCaps = if (preferSidePlacement) listOf(rightCap, leftCap) else listOf(leftCap, rightCap)
        val bestCap =
            verticalCaps.firstOrNull { it.widthCap >= preferredMinWidthDp && it.contentHeightCap >= preferredMinContentHeightDp }
                ?: sideCaps.firstOrNull { it.widthCap >= preferredMinWidthDp && it.contentHeightCap >= preferredMinContentHeightDp }
                ?: (verticalCaps + sideCaps).maxByOrNull { it.widthCap * it.contentHeightCap }

        return FloatingLookupPopupSizeSpec(
            widthPx = ((bestCap?.widthCap ?: maxWidthDp).coerceIn(1f, maxWidthDp) * density).toInt().coerceAtLeast(1),
            contentMaxHeightPx = ((bestCap?.contentHeightCap ?: maxContentHeightDp).coerceIn(1f, maxContentHeightDp) * density).toInt().coerceAtLeast(1),
            preferredDirection = bestCap?.direction ?: if (placeBelow) FloatingPopupDirection.BELOW else FloatingPopupDirection.ABOVE
        )
    }

    private fun hideFloatingLookup() {
        logDebug(FLOATING_LOOKUP_LOG_TAG) {
            "hideFloatingLookup session=${floatingLookupSession.size} hosts=${floatingLookupHostViews.size} positions=${floatingLookupCardPositions.size} paused=$playbackPausedByFloatingLookup"
        }
        lookupRequestNonce += 1L
        floatingLookupSession.clear()
        floatingLookupCardPositions.clear()
        applySubtitleSelectionHighlight(null)
        hideFloatingLookupHostsKeepingWarmRoot()
        updateFloatingLookupPanelPosition()
        resumePlaybackIfPausedByFloatingLookup()
    }

    private fun pausePlaybackForFloatingLookupIfNeeded(settings: AudiobookSettingsConfig) {
        if (settings.pausePlaybackOnLookup && BookReaderFloatingBridge.isPlaying()) {
            BookReaderFloatingBridge.setPlaying(play = false)
            playbackPausedByFloatingLookup = true
        }
    }

    private fun resumePlaybackIfPausedByFloatingLookup() {
        if (!playbackPausedByFloatingLookup) return
        playbackPausedByFloatingLookup = false
        BookReaderFloatingBridge.setPlaying(play = true)
    }

    private sealed interface FloatingCardMode {
        data object Results : FloatingCardMode
        data class Error(val message: String) : FloatingCardMode
    }

    private data class FloatingLookupPopupSizeSpec(
        val widthPx: Int,
        val contentMaxHeightPx: Int,
        val preferredDirection: FloatingPopupDirection
    )

    private data class FloatingLookupRenderItem(
        val layerIndex: Int,
        val layer: ReaderLookupLayer,
        val sizeSpec: FloatingLookupPopupSizeSpec
    )

    private enum class FloatingPopupDirection {
        ABOVE,
        BELOW,
        LEFT,
        RIGHT
    }

    private data class FloatingLookupPalette(
        val cardFill: Int,
        val cardStroke: Int,
        val divider: Int,
        val title: Int,
        val reading: Int,
        val body: Int,
        val bodyCss: String,
        val footerText: Int,
        val dictionaryFill: Int,
        val dictionaryText: Int
    )

    private fun floatingLookupPalette(): FloatingLookupPalette {
        val darkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (darkMode) {
            FloatingLookupPalette(
                cardFill = 0xEE121826.toInt(),
                cardStroke = 0x334B5563,
                divider = 0x1FFFFFFF,
                title = 0xFFF8FAFC.toInt(),
                reading = 0xFF94A3B8.toInt(),
                body = 0xFFE5E7EB.toInt(),
                bodyCss = "#E5E7EB",
                footerText = 0xFFCBD5E1.toInt(),
                dictionaryFill = 0xFF5B4B8A.toInt(),
                dictionaryText = 0xFFFFFFFF.toInt()
            )
        } else {
            FloatingLookupPalette(
                cardFill = 0xECF5F7FB.toInt(),
                cardStroke = 0x332A3442,
                divider = 0x12000000,
                title = 0xFF111827.toInt(),
                reading = 0xFF6B7280.toInt(),
                body = 0xFF1F2937.toInt(),
                bodyCss = "#1F2937",
                footerText = 0xFF4B5563.toInt(),
                dictionaryFill = 0xFFC09AE8.toInt(),
                dictionaryText = 0xFFFFFFFF.toInt()
            )
        }
    }

    private fun createFloatingLookupCard(
        layerIndex: Int,
        layer: ReaderLookupLayer,
        mode: FloatingCardMode,
        maxLookupHeightPx: Int
    ): View {
        val density = resources.displayMetrics.density
        val palette = floatingLookupPalette()
        val useHoshiWebView = mode == FloatingCardMode.Results
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
        }
        content.addView(createLookupHeader(layerIndex))
        when (mode) {
            is FloatingCardMode.Error -> {
                content.addView(createLookupBodyText(mode.message, topMarginDp = 0f, palette = palette))
            }
            FloatingCardMode.Results -> {
                content.addView(
                    createFloatingHoshiLookupWebView(
                        layerIndex = layerIndex,
                        layer = layer,
                        maxLookupHeightPx = maxLookupHeightPx
                    )
                )
            }
        }
        val lookupContentView: View = if (useHoshiWebView) {
            content
        } else {
            object : ScrollView(this) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    super.onMeasure(
                        widthMeasureSpec,
                        MeasureSpec.makeMeasureSpec(maxLookupHeightPx, MeasureSpec.AT_MOST)
                    )
                }
            }.apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isVerticalScrollBarEnabled = false
                addView(content)
            }
        }

        val cardContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(lookupContentView)
            addView(createLookupFooter(palette))
        }

        return FrameLayout(this).apply {
            background = createRoundedBackground(palette.cardFill, cornerDp = 18f, strokeColor = palette.cardStroke)
            elevation = 8 * density
            addView(
                cardContent,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun createLookupHeader(layerIndex: Int): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(createTextButton("←") {
                closeFloatingLookupLayer(layerIndex)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    (36 * density).toInt(),
                    (32 * density).toInt()
                ).apply { marginEnd = (8 * density).toInt() }
                alpha = 1f
            })
            addView(View(this@AudiobookFloatingOverlayService).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
        }
    }

    private fun closeFloatingLookupLayer(layerIndex: Int) {
        applyCloseLookupAction(floatingLookupSession.closeLayerOrClear(layerIndex))
    }

    private fun applyCloseLookupAction(closeAction: CloseLookupAction) {
        when (closeAction) {
            CloseLookupAction.ClearAll -> hideFloatingLookup()
            is CloseLookupAction.ShowLayer -> truncateFloatingLookupLayersTo(closeAction.index)
        }
    }

    private fun createLookupFooter(palette: FloatingLookupPalette): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (4 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            addView(createFooterActionButton(getString(R.string.common_close_all), palette) {
                hideFloatingLookup()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            addView(View(this@AudiobookFloatingOverlayService).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })
        }
    }

    private fun createFooterActionButton(label: String, palette: FloatingLookupPalette, onClick: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(palette.footerText)
            minHeight = (32 * density).toInt()
            background = null
            setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun truncateFloatingLookupLayersTo(index: Int, render: Boolean = true) {
        if (index !in floatingLookupSession.layers.indices) return
        floatingLookupSession.truncateTo(index)
        val removedIndices = floatingLookupCardPositions.keys.filter { it > index }
        removedIndices.forEach(::removeFloatingLookupHost)
        floatingLookupCardPositions.keys.removeAll { it > index }
        if (render) {
            floatingLookupSession.getOrNull(index)?.let(::renderFloatingLookupResults)
        }
    }

    private fun buildFloatingLookupLayer(
        term: String,
        sourceTerm: String? = null,
        anchor: ReaderLookupAnchor? = null,
        avoidAnchor: ReaderLookupAnchor? = null,
        placeBelow: Boolean = true,
        preferSidePlacement: Boolean = false,
        selectedRange: IntRange? = null,
        hoshiResults: List<LookupResult> = emptyList(),
        hoshiDictionaryStyles: Map<String, String> = emptyMap()
    ): ReaderLookupLayer {
        return ReaderLookupLayer(
            sourceTerm = sourceTerm,
            anchor = anchor,
            avoidAnchor = avoidAnchor,
            placeBelow = placeBelow,
            preferSidePlacement = preferSidePlacement,
            selectedRange = selectedRange,
            selectionText = term.takeIf { it.isNotBlank() },
            hoshiResults = hoshiResults,
            hoshiDictionaryStyles = hoshiDictionaryStyles
        )
    }

    private fun createFloatingHoshiLookupWebView(
        layerIndex: Int,
        layer: ReaderLookupLayer,
        maxLookupHeightPx: Int,
        detachFromParent: Boolean = true
    ): View {
        val audiobookSettings = loadAudiobookSettingsConfig(this)
        val html = LookupPopupHtml.render(
            results = emptyList(),
            assets = floatingHoshiLookupAssets,
            dictionaryStyles = layer.hoshiDictionaryStyles,
            settings = loadDictionarySettings(this),
            audioSettings = audiobookSettings,
            showPlayAudio = audiobookSettings.lookupPlaybackAudioEnabled,
            showRangeSelection = false,
            swipeToDismiss = true,
            swipeThreshold = 40,
            backgroundColorCss = "transparent",
            darkMode = isSystemDarkMode(),
            eInkMode = false,
        )
        var popupWebView: WebView? = null
        val existingWebView = floatingHoshiLookupWebViews[layerIndex] as? FloatingHoshiLookupWebView
        val existingTag = existingWebView?.tag as? FloatingHoshiLookupWebViewTag
        val callbackHolder = existingTag?.callbackHolder ?: PopupWebViewCallbackHolder(PopupWebViewCallbacks())
        val resultsHolder = existingTag?.resultsHolder ?: PopupLookupResultsHolder(emptyList())
        val webViewTag = existingTag ?: FloatingHoshiLookupWebViewTag(callbackHolder, resultsHolder)
        webViewTag.pendingResults = layer.hoshiResults
        fun applyPendingResults() {
            val view = popupWebView ?: return
            val pendingResults = webViewTag.pendingResults
            if (!webViewTag.shellReady || webViewTag.appliedResults === pendingResults) return
            webViewTag.appliedResults = pendingResults
            resultsHolder.results = pendingResults
            webViewTag.contentReady = false
            webViewTag.contentReadyGate.reset()
            val initialEntries = pendingResults.firstOrNull()
                ?.let(LookupPopupHtml::entryJsonString)
                ?.let { "[$it]" }
                ?: "[]"
            view.evaluateJavascript(
                "window.hoshiSelection && window.hoshiSelection.clearSelection();" +
                    "window.replacePopupResults && window.replacePopupResults(${pendingResults.size}, $initialEntries)",
                null
            )
        }
        val callbacks = PopupWebViewCallbacks(
            onSwipeDismiss = { closeFloatingLookupLayer(layerIndex) },
            onTapOutside = {},
            onOpenLink = { this@AudiobookFloatingOverlayService.openPopupExternalLink(it) },
            onImageTap = { src ->
                logDebug(FLOATING_LOOKUP_TAP_LOG_TAG) { "floating hoshi imageTap src=$src" }
                this@AudiobookFloatingOverlayService.openHoshiImagePreview(src)
            },
            onMineEntryAsync = { content, onComplete ->
                exportFloatingHoshiLookupEntryToAnkiAsync(content, layer, onComplete)
            },
            onDuplicateCheckAsync = { expression, onComplete ->
                checkFloatingAnkiDuplicateAsync(expression, onComplete)
            },
            onViewDuplicate = { noteIds ->
                openAnkiDuplicateNotesInBrowser(this@AudiobookFloatingOverlayService, noteIds)
            },
            onPlayWordAudio = { _url, term, reading ->
                if (!term.isNullOrBlank()) {
                    playLookupAudioForTerm(
                        context = this@AudiobookFloatingOverlayService,
                        term = term,
                        reading = reading,
                        settings = audiobookSettings
                    )
                }
            },
            onCloseAll = { hideFloatingLookup() },
            onTextSelected = { selection -> pushFloatingHoshiRecursiveLookup(layerIndex, selection) },
            onLookupRedirect = { query ->
                val dictionarySettings = loadDictionarySettings(this@AudiobookFloatingOverlayService)
                floatingHoshiLookupSession.lookup(
                    query,
                    dictionarySettings.maxResults,
                    dictionarySettings.scanLength,
                )
            },
            onLookupRedirected = { selection, _ -> pushFloatingHoshiRecursiveLookup(layerIndex, selection) },
            onContentReady = contentReady@{
                if (webViewTag.contentReady) return@contentReady
                webViewTag.contentReady = true
                val activeLayer = floatingLookupSession.getOrNull(layerIndex)
                if (activeLayer != null && activeLayer.hoshiResults.isNotEmpty()) {
                    repositionFloatingLookupHost(layerIndex)
                    floatingLookupHostViews[layerIndex]?.let { host ->
                        if (layerIndex == 0) {
                            applySubtitleSelectionHighlight(floatingLookupSession.getOrNull(0)?.selectedRange)
                        }
                        host.alpha = 1f
                        host.visibility = View.VISIBLE
                    }
                }
            }
        )
        callbackHolder.callbacks = callbacks
        val webView = existingWebView ?: FloatingHoshiLookupWebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            overScrollMode = WebView.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = true
            isLongClickable = false
            isHapticFeedbackEnabled = false
            isSoundEffectsEnabled = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true
            settings.allowContentAccess = true
            settings.blockNetworkLoads = true
            settings.blockNetworkImage = false
            settings.loadsImagesAutomatically = true
            settings.offscreenPreRaster = true
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.setSupportZoom(false)
            webViewClient = PopupMessageWebViewClient(
                callbackHolder = callbackHolder,
                assets = floatingHoshiLookupAssets
            )
            addJavascriptInterface(
                PopupWebViewBridge(
                    webView = this,
                    callbackHolder = callbackHolder,
                    lookupResultsHolder = resultsHolder,
                    contentReadyGate = webViewTag.contentReadyGate,
                    onShellReady = {
                        webViewTag.shellReady = true
                        applyPendingResults()
                    }
                ),
                "HoshiPopup"
            )
            tag = webViewTag
            floatingHoshiLookupWebViews[layerIndex] = this
        }
        popupWebView = webView
        if (detachFromParent) {
            (webView.parent as? ViewGroup)?.removeView(webView)
        }
        (webView as? FloatingHoshiLookupWebView)?.maxLookupHeightPx = maxLookupHeightPx
        webView.requestLayout()
        if (floatingHoshiLookupHtmlByLayer[layerIndex] != html) {
            floatingHoshiLookupHtmlByLayer[layerIndex] = html
            webViewTag.shellReady = false
            webViewTag.contentReady = false
            webViewTag.appliedResults = null
            webViewTag.contentReadyGate.reset()
            webView.scrollTo(0, 0)
            webView.loadDataWithBaseURL("https://hoshi.local/popup/", html, "text/html", "utf-8", null)
        }
        applyPendingResults()
        return webView
    }

    private fun pushFloatingHoshiRecursiveLookup(
        sourceLayerIndex: Int,
        selection: ReaderSelectionData
    ): Int? {
        val settings = loadAudiobookSettingsConfig(this)
        val popup = floatingHoshiLookupSession.createPopup(
            selection = selection,
            options = floatingHoshiLookupOptions(settings).copy(showRangeSelection = false),
        ) ?: return null
        val hoshiResults = popup.first.state.results
        if (hoshiResults.isEmpty()) {
            logDebug(FLOATING_LOOKUP_TAP_LOG_TAG) {
                "floating hoshi recursive empty text='${selection.text.take(24)}'"
            }
            return null
        }
        val density = resources.displayMetrics.density.coerceAtLeast(0.1f)
        val rect = selection.rect
        val anchorRect = Rect(
            left = (rect.x * density).toFloat(),
            top = (rect.y * density).toFloat(),
            right = ((rect.x + rect.width) * density).toFloat(),
            bottom = ((rect.y + rect.height) * density).toFloat()
        )
        val estimatedAnchorY = anchorRect.bottom
        val shouldPlaceBelow = estimatedAnchorY <= (resources.displayMetrics.heightPixels / 2f)
        val currentLayer = floatingLookupSession.getOrNull(sourceLayerIndex)
        truncateFloatingLookupLayersTo(sourceLayerIndex, render = false)
        floatingLookupSession.push(
            buildFloatingLookupLayer(
                term = hoshiResults.firstOrNull()?.matched?.takeIf { it.isNotBlank() } ?: selection.text,
                sourceTerm = currentLayer?.selectionText,
                anchor = ReaderLookupAnchor(listOf(anchorRect)),
                placeBelow = shouldPlaceBelow,
                preferSidePlacement = false,
                selectedRange = currentLayer?.selectedRange,
                hoshiResults = hoshiResults,
                hoshiDictionaryStyles = popup.first.state.dictionaryStyles
            )
        )
        floatingLookupSession.getOrNull(floatingLookupSession.lastIndex)?.let(::renderFloatingLookupResults)
        return popup.second
    }

    private fun createLookupBodyText(textValue: String, topMarginDp: Float, palette: FloatingLookupPalette): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = textValue
            textSize = 15f
            setTextColor(palette.body)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (topMarginDp * density).toInt()
            }
        }
    }

    private fun applySubtitleTypography(settings: AudiobookSettingsConfig) {
        val customTypeface = resolveSubtitleTypeface(this, settings.subtitleCustomFontUri)
        val verticalWriting = settings.floatingOverlaySubtitleWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL
        val density = resources.displayMetrics.density
        val outlineColor = Color.argb(0x59, 0, 0, 0)
        val outlineStrokeWidth = (1.2f * density).coerceAtLeast(1f)
        logDebug(FLOATING_SUBTITLE_RENDER_LOG_TAG) {
            "applyTypography vertical=$verticalWriting color=${settings.floatingOverlaySubtitleColor.toUInt().toString(16)} size=${settings.floatingOverlaySubtitleSizeSp}"
        }
        subtitleTextView?.apply {
            textSize = settings.floatingOverlaySubtitleSizeSp.toFloat()
            setTextColor(settings.floatingOverlaySubtitleColor)
            typeface = customTypeface ?: Typeface.DEFAULT
            visibility = if (verticalWriting) View.GONE else visibility
            setShadowLayer(0f, 0f, 0f, 0)
        }
        subtitleVerticalCanvasView?.apply {
            visibility = if (verticalWriting) visibility else View.GONE
            applyTypography(
                settings.floatingOverlaySubtitleColor,
                settings.floatingOverlaySubtitleSizeSp.toFloat(),
                customTypeface ?: Typeface.DEFAULT,
            )
        }
        subtitleOutlineTextView?.apply {
            textSize = settings.floatingOverlaySubtitleSizeSp.toFloat()
            setTextColor(outlineColor)
            typeface = customTypeface ?: Typeface.DEFAULT
            (this as? HorizontalSubtitleOutlineTextView)?.configureOutline(outlineStrokeWidth)
            setShadowLayer(0f, 0f, 0f, 0)
            if (verticalWriting) {
                visibility = View.GONE
                alpha = 0f
            } else {
                visibility = View.VISIBLE
                alpha = 1f
            }
        }
    }

    private fun updateBubbleIcon(isPlaying: Boolean) {
        bubbleButton?.setImageResource(
            if (isPlaying) R.drawable.ic_overlay_pause else R.drawable.ic_overlay_play
        )
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        val controls = subtitleControlsRow ?: return
        val playPause = controls.findViewWithTag<ImageButton>("playPause") ?: return
        playPause.setImageResource(
            if (isPlaying) R.drawable.ic_overlay_pause else R.drawable.ic_overlay_play
        )
    }

    private fun updateSubtitleLockIcon() {
        subtitleLockButton?.setImageResource(
            if (subtitleOverlayLocked) R.drawable.ic_overlay_lock else R.drawable.ic_overlay_unlock
        )
    }

    private fun updateBubbleLockIcon() {
        bubbleLockButton?.setImageResource(
            if (bubbleOverlayLocked) R.drawable.ic_overlay_lock else R.drawable.ic_overlay_unlock
        )
    }

    private fun createControlButton(iconRes: Int, onClick: () -> Unit): ImageButton {
        return createControlButton(iconRes, 1f, onClick)
    }

    private fun createControlButton(iconRes: Int, scale: Float, onClick: () -> Unit): ImageButton {
        val density = resources.displayMetrics.density
        val safeScale = scale.coerceIn(0.62f, 1.4f)
        val sizePx = (42 * density * safeScale).toInt()
        return ImageButton(this).apply {
            setImageResource(iconRes)
            background = null
            setColorFilter(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(sizePx / 5, sizePx / 5, sizePx / 5, sizePx / 5)
            setOnClickListener { onClick() }
        }
    }

    private fun createTextButton(label: String, onClick: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            background = createRoundedBackground(0x22151515, cornerDp = 14f, strokeColor = 0x22FFFFFF)
            layoutParams = LinearLayout.LayoutParams(
                (44 * density).toInt(),
                (34 * density).toInt()
            ).apply { marginEnd = (8 * density).toInt() }
            setOnClickListener { onClick() }
        }
    }

    private fun createColorButton(
        color: Int,
        currentColor: Int,
        onColorSelected: () -> Unit = {}
    ): View {
        val density = resources.displayMetrics.density
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (28 * density).toInt(),
                (28 * density).toInt()
            ).apply { marginEnd = (8 * density).toInt() }
            background = createSubtitleColorSwatchDrawable(
                color = color,
                selected = currentColor == color,
                density = density
            )
            setOnClickListener {
                saveAudiobookFloatingOverlaySubtitleColor(context, color)
                applySubtitleTypography(loadAudiobookSettingsConfig(context))
                onColorSelected()
            }
        }
    }

    private fun createCustomColorButton(
        color: Int,
        selected: Boolean,
        onClick: () -> Unit
    ): View {
        val density = resources.displayMetrics.density
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (28 * density).toInt(),
                (28 * density).toInt()
            ).apply { marginEnd = (8 * density).toInt() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(
                    (if (selected) 3 else 1) * density.toInt().coerceAtLeast(1),
                    if (selected) 0xFFFFFFFF.toInt() else 0x44FFFFFF
                )
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createSubtitleColorSwatchDrawable(
        color: Int,
        selected: Boolean,
        density: Float
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(
                (if (selected) 3 else 1) * density.toInt().coerceAtLeast(1),
                if (selected) 0xFFFFFFFF.toInt() else 0x44FFFFFF
            )
        }
    }

    private fun getOverlayLocalizedString(resId: Int): String {
        val option = loadAppLanguageOption(this)
        if (option == AppLanguageOption.SYSTEM) return getString(resId)
        return try {
            val locale = Locale.forLanguageTag(option.value)
            val localizedConfig = Configuration(resources.configuration).apply {
                setLocale(locale)
                setLayoutDirection(locale)
            }
            createConfigurationContext(localizedConfig).resources.getString(resId)
        } catch (_: Throwable) {
            getString(resId)
        }
    }

    private fun createRgbSliderRow(
        label: String,
        initial: Int,
        onLiveChange: (Int) -> Unit,
        onFinalChange: () -> Unit
    ): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt() }
            addView(TextView(this@AudiobookFloatingOverlayService).apply {
                text = label
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    (14 * density).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            addView(SeekBar(this@AudiobookFloatingOverlayService).apply {
                max = 255
                progress = initial.coerceIn(0, 255)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        onLiveChange(progress.coerceIn(0, 255))
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        onFinalChange()
                    }
                })
            })
        }
    }

    private fun createRoundedBackground(fillColor: Int, cornerDp: Float, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerDp * resources.displayMetrics.density
            setColor(fillColor)
            setStroke((1 * resources.displayMetrics.density).toInt().coerceAtLeast(1), strokeColor)
        }
    }

    private fun removeOverlay() {
        clearFloatingLookupHosts()
        removeSubtitleOverlay()
        removeBubbleOverlay()
    }

    private fun removeSubtitleOverlay() {
        val wm = windowManager
        val view = rootView
        if (wm != null && view != null) {
            runCatching { wm.removeView(view) }
        }
        rootView = null
        windowLayoutParams = null
        subtitlePanelView = null
        subtitleFrameView = null
        subtitleTextView = null
        subtitleOutlineTextView = null
        subtitleVerticalCanvasView = null
        subtitleControlsRow = null
        subtitleSettingsPanel = null
        subtitleSettingsInlineHost = null
        subtitleLockButton = null
        subtitleSettingsButton = null
        hideSubtitleSettingsOverlay()
    }

    private fun removeBubbleOverlay() {
        val wm = windowManager
        val view = bubbleRootView
        if (wm != null && view != null) {
            runCatching { wm.removeView(view) }
        }
        bubbleRootView = null
        bubbleWindowLayoutParams = null
        bubbleRow = null
        bubbleButton = null
        bubbleControlsRow = null
        bubbleLockButton = null
        bubbleControlsVisible = false
    }

    private fun exportFloatingHoshiLookupEntryToAnkiAsync(
        content: String,
        layer: ReaderLookupLayer,
        onComplete: (Boolean) -> Unit,
    ) {
        serviceScope.launch {
            logDebug("AnkiExportDebug") {
                "floatingHoshiExport rawContentLen=${content.length} rawPrefix=${content.take(120)}"
            }
            val success = runCatching {
                val payload = runCatching { JSONObject(content) }.getOrNull() ?: return@runCatching false
                val expression = payload.optString("expression").trim().ifBlank {
                    payload.optString("matched").trim()
                }
                if (expression.isBlank()) return@runCatching false
                val cueSnapshot = BookReaderFloatingBridge.currentCue() ?: return@runCatching false
                val settings = loadAudiobookSettingsConfig(this@AudiobookFloatingOverlayService)
                val reading = payload.optString("reading").trim().takeIf { it.isNotBlank() }
                val glossary = payload.optString("glossary").trim().ifBlank {
                    payload.optString("glossaryFirst").trim().ifBlank { expression }
                }
                val frequency = payload.optString("frequenciesHtml").trim().ifBlank {
                    payload.optString("freqHarmonicRank").trim()
                }
                val pitch = payload.optString("pitchCategories").trim().ifBlank {
                    payload.optString("pitchPositions").trim()
                }
                val dictionaryName = payload.optString("selectedDictionary").trim()
                val dictionaryMedia = parseLookupDictionaryMedia(payload)
                val sentence = cueSnapshot.fullSentenceText ?: cueSnapshot.text
                val exportResult = withContext(Dispatchers.IO) {
                    val preparedLookupAudio = prepareLookupAudioForAnkiExport(
                        context = this@AudiobookFloatingOverlayService,
                        term = expression,
                        reading = reading,
                        settings = settings
                    )
                    try {
                        addLookupDefinitionToAnkiShared(
                            context = this@AudiobookFloatingOverlayService,
                            cueText = cueSnapshot.text,
                            cueStartMs = cueSnapshot.fullSentenceStartMs ?: cueSnapshot.startMs,
                            cueEndMs = cueSnapshot.fullSentenceEndMs ?: cueSnapshot.endMs,
                            audioUri = cueSnapshot.audioUri?.let { runCatching { Uri.parse(it) }.getOrNull() },
                            lookupAudioUri = preparedLookupAudio?.uri,
                            bookTitle = cueSnapshot.bookTitle,
                            entry = DictionaryEntry(
                                term = expression,
                                reading = reading,
                                definitions = listOf(glossary),
                                pitch = pitch.ifBlank { null },
                                frequency = frequency.ifBlank { null },
                                dictionary = dictionaryName.ifBlank { expression }
                            ),
                            definition = glossary,
                            glossaryFirstHtml = payload.optString("glossaryFirst").trim().takeIf { it.isNotBlank() },
                            dictionaryCss = layer.hoshiDictionaryStyles[dictionaryName],
                            dictionaryMedia = dictionaryMedia,
                            popupSelectionText = payload.optString("popupSelectionText").trim().takeIf { it.isNotBlank() }
                                ?: layer.selectionText,
                            sentenceOverride = sentence,
                            lookupTermOverride = expression
                        )
                    } finally {
                        preparedLookupAudio?.cleanup?.invoke()
                    }
                }
                val message = ankiExportResultMessage(this@AudiobookFloatingOverlayService, exportResult)
                if (message.isNotBlank() && exportResult !is AnkiExportResult.DuplicateSkipped) {
                    Toast.makeText(
                        this@AudiobookFloatingOverlayService,
                        message.take(220),
                        if (exportResult == AnkiExportResult.Added) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                    ).show()
                }
                exportResult == AnkiExportResult.Added || exportResult is AnkiExportResult.DuplicateSkipped
            }.getOrElse { error ->
                Log.w("HoshiLookupPopup", "floatingHoshiExport async failed", error)
                false
            }
            onComplete(success)
        }
    }

    private fun checkFloatingAnkiDuplicateAsync(
        expression: String,
        onComplete: (AnkiDuplicateCheckResult) -> Unit,
    ) {
        serviceScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    checkAnkiDuplicateByFirstFieldAsync(this@AudiobookFloatingOverlayService, expression)
                }
            }.getOrElse { error ->
                Log.w("HoshiLookupPopup", "floating duplicate async failed expression=${expression.take(32)}", error)
                AnkiDuplicateCheckResult()
            }
            onComplete(result)
        }
    }

    private fun rebuildOverlay() {
        val settings = loadAudiobookSettingsConfig(this)
        val subtitleX = windowLayoutParams?.x ?: settings.floatingOverlaySubtitleX
        val subtitleY = windowLayoutParams?.y ?: settings.floatingOverlaySubtitleY
        val bubbleX = bubbleWindowLayoutParams?.x ?: settings.floatingOverlayBubbleX
        val bubbleY = bubbleWindowLayoutParams?.y ?: settings.floatingOverlayBubbleY
        removeOverlay()
        saveAudiobookFloatingOverlaySubtitlePosition(this, subtitleX, subtitleY)
        saveAudiobookFloatingOverlayBubblePosition(this, bubbleX, bubbleY)
        ensureOverlayVisible()
    }

    private inner class OverlayDragTouchListener(
        private val gestureDetector: GestureDetector?,
        private val dragAllowed: () -> Boolean,
        private val verticalOnly: Boolean = false,
        private val horizontalOnly: Boolean = false,
        private val layoutParamsProvider: () -> WindowManager.LayoutParams?,
        private val hostViewProvider: () -> View?,
        private val horizontalBoundsProvider: (() -> IntRange)? = null,
        private val onPersistPosition: (x: Int, y: Int) -> Unit
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var isDragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = layoutParamsProvider() ?: return false
            val wm = windowManager ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    isDragging = false
                    gestureDetector?.onTouchEvent(event)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - downRawX).toInt()
                    val deltaY = (event.rawY - downRawY).toInt()
                    if (!isDragging && dragAllowed() && (kotlin.math.abs(deltaX) > 10 || kotlin.math.abs(deltaY) > 10)) {
                        isDragging = true
                        if (floatingLookupSession.size > 0) {
                            hideFloatingLookup()
                        }
                    }
                    if (isDragging) {
                        if (!verticalOnly) {
                            val targetX = startX + deltaX
                            val bounds = horizontalBoundsProvider?.invoke()
                            params.x = bounds?.let { targetX.coerceIn(it.first, it.last) } ?: targetX.coerceAtLeast(0)
                        } else {
                            params.x = ((resources.displayMetrics.widthPixels - (hostViewProvider()?.width ?: 0)) / 2).coerceAtLeast(0)
                        }
                        params.y = if (horizontalOnly) {
                            startY
                        } else {
                            (startY + deltaY).coerceAtLeast(0)
                        }
                        hostViewProvider()?.let { runCatching { wm.updateViewLayout(it, params) } }
                    } else {
                        gestureDetector?.onTouchEvent(event)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        onPersistPosition(params.x, params.y.coerceAtLeast(0))
                    } else {
                        if (gestureDetector != null) {
                            gestureDetector.onTouchEvent(event)
                        } else {
                            view.performClick()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    gestureDetector?.onTouchEvent(event)
                    return true
                }
            }
            return false
        }
    }
}
