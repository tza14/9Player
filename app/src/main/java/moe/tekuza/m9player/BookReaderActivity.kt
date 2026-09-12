package moe.tekuza.m9player

import android.Manifest
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Html
import android.text.TextPaint
import android.util.Base64
import android.util.Log
import android.app.Activity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.ceil
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import moe.tekuza.m9player.ui.theme.TsetTheme
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionData
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupItem
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupOptions
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupStackView
import moe.tekuza.m9player.hoshi.features.dictionary.loadDictionarySettings
import kotlinx.coroutines.CancellationException
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.Locale
import org.json.JSONObject
import kotlin.math.abs

private const val BOOK_READER_PERMISSION_REQUEST_CODE = 21_001
private const val BOOK_READER_PENDING_INTENT_REQUEST_CODE = 21_002
private const val BOOK_READER_SLEEP_OPTIONS_PREFS = "book_reader_sleep_options_prefs"
private const val BOOK_READER_UI_TEST_PREFS = "book_reader_ui_test_prefs"
private const val BOOK_READER_UI_TEST_LAYOUT_HORIZONTAL_KEY = "layout_horizontal"
private const val BOOK_READER_UI_TEST_LAYOUT_VERTICAL_KEY = "layout_vertical"
private const val BOOK_READER_UI_SWAP_PREV_NEXT_HORIZONTAL_KEY = "ui_swap_prev_next_horizontal"
private const val BOOK_READER_UI_SWAP_PREV_NEXT_VERTICAL_KEY = "ui_swap_prev_next_vertical"
private const val BOOK_READER_UI_CHAPTER_VISIBLE_KEY = "ui_chapter_visible"
private const val BOOK_READER_SLEEP_EXIT_CONTROL_KEY = "sleep_exit_control"
private const val BOOK_READER_SLEEP_DISCONNECT_BT_KEY = "sleep_disconnect_bt"
private const val BOOK_READER_SLEEP_FADE_OUT_KEY = "sleep_fade_out"
private const val BOOK_READER_SLEEP_DEFAULT_TIMER_MINUTES_KEY = "sleep_default_timer_minutes"
internal const val BOOK_READER_SLEEP_FADE_OUT_MS = 10_000L
private const val BOOK_LOOKUP_ANCHOR_LOG_TAG = "BookLookupAnchor"
private const val BOOK_LOOKUP_SELECTION_LOG_TAG = "BookLookupSelection"
private const val BOOK_READER_BACK_LOG_TAG = "BookReaderBack"
private const val BOOK_READER_SEEK_LOG_TAG = "BookReaderSeek"
private const val BOOK_UI_MODE_LOG_TAG = "BookUiMode"
private const val FLOATING_OVERLAY_EXIT_LOG_TAG = "FloatingOverlayExit"
private const val BOOK_READER_AUDIO_CUE_LOOP_MESSAGE_TYPE = 1
private const val BOOK_VERTICAL_TAP_DEBUG_OVERLAY = false
private const val BOOK_VERTICAL_COLUMN_WIDTH_FACTOR = 1.0f
private val BOOK_VERTICAL_CUE_EDGE_PADDING = 28.dp
private val BOOK_VERTICAL_CUE_ITEM_HORIZONTAL_PADDING = 0.dp
private val BOOK_VERTICAL_CUE_GLYPH_SAFETY_WIDTH = 12.dp

class BookReaderActivity : AppCompatActivity() {
    private var gamepadKeyHandler: ((KeyEvent) -> Boolean)? = null
    private var lastMotionHorizontalKeyCode: Int? = null
    private var lastMotionVerticalKeyCode: Int? = null
    private var lastControllerBluetoothAddress: String? = null
    private var floatingOverlayStartJob: Job? = null
    private var currentAudioUriForBridge: String? = null
    private var isUiTestMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestReaderPermissions()
        enableEdgeToEdge()

        val audioUri = intent.getStringExtra(EXTRA_AUDIO_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val srtUri = intent.getStringExtra(EXTRA_SRT_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val ebookUri = intent.getStringExtra(EXTRA_EBOOK_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val ebookName = intent.getStringExtra(EXTRA_EBOOK_NAME)?.trim()?.ifBlank { null }
        val ebookFormat = intent.getStringExtra(EXTRA_EBOOK_FORMAT)?.trim()?.ifBlank { null }
        val coverUri = intent.getStringExtra(EXTRA_COVER_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val uiTestMode = intent.getBooleanExtra(EXTRA_UI_TEST_MODE, false)
        val temporaryLookupMode = intent.getBooleanExtra(EXTRA_TEMPORARY_LOOKUP_MODE, false)
        val temporaryLookupStartMs = intent.getLongExtra(EXTRA_TEMPORARY_LOOKUP_START_MS, 0L)
        val temporaryLookupEndMs = intent.getLongExtra(EXTRA_TEMPORARY_LOOKUP_END_MS, 0L)
        val uiLayoutEditMode = intent.getBooleanExtra(EXTRA_UI_LAYOUT_EDIT_MODE, false)
        isUiTestMode = uiTestMode
        BookReaderFloatingBridge.setUiTestModeActive(uiTestMode)
        val title = intent.getStringExtra(EXTRA_BOOK_TITLE).orEmpty()
        currentAudioUriForBridge = audioUri?.toString()
        if (!uiTestMode && !temporaryLookupMode) {
            activeReaderRef = WeakReference(this)
            BookReaderFloatingBridge.setCurrentAudioUri(currentAudioUriForBridge)
        } else if (temporaryLookupMode) {
            currentAudioUriForBridge = null
        }

        setContent {
            TsetTheme {
                BookReaderScreen(
                    title = title.ifBlank { "Book" },
                    audioUri = audioUri,
                    srtUri = srtUri,
                    ebookUri = ebookUri,
                    ebookName = ebookName,
                    ebookFormat = ebookFormat,
                    coverUri = coverUri,
                    uiTestMode = uiTestMode,
                    temporaryLookupMode = temporaryLookupMode,
                    temporaryLookupStartMs = temporaryLookupStartMs,
                    temporaryLookupEndMs = temporaryLookupEndMs,
                    uiLayoutEditMode = uiLayoutEditMode,
                    contentResolver = contentResolver,
                    registerGamepadKeyHandler = { handler -> gamepadKeyHandler = handler },
                    latestControllerAddressProvider = {
                        lastControllerBluetoothAddress
                            ?: loadTargetControllerInfo(this)?.address
                            ?: detectConnectedControllerInfo(this)?.address
                    },
                    onBack = { currentPositionMs, currentDurationMs ->
                        if (uiTestMode || temporaryLookupMode) {
                            finish()
                        } else {
                            val playbackKey = buildReaderAudioPlaybackKey(title, audioUri, srtUri)
                            val normalized = normalizeBookReaderPlaybackPosition(
                                currentPositionMs,
                                currentDurationMs
                            )
                            saveBookReaderPlaybackPosition(
                                context = this,
                                bookKey = playbackKey,
                                positionMs = normalized,
                                durationMs = currentDurationMs.coerceAtLeast(0L)
                            )
                            val intent = Intent(this, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                putExtra(EXTRA_RETURN_AUDIO_URI, audioUri?.toString())
                                putExtra(EXTRA_RETURN_SRT_URI, srtUri?.toString())
                                putExtra(EXTRA_RETURN_POSITION_MS, normalized)
                                putExtra(EXTRA_RETURN_DURATION_MS, currentDurationMs.coerceAtLeast(0L))
                            }
                            startActivity(intent)
                        }
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ReaderPlaybackScreenVisibility.markVisible(this)
        floatingOverlayStartJob?.cancel()
        floatingOverlayStartJob = null
        stopAudiobookFloatingOverlayService(this)
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onStop() {
        super.onStop()
        ReaderPlaybackScreenVisibility.markHidden(this)
        if (isUiTestMode || intent.getBooleanExtra(EXTRA_TEMPORARY_LOOKUP_MODE, false)) return
        val settings = loadAudiobookSettingsConfig(this)
        floatingOverlayStartJob?.cancel()
        val overlayEnabled = settings.floatingOverlayEnabled || settings.floatingOverlaySubtitleEnabled
        val playing = BookReaderFloatingBridge.isPlaying()
        logDebug(FLOATING_OVERLAY_EXIT_LOG_TAG) {
            "BookReader onStop changing=$isChangingConfigurations overlayEnabled=$overlayEnabled " +
            "showOnReaderExit=${settings.floatingOverlayShowOnReaderExit} playing=$playing"
        }
        if (isChangingConfigurations || !overlayEnabled || !playing) return

        floatingOverlayStartJob = lifecycleScope.launch {
            delay(150L)
            val refreshed = loadAudiobookSettingsConfig(this@BookReaderActivity)
            val refreshedOverlayEnabled =
                refreshed.floatingOverlayEnabled || refreshed.floatingOverlaySubtitleEnabled
            val appForeground = isAppProcessInForeground()
            val readerOrPlayerVisible = ReaderPlaybackScreenVisibility.isReaderOrPlayerScreenVisible()
            val shouldShowAfterReaderExit =
                (refreshed.floatingOverlayShowOnReaderExit || !appForeground) && !readerOrPlayerVisible
            val stillPlaying = BookReaderFloatingBridge.isPlaying()
            logDebug(FLOATING_OVERLAY_EXIT_LOG_TAG) {
                "BookReader delayed overlayEnabled=$refreshedOverlayEnabled appForeground=$appForeground " +
                "showOnReaderExit=${refreshed.floatingOverlayShowOnReaderExit} " +
                "readerOrPlayerVisible=$readerOrPlayerVisible shouldShow=$shouldShowAfterReaderExit " +
                "playing=$stillPlaying"
            }
            if (refreshedOverlayEnabled && shouldShowAfterReaderExit && stillPlaying) {
                logDebug(FLOATING_OVERLAY_EXIT_LOG_TAG) { "BookReader starting overlay service" }
                startAudiobookFloatingOverlayService(this@BookReaderActivity)
            }
        }
    }

    override fun onDestroy() {
        floatingOverlayStartJob?.cancel()
        floatingOverlayStartJob = null
        ReaderPlaybackScreenVisibility.markHidden(this)
        if (isUiTestMode || intent.getBooleanExtra(EXTRA_TEMPORARY_LOOKUP_MODE, false)) {
            BookReaderFloatingBridge.setUiTestModeActive(false)
        }
        if (!isUiTestMode && !intent.getBooleanExtra(EXTRA_TEMPORARY_LOOKUP_MODE, false)) {
            if (activeReaderRef?.get() === this) {
                activeReaderRef = null
            }
        }
        super.onDestroy()
    }

    private fun maybeRequestReaderPermissions() {
        val missing = buildList {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@BookReaderActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
            if (
                loadWearableFeatureEnabled(this@BookReaderActivity) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(
                    this@BookReaderActivity,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (missing.isEmpty()) return
        ActivityCompat.requestPermissions(
            this,
            missing.toTypedArray(),
            BOOK_READER_PERMISSION_REQUEST_CODE
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isLikelyControllerEvent(event)) {
            rememberControllerAddress(event.device)
            if (gamepadKeyHandler?.invoke(event) == true) {
                return true
            }
            // Swallow all other gamepad keys so only mapped settings keys have effects.
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (isFromControllerSource(event.source)) {
            rememberControllerAddress(event.device)
            handleControllerMotionAsDpad(event)
            // Always swallow controller motion to prevent focus navigation to UI controls.
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun isFromGamepad(event: KeyEvent): Boolean {
        val source = event.source
        return isFromControllerSource(source)
    }

    private fun isFromControllerSource(source: Int): Boolean {
        return (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private fun isLikelyControllerEvent(event: KeyEvent): Boolean {
        if (isFromGamepad(event)) return true
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR -> true
            else -> false
        }
    }

    private fun handleControllerMotionAsDpad(event: MotionEvent) {
        if (event.action != MotionEvent.ACTION_MOVE) return

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val stickX = event.getAxisValue(MotionEvent.AXIS_X)
        val stickY = event.getAxisValue(MotionEvent.AXIS_Y)

        val x = when {
            kotlin.math.abs(hatX) >= 0.5f -> hatX
            kotlin.math.abs(stickX) >= 0.8f -> stickX
            else -> 0f
        }
        val y = when {
            kotlin.math.abs(hatY) >= 0.5f -> hatY
            kotlin.math.abs(stickY) >= 0.8f -> stickY
            else -> 0f
        }

        val horizontalKeyCode = when {
            x <= -0.5f -> KeyEvent.KEYCODE_DPAD_LEFT
            x >= 0.5f -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> null
        }
        val verticalKeyCode = when {
            y <= -0.5f -> KeyEvent.KEYCODE_DPAD_UP
            y >= 0.5f -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> null
        }

        if (horizontalKeyCode == null) {
            lastMotionHorizontalKeyCode = null
        } else if (horizontalKeyCode != lastMotionHorizontalKeyCode) {
            gamepadKeyHandler?.invoke(KeyEvent(KeyEvent.ACTION_DOWN, horizontalKeyCode))
            lastMotionHorizontalKeyCode = horizontalKeyCode
        }

        if (verticalKeyCode == null) {
            lastMotionVerticalKeyCode = null
        } else if (verticalKeyCode != lastMotionVerticalKeyCode) {
            gamepadKeyHandler?.invoke(KeyEvent(KeyEvent.ACTION_DOWN, verticalKeyCode))
            lastMotionVerticalKeyCode = verticalKeyCode
        }
    }

    private fun rememberControllerAddress(device: InputDevice?) {
        val inputDevice = device ?: return
        val deviceName = inputDevice.name?.trim()?.takeIf { it.isNotBlank() }
        val address = runCatching {
            val method = InputDevice::class.java.methods
                .firstOrNull { it.name == "getBluetoothAddress" && it.parameterCount == 0 }
            method?.invoke(inputDevice) as? String
        }.getOrNull()
        if (!address.isNullOrBlank() && address != "00:00:00:00:00:00") {
            val normalized = address.uppercase(Locale.US)
            lastControllerBluetoothAddress = normalized
            saveTargetControllerInfo(
                context = this,
                info = TargetControllerInfo(
                    address = normalized,
                    name = deviceName
                )
            )
            return
        }
        if (lastControllerBluetoothAddress == null) {
            val detected = detectConnectedControllerInfo(this)
            if (detected != null) {
                lastControllerBluetoothAddress = detected.address
            }
        }
    }

    companion object {
        const val EXTRA_BOOK_TITLE = "extra_book_title"
        const val EXTRA_AUDIO_URI = "extra_audio_uri"
        const val EXTRA_SRT_URI = "extra_srt_uri"
        const val EXTRA_EBOOK_URI = "extra_ebook_uri"
        const val EXTRA_EBOOK_NAME = "extra_ebook_name"
        const val EXTRA_EBOOK_FORMAT = "extra_ebook_format"
        const val EXTRA_COVER_URI = "extra_cover_uri"
        const val EXTRA_UI_TEST_MODE = "extra_ui_test_mode"
        const val EXTRA_TEMPORARY_LOOKUP_MODE = "extra_temporary_lookup_mode"
        const val EXTRA_TEMPORARY_LOOKUP_START_MS = "extra_temporary_lookup_start_ms"
        const val EXTRA_TEMPORARY_LOOKUP_END_MS = "extra_temporary_lookup_end_ms"
        const val EXTRA_UI_LAYOUT_EDIT_MODE = "extra_ui_layout_edit_mode"
        const val EXTRA_RETURN_AUDIO_URI = "extra_return_audio_uri"
        const val EXTRA_RETURN_SRT_URI = "extra_return_srt_uri"
        const val EXTRA_RETURN_POSITION_MS = "extra_return_position_ms"
        const val EXTRA_RETURN_DURATION_MS = "extra_return_duration_ms"
        @Volatile
        private var activeReaderRef: WeakReference<BookReaderActivity>? = null

        fun hasActiveReaderForAudio(targetAudioUri: String?): Boolean {
            val active = activeReaderRef?.get() ?: return false
            return !targetAudioUri.isNullOrBlank() && active.currentAudioUriForBridge == targetAudioUri
        }

        fun stopActiveReaderIfDifferentAudio(targetAudioUri: String?) {
            val active = activeReaderRef?.get() ?: return
            val activeAudio = active.currentAudioUriForBridge
            if (!targetAudioUri.isNullOrBlank() && activeAudio == targetAudioUri) return
            active.runOnUiThread {
                val activeAudioStillOwnsSharedPlayer =
                    activeAudio != null &&
                        BookReaderPlaybackSession.currentAudioUri() == activeAudio
                if (activeAudioStillOwnsSharedPlayer && BookReaderFloatingBridge.isPlaying()) {
                    BookReaderFloatingBridge.togglePlayPause()
                }
                active.finish()
            }
        }
    }
}

internal data class ReaderSubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

private data class ReaderAudioChapter(
    val startMs: Long,
    val title: String
)

private enum class AdjacentJumpMode {
    CUE,
    DURATION
}

private fun BookReaderUiSlot.centerFacingAlignment(): Alignment {
    return when (this) {
        BookReaderUiSlot.TOP -> Alignment.BottomCenter
        BookReaderUiSlot.BOTTOM -> Alignment.TopCenter
        BookReaderUiSlot.LEFT -> Alignment.CenterEnd
        BookReaderUiSlot.RIGHT -> Alignment.CenterStart
        BookReaderUiSlot.HIDDEN -> Alignment.TopCenter
    }
}

@Composable
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun BookReaderScreen(
    title: String,
    audioUri: Uri?,
    srtUri: Uri?,
    ebookUri: Uri?,
    ebookName: String?,
    ebookFormat: String?,
    coverUri: Uri?,
    uiTestMode: Boolean,
    temporaryLookupMode: Boolean,
    temporaryLookupStartMs: Long,
    temporaryLookupEndMs: Long,
    uiLayoutEditMode: Boolean,
    contentResolver: ContentResolver,
    registerGamepadKeyHandler: (((KeyEvent) -> Boolean)?) -> Unit,
    latestControllerAddressProvider: () -> String?,
    onBack: (Long, Long) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val view = LocalView.current
    val rootDensity = LocalDensity.current
    val isDarkTheme = isSystemInDarkTheme()
    val navigationBarBottomInsetDp = with(rootDensity) {
        WindowInsets.navigationBars.getBottom(this).toDp().value.toDouble()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var cues by remember { mutableStateOf<List<ReaderSubtitleCue>>(emptyList()) }
    var audioChapters by remember { mutableStateOf<List<ReaderAudioChapter>>(emptyList()) }
    var srtLoading by remember { mutableStateOf(false) }
    var srtError by remember { mutableStateOf<String?>(null) }

    var loadedDictionaries by remember { mutableStateOf<List<LoadedDictionary>>(emptyList()) }
    var dictionaryDataVersion by remember { mutableStateOf(loadDictionaryDataVersion(context)) }

    val hoshiLookupPopups = remember { mutableStateListOf<LookupPopupItem>() }
    var hoshiLookupPopupTemporarilyHidden by remember { mutableStateOf(false) }
    var reopenHoshiLookupPopupAfterCueRangeSelection by remember { mutableStateOf(false) }
    var resumePlaybackAfterLookupDismiss by remember { mutableStateOf(false) }
    var audiobookSettings by remember { mutableStateOf(loadAudiobookSettingsConfig(context)) }

    var lyricsMode by remember { mutableStateOf(true) }
    var controlModeEnabled by remember { mutableStateOf(false) }
    var controlModeStatus by remember { mutableStateOf<String?>(null) }
    var cueRangeSelectionMode by remember { mutableStateOf(false) }
    var cueRangeStartIndex by remember { mutableStateOf<Int?>(null) }
    var cueRangeEndIndex by remember { mutableStateOf<Int?>(null) }
    var controlTargetCueIndex by remember { mutableStateOf<Int?>(null) }
    var bottomControlsVisible by remember { mutableStateOf(true) }
    var topActionsExpanded by remember { mutableStateOf(false) }
    var typographyPanelVisible by remember { mutableStateOf(false) }
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var sleepTimerDeadlineMs by remember { mutableStateOf<Long?>(null) }
    var sleepTimerRemainingMs by remember { mutableStateOf<Long?>(null) }
    var sleepTimerOptionsVisible by remember { mutableStateOf(false) }
    var sleepTimerAdvancedOptionsExpanded by remember { mutableStateOf(false) }
    var sleepCustomMinutesInput by remember { mutableStateOf("") }
    var sleepExitControlModeWhenDone by remember { mutableStateOf(false) }
    var sleepDisconnectControllerBluetoothWhenDone by remember { mutableStateOf(false) }
    var sleepFadeOutAudioWhenDone by remember { mutableStateOf(false) }
    var sleepOptionsReady by remember { mutableStateOf(false) }
    var adjacentJumpMode by remember { mutableStateOf(AdjacentJumpMode.CUE) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var chapterOptionsVisible by remember { mutableStateOf(false) }
    var uiTestChapterVisible by remember { mutableStateOf(loadUiChapterVisible(context)) }
    var readerUiWritingMode by remember { mutableStateOf(audiobookSettings.bookSubtitleWritingMode) }
    var uiTestSwapPrevNextHorizontal by remember { mutableStateOf(loadUiSwapPrevNextHorizontal(context)) }
    var uiTestSwapPrevNextVertical by remember { mutableStateOf(loadUiSwapPrevNextVertical(context)) }
    val uiTestSwapPrevNext = if (readerUiWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL) {
        uiTestSwapPrevNextVertical
    } else {
        uiTestSwapPrevNextHorizontal
    }
    var uiTestLayoutModeHorizontal by remember { mutableStateOf(loadUiTestLayoutModeHorizontal(context)) }
    var uiTestLayoutModeVertical by remember { mutableStateOf(loadUiTestLayoutModeVertical(context)) }
    val uiTestLayoutMode = if (readerUiWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL) {
        uiTestLayoutModeVertical
    } else {
        uiTestLayoutModeHorizontal
    }
    val legacyUseSideRailLayout = uiTestLayoutMode == 2
    var readerUiLayoutConfig by remember(readerUiWritingMode, legacyUseSideRailLayout) {
        mutableStateOf(
            loadBookReaderUiLayoutConfig(
                context = context,
                writingMode = readerUiWritingMode,
                fallback = defaultBookReaderUiLayoutConfig(useSideRail = legacyUseSideRailLayout)
            )
        )
    }
    var draggingLayoutModule by remember(readerUiWritingMode) { mutableStateOf<BookReaderUiModule?>(null) }
    var layoutDotsVisible by remember(readerUiWritingMode) { mutableStateOf(false) }
    val chapterRowVisible = !uiTestMode || uiTestChapterVisible
    var coverModeEnabled by remember(srtUri) { mutableStateOf(srtUri == null) }
    val hasSubtitleFile = srtUri != null
    var showOverallProgress by remember { mutableStateOf(false) }
    var showOverallDuration by remember { mutableStateOf(false) }
    var timeEditDialogVisible by remember { mutableStateOf(false) }
    var timeEditInput by remember { mutableStateOf("") }
    var timeEditError by remember { mutableStateOf<String?>(null) }
    var lastOverlayTapAtMs by remember { mutableStateOf(0L) }
    var lastGamepadCollectTapAtMs by remember { mutableStateOf(0L) }
    var lastGamepadCollectCueIndex by remember { mutableStateOf<Int?>(null) }
    var pendingGamepadCollectCueIndex by remember { mutableStateOf<Int?>(null) }
    var pendingGamepadCollectJob by remember { mutableStateOf<Job?>(null) }
    var pendingSingleTapBaseCueIndex by remember { mutableStateOf<Int?>(null) }
    var pendingSingleTapJob by remember { mutableStateOf<Job?>(null) }
    var liveSelectedRangeAnchor by remember { mutableStateOf<ReaderLookupAnchor?>(null) }
    var hoshiLookupSelectionCueIndex by remember { mutableStateOf<Int?>(null) }
    var hoshiLookupSelectionRange by remember { mutableStateOf<IntRange?>(null) }

    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var cueLoopEnabled by remember { mutableStateOf(false) }
    var cueLoopWindow by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var dragPreviewPositionMs by remember { mutableStateOf<Long?>(null) }
    val replaceSrtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pickedUri = uri ?: return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        val persistedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(pickedUri, persistedFlags) }

        val currentSrtUri = srtUri
        val targetSrtUri = runCatching {
            if (currentSrtUri != null && currentSrtUri != pickedUri) {
                runCatching { resolver.takePersistableUriPermission(currentSrtUri, persistedFlags) }
                if (swapSrtDocumentContents(resolver, currentSrtUri, pickedUri)) {
                    currentSrtUri
                } else {
                    pickedUri
                }
            } else if (currentSrtUri == null) {
                movePickedSrtToBookFolder(
                    context = context,
                    resolver = resolver,
                    pickedSrtUri = pickedUri,
                    title = title,
                    audioUri = audioUri
                ) ?: pickedUri
            } else {
                pickedUri
            }
        }.getOrElse {
            Toast.makeText(
                context,
                context.getString(
                    R.string.bookreader_replace_srt_failed,
                    it.message ?: context.getString(R.string.common_unknown)
                ),
                Toast.LENGTH_SHORT
            ).show()
            pickedUri
        }
        persistReplacedSrtToImportState(
            context = context,
            resolver = resolver,
            audioUri = audioUri,
            targetSrtUri = targetSrtUri
        )

        saveBookReaderPlaybackPosition(
            context = context,
            bookKey = buildReaderAudioPlaybackKey(title, audioUri, targetSrtUri),
            positionMs = normalizeBookReaderPlaybackPosition(positionMs, durationMs),
            durationMs = durationMs.coerceAtLeast(0L)
        )
        val intent = Intent(context, BookReaderActivity::class.java).apply {
            putExtra(BookReaderActivity.EXTRA_BOOK_TITLE, title)
            putExtra(BookReaderActivity.EXTRA_AUDIO_URI, audioUri?.toString())
            putExtra(BookReaderActivity.EXTRA_SRT_URI, targetSrtUri.toString())
            putExtra(BookReaderActivity.EXTRA_COVER_URI, coverUri?.toString())
        }
        context.startActivity(intent)
        activity?.finish()
    }

    val playbackPositionKey = remember(title, audioUri, srtUri) {
        buildReaderAudioPlaybackKey(title, audioUri, srtUri)
    }
    val currentReaderBook = remember(title, audioUri, srtUri, ebookUri, ebookName, ebookFormat, coverUri) {
        audioUri?.let { selectedAudio ->
            ReaderBook(
                id = "$selectedAudio|${srtUri?.toString().orEmpty()}",
                title = title,
                audioUri = selectedAudio,
                audioName = title,
                srtUri = srtUri,
                srtName = null,
                ebookUri = ebookUri,
                ebookName = ebookName,
                ebookFormat = ebookFormat,
                coverUri = coverUri,
                coverSource = coverUri?.let { ReaderBookCoverSource.AUDIO }
            )
        }
    }
    var playbackRestoreCompleted by remember(playbackPositionKey) { mutableStateOf(false) }
    var playbackCompleted by remember(playbackPositionKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val player = remember(context, temporaryLookupMode) {
        if (temporaryLookupMode) {
            ExoPlayer.Builder(context.applicationContext)
                .setSeekBackIncrementMs(10_000L)
                .setSeekForwardIncrementMs(10_000L)
                .build()
        } else {
            BookReaderPlaybackSession.acquirePlayer(context)
        }
    }
    DisposableEffect(player, temporaryLookupMode) {
        onDispose {
            if (temporaryLookupMode) player.release()
        }
    }
    fun isReaderPlaybackRequested(): Boolean = player.playWhenReady || player.isPlaying
    fun setLookupPlaybackState(play: Boolean) {
        player.volume = 1f
        if (play) {
            player.play()
        } else {
            player.pause()
        }
    }
    fun setReaderPlaybackState(play: Boolean) {
        if (!uiTestMode || temporaryLookupMode) {
            setLookupPlaybackState(play)
        }
        isPlaying = play
    }
    fun toggleReaderPlaybackState() {
        val currentlyPlaying = if (uiTestMode && !temporaryLookupMode) isPlaying else isReaderPlaybackRequested()
        setReaderPlaybackState(!currentlyPlaying)
    }
    val notificationController = remember(context, player, title, audioUri, srtUri, coverUri, uiTestMode, temporaryLookupMode) {
        if (uiTestMode || temporaryLookupMode) {
            null
        } else {
            PlaybackNotificationController(
                context = context,
                player = player,
                title = title,
                contentIntent = buildBookReaderNotificationPendingIntent(
                    context = context,
                    title = title,
                    audioUri = audioUri,
                    srtUri = srtUri,
                    coverUri = coverUri
                )
            )
        }
    }
    val lyricsListState = rememberLazyListState()
    val collectedCueKeys = remember { hashSetOf<String>() }
    var collectedCueUiVersion by remember { mutableStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                val updated = loadAudiobookSettingsConfig(context)
                if (updated != audiobookSettings) {
                    logDebug(BOOK_UI_MODE_LOG_TAG) {
                        "settings refreshed: writingMode=${updated.bookSubtitleWritingMode}, activeTop=${updated.activeCueDisplayAtTop}, globalFont=${updated.subtitleGlobalFontEnabled}, customFont=${updated.subtitleCustomFontUri != null}"
                    }
                }
                audiobookSettings = updated
                readerUiLayoutConfig = loadBookReaderUiLayoutConfig(
                    context = context,
                    writingMode = readerUiWritingMode,
                    fallback = defaultBookReaderUiLayoutConfig(useSideRail = legacyUseSideRailLayout)
                )
                if (!uiTestMode && !temporaryLookupMode && playbackRestoreCompleted && playbackPositionKey.isNotBlank()) {
                    val currentAudioUriText = audioUri?.toString()
                    val sharedAudioUri = BookReaderFloatingBridge.currentAudioUri()
                    if (
                        currentAudioUriText != null &&
                        currentAudioUriText == sharedAudioUri
                    ) {
                        positionMs = player.currentPosition.coerceAtLeast(0L)
                        durationMs = if (player.duration > 0L) player.duration else durationMs.coerceAtLeast(0L)
                        isPlaying = isReaderPlaybackRequested()
                        return
                    }
                    val snapshot = currentReaderBook?.let { book ->
                        loadBookReaderPlaybackSnapshotOrNull(
                            context,
                            buildReaderBookPlaybackKey(book)
                        )
                    }
                    val targetPosition = snapshot?.positionMs?.coerceAtLeast(0L) ?: 0L
                    val currentPosition = player.currentPosition.coerceAtLeast(0L)
                    if (kotlin.math.abs(targetPosition - currentPosition) > 800L) {
                        player.seekTo(targetPosition)
                        positionMs = targetPosition
                    }
                    durationMs = if (player.duration > 0L) player.duration else durationMs.coerceAtLeast(0L)
                    isPlaying = isReaderPlaybackRequested()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(audiobookSettings.bookSubtitleWritingMode, uiTestMode) {
        // Real reader should follow Settings immediately; UI test page remains isolated.
        if (!uiTestMode) {
            readerUiWritingMode = audiobookSettings.bookSubtitleWritingMode
        }
    }
    LaunchedEffect(uiTestMode) {
        if (!uiTestMode) return@LaunchedEffect
        val demoText = "吾輩は猫である。名前はまだ無い。どこで生れたかとんと見當がつかぬ。何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。吾輩はここで始めて人間というものを見た。しかもあとで聞くとそれは書生という人間中で一番獰悪な種族であったそうだ。この書生というのは時々我々を捕えて煮て食うという話である。しかしその當時は何という考もなかったから別段恐しいとも思わなかった。ただ彼の掌に載せられてスーと持ち上げられた時何だかフワフワした感じがあったばかりである。掌の上で少し落ちついて書生の顔を見たのがいわゆる人間というものの見始であろう。この時妙なものだと思った感じが今でも殘っている。第一毛をもって裝飾されべきはずの顔がつるつるしてまるで薬缶だ。その後猫にもだいぶ逢ったがこんな片輪には一度も出會わした事がない。のみならず顔の真中があまりに突起している。そうしてその穴の中から時々ぷうぷうと煙を吹く。どうも咽せぽくて実に弱った。これが人間の飲む煙草というものである事はようやくこの頃知った。"
        val sentenceDurationMs = 14_000L
        cues = demoText
            .split("。")
            .mapNotNull { it.trim().takeIf { text -> text.isNotEmpty() } }
            .mapIndexed { index, sentence ->
                val startMs = index * sentenceDurationMs
                ReaderSubtitleCue(
                    startMs = startMs,
                    endMs = startMs + sentenceDurationMs,
                    text = "$sentence。"
                )
            }
        audioChapters = listOf(
            ReaderAudioChapter(0L, "1.第一章"),
            ReaderAudioChapter(2_200_000L, "第二章"),
            ReaderAudioChapter(3_800_000L, "第三章")
        )
        positionMs = 18_000L
        durationMs = (cues.size * sentenceDurationMs).coerceAtLeast(1L)
        srtLoading = false
        srtError = null
        chapterOptionsVisible = false
        coverModeEnabled = false
    }

    DisposableEffect(notificationController) {
        onDispose { notificationController?.release() }
    }
    DisposableEffect(Unit) {
        onDispose {
            pendingSingleTapJob?.cancel()
            pendingGamepadCollectJob?.cancel()
        }
    }
    DisposableEffect(controlModeEnabled, view) {
        view.keepScreenOn = controlModeEnabled
        onDispose {
            view.keepScreenOn = false
        }
    }
    DisposableEffect(controlModeEnabled, context) {
        val shouldDimToMinimum = controlModeEnabled && loadGamepadControlConfig(context).dimScreenInControlMode
        val restoreBrightness = applyControlModeScreenBrightness(context, dimToMinimum = shouldDimToMinimum)
        onDispose {
            restoreBrightness()
        }
    }

    LaunchedEffect(hasSubtitleFile, uiTestMode) {
        if (!hasSubtitleFile && controlModeEnabled) {
            controlModeEnabled = false
            controlModeStatus = null
        }
        if ((!hasSubtitleFile || !audiobookSettings.lookupRangeSelectionEnabled) && cueRangeSelectionMode) {
            cueRangeSelectionMode = false
            cueRangeStartIndex = null
            cueRangeEndIndex = null
        }
        if (!hasSubtitleFile) {
            hoshiLookupPopupTemporarilyHidden = false
        }
    }

    LaunchedEffect(audiobookSettings.lookupRangeSelectionEnabled) {
        if (!audiobookSettings.lookupRangeSelectionEnabled) {
            cueRangeSelectionMode = false
            cueRangeStartIndex = null
            cueRangeEndIndex = null
            reopenHoshiLookupPopupAfterCueRangeSelection = false
            hoshiLookupPopupTemporarilyHidden = false
        }
    }

    if (!uiTestMode || temporaryLookupMode) DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = isReaderPlaybackRequested()
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = if (player.duration > 0L) player.duration else 0L
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                isPlaying = isReaderPlaybackRequested()
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = if (player.duration > 0L) player.duration else 0L
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isPlaying = isReaderPlaybackRequested()
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = if (player.duration > 0L) player.duration else 0L
                if (playbackState == Player.STATE_ENDED && !temporaryLookupMode) {
                    playbackCompleted = true
                    val endedDurationMs = if (player.duration > 0L) player.duration else 0L
                    scope.launch(Dispatchers.IO) {
                        saveBookReaderPlaybackPosition(
                            context = context,
                            bookKey = playbackPositionKey,
                            positionMs = 0L,
                            durationMs = endedDurationMs,
                            allowZeroPositionWrite = true
                        )
                        recordStatisticsBookCompleted(context, playbackPositionKey)
                        cleanupBookReaderSrtCache(context)
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                positionMs = newPosition.positionMs.coerceAtLeast(0L)
                val total = if (player.duration > 0L) player.duration else 0L
                if (total <= 0L || newPosition.positionMs < total - 1_500L) {
                    playbackCompleted = false
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val latestCueLoopEnabled = rememberUpdatedState(cueLoopEnabled)
    val latestCueLoopWindow = rememberUpdatedState(cueLoopWindow)
    val audioCueLoopBoundaryTarget = remember(player) {
        object : PlayerMessage.Target {
            override fun handleMessage(messageType: Int, message: Any?) {
                if (messageType != BOOK_READER_AUDIO_CUE_LOOP_MESSAGE_TYPE) return
                val startMs = message as? Long ?: return
                activity?.runOnUiThread {
                    if (
                        !latestCueLoopEnabled.value ||
                        latestCueLoopWindow.value?.first != startMs
                    ) {
                        return@runOnUiThread
                    }
                    player.seekTo(startMs)
                    positionMs = startMs
                }
            }
        }
    }
    DisposableEffect(player, cueLoopEnabled, cueLoopWindow, temporaryLookupMode) {
        val window = cueLoopWindow
        if (temporaryLookupMode || !cueLoopEnabled || window == null) {
            onDispose { }
        } else {
            val startMs = window.first.coerceAtLeast(0L)
            val endMs = window.second.coerceAtLeast(startMs + 1L)
            val loopAt = (endMs - 40L).coerceAtLeast(startMs)
            val boundaryMessage = player.createMessage(audioCueLoopBoundaryTarget)
                .setType(BOOK_READER_AUDIO_CUE_LOOP_MESSAGE_TYPE)
                .setPayload(startMs)
                .setPosition(loopAt)
                .setDeleteAfterDelivery(false)
                .send()
            onDispose { boundaryMessage.cancel() }
        }
    }

    if (!uiTestMode || temporaryLookupMode) LaunchedEffect(
        player,
        isPlaying,
        cueLoopEnabled,
        cueLoopWindow,
        audiobookSettings.readerPlaybackMode,
        cues,
        dragPreviewPositionMs,
        temporaryLookupMode,
        temporaryLookupStartMs,
        temporaryLookupEndMs
    ) {
        if (!isPlaying) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = if (player.duration > 0L) player.duration else 0L
            if (
                temporaryLookupMode &&
                temporaryLookupEndMs > temporaryLookupStartMs &&
                positionMs >= temporaryLookupEndMs
            ) {
                player.pause()
                player.seekTo(temporaryLookupStartMs.coerceAtLeast(0L))
                isPlaying = false
                return@LaunchedEffect
            }
            return@LaunchedEffect
        }
        var lastCueIndex = findBookCueIndexAtTime(cues, player.currentPosition.coerceAtLeast(0L))
        var condensedSkipBlockedUntilMs = 0L
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = if (player.duration > 0L) player.duration else 0L
            val currentCueIndex = findBookCueIndexAtTime(cues, positionMs)
            if (!temporaryLookupMode && !cueLoopEnabled && audiobookSettings.readerPlaybackMode == ReaderPlaybackMode.CONDENSED) {
                val nowMs = SystemClock.elapsedRealtime()
                val target = findCondensedPlaybackSeekTarget(
                    cues = cues,
                    previousCueIndex = lastCueIndex,
                    currentCueIndex = currentCueIndex,
                    timeMs = positionMs
                )
                if (
                    target != null &&
                    dragPreviewPositionMs == null &&
                    nowMs >= condensedSkipBlockedUntilMs
                ) {
                    condensedSkipBlockedUntilMs = nowMs + 1_000L
                    player.seekTo(target)
                    positionMs = target
                }
            }
            lastCueIndex = currentCueIndex
            delay(250L)
        }
    }

    if (!uiTestMode) LaunchedEffect(
        audioUri,
        playbackPositionKey,
        temporaryLookupMode,
        temporaryLookupStartMs,
        temporaryLookupEndMs
    ) {
        playbackRestoreCompleted = false
        playbackCompleted = false
        val selectedAudio = audioUri ?: run {
            playbackRestoreCompleted = true
            return@LaunchedEffect
        }
        if (temporaryLookupMode) {
            player.setMediaItem(MediaItem.fromUri(selectedAudio))
            player.prepare()
            player.seekTo(temporaryLookupStartMs.coerceAtLeast(0L))
            player.pause()
            positionMs = temporaryLookupStartMs.coerceAtLeast(0L)
            durationMs = if (player.duration > 0L) player.duration else 0L
            isPlaying = false
            playbackRestoreCompleted = true
            return@LaunchedEffect
        }
        val sameSharedAudio = BookReaderPlaybackSession.currentAudioUri() == selectedAudio.toString()
        val keepLiveSession = sameSharedAudio && isReaderPlaybackRequested()
        val restoredSnapshotPositionMs = if (keepLiveSession) {
            null
        } else {
            currentReaderBook?.let { book ->
                withContext(Dispatchers.IO) {
                    loadBookReaderPlaybackSnapshotOrNull(
                        context,
                        buildReaderBookPlaybackKey(book)
                    )?.positionMs
                }
            }?.coerceAtLeast(0L)
        }
        val sessionPositionMs = if (sameSharedAudio) player.currentPosition.coerceAtLeast(0L) else 0L
        val restoredPositionMs = when {
            keepLiveSession -> sessionPositionMs
            restoredSnapshotPositionMs != null -> restoredSnapshotPositionMs
            else -> sessionPositionMs
        }
        val forceSeekOnSameAudio = !keepLiveSession && restoredSnapshotPositionMs != null
        logDebug(BOOK_READER_BACK_LOG_TAG) {
            "restoreAudio sameAudio=$sameSharedAudio keepLive=$keepLiveSession " +
            "forceSeek=$forceSeekOnSameAudio snapshot=$restoredSnapshotPositionMs " +
            "session=$sessionPositionMs target=$restoredPositionMs"
        }
        BookReaderPlaybackSession.prepareAudioIfNeeded(
            context = context,
            audioUri = selectedAudio,
            restorePositionMs = restoredPositionMs,
            forceSeekOnSameAudio = forceSeekOnSameAudio
        )
        positionMs = player.currentPosition.coerceAtLeast(0L)
        durationMs = if (player.duration > 0L) player.duration else durationMs.coerceAtLeast(0L)
        isPlaying = isReaderPlaybackRequested()
        playbackRestoreCompleted = true
    }

    if (!uiTestMode) LaunchedEffect(audioUri) {
        val selectedAudio = audioUri ?: run {
            audioChapters = emptyList()
            return@LaunchedEffect
        }
        // Delay chapter parsing slightly to avoid competing with first-play startup IO.
        delay(500L)
        val loadedChapters = withContext(Dispatchers.IO) {
            loadM4bChapters(
                context = context,
                contentResolver = contentResolver,
                audioUri = selectedAudio
            )
                .map { chapter ->
                    ReaderAudioChapter(
                        startMs = chapter.startMs,
                        title = chapter.title
                    )
                }
        }
        audioChapters = loadedChapters
    }

    if (!uiTestMode) LaunchedEffect(srtUri) {
        val uri = srtUri ?: return@LaunchedEffect
        srtLoading = true
        srtError = null
        val result = withContext(Dispatchers.IO) {
            runCatching { parseBookSrtWithCache(context, contentResolver, uri) }
        }
        srtLoading = false
        result.onSuccess { cues = it }
            .onFailure {
                cues = emptyList()
                srtError = it.message ?: "Failed to parse SRT"
            }
    }

    suspend fun loadReaderDictionariesSnapshot(): List<LoadedDictionary> {
        return withContext(Dispatchers.IO) {
            loadAvailableDictionaries(context)
        }
    }

    LaunchedEffect(dictionaryDataVersion) {
        loadedDictionaries = loadReaderDictionariesSnapshot()
    }

    val dictionaryCssByName = remember(loadedDictionaries) {
        loadedDictionaries.associate { it.name to it.stylesCss }
    }
    val dictionaryPriorityByName = remember(loadedDictionaries) {
        loadedDictionaries.mapIndexed { index, dictionary -> dictionary.name to index }.toMap()
    }
    val bookHoshiLookupSession = remember(context, loadedDictionaries) {
        HoshiLookupSession(context, dictionariesProvider = { loadedDictionaries })
    }
    val hoshiLookupPopupVisible = hoshiLookupPopups.isNotEmpty()
    val lyricsFollowTopPaddingPx = with(LocalDensity.current) { 72.dp.toPx() }

    DisposableEffect(context) {
        val listener = registerDictionaryDataVersionListener(context) { version ->
            dictionaryDataVersion = version
        }
        onDispose {
            unregisterDictionaryDataVersionListener(context, listener)
        }
    }

    val playbackCueIndex = remember(positionMs, cues) { findBookCueIndexAtTime(cues, positionMs) }
    val effectiveAdjacentJumpMode = remember(cues, adjacentJumpMode) {
        if (cues.isEmpty()) AdjacentJumpMode.DURATION else adjacentJumpMode
    }
    val previewPositionMs = remember(positionMs, dragPreviewPositionMs) {
        dragPreviewPositionMs ?: positionMs
    }
    val activeCueIndex = remember(previewPositionMs, cues) { findBookDisplayCueIndexAtTime(cues, previewPositionMs) }
    val visibleSelectedRange = remember(
        activeCueIndex,
        hoshiLookupSelectionCueIndex,
        hoshiLookupSelectionRange
    ) {
        if (hoshiLookupSelectionCueIndex == activeCueIndex) {
            hoshiLookupSelectionRange
        } else {
            null
        }
    }
    LaunchedEffect(visibleSelectedRange, activeCueIndex, readerUiWritingMode, lyricsMode) {
        logDebug(BOOK_LOOKUP_SELECTION_LOG_TAG) {
            "visibleRange activeCue=$activeCueIndex mode=$readerUiWritingMode lyrics=$lyricsMode range=${formatRangeForLog(visibleSelectedRange)}"
        }
    }
    LaunchedEffect(activeCueIndex, visibleSelectedRange, lyricsMode) {
        if (visibleSelectedRange == null) {
            liveSelectedRangeAnchor = null
        }
    }
    LaunchedEffect(liveSelectedRangeAnchor, visibleSelectedRange, hoshiLookupPopups.size) {
        val range = visibleSelectedRange ?: return@LaunchedEffect
        val avoidRects = liveSelectedRangeAnchor.toSelectionRects(rootDensity.density)
        if (avoidRects.isEmpty()) return@LaunchedEffect
        val popupIndex = hoshiLookupPopups.indexOfFirst {
            it.state.selection.sentenceOffset?.let { offset -> offset in range } == true
        }.takeIf { it >= 0 } ?: 0.takeIf { hoshiLookupPopups.isNotEmpty() } ?: return@LaunchedEffect
        val current = hoshiLookupPopups.getOrNull(popupIndex) ?: return@LaunchedEffect
        if (current.state.avoidRects == avoidRects) return@LaunchedEffect
        hoshiLookupPopups[popupIndex] = current.copy(
            state = current.state.copy(
                avoidRects = avoidRects
            )
        )
    }
    val activeCue = cues.getOrNull(activeCueIndex)
    val activeCueScrollProgress = remember(activeCue, previewPositionMs) {
        activeCue?.let { cue ->
            val duration = (cue.endMs - cue.startMs).coerceAtLeast(1L)
            mapTimedSubtitleScrollProgress(
                ((previewPositionMs - cue.startMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            )
        } ?: 0f
    }
    val selectedCueIndexRange = remember(cueRangeStartIndex, cueRangeEndIndex) {
        val start = cueRangeStartIndex ?: return@remember null
        val end = cueRangeEndIndex ?: start
        minOf(start, end)..maxOf(start, end)
    }
    val backToMain = remember(onBack, positionMs, durationMs) {
        { source: String ->
            val current = positionMs.coerceAtLeast(0L)
            val total = if (player.duration > 0L) player.duration else durationMs.coerceAtLeast(0L)
            logDebug(BOOK_READER_BACK_LOG_TAG) {
                "backToMain source=$source hoshiLookupVisible=${hoshiLookupPopups.isNotEmpty()} " +
                "hoshiLookupLayers=${hoshiLookupPopups.size} playing=${player.isPlaying} " +
                "statePositionMs=$current playerPositionMs=${player.currentPosition.coerceAtLeast(0L)} " +
                "durationMs=$total"
            }
            onBack(current, total)
        }
    }
    val subtitleTypeface = remember(
        audiobookSettings.subtitleCustomFontUri
    ) {
        resolveSubtitleTypeface(context, audiobookSettings.subtitleCustomFontUri)
    }
    val subtitleFontFamily = remember(subtitleTypeface) {
        subtitleTypeface?.let { typeface ->
            runCatching { FontFamily(typeface) }.getOrNull()
        }
    }
    val currentBookVerticalWriting = readerUiWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL
    val bookSubtitleActiveSizeSp = if (currentBookVerticalWriting) {
        audiobookSettings.bookSubtitleVerticalActiveSizeSp
    } else {
        audiobookSettings.bookSubtitleActiveSizeSp
    }
    val bookSubtitleInactiveSizeSp = if (currentBookVerticalWriting) {
        audiobookSettings.bookSubtitleVerticalInactiveSizeSp
    } else {
        audiobookSettings.bookSubtitleInactiveSizeSp
    }
    val bookSubtitleHorizontalLineHeightSp = audiobookSettings.bookSubtitleHorizontalLineHeightSp
        .coerceAtLeast(bookSubtitleActiveSizeSp)
    val inactiveSubtitleHorizontalLineHeightSp = (
        bookSubtitleInactiveSizeSp +
            (bookSubtitleHorizontalLineHeightSp - bookSubtitleActiveSizeSp).coerceAtLeast(0)
        ).coerceAtLeast(bookSubtitleInactiveSizeSp)
    val bookVerticalColumnSpacingScale = audiobookSettings.bookSubtitleVerticalColumnSpacingPercent / 100f
    val activeSubtitleStyle = MaterialTheme.typography.headlineMedium.copy(
        fontSize = bookSubtitleActiveSizeSp.sp,
        lineHeight = if (currentBookVerticalWriting) bookSubtitleActiveSizeSp.sp else bookSubtitleHorizontalLineHeightSp.sp,
        fontFamily = subtitleFontFamily,
        color = MaterialTheme.colorScheme.onSurface
    )
    val inactiveSubtitleStyle = MaterialTheme.typography.titleLarge.copy(
        fontSize = bookSubtitleInactiveSizeSp.sp,
        lineHeight = if (currentBookVerticalWriting) bookSubtitleInactiveSizeSp.sp else inactiveSubtitleHorizontalLineHeightSp.sp,
        fontFamily = subtitleFontFamily,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val reloadAudiobookSettings = {
        audiobookSettings = loadAudiobookSettingsConfig(context)
    }

    LaunchedEffect(context) {
        val options = loadBookReaderSleepOptions(context)
        sleepExitControlModeWhenDone = options.exitControlModeWhenDone
        sleepDisconnectControllerBluetoothWhenDone = options.disconnectBluetoothWhenDone
        sleepFadeOutAudioWhenDone = options.fadeOutAudioWhenDone
        sleepOptionsReady = true
    }

    LaunchedEffect(
        sleepExitControlModeWhenDone,
        sleepDisconnectControllerBluetoothWhenDone,
        sleepFadeOutAudioWhenDone,
        sleepOptionsReady
    ) {
        if (!sleepOptionsReady) return@LaunchedEffect
        val currentSleepOptions = loadBookReaderSleepOptions(context)
        saveBookReaderSleepOptions(
            context = context,
            exitControlModeWhenDone = sleepExitControlModeWhenDone,
            disconnectBluetoothWhenDone = sleepDisconnectControllerBluetoothWhenDone,
            fadeOutAudioWhenDone = sleepFadeOutAudioWhenDone,
            defaultTimerMinutes = currentSleepOptions.defaultTimerMinutes
        )
    }

    LaunchedEffect(playbackSpeed, uiTestMode, temporaryLookupMode) {
        if (!uiTestMode) {
            player.playbackParameters = PlaybackParameters(playbackSpeed)
            if (!temporaryLookupMode) BookReaderFloatingBridge.notifyPlaybackSpeed(playbackSpeed)
        }
    }

    LaunchedEffect(isPlaying, uiTestMode, temporaryLookupMode, playbackRestoreCompleted) {
        if (!uiTestMode && !temporaryLookupMode && playbackRestoreCompleted) {
            BookReaderFloatingBridge.notifyPlaybackState(isPlaying)
        }
    }
    LaunchedEffect(context, playbackPositionKey, uiTestMode, temporaryLookupMode) {
        if (!uiTestMode && !temporaryLookupMode) {
            BookReaderFloatingBridge.setCurrentBookKey(context, playbackPositionKey)
        }
    }
    LaunchedEffect(positionMs, uiTestMode, temporaryLookupMode) {
        if (!uiTestMode && !temporaryLookupMode) {
            BookReaderFloatingBridge.notifyPlaybackPosition(positionMs)
        }
    }
    LaunchedEffect(cues, title, audioUri, uiTestMode, temporaryLookupMode) {
        if (!uiTestMode && !temporaryLookupMode) {
            BookReaderFloatingBridge.setSubtitleTimeline(
                bookTitle = title,
                audioUri = audioUri?.toString(),
                cues = cues.map { cue ->
                    BookReaderFloatingBridge.SubtitleTimelineCue(
                        startMs = cue.startMs,
                        endMs = cue.endMs,
                        text = cue.text,
                        fullSentenceText = cue.text,
                        fullSentenceStartMs = cue.startMs,
                        fullSentenceEndMs = cue.endMs
                    )
                }
            )
        }
    }

    val activeChapterIndex = remember(previewPositionMs, audioChapters) {
        findBookChapterIndexAtTime(audioChapters, previewPositionMs)
    }
    val useChapterTimeline = activeChapterIndex in audioChapters.indices
    val activeChapterStartMs = if (useChapterTimeline) {
        audioChapters[activeChapterIndex].startMs.coerceAtLeast(0L)
    } else {
        0L
    }
    val activeChapterEndMs = if (useChapterTimeline) {
        val nextChapterStart = audioChapters
            .getOrNull(activeChapterIndex + 1)
            ?.startMs
            ?.coerceAtLeast(activeChapterStartMs)
        when {
            nextChapterStart != null -> nextChapterStart
            durationMs > activeChapterStartMs -> durationMs
            else -> activeChapterStartMs + 1L
        }
    } else {
        durationMs
    }.coerceAtLeast(activeChapterStartMs + 1L)
    val timelineRangeMs = if (useChapterTimeline) {
        (activeChapterEndMs - activeChapterStartMs).coerceAtLeast(1L)
    } else {
        durationMs.coerceAtLeast(1L)
    }
    val sliderMax = timelineRangeMs.toFloat()
    val sliderValue = if (useChapterTimeline) {
        val preview = dragPreviewPositionMs ?: previewPositionMs
        (preview - activeChapterStartMs)
            .coerceIn(0L, timelineRangeMs)
            .toFloat()
    } else {
        when {
            durationMs <= 0L -> 0f
            dragPreviewPositionMs != null -> dragPreviewPositionMs!!.coerceIn(0L, durationMs).toFloat()
            else -> positionMs.coerceIn(0L, durationMs).toFloat()
        }
    }
    val displayedPreviewTimeMs = if (useChapterTimeline) {
        (previewPositionMs - activeChapterStartMs).coerceIn(0L, timelineRangeMs)
    } else {
        previewPositionMs.coerceAtLeast(0L)
    }
    val displayedLeftTimeMs = if (useChapterTimeline && showOverallDuration) {
        previewPositionMs.coerceAtLeast(0L)
    } else {
        displayedPreviewTimeMs
    }
    val displayedDurationTimeMs = if (useChapterTimeline) timelineRangeMs else durationMs.coerceAtLeast(0L)
    val displayedRightDurationTimeMs = if (useChapterTimeline && showOverallDuration) {
        durationMs.coerceAtLeast(0L)
    } else {
        displayedDurationTimeMs
    }
    val progressPercent = remember(displayedPreviewTimeMs, displayedDurationTimeMs) {
        if (displayedDurationTimeMs <= 0L) {
            0
        } else {
            ((displayedPreviewTimeMs.coerceIn(0L, displayedDurationTimeMs) * 100L) / displayedDurationTimeMs)
                .toInt()
                .coerceIn(0, 100)
        }
    }
    val totalProgressPercent = remember(previewPositionMs, durationMs) {
        val total = durationMs.coerceAtLeast(0L)
        if (total <= 0L) {
            0
        } else {
            ((previewPositionMs.coerceIn(0L, total) * 100L) / total)
                .toInt()
                .coerceIn(0, 100)
        }
    }
    LaunchedEffect(useChapterTimeline) {
        if (!useChapterTimeline) {
            showOverallProgress = false
            showOverallDuration = false
        }
    }
    val sleepRemainingLabel = sleepTimerRemainingMs?.let(::formatPlaybackTime)
    val favoriteCue = remember(playbackCueIndex, activeCueIndex, cues) {
        when {
            playbackCueIndex in cues.indices -> cues[playbackCueIndex]
            activeCueIndex in cues.indices -> cues[activeCueIndex]
            else -> null
        }
    }
    val favoriteCueKey = remember(favoriteCue) {
        favoriteCue?.let { cueCollectionKey(it.startMs, it.endMs, it.text) }
    }
    val favoriteCueCollected = remember(favoriteCueKey, collectedCueUiVersion) {
        favoriteCueKey?.let { collectedCueKeys.contains(it) } == true
    }
    LaunchedEffect(audioChapters.size) {
        if (audioChapters.isEmpty()) {
            chapterOptionsVisible = false
        }
    }

    LaunchedEffect(playbackPositionKey, player, isPlaying, playbackRestoreCompleted, temporaryLookupMode) {
        if (uiTestMode || temporaryLookupMode) return@LaunchedEffect
        if (playbackPositionKey.isBlank()) return@LaunchedEffect
        if (!playbackRestoreCompleted) return@LaunchedEffect
        var lastSavedPosition = Long.MIN_VALUE
        suspend fun saveSnapshotIfChanged() {
            val current = player.currentPosition.coerceAtLeast(0L)
            val total = if (player.duration > 0L) player.duration else 0L
            val normalized = if (playbackCompleted) 0L else normalizeBookReaderPlaybackPosition(current, total)
            if (normalized == lastSavedPosition) return
            lastSavedPosition = normalized
            withContext(Dispatchers.IO) {
                saveBookReaderPlaybackPosition(
                    context = context,
                    bookKey = playbackPositionKey,
                    positionMs = normalized,
                    durationMs = total
                )
            }
        }
        if (!isPlaying) {
            saveSnapshotIfChanged()
            return@LaunchedEffect
        }
        while (true) {
            delay(2_500L)
            saveSnapshotIfChanged()
        }
    }

    DisposableEffect(playbackPositionKey, player, playbackRestoreCompleted) {
        onDispose {
            if (uiTestMode || temporaryLookupMode) return@onDispose
            if (!playbackRestoreCompleted) return@onDispose
            val current = player.currentPosition.coerceAtLeast(0L)
            val total = if (player.duration > 0L) player.duration else 0L
            val normalized = if (playbackCompleted) 0L else normalizeBookReaderPlaybackPosition(current, total)
            saveBookReaderPlaybackPosition(
                context = context,
                bookKey = playbackPositionKey,
                positionMs = normalized,
                durationMs = total
            )
        }
    }

    LaunchedEffect(title) {
        val existing = withContext(Dispatchers.IO) { loadBookReaderCollectedCues(context) }
        collectedCueKeys.clear()
        existing
            .filter { it.bookTitle == title }
            .forEach { cue ->
                collectedCueKeys += cueCollectionKey(cue.startMs, cue.endMs, cue.text)
            }
        collectedCueUiVersion += 1
    }

    LaunchedEffect(
        sleepTimerDeadlineMs,
        sleepExitControlModeWhenDone,
        sleepDisconnectControllerBluetoothWhenDone,
        sleepFadeOutAudioWhenDone
    ) {
        val deadline = sleepTimerDeadlineMs ?: run {
            sleepTimerRemainingMs = null
            return@LaunchedEffect
        }
        var fadeApplied = false
        try {
            while (sleepTimerDeadlineMs == deadline) {
                val remainingMs = deadline - System.currentTimeMillis()
                sleepTimerRemainingMs = remainingMs.coerceAtLeast(0L)
                if (
                    sleepFadeOutAudioWhenDone &&
                    !uiTestMode &&
                    remainingMs in 1L until BOOK_READER_SLEEP_FADE_OUT_MS &&
                    isReaderPlaybackRequested()
                ) {
                    player.volume = sleepFadeVolume(remainingMs, BOOK_READER_SLEEP_FADE_OUT_MS)
                    fadeApplied = true
                } else if (fadeApplied && remainingMs > BOOK_READER_SLEEP_FADE_OUT_MS) {
                    player.volume = 1f
                    fadeApplied = false
                }
                if (remainingMs <= 0L) {
                    if (sleepFadeOutAudioWhenDone && !uiTestMode) {
                        pauseWithSleepFadeRewind(player)
                    } else {
                        setReaderPlaybackState(false)
                    }
                    sleepTimerDeadlineMs = null
                    sleepTimerRemainingMs = null
                    BookReaderFloatingBridge.setSleepTimerDeadline(null)
                    val statusParts = mutableListOf<String>()
                    if (sleepExitControlModeWhenDone) {
                        controlModeEnabled = false
                        view.keepScreenOn = false
                        statusParts += context.getString(R.string.status_control_mode_exited)
                    }
                    if (sleepDisconnectControllerBluetoothWhenDone) {
                        val address = latestControllerAddressProvider()
                        val behavior = loadControllerBluetoothBehaviorConfig(context)
                        val bluetoothResult = withContext(Dispatchers.IO) {
                            tryDisconnectTargetControllerThenDisableBluetooth(
                                context = context,
                                targetAddress = address,
                                allowDisableBluetoothFallback = behavior.disableBluetoothIfControllerMissing
                            )
                        }
                        when (bluetoothResult.outcome) {
                            SleepBluetoothOutcome.TARGET_DISCONNECTED -> {
                                statusParts += context.getString(R.string.status_controller_disconnected)
                            }
                            SleepBluetoothOutcome.BLUETOOTH_DISABLED -> {
                                statusParts += context.getString(R.string.status_bluetooth_disabled_fallback)
                            }
                            SleepBluetoothOutcome.FAILED -> {
                                statusParts += context.getString(R.string.status_bluetooth_failed, bluetoothResult.detail)
                            }
                        }
                    }
                    if (statusParts.isEmpty()) {
                        controlModeStatus = context.getString(R.string.status_timer_finished)
                    } else {
                        controlModeStatus = context.getString(R.string.status_timer_finished_with_parts, statusParts.joinToString(", "))
                    }
                    break
                }
                delay(if (fadeApplied) 200L else 250L)
            }
        } finally {
            if (fadeApplied && sleepTimerDeadlineMs != null && !uiTestMode) {
                player.volume = 1f
            }
        }
    }

    LaunchedEffect(positionMs, controlTargetCueIndex, cues) {
        val targetIndex = controlTargetCueIndex ?: return@LaunchedEffect
        val cue = cues.getOrNull(targetIndex) ?: run {
            controlTargetCueIndex = null
            return@LaunchedEffect
        }
        if (positionMs >= cue.endMs) {
            controlTargetCueIndex = null
            val key = cueCollectionKey(cue.startMs, cue.endMs, cue.text)
            if (collectedCueKeys.add(key)) {
                collectedCueUiVersion += 1
                val added = withContext(Dispatchers.IO) {
                    val chapterMeta = buildCollectedCueChapterMeta(audioChapters, cue.startMs, cue.endMs)
                    appendBookReaderCollectedCue(
                        context,
                        BookReaderCollectedCue(
                            id = "${System.currentTimeMillis()}-${cue.startMs}-${cue.endMs}-${cue.text.hashCode()}",
                            bookTitle = title,
                            text = cue.text,
                            startMs = cue.startMs,
                            endMs = cue.endMs,
                            savedAtMs = System.currentTimeMillis(),
                            chapterIndex = chapterMeta?.chapterIndex,
                            chapterTitle = chapterMeta?.chapterTitle,
                            chapterStartMs = chapterMeta?.chapterStartMs,
                            chapterStartOffsetMs = chapterMeta?.startOffsetMs,
                            chapterEndOffsetMs = chapterMeta?.endOffsetMs
                        )
                    )
                }
                if (added) {
                    controlModeStatus = context.getString(R.string.status_bookmarked_continue, targetIndex + 1, cues.size)
                }
            } else {
                controlModeStatus = context.getString(R.string.status_already_bookmarked_continue, targetIndex + 1, cues.size)
            }
        }
    }

    fun toggleFavoriteCue() {
        if (uiTestMode) return
        val cue = favoriteCue ?: return
        val key = cueCollectionKey(cue.startMs, cue.endMs, cue.text)
        val cueIndexLabel = when {
            playbackCueIndex in cues.indices -> "${playbackCueIndex + 1}/${cues.size}"
            activeCueIndex in cues.indices -> "${activeCueIndex + 1}/${cues.size}"
            else -> null
        }
        scope.launch {
            if (collectedCueKeys.contains(key)) {
                val removed = withContext(Dispatchers.IO) {
                    val existing = loadBookReaderCollectedCues(context)
                    val matched = existing.filter {
                        it.bookTitle == title &&
                            it.startMs == cue.startMs &&
                            it.endMs == cue.endMs &&
                            it.text == cue.text
                    }
                    matched.forEach { item ->
                        removeBookReaderCollectedCue(context, item.id)
                    }
                    matched.isNotEmpty()
                }
                if (removed) {
                    collectedCueKeys.remove(key)
                    collectedCueUiVersion += 1
                    controlModeStatus = cueIndexLabel?.let { context.getString(R.string.status_unbookmarked_cue, it) }
                        ?: context.getString(R.string.status_unbookmarked)
                } else {
                    controlModeStatus = context.getString(R.string.status_bookmark_not_found)
                }
            } else {
                val added = withContext(Dispatchers.IO) {
                    val chapterMeta = buildCollectedCueChapterMeta(audioChapters, cue.startMs, cue.endMs)
                    appendBookReaderCollectedCue(
                        context,
                        BookReaderCollectedCue(
                            id = "${System.currentTimeMillis()}-${cue.startMs}-${cue.endMs}-${cue.text.hashCode()}",
                            bookTitle = title,
                            text = cue.text,
                            startMs = cue.startMs,
                            endMs = cue.endMs,
                            savedAtMs = System.currentTimeMillis(),
                            chapterIndex = chapterMeta?.chapterIndex,
                            chapterTitle = chapterMeta?.chapterTitle,
                            chapterStartMs = chapterMeta?.chapterStartMs,
                            chapterStartOffsetMs = chapterMeta?.startOffsetMs,
                            chapterEndOffsetMs = chapterMeta?.endOffsetMs
                        )
                    )
                }
                if (added) {
                    collectedCueKeys.add(key)
                    collectedCueUiVersion += 1
                    controlModeStatus = cueIndexLabel?.let { context.getString(R.string.status_bookmarked_cue, it) }
                        ?: context.getString(R.string.status_bookmarked)
                } else {
                    controlModeStatus = context.getString(R.string.status_already_bookmarked)
                    collectedCueKeys.add(key)
                    collectedCueUiVersion += 1
                }
            }
        }
    }

    LaunchedEffect(activeCueIndex, lyricsMode, cues.size, dragPreviewPositionMs, audiobookSettings.activeCueDisplayAtTop) {
        if (!lyricsMode || activeCueIndex < 0 || cues.isEmpty()) return@LaunchedEffect
        if (dragPreviewPositionMs != null) return@LaunchedEffect
        if (lyricsListState.isScrollInProgress) return@LaunchedEffect
        if (lyricsListState.layoutInfo.visibleItemsInfo.none { it.index == activeCueIndex }) {
            lyricsListState.scrollToItem(activeCueIndex)
        }
        val activeItem = lyricsListState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeCueIndex }
            ?: return@LaunchedEffect
        val layoutInfo = lyricsListState.layoutInfo
        val itemCenter = activeItem.offset + (activeItem.size / 2f)
        val targetCenter = if (audiobookSettings.activeCueDisplayAtTop) {
            layoutInfo.viewportStartOffset + lyricsFollowTopPaddingPx + (activeItem.size / 2f)
        } else {
            (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
        }
        val delta = itemCenter - targetCenter
        if (abs(delta) > 1f) {
            lyricsListState.scrollBy(delta)
        }
    }

    fun jumpToCue(index: Int, showStatus: Boolean = true) {
        val cue = cues.getOrNull(index) ?: return
        resumePlaybackAfterLookupDismiss = false
        if (uiTestMode) {
            positionMs = cue.startMs.coerceAtLeast(0L)
            isPlaying = true
        } else {
            player.seekTo(cue.startMs)
            player.play()
        }
        controlTargetCueIndex = if (controlModeEnabled) index else null
        if (showStatus) {
            controlModeStatus = context.getString(R.string.status_jump_to_cue, index + 1, cues.size)
        }
    }

    fun clearCueRangeSelection() {
        cueRangeSelectionMode = false
        cueRangeStartIndex = null
        cueRangeEndIndex = null
        reopenHoshiLookupPopupAfterCueRangeSelection = false
        hoshiLookupPopupTemporarilyHidden = false
    }

    fun beginHoshiCueRangeSelection(reopenLookupPopupAfterSelection: Boolean) {
        if (!audiobookSettings.lookupRangeSelectionEnabled) return
        cueRangeSelectionMode = true
        cueRangeStartIndex = null
        cueRangeEndIndex = null
        reopenHoshiLookupPopupAfterCueRangeSelection = reopenLookupPopupAfterSelection
        hoshiLookupPopupTemporarilyHidden = reopenLookupPopupAfterSelection
        if (!lyricsMode) lyricsMode = true
        if (coverModeEnabled) coverModeEnabled = false
        controlModeStatus = context.getString(R.string.bookreader_range_select_start)
    }

    fun consumeCueRangeSelection() {
        if (selectedCueIndexRange != null) {
            clearCueRangeSelection()
        }
    }

    fun handleCueRangeTap(index: Int) {
        if (!cueRangeSelectionMode || cues.isEmpty()) return
        val normalizedIndex = index.coerceIn(0, cues.lastIndex)
        val start = cueRangeStartIndex
        val end = cueRangeEndIndex
        when {
            start == null || end != null -> {
                cueRangeStartIndex = normalizedIndex
                cueRangeEndIndex = null
                controlModeStatus = context.getString(
                    R.string.bookreader_range_start_selected,
                    normalizedIndex + 1
                )
            }
            else -> {
                cueRangeEndIndex = normalizedIndex
                val range = minOf(start, normalizedIndex)..maxOf(start, normalizedIndex)
                controlModeStatus = context.getString(
                    R.string.bookreader_range_end_selected,
                    range.first + 1,
                    range.last + 1,
                    range.count()
                )
                cueRangeSelectionMode = false
                if (reopenHoshiLookupPopupAfterCueRangeSelection) {
                    reopenHoshiLookupPopupAfterCueRangeSelection = false
                    hoshiLookupPopupTemporarilyHidden = false
                }
            }
        }
    }

    fun seekToManual(targetMs: Long) {
        val target = if (durationMs > 0L) {
            targetMs.coerceAtLeast(0L).coerceAtMost(durationMs)
        } else {
            targetMs.coerceAtLeast(0L)
        }
        pendingSingleTapJob?.cancel()
        pendingSingleTapJob = null
        pendingSingleTapBaseCueIndex = null
        controlTargetCueIndex = null
        resumePlaybackAfterLookupDismiss = false
        if (uiTestMode) {
            positionMs = target
        } else {
            val beforeSeekPositionMs = player.currentPosition.coerceAtLeast(0L)
            player.seekTo(target)
            positionMs = target
            val total = if (player.duration > 0L) player.duration else durationMs.coerceAtLeast(0L)
            logDebug(BOOK_READER_SEEK_LOG_TAG) {
                "seekToManual targetMs=$target beforePlayerMs=$beforeSeekPositionMs " +
                "statePositionMs=$positionMs durationMs=$total keyPresent=${playbackPositionKey.isNotBlank()}"
            }
            if (playbackPositionKey.isNotBlank() && total > 0L) {
                scope.launch(Dispatchers.IO) {
                    val normalized = normalizeBookReaderPlaybackPosition(target, total)
                    saveBookReaderPlaybackPosition(
                        context = context,
                        bookKey = playbackPositionKey,
                        positionMs = normalized,
                        durationMs = total
                    )
                    logDebug(BOOK_READER_SEEK_LOG_TAG) {
                        "seekToManual saved positionMs=$normalized durationMs=$total"
                    }
                }
            }
        }
        if (controlModeEnabled) {
            controlModeStatus = context.getString(R.string.status_manual_seek)
        }
    }

    fun jumpToAdjacentCue(step: Int) {
        val configuredStepMillis = loadAudiobookSettingsConfig(context).seekStepMillis
        logDebug(BOOK_READER_SEEK_LOG_TAG) {
            "jumpAdjacent step=$step mode=$effectiveAdjacentJumpMode configuredStepMs=$configuredStepMillis " +
            "position=$positionMs cueIndex=$playbackCueIndex"
        }
        if (effectiveAdjacentJumpMode == AdjacentJumpMode.DURATION) {
            val delta = if (step < 0) -configuredStepMillis else configuredStepMillis
            seekToManual(positionMs + delta)
            setReaderPlaybackState(true)
            return
        }
        if (cues.isEmpty()) return
        val lastIndex = cues.lastIndex
        val targetIndex = if (step < 0) {
            when {
                playbackCueIndex > 0 -> playbackCueIndex - 1
                playbackCueIndex == 0 -> 0
                else -> {
                    val before = findCueIndexAtOrBeforeTime(cues, positionMs)
                    if (before <= 0) 0 else before - 1
                }
            }
        } else {
            when {
                playbackCueIndex in 0 until lastIndex -> playbackCueIndex + 1
                playbackCueIndex == lastIndex -> lastIndex
                else -> {
                    val after = findCueIndexAtOrAfterTime(cues, positionMs)
                    if (after < 0) lastIndex else after
                }
            }
        }
        jumpToCue(targetIndex.coerceIn(0, lastIndex), showStatus = false)
    }
    fun setSleepTimer(minutes: Int) {
        if (!uiTestMode) {
            player.volume = 1f
        }
        sleepTimerDeadlineMs = if (minutes <= 0) {
            null
        } else {
            System.currentTimeMillis() + (minutes * 60_000L)
        }
        BookReaderFloatingBridge.setSleepTimerDeadline(sleepTimerDeadlineMs)
        sleepTimerOptionsVisible = false
        controlModeStatus = if (minutes <= 0) {
            context.getString(R.string.status_timer_cleared)
        } else {
            context.getString(R.string.status_timer_set, minutes)
        }
    }

    fun applyCustomSleepTimer() {
        val minutes = sleepCustomMinutesInput.trim().toIntOrNull()
        if (minutes == null || minutes <= 0) {
            controlModeStatus = context.getString(R.string.status_custom_minutes_invalid)
            return
        }
        setSleepTimer(minutes)
    }

    fun playCueForControl(index: Int) {
        val cue = cues.getOrNull(index) ?: return
        if (uiTestMode) {
            positionMs = cue.startMs.coerceAtLeast(0L)
            isPlaying = true
        } else {
            player.seekTo(cue.startMs)
            player.play()
        }
        controlTargetCueIndex = index
        controlModeStatus = context.getString(R.string.status_play_cue, index + 1, cues.size)
    }

    fun jumpToChapter(chapter: ReaderAudioChapter) {
        seekToManual(chapter.startMs)
        setReaderPlaybackState(true)
        chapterOptionsVisible = false
        controlModeStatus = context.getString(R.string.status_jump_chapter, chapter.title)
    }

    fun collectFavoriteCue(cueOverride: ReaderSubtitleCue? = null) {
        val cue = cueOverride ?: favoriteCue ?: return
        val key = cueCollectionKey(cue.startMs, cue.endMs, cue.text)
        val cueIndexLabel = when {
            playbackCueIndex in cues.indices -> "${playbackCueIndex + 1}/${cues.size}"
            activeCueIndex in cues.indices -> "${activeCueIndex + 1}/${cues.size}"
            else -> null
        }
        if (collectedCueKeys.contains(key)) {
            controlModeStatus = cueIndexLabel?.let { context.getString(R.string.status_already_bookmarked_cue, it) }
                ?: context.getString(R.string.status_already_bookmarked)
            return
        }
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                val chapterMeta = buildCollectedCueChapterMeta(audioChapters, cue.startMs, cue.endMs)
                appendBookReaderCollectedCue(
                    context,
                    BookReaderCollectedCue(
                        id = "${System.currentTimeMillis()}-${cue.startMs}-${cue.endMs}-${cue.text.hashCode()}",
                        bookTitle = title,
                        text = cue.text,
                        startMs = cue.startMs,
                        endMs = cue.endMs,
                        savedAtMs = System.currentTimeMillis(),
                        chapterIndex = chapterMeta?.chapterIndex,
                        chapterTitle = chapterMeta?.chapterTitle,
                        chapterStartMs = chapterMeta?.chapterStartMs,
                        chapterStartOffsetMs = chapterMeta?.startOffsetMs,
                        chapterEndOffsetMs = chapterMeta?.endOffsetMs
                    )
                )
            }
            collectedCueKeys.add(key)
            collectedCueUiVersion += 1
            controlModeStatus = if (added) {
                cueIndexLabel?.let { context.getString(R.string.status_bookmarked_cue, it) }
                    ?: context.getString(R.string.status_bookmarked)
            } else {
                cueIndexLabel?.let { context.getString(R.string.status_already_bookmarked_cue, it) }
                    ?: context.getString(R.string.status_already_bookmarked)
            }
        }
    }

    fun handleControlOverlayTap(): Long? {
        val currentIndex = playbackCueIndex.takeIf { it >= 0 } ?: return null
        val currentCue = cues.getOrNull(currentIndex) ?: return null
        val controlConfig = loadGamepadControlConfig(context)
        val now = System.currentTimeMillis()
        val doubleTapWindowMs = 280L
        val isDoubleTap = pendingSingleTapBaseCueIndex == currentIndex &&
            now - lastOverlayTapAtMs <= doubleTapWindowMs

        if (isDoubleTap && isPlaying && positionMs < currentCue.endMs) {
            pendingSingleTapJob?.cancel()
            pendingSingleTapJob = null
            pendingSingleTapBaseCueIndex = null
            playCueForControl((currentIndex - 1).coerceAtLeast(0))
            controlModeStatus = context.getString(R.string.status_double_tap_replay_prev)
            return (cues.getOrNull((currentIndex - 1).coerceAtLeast(0))?.endMs ?: currentCue.endMs) -
                (cues.getOrNull((currentIndex - 1).coerceAtLeast(0))?.startMs ?: currentCue.startMs)
        }

        pendingSingleTapJob?.cancel()
        pendingSingleTapBaseCueIndex = currentIndex
        lastOverlayTapAtMs = now
        pendingSingleTapJob = scope.launch {
            delay(doubleTapWindowMs)
            if (pendingSingleTapBaseCueIndex == currentIndex) {
                pendingSingleTapBaseCueIndex = null
                if (controlConfig.singleTapCollectOnlyInControlMode) {
                    collectFavoriteCue()
                    controlModeStatus = context.getString(R.string.status_single_tap_bookmark_direct)
                } else {
                    playCueForControl(currentIndex)
                    controlModeStatus = context.getString(R.string.status_single_tap_replay_bookmark)
                }
            }
        }
        return if (controlConfig.singleTapCollectOnlyInControlMode) 0L else currentCue.endMs - currentCue.startMs
    }

    fun handleGamepadCollect(config: GamepadControlConfig) {
        if (cues.isEmpty()) return
        val baseIndex = when {
            playbackCueIndex >= 0 -> playbackCueIndex
            else -> findCueIndexAtOrBeforeTime(cues, positionMs).coerceAtLeast(0)
        }
        val now = System.currentTimeMillis()
        val isDoubleTap = config.doubleTapCollectPrevious &&
            lastGamepadCollectCueIndex == baseIndex &&
            now - lastGamepadCollectTapAtMs <= 320L
        val targetIndex = if (isDoubleTap) {
            (baseIndex - 1).coerceAtLeast(0)
        } else {
            baseIndex
        }
        pendingGamepadCollectJob?.cancel()
        pendingGamepadCollectJob = null
        pendingGamepadCollectCueIndex = null
        lastGamepadCollectCueIndex = baseIndex
        lastGamepadCollectTapAtMs = now
        if (isDoubleTap) {
            playCueForControl(targetIndex)
            controlModeStatus = context.getString(R.string.status_gamepad_bookmark_prev)
            return
        }
        if (config.singleTapCollectOnlyInControlMode) {
            val collect = {
                collectFavoriteCue(cues.getOrNull(baseIndex))
                controlModeStatus = context.getString(R.string.status_single_tap_bookmark_direct)
            }
            if (config.doubleTapCollectPrevious) {
                pendingGamepadCollectCueIndex = baseIndex
                pendingGamepadCollectJob = scope.launch {
                    delay(320L)
                    if (pendingGamepadCollectCueIndex == baseIndex) {
                        pendingGamepadCollectCueIndex = null
                        pendingGamepadCollectJob = null
                        collect()
                    }
                }
            } else {
                collect()
            }
        } else {
            playCueForControl(baseIndex)
            controlModeStatus = context.getString(R.string.status_gamepad_bookmark_current)
        }
    }

    fun handleGamepadKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0) return true
        val config = loadGamepadControlConfig(context)

        return when (event.keyCode) {
            config.previousKeyCode -> {
                jumpToAdjacentCue(-1)
                true
            }
            config.nextKeyCode -> {
                jumpToAdjacentCue(1)
                true
            }
            config.collectKeyCode -> {
                handleGamepadCollect(config)
                true
            }
            else -> false
        }
    }

    fun clearHoshiLookupSelection() {
        hoshiLookupSelectionCueIndex = null
        hoshiLookupSelectionRange = null
    }

    fun triggerHoshiPopupLookup(selection: ReaderSelectionData, cue: ReaderSubtitleCue?) {
        if (uiTestMode && !temporaryLookupMode) return
        val resolvedCue = cue ?: return
        val resolvedCueIndex = cues.indexOf(resolvedCue).takeIf { it >= 0 }
        val selectionStart = selection.sentenceOffset
            ?.coerceIn(0, resolvedCue.text.length)
            ?: 0
        val initialSelectionEndExclusive = (selectionStart + selection.text.length.coerceAtLeast(1))
            .coerceIn(selectionStart, resolvedCue.text.length)
        hoshiLookupSelectionCueIndex = resolvedCueIndex
        hoshiLookupSelectionRange = if (initialSelectionEndExclusive > selectionStart) {
            selectionStart until initialSelectionEndExclusive
        } else {
            null
        }
        val popupStartNs = SystemClock.elapsedRealtimeNanos()
        consumeCueRangeSelection()
        if (audiobookSettings.pausePlaybackOnLookup && player.isPlaying) {
            setLookupPlaybackState(play = false)
            resumePlaybackAfterLookupDismiss = true
        } else {
            resumePlaybackAfterLookupDismiss = false
        }
        val rect = selection.rect
        logDebug(BOOK_LOOKUP_SELECTION_LOG_TAG) {
            "hoshi popup start cueIndex=$resolvedCueIndex textLen=${selection.text.length} rect=${rect.x.toInt()},${rect.y.toInt()} ${rect.width.toInt()}x${rect.height.toInt()} normalizedOffset=${selection.normalizedOffset} sentenceOffset=${selection.sentenceOffset}"
        }
        val preparedDictionaryCount = bookHoshiLookupSession.ensurePrepared().size
        logDebug(BOOK_LOOKUP_SELECTION_LOG_TAG) {
            "hoshi popup prepared dictCount=$preparedDictionaryCount query='${selection.text.take(32)}'"
        }
        val options = LookupPopupOptions(
            isVertical = false,
            isFullWidth = audiobookSettings.lookupRootFullWidthEnabled,
            width = 320,
            height = 250,
            swipeToDismiss = true,
            swipeThreshold = 40,
            topInset = 0.0,
            bottomInset = navigationBarBottomInsetDp,
            dictionarySettings = loadDictionarySettings(context),
            darkMode = isDarkTheme,
            eInkMode = false,
                    audioSettings = audiobookSettings,
                    showRangeSelection = hasSubtitleFile && audiobookSettings.lookupRangeSelectionEnabled,
                    showPlayAudio = audiobookSettings.lookupPlaybackAudioEnabled,
                    popupActionBar = true,
                )
        val popup = bookHoshiLookupSession.createPopup(
            selection = selection,
            options = options,
        )
        if (popup == null) {
            hoshiLookupPopups.clear()
            clearHoshiLookupSelection()
            logDebug(BOOK_LOOKUP_SELECTION_LOG_TAG) {
                "hoshi popup empty cueIndex=$resolvedCueIndex query='${selection.text.take(32)}' sentenceOffset=${selection.sentenceOffset} elapsedMs=${(SystemClock.elapsedRealtimeNanos() - popupStartNs) / 1_000_000L}"
            }
            return
        }
        logDebug("HoshiLookupPopup") {
            "book root popup built query='${selection.text.take(32)}' results=${popup.first.state.results.size} cueIndex=$resolvedCueIndex"
        }
        hoshiLookupPopups.clear()
        val popupSelection = popup.first.state.selection
        val popupSelectionStart = popupSelection.sentenceOffset
            ?.coerceIn(0, resolvedCue.text.length)
            ?: selectionStart
        val matchedLength = popup.first.state.results.firstOrNull()
            ?.matched
            ?.length
            ?.coerceAtLeast(1)
            ?: 1
        val selectionEndExclusive = (popupSelectionStart + matchedLength).coerceIn(popupSelectionStart, resolvedCue.text.length)
        val matchedAnchorRect = popupSelection.anchorRectForSourceRange(popupSelectionStart, selectionEndExclusive)
        val anchoredPopup = popup.first.copy(
            state = popup.first.state.copy(
                selection = popupSelection.copy(rect = matchedAnchorRect)
            )
        )
        hoshiLookupPopups.add(anchoredPopup)
        recordStatisticsLookup(context, playbackPositionKey)
        hoshiLookupSelectionCueIndex = resolvedCueIndex
        hoshiLookupSelectionRange = if (selectionEndExclusive > popupSelectionStart) {
            popupSelectionStart until selectionEndExclusive
        } else {
            null
        }
        logDebug(BOOK_LOOKUP_SELECTION_LOG_TAG) {
            "hoshi popup ready cueIndex=$resolvedCueIndex elapsedMs=${(SystemClock.elapsedRealtimeNanos() - popupStartNs) / 1_000_000L}"
        }
    }

    fun triggerPopupLookup(cue: ReaderSubtitleCue, offset: Int, anchor: ReaderLookupAnchor?) {
        if (uiTestMode && !temporaryLookupMode) return
        val cueIndex = cues.indexOf(cue).takeIf { it >= 0 }
        val anchorBounds = anchor.boundingRectOrNull()
        logDebug(BOOK_LOOKUP_SELECTION_LOG_TAG) {
            "hoshi tap redirect cueIndex=$cueIndex offset=$offset anchor=${formatRectForLog(anchorBounds)}"
        }
        if (anchorBounds == null) {
            logDebug(BOOK_LOOKUP_ANCHOR_LOG_TAG) {
                "lookup skipped reason=missing_anchor cueIndex=$cueIndex offset=$offset"
            }
            return
        }
        val selection = createHoshiReaderSelectionFromCueTap(
            cueText = cue.text,
            cueIndex = cueIndex ?: 0,
            cues = cues,
            offset = offset,
            anchorRect = anchorBounds,
            density = rootDensity.density
        )
        triggerHoshiPopupLookup(selection, cue)
    }

    fun triggerPopupLookup(selection: ReaderSelectionData, cue: ReaderSubtitleCue?) {
        triggerHoshiPopupLookup(selection, cue)
    }

    fun closeHoshiLookupPopup() {
        hoshiLookupPopups.clear()
        hoshiLookupSelectionCueIndex = null
        hoshiLookupSelectionRange = null
        hoshiLookupPopupTemporarilyHidden = false
        reopenHoshiLookupPopupAfterCueRangeSelection = false
        clearCueRangeSelection()
    }

    fun exportBookHoshiLookupEntryToAnkiAsync(content: String, onComplete: (Boolean) -> Unit) {
        scope.launch {
            logDebug("AnkiExportDebug") {
                "bookHoshiExport rawContentLen=${content.length} rawPrefix=${content.take(120)}"
            }
            val success = runCatching {
                val payload = runCatching { JSONObject(content) }.getOrNull() ?: run {
                    logDebug("AnkiExportDebug") { "bookHoshiExport payloadParseFailed" }
                    return@runCatching false
                }
                val expression = payload.optString("expression").trim().ifBlank {
                    payload.optString("matched").trim()
                }
                if (expression.isBlank()) {
                    logDebug("AnkiExportDebug") {
                        "bookHoshiExport expressionBlank payloadKeys=${payload.keys().asSequence().joinToString(",")}"
                    }
                    return@runCatching false
                }
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
                val primaryDictionaryName = payload.optString("selectedDictionary").trim()
                val dictionaryMedia = parseLookupDictionaryMedia(payload)
                val cueIndex = hoshiLookupSelectionCueIndex ?: activeCueIndex
                val sourceCue = cueIndex.takeIf { it in cues.indices }?.let { cues[it] }
                val cueText = sourceCue?.text?.trim()?.takeIf { it.isNotBlank() }
                    ?: title.trim().ifBlank { expression }
                val baseCue = sourceCue ?: ReaderSubtitleCue(startMs = 0L, endMs = 0L, text = cueText)
                val popupSelectionText = payload.optString("popupSelectionText").trim().takeIf { it.isNotBlank() }
                    ?: hoshiLookupSelectionRange?.let { range ->
                        val start = range.first.coerceIn(0, baseCue.text.length)
                        val endExclusive = (range.last + 1).coerceIn(start, baseCue.text.length)
                        if (endExclusive > start) baseCue.text.substring(start, endExclusive) else null
                    }?.trim()?.takeIf { it.isNotBlank() }
                val selectedRange = selectedCueIndexRange?.takeIf { range ->
                    range.first in cues.indices && range.last in cues.indices
                }
                val sentenceSelection = when {
                    selectedRange != null -> ReaderSentenceSelection(
                        text = cues.slice(selectedRange).joinToString("\n") { it.text },
                        cueRange = selectedRange
                    )
                    audiobookSettings.lookupExportFullSentence && cueIndex in cues.indices -> {
                        extractFullSentenceLikeHoshiFromCues(
                            cues = cues,
                            cueIndex = cueIndex,
                            anchorText = popupSelectionText ?: expression,
                            selectedRangeInCue = hoshiLookupSelectionRange,
                            rawAnchorOffsetInCue = hoshiLookupSelectionRange?.first
                        )
                    }
                    else -> ReaderSentenceSelection(
                        text = baseCue.text,
                        cueRange = cueIndex.takeIf { it in cues.indices }?.let { it..it } ?: 0..0
                    )
                }
                val exportCue = sentenceSelection.cueRange
                    .takeIf { range -> range.first in cues.indices && range.last in cues.indices }
                    ?.let { range ->
                        ReaderSubtitleCue(
                            startMs = cues[range.first].startMs,
                            endMs = cues[range.last].endMs,
                            text = sentenceSelection.text
                        )
                    }
                    ?: baseCue
                logDebug("AnkiExportDebug") {
                    "bookHoshiExport payload expression=$expression reading=${reading.orEmpty()} dict=$primaryDictionaryName " +
                    "glossaryLen=${glossary.length} frequencyLen=${frequency.length} pitchLen=${pitch.length} " +
                    "popupSelectionLen=${popupSelectionText.orEmpty().length} " +
                    "sentenceLen=${sentenceSelection.text.length} cueRange=${sentenceSelection.cueRange} " +
                    "cue=${exportCue.text.take(48)}"
                }
                consumeCueRangeSelection()
                val exportResult = withContext(Dispatchers.IO) {
                    val preparedLookupAudio = prepareLookupAudioForAnkiExport(
                        context = context,
                        term = expression,
                        reading = reading,
                        settings = audiobookSettings
                    )
                    try {
                        addLookupDefinitionToAnkiShared(
                            context = context,
                            cueText = exportCue.text,
                            cueStartMs = exportCue.startMs,
                            cueEndMs = exportCue.endMs,
                            audioUri = audioUri,
                            lookupAudioUri = preparedLookupAudio?.uri,
                            bookTitle = title,
                            entry = DictionaryEntry(
                                term = expression,
                                reading = reading,
                                definitions = listOf(glossary),
                                pitch = pitch.ifBlank { null },
                                frequency = frequency.ifBlank { null },
                                dictionary = primaryDictionaryName.ifBlank { expression }
                            ),
                            definition = glossary,
                            glossaryFirstHtml = payload.optString("glossaryFirst").trim().takeIf { it.isNotBlank() },
                            dictionaryCss = dictionaryCssByName[primaryDictionaryName],
                            dictionaryMedia = dictionaryMedia,
                            popupSelectionText = popupSelectionText,
                            sentenceOverride = sentenceSelection.text,
                            lookupTermOverride = expression
                        )
                    } finally {
                        preparedLookupAudio?.cleanup?.invoke()
                    }
                }
                val message = ankiExportResultMessage(context, exportResult)
                logDebug("AnkiExportDebug") {
                    "bookHoshiExport result=${exportResult.javaClass.simpleName} message=${message.take(220)}"
                }
                if (message.isNotBlank() && exportResult !is AnkiExportResult.DuplicateSkipped) {
                    Toast.makeText(
                        context,
                        message.take(220),
                        if (exportResult == AnkiExportResult.Added) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                    ).show()
                }
                exportResult == AnkiExportResult.Added ||
                    exportResult is AnkiExportResult.DuplicateSkipped
            }.getOrElse { error ->
                Log.w("AnkiExportDebug", "bookHoshiExport async failed", error)
                false
            }
            onComplete(success)
        }
    }

    fun checkBookAnkiDuplicateAsync(expression: String, onComplete: (AnkiDuplicateCheckResult) -> Unit) {
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    checkAnkiDuplicateByFirstFieldAsync(context, expression)
                }
            }.getOrElse { error ->
                Log.w("AnkiExportDebug", "book duplicate async failed expression=${expression.take(32)}", error)
                AnkiDuplicateCheckResult()
            }
            onComplete(result)
        }
    }

    fun handleControlOverlaySwipe(step: Int) {
        if (step < 0) {
            jumpToAdjacentCue(-1)
            controlModeStatus = context.getString(R.string.status_swipe_prev)
        } else {
            jumpToAdjacentCue(1)
            controlModeStatus = context.getString(R.string.status_swipe_next)
        }
    }

    fun exitControlModeByTwoFingerLongPress() {
        pendingSingleTapJob?.cancel()
        pendingSingleTapJob = null
        pendingSingleTapBaseCueIndex = null
        controlTargetCueIndex = null
        controlModeEnabled = false
        controlModeStatus = context.getString(R.string.status_control_mode_exited_full)
    }

    val latestHandleGamepadKeyEvent by rememberUpdatedState<(KeyEvent) -> Boolean>({ event ->
        handleGamepadKeyEvent(event)
    })
    val latestWearableControlCollect = rememberUpdatedState<() -> Long?> {
        if (playbackCueIndex !in cues.indices) null else handleControlOverlayTap()
    }
    val latestWearableAdjacentSeek = rememberUpdatedState<(Int) -> Unit> { step ->
        logDebug(BOOK_READER_SEEK_LOG_TAG) {
            "wearableAdjacent step=$step mode=$effectiveAdjacentJumpMode configuredStepMs=" +
            "${loadAudiobookSettingsConfig(context).seekStepMillis}"
        }
        jumpToAdjacentCue(step)
    }
    val latestWearableSleepTimer = rememberUpdatedState<(Int) -> Boolean> { minutes ->
        setSleepTimer(minutes)
        true
    }
    val controlModeConfig = loadGamepadControlConfig(context)
    val controlModePowerSaveEnabled = controlModeEnabled && controlModeConfig.powerSaveBlackScreenInControlMode
    val controlModeHintText = remember(controlModeEnabled, controlModeConfig) {
        buildString {
            append(context.getString(R.string.status_control_hint_intro))
            append(
                if (controlModeConfig.singleTapCollectOnlyInControlMode) {
                    context.getString(R.string.status_control_hint_direct_bookmark)
                } else {
                    context.getString(R.string.status_control_hint_replay_bookmark)
                }
            )
            append(context.getString(R.string.status_control_hint_footer))
        }
    }

    DisposableEffect(Unit) {
        registerGamepadKeyHandler { event -> latestHandleGamepadKeyEvent(event) }
        onDispose { registerGamepadKeyHandler(null) }
    }

    DisposableEffect(Unit) {
        BookReaderFloatingBridge.setControlCollectListener {
            latestWearableControlCollect.value()
                ?.let(BookReaderFloatingBridge::ControlCollectResult)
        }
        onDispose { BookReaderFloatingBridge.setControlCollectListener(null) }
    }

    DisposableEffect(Unit) {
        BookReaderFloatingBridge.setAdjacentSeekListener { step ->
            latestWearableAdjacentSeek.value(step)
        }
        onDispose { BookReaderFloatingBridge.setAdjacentSeekListener(null) }
    }

    DisposableEffect(Unit) {
        BookReaderFloatingBridge.setSleepTimerListener { minutes -> latestWearableSleepTimer.value(minutes) }
        onDispose { BookReaderFloatingBridge.setSleepTimerListener(null) }
    }

        BackHandler {
            when {
                hoshiLookupPopupVisible -> {
                    hoshiLookupPopups.clear()
                    hoshiLookupSelectionCueIndex = null
                    hoshiLookupSelectionRange = null
                }
                sleepTimerOptionsVisible -> sleepTimerOptionsVisible = false
                typographyPanelVisible -> typographyPanelVisible = false
                topActionsExpanded -> topActionsExpanded = false
                speedMenuExpanded -> speedMenuExpanded = false
                chapterOptionsVisible -> chapterOptionsVisible = false
                else -> backToMain("system_back")
            }
        }

    var layoutRootSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                layoutRootSize = coordinates.size
            }
    ) {
        val density = LocalDensity.current
        var leftControlsWidthDp by remember { mutableStateOf(0.dp) }
        var rightControlsWidthDp by remember { mutableStateOf(0.dp) }
        val sideRailContentGap = 8.dp
        var topBarBottomDp by remember { mutableStateOf(0.dp) }
        var contentContainerTopDp by remember { mutableStateOf(0.dp) }
        var contentContainerHeightDp by remember { mutableStateOf(0.dp) }
        val activeSideRailSlot = if (readerUiLayoutConfig.right.isNotEmpty() && readerUiLayoutConfig.left.isEmpty()) {
            BookReaderUiSlot.RIGHT
        } else {
            BookReaderUiSlot.LEFT
        }
        val sideRailModules = if (bottomControlsVisible) {
            readerUiLayoutConfig.modulesIn(activeSideRailSlot)
        } else {
            emptyList()
        }
        val leftModules = if (activeSideRailSlot == BookReaderUiSlot.LEFT) sideRailModules else emptyList()
        val rightModules = if (activeSideRailSlot == BookReaderUiSlot.RIGHT) sideRailModules else emptyList()
        val topModules = if (bottomControlsVisible) readerUiLayoutConfig.top else emptyList()
        val bottomModules = if (bottomControlsVisible) readerUiLayoutConfig.bottom else emptyList()
        val contentStartPadding: Dp = if (leftModules.isNotEmpty()) {
            leftControlsWidthDp + sideRailContentGap
        } else {
            0.dp
        }
        val contentEndPadding: Dp = if (rightModules.isNotEmpty()) {
            rightControlsWidthDp + sideRailContentGap
        } else {
            0.dp
        }
        val seekStepMillis = loadAudiobookSettingsConfig(context).seekStepMillis
        var dragOverlayModule by remember(readerUiWritingMode) { mutableStateOf<BookReaderUiModule?>(null) }
        var dragOverlayOriginSlot by remember(readerUiWritingMode) { mutableStateOf<BookReaderUiSlot?>(null) }
        var dragOverlayTargetSlot by remember(readerUiWritingMode) { mutableStateOf<BookReaderUiSlot?>(null) }
        var dragOverlayCandidateSlot by remember(readerUiWritingMode) { mutableStateOf<BookReaderUiSlot?>(null) }
        var dragOverlayCandidateValid by remember(readerUiWritingMode) { mutableStateOf(true) }
        var dragOverlayTargetIndex by remember(readerUiWritingMode) { mutableStateOf<Int?>(null) }
        var dragOverlayInsertLine by remember(readerUiWritingMode) { mutableStateOf<Rect?>(null) }
        var dragOverlayPosition by remember(readerUiWritingMode) { mutableStateOf(Offset.Zero) }
        var dragOverlaySize by remember(readerUiWritingMode) { mutableStateOf(IntSize.Zero) }
        val layoutModuleBounds = remember { mutableStateMapOf<BookReaderUiModule, Rect>() }
        val layoutSlotBounds = remember { mutableStateMapOf<BookReaderUiSlot, Rect>() }
        fun toggleAdjacentJumpMode() {
            adjacentJumpMode = if (adjacentJumpMode == AdjacentJumpMode.CUE) {
                AdjacentJumpMode.DURATION
            } else {
                AdjacentJumpMode.CUE
            }
        }
        fun canDropModuleInSlot(module: BookReaderUiModule, targetSlot: BookReaderUiSlot): Boolean {
            if (!module.canUseSlot(targetSlot)) return false
            if (targetSlot.isVertical && targetSlot != activeSideRailSlot) return false
            return true
        }
        fun moveSideRailTo(targetSlot: BookReaderUiSlot): Boolean {
            if (!targetSlot.isVertical) return false
            val sideModules = (readerUiLayoutConfig.left + readerUiLayoutConfig.right)
                .distinct()
                .filter { module -> module.canUseSlot(targetSlot) }
            if (sideModules.isEmpty()) return false
            val next = readerUiLayoutConfig.copy(
                left = if (targetSlot == BookReaderUiSlot.LEFT) sideModules else emptyList(),
                right = if (targetSlot == BookReaderUiSlot.RIGHT) sideModules else emptyList()
            ).normalized()
            if (next == readerUiLayoutConfig) return false
            readerUiLayoutConfig = next
            saveBookReaderUiLayoutConfig(context, readerUiWritingMode, next)
            return true
        }
        fun moveLayoutModule(module: BookReaderUiModule, targetSlot: BookReaderUiSlot, targetIndex: Int): Boolean {
            if (!canDropModuleInSlot(module, targetSlot)) return false
            val next = readerUiLayoutConfig.move(
                module = module,
                targetSlot = targetSlot,
                targetIndex = targetIndex
            )
            if (next == readerUiLayoutConfig) return false
            readerUiLayoutConfig = next
            saveBookReaderUiLayoutConfig(context, readerUiWritingMode, next)
            return true
        }
        fun rectFromRootPosition(position: Offset, size: IntSize): Rect {
            return Rect(
                left = position.x,
                top = position.y,
                right = position.x + size.width,
                bottom = position.y + size.height
            )
        }
        fun fallbackLayoutSlotBounds(slot: BookReaderUiSlot): Rect {
            val sideWidth = with(density) { 76.dp.toPx() }
            val edgePadding = with(density) { 16.dp.toPx() }
            val topPadding = with(density) { 12.dp.toPx() }
            val topHeight = with(density) { 88.dp.toPx() }
            val bottomHeight = with(density) { 132.dp.toPx() }
            val contentTop = contentContainerTopDp
                .takeIf { it > 0.dp }
                ?.let { with(density) { it.toPx() } }
                ?: (with(density) { topBarBottomDp.toPx() } + topPadding)
            val contentHeight = contentContainerHeightDp
                .takeIf { it > 0.dp }
                ?.let { with(density) { it.toPx() } }
                ?: (layoutRootSize.height - contentTop - bottomHeight).coerceAtLeast(1f)
            return when (slot) {
                BookReaderUiSlot.TOP -> Rect(
                    left = edgePadding,
                    top = with(density) { topBarBottomDp.toPx() } + topPadding,
                    right = layoutRootSize.width - edgePadding,
                    bottom = with(density) { topBarBottomDp.toPx() } + topPadding + topHeight
                )
                BookReaderUiSlot.BOTTOM -> Rect(
                    left = 0f,
                    top = layoutRootSize.height - bottomHeight,
                    right = layoutRootSize.width.toFloat(),
                    bottom = layoutRootSize.height.toFloat()
                )
                BookReaderUiSlot.LEFT -> Rect(
                    left = edgePadding,
                    top = contentTop,
                    right = edgePadding + sideWidth,
                    bottom = contentTop + contentHeight
                )
                BookReaderUiSlot.RIGHT -> Rect(
                    left = layoutRootSize.width - edgePadding - sideWidth,
                    top = contentTop,
                    right = layoutRootSize.width - edgePadding,
                    bottom = contentTop + contentHeight
                )
                BookReaderUiSlot.HIDDEN -> Rect.Zero
            }
        }
        fun layoutSlotBoundsFor(slot: BookReaderUiSlot): Rect {
            return layoutSlotBounds[slot] ?: fallbackLayoutSlotBounds(slot)
        }
        fun distanceToRect(point: Offset, rect: Rect): Float {
            val dx = when {
                point.x < rect.left -> rect.left - point.x
                point.x > rect.right -> point.x - rect.right
                else -> 0f
            }
            val dy = when {
                point.y < rect.top -> rect.top - point.y
                point.y > rect.bottom -> point.y - rect.bottom
                else -> 0f
            }
            return maxOf(dx, dy)
        }
        fun resolveLayoutDragCandidate(
            module: BookReaderUiModule,
            topLeftInRoot: Offset,
            size: IntSize
        ): Pair<BookReaderUiSlot, Boolean>? {
            if (layoutRootSize == IntSize.Zero) return null
            val snapDistancePx = with(density) { 72.dp.toPx() }
            val dragCenter = topLeftInRoot + Offset(size.width / 2f, size.height / 2f)
            val candidates = listOf(
                activeSideRailSlot,
                BookReaderUiSlot.TOP,
                BookReaderUiSlot.BOTTOM
            ).distinct().map { slot ->
                slot to distanceToRect(dragCenter, layoutSlotBoundsFor(slot))
            }.filter { candidate -> candidate.second <= snapDistancePx }
            val target = candidates.minByOrNull { candidate -> candidate.second }?.first
            return target?.let { it to canDropModuleInSlot(module, it) }
        }
        fun resolveLayoutTargetIndex(module: BookReaderUiModule, targetSlot: BookReaderUiSlot, dragCenterInRoot: Offset): Int {
            val modules = readerUiLayoutConfig.modulesIn(targetSlot).filter { it != module }
            if (modules.isEmpty()) return 0
            val firstAfter = modules.indexOfFirst { existing ->
                val bounds = layoutModuleBounds[existing]
                bounds != null && dragCenterInRoot.y < ((bounds.top + bounds.bottom) / 2f)
            }
            return if (firstAfter >= 0) firstAfter else modules.size
        }
        fun resolveLayoutInsertLine(module: BookReaderUiModule, targetSlot: BookReaderUiSlot, targetIndex: Int): Rect? {
            val slotBounds = layoutSlotBoundsFor(targetSlot)
            if (slotBounds == Rect.Zero) return null
            val modules = readerUiLayoutConfig.modulesIn(targetSlot).filter { it != module }
            val lineThickness = with(density) { 3.dp.toPx() }
            val inset = with(density) { 10.dp.toPx() }
            val y = when {
                modules.isEmpty() -> (slotBounds.top + slotBounds.bottom) / 2f
                targetIndex <= 0 -> layoutModuleBounds[modules.first()]?.top ?: slotBounds.top + inset
                targetIndex >= modules.size -> layoutModuleBounds[modules.last()]?.bottom ?: slotBounds.bottom - inset
                else -> {
                    val before = layoutModuleBounds[modules[targetIndex - 1]]
                    val after = layoutModuleBounds[modules[targetIndex]]
                    if (before != null && after != null) {
                        (before.bottom + after.top) / 2f
                    } else {
                        null
                    }
                }
            } ?: return null
            return Rect(
                left = slotBounds.left + inset,
                top = y - lineThickness / 2f,
                right = slotBounds.right - inset,
                bottom = y + lineThickness / 2f
            )
        }
        @Composable
        fun EditableReaderUiModule(
            module: BookReaderUiModule,
            slot: BookReaderUiSlot,
            modifier: Modifier = Modifier,
            content: @Composable (Modifier) -> Unit
        ) {
            if (!uiLayoutEditMode) {
                content(modifier)
                return
            }
            var dragX by remember(module, slot) { mutableStateOf(0f) }
            var dragY by remember(module, slot) { mutableStateOf(0f) }
            var dragPreviewTarget by remember(module, slot) { mutableStateOf<BookReaderUiSlot?>(null) }
            var moduleRootPosition by remember(module, slot) { mutableStateOf(Offset.Zero) }
            var moduleSize by remember(module, slot) { mutableStateOf(IntSize.Zero) }
            var dragStartRootPosition by remember(module, slot) { mutableStateOf(Offset.Zero) }
            Box(
                modifier = modifier
                    .onGloballyPositioned { coordinates ->
                        if (draggingLayoutModule != module) {
                            val position = coordinates.positionInRoot()
                            val size = coordinates.size
                            moduleRootPosition = position
                            moduleSize = size
                            layoutModuleBounds[module] = rectFromRootPosition(position, size)
                        }
                    }
                    .zIndex(if (draggingLayoutModule == module) 1000f else 10f)
                    .pointerInput(module, slot, readerUiLayoutConfig) {
                        detectTapGestures(onTap = { layoutDotsVisible = !layoutDotsVisible })
                    }
                    .pointerInput(module, slot, readerUiLayoutConfig) {
                        detectDragGestures(
                            onDragStart = {
                                draggingLayoutModule = module
                                dragPreviewTarget = null
                                dragStartRootPosition = moduleRootPosition
                                dragX = 0f
                                dragY = 0f
                                dragOverlayModule = module
                                dragOverlayOriginSlot = slot
                                dragOverlayTargetSlot = null
                                dragOverlayCandidateSlot = slot
                                dragOverlayCandidateValid = true
                                dragOverlayTargetIndex = readerUiLayoutConfig.modulesIn(slot).indexOf(module).coerceAtLeast(0)
                                dragOverlayInsertLine = null
                                dragOverlayPosition = moduleRootPosition
                                dragOverlaySize = moduleSize
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragX += dragAmount.x
                                dragY += dragAmount.y
                                val rawPosition = dragStartRootPosition + Offset(dragX, dragY)
                                val dragCenter = rawPosition + Offset(moduleSize.width / 2f, moduleSize.height / 2f)
                                val candidate = resolveLayoutDragCandidate(
                                    module = module,
                                    topLeftInRoot = rawPosition,
                                    size = moduleSize
                                )
                                val candidateSlot = candidate?.first
                                val candidateValid = candidate?.second == true
                                dragOverlayCandidateSlot = candidateSlot
                                dragOverlayCandidateValid = candidateValid
                                val previewTarget = candidateSlot?.takeIf { candidateValid }
                                dragPreviewTarget = previewTarget
                                val targetIndex = previewTarget?.let { targetSlot ->
                                    resolveLayoutTargetIndex(module, targetSlot, dragCenter)
                                }
                                dragOverlayTargetIndex = targetIndex
                                dragOverlayInsertLine = if (previewTarget != null && targetIndex != null) {
                                    resolveLayoutInsertLine(module, previewTarget, targetIndex)
                                } else {
                                    null
                                }
                                dragOverlayTargetSlot = previewTarget
                                dragOverlayPosition = previewTarget
                                    ?.let { targetSlot -> layoutSlotBoundsFor(targetSlot) }
                                    ?.let { bounds -> Offset(bounds.left, bounds.top) }
                                    ?: rawPosition
                            },
                            onDragEnd = {
                                val target = dragPreviewTarget
                                val targetIndex = dragOverlayTargetIndex
                                dragX = 0f
                                dragY = 0f
                                dragPreviewTarget = null
                                draggingLayoutModule = null
                                dragOverlayModule = null
                                dragOverlayOriginSlot = null
                                dragOverlayTargetSlot = null
                                dragOverlayCandidateSlot = null
                                dragOverlayCandidateValid = true
                                dragOverlayTargetIndex = null
                                dragOverlayInsertLine = null
                                dragOverlaySize = IntSize.Zero
                                if (target != null && targetIndex != null) {
                                    moveLayoutModule(module, target, targetIndex)
                                }
                            },
                            onDragCancel = {
                                dragX = 0f
                                dragY = 0f
                                dragPreviewTarget = null
                                draggingLayoutModule = null
                                dragOverlayModule = null
                                dragOverlayOriginSlot = null
                                dragOverlayTargetSlot = null
                                dragOverlayCandidateSlot = null
                                dragOverlayCandidateValid = true
                                dragOverlayTargetIndex = null
                                dragOverlayInsertLine = null
                                dragOverlaySize = IntSize.Zero
                            }
                        )
                    }
                    .alpha(if (draggingLayoutModule == module) 0f else 1f)
            ) {
                content(Modifier)
                if (module == BookReaderUiModule.PLAYBACK_TIMELINE) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent()
                                    }
                                }
                            }
                    )
                }
                if (layoutDotsVisible || draggingLayoutModule == module) {
                    Box(
                        modifier = Modifier
                            .align(slot.centerFacingAlignment())
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E88E5))
                    )
                }
            }
        }
        @Composable
        fun RenderReaderUiModuleContent(module: BookReaderUiModule, slot: BookReaderUiSlot, modifier: Modifier = Modifier) {
            when (module) {
                    BookReaderUiModule.CHAPTER_SELECTOR -> BookReaderChapterSelectorModule(
                        modifier = modifier,
                        vertical = slot.isVertical,
                        slot = slot,
                        chapters = audioChapters,
                        activeChapterIndex = activeChapterIndex,
                        expanded = chapterOptionsVisible,
                        visible = chapterRowVisible,
                        onToggleExpanded = { chapterOptionsVisible = !chapterOptionsVisible },
                        onDismissExpanded = { chapterOptionsVisible = false },
                        onJumpChapter = { chapter -> jumpToChapter(chapter) }
                    )
                    BookReaderUiModule.PLAYBACK_TIMELINE -> BookReaderPlaybackTimelineModule(
                        modifier = modifier,
                        vertical = slot.isVertical,
                        displayedRightDurationTimeMs = displayedRightDurationTimeMs,
                        displayedLeftTimeMs = displayedLeftTimeMs,
                        sliderMax = sliderMax,
                        sliderValue = sliderValue,
                        displayedDurationTimeMs = displayedDurationTimeMs,
                        useChapterTimeline = useChapterTimeline,
                        activeChapterStartMs = activeChapterStartMs,
                        timelineRangeMs = timelineRangeMs,
                        durationMs = durationMs,
                        onToggleDurationMode = { showOverallDuration = !showOverallDuration },
                        onPreviewPositionChanged = { dragPreviewPositionMs = it },
                        onSeekManual = { target -> seekToManual(target) },
                        onRequestTimeEdit = {
                            timeEditInput = formatPlaybackTime(displayedLeftTimeMs)
                            timeEditError = null
                            timeEditDialogVisible = true
                        }
                    )
                    BookReaderUiModule.PLAYBACK_CONTROLS -> BookReaderPlaybackControlsModule(
                        modifier = modifier,
                        vertical = slot.isVertical,
                        isPlaying = isPlaying,
                        onPrevious = { jumpToAdjacentCue(-1) },
                        onPlayPause = { toggleReaderPlaybackState() },
                        onNext = { jumpToAdjacentCue(1) }
                    )
                    BookReaderUiModule.CHAPTER_PROGRESS_AND_JUMP_MODE -> {
                        if (!slot.isVertical) {
                            BookReaderChapterProgressJumpModeModule(
                                modifier = modifier,
                                useChapterTimeline = useChapterTimeline,
                                showOverallProgress = showOverallProgress,
                                totalProgressPercent = totalProgressPercent,
                                progressPercent = progressPercent,
                                effectiveAdjacentJumpMode = effectiveAdjacentJumpMode,
                                cuesAvailable = cues.isNotEmpty(),
                                seekStepMillis = seekStepMillis,
                                onToggleProgressMode = { showOverallProgress = !showOverallProgress },
                                onToggleJumpMode = { toggleAdjacentJumpMode() }
                            )
                        }
                    }
                }
        }
        @Composable
        fun RenderReaderUiModule(module: BookReaderUiModule, slot: BookReaderUiSlot, modifier: Modifier = Modifier) {
            EditableReaderUiModule(module, slot, modifier) { childModifier ->
                RenderReaderUiModuleContent(module, slot, childModifier)
            }
        }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (bottomModules.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            layoutSlotBounds[BookReaderUiSlot.BOTTOM] = rectFromRootPosition(
                                coordinates.positionInRoot(),
                                coordinates.size
                            )
                        },
                        tonalElevation = 4.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .zIndex(30f)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            bottomModules.forEach { module ->
                                RenderReaderUiModule(module, BookReaderUiSlot.BOTTOM)
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            topBarBottomDp = with(density) {
                                (coordinates.positionInRoot().y + coordinates.size.height).toDp()
                            }
                        },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { backToMain("top_bar_button") }) {
                        Text(stringResource(R.string.bookreader_back))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (!temporaryLookupMode) {
                    TextButton(
                        onClick = { if (favoriteCue != null) toggleFavoriteCue() },
                        enabled = favoriteCue != null && !uiTestMode
                    ) {
                        Text(if (favoriteCueCollected) "★" else "☆")
                    }
                    TextButton(onClick = { sleepTimerOptionsVisible = !sleepTimerOptionsVisible }) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Timer,
                                contentDescription = stringResource(R.string.bookreader_sleep_timer),
                                modifier = Modifier.size(20.dp)
                            )
                            sleepRemainingLabel?.let { remaining ->
                                Text(remaining)
                            }
                        }
                    }
                    Box {
                        TextButton(onClick = { speedMenuExpanded = true }) {
                            Text("${playbackSpeed}x")
                        }
                        DropdownMenu(
                            expanded = speedMenuExpanded,
                            onDismissRequest = { speedMenuExpanded = false }
                        ) {
                            listOf(0.8f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                val isCurrent = abs(speed - playbackSpeed) < 0.001f
                                DropdownMenuItem(
                                    text = { Text((if (isCurrent) "> " else "") + "${speed}x") },
                                    onClick = {
                                        playbackSpeed = speed
                                        speedMenuExpanded = false
                                        controlModeStatus = context.getString(R.string.status_playback_speed, speed.toString())
                                    }
                                )
                            }
                        }
                    }
                    Box {
                        TextButton(onClick = { topActionsExpanded = true }) {
                            Text("...")
                        }
                        DropdownMenu(
                            expanded = topActionsExpanded,
                            onDismissRequest = { topActionsExpanded = false }
                        ) {
                            if (ebookUri != null && !uiTestMode) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bookreader_open_ebook_reader)) },
                                    onClick = {
                                        topActionsExpanded = false
                                        val immediatePositionMs = positionMs.coerceAtLeast(0L)
                                        val immediateDurationMs = durationMs.coerceAtLeast(0L)
                                        if (audioUri != null && immediateDurationMs > 0L) {
                                            scope.launch(Dispatchers.IO) {
                                                saveBookReaderPlaybackPosition(
                                                    context = context,
                                                    bookKey = buildReaderAudioPlaybackKey(title, audioUri, srtUri),
                                                    positionMs = normalizeBookReaderPlaybackPosition(
                                                        immediatePositionMs,
                                                        immediateDurationMs
                                                    ),
                                                    durationMs = immediateDurationMs
                                                )
                                            }
                                        }
                                        context.startActivity(
                                            Intent(context, LegadoReaderActivity::class.java).apply {
                                                putExtra(LegadoReaderActivity.EXTRA_EBOOK_TITLE, title)
                                                putExtra(LegadoReaderActivity.EXTRA_EBOOK_URI, ebookUri.toString())
                                                ebookName?.let {
                                                    putExtra(LegadoReaderActivity.EXTRA_EBOOK_NAME, it)
                                                }
                                                ebookFormat?.let {
                                                    putExtra(LegadoReaderActivity.EXTRA_EBOOK_FORMAT, it)
                                                }
                                                audioUri?.let {
                                                    putExtra(LegadoReaderActivity.EXTRA_AUDIO_URI, it.toString())
                                                    putExtra(LegadoReaderActivity.EXTRA_AUDIO_POSITION_MS, immediatePositionMs)
                                                    putExtra(LegadoReaderActivity.EXTRA_AUDIO_DURATION_MS, immediateDurationMs)
                                                    putExtra(LegadoReaderActivity.EXTRA_RETURN_TO_PLAYER_ON_BACK, true)
                                                }
                                                srtUri?.let {
                                                    putExtra(LegadoReaderActivity.EXTRA_SRT_URI, it.toString())
                                                }
                                            }
                                        )
                                        activity?.finish()
                                    }
                                )
                            }
                            if (!uiLayoutEditMode) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when (audiobookSettings.readerPlaybackMode) {
                                                ReaderPlaybackMode.NORMAL -> stringResource(R.string.bookreader_playback_mode_normal_label)
                                                ReaderPlaybackMode.CONDENSED -> stringResource(R.string.bookreader_playback_mode_condensed_label)
                                            }
                                        )
                                    },
                                    onClick = {
                                        val nextMode = when (audiobookSettings.readerPlaybackMode) {
                                            ReaderPlaybackMode.NORMAL -> ReaderPlaybackMode.CONDENSED
                                            ReaderPlaybackMode.CONDENSED -> ReaderPlaybackMode.NORMAL
                                        }
                                        saveAudiobookReaderPlaybackMode(context, nextMode)
                                        audiobookSettings = loadAudiobookSettingsConfig(context)
                                        topActionsExpanded = false
                                    },
                                    enabled = hasSubtitleFile
                                )
                            }
                            if (uiLayoutEditMode) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.audiobook_book_subtitle_typography)) },
                                    onClick = {
                                        typographyPanelVisible = true
                                        topActionsExpanded = false
                                    },
                                    enabled = true
                                )
                            }
                            if (uiLayoutEditMode) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bookreader_ui_layout_restore_default)) },
                                    onClick = {
                                        val defaultLayout = defaultBookReaderUiLayoutConfig(useSideRail = legacyUseSideRailLayout)
                                        readerUiLayoutConfig = defaultLayout
                                        saveBookReaderUiLayoutConfig(context, readerUiWritingMode, defaultLayout)
                                        layoutDotsVisible = false
                                        topActionsExpanded = false
                                    }
                                )
                                val targetSideRailSlot = if (activeSideRailSlot == BookReaderUiSlot.LEFT) {
                                    BookReaderUiSlot.RIGHT
                                } else {
                                    BookReaderUiSlot.LEFT
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (targetSideRailSlot == BookReaderUiSlot.RIGHT) {
                                                    R.string.bookreader_ui_layout_side_rail_to_right
                                                } else {
                                                    R.string.bookreader_ui_layout_side_rail_to_left
                                                }
                                            )
                                        )
                                    },
                                    onClick = {
                                        moveSideRailTo(targetSideRailSlot)
                                        layoutDotsVisible = false
                                        topActionsExpanded = false
                                    },
                                    enabled = readerUiLayoutConfig.left.isNotEmpty() || readerUiLayoutConfig.right.isNotEmpty()
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (bottomControlsVisible) {
                                            stringResource(R.string.bookreader_hide_controls)
                                        } else {
                                            stringResource(R.string.bookreader_show_controls)
                                        }
                                    )
                                },
                                onClick = {
                                    bottomControlsVisible = !bottomControlsVisible
                                    topActionsExpanded = false
                                    controlModeStatus = if (bottomControlsVisible) {
                                        context.getString(R.string.bookreader_controls_shown)
                                    } else {
                                        context.getString(R.string.bookreader_controls_hidden)
                                    }
                                }
                            )
                            if (hasSubtitleFile) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bookreader_control_mode)) },
                                    onClick = {
                                        controlModeEnabled = true
                                        topActionsExpanded = false
                                        controlModeStatus = context.getString(R.string.bookreader_control_mode_entered)
                                    }
                                )
                            }
                            if (uiTestMode) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (uiTestChapterVisible) {
                                                stringResource(R.string.bookreader_ui_test_chapter_off)
                                            } else {
                                                stringResource(R.string.bookreader_ui_test_chapter_on)
                                            }
                                        )
                                    },
                                    onClick = {
                                        uiTestChapterVisible = !uiTestChapterVisible
                                        saveUiChapterVisible(context, uiTestChapterVisible)
                                        chapterOptionsVisible = false
                                        topActionsExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bookreader_ui_test_layout_mode, uiTestLayoutMode)) },
                                    onClick = {
                                        val next = if (uiTestLayoutMode == 1) 2 else 1
                                        if (readerUiWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL) {
                                            uiTestLayoutModeVertical = next
                                            saveUiTestLayoutModeVertical(context, next)
                                            logDebug(BOOK_UI_MODE_LOG_TAG) { "vertical layout mode -> $next" }
                                        } else {
                                            uiTestLayoutModeHorizontal = next
                                            saveUiTestLayoutModeHorizontal(context, next)
                                            logDebug(BOOK_UI_MODE_LOG_TAG) { "horizontal layout mode -> $next" }
                                        }
                                        topActionsExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (uiTestSwapPrevNext) {
                                                stringResource(R.string.bookreader_ui_test_swap_swapped)
                                            } else {
                                                stringResource(R.string.bookreader_ui_test_swap_normal)
                                            }
                                        )
                                    },
                                    onClick = {
                                        val next = !uiTestSwapPrevNext
                                        if (readerUiWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL) {
                                            uiTestSwapPrevNextVertical = next
                                            saveUiSwapPrevNextVertical(context, next)
                                        } else {
                                            uiTestSwapPrevNextHorizontal = next
                                            saveUiSwapPrevNextHorizontal(context, next)
                                        }
                                        topActionsExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.audiobook_overlay_subtitle_writing_mode_horizontal)) },
                                    onClick = {
                                        readerUiWritingMode = FloatingSubtitleWritingMode.HORIZONTAL
                                        logDebug(BOOK_UI_MODE_LOG_TAG) { "writing mode -> HORIZONTAL" }
                                        topActionsExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.audiobook_overlay_subtitle_writing_mode_vertical_rtl)) },
                                    onClick = {
                                        readerUiWritingMode = FloatingSubtitleWritingMode.VERTICAL_RTL
                                        logDebug(BOOK_UI_MODE_LOG_TAG) { "writing mode -> VERTICAL_RTL" }
                                        topActionsExpanded = false
                                    }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (lyricsMode) {
                                                stringResource(R.string.bookreader_subtitle_list)
                                            } else {
                                                stringResource(R.string.bookreader_subtitle_single)
                                            }
                                        )
                                    },
                                    onClick = {
                                        lyricsMode = !lyricsMode
                                        topActionsExpanded = false
                                        controlModeStatus = if (lyricsMode) {
                                            context.getString(R.string.bookreader_subtitle_list_enabled)
                                        } else {
                                            context.getString(R.string.bookreader_subtitle_single_enabled)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (coverModeEnabled) {
                                                stringResource(R.string.bookreader_switch_to_subtitle)
                                            } else {
                                                stringResource(R.string.bookreader_switch_to_cover)
                                            }
                                        )
                                    },
                                    onClick = {
                                        coverModeEnabled = !coverModeEnabled
                                        topActionsExpanded = false
                                        controlModeStatus = if (coverModeEnabled) {
                                            context.getString(R.string.bookreader_switched_to_cover)
                                        } else {
                                            context.getString(R.string.bookreader_switched_to_subtitle)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bookreader_replace_srt)) },
                                    onClick = {
                                        topActionsExpanded = false
                                        if (uiTestMode) {
                                            isPlaying = false
                                        } else if (player.isPlaying) {
                                            player.pause()
                                        }
                                        replaceSrtLauncher.launch(arrayOf("application/x-subrip"))
                                    }
                                )
                            }
                        }
                    }
                }
                    }
                if (!temporaryLookupMode && topModules.isNotEmpty()) {
                    Surface(
                        tonalElevation = 1.dp,
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            val position = coordinates.positionInRoot()
                            layoutSlotBounds[BookReaderUiSlot.TOP] = rectFromRootPosition(position, coordinates.size)
                        }.zIndex(30f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            topModules.forEach { module ->
                                RenderReaderUiModule(module, BookReaderUiSlot.TOP)
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = contentStartPadding)
                            .padding(end = contentEndPadding)
                            .padding(
                                top = if (coverModeEnabled) 18.dp else 0.dp,
                                bottom = if (coverModeEnabled) 20.dp else 0.dp
                            )
                            .onGloballyPositioned { coordinates ->
                                if ((leftModules.isNotEmpty() || rightModules.isNotEmpty()) && bottomControlsVisible) {
                                    contentContainerTopDp = with(density) { coordinates.positionInRoot().y.toDp() }
                                    contentContainerHeightDp = with(density) { coordinates.size.height.toDp() }
                                }
                            },
                        tonalElevation = 1.dp,
                        shape = MaterialTheme.shapes.large
                    ) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(if (coverModeEnabled) 0.dp else 16.dp)
                        ) {
                        val density = LocalDensity.current
                        val bookVerticalWriting = readerUiWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL
                        val verticalRowsPerColumn = remember(
                            bookVerticalWriting,
                            density,
                            maxHeight,
                            activeSubtitleStyle.fontSize,
                            activeSubtitleStyle.lineHeight
                        ) {
                            if (!bookVerticalWriting) {
                                BOOK_VERTICAL_ROWS_PER_COLUMN
                            } else {
                                val availableHeightPx = with(density) { maxHeight.toPx() }
                                val glyphStepPx = with(density) {
                                    activeSubtitleStyle.fontSize.toPx().coerceAtLeast(1f)
                                }
                                (availableHeightPx / glyphStepPx)
                                    .toInt()
                                    .coerceAtLeast(2)
                            }
                        }
                        when {
                                srtLoading -> Text(stringResource(R.string.bookreader_parsing_srt))
                                srtError != null -> Text(
                                    stringResource(R.string.bookreader_srt_error, srtError.orEmpty()),
                                    color = MaterialTheme.colorScheme.error
                                )
                            coverModeEnabled -> {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    if (coverUri != null) {
                                        Text(
                                            text = title,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            style = MaterialTheme.typography.titleLarge,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f)
                                                .padding(top = 12.dp, bottom = 20.dp)
                                        ) {
                                            BookReaderCoverImage(
                                                coverUri = coverUri,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = title,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                        )
                                        Box(modifier = Modifier.fillMaxSize()) {
                                            Text(
                                                text = "No cover",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.align(Alignment.Center)
                                            )
                                        }
                                    }
                                } 
                            }
                                cues.isEmpty() -> Text(stringResource(R.string.bookreader_no_subtitles))
                            lyricsMode -> {
                                if (bookVerticalWriting) {
                                    LazyRow(
                                        modifier = Modifier.fillMaxSize(),
                                        state = lyricsListState,
                                        reverseLayout = true,
                                        contentPadding = PaddingValues(horizontal = BOOK_VERTICAL_CUE_EDGE_PADDING),
                                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        itemsIndexed(
                                            items = cues,
                                            key = { _, cue -> "${cue.startMs}:${cue.endMs}:${cue.text.hashCode()}" },
                                            contentType = { _, _ -> "verticalCue" }
                                        ) { index, cue ->
                                            val isActive = index == activeCueIndex
                                            val inSelectedRange = selectedCueIndexRange?.contains(index) == true
                                            Box(
                                                modifier = Modifier
                                                    .fillParentMaxHeight()
                                                    .background(
                                                        if (inSelectedRange) {
                                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                                        } else {
                                                            Color.Transparent
                                                    },
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                    .padding(
                                                        horizontal = BOOK_VERTICAL_CUE_ITEM_HORIZONTAL_PADDING,
                                                        vertical = 6.dp
                                                    )
                                            ) {
                                                val cueStyle = if (isActive) {
                                                    activeSubtitleStyle
                                                } else {
                                                    inactiveSubtitleStyle
                                                }
                                                if (bookVerticalWriting) {
                                                    if (isActive) {
                                                        val cueWidth = rememberVerticalCueWidth(
                                                            text = cue.text,
                                                            style = cueStyle,
                                                            rowsPerColumn = verticalRowsPerColumn,
                                                            compact = true,
                                                            columnSpacingScale = bookVerticalColumnSpacingScale
                                                        )
                                                        VerticalLookupClickableSubtitle(
                                                            sourceText = cue.text,
                                                            style = cueStyle,
                                                            typeface = subtitleTypeface,
                                                            rowsPerColumn = verticalRowsPerColumn,
                                                            columnSpacingScale = bookVerticalColumnSpacingScale,
                                                            selectedSourceRange = visibleSelectedRange,
                                                            compactVerticalLayout = true,
                                                            lookupEnabled = !cueRangeSelectionMode,
                                                            modifier = Modifier
                                                                .fillParentMaxHeight()
                                                                .width(cueWidth),
                                                            onDisplayTap = {
                                                                if (cueRangeSelectionMode) {
                                                                    handleCueRangeTap(index)
                                                                }
                                                            },
                                                            onSelectedRangeAnchorChanged = { anchor ->
                                                                liveSelectedRangeAnchor = anchor
                                                            },
                                                            onTextTap = { offset, anchor ->
                                                                if (cueRangeSelectionMode) return@VerticalLookupClickableSubtitle
                                                                logDebug(BOOK_LOOKUP_SELECTION_LOG_TAG) {
                                                                    "verticalNativeTap(list) cueIndex=$index offset=$offset range=${formatRangeForLog(visibleSelectedRange)} anchor=${anchor.boundingRectOrNull()?.let { "${it.left.toInt()},${it.top.toInt()},${it.right.toInt()},${it.bottom.toInt()}" } ?: "null"}"
                                                                }
                                                                triggerPopupLookup(cue, offset, anchor)
                                                            }
                                                        )
                                                    } else {
                                                        val cueWidth = rememberVerticalCueWidth(
                                                            text = cue.text,
                                                            style = cueStyle,
                                                            rowsPerColumn = verticalRowsPerColumn,
                                                            compact = true,
                                                            columnSpacingScale = bookVerticalColumnSpacingScale
                                                        )
                                                        VerticalSubtitleText(
                                                            text = cue.text,
                                                            style = cueStyle,
                                                            typeface = subtitleTypeface,
                                                            rowsPerColumn = verticalRowsPerColumn,
                                                            columnSpacingScale = bookVerticalColumnSpacingScale,
                                                            compactVerticalLayout = true,
                                                            onClick = {
                                                                if (cueRangeSelectionMode) {
                                                                    handleCueRangeTap(index)
                                                                } else {
                                                                    jumpToCue(index)
                                                                }
                                                            },
                                                            modifier = Modifier
                                                                .fillParentMaxHeight()
                                                                .width(cueWidth)
                                                        )
                                                    }
                                                } else if (!isActive && !cueRangeSelectionMode) {
                                                    val cueWidth = rememberVerticalCueWidth(
                                                        text = cue.text,
                                                        style = cueStyle,
                                                        rowsPerColumn = verticalRowsPerColumn,
                                                        compact = true,
                                                        columnSpacingScale = bookVerticalColumnSpacingScale
                                                    )
                                                    VerticalSubtitleText(
                                                        text = cue.text,
                                                        style = cueStyle,
                                                        typeface = subtitleTypeface,
                                                        rowsPerColumn = verticalRowsPerColumn,
                                                        columnSpacingScale = bookVerticalColumnSpacingScale,
                                                        compactVerticalLayout = true,
                                                        onClick = { jumpToCue(index) },
                                                        modifier = Modifier
                                                            .fillParentMaxHeight()
                                                            .width(cueWidth)
                                                    )
                                                } else {
                                                    ReaderLookupClickableSubtitle(
                                                        text = buildHighlightedText(
                                                            cue.text,
                                                            if (isActive) {
                                                                visibleSelectedRange
                                                            } else {
                                                                null
                                                            }
                                                        ),
                                                        selectedRange = if (isActive) {
                                                            visibleSelectedRange
                                                        } else {
                                                            null
                                                        },
                                                        style = cueStyle,
                                                        onSelectedRangeAnchorChanged = if (isActive) {
                                                            { anchor ->
                                                                liveSelectedRangeAnchor = anchor
                                                            }
                                                        } else null,
                                                        onTextTap = { offset, anchor ->
                                                            if (cueRangeSelectionMode) {
                                                                handleCueRangeTap(index)
                                                            } else if (isActive) {
                                                                triggerPopupLookup(cue, offset, anchor)
                                                            } else {
                                                                jumpToCue(index)
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        state = lyricsListState,
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        itemsIndexed(
                                            items = cues,
                                            key = { _, cue -> "${cue.startMs}:${cue.endMs}:${cue.text.hashCode()}" },
                                            contentType = { _, _ -> "horizontalCue" }
                                        ) { index, cue ->
                                            val isActive = index == activeCueIndex
                                            val inSelectedRange = selectedCueIndexRange?.contains(index) == true
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(
                                                        if (inSelectedRange) {
                                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                                        } else {
                                                            Color.Transparent
                                                        },
                                                        shape = RoundedCornerShape(12.dp)
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                            ) {
                                                ReaderLookupClickableSubtitle(
                                                    text = buildHighlightedText(
                                                        cue.text,
                                                        if (isActive) visibleSelectedRange else null
                                                    ),
                                                    selectedRange = if (isActive) {
                                                        visibleSelectedRange
                                                    } else {
                                                        null
                                                    },
                                                    style = if (isActive) {
                                                        activeSubtitleStyle
                                                    } else {
                                                        inactiveSubtitleStyle
                                                    },
                                                    onSelectedRangeAnchorChanged = if (isActive) {
                                                        { anchor ->
                                                            liveSelectedRangeAnchor = anchor
                                                        }
                                                    } else null,
                                                    onTextTap = { offset, anchor ->
                                                        if (cueRangeSelectionMode) {
                                                            handleCueRangeTap(index)
                                                        } else if (isActive) {
                                                            triggerPopupLookup(cue, offset, anchor)
                                                        } else {
                                                            jumpToCue(index)
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                                activeCue == null -> Text(stringResource(R.string.bookreader_waiting_for_subtitle))
                            else -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(
                                            top = if (audiobookSettings.activeCueDisplayAtTop) 36.dp else 0.dp
                                        ),
                                    contentAlignment = when {
                                        bookVerticalWriting && audiobookSettings.activeCueDisplayAtTop -> Alignment.TopEnd
                                        bookVerticalWriting -> Alignment.CenterEnd
                                        audiobookSettings.activeCueDisplayAtTop -> Alignment.TopCenter
                                        else -> Alignment.Center
                                    }
                                ) {
                                    if (bookVerticalWriting) {
                                        VerticalLookupClickableSubtitle(
                                            sourceText = activeCue.text,
                                            style = activeSubtitleStyle,
                                            typeface = subtitleTypeface,
                                            rowsPerColumn = verticalRowsPerColumn,
                                            columnSpacingScale = bookVerticalColumnSpacingScale,
                                            selectedSourceRange = visibleSelectedRange,
                                            lookupEnabled = !cueRangeSelectionMode,
                                            modifier = Modifier.fillMaxSize(),
                                            onDisplayTap = {
                                                if (cueRangeSelectionMode) {
                                                    handleCueRangeTap(activeCueIndex)
                                                }
                                            },
                                            onSelectedRangeAnchorChanged = { anchor ->
                                                liveSelectedRangeAnchor = anchor
                                            },
                                            onTextTap = { offset, anchor ->
                                                if (cueRangeSelectionMode) return@VerticalLookupClickableSubtitle
                                                logDebug(BOOK_LOOKUP_SELECTION_LOG_TAG) {
                                                    "verticalNativeTap(active) cueIndex=$activeCueIndex offset=$offset range=${formatRangeForLog(visibleSelectedRange)} anchor=${anchor.boundingRectOrNull()?.let { "${it.left.toInt()},${it.top.toInt()},${it.right.toInt()},${it.bottom.toInt()}" } ?: "null"}"
                                                }
                                                triggerPopupLookup(activeCue, offset, anchor)
                                            }
                                        )
                                    } else {
                                        ReaderLookupClickableSubtitle(
                                            text = buildHighlightedText(
                                                activeCue.text,
                                                visibleSelectedRange
                                            ),
                                            selectedRange = visibleSelectedRange,
                                            style = activeSubtitleStyle,
                                            onSelectedRangeAnchorChanged = { anchor ->
                                                liveSelectedRangeAnchor = anchor
                                            },
                                            onTextTap = { offset, anchor ->
                                                if (cueRangeSelectionMode) {
                                                    handleCueRangeTap(activeCueIndex)
                                                } else {
                                                    triggerPopupLookup(activeCue, offset, anchor)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        }
                    }
                if (leftModules.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxHeight()
                            .zIndex(30f)
                            .onGloballyPositioned { coordinates ->
                                leftControlsWidthDp = with(density) { coordinates.size.width.toDp() }
                                layoutSlotBounds[BookReaderUiSlot.LEFT] = rectFromRootPosition(
                                    coordinates.positionInRoot(),
                                    coordinates.size
                                )
                            },
                        tonalElevation = 2.dp,
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(horizontal = 8.dp, vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceEvenly
                        ) {
                            leftModules.forEach { module ->
                                RenderReaderUiModule(module, BookReaderUiSlot.LEFT)
                            }
                        }
                    }
                }
                if (rightModules.isNotEmpty()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .fillMaxHeight()
                            .zIndex(30f)
                            .onGloballyPositioned { coordinates ->
                                rightControlsWidthDp = with(density) { coordinates.size.width.toDp() }
                                layoutSlotBounds[BookReaderUiSlot.RIGHT] = rectFromRootPosition(
                                    coordinates.positionInRoot(),
                                    coordinates.size
                                )
                            },
                        tonalElevation = 2.dp,
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(horizontal = 8.dp, vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceEvenly
                        ) {
                            rightModules.forEach { module ->
                                RenderReaderUiModule(module, BookReaderUiSlot.RIGHT)
                            }
                        }
                    }
                }
                }
            }
        }

        val highlightedSlot = dragOverlayCandidateSlot
        if (uiLayoutEditMode && dragOverlayModule != null && highlightedSlot != null) {
            val highlightColor = if (dragOverlayCandidateValid) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
            }
            val highlightBorderColor = if (dragOverlayCandidateValid) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
            } else {
                MaterialTheme.colorScheme.error.copy(alpha = 0.42f)
            }
            val highlightShape = RoundedCornerShape(18.dp)
            if (highlightedSlot != BookReaderUiSlot.HIDDEN) {
                val bounds = layoutSlotBoundsFor(highlightedSlot)
                Box(
                    modifier = Modifier
                        .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                        .width(with(density) { bounds.width.toDp() })
                        .height(with(density) { bounds.height.toDp() })
                        .zIndex(1500f)
                        .padding(4.dp)
                        .background(highlightColor, highlightShape)
                        .border(1.dp, highlightBorderColor, highlightShape)
                )
            }
        }

        dragOverlayInsertLine?.let { line ->
            Box(
                modifier = Modifier
                    .offset { IntOffset(line.left.roundToInt(), line.top.roundToInt()) }
                    .width(with(density) { line.width.toDp() })
                    .height(with(density) { line.height.toDp() })
                    .zIndex(1800f)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
            )
        }

        val overlayModule = dragOverlayModule.takeIf { dragOverlayTargetSlot == null }
        val overlaySlot = dragOverlayOriginSlot
        val overlayTargetBounds: Rect? = null
        if (overlayModule != null && overlaySlot != null) {
            val overlayBaseModifier = if (overlayTargetBounds != null) {
                val overlayWidth = when (overlaySlot) {
                    BookReaderUiSlot.LEFT, BookReaderUiSlot.RIGHT -> dragOverlaySize.width.coerceAtLeast(1)
                    else -> overlayTargetBounds.width.roundToInt().coerceAtLeast(1)
                }
                val overlayHeight = when (overlaySlot) {
                    BookReaderUiSlot.LEFT, BookReaderUiSlot.RIGHT -> dragOverlaySize.height.coerceAtLeast(1)
                    BookReaderUiSlot.BOTTOM -> with(density) { 88.dp.roundToPx() }
                    BookReaderUiSlot.TOP -> dragOverlaySize.height.coerceAtMost(with(density) { 88.dp.roundToPx() }).coerceAtLeast(1)
                    BookReaderUiSlot.HIDDEN -> dragOverlaySize.height.coerceAtLeast(1)
                }
                val overlayX = when (overlaySlot) {
                    BookReaderUiSlot.LEFT, BookReaderUiSlot.RIGHT -> {
                        ((overlayTargetBounds.left + overlayTargetBounds.right) / 2f) - overlayWidth / 2f
                    }
                    else -> overlayTargetBounds.left
                }
                val overlayY = when (overlaySlot) {
                    BookReaderUiSlot.TOP -> overlayTargetBounds.top
                    BookReaderUiSlot.BOTTOM -> overlayTargetBounds.top + with(density) { 8.dp.toPx() }
                    BookReaderUiSlot.LEFT, BookReaderUiSlot.RIGHT -> {
                        dragOverlayPosition.y.coerceIn(
                            overlayTargetBounds.top,
                            overlayTargetBounds.bottom - overlayHeight
                        )
                    }
                    BookReaderUiSlot.HIDDEN -> dragOverlayPosition.y
                }
                Modifier
                    .offset {
                        IntOffset(
                            overlayX.roundToInt(),
                            overlayY.roundToInt()
                        )
                    }
                    .width(with(density) { overlayWidth.toDp() })
                    .height(with(density) { overlayHeight.toDp() })
            } else {
                Modifier
                    .offset {
                        IntOffset(
                            dragOverlayPosition.x.roundToInt(),
                            dragOverlayPosition.y.roundToInt()
                        )
                    }
                    .width(with(density) { dragOverlaySize.width.coerceAtLeast(1).toDp() })
                    .height(with(density) { dragOverlaySize.height.coerceAtLeast(1).toDp() })
            }
            Box(
                modifier = overlayBaseModifier
                    .zIndex(2000f)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.48f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        shape = RoundedCornerShape(14.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                val contentPadding = when (overlaySlot) {
                    BookReaderUiSlot.LEFT, BookReaderUiSlot.RIGHT -> PaddingValues(0.dp)
                    BookReaderUiSlot.TOP -> PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    BookReaderUiSlot.BOTTOM -> PaddingValues(12.dp)
                    BookReaderUiSlot.HIDDEN -> PaddingValues(0.dp)
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    contentAlignment = Alignment.Center
                ) {
                    RenderReaderUiModuleContent(
                        module = overlayModule,
                        slot = overlaySlot,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Box(
                    modifier = Modifier
                        .align(overlaySlot.centerFacingAlignment())
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E88E5))
                )
            }
        }

        if (timeEditDialogVisible) {
            AlertDialog(
                onDismissRequest = {
                    timeEditDialogVisible = false
                    timeEditError = null
                },
                title = {
                    Text(
                        if (useChapterTimeline && !showOverallDuration) {
                            stringResource(R.string.bookreader_edit_chapter_time)
                        } else {
                            stringResource(R.string.bookreader_edit_playback_time)
                        }
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = timeEditInput,
                            onValueChange = { value ->
                                timeEditInput = value.filter { it.isDigit() || it == ':' }.take(12)
                                timeEditError = null
                            },
                            singleLine = true,
                            label = { Text(stringResource(R.string.bookreader_time_format_hint)) }
                        )
                        if (!timeEditError.isNullOrBlank()) {
                            Text(timeEditError!!, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            timeEditDialogVisible = false
                            timeEditError = null
                        }
                    ) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val targetOffsetMs = parseEditableTimeInputToMillis(timeEditInput)
                            if (targetOffsetMs == null) {
                                timeEditError = context.getString(R.string.bookreader_time_invalid)
                                return@Button
                            }
                            val absoluteTarget = if (useChapterTimeline && !showOverallDuration) {
                                activeChapterStartMs + targetOffsetMs.coerceIn(0L, timelineRangeMs)
                            } else {
                                targetOffsetMs.coerceAtLeast(0L)
                            }
                            seekToManual(absoluteTarget)
                            timeEditDialogVisible = false
                            timeEditError = null
                        }
                    ) {
                        Text(stringResource(R.string.bookreader_jump))
                    }
                }
            )
        }

        if (sleepTimerOptionsVisible) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(-16, 84),
                onDismissRequest = {
                    sleepTimerOptionsVisible = false
                    sleepTimerAdvancedOptionsExpanded = false
                },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                Card {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (sleepRemainingLabel != null) {
                            Text(stringResource(R.string.bookreader_sleep_remaining, sleepRemainingLabel))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(onClick = { setSleepTimer(15) }) { Text("15m") }
                            OutlinedButton(onClick = { setSleepTimer(30) }) { Text("30m") }
                            OutlinedButton(onClick = { setSleepTimer(60) }) { Text("60m") }
                            TextButton(onClick = { setSleepTimer(0) }) { Text(stringResource(R.string.common_close)) }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = sleepCustomMinutesInput,
                                onValueChange = { value ->
                                    sleepCustomMinutesInput = value.filter { it.isDigit() }.take(4)
                                },
                                modifier = Modifier.weight(1f),
                                label = { Text(stringResource(R.string.bookreader_custom_minutes)) },
                                singleLine = true
                            )
                            Button(onClick = { applyCustomSleepTimer() }) {
                                Text(stringResource(R.string.bookreader_set))
                            }
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    sleepTimerAdvancedOptionsExpanded = !sleepTimerAdvancedOptionsExpanded
                                },
                            shape = RoundedCornerShape(999.dp),
                            color = Color.Transparent
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    modifier = Modifier.size(width = 44.dp, height = 6.dp),
                                    shape = RoundedCornerShape(999.dp),
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                ) {}
                            }
                        }
                        AnimatedVisibility(
                            visible = sleepTimerAdvancedOptionsExpanded,
                            enter = fadeIn(animationSpec = tween(180)),
                            exit = fadeOut(animationSpec = tween(140))
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.bookreader_sleep_fade_out_audio),
                                    modifier = Modifier.weight(1f).padding(end = 12.dp)
                                )
                                Switch(
                                    checked = sleepFadeOutAudioWhenDone,
                                    onCheckedChange = { checked ->
                                        sleepFadeOutAudioWhenDone = checked
                                    }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.bookreader_sleep_exit_control),
                                    modifier = Modifier.weight(1f).padding(end = 12.dp)
                                )
                                Switch(
                                    checked = sleepExitControlModeWhenDone,
                                    onCheckedChange = { checked ->
                                        sleepExitControlModeWhenDone = checked
                                    }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.bookreader_sleep_disconnect_bluetooth),
                                    modifier = Modifier.weight(1f).padding(end = 12.dp)
                                )
                                Switch(
                                    checked = sleepDisconnectControllerBluetoothWhenDone,
                                    onCheckedChange = { checked ->
                                        sleepDisconnectControllerBluetoothWhenDone = checked
                                    }
                                )
                            }
                            TextButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                }
                            ) {
                                Text(stringResource(R.string.bookreader_open_bluetooth))
                            }
                            }
                        }
                    }
                }
            }
        }

        if (typographyPanelVisible) {
            Popup(
                alignment = Alignment.BottomCenter,
                offset = IntOffset(0, -16),
                onDismissRequest = { typographyPanelVisible = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                BookReaderTypographyPanel(
                    settings = audiobookSettings,
                    writingMode = readerUiWritingMode,
                    onDismiss = { typographyPanelVisible = false },
                    onSettingsChanged = reloadAudiobookSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .navigationBarsPadding()
                )
            }
        }

    }

    val bookHoshiPopupOptions = LookupPopupOptions(
        isVertical = false,
        isFullWidth = false,
        width = 320,
        height = 250,
        swipeToDismiss = true,
        swipeThreshold = 40,
        topInset = 0.0,
        bottomInset = navigationBarBottomInsetDp,
        dictionarySettings = loadDictionarySettings(context),
        darkMode = isDarkTheme,
        eInkMode = false,
        audioSettings = audiobookSettings,
        showRangeSelection = false,
        showPlayAudio = audiobookSettings.lookupPlaybackAudioEnabled,
        popupActionBar = true,
    )
    if (!hoshiLookupPopupTemporarilyHidden) LookupPopupStackView(
        popups = hoshiLookupPopups,
        onPopupsChange = { next ->
            hoshiLookupPopups.clear()
            hoshiLookupPopups.addAll(next)
            if (next.isEmpty()) {
                hoshiLookupSelectionCueIndex = null
                hoshiLookupSelectionRange = null
                hoshiLookupPopupTemporarilyHidden = false
                reopenHoshiLookupPopupAfterCueRangeSelection = false
            }
        },
        lookupChildPopup = { selection ->
            logDebug("AnkiExportDebug") {
                "bookHoshi lookupChildPopup request text='${selection.text.take(24)}' sentenceOffset=${selection.sentenceOffset} hasResults=${hoshiLookupPopups.isNotEmpty()} stackSize=${hoshiLookupPopups.size}"
            }
            val popup = bookHoshiLookupSession.createPopup(
                selection = selection,
                options = bookHoshiPopupOptions,
            )
            if (popup == null) {
                logDebug("AnkiExportDebug") {
                    "bookHoshi lookupChildPopup empty text='${selection.text.take(24)}'"
                }
            }
            popup
        },
        onLookupRedirect = { query ->
            val dictionarySettings = loadDictionarySettings(context)
            bookHoshiLookupSession.lookup(
                query,
                dictionarySettings.maxResults,
                dictionarySettings.scanLength,
            )
        },
        onRangeSelection = {
            beginHoshiCueRangeSelection(reopenLookupPopupAfterSelection = true)
        },
        onMineEntryAsync = { content, onComplete ->
            logDebug("AnkiExportDebug") {
                "bookHoshi onMineEntry contentSize=${content.length} selectionCueIndex=$hoshiLookupSelectionCueIndex activeCueIndex=$activeCueIndex"
            }
            exportBookHoshiLookupEntryToAnkiAsync(content, onComplete)
        },
        onDuplicateCheckAsync = { expression, onComplete -> checkBookAnkiDuplicateAsync(expression, onComplete) },
        onViewDuplicate = { noteIds -> openAnkiDuplicateNotesInBrowser(context, noteIds) },
        onPlayWordAudio = { _url, term, reading ->
            if (!term.isNullOrBlank()) {
                playLookupAudioForTerm(
                    context = context,
                    term = term,
                    reading = reading,
                    settings = audiobookSettings
                )
            }
        },
        onCloseAll = {
            logDebug("AnkiExportDebug") {
                "bookHoshi onCloseAll stackSize=${hoshiLookupPopups.size} topIndex=${hoshiLookupPopups.lastIndex}"
            }
            closeHoshiLookupPopup()
        },
        modifier = Modifier.fillMaxSize(),
        onRootPopupDismissed = {
            hoshiLookupPopups.clear()
            hoshiLookupSelectionCueIndex = null
            hoshiLookupSelectionRange = null
            hoshiLookupPopupTemporarilyHidden = false
            reopenHoshiLookupPopupAfterCueRangeSelection = false
            clearCueRangeSelection()
        },
        warmRootOptions = bookHoshiPopupOptions,
    )

    if (controlModeEnabled && cues.isNotEmpty()) {
        val configuration = LocalConfiguration.current
        Popup(
            alignment = Alignment.Center,
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                clippingEnabled = false
            )
        ) {
            Box(
                modifier = Modifier
                    .size(
                        width = configuration.screenWidthDp.dp,
                        height = configuration.screenHeightDp.dp
                    )
                    .background(if (controlModePowerSaveEnabled) Color.Black else Color.Gray.copy(alpha = 0.38f))
                    .pointerInput(playbackCueIndex, isPlaying, cues.size) {
                        detectTapGestures(onTap = { handleControlOverlayTap() })
                    }
                    .pointerInput(playbackCueIndex, cues.size) {
                        var totalDrag = 0f
                        var handled = false
                        detectHorizontalDragGestures(
                            onDragStart = {
                                totalDrag = 0f
                                handled = false
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                if (handled) return@detectHorizontalDragGestures
                                totalDrag += dragAmount
                                if (totalDrag <= -80f) {
                                    handleControlOverlaySwipe(-1)
                                    handled = true
                                } else if (totalDrag >= 80f) {
                                    handleControlOverlaySwipe(1)
                                    handled = true
                                }
                            },
                            onDragEnd = {
                                totalDrag = 0f
                                handled = false
                            },
                            onDragCancel = {
                                totalDrag = 0f
                                handled = false
                            }
                        )
                    }
                    .pointerInput(controlModeEnabled, playbackCueIndex, cues.size) {
                        awaitPointerEventScope {
                            while (true) {
                                val first = awaitPointerEvent()
                                val pressedCount = first.changes.count { it.pressed }
                                if (pressedCount < 2) continue

                                val startAt = System.currentTimeMillis()
                                var cancelled = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val nowPressed = event.changes.count { it.pressed }
                                    if (nowPressed < 2) {
                                        cancelled = true
                                        break
                                    }
                                    val moved = event.changes.any { change ->
                                        val delta = change.positionChange()
                                        abs(delta.x) > 24f || abs(delta.y) > 24f
                                    }
                                    if (moved) {
                                        cancelled = true
                                        break
                                    }
                                    if (System.currentTimeMillis() - startAt >= 450L) {
                                        exitControlModeByTwoFingerLongPress()
                                        break
                                    }
                                }
                                if (!controlModeEnabled) break
                                if (cancelled) continue
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (controlModePowerSaveEnabled) {
                        stringResource(R.string.control_mode_overlay_hint)
                    } else {
                        controlModeHintText
                    },
                    color = Color.White
                )
            }
        }
    }

}
}

@Composable
private fun BookReaderChapterSelectorModule(
    modifier: Modifier = Modifier,
    vertical: Boolean,
    slot: BookReaderUiSlot? = null,
    chapters: List<ReaderAudioChapter>,
    activeChapterIndex: Int,
    expanded: Boolean,
    visible: Boolean,
    onToggleExpanded: () -> Unit,
    onDismissExpanded: () -> Unit,
    onJumpChapter: (ReaderAudioChapter) -> Unit
) {
    if (!visible || chapters.isEmpty()) return
    val activeChapterTitle = chapters
        .getOrNull(activeChapterIndex)
        ?.title
        ?.takeIf { it.isNotBlank() }
    val activeChapterNumber = chapters
        .getOrNull(activeChapterIndex)
        ?.let { (activeChapterIndex + 1).toString() }
        ?: "--"
    if (vertical) {
        val chapterButtonLabel = stringResource(R.string.bookreader_chapters_collapsed)
            .substringBefore(" ")
            .take(2)
        val chapterArrow = when (slot) {
            BookReaderUiSlot.LEFT -> "▶"
            BookReaderUiSlot.RIGHT -> "◀"
            else -> if (expanded) "▲" else "▼"
        }
        Column(
            modifier = modifier.width(56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box {
                OutlinedButton(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
                        .width(44.dp)
                        .height(72.dp),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
                    onClick = onToggleExpanded
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        chapterButtonLabel.forEach { char ->
                            Text(
                                text = char.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = chapterArrow,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = onDismissExpanded
                ) {
                    chapters.forEach { chapter ->
                        DropdownMenuItem(
                            text = { Text(chapter.title) },
                            onClick = {
                                onJumpChapter(chapter)
                                onDismissExpanded()
                            }
                        )
                    }
                }
            }
            Text(
                text = activeChapterNumber,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall
            )
        }
        return
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            OutlinedButton(onClick = onToggleExpanded) {
                Text(
                    if (expanded) {
                        stringResource(R.string.bookreader_chapters_expanded)
                    } else {
                        stringResource(R.string.bookreader_chapters_collapsed)
                    }
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = onDismissExpanded
            ) {
                chapters.forEach { chapter ->
                    DropdownMenuItem(
                        text = { Text(chapter.title) },
                        onClick = {
                            onJumpChapter(chapter)
                            onDismissExpanded()
                        }
                    )
                }
            }
        }
        if (activeChapterTitle != null) {
            Text(
                text = "Now: ${
                    if (activeChapterTitle.length > 26) {
                        activeChapterTitle.take(26) + "..."
                    } else {
                        activeChapterTitle
                    }
                }",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BookReaderPlaybackTimelineModule(
    modifier: Modifier = Modifier,
    vertical: Boolean,
    displayedRightDurationTimeMs: Long,
    displayedLeftTimeMs: Long,
    sliderMax: Float,
    sliderValue: Float,
    displayedDurationTimeMs: Long,
    useChapterTimeline: Boolean,
    activeChapterStartMs: Long,
    timelineRangeMs: Long,
    durationMs: Long,
    onToggleDurationMode: () -> Unit,
    onPreviewPositionChanged: (Long?) -> Unit,
    onSeekManual: (Long) -> Unit,
    onRequestTimeEdit: () -> Unit
) {
    var horizontalPendingSeekTargetMs by remember { mutableStateOf<Long?>(null) }
    if (vertical) {
        var verticalTimelineHeightPx by remember { mutableStateOf(220f) }
        var pendingSeekTargetMs by remember { mutableStateOf<Long?>(null) }
        fun resolveVerticalTimelineTarget(y: Float): Long? {
            if (displayedDurationTimeMs <= 0L) return null
            val ratio = 1f - (y.coerceIn(0f, verticalTimelineHeightPx) / verticalTimelineHeightPx)
            val clamped = (ratio * timelineRangeMs.toFloat()).toLong()
                .coerceIn(0L, timelineRangeMs)
            return if (useChapterTimeline) {
                activeChapterStartMs + clamped
            } else {
                clamped.coerceIn(0L, durationMs.coerceAtLeast(0L))
            }
        }
        Column(
            modifier = modifier
                .width(60.dp)
                .height(260.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = formatPlaybackTime(displayedRightDurationTimeMs),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                maxLines = 1,
                modifier = if (useChapterTimeline) {
                    Modifier.clickable(onClick = onToggleDurationMode)
                } else {
                    Modifier
                }
            )
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .height(180.dp)
                    .background(Color(0xFFD4E0F2), RoundedCornerShape(10.dp))
                    .onGloballyPositioned { coordinates ->
                        verticalTimelineHeightPx = coordinates.size.height.toFloat().coerceAtLeast(1f)
                    }
                    .pointerInput(
                        displayedDurationTimeMs,
                        useChapterTimeline,
                        activeChapterStartMs,
                        timelineRangeMs,
                        durationMs,
                        verticalTimelineHeightPx
                    ) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                val target = resolveVerticalTimelineTarget(offset.y)
                                pendingSeekTargetMs = target
                                onPreviewPositionChanged(target)
                            },
                            onVerticalDrag = { change, _ ->
                                val target = resolveVerticalTimelineTarget(change.position.y)
                                pendingSeekTargetMs = target
                                onPreviewPositionChanged(target)
                                change.consume()
                            },
                            onDragEnd = {
                                pendingSeekTargetMs?.let(onSeekManual)
                                pendingSeekTargetMs = null
                                onPreviewPositionChanged(null)
                            },
                            onDragCancel = {
                                pendingSeekTargetMs = null
                                onPreviewPositionChanged(null)
                            }
                        )
                    }
            ) {
                val ratio = if (sliderMax > 0f) (sliderValue / sliderMax).coerceIn(0f, 1f) else 0f
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(ratio)
                        .background(Color(0xFF3E6E9C), RoundedCornerShape(10.dp))
                )
            }
            Text(
                text = formatPlaybackTime(displayedLeftTimeMs),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier.clickable(onClick = onRequestTimeEdit)
            )
        }
        return
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onRequestTimeEdit) {
            Text(formatPlaybackTime(displayedLeftTimeMs))
        }
        Slider(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            value = sliderValue,
            valueRange = 0f..sliderMax,
            enabled = displayedDurationTimeMs > 0L,
            onValueChange = { raw ->
                if (displayedDurationTimeMs > 0L) {
                    val clamped = raw.toLong().coerceIn(0L, timelineRangeMs)
                    onPreviewPositionChanged(
                        if (useChapterTimeline) {
                            activeChapterStartMs + clamped
                        } else {
                            clamped.coerceIn(0L, durationMs.coerceAtLeast(0L))
                        }.also { horizontalPendingSeekTargetMs = it }
                    )
                }
            },
            onValueChangeFinished = {
                horizontalPendingSeekTargetMs?.let(onSeekManual)
                horizontalPendingSeekTargetMs = null
                onPreviewPositionChanged(null)
            }
        )
        Text(
            text = formatPlaybackTime(displayedRightDurationTimeMs),
            modifier = if (useChapterTimeline) {
                Modifier.clickable(onClick = onToggleDurationMode)
            } else {
                Modifier
            }
        )
    }
}

@Composable
private fun BookReaderPlaybackControlsModule(
    modifier: Modifier = Modifier,
    vertical: Boolean,
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    val arrangement = Arrangement.spacedBy(if (vertical) 12.dp else 28.dp)
    if (vertical) {
        Column(
            modifier = modifier.width(60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = arrangement
        ) {
            BookReaderPlaybackControlButtons(isPlaying, onPrevious, onPlayPause, onNext)
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookReaderPlaybackControlButtons(isPlaying, onPrevious, onPlayPause, onNext)
        }
    }
}

@Composable
private fun BookReaderPlaybackControlButtons(
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    IconButton(onClick = onPrevious, modifier = Modifier.size(40.dp)) {
        Icon(
            painter = painterResource(id = R.drawable.ic_overlay_previous),
            contentDescription = stringResource(R.string.controller_previous)
        )
    }
    IconButton(onClick = onPlayPause, modifier = Modifier.size(44.dp)) {
        Icon(
            painter = painterResource(
                id = if (isPlaying) R.drawable.ic_overlay_pause else R.drawable.ic_overlay_play
            ),
            contentDescription = if (isPlaying) {
                stringResource(R.string.common_pause)
            } else {
                stringResource(R.string.common_play)
            }
        )
    }
    IconButton(onClick = onNext, modifier = Modifier.size(40.dp)) {
        Icon(
            painter = painterResource(id = R.drawable.ic_overlay_next),
            contentDescription = stringResource(R.string.controller_next)
        )
    }
}

@Composable
private fun BookReaderChapterProgressJumpModeModule(
    modifier: Modifier = Modifier,
    useChapterTimeline: Boolean,
    showOverallProgress: Boolean,
    totalProgressPercent: Int,
    progressPercent: Int,
    effectiveAdjacentJumpMode: AdjacentJumpMode,
    cuesAvailable: Boolean,
    seekStepMillis: Long,
    onToggleProgressMode: () -> Unit,
    onToggleJumpMode: () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val progressLabel = when {
            useChapterTimeline && showOverallProgress -> stringResource(R.string.bookreader_progress_total, totalProgressPercent)
            useChapterTimeline -> stringResource(R.string.bookreader_progress_chapter, progressPercent)
            else -> stringResource(R.string.bookreader_progress_plain, progressPercent)
        }
        Text(
            text = progressLabel,
            modifier = if (useChapterTimeline) {
                Modifier.clickable(onClick = onToggleProgressMode)
            } else {
                Modifier
            }
        )
        OutlinedButton(
            enabled = cuesAvailable,
            onClick = onToggleJumpMode
        ) {
            val stepSeconds = seekStepMillis / 1000L
            val label = when (effectiveAdjacentJumpMode) {
                AdjacentJumpMode.CUE -> stringResource(R.string.bookreader_jump_by_cue)
                AdjacentJumpMode.DURATION -> stringResource(R.string.bookreader_jump_by_duration, stepSeconds.toInt())
            }
            Text(label)
        }
    }
}

private fun buildHighlightedText(text: String, selectedRange: IntRange?): AnnotatedString {
    return buildAnnotatedString {
        append(text)
        val range = selectedRange ?: return@buildAnnotatedString
        val start = range.first.coerceIn(0, text.length)
        val endExclusive = (range.last + 1).coerceIn(start, text.length)
        if (endExclusive <= start) return@buildAnnotatedString
        addStyle(
            SpanStyle(
                background = Color(0x66A0A0A0)
            ),
            start,
            endExclusive
        )
    }
}

private const val BOOK_VERTICAL_ROWS_PER_COLUMN = 12

@Composable
private fun BookReaderTypographyPanel(
    settings: AudiobookSettingsConfig,
    writingMode: FloatingSubtitleWritingMode,
    onDismiss: () -> Unit,
    onSettingsChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val verticalWriting = writingMode == FloatingSubtitleWritingMode.VERTICAL_RTL
    val activeSize = if (verticalWriting) {
        settings.bookSubtitleVerticalActiveSizeSp
    } else {
        settings.bookSubtitleActiveSizeSp
    }
    val inactiveSize = if (verticalWriting) {
        settings.bookSubtitleVerticalInactiveSizeSp
    } else {
        settings.bookSubtitleInactiveSizeSp
    }

    fun changeActiveSize(delta: Int) {
        val nextSize = (activeSize + delta)
            .coerceIn(MIN_BOOK_SUBTITLE_ACTIVE_SIZE_SP, MAX_BOOK_SUBTITLE_ACTIVE_SIZE_SP)
        if (verticalWriting) {
            saveAudiobookBookSubtitleVerticalActiveSizeSp(context, nextSize)
        } else {
            saveAudiobookBookSubtitleActiveSizeSp(context, nextSize)
        }
        if (!verticalWriting && settings.bookSubtitleHorizontalLineHeightSp < nextSize) {
            saveAudiobookBookSubtitleHorizontalLineHeightSp(context, nextSize)
        }
        onSettingsChanged()
    }

    fun changeInactiveSize(delta: Int) {
        if (verticalWriting) {
            saveAudiobookBookSubtitleVerticalInactiveSizeSp(context, inactiveSize + delta)
        } else {
            saveAudiobookBookSubtitleInactiveSizeSp(context, inactiveSize + delta)
        }
        onSettingsChanged()
    }

    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.audiobook_book_subtitle_typography),
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_close))
                }
            }
            BookReaderTypographyStepper(
                title = stringResource(R.string.audiobook_book_subtitle_active_size),
                valueText = stringResource(
                    R.string.audiobook_book_subtitle_sp_value,
                    activeSize
                ),
                decreaseEnabled = activeSize > MIN_BOOK_SUBTITLE_ACTIVE_SIZE_SP,
                increaseEnabled = activeSize < MAX_BOOK_SUBTITLE_ACTIVE_SIZE_SP,
                onDecrease = { changeActiveSize(-1) },
                onIncrease = { changeActiveSize(1) }
            )
            BookReaderTypographyStepper(
                title = stringResource(R.string.audiobook_book_subtitle_inactive_size),
                valueText = stringResource(
                    R.string.audiobook_book_subtitle_sp_value,
                    inactiveSize
                ),
                decreaseEnabled = inactiveSize > MIN_BOOK_SUBTITLE_INACTIVE_SIZE_SP,
                increaseEnabled = inactiveSize < MAX_BOOK_SUBTITLE_INACTIVE_SIZE_SP,
                onDecrease = { changeInactiveSize(-1) },
                onIncrease = { changeInactiveSize(1) }
            )
            if (verticalWriting) {
                BookReaderTypographyStepper(
                    title = stringResource(R.string.audiobook_book_subtitle_vertical_column_spacing),
                    valueText = stringResource(
                        R.string.audiobook_book_subtitle_percent_value,
                        settings.bookSubtitleVerticalColumnSpacingPercent
                    ),
                    decreaseEnabled = settings.bookSubtitleVerticalColumnSpacingPercent > MIN_BOOK_SUBTITLE_VERTICAL_COLUMN_SPACING_PERCENT,
                    increaseEnabled = settings.bookSubtitleVerticalColumnSpacingPercent < MAX_BOOK_SUBTITLE_VERTICAL_COLUMN_SPACING_PERCENT,
                    onDecrease = {
                        saveAudiobookBookSubtitleVerticalColumnSpacingPercent(
                            context,
                            settings.bookSubtitleVerticalColumnSpacingPercent - 5
                        )
                        onSettingsChanged()
                    },
                    onIncrease = {
                        saveAudiobookBookSubtitleVerticalColumnSpacingPercent(
                            context,
                            settings.bookSubtitleVerticalColumnSpacingPercent + 5
                        )
                        onSettingsChanged()
                    }
                )
            } else {
                val visibleLineHeight = maxOf(
                    settings.bookSubtitleHorizontalLineHeightSp,
                    settings.bookSubtitleActiveSizeSp
                )
                BookReaderTypographyStepper(
                    title = stringResource(R.string.audiobook_book_subtitle_horizontal_line_height),
                    valueText = stringResource(R.string.audiobook_book_subtitle_sp_value, visibleLineHeight),
                    decreaseEnabled = visibleLineHeight > maxOf(
                        MIN_BOOK_SUBTITLE_HORIZONTAL_LINE_HEIGHT_SP,
                        settings.bookSubtitleActiveSizeSp
                    ),
                    increaseEnabled = visibleLineHeight < MAX_BOOK_SUBTITLE_HORIZONTAL_LINE_HEIGHT_SP,
                    onDecrease = {
                        saveAudiobookBookSubtitleHorizontalLineHeightSp(context, visibleLineHeight - 1)
                        onSettingsChanged()
                    },
                    onIncrease = {
                        saveAudiobookBookSubtitleHorizontalLineHeightSp(context, visibleLineHeight + 1)
                        onSettingsChanged()
                    }
                )
            }
            OutlinedButton(
                onClick = {
                    if (verticalWriting) {
                        resetAudiobookBookSubtitleVerticalTypography(context)
                    } else {
                        resetAudiobookBookSubtitleHorizontalTypography(context)
                    }
                    onSettingsChanged()
                }
            ) {
                Text(stringResource(R.string.audiobook_book_subtitle_typography_reset))
            }
        }
    }
}

@Composable
private fun BookReaderTypographyStepper(
    title: String,
    valueText: String,
    decreaseEnabled: Boolean,
    increaseEnabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(onClick = onDecrease, enabled = decreaseEnabled) {
            Text("-")
        }
        OutlinedButton(onClick = onIncrease, enabled = increaseEnabled) {
            Text("+")
        }
    }
}

@Composable
private fun ReaderLookupClickableSubtitle(
    text: AnnotatedString,
    selectedRange: IntRange?,
    style: androidx.compose.ui.text.TextStyle,
    autoScrollProgress: Float? = null,
    modifier: Modifier = Modifier,
    onSelectedRangeAnchorChanged: ((ReaderLookupAnchor?) -> Unit)? = null,
    onTextTap: (offset: Int, anchor: ReaderLookupAnchor) -> Unit
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var textWindowOrigin by remember { mutableStateOf(Offset.Zero) }
    var viewportWidthPx by remember { mutableStateOf(0) }
    val scrollState = rememberScrollState()
    val lineWidthPx = remember(textLayoutResult) {
        textLayoutResult?.let { layout ->
            if (layout.lineCount > 0) {
                (layout.getLineRight(0) - layout.getLineLeft(0)).coerceAtLeast(0f)
            } else {
                0f
            }
        } ?: 0f
    }
    LaunchedEffect(autoScrollProgress, viewportWidthPx, lineWidthPx, text.text) {
        val progress = autoScrollProgress ?: return@LaunchedEffect
        val maxScroll = (lineWidthPx - viewportWidthPx.toFloat()).coerceAtLeast(0f)
        val target = (maxScroll * progress.coerceIn(0f, 1f)).roundToInt()
        scrollState.scrollTo(target)
    }

    LaunchedEffect(selectedRange, textLayoutResult, textWindowOrigin, scrollState.value, text.text) {
        val callback = onSelectedRangeAnchorChanged ?: return@LaunchedEffect
        val layout = textLayoutResult ?: run {
            callback(null)
            return@LaunchedEffect
        }
        val range = selectedRange ?: run {
            callback(null)
            return@LaunchedEffect
        }
        val textLength = text.length
        if (textLength <= 0) {
            callback(null)
            return@LaunchedEffect
        }
        val start = range.first.coerceIn(0, textLength - 1)
        val end = range.last.coerceIn(start, textLength - 1)
        val localRects = buildList {
            for (i in start..end) {
                val box = layout.getBoundingBox(i)
                if (!box.isEmpty) {
                    add(
                        Rect(
                            left = textWindowOrigin.x + box.left - scrollState.value,
                            top = textWindowOrigin.y + box.top,
                            right = textWindowOrigin.x + box.right - scrollState.value,
                            bottom = textWindowOrigin.y + box.bottom
                        )
                    )
                }
            }
        }
        callback(
            if (localRects.isEmpty()) null else ReaderLookupAnchor(rects = mergeRectsByLineShared(localRects))
        )
    }

    Text(
        text = text,
        style = style,
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                textWindowOrigin = bounds.topLeft
                viewportWidthPx = coordinates.size.width
            }
            .then(
                if (autoScrollProgress != null) {
                    Modifier.horizontalScroll(scrollState, enabled = false)
                } else {
                    Modifier
                }
            )
            .pointerInput(onTextTap) {
                detectTapGestures { tapOffset ->
                    val layout = textLayoutResult ?: return@detectTapGestures
                    val textLength = text.length
                    if (textLength <= 0) return@detectTapGestures
                    val contentTapOffset = Offset(
                        x = tapOffset.x + scrollState.value.toFloat(),
                        y = tapOffset.y
                    )
                    var offset = layout.getOffsetForPosition(contentTapOffset)
                        .coerceIn(0, textLength - 1)
                    if (offset > 0) {
                        val previousBox = layout.getBoundingBox(offset - 1)
                        if (
                            contentTapOffset.x >= previousBox.left &&
                            contentTapOffset.x <= previousBox.right &&
                            contentTapOffset.y >= previousBox.top &&
                            contentTapOffset.y <= previousBox.bottom
                        ) {
                            offset -= 1
                        }
                    }
                    val box = layout.getBoundingBox(offset)
                    val line = layout.getLineForOffset(offset)
                    val lineTop = layout.getLineTop(line).toFloat()
                    val lineBottom = layout.getLineBottom(line).toFloat()
                    val anchor = ReaderLookupAnchor(
                        rects = listOf(
                            Rect(
                                left = textWindowOrigin.x + box.left - scrollState.value,
                                right = textWindowOrigin.x + box.right - scrollState.value,
                                top = textWindowOrigin.y + lineTop,
                                bottom = textWindowOrigin.y + lineBottom
                            )
                        )
                    )
                    logDebug(BOOK_LOOKUP_ANCHOR_LOG_TAG) {
                        "tap offset=$offset tap=${tapOffset.x.roundToInt()},${tapOffset.y.roundToInt()} box=${formatRectForLog(anchor.boundingRectOrNull())} scrollX=${scrollState.value}"
                    }
                    onTextTap(offset, anchor)
                }
            },
        softWrap = autoScrollProgress == null,
        maxLines = if (autoScrollProgress != null) 1 else Int.MAX_VALUE,
        overflow = TextOverflow.Clip,
        onTextLayout = { textLayoutResult = it }
    )
}

@Composable
private fun VerticalSubtitleText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    typeface: Typeface?,
    rowsPerColumn: Int,
    columnSpacingScale: Float,
    compactVerticalLayout: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textColor = if (style.color == Color.Unspecified) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        style.color
    }
    val textSizePx = with(density) {
        if (style.fontSize.isSpecified) style.fontSize.toPx() else 22.sp.toPx()
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            VerticalSubtitleView(context).apply {
                isClickable = onClick != null
                setOnClickListener { onClick?.invoke() }
            }
        },
        update = { view ->
            view.isClickable = onClick != null
            view.setOnClickListener { onClick?.invoke() }
            view.bind(text, textColor.toArgb(), textSizePx, typeface, columnSpacingScale)
        }
    )
}

@Composable
private fun rememberVerticalCueWidth(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    rowsPerColumn: Int,
    columnSpacingScale: Float,
    compact: Boolean = false
): Dp {
    val density = LocalDensity.current
    return remember(text, style.fontSize, rowsPerColumn, columnSpacingScale, compact, density) {
        val fontSizeDp = with(density) {
            if (style.fontSize.isSpecified) style.fontSize.toDp() else 22.sp.toDp()
        }
        val columns = ceil(text.length.toFloat() / rowsPerColumn.coerceAtLeast(1).toFloat())
            .toInt()
            .coerceAtLeast(1)
        if (compact) {
            (fontSizeDp * (columns * BOOK_VERTICAL_COLUMN_WIDTH_FACTOR * columnSpacingScale) + 8.dp + BOOK_VERTICAL_CUE_GLYPH_SAFETY_WIDTH)
                .coerceIn(36.dp, 420.dp)
        } else {
            (fontSizeDp * (columns * BOOK_VERTICAL_COLUMN_WIDTH_FACTOR * columnSpacingScale) + 40.dp + BOOK_VERTICAL_CUE_GLYPH_SAFETY_WIDTH)
                .coerceIn(72.dp, 420.dp)
        }
    }
}

@Composable
private fun VerticalLookupClickableSubtitle(
    sourceText: String,
    style: androidx.compose.ui.text.TextStyle,
    typeface: Typeface?,
    rowsPerColumn: Int,
    columnSpacingScale: Float,
    selectedSourceRange: IntRange? = null,
    compactVerticalLayout: Boolean = false,
    lookupEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    onDisplayTap: (() -> Unit)? = null,
    onSelectedRangeAnchorChanged: ((ReaderLookupAnchor?) -> Unit)? = null,
    onTextTap: (sourceOffset: Int, anchor: ReaderLookupAnchor?) -> Unit
) {
    val density = LocalDensity.current
    val textColor = if (style.color == Color.Unspecified) {
        MaterialTheme.colorScheme.onSurface
    } else {
        style.color
    }
    val textSizePx = with(density) {
        if (style.fontSize.isSpecified) style.fontSize.toPx() else 28.sp.toPx()
    }
    val lineHeightPx = with(density) {
        if (style.lineHeight.isSpecified) style.lineHeight.toPx() else textSizePx
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            VerticalLookupSubtitleView(context).apply {
                isClickable = true
            }
        },
        update = { view ->
            view.bind(
                newText = sourceText,
                color = textColor.toArgb(),
                sizePx = textSizePx,
                typeface = typeface,
                lineHeightPx = lineHeightPx,
                rowsPerColumn = rowsPerColumn,
                columnSpacingScale = columnSpacingScale,
                selectedSourceRange = selectedSourceRange,
                onSelectionAnchorChanged = onSelectedRangeAnchorChanged,
                onTap = { sourceOffset, rectInWindow ->
                    if (!lookupEnabled) {
                        onDisplayTap?.invoke()
                    } else {
                        onTextTap(
                            sourceOffset,
                            ReaderLookupAnchor(
                                rects = listOf(
                                    Rect(
                                        left = rectInWindow.left,
                                        top = rectInWindow.top,
                                        right = rectInWindow.right,
                                        bottom = rectInWindow.bottom
                                    )
                                )
                            )
                        )
                    }
                }
            )
        }
    )
}

private class VerticalSubtitleView(context: Context) : android.view.View(context) {
    private var content: String = ""
    private val paint = TextPaint().apply {
        isAntiAlias = true
    }
    private var cachedLayout: VerticalSubtitleLayout? = null
    private var cachedHeight: Int = -1
    private var cachedTextSize: Float = Float.NaN
    private var cachedTypeface: Typeface? = null
    private var columnSpacingScale: Float = 1f

    fun bind(newText: String, color: Int, sizePx: Float, typeface: Typeface?, columnSpacingScale: Float) {
        val normalizedColumnSpacingScale = columnSpacingScale.coerceIn(0.5f, 2f)
        val changed = content != newText ||
            paint.color != color ||
            paint.textSize != sizePx ||
            paint.typeface != typeface ||
            this.columnSpacingScale != normalizedColumnSpacingScale
        if (!changed) return
        content = newText
        paint.color = color
        paint.textSize = sizePx
        paint.typeface = typeface
        this.columnSpacingScale = normalizedColumnSpacingScale
        cachedLayout = null
        cachedHeight = -1
        cachedTypeface = null
        requestLayout()
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val layout = obtainLayout(height) ?: return
        VerticalSubtitleLayoutEngine.draw(canvas, paint, layout, width, height)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val measuredHeight = if (heightSize > 0) {
            heightSize
        } else {
            (paint.textSize * 12f).roundToInt().coerceAtLeast(1)
        }
        val layout = obtainLayout(measuredHeight)
        val desiredWidth = layout?.contentWidth()?.let { ceil(it.toDouble()).toInt() }?.coerceAtLeast(1) ?: 1
        val measuredWidth = resolveSize(desiredWidth, widthMeasureSpec)
        setMeasuredDimension(measuredWidth, resolveSize(measuredHeight, heightMeasureSpec))
    }

    private fun obtainLayout(targetHeight: Int): VerticalSubtitleLayout? {
        if (content.isBlank() || targetHeight <= 0) return null
        if (cachedLayout != null &&
            cachedHeight == targetHeight &&
            cachedTextSize == paint.textSize &&
            cachedTypeface == paint.typeface
        ) {
            return cachedLayout
        }
        cachedLayout = VerticalSubtitleLayoutEngine.build(
            content,
            paint,
            targetHeight,
            paint.textSize.coerceAtLeast(1f),
            cellWidthPx = VerticalTextGlyphEngine.estimateCellWidth(paint) * columnSpacingScale
        )
        cachedHeight = targetHeight
        cachedTextSize = paint.textSize
        cachedTypeface = paint.typeface
        return cachedLayout
    }
}

private class VerticalLookupSubtitleView(context: Context) : android.view.View(context) {
    private var content: String = ""
    private val paint = TextPaint().apply {
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.LEFT
    }
    private var lineHeightPx: Float = 1f
    private var rowsPerColumn: Int = BOOK_VERTICAL_ROWS_PER_COLUMN
    private var columnSpacingScale: Float = 1f
    private var selectedSourceRange: IntRange? = null
    private var onSelectionAnchorChanged: ((ReaderLookupAnchor?) -> Unit)? = null
    private var onTap: ((sourceOffset: Int, rectInWindow: android.graphics.RectF) -> Unit)? = null
    private var downEventTimeMs: Long = 0L
    private var lastSelectionDebugSignature: String? = null
    private var lastSelectionAnchorSignature: String? = null
    private var lastLayoutMetricsSignature: String? = null
    private var debugLastTap: VerticalTapResolved? = null
    private var preferredAnchorRectInWindow: Rect? = null
    private var cachedGridModel: VerticalGridModel? = null
    private var cachedGridHeight: Int = -1
    private var cachedGridText: String = ""
    private var cachedGridTextSize: Float = Float.NaN
    private var cachedGridTypeface: Typeface? = null
    private var cachedVerticalLayout: VerticalSubtitleLayout? = null
    private var cachedVerticalLayoutHeight: Int = -1
    private var cachedVerticalLayoutTypeface: Typeface? = null

    fun bind(
        newText: String,
        color: Int,
        sizePx: Float,
        typeface: Typeface?,
        lineHeightPx: Float,
        rowsPerColumn: Int,
        columnSpacingScale: Float,
        selectedSourceRange: IntRange?,
        onSelectionAnchorChanged: ((ReaderLookupAnchor?) -> Unit)?,
        onTap: (sourceOffset: Int, rectInWindow: android.graphics.RectF) -> Unit
    ) {
        val normalizedColumnSpacingScale = columnSpacingScale.coerceIn(0.5f, 2f)
        val changed = content != newText ||
            paint.color != color ||
            paint.textSize != sizePx ||
            paint.typeface != typeface ||
            this.lineHeightPx != lineHeightPx ||
            this.rowsPerColumn != rowsPerColumn ||
            this.columnSpacingScale != normalizedColumnSpacingScale ||
            this.selectedSourceRange != selectedSourceRange
        this.onTap = onTap
        this.selectedSourceRange = selectedSourceRange
        this.onSelectionAnchorChanged = onSelectionAnchorChanged
        if (!changed) return
        content = newText
        paint.color = color
        paint.textSize = sizePx
        paint.typeface = typeface
        this.lineHeightPx = lineHeightPx.coerceAtLeast(1f)
        this.rowsPerColumn = rowsPerColumn.coerceAtLeast(2)
        this.columnSpacingScale = normalizedColumnSpacingScale
        lastSelectionDebugSignature = null
        lastSelectionAnchorSignature = null
        lastLayoutMetricsSignature = null
        preferredAnchorRectInWindow = null
        clearGridCache()
        clearVerticalLayoutCache()
        requestLayout()
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        logLayoutMetricsIfNeeded()
        drawSelectionBackground(canvas)
        drawVerticalLayoutText(canvas)
        if (BOOK_VERTICAL_TAP_DEBUG_OVERLAY) {
            drawTapDebugOverlay(canvas)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val measuredHeight = if (heightSize > 0) {
            heightSize
        } else {
            (effectiveCellHeightPx() * rowsPerColumn).roundToInt().coerceAtLeast(1)
        }
        val model = buildGridModel(measuredHeight)
        val desiredWidth = model?.let {
            ceil((it.columnCount * it.cellWidth).toDouble()).toInt().coerceAtLeast(1)
        } ?: 1
        val measuredWidth = resolveSize(desiredWidth, widthMeasureSpec)
        setMeasuredDimension(measuredWidth, resolveSize(measuredHeight, heightMeasureSpec))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val resolved = resolveOffsetForEvent(event.x, event.y) ?: return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downEventTimeMs = event.eventTime
                return true
            }
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_CANCEL -> return true
            MotionEvent.ACTION_UP -> {
                val pressDuration = (event.eventTime - downEventTimeMs).coerceAtLeast(0L)
                val tapTimeout = ViewConfiguration.getTapTimeout().toLong()
                val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
                if (pressDuration > tapTimeout && pressDuration >= longPressTimeout) {
                    return true
                }
                val finalOffset = resolved.sourceOffset
                val rectInWindow = resolved.rectInWindow
                val tappedChar = content.getOrNull(finalOffset)?.toString().orEmpty()
                logDebug(BOOK_LOOKUP_SELECTION_LOG_TAG) {
                    "verticalViewTap x=${event.x.roundToInt()} y=${event.y.roundToInt()} row=${resolved.row} col=${resolved.column} logical=${resolved.logical} displayOffset=$finalOffset char='$tappedChar' rect=${rectInWindow.left.roundToInt()},${rectInWindow.top.roundToInt()},${rectInWindow.right.roundToInt()},${rectInWindow.bottom.roundToInt()}"
                }
                if (BOOK_VERTICAL_TAP_DEBUG_OVERLAY) {
                    debugLastTap = resolved
                    invalidate()
                }
                preferredAnchorRectInWindow = Rect(
                    left = rectInWindow.left,
                    top = rectInWindow.top,
                    right = rectInWindow.right,
                    bottom = rectInWindow.bottom
                )
                onTap?.invoke(finalOffset, rectInWindow)
                return true
            }
        }
        return true
    }

    private data class VerticalTapResolved(
        val sourceOffset: Int,
        val logical: Int,
        val row: Int,
        val column: Int,
        val rectInWindow: android.graphics.RectF
    )

    private data class VerticalGridModel(
        val cells: List<VerticalSubtitleCell>,
        val columnCount: Int,
        val maxRows: Int,
        val cellWidth: Float,
        val cellHeight: Float
    )

    private fun clearGridCache() {
        cachedGridModel = null
        cachedGridHeight = -1
        cachedGridText = ""
        cachedGridTextSize = Float.NaN
        cachedGridTypeface = null
    }

    private fun clearVerticalLayoutCache() {
        cachedVerticalLayout = null
        cachedVerticalLayoutHeight = -1
        cachedVerticalLayoutTypeface = null
    }

    private fun obtainVerticalLayout(targetHeight: Int): VerticalSubtitleLayout? {
        if (content.isBlank() || targetHeight <= 0) return null
        if (cachedVerticalLayout != null &&
            cachedVerticalLayoutHeight == targetHeight &&
            cachedVerticalLayoutTypeface == paint.typeface
        ) {
            return cachedVerticalLayout
        }
        cachedVerticalLayout = VerticalSubtitleLayoutEngine.build(
            content,
            paint,
            targetHeight,
            effectiveCellHeightPx(),
            cellWidthPx = VerticalTextGlyphEngine.estimateCellWidth(paint) * columnSpacingScale
        )
        cachedVerticalLayoutHeight = targetHeight
        cachedVerticalLayoutTypeface = paint.typeface
        return cachedVerticalLayout
    }

    private fun resolveOffsetForEvent(x: Float, y: Float): VerticalTapResolved? {
        val layout = obtainVerticalLayout(height) ?: return null
        val hit = VerticalSubtitleLayoutEngine.hitTest(
            x = x,
            y = y,
            viewWidth = width,
            viewHeight = height,
            layout = layout,
            paint = paint
        ) ?: return null
        val location = IntArray(2)
        getLocationInWindow(location)
        val rectInWindow = android.graphics.RectF(
            location[0] + hit.rect.left,
            location[1] + hit.rect.top,
            location[0] + hit.rect.right,
            location[1] + hit.rect.bottom
        )
        return VerticalTapResolved(
            sourceOffset = hit.sourceOffset,
            logical = hit.logical,
            row = hit.row,
            column = hit.column,
            rectInWindow = rectInWindow
        )
    }

    private fun buildGridModel(viewHeight: Int): VerticalGridModel? {
        if (content.isBlank() || viewHeight <= 0) return null
        if (cachedGridModel != null &&
            cachedGridHeight == viewHeight &&
            cachedGridText == content &&
            cachedGridTextSize == paint.textSize &&
            cachedGridTypeface == paint.typeface
        ) {
            return cachedGridModel
        }

        val layout = obtainVerticalLayout(viewHeight) ?: return null
        val computed = VerticalGridModel(
            cells = layout.cells,
            columnCount = layout.columnCount,
            maxRows = layout.maxRows,
            cellWidth = layout.cellWidth,
            cellHeight = layout.cellHeight
        )

        cachedGridModel = computed
        cachedGridHeight = viewHeight
        cachedGridText = content
        cachedGridTextSize = paint.textSize
        cachedGridTypeface = paint.typeface
        return computed
    }

    private fun drawSelectionBackground(canvas: android.graphics.Canvas) {
        val range = selectedSourceRange ?: return
        val model = buildGridModel(height) ?: return
        if (model.cells.isEmpty()) return
        val highlightPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(0x66, 0xA0, 0xA0, 0xA0)
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
        }
        val start = range.first.coerceAtMost(range.last)
        val end = range.last.coerceAtLeast(range.first)
        val selectedCellsForDebug = ArrayList<String>(8)
        val selectedRectsInWindow = ArrayList<Rect>(8)
        val layout = obtainVerticalLayout(height) ?: return
        val selectionRects = VerticalSubtitleLayoutEngine.selectionRects(start..end, width, height, layout, paint)
        val location = IntArray(2)
        getLocationInWindow(location)
        for (cell in model.cells) {
            val sourceIndex = cell.sourceOffset
            if (sourceIndex < start || sourceIndex > end) continue
            if (selectedCellsForDebug.size < 24) {
                val ch = content.getOrNull(sourceIndex)?.toString().orEmpty()
                selectedCellsForDebug.add(
                    "s=$sourceIndex('$ch')->L${cell.logical}(r${cell.row},c${cell.column})"
                )
            }
        }

        selectionRects.forEach { rect ->
            canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, highlightPaint)
            if (selectedRectsInWindow.size < 128) {
                selectedRectsInWindow.add(
                    Rect(
                        left = location[0] + rect.left,
                        top = location[1] + rect.top,
                        right = location[0] + rect.right,
                        bottom = location[1] + rect.bottom
                    )
                )
            }
        }

        publishSelectionAnchor(selectedRectsInWindow, start..end)
        logSelectionDebugIfNeeded(
            range = start..end,
            model = model,
            selectedCells = selectedCellsForDebug
        )
    }

    private fun drawTapDebugOverlay(canvas: android.graphics.Canvas) {
        val model = buildGridModel(height) ?: return
        if (model.cells.isEmpty()) return

        val gridPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(70, 40, 120, 220)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1f
            isAntiAlias = true
        }
        val tapPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(220, 230, 60, 60)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        val labelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(200, 30, 30, 30)
            textSize = (paint.textSize * 0.24f).coerceAtLeast(10f)
            isAntiAlias = true
        }

        for (cell in model.cells) {
            val left = (width - (cell.column + 1) * model.cellWidth).coerceAtLeast(0f)
            val top = (cell.row * model.cellHeight).coerceAtLeast(0f)
            val right = (left + model.cellWidth).coerceAtMost(width.toFloat())
            val bottom = (top + model.cellHeight).coerceAtMost(height.toFloat())
            canvas.drawRect(left, top, right, bottom, gridPaint)
        }

        debugLastTap?.let { tap ->
            val left = (width - (tap.column + 1) * model.cellWidth).coerceAtLeast(0f)
            val top = (tap.row * model.cellHeight).coerceAtLeast(0f)
            val right = (left + model.cellWidth).coerceAtMost(width.toFloat())
            val bottom = (top + model.cellHeight).coerceAtMost(height.toFloat())
            canvas.drawRect(left, top, right, bottom, tapPaint)
            val ch = content.getOrNull(tap.sourceOffset)?.toString().orEmpty()
            val label = "L${tap.logical} S${tap.sourceOffset} '$ch'"
            canvas.drawText(label, 8f, (height - 8f).coerceAtLeast(labelPaint.textSize + 2f), labelPaint)
        }
    }

    private fun drawVerticalLayoutText(canvas: android.graphics.Canvas) {
        val layout = obtainVerticalLayout(height) ?: return
        VerticalSubtitleLayoutEngine.draw(canvas, paint, layout, width, height)
    }

    private fun publishSelectionAnchor(rects: List<Rect>, range: IntRange) {
        val callback = onSelectionAnchorChanged ?: return
        val orderedRects = reorderRectsWithPreferredFirst(rects, preferredAnchorRectInWindow)
        val signature = buildString {
            append(range.first).append('-').append(range.last).append('|').append(orderedRects.size)
            orderedRects.take(6).forEach {
                append('|')
                    .append(it.left.roundToInt()).append(',')
                    .append(it.top.roundToInt()).append(',')
                    .append(it.right.roundToInt()).append(',')
                    .append(it.bottom.roundToInt())
            }
        }
        if (signature == lastSelectionAnchorSignature) return
        lastSelectionAnchorSignature = signature
        callback(if (orderedRects.isEmpty()) null else ReaderLookupAnchor(rects = orderedRects))
    }

    private fun reorderRectsWithPreferredFirst(rects: List<Rect>, preferred: Rect?): List<Rect> {
        if (rects.isEmpty() || preferred == null) return rects
        val bestIndex = rects.indices.maxByOrNull { idx ->
            val rect = rects[idx]
            val overlap = overlapArea(rect, preferred)
            if (overlap > 0f) {
                overlap + 10_000_000f
            } else {
                -centerDistance(rect, preferred)
            }
        } ?: return rects
        if (bestIndex == 0) return rects
        return buildList(rects.size) {
            add(rects[bestIndex])
            rects.forEachIndexed { idx, rect ->
                if (idx != bestIndex) add(rect)
            }
        }
    }

    private fun overlapArea(a: Rect, b: Rect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val w = (right - left).coerceAtLeast(0f)
        val h = (bottom - top).coerceAtLeast(0f)
        return w * h
    }

    private fun centerDistance(a: Rect, b: Rect): Float {
        val ax = (a.left + a.right) * 0.5f
        val ay = (a.top + a.bottom) * 0.5f
        val bx = (b.left + b.right) * 0.5f
        val by = (b.top + b.bottom) * 0.5f
        val dx = ax - bx
        val dy = ay - by
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun effectiveCellHeightPx(): Float {
        return paint.textSize.coerceAtLeast(1f)
    }

    private fun logLayoutMetricsIfNeeded() {
        val model = buildGridModel(height)
        val dynamicRows = model?.maxRows ?: rowsPerColumn.coerceAtLeast(2)
        val dynamicColumns = model?.columnCount ?: 0
        val signature = "${width}x$height|${paint.textSize.roundToInt()}|${paint.typeface?.hashCode() ?: 0}|$rowsPerColumn|$dynamicRows|$dynamicColumns|${content.length}"
        if (signature == lastLayoutMetricsSignature) return
        lastLayoutMetricsSignature = signature
        logDebug(BOOK_LOOKUP_SELECTION_LOG_TAG) {
            "verticalLayoutMetrics view=${width}x$height textSize=${paint.textSize.roundToInt()} hintRows=$rowsPerColumn dynamicRows=$dynamicRows columns=$dynamicColumns contentLen=${content.length}"
        }
    }

    private fun logSelectionDebugIfNeeded(
        range: IntRange,
        model: VerticalGridModel,
        selectedCells: List<String>
    ) {
        val signature = "${range.first}-${range.last}|${model.maxRows}|${model.columnCount}|${content.length}"
        if (signature == lastSelectionDebugSignature) return
        lastSelectionDebugSignature = signature
        val preview = content
            .replace("\n", "↩")
            .let { if (it.length > 120) it.take(120) + "…" else it }
        logDebug(BOOK_LOOKUP_SELECTION_LOG_TAG) {
            "verticalSelectionDebug range=${range.first}..${range.last} rows=${model.maxRows} cols=${model.columnCount} view=${width}x$height cell=${model.cellWidth.roundToInt()}x${model.cellHeight.roundToInt()} mapperLen=${model.cells.size} contentLen=${content.length} preview='$preview' cells=${selectedCells.joinToString(" | ")}"
        }
    }
}

private fun mergeRects(rects: List<Rect>): Rect {
    return Rect(
        left = rects.minOf { it.left },
        top = rects.minOf { it.top },
        right = rects.maxOf { it.right },
        bottom = rects.maxOf { it.bottom }
    )
}

private fun formatRectForLog(rect: Rect?): String {
    if (rect == null) return "null"
    return "${rect.left.roundToInt()},${rect.top.roundToInt()},${rect.right.roundToInt()},${rect.bottom.roundToInt()}"
}

private fun formatRangeForLog(range: IntRange?): String {
    return range?.let { "${it.first}..${it.last}" } ?: "null"
}

private fun ReaderSelectionData.anchorRectForSourceRange(
    startOffset: Int,
    endExclusive: Int,
): ReaderSelectionRect {
    val matchingRects = textRects
        .filter { it.endOffset > startOffset && it.startOffset < endExclusive }
        .map { it.rect }
        .filter { it.width > 0.0 && it.height > 0.0 }
    if (matchingRects.isEmpty()) return rect

    val clickedCenterX = rect.x + rect.width / 2.0
    val clickedCenterY = rect.y + rect.height / 2.0
    val verticalGroups = matchingRects
        .sortedWith(compareBy<ReaderSelectionRect> { it.x }.thenBy { it.y })
        .fold(mutableListOf<MutableList<ReaderSelectionRect>>()) { groups, item ->
            val group = groups.firstOrNull { existing ->
                val first = existing.first()
                abs(first.x - item.x) < 2.0 && abs(first.width - item.width) < 3.0
            }
            if (group != null) {
                group.add(item)
            } else {
                groups.add(mutableListOf(item))
            }
            groups
        }
    val clickedGroup = verticalGroups.firstOrNull { group ->
        group.any { item ->
            clickedCenterX >= item.x - 1.0 &&
                clickedCenterX <= item.x + item.width + 1.0 &&
                clickedCenterY >= item.y - 1.0 &&
                clickedCenterY <= item.y + item.height + 1.0
        }
    } ?: verticalGroups.minByOrNull { group ->
        val first = group.first()
        abs((first.x + first.width / 2.0) - clickedCenterX)
    } ?: return rect
    return clickedGroup.mergeSelectionRects()
}

private fun List<ReaderSelectionRect>.mergeSelectionRects(): ReaderSelectionRect {
    val left = minOf { it.x }
    val top = minOf { it.y }
    val right = maxOf { it.x + it.width }
    val bottom = maxOf { it.y + it.height }
    return ReaderSelectionRect(
        x = left,
        y = top,
        width = right - left,
        height = bottom - top,
    )
}

private fun ReaderLookupAnchor?.boundingRectOrNull(): Rect? {
    val rects = this?.rects?.filter { !it.isEmpty } ?: return null
    if (rects.isEmpty()) return null
    return mergeRects(rects)
}

private fun ReaderLookupAnchor?.toSelectionRects(density: Float): List<ReaderSelectionRect> {
    val densityScale = density.coerceAtLeast(0.1f)
    return this?.rects
        ?.filter { !it.isEmpty }
        ?.map { rect ->
            ReaderSelectionRect(
                x = (rect.left / densityScale).toDouble(),
                y = (rect.top / densityScale).toDouble(),
                width = ((rect.right - rect.left) / densityScale).coerceAtLeast(1f).toDouble(),
                height = ((rect.bottom - rect.top) / densityScale).coerceAtLeast(1f).toDouble()
            )
        }
        .orEmpty()
}

private fun ReaderLookupAnchor?.expandForSelectionText(
    selectionText: String?,
    writingMode: FloatingSubtitleWritingMode? = null
): ReaderLookupAnchor? {
    val anchor = this ?: return null
    if (writingMode != FloatingSubtitleWritingMode.VERTICAL_RTL) return anchor
    val rects = anchor.rects.filter { !it.isEmpty }
    if (rects.size != 1) return anchor
    val text = selectionText?.trim().orEmpty()
    val textLength = text.length.coerceAtMost(12)
    if (textLength <= 1) return anchor
    val rect = rects.first()
    val charHeight = (rect.bottom - rect.top).coerceAtLeast(1f)
    val extra = (textLength - 1).coerceAtLeast(0)
    val verticalExtra = (extra * charHeight * 0.5f).coerceAtLeast(0f)
    val expanded = Rect(
        left = rect.left - 3f,
        top = rect.top - verticalExtra - 4f,
        right = rect.right + 3f,
        bottom = rect.bottom + verticalExtra + 4f
    )
    return ReaderLookupAnchor(rects = listOf(expanded))
}

private val HOSHI_SENTENCE_DELIMITERS = setOf('\u3002', '\uFF01', '\uFF1F', '.', '!', '?', '\n', '\r')
private val HOSHI_TRAILING_SENTENCE_CHARS = setOf(
    '\u3002', '、', '\uFF01', '\uFF1F', '…', '‥',
    '」', '』', '）', ')', '】', '〉', '》', '〕', '｝', '}', '］', ']'
)
private val HOSHI_BRACKET_PAIRS = mapOf(
    '「' to '」',
    '『' to '』',
    '（' to '）',
    '(' to ')',
    '【' to '】',
    '〈' to '〉',
    '《' to '》',
    '〔' to '〕',
    '｛' to '｝',
    '{' to '}',
    '［' to '］',
    '[' to ']'
)
private data class SentenceBounds(val start: Int, val endExclusive: Int)

private fun findSentenceBoundsLikeHoshi(
    text: String,
    anchorIndex: Int
): SentenceBounds {
    if (text.isEmpty()) return SentenceBounds(0, 0)
    val normalizedAnchor = anchorIndex.coerceIn(0, text.lastIndex)
    var start = 0
    for (index in (normalizedAnchor - 1) downTo 0) {
        if (text[index] in HOSHI_SENTENCE_DELIMITERS) {
            start = index + 1
            break
        }
    }

    var endExclusive = text.length
    for (index in normalizedAnchor until text.length) {
        if (text[index] in HOSHI_SENTENCE_DELIMITERS) {
            endExclusive = index + 1
            while (endExclusive < text.length) {
                val next = text[endExclusive]
                if (next.isLetterOrDigit() || Character.getType(next) == Character.OTHER_LETTER.toInt()) {
                    break
                }
                if (next !in HOSHI_TRAILING_SENTENCE_CHARS) {
                    break
                }
                endExclusive += 1
            }
            break
        }
    }
    val rawSentence = text.substring(start, endExclusive)
    val leadingTrim = rawSentence.length - rawSentence.trimStart().length
    val sentence = rawSentence.trim()
    if (sentence.isEmpty()) {
        val emptyStart = start + leadingTrim
        return SentenceBounds(emptyStart, emptyStart)
    }

    val closeBrackets = HOSHI_BRACKET_PAIRS.values.toSet()
    val openBrackets = HOSHI_BRACKET_PAIRS.keys
    val unmatchedOpen = mutableListOf<Char>()
    val unmatchedClose = mutableListOf<Char>()
    sentence.forEach { char ->
        when {
            char in openBrackets -> unmatchedOpen += char
            char in closeBrackets -> {
                if (unmatchedOpen.isNotEmpty() && HOSHI_BRACKET_PAIRS[unmatchedOpen.last()] == char) {
                    unmatchedOpen.removeAt(unmatchedOpen.lastIndex)
                } else {
                    unmatchedClose += char
                }
            }
        }
    }

    var startSlice = 0
    while (unmatchedOpen.isNotEmpty() && startSlice < sentence.length - 1) {
        if (unmatchedOpen.first() != sentence[startSlice]) break
        unmatchedOpen.removeAt(0)
        startSlice += 1
    }

    var endSlice = sentence.length - 1
    var endIndex = sentence.length - 1
    while (unmatchedClose.isNotEmpty() && endIndex > startSlice) {
        if (unmatchedClose.last() == sentence[endIndex]) {
            unmatchedClose.removeAt(unmatchedClose.lastIndex)
            endSlice = endIndex - 1
        } else if (sentence[endIndex] !in HOSHI_SENTENCE_DELIMITERS) {
            break
        }
        endIndex -= 1
    }

    val sentenceStart = start + leadingTrim + startSlice
    val sentenceEnd = start + leadingTrim + (endSlice + 1).coerceAtLeast(startSlice)
    return SentenceBounds(sentenceStart, sentenceEnd)
}

private fun extractFullSentenceLikeHoshiFromCues(
    cues: List<ReaderSubtitleCue>,
    cueIndex: Int,
    anchorText: String?,
    selectedRangeInCue: IntRange?,
    rawAnchorOffsetInCue: Int?
): ReaderSentenceSelection {
    if (cues.isEmpty() || cueIndex !in cues.indices) return ReaderSentenceSelection("", 0..0)
    val cueStarts = IntArray(cues.size)
    val combined = buildString {
        cues.forEachIndexed { index, cue ->
            cueStarts[index] = length
            append(cue.text)
        }
    }
    if (combined.isBlank()) return ReaderSentenceSelection("", cueIndex..cueIndex)

    val localCueText = cues[cueIndex].text
    val localAnchor = anchorText?.trim().orEmpty()
    val localAnchorIndex = when {
        rawAnchorOffsetInCue != null -> rawAnchorOffsetInCue.coerceIn(0, localCueText.lastIndex.coerceAtLeast(0))
        selectedRangeInCue != null -> selectedRangeInCue.first.coerceIn(0, localCueText.lastIndex.coerceAtLeast(0))
        localAnchor.isNotBlank() -> localCueText.indexOf(localAnchor).takeIf { it >= 0 } ?: 0
        else -> 0
    }
    val globalAnchor = (cueStarts[cueIndex] + localAnchorIndex).coerceIn(0, combined.lastIndex)
    val bounds = findSentenceBoundsLikeHoshi(combined, globalAnchor)
    val sentenceText = combined.substring(bounds.start, bounds.endExclusive).trim()
    val leadingTrim = combined.substring(bounds.start, bounds.endExclusive).indexOf(sentenceText).takeIf { it >= 0 } ?: 0
    val sentenceStart = bounds.start + leadingTrim
    val sentenceEndExclusive = sentenceStart + sentenceText.length
    var firstCue = cueIndex
    var lastCue = cueIndex
    for (index in cues.indices) {
        val cueStart = cueStarts[index]
        val cueEndExclusive = cueStart + cues[index].text.length
        if (cueEndExclusive > sentenceStart) {
            firstCue = index
            break
        }
    }
    for (index in cues.indices.reversed()) {
        val cueStart = cueStarts[index]
        if (cueStart < sentenceEndExclusive) {
            lastCue = index
            break
        }
    }
    return ReaderSentenceSelection(
        text = sentenceText,
        cueRange = firstCue..lastCue
    )
}

internal fun createHoshiReaderSelectionFromCueTap(
    cueText: String,
    cueIndex: Int,
    cues: List<ReaderSubtitleCue>,
    offset: Int,
    anchorRect: Rect,
    density: Float = 1f
): ReaderSelectionData {
    val safeOffset = offset.coerceIn(0, cueText.lastIndex.coerceAtLeast(0))
    val scanSelection = selectLookupScanText(
        text = cueText,
        charOffset = safeOffset,
        maxLength = HOSHI_LOOKUP_SCAN_MAX_LENGTH
    )
    val scanStart = scanSelection?.range?.first ?: safeOffset
    val scanEnd = scanSelection?.range?.last?.plus(1) ?: (scanStart + HOSHI_LOOKUP_SCAN_MAX_LENGTH).coerceAtMost(cueText.length)
    val densityScale = density.coerceAtLeast(0.1f)
    val selectedText = cueText
        .substring(scanStart, scanEnd)
        .trim()
        .ifBlank { cueText.trim() }
    val sentenceSelection = extractFullSentenceLikeHoshiFromCues(
        cues = cues,
        cueIndex = cueIndex.coerceIn(0, cues.lastIndex.coerceAtLeast(0)),
        anchorText = null,
        selectedRangeInCue = null,
        rawAnchorOffsetInCue = safeOffset
    )
    return ReaderSelectionData(
        text = selectedText,
        sentence = sentenceSelection.text.ifBlank { cueText.trim() },
        rect = ReaderSelectionRect(
            x = (anchorRect.left / densityScale).toDouble(),
            y = (anchorRect.top / densityScale).toDouble(),
            width = ((anchorRect.right - anchorRect.left) / densityScale).coerceAtLeast(1f).toDouble(),
            height = ((anchorRect.bottom - anchorRect.top) / densityScale).coerceAtLeast(1f).toDouble()
        ),
        normalizedOffset = 0,
        sentenceOffset = scanSelection?.range?.first ?: scanStart
    )
}

private const val BOOK_READER_SRT_CACHE_DIR = "book_reader_srt_cache"
private const val BOOK_READER_SRT_CACHE_MAX_FILES = 120
private const val BOOK_READER_SRT_CACHE_MAX_AGE_MS = 14L * 24L * 60L * 60L * 1000L

private fun parseBookSrtWithCache(
    context: Context,
    contentResolver: ContentResolver,
    uri: Uri
): List<ReaderSubtitleCue> {
    val cacheDir = File(context.cacheDir, BOOK_READER_SRT_CACHE_DIR)
    if (!cacheDir.exists()) {
        cacheDir.mkdirs()
    }
    val cacheKey = buildDictionaryCacheKey(uri.toString(), "srt")
    val cacheFile = File(cacheDir, "$cacheKey.cache")
    val sourceStamp = buildSrtSourceStamp(contentResolver, uri)

    readBookSrtCache(cacheFile, sourceStamp)?.let { cached ->
        cacheFile.setLastModified(System.currentTimeMillis())
        return cached
    }

            val parsed = parseBookSrt(context, contentResolver, uri)
    writeBookSrtCache(cacheFile, sourceStamp, parsed)
    return parsed
}

private fun buildSrtSourceStamp(contentResolver: ContentResolver, uri: Uri): String {
    val projection = arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    return runCatching {
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use "size=-1|modified=-1"
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
            val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else -1L
            "size=$size|modified=$modified"
        } ?: "size=-1|modified=-1"
    }.getOrDefault("size=-1|modified=-1")
}

private fun readBookSrtCache(cacheFile: File, expectedStamp: String): List<ReaderSubtitleCue>? {
    if (!cacheFile.exists() || !cacheFile.isFile) return null
    return runCatching {
        cacheFile.bufferedReader(Charsets.UTF_8).use { reader ->
            val header = reader.readLine() ?: return@use null
            if (!header.startsWith("#stamp\t")) return@use null
            val stamp = header.substringAfter('\t')
            if (stamp != expectedStamp) return@use null

            val cues = mutableListOf<ReaderSubtitleCue>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val parts = line.split('\t', limit = 3)
                if (parts.size < 3) return@use null
                val start = parts[0].toLongOrNull() ?: return@use null
                val end = parts[1].toLongOrNull() ?: return@use null
                val text = runCatching {
                    String(Base64.decode(parts[2], Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull() ?: return@use null
                cues += ReaderSubtitleCue(startMs = start, endMs = end, text = text)
            }
            cues.sortedBy { it.startMs }
        }
    }.getOrNull()
}

private fun writeBookSrtCache(cacheFile: File, sourceStamp: String, cues: List<ReaderSubtitleCue>) {
    val parent = cacheFile.parentFile
    if (parent != null && !parent.exists()) {
        parent.mkdirs()
    }
    val tempFile = File(parent, "${cacheFile.name}.tmp")
    runCatching {
        tempFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.append("#stamp\t").append(sourceStamp).append('\n')
            cues.forEach { cue ->
                val encoded = Base64.encodeToString(cue.text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                writer.append(cue.startMs.toString())
                    .append('\t')
                    .append(cue.endMs.toString())
                    .append('\t')
                    .append(encoded)
                    .append('\n')
            }
        }
        if (cacheFile.exists()) {
            cacheFile.delete()
        }
        tempFile.renameTo(cacheFile)
        cacheFile.setLastModified(System.currentTimeMillis())
    }.onFailure {
        tempFile.delete()
    }
}

private fun cleanupBookReaderSrtCache(context: Context) {
    val cacheDir = File(context.cacheDir, BOOK_READER_SRT_CACHE_DIR)
    if (!cacheDir.exists() || !cacheDir.isDirectory) return
    val files = cacheDir.listFiles()?.filter { it.isFile }?.toMutableList() ?: return
    if (files.isEmpty()) return

    val now = System.currentTimeMillis()
    files.forEach { file ->
        val ageMs = now - file.lastModified()
        if (ageMs > BOOK_READER_SRT_CACHE_MAX_AGE_MS) {
            file.delete()
        }
    }

    val remaining = cacheDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
    if (remaining.size <= BOOK_READER_SRT_CACHE_MAX_FILES) return
    remaining.drop(BOOK_READER_SRT_CACHE_MAX_FILES).forEach { it.delete() }
}

private fun parseBookSrt(context: Context, contentResolver: ContentResolver, uri: Uri): List<ReaderSubtitleCue> {
    val cues = mutableListOf<ReaderSubtitleCue>()
    val blockLines = mutableListOf<String>()

    openReaderInputStream(contentResolver, uri)?.use { input ->
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            var isFirstLine = true
            while (true) {
                val rawLine = reader.readLine() ?: break
                val line = if (isFirstLine) {
                    isFirstLine = false
                    rawLine.removePrefix("\uFEFF")
                } else {
                    rawLine
                }
                if (line.isBlank()) {
                    appendParsedSrtBlock(blockLines, cues)
                    blockLines.clear()
                } else {
                    blockLines += line.trimEnd()
                }
            }
        }
    } ?: error(context.getString(R.string.error_srt_unreadable))

    appendParsedSrtBlock(blockLines, cues)

    if (cues.isEmpty()) error(context.getString(R.string.error_srt_no_valid_cues))
    return cues.sortedBy { it.startMs }
}

private fun appendParsedSrtBlock(
    blockLines: List<String>,
    out: MutableList<ReaderSubtitleCue>
) {
    val lines = blockLines.filter { it.isNotBlank() }
    if (lines.isEmpty()) return

    val timingLineIndex = if (lines.first().all { it.isDigit() } && lines.size >= 2) 1 else 0
    val timingLine = lines.getOrNull(timingLineIndex) ?: return
    if (!timingLine.contains("-->")) return

    val parts = timingLine.split("-->")
    if (parts.size < 2) return

    val start = parseBookSrtTimestamp(parts[0].trim()) ?: return
    val endToken = parts[1].trim().substringBefore(' ')
    val end = parseBookSrtTimestamp(endToken) ?: return

    val cueTextRaw = lines.drop(timingLineIndex + 1).joinToString("\n").trim()
    val cueText = Html.fromHtml(cueTextRaw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    if (cueText.isBlank()) return

    out += ReaderSubtitleCue(startMs = start, endMs = end, text = cueText)
}

internal inline fun <T> withAnkiStep(step: String, block: () -> T): T {
    return try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        val message = error.message?.trim().orEmpty()
        if (error is IllegalStateException && message.startsWith("Anki step failed [")) {
            throw error
        }
        val detail = if (message.isBlank()) {
            error.javaClass.simpleName
        } else {
            "${error.javaClass.simpleName}: $message"
        }
        throw IllegalStateException("Anki step failed [$step]. $detail", error)
    }
}

private fun openReaderInputStream(contentResolver: ContentResolver, uri: Uri): InputStream? {
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme == "file") {
        return runCatching {
            val path = uri.path ?: return@runCatching null
            File(path).inputStream()
        }.getOrNull()
    }
    val direct = runCatching { contentResolver.openInputStream(uri) }.getOrNull()
    if (direct != null) return direct
    val pfd = runCatching { contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
    return ParcelFileDescriptor.AutoCloseInputStream(pfd)
}

private fun parseBookSrtTimestamp(raw: String): Long? {
    val normalized = raw.trim().replace(',', '.')
    val parts = normalized.split(':')
    if (parts.size != 3) return null

    val hour = parts[0].toLongOrNull() ?: return null
    val minute = parts[1].toLongOrNull() ?: return null

    val secParts = parts[2].split('.')
    if (secParts.isEmpty()) return null

    val second = secParts[0].toLongOrNull() ?: return null
    val millisecondPart = secParts.getOrNull(1) ?: "0"
    val millisecond = millisecondPart.padEnd(3, '0').take(3).toLongOrNull() ?: return null

    return (((hour * 60 + minute) * 60) + second) * 1000 + millisecond
}

private fun findBookCueIndexAtTime(cues: List<ReaderSubtitleCue>, timeMs: Long): Int {
    if (cues.isEmpty()) return -1
    var low = 0
    var high = cues.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        val cue = cues[mid]
        when {
            timeMs < cue.startMs -> {
                high = mid - 1
            }
            timeMs >= cue.endMs -> {
                low = mid + 1
            }
            else -> {
                return mid
            }
        }
    }
    return -1
}

private fun findBookDisplayCueIndexAtTime(cues: List<ReaderSubtitleCue>, timeMs: Long): Int {
    val current = findBookCueIndexAtTime(cues, timeMs)
    if (current >= 0) return current
    return findCueIndexAtOrBeforeTime(cues, timeMs)
}

private fun findCondensedPlaybackSeekTarget(
    cues: List<ReaderSubtitleCue>,
    previousCueIndex: Int,
    currentCueIndex: Int,
    timeMs: Long
): Long? {
    if (cues.isEmpty()) return null
    if (previousCueIndex !in cues.indices || currentCueIndex >= 0) return null
    val nextIndex = previousCueIndex + 1
    if (nextIndex !in cues.indices) return null
    val target = cues[nextIndex].startMs.coerceAtLeast(0L)
    return if (abs(timeMs - target) > 2_000L) target else null
}

private fun findCueIndexAtOrBeforeTime(cues: List<ReaderSubtitleCue>, timeMs: Long): Int {
    if (cues.isEmpty()) return -1
    var low = 0
    var high = cues.lastIndex
    var candidate = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val cue = cues[mid]
        if (cue.startMs <= timeMs) {
            candidate = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return candidate
}

private fun findCueIndexAtOrAfterTime(cues: List<ReaderSubtitleCue>, timeMs: Long): Int {
    if (cues.isEmpty()) return -1
    var low = 0
    var high = cues.lastIndex
    var candidate = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val cue = cues[mid]
        if (cue.startMs >= timeMs) {
            candidate = mid
            high = mid - 1
        } else {
            low = mid + 1
        }
    }
    return candidate
}

internal data class BookReaderSleepOptions(
    val exitControlModeWhenDone: Boolean,
    val disconnectBluetoothWhenDone: Boolean,
    val fadeOutAudioWhenDone: Boolean,
    val defaultTimerMinutes: Int
)

internal fun sleepFadeVolume(remainingMs: Long, fadeOutMs: Long = BOOK_READER_SLEEP_FADE_OUT_MS): Float {
    val percentage = (remainingMs.toDouble() / fadeOutMs.toDouble()).coerceIn(0.0, 1.0)
    val progress = 1.0 - percentage
    val eased = progress * progress * (3.0 - 2.0 * progress)
    return (1.0 - eased).toFloat().coerceIn(0f, 1f)
}

internal fun pauseWithSleepFadeRewind(player: Player, fadeOutMs: Long = BOOK_READER_SLEEP_FADE_OUT_MS) {
    player.volume = 1f
    player.pause()
    val target = (player.currentPosition.coerceAtLeast(0L) - fadeOutMs).coerceAtLeast(0L)
    player.seekTo(target)
}

internal fun loadBookReaderSleepOptions(context: Context): BookReaderSleepOptions {
    val prefs = context.getSharedPreferences(BOOK_READER_SLEEP_OPTIONS_PREFS, Context.MODE_PRIVATE)
    return BookReaderSleepOptions(
        exitControlModeWhenDone = prefs.getBoolean(BOOK_READER_SLEEP_EXIT_CONTROL_KEY, false),
        disconnectBluetoothWhenDone = prefs.getBoolean(BOOK_READER_SLEEP_DISCONNECT_BT_KEY, false),
        fadeOutAudioWhenDone = prefs.getBoolean(BOOK_READER_SLEEP_FADE_OUT_KEY, false),
        defaultTimerMinutes = prefs.getInt(BOOK_READER_SLEEP_DEFAULT_TIMER_MINUTES_KEY, 0)
    )
}

internal fun saveBookReaderSleepOptions(
    context: Context,
    exitControlModeWhenDone: Boolean,
    disconnectBluetoothWhenDone: Boolean,
    fadeOutAudioWhenDone: Boolean,
    defaultTimerMinutes: Int
) {
    context.getSharedPreferences(BOOK_READER_SLEEP_OPTIONS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(BOOK_READER_SLEEP_EXIT_CONTROL_KEY, exitControlModeWhenDone)
        .putBoolean(BOOK_READER_SLEEP_DISCONNECT_BT_KEY, disconnectBluetoothWhenDone)
        .putBoolean(BOOK_READER_SLEEP_FADE_OUT_KEY, fadeOutAudioWhenDone)
        .putInt(BOOK_READER_SLEEP_DEFAULT_TIMER_MINUTES_KEY, defaultTimerMinutes.coerceAtLeast(0))
        .apply()
}

private fun cueCollectionKey(startMs: Long, endMs: Long, text: String): String {
    return "$startMs|$endMs|${text.trim()}"
}

private data class CollectedCueChapterMeta(
    val chapterIndex: Int,
    val chapterTitle: String?,
    val chapterStartMs: Long,
    val startOffsetMs: Long,
    val endOffsetMs: Long
)

private fun buildCollectedCueChapterMeta(
    chapters: List<ReaderAudioChapter>,
    cueStartMs: Long,
    cueEndMs: Long
): CollectedCueChapterMeta? {
    val index = findBookChapterIndexAtTime(chapters, cueStartMs)
    if (index !in chapters.indices) return null
    val chapterStartMs = chapters[index].startMs.coerceAtLeast(0L)
    val startOffset = (cueStartMs - chapterStartMs).coerceAtLeast(0L)
    val endOffset = (cueEndMs - chapterStartMs).coerceAtLeast(startOffset)
    return CollectedCueChapterMeta(
        chapterIndex = index,
        chapterTitle = chapters[index].title.takeIf { it.isNotBlank() },
        chapterStartMs = chapterStartMs,
        startOffsetMs = startOffset,
        endOffsetMs = endOffset
    )
}

private fun buildBookReaderNotificationPendingIntent(
    context: Context,
    title: String,
    audioUri: Uri?,
    srtUri: Uri?,
    coverUri: Uri?
): PendingIntent {
    val intent = Intent(context, BookReaderActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(BookReaderActivity.EXTRA_BOOK_TITLE, title)
        putExtra(BookReaderActivity.EXTRA_AUDIO_URI, audioUri?.toString())
        putExtra(BookReaderActivity.EXTRA_SRT_URI, srtUri?.toString())
        putExtra(BookReaderActivity.EXTRA_COVER_URI, coverUri?.toString())
    }
    val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getActivity(
        context,
        BOOK_READER_PENDING_INTENT_REQUEST_CODE,
        intent,
        pendingIntentFlags
    )
}

private fun normalizeBookReaderPlaybackPosition(positionMs: Long, durationMs: Long): Long {
    return positionMs.coerceAtLeast(0L)
}

private fun swapSrtDocumentContents(
    resolver: ContentResolver,
    currentSrtUri: Uri,
    pickedSrtUri: Uri
): Boolean {
    return runCatching {
        val currentBytes = resolver.openInputStream(currentSrtUri)?.use { it.readBytes() }
            ?: error("read current srt failed")
        val pickedBytes = resolver.openInputStream(pickedSrtUri)?.use { it.readBytes() }
            ?: error("read picked srt failed")

        resolver.openOutputStream(currentSrtUri, "wt")?.use { it.write(pickedBytes) }
            ?: error("write current srt failed")
        resolver.openOutputStream(pickedSrtUri, "wt")?.use { it.write(currentBytes) }
            ?: error("write picked srt failed")
        true
    }.getOrElse {
        Log.e("BookReaderSrtReplace", "swap srt failed", it)
        false
    }
}

private fun persistReplacedSrtToImportState(
    context: Context,
    resolver: ContentResolver,
    audioUri: Uri?,
    targetSrtUri: Uri?
) {
    val audioKey = audioUri?.toString()?.trim().orEmpty()
    val srtKey = targetSrtUri?.toString()?.trim().orEmpty()
    if (audioKey.isBlank() || srtKey.isBlank()) return

    val state = loadPersistedImports(context)
    val srtName = runCatching { queryDisplayName(resolver, targetSrtUri!!) }.getOrNull()

    var matched = false
    val updatedBooks = state.books.map { book ->
        if (book.audioUri == audioKey) {
            matched = true
            book.copy(
                srtUri = srtKey,
                srtName = srtName ?: book.srtName
            )
        } else {
            book
        }
    }
    val updatedState = state.copy(
        srtUri = if (state.audioUri == audioKey) srtKey else state.srtUri,
        srtName = if (state.audioUri == audioKey) (srtName ?: state.srtName) else state.srtName,
        books = updatedBooks
    )
    savePersistedImports(context, updatedState)
}

private fun movePickedSrtToBookFolder(
    context: Context,
    resolver: ContentResolver,
    pickedSrtUri: Uri,
    title: String,
    audioUri: Uri?
): Uri? {
    return runCatching {
        val sourceName = queryDisplayName(resolver, pickedSrtUri)
            .trim()
            .ifBlank { "${title.ifBlank { "book" }}.srt" }
        val normalizedSourceName = if (sourceName.lowercase(Locale.ROOT).endsWith(".srt")) {
            sourceName
        } else {
            "$sourceName.srt"
        }

        val targetUri = when (audioUri?.scheme?.lowercase(Locale.ROOT)) {
            "file" -> {
                val audioPath = audioUri.path ?: return@runCatching null
                val parent = File(audioPath).parentFile ?: return@runCatching null
                if (!parent.exists()) return@runCatching null
                val targetFile = resolveUniqueFileName(parent, normalizedSourceName)
                openReaderInputStream(resolver, pickedSrtUri)?.use { src ->
                    targetFile.outputStream().use { out ->
                        src.copyTo(out)
                        out.flush()
                    }
                } ?: return@runCatching null
                Uri.fromFile(targetFile)
            }
            "content" -> {
                val parentFolder = resolveAudioParentFolder(context, audioUri) ?: return@runCatching null
                val targetName = resolveUniqueDocumentNameLocal(parentFolder, normalizedSourceName)
                val created = parentFolder.createFile("application/x-subrip", targetName)
                    ?: return@runCatching null
                openReaderInputStream(resolver, pickedSrtUri)?.use { src ->
                    resolver.openOutputStream(created.uri, "w")?.use { out ->
                        src.copyTo(out)
                        out.flush()
                    } ?: return@runCatching null
                } ?: return@runCatching null
                created.uri
            }
            else -> return@runCatching null
        }
        if (targetUri.toString() != pickedSrtUri.toString() && !deleteSourceSrtUri(context, resolver, pickedSrtUri)) {
            Log.w("BookReaderSrtReplace", "move source delete failed uri=$pickedSrtUri")
        }
        targetUri
    }.getOrNull()
}

private fun resolveAudioParentFolder(context: Context, audioUri: Uri): DocumentFile? {
    return runCatching {
        val docId = DocumentsContract.getDocumentId(audioUri)
        val parentDocId = docId.substringBeforeLast('/', "")
        if (parentDocId.isBlank() || parentDocId == docId) return@runCatching null
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(audioUri, parentDocId)
        DocumentFile.fromTreeUri(context, parentDocUri)
            ?: DocumentFile.fromSingleUri(context, parentDocUri)
    }.getOrNull()
}

private fun resolveUniqueDocumentNameLocal(folder: DocumentFile, originalName: String): String {
    val cleaned = originalName.trim().ifBlank { "subtitle.srt" }
    if (folder.findFile(cleaned) == null) return cleaned

    val dot = cleaned.lastIndexOf('.')
    val hasExtension = dot > 0 && dot < cleaned.lastIndex
    val base = if (hasExtension) cleaned.substring(0, dot) else cleaned
    val ext = if (hasExtension) cleaned.substring(dot) else ""
    var index = 2
    while (index <= 9999) {
        val candidate = "$base ($index)$ext"
        if (folder.findFile(candidate) == null) return candidate
        index += 1
    }
    return "$base-${System.currentTimeMillis()}$ext"
}

private fun resolveUniqueFileName(parent: File, originalName: String): File {
    val cleaned = originalName.trim().ifBlank { "subtitle.srt" }
    val first = File(parent, cleaned)
    if (!first.exists()) return first

    val dot = cleaned.lastIndexOf('.')
    val hasExtension = dot > 0 && dot < cleaned.lastIndex
    val base = if (hasExtension) cleaned.substring(0, dot) else cleaned
    val ext = if (hasExtension) cleaned.substring(dot) else ""
    var index = 2
    while (index <= 9999) {
        val candidate = File(parent, "$base ($index)$ext")
        if (!candidate.exists()) return candidate
        index += 1
    }
    return File(parent, "$base-${System.currentTimeMillis()}$ext")
}

private fun deleteSourceSrtUri(
    context: Context,
    resolver: ContentResolver,
    uri: Uri
): Boolean {
    if (uri.scheme.equals("file", ignoreCase = true)) {
        val path = uri.path ?: return false
        return runCatching { File(path).delete() }.getOrDefault(false)
    }
    val documentDeleted = runCatching {
        DocumentFile.fromSingleUri(context, uri)?.delete()
    }.getOrNull()
    if (documentDeleted == true) return true
    return runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
}

private fun findBookChapterIndexAtTime(chapters: List<ReaderAudioChapter>, timeMs: Long): Int {
    if (chapters.isEmpty()) return -1
    var low = 0
    var high = chapters.lastIndex
    var candidate = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val chapter = chapters[mid]
        if (chapter.startMs <= timeMs) {
            candidate = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return candidate
}

private fun parseEditableTimeInputToMillis(raw: String): Long? {
    val normalized = raw.trim()
    if (normalized.isBlank()) return null
    val parts = normalized.split(':').map { it.trim() }
    if (parts.isEmpty() || parts.any { it.isBlank() || !it.all(Char::isDigit) }) return null
    val values = parts.mapNotNull { it.toLongOrNull() }
    if (values.size != parts.size) return null
    val seconds = when (values.size) {
        1 -> values[0]
        2 -> {
            val (mm, ss) = values
            if (ss >= 60L) return null
            mm * 60L + ss
        }
        3 -> {
            val hh = values[0]
            val mm = values[1]
            val ss = values[2]
            if (mm >= 60L || ss >= 60L) return null
            hh * 3600L + mm * 60L + ss
        }
        else -> return null
    }
    return seconds.coerceAtLeast(0L) * 1000L
}

@Composable
private fun BookReaderCoverImage(
    coverUri: Uri,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = true
            }
        },
        update = { imageView ->
            imageView.setImageURI(coverUri)
        }
    )
}

private fun applyControlModeScreenBrightness(context: Context, dimToMinimum: Boolean): () -> Unit {
    val activity = context.findHostActivity() ?: return {}
    val window = activity.window ?: return {}

    if (Settings.System.canWrite(context)) {
        val resolver = context.contentResolver
        val previousMode = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        }.getOrNull()
        val previousBrightness = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()

        if (dimToMinimum) {
            runCatching {
                Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    1
                )
            }
        }

        return {
            previousMode?.let {
                runCatching {
                    Settings.System.putInt(
                        resolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        it
                    )
                }
            }
            previousBrightness?.let {
                runCatching {
                    Settings.System.putInt(
                        resolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        it
                    )
                }
            }
            val attrs = window.attributes
            if (attrs.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = attrs
            }
        }
    }

    val attrs = window.attributes
    val previousBrightness = attrs.screenBrightness
    val targetBrightness = if (dimToMinimum) {
        0.01f
    } else {
        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
    if (attrs.screenBrightness != targetBrightness) {
        attrs.screenBrightness = targetBrightness
        window.attributes = attrs
    }
    return {
        val restoreAttrs = window.attributes
        if (restoreAttrs.screenBrightness != previousBrightness) {
            restoreAttrs.screenBrightness = previousBrightness
            window.attributes = restoreAttrs
        }
    }
}

private tailrec fun Context.findHostActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findHostActivity()
        else -> null
    }
}

private fun loadUiTestLayoutModeHorizontal(context: Context): Int {
    return context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .getInt(BOOK_READER_UI_TEST_LAYOUT_HORIZONTAL_KEY, 1)
        .coerceIn(1, 2)
}

private fun loadUiTestLayoutModeVertical(context: Context): Int {
    return context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .getInt(BOOK_READER_UI_TEST_LAYOUT_VERTICAL_KEY, 2)
        .coerceIn(1, 2)
}

private fun saveUiTestLayoutModeHorizontal(context: Context, mode: Int) {
    context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putInt(BOOK_READER_UI_TEST_LAYOUT_HORIZONTAL_KEY, mode.coerceIn(1, 2))
        .apply()
}

private fun saveUiTestLayoutModeVertical(context: Context, mode: Int) {
    context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putInt(BOOK_READER_UI_TEST_LAYOUT_VERTICAL_KEY, mode.coerceIn(1, 2))
        .apply()
}

private fun loadUiSwapPrevNextHorizontal(context: Context): Boolean {
    return context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .getBoolean(BOOK_READER_UI_SWAP_PREV_NEXT_HORIZONTAL_KEY, false)
}

private fun saveUiSwapPrevNextHorizontal(context: Context, enabled: Boolean) {
    context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(BOOK_READER_UI_SWAP_PREV_NEXT_HORIZONTAL_KEY, enabled)
        .apply()
}

private fun loadUiSwapPrevNextVertical(context: Context): Boolean {
    return context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .getBoolean(BOOK_READER_UI_SWAP_PREV_NEXT_VERTICAL_KEY, false)
}

private fun saveUiSwapPrevNextVertical(context: Context, enabled: Boolean) {
    context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(BOOK_READER_UI_SWAP_PREV_NEXT_VERTICAL_KEY, enabled)
        .apply()
}

private fun loadUiChapterVisible(context: Context): Boolean {
    return context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .getBoolean(BOOK_READER_UI_CHAPTER_VISIBLE_KEY, true)
}

private fun saveUiChapterVisible(context: Context, visible: Boolean) {
    context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(BOOK_READER_UI_CHAPTER_VISIBLE_KEY, visible)
        .apply()
}
