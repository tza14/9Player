package moe.tekuza.m9player

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.kyant.taglib.TagLib
import moe.tekuza.m9player.ui.theme.TsetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupHtml
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupItem
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupAssets
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupOptions
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupStackView
import moe.tekuza.m9player.hoshi.features.dictionary.PopupWebViewCallbacks
import moe.tekuza.m9player.hoshi.features.dictionary.loadDictionarySettings
import moe.tekuza.m9player.hoshi.features.dictionary.openPopupExternalLink
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionData
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionRect
import de.manhhao.hoshi.LookupResult
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private var floatingOverlayStartJob: Job? = null
    private var autoUpdateCheckJob: Job? = null
    internal var launchUpdatePromptRelease by mutableStateOf<AppUpdateRelease?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedAppLanguage(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TsetTheme {
                ReaderSyncScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        floatingOverlayStartJob?.cancel()
        floatingOverlayStartJob = null
        val settings = loadAudiobookSettingsConfig(this)
        val keepOverlay =
            (settings.floatingOverlayEnabled || settings.floatingOverlaySubtitleEnabled) &&
                BookReaderFloatingBridge.currentAudioUri() != null &&
                BookReaderFloatingBridge.isPlaying()
        logDebug(FLOATING_OVERLAY_EXIT_LOG_TAG) {
            "Main onStart keepOverlay=$keepOverlay audio=${BookReaderFloatingBridge.currentAudioUri() != null} " +
            "playing=${BookReaderFloatingBridge.isPlaying()}"
        }
        if (!keepOverlay) {
            stopAudiobookFloatingOverlayService(this)
        }
    }

    override fun onStop() {
        super.onStop()
        val settings = loadAudiobookSettingsConfig(this)
        floatingOverlayStartJob?.cancel()
        val overlayEnabled = settings.floatingOverlayEnabled || settings.floatingOverlaySubtitleEnabled
        if (isChangingConfigurations || !overlayEnabled || !BookReaderFloatingBridge.isPlaying()) return

        floatingOverlayStartJob = lifecycleScope.launch {
            delay(150L)
            if (
                !isAppProcessInForeground() &&
                run {
                    val refreshed = loadAudiobookSettingsConfig(this@MainActivity)
                    refreshed.floatingOverlayEnabled || refreshed.floatingOverlaySubtitleEnabled
                } &&
                BookReaderFloatingBridge.isPlaying()
            ) {
                startAudiobookFloatingOverlayService(this@MainActivity)
            }
        }
    }

    override fun onDestroy() {
        floatingOverlayStartJob?.cancel()
        floatingOverlayStartJob = null
        autoUpdateCheckJob?.cancel()
        autoUpdateCheckJob = null
        super.onDestroy()
    }

    fun checkAppUpdateInBackground(force: Boolean) {
        val config = loadAppUpdateConfig(this)
        val checkIntervalMs = 12L * 60L * 60L * 1000L
        if (!force && System.currentTimeMillis() - config.lastCheckedAtMs < checkIntervalMs) return
        autoUpdateCheckJob?.cancel()
        autoUpdateCheckJob = lifecycleScope.launch {
            val result = checkLatestAppUpdate(this@MainActivity)
            saveAppUpdateCheckedAt(this@MainActivity, System.currentTimeMillis())
            val release = (result as? AppUpdateCheckResult.UpdateAvailable)?.release ?: return@launch
            if (isFinishing || isDestroyed) return@launch
            launchUpdatePromptRelease = release
        }
    }

    internal fun dismissLaunchUpdatePrompt() {
        launchUpdatePromptRelease = null
    }

    internal fun downloadLaunchUpdatePrompt(release: AppUpdateRelease) {
        launchUpdatePromptRelease = null
        downloadUpdateFromLaunchPrompt(release)
    }

    private fun downloadUpdateFromLaunchPrompt(release: AppUpdateRelease) {
        autoUpdateCheckJob?.cancel()
        autoUpdateCheckJob = lifecycleScope.launch {
            Toast.makeText(this@MainActivity, getString(R.string.update_downloading), Toast.LENGTH_SHORT).show()
            val result = downloadAppUpdateApk(this@MainActivity, release) {}
            result
                .onSuccess { file ->
                    if (!launchAppUpdateInstall(this@MainActivity, file)) {
                        Toast.makeText(this@MainActivity, getString(R.string.update_install_failed), Toast.LENGTH_LONG).show()
                    }
                }
                .onFailure { error ->
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_failed, error.message ?: error.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }
}

private enum class MiningSection {
    MAIN,
    DICTIONARY,
    COLLECTIONS,
    SETTINGS
}

private enum class HomeLibraryView {
    BOOKSHELF,
    LIST
}

internal enum class HomeLibrarySort {
    /** 最近阅读：按最后播放/阅读时间倒序，未读的按书名垫底 */
    RECENT,
    /** 书名：按标题字母序 */
    TITLE,
    /** 手动：保持书架顺序，长按拖动调整 */
    MANUAL
}

internal enum class HomeCoverAspect {
    SQUARE,
    BOOK
}

internal enum class HomeCoverCropFocus {
    CENTER,
    START
}

private val miningSectionSaver = Saver<MiningSection, String>(
    save = { it.name },
    restore = { runCatching { MiningSection.valueOf(it) }.getOrDefault(MiningSection.MAIN) }
)

private val FIELD_VARIABLE_CHOICES = listOf(
    "",
    "{audio}",
    "{cut-audio}",
    "{expression}",
    "{reading}",
    "{furigana-plain}",
    "{glossary}",
    "{glossary-first}",
    "{single-glossary}",
    "{definitions}",
    "{popup-selection-text}",
    "{sentence}",
    "{cloze-prefix}",
    "{cloze-body}",
    "{cloze-body-kana}",
    "{cloze-suffix}",
    "{frequencies}",
    "{frequency-harmonic-rank}",
    "{frequency-average-rank}",
    "{frequency}",
    "{pitch}",
    "{pitch-accents}",
    "{pitch-accent-positions}",
    "{pitch-accent-categories}",
    "{book-title}",
    "{search-query}"
)
private const val ANKI_CONFIG_LOG_TAG = "AnkiConfig"
private const val BOOK_DELETE_LOG_TAG = "BookDelete"
private const val BOOK_IMPORT_MOVE_LOG_TAG = "BookImportMove"
private const val FLOATING_OVERLAY_EXIT_LOG_TAG = "FloatingOverlayExit"
private const val MAIN_READER_RESTORE_LOG_TAG = "MainReaderRestore"
private const val BOOK_SOURCES_FOLDER_NAME = "9Player Sources"

internal data class ReaderBook(
    val id: String,
    val title: String,
    val audioUri: Uri?,
    val audioName: String?,
    val srtUri: Uri?,
    val srtName: String?,
    val ebookUri: Uri?,
    val ebookName: String?,
    val ebookFormat: String?,
    val coverUri: Uri?,
    val coverSource: ReaderBookCoverSource?,
    val audioCoverUri: Uri? = if (coverSource == ReaderBookCoverSource.AUDIO) coverUri else null,
    val ebookCoverUri: Uri? = if (coverSource == ReaderBookCoverSource.EBOOK) coverUri else null,
    val coverFocus: HomeCoverCropFocus = HomeCoverCropFocus.CENTER,
    val startBookCoverAdjustment: BookCoverAdjustment? = null,
    val centerBookCoverAdjustment: BookCoverAdjustment? = null,
    /**
     * 导入时间。「最近」排序取它和播放快照时间的较大者，这样刚导入、还没播过的书
     * 也会排在最前面（以前它没有快照，只能落到"没播过"那组按书名排，看着像沉在底部）。
     * 0 = 旧数据里没有这个字段，排序时按书名处理。
     */
    val addedAtMs: Long = 0L
) {
    val isEbookOnly: Boolean
        get() = audioUri == null && ebookUri != null

    val bookCoverAdjustment: BookCoverAdjustment?
        get() = adjustmentFor(coverFocus)
}

internal enum class ReaderBookCoverSource {
    AUDIO,
    EBOOK
}

private fun parseCachedCoverUri(raw: String?): Uri? {
    val uri = raw?.trim()?.takeIf { it.isNotBlank() }?.let {
        runCatching { Uri.parse(it) }.getOrNull()
    } ?: return null
    if (!uri.scheme.equals("file", ignoreCase = true)) return null
    val file = uri.path?.let(::File) ?: return null
    return file.takeIf { it.isFile && it.length() > 0L }?.let(Uri::fromFile)
}

private const val ReaderBookCoverAspectRatio = 279f / 400f

internal data class BookCoverAdjustment(
    val zoom: Float = 1f,
    val anchorXPx: Int? = null,
    val anchorYPx: Int? = null,
    val anchorXExactPx: Float? = null,
    val anchorYExactPx: Float? = null,
    val fallbackViewportX: Float? = null,
    val fallbackViewportY: Float? = null
)

private data class BookCoverRenderSpec(
    val scale: Float,
    val dx: Float,
    val dy: Float,
    val drawableWidth: Int,
    val drawableHeight: Int,
    val viewWidth: Int,
    val viewHeight: Int
)

private data class DecodedBookCover(
    val bitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int
)

private const val BookCoverPreviewMaxZoom = 3f
private const val BookCoverMagnifierZoom = 16f

private data class ReaderBookDisplayCover(
    val uri: Uri,
    val source: ReaderBookCoverSource
)

private fun ReaderBook.displayCoverFor(aspect: HomeCoverAspect): ReaderBookDisplayCover? {
    val preferred = when (aspect) {
        HomeCoverAspect.BOOK -> listOf(
            ebookCoverUri?.let { ReaderBookDisplayCover(it, ReaderBookCoverSource.EBOOK) },
            audioCoverUri?.let { ReaderBookDisplayCover(it, ReaderBookCoverSource.AUDIO) }
        )
        HomeCoverAspect.SQUARE -> listOf(
            audioCoverUri?.let { ReaderBookDisplayCover(it, ReaderBookCoverSource.AUDIO) },
            ebookCoverUri?.let { ReaderBookDisplayCover(it, ReaderBookCoverSource.EBOOK) }
        )
    }.filterNotNull()
    return preferred.firstOrNull()
        ?: coverUri?.let { uri ->
            ReaderBookDisplayCover(uri, coverSource ?: ReaderBookCoverSource.AUDIO)
        }
}

private fun defaultBookCoverAdjustmentForFocus(focus: HomeCoverCropFocus): BookCoverAdjustment {
    return when (focus) {
        HomeCoverCropFocus.START -> BookCoverAdjustment(
            zoom = 1f,
            fallbackViewportX = 0f,
            fallbackViewportY = 0.5f
        )
        HomeCoverCropFocus.CENTER -> BookCoverAdjustment(
            zoom = 1f,
            fallbackViewportX = 0.5f,
            fallbackViewportY = 0.5f
        )
    }
}

private fun normalizeBookCoverAdjustment(
    zoom: Float,
    anchorXPx: Int? = null,
    anchorYPx: Int? = null,
    anchorXExactPx: Float? = null,
    anchorYExactPx: Float? = null,
    fallbackViewportX: Float? = null,
    fallbackViewportY: Float? = null,
    maxZoom: Float = BookCoverPreviewMaxZoom,
    clampAnchors: Boolean = true
): BookCoverAdjustment {
    return BookCoverAdjustment(
        zoom = zoom.coerceIn(1f, maxZoom),
        anchorXPx = if (clampAnchors) anchorXPx?.coerceAtLeast(0) else anchorXPx,
        anchorYPx = if (clampAnchors) anchorYPx?.coerceAtLeast(0) else anchorYPx,
        anchorXExactPx = if (clampAnchors) anchorXExactPx?.coerceAtLeast(0f) else anchorXExactPx,
        anchorYExactPx = if (clampAnchors) anchorYExactPx?.coerceAtLeast(0f) else anchorYExactPx,
        fallbackViewportX = fallbackViewportX?.coerceIn(0f, 1f),
        fallbackViewportY = fallbackViewportY?.coerceIn(0f, 1f)
    )
}

private fun effectiveBookCoverAdjustment(book: ReaderBook): BookCoverAdjustment {
    return book.adjustmentFor(book.coverFocus) ?: defaultBookCoverAdjustmentForFocus(book.coverFocus)
}

private fun isCustomBookCoverAdjustment(
    adjustment: BookCoverAdjustment,
    focus: HomeCoverCropFocus
): Boolean {
    val baseline = defaultBookCoverAdjustmentForFocus(focus)
    return kotlin.math.abs(adjustment.zoom - baseline.zoom) > 0.001f ||
        adjustment.anchorXPx != null ||
        adjustment.anchorYPx != null ||
        kotlin.math.abs(
            (adjustment.fallbackViewportX ?: baseline.fallbackViewportX ?: 0.5f) -
                (baseline.fallbackViewportX ?: 0.5f)
        ) > 0.001f ||
        kotlin.math.abs(
            (adjustment.fallbackViewportY ?: baseline.fallbackViewportY ?: 0.5f) -
                (baseline.fallbackViewportY ?: 0.5f)
        ) > 0.001f
}

private fun persistedBookCoverAdjustment(
    book: PersistedReaderBook,
    focus: HomeCoverCropFocus
): BookCoverAdjustment? {
    val slotZoom = when (focus) {
        HomeCoverCropFocus.START -> book.startBookCoverZoom
        HomeCoverCropFocus.CENTER -> book.centerBookCoverZoom
    }
    val slotAnchorXPx = when (focus) {
        HomeCoverCropFocus.START -> book.startBookCoverAnchorXPx
        HomeCoverCropFocus.CENTER -> book.centerBookCoverAnchorXPx
    }
    val slotAnchorYPx = when (focus) {
        HomeCoverCropFocus.START -> book.startBookCoverAnchorYPx
        HomeCoverCropFocus.CENTER -> book.centerBookCoverAnchorYPx
    }
    val zoom = slotZoom?.toFloat()
        ?: book.bookCoverZoom?.toFloat()?.takeIf { bookFocusOrDefault(book) == focus }
        ?: return null
    return normalizeBookCoverAdjustment(
        zoom = zoom,
        anchorXPx = slotAnchorXPx ?: book.bookCoverAnchorXPx?.takeIf { bookFocusOrDefault(book) == focus },
        anchorYPx = slotAnchorYPx ?: book.bookCoverAnchorYPx?.takeIf { bookFocusOrDefault(book) == focus },
        fallbackViewportX = book.bookCoverViewportX?.toFloat(),
        fallbackViewportY = book.bookCoverViewportY?.toFloat()
    )
}

private fun bookFocusOrDefault(book: PersistedReaderBook): HomeCoverCropFocus {
    return when (book.coverFocus?.uppercase(Locale.ROOT)) {
        HomeCoverCropFocus.START.name -> HomeCoverCropFocus.START
        else -> HomeCoverCropFocus.CENTER
    }
}

private fun ReaderBook.adjustmentFor(focus: HomeCoverCropFocus): BookCoverAdjustment? {
    return when (focus) {
        HomeCoverCropFocus.START -> startBookCoverAdjustment
        HomeCoverCropFocus.CENTER -> centerBookCoverAdjustment
    }
}

private fun ReaderBook.withAdjustmentFor(
    focus: HomeCoverCropFocus,
    adjustment: BookCoverAdjustment?
): ReaderBook {
    return when (focus) {
        HomeCoverCropFocus.START -> copy(startBookCoverAdjustment = adjustment)
        HomeCoverCropFocus.CENTER -> copy(centerBookCoverAdjustment = adjustment)
    }
}

private fun PersistedReaderBook.withAdjustmentFor(
    focus: HomeCoverCropFocus,
    adjustment: BookCoverAdjustment?
): PersistedReaderBook {
    return when (focus) {
        HomeCoverCropFocus.START -> copy(
            startBookCoverZoom = adjustment?.zoom?.toDouble(),
            startBookCoverAnchorXPx = adjustment?.anchorXPx,
            startBookCoverAnchorYPx = adjustment?.anchorYPx
        )
        HomeCoverCropFocus.CENTER -> copy(
            centerBookCoverZoom = adjustment?.zoom?.toDouble(),
            centerBookCoverAnchorXPx = adjustment?.anchorXPx,
            centerBookCoverAnchorYPx = adjustment?.anchorYPx
        )
    }
}

private data class ReturnedBookProgress(
    val audioUri: String,
    val srtUri: String?,
    val positionMs: Long,
    val durationMs: Long
)

// 书架排序菜单项的显示文案（枚举声明顺序即菜单顺序）。
private fun homeSortLabelRes(sort: HomeLibrarySort): Int = when (sort) {
    HomeLibrarySort.RECENT -> R.string.home_sort_recent
    HomeLibrarySort.TITLE -> R.string.home_sort_title
    HomeLibrarySort.MANUAL -> R.string.home_sort_manual
}

// 拖拽让位弹簧动画：目标为 0 时自动回到原位（松手落位/取消后平滑归位）。
@Composable
private fun animateDragDisplacement(target: Offset): Offset = animateOffsetAsState(
    targetValue = target,
    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
    label = "shelfDragDisplacement"
).value

@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun ReaderSyncScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val mainActivity = activity as? MainActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val rootDensity = LocalDensity.current
    val navigationBarBottomInsetDp = with(rootDensity) {
        WindowInsets.navigationBars.getBottom(this).toDp().value.toDouble()
    }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val contentResolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val dictionaryPageBackground = MaterialTheme.colorScheme.background
    val dictionaryPageBackgroundCss = dictionaryPageBackground.toCssRgbHex()
    val isDarkTheme = isSystemInDarkTheme()

    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var audioName by remember { mutableStateOf<String?>(null) }
    var srtUri by remember { mutableStateOf<Uri?>(null) }
    var srtName by remember { mutableStateOf<String?>(null) }

    var srtCues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var srtLoading by remember { mutableStateOf(false) }
    var srtError by remember { mutableStateOf<String?>(null) }
    var readerBooks by remember { mutableStateOf<List<ReaderBook>>(emptyList()) }
    var readerBookPlaybackSnapshots by remember {
        mutableStateOf<Map<String, BookReaderPlaybackSnapshot>>(emptyMap())
    }
    var selectedBookId by remember { mutableStateOf<String?>(null) }
    var adjustingBookCoverId by remember { mutableStateOf<String?>(null) }
    var homeLibraryView by remember { mutableStateOf(HomeLibraryView.BOOKSHELF) }
    var homeCoverAspect by remember { mutableStateOf(HomeCoverAspect.BOOK) }
    var homeLibrarySort by remember { mutableStateOf(HomeLibrarySort.RECENT) }
    var homeCoverFocusEditMode by remember { mutableStateOf(false) }
    var homeDisplayMenuExpanded by remember { mutableStateOf(false) }
    var addBookDialogVisible by remember { mutableStateOf(false) }
    var addBookAudioUri by remember { mutableStateOf<Uri?>(null) }
    var addBookAudioName by remember { mutableStateOf<String?>(null) }
    var addBookSrtUri by remember { mutableStateOf<Uri?>(null) }
    var addBookSrtName by remember { mutableStateOf<String?>(null) }
    var addBookEbookUri by remember { mutableStateOf<Uri?>(null) }
    var addBookEbookName by remember { mutableStateOf<String?>(null) }
    var addBookEbookFormat by remember { mutableStateOf<String?>(null) }
    var addBookFolderUri by remember { mutableStateOf<Uri?>(null) }
    var addBookFolderName by remember { mutableStateOf<String?>(null) }
    var ebookFeatureEnabled by remember { mutableStateOf(loadEbookFeatureEnabled(context)) }
    var autoMoveToAudiobookFolder by remember { mutableStateOf(true) }
    var keepSourceFilesWhenAutoMove by remember { mutableStateOf(false) }
    var importOnboardingCompleted by remember { mutableStateOf(false) }
    var importGuideVisible by remember { mutableStateOf(false) }
    var persistedImportsLoaded by remember { mutableStateOf(false) }
    var persistedImportsVersion by remember { mutableStateOf<Long?>(null) }
    var autoUpdatePromptVisible by remember { mutableStateOf(false) }
    var autoUpdateStartupHandled by remember { mutableStateOf(false) }
    val selectedBookIds = remember { mutableStateListOf<String>() }
    var isBookSelectionMode by remember { mutableStateOf(false) }

    val dictionaryController = rememberDictionaryManagementController(
        context = context,
        contentResolver = contentResolver,
        scope = scope
    )
    val loadedDictionaries = dictionaryController.loadedDictionaries
    val dictionaryRefs = dictionaryController.dictionaryRefs
    val dictionaryLoading = dictionaryController.dictionaryLoading
    val dictionaryProgressText = dictionaryController.dictionaryProgressText
    val dictionaryProgressValue = dictionaryController.dictionaryProgressValue
    val dictionaryError = dictionaryController.dictionaryError
    var dictionaryUiConfig by remember { mutableStateOf(loadDictionaryUiConfig(context)) }

    var lookupQuery by remember { mutableStateOf("") }
    var lookupLoading by remember { mutableStateOf(false) }
    var dictionaryFirstLayerHtml by remember { mutableStateOf("") }
    var dictionaryFirstLayerResults by remember { mutableStateOf<List<LookupResult>>(emptyList()) }
    var dictionaryFirstLayerClearSelectionSignal by remember { mutableStateOf(0) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var pendingAnkiCard by remember { mutableStateOf<MinedCard?>(null) }
    var awaitingExternalAnkiPermission by remember { mutableStateOf(false) }
    var ankiPermissionGranted by remember { mutableStateOf(hasAnkiReadWritePermission(context)) }
    var ankiDeckName by remember { mutableStateOf("Default") }
    var ankiModelName by remember { mutableStateOf("") }
    var ankiTagsInput by remember { mutableStateOf(DEFAULT_ANKI_TAG) }
    var ankiModels by remember { mutableStateOf<List<AnkiModelTemplate>>(emptyList()) }
    val ankiFieldTemplates = remember { mutableStateMapOf<String, String>() }

    var showDictionaryManager by remember { mutableStateOf(false) }
    var showDictionaryDeleteActions by remember { mutableStateOf(false) }
    var activeSection by rememberSaveable(stateSaver = miningSectionSaver) {
        mutableStateOf(MiningSection.MAIN)
    }
    var languageDialogVisible by remember { mutableStateOf(false) }
    var selectedAppLanguage by remember { mutableStateOf(loadAppLanguageOption(context)) }
    var collectedCues by remember { mutableStateOf<List<BookReaderCollectedCue>>(emptyList()) }
    var clearCollectionsConfirmVisible by remember { mutableStateOf(false) }
    var deleteBooksConfirmVisible by remember { mutableStateOf(false) }
    var deleteBooksDeleteSourceFiles by remember { mutableStateOf(false) }
    var pendingDeleteBookIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var renameBookDialogVisible by remember { mutableStateOf(false) }
    var renameTargetBookId by remember { mutableStateOf<String?>(null) }
    var renameBookInput by remember { mutableStateOf("") }
    val mainHoshiLookupPopups = remember { mutableStateListOf<LookupPopupItem>() }
    var mainHoshiLookupCue by remember { mutableStateOf<SubtitleCue?>(null) }
    var mainHoshiLookupSelectedRange by remember { mutableStateOf<IntRange?>(null) }
    var mainHoshiLookupAudioUri by remember { mutableStateOf<Uri?>(null) }
    var mainHoshiLookupTitle by remember { mutableStateOf("") }
    var collectionLookupPreviewVisible by remember { mutableStateOf(false) }
    var collectionLookupPreviewSentence by remember { mutableStateOf("") }
    var collectionLookupPreviewCue by remember { mutableStateOf<SubtitleCue?>(null) }
    var collectionLookupPreviewSelectedRange by remember { mutableStateOf<IntRange?>(null) }
    var collectionLookupPreviewAudioUri by remember { mutableStateOf<Uri?>(null) }
    var collectionFirstLayerHtml by remember { mutableStateOf("") }
    var collectionFirstLayerResults by remember { mutableStateOf<List<LookupResult>>(emptyList()) }
    var collectionFirstLayerClearSelectionSignal by remember { mutableStateOf(0) }
    var audiobookSettings by remember { mutableStateOf(loadAudiobookSettingsConfig(context)) }
    var versionTapCount by remember { mutableStateOf(0) }
    var showVersionEasterGif by remember { mutableStateOf(false) }
    var diagnosticsExportSheetVisible by remember { mutableStateOf(false) }

    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var pendingCollectionPlayMs by remember { mutableStateOf<Long?>(null) }
    var pendingCollectionStopMs by remember { mutableStateOf<Long?>(null) }
    var collectionPlayRequestNonce by remember { mutableStateOf(0L) }

    val importedLookupById = remember(dictionaryRefs, loadedDictionaries) {
        dictionaryRefs.mapIndexedNotNull { index, ref ->
            if (!ref.enabled) return@mapIndexedNotNull null
            val loaded = loadedDictionaries.getOrNull(index) ?: return@mapIndexedNotNull null
            importedDictionaryId(ref) to loaded
        }.toMap(LinkedHashMap())
    }
    val effectiveLookupDictionaries = remember(importedLookupById) {
        if (importedLookupById.isEmpty()) return@remember emptyList()
        buildList {
            addAll(importedLookupById.values)
        }
    }
    val dictionaryCssByName = remember(effectiveLookupDictionaries) {
        effectiveLookupDictionaries.associate { it.name to it.stylesCss }
    }
    val mainHoshiLookupSession = remember(context, effectiveLookupDictionaries) {
        HoshiLookupSession(context, dictionariesProvider = { effectiveLookupDictionaries })
    }
    fun closeMainLookupPopup() {
        mainHoshiLookupPopups.clear()
        mainHoshiLookupCue = null
        mainHoshiLookupSelectedRange = null
        mainHoshiLookupAudioUri = null
        mainHoshiLookupTitle = ""
        dictionaryFirstLayerHtml = ""
        dictionaryFirstLayerResults = emptyList()
        dictionaryFirstLayerClearSelectionSignal += 1
        collectionLookupPreviewVisible = false
        collectionLookupPreviewSentence = ""
        collectionLookupPreviewCue = null
        collectionLookupPreviewSelectedRange = null
        collectionLookupPreviewAudioUri = null
        collectionFirstLayerHtml = ""
        collectionFirstLayerResults = emptyList()
        collectionFirstLayerClearSelectionSignal += 1
    }

    BackHandler {
        when {
            collectionLookupPreviewVisible -> closeMainLookupPopup()
            mainHoshiLookupPopups.isNotEmpty() -> closeMainLookupPopup()
            addBookDialogVisible -> addBookDialogVisible = false
            importGuideVisible -> importGuideVisible = false
            autoUpdatePromptVisible -> autoUpdatePromptVisible = false
            mainActivity?.launchUpdatePromptRelease != null -> mainActivity.dismissLaunchUpdatePrompt()
            clearCollectionsConfirmVisible -> clearCollectionsConfirmVisible = false
            deleteBooksConfirmVisible -> deleteBooksConfirmVisible = false
            activeSection != MiningSection.MAIN -> activeSection = MiningSection.MAIN
            BookReaderFloatingBridge.currentAudioUri() != null -> activity?.moveTaskToBack(true)
            else -> activity?.finish()
        }
    }

    val player = remember(context) {
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    DisposableEffect(dictionaryLoading, view) {
        view.keepScreenOn = dictionaryLoading
        onDispose {
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(readerBooks) {
        readerBookPlaybackSnapshots = loadReaderBookPlaybackSnapshotsForBooks(
            context = context,
            books = readerBooks
        )
    }

    LaunchedEffect(persistedImportsLoaded, importGuideVisible) {
        if (!persistedImportsLoaded || importGuideVisible || autoUpdateStartupHandled) return@LaunchedEffect
        autoUpdateStartupHandled = true
        val updateConfig = loadAppUpdateConfig(context)
        if (!updateConfig.firstPromptShown) {
            autoUpdatePromptVisible = true
        } else if (updateConfig.autoUpdateEnabled) {
            mainActivity?.checkAppUpdateInBackground(force = false)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (!persistedImportsLoaded) return@LifecycleEventObserver
                audiobookSettings = loadAudiobookSettingsConfig(context)
                dictionaryUiConfig = loadDictionaryUiConfig(context)
                scope.launch {
                    val persistedVersion = loadPersistedImportsVersion(context)
                    var loadedSnapshots = readerBookPlaybackSnapshots
                    if (persistedImportsVersion != persistedVersion) {
                        val persistedNow = loadPersistedImports(context)
                        persistedImportsVersion = persistedVersion
                        var snapshotBooks = readerBooks
                        dictionaryController.syncPersistedDictionaries(persistedNow.dictionaries)
                        if (persistedNow.books.isNotEmpty() && readerBooks.isNotEmpty()) {
                            val persistedByAudio = persistedNow.books.associateBy { it.audioUri }
                            var changed = false
                            val mergedBooks = readerBooks.map { book ->
                                val bookAudioUri = book.audioUri ?: return@map book
                                val bookAudioName = book.audioName ?: return@map book
                                val persistedBook = persistedByAudio[bookAudioUri.toString()] ?: return@map book
                                val persistedSrtRaw = persistedBook.srtUri?.trim().orEmpty()
                                if (persistedSrtRaw.isBlank()) return@map book
                                val persistedSrt = runCatching { Uri.parse(persistedSrtRaw) }.getOrNull()
                                    ?: return@map book
                                if ((book.srtUri?.toString().orEmpty()) == persistedSrtRaw) return@map book
                                changed = true
                                val mergedSrtName = persistedBook.srtName?.ifBlank { null }
                                    ?: queryDisplayName(contentResolver, persistedSrt)
                                val mergedTitle = buildBookTitle(bookAudioName, mergedSrtName)
                                val mergedId = buildDictionaryCacheKey(
                                    uri = "book|$bookAudioUri|$persistedSrtRaw",
                                    displayName = "$bookAudioName|${mergedSrtName.orEmpty()}"
                                )
                                book.copy(
                                    id = mergedId,
                                    title = mergedTitle,
                                    srtUri = persistedSrt,
                                    srtName = mergedSrtName
                                )
                            }
                            if (changed) {
                                readerBooks = mergedBooks
                                snapshotBooks = mergedBooks
                                val selected = mergedBooks.firstOrNull { it.id == selectedBookId }
                                    ?: mergedBooks.firstOrNull { it.audioUri?.toString() == audioUri?.toString() }
                                if (selected != null) {
                                    selectedBookId = selected.id
                                    audioUri = selected.audioUri
                                    audioName = selected.audioName
                                    srtUri = selected.srtUri
                                    srtName = selected.srtName
                                }
                            }
                        }
                        loadedSnapshots = loadReaderBookPlaybackSnapshotsForBooks(
                            context = context,
                            books = snapshotBooks
                        )
                    }
                    val returnedProgress = consumeReturnedBookProgress(activity?.intent)
                    if (returnedProgress != null) {
                        val targetBook = readerBooks.firstOrNull {
                            it.audioUri?.toString() == returnedProgress.audioUri
                        }
                        if (targetBook != null) {
                            val targetAudioName = targetBook.audioName
                            val targetAudioUri = targetBook.audioUri
                            val returnedSrt = returnedProgress.srtUri
                                ?.trim()
                                ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                ?.let { raw -> runCatching { Uri.parse(raw) }.getOrNull() }
                            if (
                                targetAudioName != null &&
                                targetAudioUri != null &&
                                (targetBook.srtUri?.toString().orEmpty()) != (returnedSrt?.toString().orEmpty())
                            ) {
                                val updatedSrtName = returnedSrt?.let { queryDisplayName(contentResolver, it) }
                                val updatedTitle = buildBookTitle(targetAudioName, updatedSrtName)
                                val updatedId = buildDictionaryCacheKey(
                                    uri = "book|$targetAudioUri|${returnedSrt?.toString().orEmpty()}",
                                    displayName = "$targetAudioName|${updatedSrtName.orEmpty()}"
                                )
                                val updatedBook = targetBook.copy(
                                    id = updatedId,
                                    title = updatedTitle,
                                    audioUri = targetAudioUri,
                                    audioName = targetAudioName,
                                    srtUri = returnedSrt,
                                    srtName = updatedSrtName
                                )
                                val wasSelected = selectedBookId == targetBook.id
                                readerBooks = listOf(updatedBook) + readerBooks.filterNot {
                                    it.audioUri?.toString() == targetAudioUri.toString()
                                }
                                if (wasSelected) {
                                    selectedBookId = updatedBook.id
                                    audioUri = updatedBook.audioUri
                                    audioName = updatedBook.audioName
                                    srtUri = updatedBook.srtUri
                                    srtName = updatedBook.srtName
                                }
                                // Persist immediately so "no SRT -> replaced SRT" survives app restart.
                                val persistedBooks = readerBooks.map { book ->
                                    PersistedReaderBook(
                                        id = book.id,
                                        title = book.title,
                                        audioUri = book.audioUri?.toString(),
                                        audioName = book.audioName,
                                        srtUri = book.srtUri?.toString(),
                                        srtName = book.srtName,
                                        ebookUri = book.ebookUri?.toString(),
                                        ebookName = book.ebookName,
                                        ebookFormat = book.ebookFormat,
                                        audioCoverUri = book.audioCoverUri?.toString(),
                                        ebookCoverUri = book.ebookCoverUri?.toString(),
                                        coverFocus = book.coverFocus.name,
                                        startBookCoverZoom = book.startBookCoverAdjustment?.zoom?.toDouble(),
                                        startBookCoverAnchorXPx = book.startBookCoverAdjustment?.anchorXPx,
                                        startBookCoverAnchorYPx = book.startBookCoverAdjustment?.anchorYPx,
                                        centerBookCoverZoom = book.centerBookCoverAdjustment?.zoom?.toDouble(),
                                        centerBookCoverAnchorXPx = book.centerBookCoverAdjustment?.anchorXPx,
                                        centerBookCoverAnchorYPx = book.centerBookCoverAdjustment?.anchorYPx,
                                        addedAtMs = book.addedAtMs
                                    )
                                }
                                savePersistedImports(
                                    context = context,
                                    state = PersistedImports(
                                        audioUri = audioUri?.toString(),
                                        audioName = audioName,
                                        srtUri = srtUri?.toString(),
                                        srtName = srtName,
                                        audiobookFolderUri = addBookFolderUri?.toString(),
                                        audiobookFolderName = addBookFolderName,
                                        autoMoveToAudiobookFolder = autoMoveToAudiobookFolder,
                                        keepSourceFilesWhenAutoMove = keepSourceFilesWhenAutoMove,
                                        importOnboardingCompleted = importOnboardingCompleted,
                                        books = persistedBooks,
                                        selectedBookId = selectedBookId,
                                        homeLibraryView = homeLibraryView.name,
                                        homeCoverAspect = homeCoverAspect.name,
                                        homeLibrarySort = homeLibrarySort.name,
                                        dictionaries = dictionaryRefs
                                    )
                                )
                            }
                            if (targetAudioUri != null && returnedProgress.positionMs >= 0L && returnedProgress.durationMs > 0L) {
                                val effectiveBook = readerBooks.firstOrNull {
                                    it.audioUri?.toString() == targetAudioUri.toString()
                                } ?: targetBook
                                val immediate = BookReaderPlaybackSnapshot(
                                    positionMs = returnedProgress.positionMs.coerceAtLeast(0L),
                                    durationMs = returnedProgress.durationMs.coerceAtLeast(0L)
                                )
                                loadedSnapshots = loadedSnapshots + (effectiveBook.id to immediate)
                                withContext(Dispatchers.IO) {
                                    saveBookReaderPlaybackPosition(
                                        context = context,
                                        bookKey = buildReaderBookPlaybackKey(effectiveBook),
                                        positionMs = immediate.positionMs,
                                        durationMs = immediate.durationMs
                                    )
                                }
                            }
                        }
                    }
                    readerBookPlaybackSnapshots = loadedSnapshots
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = if (player.duration > 0L) player.duration else 0L
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                isPlaying = player.isPlaying
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = if (player.duration > 0L) player.duration else 0L
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = if (player.duration > 0L) player.duration else 0L
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                positionMs = newPosition.positionMs.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player, isPlaying) {
        if (!isPlaying) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = if (player.duration > 0L) player.duration else 0L
            return@LaunchedEffect
        }
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = if (player.duration > 0L) player.duration else 0L
            delay(320L)
        }
    }

    LaunchedEffect(audioUri) {
        val selectedAudio = audioUri ?: return@LaunchedEffect
        if (BookReaderFloatingBridge.isPlaying()) {
            // Keep reader playback untouched when returning to home.
            return@LaunchedEffect
        }
        val selectedReaderBook = selectedBookId?.let { id -> readerBooks.firstOrNull { it.id == id && it.audioUri != null } }
            ?: readerBooks.firstOrNull { it.audioUri == selectedAudio && it.srtUri == srtUri }
        val shouldPrewarmLegadoReader =
            selectedReaderBook?.ebookUri != null &&
                loadEbookFeatureEnabled(context) &&
                loadEbookDefaultToReader(context)
        if (shouldPrewarmLegadoReader) {
            val restoredSnapshot = loadBookReaderPlaybackSnapshotOrNull(
                context,
                buildReaderBookPlaybackKey(selectedReaderBook)
            )
            val restorePositionMs = restoredSnapshot?.positionMs?.coerceAtLeast(0L) ?: 0L
            val alreadyPreparedForAudio = BookReaderPlaybackSession.currentAudioUri() == selectedAudio.toString()
            logDebug(MAIN_READER_RESTORE_LOG_TAG) {
                "prewarm positionMs=$restorePositionMs " +
                "durationMs=${restoredSnapshot?.durationMs ?: 0L} updatedAt=${restoredSnapshot?.updatedAtMs ?: 0L} " +
                "sameAudio=$alreadyPreparedForAudio"
            }
            BookReaderPlaybackSession.prepareAudioIfNeeded(
                context = context,
                audioUri = selectedAudio,
                restorePositionMs = restorePositionMs,
                forceSeekOnSameAudio = false
            )
            return@LaunchedEffect
        }
        val restoredSnapshot = selectedReaderBook?.let { book ->
            loadBookReaderPlaybackSnapshotOrNull(
                context,
                buildReaderBookPlaybackKey(book)
            )
        }
        val restorePositionMs = restoredSnapshot?.positionMs?.coerceAtLeast(0L) ?: 0L
        logDebug(MAIN_READER_RESTORE_LOG_TAG) {
            "mainPlayerPrepare positionMs=$restorePositionMs " +
            "durationMs=${restoredSnapshot?.durationMs ?: 0L} updatedAt=${restoredSnapshot?.updatedAtMs ?: 0L}"
        }
        player.setMediaItem(MediaItem.fromUri(selectedAudio))
        player.prepare()
        player.pause()
        player.seekTo(restorePositionMs)
        if (selectedReaderBook != null && restoredSnapshot != null) {
            readerBookPlaybackSnapshots = readerBookPlaybackSnapshots + (selectedReaderBook.id to restoredSnapshot)
        }
    }

    LaunchedEffect(audioUri, pendingCollectionPlayMs, collectionPlayRequestNonce) {
        val targetMs = pendingCollectionPlayMs ?: return@LaunchedEffect
        val selectedAudio = audioUri ?: return@LaunchedEffect
        if (player.playbackState == Player.STATE_IDLE) {
            player.setMediaItem(MediaItem.fromUri(selectedAudio))
            player.prepare()
        }
        var waitedMs = 0L
        while (player.playbackState == Player.STATE_IDLE && waitedMs < 2_000L) {
            delay(50L)
            waitedMs += 50L
        }
        player.seekTo(targetMs.coerceAtLeast(0L))
        player.play()
        pendingCollectionPlayMs = null
    }

    LaunchedEffect(positionMs, isPlaying, pendingCollectionStopMs) {
        val stopMs = pendingCollectionStopMs ?: return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        if (positionMs >= stopMs) {
            player.pause()
            pendingCollectionStopMs = null
        }
    }

    val requestAnkiPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            ankiPermissionGranted = granted || hasAnkiReadWritePermission(context)
            if (ankiPermissionGranted) {
                exportStatus = "Anki access permission granted."
            } else {
                pendingAnkiCard = null
                exportStatus = "Anki access permission was denied."
            }
        }
    val requestExternalAnkiPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            awaitingExternalAnkiPermission = false
            ankiPermissionGranted = hasAnkiReadWritePermission(context)
            if (ankiPermissionGranted) {
                exportStatus = "Anki access permission granted."
            } else {
                pendingAnkiCard = null
                exportStatus = ankiAvailabilityUiMessage(context, requirePermission = true)
                    ?: "Anki access permission was denied."
            }
        }
    fun persistImportState(dictionaryRefsOverride: List<PersistedDictionaryRef> = dictionaryRefs) {
        val previous = loadPersistedImports(context)
        val previousByBookKey = previous.books.associateBy { persistedBookImportKey(it) }
        val persistedBooks = readerBooks.map { book ->
            val audioKey = book.audioUri?.toString()
            val previousBook = previousByBookKey[readerBookImportKey(book)]
            val currentSrt = book.srtUri?.toString()?.takeIf { it.isNotBlank() }
            val mergedSrt = currentSrt ?: previousBook?.srtUri?.takeIf { it.isNotBlank() }
            val mergedSrtName = if (currentSrt != null) {
                book.srtName
            } else {
                book.srtName ?: previousBook?.srtName
            }
            PersistedReaderBook(
                id = book.id,
                title = book.title,
                audioUri = audioKey,
                audioName = book.audioName,
                srtUri = mergedSrt,
                srtName = mergedSrtName,
                ebookUri = book.ebookUri?.toString(),
                ebookName = book.ebookName,
                ebookFormat = book.ebookFormat,
                audioCoverUri = book.audioCoverUri?.toString(),
                ebookCoverUri = book.ebookCoverUri?.toString(),
                coverFocus = book.coverFocus.name,
                startBookCoverZoom = book.startBookCoverAdjustment?.zoom?.toDouble(),
                startBookCoverAnchorXPx = book.startBookCoverAdjustment?.anchorXPx,
                startBookCoverAnchorYPx = book.startBookCoverAdjustment?.anchorYPx,
                centerBookCoverZoom = book.centerBookCoverAdjustment?.zoom?.toDouble(),
                centerBookCoverAnchorXPx = book.centerBookCoverAdjustment?.anchorXPx,
                centerBookCoverAnchorYPx = book.centerBookCoverAdjustment?.anchorYPx,
                // 0 = 未知（旧数据，或某些重建路径交上来的新 ReaderBook）：不许把磁盘上
                // 已知的导入时间降级成 0，否则「最近」排序会静默塌回按书名排。
                addedAtMs = book.addedAtMs.takeIf { it > 0L } ?: previousBook?.addedAtMs ?: 0L
            )
        }
        val previousSelectedSrt = previous.srtUri?.takeIf { it.isNotBlank() }
        val currentSelectedSrt = srtUri?.toString()?.takeIf { it.isNotBlank() }
        val mergedSelectedSrt = currentSelectedSrt ?: previousSelectedSrt
        val mergedSelectedSrtName = if (currentSelectedSrt != null) {
            srtName
        } else {
            srtName ?: previous.srtName
        }
        savePersistedImports(
            context = context,
            state = PersistedImports(
                audioUri = audioUri?.toString(),
                audioName = audioName,
                srtUri = mergedSelectedSrt,
                srtName = mergedSelectedSrtName,
                audiobookFolderUri = addBookFolderUri?.toString(),
                audiobookFolderName = addBookFolderName,
                autoMoveToAudiobookFolder = autoMoveToAudiobookFolder,
                keepSourceFilesWhenAutoMove = keepSourceFilesWhenAutoMove,
                importOnboardingCompleted = importOnboardingCompleted,
                books = persistedBooks,
                selectedBookId = selectedBookId,
                homeLibraryView = homeLibraryView.name,
                homeCoverAspect = homeCoverAspect.name,
                homeLibrarySort = homeLibrarySort.name,
                dictionaries = dictionaryRefsOverride
            )
        )
    }

    fun persistHomeDisplaySettings(
        nextView: HomeLibraryView = homeLibraryView,
        nextAspect: HomeCoverAspect = homeCoverAspect,
        nextSort: HomeLibrarySort = homeLibrarySort
    ) {
        scope.launch(Dispatchers.IO) {
            val previous = loadPersistedImports(context)
            savePersistedImports(
                context = context,
                state = previous.copy(
                    homeLibraryView = nextView.name,
                    homeCoverAspect = nextAspect.name,
                    homeLibrarySort = nextSort.name
                )
            )
        }
    }

    fun toggleBookCoverFocus(book: ReaderBook) {
        val nextFocus = if (book.coverFocus == HomeCoverCropFocus.START) {
            HomeCoverCropFocus.CENTER
        } else {
            HomeCoverCropFocus.START
        }
        readerBooks = readerBooks.map { current ->
            if (current.id == book.id) {
                current.copy(coverFocus = nextFocus)
            } else {
                current
            }
        }
        scope.launch(Dispatchers.IO) {
            val previous = loadPersistedImports(context)
            savePersistedImports(
                context = context,
                state = previous.copy(
                    books = previous.books.map { persisted ->
                        if (persisted.id == book.id) {
                            persisted.copy(coverFocus = nextFocus.name)
                        } else {
                            persisted
                        }
                    }
                )
            )
        }
    }

    fun updateBookCoverAdjustment(bookId: String, adjustment: BookCoverAdjustment?) {
        readerBooks = readerBooks.map { current ->
            if (current.id == bookId) current.withAdjustmentFor(current.coverFocus, adjustment) else current
        }
        persistImportState()
    }

    fun persistAnkiConfig() {
        logDebug(ANKI_CONFIG_LOG_TAG) {
            "MainActivity persist request deck='$ankiDeckName' model='$ankiModelName' tagsLen=${ankiTagsInput.length} fieldCount=${ankiFieldTemplates.size}"
        }
        savePersistedAnkiConfig(
            context = context,
            config = buildPersistedAnkiConfig(
                deckName = ankiDeckName,
                modelName = ankiModelName,
                tags = ankiTagsInput,
                fieldTemplates = ankiFieldTemplates.toMap()
            )
        )
    }

    fun selectAnkiModel(modelName: String) {
        val modelChanged = ankiModelName != modelName
        ankiModelName = modelName
        val model = ankiModels.firstOrNull { it.name == modelName }
        logDebug(ANKI_CONFIG_LOG_TAG) {
            "MainActivity select model='$modelName' modelChanged=$modelChanged found=${model != null} fieldCount=${model?.fields?.size ?: 0}"
        }
        syncAnkiFieldTemplates(
            target = ankiFieldTemplates,
            fields = model?.fields ?: emptyList(),
            clearExisting = modelChanged
        )
        persistAnkiConfig()
    }

    fun refreshAnkiCatalog() {
        ankiAvailabilityUiMessage(context, requirePermission = true)?.let { availabilityMessage ->
            logDebug(ANKI_CONFIG_LOG_TAG) { "MainActivity refresh unavailable message='$availabilityMessage'" }
            ankiModels = emptyList()
            return
        }

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                loadResolvedAnkiCatalogResult(
                    context = context,
                    currentDeckName = ankiDeckName,
                    currentModelName = ankiModelName,
                    defaultDeckName = "Default"
                )
            }
            when (result) {
                is AnkiCatalogLoadResult.Success -> {
                    val resolvedCatalog = result.data
                    val resolvedModelName = resolvedCatalog.selection.modelName
                    val modelInCatalog = isAnkiModelInCatalog(
                        catalog = AnkiCatalog(
                            decks = resolvedCatalog.decks,
                            models = resolvedCatalog.models
                        ),
                        modelName = resolvedModelName
                    )
                    logDebug(ANKI_CONFIG_LOG_TAG) {
                        "MainActivity refresh success currentDeck='$ankiDeckName' currentModel='$ankiModelName' resolvedDeck='${resolvedCatalog.selection.deckName}' resolvedModel='$resolvedModelName' modelInCatalog=$modelInCatalog modelCount=${resolvedCatalog.models.size}"
                    }
                    ankiModels = resolvedCatalog.models
                    ankiDeckName = resolvedCatalog.selection.deckName
                    if (modelInCatalog) {
                        selectAnkiModel(resolvedModelName)
                    } else {
                        logDebug(ANKI_CONFIG_LOG_TAG) {
                            "MainActivity refresh kept existing model='$ankiModelName' because it is not in catalog; templates were not cleared"
                        }
                    }
                }
                is AnkiCatalogLoadResult.Failure -> {
                    logDebug(ANKI_CONFIG_LOG_TAG) {
                        "MainActivity refresh failed message='${result.message}'"
                    }
                }
            }
        }
    }

    fun currentAnkiExportConfigOrNull(): AnkiExportConfig? {
        return buildCurrentAnkiExportConfigOrNull(
            deckName = ankiDeckName,
            modelName = ankiModelName,
            tags = ankiTagsInput,
            models = ankiModels,
            fieldTemplates = ankiFieldTemplates
        )
    }

    fun refreshCollectedCues() {
        collectedCues = loadBookReaderCollectedCues(context)
    }

    fun buildReaderBook(
        audio: Uri?,
        audioDisplayName: String?,
        srt: Uri?,
        srtDisplayName: String?,
        ebook: Uri? = null,
        ebookDisplayName: String? = null,
        ebookFormat: String? = null,
        cachedAudioCoverUri: Uri? = null,
        cachedEbookCoverUri: Uri? = null
    ): ReaderBook {
        val resolvedAudioName = audio?.let {
            audioDisplayName?.takeIf { name -> name.isNotBlank() }
                ?: queryDisplayName(contentResolver, it)
        }
        val resolvedSrtName = srt?.let {
            srtDisplayName?.takeIf { name -> name.isNotBlank() }
                ?: queryDisplayName(contentResolver, it)
        }
        val resolvedEbookName = ebook?.let {
            ebookDisplayName?.takeIf { name -> name.isNotBlank() }
                ?: queryDisplayName(contentResolver, it)
        }
        val title = resolvedAudioName?.let { buildBookTitle(it, resolvedSrtName) }
            ?: buildEbookTitle(resolvedEbookName)
        val audioCoverUri = cachedAudioCoverUri ?: audio?.let {
            resolveEmbeddedCoverUriForM4b(
                context = context,
                audioUri = it,
                audioDisplayName = resolvedAudioName.orEmpty()
            )
        }
        val ebookCoverUri = cachedEbookCoverUri ?: resolveEmbeddedCoverUriForEpub(
            context = context,
            ebookUri = ebook,
            ebookDisplayName = resolvedEbookName,
            ebookFormat = ebookFormat
        )
        val coverUri = audioCoverUri ?: ebookCoverUri
        val coverSource = when {
            audioCoverUri != null -> ReaderBookCoverSource.AUDIO
            ebookCoverUri != null -> ReaderBookCoverSource.EBOOK
            audio == null && ebook != null -> ReaderBookCoverSource.EBOOK
            else -> null
        }
        val srtIdPart = srt?.toString().orEmpty()
        val srtNamePart = resolvedSrtName.orEmpty()
        val stableSource = audio?.toString() ?: ebook?.toString().orEmpty()
        val stableName = resolvedAudioName ?: resolvedEbookName.orEmpty()
        val id = buildDictionaryCacheKey(
            uri = "book|$stableSource|$srtIdPart",
            displayName = "$stableName|$srtNamePart"
        )
        return ReaderBook(
            id = id,
            title = title,
            audioUri = audio,
            audioName = resolvedAudioName,
            srtUri = srt,
            srtName = resolvedSrtName,
            ebookUri = ebook,
            ebookName = resolvedEbookName,
            ebookFormat = ebookFormat,
            coverUri = coverUri,
            coverSource = coverSource,
            audioCoverUri = audioCoverUri,
            ebookCoverUri = ebookCoverUri
        )
    }

    fun activateReaderBook(book: ReaderBook, persist: Boolean = true) {
        selectedBookId = book.id
        audioUri = book.audioUri
        audioName = book.audioName
        srtUri = book.srtUri
        srtName = book.srtName
        srtCues = emptyList()
        srtError = null
        pendingCollectionPlayMs = null
        pendingCollectionStopMs = null
        if (persist) {
            persistImportState()
        }
    }

    fun createLegadoReaderIntent(context: Context, book: ReaderBook): Intent {
        return Intent(context, LegadoReaderActivity::class.java).apply {
            putExtra(LegadoReaderActivity.EXTRA_EBOOK_TITLE, book.title)
            book.ebookUri?.let { putExtra(LegadoReaderActivity.EXTRA_EBOOK_URI, it.toString()) }
            book.ebookName?.let { putExtra(LegadoReaderActivity.EXTRA_EBOOK_NAME, it) }
            book.ebookFormat?.let { putExtra(LegadoReaderActivity.EXTRA_EBOOK_FORMAT, it) }
            book.audioUri?.let { putExtra(LegadoReaderActivity.EXTRA_AUDIO_URI, it.toString()) }
            book.srtUri?.let { putExtra(LegadoReaderActivity.EXTRA_SRT_URI, it.toString()) }
        }
    }

    fun openReaderBook(book: ReaderBook, persist: Boolean = true) {
        val targetAudioUri = book.audioUri?.toString()
        if (book.audioUri == null && book.ebookUri != null) {
            activateReaderBook(book, persist = persist)
            context.startActivity(createLegadoReaderIntent(context, book))
            return
        }
        if (targetAudioUri == null) return
        if (
            loadEbookFeatureEnabled(context) &&
            loadEbookDefaultToReader(context) &&
            book.ebookUri != null
        ) {
            BookReaderActivity.stopActiveReaderIfDifferentAudio(targetAudioUri)
            activateReaderBook(book, persist = persist)
            context.startActivity(createLegadoReaderIntent(context, book))
            return
        }
        val isSameReaderBook =
            !BookReaderFloatingBridge.isUiTestModeActive() &&
                BookReaderFloatingBridge.currentAudioUri() == targetAudioUri
        if (isSameReaderBook) {
            activateReaderBook(book, persist = persist)
            val intent = Intent(context, BookReaderActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
            return
        }
        BookReaderActivity.stopActiveReaderIfDifferentAudio(targetAudioUri)
        activateReaderBook(book, persist = persist)
        val intent = Intent(context, BookReaderActivity::class.java).apply {
            putExtra(BookReaderActivity.EXTRA_BOOK_TITLE, book.audioName?.let { buildBookTitle(it, book.srtName) } ?: book.title)
            putExtra(BookReaderActivity.EXTRA_AUDIO_URI, targetAudioUri)
            book.srtUri?.let { putExtra(BookReaderActivity.EXTRA_SRT_URI, it.toString()) }
            book.ebookUri?.let { putExtra(BookReaderActivity.EXTRA_EBOOK_URI, it.toString()) }
            book.ebookName?.let { putExtra(BookReaderActivity.EXTRA_EBOOK_NAME, it) }
            book.ebookFormat?.let { putExtra(BookReaderActivity.EXTRA_EBOOK_FORMAT, it) }
            book.coverUri?.let { putExtra(BookReaderActivity.EXTRA_COVER_URI, it.toString()) }
        }
        context.startActivity(intent)
    }

    fun requestRenameBook(book: ReaderBook) {
        renameTargetBookId = book.id
        renameBookInput = book.title
        renameBookDialogVisible = true
    }

    fun applyRenameBook() {
        val targetId = renameTargetBookId ?: return
        val nextTitle = renameBookInput.trim()
        if (nextTitle.isBlank()) {
            renameBookDialogVisible = false
            renameTargetBookId = null
            return
        }
        readerBooks = readerBooks.map { book ->
            if (book.id == targetId) book.copy(title = nextTitle) else book
        }
        persistImportState()
        renameBookDialogVisible = false
        renameTargetBookId = null
    }

    fun upsertReaderBook(book: ReaderBook, activate: Boolean) {
        // 已存在的书原位更新，保持存储顺序稳定（手动排序的依据）。
        // 新书插到最前而不是追加到末尾：手动排序下也能一眼看到刚导入的书
        //（与"重新关联字幕"那条路径一致）。「最近」排序不看这个顺序，它按
        // addedAtMs 与播放快照时间取较大者排。
        readerBooks = if (readerBooks.any { it.id == book.id }) {
            // 原位替换时保住导入时间：有些路径（重建/重新授权）会拿一个新构造的 ReaderBook
            // 过来，那上面的 addedAtMs 是 0，直接替换会让这本书在「最近」里掉到最后。
            readerBooks.map { current ->
                if (current.id == book.id) {
                    book.copy(addedAtMs = book.addedAtMs.takeIf { it > 0L } ?: current.addedAtMs)
                } else {
                    current
                }
            }
        } else {
            listOf(book.copy(addedAtMs = System.currentTimeMillis())) + readerBooks
        }
        if (activate) {
            activateReaderBook(book, persist = true)
        }
    }

    fun toggleBookSelection(bookId: String) {
        if (selectedBookIds.contains(bookId)) {
            selectedBookIds.remove(bookId)
        } else {
            selectedBookIds.add(bookId)
        }
    }

    fun clearBookSelection() {
        selectedBookIds.clear()
        isBookSelectionMode = false
    }

    fun enterBookSelectionMode() {
        isBookSelectionMode = true
    }

    fun removeBooksFromShelf(removeIds: Set<String>) {
        val remaining = readerBooks.filterNot { it.id in removeIds }
        readerBooks = remaining
        if (selectedBookId in removeIds) {
            val next = remaining.firstOrNull()
            if (next != null) {
                activateReaderBook(next, persist = true)
            } else {
                selectedBookId = null
                audioUri = null
                audioName = null
                srtUri = null
                srtName = null
                srtCues = emptyList()
            }
        }
        persistImportState()
        clearBookSelection()
    }

    fun deleteSelectedBooks(removeIds: Set<String>, deleteSourceFiles: Boolean) {
        if (removeIds.isEmpty()) return
        val deletingBooks = readerBooks.filter { it.id in removeIds }
        logDebug(BOOK_DELETE_LOG_TAG) {
            "deleteSelected count=${removeIds.size} matched=${deletingBooks.size} " +
            "deleteSourceFiles=$deleteSourceFiles ids=${removeIds.joinToString(separator = ",") { it.take(12) }}"
        }
        if (!deleteSourceFiles) {
            val archiveRootUri = addBookFolderUri
            removeBooksFromShelf(removeIds)
            exportStatus = context.getString(R.string.status_books_archiving_sources, removeIds.size)
            scope.launch {
                val archiveResults = withContext(Dispatchers.IO) {
                    deletingBooks.map { book ->
                        archiveBookStorage(
                            context = context,
                            contentResolver = contentResolver,
                            book = book,
                            audiobookFolderUri = archiveRootUri
                        )
                    }
                }
                val archiveCopyFailures = archiveResults.sumOf { it.fileCopyFailures }
                val archiveDeleteFailures = archiveResults.sumOf { it.fileDeleteFailures }
                logDebug(BOOK_DELETE_LOG_TAG) {
                    "deleteSelected archiveResult archiveCopyFailures=$archiveCopyFailures " +
                    "archiveDeleteFailures=$archiveDeleteFailures"
                }
                if (archiveCopyFailures == 0 && archiveDeleteFailures == 0) {
                    exportStatus = null
                    Toast.makeText(
                        context,
                        context.getString(R.string.status_books_archived_sources_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    exportStatus = context.getString(
                        R.string.status_books_archived_with_failures,
                        removeIds.size,
                        archiveCopyFailures,
                        archiveDeleteFailures
                    )
                }
            }
            return
        }
        val deleteResults = if (deleteSourceFiles) {
            deletingBooks.map { book ->
                deleteBookStorage(
                    context = context,
                    contentResolver = contentResolver,
                    book = book,
                    audiobookFolderUri = addBookFolderUri
                )
            }
        } else {
            emptyList()
        }
        val folderDeleteFailures = deleteResults.count { it.folderDeleteAttempted && !it.folderDeleteSucceeded }
        val fileDeleteFailures = deleteResults.sumOf { it.fileDeleteFailures }
        val deletedFolders = deleteResults.count { it.folderDeleteSucceeded }
        logDebug(BOOK_DELETE_LOG_TAG) {
            "deleteSelected result folderFailures=$folderDeleteFailures fileFailures=$fileDeleteFailures " +
            "deletedFolders=$deletedFolders"
        }
        removeBooksFromShelf(removeIds)
        exportStatus = if (folderDeleteFailures == 0 && fileDeleteFailures == 0) {
            if (deletedFolders > 0) {
                context.getString(R.string.status_books_deleted_with_folder, removeIds.size)
            } else {
                context.getString(R.string.status_books_deleted_files_only, removeIds.size)
            }
        } else {
            context.getString(
                R.string.status_books_deleted_with_failures,
                removeIds.size,
                fileDeleteFailures,
                folderDeleteFailures
            )
        }
    }

    fun requestDeleteSelectedBooks() {
        val removeIds = selectedBookIds.toSet()
        if (removeIds.isEmpty()) return
        pendingDeleteBookIds = removeIds
        deleteBooksDeleteSourceFiles = false
        deleteBooksConfirmVisible = true
    }

    fun playCollectedCue(item: BookReaderCollectedCue) {
        val targetBook = readerBooks.firstOrNull { it.title == item.bookTitle }
        if (targetBook == null) {
            exportStatus = context.getString(R.string.collection_play_missing_book)
            return
        }
        activateReaderBook(targetBook, persist = true)
        pendingCollectionPlayMs = item.startMs
        pendingCollectionStopMs = item.endMs.takeIf { it > item.startMs }
        collectionPlayRequestNonce += 1L
    }

    fun confirmAddBookFromDialog() {
        val selectedFolder = addBookFolderUri
        val shouldAutoMove = autoMoveToAudiobookFolder
        if (shouldAutoMove && selectedFolder == null) {
            exportStatus = context.getString(R.string.status_pick_audiobook_folder_first)
            return
        }
        val pickedAudio = addBookAudioUri
        val pickedEbook = addBookEbookUri
        val ebookOnlyImport = loadEbookOnlyImportEnabled(context)
        if (pickedAudio == null && !(ebookOnlyImport && pickedEbook != null)) {
            exportStatus = context.getString(R.string.status_pick_audio_first)
            return
        }
        val pickedSrt = addBookSrtUri
        val pickedAudioName = addBookAudioName
        val pickedSrtName = addBookSrtName
        val pickedEbookName = addBookEbookName
        val pickedEbookFormat = addBookEbookFormat
        val deleteSourceFilesForAutoMove = shouldAutoMove
        logDebug(BOOK_IMPORT_MOVE_LOG_TAG) {
            "confirm autoMove=$shouldAutoMove deleteSourceFiles=$deleteSourceFilesForAutoMove " +
            "ebookOnly=$ebookOnlyImport " +
            "root=${selectedFolder?.toString()?.take(96)} " +
            "audio=${pickedAudio?.toString()?.take(96)} srt=${pickedSrt?.toString()?.take(96)} " +
            "ebook=${pickedEbook?.toString()?.take(96)}"
        }
        scope.launch {
            srtLoading = true
            srtError = null
            val importResult = withContext(Dispatchers.IO) {
                runCatching {
                    if (shouldAutoMove) {
                        val relocated = relocateSelectedBookFilesToAudFolder(
                            context = context,
                            contentResolver = contentResolver,
                            rootFolderUri = selectedFolder ?: error(context.getString(R.string.error_audiobook_folder_required)),
                            audioSourceUri = pickedAudio,
                            audioSourceName = pickedAudioName,
                            srtSourceUri = pickedSrt,
                            srtSourceName = pickedSrtName,
                            ebookSourceUri = pickedEbook,
                            ebookSourceName = pickedEbookName,
                            deleteSourceFiles = deleteSourceFilesForAutoMove
                        )
                        val book = buildReaderBook(
                            audio = relocated.audioUri,
                            audioDisplayName = relocated.audioName,
                            srt = relocated.srtUri,
                            srtDisplayName = relocated.srtName,
                            ebook = relocated.ebookUri,
                            ebookDisplayName = relocated.ebookName,
                            ebookFormat = pickedEbookFormat
                        )
                        val warning = relocated.moveWarnings.takeIf { it.isNotEmpty() }?.joinToString(" ")
                        Triple(book, relocated.folderName, warning)
                    } else {
                        val book = buildReaderBook(
                            audio = pickedAudio,
                            audioDisplayName = pickedAudioName,
                            srt = pickedSrt,
                            srtDisplayName = pickedSrtName,
                            ebook = pickedEbook,
                            ebookDisplayName = pickedEbookName,
                            ebookFormat = pickedEbookFormat
                        )
                        Triple(book, null, null)
                    }
                }
            }
            srtLoading = false
            importResult.onSuccess { (book, folderName, warning) ->
                upsertReaderBook(book, activate = true)
                addBookDialogVisible = false
                addBookAudioUri = null
                addBookAudioName = null
                addBookSrtUri = null
                addBookSrtName = null
                addBookEbookUri = null
                addBookEbookName = null
                addBookEbookFormat = null
                exportStatus = buildString {
                    append(context.getString(R.string.status_book_added, book.title))
                    if (!folderName.isNullOrBlank()) {
                        append(' ')
                        append(context.getString(R.string.status_book_saved_to, folderName))
                        if (!deleteSourceFilesForAutoMove) {
                            append(' ')
                            append(context.getString(R.string.status_book_keep_original))
                        }
                    } else {
                        append(' ')
                        append(context.getString(R.string.status_book_keep_original))
                    }
                    if (!warning.isNullOrBlank()) {
                        append(' ')
                        append(warning)
                    }
                }
            }.onFailure { error ->
                srtError = error.message ?: context.getString(R.string.status_add_book_failed)
                exportStatus = context.getString(R.string.status_add_book_failed_short)
            }
        }
    }

    fun refreshBookshelfFromFolder() {
        val selectedFolder = addBookFolderUri
        if (selectedFolder == null) {
            exportStatus = context.getString(R.string.status_pick_audiobook_folder_first)
            return
        }
        val persistedState = loadPersistedImports(context)
        val persistedTitleById = persistedState.books
            .associate { it.id to it.title.trim() }
            .filterValues { it.isNotBlank() }
        val persistedTitleByAudioUri = persistedState.books
            .mapNotNull { book -> book.audioUri?.let { it to book.title.trim() } }
            .toMap()
            .filterValues { it.isNotBlank() }
        val persistedFocusById = persistedState.books
            .mapNotNull { book ->
                book.coverFocus
                    ?.let { focus -> runCatching { HomeCoverCropFocus.valueOf(focus) }.getOrNull() }
                    ?.let { focus -> book.id to focus }
            }
            .toMap()
        val persistedFocusByAudioUri = persistedState.books
            .mapNotNull { book ->
                val audio = book.audioUri ?: return@mapNotNull null
                book.coverFocus
                    ?.let { focus -> runCatching { HomeCoverCropFocus.valueOf(focus) }.getOrNull() }
                    ?.let { focus -> audio to focus }
            }
            .toMap()
        val persistedStartAdjustmentById = persistedState.books
            .mapNotNull { book ->
                persistedBookCoverAdjustment(book, HomeCoverCropFocus.START)?.let { adjustment -> book.id to adjustment }
            }
            .toMap()
        val persistedStartAdjustmentByAudioUri = persistedState.books
            .mapNotNull { book ->
                val audio = book.audioUri ?: return@mapNotNull null
                persistedBookCoverAdjustment(book, HomeCoverCropFocus.START)?.let { adjustment -> audio to adjustment }
            }
            .toMap()
        val persistedCenterAdjustmentById = persistedState.books
            .mapNotNull { book ->
                persistedBookCoverAdjustment(book, HomeCoverCropFocus.CENTER)?.let { adjustment -> book.id to adjustment }
            }
            .toMap()
        val persistedCenterAdjustmentByAudioUri = persistedState.books
            .mapNotNull { book ->
                val audio = book.audioUri ?: return@mapNotNull null
                persistedBookCoverAdjustment(book, HomeCoverCropFocus.CENTER)?.let { adjustment -> audio to adjustment }
            }
            .toMap()
        // 重建只还原文件信息，导入时间沿用持久化记录（认书用 import key，同 persistImportState）
        val persistedAddedAtByImportKey = persistedState.books
            .filter { it.addedAtMs > 0L }
            .associate { persistedBookImportKey(it) to it.addedAtMs }
        val previousSelectedId = selectedBookId
        scope.launch {
            srtLoading = true
            srtError = null
            val refreshResult = withContext(Dispatchers.IO) {
                runCatching {
                    val scanResult = scanBooksFromRootFolder(
                        context = context,
                        contentResolver = contentResolver,
                        rootFolderUri = selectedFolder,
                        includeEbookOnly = loadEbookOnlyImportEnabled(context)
                    )
                    val refreshedBooks = mutableListOf<ReaderBook>()
                    scanResult.books.forEach { candidate ->
                        runCatching {
                            val rebuilt = buildReaderBook(
                                audio = candidate.audioUri,
                                audioDisplayName = candidate.audioName,
                                srt = candidate.srtUri,
                                srtDisplayName = candidate.srtName,
                                ebook = candidate.ebookUri,
                                ebookDisplayName = candidate.ebookName,
                                ebookFormat = candidate.ebookFormat
                            )
                            val persistedTitle = persistedTitleById[rebuilt.id]
                                ?: rebuilt.audioUri?.toString()?.let { persistedTitleByAudioUri[it] }
                            val persistedFocus = persistedFocusById[rebuilt.id]
                                ?: rebuilt.audioUri?.toString()?.let { persistedFocusByAudioUri[it] }
                                ?: HomeCoverCropFocus.CENTER
                            val persistedStartAdjustment = persistedStartAdjustmentById[rebuilt.id]
                                ?: rebuilt.audioUri?.toString()?.let { persistedStartAdjustmentByAudioUri[it] }
                            val persistedCenterAdjustment = persistedCenterAdjustmentById[rebuilt.id]
                                ?: rebuilt.audioUri?.toString()?.let { persistedCenterAdjustmentByAudioUri[it] }
                            val persistedAddedAt = persistedAddedAtByImportKey[readerBookImportKey(rebuilt)] ?: 0L
                            val titled = if (!persistedTitle.isNullOrBlank()) {
                                rebuilt.copy(title = persistedTitle)
                            } else {
                                rebuilt
                            }
                            titled.copy(
                                coverFocus = persistedFocus,
                                startBookCoverAdjustment = persistedStartAdjustment,
                                centerBookCoverAdjustment = persistedCenterAdjustment,
                                addedAtMs = persistedAddedAt
                            )
                        }.onSuccess { refreshedBooks += it }
                    }
                    refreshedBooks to scanResult.skippedFolders
                }
            }
            srtLoading = false
            refreshResult.onSuccess { (books, skippedFolders) ->
                if (books.isEmpty()) {
                    srtError = context.getString(R.string.status_no_books_found_to_import)
                    exportStatus = context.getString(R.string.status_refresh_done_zero)
                    return@onSuccess
                }
                readerBooks = books
                clearBookSelection()
                val selected = books.firstOrNull { it.id == previousSelectedId } ?: books.first()
                activateReaderBook(selected, persist = false)
                selectedBookId = selected.id
                persistImportState()

                exportStatus = buildString {
                    append(context.getString(R.string.status_refresh_done, books.size))
                    if (skippedFolders.isNotEmpty()) {
                        append(' ')
                        append(context.getString(R.string.status_refresh_skipped_missing_audio, skippedFolders.size))
                    }
                }
            }.onFailure { error ->
                srtError = error.message ?: context.getString(R.string.status_refresh_failed)
                exportStatus = context.getString(R.string.status_refresh_failed_short)
            }
        }
    }

    fun tryExportCardToAnki(card: MinedCard) {
        when (detectAnkiAvailability(context, requirePermission = true)) {
            AnkiAvailabilityState.PERMISSION_MISSING -> {
                pendingAnkiCard = card
                exportStatus = "Requesting Anki access permission..."
                val launchedIntent = createAnkiPermissionRequestIntent(context)
                if (launchedIntent != null) {
                    awaitingExternalAnkiPermission = true
                    requestExternalAnkiPermissionLauncher.launch(launchedIntent)
                } else {
                    requestAnkiPermissionLauncher.launch(ANKI_READ_WRITE_PERMISSION)
                }
                return
            }
            AnkiAvailabilityState.NOT_INSTALLED,
            AnkiAvailabilityState.API_UNAVAILABLE -> {
                exportStatus = ankiAvailabilityErrorMessage(context, requirePermission = true)
                    ?: context.getString(R.string.error_anki_not_installed)
                return
            }
            AnkiAvailabilityState.READY -> Unit
        }

        if (ankiModels.isEmpty()) {
            refreshAnkiCatalog()
            exportStatus = "Loading Anki models. Select a model/template, then export."
            return
        }

        val config = currentAnkiExportConfigOrNull()
        if (config == null) {
            exportStatus = "Select an Anki model/template first, then export."
            return
        }
        if (!hasAnyAnkiFieldTemplate(config.fieldTemplates)) {
            exportStatus = context.getString(R.string.status_anki_fields_empty)
            return
        }
        scope.launch {
            val exportResult = withContext(Dispatchers.IO) {
                exportToAnkiDroidApiResult(context, card, config)
            }
            exportStatus = ankiExportResultMessage(context, exportResult)
        }
    }

    LaunchedEffect(ankiPermissionGranted) {
        if (ankiPermissionGranted && ankiModels.isEmpty()) {
            refreshAnkiCatalog()
        }
    }

    LaunchedEffect(ankiPermissionGranted, pendingAnkiCard) {
        val card = pendingAnkiCard ?: return@LaunchedEffect
        if (!ankiPermissionGranted) return@LaunchedEffect
        pendingAnkiCard = null
        tryExportCardToAnki(card)
    }

    DisposableEffect(context, awaitingExternalAnkiPermission) {
        val activity = context as? ComponentActivity
        val observer = activity?.let {
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && awaitingExternalAnkiPermission) {
                    awaitingExternalAnkiPermission = false
                    ankiPermissionGranted = hasAnkiReadWritePermission(context)
                    if (ankiPermissionGranted) {
                        exportStatus = "Anki access permission granted."
                    }
                }
            }
        }
        if (observer != null) {
            activity.lifecycle.addObserver(observer)
        }
        onDispose {
            if (observer != null) {
                activity.lifecycle.removeObserver(observer)
            }
        }
    }

    LaunchedEffect(activeSection) {
        closeMainLookupPopup()
        if (activeSection == MiningSection.COLLECTIONS) {
            refreshCollectedCues()
        }
        if (activeSection != MiningSection.DICTIONARY) {
            lookupQuery = ""
            lookupLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshCollectedCues()
        val persistedAnki = loadPersistedAnkiConfig(context)
        ankiDeckName = persistedAnki.deckName
        ankiModelName = persistedAnki.modelName
        ankiTagsInput = persistedAnki.tags
        ankiFieldTemplates.clear()
        persistedAnki.fieldTemplates.forEach { (field, template) ->
            ankiFieldTemplates[field] = template
        }

        val persisted = loadPersistedImports(context)
        persistedImportsVersion = loadPersistedImportsVersion(context)
        addBookFolderUri = persisted.audiobookFolderUri
            ?.let { rawUri -> runCatching { Uri.parse(rawUri) }.getOrNull() }
        addBookFolderName = persisted.audiobookFolderName?.ifBlank { null }
            ?: addBookFolderUri?.let { uri ->
                queryTreeDisplayName(context, contentResolver, uri)
            }
        ebookFeatureEnabled = loadEbookFeatureEnabled(context)
        autoMoveToAudiobookFolder = persisted.autoMoveToAudiobookFolder
        keepSourceFilesWhenAutoMove = persisted.keepSourceFilesWhenAutoMove
        importOnboardingCompleted = persisted.importOnboardingCompleted
        importGuideVisible = !persisted.importOnboardingCompleted
        persistedImportsLoaded = true
        homeLibraryView = when (persisted.homeLibraryView.uppercase(Locale.ROOT)) {
            HomeLibraryView.LIST.name -> HomeLibraryView.LIST
            else -> HomeLibraryView.BOOKSHELF
        }
        homeCoverAspect = when (persisted.homeCoverAspect.uppercase(Locale.ROOT)) {
            HomeCoverAspect.SQUARE.name -> HomeCoverAspect.SQUARE
            else -> HomeCoverAspect.BOOK
        }
        homeLibrarySort = when (persisted.homeLibrarySort.uppercase(Locale.ROOT)) {
            HomeLibrarySort.RECENT.name -> HomeLibrarySort.RECENT
            HomeLibrarySort.TITLE.name -> HomeLibrarySort.TITLE
            HomeLibrarySort.MANUAL.name -> HomeLibrarySort.MANUAL
            // 空值/未知值（含旧版本没写过这一项的情况）→ 默认"最近"
            else -> HomeLibrarySort.RECENT
        }

        if (persisted.books.isNotEmpty()) {
            srtLoading = true
            srtError = null
            val restoreBooksResult = withContext(Dispatchers.IO) {
                runCatching {
                    val restoredBooks = mutableListOf<ReaderBook>()
                    val failedBooks = mutableListOf<String>()
                    persisted.books.forEach { savedBook ->
                        val audio = savedBook.audioUri?.let { raw -> runCatching { Uri.parse(raw) }.getOrNull() }
                        val srt = savedBook.srtUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                        val ebook = savedBook.ebookUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                        if (audio == null && ebook == null) {
                            failedBooks += savedBook.title.ifBlank { savedBook.audioName ?: savedBook.ebookName.orEmpty() }
                            return@forEach
                        }
                        runCatching {
                            val rebuilt = buildReaderBook(
                                audio = audio,
                                audioDisplayName = savedBook.audioName,
                                srt = srt,
                                srtDisplayName = savedBook.srtName,
                                ebook = ebook,
                                ebookDisplayName = savedBook.ebookName,
                                ebookFormat = savedBook.ebookFormat,
                                cachedAudioCoverUri = parseCachedCoverUri(savedBook.audioCoverUri),
                                cachedEbookCoverUri = parseCachedCoverUri(savedBook.ebookCoverUri)
                            )
                            val coverFocus = when (savedBook.coverFocus?.uppercase(Locale.ROOT)) {
                                HomeCoverCropFocus.START.name -> HomeCoverCropFocus.START
                                else -> HomeCoverCropFocus.CENTER
                            }
                            val persistedStartAdjustment = persistedBookCoverAdjustment(savedBook, HomeCoverCropFocus.START)
                            val persistedCenterAdjustment = persistedBookCoverAdjustment(savedBook, HomeCoverCropFocus.CENTER)
                            val persistedTitle = savedBook.title.trim()
                            val restored = if (persistedTitle.isNotBlank()) {
                                rebuilt.copy(title = persistedTitle)
                            } else {
                                rebuilt
                            }
                            restored.copy(
                                coverFocus = coverFocus,
                                startBookCoverAdjustment = persistedStartAdjustment,
                                centerBookCoverAdjustment = persistedCenterAdjustment,
                                // 与「刷新文件夹」同理：重建只还原文件信息，导入时间要跟着记录走。
                                addedAtMs = savedBook.addedAtMs
                            )
                        }.onSuccess { restoredBooks += it }
                            .onFailure {
                                failedBooks += savedBook.title.ifBlank { savedBook.audioName ?: savedBook.ebookName.orEmpty() }
                            }
                    }
                    restoredBooks to failedBooks
                }
            }
            srtLoading = false
            restoreBooksResult.onSuccess { (restoredBooks, failedBooks) ->
                readerBooks = restoredBooks
                val restoredById = restoredBooks.associateBy { it.id }
                val indexedBooks = persisted.books.map { savedBook ->
                    val restored = restoredById[savedBook.id] ?: return@map savedBook
                    savedBook.copy(
                        audioCoverUri = restored.audioCoverUri?.toString(),
                        ebookCoverUri = restored.ebookCoverUri?.toString()
                    )
                }
                if (indexedBooks != persisted.books) {
                    val loadedVersion = persistedImportsVersion
                    scope.launch(Dispatchers.IO) {
                        if (loadedVersion != null &&
                            loadPersistedImportsVersion(context) == loadedVersion
                        ) {
                            savePersistedImports(
                                context = context,
                                state = persisted.copy(books = indexedBooks)
                            )
                        }
                    }
                }
                val restoredSelected = restoredBooks.firstOrNull { it.id == persisted.selectedBookId }
                    ?: restoredBooks.firstOrNull()
                if (restoredSelected != null) {
                    activateReaderBook(restoredSelected, persist = false)
                    selectedBookId = restoredSelected.id
                } else {
                    selectedBookId = null
                    audioUri = null
                    audioName = null
                    srtUri = null
                    srtName = null
                    srtCues = emptyList()
                }
                if (failedBooks.isNotEmpty()) {
                    exportStatus = context.getString(R.string.status_restore_books_failed_count, failedBooks.size)
                }
            }.onFailure { error ->
                readerBooks = emptyList()
                selectedBookId = null
                audioUri = null
                audioName = null
                srtUri = null
                srtName = null
                srtCues = emptyList()
                srtError = context.getString(R.string.status_restore_book_failed, error.message ?: "unknown error")
                exportStatus = context.getString(R.string.status_restore_bookshelf_failed)
            }
        } else {
            // Backward compatibility with older single-book persistence format.
            val restoredAudioRaw = persisted.audioUri?.let { rawUri ->
                runCatching { Uri.parse(rawUri) }.getOrNull()
            }
            val restoredSrtRaw = persisted.srtUri?.let { rawUri ->
                runCatching { Uri.parse(rawUri) }.getOrNull()
            }

            if (restoredAudioRaw != null) {
                srtLoading = true
                srtError = null
                val restoreResult = withContext(Dispatchers.IO) {
                    runCatching {
                        val restoredAudioName = persisted.audioName?.ifBlank { null }
                            ?: queryDisplayName(contentResolver, restoredAudioRaw)
                        val restoredSrtName = restoredSrtRaw?.let {
                            persisted.srtName?.ifBlank { null }
                                ?: queryDisplayName(contentResolver, it)
                        }
                        Triple(restoredAudioRaw, restoredAudioName, Pair(restoredSrtRaw, restoredSrtName))
                    }
                }
                srtLoading = false
                restoreResult.onSuccess { restored ->
                    val (audio, audioDisplay, srtPair) = restored
                    val (srt, srtDisplay) = srtPair
                    audioUri = audio
                    audioName = audioDisplay
                    srtUri = srt
                    srtName = srtDisplay
                    srtCues = emptyList()
                    val restoredBook = buildReaderBook(
                        audio = audio,
                        audioDisplayName = audioDisplay,
                        srt = srt,
                        srtDisplayName = srtDisplay
                    )
                    readerBooks = listOf(restoredBook)
                    selectedBookId = restoredBook.id
                    persistImportState()
                }.onFailure { error ->
                    audioUri = null
                    audioName = null
                    srtUri = null
                    srtName = null
                    srtCues = emptyList()
                    srtError = "Failed to restore book files. Please re-add audio. ${error.message ?: "unknown error"}"
                    exportStatus = "Saved book file permission expired. Re-add audio."
                }
            }
        }

        dictionaryController.restorePersistedDictionaries(
            persistedRefs = persisted.dictionaries,
            onPersistDictionaryRefs = ::persistImportState
        )

        if (ankiPermissionGranted) {
            refreshAnkiCatalog()
        }
    }

    fun mainHoshiLookupOptions(showRangeSelection: Boolean = false): LookupPopupOptions =
        LookupPopupOptions(
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
            showRangeSelection = showRangeSelection,
            showPlayAudio = audiobookSettings.lookupPlaybackAudioEnabled,
            popupActionBar = true,
        )
    val mainHoshiLookupAssets = remember(context) { LookupPopupAssets.load(context) }

    fun mainHoshiFallbackSelection(query: String, anchor: ReaderLookupAnchor?): ReaderSelectionData {
        val densityScale = rootDensity.density.coerceAtLeast(0.1f)
        val bounds = anchor.boundingRectCoreOrNull() ?: Rect(
            left = view.width * 0.5f,
            top = view.height * 0.45f,
            right = view.width * 0.5f + 1f,
            bottom = view.height * 0.45f + 1f
        )
        return ReaderSelectionData(
            text = query,
            sentence = query,
            rect = ReaderSelectionRect(
                x = (bounds.left / densityScale).toDouble(),
                y = (bounds.top / densityScale).toDouble(),
                width = ((bounds.right - bounds.left) / densityScale).coerceAtLeast(1f).toDouble(),
                height = ((bounds.bottom - bounds.top) / densityScale).coerceAtLeast(1f).toDouble()
            ),
            normalizedOffset = 0,
            sentenceOffset = 0
        )
    }

    fun clearMainHoshiChildPopups() {
        mainHoshiLookupPopups.clear()
        mainHoshiLookupCue = null
        mainHoshiLookupSelectedRange = null
        mainHoshiLookupAudioUri = null
        mainHoshiLookupTitle = ""
    }

    fun currentStatisticsBookKey(): String? {
        val selected = selectedBookId?.let { id -> readerBooks.firstOrNull { it.id == id } }
            ?: readerBooks.firstOrNull()
        if (selected?.audioUri == null) return null
        return selected?.let { buildReaderBookPlaybackKey(it) }
    }

    fun renderMainHoshiFirstLayerHtml(
        popup: LookupPopupItem,
        backgroundColorCss: String? = null
    ): String =
        LookupPopupHtml.render(
            results = popup.state.results,
            assets = mainHoshiLookupAssets,
            dictionaryStyles = popup.state.dictionaryStyles,
            settings = popup.state.dictionarySettings,
            audioSettings = audiobookSettings,
            showPlayAudio = audiobookSettings.lookupPlaybackAudioEnabled,
            backgroundColorCss = backgroundColorCss,
            darkMode = isDarkTheme,
            eInkMode = false,
        )

    fun createMainHoshiFirstLayerPopup(
        selection: ReaderSelectionData,
        showRangeSelection: Boolean = false
    ): Pair<LookupPopupItem, Int>? {
        return mainHoshiLookupSession.createPopup(
            selection = selection,
            options = mainHoshiLookupOptions(showRangeSelection = showRangeSelection),
        )
    }

    fun showDictionaryFirstLayerLookup(rawQuery: String) {
        val query = rawQuery.trim()
        lookupQuery = query
        if (query.isBlank()) {
            lookupLoading = false
            dictionaryFirstLayerHtml = ""
            dictionaryFirstLayerResults = emptyList()
            dictionaryFirstLayerClearSelectionSignal += 1
            clearMainHoshiChildPopups()
            return
        }
        lookupLoading = true
        runCatching {
            val selection = mainHoshiFallbackSelection(query, anchor = null)
            val popup = createMainHoshiFirstLayerPopup(selection = selection)
            if (popup == null) {
                dictionaryFirstLayerHtml = ""
                dictionaryFirstLayerResults = emptyList()
                dictionaryFirstLayerClearSelectionSignal += 1
                clearMainHoshiChildPopups()
                logDebug("MainHoshiResultPopup") { "dictionary first-layer empty query='${query.take(32)}'" }
                return@runCatching
            }
            dictionaryFirstLayerResults = popup.first.state.results
            dictionaryFirstLayerHtml = renderMainHoshiFirstLayerHtml(
                popup = popup.first,
                backgroundColorCss = dictionaryPageBackgroundCss
            )
            dictionaryFirstLayerClearSelectionSignal += 1
            clearMainHoshiChildPopups()
            mainHoshiLookupCue = null
            mainHoshiLookupSelectedRange = null
            mainHoshiLookupAudioUri = audioUri
            mainHoshiLookupTitle = query
            recordStatisticsLookup(context, currentStatisticsBookKey())
            logDebug("MainHoshiResultPopup") { "dictionary first-layer applied query='${query.take(32)}' results=${popup.first.state.results.size}" }
        }.onFailure { error ->
            dictionaryFirstLayerHtml = ""
            dictionaryFirstLayerResults = emptyList()
            dictionaryFirstLayerClearSelectionSignal += 1
            exportStatus = "Lookup failed: ${error.message ?: "unknown error"}"
        }
        lookupLoading = false
    }

    fun showCollectionFirstLayerLookup(
        selection: ReaderSelectionData,
        sourceRange: IntRange,
        cue: SubtitleCue?,
        audioForExport: Uri?,
    ) {
        val query = selection.text.trim()
        if (query.isBlank()) return
        runCatching {
            val popup = createMainHoshiFirstLayerPopup(selection = selection)
            if (popup == null) {
                collectionFirstLayerHtml = ""
                collectionFirstLayerResults = emptyList()
                collectionFirstLayerClearSelectionSignal += 1
                exportStatus = context.getString(R.string.bookreader_lookup_failed)
                logDebug("MainHoshiResultPopup") { "collection first-layer empty query='${query.take(32)}'" }
                return@runCatching
            }
            val matchedText = popup.first.state.results.firstOrNull()?.matched ?: query
            val selectedRange = matchedRangeFromSentenceOffset(
                sentence = selection.sentence,
                sentenceOffset = selection.sentenceOffset,
                matchedText = matchedText
            ) ?: sourceRange
            collectionLookupPreviewSelectedRange = selectedRange
            collectionFirstLayerResults = popup.first.state.results
            collectionFirstLayerHtml = renderMainHoshiFirstLayerHtml(popup.first)
            collectionFirstLayerClearSelectionSignal += 1
            clearMainHoshiChildPopups()
            mainHoshiLookupCue = cue
            mainHoshiLookupSelectedRange = selectedRange
            mainHoshiLookupAudioUri = audioForExport
            mainHoshiLookupTitle = query
            recordStatisticsLookup(context, currentStatisticsBookKey())
            logDebug("MainHoshiResultPopup") { "collection first-layer applied query='${query.take(32)}' results=${popup.first.state.results.size}" }
        }.onFailure { error ->
            collectionFirstLayerHtml = ""
            collectionFirstLayerResults = emptyList()
            collectionFirstLayerClearSelectionSignal += 1
            exportStatus = "Lookup failed: ${error.message ?: "unknown error"}"
        }
    }

    fun showMainHoshiLookup(
        selection: ReaderSelectionData,
        cue: SubtitleCue?,
        selectedRange: IntRange?,
        audioForExport: Uri?,
        titleForExport: String,
        showRangeSelection: Boolean = false
    ): Boolean {
        logDebug("MainHoshiResultPopup") {
            "showMainHoshiLookup start text='${selection.text.take(32)}' range=${selectedRange ?: "null"} rect=${selection.rect.x},${selection.rect.y} ${selection.rect.width}x${selection.rect.height} showRange=$showRangeSelection"
        }
        val popup = mainHoshiLookupSession.createPopup(
            selection = selection,
            options = mainHoshiLookupOptions(showRangeSelection = showRangeSelection),
        )
        if (popup == null) {
            logDebug("MainHoshiResultPopup") { "showMainHoshiLookup empty text='${selection.text.take(32)}'" }
            return false
        }
        mainHoshiLookupPopups.clear()
        mainHoshiLookupPopups.add(popup.first)
        mainHoshiLookupCue = cue
        mainHoshiLookupSelectedRange = selectedRange
        mainHoshiLookupAudioUri = audioForExport
        mainHoshiLookupTitle = titleForExport.ifBlank { selection.text }
        recordStatisticsLookup(context, currentStatisticsBookKey())
        logDebug("MainHoshiResultPopup") {
            "showMainHoshiLookup applied text='${selection.text.take(32)}' popupCount=${mainHoshiLookupPopups.size}"
        }
        return true
    }

    fun pushMainHoshiRecursiveLookup(selection: ReaderSelectionData): Boolean {
        logDebug("MainHoshiResultPopup") {
            "pushRecursiveLookup start currentSize=${mainHoshiLookupPopups.size} text='${selection.text.take(48)}' " +
            "sentenceLen=${selection.sentence.length} sentenceOffset=${selection.sentenceOffset} " +
            "rect=${selection.rect.x},${selection.rect.y} ${selection.rect.width}x${selection.rect.height}"
        }
        val popup = mainHoshiLookupSession.createPopup(
            selection = selection,
            options = mainHoshiLookupOptions(showRangeSelection = false),
        )
        if (popup == null) {
            logDebug("MainHoshiResultPopup") { "pushRecursiveLookup empty text='${selection.text.take(32)}'" }
            return false
        }
        mainHoshiLookupPopups.clear()
        mainHoshiLookupPopups.add(popup.first)
        logDebug("MainHoshiResultPopup") {
            "pushRecursiveLookup applied text='${selection.text.take(48)}' popupId=${popup.first.id} " +
            "results=${popup.first.state.results.size} popupCount=${mainHoshiLookupPopups.size}"
        }
        return true
    }

    fun triggerMainHoshiQueryLookup(rawQuery: String) {
        val query = rawQuery.trim()
        lookupQuery = query
        if (query.isBlank()) {
            lookupLoading = false
            closeMainLookupPopup()
            return
        }
        if (effectiveLookupDictionaries.isEmpty()) {
            exportStatus = context.getString(R.string.bookreader_lookup_no_dict)
            return
        }
        showDictionaryFirstLayerLookup(query)
    }

    fun openMainLookupCuePreview(cue: SubtitleCue, sourceBookTitle: String? = null) {
        if (effectiveLookupDictionaries.isEmpty()) {
            exportStatus = context.getString(R.string.bookreader_lookup_no_dict)
            return
        }
        val exportAudioUri = if (sourceBookTitle.isNullOrBlank()) {
            audioUri
        } else {
            readerBooks.firstOrNull { it.title == sourceBookTitle }?.audioUri ?: audioUri
        }
        mainHoshiLookupPopups.clear()
        mainHoshiLookupCue = null
        mainHoshiLookupSelectedRange = null
        mainHoshiLookupAudioUri = null
        mainHoshiLookupTitle = ""
        collectionLookupPreviewVisible = true
        collectionLookupPreviewSentence = cue.text
        collectionLookupPreviewCue = cue
        collectionLookupPreviewSelectedRange = null
        collectionLookupPreviewAudioUri = exportAudioUri
        collectionFirstLayerHtml = ""
        collectionFirstLayerResults = emptyList()
        collectionFirstLayerClearSelectionSignal += 1
    }

    fun openTemporaryLookupPlayer(item: BookReaderCollectedCue) {
        if (effectiveLookupDictionaries.isEmpty()) {
            exportStatus = context.getString(R.string.bookreader_lookup_no_dict)
            return
        }
        val book = readerBooks.firstOrNull { it.title == item.bookTitle }
        val audio = book?.audioUri
        if (audio == null) {
            exportStatus = context.getString(R.string.collection_play_missing_book)
            return
        }
        context.startActivity(
            Intent(context, BookReaderActivity::class.java).apply {
                putExtra(BookReaderActivity.EXTRA_BOOK_TITLE, item.bookTitle)
                putExtra(BookReaderActivity.EXTRA_AUDIO_URI, audio.toString())
                book.srtUri?.let { putExtra(BookReaderActivity.EXTRA_SRT_URI, it.toString()) }
                putExtra(BookReaderActivity.EXTRA_TEMPORARY_LOOKUP_MODE, true)
                putExtra(BookReaderActivity.EXTRA_TEMPORARY_LOOKUP_START_MS, item.startMs.coerceAtLeast(0L))
                putExtra(
                    BookReaderActivity.EXTRA_TEMPORARY_LOOKUP_END_MS,
                    item.endMs.coerceAtLeast(item.startMs + 1L)
                )
                book.coverUri?.let { putExtra(BookReaderActivity.EXTRA_COVER_URI, it.toString()) }
            }
        )
    }

    fun exportMainHoshiLookupEntryToAnkiAsync(content: String, onComplete: (Boolean) -> Unit) {
        scope.launch {
            logDebug("AnkiExportDebug") {
                "mainHoshiExport rawContentLen=${content.length} rawPrefix=${content.take(120)}"
            }
            val success = runCatching {
                val payload = runCatching { JSONObject(content) }.getOrNull() ?: run {
                    logDebug("AnkiExportDebug") { "mainHoshiExport payloadParseFailed" }
                    return@runCatching false
                }
                val expression = payload.optString("expression").trim().ifBlank {
                    payload.optString("matched").trim()
                }
                if (expression.isBlank()) {
                    logDebug("AnkiExportDebug") {
                        "mainHoshiExport expressionBlank payloadKeys=${payload.keys().asSequence().joinToString(",")}"
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
                val sourceCue = mainHoshiLookupCue
                val cueText = sourceCue?.text?.trim()?.takeIf { it.isNotBlank() }
                    ?: mainHoshiLookupTitle.trim().ifBlank { expression }
                val cue = sourceCue ?: SubtitleCue(startMs = 0L, endMs = 0L, text = cueText)
                val popupSelectionText = payload.optString("popupSelectionText").trim().takeIf { it.isNotBlank() }
                    ?: mainHoshiLookupSelectedRange?.let { range ->
                        val start = range.first.coerceIn(0, cue.text.length)
                        val endExclusive = (range.last + 1).coerceIn(start, cue.text.length)
                        if (endExclusive > start) cue.text.substring(start, endExclusive) else null
                    }?.trim()?.takeIf { it.isNotBlank() }
                // A normal query-page lookup has no subtitle cue. Do not pass the
                // currently selected book audio as a cue source, otherwise the
                // Anki exporter tries to cut a synthetic 0ms..0ms clip.
                val exportAudioUri = sourceCue?.let { mainHoshiLookupAudioUri }
                logDebug("AnkiExportDebug") {
                    "mainHoshiExport payload expression=$expression reading=${reading.orEmpty()} dict=$primaryDictionaryName " +
                    "glossaryLen=${glossary.length} frequencyLen=${frequency.length} pitchLen=${pitch.length} " +
                    "popupSelectionLen=${popupSelectionText.orEmpty().length} audioUri=${exportAudioUri?.toString().orEmpty()} " +
                    "lookupCue=${sourceCue?.text.orEmpty().take(48)}"
                }
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
                            cueText = cue.text,
                            cueStartMs = cue.startMs,
                            cueEndMs = cue.endMs,
                            audioUri = exportAudioUri,
                            lookupAudioUri = preparedLookupAudio?.uri,
                            bookTitle = readerBooks.firstOrNull { it.audioUri == exportAudioUri }?.title,
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
                            sentenceOverride = cue.text,
                            lookupTermOverride = expression
                        )
                    } finally {
                        preparedLookupAudio?.cleanup?.invoke()
                    }
                }
                val message = ankiExportResultMessage(context, exportResult)
                logDebug("AnkiExportDebug") {
                    "mainHoshiExport result=${exportResult.javaClass.simpleName} message=${message.take(220)}"
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
                android.util.Log.w("AnkiExportDebug", "mainHoshiExport async failed", error)
                false
            }
            onComplete(success)
        }
    }

    fun checkMainAnkiDuplicateAsync(expression: String, onComplete: (AnkiDuplicateCheckResult) -> Unit) {
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    checkAnkiDuplicateByFirstFieldAsync(context, expression)
                }
            }.getOrElse { error ->
                android.util.Log.w("AnkiExportDebug", "main duplicate async failed expression=${expression.take(32)}", error)
                AnkiDuplicateCheckResult()
            }
            onComplete(result)
        }
    }

    fun refreshLookupIfNeeded() {
        invalidateDictionaryLookupCaches()
        bumpDictionaryDataVersion(context)
        if (lookupQuery.isNotBlank()) {
            triggerMainHoshiQueryLookup(lookupQuery)
        }
    }

    val pickBookAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        keepReadPermission(context, uri)
        addBookAudioUri = uri
        addBookAudioName = queryDisplayName(contentResolver, uri)
        addBookEbookUri = null
        addBookEbookName = null
        addBookEbookFormat = null
    }

    val pickBookSrtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        keepReadPermission(context, uri)
        addBookSrtUri = uri
        addBookSrtName = queryDisplayName(contentResolver, uri)
    }

    val pickBookEbookLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (!loadEbookOnlyImportEnabled(context) && (addBookAudioUri == null || addBookSrtUri == null)) {
            exportStatus = context.getString(R.string.status_pick_audio_srt_before_ebook)
            return@rememberLauncherForActivityResult
        }
        keepReadPermission(context, uri)
        val displayName = queryDisplayName(contentResolver, uri)
        val format = inferLocalReaderBookFormat(displayName, contentResolver.getType(uri))
        if (format == null) {
            exportStatus = context.getString(R.string.status_pick_ebook_file)
            return@rememberLauncherForActivityResult
        }
        addBookEbookUri = uri
        addBookEbookName = displayName
        addBookEbookFormat = format
    }

    val pickBookFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        keepReadPermission(context, uri)
        addBookFolderUri = uri
        addBookFolderName = queryTreeDisplayName(context, contentResolver, uri)
        addBookAudioUri = null
        addBookAudioName = null
        addBookSrtUri = null
        addBookSrtName = null
        addBookEbookUri = null
        addBookEbookName = null
        addBookEbookFormat = null
        persistImportState()
    }

    val pickDictionaryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        dictionaryController.importDictionaries(
            uris = uris,
            onPersistDictionaryRefs = ::persistImportState,
            onLookupDataChanged = ::refreshLookupIfNeeded
        )
    }

    val activeCue = remember(positionMs, srtCues) { findCueAtTime(srtCues, positionMs) }
    val selectedReaderBook = remember(readerBooks, selectedBookId) {
        readerBooks.firstOrNull { it.id == selectedBookId }
    }

    val dictionarySpecificVariableChoices = remember(loadedDictionaries) {
        loadedDictionaries.map { "{single-glossary-${it.name}}" }.distinct()
    }
    val fieldVariableChoices = remember(dictionarySpecificVariableChoices) {
        (FIELD_VARIABLE_CHOICES + dictionarySpecificVariableChoices).distinct()
    }
    val sliderValue = when {
        durationMs <= 0L -> 0f
        else -> (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    val dictionaryCount = loadedDictionaries.size
    val cueLookupTokens = remember(activeCue?.text) {
        activeCue?.let { tokenizeLookupTerms(it.text).take(12) } ?: emptyList()
    }
    val mainPageScrollState = rememberScrollState()
    val dictionaryManagerScrollState = rememberScrollState()
    // 首页内容视口（窗口坐标），供手动排序拖拽到边缘时自动滚动使用。
    var shelfViewportBounds by remember { mutableStateOf<Rect?>(null) }
    // 滚动内容列的 LayoutCoordinates：卡片槽位与手指位置都换算到它的本地
    // （内容）坐标系。内容坐标对滚动/视口裁剪免疫，卡片滚出视口仍真实，
    // 而窗口坐标(boundsInWindow)在卡片离开视口后会退化（裁剪/归零）。
    var shelfColumnCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // 观察器最新记录：手指在内容坐标系中的位置，及其所在帧的滚动值。
    // 手指静止而页面自动滚动时，真实内容位置 = dragFingerContent.y
    // + (当前scroll - dragFingerScrollAnchor)。
    var dragFingerContent by remember { mutableStateOf(Offset.Zero) }
    var dragFingerScrollAnchor by remember { mutableFloatStateOf(0f) }
    // 书架手动排序可用的总开关（观察器与手势共用）。
    val manualSortTrackEnabled = homeLibrarySort == HomeLibrarySort.MANUAL &&
        !isBookSelectionMode &&
        !homeCoverFocusEditMode

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            when {
                activeSection == MiningSection.MAIN -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FloatingActionButton(
                            onClick = { refreshBookshelfFromFolder() },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.home_refresh),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        FloatingActionButton(
                            onClick = {
                                ebookFeatureEnabled = loadEbookFeatureEnabled(context)
                                addBookDialogVisible = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = stringResource(R.string.home_add_book),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                activeSection == MiningSection.COLLECTIONS && collectedCues.isNotEmpty() -> {
                    FloatingActionButton(
                        onClick = { clearCollectionsConfirmVisible = true }
                    ) {
                        Text(stringResource(R.string.home_clear))
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = hoshiBottomNavigationBackgroundColor(),
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = activeSection == MiningSection.MAIN,
                    onClick = { activeSection = MiningSection.MAIN },
                    // Visual glyph (intentional): keep as a symbol and do not localize.
                    icon = { Text("本") },
                    label = { Text(stringResource(R.string.nav_home)) }
                )
                NavigationBarItem(
                    selected = activeSection == MiningSection.DICTIONARY,
                    onClick = { activeSection = MiningSection.DICTIONARY },
                    // Visual glyph (intentional): keep as a symbol and do not localize.
                    icon = { Text("調") },
                    label = { Text(stringResource(R.string.nav_dictionary)) }
                )
                NavigationBarItem(
                    selected = activeSection == MiningSection.COLLECTIONS,
                    onClick = { activeSection = MiningSection.COLLECTIONS },
                    // Visual glyph (intentional): keep as a symbol and do not localize.
                    icon = { Text("蔵") },
                    label = { Text(stringResource(R.string.collections_title)) }
                )
                NavigationBarItem(
                    selected = activeSection == MiningSection.SETTINGS,
                    onClick = { activeSection = MiningSection.SETTINGS },
                    // Visual glyph (intentional): keep as a symbol and do not localize.
                    icon = { Text("設") },
                    label = { Text(stringResource(R.string.nav_settings)) }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            val mainContentModifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom = 16.dp
                )
                .let { baseModifier ->
                    if (activeSection == MiningSection.DICTIONARY) {
                        baseModifier
                    } else {
                        baseModifier.verticalScroll(mainPageScrollState)
                    }
                }
                .onGloballyPositioned {
                    shelfViewportBounds = it.boundsInWindow()
                    shelfColumnCoords = it
                }
                // 手动排序拖拽观察器：不消费任何事件，仅把手指位置换算进内容坐标。
                // 挂在滚动内容列节点上：该节点在内容滚动/视口裁剪时保持真实局部坐标
                // （卡片节点滚出视口后会退化，不能作为拖拽几何来源）。
                .pointerInput(manualSortTrackEnabled) {
                    if (manualSortTrackEnabled) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            dragFingerContent = down.position
                            dragFingerScrollAnchor = mainPageScrollState.value.toFloat()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull {
                                    it.id == down.id && it.pressed
                                }
                                if (change == null) break
                                dragFingerContent = change.position
                                dragFingerScrollAnchor = mainPageScrollState.value.toFloat()
                            }
                        }
                    }
                }
            Column(
                modifier = mainContentModifier,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)

            val adjustingBook = adjustingBookCoverId?.let { bookId ->
                readerBooks.firstOrNull { it.id == bookId }
            }
            val adjustingCoverUri = adjustingBook?.audioCoverUri
            if (adjustingBook != null && adjustingCoverUri != null) {
                val initialAdjustment = effectiveBookCoverAdjustment(adjustingBook)
                var previewBitmap by remember(adjustingBook.id, adjustingCoverUri) {
                    mutableStateOf<Bitmap?>(null)
                }
                var imageSize by remember(adjustingBook.id, adjustingCoverUri) {
                    mutableStateOf(IntSize.Zero)
                }
                var previewZoom by remember(adjustingBook.id, adjustingBook.bookCoverAdjustment, adjustingBook.coverFocus) {
                    mutableStateOf(initialAdjustment.zoom)
                }
                var previewAnchorXPx by remember(adjustingBook.id, adjustingBook.bookCoverAdjustment, adjustingBook.coverFocus) {
                    mutableStateOf(initialAdjustment.anchorXPx)
                }
                var previewAnchorYPx by remember(adjustingBook.id, adjustingBook.bookCoverAdjustment, adjustingBook.coverFocus) {
                    mutableStateOf(initialAdjustment.anchorYPx)
                }
                var previewAnchorXExactPx by remember(adjustingBook.id, adjustingBook.bookCoverAdjustment, adjustingBook.coverFocus) {
                    mutableStateOf(initialAdjustment.anchorXPx?.toFloat())
                }
                var previewAnchorYExactPx by remember(adjustingBook.id, adjustingBook.bookCoverAdjustment, adjustingBook.coverFocus) {
                    mutableStateOf(initialAdjustment.anchorYPx?.toFloat())
                }
                LaunchedEffect(adjustingBook.id, adjustingCoverUri) {
                    val decodedBitmap = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(adjustingCoverUri)?.use { stream ->
                                BitmapFactory.decodeStream(stream)
                            }
                        }.getOrNull()
                    }
                    previewBitmap = decodedBitmap
                    imageSize = decodedBitmap?.let { IntSize(it.width.coerceAtLeast(0), it.height.coerceAtLeast(0)) }
                        ?: IntSize.Zero
                }
                Dialog(
                    onDismissRequest = { adjustingBookCoverId = null },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    val previewAdjustment = normalizeBookCoverAdjustment(
                        zoom = previewZoom,
                        anchorXPx = previewAnchorXPx,
                        anchorYPx = previewAnchorYPx,
                        anchorXExactPx = previewAnchorXExactPx,
                        anchorYExactPx = previewAnchorYExactPx,
                        fallbackViewportX = initialAdjustment.fallbackViewportX,
                        fallbackViewportY = initialAdjustment.fallbackViewportY
                    )
                    val controlScrollState = rememberScrollState()
                    val previewMaxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.46f
                    var sampledPoint by remember(adjustingBook.id) { mutableStateOf<Pair<Int, Int>?>(null) }
                    var isRangeMode by remember(adjustingBook.id) { mutableStateOf(false) }
                    var rangeStartPoint by remember(adjustingBook.id) { mutableStateOf<Pair<Int, Int>?>(null) }
                    var isSmoothDragMode by remember(adjustingBook.id) { mutableStateOf(true) }
                    var controlsExpanded by remember(adjustingBook.id) { mutableStateOf(false) }
                    var showRoundedPreview by remember(adjustingBook.id) { mutableStateOf(false) }
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.home_cover_adjust_title),
                                    style = MaterialTheme.typography.titleLarge
                                )
                                IconButton(onClick = { adjustingBookCoverId = null }) {
                                    Text(
                                        text = "×",
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (imageSize.width > 0 && imageSize.height > 0) {
                                    var previewSize by remember(adjustingBook.id) { mutableStateOf(IntSize.Zero) }
                                    var magnifierSize by remember(adjustingBook.id) { mutableStateOf(IntSize.Zero) }
                                    val rangeDisplayAdjustment = sampledPoint?.let { sampled ->
                                        rangeStartPoint?.takeIf { previewSize.width > 0 && previewSize.height > 0 }?.let { start ->
                                            computeBookCoverAdjustmentForRange(
                                                drawableWidth = imageSize.width,
                                                drawableHeight = imageSize.height,
                                                viewWidth = previewSize.width,
                                                viewHeight = previewSize.height,
                                                startXPx = start.first,
                                                startYPx = start.second,
                                                endXPx = sampled.first,
                                                endYPx = sampled.second
                                            )
                                        }
                                    }
                                    val displayAdjustment = rangeDisplayAdjustment ?: sampledPoint?.let {
                                        val displayZoom = if (previewSize.width > 0 && previewSize.height > 0) {
                                            computeZoomToFitBookCoverAnchor(
                                                drawableWidth = imageSize.width,
                                                drawableHeight = imageSize.height,
                                                viewWidth = previewSize.width,
                                                viewHeight = previewSize.height,
                                                anchorXPx = it.first,
                                                anchorYPx = it.second
                                            )
                                        } else {
                                            previewZoom
                                        }
                                        normalizeBookCoverAdjustment(
                                            zoom = displayZoom,
                                            anchorXPx = it.first,
                                            anchorYPx = it.second,
                                            fallbackViewportX = initialAdjustment.fallbackViewportX,
                                            fallbackViewportY = initialAdjustment.fallbackViewportY
                                        )
                                    } ?: previewAdjustment
                                    val committedRenderSpec = previewSize.takeIf { it.width > 0 && it.height > 0 }?.let {
                                        computeBookCoverRenderSpec(
                                            drawableWidth = imageSize.width,
                                            drawableHeight = imageSize.height,
                                            viewWidth = it.width,
                                            viewHeight = it.height,
                                            adjustment = previewAdjustment,
                                            focus = adjustingBook.coverFocus
                                        )
                                    }
                                    val latestPreviewAdjustment by rememberUpdatedState(previewAdjustment)
                                    val latestCommittedRenderSpec by rememberUpdatedState(committedRenderSpec)
                                    val latestPreviewZoom by rememberUpdatedState(previewZoom)
                                    val bitmap = previewBitmap
                                    fun computeCenterAnchoredGestureAnchor(
                                        spec: BookCoverRenderSpec,
                                        adjustment: BookCoverAdjustment,
                                        nextZoom: Float
                                    ): Pair<Float, Float> {
                                        val resolvedAnchor = resolveBookCoverAnchor(adjustment, spec)
                                        val oldScale = spec.scale
                                        val nextScale = oldScale * (nextZoom / adjustment.zoom)
                                        val pivotX = previewSize.width / 2f
                                        val pivotY = previewSize.height / 2f
                                        val nextAnchorX = (
                                            resolvedAnchor.first +
                                                pivotX / oldScale -
                                                pivotX / nextScale
                                        ).coerceIn(0f, (imageSize.width - 1).coerceAtLeast(0).toFloat())
                                        val nextAnchorY = (
                                            resolvedAnchor.second +
                                                pivotY / oldScale -
                                                pivotY / nextScale
                                        ).coerceIn(0f, (imageSize.height - 1).coerceAtLeast(0).toFloat())
                                        return nextAnchorX to nextAnchorY
                                    }
                                    fun updatePreviewZoom(nextZoom: Float) {
                                        val clampedZoom = nextZoom.coerceIn(1f, 3f)
                                        if (adjustingBook.coverFocus != HomeCoverCropFocus.CENTER) {
                                            previewZoom = clampedZoom
                                            return
                                        }
                                        val spec = committedRenderSpec
                                        if (spec == null || previewSize.width <= 0 || previewSize.height <= 0) {
                                            previewZoom = clampedZoom
                                            return
                                        }
                                        val (nextAnchorX, nextAnchorY) = computeCenterAnchoredGestureAnchor(
                                            spec = spec,
                                            adjustment = previewAdjustment,
                                            nextZoom = clampedZoom
                                        )
                                        previewZoom = clampedZoom
                                        previewAnchorXExactPx = nextAnchorX
                                        previewAnchorYExactPx = nextAnchorY
                                        previewAnchorXPx = nextAnchorX.roundToInt()
                                        previewAnchorYPx = nextAnchorY.roundToInt()
                                    }
                                    fun nudgeAnchor(dx: Int = 0, dy: Int = 0) {
                                        if (dx != 0) {
                                            previewAnchorXPx = ((previewAnchorXPx ?: (imageSize.width / 2)) + dx)
                                                .coerceIn(0, (imageSize.width - 1).coerceAtLeast(0))
                                            previewAnchorXExactPx = previewAnchorXPx?.toFloat()
                                        }
                                        if (dy != 0) {
                                            previewAnchorYPx = ((previewAnchorYPx ?: (imageSize.height / 2)) + dy)
                                                .coerceIn(0, (imageSize.height - 1).coerceAtLeast(0))
                                            previewAnchorYExactPx = previewAnchorYPx?.toFloat()
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = previewMaxHeight),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (bitmap != null) {
                                            BookCoverBitmapPreview(
                                                bitmap = bitmap,
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .aspectRatio(ReaderBookCoverAspectRatio)
                                                    .then(
                                                        if (showRoundedPreview) {
                                                            Modifier.clip(RoundedCornerShape(12.dp))
                                                        } else {
                                                            Modifier
                                                        }
                                                    )
                                                    .onSizeChanged { previewSize = it }
                                                    .then(
                                                        if (controlsExpanded) {
                                                            Modifier.pointerInput(
                                                                imageSize,
                                                                previewSize,
                                                                previewZoom,
                                                                previewAnchorXPx,
                                                                previewAnchorYPx,
                                                                isRangeMode,
                                                                rangeStartPoint
                                                            ) {
                                                                awaitEachGesture {
                                                                    val down = awaitFirstDown(requireUnconsumed = false)
                                                                    val longPress = awaitLongPressOrCancellation(down.id)
                                                                    if (longPress == null || committedRenderSpec == null) {
                                                                        sampledPoint = null
                                                                        return@awaitEachGesture
                                                                    }
                                                                    val spec = committedRenderSpec
                                                                    val minSampleX = 0
                                                                    val minSampleY = 0
                                                                    val maxSampleX = (imageSize.width - 1).coerceAtLeast(0)
                                                                    val maxSampleY = (imageSize.height - 1).coerceAtLeast(0)
                                                                    var lastPosition = longPress.position
                                                                    var currentSampleX = ((lastPosition.x - spec.dx) / spec.scale)
                                                                        .roundToInt()
                                                                        .coerceIn(minSampleX, maxSampleX)
                                                                    var currentSampleY = ((lastPosition.y - spec.dy) / spec.scale)
                                                                        .roundToInt()
                                                                        .coerceIn(minSampleY, maxSampleY)
                                                                    sampledPoint = currentSampleX to currentSampleY
                                                                    while (true) {
                                                                        val event = awaitPointerEvent()
                                                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                                        if (!change.pressed) break
                                                                        val delta = change.position - lastPosition
                                                                        currentSampleX = (currentSampleX + delta.x / spec.scale)
                                                                            .roundToInt()
                                                                            .coerceIn(minSampleX, maxSampleX)
                                                                        currentSampleY = (currentSampleY + delta.y / spec.scale)
                                                                            .roundToInt()
                                                                            .coerceIn(minSampleY, maxSampleY)
                                                                        sampledPoint = currentSampleX to currentSampleY
                                                                        lastPosition = change.position
                                                                        change.consume()
                                                                    }
                                                                    sampledPoint?.let { committedPoint ->
                                                                        val clampedX = committedPoint.first.coerceIn(0, imageSize.width - 1)
                                                                        val clampedY = committedPoint.second.coerceIn(0, imageSize.height - 1)
                                                                        if (isRangeMode && previewSize.width > 0 && previewSize.height > 0) {
                                                                            val start = rangeStartPoint
                                                                            if (start == null) {
                                                                                val legalStart = coerceBookCoverRangeStart(
                                                                                    drawableWidth = imageSize.width,
                                                                                    drawableHeight = imageSize.height,
                                                                                    viewWidth = previewSize.width,
                                                                                    viewHeight = previewSize.height,
                                                                                    startXPx = clampedX,
                                                                                    startYPx = clampedY
                                                                                )
                                                                                rangeStartPoint = legalStart
                                                                                previewAnchorXPx = legalStart.first
                                                                                previewAnchorYPx = legalStart.second
                                                                                previewAnchorXExactPx = legalStart.first.toFloat()
                                                                                previewAnchorYExactPx = legalStart.second.toFloat()
                                                                            } else {
                                                                                val rangeAdjustment = computeBookCoverAdjustmentForRange(
                                                                                    drawableWidth = imageSize.width,
                                                                                    drawableHeight = imageSize.height,
                                                                                    viewWidth = previewSize.width,
                                                                                    viewHeight = previewSize.height,
                                                                                    startXPx = start.first,
                                                                                    startYPx = start.second,
                                                                                    endXPx = clampedX,
                                                                                    endYPx = clampedY
                                                                                )
                                                                                previewZoom = rangeAdjustment.zoom
                                                                                previewAnchorXPx = rangeAdjustment.anchorXPx
                                                                                previewAnchorYPx = rangeAdjustment.anchorYPx
                                                                                previewAnchorXExactPx = rangeAdjustment.anchorXPx?.toFloat()
                                                                                previewAnchorYExactPx = rangeAdjustment.anchorYPx?.toFloat()
                                                                                isRangeMode = false
                                                                                rangeStartPoint = null
                                                                            }
                                                                        } else {
                                                                            val adjustedZoom = if (previewSize.width > 0 && previewSize.height > 0) {
                                                                                computeZoomToFitBookCoverAnchor(
                                                                                    drawableWidth = imageSize.width,
                                                                                    drawableHeight = imageSize.height,
                                                                                    viewWidth = previewSize.width,
                                                                                    viewHeight = previewSize.height,
                                                                                    anchorXPx = clampedX,
                                                                                    anchorYPx = clampedY
                                                                                )
                                                                            } else {
                                                                                previewZoom
                                                                            }
                                                                            previewZoom = adjustedZoom
                                                                            previewAnchorXPx = clampedX
                                                                            previewAnchorYPx = clampedY
                                                                            previewAnchorXExactPx = clampedX.toFloat()
                                                                            previewAnchorYExactPx = clampedY.toFloat()
                                                                        }
                                                                    }
                                                                    sampledPoint = null
                                                                }
                                                            }
                                                        } else {
                                                            Modifier
                                                        }
                                                    )
                                                    .then(
                                                        if (isSmoothDragMode) {
                                                            Modifier.pointerInput(imageSize, previewSize) {
                                                                detectTransformGestures { centroid, pan, zoom, _ ->
                                                                    if (sampledPoint != null) return@detectTransformGestures
                                                                    val spec = latestCommittedRenderSpec ?: return@detectTransformGestures
                                                                    val adjustment = latestPreviewAdjustment
                                                                    val resolvedAnchor = resolveBookCoverAnchor(
                                                                        adjustment = adjustment,
                                                                        renderSpec = spec
                                                                    )
                                                                    val oldScale = spec.scale
                                                                    val nextZoom = (latestPreviewZoom * zoom).coerceIn(1f, 3f)
                                                                    previewZoom = nextZoom
                                                                    val nextAnchors = if (adjustingBook.coverFocus == HomeCoverCropFocus.CENTER) {
                                                                        computeCenterAnchoredGestureAnchor(
                                                                            spec = spec,
                                                                            adjustment = adjustment,
                                                                            nextZoom = nextZoom
                                                                        )
                                                                    } else {
                                                                        val nextScale = oldScale * (nextZoom / adjustment.zoom)
                                                                        (
                                                                            resolvedAnchor.first +
                                                                                centroid.x / oldScale -
                                                                                centroid.x / nextScale -
                                                                                pan.x / oldScale
                                                                            ).coerceIn(0f, (imageSize.width - 1).coerceAtLeast(0).toFloat()) to
                                                                            (
                                                                                resolvedAnchor.second +
                                                                                    centroid.y / oldScale -
                                                                                    centroid.y / nextScale -
                                                                                    pan.y / oldScale
                                                                                ).coerceIn(0f, (imageSize.height - 1).coerceAtLeast(0).toFloat())
                                                                    }
                                                                    val nextAnchorX = nextAnchors.first
                                                                    val nextAnchorY = nextAnchors.second
                                                                    previewAnchorXExactPx = nextAnchorX
                                                                    previewAnchorYExactPx = nextAnchorY
                                                                    previewAnchorXPx = nextAnchorX.roundToInt()
                                                                    previewAnchorYPx = nextAnchorY.roundToInt()
                                                                }
                                                            }
                                                        } else {
                                                            Modifier.pointerInput(
                                                                imageSize,
                                                                previewSize,
                                                                previewZoom,
                                                                previewAnchorXPx,
                                                                previewAnchorYPx
                                                            ) {
                                                                detectTransformGestures { centroid, pan, zoom, _ ->
                                                                    if (sampledPoint != null) return@detectTransformGestures
                                                                    val spec = committedRenderSpec ?: return@detectTransformGestures
                                                                    val resolvedAnchor = resolveBookCoverAnchor(
                                                                        adjustment = previewAdjustment,
                                                                        renderSpec = spec
                                                                    )
                                                                    val oldScale = spec.scale
                                                                    val nextZoom = (previewZoom * zoom).coerceIn(1f, 3f)
                                                                    previewZoom = nextZoom
                                                                    val nextAnchors = if (adjustingBook.coverFocus == HomeCoverCropFocus.CENTER) {
                                                                        computeCenterAnchoredGestureAnchor(
                                                                            spec = spec,
                                                                            adjustment = previewAdjustment,
                                                                            nextZoom = nextZoom
                                                                        )
                                                                    } else {
                                                                        val nextScale = oldScale * (nextZoom / previewAdjustment.zoom)
                                                                        (
                                                                            resolvedAnchor.first +
                                                                                centroid.x / oldScale -
                                                                                centroid.x / nextScale -
                                                                                pan.x / oldScale
                                                                            ).coerceIn(0f, (imageSize.width - 1).coerceAtLeast(0).toFloat()) to
                                                                            (
                                                                                resolvedAnchor.second +
                                                                                    centroid.y / oldScale -
                                                                                    centroid.y / nextScale -
                                                                                    pan.y / oldScale
                                                                                ).coerceIn(0f, (imageSize.height - 1).coerceAtLeast(0).toFloat())
                                                                    }
                                                                    previewAnchorXPx = nextAnchors.first.roundToInt().coerceIn(0, imageSize.width - 1)
                                                                    previewAnchorYPx = nextAnchors.second.roundToInt().coerceIn(0, imageSize.height - 1)
                                                                    previewAnchorXExactPx = previewAnchorXPx?.toFloat()
                                                                    previewAnchorYExactPx = previewAnchorYPx?.toFloat()
                                                                }
                                                            }
                                                        }
                                                    ),
                                                coverFocus = adjustingBook.coverFocus,
                                                coverAdjustment = displayAdjustment,
                                                clampToBounds = true
                                            )
                                        }
                                        if (sampledPoint != null) {
                                            val magnifierAdjustment = sampledPoint?.takeIf {
                                                magnifierSize.width > 0 && magnifierSize.height > 0
                                            }?.let {
                                                buildMagnifierBookCoverAdjustment(
                                                    drawableWidth = imageSize.width,
                                                    drawableHeight = imageSize.height,
                                                    viewWidth = magnifierSize.width,
                                                    viewHeight = magnifierSize.height,
                                                    sampleXPx = it.first,
                                                    sampleYPx = it.second
                                                )
                                            }
                                            Surface(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(10.dp)
                                                    .size(172.dp),
                                                shape = RoundedCornerShape(14.dp),
                                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                                                shadowElevation = 8.dp,
                                                border = BorderStroke(
                                                    1.dp,
                                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                                                )
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .onSizeChanged { magnifierSize = it }
                                                ) {
                                                    if (bitmap != null && magnifierAdjustment != null) {
                                                        BookCoverBitmapPreview(
                                                            bitmap = bitmap,
                                                            modifier = Modifier.fillMaxSize(),
                                                            coverFocus = HomeCoverCropFocus.CENTER,
                                                            coverAdjustment = magnifierAdjustment,
                                                            maxZoom = BookCoverMagnifierZoom,
                                                            clampToBounds = false,
                                                            showBlackBackground = true
                                                        )
                                                        val crosshairColor = MaterialTheme.colorScheme.primary
                                                        Box(
                                                            modifier = Modifier
                                                                .align(Alignment.Center)
                                                                .size(40.dp)
                                                                .drawBehind {
                                                                    val stroke = 2.dp.toPx()
                                                                    drawLine(
                                                                        color = crosshairColor,
                                                                        start = Offset(size.width / 2f, 0f),
                                                                        end = Offset(size.width / 2f, size.height),
                                                                        strokeWidth = stroke
                                                                    )
                                                                    drawLine(
                                                                        color = crosshairColor,
                                                                        start = Offset(0f, size.height / 2f),
                                                                        end = Offset(size.width, size.height / 2f),
                                                                        strokeWidth = stroke
                                                                    )
                                                                }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = previewMaxHeight),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { controlsExpanded = !controlsExpanded },
                                            shape = RoundedCornerShape(999.dp),
                                            color = Color.Transparent
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 10.dp),
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
                                            visible = controlsExpanded,
                                            enter = fadeIn(animationSpec = tween(180)),
                                            exit = fadeOut(animationSpec = tween(140))
                                        ) {
                                            Column(
                                                modifier = Modifier.verticalScroll(controlScrollState),
                                                verticalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                CoverAdjustSection(title = stringResource(R.string.home_cover_adjust_scale)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        OutlinedButton(onClick = { updatePreviewZoom(previewZoom - 0.01f) }) {
                                                            Text("-")
                                                        }
                                                        Box(
                                                            modifier = Modifier.weight(1f),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Slider(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                value = previewZoom,
                                                                onValueChange = { updatePreviewZoom(it) },
                                                                valueRange = 1f..3f
                                                            )
                                                            Surface(
                                                                shape = RoundedCornerShape(999.dp),
                                                                color = MaterialTheme.colorScheme.primaryContainer
                                                            ) {
                                                                Text(
                                                                    text = String.format(Locale.US, "%.2fx", previewZoom),
                                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                                                    style = MaterialTheme.typography.labelLarge,
                                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                                )
                                                            }
                                                        }
                                                        OutlinedButton(onClick = { updatePreviewZoom(previewZoom + 0.01f) }) {
                                                            Text("+")
                                                        }
                                                    }
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(stringResource(R.string.home_cover_adjust_rounded))
                                                        Switch(
                                                            checked = showRoundedPreview,
                                                            onCheckedChange = { showRoundedPreview = it }
                                                        )
                                                    }
                                                }
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                    verticalAlignment = Alignment.Top
                                                ) {
                                                    Column(
                                                        modifier = Modifier.weight(1f),
                                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                                    ) {
                                                        CoverAdjustSection(title = stringResource(R.string.home_cover_adjust_mode)) {
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                            ) {
                                                                CoverAdjustChoicePill(
                                                                    text = stringResource(R.string.home_cover_adjust_continuous),
                                                                    selected = isSmoothDragMode,
                                                                    onClick = { isSmoothDragMode = true },
                                                                    modifier = Modifier.weight(1f)
                                                                )
                                                                CoverAdjustChoicePill(
                                                                    text = stringResource(R.string.home_cover_adjust_step),
                                                                    selected = !isSmoothDragMode,
                                                                    onClick = { isSmoothDragMode = false },
                                                                    modifier = Modifier.weight(1f)
                                                                )
                                                            }
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                            ) {
                                                                CoverAdjustChoicePill(
                                                                    text = if (isRangeMode) {
                                                                        if (rangeStartPoint == null) {
                                                                            stringResource(R.string.home_cover_adjust_select_top_left)
                                                                        } else {
                                                                            stringResource(R.string.home_cover_adjust_select_bottom_right)
                                                                        }
                                                                    } else {
                                                                        stringResource(R.string.home_cover_adjust_two_point_range)
                                                                    },
                                                                    selected = isRangeMode,
                                                                    onClick = {
                                                                        isRangeMode = !isRangeMode
                                                                        rangeStartPoint = null
                                                                        sampledPoint = null
                                                                    },
                                                                    modifier = Modifier.weight(1f)
                                                                )
                                                                sampledPoint?.let { point ->
                                                                    Surface(
                                                                        modifier = Modifier.weight(1f),
                                                                        shape = RoundedCornerShape(999.dp),
                                                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                                                        border = BorderStroke(
                                                                            1.dp,
                                                                            MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                                                                        )
                                                                    )
                                                                    {
                                                                        Box(
                                                                            modifier = Modifier
                                                                                .fillMaxWidth()
                                                                                .padding(horizontal = 14.dp, vertical = 8.dp),
                                                                            contentAlignment = Alignment.Center
                                                                        ) {
                                                                            Text(
                                                                                text = "${point.first}, ${point.second}",
                                                                                style = MaterialTheme.typography.labelLarge,
                                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                    Column(
                                                        modifier = Modifier.weight(1f),
                                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                                    ) {
                                                        CoverAdjustSection(title = stringResource(R.string.home_cover_adjust_pixel_nudge)) {
                                                            Column(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .width(176.dp),
                                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                                                horizontalAlignment = Alignment.CenterHorizontally
                                                            ) {
                                                                CoverAdjustDirectionButton(
                                                                    text = "↑",
                                                                    onClick = { nudgeAnchor(dy = -1) }
                                                                )
                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    CoverAdjustDirectionButton(
                                                                        text = "←",
                                                                        onClick = { nudgeAnchor(dx = -1) }
                                                                    )
                                                                    Box(
                                                                        modifier = Modifier.weight(1f),
                                                                        contentAlignment = Alignment.Center
                                                                    ) {
                                                                        Text("1px", style = MaterialTheme.typography.labelLarge)
                                                                    }
                                                                    CoverAdjustDirectionButton(
                                                                        text = "→",
                                                                        onClick = { nudgeAnchor(dx = 1) }
                                                                    )
                                                                }
                                                                CoverAdjustDirectionButton(
                                                                    text = "↓",
                                                                    onClick = { nudgeAnchor(dy = 1) }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = {
                                        val baseline = defaultBookCoverAdjustmentForFocus(adjustingBook.coverFocus)
                                        previewZoom = baseline.zoom
                                        previewAnchorXPx = null
                                        previewAnchorYPx = null
                                        previewAnchorXExactPx = null
                                        previewAnchorYExactPx = null
                                    }
                                ) {
                                    Text(stringResource(R.string.home_cover_adjust_reset))
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                Button(
                                    onClick = {
                                        val normalized = normalizeBookCoverAdjustment(
                                            zoom = previewZoom,
                                            anchorXPx = previewAnchorXPx,
                                            anchorYPx = previewAnchorYPx,
                                            fallbackViewportX = initialAdjustment.fallbackViewportX,
                                            fallbackViewportY = initialAdjustment.fallbackViewportY
                                        )
                                        val savedAdjustment = if (
                                            isCustomBookCoverAdjustment(normalized, adjustingBook.coverFocus)
                                        ) {
                                            normalized
                                        } else {
                                            null
                                        }
                                        updateBookCoverAdjustment(adjustingBook.id, savedAdjustment)
                                        adjustingBookCoverId = null
                                    }
                                ) {
                                    Text(stringResource(android.R.string.ok))
                                }
                            }
                        }
                    }
                }
            }

            if (activeSection == MiningSection.MAIN) {
                // 书架排序：最近阅读（播放快照时间） / 书名 / 手动（存储顺序）
                val displayedBooks = remember(readerBooks, homeLibrarySort, readerBookPlaybackSnapshots) {
                    when (homeLibrarySort) {
                        HomeLibrarySort.TITLE -> readerBooks.sortedWith(
                            compareBy({ it.title.lowercase(Locale.ROOT) }, { it.id })
                        )
                        HomeLibrarySort.RECENT -> readerBooks.sortedWith(
                            // 「最近」＝最近动过的：导入时间与播放快照时间取较大者。
                            // 刚导入还没播过的书以前没有快照，只能落到"没播过"那一组按书名排，
                            // 于是看着像沉在列表底部；现在它按导入时间参与排序，直接排在前面。
                            compareByDescending<ReaderBook> {
                                maxOf(it.addedAtMs, readerBookPlaybackSnapshots[it.id]?.updatedAtMs ?: 0L)
                            }.thenBy { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }
                        )
                        HomeLibrarySort.MANUAL -> readerBooks
                    }
                }
                // ---- 手动排序拖拽（MANUAL 模式；延迟落位模型）----
                // 拖动中不改列表顺序：只记录「起点/目标槽位」，其它卡片弹簧让位显示空隙，
                // 松手才一次性落位并持久化（与词典列表拖拽同款模型）。
                // 几何量全部使用「内容坐标系」（见 shelfColumnCoords / dragFingerContent）：
                // 卡片槽位与手指位置对页面滚动免疫，卡片滚出视口也保持真实，
                // 避免了窗口坐标在视口外退化(裁剪/(0,0))导致的目标错乱与反向拖回失效。
                var draggingBookId by remember { mutableStateOf<String?>(null) }
                // 抓取点：长按时手指相对被拖卡片槽位的偏移（内容坐标，拖动期间恒定）。
                var dragPressStart by remember { mutableStateOf(Offset.Zero) }
                var dragStartIndex by remember { mutableIntStateOf(-1) }
                var dragTargetIndex by remember { mutableIntStateOf(-1) }
                // 拖动期间其它卡片的让位位移（只作用于视觉，不改布局/列表）。
                val dragDisplacementMap = remember { mutableStateMapOf<String, Offset>() }
                // 卡片实际槽位（内容坐标矩形，由 onGloballyPositioned 换算；随布局刷新）。
                val shelfCardBounds = remember { mutableStateMapOf<String, Rect>() }
                // 拖到视口边缘自动滚动：边缘检测带宽度（px）
                val dragAutoScrollEdgePx = with(LocalDensity.current) { 72.dp.toPx() }

                fun resetDragStates() {
                    draggingBookId = null
                    dragPressStart = Offset.Zero
                    dragStartIndex = -1
                    dragTargetIndex = -1
                    dragDisplacementMap.clear()
                }

                // 手指当前的真实内容位置：观察器值 + 观察器之后发生的滚动增量。
                // （dragFingerContent/dragFingerScrollAnchor 由 mainContentModifier 上的
                // 观察器按事件写入，见其上方的 pointerInput。）
                fun currentFingerContent(): Offset = Offset(
                    dragFingerContent.x,
                    dragFingerContent.y + (mainPageScrollState.value - dragFingerScrollAnchor)
                )

                // 手指相对视口顶部的内容行距（窗口坐标意义）：内容 y - 滚动值。
                fun fingerViewportRelativeY(): Float =
                    currentFingerContent().y - mainPageScrollState.value

                // 让位位移：起点与目标之间(不含被拖卡片)的其它卡片，向空出的方向平移一个槽位。
                fun rebuildDisplacementMap() {
                    val draggedId = draggingBookId ?: return
                    val start = dragStartIndex
                    val target = dragTargetIndex
                    if (start < 0 || target < 0) return
                    val order = displayedBooks
                    val updates = mutableMapOf<String, Offset>()
                    // 两支互为镜像：取"另一侧相邻槽位"的位置做对齐（往下拖取前一个槽位，
                    // 往上拖取后一个槽位），所以统一成 neighbor = i - step。
                    val step = if (target > start) 1 else -1
                    val firstIndex = if (step == 1) start + 1 else target
                    val lastIndex = if (step == 1) target else start - 1
                    for (i in firstIndex..lastIndex) {
                        val neighbor = i - step
                        if (i !in order.indices || neighbor !in order.indices) continue
                        val neighborTopLeft = shelfCardBounds[order[neighbor].id]?.topLeft ?: continue
                        val cur = shelfCardBounds[order[i].id]?.topLeft ?: continue
                        updates[order[i].id] = Offset(neighborTopLeft.x - cur.x, neighborTopLeft.y - cur.y)
                    }
                    dragDisplacementMap.clear()
                    dragDisplacementMap.putAll(updates)
                }

                // 手指(拖影中心)落在哪个槽位，落点就定到哪个下标；无命中取最近槽位。
                fun updateDragTarget() {
                    val draggedId = draggingBookId ?: return
                    val draggedSlot = shelfCardBounds[draggedId] ?: return
                    val order = displayedBooks
                    if (order.isEmpty()) return
                    val center = currentFingerContent() - dragPressStart +
                        Offset(draggedSlot.width / 2f, draggedSlot.height / 2f)
                    var containsIdx = -1
                    var best = dragStartIndex.coerceIn(order.indices)
                    var bestDist = Float.POSITIVE_INFINITY
                    for ((idx, book) in order.withIndex()) {
                        val rect = shelfCardBounds[book.id] ?: continue
                        if (rect.contains(center)) {
                            containsIdx = idx
                            break
                        }
                        val d = (rect.center - center).getDistance()
                        if (d < bestDist) {
                            bestDist = d
                            best = idx
                        }
                    }
                    val target = if (containsIdx >= 0) containsIdx else best
                    if (target != dragTargetIndex) {
                        dragTargetIndex = target
                        rebuildDisplacementMap()
                    }
                }

                // 松手落位：removeAt(起点) 后插入目标下标（与让位动画的终点一致）。
                fun dropDragMove(fromId: String, targetIndex: Int) {
                    val list = readerBooks.toMutableList()
                    val from = list.indexOfFirst { it.id == fromId }
                    if (from >= 0 && targetIndex in list.indices && targetIndex != from) {
                        val item = list.removeAt(from)
                        list.add(targetIndex, item)
                        readerBooks = list
                        persistImportState()
                    }
                }

                /**
                 * 手动排序长按手势（MANUAL 模式启用）：
                 * 长按后移动 = 拖动重排；长按后原地抬起 = 重命名。
                 * 与 combinedClickable 的点击共存（快速点击仍由它处理，长按回调在
                 * 手动模式下置空，避免两个长按计时器同时触发）。
                 */
                fun Modifier.manualReorderDrag(bookId: String, onRename: () -> Unit): Modifier {
                    if (!manualSortTrackEnabled) return this
                    // key 必须包含列表本身：落位会更换 readerBooks/displayedBooks，
                    // 若只按 size 键控，闭包里的 displayedBooks 会停留在旧顺序，
                    // 使连续多次拖拽的目标下标与实际槽位错位。
                    return this.pointerInput(bookId, manualSortTrackEnabled, displayedBooks) {
                        val autoScrollEdgePx = dragAutoScrollEdgePx
                        // 心跳（约 60fps）：手指贴近视口上/下边缘时自动滚动页面；
                        // 滚动/停留期间持续按手指内容位置刷新落点与让位动画目标。
                        coroutineScope {
                            val tickerJob = launch {
                                while (isActive) {
                                    if (draggingBookId == bookId) {
                                        val bounds = shelfViewportBounds
                                        if (bounds != null && mainPageScrollState.maxValue > 0) {
                                            val vpHeight = bounds.height
                                            if (vpHeight > autoScrollEdgePx * 2) {
                                                // 手指在内容坐标系；相对视口顶部的行距 = 内容y - scroll。
                                                val relY = fingerViewportRelativeY()
                                                val dir = when {
                                                    relY < autoScrollEdgePx -> -1
                                                    relY > vpHeight - autoScrollEdgePx -> 1
                                                    else -> 0
                                                }
                                                if (dir != 0) {
                                                    val canScroll = if (dir < 0) {
                                                        mainPageScrollState.value > 0
                                                    } else {
                                                        mainPageScrollState.value < mainPageScrollState.maxValue
                                                    }
                                                    if (canScroll) {
                                                        val frac = if (dir < 0) {
                                                            ((autoScrollEdgePx - relY) / autoScrollEdgePx)
                                                                .coerceIn(0f, 1f)
                                                        } else {
                                                            ((relY - (vpHeight - autoScrollEdgePx)) /
                                                                autoScrollEdgePx).coerceIn(0f, 1f)
                                                        }
                                                        mainPageScrollState.scrollBy(dir * (8f + 26f * frac))
                                                    }
                                                }
                                            }
                                        }
                                        updateDragTarget()
                                        delay(16)
                                    } else {
                                        delay(50)
                                    }
                                }
                            }
                            try {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                        ?: return@awaitEachGesture
                                    val longPress = awaitLongPressOrCancellation(down.id)
                                        ?: return@awaitEachGesture
                                    dragPressStart = longPress.position
                                    var startedDrag = false
                                    var slopAccum = Offset.Zero
                                    var lastPos = longPress.position
                                    var ended = false
                                    try {
                                        while (!ended) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                            if (change == null || !change.pressed) {
                                                // 长按已成立：吞掉抬起事件，避免 combinedClickable 的
                                                // onClick 在重命名/拖拽结束后再次触发（如同时打开书籍）。
                                                change?.consume()
                                                ended = true
                                                break
                                            }
                                            val pos = change.position
                                            if (!startedDrag) {
                                                val delta = pos - lastPos
                                                lastPos = pos
                                                slopAccum += delta
                                                if (
                                                    abs(slopAccum.x) > viewConfiguration.touchSlop ||
                                                    abs(slopAccum.y) > viewConfiguration.touchSlop
                                                ) {
                                                    startedDrag = true
                                                    draggingBookId = bookId
                                                    val startIdx = displayedBooks.indexOfFirst { it.id == bookId }
                                                    dragStartIndex = startIdx
                                                    dragTargetIndex = startIdx
                                                    dragDisplacementMap.clear()
                                                }
                                            }
                                            if (startedDrag) {
                                                // 手指位置由主列观察器（内容坐标，滚出视口仍可靠）维护。
                                                change.consume()
                                                updateDragTarget()
                                            }
                                        }
                                        if (startedDrag) {
                                            // 延迟落位：松手时一次性移动并持久化
                                            if (dragTargetIndex != dragStartIndex) {
                                                dropDragMove(bookId, dragTargetIndex)
                                            }
                                            resetDragStates()
                                        } else if (ended) {
                                            // 长按后原地抬起：重命名
                                            dragPressStart = Offset.Zero
                                            onRename()
                                        }
                                    } finally {
                                        // 手势结束或协程被取消（如节点销毁）都清理拖拽态，避免拖影卡死。
                                        if (draggingBookId == bookId) {
                                            resetDragStates()
                                        }
                                    }
                                }
                            } finally {
                                tickerJob.cancel()
                            }
                        }
                    }
                }

                fun Modifier.dragVisual(bookId: String): Modifier {
                    return if (draggingBookId == bookId) {
                        this.offset {
                            // 布局阶段实时跟手（内容坐标系）：拖影平移 = 手指当前内容位置
                            // - 本卡内容槽位原点 - 抓取点。滚动/自动滚动下内容坐标恒定，
                            // 无窗口坐标裁剪/退化问题，也不会与心跳差频抽搐。
                            val origin = shelfCardBounds[bookId]?.topLeft ?: Offset.Zero
                            val finger = currentFingerContent()
                            IntOffset(
                                (finger.x - origin.x - dragPressStart.x).roundToInt(),
                                (finger.y - origin.y - dragPressStart.y).roundToInt()
                            )
                        }.alpha(0.92f)
                    } else {
                        this
                    }
                }

                // 书架(FlowRow)/列表两视图共用的书籍卡片交互链：
                // 置顶 + 点击/长按 + 内容坐标槽位记录 + 手动排序拖拽 + 拖影跟手 + 让位动画偏移。
                // 顺序敏感，勿重排：槽位记录(onGloballyPositioned)必须在视觉 offset 外层，
                // 否则矩形被位移污染；dragVisual 布局期跟手与心跳同相。sizing(.width/.fillMaxWidth)
                // 由调用方放在链首。
                fun Modifier.manualReorderCardChain(book: ReaderBook, dispAnim: Offset): Modifier =
                    this
                        .zIndex(if (draggingBookId == book.id) 1f else 0f)
                        .combinedClickable(
                            onClick = {
                                if (isBookSelectionMode) {
                                    toggleBookSelection(book.id)
                                } else if (homeCoverFocusEditMode && homeCoverAspect == HomeCoverAspect.BOOK) {
                                    // Cover focus edit mode only reacts to cover taps.
                                } else {
                                    openReaderBook(book, persist = true)
                                }
                            },
                            onLongClick = {
                                if (!manualSortTrackEnabled) {
                                    requestRenameBook(book)
                                }
                            }
                        )
                        .onGloballyPositioned {
                            // 内容坐标槽位：卡片相对内容列的位置，滚动/裁剪免疫，
                            // 滚出视口也保持真实（窗口坐标会在视口外退化，勿用）。
                            val colCoords = shelfColumnCoords
                            if (colCoords != null) {
                                val pf = colCoords.localPositionOf(it, Offset.Zero)
                                shelfCardBounds[book.id] = Rect(
                                    pf.x, pf.y,
                                    pf.x + it.size.width, pf.y + it.size.height
                                )
                            } else {
                                shelfCardBounds.remove(book.id)
                            }
                        }
                        .manualReorderDrag(book.id) { requestRenameBook(book) }
                        .dragVisual(book.id)
                        .offset {
                            IntOffset(
                                dispAnim.x.roundToInt(),
                                dispAnim.y.roundToInt()
                            )
                        }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.home_bookshelf_title), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isBookSelectionMode) {
                            OutlinedButton(onClick = { requestDeleteSelectedBooks() }) {
                                Text(stringResource(R.string.home_delete_selected, selectedBookIds.size))
                            }
                            OutlinedButton(onClick = { clearBookSelection() }) {
                                Text(stringResource(R.string.home_cancel_selection))
                            }
                        }
                        if (!isBookSelectionMode) {
                            if (homeCoverAspect == HomeCoverAspect.BOOK) {
                                OutlinedButton(
                                    onClick = { homeCoverFocusEditMode = !homeCoverFocusEditMode },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (homeCoverFocusEditMode) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Crop,
                                        contentDescription = "Crop",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    homeCoverFocusEditMode = false
                                    enterBookSelectionMode()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Checklist,
                                    contentDescription = stringResource(R.string.home_selected)
                                )
                            }
                            Box {
                                OutlinedButton(
                                    onClick = { homeDisplayMenuExpanded = true }
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.MoreHoriz,
                                        contentDescription = stringResource(R.string.home_display_menu)
                                    )
                                }
                                DropdownMenu(
                                    expanded = homeDisplayMenuExpanded,
                                    onDismissRequest = { homeDisplayMenuExpanded = false }
                                ) {
                                    HomeLibrarySort.entries.forEach { sort ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(homeSortLabelRes(sort))) },
                                            leadingIcon = if (homeLibrarySort == sort) {
                                                { Icon(Icons.Outlined.Check, contentDescription = null) }
                                            } else {
                                                null
                                            },
                                            onClick = {
                                                homeLibrarySort = sort
                                                persistHomeDisplaySettings(nextSort = sort)
                                                homeDisplayMenuExpanded = false
                                            }
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "${stringResource(R.string.home_display_menu)}：" +
                                                    if (homeLibraryView == HomeLibraryView.BOOKSHELF) {
                                                        stringResource(R.string.home_display_bookshelf)
                                                    } else {
                                                        stringResource(R.string.home_display_list)
                                                    }
                                            )
                                        },
                                        onClick = {
                                            val nextView = if (homeLibraryView == HomeLibraryView.BOOKSHELF) {
                                                HomeLibraryView.LIST
                                            } else {
                                                HomeLibraryView.BOOKSHELF
                                            }
                                            homeLibraryView = nextView
                                            persistHomeDisplaySettings(nextView = nextView)
                                            homeDisplayMenuExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (homeCoverAspect == HomeCoverAspect.BOOK) {
                                                    stringResource(R.string.home_cover_aspect_book)
                                                } else {
                                                    stringResource(R.string.home_cover_aspect_square)
                                                }
                                            )
                                        },
                                        onClick = {
                                            val nextAspect = if (homeCoverAspect == HomeCoverAspect.BOOK) {
                                                HomeCoverAspect.SQUARE
                                            } else {
                                                HomeCoverAspect.BOOK
                                            }
                                            homeCoverAspect = nextAspect
                                            persistHomeDisplaySettings(nextAspect = nextAspect)
                                            if (nextAspect != HomeCoverAspect.BOOK) {
                                                homeCoverFocusEditMode = false
                                            }
                                            homeDisplayMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (readerBooks.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = hoshiPanelBackgroundColor())
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(stringResource(R.string.home_no_books))
                            Text(stringResource(R.string.home_no_books_hint))
                        }
                    }
                } else if (homeLibraryView == HomeLibraryView.BOOKSHELF) {
                    // 单一父容器 FlowRow（每行至多 2 本）：卡片跨行换位时按 key 在同一父容器内
                    // 移动、节点不被销毁，长按拖拽可跨行连续进行；chunked(2)+多行 Row 会在
                    // 跨行处重建节点导致手势中途断掉（表现为“没松手却自动松手”）。
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val shelfCardWidth = (maxWidth - 12.dp) / 2
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            maxItemsInEachRow = 2
                        ) {
                            displayedBooks.forEach { book ->
                                key(book.id, homeCoverAspect) {
                                val selected = selectedBookId == book.id
                                val multiSelected = selectedBookIds.contains(book.id)
                                val playbackSnapshot = readerBookPlaybackSnapshots[book.id]
                                val playbackPercent = playbackSnapshot?.progressPercent ?: 0
                                // 拖拽让位：本卡片被让位时的弹簧位移动画（目标由 dragDisplacementMap 驱动）
                                val dispTarget = dragDisplacementMap[book.id] ?: Offset.Zero
                                val dispAnim = animateDragDisplacement(dispTarget)
                                Card(
                                    modifier = Modifier
                                        .width(shelfCardWidth)
                                        .manualReorderCardChain(book, dispAnim),
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color.Transparent
                                    )
                                ) {
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            BoxWithConstraints(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .aspectRatio(
                                                        if (homeCoverAspect == HomeCoverAspect.BOOK) {
                                                            ReaderBookCoverAspectRatio
                                                        } else {
                                                            1f
                                                        }
                                                    )
                                            ) {
                                                val coverSlotWidth = maxWidth
                                                val displayCover = book.displayCoverFor(homeCoverAspect)
                                                val canEditCoverFocus =
                                                    homeCoverFocusEditMode &&
                                                        homeCoverAspect == HomeCoverAspect.BOOK &&
                                                        displayCover?.source == ReaderBookCoverSource.AUDIO
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .clip(RoundedCornerShape(12.dp))
                                                        .then(
                                                            if (canEditCoverFocus) {
                                                                Modifier.combinedClickable(
                                                                    onClick = {
                                                                        toggleBookCoverFocus(book)
                                                                    },
                                                                    onLongClick = {
                                                                        adjustingBookCoverId = book.id
                                                                    }
                                                                )
                                                            } else {
                                                                Modifier
                                                            }
                                                        )
                                                ) {
                                                    if (displayCover != null) {
                                                        val squareEbookCover =
                                                            homeCoverAspect == HomeCoverAspect.SQUARE &&
                                                                displayCover.source == ReaderBookCoverSource.EBOOK
                                                        BookCoverThumbnail(
                                                            coverUri = displayCover.uri,
                                                            modifier = if (squareEbookCover) {
                                                                Modifier
                                                                    .width(coverSlotWidth * ReaderBookCoverAspectRatio)
                                                                    .height(coverSlotWidth)
                                                                    .align(Alignment.Center)
                                                                    .clip(RoundedCornerShape(12.dp))
                                                            } else {
                                                                Modifier.fillMaxSize()
                                                            },
                                                            coverFocus = book.coverFocus,
                                                            coverAdjustment = if (homeCoverAspect == HomeCoverAspect.BOOK) {
                                                                effectiveBookCoverAdjustment(book)
                                                            } else {
                                                                null
                                                            },
                                                            useStartFocus = homeCoverAspect == HomeCoverAspect.BOOK
                                                        )
                                                    } else {
                                                        Surface(
                                                            modifier = Modifier.fillMaxSize(),
                                                            shape = RoundedCornerShape(10.dp),
                                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                                        ) {}
                                                    }
                                                    if (canEditCoverFocus) {
                                                        Surface(
                                                            modifier = Modifier
                                                                .align(Alignment.TopEnd)
                                                                .padding(6.dp),
                                                            shape = RoundedCornerShape(8.dp),
                                                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
                                                        ) {
                                                            Text(
                                                                text = if (book.coverFocus == HomeCoverCropFocus.START) {
                                                                    "左"
                                                                } else {
                                                                    "中"
                                                                },
                                                                style = MaterialTheme.typography.labelSmall,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                    if (selected) {
                                                        Surface(
                                                            modifier = Modifier
                                                                .align(Alignment.BottomEnd)
                                                                .padding(6.dp),
                                                            shape = RoundedCornerShape(8.dp),
                                                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
                                                        ) {
                                                            Text(
                                                                text = stringResource(R.string.home_opened),
                                                                style = MaterialTheme.typography.labelSmall,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                    BookAttachmentBadges(
                                                        hasSubtitle = book.srtUri != null,
                                                        hasEbook = book.ebookUri != null,
                                                        modifier = Modifier
                                                            .align(Alignment.BottomStart)
                                                            .padding(6.dp)
                                                    )
                                                }
                                            }
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                color = Color.Transparent,
                                                shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                                            ) {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(start = 4.dp, end = 4.dp, top = 0.dp, bottom = 6.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        LinearProgressIndicator(
                                                            progress = { (playbackPercent / 100f).coerceIn(0f, 1f) },
                                                            modifier = Modifier.weight(1f).height(4.dp),
                                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                                                            trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                                        )
                                                        Text(
                                                            text = "$playbackPercent%",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    Text(
                                                        book.title,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        minLines = 2,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    if (multiSelected) {
                                                        Text(stringResource(R.string.home_selected), color = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                }
                                }
                            }
                        }
                } else {
                    displayedBooks.forEach { book ->
                        key(book.id, homeCoverAspect) {
                        val selected = selectedBookId == book.id
                        val multiSelected = selectedBookIds.contains(book.id)
                        val playbackSnapshot = readerBookPlaybackSnapshots[book.id]
                        val playbackPercent = playbackSnapshot?.progressPercent ?: 0
                        val displayCover = book.displayCoverFor(homeCoverAspect)
                        val canEditCoverFocus =
                            homeCoverFocusEditMode &&
                                homeCoverAspect == HomeCoverAspect.BOOK &&
                                displayCover?.source == ReaderBookCoverSource.AUDIO
                        // 拖拽让位：本卡片被让位时的弹簧位移动画（目标由 dragDisplacementMap 驱动）
                        val dispTarget = dragDisplacementMap[book.id] ?: Offset.Zero
                        val dispAnim = animateDragDisplacement(dispTarget)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .manualReorderCardChain(book, dispAnim),
                            colors = CardDefaults.cardColors(containerColor = hoshiCardBackgroundColor())
                        ) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    if (displayCover != null || book.isEbookOnly) {
                                        BoxWithConstraints(
                                            modifier = Modifier
                                                .then(
                                                    if (homeCoverAspect == HomeCoverAspect.BOOK) {
                                                        Modifier.width(96.dp).aspectRatio(ReaderBookCoverAspectRatio)
                                                    } else {
                                                        Modifier.size(96.dp)
                                                    }
                                                )
                                                .clip(RoundedCornerShape(10.dp))
                                                .then(
                                                    if (canEditCoverFocus) {
                                                        Modifier.combinedClickable(
                                                            onClick = {
                                                                toggleBookCoverFocus(book)
                                                            },
                                                            onLongClick = {
                                                                adjustingBookCoverId = book.id
                                                            }
                                                        )
                                                    } else {
                                                        Modifier
                                                    }
                                                )
                                        ) {
                                            val coverSlotWidth = maxWidth
                                            if (displayCover != null) {
                                                val squareEbookCover =
                                                    homeCoverAspect == HomeCoverAspect.SQUARE &&
                                                        displayCover.source == ReaderBookCoverSource.EBOOK
                                                BookCoverThumbnail(
                                                    coverUri = displayCover.uri,
                                                    modifier = if (squareEbookCover) {
                                                        Modifier
                                                            .width(coverSlotWidth * ReaderBookCoverAspectRatio)
                                                            .height(coverSlotWidth)
                                                            .align(Alignment.Center)
                                                            .clip(RoundedCornerShape(10.dp))
                                                    } else {
                                                        Modifier.fillMaxSize()
                                                    },
                                                    coverFocus = book.coverFocus,
                                                    coverAdjustment = if (homeCoverAspect == HomeCoverAspect.BOOK) {
                                                        effectiveBookCoverAdjustment(book)
                                                    } else {
                                                        null
                                                    },
                                                    useStartFocus = homeCoverAspect == HomeCoverAspect.BOOK
                                                )
                                            } else {
                                                Surface(
                                                    modifier = Modifier.fillMaxSize(),
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                                ) {}
                                            }
                                            if (canEditCoverFocus) {
                                                Surface(
                                                    modifier = Modifier
                                                        .align(Alignment.TopEnd)
                                                        .padding(4.dp),
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
                                                ) {
                                                    Text(
                                                        text = if (book.coverFocus == HomeCoverCropFocus.START) {
                                                            "左"
                                                        } else {
                                                            "中"
                                                        },
                                                        style = MaterialTheme.typography.labelSmall,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            if (!isBookSelectionMode && selected) {
                                                Surface(
                                                    modifier = Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .padding(4.dp),
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.home_opened),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 92.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            book.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            minLines = 2,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        LinearProgressIndicator(
                                            progress = { (playbackPercent / 100f).coerceIn(0f, 1f) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                                            trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                        )
                                        Spacer(modifier = Modifier.weight(1f, fill = true))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("$playbackPercent%")
                                            BookAttachmentBadges(
                                                hasSubtitle = book.srtUri != null,
                                                hasEbook = book.ebookUri != null
                                            )
                                        }
                                        if (multiSelected) {
                                            Text(stringResource(R.string.home_selected), color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                        }
                    }
                }

                if (srtLoading || srtError != null || exportStatus != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = hoshiPanelBackgroundColor())
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (srtLoading) Text(stringResource(R.string.bookreader_parsing_srt))
                            if (srtError != null) {
                                Text(
                                    stringResource(R.string.bookreader_srt_error, srtError.orEmpty()),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            if (exportStatus != null) Text(exportStatus!!)
                        }
                    }
                }
            }

            if (activeSection == MiningSection.DICTIONARY) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .let { baseModifier ->
                            if (showDictionaryManager) {
                                baseModifier.verticalScroll(dictionaryManagerScrollState)
                            } else {
                                baseModifier
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (dictionaryUiConfig.showRichHomeDictionary) {
                        DictionaryManagementCard(
                            context = context,
                            dictionaryScrollState = dictionaryManagerScrollState,
                            dictionaryCount = dictionaryCount,
                            containerColor = hoshiPanelBackgroundColor(),
                            itemContainerColor = hoshiCardBackgroundColor(),
                            dictionaryLoading = dictionaryLoading,
                            dictionaryProgressText = dictionaryProgressText,
                            dictionaryProgressValue = dictionaryProgressValue,
                            dictionaryError = dictionaryError,
                            showDictionaryManager = showDictionaryManager,
                            showDictionaryDeleteActions = showDictionaryDeleteActions,
                            dictionaryRefs = dictionaryRefs,
                            onImportClick = { pickDictionaryLauncher.launch(arrayOf("application/zip", "*/*")) },
                            onShowDictionaryManagerToggle = {
                                val nextShowDictionaryManager = !showDictionaryManager
                                showDictionaryManager = nextShowDictionaryManager
                                if (nextShowDictionaryManager) {
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                    lookupLoading = false
                                    dictionaryFirstLayerHtml = ""
                                    dictionaryFirstLayerResults = emptyList()
                                    dictionaryFirstLayerClearSelectionSignal += 1
                                    clearMainHoshiChildPopups()
                                }
                            },
                            onShowDictionaryDeleteActionsToggle = { showDictionaryDeleteActions = !showDictionaryDeleteActions },
                            onMoveImportedDictionary = { dictionaryId, toIndex ->
                                dictionaryController.moveImportedDictionary(
                                    dictionaryId = dictionaryId,
                                    toIndex = toIndex,
                                    onPersistDictionaryRefs = ::persistImportState,
                                    onLookupDataChanged = ::refreshLookupIfNeeded
                                )
                            },
                            onRemoveImportedDictionary = { index ->
                                dictionaryController.removeImportedDictionary(
                                    index = index,
                                    onPersistDictionaryRefs = ::persistImportState,
                                    onLookupDataChanged = ::refreshLookupIfNeeded
                                )
                            },
                            onSetImportedDictionaryEnabled = { dictionaryId, enabled ->
                                dictionaryController.setImportedDictionaryEnabled(
                                    dictionaryId = dictionaryId,
                                    enabled = enabled,
                                    onPersistDictionaryRefs = ::persistImportState,
                                    onLookupDataChanged = ::refreshLookupIfNeeded
                                )
                            }
                        )
                    }

                    if (!showDictionaryManager) {
                        OutlinedTextField(
                            value = lookupQuery,
                            onValueChange = { lookupQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.dictionary_query_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    if (effectiveLookupDictionaries.isNotEmpty() && lookupQuery.isNotBlank()) {
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                        triggerMainHoshiQueryLookup(lookupQuery)
                                    }
                                }
                            )
                        )

                        if (lookupLoading) {
                            Text(stringResource(R.string.dictionary_querying))
                        }

                        if (dictionaryFirstLayerHtml.isNotBlank()) {
                            var dictionaryFirstLayerOrigin by remember { mutableStateOf(Offset.Zero) }
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .onGloballyPositioned { coordinates ->
                                        val bounds = coordinates.boundsInWindow()
                                        val densityScale = rootDensity.density.coerceAtLeast(0.1f)
                                        dictionaryFirstLayerOrigin = Offset(
                                            x = bounds.left / densityScale,
                                            y = bounds.top / densityScale
                                        )
                                    },
                                color = dictionaryPageBackground,
                            ) {
                                MainHoshiResultWebView(
                                    html = dictionaryFirstLayerHtml,
                                    results = dictionaryFirstLayerResults,
                                    clearSelectionSignal = dictionaryFirstLayerClearSelectionSignal,
                                    selectionOffsetX = dictionaryFirstLayerOrigin.x.toDouble(),
                                    selectionOffsetY = dictionaryFirstLayerOrigin.y.toDouble(),
                                    callbacks = PopupWebViewCallbacks(
                                        onTapOutside = { dictionaryFirstLayerClearSelectionSignal += 1 },
                                        onSwipeDismiss = { dictionaryFirstLayerClearSelectionSignal += 1 },
                                        onOpenLink = { context.openPopupExternalLink(it) },
                                        onMineEntryAsync = { content, onComplete ->
                                            exportMainHoshiLookupEntryToAnkiAsync(content, onComplete)
                                        },
                                        onDuplicateCheckAsync = { expression, onComplete ->
                                            checkMainAnkiDuplicateAsync(expression, onComplete)
                                        },
                                        onViewDuplicate = { noteIds -> openAnkiDuplicateNotesInBrowser(context, noteIds) },
                                        onTextSelected = { selection ->
                                            val popup = createMainHoshiFirstLayerPopup(selection = selection)
                                            if (popup == null) {
                                                null
                                            } else {
                                                mainHoshiLookupPopups.clear()
                                                mainHoshiLookupPopups.add(popup.first)
                                                popup.second
                                            }
                                        },
                                        onLookupRedirect = { query ->
                                            val dictionarySettings = loadDictionarySettings(context)
                                            mainHoshiLookupSession.lookup(
                                                query,
                                                dictionarySettings.maxResults,
                                                dictionarySettings.scanLength,
                                            )
                                        },
                                        onLookupRedirected = { selection, results ->
                                            if (!pushMainHoshiRecursiveLookup(selection)) {
                                                Log.w(
                                                    "MainHoshiResultPopup",
                                                    "dictionary redirect failed to open recursive popup query='${selection.text.take(32)}' resultCount=${results.size}"
                                                )
                                            }
                                        },
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
                                    ),
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }

            if (activeSection == MiningSection.COLLECTIONS) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = hoshiPanelBackgroundColor())
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(stringResource(R.string.collections_title), style = MaterialTheme.typography.titleMedium)
                        Text(context.getString(R.string.collections_count, collectedCues.size))
                        if (collectedCues.isEmpty()) {
                            Text(stringResource(R.string.collections_empty))
                        } else {
                            collectedCues.forEach { item ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = hoshiSoftCardBackgroundColor())
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(item.text)
                                        Text(
                                            formatCollectedCueMeta(context, item),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = { playCollectedCue(item) }
                                            ) {
                                                Text(stringResource(R.string.common_play))
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    openMainLookupCuePreview(
                                                        cue = SubtitleCue(
                                                            startMs = item.startMs,
                                                            endMs = item.endMs,
                                                            text = item.text
                                                        ),
                                                        sourceBookTitle = item.bookTitle
                                                    )
                                                },
                                                enabled = effectiveLookupDictionaries.isNotEmpty()
                                            ) {
                                                Text(stringResource(R.string.common_lookup))
                                            }
                                            OutlinedButton(
                                                onClick = { openTemporaryLookupPlayer(item) },
                                                enabled = effectiveLookupDictionaries.isNotEmpty()
                                            ) {
                                                Text(stringResource(R.string.common_temporary_lookup))
                                            }
                                            IconButton(
                                                onClick = {
                                                    removeBookReaderCollectedCue(context, item.id)
                                                    refreshCollectedCues()
                                                },
                                                modifier = Modifier.size(48.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Delete,
                                                    contentDescription = stringResource(R.string.common_delete)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (activeSection == MiningSection.SETTINGS) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsPanel(
                        selectedAppLanguageLabel = selectedAppLanguage.displayLabel(context),
                        versionName = resolveAppVersionName(context),
                        onAudiobookClick = { context.startActivity(Intent(context, AudiobookSettingsActivity::class.java)) },
                        onControlModeClick = { context.startActivity(Intent(context, ControlModeSettingsActivity::class.java)) },
                        onAudiobookUiClick = { context.startActivity(Intent(context, AudiobookUiSettingsActivity::class.java)) },
                        onFontClick = { context.startActivity(Intent(context, FontSettingsActivity::class.java)) },
                        onControllerClick = { context.startActivity(Intent(context, ControllerSettingsActivity::class.java)) },
                        onAnkiClick = { context.startActivity(Intent(context, AnkiSettingsActivity::class.java)) },
                        onDictionaryClick = { context.startActivity(Intent(context, DictionarySettingsActivity::class.java)) },
                        onAdvancedOverlayClick = { context.startActivity(Intent(context, OverlaySettingsActivity::class.java)) },
                        onAdvancedStatisticsClick = { context.startActivity(Intent(context, StatisticsDemoActivity::class.java)) },
                        onAdvancedOtherClick = { context.startActivity(Intent(context, OtherSettingsActivity::class.java)) },
                        onLanguageClick = { languageDialogVisible = true },
                        onGuideClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/tza14/9Player")
                            )
                            runCatching { context.startActivity(intent) }
                                .onFailure { Toast.makeText(context, context.getString(R.string.settings_open_link_failed), Toast.LENGTH_SHORT).show() }
                        },
                        onExportDiagnosticsClick = { diagnosticsExportSheetVisible = true },
                        onUpdateClick = { context.startActivity(Intent(context, UpdateSettingsActivity::class.java)) },
                        onVersionClick = {
                            val version = resolveAppVersionName(context)
                            Toast.makeText(context, context.getString(R.string.settings_version_toast, version), Toast.LENGTH_SHORT).show()
                            versionTapCount += 1
                            if (versionTapCount >= 5) {
                                versionTapCount = 0
                                showVersionEasterGif = true
                            }
                        }
                    )
                }
            }
        }

        if (diagnosticsExportSheetVisible) {
            DiagnosticsExportSheet(
                onDismiss = { diagnosticsExportSheetVisible = false },
                onDownload = {
                    diagnosticsExportSheetVisible = false
                    runCatching { downloadDiagnosticsReport(context) }
                        .onSuccess { fileName ->
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_export_diagnostics_saved, fileName),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        .onFailure {
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_export_diagnostics_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                },
                onShare = {
                    diagnosticsExportSheetVisible = false
                    runCatching { shareDiagnosticsReport(context) }
                        .onFailure {
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_export_diagnostics_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                }
            )
        }

        if (renameBookDialogVisible) {
            AlertDialog(
                onDismissRequest = {
                    renameBookDialogVisible = false
                    renameTargetBookId = null
                },
                title = { Text(stringResource(R.string.home_rename_book)) },
                text = {
                    OutlinedTextField(
                        value = renameBookInput,
                        onValueChange = { renameBookInput = it.take(80) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.home_book_name)) }
                    )
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            renameBookDialogVisible = false
                            renameTargetBookId = null
                        }
                    ) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
                confirmButton = {
                    Button(onClick = { applyRenameBook() }) {
                        Text(stringResource(R.string.common_confirm))
                    }
                }
            )
        }

        if (languageDialogVisible) {
            AppLanguageDialog(
                selectedAppLanguage = selectedAppLanguage,
                onDismiss = { languageDialogVisible = false },
                onSelectLanguage = { option ->
                    selectedAppLanguage = option
                    saveAppLanguageOption(context, option)
                    applyAppLanguage(option)
                    languageDialogVisible = false
                    val activity = context as? Activity
                    if (activity != null) {
                        val restartIntent = Intent(activity, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                        activity.startActivity(restartIntent)
                        activity.overridePendingTransition(0, 0)
                        activity.finish()
                    }
                }
            )
        }

        if (importGuideVisible) {
            ImportGuideDialog(
                onKeepOriginal = {
                    autoMoveToAudiobookFolder = false
                    keepSourceFilesWhenAutoMove = false
                    importOnboardingCompleted = true
                    importGuideVisible = false
                    persistImportState()
                },
                onAutoMove = {
                    autoMoveToAudiobookFolder = true
                    keepSourceFilesWhenAutoMove = false
                    importOnboardingCompleted = true
                    importGuideVisible = false
                    persistImportState()
                }
            )
        }

        if (autoUpdatePromptVisible) {
            AutoUpdateFirstPromptDialog(
                onSkip = {
                    markAutoUpdateFirstPromptShown(context)
                    saveAutoUpdateEnabled(context, false)
                    autoUpdatePromptVisible = false
                },
                onEnable = {
                    markAutoUpdateFirstPromptShown(context)
                    saveAutoUpdateEnabled(context, true)
                    autoUpdatePromptVisible = false
                    mainActivity?.checkAppUpdateInBackground(force = true)
                }
            )
        }

        mainActivity?.launchUpdatePromptRelease?.let { release ->
            AppUpdateLaunchPromptDialog(
                releaseDisplayName = release.displayName,
                onDismiss = { mainActivity.dismissLaunchUpdatePrompt() },
                onDownload = { mainActivity.downloadLaunchUpdatePrompt(release) }
            )
        }

        if (clearCollectionsConfirmVisible) {
            ClearCollectionsDialog(
                onDismiss = { clearCollectionsConfirmVisible = false },
                onConfirm = {
                    clearBookReaderCollectedCues(context)
                    refreshCollectedCues()
                    clearCollectionsConfirmVisible = false
                }
            )
        }

        if (deleteBooksConfirmVisible) {
            DeleteBooksConfirmDialog(
                deleteSourceFiles = deleteBooksDeleteSourceFiles,
                onDeleteSourceFilesChange = { checked -> deleteBooksDeleteSourceFiles = checked },
                onDismiss = {
                    deleteBooksConfirmVisible = false
                    pendingDeleteBookIds = emptySet()
                },
                onConfirm = {
                    val removeIds = pendingDeleteBookIds
                    val deleteSourceFiles = deleteBooksDeleteSourceFiles
                    deleteBooksConfirmVisible = false
                    pendingDeleteBookIds = emptySet()
                    deleteBooksDeleteSourceFiles = false
                    deleteSelectedBooks(removeIds, deleteSourceFiles = deleteSourceFiles)
                }
            )
        }

        if (addBookDialogVisible) {
            AddBookDialog(
                folderName = addBookFolderName,
                folderUri = addBookFolderUri,
                audioName = addBookAudioName,
                audioUri = addBookAudioUri,
                srtName = addBookSrtName,
                ebookEnabled = ebookFeatureEnabled,
                ebookOnlyImportEnabled = loadEbookOnlyImportEnabled(context),
                ebookName = addBookEbookName,
                ebookUri = addBookEbookUri,
                autoMoveToAudiobookFolder = autoMoveToAudiobookFolder,
                srtLoading = srtLoading,
                onPickFolder = { pickBookFolderLauncher.launch(null) },
                onClearFolderSelection = {
                    addBookFolderUri = null
                    addBookFolderName = null
                    addBookAudioUri = null
                    addBookAudioName = null
                    addBookSrtUri = null
                    addBookSrtName = null
                    addBookEbookUri = null
                    addBookEbookName = null
                    addBookEbookFormat = null
                    persistImportState()
                },
                onPickAudio = {
                    pickBookAudioLauncher.launch(
                        arrayOf(
                            "audio/*",
                            "video/*",
                            "audio/mp4",
                            "audio/x-m4a",
                            "audio/m4a",
                            "audio/x-m4b",
                            "audio/m4b",
                            "application/mp4",
                            "video/mp4",
                            "video/x-matroska",
                            "video/webm",
                            "video/quicktime",
                            "video/x-msvideo",
                            "video/3gpp"
                        )
                    )
                },
                onPickSrt = {
                    pickBookSrtLauncher.launch(
                        arrayOf("application/x-subrip")
                    )
                },
                onPickEbook = {
                    pickBookEbookLauncher.launch(
                        arrayOf("application/epub+zip", "text/plain", "application/octet-stream")
                    )
                },
                onDismiss = { addBookDialogVisible = false },
                onConfirm = { confirmAddBookFromDialog() }
            )
        }

        if (collectionLookupPreviewVisible) {
            MainCollectionsHoshiPopup(
                previewSentence = collectionLookupPreviewSentence,
                selectedRange = collectionLookupPreviewSelectedRange,
                html = collectionFirstLayerHtml,
                results = collectionFirstLayerResults,
                clearSelectionSignal = collectionFirstLayerClearSelectionSignal,
                onClose = { closeMainLookupPopup() },
                onPreviewLookup = { offset, layout, coords ->
                    val selection = selectLookupScanText(collectionLookupPreviewSentence, offset)
                        ?: return@MainCollectionsHoshiPopup
                    val query = selection.text.trim()
                    if (query.isBlank()) return@MainCollectionsHoshiPopup
                    val anchor = if (layout != null && coords != null) {
                        val charOffset = selection.range.first
                            .coerceIn(0, (collectionLookupPreviewSentence.length - 1).coerceAtLeast(0))
                        val localRect = layout.getBoundingBox(charOffset)
                        val topLeft = coords.localToWindow(localRect.topLeft)
                        val bottomRight = coords.localToWindow(localRect.bottomRight)
                        ReaderLookupAnchor(
                            rects = listOf(
                                Rect(
                                    topLeft.x,
                                    topLeft.y,
                                    bottomRight.x,
                                    bottomRight.y
                                )
                            )
                        )
                    } else null
                    if (anchor == null) {
                        exportStatus = context.getString(R.string.bookreader_lookup_failed)
                        Log.w(
                            "MainHoshiResultPopup",
                            "Missing anchor for collection first-layer lookup; refusing to open popup"
                        )
                        return@MainCollectionsHoshiPopup
                    }
                    logDebug("MainHoshiResultPopup") {
                        "collection first-layer anchor=${anchor.boundingRectCoreOrNull()?.let { "${it.left},${it.top},${it.right},${it.bottom}" }} " +
                        "query='${query.take(32)}' selection='${selection.text.take(32)}' selectedRange=${selection.range}"
                    }
                    showCollectionFirstLayerLookup(
                        selection = mainHoshiFallbackSelection(query, anchor).copy(
                            sentence = collectionLookupPreviewSentence,
                            sentenceOffset = selection.range.first
                        ),
                        sourceRange = selection.range,
                        cue = collectionLookupPreviewCue,
                        audioForExport = collectionLookupPreviewAudioUri
                    )
                },
                onResultTextSelected = { selection ->
                    val popup = createMainHoshiFirstLayerPopup(selection = selection)
                    if (popup == null) {
                        null
                    } else {
                        mainHoshiLookupPopups.clear()
                        mainHoshiLookupPopups.add(popup.first)
                        popup.second
                    }
                },
                onLookupRedirect = { query ->
                    val dictionarySettings = loadDictionarySettings(context)
                    mainHoshiLookupSession.lookup(
                        query,
                        dictionarySettings.maxResults,
                        dictionarySettings.scanLength,
                    )
                },
                onResultRedirected = { selection, results ->
                    if (!pushMainHoshiRecursiveLookup(selection)) {
                        Log.w(
                            "MainHoshiResultPopup",
                            "collection redirect failed to open recursive popup query='${selection.text.take(32)}' resultCount=${results.size}"
                        )
                    }
                },
                onTapOutside = {
                    collectionFirstLayerClearSelectionSignal += 1
                },
                onMineEntry = { false },
                onMineEntryAsync = { content, onComplete ->
                    exportMainHoshiLookupEntryToAnkiAsync(content, onComplete)
                },
                onDuplicateCheck = { AnkiDuplicateCheckResult() },
                onDuplicateCheckAsync = { expression, onComplete ->
                    checkMainAnkiDuplicateAsync(expression, onComplete)
                },
                onViewDuplicate = { noteIds -> openAnkiDuplicateNotesInBrowser(context, noteIds) },
                onPlayWordAudio = { term, reading ->
                    if (!term.isNullOrBlank()) {
                        playLookupAudioForTerm(
                            context = context,
                            term = term,
                            reading = reading,
                            settings = audiobookSettings
                        )
                    }
                },
            )
        }

        LookupPopupStackView(
                popups = mainHoshiLookupPopups,
                onPopupsChange = { next ->
                    logDebug("MainHoshiResultPopup") {
                        "stack onPopupsChange old=${mainHoshiLookupPopups.size} new=${next.size} ids=${next.joinToString(",") { it.id.take(8) }}"
                    }
                    mainHoshiLookupPopups.clear()
                    mainHoshiLookupPopups.addAll(next)
                    if (next.isEmpty()) {
                        mainHoshiLookupCue = null
                        mainHoshiLookupSelectedRange = null
                        mainHoshiLookupAudioUri = null
                        mainHoshiLookupTitle = ""
                    }
                },
                lookupChildPopup = { selection ->
                    mainHoshiLookupSession.createPopup(
                        selection = selection,
                        options = mainHoshiLookupOptions(showRangeSelection = false),
                    )
                },
                onLookupRedirect = { query ->
                    val dictionarySettings = loadDictionarySettings(context)
                    mainHoshiLookupSession.lookup(
                        query,
                        dictionarySettings.maxResults,
                        dictionarySettings.scanLength,
                    )
                },
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
                onMineEntryAsync = { content, onComplete ->
                    exportMainHoshiLookupEntryToAnkiAsync(content, onComplete)
                },
                onDuplicateCheckAsync = { expression, onComplete ->
                    checkMainAnkiDuplicateAsync(expression, onComplete)
                },
                onViewDuplicate = { noteIds -> openAnkiDuplicateNotesInBrowser(context, noteIds) },
                onCloseAll = {
                    clearMainHoshiChildPopups()
                },
                modifier = Modifier.fillMaxSize(),
                onRootPopupDismissed = {
                    clearMainHoshiChildPopups()
                },
                warmRootOptions = mainHoshiLookupOptions(showRangeSelection = false),
                platformPopupHost = collectionLookupPreviewVisible,
                extraBottomInsetDp = (
                    innerPadding.calculateBottomPadding().value.toDouble() - navigationBarBottomInsetDp
                ).coerceAtLeast(0.0),
        )

        VersionEasterGifPopup(
            visible = showVersionEasterGif && activeSection == MiningSection.SETTINGS,
            bottomPadding = innerPadding.calculateBottomPadding(),
            onDismiss = { showVersionEasterGif = false }
        )
        }
    }
}

@Composable
private fun VersionEasterGifPopup(
    visible: Boolean,
    bottomPadding: Dp,
    onDismiss: () -> Unit
) {
    if (!visible) return
    val density = LocalDensity.current
    val horizontalOffsetPx = with(density) { (-14).dp.roundToPx() }
    val verticalOffsetPx = with(density) { -(bottomPadding + 8.dp).roundToPx() }
    Popup(
        alignment = Alignment.BottomEnd,
        offset = IntOffset(horizontalOffsetPx, verticalOffsetPx),
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = false
        )
    ) {
        Box(
            modifier = Modifier
                .size(90.dp)
                .clickable { onDismiss() }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { popupContext ->
                    ImageView(popupContext).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        adjustViewBounds = true
                        val animated = runCatching {
                            ImageDecoder.decodeDrawable(
                                ImageDecoder.createSource(resources, R.raw.easter_chibi)
                            )
                        }.getOrNull()
                        if (animated != null) {
                            setImageDrawable(animated)
                            (animated as? AnimatedImageDrawable)?.apply {
                                repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                                start()
                            }
                        } else {
                            setImageResource(R.mipmap.ic_launcher_foreground)
                        }
                    }
                }
            )
        }
    }
}

internal fun resolveAppVersionName(context: Context): String {
    return runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
            .versionName
            ?.trim()
            .orEmpty()
            .ifBlank { "unknown" }
    }.getOrDefault("unknown")
}

internal fun resolveAppVersionCode(context: Context): Long {
    return runCatching {
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo.longVersionCode
    }.getOrDefault(-1L)
}

private fun shareDiagnosticsReport(context: Context) {
    val report = buildDiagnosticsReport(context)
    val reportFile = writeDiagnosticsReportFile(context, report)
    val reportUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        reportFile
    )
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_export_diagnostics_subject))
        putExtra(Intent.EXTRA_STREAM, reportUri)
        clipData = android.content.ClipData.newUri(context.contentResolver, reportFile.name, reportUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(
        shareIntent,
        context.getString(R.string.settings_export_diagnostics_title)
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

private const val DIAGNOSTICS_REPORT_PREFIX = "9player-diagnostics-"

private fun diagnosticsReportFileName(): String =
    "$DIAGNOSTICS_REPORT_PREFIX${System.currentTimeMillis()}.txt"

private fun clearCachedDiagnosticsReports(context: Context) {
    File(context.cacheDir, "anki_media")
        .listFiles()
        ?.filter { it.name.startsWith(DIAGNOSTICS_REPORT_PREFIX) && it.extension.equals("txt", ignoreCase = true) }
        ?.forEach { runCatching { it.delete() } }
}

private fun writeDiagnosticsReportFile(context: Context, report: String): File {
    clearCachedDiagnosticsReports(context)
    val diagnosticsDir = File(context.cacheDir, "anki_media").apply { mkdirs() }
    return File(diagnosticsDir, diagnosticsReportFileName()).apply {
        writeText(report, Charsets.UTF_8)
    }
}

private fun downloadDiagnosticsReport(context: Context): String {
    val report = buildDiagnosticsReport(context)
    val displayName = diagnosticsReportFileName()
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, displayName)
        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("Unable to create diagnostics report in Downloads")
    try {
        resolver.openOutputStream(uri)?.use { stream ->
            stream.write(report.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("Unable to open diagnostics report output stream")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    } catch (t: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        throw t
    }
    return displayName
}

private fun buildDiagnosticsReport(context: Context): String {
    val versionName = resolveAppVersionName(context)
    val versionCode = resolveAppVersionCode(context)
    val appLanguage = loadAppLanguageOption(context).displayLabel(context)
    val audiobookSettings = loadAudiobookSettingsConfig(context)
    val persistedImports = loadPersistedImports(context)
    val persistedAnki = loadPersistedAnkiConfig(context)
    val ankiResolvedPackage = resolveAnkiPackageName(context)
    val recentLogs = recentLogsForDiagnostics(200)

    return buildString {
        appendLine("9Player Diagnostics")
        appendLine()
        appendLine("[App]")
        appendLine("VersionName=$versionName")
        appendLine("VersionCode=$versionCode")
        appendLine("Package=${context.packageName}")
        appendLine()
        appendLine("[Device]")
        appendLine("Android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Brand=${Build.BRAND}")
        appendLine("Manufacturer=${Build.MANUFACTURER}")
        appendLine("Model=${Build.MODEL}")
        appendLine("Device=${Build.DEVICE}")
        appendLine()
        appendLine("[Settings Summary]")
        appendLine("AppLanguage=$appLanguage")
        appendLine("ImportedBooks=${persistedImports.books.size}")
        appendLine("ImportedDictionaries=${persistedImports.dictionaries.size}")
        appendLine("AutoMoveToAudiobookFolder=${persistedImports.autoMoveToAudiobookFolder}")
        appendLine("DeleteSourceFilesWhenAutoMove=${!persistedImports.keepSourceFilesWhenAutoMove}")
        appendLine("HomeLibraryView=${persistedImports.homeLibraryView}")
        appendLine("HomeCoverAspect=${persistedImports.homeCoverAspect}")
        appendLine("FloatingOverlayEnabled=${audiobookSettings.floatingOverlayEnabled}")
        appendLine("FloatingOverlaySubtitleEnabled=${audiobookSettings.floatingOverlaySubtitleEnabled}")
        appendLine("FloatingOverlaySubtitleY=${audiobookSettings.floatingOverlaySubtitleY}")
        appendLine("FloatingOverlayBubbleX=${audiobookSettings.floatingOverlayBubbleX}")
        appendLine("FloatingOverlayBubbleY=${audiobookSettings.floatingOverlayBubbleY}")
        appendLine("FloatingOverlaySizeDp=${audiobookSettings.floatingOverlaySizeDp}")
        appendLine("FloatingSubtitleSizeSp=${audiobookSettings.floatingOverlaySubtitleSizeSp}")
        appendLine("PausePlaybackOnLookup=${audiobookSettings.pausePlaybackOnLookup}")
        appendLine("LookupAudioEnabled=${audiobookSettings.lookupPlaybackAudioEnabled}")
        appendLine("LookupAudioAutoPlay=${audiobookSettings.lookupPlaybackAudioAutoPlay}")
        appendLine("LookupAudioMode=${audiobookSettings.lookupAudioMode.storageValue}")
        appendLine("LookupFullSentence=${audiobookSettings.lookupExportFullSentence}")
        appendLine("LookupRangeSelection=${audiobookSettings.lookupRangeSelectionEnabled}")
        appendLine()
        appendLine("[Reader Playback]")
        appendLine(buildReaderPlaybackDiagnostics(context, persistedImports))
        appendLine()
        appendLine("[Anki Diagnostics]")
        appendLine("AvailabilityState=${detectAnkiAvailability(context, requirePermission = true)}")
        appendLine("ResolvedPackage=${ankiResolvedPackage ?: "(null)"}")
        appendLine("Installed=${isAnkiInstalled(context)}")
        appendLine("ReadWritePermission=${hasAnkiReadWritePermission(context)}")
        appendLine("Deck=${persistedAnki.deckName}")
        appendLine("Model=${persistedAnki.modelName.ifBlank { "(blank)" }}")
        appendLine("Tags=${persistedAnki.tags.ifBlank { "(blank)" }}")
        appendLine("FieldTemplateCount=${persistedAnki.fieldTemplates.size}")
        appendLine()
        appendLine(buildCrashDiagnosticsReport(context))
        appendLine()
        appendLine("[Recent Logs]")
        appendLine(recentLogs.ifBlank { "(no recent logs captured)" })
    }
}

private fun buildReaderPlaybackDiagnostics(
    context: Context,
    persistedImports: PersistedImports
): String {
    if (persistedImports.books.isEmpty()) return "(no imported books)"
    return persistedImports.books.take(20).joinToString(separator = "\n") { persistedBook ->
        val readerBook = persistedBook.toReaderBookOrNull()
        if (readerBook == null) {
            "Book=${persistedBook.title.ifBlank { persistedBook.audioName }} source=invalid-audio-uri"
        } else {
            val snapshot = readerBook.audioUri?.let {
                loadBookReaderPlaybackSnapshotOrNull(context, buildReaderBookPlaybackKey(readerBook))
            }
            "Book=${readerBook.title.ifBlank { readerBook.audioName ?: readerBook.ebookName.orEmpty() }.take(48)} " +
                "positionMs=${snapshot?.positionMs ?: 0L} " +
                "durationMs=${snapshot?.durationMs ?: 0L} " +
                "updatedAt=${snapshot?.updatedAtMs ?: 0L}"
        }

    }
}

private fun PersistedReaderBook.toReaderBookOrNull(): ReaderBook? {
    val parsedAudioUri = audioUri?.trim()?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
    val parsedSrtUri = srtUri?.trim()?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
    val parsedEbookUri = ebookUri?.trim()?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
    if (parsedAudioUri == null && parsedEbookUri == null) return null
    return ReaderBook(
        id = id,
        title = title.ifBlank { audioName?.let { buildBookTitle(it, srtName) } ?: buildEbookTitle(ebookName) },
        audioUri = parsedAudioUri,
        audioName = audioName,
        srtUri = parsedSrtUri,
        srtName = srtName,
        ebookUri = parsedEbookUri,
        ebookName = ebookName,
        ebookFormat = ebookFormat,
        coverUri = null,
        coverSource = if (parsedAudioUri == null && parsedEbookUri != null) ReaderBookCoverSource.EBOOK else null,
        coverFocus = bookFocusOrDefault(this),
        startBookCoverAdjustment = persistedBookCoverAdjustment(this, HomeCoverCropFocus.START),
        centerBookCoverAdjustment = persistedBookCoverAdjustment(this, HomeCoverCropFocus.CENTER),
        addedAtMs = addedAtMs
    )
}

private fun persistedBookImportKey(book: PersistedReaderBook): String {
    return book.audioUri?.takeIf { it.isNotBlank() }
        ?: book.ebookUri?.takeIf { it.isNotBlank() }
        ?: book.id
}

private fun readerBookImportKey(book: ReaderBook): String {
    return book.audioUri?.toString()?.takeIf { it.isNotBlank() }
        ?: book.ebookUri?.toString()?.takeIf { it.isNotBlank() }
        ?: book.id
}



private data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

private fun SubtitleCue.toReaderSubtitleCue(): ReaderSubtitleCue {
    return ReaderSubtitleCue(
        startMs = startMs,
        endMs = endMs,
        text = text
    )
}

@Composable
private fun BookAttachmentBadges(
    hasSubtitle: Boolean,
    hasEbook: Boolean,
    modifier: Modifier = Modifier
) {
    if (!hasSubtitle && !hasEbook) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasSubtitle) {
            BookAttachmentBadge(
                icon = Icons.Outlined.ClosedCaption,
                contentDescription = stringResource(R.string.home_subtitle_attached)
            )
        }
        if (hasEbook) {
            BookAttachmentBadge(
                icon = Icons.Outlined.Book,
                contentDescription = stringResource(R.string.home_ebook_attached)
            )
        }
    }
}

@Composable
private fun BookAttachmentBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(4.dp)
                .size(16.dp)
        )
    }
}

@Composable
private fun BookCoverThumbnail(
    coverUri: Uri,
    modifier: Modifier = Modifier,
    coverFocus: HomeCoverCropFocus = HomeCoverCropFocus.CENTER,
    coverAdjustment: BookCoverAdjustment? = null,
    useStartFocus: Boolean = false,
    maxZoom: Float = BookCoverPreviewMaxZoom,
    clampToBounds: Boolean = true,
    showBlackBackground: Boolean = false
) {
    val context = LocalContext.current.applicationContext
    var targetSize by remember(coverUri) { mutableStateOf(IntSize.Zero) }
    val decodeDimension = if (targetSize.width > 0 && targetSize.height > 0) {
        val zoom = coverAdjustment?.zoom?.coerceIn(1f, maxZoom) ?: 1f
        (maxOf(targetSize.width, targetSize.height) * zoom)
            .roundToInt()
            .coerceIn(1, 1536)
    } else {
        0
    }
    val needsCustomMatrix = coverAdjustment != null ||
        (useStartFocus && coverFocus == HomeCoverCropFocus.START)
    val renderKey = "$coverUri|$coverAdjustment|$coverFocus|$useStartFocus|$maxZoom|$clampToBounds|$decodeDimension"
    val coverEntry by produceState<DecodedBookCover?>(
        initialValue = BookCoverThumbnailCache.get(coverUri, decodeDimension),
        key1 = coverUri,
        key2 = decodeDimension
    ) {
        if (decodeDimension > 0 && value == null) {
            value = BookCoverThumbnailCache.load(context, coverUri, decodeDimension)
        }
    }
    val bitmap = coverEntry?.bitmap
    val bitmapRenderKey = if (bitmap != null) {
        "$renderKey|bitmap:${System.identityHashCode(bitmap)}"
    } else {
        renderKey
    }
    AndroidView(
        modifier = modifier.onSizeChanged { targetSize = it },
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = false
                if (showBlackBackground) {
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
                tag = bitmapRenderKey
                alpha = if (needsCustomMatrix && bitmap != null) 0f else 1f
                if (bitmap != null) setImageBitmap(bitmap) else setImageDrawable(null)
            }
        },
        update = { view ->
            view.setBackgroundColor(
                if (showBlackBackground) android.graphics.Color.BLACK else android.graphics.Color.TRANSPARENT
            )
            if (view.tag != bitmapRenderKey) {
                view.tag = bitmapRenderKey
                if (bitmap != null) view.setImageBitmap(bitmap) else view.setImageDrawable(null)
                view.alpha = if (needsCustomMatrix && bitmap != null) 0f else 1f
            }
            view.scaleType = if (needsCustomMatrix) {
                ImageView.ScaleType.MATRIX
            } else {
                ImageView.ScaleType.CENTER_CROP
            }
            if (needsCustomMatrix) {
                fun applyMatrixWhenReady() {
                    if (view.tag != bitmapRenderKey || view.drawable == null) return
                    if (view.width <= 0 || view.height <= 0) {
                        view.post { applyMatrixWhenReady() }
                        return
                    }
                    if (coverAdjustment != null) {
                        applyBookCoverAdjustment(
                            view,
                            coverAdjustment,
                            maxZoom,
                            clampToBounds,
                            coverEntry?.originalWidth,
                            coverEntry?.originalHeight
                        )
                    } else {
                        applyStartCrop(view)
                    }
                    view.alpha = 1f
                }
                view.post { applyMatrixWhenReady() }
            } else {
                view.imageMatrix = Matrix()
                view.alpha = 1f
            }
        }
    )
}

private object BookCoverThumbnailCache {
    private val cache = object : LruCache<String, DecodedBookCover>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: DecodedBookCover): Int = value.bitmap.byteCount
    }

    fun get(uri: Uri, targetDimension: Int): DecodedBookCover? = synchronized(cache) {
        cache.get(cacheKey(uri, targetDimension))
    }

    suspend fun load(context: Context, uri: Uri, targetDimension: Int): DecodedBookCover? = withContext(Dispatchers.IO) {
        if (targetDimension <= 0) return@withContext null
        val key = cacheKey(uri, targetDimension)
        synchronized(cache) {
            cache.get(key)?.let { return@withContext it }
        }

        var originalWidth = 0
        var originalHeight = 0
        val bitmap = try {
            val source = if (uri.scheme.equals("file", ignoreCase = true)) {
                ImageDecoder.createSource(File(uri.path ?: return@withContext null))
            } else {
                ImageDecoder.createSource(context.contentResolver, uri)
            }
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // 原始尺寸在解码回调里顺便拿到（info.size），
                // 无需再单独打开文件读一遍文件头
                originalWidth = info.size.width
                originalHeight = info.size.height
                val longest = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                val scale = minOf(1f, targetDimension.toFloat() / longest)
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1)
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (error: Exception) {
            Log.e("BookCoverThumbnail", "decode failed uri=$uri target=$targetDimension", error)
            return@withContext null
        }
        bitmap.prepareToDraw()
        val entry = DecodedBookCover(
            bitmap = bitmap,
            originalWidth = originalWidth.takeIf { it > 0 } ?: bitmap.width,
            originalHeight = originalHeight.takeIf { it > 0 } ?: bitmap.height
        )
        synchronized(cache) {
            cache.put(key, entry)
        }
        entry
    }

    private fun cacheKey(uri: Uri, targetDimension: Int): String =
        "${uri}#$targetDimension"
}

private fun applyStartCrop(view: ImageView) {
    applyBookCoverAdjustment(view, defaultBookCoverAdjustmentForFocus(HomeCoverCropFocus.START))
}

@Composable
private fun BookCoverBitmapPreview(
    bitmap: Bitmap,
    modifier: Modifier = Modifier,
    coverFocus: HomeCoverCropFocus = HomeCoverCropFocus.CENTER,
    coverAdjustment: BookCoverAdjustment,
    maxZoom: Float = BookCoverPreviewMaxZoom,
    clampToBounds: Boolean = true,
    showBlackBackground: Boolean = false
) {
    Canvas(modifier = modifier.clipToBounds()) {
        if (showBlackBackground) {
            drawRect(Color.Black)
        }
        val width = size.width.roundToInt().coerceAtLeast(1)
        val height = size.height.roundToInt().coerceAtLeast(1)
        val renderSpec = computeBookCoverRenderSpec(
            drawableWidth = bitmap.width,
            drawableHeight = bitmap.height,
            viewWidth = width,
            viewHeight = height,
            adjustment = coverAdjustment,
            focus = coverFocus,
            maxZoom = maxZoom,
            clampToBounds = clampToBounds
        )
        val destRect = RectF(
            renderSpec.dx,
            renderSpec.dy,
            renderSpec.dx + bitmap.width * renderSpec.scale,
            renderSpec.dy + bitmap.height * renderSpec.scale
        )
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawBitmap(bitmap, null, destRect, null)
        }
    }
}

@Composable
private fun CoverAdjustSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

@Composable
private fun CoverAdjustChoicePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
            }
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@Composable
private fun CoverAdjustDirectionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun applyBookCoverAdjustment(
    view: ImageView,
    adjustment: BookCoverAdjustment,
    maxZoom: Float = BookCoverPreviewMaxZoom,
    clampToBounds: Boolean = true,
    originalWidth: Int? = null,
    originalHeight: Int? = null
) {
    val drawable = view.drawable ?: return
    val drawableWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: return
    val drawableHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: return
    val viewWidth = view.width.takeIf { it > 0 } ?: return
    val viewHeight = view.height.takeIf { it > 0 } ?: return
    val renderAdjustment = scaleBookCoverAdjustmentForDrawable(
        adjustment = adjustment,
        drawableWidth = drawableWidth,
        drawableHeight = drawableHeight,
        originalWidth = originalWidth,
        originalHeight = originalHeight
    )
    val renderSpec = computeBookCoverRenderSpec(
        drawableWidth = drawableWidth,
        drawableHeight = drawableHeight,
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        adjustment = renderAdjustment,
        focus = HomeCoverCropFocus.CENTER,
        maxZoom = maxZoom,
        clampToBounds = clampToBounds
    )
    view.imageMatrix = Matrix().apply {
        setScale(renderSpec.scale, renderSpec.scale)
        postTranslate(renderSpec.dx, renderSpec.dy)
    }
}

private fun scaleBookCoverAdjustmentForDrawable(
    adjustment: BookCoverAdjustment,
    drawableWidth: Int,
    drawableHeight: Int,
    originalWidth: Int?,
    originalHeight: Int?
): BookCoverAdjustment {
    if (originalWidth == null || originalHeight == null) return adjustment
    if (originalWidth == drawableWidth && originalHeight == drawableHeight) return adjustment
    val rotated = originalWidth == drawableHeight && originalHeight == drawableWidth
    val sourceWidth = if (rotated) originalHeight else originalWidth
    val sourceHeight = if (rotated) originalWidth else originalHeight
    val scaleX = drawableWidth.toFloat() / sourceWidth.toFloat()
    val scaleY = drawableHeight.toFloat() / sourceHeight.toFloat()
    return adjustment.copy(
        anchorXPx = adjustment.anchorXPx?.let { (it * scaleX).roundToInt() },
        anchorYPx = adjustment.anchorYPx?.let { (it * scaleY).roundToInt() },
        anchorXExactPx = adjustment.anchorXExactPx?.let { it * scaleX },
        anchorYExactPx = adjustment.anchorYExactPx?.let { it * scaleY }
    )
}

private fun computeBookCoverRenderSpec(
    drawableWidth: Int,
    drawableHeight: Int,
    viewWidth: Int,
    viewHeight: Int,
    adjustment: BookCoverAdjustment,
    focus: HomeCoverCropFocus,
    maxZoom: Float = BookCoverPreviewMaxZoom,
    clampToBounds: Boolean = true
): BookCoverRenderSpec {
    val baseScale = maxOf(
        viewWidth.toFloat() / drawableWidth.toFloat(),
        viewHeight.toFloat() / drawableHeight.toFloat()
    )
    val finalScale = baseScale * adjustment.zoom.coerceIn(1f, maxZoom)
    val visibleWidth = viewWidth / finalScale
    val visibleHeight = viewHeight / finalScale
    val maxAnchorX = (drawableWidth - visibleWidth).coerceAtLeast(0f)
    val maxAnchorY = (drawableHeight - visibleHeight).coerceAtLeast(0f)
    val viewportX = adjustment.fallbackViewportX
        ?: defaultBookCoverAdjustmentForFocus(focus).fallbackViewportX
        ?: 0.5f
    val viewportY = adjustment.fallbackViewportY
        ?: defaultBookCoverAdjustmentForFocus(focus).fallbackViewportY
        ?: 0.5f
    val exactAnchorX = adjustment.anchorXExactPx ?: adjustment.anchorXPx?.toFloat()
    val exactAnchorY = adjustment.anchorYExactPx ?: adjustment.anchorYPx?.toFloat()
    val anchorX = if (exactAnchorX != null) {
        if (clampToBounds) exactAnchorX.coerceIn(0f, maxAnchorX)
        else exactAnchorX
    } else {
        (maxAnchorX * viewportX.coerceIn(0f, 1f))
    }
    val anchorY = if (exactAnchorY != null) {
        if (clampToBounds) exactAnchorY.coerceIn(0f, maxAnchorY)
        else exactAnchorY
    } else {
        (maxAnchorY * viewportY.coerceIn(0f, 1f))
    }
    return BookCoverRenderSpec(
        scale = finalScale,
        dx = -anchorX * finalScale,
        dy = -anchorY * finalScale,
        drawableWidth = drawableWidth,
        drawableHeight = drawableHeight,
        viewWidth = viewWidth,
        viewHeight = viewHeight
    )
}

private fun resolveBookCoverAnchor(
    adjustment: BookCoverAdjustment,
    renderSpec: BookCoverRenderSpec
): Pair<Int, Int> {
    val maxAnchorX = (
        renderSpec.drawableWidth - renderSpec.viewWidth / renderSpec.scale
    ).coerceAtLeast(0f)
    val maxAnchorY = (
        renderSpec.drawableHeight - renderSpec.viewHeight / renderSpec.scale
    ).coerceAtLeast(0f)
    val anchorX = adjustment.anchorXPx ?: (-renderSpec.dx / renderSpec.scale)
        .roundToInt()
        .coerceIn(0, maxAnchorX.roundToInt())
    val anchorY = adjustment.anchorYPx ?: (-renderSpec.dy / renderSpec.scale)
        .roundToInt()
        .coerceIn(0, maxAnchorY.roundToInt())
    return Pair(
        anchorX.coerceIn(0, maxAnchorX.roundToInt()),
        anchorY.coerceIn(0, maxAnchorY.roundToInt())
    )
}

private fun coerceBookCoverRangeStart(
    drawableWidth: Int,
    drawableHeight: Int,
    viewWidth: Int,
    viewHeight: Int,
    startXPx: Int,
    startYPx: Int
): Pair<Int, Int> {
    val safeWidth = drawableWidth.coerceAtLeast(1)
    val safeHeight = drawableHeight.coerceAtLeast(1)
    val baseScale = maxOf(
        viewWidth.toFloat() / safeWidth.toFloat(),
        viewHeight.toFloat() / safeHeight.toFloat()
    )
    val minVisibleWidth = viewWidth / (baseScale * BookCoverPreviewMaxZoom)
    val minVisibleHeight = viewHeight / (baseScale * BookCoverPreviewMaxZoom)
    val maxStartX = (safeWidth - minVisibleWidth).coerceAtLeast(0f).roundToInt()
    val maxStartY = (safeHeight - minVisibleHeight).coerceAtLeast(0f).roundToInt()
    return startXPx.coerceIn(0, maxStartX) to startYPx.coerceIn(0, maxStartY)
}

private fun computeBookCoverAdjustmentForRange(
    drawableWidth: Int,
    drawableHeight: Int,
    viewWidth: Int,
    viewHeight: Int,
    startXPx: Int,
    startYPx: Int,
    endXPx: Int,
    endYPx: Int
): BookCoverAdjustment {
    val safeWidth = drawableWidth.coerceAtLeast(1)
    val safeHeight = drawableHeight.coerceAtLeast(1)
    val baseScale = maxOf(
        viewWidth.toFloat() / safeWidth.toFloat(),
        viewHeight.toFloat() / safeHeight.toFloat()
    )
    val start = coerceBookCoverRangeStart(
        drawableWidth = safeWidth,
        drawableHeight = safeHeight,
        viewWidth = viewWidth,
        viewHeight = viewHeight,
        startXPx = startXPx,
        startYPx = startYPx
    )
    val aspect = viewWidth.toFloat() / viewHeight.toFloat()
    val minVisibleHeight = viewHeight / (baseScale * BookCoverPreviewMaxZoom)
    val maxVisibleHeightByZoom = viewHeight / baseScale
    val maxVisibleHeightByBounds = minOf(
        (safeWidth - start.first) / aspect,
        (safeHeight - start.second).toFloat()
    ).coerceAtLeast(1f)
    val maxVisibleHeight = minOf(maxVisibleHeightByZoom, maxVisibleHeightByBounds)
    val minVisibleHeightClamped = minVisibleHeight.coerceAtMost(maxVisibleHeight)
    val dx = (endXPx - start.first).coerceAtLeast(1).toFloat()
    val dy = (endYPx - start.second).coerceAtLeast(1).toFloat()
    val projectedVisibleHeight = minOf(dx / aspect, dy)
        .coerceIn(minVisibleHeightClamped, maxVisibleHeight)
    val zoom = (viewHeight / projectedVisibleHeight / baseScale).coerceIn(1f, BookCoverPreviewMaxZoom)
    return normalizeBookCoverAdjustment(
        zoom = zoom,
        anchorXPx = start.first,
        anchorYPx = start.second
    )
}

private fun computeZoomToFitBookCoverAnchor(
    drawableWidth: Int,
    drawableHeight: Int,
    viewWidth: Int,
    viewHeight: Int,
    anchorXPx: Int,
    anchorYPx: Int
): Float {
    val safeWidth = drawableWidth.coerceAtLeast(1)
    val safeHeight = drawableHeight.coerceAtLeast(1)
    val baseScale = maxOf(
        viewWidth.toFloat() / safeWidth.toFloat(),
        viewHeight.toFloat() / safeHeight.toFloat()
    )
    val remainingWidth = (safeWidth - anchorXPx).coerceAtLeast(1)
    val remainingHeight = (safeHeight - anchorYPx).coerceAtLeast(1)
    val requiredScale = maxOf(
        viewWidth.toFloat() / remainingWidth.toFloat(),
        viewHeight.toFloat() / remainingHeight.toFloat()
    )
    return (requiredScale / baseScale).coerceIn(1f, BookCoverPreviewMaxZoom)
}

private fun buildMagnifierBookCoverAdjustment(
    drawableWidth: Int,
    drawableHeight: Int,
    viewWidth: Int,
    viewHeight: Int,
    sampleXPx: Int,
    sampleYPx: Int
): BookCoverAdjustment {
    val baseScale = maxOf(
        viewWidth.toFloat() / drawableWidth.toFloat(),
        viewHeight.toFloat() / drawableHeight.toFloat()
    )
    val finalScale = baseScale * BookCoverMagnifierZoom
    val visibleWidth = viewWidth / finalScale
    val visibleHeight = viewHeight / finalScale
    val left = (sampleXPx - visibleWidth / 2f).roundToInt()
    val top = (sampleYPx - visibleHeight / 2f).roundToInt()
    return normalizeBookCoverAdjustment(
        zoom = BookCoverMagnifierZoom,
        anchorXPx = left,
        anchorYPx = top,
        maxZoom = BookCoverMagnifierZoom,
        clampAnchors = false
    )
}

private fun resolveEmbeddedCoverUriForM4b(
    context: Context,
    audioUri: Uri,
    audioDisplayName: String
): Uri? {
    val isM4b = audioDisplayName.endsWith(".m4b", ignoreCase = true) ||
        audioUri.toString().contains(".m4b", ignoreCase = true)
    if (!isM4b) return null

    val coverDir = File(File(context.filesDir, "books"), "covers")
    if (!coverDir.exists()) {
        coverDir.mkdirs()
    }

    val cacheKey = buildDictionaryCacheKey(audioUri.toString(), audioDisplayName)
    val existing = coverDir.listFiles()
        ?.firstOrNull { it.nameWithoutExtension == "cover-$cacheKey" }
    if (existing != null && existing.exists() && existing.length() > 0L) {
        return Uri.fromFile(existing)
    }

    val retriever = MediaMetadataRetriever()
    return try {
        if (audioUri.scheme.equals("file", ignoreCase = true)) {
            val path = audioUri.path ?: return null
            retriever.setDataSource(path)
        } else {
            retriever.setDataSource(context, audioUri)
        }
        val picture = retriever.embeddedPicture
        if (picture != null) {
            val ext = if (
                picture.size >= 4 &&
                picture[0] == 0x89.toByte() &&
                picture[1] == 0x50.toByte() &&
                picture[2] == 0x4E.toByte() &&
                picture[3] == 0x47.toByte()
            ) {
                "png"
            } else {
                "jpg"
            }
            val outFile = File(coverDir, "cover-$cacheKey.$ext")
            outFile.writeBytes(picture)
            if (outFile.exists() && outFile.length() > 0L) {
                return Uri.fromFile(outFile)
            }
        }

        val attachedFrame = runCatching {
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }.getOrNull()
        saveBitmapCoverIfPresent(
            bitmap = attachedFrame,
            coverDir = coverDir,
            cacheKey = cacheKey
        )?.let { return it }

        extractAttachedPicCoverWithMediaExtractor(
            context = context,
            audioUri = audioUri,
            coverDir = coverDir,
            cacheKey = cacheKey
        )?.let { return it }

        extractCoverWithTagLib(context, audioUri, coverDir, cacheKey)
    } catch (_: Throwable) {
        extractCoverWithTagLib(context, audioUri, coverDir, cacheKey)
    } finally {
        runCatching { retriever.release() }
    }
}

private fun saveBitmapCoverIfPresent(
    bitmap: Bitmap?,
    coverDir: File,
    cacheKey: String
): Uri? {
    val target = bitmap ?: return null
    val outFile = File(coverDir, "cover-$cacheKey.jpg")
    return runCatching {
        outFile.outputStream().use { output ->
            target.compress(Bitmap.CompressFormat.JPEG, 95, output)
        }
        if (outFile.exists() && outFile.length() > 0L) {
            Uri.fromFile(outFile)
        } else {
            null
        }
    }.getOrNull()
}

private fun extractAttachedPicCoverWithMediaExtractor(
    context: Context,
    audioUri: Uri,
    coverDir: File,
    cacheKey: String
): Uri? {
    val extractor = MediaExtractor()
    return try {
        val dataSourceSet = if (audioUri.scheme.equals("file", ignoreCase = true)) {
            val path = audioUri.path ?: return null
            runCatching { extractor.setDataSource(path) }.isSuccess
        } else {
            runCatching { extractor.setDataSource(context, audioUri, null) }.isSuccess
        }
        if (!dataSourceSet) return null

        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            mime.startsWith("image/", ignoreCase = true) ||
                mime.startsWith("video/", ignoreCase = true)
        } ?: return null

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty().lowercase(Locale.ROOT)
        val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
        } else {
            2 * 1024 * 1024
        }
        val buffer = java.nio.ByteBuffer.allocateDirect(maxSize)
        val size = extractor.readSampleData(buffer, 0)
        if (size <= 0) return null

        val bytes = ByteArray(size)
        buffer.position(0)
        buffer.get(bytes, 0, size)

        val ext = when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            else -> "jpg"
        }
        val outFile = File(coverDir, "cover-$cacheKey.$ext")
        outFile.writeBytes(bytes)
        if (outFile.exists() && outFile.length() > 0L) {
            Uri.fromFile(outFile)
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { extractor.release() }
    }
}

private fun extractCoverWithTagLib(
    context: Context,
    audioUri: Uri,
    coverDir: File,
    cacheKey: String
): Uri? {
    val descriptor = if (audioUri.scheme.equals("file", ignoreCase = true)) {
        val path = audioUri.path ?: return null
        runCatching {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull()
    } else {
        runCatching {
            context.contentResolver.openFileDescriptor(audioUri, "r")
        }.getOrNull()
    } ?: return null

    return descriptor.use { pfd ->
        val detachedFd = runCatching { pfd.detachFd() }.getOrNull() ?: return@use null
        runCatching {
            val picture = TagLib.getFrontCover(detachedFd) ?: return@runCatching null
            saveTagLibCoverBytes(
                bytes = picture.data,
                mimeType = picture.mimeType,
                coverDir = coverDir,
                cacheKey = cacheKey
            )
        }.getOrNull()
    }
}

private fun saveTagLibCoverBytes(
    bytes: ByteArray,
    mimeType: String?,
    coverDir: File,
    cacheKey: String
): Uri? {
    if (bytes.isEmpty()) return null
    val normalizedMime = mimeType.orEmpty().lowercase(Locale.ROOT)
    val ext = when {
        normalizedMime.contains("png") -> "png"
        normalizedMime.contains("webp") -> "webp"
        normalizedMime.contains("bmp") -> "bmp"
        normalizedMime.contains("gif") -> "gif"
        else -> detectTagLibCoverFileExtension(bytes)
    }
    val outFile = File(coverDir, "cover-$cacheKey.$ext")
    return runCatching {
        outFile.writeBytes(bytes)
        if (outFile.exists() && outFile.length() > 0L) {
            Uri.fromFile(outFile)
        } else {
            null
        }
    }.getOrNull()
}

private fun detectTagLibCoverFileExtension(bytes: ByteArray): String {
    return when {
        bytes.size >= 4 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() -> "png"
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "jpg"
        bytes.size >= 4 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() -> "webp"
        else -> "jpg"
    }
}

private fun openBookInputStream(contentResolver: ContentResolver, uri: Uri): InputStream? {
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

private data class RelocatedBookFiles(
    val folderName: String,
    val audioUri: Uri?,
    val audioName: String?,
    val srtUri: Uri?,
    val srtName: String?,
    val ebookUri: Uri?,
    val ebookName: String?,
    val moveWarnings: List<String>
)

private data class CopiedBookFile(
    val uri: Uri,
    val displayName: String
)

private data class FolderBookCandidate(
    val folderName: String,
    val audioUri: Uri?,
    val audioName: String?,
    val srtUri: Uri?,
    val srtName: String?,
    val ebookUri: Uri?,
    val ebookName: String?,
    val ebookFormat: String?
)

private data class FolderBookScanResult(
    val books: List<FolderBookCandidate>,
    val skippedFolders: List<String>
)

private fun relocateSelectedBookFilesToAudFolder(
    context: Context,
    contentResolver: ContentResolver,
    rootFolderUri: Uri,
    audioSourceUri: Uri?,
    audioSourceName: String?,
    srtSourceUri: Uri?,
    srtSourceName: String?,
    ebookSourceUri: Uri?,
    ebookSourceName: String?,
    deleteSourceFiles: Boolean
): RelocatedBookFiles {
    val root = DocumentFile.fromTreeUri(context, rootFolderUri)
        ?: error(context.getString(R.string.error_audiobook_folder_inaccessible))
    if (!root.isDirectory) error(context.getString(R.string.error_audiobook_folder_not_directory))

    val audFolder = createNextAudFolder(context, root)
    logDebug(BOOK_IMPORT_MOVE_LOG_TAG) {
        "relocate start deleteSourceFiles=$deleteSourceFiles root=${rootFolderUri.toString().take(96)} " +
        "targetFolder=${audFolder.name} " +
        "audioName=$audioSourceName srtName=$srtSourceName ebookName=$ebookSourceName"
    }
    val audioDisplayName = audioSourceUri?.let { sourceUri ->
        audioSourceName?.trim().takeUnless { it.isNullOrBlank() }
            ?: queryDisplayName(contentResolver, sourceUri)
    }

    val copiedAudio = audioSourceUri?.let { sourceUri ->
        copyUriIntoFolder(
            context = context,
            contentResolver = contentResolver,
            parentFolder = audFolder,
            sourceUri = sourceUri,
            preferredDisplayName = audioDisplayName.orEmpty()
        )
    }
    if (audioSourceUri != null) {
        logDebug(BOOK_IMPORT_MOVE_LOG_TAG) {
            "copy audio source=${audioSourceUri.toString().take(96)} target=${copiedAudio?.uri?.toString()?.take(96)} " +
            "name=${copiedAudio?.displayName}"
        }
    }
    val copiedSrt = srtSourceUri?.let { sourceUri ->
        val srtDisplayName = srtSourceName?.trim().takeUnless { it.isNullOrBlank() }
            ?: queryDisplayName(contentResolver, sourceUri)
        copyUriIntoFolder(
            context = context,
            contentResolver = contentResolver,
            parentFolder = audFolder,
            sourceUri = sourceUri,
            preferredDisplayName = srtDisplayName
        )
    }
    if (srtSourceUri != null) {
        logDebug(BOOK_IMPORT_MOVE_LOG_TAG) {
            "copy srt source=${srtSourceUri.toString().take(96)} target=${copiedSrt?.uri?.toString()?.take(96)} " +
            "name=${copiedSrt?.displayName}"
        }
    }
    val copiedEbook = ebookSourceUri?.let { sourceUri ->
        val ebookDisplayName = ebookSourceName?.trim().takeUnless { it.isNullOrBlank() }
            ?: queryDisplayName(contentResolver, sourceUri)
        copyUriIntoFolder(
            context = context,
            contentResolver = contentResolver,
            parentFolder = audFolder,
            sourceUri = sourceUri,
            preferredDisplayName = ebookDisplayName
        )
    }
    if (ebookSourceUri != null) {
        logDebug(BOOK_IMPORT_MOVE_LOG_TAG) {
            "copy ebook source=${ebookSourceUri.toString().take(96)} target=${copiedEbook?.uri?.toString()?.take(96)} " +
            "name=${copiedEbook?.displayName}"
        }
    }

    val warnings = mutableListOf<String>()
    if (deleteSourceFiles) {
        audioSourceUri?.let { uri ->
            val deleted = deleteSourceUri(context, contentResolver, uri)
            logDebug(BOOK_IMPORT_MOVE_LOG_TAG) { "delete audio source=${uri.toString().take(96)} success=$deleted" }
            if (!deleted) {
                warnings += context.getString(R.string.error_audio_delete_failed)
            }
        }
        srtSourceUri?.let { uri ->
            val deleted = deleteSourceUri(context, contentResolver, uri)
            logDebug(BOOK_IMPORT_MOVE_LOG_TAG) { "delete srt source=${uri.toString().take(96)} success=$deleted" }
            if (!deleted) {
                warnings += context.getString(R.string.error_srt_delete_failed)
            }
        }
        ebookSourceUri?.let { uri ->
            val deleted = deleteSourceUri(context, contentResolver, uri)
            logDebug(BOOK_IMPORT_MOVE_LOG_TAG) { "delete ebook source=${uri.toString().take(96)} success=$deleted" }
            if (!deleted) {
                warnings += context.getString(R.string.error_ebook_delete_failed)
            }
        }
    } else {
        logDebug(BOOK_IMPORT_MOVE_LOG_TAG) {
            "keep original sources audio=${audioSourceUri?.toString()?.take(96)} " +
            "srt=${srtSourceUri?.toString()?.take(96)} ebook=${ebookSourceUri?.toString()?.take(96)}"
        }
    }

    return RelocatedBookFiles(
        folderName = audFolder.name?.ifBlank { "Aud" } ?: "Aud",
        audioUri = copiedAudio?.uri,
        audioName = copiedAudio?.displayName,
        srtUri = copiedSrt?.uri,
        srtName = copiedSrt?.displayName,
        ebookUri = copiedEbook?.uri,
        ebookName = copiedEbook?.displayName,
        moveWarnings = warnings
    )
}

private fun createNextAudFolder(context: Context, rootFolder: DocumentFile): DocumentFile {
    val pattern = Regex("^Aud(\\d+)$", RegexOption.IGNORE_CASE)
    var next = rootFolder.listFiles()
        .filter { it.isDirectory }
        .mapNotNull { dir ->
            dir.name
                ?.trim()
                ?.let { pattern.matchEntire(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        }
        .maxOrNull()
        ?.plus(1)
        ?: 1

    repeat(1000) {
        val candidate = "Aud$next"
        if (rootFolder.findFile(candidate) == null) {
            return rootFolder.createDirectory(candidate) ?: error(context.getString(R.string.error_create_folder_failed, candidate))
        }
        next += 1
    }
    error(context.getString(R.string.error_create_aud_folder_failed))
}

private fun ensureChildDirectory(context: Context, parentFolder: DocumentFile, name: String): DocumentFile {
    parentFolder.findFile(name)?.let { existing ->
        if (existing.isDirectory) return existing
        error(context.getString(R.string.error_create_folder_failed, name))
    }
    return parentFolder.createDirectory(name) ?: error(context.getString(R.string.error_create_folder_failed, name))
}

private fun createUniqueChildDirectory(context: Context, parentFolder: DocumentFile, preferredName: String): DocumentFile {
    val uniqueName = resolveUniqueDirectoryName(parentFolder, preferredName)
    return parentFolder.createDirectory(uniqueName)
        ?: error(context.getString(R.string.error_create_folder_failed, uniqueName))
}

private fun resolveUniqueDirectoryName(folder: DocumentFile, originalName: String): String {
    val cleaned = originalName.trim().ifBlank { "folder" }
    if (folder.findFile(cleaned) == null) return cleaned

    var index = 2
    while (index <= 9999) {
        val candidate = "$cleaned ($index)"
        if (folder.findFile(candidate) == null) return candidate
        index += 1
    }
    return "$cleaned-${System.currentTimeMillis()}"
}

private fun copyUriIntoFolder(
    context: Context,
    contentResolver: ContentResolver,
    parentFolder: DocumentFile,
    sourceUri: Uri,
    preferredDisplayName: String
): CopiedBookFile {
    val normalizedName = preferredDisplayName.trim().ifBlank { "file" }
    val uniqueName = resolveUniqueDocumentName(parentFolder, normalizedName)
    val sourceMime = contentResolver.getType(sourceUri)
    val targetMime = resolveMimeTypeForDocument(uniqueName, sourceMime)
    val created = parentFolder.createFile(targetMime, uniqueName)
        ?: error(context.getString(R.string.error_create_target_file_failed, uniqueName))

    val input = openBookInputStream(contentResolver, sourceUri)
        ?: error(context.getString(R.string.error_read_source_file_failed, normalizedName))
    input.use { src ->
        contentResolver.openOutputStream(created.uri, "w")?.use { output ->
            src.copyTo(output)
        } ?: error(context.getString(R.string.error_write_target_file_failed, uniqueName))
    }

    return CopiedBookFile(
        uri = created.uri,
        displayName = uniqueName
    )
}

private fun resolveUniqueDocumentName(folder: DocumentFile, originalName: String): String {
    val cleaned = originalName.trim().ifBlank { "file" }
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

private fun scanBooksFromRootFolder(
    context: Context,
    contentResolver: ContentResolver,
    rootFolderUri: Uri,
    includeEbookOnly: Boolean = false
): FolderBookScanResult {
    val root = DocumentFile.fromTreeUri(context, rootFolderUri)
        ?: error(context.getString(R.string.error_audiobook_folder_inaccessible))
    if (!root.isDirectory) error(context.getString(R.string.error_audiobook_folder_not_directory))

    val books = mutableListOf<FolderBookCandidate>()
    val skippedFolders = mutableListOf<String>()

    root.listFiles()
        .filter { it.isDirectory }
        .filterNot { folder -> folder.name?.trim().orEmpty().equals(BOOK_SOURCES_FOLDER_NAME, ignoreCase = true) }
        .sortedBy { it.name?.lowercase(Locale.ROOT) ?: it.uri.toString() }
        .forEach { folder ->
            val folderName = folder.name?.trim().takeUnless { it.isNullOrBlank() }
                ?: "Untitled"
            val files = folder.listFiles()
                .filter { it.isFile }
                .sortedBy { it.name?.lowercase(Locale.ROOT) ?: it.uri.toString() }

            val audioFile = files.firstOrNull { isAudioDocumentFile(it) }
            val srtFile = files.firstOrNull { isSrtDocumentFile(it) }
            val ebookFile = files.firstOrNull { isEbookDocumentFile(it) }
            if (audioFile == null && !(includeEbookOnly && ebookFile != null)) {
                skippedFolders += folderName
                return@forEach
            }

            val audioName = audioFile?.name?.trim()?.takeUnless { it.isBlank() }
                ?: audioFile?.let { queryDisplayName(contentResolver, it.uri) }
            val srtName = srtFile?.name?.trim()?.takeUnless { it.isNullOrBlank() }
                ?: srtFile?.let { queryDisplayName(contentResolver, it.uri) }
            val ebookName = ebookFile?.name?.trim()?.takeUnless { it.isNullOrBlank() }
                ?: ebookFile?.let { queryDisplayName(contentResolver, it.uri) }
            val ebookFormat = ebookName?.let { inferLocalReaderBookFormat(it, ebookFile?.type) }

            books += FolderBookCandidate(
                folderName = folderName,
                audioUri = audioFile?.uri,
                audioName = audioName,
                srtUri = srtFile?.uri,
                srtName = srtName,
                ebookUri = ebookFile?.uri,
                ebookName = ebookName,
                ebookFormat = ebookFormat
            )
        }

    return FolderBookScanResult(
        books = books,
        skippedFolders = skippedFolders
    )
}

private fun isAudioDocumentFile(file: DocumentFile): Boolean {
    val name = file.name?.trim().orEmpty()
    val mime = file.type?.lowercase(Locale.ROOT).orEmpty()
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        .trim()
        .lowercase(Locale.ROOT)
    if (extension in setOf(
            "m4b", "m4a", "mp3", "aac", "flac", "wav", "ogg", "opus",
            "mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "ts"
        )
    ) {
        return true
    }
    return mime.startsWith("audio/") || mime.startsWith("video/") || mime == "application/mp4"
}

private fun isSrtDocumentFile(file: DocumentFile): Boolean {
    val name = file.name?.trim().orEmpty().lowercase(Locale.ROOT)
    val mime = file.type?.lowercase(Locale.ROOT).orEmpty()
    if (name.endsWith(".srt")) return true
    return mime == "application/x-subrip" || mime.contains("subrip")
}

private fun isEbookDocumentFile(file: DocumentFile): Boolean {
    val displayName = file.name?.trim().orEmpty()
    if (displayName.endsWith(".srt", ignoreCase = true)) return false
    return inferLocalReaderBookFormat(displayName, file.type) != null
}

private fun resolveMimeTypeForDocument(fileName: String, sourceMime: String?): String {
    if (!sourceMime.isNullOrBlank() && sourceMime != "application/octet-stream") {
        return sourceMime
    }
    val ext = fileName.substringAfterLast('.', missingDelimiterValue = "")
        .trim()
        .lowercase(Locale.ROOT)
    return when (ext) {
        "m4b", "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "3gp" -> "video/3gpp"
        "ts" -> "video/mp2t"
        "srt" -> "application/x-subrip"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}

private fun deleteSourceUri(
    context: Context,
    contentResolver: ContentResolver,
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

    return runCatching {
        contentResolver.delete(uri, null, null) > 0
    }.getOrDefault(false)
}

private data class DeleteBookStorageResult(
    val folderDeleteAttempted: Boolean,
    val folderDeleteSucceeded: Boolean,
    val fileDeleteFailures: Int
)

private data class ArchiveBookStorageResult(
    val fileCopyFailures: Int,
    val fileDeleteFailures: Int
)

private data class BookArchiveFile(
    val kind: String,
    val uri: Uri,
    val displayName: String?
)

private fun archiveBookStorage(
    context: Context,
    contentResolver: ContentResolver,
    book: ReaderBook,
    audiobookFolderUri: Uri?
): ArchiveBookStorageResult {
    val rootUri = audiobookFolderUri ?: run {
        logDebug(BOOK_DELETE_LOG_TAG) { "archiveStorage skipped reason=no-root title=${book.title.take(48)}" }
        return ArchiveBookStorageResult(fileCopyFailures = 1, fileDeleteFailures = 0)
    }
    val root = DocumentFile.fromTreeUri(context, rootUri) ?: run {
        logDebug(BOOK_DELETE_LOG_TAG) { "archiveStorage skipped reason=root-inaccessible root=${rootUri.toString().take(96)}" }
        return ArchiveBookStorageResult(fileCopyFailures = 1, fileDeleteFailures = 0)
    }
    val sourcesRoot = runCatching {
        ensureChildDirectory(context, root, BOOK_SOURCES_FOLDER_NAME)
    }.getOrElse { error ->
        logDebug(BOOK_DELETE_LOG_TAG) { "archiveStorage skipped reason=sources-create-failed error=${error.message}" }
        return ArchiveBookStorageResult(fileCopyFailures = 1, fileDeleteFailures = 0)
    }
    val primaryFileUri = book.audioUri ?: book.ebookUri
    val archiveFolderName = importedBookFolderNameFromUri(context, primaryFileUri)
        ?: book.title.trim().ifBlank { book.audioName ?: book.ebookName ?: "Book" }
    val archiveFolder = runCatching {
        createUniqueChildDirectory(context, sourcesRoot, archiveFolderName)
    }.getOrElse { error ->
        logDebug(BOOK_DELETE_LOG_TAG) { "archiveStorage skipped reason=archive-folder-create-failed error=${error.message}" }
        return ArchiveBookStorageResult(fileCopyFailures = 1, fileDeleteFailures = 0)
    }
    val files = buildList {
        book.audioUri?.let { add(BookArchiveFile("audio", it, book.audioName)) }
        book.srtUri?.let { add(BookArchiveFile("srt", it, book.srtName)) }
        book.ebookUri?.let { add(BookArchiveFile("ebook", it, book.ebookName)) }
    }.distinctBy { it.uri.toString() }
    logDebug(BOOK_DELETE_LOG_TAG) {
        "archiveStorage start title=${book.title.take(48)} target=$BOOK_SOURCES_FOLDER_NAME/${archiveFolder.name} " +
        "files=${files.joinToString(separator = ",") { it.kind }}"
    }

    val copiedFiles = mutableListOf<BookArchiveFile>()
    var copyFailures = 0
    files.forEach { file ->
        val displayName = file.displayName?.trim().takeUnless { it.isNullOrBlank() }
            ?: queryDisplayName(contentResolver, file.uri)
        val copied = runCatching {
            copyUriIntoFolder(
                context = context,
                contentResolver = contentResolver,
                parentFolder = archiveFolder,
                sourceUri = file.uri,
                preferredDisplayName = displayName
            )
        }.onSuccess { copiedFile ->
            copiedFiles += file
            logDebug(BOOK_DELETE_LOG_TAG) {
                "archiveStorage copy ${file.kind} source=${file.uri.toString().take(96)} " +
                "target=${copiedFile.uri.toString().take(96)} name=${copiedFile.displayName}"
            }
        }.onFailure { error ->
            copyFailures += 1
            logDebug(BOOK_DELETE_LOG_TAG) {
                "archiveStorage copyFailed ${file.kind} source=${file.uri.toString().take(96)} error=${error.message}"
            }
        }
        copied.getOrNull()
    }
    if (copyFailures > 0) {
        return ArchiveBookStorageResult(fileCopyFailures = copyFailures, fileDeleteFailures = 0)
    }

    val isInsideAudiobookFolder = isUriInsideAudiobookFolder(
        context = context,
        fileUri = primaryFileUri,
        audiobookFolderUri = rootUri
    )
    if (isInsideAudiobookFolder && primaryFileUri != null) {
        val folderDeleted = deleteAudParentFolder(context, primaryFileUri)
        logDebug(BOOK_DELETE_LOG_TAG) {
            "archiveStorage deleteOriginalFolder primary=${primaryFileUri.toString().take(96)} success=$folderDeleted"
        }
        if (folderDeleted) {
            return ArchiveBookStorageResult(fileCopyFailures = 0, fileDeleteFailures = 0)
        }
    }

    var deleteFailures = 0
    copiedFiles.forEach { file ->
        val deleted = deleteSourceUri(context, contentResolver, file.uri)
        logDebug(BOOK_DELETE_LOG_TAG) {
            "archiveStorage deleteOriginalFile ${file.kind} source=${file.uri.toString().take(96)} success=$deleted"
        }
        if (!deleted) deleteFailures += 1
    }
    return ArchiveBookStorageResult(fileCopyFailures = 0, fileDeleteFailures = deleteFailures)
}

private fun deleteBookStorage(
    context: Context,
    contentResolver: ContentResolver,
    book: ReaderBook,
    audiobookFolderUri: Uri?
): DeleteBookStorageResult {
    val primaryFileUri = book.audioUri ?: book.ebookUri
    val isInsideAudiobookFolder = isUriInsideAudiobookFolder(
        context = context,
        fileUri = primaryFileUri,
        audiobookFolderUri = audiobookFolderUri
    )
    logDebug(BOOK_DELETE_LOG_TAG) {
        "deleteStorage title=${book.title.take(48)} insideRoot=$isInsideAudiobookFolder " +
        "primary=${primaryFileUri?.toString()?.take(96)} root=${audiobookFolderUri?.toString()?.take(96)} " +
        "audio=${book.audioUri?.toString()?.take(96)} srt=${book.srtUri?.toString()?.take(96)} " +
        "ebook=${book.ebookUri?.toString()?.take(96)}"
    }

    if (isInsideAudiobookFolder && primaryFileUri != null) {
        val folderDeleted = deleteAudParentFolder(context, primaryFileUri)
        logDebug(BOOK_DELETE_LOG_TAG) {
            "deleteStorage parentFolder primary=${primaryFileUri.toString().take(96)} success=$folderDeleted"
        }
        if (folderDeleted) {
            return DeleteBookStorageResult(
                folderDeleteAttempted = true,
                folderDeleteSucceeded = true,
                fileDeleteFailures = 0
            )
        }
    }

    var fileDeleteFailures = 0
    val audio = book.audioUri
    if (audio != null) {
        val deleted = deleteSourceUri(context, contentResolver, audio)
        logDebug(BOOK_DELETE_LOG_TAG) { "deleteStorage file audio=${audio.toString().take(96)} success=$deleted" }
        if (!deleted) fileDeleteFailures += 1
    }
    val srt = book.srtUri
    if (srt != null) {
        val deleted = deleteSourceUri(context, contentResolver, srt)
        logDebug(BOOK_DELETE_LOG_TAG) { "deleteStorage file srt=${srt.toString().take(96)} success=$deleted" }
        if (!deleted) fileDeleteFailures += 1
    }
    val ebook = book.ebookUri
    if (ebook != null && ebook != audio) {
        val deleted = deleteSourceUri(context, contentResolver, ebook)
        logDebug(BOOK_DELETE_LOG_TAG) { "deleteStorage file ebook=${ebook.toString().take(96)} success=$deleted" }
        if (!deleted) fileDeleteFailures += 1
    }

    return DeleteBookStorageResult(
        folderDeleteAttempted = isInsideAudiobookFolder,
        folderDeleteSucceeded = false,
        fileDeleteFailures = fileDeleteFailures
    )
}

private fun isUriInsideAudiobookFolder(
    context: Context,
    fileUri: Uri?,
    audiobookFolderUri: Uri?
): Boolean {
    fileUri ?: return false
    val rootUri = audiobookFolderUri ?: return false
    if (fileUri.scheme.equals("file", ignoreCase = true) && rootUri.scheme.equals("file", ignoreCase = true)) {
        val filePath = runCatching { File(fileUri.path ?: return false).canonicalPath }.getOrNull() ?: return false
        val rootPath = runCatching { File(rootUri.path ?: return false).canonicalPath }.getOrNull() ?: return false
        if (filePath == rootPath) return true
        return filePath.startsWith("$rootPath${File.separator}")
    }

    if (!fileUri.scheme.equals("content", ignoreCase = true) || !rootUri.scheme.equals("content", ignoreCase = true)) {
        return false
    }

    val rootDocumentId = runCatching { DocumentsContract.getTreeDocumentId(rootUri) }
        .getOrElse {
            if (DocumentsContract.isDocumentUri(context, rootUri)) {
                runCatching { DocumentsContract.getDocumentId(rootUri) }.getOrNull()
            } else {
                null
            }
        } ?: return false

    val fileDocumentId = if (DocumentsContract.isDocumentUri(context, fileUri)) {
        runCatching { DocumentsContract.getDocumentId(fileUri) }.getOrNull()
    } else {
        runCatching { DocumentsContract.getTreeDocumentId(fileUri) }.getOrNull()
    } ?: return false

    return fileDocumentId == rootDocumentId || fileDocumentId.startsWith("$rootDocumentId/")
}

private fun deleteAudParentFolder(context: Context, fileUri: Uri): Boolean {
    if (fileUri.scheme.equals("file", ignoreCase = true)) {
        val file = fileUri.path?.let { File(it) } ?: return false
        val parent = file.parentFile ?: return false
        if (!isImportedBookFolderName(parent.name)) return false
        return runCatching { parent.deleteRecursively() }.getOrDefault(false)
    }

    if (!DocumentsContract.isDocumentUri(context, fileUri)) return false
    val documentId = runCatching { DocumentsContract.getDocumentId(fileUri) }.getOrNull() ?: return false
    val parentDocumentId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
    if (parentDocumentId.isBlank()) return false
    val parentName = parentDocumentId.substringAfterLast('/')
    if (!isImportedBookFolderName(parentName)) return false

    val parentUri = runCatching {
        DocumentsContract.buildDocumentUriUsingTree(fileUri, parentDocumentId)
    }.getOrNull() ?: return false

    val parentDocument = DocumentFile.fromSingleUri(context, parentUri) ?: return false
    return runCatching { parentDocument.delete() }.getOrDefault(false)
}

private fun isImportedBookFolderName(name: String): Boolean {
    return Regex("^Aud\\d+$", RegexOption.IGNORE_CASE).matches(name) ||
        Regex("^aud\\d+$", RegexOption.IGNORE_CASE).matches(name)
}

private fun importedBookFolderNameFromUri(context: Context, fileUri: Uri?): String? {
    fileUri ?: return null
    if (fileUri.scheme.equals("file", ignoreCase = true)) {
        val parentName = fileUri.path?.let { File(it).parentFile?.name } ?: return null
        return parentName.takeIf { isImportedBookFolderName(it) }
    }
    if (!DocumentsContract.isDocumentUri(context, fileUri)) return null
    val documentId = runCatching { DocumentsContract.getDocumentId(fileUri) }.getOrNull() ?: return null
    val parentDocumentId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
    if (parentDocumentId.isBlank()) return null
    val parentName = parentDocumentId.substringAfterLast('/')
    return parentName.takeIf { isImportedBookFolderName(it) }
}

private fun findCueAtTime(cues: List<SubtitleCue>, timeMs: Long): SubtitleCue? {
    if (cues.isEmpty()) return null
    var low = 0
    var high = cues.lastIndex
    var candidateIndex = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val cue = cues[mid]
        if (cue.startMs <= timeMs) {
            candidateIndex = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    if (candidateIndex < 0) return null
    return cues[candidateIndex]
}

internal fun buildMainHighlightedText(text: String, selectedRange: IntRange?): AnnotatedString {
    return buildAnnotatedString {
        append(text)
        val range = selectedRange ?: return@buildAnnotatedString
        val start = range.first.coerceIn(0, text.length)
        val endExclusive = (range.last + 1).coerceIn(start, text.length)
        if (endExclusive <= start) return@buildAnnotatedString
        addStyle(SpanStyle(background = Color(0x1FA0A0A0)), start, endExclusive)
    }
}

@Composable
internal fun MainLookupClickableSentence(
    text: AnnotatedString,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    onTextTap: (offset: Int) -> Unit,
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        style = style,
        modifier = modifier.pointerInput(text, onTextTap) {
            detectTapGestures { tapOffset ->
                val layout = textLayoutResult ?: return@detectTapGestures
                val textLength = text.text.length
                if (textLength <= 0) return@detectTapGestures
                val offset = layout.getOffsetForPosition(tapOffset).coerceIn(0, textLength - 1)
                onTextTap(offset)
            }
        },
        onTextLayout = {
            textLayoutResult = it
            onTextLayout(it)
        }
    )
}

private fun consumeReturnedBookProgress(intent: Intent?): ReturnedBookProgress? {
    val sourceIntent = intent ?: return null
    val audioUri = sourceIntent
        .getStringExtra(BookReaderActivity.EXTRA_RETURN_AUDIO_URI)
        ?.trim()
        .orEmpty()
    val srtUri = sourceIntent
        .getStringExtra(BookReaderActivity.EXTRA_RETURN_SRT_URI)
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    val positionMs = sourceIntent.getLongExtra(BookReaderActivity.EXTRA_RETURN_POSITION_MS, -1L)
    val durationMs = sourceIntent.getLongExtra(BookReaderActivity.EXTRA_RETURN_DURATION_MS, -1L)
    sourceIntent.removeExtra(BookReaderActivity.EXTRA_RETURN_AUDIO_URI)
    sourceIntent.removeExtra(BookReaderActivity.EXTRA_RETURN_SRT_URI)
    sourceIntent.removeExtra(BookReaderActivity.EXTRA_RETURN_POSITION_MS)
    sourceIntent.removeExtra(BookReaderActivity.EXTRA_RETURN_DURATION_MS)
    if (audioUri.isBlank()) return null
    return ReturnedBookProgress(
        audioUri = audioUri,
        srtUri = srtUri,
        positionMs = positionMs,
        durationMs = durationMs
    )
}

private fun formatCollectedCueMeta(context: Context, item: BookReaderCollectedCue): String {
    val chapterLabel = item.chapterTitle?.takeIf { it.isNotBlank() }
        ?: item.chapterIndex?.let { context.getString(R.string.chapter_label_number, it + 1) }
    val startLabel = if (item.chapterStartOffsetMs != null) {
        formatPlaybackTime(item.chapterStartOffsetMs)
    } else {
        formatPlaybackTime(item.startMs)
    }
    val endLabel = if (item.chapterEndOffsetMs != null) {
        formatPlaybackTime(item.chapterEndOffsetMs)
    } else {
        formatPlaybackTime(item.endMs)
    }
    return buildString {
        append(item.bookTitle)
        if (!chapterLabel.isNullOrBlank()) {
            append(" | ")
            append(chapterLabel)
        }
        append(" | ")
        append(startLabel)
        append(" - ")
        append(endLabel)
    }
}
