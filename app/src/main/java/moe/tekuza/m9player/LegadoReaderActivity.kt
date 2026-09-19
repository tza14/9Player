package moe.tekuza.m9player

import android.app.DatePickerDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import moe.tekuza.m9player.legado.reader.M9PageAnim
import moe.tekuza.m9player.legado.reader.M9ReadBookConfig
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.M9TextWeight
import moe.tekuza.m9player.legado.reader.SearchMenu
import moe.tekuza.m9player.legado.reader.config.MoreConfigDialog
import moe.tekuza.m9player.legado.reader.config.MoreConfigState
import moe.tekuza.m9player.legado.reader.config.ReadStyleColorItem
import moe.tekuza.m9player.legado.reader.config.ReadStyleDialog
import moe.tekuza.m9player.legado.reader.config.ReadStyleState
import moe.tekuza.m9player.legado.reader.entities.TextColumn
import moe.tekuza.m9player.legado.reader.entities.TextPage
import moe.tekuza.m9player.legado.reader.page.ReaderBatteryTextView
import moe.tekuza.m9player.legado.reader.page.ReaderTipFormatter
import moe.tekuza.m9player.legado.reader.page.ReadView
import moe.tekuza.m9player.legado.reader.provider.TextPageFactory

private const val LEGADO_READER_DEFAULT_TITLE = "吾輩は猫である"
private const val LEGADO_READER_LOG_TAG = "LegadoReader"
private const val M9_SENTENCE_TAIL_LOG_TAG = "M9SentenceTail"
private const val LEGADO_AUDIO_PROGRESS_LOG_TAG = "LegadoAudioProgress"
private const val LEGADO_MATCH_LOG_TAG = "LegadoMatch"
private const val FLOATING_OVERLAY_EXIT_LOG_TAG = "FloatingOverlayExit"
private const val READER_PAUSED_SEEK_LOG_TAG = "ReaderPausedSeek"
private const val AUDIO_CUE_LOOP_CLIP_LOG_TAG = "LegadoRepeatClip"
private const val NIGHT_BOTTOM_BG = 0xFF3A3A3A.toInt()
private const val NIGHT_BRIGHTNESS_BG = 0x80303030.toInt()
private const val NIGHT_ACCENT = 0xFFE36A3C.toInt()
private val LEGADO_READER_DEFAULT_PARAGRAPHS = listOf(
    "吾輩は猫である。名前はまだ無い。",
    "どこで生れたかとんと見當がつかぬ。何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。",
    "吾輩はここで始めて人間というものを見た。しかもあとで聞くとそれは書生という人間中で一番獰悪な種族であったそうだ。",
    "この書生というのは時々我々を捕えて煮て食うという話である。しかしその當時は何という考もなかったから別段恐しいとも思わなかった。"
)
private const val DEFAULT_IMAGE_PAUSE_SECONDS = 0
private const val MAX_IMAGE_PAUSE_SECONDS = 300
/** 跨章后补判图片停点的窗口：只有刚进入触发句这么长时间内才补暂停 */
private const val IMAGE_STOP_CATCH_UP_WINDOW_MS = 2_500L

/** SRT 里标注章节题图位置的标记前缀 */
private const val SECTION_MARKER_PREFIX = "＊"

/** 比较章节标记与章节标题时忽略的装饰字符（破折号等） */
private val SECTION_TITLE_DECORATIONS: Set<Char> = setOf(
    '─', '━', '―', '─', '—', '–', '‐', '-', '＊', '*', '　', ' '
)
private const val DEFAULT_AUDIO_CUE_REPEAT_FIXED_PAUSE_SECONDS = 2
private const val MAX_AUDIO_CUE_REPEAT_FIXED_PAUSE_SECONDS = 30
private const val MAX_AUDIO_CUE_REPEAT_COUNT = 20
private const val AUDIO_CUE_LOOP_RESUME_STALL_CHECK_DELAY_MS = 400L
private const val AUDIO_CUE_LOOP_RESUME_STALL_CHECK_WINDOW_MS = 500L

/** 「sync skip」这类每 tick 都会走到的日志最多每 5 秒输出一条（见 syncToAudioPositionAt）。 */
private const val SYNC_SKIP_LOG_INTERVAL_MS = 5_000L

/**
 * 首屏分页前等 SRT + 存档匹配的上限。句边界（cue 匹配）是分页的输入，等到了这次分页就一次到位，
 * 不用"先按段落排一遍、再为句边界重排一遍"。等不到（首次解析 SRT / 正在重新匹配）就先按段落排，
 * 匹配到齐后由 relayoutIfPagesLackSentenceBoundaries 补一次重排。
 */
private const val SENTENCE_BOUNDARY_WAIT_MS = 400L

/** 目录里每深一层的缩进像素，以及最大缩进层数（照 Hoshi-Reader 的 indentLevel×18dp，加上限防标题被挤出屏幕）。 */
private const val CATALOG_INDENT_STEP_DP = 18
private const val CATALOG_MAX_INDENT_LEVEL = 4

private enum class AudioCueLoopPauseAction {
    SWITCH_CUE,
    END_LOOP
}

private data class ReaderPageAnchor(
    val chapterIndex: Int,
    val charPosition: Int
)

private data class ReaderChapterPageCacheKey(
    val chapterIndex: Int,
    val contentWidthPx: Int,
    val contentHeightPx: Int,
    val firstPageReservePx: Int = 0
)

private data class ReaderSearchHit(
    val chapterIndex: Int,
    val chapterTitle: String,
    val preview: String,
    val chapterPosition: Int,
    val query: String
)

private enum class CatalogMode {
    CHAPTERS,
    BOOKMARKS
}
private enum class ReaderOverflowAction(val menuId: Int) {
    PLAYER(1),
    ADD_BOOKMARK(2),
    SIMULATED_READING(3),
    REMOVE_RUBY(4),
    SWITCH_LAYOUT(5),
    CHARSET(6),
    HELP(7),
    IMAGES(8)
}

private enum class ImageGallerySystemBarMode {
    LIST,
    FOCUS
}

private data class ReaderImageGalleryItem(
    val image: EbookImageRef,
    val chapterIndex: Int,
    val chapterPosition: Int
) {
    val key: String get() = "$chapterIndex:$chapterPosition:${image.cacheIdentity()}"
}

private data class ReaderImageStopTarget(
    val chapterIndex: Int,
    val imagePosition: Int
) {
    val key: String get() = "$chapterIndex:$imagePosition"
}

private data class ReaderChapterImageStop(
    val target: ReaderImageStopTarget,
    val pageIndex: Int,
    val triggerCueIndex: Int? = null,
    val tailCueIndex: Int? = null,
    val tailCueEndMs: Long? = null
)

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class LegadoReaderActivity : AppCompatActivity(), ColorPickerDialogListener {
    private lateinit var readMenu: View
    private lateinit var statusBarScrim: View
    private lateinit var navigationBarScrim: View
    private lateinit var moreSettingsPanel: MoreConfigDialog
    private lateinit var audioControlPanel: View
    private lateinit var playbackBar: View
    private lateinit var searchMenu: SearchMenu
    private lateinit var catalogPanel: View
    private lateinit var catalogListView: ListView
    private lateinit var catalogTitleView: TextView
    private lateinit var catalogSearchInputView: EditText
    private lateinit var catalogTabChaptersView: TextView
    private lateinit var catalogTabBookmarksView: TextView
    private lateinit var searchPanel: View
    private lateinit var searchInputView: EditText
    private lateinit var searchResultInfoView: TextView
    private lateinit var searchResultListView: ListView
    private lateinit var readView: ReadView
    private lateinit var readerRoot: View
    private lateinit var contentContainer: FrameLayout
    private lateinit var toolbarTitleText: TextView
    private lateinit var toolbarAdditionContainer: View
    private lateinit var toolbarAdditionLeftText: TextView
    private lateinit var toolbarAdditionMiddleText: TextView
    private lateinit var toolbarAdditionRightText: TextView
    private lateinit var chapterSeekBar: SeekBar
    private lateinit var listenActionText: TextView
    private lateinit var audioPlayPauseText: TextView
    private lateinit var playbackBarToggleButton: ImageButton
    private lateinit var playbackBarRepeatButton: ImageButton
    private lateinit var audioTimerSeekBar: SeekBar
    private lateinit var audioTimerValueText: TextView
    private lateinit var chapterControlRow: LinearLayout
    private lateinit var brightnessPanel: View
    private lateinit var brightnessSeekBar: SeekBar
    private lateinit var brightnessAutoButton: ImageView
    private lateinit var brightnessPositionButton: ImageView
    private lateinit var floatingSearchButton: ImageButton
    private lateinit var floatingReplaceButton: ImageButton
    private lateinit var floatingPlaybackBarButton: ImageButton
    private lateinit var floatingNightButton: ImageButton
    private var tipConfigDialog: AlertDialog? = null
    private var importedBook: LocalReaderBook? = null
    private var audioUri: Uri? = null
    private var srtUri: Uri? = null
    private var document: EbookDocument? = null
    private var pages: List<TextPage> = emptyList()
    private var pageIndex: Int = 0
    private var cues: List<EbookSrtCue> = emptyList()
    /** 正在进行的 SRT 加载：既是"在加载吗"的唯一状态，也让"已经在加载"的调用方等到真实结果（见 loadSrtSyncIfNeeded）。 */
    private var srtLoadInFlight: CompletableDeferred<Boolean>? = null
    private var srtLoadError: String? = null
    private var loadedSrtUriText: String? = null
    private var cueMatchesByCueIndex: Map<Int, EbookCueMatch> = emptyMap()
    private var matchData: EbookMatchData? = null
    private var matchSearchWindow: Int = DEFAULT_MATCH_SEARCH_WINDOW
    private var audioCueIndex: Int = -1
    private var activeCueIndex: Int = -1
    private var audioSeekSyncTargetMs: Long? = null
    private var audioSeekSyncDisplayMs: Long? = null
    private var audioSeekSyncTargetUntilElapsedMs: Long = 0L
    private var audioSeekSyncGeneration: Long = 0L
    private var pendingAudioSyncLoadAnchor: ReaderPageAnchor? = null
    private var textSelectionActive: Boolean = false
    private var player: ExoPlayer? = null
    private var audioPlayerLoopListener: Player.Listener? = null
    private var returnToPlayerOnBack: Boolean = false
    private var simulatedReadingConfig: SimulatedReadingConfig = SimulatedReadingConfig()
    private var simulatedUnlockedChapterCount: Int = 0
    private var syncJob: Job? = null
    private var reloadBookJob: Job? = null
    private var paginationJob: Job? = null
    private var chapterPreloadJob: Job? = null
    private val chapterPageCache: MutableMap<ReaderChapterPageCacheKey, List<TextPage>> = linkedMapOf()
    private var pendingAudioRestorePositionMs: Long = 0L
    private var pendingAudioRestoreDurationMs: Long = 0L
    /** 最近一次已知的整轨时长：裁剪窗口（逐句重复/页尾整页裁剪）期间 player.duration 只是窗口长度 */
    private var lastKnownFullAudioDurationMs: Long = -1L
    private var pendingPlayerOpenAudioReveal: Boolean = false
    private var lastSavedPlaybackPositionMs: Long = Long.MIN_VALUE
    private val sharedPlaybackStateListener = object : BookReaderFloatingBridge.PlaybackStateListener {
        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            runOnUiThread {
                updateAudioControlLabels()
            }
        }
    }
    private val sharedPlaybackPositionListener = object : BookReaderFloatingBridge.PlaybackPositionListener {
        override fun onPlaybackPositionChanged(positionMs: Long) {
            runOnUiThread {
                if (audioUri != null && isAudioPlaying()) {
                    syncToAudioPosition()
                }
            }
        }
    }
    private var preferredCharsetName: String? = null
    private var searchQuery: String? = null
    private var searchHits: List<ReaderSearchHit> = emptyList()
    private var searchHitIndex: Int = -1
    private var catalogMode: CatalogMode = CatalogMode.CHAPTERS
    private var catalogFilterQuery: String = ""
    private var bookmarks: MutableList<ReaderBookmark> = mutableListOf()
    private var readerTextSizeSp: Int = 20
    private var readerLineSpacingDp: Int = 8
    private var readerParagraphSpacingDp: Int = 14
    private var readerLetterSpacingDp: Int = 0
    private var readerTextWeight: M9TextWeight = M9TextWeight.NORMAL
    private var readerTypefaceIndex: Int = 0
    private var readerTypeface: Typeface? = Typeface.DEFAULT
    private var readerParagraphIndentCount: Int = 0
    private var readerPaddingDp: Int = 22
    private var readerPaddingTopDp: Int = 34
    private var readerPaddingBottomDp: Int = 22
    private var readerPaddingLeftDp: Int = 22
    private var readerPaddingRightDp: Int = 22
    private var readerLayoutMode: M9LayoutMode = M9LayoutMode.HORIZONTAL
    private var readerPageAnim: M9PageAnim = M9PageAnim.NONE
    private var readerStyleSelectDefault: Int = DEFAULT_LEGADO_READER_STYLE_INDEX
    private var readerStyleSelect: Int = DEFAULT_LEGADO_READER_STYLE_INDEX
    private var readerNightMode: Boolean = false
    private var readerStyleConfigs: MutableList<LegadoReaderStyleConfig> =
        defaultLegadoReaderStyleConfigs().toMutableList()
    private var readerBgColor: Int = READER_PAGE_BG
    private var readerTextColor: Int = READER_TEXT
    private var readerTipColor: Int = READER_TIP
    private var readerCueHighlightColor: Int = 0xFFFFEFF6.toInt()
    private var readerBgAlpha: Int = 100
    private var readerDarkStatusIcon: Boolean = true
    private var readerUnderline: Boolean = false
    private var readerBgAssetName: String? = null
    private var readerBgImageUri: String? = null
    private var pendingReaderStyleImageIndex: Int = -1
    private var pendingReaderColorDialogId: Int = -1
    private var pendingReaderColorSelected: ((Int) -> Unit)? = null
    private val readerStyleImagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val index = pendingReaderStyleImageIndex
        pendingReaderStyleImageIndex = -1
        if (uri == null || index !in readerStyleConfigs.indices) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        readerStyleConfigs[index] = readerStyleConfigs[index].copy(
            bgAssetName = null,
            bgImageUri = uri.toString()
        )
        selectReaderStyle(index)
    }
    private var audioStopAtMs: Long? = null
    private var audioTimerSelectedMinutes: Int = 0
    private var audioTimerFadeOutEnabled: Boolean = false
    private var audioCueLoopEnabled: Boolean = false
    private var audioCueLoopWindow: Pair<Long, Long>? = null
    private var audioCueRepeatPauseUsesCueDuration: Boolean = true
    private var audioCueRepeatFixedPauseSeconds: Int = DEFAULT_AUDIO_CUE_REPEAT_FIXED_PAUSE_SECONDS
    private var audioCueRepeatFiniteEnabled: Boolean = false
    private var audioCueRepeatCount: Int = 0
    private var audioCueRepeatTailPauseEnabled: Boolean = false
    private var audioCueRepeatFollowCueEnabled: Boolean = false
    private var sentenceNoCrossPage: Boolean = false
    /**
     * 本卷页面是在「还没有 cue 匹配」时切出来的：首屏等匹配超过 [SENTENCE_BOUNDARY_WAIT_MS]。
     * 那样句边界没参与分页、「句子不跨页」等于没开，匹配到齐后补一次重排
     * （见 [relayoutIfPagesLackSentenceBoundaries]）—— 只是等不到时的兜底，正常路径不会走到。
     */
    private var pagesPinnedWithoutSentenceBoundaries: Boolean = false
    private var pauseAfterPageEnd: Boolean = false
    /**
     * 「读完此页暂停」**本次阅读**是否生效：由右下角按钮切换，只活在这一趟（不落设置、不进 UI 状态，
     * 转屏/退出重进都回到默认关）。设置里的 [pauseAfterPageEnd] 只决定那个按钮管哪个功能。
     */
    private var pageEndPauseActive: Boolean = false
    private var audioCueRepeatRemainingCount: Int = 0
    private var audioCueRepeatDelayJob: Job? = null
    private var audioCueRepeatDelayGeneration: Long = 0L
    private var audioCueLoopPausedForRepeat: Boolean = false
    private var audioCueLoopClipActive: Boolean = false
    private var audioCueLoopClipBaseMs: Long = 0L
    private var audioCueLoopClipEndMs: Long = 0L
    private var audioCueLoopPauseAction: AudioCueLoopPauseAction? = null
    private var audioCueLoopPauseCueIndex: Int = -1
    private var hideStatusBar: Boolean = false
    private var readBodyToLh: Boolean = true
    private var hideNavigationBar: Boolean = false
    private var showBrightnessView: Boolean = true
    private var brightnessAuto: Boolean = true
    private var brightnessValue: Int = 160
    private var brightnessPanelOnRight: Boolean = false
    private var topBarMode: ReaderHeaderMode = ReaderHeaderMode.SHOW
    private var tipTopBarLeft: ReaderTipContent = ReaderTipContent.CHAPTER_TITLE
    private var tipTopBarMiddle: ReaderTipContent = ReaderTipContent.NONE
    private var tipTopBarRight: ReaderTipContent = ReaderTipContent.NONE
    private var bodyTitleMode: ReaderBodyTitleMode = ReaderBodyTitleMode.LEFT
    private var bodyTitleSizeAddSp: Int = 0
    private var bodyTitleTopSpacingDp: Int = 0
    private var bodyTitleBottomSpacingDp: Int = 0
    private var headerMode: ReaderHeaderMode = ReaderHeaderMode.HIDE_WHEN_STATUS_BAR_SHOW
    private var footerMode: ReaderFooterMode = ReaderFooterMode.SHOW
    private var tipHeaderLeft: ReaderTipContent = ReaderTipContent.CHAPTER_TITLE
    private var tipHeaderMiddle: ReaderTipContent = ReaderTipContent.NONE
    private var tipHeaderRight: ReaderTipContent = ReaderTipContent.TIME
    private var tipFooterLeft: ReaderTipContent = ReaderTipContent.BOOK_NAME
    private var tipFooterMiddle: ReaderTipContent = ReaderTipContent.NONE
    private var tipFooterRight: ReaderTipContent = ReaderTipContent.PAGE_AND_TOTAL
    private var tipColorMode: ReaderTipColorMode = ReaderTipColorMode.FOLLOW_CONTENT
    private var tipDividerColorMode: ReaderTipDividerColorMode = ReaderTipDividerColorMode.DEFAULT
    private var tipDividerColor: Int = 0x1F000000
    private var headerPaddingTopDp: Int = 0
    private var headerPaddingBottomDp: Int = 0
    private var headerPaddingLeftDp: Int = 0
    private var headerPaddingRightDp: Int = 0
    private var footerPaddingTopDp: Int = 0
    private var footerPaddingBottomDp: Int = 0
    private var footerPaddingLeftDp: Int = 0
    private var footerPaddingRightDp: Int = 0
    private var showHeaderLine: Boolean = false
    private var showFooterLine: Boolean = true
    private var useZhLayout: Boolean = true
    private var textFullJustify: Boolean = true
    private var textBottomJustify: Boolean = true
    private var clickRegionActions: List<ReadView.TapAction> = ReadView.defaultClickRegionActions()
    private var selectionPrimaryActionKey: String = ReadView.DEFAULT_SELECTION_PRIMARY_ACTION_KEY
    private var progressByChapter: Boolean = true
    private val readerInfoAlternateSlots: MutableSet<ReaderInfoSlot> = mutableSetOf()
    private var pendingPageSeekIndex: Int? = null
    private var pendingChapterSeekIndex: Int? = null
    private var chapterSeekBarTracking: Boolean = false
    private var confirmSkipToChapter: Boolean = false
    private var chapterSourceMode: ReaderChapterSourceMode = ReaderChapterSourceMode.BOOK
    private var m4bChapters: List<M4bChapter> = emptyList()
    private var m4bChapterLoadJob: Job? = null
    private var keepScreenOn: Boolean = false
    private var noAnimScrollPage: Boolean = false
    private var disableReturnKey: Boolean = false
    private var readBarStyleFollowPage: Boolean = false
    private var playbackBarPinnedVisible: Boolean = false
    private var crossPageCueWindowEnabled: Boolean = true
    private var crossPageCueWindowMode: CrossPageCueWindowMode = CrossPageCueWindowMode.OVERLAY
    private var stopPlaybackOnImage: Boolean = false
    private var imagePauseSeconds: Int = DEFAULT_IMAGE_PAUSE_SECONDS
    private var verticalControlDirectionReversed: Boolean = false
    private var verticalProgressDirectionReversed: Boolean = false
    private var lastImageStopKey: String? = null
    private var lastSyncSkipLogAtMs: Long = 0L
    private var syncSkipLogCount: Int = 0
    private var imagePauseResumeJob: Job? = null
    private var imagePausePageIndex: Int? = null
    private var currentChapterImageStops: List<ReaderChapterImageStop> = emptyList()
    private var currentChapterImageStopIndex: Int = 0
    private var currentChapterImageStopChapterIndex: Int = -1
    private var showRubyText: Boolean = true
    private var playbackBarHeightPx: Int = 0
    private var pendingRestoreAnchor: ReaderPageAnchor? = null
    private var loadedDocumentBookUriText: String? = null
    private var loadedDocumentCharsetName: String? = null
    private var floatingOverlayStartJob: Job? = null
    private var suppressFloatingOverlayOnStop: Boolean = false
    private var imageGalleryOverlay: View? = null
    private var imageGalleryHideFocus: (() -> Boolean)? = null
    private var imageGalleryDismiss: (() -> Unit)? = null
    private var imageGallerySystemBarMode: ImageGallerySystemBarMode? = null

    private fun readerString(resId: Int): String = getString(resId)

    private fun bridgeCanReturnToPlayer(): Boolean {
        return audioUri != null &&
            BookReaderPlaybackSession.currentAudioUri() == audioUri?.toString()
    }

    private fun publishReaderPlaybackBridgeSnapshot(notifyState: Boolean = false) {
        val uriText = audioUri?.toString()
        BookReaderFloatingBridge.setCurrentAudioUri(uriText)
        currentSharedReaderPlaybackKey()?.let { BookReaderFloatingBridge.setCurrentBookKey(this, it) }
        BookReaderFloatingBridge.notifyPlaybackSpeed(currentAudioPlaybackSpeed())
        currentAudioPositionMs()?.let { BookReaderFloatingBridge.notifyPlaybackPosition(it) }
        publishReaderSubtitleBridgeSnapshot(clearWhenMissing = false)
        if (notifyState) {
            BookReaderFloatingBridge.notifyPlaybackState(isAudioPlaying())
        }
    }

    private fun publishReaderSubtitleBridgeSnapshot(clearWhenMissing: Boolean) {
        BookReaderFloatingBridge.setSubtitleTimeline(
            bookTitle = currentReaderTitle(),
            audioUri = audioUri?.toString(),
            cues = cues.mapIndexed { index, cue ->
                val fullSentence = cueMatchesByCueIndex[index]
                    ?.let(::fullCueText)
                    ?.takeIf { it.isNotBlank() }
                    ?: cue.text
                BookReaderFloatingBridge.SubtitleTimelineCue(
                    startMs = cue.startMs,
                    endMs = cue.endMs,
                    text = cue.text,
                    fullSentenceText = fullSentence,
                    fullSentenceStartMs = cue.startMs,
                    fullSentenceEndMs = cue.endMs
                )
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoreReaderSettings()
        importedBook = intentLocalReaderBook() ?: loadLastLocalReaderBook(this)
        refreshSimulatedReadingConfig()
        restoreBookmarks()
        attachSavedAnchorIfNeeded()
        resolveBookReaderStyleIfNeeded()
        audioUri = intent.getStringExtra(EXTRA_AUDIO_URI)?.trim()?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        srtUri = intent.getStringExtra(EXTRA_SRT_URI)?.trim()?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        returnToPlayerOnBack = intent.getBooleanExtra(EXTRA_RETURN_TO_PLAYER_ON_BACK, false)
        pendingAudioRestorePositionMs = intent.getLongExtra(EXTRA_AUDIO_POSITION_MS, -1L).coerceAtLeast(0L)
        pendingAudioRestoreDurationMs = intent.getLongExtra(EXTRA_AUDIO_DURATION_MS, -1L).coerceAtLeast(0L)
        pendingPlayerOpenAudioReveal = returnToPlayerOnBack && pendingAudioRestorePositionMs > 0L
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyLayoutInDisplayCutoutMode()
        window.isNavigationBarContrastEnforced = false
        volumeControlStream = AudioManager.STREAM_MUSIC
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!handleReaderBackPressed()) {
                        finish()
                    }
                }
            }
        )
        setContentView(buildLegadoReaderShell())
        applySystemUiSettings()
        updateDisplayedBookTitle()
        BookReaderFloatingBridge.addPlaybackStateListener(sharedPlaybackStateListener)
        BookReaderFloatingBridge.addPlaybackPositionListener(sharedPlaybackPositionListener)
        initAudioPlayerIfNeeded()
        ensureM4bChaptersLoaded()
        readView.post { loadDisplayedBook(anchor = pendingRestoreAnchor) }
    }

    private fun handleReaderBackPressed(): Boolean {
        imageGalleryHideFocus?.let { hideFocus ->
            if (hideFocus()) return true
        }
        imageGalleryDismiss?.let { dismiss ->
            dismiss()
            return true
        }
        if (disableReturnKey) {
            setReadMenuVisible(true)
            return true
        }
        when {
            ::searchPanel.isInitialized && searchPanel.visibility == View.VISIBLE -> {
                hideSearchPanel()
                return true
            }
            ::catalogPanel.isInitialized && catalogPanel.visibility == View.VISIBLE -> {
                hideCatalogPanel()
                return true
            }
            isSearchMenuVisible() -> {
                searchQuery = null
                searchHits = emptyList()
                searchHitIndex = -1
                hideSearchMenu()
                renderCurrentPage()
                return true
            }
            ::audioControlPanel.isInitialized && audioControlPanel.visibility == View.VISIBLE -> {
                audioControlPanel.visibility = View.GONE
                updateSystemBarSurfaces()
                return true
            }
            ::moreSettingsPanel.isInitialized && moreSettingsPanel.visibility == View.VISIBLE -> {
                moreSettingsPanel.visibility = View.GONE
                updateSystemBarSurfaces()
                return true
            }
            isReadMenuVisible() -> {
                setReadMenuVisible(false)
                return true
            }
        }
        if (returnToPlayerIfShared()) {
            return true
        }
        return false
    }

    override fun onPause() {
        persistAudioPlaybackSnapshot()
        persistReaderSettingsWithCurrentAnchor("onPause")
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        ReaderPlaybackScreenVisibility.markVisible(this)
        floatingOverlayStartJob?.cancel()
        floatingOverlayStartJob = null
        suppressFloatingOverlayOnStop = false
        stopAudiobookFloatingOverlayService(this)
        // 回到阅读器：别的界面可能换过音频，先跟播放器对账，免得拿过期的本地裁剪旗标算位置
        reconcileAudioClipStateWithPlayer()
    }

    override fun onStop() {
        super.onStop()
        ReaderPlaybackScreenVisibility.markHidden(this)
        floatingOverlayStartJob?.cancel()
        if (isChangingConfigurations || suppressFloatingOverlayOnStop) {
            logDebug(FLOATING_OVERLAY_EXIT_LOG_TAG) {
                "Legado onStop skipped changing=$isChangingConfigurations " +
                "suppress=$suppressFloatingOverlayOnStop"
            }
            return
        }
        val settings = loadAudiobookSettingsConfig(this)
        val overlayEnabled = settings.floatingOverlayEnabled || settings.floatingOverlaySubtitleEnabled
        publishReaderPlaybackBridgeSnapshot(notifyState = true)
        val playing = BookReaderFloatingBridge.isPlaying()
        logDebug(FLOATING_OVERLAY_EXIT_LOG_TAG) {
            "Legado onStop overlayEnabled=$overlayEnabled " +
            "showOnReaderExit=${settings.floatingOverlayShowOnReaderExit} playing=$playing"
        }
        // 两种"裁剪型"武装（读完此页暂停 / 重复）退出后照常生效：裁剪与监视都留在共享播放器上。
        // 显示侧位置由 BookReaderPlaybackSession 换算；**写方向别再加一层偏移**（那里的注释有实测教训）。
        if (!overlayEnabled || !playing) {
            return
        }
        if (settings.floatingOverlayShowOnReaderExit) {
            logDebug(FLOATING_OVERLAY_EXIT_LOG_TAG) { "Legado starting overlay service immediately" }
            startAudiobookFloatingOverlayService(this)
            return
        }
        floatingOverlayStartJob = lifecycleScope.launch {
            delay(150L)
            val refreshed = loadAudiobookSettingsConfig(this@LegadoReaderActivity)
            val refreshedOverlayEnabled =
                refreshed.floatingOverlayEnabled || refreshed.floatingOverlaySubtitleEnabled
            val appForeground = isAppProcessInForeground()
            val readerOrPlayerVisible = ReaderPlaybackScreenVisibility.isReaderOrPlayerScreenVisible()
            val shouldShowAfterReaderExit =
                (refreshed.floatingOverlayShowOnReaderExit || !appForeground) && !readerOrPlayerVisible
            val stillPlaying = BookReaderFloatingBridge.isPlaying()
            logDebug(FLOATING_OVERLAY_EXIT_LOG_TAG) {
                "Legado delayed overlayEnabled=$refreshedOverlayEnabled appForeground=$appForeground " +
                "showOnReaderExit=${refreshed.floatingOverlayShowOnReaderExit} " +
                "readerOrPlayerVisible=$readerOrPlayerVisible shouldShow=$shouldShowAfterReaderExit " +
                "playing=$stillPlaying"
            }
            if (refreshedOverlayEnabled && shouldShowAfterReaderExit && stillPlaying) {
                logDebug(FLOATING_OVERLAY_EXIT_LOG_TAG) { "Legado starting overlay service" }
                startAudiobookFloatingOverlayService(this@LegadoReaderActivity)
            }
        }
    }

    private fun intentLocalReaderBook(): LocalReaderBook? {
        val uriText = intent.getStringExtra(EXTRA_EBOOK_URI)?.trim().orEmpty()
        if (uriText.isBlank()) return null
        val title = intent.getStringExtra(EXTRA_EBOOK_TITLE)?.trim()
            ?: intent.getStringExtra(EXTRA_EBOOK_NAME)?.let(::localReaderBookTitleFromDisplayName)
            ?: LEGADO_READER_DEFAULT_TITLE
        val format = intent.getStringExtra(EXTRA_EBOOK_FORMAT)?.trim().orEmpty().ifBlank { "ebook" }
        return LocalReaderBook(
            title = title,
            uri = Uri.parse(uriText),
            format = format,
            importedAtMs = System.currentTimeMillis()
        )
    }

    private fun buildLegadoReaderShell(): View {
        val root = FrameLayout(this).apply {
            readerRoot = this
            setBackgroundColor(readerBgColor)
        }
        contentContainer = FrameLayout(this)
        statusBarScrim = View(this).apply {
            setBackgroundColor(currentSystemBarColor())
        }
        navigationBarScrim = View(this).apply {
            setBackgroundColor(currentSystemBarColor())
        }
        root.addView(
            statusBarScrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0,
                Gravity.TOP
            )
        )
        root.addView(
            navigationBarScrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0,
                Gravity.BOTTOM
            )
        )
        root.addView(
            contentContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val safeInsets = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val hideStatusForReader = shouldHideStatusBarForReaderChrome()
            val hideNavigationForReader = shouldHideNavigationBarForReaderChrome()
            val top = if (hideStatusBar && readBodyToLh) 0 else safeInsets.top
            val bottom = if (hideNavigationBar) 0 else bars.bottom
            contentContainer.setPadding(0, top, 0, bottom)
            fitMoreSettingsPanelToAvailableHeight()
            applyReadMenuTopInset(safeInsets.top)
            (statusBarScrim.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.height =
                    if (hideStatusForReader && !shouldUseBlackCutoutGuard() && !shouldFillHiddenStatusBarForReaderChrome()) {
                        0
                    } else {
                        safeInsets.top
                    }
                statusBarScrim.layoutParams = params
            }
            (navigationBarScrim.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.height = if (hideNavigationForReader) 0 else bars.bottom
                navigationBarScrim.layoutParams = params
            }
            statusBarScrim.setBackgroundColor(currentStatusBarSurfaceColor())
            navigationBarScrim.setBackgroundColor(currentSystemBarColor())
            insets
        }

        contentContainer.addView(buildStaticPage())

        readMenu = buildReadMenu()
        contentContainer.addView(readMenu)

        searchMenu = buildSearchMenu()
        contentContainer.addView(searchMenu)

        catalogPanel = buildCatalogPanel().apply {
            visibility = View.GONE
        }
        contentContainer.addView(
            catalogPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(520),
                Gravity.BOTTOM
            )
        )

        searchPanel = buildSearchPanel().apply {
            visibility = View.GONE
        }
        contentContainer.addView(
            searchPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(520),
                Gravity.BOTTOM
            )
        )

        moreSettingsPanel = buildMoreSettingsPanel().apply {
            visibility = View.GONE
        }
        contentContainer.addView(
            moreSettingsPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(500),
                Gravity.BOTTOM
            )
        )
        contentContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            fitMoreSettingsPanelToAvailableHeight()
        }

        audioControlPanel = buildAudioControlPanel().apply {
            visibility = View.GONE
        }
        contentContainer.addView(
            audioControlPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        playbackBar = buildPlaybackBar().apply {
            visibility = if (playbackBarPinnedVisible) View.VISIBLE else View.GONE
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                val newHeight = (bottom - top).coerceAtLeast(0)
                val oldHeight = (oldBottom - oldTop).coerceAtLeast(0)
                if (visibility == View.VISIBLE && newHeight > 0 && newHeight != oldHeight) {
                    playbackBarHeightPx = newHeight
                    applyReadViewViewportInset(reload = true)
                }
            }
        }
        contentContainer.addView(
            playbackBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        applyDirectionSettings()

        root.setOnClickListener {
            when {
                searchPanel.visibility == View.VISIBLE -> hideSearchPanel()
                catalogPanel.visibility == View.VISIBLE -> hideCatalogPanel()
                // 搜寻菜单自己吃掉点击：与 legado 的点遮罩一致，收起搜寻菜单（仍在搜寻模式内）
                isSearchMenuVisible() -> hideSearchMenu()
                audioControlPanel.visibility == View.VISIBLE -> {
                    audioControlPanel.visibility = View.GONE
                    updateSystemBarSurfaces()
                }
                moreSettingsPanel.visibility == View.VISIBLE -> {
                    moreSettingsPanel.visibility = View.GONE
                    updateSystemBarSurfaces()
                }
                isSearchModeActive() -> showSearchMenu()
                else -> {
                    toggleReadMenuVisibility()
                }
            }
        }
        applyBrightnessPanelPosition()
        applyBrightnessState()
        applyReadBarStyle()
        ViewCompat.requestApplyInsets(root)
        return root
    }

    private fun readerRootAsViewGroup(): ViewGroup? {
        return if (::readerRoot.isInitialized) readerRoot as? ViewGroup else null
    }

    private fun buildStaticPage(): View {
        return ReadView(this).apply {
            readView = this
            setReaderColors(
                readerBgColor,
                readerTextColor,
                effectiveReaderTipColor(),
                readerBgAssetName,
                readerBgImageUri,
                readerBgAlpha
            )
            setCueHighlightColor(readerCueHighlightColor)
            setTextSizeSp(readerTextSizeSp.toFloat())
            setTextWeight(readerTextWeight)
            setTextUnderline(readerUnderline && readerLayoutMode == M9LayoutMode.HORIZONTAL)
            setReaderTypeface(readerTypeface)
            setReaderPadding(
                dp(readerPaddingLeftDp),
                dp(readerPaddingTopDp),
                dp(readerPaddingRightDp),
                currentReaderBottomPaddingPx()
            )
            setPageAnim(readerPageAnim)
            setLayoutMode(readerLayoutMode)
            setNoAnimScrollPage(noAnimScrollPage)
            setClickRegionActions(clickRegionActions)
            setOnReaderInfoClick { slot -> toggleReaderInfoProgressText(slot) }
            setBookTitle(currentReaderTitle())
            applyReaderInfoConfig()
            setSelectionPrimaryActionKey(selectionPrimaryActionKey)
            selectionJumpToCueEnabled = hasReaderSelectionCueJump()
            canJumpSelectionToCue = { selection -> hasReaderSelectionCueJump(selection) }
            onPagePreview = { delta -> pagePreviewForDelta(delta) }
            onDisplayedPageCommitted = { page -> updateChapterTitleSurfaces(page) }
            onMovePages = { delta -> movePage(delta) }
            onPrevPage = { movePage(-1) }
            onNextPage = { movePage(1) }
            onTapAction = { handleTapRegionAction(it) }
            onSelectionAction = { action, selection -> handleSelectionAction(action, selection) }
            onSelectionProcessText = { intent, text -> handleSelectionProcessText(intent, text) }
            onTextSelectionStateChanged = { active -> handleTextSelectionStateChanged(active) }
            onImageClick = { image ->
                showFocusedImagePreview(image)
            }
            onMenu = {
                when {
                    audioControlPanel.visibility == View.VISIBLE -> {
                        audioControlPanel.visibility = View.GONE
                        updateSystemBarSurfaces()
                    }
                    moreSettingsPanel.visibility == View.VISIBLE -> {
                        moreSettingsPanel.visibility = View.GONE
                        updateSystemBarSurfaces()
                    }
                    // 与 legado 一致：搜寻模式下点屏幕回到搜寻菜单，而不是阅读主菜单
                    isSearchModeActive() -> showSearchMenu()
                    else -> toggleReadMenuVisibility()
                }
            }
        }
    }

    private fun buildReadMenu(): View {
        return layoutInflater.inflate(R.layout.view_m9_legado_read_menu, null, false).apply {
            findViewById<View>(R.id.reader_menu_scrim).setOnClickListener {
                audioControlPanel.visibility = View.GONE
                moreSettingsPanel.visibility = View.GONE
                setReadMenuVisible(false)
            }
            findViewById<TextView>(R.id.reader_back).also {
                it.setOnClickListener {
                    returnToHome()
                }
            }
            findViewById<TextView>(R.id.reader_toolbar_title).also {
                toolbarTitleText = it
                it.text = currentReaderTitle()
            }
            findViewById<View>(R.id.reader_title_addition).also {
                toolbarAdditionContainer = it
            }
            findViewById<TextView>(R.id.reader_title_addition_left).also {
                toolbarAdditionLeftText = it
            }
            findViewById<TextView>(R.id.reader_title_addition_middle).also {
                toolbarAdditionMiddleText = it
            }
            findViewById<TextView>(R.id.reader_title_addition_right).also {
                toolbarAdditionRightText = it
            }
            findViewById<TextView>(R.id.reader_encoding).also {
                it.visibility = View.GONE
                it.setOnClickListener { view -> showEncodingMenu(view) }
            }
            findViewById<TextView>(R.id.reader_overflow).also {
                it.setOnClickListener { view -> showOverflowMenu(view) }
            }
            findViewById<ImageButton>(R.id.reader_search).also {
                floatingSearchButton = it
                it.setOnClickListener {
                    closeReaderChrome()
                    showSearchPanel(searchQuery.orEmpty())
                }
            }
            findViewById<ImageButton>(R.id.reader_replace).also {
                floatingReplaceButton = it
                it.setOnClickListener { showSasayakiMatchDialog() }
            }
            findViewById<ImageButton>(R.id.reader_playback_bar).also {
                floatingPlaybackBarButton = it
                it.setOnClickListener { togglePlaybackBar() }
            }
            findViewById<ImageButton>(R.id.reader_night).also {
                floatingNightButton = it
                it.setOnClickListener { toggleNightMode() }
            }
            findViewById<View>(R.id.reader_brightness).also {
                brightnessPanel = it
                it.visibility = if (showBrightnessView) View.VISIBLE else View.GONE
            }
            findViewById<SeekBar>(R.id.reader_brightness_seek).also { seekBar ->
                brightnessSeekBar = seekBar
                seekBar.max = 255
                seekBar.progress = brightnessValue.coerceIn(1, 255)
                seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            brightnessAuto = false
                            brightnessValue = progress.coerceIn(1, 255)
                            applyBrightnessState()
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        persistReaderSettings()
                    }
                })
            }
            findViewById<ImageView>(R.id.reader_brightness_auto).also {
                brightnessAutoButton = it
                it.setOnClickListener {
                    brightnessAuto = !brightnessAuto
                    applyBrightnessState()
                    persistReaderSettings()
                }
            }
            findViewById<ImageView>(R.id.reader_brightness_position).also {
                brightnessPositionButton = it
                it.setOnClickListener {
                    brightnessPanelOnRight = !brightnessPanelOnRight
                    applyBrightnessPanelPosition()
                    persistReaderSettings()
                }
            }
            findViewById<TextView>(R.id.reader_prev_chapter).setOnClickListener { moveChapter(-1) }
            findViewById<TextView>(R.id.reader_next_chapter).setOnClickListener { moveChapter(1) }
            findViewById<LinearLayout>(R.id.reader_chapter_control_row).also {
                chapterControlRow = it
            }
            findViewById<SeekBar>(R.id.reader_page_seek).also { seek ->
                chapterSeekBar = seek
                seek.max = 0
                seek.progress = 0
                seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser || pages.isEmpty()) return
                        if (progressByChapter) {
                            pendingPageSeekIndex = progress
                        } else {
                            pendingChapterSeekIndex = progress
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {
                        chapterSeekBarTracking = true
                        pendingPageSeekIndex = null
                        pendingChapterSeekIndex = null
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        chapterSeekBarTracking = false
                        if (progressByChapter) {
                            val targetPage = pendingPageSeekIndex ?: seekBar?.progress ?: return
                            pendingPageSeekIndex = null
                            pendingChapterSeekIndex = null
                            pageIndex = seekProgressToPageIndex(targetPage)
                            activeCueIndex = -1
                            renderCurrentPage(persistAnchor = true)
                        } else {
                            val targetChapter = pendingChapterSeekIndex ?: seekBar?.progress ?: return
                            pendingChapterSeekIndex = null
                            pendingPageSeekIndex = null
                            confirmOrJumpToChapterFromSeekBar(targetChapter)
                        }
                    }
                })
            }
            findViewById<LinearLayout>(R.id.reader_catalog).setOnClickListener {
                closeReaderChrome()
                showChapterListDialog()
            }
            findViewById<LinearLayout>(R.id.reader_listen).setOnClickListener {
                toggleAudioControlPanel()
            }
            findViewById<TextView>(R.id.reader_listen_text).also {
                listenActionText = it
            }
            findViewById<LinearLayout>(R.id.reader_style).setOnClickListener {
                showStyleDialog()
            }
            findViewById<LinearLayout>(R.id.reader_setting).setOnClickListener {
                audioControlPanel.visibility = View.GONE
                moreSettingsPanel.visibility =
                    if (moreSettingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                fitMoreSettingsPanelToAvailableHeight()
                updateSystemBarSurfaces()
            }
        }
    }

    private fun buildPlaybackBar(): View {
        return layoutInflater.inflate(R.layout.view_reader_playback_bar, null, false).apply {
            elevation = dp(8).toFloat()
            isClickable = true
            setOnClickListener { }
            findViewById<ImageButton>(R.id.reader_playback_catalog).setOnClickListener {
                showChapterListDialog()
            }
            findViewById<ImageButton>(R.id.reader_playback_prev).setOnClickListener {
                seekToAdjacentCue(controlCueDelta(-1))
            }
            findViewById<ImageButton>(R.id.reader_playback_next).setOnClickListener {
                seekToAdjacentCue(controlCueDelta(1))
            }
            findViewById<ImageButton>(R.id.reader_playback_toggle).also {
                playbackBarToggleButton = it
                it.setOnClickListener { toggleAudioPlayback() }
            }
            findViewById<ImageButton>(R.id.reader_playback_repeat).also {
                playbackBarRepeatButton = it
                it.setOnClickListener { toggleAudioCueLoop() }
                it.setOnLongClickListener {
                    showAudioCueRepeatConfigDialog()
                    true
                }
            }
        }
    }

    private fun buildSearchMenu(): SearchMenu {
        return SearchMenu(this).apply {
            onPrevious = { navigateSearch(-1) }
            onNext = { navigateSearch(1) }
            onResults = { showSearchPanel(searchQuery.orEmpty()) }
            onMainMenu = {
                hideSearchMenu()
                setReadMenuVisible(true)
            }
            onExit = {
                searchQuery = null
                searchHits = emptyList()
                searchHitIndex = -1
                hideSearchMenu()
                renderCurrentPage()
            }
        }
    }

    private fun buildAudioControlPanel(): View {
        return layoutInflater.inflate(R.layout.dialog_m9_read_aloud, null, false).apply {
            elevation = dp(8).toFloat()
            isClickable = true
            setOnClickListener { }
            findViewById<ImageButton>(R.id.audio_play_prev).setOnClickListener {
                seekToAdjacentCue(controlCueDelta(-1))
            }
            findViewById<TextView>(R.id.audio_play_pause).also {
                audioPlayPauseText = it
                it.setOnClickListener { toggleAudioPlayback() }
            }
            findViewById<ImageButton>(R.id.audio_play_next).setOnClickListener {
                seekToAdjacentCue(controlCueDelta(1))
            }
            val speedValue = findViewById<TextView>(R.id.audio_speed_value)
            val speedSeek = findViewById<SeekBar>(R.id.audio_speed_seek)
            val initialSpeed = currentAudioPlaybackSpeed()
            speedValue.text = String.format(java.util.Locale.US, "%.1fx", initialSpeed)
            speedSeek.progress = ((initialSpeed - 0.5f) * 50f).toInt().coerceIn(0, speedSeek.max)
            speedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val speed = 0.5f + progress / 50f
                        speedValue.text = String.format(java.util.Locale.US, "%.1fx", speed)
                        setAudioPlaybackSpeed(speed)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            audioTimerSeekBar = findViewById(R.id.audio_timer_seek)
            audioTimerValueText = findViewById(R.id.audio_timer_value)
            syncAudioTimerSelectionFromState()
            audioTimerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    audioTimerSelectedMinutes = progress.coerceIn(0, AUDIO_TIMER_MAX_MINUTES)
                    updateAudioTimerValueLabel()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    applySelectedAudioTimer(showToast = false)
                }
            })
            findViewById<ImageView>(R.id.audio_timer_apply).setOnClickListener {
                saveDefaultAudioTimerMinutes()
            }
            findViewById<TextView>(R.id.audio_timer_value).setOnClickListener {
                showAudioTimerPresetDialog()
            }
            findViewById<CheckBox>(R.id.audio_timer_fade_out).also { checkbox ->
                val options = loadBookReaderSleepOptions(this@LegadoReaderActivity)
                audioTimerFadeOutEnabled = options.fadeOutAudioWhenDone
                checkbox.isChecked = audioTimerFadeOutEnabled
                checkbox.setOnCheckedChangeListener { _, checked ->
                    audioTimerFadeOutEnabled = checked
                    val current = loadBookReaderSleepOptions(this@LegadoReaderActivity)
                    saveBookReaderSleepOptions(
                        context = this@LegadoReaderActivity,
                        exitControlModeWhenDone = current.exitControlModeWhenDone,
                        disconnectBluetoothWhenDone = current.disconnectBluetoothWhenDone,
                        fadeOutAudioWhenDone = checked,
                        defaultTimerMinutes = current.defaultTimerMinutes
                    )
                }
            }
            updateAudioTimerValueLabel()
        }
    }

    private fun buildMoreSettingsPanel(): MoreConfigDialog {
        return MoreConfigDialog(this).apply {
            onHideStatusBarChanged = {
                hideStatusBar = it
                applySystemUiSettings()
                applyReaderInfoConfig()
                persistReaderSettings()
            }
            onHideNavigationBarChanged = {
                hideNavigationBar = it
                applySystemUiSettings()
                persistReaderSettings()
            }
            onReadBodyToLhChanged = {
                readBodyToLh = it
                persistReaderSettingsWithCurrentAnchor("readBodyToLh")
                applySystemUiSettings()
                applyReaderInfoConfig()
            }
            onShowBrightnessViewChanged = {
                showBrightnessView = it
                brightnessPanel.visibility = if (it) View.VISIBLE else View.GONE
                applyBrightnessState()
                persistReaderSettings()
            }
            onShowReadTitleAdditionChanged = {
                topBarMode = if (it) ReaderHeaderMode.SHOW else ReaderHeaderMode.HIDE
                updateChapterTitleSurfaces()
                persistReaderSettings(updateAnchor = false)
            }
            onUseZhLayoutChanged = {
                useZhLayout = it
                requestBookRelayout()
                if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
            }
            onTextFullJustifyChanged = {
                textFullJustify = it
                requestBookRelayout()
            }
            onTextBottomJustifyChanged = {
                textBottomJustify = it
                requestBookRelayout()
            }
            onNoAnimScrollPageChanged = {
                noAnimScrollPage = it
                readView.setNoAnimScrollPage(it)
                persistReaderSettings()
            }
            onHideUnreadImagesChanged = {
                saveEbookImageSpoilerEnabled(this@LegadoReaderActivity, it)
                bind(currentMoreConfigState())
            }
            onDisableReturnKeyChanged = {
                disableReturnKey = it
                persistReaderSettings()
            }
            onReadBarStyleFollowPageChanged = {
                readBarStyleFollowPage = it
                applyReadBarStyle()
                persistReaderSettings()
            }
            onScreenOrientationClicked = { showScreenOrientationDialog() }
            onKeepLightClicked = { showKeepLightDialog() }
            onProgressBehaviorClicked = { showProgressBehaviorDialog() }
            onSelectionPrimaryActionClicked = { showSelectionPrimaryActionDialog() }
            onClickRegionalConfigClicked = { showClickRegionDialog() }
            onResetDefaultsClicked = { showResetReaderDefaultsDialog() }
            bind(currentMoreConfigState())
        }
    }

    private fun currentMoreConfigState(): MoreConfigState {
        return MoreConfigState(
            screenOrientationIndex = when (requestedOrientation) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> 1
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> 2
                else -> 0
            },
            keepScreenOn = keepScreenOn,
            progressByChapter = progressByChapter,
            selectionPrimaryActionSummary = currentSelectionPrimaryActionSummary(),
            clickRegionSummary = currentClickRegionSummary(),
            hideStatusBar = hideStatusBar,
            readBodyToLh = readBodyToLh,
            hideNavigationBar = hideNavigationBar,
            showBrightnessView = showBrightnessView,
            showReadTitleAddition = isTopBarAdditionVisible(),
            useZhLayout = useZhLayout,
            useZhLayoutLabel = currentZhLayoutToggleLabel(),
            textFullJustify = textFullJustify,
            textBottomJustify = textBottomJustify,
            noAnimScrollPage = noAnimScrollPage,
            hideUnreadImages = loadEbookImageSpoilerEnabled(this),
            disableReturnKey = disableReturnKey,
            readBarStyleFollowPage = readBarStyleFollowPage
        )
    }

    private fun applySystemUiSettings() {
        applyLayoutInDisplayCutoutMode()
        WindowCompat.setDecorFitsSystemWindows(window, !(hideStatusBar || hideNavigationBar || readBodyToLh))
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val hideStatusForReader = shouldHideStatusBarForReaderChrome()
        val hideNavigationForReader = shouldHideNavigationBarForReaderChrome()
        val navigationBarColor = currentSystemBarColor()
        window.statusBarColor = currentStatusBarSurfaceColor()
        window.navigationBarColor = navigationBarColor
        applyLegacySystemUiVisibilityFlags(hideStatusForReader, hideNavigationForReader)
        controller.isAppearanceLightStatusBars = currentStatusBarUsesDarkIcons()
        controller.isAppearanceLightNavigationBars = currentSystemBarUsesDarkIcons()
        val hideTypes =
            (if (hideStatusForReader) WindowInsetsCompat.Type.statusBars() else 0) or
                (if (hideNavigationForReader) WindowInsetsCompat.Type.navigationBars() else 0)
        val showTypes =
            (if (!hideStatusForReader) WindowInsetsCompat.Type.statusBars() else 0) or
                (if (!hideNavigationForReader) WindowInsetsCompat.Type.navigationBars() else 0)
        if (hideTypes != 0) controller.hide(hideTypes)
        if (showTypes != 0) controller.show(showTypes)
        ViewCompat.requestApplyInsets(window.decorView)
    }

    private fun applyReadMenuTopInset(statusBarInset: Int) {
        if (!::readMenu.isInitialized) return
        val titleContainer = readMenu.findViewById<View>(R.id.reader_title_container) ?: return
        val topInset = if (isReadMenuVisible() && hideStatusBar && readBodyToLh) statusBarInset else 0
        if (titleContainer.paddingTop == topInset) return
        titleContainer.setPadding(
            titleContainer.paddingLeft,
            topInset,
            titleContainer.paddingRight,
            titleContainer.paddingBottom
        )
    }

    private fun applyLegacySystemUiVisibilityFlags(
        hideStatusForReader: Boolean,
        hideNavigationForReader: Boolean
    ) {
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_IMMERSIVE or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        if (hideStatusBar || readBodyToLh) {
            flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        if (hideNavigationBar) {
            flags = flags or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        if (hideStatusForReader) {
            flags = flags or View.SYSTEM_UI_FLAG_FULLSCREEN
        }
        if (hideNavigationForReader) {
            flags = flags or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun applyLayoutInDisplayCutoutMode() {
        val targetMode = if (readBodyToLh) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
        }
        if (window.attributes.layoutInDisplayCutoutMode == targetMode) return
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = targetMode
        }
    }

    private fun applyReadBarStyle() {
        if (!::readMenu.isInitialized) return
        val isNight = isNightReaderTheme()
        val immersiveMenu = readBarStyleFollowPage
        val menuBgColor = currentMenuBackgroundColor()
        val textColor = when {
            immersiveMenu -> readerTextColor
            isNight -> 0xFFF4F0E6.toInt()
            else -> 0xFF2C241B.toInt()
        }
        val progressColor = if (isNight) NIGHT_ACCENT else textColor
        val titleContainer = readMenu.findViewById<View>(R.id.reader_title_container)
        val titleBar = readMenu.findViewById<View>(R.id.reader_title_bar)
        val titleAddition = readMenu.findViewById<View>(R.id.reader_title_addition)
        val bottomPanel = readMenu.findViewById<View>(R.id.reader_bottom_panel)
        titleContainer.setBackgroundColor(menuBgColor)
        bottomPanel.setBackgroundColor(menuBgColor)
        applyPlaybackBarStyle()
        tintMenuContent(titleBar, textColor)
        tintMenuContent(titleAddition, textColor)
        tintMenuContent(bottomPanel, textColor)
        updateChapterTitleSurfaces()
        chapterSeekBar.thumb.setTint(progressColor)
        chapterSeekBar.progressTintList = ColorStateList.valueOf(progressColor)
        chapterSeekBar.progressBackgroundTintList = ColorStateList.valueOf(withAlpha(textColor, 0.2f))
        brightnessAutoButton.setColorFilter(if (brightnessAuto) progressColor else withAlpha(textColor, 0.55f))
        brightnessPositionButton.setColorFilter(textColor)
        brightnessPanel.background = GradientDrawable().apply {
            cornerRadius = dp(5).toFloat()
            setColor(if (isNight && !immersiveMenu) NIGHT_BRIGHTNESS_BG else withAlpha(menuBgColor, 0.5f))
        }
        if (::catalogPanel.isInitialized) {
            catalogPanel.setBackgroundColor(menuBgColor)
            tintMenuContent(catalogPanel, textColor)
            updateCatalogTabs()
            catalogSearchInputView.setTextColor(textColor)
            catalogSearchInputView.setHintTextColor(withAlpha(textColor, 0.55f))
        }
        if (::searchPanel.isInitialized) {
            searchPanel.setBackgroundColor(menuBgColor)
            tintMenuContent(searchPanel, textColor)
            searchInputView.setTextColor(textColor)
            searchInputView.setHintTextColor(withAlpha(textColor, 0.55f))
        }
        // 搜寻菜单同样跟随主题（原先布局里是硬编码浅色，夜间模式下会刺眼）
        if (::searchMenu.isInitialized) {
            searchMenu.findViewById<View>(R.id.search_info_bar)?.setBackgroundColor(menuBgColor)
            searchMenu.findViewById<View>(R.id.search_bottom_bar)?.setBackgroundColor(menuBgColor)
            listOf(R.id.search_prev_float, R.id.search_next_float).forEach { id ->
                searchMenu.findViewById<TextView>(id)?.backgroundTintList =
                    ColorStateList.valueOf(menuBgColor)
            }
            tintMenuContent(searchMenu, textColor)
        }
        updateFabStyle(floatingSearchButton, textColor, menuBgColor)
        updateFabStyle(floatingReplaceButton, textColor, menuBgColor)
        updateFabStyle(floatingPlaybackBarButton, textColor, menuBgColor)
        updateFabStyle(floatingNightButton, textColor, menuBgColor)
        floatingNightButton.setImageResource(if (isNightReaderTheme()) R.drawable.reader_ic_daytime else R.drawable.reader_ic_brightness)
        updateSystemBarSurfaces()
    }

    private fun currentMenuBackgroundColor(): Int {
        return when {
            readBarStyleFollowPage -> readerBgColor
            isNightReaderTheme() -> NIGHT_BOTTOM_BG
            else -> 0xFFF8F1E3.toInt()
        }
    }

    private fun currentImageGallerySystemBarColor(): Int? {
        return when (imageGallerySystemBarMode) {
            ImageGallerySystemBarMode.LIST -> Color.WHITE
            ImageGallerySystemBarMode.FOCUS -> Color.TRANSPARENT
            null -> null
        }
    }

    private fun currentImageGallerySystemBarUsesDarkIcons(): Boolean? {
        return when (imageGallerySystemBarMode) {
            ImageGallerySystemBarMode.LIST -> true
            ImageGallerySystemBarMode.FOCUS -> false
            null -> null
        }
    }

    private fun currentSystemBarColor(): Int {
        currentImageGallerySystemBarColor()?.let { return it }
        return if (isReaderChromeVisibleForSystemBars()) currentMenuBackgroundColor() else readerBgColor
    }

    private fun currentStatusBarSurfaceColor(): Int {
        currentImageGallerySystemBarColor()?.let { return it }
        return if (shouldUseBlackCutoutGuard()) Color.BLACK else currentSystemBarColor()
    }

    private fun isReaderChromeVisibleForSystemBars(): Boolean {
        return isReadMenuVisible() ||
            (::catalogPanel.isInitialized && catalogPanel.visibility == View.VISIBLE) ||
            (::searchPanel.isInitialized && searchPanel.visibility == View.VISIBLE) ||
            (::audioControlPanel.isInitialized && audioControlPanel.visibility == View.VISIBLE) ||
            (::moreSettingsPanel.isInitialized && moreSettingsPanel.visibility == View.VISIBLE)
    }

    private fun currentSystemBarUsesDarkIcons(): Boolean {
        currentImageGallerySystemBarUsesDarkIcons()?.let { return it }
        return when {
            !isReaderChromeVisibleForSystemBars() -> readerDarkStatusIcon
            readBarStyleFollowPage -> readerDarkStatusIcon
            else -> !isNightReaderTheme()
        }
    }

    private fun currentStatusBarUsesDarkIcons(): Boolean {
        currentImageGallerySystemBarUsesDarkIcons()?.let { return it }
        return if (shouldUseBlackCutoutGuard()) false else currentSystemBarUsesDarkIcons()
    }

    private fun shouldUseBlackCutoutGuard(): Boolean {
        return hideStatusBar && !readBodyToLh && !isReaderChromeVisibleForSystemBars()
    }

    private fun shouldFillHiddenStatusBarForReaderChrome(): Boolean {
        return hideStatusBar && isReaderChromeVisibleForSystemBars()
    }

    private fun shouldHideStatusBarForReaderChrome(): Boolean {
        return hideStatusBar
    }

    private fun shouldHideNavigationBarForReaderChrome(): Boolean {
        return hideNavigationBar && !isReaderChromeVisibleForSystemBars()
    }

    private fun isReadMenuVisible(): Boolean {
        return ::readMenu.isInitialized && readMenu.visibility == View.VISIBLE
    }

    private fun setReadMenuVisible(visible: Boolean, updateSystemBars: Boolean = true) {
        if (!::readMenu.isInitialized) return
        val targetVisibility = if (visible) View.VISIBLE else View.GONE
        if (readMenu.visibility != targetVisibility) {
            readMenu.visibility = targetVisibility
        }
        if (visible) {
            updateChapterTitleSurfaces()
        }
        if (updateSystemBars) updateSystemBarSurfaces()
    }

    private fun toggleReadMenuVisibility() {
        setReadMenuVisible(!isReadMenuVisible())
    }

    private fun updateSystemBarSurfaces() {
        applyPlaybackBarStyle()
        if (::statusBarScrim.isInitialized) {
            statusBarScrim.setBackgroundColor(currentStatusBarSurfaceColor())
        }
        if (::navigationBarScrim.isInitialized) {
            navigationBarScrim.setBackgroundColor(currentSystemBarColor())
        }
        applySystemUiSettings()
    }

    private fun isVerticalControlDirectionReversed(): Boolean {
        return verticalControlDirectionReversed && readerLayoutMode == M9LayoutMode.VERTICAL
    }

    private fun isVerticalProgressDirectionReversed(): Boolean {
        return verticalProgressDirectionReversed && readerLayoutMode == M9LayoutMode.VERTICAL
    }

    private fun controlCueDelta(delta: Int): Int {
        return if (isVerticalControlDirectionReversed()) -delta else delta
    }

    private fun applyDirectionSettings() {
        val progressDirection = if (isVerticalProgressDirectionReversed()) {
            View.LAYOUT_DIRECTION_RTL
        } else {
            View.LAYOUT_DIRECTION_LTR
        }
        if (::chapterControlRow.isInitialized) chapterControlRow.layoutDirection = progressDirection
        if (::chapterSeekBar.isInitialized) chapterSeekBar.layoutDirection = progressDirection
    }

    private fun tintMenuContent(view: View, textColor: Int) {
        when (view) {
            is TextView -> {
                view.setTextColor(textColor)
                // drawableTop 之类随文字排布的图标不吃 textColor，需要单独上色（搜寻菜单用到）
                if (view.compoundDrawables.any { it != null }) {
                    view.compoundDrawableTintList = ColorStateList.valueOf(textColor)
                }
            }
            is ImageView -> view.setColorFilter(textColor)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                tintMenuContent(view.getChildAt(index), textColor)
            }
        }
    }

    private fun buildCatalogPanel(): View {
        return layoutInflater.inflate(R.layout.view_reader_catalog_panel, null, false).apply {
            findViewById<TextView>(R.id.reader_catalog_title).also {
                catalogTitleView = it
            }
            findViewById<TextView>(R.id.reader_catalog_tab_chapters).also {
                catalogTabChaptersView = it
                it.setOnClickListener {
                    catalogMode = CatalogMode.CHAPTERS
                    bindCatalogList()
                }
            }
            findViewById<TextView>(R.id.reader_catalog_tab_bookmarks).also {
                catalogTabBookmarksView = it
                it.setOnClickListener {
                    catalogMode = CatalogMode.BOOKMARKS
                    bindCatalogList()
                }
            }
            findViewById<EditText>(R.id.reader_catalog_search_input).also { input ->
                catalogSearchInputView = input
                input.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        catalogFilterQuery = s?.toString().orEmpty()
                        bindCatalogList()
                    }
                })
            }
            findViewById<View>(R.id.reader_catalog_close).setOnClickListener { hideCatalogPanel() }
            findViewById<ListView>(R.id.reader_catalog_list).also {
                catalogListView = it
            }
        }
    }

    private fun buildSearchPanel(): View {
        return layoutInflater.inflate(R.layout.view_reader_search_panel, null, false).apply {
            findViewById<EditText>(R.id.reader_search_input).also { input ->
                searchInputView = input
                input.setOnEditorActionListener { _, _, _ ->
                    performSearchFromPanel()
                    true
                }
            }
            findViewById<TextView>(R.id.reader_search_result_info).also {
                searchResultInfoView = it
            }
            findViewById<ListView>(R.id.reader_search_result_list).also {
                searchResultListView = it
            }
            findViewById<View>(R.id.reader_search_submit).setOnClickListener {
                performSearchFromPanel()
            }
            findViewById<View>(R.id.reader_search_close).setOnClickListener {
                hideSearchPanel()
            }
        }
    }

    private fun updateFabStyle(button: ImageButton, iconColor: Int, bgColor: Int) {
        button.backgroundTintList = ColorStateList.valueOf(bgColor)
        button.imageTintList = ColorStateList.valueOf(iconColor)
    }

    private fun restoreBookmarks() {
        bookmarks = loadReaderBookmarks(this, readerBookmarkStoreKey()).toMutableList()
    }

    private fun persistBookmarks() {
        saveReaderBookmarks(this, readerBookmarkStoreKey(), bookmarks)
    }

    private fun readerBookmarkStoreKey(): String {
        val stable = importedBook?.uri?.toString()?.ifBlank { null } ?: currentReaderTitle()
        return "bookmark::$stable"
    }

    private fun addCurrentBookmark(preferredExcerpt: String? = null) {
        val page = pages.getOrNull(pageIndex) ?: run {
            Toast.makeText(this, R.string.reader_bookmark_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        findBookmarkForPage(page)?.let { bookmark ->
            showBookmarkEditor(bookmark, isNew = false)
            return
        }
        val excerpt = preferredExcerpt
            ?.replace('\n', ' ')
            ?.trim()
            ?.take(300)
            ?.takeIf { it.isNotBlank() }
            ?: defaultBookmarkExcerpt(page)
            .ifBlank { page.title.ifBlank { currentReaderTitle() } }
        showBookmarkEditor(
            ReaderBookmark(
                chapterIndex = page.chapterIndex,
                chapterPosition = page.charStart,
                chapterTitle = page.title,
                excerpt = excerpt,
                note = "",
                createdAtMs = System.currentTimeMillis()
            ),
            isNew = true
        )
    }

    private fun findBookmarkForPage(page: TextPage): ReaderBookmark? {
        return bookmarks.firstOrNull {
            it.chapterIndex == page.chapterIndex && it.chapterPosition == page.charStart
        }
    }

    private fun defaultBookmarkExcerpt(page: TextPage): String {
        return page.text
            .replace('\n', ' ')
            .trim()
            .take(300)
    }

    private fun showBookmarkEditor(bookmark: ReaderBookmark, isNew: Boolean) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        container.addView(TextView(this).apply {
            text = bookmark.chapterTitle.ifBlank { readerString(R.string.reader_unknown_chapter) }
            textSize = 14f
            setTextColor(currentMenuTextColor())
            setPadding(0, 0, 0, dp(8))
        })
        val excerptInput = addBookmarkDialogInput(
            container = container,
            labelRes = R.string.reader_bookmark_excerpt,
            value = bookmark.excerpt,
            minLines = 3
        )
        val noteInput = addBookmarkDialogInput(
            container = container,
            labelRes = R.string.reader_bookmark_note,
            value = bookmark.note,
            minLines = 2
        )
        val scroll = ScrollView(this).apply {
            addView(container)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isNew) R.string.reader_bookmark_add_title else R.string.reader_bookmark_edit_title)
            .setView(scroll)
            .setPositiveButton(R.string.reader_bookmark_save, null)
            .setNegativeButton(R.string.common_cancel, null)
            .apply {
                if (!isNew) {
                    setNeutralButton(R.string.common_delete, null)
                }
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val excerpt = excerptInput.text?.toString()?.trim().orEmpty()
                if (excerpt.isBlank()) {
                    Toast.makeText(this, R.string.reader_bookmark_excerpt_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                upsertBookmark(
                    bookmark.copy(
                        excerpt = excerpt,
                        note = noteInput.text?.toString()?.trim().orEmpty()
                    )
                )
                Toast.makeText(
                    this,
                    if (isNew) R.string.reader_bookmark_added else R.string.reader_bookmark_updated,
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                deleteBookmark(bookmark)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun addBookmarkDialogInput(
        container: LinearLayout,
        labelRes: Int,
        value: String,
        minLines: Int
    ): EditText {
        container.addView(TextView(this).apply {
            text = getString(labelRes)
            textSize = 13f
            setTextColor(readerTipColor)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        })
        return EditText(this).apply {
            setText(value)
            setMinLines(minLines)
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }.also { input ->
            container.addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun upsertBookmark(bookmark: ReaderBookmark) {
        bookmarks.removeAll {
            it.chapterIndex == bookmark.chapterIndex && it.chapterPosition == bookmark.chapterPosition
        }
        bookmarks += bookmark
        bookmarks.sortBy { it.chapterIndex * 1_000_000L + it.chapterPosition }
        persistBookmarks()
        refreshBookmarkCatalogIfVisible()
    }

    private fun deleteBookmark(bookmark: ReaderBookmark) {
        val removed = bookmarks.removeAll {
            it.chapterIndex == bookmark.chapterIndex && it.chapterPosition == bookmark.chapterPosition
        }
        if (!removed) return
        persistBookmarks()
        refreshBookmarkCatalogIfVisible()
        Toast.makeText(this, R.string.reader_bookmark_deleted, Toast.LENGTH_SHORT).show()
    }

    private fun refreshBookmarkCatalogIfVisible() {
        if (::catalogPanel.isInitialized && catalogPanel.visibility == View.VISIBLE && catalogMode == CatalogMode.BOOKMARKS) {
            bindCatalogList()
        }
    }

    private fun applyBrightnessState() {
        if (!::brightnessSeekBar.isInitialized) return
        brightnessSeekBar.isEnabled = !brightnessAuto
        val clamped = brightnessValue.coerceIn(1, 255)
        if (brightnessSeekBar.progress != clamped) {
            brightnessSeekBar.progress = clamped
        }
        window.attributes = window.attributes.apply {
            screenBrightness = if (brightnessAuto) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                (clamped / 255f).coerceIn(1f / 255f, 1f)
            }
        }
        if (::brightnessAutoButton.isInitialized && ::brightnessPositionButton.isInitialized) {
            applyReadBarStyle()
        }
    }

    private fun applyBrightnessPanelPosition() {
        if (!::brightnessPanel.isInitialized) return
        (brightnessPanel.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.gravity = if (brightnessPanelOnRight) {
                Gravity.END or Gravity.CENTER_VERTICAL
            } else {
                Gravity.START or Gravity.CENTER_VERTICAL
            }
            brightnessPanel.layoutParams = params
        }
    }

    private fun fitMoreSettingsPanelToAvailableHeight() {
        if (!::contentContainer.isInitialized || !::moreSettingsPanel.isInitialized) return
        val availableHeight = (contentContainer.height - contentContainer.paddingTop - contentContainer.paddingBottom)
            .coerceAtLeast(0)
        val params = moreSettingsPanel.layoutParams as? FrameLayout.LayoutParams ?: return
        val targetHeight = minOf(dp(500), availableHeight)
        if (params.height != targetHeight) {
            params.height = targetHeight
            moreSettingsPanel.layoutParams = params
        }
    }

    private fun closeReaderChrome() {
        audioControlPanel.visibility = View.GONE
        moreSettingsPanel.visibility = View.GONE
        if (::catalogPanel.isInitialized) catalogPanel.visibility = View.GONE
        if (::searchPanel.isInitialized) searchPanel.visibility = View.GONE
        setReadMenuVisible(false, updateSystemBars = false)
        updateSystemBarSurfaces()
    }

    private fun isNightReaderTheme(): Boolean = readerNightMode

    private fun currentMenuTextColor(): Int = if (isNightReaderTheme()) 0xFFF4F0E6.toInt() else 0xFF2C241B.toInt()

    private fun applyPlaybackBarStyle() {
        if (!::playbackBar.isInitialized) return
        val chromeVisible = isReaderChromeVisibleForSystemBars()
        val bgColor = if (chromeVisible) currentMenuBackgroundColor() else readerBgColor
        val iconColor = if (chromeVisible) {
            when {
                readBarStyleFollowPage -> readerTextColor
                isNightReaderTheme() -> 0xFFF4F0E6.toInt()
                else -> 0xFF2C241B.toInt()
            }
        } else {
            readerTextColor
        }
        playbackBar.setBackgroundColor(bgColor)
        tintMenuContent(playbackBar, iconColor)
    }

    private fun withAlpha(color: Int, alphaFraction: Float): Int {
        val alpha = (alphaFraction.coerceIn(0f, 1f) * 255f).toInt()
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun accentColor(): Int = NIGHT_ACCENT

    private fun showScreenOrientationDialog() {
        val items = resources.getStringArray(R.array.reader_screen_direction_titles)
        val checked = when (requestedOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> 1
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_title_screen_direction)
            .setSingleChoiceItems(items, checked) { dialog, which ->
                requestedOrientation = when (which) {
                    1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
                dialog.dismiss()
            }
            .show()
    }

    private fun showKeepLightDialog() {
        val items = resources.getStringArray(R.array.reader_keep_light_titles)
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_title_keep_light)
            .setSingleChoiceItems(items, if (keepScreenOn) 1 else 0) { dialog, which ->
                keepScreenOn = which == 1
                if (keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                persistReaderSettings()
                if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
                dialog.dismiss()
            }
            .show()
    }

    private fun showProgressBehaviorDialog() {
        val items = resources.getStringArray(R.array.reader_progress_behavior_titles)
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_title_progress_behavior)
            .setSingleChoiceItems(items, if (progressByChapter) 0 else 1) { dialog, which ->
                progressByChapter = which == 0
                persistReaderSettings()
                dialog.dismiss()
                renderCurrentPage()
                if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
            }
            .show()
    }

    private fun showClickRegionDialog() {
        val root = layoutInflater.inflate(R.layout.dialog_reader_click_action_config, null, false)
        val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val cellIds = intArrayOf(
            R.id.reader_click_region_top_left,
            R.id.reader_click_region_top_center,
            R.id.reader_click_region_top_right,
            R.id.reader_click_region_middle_left,
            R.id.reader_click_region_middle_center,
            R.id.reader_click_region_middle_right,
            R.id.reader_click_region_bottom_left,
            R.id.reader_click_region_bottom_center,
            R.id.reader_click_region_bottom_right
        )
        repeat(ReadView.CLICK_REGION_COUNT) { index ->
            root.findViewById<TextView>(cellIds[index]).apply {
                text = readerTapActionLabel(clickRegionActions[index])
                setOnClickListener {
                    showClickRegionActionDialog(index, clickRegionActions[index]) { action ->
                        clickRegionActions = clickRegionActions.toMutableList().apply {
                            this[index] = action
                        }.toList()
                        text = readerTapActionLabel(action)
                        readView.setClickRegionActions(clickRegionActions)
                        persistReaderSettings()
                        if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
                    }
                }
            }
        }
        root.findViewById<View>(R.id.reader_click_region_close).setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            val normalized = normalizeClickRegionActions(clickRegionActions)
            if (normalized != clickRegionActions) {
                clickRegionActions = normalized
                readView.setClickRegionActions(clickRegionActions)
                persistReaderSettings()
                Toast.makeText(this, R.string.reader_click_region_menu_required, Toast.LENGTH_SHORT).show()
            }
            if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
        }
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    private fun currentZhLayoutToggleLabel(): String {
        return if (useZhLayout) {
            readerString(R.string.reader_convert_state_enabled)
        } else {
            readerString(R.string.reader_convert_state_disabled)
        }
    }

    private fun showResetReaderDefaultsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_reset_defaults)
            .setMessage(R.string.reader_reset_defaults_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                resetMoreSettingsToDefaults()
            }
            .show()
    }

    private fun resetMoreSettingsToDefaults() {
        val anchor = currentPageAnchor()
        val defaults = LegadoReaderPersistedState()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        hideStatusBar = defaults.hideStatusBar
        readBodyToLh = defaults.readBodyToLh
        hideNavigationBar = defaults.hideNavigationBar
        showBrightnessView = defaults.showBrightnessView
        topBarMode = defaults.topBarMode
        tipTopBarLeft = defaults.tipTopBarLeft
        tipTopBarMiddle = defaults.tipTopBarMiddle
        tipTopBarRight = defaults.tipTopBarRight
        bodyTitleMode = defaults.bodyTitleMode
        bodyTitleSizeAddSp = defaults.bodyTitleSizeAddSp
        bodyTitleTopSpacingDp = defaults.bodyTitleTopSpacingDp
        bodyTitleBottomSpacingDp = defaults.bodyTitleBottomSpacingDp
        headerMode = defaults.headerMode
        footerMode = defaults.footerMode
        tipHeaderLeft = defaults.tipHeaderLeft
        tipHeaderMiddle = defaults.tipHeaderMiddle
        tipHeaderRight = defaults.tipHeaderRight
        tipFooterLeft = defaults.tipFooterLeft
        tipFooterMiddle = defaults.tipFooterMiddle
        tipFooterRight = defaults.tipFooterRight
        readerInfoAlternateSlots.clear()
        tipColorMode = defaults.tipColorMode
        tipDividerColorMode = defaults.tipDividerColorMode
        tipDividerColor = defaults.tipDividerColor
        readerPaddingTopDp = defaults.readerPaddingTopDp
        readerPaddingBottomDp = defaults.readerPaddingBottomDp
        readerPaddingLeftDp = defaults.readerPaddingLeftDp
        readerPaddingRightDp = defaults.readerPaddingRightDp
        readerPaddingDp = defaults.paddingDp
        headerPaddingTopDp = defaults.headerPaddingTopDp
        headerPaddingBottomDp = defaults.headerPaddingBottomDp
        headerPaddingLeftDp = defaults.headerPaddingLeftDp
        headerPaddingRightDp = defaults.headerPaddingRightDp
        footerPaddingTopDp = defaults.footerPaddingTopDp
        footerPaddingBottomDp = defaults.footerPaddingBottomDp
        footerPaddingLeftDp = defaults.footerPaddingLeftDp
        footerPaddingRightDp = defaults.footerPaddingRightDp
        showHeaderLine = defaults.showHeaderLine
        showFooterLine = defaults.showFooterLine
        useZhLayout = defaults.useZhLayout
        textFullJustify = defaults.textFullJustify
        textBottomJustify = defaults.textBottomJustify
        clickRegionActions = defaultClickRegionActionsForCurrentLayout()
        selectionPrimaryActionKey = defaults.selectionPrimaryActionKey
        progressByChapter = defaults.progressByChapter
        chapterSourceMode = defaults.chapterSourceMode
        keepScreenOn = defaults.keepScreenOn
        noAnimScrollPage = defaults.noAnimScrollPage
        saveEbookImageSpoilerEnabled(this, false)
        disableReturnKey = defaults.disableReturnKey
        readBarStyleFollowPage = defaults.readBarStyleFollowPage

        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        readView.setClickRegionActions(clickRegionActions)
        readView.setSelectionPrimaryActionKey(selectionPrimaryActionKey)
        applyReaderInfoConfig()
        readView.setNoAnimScrollPage(noAnimScrollPage)
        brightnessPanel.visibility = if (showBrightnessView) View.VISIBLE else View.GONE
        applyBrightnessState()
        applyReadBarStyle()
        persistReaderSettings()
        if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
        readView.post { relayoutCurrentDocument(anchor) }
    }

    private fun currentClickRegionSummary(): String {
        return if (clickRegionActions == defaultClickRegionActionsForCurrentLayout()) {
            readerString(R.string.reader_click_region_summary_default)
        } else {
            readerString(R.string.reader_click_region_summary_custom)
        }
    }

    private fun currentSelectionPrimaryActionSummary(): String {
        val options = readView.selectionPrimaryActionOptions()
        return options.firstOrNull { it.key == selectionPrimaryActionKey }?.label
            ?: readerString(R.string.reader_selection_primary_default)
    }

    private fun showSelectionPrimaryActionDialog() {
        val options = readView.selectionPrimaryActionOptions()
        val labels = options.map { it.label }.toTypedArray()
        val selectedIndex = options.indexOfFirst { it.key == selectionPrimaryActionKey }
            .takeIf { it >= 0 }
            ?: 0
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_selection_primary_action)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                selectionPrimaryActionKey = options.getOrNull(which)?.key
                    ?: ReadView.DEFAULT_SELECTION_PRIMARY_ACTION_KEY
                readView.setSelectionPrimaryActionKey(selectionPrimaryActionKey)
                persistReaderSettings(updateAnchor = false)
                if (::moreSettingsPanel.isInitialized) {
                    moreSettingsPanel.bind(currentMoreConfigState())
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun defaultClickRegionActionsForCurrentLayout(): List<ReadView.TapAction> {
        return ReadView.defaultClickRegionActions(readerLayoutMode)
    }

    private fun showClickRegionActionDialog(
        regionIndex: Int,
        currentAction: ReadView.TapAction,
        onSelected: (ReadView.TapAction) -> Unit
    ) {
        val actions = readerTapActionOptions()
        val labels = actions.map(::readerTapActionLabel).toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(readerTapRegionLabel(regionIndex))
            .setSingleChoiceItems(labels, actions.indexOf(currentAction)) { dialog, which ->
                onSelected(actions[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun readerTapActionOptions(): List<ReadView.TapAction> = listOf(
        ReadView.TapAction.NONE,
        ReadView.TapAction.MENU,
        ReadView.TapAction.NEXT_PAGE,
        ReadView.TapAction.PREV_PAGE,
        ReadView.TapAction.NEXT_CHAPTER,
        ReadView.TapAction.PREV_CHAPTER,
        ReadView.TapAction.NEXT_AUDIO_CUE,
        ReadView.TapAction.PREV_AUDIO_CUE,
        ReadView.TapAction.ADD_BOOKMARK,
        ReadView.TapAction.TOGGLE_CONVERT,
        ReadView.TapAction.CATALOG,
        ReadView.TapAction.SEARCH,
        ReadView.TapAction.TOGGLE_REPEAT
    )

    private fun readerTapActionLabel(action: ReadView.TapAction): String {
        val resId = when (action) {
            ReadView.TapAction.NONE -> R.string.reader_click_action_none
            ReadView.TapAction.MENU -> R.string.reader_click_action_menu
            ReadView.TapAction.NEXT_PAGE -> R.string.reader_click_action_next_page
            ReadView.TapAction.PREV_PAGE -> R.string.reader_click_action_prev_page
            ReadView.TapAction.NEXT_CHAPTER -> R.string.reader_click_action_next_chapter
            ReadView.TapAction.PREV_CHAPTER -> R.string.reader_click_action_prev_chapter
            ReadView.TapAction.NEXT_AUDIO_CUE -> R.string.reader_click_action_next_audio
            ReadView.TapAction.PREV_AUDIO_CUE -> R.string.reader_click_action_prev_audio
            ReadView.TapAction.ADD_BOOKMARK -> R.string.reader_click_action_add_bookmark
            ReadView.TapAction.TOGGLE_CONVERT -> R.string.reader_click_action_toggle_convert
            ReadView.TapAction.CATALOG -> R.string.reader_click_action_catalog
            ReadView.TapAction.SEARCH -> R.string.reader_click_action_search
            ReadView.TapAction.TOGGLE_REPEAT -> R.string.reader_click_action_toggle_repeat
        }
        return readerString(resId)
    }

    private fun readerTapRegionLabel(index: Int): String {
        val resId = when (index) {
            0 -> R.string.reader_click_region_top_left
            1 -> R.string.reader_click_region_top_center
            2 -> R.string.reader_click_region_top_right
            3 -> R.string.reader_click_region_middle_left
            4 -> R.string.reader_click_region_middle_center
            5 -> R.string.reader_click_region_middle_right
            6 -> R.string.reader_click_region_bottom_left
            7 -> R.string.reader_click_region_bottom_center
            else -> R.string.reader_click_region_bottom_right
        }
        return readerString(resId)
    }

    private fun handleTapRegionAction(action: ReadView.TapAction) {
        when (action) {
            ReadView.TapAction.NONE,
            ReadView.TapAction.MENU,
            ReadView.TapAction.NEXT_PAGE,
            ReadView.TapAction.PREV_PAGE -> Unit
            ReadView.TapAction.NEXT_CHAPTER -> moveChapter(1)
            ReadView.TapAction.PREV_CHAPTER -> moveChapter(-1)
            ReadView.TapAction.NEXT_AUDIO_CUE -> seekToAdjacentCue(1)
            ReadView.TapAction.PREV_AUDIO_CUE -> seekToAdjacentCue(-1)
            ReadView.TapAction.ADD_BOOKMARK -> addCurrentBookmark()
            ReadView.TapAction.TOGGLE_CONVERT -> toggleReaderConvertState()
            ReadView.TapAction.CATALOG -> {
                closeReaderChrome()
                showChapterListDialog()
            }
            ReadView.TapAction.SEARCH -> {
                closeReaderChrome()
                showSearchPanel(searchQuery.orEmpty())
            }
            ReadView.TapAction.TOGGLE_REPEAT -> toggleAudioCueLoop()
        }
    }

    private fun handleSelectionAction(action: ReadView.SelectionAction, selection: ReadView.SelectionInfo) {
        val text = selection.text
        when (action) {
            ReadView.SelectionAction.PROCESS_TEXT -> startSelectionProcessText(text)
            ReadView.SelectionAction.COPY -> Unit
            ReadView.SelectionAction.SHARE -> {
                runCatching {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            },
                            text
                        )
                    )
                }
            }
            ReadView.SelectionAction.SEARCH -> {
                closeReaderChrome()
                showSearchPanel(text)
                startSearch(text)
            }
            ReadView.SelectionAction.ADD_BOOKMARK -> addCurrentBookmark(preferredExcerpt = text)
            ReadView.SelectionAction.JUMP_TO_CUE -> jumpSelectionToCurrentCue(selection)
            ReadView.SelectionAction.BROWSER -> {
                runCatching {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com/search?q=${Uri.encode(text)}")
                        )
                    )
                }
            }
        }
    }

    private fun startSelectionProcessText(text: String) {
        launchSelectionProcessText(
            Intent(Intent.ACTION_PROCESS_TEXT).apply {
                type = "text/plain"
            },
            text = text,
            readOnly = true
        )
    }

    private fun handleSelectionProcessText(intent: Intent, text: String) {
        launchSelectionProcessText(intent, text = text, readOnly = false)
    }

    private fun handleTextSelectionStateChanged(active: Boolean) {
        textSelectionActive = active
    }

    private fun hasReaderSelectionCueJump(): Boolean {
        return audioUri != null
    }

    private fun hasReaderSelectionCueJump(selection: ReadView.SelectionInfo): Boolean {
        if (audioUri == null) return false
        if (cues.isEmpty() || cueMatchesByCueIndex.isEmpty()) return false
        return findSelectionCueIndex(selection) != null
    }

    private fun jumpSelectionToCurrentCue(selection: ReadView.SelectionInfo? = null) {
        if (audioUri == null) {
            Toast.makeText(this, R.string.reader_no_audio, Toast.LENGTH_SHORT).show()
            return
        }
        val targetIndex = selection?.let(::findSelectionCueIndex) ?: -1
        if (targetIndex !in cues.indices) {
            Toast.makeText(this, R.string.reader_no_srt, Toast.LENGTH_SHORT).show()
            return
        }
        seekToCueIndex(targetIndex)
    }

    private fun findSelectionCueIndex(selection: ReadView.SelectionInfo): Int? {
        val chapterIndex = selection.chapterIndex ?: return null
        val chapterRange = selection.chapterRange ?: return null
        if (cueMatchesByCueIndex.isEmpty()) return null
        val selectionStart = chapterRange.first
        val selectionEndExclusive = chapterRange.last + 1
        val midpoint = selectionStart + (chapterRange.last - selectionStart).coerceAtLeast(0) / 2
        val chapterMatches = cueMatchesByCueIndex.values.filter { it.chapterIndex == chapterIndex }
        if (chapterMatches.isEmpty()) return null
        chapterMatches.firstOrNull { match ->
            midpoint in match.rawStart until match.rawEnd
        }?.cueIndex?.let { return it }
        chapterMatches
            .asSequence()
            .mapNotNull { match ->
                val overlapStart = maxOf(selectionStart, match.rawStart)
                val overlapEnd = minOf(selectionEndExclusive, match.rawEnd)
                val overlap = overlapEnd - overlapStart
                if (overlap > 0) match.cueIndex to overlap else null
            }
            .maxByOrNull { it.second }
            ?.first
            ?.let { return it }
        return chapterMatches.minByOrNull { match ->
            when {
                midpoint < match.rawStart -> match.rawStart - midpoint
                midpoint >= match.rawEnd -> midpoint - match.rawEnd
                else -> 0
            }
        }?.cueIndex
    }

    private fun launchSelectionProcessText(intent: Intent, text: String, readOnly: Boolean) {
        runCatching {
            startActivity(
                Intent(intent).apply {
                    putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                    putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, readOnly)
                }
            )
        }.onFailure {
            Toast.makeText(this, it.localizedMessage ?: "PROCESS_TEXT", Toast.LENGTH_SHORT).show()
        }
    }

    private fun normalizeClickRegionActions(actions: List<ReadView.TapAction>): List<ReadView.TapAction> {
        if (actions.any { it == ReadView.TapAction.MENU }) return actions
        return actions.toMutableList().apply {
            this[4] = ReadView.TapAction.MENU
        }.toList()
    }

    private fun toggleReaderConvertState() {
        useZhLayout = !useZhLayout
        requestBookRelayout()
        if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
    }

    private fun showOverflowMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, ReaderOverflowAction.PLAYER.menuId, 0, R.string.reader_menu_player)
            menu.add(0, ReaderOverflowAction.ADD_BOOKMARK.menuId, 1, R.string.reader_menu_add_bookmark)
            if (collectReaderImageGalleryItems().isNotEmpty()) {
                menu.add(0, ReaderOverflowAction.IMAGES.menuId, 2, R.string.reader_menu_images)
            }
            menu.add(0, ReaderOverflowAction.SIMULATED_READING.menuId, 3, R.string.reader_simulated_reading_title)
            if (hasRubySpans()) {
                menu.add(0, ReaderOverflowAction.REMOVE_RUBY.menuId, 4, R.string.del_ruby_tag).apply {
                    isCheckable = true
                    isChecked = !showRubyText
                }
            }
            menu.add(
                0,
                ReaderOverflowAction.SWITCH_LAYOUT.menuId,
                5,
                if (readerLayoutMode == M9LayoutMode.VERTICAL) R.string.reader_menu_switch_horizontal else R.string.reader_menu_switch_vertical
            )
            menu.add(0, ReaderOverflowAction.CHARSET.menuId, 6, R.string.reader_encoding_set)
            menu.add(0, ReaderOverflowAction.HELP.menuId, 7, R.string.reader_menu_help)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    ReaderOverflowAction.PLAYER.menuId -> openPlayerFromReader()
                    ReaderOverflowAction.ADD_BOOKMARK.menuId -> addCurrentBookmark()
                    ReaderOverflowAction.IMAGES.menuId -> showImageGalleryDialog()
                    ReaderOverflowAction.SIMULATED_READING.menuId -> showSimulatedReadingDialog()
                    ReaderOverflowAction.REMOVE_RUBY.menuId -> {
                        item.isChecked = !item.isChecked
                        showRubyText = !item.isChecked
                        requestBookRelayout(immediate = true)
                    }
                    ReaderOverflowAction.SWITCH_LAYOUT.menuId -> {
                        val oldLayoutMode = readerLayoutMode
                        val wasDefaultClickRegions = clickRegionActions == ReadView.defaultClickRegionActions(oldLayoutMode)
                        readerLayoutMode =
                            if (oldLayoutMode == M9LayoutMode.VERTICAL) {
                                M9LayoutMode.HORIZONTAL
                            } else {
                                M9LayoutMode.VERTICAL
                            }
                        readView.setLayoutMode(readerLayoutMode)
                        if (wasDefaultClickRegions) {
                            clickRegionActions = defaultClickRegionActionsForCurrentLayout()
                            readView.setClickRegionActions(clickRegionActions)
                            if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
                        }
                        readView.setTextUnderline(readerUnderline && readerLayoutMode == M9LayoutMode.HORIZONTAL)
                        applyDirectionSettings()
                        requestBookRelayout()
                        updateSelectedReaderStyleLayoutFields()
                        persistReaderSettings()
                    }
                    ReaderOverflowAction.CHARSET.menuId -> showEncodingMenu(anchor)
                }
                true
            }
            show()
        }
    }

    private fun showSimulatedReadingDialog() {
        val bookUri = currentBookUriText()
        if (bookUri == null) {
            Toast.makeText(this, R.string.reader_catalog_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val currentChapterOneBased = ((currentPageAnchor()?.chapterIndex ?: pages.getOrNull(pageIndex)?.chapterIndex) ?: 0) + 1
        val dialogStartChapter = if (!simulatedReadingConfig.enabled && simulatedReadingConfig.startChapter <= 1) {
            currentChapterOneBased
        } else {
            simulatedReadingConfig.startChapter.coerceAtLeast(1)
        }
        var selectedStartEpochDay = simulatedReadingConfig.startEpochDay
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(12), dp(30), 0)
        }
        fun label(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 18f
            setTextColor(currentMenuTextColor())
            gravity = Gravity.CENTER_VERTICAL
        }
        fun row(left: View, right: View): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(right)
        }
        val enabledSwitch = Switch(this).apply {
            isChecked = simulatedReadingConfig.enabled
        }
        val dateButton = EditText(this).apply {
            inputType = InputType.TYPE_NULL
            isFocusable = false
            isClickable = true
            textSize = 18f
            setTextColor(currentMenuTextColor())
            gravity = Gravity.CENTER_VERTICAL
            fun updateDateText() {
                setText(simulatedReadingDateLabel(selectedStartEpochDay))
            }
            updateDateText()
            setOnClickListener {
                val date = runCatching { LocalDate.ofEpochDay(selectedStartEpochDay) }
                    .getOrDefault(LocalDate.now())
                DatePickerDialog(
                    this@LegadoReaderActivity,
                    { _, year, month, dayOfMonth ->
                        selectedStartEpochDay = LocalDate.of(year, month + 1, dayOfMonth).toEpochDay()
                        updateDateText()
                    },
                    date.year,
                    date.monthValue - 1,
                    date.dayOfMonth
                ).show()
            }
        }
        val startChapterInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            textSize = 18f
            setText(dialogStartChapter.toString())
            selectAll()
        }
        val dailyChaptersInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            textSize = 18f
            setText(simulatedReadingConfig.dailyChapters.coerceAtLeast(1).toString())
        }
        val numericRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            addView(label(readerString(R.string.reader_simulated_reading_start_chapter)))
            addView(startChapterInput, LinearLayout.LayoutParams(dp(72), dp(54)))
            addView(View(this@LegadoReaderActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(label(readerString(R.string.reader_simulated_reading_daily_chapters)))
            addView(dailyChaptersInput, LinearLayout.LayoutParams(dp(72), dp(54)))
        }
        container.addView(row(label(readerString(R.string.reader_simulated_reading_switch)), enabledSwitch))
        container.addView(row(label(readerString(R.string.reader_simulated_reading_start_date)), dateButton))
        container.addView(numericRow)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reader_simulated_reading_title)
            .setView(container)
            .setPositiveButton(android.R.string.yes) { _, _ ->
                val realChapterCount = document?.chapters?.size ?: Int.MAX_VALUE
                simulatedReadingConfig = SimulatedReadingConfig(
                    enabled = enabledSwitch.isChecked,
                    startEpochDay = selectedStartEpochDay,
                    startChapter = startChapterInput.text.toString().toIntOrNull()
                        ?.coerceIn(1, realChapterCount.coerceAtLeast(1))
                        ?: 1,
                    dailyChapters = dailyChaptersInput.text.toString().toIntOrNull()
                        ?.coerceAtLeast(1)
                        ?: 1
                )
                saveSimulatedReadingConfig(this, bookUri, simulatedReadingConfig)
                updateSimulatedUnlockedChapterCount(document)
                bindCatalogList()
                val anchor = currentPageAnchor()?.let { clampAnchorToUnlocked(it, document) }
                if (anchor != null && anchor.chapterIndex != currentPageAnchor()?.chapterIndex) {
                    showAnchorOrLoad(anchor = anchor, forward = false)
                } else {
                    renderCurrentPage()
                }
            }
            .setNegativeButton(android.R.string.no, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accentColor())
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accentColor())
    }

    private fun showEncodingMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 0, 0, R.string.reader_charset_auto)
            menu.add("UTF-8")
            menu.add("Shift_JIS")
            menu.add("GBK")
            menu.add("Big5")
            menu.add("UTF-16LE")
            setOnMenuItemClickListener { item ->
                preferredCharsetName = item.title.toString().takeUnless {
                    it == readerString(R.string.reader_charset_auto)
                }
                persistReaderSettings()
                document = null
                loadedDocumentBookUriText = null
                loadedDocumentCharsetName = null
                cueMatchesByCueIndex = emptyMap()
                matchData = null
                audioCueIndex = -1
                activeCueIndex = -1
                loadDisplayedBook(anchor = currentPageAnchor(), forceDocumentReload = true)
                true
            }
            show()
        }
    }

    private fun hasRubySpans(): Boolean {
        return document?.chapters?.any { chapter -> chapter.rubySpans.isNotEmpty() } == true
    }

    private fun text(value: String, sizeSp: Float, color: Int): TextView {
        return TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = true
        }
    }

    private fun currentReaderTitle(): String {
        return document?.title ?: importedBook?.title ?: LEGADO_READER_DEFAULT_TITLE
    }

    private fun currentBookUriText(): String? {
        return importedBook?.uri?.toString()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun refreshSimulatedReadingConfig() {
        simulatedReadingConfig = loadSimulatedReadingConfig(this, currentBookUriText())
    }

    private fun updateSimulatedUnlockedChapterCount(loaded: EbookDocument?) {
        simulatedUnlockedChapterCount = simulatedReadingUnlockedChapterCount(
            config = simulatedReadingConfig,
            realChapterCount = loaded?.chapters?.size ?: 0
        )
    }

    private fun unlockedChapterCount(loaded: EbookDocument? = document): Int {
        val realCount = loaded?.chapters?.size ?: return 0
        if (realCount <= 0) return 0
        val cached = simulatedUnlockedChapterCount.takeIf { it > 0 } ?: simulatedReadingUnlockedChapterCount(
            config = simulatedReadingConfig,
            realChapterCount = realCount
        )
        return cached.coerceIn(1, realCount)
    }

    private fun lastUnlockedChapterIndex(loaded: EbookDocument? = document): Int {
        return (unlockedChapterCount(loaded) - 1).coerceAtLeast(0)
    }

    private fun isChapterUnlocked(chapterIndex: Int, loaded: EbookDocument? = document): Boolean {
        val realCount = loaded?.chapters?.size ?: return true
        if (realCount <= 0) return true
        return chapterIndex <= lastUnlockedChapterIndex(loaded)
    }

    private fun clampChapterToUnlocked(chapterIndex: Int, loaded: EbookDocument? = document): Int {
        val realLastIndex = loaded?.chapters?.lastIndex ?: return chapterIndex.coerceAtLeast(0)
        return chapterIndex.coerceIn(0, lastUnlockedChapterIndex(loaded).coerceAtMost(realLastIndex))
    }

    private fun clampAnchorToUnlocked(anchor: ReaderPageAnchor, loaded: EbookDocument? = document): ReaderPageAnchor {
        val safeChapter = clampChapterToUnlocked(anchor.chapterIndex, loaded)
        return if (safeChapter == anchor.chapterIndex) {
            anchor
        } else {
            ReaderPageAnchor(safeChapter, 0)
        }
    }

    private fun showSimulatedReadingLockedToast() {
        if (simulatedReadingConfig.enabled) {
            Toast.makeText(this, R.string.reader_simulated_reading_locked, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPlayerFromReader() {
        val targetAudioUri = audioUri ?: run {
            Toast.makeText(this, R.string.reader_no_audio, Toast.LENGTH_SHORT).show()
            return
        }
        if (bridgeCanReturnToPlayer()) {
            returnToSharedPlayer()
            return
        }
        persistReaderSettingsWithCurrentAnchor("openPlayer")
        persistAudioPlaybackSnapshot()
        startPlayerActivity(targetAudioUri)
    }

    private fun startPlayerActivity(targetAudioUri: Uri) {
        val targetAudioUriText = targetAudioUri.toString()
        val reuseExistingPlayer = BookReaderActivity.hasActiveReaderForAudio(targetAudioUriText)
        BookReaderActivity.stopActiveReaderIfDifferentAudio(targetAudioUriText)
        val intent = Intent(this, BookReaderActivity::class.java).apply {
            if (reuseExistingPlayer) {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            putExtra(BookReaderActivity.EXTRA_BOOK_TITLE, currentReaderTitle())
            putExtra(BookReaderActivity.EXTRA_AUDIO_URI, targetAudioUriText)
            putExtra(BookReaderActivity.EXTRA_SRT_URI, srtUri?.toString())
            importedBook?.uri?.let { putExtra(BookReaderActivity.EXTRA_EBOOK_URI, it.toString()) }
            importedBook?.title?.let { putExtra(BookReaderActivity.EXTRA_EBOOK_NAME, it) }
            importedBook?.format?.let { putExtra(BookReaderActivity.EXTRA_EBOOK_FORMAT, it) }
        }
        suppressFloatingOverlayOnStop = true
        startActivity(intent)
    }

    private fun returnToSharedPlayer(): Boolean {
        val targetAudioUri = audioUri ?: return false
        if (activeAudioClipRangeMs() != null) {
            disableAudioCueLoop(updateUi = false)
        }
        persistReaderSettingsWithCurrentAnchor("returnToPlayer")
        persistAudioPlaybackSnapshot()
        startPlayerActivity(targetAudioUri)
        finish()
        return true
    }

    private fun returnToHome() {
        val targetAudioUri = audioUri
        if (activeAudioClipRangeMs() != null) {
            disableAudioCueLoop(updateUi = false)
        }
        val currentPlayer = player
        val currentPositionMs = currentAudioPositionMs() ?: 0L
        val currentDurationMs = currentAudioDurationMs() ?: pendingAudioRestoreDurationMs.coerceAtLeast(0L)
        persistAudioPlaybackSnapshot()
        persistReaderSettingsWithCurrentAnchor("returnToHome")
        logDebug(LEGADO_AUDIO_PROGRESS_LOG_TAG) {
            "returnHome positionMs=$currentPositionMs durationMs=$currentDurationMs audioUri=${targetAudioUri?.toString()?.take(80)}"
        }
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (targetAudioUri != null && currentDurationMs > 0L) {
                    putExtra(BookReaderActivity.EXTRA_RETURN_AUDIO_URI, targetAudioUri.toString())
                    putExtra(BookReaderActivity.EXTRA_RETURN_SRT_URI, srtUri?.toString())
                    putExtra(BookReaderActivity.EXTRA_RETURN_POSITION_MS, currentPositionMs)
                    putExtra(BookReaderActivity.EXTRA_RETURN_DURATION_MS, currentDurationMs)
                }
            }
        )
        finish()
    }

    private fun returnToPlayerIfShared(): Boolean {
        if (!returnToPlayerOnBack) return false
        if (!bridgeCanReturnToPlayer()) return false
        return returnToSharedPlayer()
    }

    private fun updateDisplayedBookTitle() {
        val title = currentReaderTitle()
        if (::toolbarTitleText.isInitialized) toolbarTitleText.text = title
        updateChapterTitleSurfaces()
        if (::readView.isInitialized) readView.setBookTitle(title)
    }

    private fun updateChapterTitleSurfaces(pageOverride: TextPage? = null) {
        val page = pageOverride ?: pages.getOrNull(pageIndex)
        val title = currentDisplayedChapterTitle(page)
        if (::readView.isInitialized) {
            readView.setDisplayedChapterTitle(title)
        }
        if (!::toolbarAdditionContainer.isInitialized) return
        val showTopBarAddition = isTopBarAdditionVisible()
        toolbarAdditionContainer.visibility = if (showTopBarAddition) View.VISIBLE else View.GONE
        if (!showTopBarAddition) return
        bindToolbarAdditionTip(
            toolbarAdditionLeftText,
            ReaderInfoSlot.TOP_BAR_LEFT,
            tipTopBarLeft,
            page,
            title
        )
        bindToolbarAdditionTip(
            toolbarAdditionMiddleText,
            ReaderInfoSlot.TOP_BAR_MIDDLE,
            tipTopBarMiddle,
            page,
            title
        )
        bindToolbarAdditionTip(
            toolbarAdditionRightText,
            ReaderInfoSlot.TOP_BAR_RIGHT,
            tipTopBarRight,
            page,
            title
        )
    }

    private fun isTopBarAdditionVisible(): Boolean {
        return when (topBarMode) {
            ReaderHeaderMode.SHOW -> true
            ReaderHeaderMode.HIDE -> false
            ReaderHeaderMode.HIDE_WHEN_STATUS_BAR_SHOW -> hideStatusBar
        }
    }

    private fun bindToolbarAdditionTip(
        view: TextView,
        slot: ReaderInfoSlot,
        content: ReaderTipContent,
        page: TextPage?,
        chapterTitle: String
    ) {
        if (view is ReaderBatteryTextView && ReaderTipFormatter.isBatteryGraphicTip(content) && page != null) {
            view.setBatteryValue(
                value = readerBatteryPercent(this),
                prefix = if (content == ReaderTipContent.TIME_BATTERY) readerClockText() else null
            )
        } else {
            val text = readerTipText(content, page, slot, chapterTitle)
            val displayText = if (content == ReaderTipContent.CHAPTER_TITLE) text.ifBlank { chapterTitle } else text
            if (view is ReaderBatteryTextView) {
                view.setPlainText(displayText)
            } else {
                view.text = displayText
            }
        }
        view.visibility = if (view.text.isNullOrBlank()) View.INVISIBLE else View.VISIBLE
        val clickable = ReaderTipFormatter.isProgressTip(content)
        view.isClickable = clickable
        view.setOnClickListener(if (clickable) View.OnClickListener { toggleReaderInfoProgressText(slot) } else null)
    }

    private fun readerTipText(
        content: ReaderTipContent,
        page: TextPage?,
        slot: ReaderInfoSlot,
        chapterTitle: String = currentDisplayedChapterTitle(page)
    ): String {
        return ReaderTipFormatter.text(
            content = content,
            page = page,
            alternateProgress = slot in readerInfoAlternateSlots,
            bookTitle = currentReaderTitle(),
            chapterTitle = chapterTitle,
            clockText = readerClockText(),
            batteryPercent = readerBatteryPercent(this)
        )
    }

    private fun readerInfoContent(slot: ReaderInfoSlot): ReaderTipContent = when (slot) {
        ReaderInfoSlot.TOP_BAR_LEFT -> tipTopBarLeft
        ReaderInfoSlot.TOP_BAR_MIDDLE -> tipTopBarMiddle
        ReaderInfoSlot.TOP_BAR_RIGHT -> tipTopBarRight
        ReaderInfoSlot.HEADER_LEFT -> tipHeaderLeft
        ReaderInfoSlot.HEADER_MIDDLE -> tipHeaderMiddle
        ReaderInfoSlot.HEADER_RIGHT -> tipHeaderRight
        ReaderInfoSlot.FOOTER_LEFT -> tipFooterLeft
        ReaderInfoSlot.FOOTER_MIDDLE -> tipFooterMiddle
        ReaderInfoSlot.FOOTER_RIGHT -> tipFooterRight
    }

    private fun validReaderInfoAlternateSlots(): Set<ReaderInfoSlot> {
        return readerInfoAlternateSlots.filterTo(mutableSetOf()) { slot ->
            ReaderTipFormatter.isProgressTip(readerInfoContent(slot))
        }
    }

    private fun pruneReaderInfoAlternateSlots() {
        readerInfoAlternateSlots.retainAll(validReaderInfoAlternateSlots())
    }

    private fun toggleReaderInfoProgressText(slot: ReaderInfoSlot) {
        if (!readerInfoAlternateSlots.add(slot)) {
            readerInfoAlternateSlots.remove(slot)
        }
        applyReaderInfoConfig()
        updateChapterTitleSurfaces()
        persistReaderSettings(updateAnchor = false)
    }

    private fun currentDisplayedChapterTitle(page: TextPage? = pages.getOrNull(pageIndex)): String {
        return page?.title?.ifBlank { currentReaderTitle() } ?: currentReaderTitle()
    }

    private fun toggleNightMode() {
        readerNightMode = !readerNightMode
        applySelectedReaderStyleFields()
        closeReaderChrome()
        applyReaderVisualStyle()
        persistReaderSettings(updateAnchor = false)
    }

    private fun startSearch(query: String) {
        if (query.isBlank()) {
            Toast.makeText(this, R.string.reader_search_input_required, Toast.LENGTH_SHORT).show()
            return
        }
        val lowerQuery = query.lowercase()
        val maxChapterCount = unlockedChapterCount(document)
        val hits = buildList {
            document?.chapters?.take(maxChapterCount)?.forEachIndexed { chapterIndex, chapter ->
                val pageText = chapter.text
                val lowerPageText = pageText.lowercase()
                var fromIndex = 0
                while (true) {
                    val hitIndex = lowerPageText.indexOf(lowerQuery, fromIndex)
                    if (hitIndex < 0) break
                    val previewStart = (hitIndex - 12).coerceAtLeast(0)
                    val previewEnd = (hitIndex + query.length + 20).coerceAtMost(pageText.length)
                    add(
                        ReaderSearchHit(
                            chapterIndex = chapterIndex,
                            chapterTitle = chapter.title,
                            preview = pageText.substring(previewStart, previewEnd).replace('\n', ' '),
                            chapterPosition = hitIndex,
                            query = query
                        )
                    )
                    fromIndex = hitIndex + query.length
                }
            }
        }
        searchQuery = query
        if (hits.isEmpty()) {
            searchHits = emptyList()
            searchHitIndex = -1
            bindSearchResultList()
            updateSearchInfo()
            Toast.makeText(this, R.string.reader_search_no_results, Toast.LENGTH_SHORT).show()
            return
        }
        searchHits = hits
        searchHitIndex = -1
        bindSearchResultList()
        updateSearchInfo()
    }

    private fun navigateSearch(delta: Int) {
        if (searchHits.isEmpty()) return
        val startIndex = searchHitIndex.takeIf { it >= 0 } ?: if (delta >= 0) -1 else searchHits.size
        searchHitIndex = (startIndex + delta).let { value ->
            when {
                value < 0 -> searchHits.lastIndex
                value > searchHits.lastIndex -> 0
                else -> value
            }
        }
        navigateToSearchHit(searchHitIndex, showSearchMenu = true)
    }

    private fun updateSearchInfo() {
        if (::searchMenu.isInitialized) {
            val current = searchHits.getOrNull(searchHitIndex)
            val chapterTitle = current?.chapterTitle ?: pages.getOrNull(pageIndex)?.title ?: currentReaderTitle()
            val currentIndex = if (searchHitIndex >= 0) searchHitIndex + 1 else 0
            searchMenu.updateInfo(
                getString(
                    R.string.reader_search_result_current,
                    currentIndex,
                    searchHits.size,
                    chapterTitle
                )
            )
        }
        if (::searchResultInfoView.isInitialized) {
            searchResultInfoView.text = if (searchHits.isEmpty()) {
                readerString(R.string.reader_search_result_none)
            } else {
                getString(R.string.reader_search_result_total, searchHits.size)
            }
        }
    }

    private fun hideSearchMenu() {
        if (::searchMenu.isInitialized) searchMenu.hideMenu()
        updatePlaybackBarVisibility()
    }

    /**
     * 是否处于「搜寻模式」：出过搜索结果、直到按退出为止（与 legado 的 isShowingSearchResult 对应）。
     * 这期间点屏幕应该回到**搜寻菜单**（结果/主菜单/退出），而不是阅读主菜单。
     */
    private fun isSearchModeActive(): Boolean {
        return searchHits.isNotEmpty() || isSearchMenuVisible()
    }

    private fun showSearchMenu() {
        if (!::searchMenu.isInitialized) return
        setReadMenuVisible(false, updateSystemBars = false)
        updateSearchInfo()
        searchMenu.showMenu()
        // 播放栏是本 diff 后加入的视图、绘制在搜寻菜单之上；搜寻期间把它藏起来，
        // 免得压住底下「结果 / 主菜单 / 退出」那排按钮（关闭搜寻菜单后原样恢复）。
        updatePlaybackBarVisibility()
    }

    private fun isSearchMenuVisible(): Boolean {
        return ::searchMenu.isInitialized && searchMenu.visibility == View.VISIBLE
    }

    /** 播放栏是否该显示：钉住且没有别的下方菜单需要这块地方。 */
    private fun updatePlaybackBarVisibility() {
        if (!::playbackBar.isInitialized) return
        val target = if (playbackBarPinnedVisible && !isSearchMenuVisible()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        if (playbackBar.visibility != target) playbackBar.visibility = target
    }

    private fun showSearchPanel(initialQuery: String = searchQuery.orEmpty()) {
        if (!::searchPanel.isInitialized) return
        hideSearchMenu()
        audioControlPanel.visibility = View.GONE
        moreSettingsPanel.visibility = View.GONE
        catalogPanel.visibility = View.GONE
        setReadMenuVisible(false, updateSystemBars = false)
        searchPanel.visibility = View.VISIBLE
        searchInputView.setText(initialQuery)
        searchInputView.setSelection(searchInputView.text.length)
        bindSearchResultList()
        updateSearchInfo()
        updateSystemBarSurfaces()
    }

    private fun hideSearchPanel() {
        if (::searchPanel.isInitialized) {
            hideSoftKeyboard()
            searchPanel.visibility = View.GONE
            updateSystemBarSurfaces()
        }
    }

    /** 收起输入法：搜寻提交后、点结果跳转后、关闭搜寻面板时都要收。 */
    private fun hideSoftKeyboard() {
        val inputMethodManager =
            getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val token = (currentFocus
            ?: searchPanel.takeIf { ::searchPanel.isInitialized })?.windowToken ?: return
        inputMethodManager.hideSoftInputFromWindow(token, 0)
    }

    private fun performSearchFromPanel() {
        val query = searchInputView.text.toString().trim()
        // 提交后收起输入法，让搜索结果列表直接可见（结果多时不必再手动收键盘）
        hideSoftKeyboard()
        startSearch(query)
    }

    private fun bindSearchResultList() {
        if (!::searchResultListView.isInitialized) return
        searchResultListView.adapter = object : ArrayAdapter<ReaderSearchHit>(
            this,
            android.R.layout.simple_list_item_1,
            searchHits
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = (convertView as? TextView) ?: TextView(context).apply {
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    textSize = 15f
                }
                val item = getItem(position)
                view.text = "${position + 1}. ${item?.chapterTitle.orEmpty()}\n${item?.preview.orEmpty()}"
                view.setTextColor(if (position == searchHitIndex) accentColor() else currentMenuTextColor())
                return view
            }
        }
        searchResultListView.setOnItemClickListener { _, _, position, _ ->
            navigateToSearchHit(position, showSearchMenu = true)
        }
    }

    private fun hideCatalogPanel() {
        if (::catalogPanel.isInitialized) {
            catalogPanel.visibility = View.GONE
            updateSystemBarSurfaces()
        }
    }

    private fun showChapterListDialog() {
        val chapters = document?.chapters.orEmpty()
        if (chapters.isEmpty() && !useM4bChapterSource()) {
            Toast.makeText(this, R.string.reader_catalog_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        catalogMode = CatalogMode.CHAPTERS
        catalogFilterQuery = ""
        audioControlPanel.visibility = View.GONE
        moreSettingsPanel.visibility = View.GONE
        searchPanel.visibility = View.GONE
        setReadMenuVisible(false, updateSystemBars = false)
        catalogPanel.visibility = View.VISIBLE
        catalogSearchInputView.setText("")
        bindCatalogList()
        updateSystemBarSurfaces()
    }

    private fun navigateToSearchHit(index: Int, showSearchMenu: Boolean) {
        val hit = searchHits.getOrNull(index) ?: return
        searchHitIndex = index
        activeCueIndex = -1
        showAnchorOrLoad(
            anchor = ReaderPageAnchor(hit.chapterIndex, hit.chapterPosition),
            forward = hit.chapterIndex >= (pages.getOrNull(pageIndex)?.chapterIndex ?: 0),
            keepSearchHit = true
        )
        updateSearchInfo()
        hideSearchPanel()
        if (showSearchMenu) {
            showSearchMenu()
        }
    }

    private fun bindCatalogList() {
        if (!::catalogListView.isInitialized) return
        updateCatalogTabs()
        when (catalogMode) {
            CatalogMode.CHAPTERS -> bindChapterCatalogList()
            CatalogMode.BOOKMARKS -> bindBookmarkCatalogList()
        }
    }

    private fun updateCatalogTabs() {
        if (!::catalogTitleView.isInitialized) return
        catalogTitleView.text =
            if (catalogMode == CatalogMode.CHAPTERS) {
                readerString(R.string.reader_catalog_title_chapters)
            } else {
                readerString(R.string.reader_catalog_title_bookmarks)
            }
        val activeBg = accentColor()
        val inactiveBg = 0x00000000
        val activeText = if (isNightReaderTheme()) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
        val inactiveText = currentMenuTextColor()
        catalogTabChaptersView.backgroundTintList = ColorStateList.valueOf(if (catalogMode == CatalogMode.CHAPTERS) activeBg else inactiveBg)
        catalogTabBookmarksView.backgroundTintList = ColorStateList.valueOf(if (catalogMode == CatalogMode.BOOKMARKS) activeBg else inactiveBg)
        catalogTabChaptersView.setTextColor(if (catalogMode == CatalogMode.CHAPTERS) activeText else inactiveText)
        catalogTabBookmarksView.setTextColor(if (catalogMode == CatalogMode.BOOKMARKS) activeText else inactiveText)
        catalogSearchInputView.hint =
            if (catalogMode == CatalogMode.CHAPTERS) {
                readerString(R.string.reader_catalog_search_chapters)
            } else {
                readerString(R.string.reader_catalog_search_bookmarks)
            }
    }

    private fun bindChapterCatalogList() {
        if (useM4bChapterSource()) {
            bindM4bChapterCatalogList()
            return
        }
        val chapters = document?.chapters.orEmpty()
        val currentChapterIndex = pages.getOrNull(pageIndex)?.chapterIndex ?: 0
        val visibleChapters = chapters.take(unlockedChapterCount(document))
        val filtered = visibleChapters.mapIndexed { index, chapter -> index to chapter }.filter { (_, chapter) ->
            val query = catalogFilterQuery.trim()
            query.isBlank() || chapter.title.contains(query, ignoreCase = true)
        }
        catalogListView.adapter = object : ArrayAdapter<Pair<Int, EbookChapter>>(
            this,
            android.R.layout.simple_list_item_1,
            filtered
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = (convertView as? TextView) ?: TextView(context).apply {
                    textSize = 16f
                }
                val item = getItem(position)
                val chapterIndex = item?.first ?: position
                val chapter = item?.second
                val chapterTitle = chapter?.title.orEmpty()
                view.text = chapterTitle
                // 目录层级：按 level 左缩进（convertView 会复用，所以每行都要重设 padding）
                val level = (chapter?.level ?: 0).coerceIn(0, CATALOG_MAX_INDENT_LEVEL)
                view.setPadding(dp(16 + level * CATALOG_INDENT_STEP_DP), dp(14), dp(16), dp(14))
                // 卷/部分章节（"第一部分 xxx"等）和**大章节**（目录条目下面还有子条目）灰底分组显示，
                // 与参考实现 legado 的 ChapterListAdapter 一致（btn_bg_press = #63ACACAC）；
                // 当前章高亮（accent 色）优先。
                val isVolume = chapter?.isVolume == true || chapter?.isGroup == true
                view.setTypeface(null, Typeface.NORMAL)
                view.setBackgroundColor(
                    if (isVolume) 0x63ACACAC.toInt() else Color.TRANSPARENT
                )
                view.setTextColor(
                    if (chapterIndex == currentChapterIndex) accentColor() else currentMenuTextColor()
                )
                return view
            }
        }
        catalogListView.setOnItemClickListener { _, _, which, _ ->
            val chapterIndex = filtered.getOrNull(which)?.first ?: return@setOnItemClickListener
            showAnchorOrLoad(
                anchor = ReaderPageAnchor(chapterIndex, 0),
                forward = chapterIndex >= currentChapterIndex
            )
            hideCatalogPanel()
        }
        val selection = filtered.indexOfFirst { it.first == currentChapterIndex }.coerceAtLeast(0)
        catalogListView.post { catalogListView.setSelection(selection) }
    }

    private fun bindM4bChapterCatalogList() {
        val chapters = m4bChapters
        if (chapters.isEmpty()) {
            Toast.makeText(this, R.string.reader_chapter_source_m4b_unavailable, Toast.LENGTH_SHORT).show()
            chapterSourceMode = ReaderChapterSourceMode.BOOK
            updateChapterTitleSurfaces()
            if (::moreSettingsPanel.isInitialized) moreSettingsPanel.bind(currentMoreConfigState())
            bindChapterCatalogList()
            return
        }
        val currentChapterIndex = currentM4bChapterIndex()
        val filtered = chapters.mapIndexed { index, chapter -> index to chapter }.filter { (_, chapter) ->
            val query = catalogFilterQuery.trim()
            query.isBlank() || chapter.title.contains(query, ignoreCase = true)
        }
        catalogListView.adapter = object : ArrayAdapter<Pair<Int, M4bChapter>>(
            this,
            android.R.layout.simple_list_item_1,
            filtered
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = (convertView as? TextView) ?: TextView(context).apply {
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    textSize = 16f
                }
                val item = getItem(position)
                val chapterIndex = item?.first ?: position
                val chapterTitle = item?.second?.title.orEmpty()
                view.text = chapterTitle
                view.setTextColor(if (chapterIndex == currentChapterIndex) accentColor() else currentMenuTextColor())
                return view
            }
        }
        catalogListView.setOnItemClickListener { _, _, which, _ ->
            val chapterIndex = filtered.getOrNull(which)?.first ?: return@setOnItemClickListener
            seekToM4bChapter(chapterIndex)
            hideCatalogPanel()
        }
        val selection = filtered.indexOfFirst { it.first == currentChapterIndex }.coerceAtLeast(0)
        catalogListView.post { catalogListView.setSelection(selection) }
    }

    private fun bindBookmarkCatalogList() {
        val query = catalogFilterQuery.trim()
        val filtered = bookmarks.filter { bookmark ->
            isChapterUnlocked(bookmark.chapterIndex) && (
                query.isBlank() ||
                bookmark.chapterTitle.contains(query, ignoreCase = true) ||
                bookmark.excerpt.contains(query, ignoreCase = true) ||
                bookmark.note.contains(query, ignoreCase = true)
            )
        }
        catalogListView.adapter = object : ArrayAdapter<ReaderBookmark>(
            this,
            android.R.layout.simple_list_item_1,
            filtered
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = (convertView as? TextView) ?: TextView(context).apply {
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    textSize = 15f
                }
                val item = getItem(position)
                view.text = buildString {
                    append(item?.chapterTitle ?: readerString(R.string.reader_unknown_chapter))
                    append('\n')
                    append(bookmarkListPreview(item))
                }
                view.setTextColor(currentMenuTextColor())
                return view
            }
        }
        catalogListView.setOnItemClickListener { _, _, which, _ ->
            val bookmark = filtered.getOrNull(which) ?: return@setOnItemClickListener
            showAnchorOrLoad(
                anchor = ReaderPageAnchor(bookmark.chapterIndex, bookmark.chapterPosition),
                forward = bookmark.chapterIndex >= (pages.getOrNull(pageIndex)?.chapterIndex ?: 0)
            )
            hideCatalogPanel()
        }
        catalogListView.setOnItemLongClickListener { _, _, which, _ ->
            val bookmark = filtered.getOrNull(which) ?: return@setOnItemLongClickListener true
            showBookmarkEditor(bookmark, isNew = false)
            true
        }
    }

    private fun bookmarkListPreview(bookmark: ReaderBookmark?): String {
        bookmark ?: return ""
        return buildString {
            append(bookmark.excerpt)
            if (bookmark.note.isNotBlank()) {
                append('\n')
                append(readerString(R.string.reader_bookmark_note_prefix))
                append(bookmark.note)
            }
        }
    }

    private fun findPageIndexForChapterPosition(chapterIndex: Int, chapterPosition: Int): Int {
        return pages.indexOfFirst { it.chapterIndex == chapterIndex && it.containPos(chapterPosition) }
            .takeIf { it >= 0 }
            ?: pages.indexOfFirst { it.chapterIndex == chapterIndex && chapterPosition <= it.charStart }
                .takeIf { it >= 0 }
            ?: pages.indexOfLast { it.chapterIndex == chapterIndex }
    }

    private fun pagePreviewForDelta(delta: Int): TextPage? {
        if (delta == 0 || pages.isEmpty()) return pages.getOrNull(pageIndex)
        pages.getOrNull(pageIndex + delta)?.let { return it }
        val currentPage = pages.getOrNull(pageIndex) ?: return null
        val targetChapter = currentPage.chapterIndex + delta.coerceIn(-1, 1)
        val loaded = document ?: return null
        if (targetChapter !in loaded.chapters.indices) return null
        if (!isChapterUnlocked(targetChapter, loaded)) return null
        val cacheKey = currentChapterPageCacheKey(targetChapter)
        val cachedPages = chapterPageCache[cacheKey].orEmpty()
        return if (delta > 0) cachedPages.firstOrNull() else cachedPages.lastOrNull()
    }

    private fun showAnchorOrLoad(
        anchor: ReaderPageAnchor,
        forward: Boolean = true,
        keepSearchHit: Boolean = false,
        persistAnchor: Boolean = true
    ) {
        val safeAnchor = clampAnchorToUnlocked(anchor, document)
        if (safeAnchor.chapterIndex != anchor.chapterIndex) {
            showSimulatedReadingLockedToast()
        }
        val next = findPageIndexForChapterPosition(safeAnchor.chapterIndex, safeAnchor.charPosition)
        if (!keepSearchHit) searchHitIndex = -1
        activeCueIndex = -1
        if (next >= 0) {
            pageIndex = next
            renderCurrentPage(
                forward = forward,
                persistAnchor = persistAnchor
            )
        } else {
            loadDisplayedBook(anchor = safeAnchor, forceDocumentReload = false)
            if (persistAnchor) {
                persistReaderAnchor(safeAnchor)
            }
        }
    }

    private fun showStyleDialog() {
        ReadStyleDialog(
            activity = this,
            state = currentReadStyleState(),
            callback = object : ReadStyleDialog.Callback {
                override fun onTextSizeChanged(valueSp: Int) {
                    readerTextSizeSp = valueSp
                    readView.setTextSizeSp(valueSp.toFloat())
                    updateSelectedReaderStyleLayoutFields()
                }

                override fun onLetterSpacingChanged(value: Int) {
                    readerLetterSpacingDp = value
                    updateSelectedReaderStyleLayoutFields()
                }

                override fun onLineSpacingChanged(valueDp: Int) {
                    readerLineSpacingDp = valueDp
                    updateSelectedReaderStyleLayoutFields()
                }

                override fun onParagraphSpacingChanged(valueDp: Int) {
                    readerParagraphSpacingDp = valueDp
                    updateSelectedReaderStyleLayoutFields()
                }

                override fun onTextSizeChangeFinished(valueSp: Int) {
                    requestBookRelayout(immediate = true)
                }

                override fun onLetterSpacingChangeFinished(value: Int) {
                    requestBookRelayout(immediate = true)
                }

                override fun onLineSpacingChangeFinished(valueDp: Int) {
                    requestBookRelayout(immediate = true)
                }

                override fun onParagraphSpacingChangeFinished(valueDp: Int) {
                    requestBookRelayout(immediate = true)
                }

                override fun onInfoClicked() {
                    showTipConfigDialog()
                }

                override fun onWeightClicked(onChanged: (M9TextWeight) -> Unit) {
                    showTextWeightDialog(onChanged)
                }

                override fun onFontClicked() {
                    showFontDialog()
                }

                override fun onIndentClicked() {
                    showIndentDialog()
                }

                override fun onPaddingClicked() {
                    showPaddingDialog()
                }

                override fun onTipClicked() {
                    showTipConfigDialog()
                }

                override fun onPageAnimClicked(animIndex: Int) {
                    readerPageAnim = when (animIndex) {
                        0 -> M9PageAnim.COVER
                        1 -> M9PageAnim.SLIDE
                        2 -> M9PageAnim.SIMULATION
                        3 -> M9PageAnim.SCROLL
                        else -> M9PageAnim.NONE
                    }
                    readView.setPageAnim(readerPageAnim)
                    persistReaderSettings()
                }

                override fun onBackgroundClicked(index: Int): ReadStyleState {
                    selectReaderStyle(index)
                    return currentReadStyleState()
                }

                override fun onBackgroundLongClicked(index: Int) {
                    selectReaderStyle(index)
                    showReaderStyleConfigDialog(index)
                }

                override fun onBackgroundAddClicked() {
                    readerStyleConfigs.add(defaultReaderStyleConfig().copy(name = "Text"))
                    val index = readerStyleConfigs.lastIndex
                    selectReaderStyle(index)
                    showReaderStyleConfigDialog(index)
                }
            }
        ).show()
    }

    private fun currentReadStyleState(): ReadStyleState {
        return ReadStyleState(
            textSizeSp = readerTextSizeSp,
            letterSpacingDp = readerLetterSpacingDp,
            lineSpacingDp = readerLineSpacingDp,
            paragraphSpacingDp = readerParagraphSpacingDp,
            textWeight = readerTextWeight,
            backgroundStyleIndex = readerStyleSelect.coerceIn(0, readerStyleConfigs.lastIndex),
            backgroundStyles = readerStyleConfigs.map {
                ReadStyleColorItem(
                    name = it.name,
                    bgColor = it.bgColor,
                    textColor = it.textColor,
                    tipColor = it.tipColor,
                    bgAlpha = it.bgAlpha,
                    bgAssetName = it.bgAssetName,
                    bgImageUri = it.bgImageUri
                )
            },
            pageAnim = readerPageAnim
        )
    }

    private fun selectReaderStyle(index: Int) {
        val style = readerStyleConfigs.getOrNull(index) ?: return
        val layoutChanged = styleReaderLayoutDiffers(style)
        readerStyleSelect = index
        saveLegadoReaderBookStyleSelect(this, importedBook?.uri?.toString(), index)
        applySelectedReaderStyleFields()
        applyReaderTypography()
        applyReaderVisualStyle()
        if (layoutChanged) {
            if (::readView.isInitialized) {
                readView.setLayoutMode(readerLayoutMode)
                applyDirectionSettings()
            }
            requestBookRelayout(immediate = true)
        } else {
            persistReaderSettings()
        }
    }

    private fun applySelectedReaderStyleFields() {
        val style = readerStyleConfigs.getOrNull(readerStyleSelect) ?: defaultReaderStyleConfig()
        readerTextSizeSp = style.textSizeSp
        readerLineSpacingDp = style.lineSpacingDp
        readerParagraphSpacingDp = style.paragraphSpacingDp
        readerLetterSpacingDp = style.letterSpacingDp
        readerTextWeight = style.textWeight
        readerTypefaceIndex = style.typefaceIndex
        readerTypeface = readerTypefaceForIndex(readerTypefaceIndex)
        readerParagraphIndentCount = style.paragraphIndentCount
        readerLayoutMode = style.layoutMode
        readerPaddingDp = style.paddingDp
        readerPaddingLeftDp = style.paddingDp
        readerPaddingRightDp = style.paddingDp
        readerBgColor = style.bgColor
        readerTextColor = style.textColor
        readerTipColor = style.tipColor
        readerBgAlpha = style.bgAlpha
        readerDarkStatusIcon = style.darkStatusIcon
        readerUnderline = style.underline
        readerBgAssetName = style.bgAssetName
        readerBgImageUri = style.bgImageUri
        if (readerNightMode) {
            readerBgColor = 0xFF1F1F1F.toInt()
            readerTextColor = 0xFFD8D2C5.toInt()
            readerTipColor = 0xFF948B7D.toInt()
            readerBgAlpha = 100
            readerDarkStatusIcon = false
            readerBgAssetName = null
            readerBgImageUri = null
        }
    }

    private fun defaultReaderStyleConfig(): LegadoReaderStyleConfig {
        val defaults = defaultLegadoReaderStyleConfigs()
        return defaults.getOrElse(DEFAULT_LEGADO_READER_STYLE_INDEX) { defaults.first() }
    }

    private fun styleReaderLayoutDiffers(style: LegadoReaderStyleConfig): Boolean {
        return readerTextSizeSp != style.textSizeSp ||
            readerLineSpacingDp != style.lineSpacingDp ||
            readerParagraphSpacingDp != style.paragraphSpacingDp ||
            readerLetterSpacingDp != style.letterSpacingDp ||
            readerTextWeight != style.textWeight ||
            readerTypefaceIndex != style.typefaceIndex ||
            readerParagraphIndentCount != style.paragraphIndentCount ||
            readerLayoutMode != style.layoutMode ||
            readerPaddingDp != style.paddingDp
    }

    private fun updateSelectedReaderStyleLayoutFields() {
        val style = readerStyleConfigs.getOrNull(readerStyleSelect) ?: return
        readerStyleConfigs[readerStyleSelect] = style.copy(
            textSizeSp = readerTextSizeSp,
            lineSpacingDp = readerLineSpacingDp,
            paragraphSpacingDp = readerParagraphSpacingDp,
            letterSpacingDp = readerLetterSpacingDp,
            textWeight = readerTextWeight,
            typefaceIndex = readerTypefaceIndex,
            paragraphIndentCount = readerParagraphIndentCount,
            layoutMode = readerLayoutMode,
            paddingDp = readerPaddingDp
        )
    }

    private fun applyReaderTypography() {
        if (!::readView.isInitialized) return
        readView.setTextSizeSp(readerTextSizeSp.toFloat())
        readView.setTextWeight(readerTextWeight)
        readView.setReaderTypeface(readerTypeface)
        readView.setReaderPadding(
            dp(readerPaddingLeftDp),
            dp(readerPaddingTopDp),
            dp(readerPaddingRightDp),
            currentReaderBottomPaddingPx()
        )
    }

    private fun readerTypefaceForIndex(index: Int): Typeface {
        return when (index) {
            1 -> Typeface.SERIF
            2 -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }
    }

    private fun applyReaderVisualStyle() {
        if (::readerRoot.isInitialized) {
            readerRoot.setBackgroundColor(readerBgColor)
        }
        readView.setReaderColors(
            readerBgColor,
            readerTextColor,
            effectiveReaderTipColor(),
            readerBgAssetName,
            readerBgImageUri,
            readerBgAlpha
        )
        readView.setCueHighlightColor(readerCueHighlightColor)
        readView.setTextUnderline(readerUnderline && readerLayoutMode == M9LayoutMode.HORIZONTAL)
        applyReaderInfoConfig()
        applyReadBarStyle()
    }

    private fun effectiveReaderTipColor(): Int {
        return when (tipColorMode) {
            ReaderTipColorMode.FOLLOW_CONTENT -> readerTextColor
            ReaderTipColorMode.CUSTOM -> readerTipColor
        }
    }

    private fun effectiveReaderTipDividerColor(): Int? {
        return when (tipDividerColorMode) {
            ReaderTipDividerColorMode.DEFAULT -> (effectiveReaderTipColor() and 0x00FFFFFF) or 0x33000000
            ReaderTipDividerColorMode.FOLLOW_CONTENT -> effectiveReaderTipColor()
            ReaderTipDividerColorMode.CUSTOM -> tipDividerColor
        }
    }

    private fun applyReaderInfoConfig() {
        if (!::readView.isInitialized) return
        readView.setReaderInfoConfig(
            bodyTitleMode = bodyTitleMode,
            bodyTitleSizeAddSp = bodyTitleSizeAddSp,
            bodyTitleTopSpacingDp = bodyTitleTopSpacingDp,
            bodyTitleBottomSpacingDp = bodyTitleBottomSpacingDp,
            headerMode = headerMode,
            footerMode = footerMode,
            headerLeft = tipHeaderLeft,
            headerMiddle = tipHeaderMiddle,
            headerRight = tipHeaderRight,
            footerLeft = tipFooterLeft,
            footerMiddle = tipFooterMiddle,
            footerRight = tipFooterRight,
            statusBarHidden = hideStatusBar,
            dividerColor = effectiveReaderTipDividerColor(),
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
            alternateInfoSlots = validReaderInfoAlternateSlots()
        )
    }

    private fun showReaderStyleConfigDialog(index: Int) {
        val current = readerStyleConfigs.getOrNull(index) ?: return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            setBackgroundColor(readerBgColor)
        }
        var selectedBgColor = current.bgColor
        var selectedTextColor = current.textColor
        var selectedTipColor = current.tipColor
        var selectedBgAssetName = current.bgAssetName
        var selectedBgImageUri = current.bgImageUri
        val nameInput = addTextInput(container, R.string.reader_style_name, current.name)
        val restoreView = addActionText(container, R.string.reader_style_restore)
        val darkStatusIconCheck = addCheckBox(container, R.string.reader_dark_status_icon, current.darkStatusIcon)
        val underlineCheck = addCheckBox(container, R.string.reader_text_underline, current.underline)
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val textColorButton = styleConfigButton(R.string.reader_style_text_color, selectedTextColor)
        val bgColorButton = styleConfigButton(R.string.reader_style_bg_color, selectedBgColor)
        colorRow.addView(textColorButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginEnd = dp(8)
        })
        colorRow.addView(bgColorButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        container.addView(colorRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        val alphaLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(readerTipColor)
        }
        val alphaSeek = SeekBar(this).apply {
            max = 100
            progress = current.bgAlpha.coerceIn(0, 100)
        }
        fun updateAlphaLabel() {
            alphaLabel.text = getString(R.string.reader_bg_alpha, alphaSeek.progress)
        }
        updateAlphaLabel()
        container.addView(alphaLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        container.addView(alphaSeek, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        fun persistDraftStyle() {
            val previous = readerStyleConfigs.getOrNull(index) ?: current
            readerStyleConfigs[index] = previous.copy(
                name = nameInput.text.toString().trim(),
                bgColor = selectedBgColor,
                textColor = selectedTextColor,
                tipColor = selectedTipColor,
                bgAlpha = alphaSeek.progress,
                darkStatusIcon = darkStatusIconCheck.isChecked,
                underline = underlineCheck.isChecked,
                bgAssetName = selectedBgAssetName,
                bgImageUri = selectedBgImageUri
            )
            selectReaderStyle(index)
        }
        val updateBgImageSelection = addBackgroundImagePicker(
            container = container,
            selectedAssetName = selectedBgAssetName,
            selectedImageUri = selectedBgImageUri,
            onSelected = { nextAssetName, nextImageUri ->
                selectedBgAssetName = nextAssetName
                selectedBgImageUri = nextImageUri
                persistDraftStyle()
            },
            onPickExternal = {
                persistDraftStyle()
                pendingReaderStyleImageIndex = index
                readerStyleImagePicker.launch(arrayOf("image/*"))
            }
        )
        textColorButton.setOnClickListener {
            showReaderColorPicker(READER_TEXT_COLOR_DIALOG_ID, selectedTextColor) { color ->
                selectedTextColor = color
                bindStyleConfigButton(textColorButton, R.string.reader_style_text_color, selectedTextColor)
                persistDraftStyle()
            }
        }
        bgColorButton.setOnClickListener {
            val pickerColor = if (selectedBgAssetName == null && selectedBgImageUri == null) {
                selectedBgColor
            } else {
                LEGADO_COLOR_PICKER_IMAGE_BG_FALLBACK
            }
            showReaderColorPicker(READER_BG_COLOR_DIALOG_ID, pickerColor) { color ->
                selectedBgColor = color
                selectedBgAssetName = null
                selectedBgImageUri = null
                bindStyleConfigButton(bgColorButton, R.string.reader_style_bg_color, selectedBgColor)
                updateBgImageSelection(selectedBgAssetName, selectedBgImageUri)
                persistDraftStyle()
            }
        }
        darkStatusIconCheck.setOnCheckedChangeListener { _, _ -> persistDraftStyle() }
        underlineCheck.setOnCheckedChangeListener { _, _ -> persistDraftStyle() }
        alphaSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateAlphaLabel()
                if (fromUser) persistDraftStyle()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        restoreView.setOnClickListener {
            val defaults = defaultLegadoReaderStyleConfigs()
            val restoreDialog = AlertDialog.Builder(this)
                .setTitle(R.string.reader_style_restore)
                .setItems(defaults.map { it.name }.toTypedArray()) { dialog, which ->
                    val restored = defaults[which]
                    nameInput.setText(restored.name)
                    selectedBgColor = restored.bgColor
                    selectedTextColor = restored.textColor
                    selectedTipColor = restored.tipColor
                    bindStyleConfigButton(bgColorButton, R.string.reader_style_bg_color, selectedBgColor)
                    bindStyleConfigButton(textColorButton, R.string.reader_style_text_color, selectedTextColor)
                    alphaSeek.progress = restored.bgAlpha
                    darkStatusIconCheck.isChecked = restored.darkStatusIcon
                    underlineCheck.isChecked = restored.underline
                    selectedBgAssetName = restored.bgAssetName
                    selectedBgImageUri = restored.bgImageUri
                    updateBgImageSelection(selectedBgAssetName, selectedBgImageUri)
                    readerStyleConfigs[index] = restored
                    selectReaderStyle(index)
                    dialog.dismiss()
                }
                .create()
            restoreDialog.setOnShowListener {
                restoreDialog.window?.run {
                    clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    val attr = attributes
                    attr.dimAmount = 0f
                    attributes = attr
                }
            }
            restoreDialog.show()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reader_style_config_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.reader_style_delete, null)
            .setPositiveButton(R.string.reader_dialog_done, null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.run {
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setBackgroundDrawable(ColorDrawable(readerBgColor))
                val attr = attributes
                attr.dimAmount = 0f
                attr.gravity = Gravity.BOTTOM
                attributes = attr
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).apply {
                isEnabled = readerStyleConfigs.size > 1
                setOnClickListener {
                    if (readerStyleConfigs.size <= 1) return@setOnClickListener
                    readerStyleConfigs.removeAt(index)
                    readerStyleSelect = readerStyleSelect.coerceAtMost(readerStyleConfigs.lastIndex)
                    readerStyleSelectDefault = readerStyleSelectDefault.coerceAtMost(readerStyleConfigs.lastIndex)
                    selectReaderStyle(readerStyleSelect)
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val previous = readerStyleConfigs.getOrNull(index) ?: current
                readerStyleConfigs[index] = previous.copy(
                    name = nameInput.text.toString().trim(),
                    bgColor = selectedBgColor,
                    textColor = selectedTextColor,
                    tipColor = selectedTipColor,
                    bgAlpha = alphaSeek.progress,
                    darkStatusIcon = darkStatusIconCheck.isChecked,
                    underline = underlineCheck.isChecked,
                    bgAssetName = selectedBgAssetName,
                    bgImageUri = selectedBgImageUri
                )
                selectReaderStyle(index)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun addTextInput(container: LinearLayout, labelRes: Int, value: String): EditText {
        return addLabeledInput(container, labelRes).apply {
            setText(value)
            selectAll()
        }
    }

    private fun addCheckBox(container: LinearLayout, labelRes: Int, checked: Boolean): CheckBox {
        return CheckBox(this).apply {
            text = getString(labelRes)
            textSize = 14f
            setTextColor(readerTextColor)
            isChecked = checked
        }.also { checkBox ->
            container.addView(checkBox, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun addActionText(container: LinearLayout, labelRes: Int): TextView {
        return TextView(this).apply {
            text = getString(labelRes)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(readerTextColor)
            setPadding(0, dp(10), 0, dp(10))
        }.also { view ->
            container.addView(view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun styleConfigButton(labelRes: Int, color: Int): TextView {
        return TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setPadding(dp(8), 0, dp(8), 0)
            bindStyleConfigButton(this, labelRes, color)
        }
    }

    private fun bindStyleConfigButton(view: TextView, labelRes: Int, color: Int) {
        view.text = getString(labelRes)
        view.setTextColor(readerTextColor)
        view.background = GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(withAlpha(color, 0.18f))
            setStroke(dp(1), color)
        }
    }

    private fun showReaderColorPicker(dialogId: Int, currentColor: Int, onColor: (Int) -> Unit) {
        pendingReaderColorDialogId = dialogId
        pendingReaderColorSelected = onColor
        ColorPickerDialog.newBuilder()
            .setColor(currentColor)
            .setShowAlphaSlider(false)
            .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
            .setDialogId(dialogId)
            .show(this)
    }

    private fun addLabeledInput(container: LinearLayout, labelRes: Int): EditText {
        container.addView(TextView(this).apply {
            text = getString(labelRes)
            textSize = 13f
            setTextColor(readerTipColor)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8)
        })
        return EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }.also { input ->
            container.addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun addBackgroundImagePicker(
        container: LinearLayout,
        selectedAssetName: String?,
        selectedImageUri: String?,
        onSelected: (String?, String?) -> Unit,
        onPickExternal: () -> Unit
    ): (String?, String?) -> Unit {
        container.addView(TextView(this).apply {
            text = getString(R.string.reader_bg_image)
            textSize = 13f
            setTextColor(readerTipColor)
            setPadding(0, dp(10), 0, dp(4))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tiles = mutableMapOf<Pair<String?, String?>, LinearLayout>()
        fun addTile(assetName: String?, imageUri: String?, title: String, drawable: Drawable?) {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ColorDrawable(if (assetName == null && imageUri == null) readerBgColor else 0x00000000)
                setImageDrawable(drawable)
            }
            val label = TextView(this).apply {
                text = title
                textSize = 10f
                setSingleLine(true)
                gravity = Gravity.CENTER
                setTextColor(readerTextColor)
            }
            tile.addView(image, LinearLayout.LayoutParams(dp(54), dp(44)))
            tile.addView(label, LinearLayout.LayoutParams(dp(62), LinearLayout.LayoutParams.WRAP_CONTENT))
            tile.setOnClickListener {
                onSelected(assetName, imageUri)
                bindBgTiles(tiles, assetName, imageUri)
            }
            row.addView(tile, LinearLayout.LayoutParams(dp(66), dp(88)).apply {
                marginEnd = dp(6)
            })
            tiles[assetName to imageUri] = tile
        }
        addTile(null, null, getString(R.string.reader_bg_image_none), null)
        if (!selectedImageUri.isNullOrBlank()) {
            val currentDrawable = runCatching {
                contentResolver.openInputStream(Uri.parse(selectedImageUri))?.use { input ->
                    Drawable.createFromStream(input, selectedImageUri)
                }
            }.getOrNull()
            addTile(null, selectedImageUri, getString(R.string.reader_bg_image_current), currentDrawable)
        }
        val pickTile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(0x00000000)
                setStroke(dp(1), readerTipColor)
            }
            setOnClickListener { onPickExternal() }
        }
        pickTile.addView(TextView(this).apply {
            text = "+"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(readerTextColor)
        }, LinearLayout.LayoutParams(dp(54), dp(44)))
        pickTile.addView(TextView(this).apply {
            text = getString(R.string.reader_select_image)
            textSize = 10f
            setSingleLine(true)
            gravity = Gravity.CENTER
            setTextColor(readerTextColor)
        }, LinearLayout.LayoutParams(dp(62), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(pickTile, LinearLayout.LayoutParams(dp(66), dp(88)).apply {
            marginEnd = dp(6)
        })
        legadoBgAssets().forEach { asset ->
            val drawable = runCatching {
                assets.open("legado_bg/$asset").use { input ->
                    Drawable.createFromStream(input, asset)
                }
            }.getOrNull()
            addTile(asset, null, asset.substringBeforeLast("."), drawable)
        }
        container.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(96)
        ))
        bindBgTiles(tiles, selectedAssetName, selectedImageUri)
        return { nextAssetName, nextImageUri -> bindBgTiles(tiles, nextAssetName, nextImageUri) }
    }

    private fun bindBgTiles(
        tiles: Map<Pair<String?, String?>, LinearLayout>,
        selectedAssetName: String?,
        selectedImageUri: String?
    ) {
        tiles.forEach { (key, tile) ->
            val selected = key.first == selectedAssetName && key.second == selectedImageUri
            tile.background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(0x00000000)
                setStroke(dp(if (selected) 2 else 1), if (selected) 0xFF2E9F6E.toInt() else readerTipColor)
            }
        }
    }

    private fun legadoBgAssets(): List<String> {
        return runCatching {
            assets.list("legado_bg")?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun showTextWeightDialog(onChanged: (M9TextWeight) -> Unit = {}) {
        val choices = arrayOf(
            getString(R.string.reader_text_weight_normal),
            getString(R.string.reader_text_weight_bold),
            getString(R.string.reader_text_weight_light)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_text_font_weight_converter)
            .setSingleChoiceItems(choices, readerTextWeight.ordinal) { dialog, which ->
                readerTextWeight = M9TextWeight.fromIndex(which)
                readView.setTextWeight(readerTextWeight)
                onChanged(readerTextWeight)
                updateSelectedReaderStyleLayoutFields()
                dialog.dismiss()
                requestBookRelayout(immediate = true)
            }
            .show()
    }

    private fun showIndentDialog() {
        val choices = resources.getStringArray(R.array.reader_indent_titles)
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_title_indent)
            .setSingleChoiceItems(choices, readerParagraphIndentCount) { dialog, which ->
                readerParagraphIndentCount = which
                updateSelectedReaderStyleLayoutFields()
                dialog.dismiss()
                requestBookRelayout()
            }
            .show()
    }

    private fun showFontDialog() {
        val choices = resources.getStringArray(R.array.reader_font_titles)
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_title_font)
            .setSingleChoiceItems(choices, readerTypefaceIndex) { dialog, which ->
                readerTypefaceIndex = which
                readerTypeface = readerTypefaceForIndex(which)
                readView.setReaderTypeface(readerTypeface)
                updateSelectedReaderStyleLayoutFields()
                dialog.dismiss()
                requestBookRelayout()
            }
            .show()
    }

    private fun collectReaderImageGalleryItems(): List<ReaderImageGalleryItem> {
        val seen = mutableSetOf<String>()
        return document?.chapters
            .orEmpty()
            .flatMapIndexed { chapterIndex, chapter ->
                chapter.images
                    .toSortedMap()
                    .mapNotNull { (position, image) ->
                        val key = image.cacheIdentity()
                        if (seen.add(key)) {
                            ReaderImageGalleryItem(
                                image = image,
                                chapterIndex = chapterIndex,
                                chapterPosition = position
                            )
                        } else {
                            null
                        }
                    }
            }
    }

    private fun initialImageGalleryIndex(
        items: List<ReaderImageGalleryItem>,
        preferredImage: EbookImageRef? = null
    ): Int {
        if (preferredImage != null) {
            val preferredIdentity = preferredImage.cacheIdentity()
            val preferredPath = preferredImage.path
            val preferredIndex = items.indexOfFirst { item ->
                item.image.cacheIdentity() == preferredIdentity || item.image.path == preferredPath
            }
            if (preferredIndex >= 0) return preferredIndex
        }
        val currentPage = pages.getOrNull(pageIndex) ?: return 0
        val samePage = items.indexOfFirst { item ->
            item.chapterIndex == currentPage.chapterIndex &&
                item.chapterPosition in currentPage.charStart until currentPage.charEnd
        }
        if (samePage >= 0) return samePage
        val nextImage = items.indexOfFirst { item ->
            item.chapterIndex > currentPage.chapterIndex ||
                (item.chapterIndex == currentPage.chapterIndex && item.chapterPosition >= currentPage.charStart)
        }
        return nextImage.takeIf { it >= 0 } ?: 0
    }

    private fun isImageGalleryItemUnlocked(
        item: ReaderImageGalleryItem,
        unlockAnchor: ReaderPageAnchor?
    ): Boolean {
        val anchor = unlockAnchor ?: return false
        return item.chapterIndex < anchor.chapterIndex ||
            (item.chapterIndex == anchor.chapterIndex && item.chapterPosition <= anchor.charPosition)
    }

    private fun showFocusedImagePreview(image: EbookImageRef) {
        imageGalleryDismiss?.invoke()
        val focusBgColor = Color.BLACK
        var focusJob: Job? = null
        val root = FrameLayout(this).apply {
            setBackgroundColor(focusBgColor)
            isClickable = true
        }
        val focusImage = ComposeView(this).apply {
            setBackgroundColor(focusBgColor)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        }
        root.addView(
            focusImage,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        fun dismissFocusPreview() {
            focusJob?.cancel()
            imageGalleryHideFocus = null
            imageGalleryDismiss = null
            imageGallerySystemBarMode = null
            if (imageGalleryOverlay === root) {
                (root.parent as? ViewGroup)?.removeView(root)
                imageGalleryOverlay = null
            }
            applySystemUiSettings()
            updateSystemBarSurfaces()
        }

        imageGalleryOverlay = root
        imageGalleryHideFocus = {
            dismissFocusPreview()
            true
        }
        imageGalleryDismiss = { dismissFocusPreview() }
        imageGallerySystemBarMode = ImageGallerySystemBarMode.FOCUS
        updateSystemBarSurfaces()
        (readerRootAsViewGroup() ?: contentContainer).addView(
            root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.setOnClickListener { dismissFocusPreview() }
        focusJob = lifecycleScope.launch {
            val imageBytes = withContext(Dispatchers.IO) {
                image.readBytes()
            }
            if (imageBytes == null) {
                Toast.makeText(
                    this@LegadoReaderActivity,
                    R.string.reader_image_preview_unavailable,
                    Toast.LENGTH_SHORT
                ).show()
                dismissFocusPreview()
                return@launch
            }
            focusImage.setContent {
                ZoomableImagePreview(
                    imageBytes = imageBytes,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private fun showImageGalleryDialog(preferredImage: EbookImageRef? = null) {
        val items = collectReaderImageGalleryItems()
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.reader_image_gallery_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val spoilerEnabled = loadEbookImageSpoilerEnabled(this)
        val unlockAnchor = if (spoilerEnabled) {
            currentPageAnchor(includeCueMatch = false) ?: pendingRestoreAnchor
        } else {
            null
        }
        val temporaryUnlockedImageKeys = mutableSetOf<String>()
        imageGalleryDismiss?.invoke()
        val initialIndex = initialImageGalleryIndex(items, preferredImage).coerceIn(0, items.lastIndex)
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.coerceAtLeast(1)
        val screenHeight = displayMetrics.heightPixels.coerceAtLeast(1)
        val headerHeight = dp(56)
        val topInset = contentContainer.paddingTop
        val bottomInset = contentContainer.paddingBottom
        val listImageHeight = (screenHeight * 0.58f).toInt().coerceIn(dp(260), dp(520))
        val thumbnailTargetWidth = (screenWidth - dp(32)).coerceAtLeast(1)
        val galleryBgColor = Color.WHITE
        val galleryTextColor = Color.BLACK
        val focusBgColor = Color.BLACK
        val imageJobs = mutableListOf<Job>()
        var focusJob: Job? = null

        fun applyGallerySystemBarSurfaces() {
            imageGallerySystemBarMode = ImageGallerySystemBarMode.LIST
            updateSystemBarSurfaces()
        }

        fun applyFocusSystemBarSurfaces() {
            imageGallerySystemBarMode = ImageGallerySystemBarMode.FOCUS
            updateSystemBarSurfaces()
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(galleryBgColor)
            isClickable = true
        }
        val statusText = TextView(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            textSize = 16f
            maxLines = 1
            setTextColor(galleryTextColor)
            setPadding(dp(16), 0, dp(72), 0)
            text = imageGalleryStatusText(items[initialIndex], initialIndex, items.size)
        }
        val closeButton = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(galleryTextColor)
            setText(R.string.close)
        }
        val header = FrameLayout(this).apply {
            setBackgroundColor(galleryBgColor)
            addView(
                statusText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                closeButton,
                FrameLayout.LayoutParams(dp(72), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END)
            )
        }
        val imageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        val imageRows = mutableListOf<View>()
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(
                imageContainer,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val focusOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(focusBgColor)
        }
        val focusImage = ComposeView(this).apply {
            setBackgroundColor(focusBgColor)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        }
        focusOverlay.addView(
            focusImage,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        fun updateStatusFromScroll() {
            if (imageRows.isEmpty()) return
            val viewportCenter = scrollView.scrollY + scrollView.height / 2
            val visibleIndex = imageRows.indices.minByOrNull { index ->
                abs((imageRows[index].top + imageRows[index].height / 2) - viewportCenter)
            } ?: initialIndex
            statusText.text = imageGalleryStatusText(items[visibleIndex], visibleIndex, items.size)
        }

        fun hideFocus(): Boolean {
            if (focusOverlay.visibility != View.VISIBLE) return false
            focusJob?.cancel()
            focusJob = null
            focusImage.setContent {}
            focusOverlay.visibility = View.GONE
            applyGallerySystemBarSurfaces()
            return true
        }

        fun showFocus(index: Int) {
            val item = items[index]
            focusImage.setContent {}
            applyFocusSystemBarSurfaces()
            focusOverlay.visibility = View.VISIBLE
            focusJob?.cancel()
            focusJob = lifecycleScope.launch {
                val imageBytes = withContext(Dispatchers.IO) {
                    item.image.readBytes()
                }
                if (imageBytes == null) {
                    Toast.makeText(
                        this@LegadoReaderActivity,
                        R.string.reader_image_preview_unavailable,
                        Toast.LENGTH_SHORT
                    ).show()
                    hideFocus()
                    return@launch
                }
                focusImage.setContent {
                    ZoomableImagePreview(
                        imageBytes = imageBytes,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        items.forEachIndexed { index, item ->
            val unlockedByProgress = !spoilerEnabled || isImageGalleryItemUnlocked(item, unlockAnchor)
            val imageView = ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(galleryBgColor)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                setOnClickListener {
                    if (unlockedByProgress || item.key in temporaryUnlockedImageKeys) {
                        showFocus(index)
                    }
                }
            }
            val spoilerLabel = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.BLACK)
                setText(R.string.reader_image_gallery_spoiler)
                setPadding(dp(18), dp(8), dp(18), dp(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(20).toFloat()
                    setColor(0xE6FFFFFF.toInt())
                    setStroke(dp(1), 0x33000000)
                }
            }
            val spoilerOverlay = FrameLayout(this).apply {
                setBackgroundColor(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        0x99FFFFFF.toInt()
                    } else {
                        0xEAF5F5F5.toInt()
                    }
                )
                isClickable = true
                addView(
                    spoilerLabel,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                )
            }
            val row = FrameLayout(this).apply {
                addView(
                    imageView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                addView(
                    spoilerOverlay,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
            fun updateSpoilerState() {
                val unlocked = unlockedByProgress || item.key in temporaryUnlockedImageKeys
                spoilerOverlay.visibility = if (unlocked) View.GONE else View.VISIBLE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    imageView.setRenderEffect(
                        if (unlocked) {
                            null
                        } else {
                            RenderEffect.createBlurEffect(44f, 44f, Shader.TileMode.CLAMP)
                        }
                    )
                }
            }
            spoilerOverlay.setOnClickListener {
                temporaryUnlockedImageKeys += item.key
                updateSpoilerState()
            }
            updateSpoilerState()
            imageRows += row
            imageContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    listImageHeight
                ).apply {
                    bottomMargin = dp(18)
                }
            )
            imageJobs += lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    val bytes = item.image.readBytes() ?: return@withContext null
                    decodeSampledBitmap(
                        bytes = bytes,
                        targetWidthPx = thumbnailTargetWidth,
                        targetHeightPx = listImageHeight
                    )
                }
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }

        scrollView.setOnScrollChangeListener { _, _, _, _, _ -> updateStatusFromScroll() }
        focusOverlay.setOnClickListener { hideFocus() }
        closeButton.setOnClickListener { imageGalleryDismiss?.invoke() }

        root.addView(
            scrollView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = topInset + headerHeight
                bottomMargin = bottomInset
            }
        )
        root.addView(
            header,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                headerHeight,
                Gravity.TOP
            ).apply {
                topMargin = topInset
            }
        )
        root.addView(
            focusOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        fun dismissGallery() {
            imageJobs.forEach { it.cancel() }
            focusJob?.cancel()
            imageGalleryHideFocus = null
            imageGalleryDismiss = null
            imageGallerySystemBarMode = null
            if (imageGalleryOverlay === root) {
                (root.parent as? ViewGroup)?.removeView(root)
                imageGalleryOverlay = null
            }
            applySystemUiSettings()
            updateSystemBarSurfaces()
        }
        imageGalleryOverlay = root
        imageGalleryHideFocus = { hideFocus() }
        imageGalleryDismiss = { dismissGallery() }
        applyGallerySystemBarSurfaces()
        (readerRootAsViewGroup() ?: contentContainer).addView(
            root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        scrollView.post {
            imageRows.getOrNull(initialIndex)?.let { row ->
                scrollView.scrollTo(0, row.top)
            }
            updateStatusFromScroll()
        }
    }

    private fun imageGalleryStatusText(
        item: ReaderImageGalleryItem,
        index: Int,
        total: Int
    ): String {
        val base = getString(R.string.reader_image_gallery_title, index + 1, total)
        val chapterTitle = document?.chapters
            ?.getOrNull(item.chapterIndex)
            ?.title
            .orEmpty()
            .trim()
        return if (chapterTitle.isBlank()) base else "$base · $chapterTitle"
    }

    private fun showPaddingDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(10))
        }
        addInfoSectionTitle(content, getString(R.string.reader_margin_header))
        addInfoCheckRow(content, getString(R.string.reader_margin_show_divider), showHeaderLine) {
            showHeaderLine = it
            applyPaddingConfigChange(repaginate = false)
        }
        addInfoAdjustRow(content, getString(R.string.reader_margin_top), headerPaddingTopDp, 0, 100) {
            headerPaddingTopDp = it
            applyPaddingConfigChange(repaginate = true)
        }
        addInfoAdjustRow(content, getString(R.string.reader_margin_bottom), headerPaddingBottomDp, 0, 100) {
            headerPaddingBottomDp = it
            applyPaddingConfigChange(repaginate = true)
        }
        addInfoAdjustRow(content, getString(R.string.reader_margin_left), headerPaddingLeftDp, 0, 100) {
            headerPaddingLeftDp = it
            applyPaddingConfigChange(repaginate = true)
        }
        addInfoAdjustRow(content, getString(R.string.reader_margin_right), headerPaddingRightDp, 0, 100) {
            headerPaddingRightDp = it
            applyPaddingConfigChange(repaginate = true)
        }

        addInfoSectionTitle(content, getString(R.string.reader_margin_body))
        addInfoAdjustRow(content, getString(R.string.reader_margin_top), readerPaddingTopDp, 0, 160) {
            readerPaddingTopDp = it
            applyPaddingConfigChange(repaginate = true)
        }
        addInfoAdjustRow(content, getString(R.string.reader_margin_bottom), readerPaddingBottomDp, 0, 160) {
            readerPaddingBottomDp = it
            applyPaddingConfigChange(repaginate = true)
        }
        addInfoAdjustRow(content, getString(R.string.reader_margin_left), readerPaddingLeftDp, 0, 100) {
            readerPaddingLeftDp = it
            readerPaddingDp = it
            applyPaddingConfigChange(repaginate = true)
        }
        addInfoAdjustRow(content, getString(R.string.reader_margin_right), readerPaddingRightDp, 0, 100) {
            readerPaddingRightDp = it
            readerPaddingDp = readerPaddingLeftDp
            applyPaddingConfigChange(repaginate = true)
        }

        addInfoSectionTitle(content, getString(R.string.reader_margin_footer))
        addInfoCheckRow(content, getString(R.string.reader_margin_show_divider), showFooterLine) {
            showFooterLine = it
            applyPaddingConfigChange(repaginate = false)
        }
        addInfoAdjustRow(content, getString(R.string.reader_margin_top), footerPaddingTopDp, 0, 100) {
            footerPaddingTopDp = it
            applyPaddingConfigChange(repaginate = true)
        }
        addInfoAdjustRow(content, getString(R.string.reader_margin_bottom), footerPaddingBottomDp, 0, 100) {
            footerPaddingBottomDp = it
            applyPaddingConfigChange(repaginate = true)
        }
        addInfoAdjustRow(content, getString(R.string.reader_margin_left), footerPaddingLeftDp, 0, 100) {
            footerPaddingLeftDp = it
            applyPaddingConfigChange(repaginate = true)
        }
        addInfoAdjustRow(content, getString(R.string.reader_margin_right), footerPaddingRightDp, 0, 100) {
            footerPaddingRightDp = it
            applyPaddingConfigChange(repaginate = true)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reader_title_margin)
            .setView(scroll)
            .setPositiveButton(R.string.reader_dialog_done, null)
            .show()
        configureReaderConfigDialogWindow(dialog, widthFraction = 0.9f)
    }

    private fun applyPaddingConfigChange(repaginate: Boolean) {
        applyReaderTypography()
        applyReaderInfoConfig()
        updateSelectedReaderStyleLayoutFields()
        if (repaginate) {
            requestBookRelayout(immediate = true)
        } else {
            persistReaderSettings()
        }
    }

    private fun showTipConfigDialog() {
        tipConfigDialog?.setOnDismissListener(null)
        tipConfigDialog?.dismiss()
        tipConfigDialog = null
        var dialog: AlertDialog? = null
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(10))
        }
        addInfoSectionTitle(content, getString(R.string.reader_margin_body_title))
        addBodyTitleModeGroup(content)
        addInfoAdjustRow(content, getString(R.string.reader_margin_font_size), bodyTitleSizeAddSp, 0, 10) {
            bodyTitleSizeAddSp = it
            applyTipConfigChange(repaginate = true)
        }
        addInfoAdjustRow(content, getString(R.string.reader_margin_top), bodyTitleTopSpacingDp, 0, 100) {
            bodyTitleTopSpacingDp = it
            applyTipConfigChange(repaginate = true)
        }
        addInfoAdjustRow(content, getString(R.string.reader_margin_bottom), bodyTitleBottomSpacingDp, 0, 100) {
            bodyTitleBottomSpacingDp = it
            applyTipConfigChange(repaginate = true)
        }

        addInfoSectionTitle(content, getString(R.string.reader_tip_top_toolbar))
        addInfoSelectorRow(content, getString(R.string.reader_tip_show_hide), headerModeLabel(topBarMode)) {
            dialog?.dismiss()
            showTopBarModeSelector()
        }
        addInfoSelectorRow(content, getString(R.string.reader_tip_left), tipContentLabel(tipTopBarLeft)) {
            dialog?.dismiss()
            showReaderInfoContentSelector(ReaderInfoSlot.TOP_BAR_LEFT, tipTopBarLeft, { tipTopBarLeft = it }) {
                applyTopBarTipConfigChange()
            }
        }
        addInfoSelectorRow(content, getString(R.string.reader_tip_middle), tipContentLabel(tipTopBarMiddle)) {
            dialog?.dismiss()
            showReaderInfoContentSelector(ReaderInfoSlot.TOP_BAR_MIDDLE, tipTopBarMiddle, { tipTopBarMiddle = it }) {
                applyTopBarTipConfigChange()
            }
        }
        addInfoSelectorRow(content, getString(R.string.reader_tip_right), tipContentLabel(tipTopBarRight)) {
            dialog?.dismiss()
            showReaderInfoContentSelector(ReaderInfoSlot.TOP_BAR_RIGHT, tipTopBarRight, { tipTopBarRight = it }) {
                applyTopBarTipConfigChange()
            }
        }

        addInfoSectionTitle(content, getString(R.string.reader_margin_header))
        addInfoSelectorRow(content, getString(R.string.reader_tip_show_hide), headerModeLabel(headerMode)) {
            dialog?.dismiss()
            showHeaderModeSelector()
        }
        addInfoSelectorRow(content, getString(R.string.reader_tip_left), tipContentLabel(tipHeaderLeft)) {
            dialog?.dismiss()
            showReaderInfoContentSelector(ReaderInfoSlot.HEADER_LEFT, tipHeaderLeft, { tipHeaderLeft = it }) {
                applyTipConfigChange(repaginate = true)
            }
        }
        addInfoSelectorRow(content, getString(R.string.reader_tip_middle), tipContentLabel(tipHeaderMiddle)) {
            dialog?.dismiss()
            showReaderInfoContentSelector(ReaderInfoSlot.HEADER_MIDDLE, tipHeaderMiddle, { tipHeaderMiddle = it }) {
                applyTipConfigChange(repaginate = true)
            }
        }
        addInfoSelectorRow(content, getString(R.string.reader_tip_right), tipContentLabel(tipHeaderRight)) {
            dialog?.dismiss()
            showReaderInfoContentSelector(ReaderInfoSlot.HEADER_RIGHT, tipHeaderRight, { tipHeaderRight = it }) {
                applyTipConfigChange(repaginate = true)
            }
        }
        addInfoSectionTitle(content, getString(R.string.reader_margin_footer))
        addInfoSelectorRow(content, getString(R.string.reader_tip_show_hide), footerModeLabel(footerMode)) {
            dialog?.dismiss()
            showFooterModeSelector()
        }
        addInfoSelectorRow(content, getString(R.string.reader_tip_left), tipContentLabel(tipFooterLeft)) {
            dialog?.dismiss()
            showReaderInfoContentSelector(ReaderInfoSlot.FOOTER_LEFT, tipFooterLeft, { tipFooterLeft = it }) {
                applyTipConfigChange(repaginate = true)
            }
        }
        addInfoSelectorRow(content, getString(R.string.reader_tip_middle), tipContentLabel(tipFooterMiddle)) {
            dialog?.dismiss()
            showReaderInfoContentSelector(ReaderInfoSlot.FOOTER_MIDDLE, tipFooterMiddle, { tipFooterMiddle = it }) {
                applyTipConfigChange(repaginate = true)
            }
        }
        addInfoSelectorRow(content, getString(R.string.reader_tip_right), tipContentLabel(tipFooterRight)) {
            dialog?.dismiss()
            showReaderInfoContentSelector(ReaderInfoSlot.FOOTER_RIGHT, tipFooterRight, { tipFooterRight = it }) {
                applyTipConfigChange(repaginate = true)
            }
        }
        addInfoSectionTitle(content, getString(R.string.reader_tip_header_footer))
        addInfoSelectorRow(content, getString(R.string.reader_tip_text_color), tipColorModeLabel(tipColorMode)) {
            dialog?.dismiss()
            showTipColorModeSelector()
        }
        addInfoSelectorRow(content, getString(R.string.reader_tip_divider_color), tipDividerColorModeLabel(tipDividerColorMode)) {
            dialog?.dismiss()
            showTipDividerColorModeSelector()
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reader_title_info)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.reader_dialog_done, null)
            .show()
        configureReaderConfigDialogWindow(dialog, widthFraction = 0.95f)
        tipConfigDialog = dialog
        dialog.setOnDismissListener {
            if (tipConfigDialog === dialog) {
                tipConfigDialog = null
            }
        }
    }

    private fun addInfoSectionTitle(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(0xFFE0483F.toInt())
            includeFontPadding = true
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
    }

    private fun addBodyTitleModeGroup(parent: LinearLayout) {
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val options = listOf(
            ReaderBodyTitleMode.LEFT to getString(R.string.reader_tip_align_left),
            ReaderBodyTitleMode.CENTER to getString(R.string.reader_tip_align_center),
            ReaderBodyTitleMode.HIDE to getString(R.string.reader_tip_hide)
        )
        options.forEach { (mode, label) ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 14f
                setTextColor(readerTextColor)
                isChecked = bodyTitleMode == mode
                setOnClickListener {
                    bodyTitleMode = mode
                    applyTipConfigChange(repaginate = true)
                }
            }, RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.WRAP_CONTENT,
                RadioGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) })
        }
        parent.addView(group, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun addInfoAdjustRow(
        parent: LinearLayout,
        label: String,
        initialValue: Int,
        min: Int,
        max: Int,
        onChange: (Int) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
        }
        val valueView = TextView(this).apply {
            text = initialValue.toString()
            textSize = 14f
            gravity = Gravity.END
            setTextColor(readerTextColor)
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(readerTextColor)
        }, LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(this).apply {
            text = "－"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(readerTextColor)
            setOnClickListener {
                val value = (valueView.text.toString().toIntOrNull() ?: initialValue).minus(1).coerceIn(min, max)
                valueView.text = value.toString()
                onChange(value)
            }
        }, LinearLayout.LayoutParams(dp(34), dp(36)))
        row.addView(SeekBar(this).apply {
            this.max = max - min
            progress = (initialValue - min).coerceIn(0, this.max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val value = min + progress
                    valueView.text = value.toString()
                    onChange(value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(0, dp(36), 1f))
        row.addView(TextView(this).apply {
            text = "＋"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(readerTextColor)
            setOnClickListener {
                val value = (valueView.text.toString().toIntOrNull() ?: initialValue).plus(1).coerceIn(min, max)
                valueView.text = value.toString()
                (row.getChildAt(2) as? SeekBar)?.progress = value - min
                onChange(value)
            }
        }, LinearLayout.LayoutParams(dp(34), dp(36)))
        row.addView(valueView, LinearLayout.LayoutParams(dp(34), LinearLayout.LayoutParams.WRAP_CONTENT))
        parent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun addInfoSelectorRow(parent: LinearLayout, label: String, value: String, onClick: () -> Unit) {
        parent.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(7), 0, dp(7))
            isClickable = true
            setOnClickListener { onClick() }
            addView(TextView(this@LegadoReaderActivity).apply {
                text = label
                textSize = 14f
                setTextColor(readerTextColor)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@LegadoReaderActivity).apply {
                text = value
                textSize = 14f
                gravity = Gravity.END
                setTextColor(readerTextColor)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun addInfoCheckRow(parent: LinearLayout, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        parent.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, dp(5))
            addView(TextView(this@LegadoReaderActivity).apply {
                text = label
                textSize = 14f
                setTextColor(readerTextColor)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(CheckBox(this@LegadoReaderActivity).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun showHeaderModeSelector() {
        val entries = listOf(
            ReaderHeaderMode.HIDE_WHEN_STATUS_BAR_SHOW to getString(R.string.reader_tip_hide_status_bar_show),
            ReaderHeaderMode.SHOW to getString(R.string.reader_tip_show),
            ReaderHeaderMode.HIDE to getString(R.string.reader_tip_hide)
        )
        showSimpleSelector(getString(R.string.reader_margin_header), entries, headerMode) {
            headerMode = it
            applyTipConfigChange(repaginate = true)
            showTipConfigDialog()
        }
    }

    private fun showFooterModeSelector() {
        val entries = listOf(
            ReaderFooterMode.SHOW to getString(R.string.reader_tip_show),
            ReaderFooterMode.HIDE to getString(R.string.reader_tip_hide)
        )
        showSimpleSelector(getString(R.string.reader_margin_footer), entries, footerMode) {
            footerMode = it
            applyTipConfigChange(repaginate = true)
            showTipConfigDialog()
        }
    }

    private fun showTopBarModeSelector() {
        val entries = listOf(
            ReaderHeaderMode.HIDE_WHEN_STATUS_BAR_SHOW to getString(R.string.reader_tip_hide_status_bar_show),
            ReaderHeaderMode.SHOW to getString(R.string.reader_tip_show),
            ReaderHeaderMode.HIDE to getString(R.string.reader_tip_hide)
        )
        showSimpleSelector(getString(R.string.reader_tip_top_toolbar), entries, topBarMode) {
            topBarMode = it
            applyTopBarTipConfigChange()
            showTipConfigDialog()
        }
    }

    private fun showTipContentSelector(current: ReaderTipContent, onSelected: (ReaderTipContent) -> Unit) {
        showSimpleSelector(getString(R.string.reader_tip_display_content), tipContentEntries(), current) {
            onSelected(it)
            showTipConfigDialog()
        }
    }

    private fun showReaderInfoContentSelector(
        slot: ReaderInfoSlot,
        current: ReaderTipContent,
        onSelected: (ReaderTipContent) -> Unit,
        onApplied: () -> Unit
    ) {
        showTipContentSelector(current) { selected ->
            if (selected != current) readerInfoAlternateSlots.remove(slot)
            onSelected(selected)
            onApplied()
        }
    }

    private fun showTipColorModeSelector() {
        val entries = listOf(
            ReaderTipColorMode.FOLLOW_CONTENT to getString(R.string.reader_tip_follow_content),
            ReaderTipColorMode.CUSTOM to getString(R.string.reader_tip_custom)
        )
        showSimpleSelector(getString(R.string.reader_tip_text_color), entries, tipColorMode) {
            tipColorMode = it
            if (it == ReaderTipColorMode.CUSTOM) {
                showReaderColorPicker(READER_TIP_COLOR_DIALOG_ID, readerTipColor) { color ->
                    readerTipColor = color
                    applyTipConfigChange(repaginate = false)
                    showTipConfigDialog()
                }
                applyTipConfigChange(repaginate = false)
                return@showSimpleSelector
            }
            applyTipConfigChange(repaginate = false)
            showTipConfigDialog()
        }
    }

    private fun showTipDividerColorModeSelector() {
        val entries = listOf(
            ReaderTipDividerColorMode.DEFAULT to getString(R.string.reader_tip_default),
            ReaderTipDividerColorMode.FOLLOW_CONTENT to getString(R.string.reader_tip_follow_content),
            ReaderTipDividerColorMode.CUSTOM to getString(R.string.reader_tip_custom)
        )
        showSimpleSelector(getString(R.string.reader_tip_divider_color), entries, tipDividerColorMode) {
            tipDividerColorMode = it
            if (it == ReaderTipDividerColorMode.CUSTOM) {
                showReaderColorPicker(READER_TIP_DIVIDER_COLOR_DIALOG_ID, tipDividerColor) { color ->
                    tipDividerColor = color
                    applyTipConfigChange(repaginate = false)
                    showTipConfigDialog()
                }
                applyTipConfigChange(repaginate = false)
                return@showSimpleSelector
            }
            applyTipConfigChange(repaginate = false)
            showTipConfigDialog()
        }
    }

    private fun <T> showSimpleSelector(
        title: String,
        entries: List<Pair<T, String>>,
        current: T,
        onSelected: (T) -> Unit
    ) {
        val labels = entries.map { it.second }.toTypedArray()
        val checked = entries.indexOfFirst { it.first == current }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                onSelected(entries[which].first)
            }
            .show()
    }

    private fun configureReaderConfigDialogWindow(dialog: AlertDialog, widthFraction: Float) {
        dialog.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attr = attributes
            attr.dimAmount = 0f
            attributes = attr
            val width = (resources.displayMetrics.widthPixels * widthFraction)
                .toInt()
                .coerceAtLeast(dp(280))
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun applyTipConfigChange(repaginate: Boolean) {
        applyReaderVisualStyle()
        updateChapterTitleSurfaces()
        persistReaderSettings(updateAnchor = false)
        if (repaginate) {
            requestBookRelayout()
        } else {
            renderCurrentPage()
        }
    }

    private fun applyTopBarTipConfigChange() {
        updateChapterTitleSurfaces()
        persistReaderSettings(updateAnchor = false)
    }

    private fun tipContentEntries(): List<Pair<ReaderTipContent, String>> = listOf(
        ReaderTipContent.NONE to getString(R.string.reader_tip_none),
        ReaderTipContent.BOOK_NAME to getString(R.string.reader_tip_book_name),
        ReaderTipContent.CHAPTER_TITLE to getString(R.string.reader_tip_chapter_title),
        ReaderTipContent.TIME to getString(R.string.reader_tip_time),
        ReaderTipContent.BATTERY to getString(R.string.reader_tip_battery),
        ReaderTipContent.BATTERY_PERCENTAGE to getString(R.string.reader_tip_battery_percentage),
        ReaderTipContent.PAGE to getString(R.string.reader_tip_page),
        ReaderTipContent.TOTAL_PROGRESS to getString(R.string.reader_tip_total_progress),
        ReaderTipContent.CHAPTER_PROGRESS to getString(R.string.reader_tip_chapter_progress),
        ReaderTipContent.PAGE_AND_TOTAL to getString(R.string.reader_tip_page_and_total),
        ReaderTipContent.PAGE_OR_PROGRESS to getString(R.string.reader_tip_page_or_progress),
        ReaderTipContent.CHAR_COUNT to getString(R.string.reader_tip_char_count),
        ReaderTipContent.TIME_BATTERY to getString(R.string.reader_tip_time_battery),
        ReaderTipContent.TIME_BATTERY_PERCENTAGE to getString(R.string.reader_tip_time_battery_percentage)
    )

    private fun tipContentLabel(content: ReaderTipContent): String {
        return tipContentEntries().firstOrNull { it.first == content }?.second ?: getString(R.string.reader_tip_none)
    }

    private fun headerModeLabel(mode: ReaderHeaderMode): String = when (mode) {
        ReaderHeaderMode.HIDE_WHEN_STATUS_BAR_SHOW -> getString(R.string.reader_tip_hide_status_bar_show)
        ReaderHeaderMode.SHOW -> getString(R.string.reader_tip_show)
        ReaderHeaderMode.HIDE -> getString(R.string.reader_tip_hide)
    }

    private fun footerModeLabel(mode: ReaderFooterMode): String = when (mode) {
        ReaderFooterMode.SHOW -> getString(R.string.reader_tip_show)
        ReaderFooterMode.HIDE -> getString(R.string.reader_tip_hide)
    }

    private fun tipColorModeLabel(mode: ReaderTipColorMode): String = when (mode) {
        ReaderTipColorMode.FOLLOW_CONTENT -> getString(R.string.reader_tip_follow_content)
        ReaderTipColorMode.CUSTOM -> getString(R.string.reader_tip_custom)
    }

    private fun tipDividerColorModeLabel(mode: ReaderTipDividerColorMode): String = when (mode) {
        ReaderTipDividerColorMode.DEFAULT -> getString(R.string.reader_tip_default)
        ReaderTipDividerColorMode.FOLLOW_CONTENT -> getString(R.string.reader_tip_follow_content)
        ReaderTipDividerColorMode.CUSTOM -> getString(R.string.reader_tip_custom)
    }

    private fun loadDisplayedBook(anchor: ReaderPageAnchor?, forceDocumentReload: Boolean = false) {
        val pageWidth = readView.contentWidth.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dp(44))
        val pageHeight = readView.contentHeight.takeIf { it > 0 } ?: (resources.displayMetrics.heightPixels - dp(220))
        val book = importedBook
        paginationJob?.cancel()
        paginationJob = lifecycleScope.launch {
            val startupStartMs = SystemClock.elapsedRealtime()
            logDebug(LEGADO_READER_LOG_TAG) {
                "readerStartup start forceReload=$forceDocumentReload anchor=$anchor " +
                "page=${pageWidth}x$pageHeight book=${book?.uri}"
            }
            runCatching {
                val loadDocumentStartMs = SystemClock.elapsedRealtime()
                val loaded = loadOrReuseDocument(book, forceDocumentReload)
                logDebug(LEGADO_READER_LOG_TAG) {
                    "readerStartup loadDocument=${SystemClock.elapsedRealtime() - loadDocumentStartMs}ms " +
                    "chapters=${loaded.chapters.size}"
                }
                document = loaded
                updateSimulatedUnlockedChapterCount(loaded)
                val safeAnchor = anchor?.let { clampAnchorToUnlocked(it, loaded) }
                val currentChapterIndex = currentPageAnchor()?.chapterIndex
                    ?: pendingRestoreAnchor?.chapterIndex
                    ?: 0
                val previewChapterIndex = (safeAnchor?.chapterIndex ?: currentChapterIndex).coerceIn(
                    0,
                    lastUnlockedChapterIndex(loaded)
                )
                val boundaryWaitStartMs = SystemClock.elapsedRealtime()
                awaitSrtAndMatchesForPagination()
                logDebug(LEGADO_MATCH_LOG_TAG) {
                    "readerStartup boundaryWait=${SystemClock.elapsedRealtime() - boundaryWaitStartMs}ms " +
                        "matches=${cueMatchesByCueIndex.size}"
                }
                val previewStartMs = SystemClock.elapsedRealtime()
                val previewPages = getOrPaginateChapterPages(
                    document = loaded,
                    chapterIndex = previewChapterIndex,
                    contentWidthPx = pageWidth.coerceAtLeast(1)
                )
                logDebug(LEGADO_READER_LOG_TAG) {
                    "readerStartup chapterPaginate=${SystemClock.elapsedRealtime() - previewStartMs}ms " +
                    "chapter=$previewChapterIndex pages=${previewPages.size}"
                }
                pages = previewPages
                pageIndex = safeAnchor
                    ?.let { pageIndexForAnchor(previewPages, it) }
                    ?: 0
                rebuildCurrentChapterImageStops()
                updateDisplayedBookTitle()
                val firstRenderStartMs = SystemClock.elapsedRealtime()
                renderCurrentPage()
                if (safeAnchor != null && pendingAudioSyncLoadAnchor == safeAnchor) {
                    pendingAudioSyncLoadAnchor = null
                }
                logDebug(LEGADO_READER_LOG_TAG) {
                    "readerStartup firstRender=${SystemClock.elapsedRealtime() - firstRenderStartMs}ms " +
                    "firstText=${SystemClock.elapsedRealtime() - startupStartMs}ms pageIndex=$pageIndex"
                }
                if (cueMatchesByCueIndex.isNotEmpty()) {
                    logDebug(LEGADO_READER_LOG_TAG) { "readerStartup syncToAudioPosition begin" }
                    syncToAudioPosition(
                        allowPageJump = isAudioPlaybackRequested() || pendingPlayerOpenAudioReveal,
                        forceReveal = pendingPlayerOpenAudioReveal
                    )
                    pendingPlayerOpenAudioReveal = false
                } else {
                    logDebug(LEGADO_READER_LOG_TAG) { "readerStartup loadSrtSync begin" }
                    loadSrtSyncIfNeeded { success ->
                        if (success) {
                            revealPendingPlayerOpenAudioPositionIfNeeded()
                        }
                    }
                }
                preloadAdjacentChapters(
                    document = loaded,
                    centerChapterIndex = previewChapterIndex,
                    contentWidthPx = pageWidth.coerceAtLeast(1)
                )
            }.onSuccess {
                logDebug(LEGADO_READER_LOG_TAG) {
                    "readerStartup done=${SystemClock.elapsedRealtime() - startupStartMs}ms pageIndex=$pageIndex"
                }
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                Log.w(
                    LEGADO_READER_LOG_TAG,
                    "readerStartup failed after ${SystemClock.elapsedRealtime() - startupStartMs}ms",
                    error
                )
                readView.setPage(
                    TextPage(
                        title = currentReaderTitle(),
                        text = getString(
                            R.string.reader_open_ebook_failed,
                            error.message ?: error.javaClass.simpleName
                        ),
                        totalPages = 1
                    )
                )
            }
        }
    }

    private suspend fun loadOrReuseDocument(
        book: LocalReaderBook?,
        forceDocumentReload: Boolean
    ): EbookDocument {
        val bookUriText = book?.uri?.toString()
        val canReuseDocument = !forceDocumentReload &&
            document != null &&
            loadedDocumentBookUriText == bookUriText &&
            loadedDocumentCharsetName == preferredCharsetName
        if (canReuseDocument) {
            logDebug(LEGADO_READER_LOG_TAG) {
                "readerStartup loadOrReuseDocument reused bookUri=$bookUriText charset=$preferredCharsetName"
            }
            return document!!
        }
        val startMs = SystemClock.elapsedRealtime()
        val loaded = if (book != null) {
            withContext(Dispatchers.IO) {
                loadEbookDocument(
                    context = this@LegadoReaderActivity,
                    book = book,
                    preferredCharsetName = preferredCharsetName
                )
            }
        } else {
            EbookDocument(
                title = LEGADO_READER_DEFAULT_TITLE,
                format = "TXT",
                chapters = listOf(
                    EbookChapter(
                        title = LEGADO_READER_DEFAULT_TITLE,
                        text = LEGADO_READER_DEFAULT_PARAGRAPHS.joinToString("\n\n")
                    )
                )
            )
        }
        logDebug(LEGADO_READER_LOG_TAG) {
            "readerStartup loadOrReuseDocument parsed=${SystemClock.elapsedRealtime() - startMs}ms " +
            "format=${loaded.format} chapters=${loaded.chapters.size}"
        }
        clearChapterPageCache()
        loadedDocumentBookUriText = bookUriText
        loadedDocumentCharsetName = preferredCharsetName
        if (forceDocumentReload) {
            logDebug(LEGADO_READER_LOG_TAG) {
                "loadOrReuseDocument force reload resets in-memory match cache bookUri=$bookUriText charset=$preferredCharsetName"
            }
            cueMatchesByCueIndex = emptyMap()
            matchData = null
            audioCueIndex = -1
            activeCueIndex = -1
        }
        return loaded
    }

    private fun paginateDocument(
        document: EbookDocument,
        contentWidthPx: Int
    ): List<TextPage> {
        return buildTextPageFactory().createPages(
            document = document,
            contentWidthPx = contentWidthPx,
            contentHeightPx = currentNoTitleContentHeightPx(),
            firstPageReservePx = 0f
        )
    }

    private fun buildTextPageFactory(paragraphIndentOverride: String? = null): TextPageFactory {
        // 句尾处理（最后一句显示不全时放到下一页）：按章节收集 cue 匹配的句子边界。
        // 匹配是按"可读字符"做的，句首的「『（ 与句尾的」』）。！？ 不在其中，
        // 所以要按正文把范围扩到引号/括号之外，否则"句子不跨页"会把开引号单独留在上一页。
        val sentenceBoundariesByChapter: Map<Int, Pair<Set<Int>, Set<Int>>> = if (
            sentenceNoCrossPage
        ) {
            val chapters = document?.chapters.orEmpty()
            cueMatchesByCueIndex.values
                .groupBy { it.chapterIndex }
                .mapValues { (chapterIndex, matches) ->
                    val chapterText = chapters.getOrNull(chapterIndex)?.text.orEmpty()
                    val ranges = matches.map { match ->
                        expandCueRangeToSentence(chapterText, match.rawStart, match.rawEnd)
                    }
                    ranges.map { it.first }.toSet() to ranges.map { it.second }.toSet()
                }
        } else {
            emptyMap()
        }
        logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
            "buildTextPageFactory tailHandling=$sentenceNoCrossPage " +
            "boundaryChapters=${sentenceBoundariesByChapter.size} cues=${cueMatchesByCueIndex.size} " +
            "chapterBoundarySizes=${sentenceBoundariesByChapter.mapValues { it.value.first.size }}"
        }
        pagesPinnedWithoutSentenceBoundaries = sentenceNoCrossPage && cueMatchesByCueIndex.isEmpty()
        return TextPageFactory(
            config = M9ReadBookConfig(
                textSizePx = readView.textSizePx,
                lineSpacingPx = dp(readerLineSpacingDp).toFloat(),
                paragraphSpacingPx = dp(readerParagraphSpacingDp).toFloat(),
                textColor = readerTextColor,
                tipColor = readerTipColor,
                backgroundColor = readerBgColor,
                useZhLayout = useZhLayout,
                textFullJustify = textFullJustify,
                textBottomJustify = textBottomJustify,
                paragraphIndent = paragraphIndentOverride ?: "　".repeat(readerParagraphIndentCount),
                letterSpacingPx = dp(readerLetterSpacingDp).toFloat(),
                textWeight = readerTextWeight,
                typeface = readerTypeface,
                paddingLeftPx = dp(readerPaddingLeftDp),
                paddingRightPx = dp(readerPaddingRightDp),
                layoutMode = readerLayoutMode,
                pageAnim = readerPageAnim,
                showRubyText = showRubyText
            ),
            emptyPageText = readerString(R.string.reader_no_text),
            sentenceStartsByChapter = sentenceBoundariesByChapter.mapValues { it.value.first },
            sentenceEndsByChapter = sentenceBoundariesByChapter.mapValues { it.value.second }
        )
    }

    /**
     * 首屏分页前等 SRT + 存档匹配就位：句边界是分页输入，等到了这次分页一次到位，
     * 不必"先按段落排一遍、再为句边界重排一遍"。
     *
     * 「句子不跨页」没开时句边界压根不参与分页，直接返回、一秒都不等（SRT 仍按老路在首屏后加载）。
     * 开着但等不到（首次解析 SRT / 正在重新匹配 / 没有 SRT）也直接返回、按段落排，
     * 之后由 [relayoutIfPagesLackSentenceBoundaries] 兜底。
     */
    private suspend fun awaitSrtAndMatchesForPagination() {
        if (!sentenceNoCrossPage) return
        if (cueMatchesByCueIndex.isNotEmpty()) return
        if (srtUri == null) return
        // 这次分页会重新判定，先清掉上一轮（别的章节/别的文档）留下的旗标，
        // 免得在等匹配期间被兜底钩子当成"已分页且缺边界"而触发多余重排。
        pagesPinnedWithoutSentenceBoundaries = false
        val done = CompletableDeferred<Unit>()
        loadSrtSyncIfNeeded { done.complete(Unit) }
        withTimeoutOrNull(SENTENCE_BOUNDARY_WAIT_MS) { done.await() }
    }

    /**
     * 兜底：首屏没等到匹配就分了页，匹配到齐后补一次重排，让「句子不跨页」在本次会话也生效；
     * 已经带边界分过页则什么都不做。
     */
    private fun relayoutIfPagesLackSentenceBoundaries() {
        if (!pagesPinnedWithoutSentenceBoundaries) return
        if (!sentenceNoCrossPage || cueMatchesByCueIndex.isEmpty()) return
        pagesPinnedWithoutSentenceBoundaries = false
        logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
            "sentences: boundaries arrived after paginate -> relayout once matches=${cueMatchesByCueIndex.size}"
        }
        requestBookRelayout(immediate = false)
    }

    private fun currentContentWidthPx(): Int {
        return readView.contentWidth.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - dp(44))
    }

    private fun currentNoTitleContentHeightPx(): Int {
        // 分页高度基准 = 无标题时的正文区高度：当前正文区实测高度 +
        // （若此刻标题正显示）标题占用高度，保证任何时刻分页都在同一基准上。
        val measured = readView.contentHeight.takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels - dp(220))
        val extra = if (bodyTitleShownOnCurrentPage()) {
            readView.bodyTitleReserveFor(
                pages.getOrNull(pageIndex)?.title.orEmpty(),
                currentContentWidthPx()
            )
        } else {
            0
        }
        return (measured + extra).coerceAtLeast(dp(120))
    }

    private fun bodyTitleShownOnCurrentPage(): Boolean {
        val page = pages.getOrNull(pageIndex) ?: return false
        return page.pageInChapter == 0 &&
            bodyTitleMode != ReaderBodyTitleMode.HIDE &&
            page.isVolume != true
    }

    /** 某章节首页分页时需扣除的标题预留高度（卷页标题在正文区渲染，无需预留）。 */
    private fun chapterFirstPageReservePx(chapterIndex: Int, loaded: EbookDocument): Int {
        val chapter = loaded.chapters.getOrNull(chapterIndex) ?: return 0
        if (chapter.isVolume) return 0
        return readView.bodyTitleReserveFor(chapter.title, currentContentWidthPx())
    }

    private suspend fun getOrPaginateChapterPages(
        document: EbookDocument,
        chapterIndex: Int,
        contentWidthPx: Int
    ): List<TextPage> {
        if (document.chapters.isEmpty()) {
            return paginateDocument(document, contentWidthPx)
        }
        val safeChapterIndex = clampChapterToUnlocked(chapterIndex, document).coerceIn(0, document.chapters.lastIndex)
        val contentHeightPx = currentNoTitleContentHeightPx()
        val firstPageReservePx = chapterFirstPageReservePx(safeChapterIndex, document)
        val cacheKey = ReaderChapterPageCacheKey(
            chapterIndex = safeChapterIndex,
            contentWidthPx = contentWidthPx,
            contentHeightPx = contentHeightPx,
            firstPageReservePx = firstPageReservePx
        )
        chapterPageCache[cacheKey]?.let { cachedPages ->
            logDebug(LEGADO_READER_LOG_TAG) {
                "chapterPageCache hit chapter=$safeChapterIndex pages=${cachedPages.size}"
            }
            return cachedPages
        }
        val factory = buildTextPageFactory()
        val pages = withContext(Dispatchers.Default) {
            factory.createChapterPages(
                document = document,
                chapterIndex = safeChapterIndex,
                contentWidthPx = contentWidthPx,
                contentHeightPx = contentHeightPx,
                firstPageReservePx = firstPageReservePx.toFloat()
            )
        }
        chapterPageCache[cacheKey] = pages
        return pages
    }

    private fun currentChapterPageCacheKey(chapterIndex: Int): ReaderChapterPageCacheKey {
        val loaded = document
        val pageWidth = currentContentWidthPx()
        return ReaderChapterPageCacheKey(
            chapterIndex = chapterIndex,
            contentWidthPx = pageWidth,
            contentHeightPx = currentNoTitleContentHeightPx(),
            firstPageReservePx = loaded?.let { chapterFirstPageReservePx(chapterIndex, it) } ?: 0
        )
    }

    private fun preloadAdjacentChapters(
        document: EbookDocument,
        centerChapterIndex: Int,
        contentWidthPx: Int
    ) {
        if (document.chapters.size <= 1) return
        val safeCenter = clampChapterToUnlocked(centerChapterIndex, document)
        pruneChapterPageCache(safeCenter, contentWidthPx, currentNoTitleContentHeightPx(), document)
        chapterPreloadJob?.cancel()
        chapterPreloadJob = lifecycleScope.launch {
            listOf(safeCenter - 1, safeCenter + 1)
                .filter { it in document.chapters.indices }
                .filter { isChapterUnlocked(it, document) }
                .forEach { chapterIndex ->
                    val startMs = SystemClock.elapsedRealtime()
                    runCatching {
                        getOrPaginateChapterPages(
                            document = document,
                            chapterIndex = chapterIndex,
                            contentWidthPx = contentWidthPx
                        )
                    }.onSuccess { loadedPages ->
                        logDebug(LEGADO_READER_LOG_TAG) {
                            "chapterPreload done=${SystemClock.elapsedRealtime() - startMs}ms " +
                            "chapter=$chapterIndex pages=${loadedPages.size}"
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) return@launch
                        Log.w(LEGADO_READER_LOG_TAG, "chapterPreload failed chapter=$chapterIndex", error)
                    }
                }
        }
    }

    private fun pruneChapterPageCache(
        centerChapterIndex: Int,
        contentWidthPx: Int,
        contentHeightPx: Int,
        loaded: EbookDocument
    ) {
        chapterPageCache.keys.removeAll { key ->
            key.contentWidthPx != contentWidthPx ||
                key.contentHeightPx != contentHeightPx ||
                key.firstPageReservePx != chapterFirstPageReservePx(key.chapterIndex, loaded) ||
                abs(key.chapterIndex - centerChapterIndex) > 2
        }
    }

    private fun clearChapterPageCache() {
        chapterPreloadJob?.cancel()
        chapterPreloadJob = null
        chapterPageCache.clear()
    }

    private fun renderCurrentPage(
        forward: Boolean = true,
        persistAnchor: Boolean = false
    ) {
        resumeImagePauseIfPageLeft()
        val normalPage = pages.getOrNull(pageIndex)
        val page = normalPage
        if (page == null) {
            hideCrossPageCueWindow()
            readView.setPage(
                TextPage(
                    title = currentReaderTitle(),
                    text = LEGADO_READER_DEFAULT_PARAGRAPHS.joinToString("\n\n"),
                    totalPages = 1
                )
            )
            return
        }
        val match = currentPageCueMatch(page)
        val highlight = highlightTextPage(page, match)
        val currentSearchHit = searchHits.getOrNull(searchHitIndex)
        val searchHighlight = searchQuery
            ?.takeIf { currentSearchHit?.chapterIndex == page.chapterIndex }
            ?.let { query ->
                val absoluteStart = currentSearchHit?.chapterPosition ?: -1
                val start = if (absoluteStart >= page.charStart && absoluteStart < page.charEnd) {
                    absoluteStart - page.charStart
                } else {
                    page.text.indexOf(query, ignoreCase = true)
                }
                start.takeIf { it >= 0 }?.let { it until (it + query.length) }
            }
        readView.setPage(page, highlight, searchHighlight, forward = forward)
        updateCrossPageCueWindow(page, match)
        updateProgressSeekBar()
        updateChapterTitleSurfaces()
        if (persistAnchor) {
            persistReaderAnchor(currentPageAnchor(includeCueMatch = false))
        }
    }

    private fun persistReaderAnchor(anchor: ReaderPageAnchor?) {
        persistReaderSettings(updateAnchor = true, anchorOverride = anchor)
    }

    private fun persistReaderSettingsWithCurrentAnchor(reason: String) {
        val anchor = currentPageAnchor(includeCueMatch = false)
        persistReaderSettings(updateAnchor = anchor != null, anchorOverride = anchor)
    }

    private fun useM4bChapterSource(): Boolean {
        return chapterSourceMode == ReaderChapterSourceMode.M4B && m4bChapters.isNotEmpty()
    }

    private fun currentChapterSourceSummary(): String {
        return if (chapterSourceMode == ReaderChapterSourceMode.M4B) {
            readerString(R.string.reader_chapter_source_m4b)
        } else {
            readerString(R.string.reader_chapter_source_book)
        }
    }

    private fun ensureM4bChaptersLoaded(onLoaded: ((Boolean) -> Unit)? = null) {
        val uri = audioUri
        if (uri == null) {
            m4bChapters = emptyList()
            onLoaded?.invoke(false)
            return
        }
        if (m4bChapters.isNotEmpty()) {
            onLoaded?.invoke(true)
            return
        }
        if (m4bChapterLoadJob?.isActive == true) {
            val activeJob = m4bChapterLoadJob
            if (onLoaded != null) {
                activeJob?.invokeOnCompletion {
                    lifecycleScope.launch {
                        onLoaded(m4bChapters.isNotEmpty())
                    }
                }
            }
            return
        }
        m4bChapterLoadJob = lifecycleScope.launch {
            val chapters = withContext(Dispatchers.IO) {
                loadM4bChapters(this@LegadoReaderActivity, contentResolver, uri)
            }
            m4bChapters = chapters
            onLoaded?.invoke(chapters.isNotEmpty())
            if (chapters.isNotEmpty()) {
                updateProgressSeekBar()
                updateChapterTitleSurfaces()
            }
        }
    }
    private fun currentM4bChapterIndex(): Int {
        if (m4bChapters.isEmpty()) return 0
        val positionMs = currentAudioPositionMs() ?: 0L
        return m4bChapters.indexOfLast { it.startMs <= positionMs }
            .coerceAtLeast(0)
            .coerceAtMost(m4bChapters.lastIndex)
    }

    private fun seekToM4bChapter(targetIndex: Int) {
        val target = m4bChapters.getOrNull(targetIndex) ?: return
        seekReaderAudioToTarget(
            targetMs = target.startMs,
            preferNextMatchedCueForText = true
        )
    }

    private fun currentAudioDurationMs(): Long? {
        val currentPlayer = player
        val clip = activeAudioClipRangeMs()
        if (clip != null) {
            // 裁剪窗口下 player.duration 只是窗口长度（不是整轨时长），
            // 用缓存/存档里的整轨时长，避免进度条与进度存档把「本页窗口」当成整本书
            lastKnownFullAudioDurationMs.takeIf { it > 0L }?.let { return it }
            pendingAudioRestoreDurationMs.takeIf { it > 0L }?.let { return it }
            // 实在不知道整轨时长时退回窗口终点（旧行为：至少不会把当前位置截断掉）
            return clip.second
        }
        val durationMs = when {
            currentPlayer != null && currentPlayer.duration > 0L -> currentPlayer.duration
            pendingAudioRestoreDurationMs > 0L -> pendingAudioRestoreDurationMs
            else -> 0L
        }
        if (durationMs > 0L) {
            lastKnownFullAudioDurationMs = durationMs
        }
        return durationMs.takeIf { it > 0L }
    }

    private fun seekReaderAudioToTarget(
        targetMs: Long,
        preferNextMatchedCueForText: Boolean = false
    ) {
        // 用户主动 seek（进度条/章节跳转）：解除读完此页暂停并继续播放
        clearPageEndPauseForSeek()
        if (audioCueLoopClipActive) {
            disableAudioCueLoop(updateUi = true)
        }
        val currentPlayer = player ?: return
        val durationMs = currentAudioDurationMs()
        val safeTargetMs = if (durationMs != null) {
            targetMs.coerceIn(0L, durationMs)
        } else {
            targetMs.coerceAtLeast(0L)
        }
        val initialDisplayMs = if (preferNextMatchedCueForText) {
            chapterSeekTextSyncPositionMs(safeTargetMs)
        } else {
            safeTargetMs
        }
        val resumePlayback = isAudioPlaybackRequested()
        val playbackSpeed = currentAudioPlaybackSpeed()
        val generation = markAudioSeekSyncTarget(
            targetMs = safeTargetMs,
            displayMs = initialDisplayMs
        )
        seekAudioPlayerTo(currentPlayer, safeTargetMs)
        activeCueIndex = -1
        publishReaderPlaybackBridgeSnapshot(notifyState = false)
        BookReaderFloatingBridge.notifyPlaybackPosition(safeTargetMs)
        persistAudioPlaybackSnapshotAt(safeTargetMs)
        if (cues.isNotEmpty()) {
            syncToAudioPositionAt(initialDisplayMs, allowPageJump = true, forceReveal = true)
        } else {
            loadSrtSyncIfNeeded {
                val loadedDisplayMs = if (preferNextMatchedCueForText) {
                    chapterSeekTextSyncPositionMs(safeTargetMs)
                } else {
                    safeTargetMs
                }
                if (generation == audioSeekSyncGeneration && audioSeekSyncTargetMs != null) {
                    audioSeekSyncDisplayMs = loadedDisplayMs
                }
                syncToAudioPositionAt(loadedDisplayMs, allowPageJump = true, forceReveal = true)
            }
        }
        verifyReaderAudioSeek(
            currentPlayer = currentPlayer,
            targetMs = safeTargetMs,
            generation = generation,
            resumePlayback = resumePlayback,
            playbackSpeed = playbackSpeed
        )
        updateProgressSeekBar()
        updateChapterTitleSurfaces()
    }

    private fun markAudioSeekSyncTarget(targetMs: Long, displayMs: Long): Long {
        val generation = audioSeekSyncGeneration + 1L
        audioSeekSyncGeneration = generation
        audioSeekSyncTargetMs = targetMs
        audioSeekSyncDisplayMs = displayMs
        val displayGapMs = (displayMs - targetMs).coerceAtLeast(0L)
        audioSeekSyncTargetUntilElapsedMs = SystemClock.elapsedRealtime() +
            AUDIO_SEEK_SYNC_LOCK_MS.coerceAtLeast(displayGapMs + AUDIO_SEEK_SYNC_LOCK_AFTER_DISPLAY_MS)
        pendingAudioSyncLoadAnchor = null
        return generation
    }

    private fun verifyReaderAudioSeek(
        currentPlayer: ExoPlayer,
        targetMs: Long,
        generation: Long,
        resumePlayback: Boolean,
        playbackSpeed: Float
    ) {
        readView.postDelayed({
            if (player !== currentPlayer) return@postDelayed
            if (generation != audioSeekSyncGeneration) return@postDelayed
            val actualMs = playerPositionMs(currentPlayer)
            val deltaMs = abs(actualMs - targetMs)
            if (deltaMs <= AUDIO_SEEK_FALLBACK_TOLERANCE_MS) {
                maybeClearAudioSeekSyncTarget(generation, actualMs)
                verifyReaderAudioSeekSettled(currentPlayer, targetMs, generation, resumePlayback, playbackSpeed)
                return@postDelayed
            }
            recoverReaderAudioSeek(currentPlayer, targetMs, generation, resumePlayback, playbackSpeed)
        }, AUDIO_SEEK_VERIFY_DELAY_MS)
    }

    private fun verifyReaderAudioSeekSettled(
        currentPlayer: ExoPlayer,
        targetMs: Long,
        generation: Long,
        resumePlayback: Boolean,
        playbackSpeed: Float
    ) {
        readView.postDelayed({
            if (player !== currentPlayer || generation != audioSeekSyncGeneration) return@postDelayed
            if (audioSeekSyncTargetMs == null) return@postDelayed
            val actualMs = playerPositionMs(currentPlayer)
            val displayMs = audioSeekSyncDisplayMs ?: targetMs
            val staleOldPosition = actualMs > displayMs + AUDIO_SEEK_STALE_POSITION_TOLERANCE_MS
            if (staleOldPosition) {
                recoverReaderAudioSeek(currentPlayer, targetMs, generation, resumePlayback, playbackSpeed)
                return@postDelayed
            }
            maybeClearAudioSeekSyncTarget(generation, actualMs)
        }, AUDIO_SEEK_SETTLE_VERIFY_DELAY_MS)
    }

    private fun recoverReaderAudioSeek(
        currentPlayer: ExoPlayer,
        targetMs: Long,
        generation: Long,
        resumePlayback: Boolean,
        playbackSpeed: Float
    ) {
        if (generation != audioSeekSyncGeneration) return
        currentPlayer.setMediaItem(currentAudioMediaItem(), audioPositionInClipFor(targetMs))
        currentPlayer.prepare()
        currentPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
        if (resumePlayback) {
            currentPlayer.playWhenReady = true
            currentPlayer.play()
        } else {
            currentPlayer.pause()
        }
        publishReaderPlaybackBridgeSnapshot(notifyState = true)
        BookReaderFloatingBridge.notifyPlaybackPosition(targetMs)
        if (cues.isNotEmpty()) {
            syncToAudioPositionAt(audioSeekSyncDisplayMs ?: targetMs, allowPageJump = true, forceReveal = true)
        }
        readView.postDelayed({
            if (player !== currentPlayer || generation != audioSeekSyncGeneration) return@postDelayed
            val actualMs = playerPositionMs(currentPlayer)
            val deltaMs = abs(actualMs - targetMs)
            if (deltaMs <= AUDIO_SEEK_FALLBACK_TOLERANCE_MS) {
                maybeClearAudioSeekSyncTarget(generation, actualMs)
                verifyReaderAudioSeekSettled(currentPlayer, targetMs, generation, resumePlayback, playbackSpeed)
            }
        }, AUDIO_SEEK_RECOVER_VERIFY_DELAY_MS)
        updateProgressSeekBar()
    }

    private fun lockedAudioSeekSyncTargetMs(): Long? {
        val targetMs = audioSeekSyncTargetMs ?: return null
        val displayMs = audioSeekSyncDisplayMs ?: targetMs
        currentAudioPositionMs()?.let { actualMs ->
            if (maybeClearAudioSeekSyncTarget(audioSeekSyncGeneration, actualMs)) {
                return null
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (now <= audioSeekSyncTargetUntilElapsedMs) return displayMs
        clearAudioSeekSyncTarget(audioSeekSyncGeneration)
        return null
    }

    private fun maybeClearAudioSeekSyncTarget(generation: Long, actualMs: Long): Boolean {
        if (generation != audioSeekSyncGeneration) return true
        val targetMs = audioSeekSyncTargetMs ?: return true
        val displayMs = audioSeekSyncDisplayMs ?: targetMs
        val displayReached = actualMs >= displayMs &&
            actualMs - displayMs <= AUDIO_SEEK_DISPLAY_CLEAR_TOLERANCE_MS
        val noSeparateDisplayTarget = abs(displayMs - targetMs) <= AUDIO_SEEK_FALLBACK_TOLERANCE_MS
        val targetReached = noSeparateDisplayTarget &&
            actualMs >= targetMs &&
            actualMs - targetMs <= AUDIO_SEEK_DISPLAY_CLEAR_TOLERANCE_MS
        if (displayReached || targetReached) {
            clearAudioSeekSyncTarget(generation)
            return true
        }
        return false
    }

    private fun clearAudioSeekSyncTarget(generation: Long) {
        if (generation != audioSeekSyncGeneration) return
        if (audioSeekSyncTargetMs == null) return
        audioSeekSyncTargetMs = null
        audioSeekSyncDisplayMs = null
        audioSeekSyncTargetUntilElapsedMs = 0L
    }

    private fun updateProgressSeekBar() {
        if (pages.isEmpty()) return
        if (chapterSeekBarTracking) return
        if (!progressByChapter) {
            val lastChapterIndex = lastUnlockedChapterIndex(document)
            val currentChapterIndex = pages.getOrNull(pageIndex)?.chapterIndex ?: 0
            chapterSeekBar.max = lastChapterIndex.coerceAtLeast(0)
            chapterSeekBar.progress = currentChapterIndex.coerceIn(0, chapterSeekBar.max)
            return
        }
        val page = pages.getOrNull(pageIndex) ?: return
        val chapterPages = pages.filter { it.chapterIndex == page.chapterIndex }
        chapterSeekBar.max = (chapterPages.size - 1).coerceAtLeast(0)
        chapterSeekBar.progress = page.pageInChapter.coerceIn(0, chapterSeekBar.max)
    }

    private fun seekProgressToPageIndex(progress: Int): Int {
        val currentChapter = pages.getOrNull(pageIndex)?.chapterIndex ?: return pageIndex
        val chapterStart = pages.indexOfFirst { it.chapterIndex == currentChapter }
        if (chapterStart < 0) return pageIndex
        return (chapterStart + progress).coerceIn(chapterStart, pages.lastIndex)
    }

    private fun confirmOrJumpToChapterFromSeekBar(targetChapter: Int) {
        val currentChapter = pages.getOrNull(pageIndex)?.chapterIndex ?: 0
        val lastChapter = lastUnlockedChapterIndex(document)
        val safeTarget = targetChapter.coerceIn(0, lastChapter.coerceAtLeast(0))
        if (targetChapter > safeTarget) {
            showSimulatedReadingLockedToast()
        }
        if (safeTarget == currentChapter || confirmSkipToChapter) {
            jumpToChapterFromSeekBar(safeTarget, currentChapter)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.reader_chapter_jump_confirm_title)
            .setMessage(R.string.reader_chapter_jump_confirm_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                confirmSkipToChapter = true
                jumpToChapterFromSeekBar(safeTarget, currentChapter)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                updateProgressSeekBar()
            }
            .setOnCancelListener {
                updateProgressSeekBar()
            }
            .show()
    }

    private fun jumpToChapterFromSeekBar(targetChapter: Int, currentChapter: Int) {
        showAnchorOrLoad(
            anchor = ReaderPageAnchor(targetChapter, 0),
            forward = targetChapter >= currentChapter
        )
    }

    private fun highlightTextPage(page: TextPage, match: EbookCueMatch?): IntRange? {
        if (match == null || match.chapterIndex != page.chapterIndex) return null
        val absoluteStart = match.rawStart.coerceAtLeast(page.charStart)
        val absoluteEnd = match.rawEnd.coerceAtMost(page.charEnd)
        if (absoluteEnd <= absoluteStart) return null
        val start = (absoluteStart - page.charStart).coerceIn(0, page.text.length)
        val end = (absoluteEnd - page.charStart).coerceIn(start, page.text.length)
        return (start until end).takeIf { it.first < it.last }
    }

    private fun currentPageCueMatch(page: TextPage): EbookCueMatch? {
        return cueMatchesByCueIndex[activeCueIndex]
            ?.takeIf { it.intersects(page) }
            ?: cueMatchesByCueIndex[audioCueIndex]
                ?.takeIf { it.intersects(page) }
    }

    private fun updateCrossPageCueWindow(page: TextPage?, match: EbookCueMatch?) {
        if (!::readView.isInitialized) return
        // 句尾处理开启时（最后一句显示不全放下一页，排版层面处理），
        // 不会出现跨页句子窗口；跨页句子窗口仅在句尾处理关闭时生效
        if (sentenceNoCrossPage) {
            hideCrossPageCueWindow()
            return
        }
        if (
            !crossPageCueWindowEnabled ||
            // 暂停（player 存在但 playWhenReady=false）时保留临时页，
            // 只有播放器不存在（无音频）时才隐藏
            player == null ||
            page == null ||
            match == null ||
            match.chapterIndex != page.chapterIndex
        ) {
            hideCrossPageCueWindow()
            return
        }
        val fullyVisible = match.rawStart >= page.charStart && match.rawEnd <= page.charEnd
        if (fullyVisible) {
            hideCrossPageCueWindow()
            return
        }
        val text = fullCueText(match).takeIf { it.isNotBlank() } ?: run {
            hideCrossPageCueWindow()
            return
        }
        if (crossPageCueWindowMode == CrossPageCueWindowMode.TEMP_PAGE) {
            // 临时页模式：砍掉本页最前面的行，让跨页的最后一句完整显示，
            // 整页覆盖呈现（像正常页面、像本页滚动到页尾看到完整最后一句）。
            val tempPage = buildCrossPageTailPage(page, match) ?: run {
                hideCrossPageCueWindow()
                return
            }
            // 最后一句在临时页坐标系（相对原页 charStart）中的高亮范围
            val highlightStart = (match.rawStart - page.charStart).coerceAtLeast(0)
            val highlightEnd = (match.rawEnd - page.charStart).coerceAtLeast(highlightStart)
            val highlightRange = (highlightStart until highlightEnd).takeIf { it.first < it.last }
            readView.showCrossPageCuePageOverlay(tempPage, highlightRange, fullPage = true)
            return
        }
        val visibleStart = match.rawStart.coerceAtLeast(page.charStart) - page.charStart
        val visibleEnd = match.rawEnd.coerceAtMost(page.charEnd) - page.charStart
        val visibleRange = (visibleStart until visibleEnd).takeIf { it.first < it.last }
        val verticalPage = if (readerLayoutMode == M9LayoutMode.VERTICAL) {
            buildCrossPageCuePage(text)
        } else {
            null
        }
        readView.showCrossPageCueOverlay(text, visibleRange, verticalPage)
    }

    /**
     * 临时页：保持当前页排版格式不变（行内容/行距/字号/段落间距完全一致），
     * 从页首砍掉最前面的若干行（行数 = 让跨页最后一句完整放下所需的最少量），
     * 最后一句延伸到下一页的结尾部分按当前排版追加在剩余内容之后。
     * 效果像"本页向下滚动到能完整看到最后一句"，而不是重新排版。
     */
    private fun buildCrossPageTailPage(
        page: TextPage,
        match: EbookCueMatch,
        cutLines: Boolean = true
    ): TextPage? {
        val chapterText = document?.chapters?.getOrNull(match.chapterIndex)?.text ?: return null
        // 临时页只用于整页覆盖模式：排版尺寸 = 正文区完整尺寸（与正常页面一致，
        // 不减去小窗模式的 16dp 余量，保证行宽、行尾位置与本页完全一致）
        val contentWidth = readView.contentWidth.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - dp(44))
        val contentHeight = readView.contentHeight.takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels - dp(220))
        val isVertical = readerLayoutMode == M9LayoutMode.VERTICAL
        val baseLines = page.lines
        if (baseLines.isEmpty()) return null
        // 最后一句的完整结束位置（可能延伸到下一页）
        val tailEnd = maxOf(match.rawEnd, page.charEnd).coerceIn(page.charStart, chapterText.length)
        val tailStart = page.charEnd.coerceIn(page.charStart, tailEnd)
        val rawTail = chapterText.substring(tailStart, tailEnd)
        val leadingTrim = rawTail.indexOfFirst { !it.isWhitespace() }
            .let { if (it < 0) rawTail.length else it }
        val tailText = rawTail.trim()
        // 排版文本在章节中的实际起点（trim 后）
        val tailBase = tailStart + leadingTrim
        if (tailText.isBlank()) return null
        // charEnd 断在段落中间时，延伸文本首行不应按段落首行缩进
        val midParagraph = tailBase > page.charStart && chapterText.getOrNull(tailBase - 1) != '\n'
        val factory = if (midParagraph) {
            buildTextPageFactory(paragraphIndentOverride = "")
        } else {
            buildTextPageFactory()
        }
        // 延伸行沿用原章 ruby：范围内的 span 精确 offset 到 tailText 坐标；
        // 若 offset 后为空但原章有 ruby，保留原列表以保证行高（rubyReserve）与页面一致
        val chapterRuby = document?.chapters?.getOrNull(match.chapterIndex)?.rubySpans.orEmpty()
        val tailRuby = if (chapterRuby.isEmpty()) {
            emptyList()
        } else {
            val offsetSpans = chapterRuby.mapNotNull { span ->
                val newStart = span.start - tailBase
                val newEnd = span.end - tailBase
                if (newEnd <= 0 || newStart >= tailText.length) {
                    null
                } else {
                    span.copy(
                        start = newStart.coerceAtLeast(0),
                        end = newEnd.coerceAtMost(tailText.length),
                        segments = span.segments.mapNotNull { segment ->
                            val s = segment.baseStart - tailBase
                            val e = segment.baseEnd - tailBase
                            if (e <= 0 || s >= tailText.length) {
                                null
                            } else {
                                segment.copy(
                                    baseStart = s.coerceAtLeast(0),
                                    baseEnd = e.coerceAtMost(tailText.length)
                                )
                            }
                        }
                    )
                }
            }
            offsetSpans.ifEmpty { chapterRuby }
        }
        val tailLines = if (tailText.isBlank()) emptyList() else {
            val tempDoc = EbookDocument(
                title = currentReaderTitle(),
                format = "TEXT",
                chapters = listOf(
                    EbookChapter(
                        title = "",
                        text = tailText,
                        rubySpans = tailRuby
                    )
                )
            )
            factory.createChapterPages(tempDoc, chapterIndex = 0, contentWidth, contentHeight)
                .firstOrNull()?.lines.orEmpty()
        }
        if (tailLines.isEmpty()) return null
        // cutLines=true：本页行 + 延伸行压缩进一屏（旧跨页句子窗口临时页/静态整页）；
        // cutLines=false：本页全部行 + 延伸行，内容可超出一屏（可滚动临时页用，已删除，
        // 恢复提示词见 docs/scrollable-tail-page-revival-prompt.md）
        val remaining = if (cutLines) {
            var dropCount = 0
            while (dropCount < baseLines.size) {
                val candidate = baseLines.drop(dropCount)
                val bodyExtent = if (isVertical) {
                    candidate.first().lineBottom - candidate.last().lineTop
                } else {
                    candidate.last().lineBottom - candidate.first().lineTop
                }
                val tailExtent = if (isVertical) {
                    tailLines.first().lineBottom - tailLines.last().lineTop
                } else {
                    tailLines.last().lineBottom - tailLines.first().lineTop
                }
                val fits = if (isVertical) {
                    bodyExtent + tailExtent <= contentWidth.toFloat()
                } else {
                    bodyExtent + tailExtent <= contentHeight.toFloat()
                }
                if (fits) break
                dropCount += 1
            }
            baseLines.drop(dropCount.coerceAtMost(baseLines.size - 1))
        } else {
            baseLines
        }
        // 内容对齐：横排对齐到顶部（y=0）；竖排左对齐（最左列左边缘 = 0，
        // 页首列右边缘 = 内容总宽，初始滚动到右端显示页首，与正常页位置一致）
        val shift = if (isVertical) {
            -remaining.last().lineTop
        } else {
            -remaining.first().lineTop
        }
        val page2 = TextPage(
            index = 0,
            // pageInChapter = 1：覆盖层不显示章节标题占位（避免正文被往下推）
            pageInChapter = 1,
            chapterPageCount = 1,
            chapterIndex = page.chapterIndex,
            chapterSize = page.chapterSize,
            title = "",
            charStart = page.charStart,
            charEnd = tailEnd
        )
        // 剩余行：原排版平移（竖排只平移列位置 lineTop/lineBase/lineBottom，cross 不变）
        remaining.forEach { line ->
            val rebased = line.copy(
                lineTop = line.lineTop + shift,
                lineBase = line.lineBase + shift,
                lineBottom = line.lineBottom + shift,
                crossStart = if (isVertical) line.crossStart else line.crossStart + shift,
                crossEnd = if (isVertical) line.crossEnd else line.crossEnd + shift
            )
            page2.addLine(rebased)
        }
        // 延伸行：拼接到剩余内容之后（横排：下方；竖排：最左列左侧，紧贴无重叠）
        val offset = if (isVertical) {
            // tailLines 是独立排版（最右列右边缘 = contentWidth），
            // 拼接锚点 = 延伸段最右列右边缘 → 剩余段最左列左边缘（紧贴）
            remaining.last().lineTop + shift - tailLines.first().lineBottom
        } else {
            remaining.last().lineBottom + shift
        }
        tailLines.forEach { line ->
            val rebased = line.copy(
                lineTop = line.lineTop + offset,
                lineBase = line.lineBase + offset,
                lineBottom = line.lineBottom + offset,
                crossStart = if (isVertical) line.crossStart else line.crossStart + offset,
                crossEnd = if (isVertical) line.crossEnd else line.crossEnd + offset
            )
            // 延伸行的 source 坐标统一到"相对临时页 charStart(=原页 charStart)"的坐标系
            val sourceDelta = tailBase - page.charStart
            rebased.columns.forEach { column ->
                if (column is TextColumn) {
                    column.sourceStart += sourceDelta
                    column.sourceEnd += sourceDelta
                }
            }
            page2.addLine(rebased)
        }
        page2.height = page2.lines.maxOfOrNull { it.crossEnd } ?: contentHeight.toFloat()
        page2.width = if (isVertical) {
            page2.lines.maxOfOrNull { it.lineBottom }?.minus(page2.lines.minOf { l -> l.lineTop })
                ?: contentWidth.toFloat()
        } else {
            contentWidth.toFloat()
        }
        return page2
    }

    /** 本页最后一句的匹配（页内 rawStart 最大的匹配） */
    private fun lastMatchOnPage(page: TextPage): EbookCueMatch? {
        return cueMatchesByCueIndex.values
            .filter { it.chapterIndex == page.chapterIndex && it.intersects(page) }
            .maxByOrNull { it.rawStart }
    }

    private fun hideCrossPageCueWindow() {
        if (::readView.isInitialized) {
            readView.hideCrossPageCueOverlay()
        }
    }

    private fun fullCueText(match: EbookCueMatch): String {
        val chapterText = document?.chapters?.getOrNull(match.chapterIndex)?.text ?: return ""
        val start = match.rawStart.coerceIn(0, chapterText.length)
        val end = match.rawEnd.coerceIn(start, chapterText.length)
        return chapterText.substring(start, end).trim()
    }

    private fun buildCrossPageCuePage(text: String): TextPage? {
        val contentWidth = (readView.contentWidth - dp(16)).coerceAtLeast(dp(120))
        val contentHeight = (readView.contentHeight - dp(16)).coerceAtLeast(dp(160))
        val cueDocument = EbookDocument(
            title = currentReaderTitle(),
            format = "TEXT",
            chapters = listOf(EbookChapter(title = "", text = text))
        )
        val page = buildTextPageFactory()
            .createChapterPages(cueDocument, chapterIndex = 0, contentWidth, contentHeight)
            .firstOrNull()
            ?: return null
        page.title = ""
        page.globalIndex = 0
        page.totalPages = 1
        page.pageInChapter = 0
        page.chapterPageCount = 1
        return normalizeCrossPageCuePage(page, contentHeight)
    }

    private fun normalizeCrossPageCuePage(page: TextPage, contentHeight: Int): TextPage? {
        if (page.lines.isEmpty()) return null
        val isVertical = readerLayoutMode == M9LayoutMode.VERTICAL
        val minX = if (isVertical) {
            page.lines.minOf { it.lineTop }
        } else {
            page.lines.minOf { line -> line.columns.minOfOrNull { it.start } ?: line.startX }
        }
        val maxX = if (isVertical) {
            page.lines.maxOf { it.lineBottom }
        } else {
            page.lines.maxOf { line -> line.columns.maxOfOrNull { it.end } ?: line.lineEnd }
        }
        val minY = if (isVertical) {
            page.lines.minOf { line -> line.columns.minOfOrNull { it.start } ?: line.crossStart }
        } else {
            page.lines.minOf { it.lineTop }
        }
        val maxY = if (isVertical) {
            page.lines.maxOf { line -> line.columns.maxOfOrNull { it.end } ?: line.crossEnd }
        } else {
            page.lines.maxOf { it.lineBottom }
        }
        val left = minX.coerceAtLeast(0f)
        val top = minY.coerceAtLeast(0f)
        page.lines.forEach { line ->
            if (isVertical) {
                line.lineTop -= left
                line.lineBase -= left
                line.lineBottom -= left
                line.crossStart -= top
                line.crossEnd -= top
                line.columns.forEach { column ->
                    column.start -= top
                    column.end -= top
                }
            } else {
                line.lineTop -= top
                line.lineBase -= top
                line.lineBottom -= top
                line.crossStart -= top
                line.crossEnd -= top
                line.startX -= left
                line.columns.forEach { column ->
                    column.start -= left
                    column.end -= left
                }
            }
        }
        page.width = (maxX - minX).coerceAtLeast(1f)
        page.height = if (isVertical) {
            contentHeight.toFloat()
        } else {
            (maxY - minY).coerceAtLeast(1f)
        }
        page.charStart = 0
        page.charEnd = page.text.length
        return page
    }

    private fun EbookCueMatch.intersects(page: TextPage): Boolean {
        return chapterIndex == page.chapterIndex &&
            rawStart < page.charEnd &&
            rawEnd > page.charStart
    }

    private fun findTextPageForMatch(match: EbookCueMatch): Int? {
        return pages.indexOfFirst { page ->
            page.chapterIndex == match.chapterIndex &&
                match.rawStart >= page.charStart &&
                match.rawStart < page.charEnd
        }.takeIf { it >= 0 }
            ?: pages.indexOfFirst { it.chapterIndex == match.chapterIndex }.takeIf { it >= 0 }
    }

    private fun movePage(delta: Int) {
        if (pages.isEmpty()) return
        val next = (pageIndex + delta).coerceIn(0, pages.lastIndex)
        if (next != pageIndex) {
            pageIndex = next
            activeCueIndex = -1
            renderCurrentPage(
                forward = delta > 0,
                persistAnchor = true
            )
            // 读完此页暂停：翻页后自动继续播放（resume 里会在暂停态重新武装新页的整页裁剪）
            logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
                "pageEndPause: page moved delta=$delta pageIndex=$next pending=$pageEndPausePending"
            }
            resumePlaybackFromPageEndPause()
            return        }
        val currentChapter = pages.getOrNull(pageIndex)?.chapterIndex ?: return
        val targetChapter = currentChapter + if (delta > 0) 1 else -1
        val lastChapter = lastUnlockedChapterIndex(document)
        if (delta > 0 && targetChapter > lastChapter) {
            showSimulatedReadingLockedToast()
            return
        }
        if (targetChapter in 0..lastChapter) {
            showAnchorOrLoad(
                anchor = ReaderPageAnchor(
                    chapterIndex = targetChapter,
                    charPosition = if (delta > 0) 0 else Int.MAX_VALUE
                ),
                forward = delta > 0
            )
        }
    }

    private fun moveChapter(delta: Int) {
        if (useM4bChapterSource()) {
            val currentChapter = currentM4bChapterIndex()
            val targetChapter = (currentChapter + delta).coerceIn(0, m4bChapters.lastIndex.coerceAtLeast(0))
            if (targetChapter != currentChapter) {
                seekToM4bChapter(targetChapter)
            }
            return
        }
        if (pages.isEmpty()) return
        val currentChapter = pages.getOrNull(pageIndex)?.chapterIndex ?: return
        val lastChapter = lastUnlockedChapterIndex(document)
        val rawTargetChapter = currentChapter + delta
        if (delta > 0 && rawTargetChapter > lastChapter) {
            showSimulatedReadingLockedToast()
            return
        }
        val targetChapter = rawTargetChapter.coerceIn(0, lastChapter)
        if (targetChapter != currentChapter) {
            showAnchorOrLoad(
                anchor = ReaderPageAnchor(
                    chapterIndex = targetChapter,
                    charPosition = if (delta > 0) 0 else Int.MAX_VALUE
                ),
                forward = delta > 0
            )
        }
    }

    private fun initAudioPlayerIfNeeded() {
        val uri = audioUri ?: return
        val restoredSnapshot = currentSharedReaderPlaybackKey()?.let { key ->
            loadBookReaderPlaybackSnapshotOrNull(this, key)
        }
        val restoredPositionMs = when {
            pendingAudioRestorePositionMs > 0L -> pendingAudioRestorePositionMs
            restoredSnapshot != null -> restoredSnapshot.positionMs
            else -> 0L
        }.coerceAtLeast(0L)
        val sameSharedAudio = BookReaderPlaybackSession.currentAudioUri() == uri.toString()
        val keepLiveSession = sameSharedAudio && BookReaderPlaybackSession.isPlaybackRequested()
        val forceSeekOnSameAudio = sameSharedAudio && !keepLiveSession && restoredPositionMs > 0L
        logDebug(LEGADO_AUDIO_PROGRESS_LOG_TAG) {
            "restore source=${if (pendingAudioRestorePositionMs > 0L) "pending" else "shared"} " +
            "positionMs=$restoredPositionMs durationMs=${restoredSnapshot?.durationMs ?: pendingAudioRestoreDurationMs} " +
            "updatedAt=${restoredSnapshot?.updatedAtMs ?: 0L} sameAudio=$sameSharedAudio " +
            "keepLive=$keepLiveSession forceSeek=$forceSeekOnSameAudio"
        }
        audioCueIndex = -1
        disableAudioCueLoop(updateUi = false)
        player = BookReaderPlaybackSession.prepareAudioIfNeeded(
            context = this,
            audioUri = uri,
            restorePositionMs = restoredPositionMs,
            forceSeekOnSameAudio = forceSeekOnSameAudio
        )
        attachAudioPlayerLoopListener()
        publishReaderPlaybackBridgeSnapshot(notifyState = true)
        updateAudioControlLabels()
        startSyncLoop()
    }

    private fun attachAudioPlayerLoopListener() {
        val currentPlayer = player ?: return
        audioPlayerLoopListener?.let { currentPlayer.removeListener(it) }
        audioPlayerLoopListener = null
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    scheduleClipResumeStallCheck()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    if (pageEndPauseClipActive) {
                        handlePageEndPauseClipEnded()
                    } else {
                        handleAudioCueLoopClipEnded()
                    }
                }
            }
        }
        currentPlayer.addListener(listener)
        audioPlayerLoopListener = listener
    }

    private fun scheduleClipResumeStallCheck() {
        if (!::readView.isInitialized) return
        readView.postDelayed(
            {
                if (!audioCueLoopEnabled || !audioCueLoopClipActive) return@postDelayed
                val currentPlayer = player ?: return@postDelayed
                if (!currentPlayer.isPlaying) return@postDelayed
                val clipLengthMs = (audioCueLoopClipEndMs - audioCueLoopClipBaseMs).coerceAtLeast(1L)
                if (clipLengthMs <= AUDIO_CUE_LOOP_RESUME_STALL_CHECK_WINDOW_MS) {
                    return@postDelayed
                }
                val rawPosition = currentPlayer.currentPosition.coerceAtLeast(0L)
                if (rawPosition < clipLengthMs - AUDIO_CUE_LOOP_RESUME_STALL_CHECK_WINDOW_MS) {
                    return@postDelayed
                }
                currentPlayer.seekTo(clipLengthMs)
                currentPlayer.play()
            },
            AUDIO_CUE_LOOP_RESUME_STALL_CHECK_DELAY_MS
        )
    }

    private fun toggleAudioControlPanel() {
        moreSettingsPanel.visibility = View.GONE
        audioControlPanel.visibility =
            if (audioControlPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (audioControlPanel.visibility == View.VISIBLE) {
            updateAudioControlLabels()
            syncAudioTimerSelectionFromState()
            loadSrtSyncIfNeeded()
        }
        updateSystemBarSurfaces()
    }

    private fun togglePlaybackBar() {
        playbackBarPinnedVisible = !playbackBarPinnedVisible
        updatePlaybackBarVisibility()
        if (playbackBarPinnedVisible) {
            setReadMenuVisible(false, updateSystemBars = false)
            playbackBar.post {
                val newHeight = playbackBar.height.coerceAtLeast(0)
                if (newHeight > 0) {
                    playbackBarHeightPx = newHeight
                }
                applyReadViewViewportInset(reload = true)
            }
        } else {
            playbackBarHeightPx = 0
            applyReadViewViewportInset(reload = true)
        }
        persistReaderSettings()
        updateAudioControlLabels()
        updateSystemBarSurfaces()
    }

    private fun toggleAudioPlayback() {
        val currentPlayer = player
        if (currentPlayer == null) {
            Toast.makeText(this, R.string.reader_no_audio, Toast.LENGTH_SHORT).show()
            return
        }
        val requested = isAudioPlaybackRequested()
        if (requested) {
            cancelAudioCueRepeatDelay()
            currentPlayer.pause()
        } else {
            clearImagePauseResume()
            // 读完此页暂停：页尾暂停后点播放按钮 = 和翻页同一逻辑（翻到下一页再继续）。
            // 若只把 pending 清掉直接续播，画面仍停在旧页，而"刚播完的那一句"仍被认作
            // "本页最后一句" → 兜底检查会在刚恢复的下一拍又把它停住（表现为"点了播放又停"）。
            if (pageEndPausePending) {
                movePage(1)
                return
            }
            val pendingAction = audioCueLoopPauseAction
            val pendingCueIndex = audioCueLoopPauseCueIndex
            audioCueLoopPauseAction = null
            audioCueLoopPauseCueIndex = -1
            val handled = when (pendingAction) {
                AudioCueLoopPauseAction.SWITCH_CUE -> {
                    val targetIndex = pendingCueIndex.takeIf { it in cues.indices }
                    if (targetIndex != null) {
                        switchAudioCueLoopToCue(targetIndex)
                        true
                    } else {
                        false
                    }
                }
                AudioCueLoopPauseAction.END_LOOP -> {
                    endAudioCueLoopAt(audioCueLoopClipEndMs)
                    true
                }
                else -> false
            }
            if (!handled) {
                if (audioCueLoopClipActive && currentPlayer.playbackState == Player.STATE_ENDED) {
                    currentPlayer.seekTo(0L)
                }
                // 暂停态按播放：先把本页整页裁剪武装好（此刻无声，重载不会重读），页尾才能精确停
                refreshPageEndPauseArming(currentAudioPositionMs() ?: currentPlayer.currentPosition)
                currentPlayer.play()
            }
        }
        publishReaderPlaybackBridgeSnapshot(notifyState = true)
        updateAudioControlLabels()
        if (isAudioPlaying()) {
            syncToAudioPosition(allowPageJump = true, forceReveal = true)
        }
        if (!isAudioPlaying()) {
            persistAudioPlaybackSnapshot()
        }
    }

    private fun updateAudioControlLabels() {
        val isPlaying = isAudioPlaybackRequested()
        if (!isPlaying) {
            hideCrossPageCueWindow()
        }
        if (::listenActionText.isInitialized) {
            listenActionText.text =
                if (isPlaying) readerString(R.string.reader_pause) else readerString(R.string.reader_listen)
        }
        if (::audioPlayPauseText.isInitialized) {
            audioPlayPauseText.text =
                if (isPlaying) readerString(R.string.reader_pause) else readerString(R.string.play)
        }
        if (::playbackBarToggleButton.isInitialized) {
            playbackBarToggleButton.setImageResource(
                if (isPlaying) R.drawable.reader_ic_pause_24dp else R.drawable.reader_ic_play_24dp
            )
        }
        updateAudioCueLoopLabel()
        updateAudioTimerValueLabel()
    }

    private fun setAudioTimer(minutes: Int, showToast: Boolean = true) {
        player?.volume = 1f
        audioStopAtMs = System.currentTimeMillis() + minutes * 60_000L
        audioTimerSelectedMinutes = minutes.coerceIn(0, AUDIO_TIMER_MAX_MINUTES)
        if (showToast) {
            Toast.makeText(this, getString(R.string.reader_stop_after_minutes, minutes), Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearAudioTimer(showToast: Boolean = true) {
        audioStopAtMs = null
        audioTimerSelectedMinutes = 0
        player?.volume = 1f
        if (showToast) {
            Toast.makeText(this, readerString(R.string.reader_timer_closed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun applySelectedAudioTimer(showToast: Boolean = true) {
        val minutes = audioTimerSelectedMinutes.coerceIn(0, AUDIO_TIMER_MAX_MINUTES)
        if (minutes <= 0) {
            clearAudioTimer(showToast = showToast)
        } else {
            setAudioTimer(minutes, showToast = showToast)
        }
        updateAudioTimerValueLabel()
    }

    private fun syncAudioTimerSelectionFromState() {
        audioTimerSelectedMinutes = currentAudioTimerRemainingMinutes()
            .takeIf { it > 0 }
            ?: loadBookReaderSleepOptions(this).defaultTimerMinutes.coerceIn(0, AUDIO_TIMER_MAX_MINUTES)
        if (::audioTimerSeekBar.isInitialized) {
            audioTimerSeekBar.progress = audioTimerSelectedMinutes.coerceIn(0, audioTimerSeekBar.max)
        }
        updateAudioTimerValueLabel()
    }

    private fun saveDefaultAudioTimerMinutes() {
        val current = loadBookReaderSleepOptions(this)
        saveBookReaderSleepOptions(
            context = this,
            exitControlModeWhenDone = current.exitControlModeWhenDone,
            disconnectBluetoothWhenDone = current.disconnectBluetoothWhenDone,
            fadeOutAudioWhenDone = current.fadeOutAudioWhenDone,
            defaultTimerMinutes = audioTimerSelectedMinutes.coerceIn(0, AUDIO_TIMER_MAX_MINUTES)
        )
        Toast.makeText(this, R.string.reader_timer_default_saved, Toast.LENGTH_SHORT).show()
    }

    private fun showAudioTimerPresetDialog() {
        val presetMinutes = intArrayOf(0, 5, 10, 15, 30, 60, 90, 180)
        val labels = presetMinutes.map { getString(R.string.reader_timer_minutes_value, it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.bookreader_sleep_timer)
            .setItems(labels) { _, which ->
                val minutes = presetMinutes.getOrElse(which) { 0 }.coerceIn(0, AUDIO_TIMER_MAX_MINUTES)
                audioTimerSelectedMinutes = minutes
                if (::audioTimerSeekBar.isInitialized) {
                    audioTimerSeekBar.progress = minutes
                } else {
                    applySelectedAudioTimer(showToast = false)
                }
            }
            .show()
    }

    private fun updateAudioTimerValueLabel() {
        if (!::audioTimerValueText.isInitialized) return
        val minutes = audioTimerSelectedMinutes.coerceIn(0, AUDIO_TIMER_MAX_MINUTES)
        audioTimerValueText.text = getString(R.string.reader_timer_minutes_value, minutes)
    }

    private fun currentAudioTimerRemainingMinutes(): Int {
        val stopAt = audioStopAtMs ?: return 0
        val remainingMs = stopAt - System.currentTimeMillis()
        if (remainingMs <= 0L) return 0
        return ((remainingMs + 59_999L) / 60_000L).toInt().coerceIn(0, AUDIO_TIMER_MAX_MINUTES)
    }

    private fun toggleAudioCueLoop() {
        if (audioCueLoopEnabled) {
            disableAudioCueLoop(updateUi = true)
            return
        }
        if (pauseAfterPageEnd) {
            // 按钮只切「本次阅读」的运行状态，不动设置——设置决定这个按钮管哪个功能
            pageEndPauseActive = !pageEndPauseActive
            if (pageEndPauseActive) {
                // 先取绝对位置：清掉重复状态之前本地旗标还在，此时 currentAudioPositionMs() 才对；
                // 清完旗标但没换 MediaItem 时，读出来的会是"裁剪内相对位置"。
                val absoluteMs = currentAudioPositionMs()
                if (audioCueLoopEnabled) {
                    // 读完此页暂停与逐句重复共用同一个播放窗口：只关掉重复、不重载，
                    // 紧接着的页尾武装会做唯一一次重载（省掉一次可感知的 prepare）
                    disableAudioCueLoop(updateUi = true, reloadAudio = false)
                }
                // 暂停态就地把本页整页裁剪武装好（播放中则退化为句尾监视兜底）
                absoluteMs?.let { refreshPageEndPauseArming(it) }
            } else {
                releasePageEndPauseClipIfActive()
            }
            logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
                "pageEndPause: repeat button toggle active=$pageEndPauseActive " +
                    "(setting=$pauseAfterPageEnd)"
            }
            updateAudioCueLoopLabel()
            return
        }
        val currentPlayer = player
        if (currentPlayer == null) {
            Toast.makeText(this, R.string.reader_no_audio, Toast.LENGTH_SHORT).show()
            return
        }
        if (cues.isEmpty()) {
            if (srtUri == null) {
                Toast.makeText(this, R.string.reader_no_srt, Toast.LENGTH_SHORT).show()
                return
            }
            loadSrtSyncIfNeeded(force = true) { success ->
                if (success) {
                    toggleAudioCueLoop()
                } else {
                    Toast.makeText(
                        this,
                        srtLoadError ?: readerString(R.string.reader_srt_parse_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            Toast.makeText(this, R.string.reader_srt_loading, Toast.LENGTH_SHORT).show()
            return
        }
        audioCueLoopEnabled = true
        val targetCueIndex = currentCueLoopIndexForPosition(currentAudioPositionMs() ?: 0L)
        if (targetCueIndex !in cues.indices || !updateAudioCueLoopWindow(targetCueIndex, startFromCueStart = true)) {
            audioCueLoopEnabled = false
            return
        }
        updateAudioCueLoopLabel()
    }

    /**
     * 关闭逐句重复（并顺带解除页尾裁剪：两者共用同一个播放窗口）。
     * [reloadAudio]=false 只清状态、不换 MediaItem —— 给"紧接着一定会重新武装"的调用方用
     * （否则会白换一次，用户能感觉到那一次 `prepare` 的延迟）。
     */
    private fun disableAudioCueLoop(updateUi: Boolean, reloadAudio: Boolean = true) {
        cancelAudioCueRepeatDelay()
        // 页尾整页裁剪也用同一个播放窗口：这里一并解除，避免残留一段被裁短的音频
        val wasClipActive = audioCueLoopClipActive || pageEndPauseRange != null
        val absoluteMs = currentAudioPositionMs() ?: audioCueLoopClipBaseMs
        audioCueLoopEnabled = false
        audioCueLoopWindow = null
        audioCueLoopClipActive = false
        pageEndPauseClipActive = false
        pageEndPauseRange = null
        cancelPageEndPauseWatch()
        audioCueLoopPauseAction = null
        audioCueLoopPauseCueIndex = -1
        audioCueRepeatRemainingCount = initialAudioCueRepeatRemainingCount()
        if (wasClipActive && reloadAudio) {
            logDebug(AUDIO_CUE_LOOP_CLIP_LOG_TAG) {
                "clipDisabled restoreAt=$absoluteMs playWhenReady=${player?.playWhenReady}"
            }
            reloadAudioAt(absoluteMs)
        }
        if (updateUi) {
            updateAudioCueLoopLabel()
        }
    }

    private fun updateAudioCueLoopWindow(
        cueIndex: Int,
        startFromCueStart: Boolean = false,
        forcePlay: Boolean = true
    ): Boolean {
        val cue = cues.getOrNull(cueIndex) ?: return false
        cancelAudioCueRepeatDelay()
        applyAudioCueLoopWindow(cueIndex, cue, startFromCueStart, forcePlay)
        return true
    }

    private fun applyAudioCueLoopWindow(
        cueIndex: Int,
        cue: EbookSrtCue?,
        startFromCueStart: Boolean = false,
        forcePlay: Boolean = true
    ) {
        cue ?: return
        val startMs = cue.startMs.coerceAtLeast(0L)
        val endMs = cue.endMs.coerceAtLeast(startMs + 1L)
        audioCueLoopWindow = startMs to endMs
        audioCueIndex = cueIndex
        audioCueLoopPauseAction = null
        audioCueLoopPauseCueIndex = -1
        audioCueRepeatRemainingCount = initialAudioCueRepeatRemainingCount()
        updateAudioCueLoopLabel()
        if (audioCueLoopEnabled) {
            applyAudioCueLoopClip(startMs, endMs, startFromCueStart, forcePlay)
        }
    }

    private fun applyAudioCueLoopClip(
        startMs: Long,
        endMs: Long,
        startFromCueStart: Boolean,
        forcePlay: Boolean = true
    ) {
        val currentPlayer = player ?: return
        val sourceAbsoluteMs = currentAudioPositionMs() ?: startMs
        audioCueLoopClipBaseMs = startMs
        audioCueLoopClipEndMs = endMs
        audioCueLoopClipActive = true
        currentPlayer.repeatMode = Player.REPEAT_MODE_OFF
        val positionInClip = if (startFromCueStart) {
            0L
        } else {
            (sourceAbsoluteMs.coerceIn(startMs, endMs) - startMs).coerceAtLeast(0L)
        }
        logDebug(AUDIO_CUE_LOOP_CLIP_LOG_TAG) {
            "clipApply cueIndex=$audioCueIndex startMs=$startMs endMs=$endMs " +
            "positionInClip=$positionInClip startFromCueStart=$startFromCueStart " +
            "nextCueStart=${cues.getOrNull(audioCueIndex + 1)?.startMs} " +
            "playWhenReady=${currentPlayer.playWhenReady}"
        }
        currentPlayer.setMediaItem(currentAudioMediaItem(), positionInClip)
        currentPlayer.prepare()
        if (startFromCueStart && forcePlay) {
            currentPlayer.play()
        }
        val absoluteMs = startMs + positionInClip
        BookReaderFloatingBridge.notifyPlaybackPosition(absoluteMs)
        publishReaderPlaybackBridgeSnapshot(notifyState = true)
        syncToAudioPositionAt(absoluteMs, allowPageJump = true, forceReveal = true)
    }

    private fun currentAudioMediaItem(): MediaItem {
        val uri = audioUri ?: return MediaItem.EMPTY
        val builder = MediaItem.Builder().setUri(uri)
        val clip = activeAudioClipRangeMs()
        return if (clip != null) {
            builder
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.first)
                        .setEndPositionMs(clip.second)
                        .build()
                )
                .build()
        } else {
            builder.build()
        }
    }

    /**
     * 把播放器换回"当前该有的音频"（按 [activeAudioClipRangeMs] 是否带裁剪）并定位到 [absoluteMs]。
     * 三处解除路径共用：重复解除、销毁前恢复、页尾裁剪解除 —— 换回音频只有这一份写法。
     */
    private fun reloadAudioAt(absoluteMs: Long) {
        val currentPlayer = player ?: return
        if (audioUri == null) return
        val safeMs = absoluteMs.coerceAtLeast(0L)
        currentPlayer.setMediaItem(currentAudioMediaItem(), safeMs)
        currentPlayer.prepare()
        BookReaderFloatingBridge.notifyPlaybackPosition(safeMs)
    }

    private fun restoreAudioCueLoopMediaIfNeeded() {
        if (!audioCueLoopClipActive && pageEndPauseRange == null) return
        val absoluteMs = currentAudioPositionMs() ?: audioCueLoopClipBaseMs
        audioCueLoopClipActive = false
        pageEndPauseClipActive = false
        pageEndPauseRange = null
        cancelPageEndPauseWatch()
        reloadAudioAt(absoluteMs)
    }

    private fun updateAudioCueLoopLabel() {
        if (!::playbackBarRepeatButton.isInitialized) return
        // "开着"的图形分开：逐句重复=repeat-one（重复的就是当前这一句），读完此页暂停=repeat_on
        // （方框底+镂空箭头，按钮的 on 态）。只改颜色/透明度在日间主题里几乎分不出来。
        val active = audioCueLoopEnabled || pageEndPauseActive
        playbackBarRepeatButton.setImageResource(
            when {
                audioCueLoopEnabled -> R.drawable.reader_ic_repeat_one
                pageEndPauseActive -> R.drawable.reader_ic_repeat_on
                else -> R.drawable.reader_ic_repeat
            }
        )
        playbackBarRepeatButton.alpha = if (active) 1f else 0.72f
        playbackBarRepeatButton.imageTintList = ColorStateList.valueOf(
            if (active) NIGHT_ACCENT else MENU_TEXT
        )
        val label = if (pauseAfterPageEnd) {
            // 按钮处于「读完此页暂停」模式：短按反复开关该功能（本次阅读）
            getString(
                if (pageEndPauseActive) {
                    R.string.reader_pause_after_page_end_on
                } else {
                    R.string.reader_pause_after_page_end_off
                }
            )
        } else if (audioCueLoopEnabled) {
            getString(R.string.reader_repeat_enabled_content_description, currentAudioCueRepeatPauseLabel())
        } else {
            getString(R.string.reader_repeat_disabled_content_description)
        }
        playbackBarRepeatButton.contentDescription = label
    }

    private fun showAudioCueRepeatConfigDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(4))
        }
        var useCueDuration = audioCueRepeatPauseUsesCueDuration
        var fixedSeconds = audioCueRepeatFixedPauseSeconds
            .coerceIn(0, MAX_AUDIO_CUE_REPEAT_FIXED_PAUSE_SECONDS)
        var finiteRepeatEnabled = audioCueRepeatFiniteEnabled
        var playbackCount = audioCueRepeatCount.coerceIn(1, MAX_AUDIO_CUE_REPEAT_COUNT)
        var tailPauseEnabled = audioCueRepeatTailPauseEnabled
        var followCueEnabled = audioCueRepeatFollowCueEnabled

        val modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val cueDurationButton = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.reader_repeat_pause_follow_cue)
            textSize = 15f
            setTextColor(MENU_TEXT)
            isChecked = useCueDuration
        }
        val fixedButton = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.reader_repeat_pause_fixed)
            textSize = 15f
            setTextColor(MENU_TEXT)
            isChecked = !useCueDuration
        }
        modeGroup.addView(cueDurationButton)
        modeGroup.addView(fixedButton)

        val fixedLabel = text(
            getString(R.string.reader_repeat_fixed_seconds, fixedSeconds),
            14f,
            MENU_TEXT
        )
        val fixedSeek = SeekBar(this).apply {
            max = MAX_AUDIO_CUE_REPEAT_FIXED_PAUSE_SECONDS
            progress = fixedSeconds
        }
        val finiteSwitch = Switch(this).apply {
            text = getString(R.string.reader_repeat_playback_count_toggle)
            textSize = 15f
            setTextColor(MENU_TEXT)
            isChecked = finiteRepeatEnabled
        }
        val tailPauseSwitch = Switch(this).apply {
            text = getString(R.string.reader_repeat_tail_pause)
            textSize = 15f
            setTextColor(MENU_TEXT)
            isChecked = tailPauseEnabled
        }
        val followCueSwitch = Switch(this).apply {
            text = getString(R.string.reader_repeat_follow_cue)
            textSize = 15f
            setTextColor(MENU_TEXT)
            isChecked = followCueEnabled
        }
        var tailHandlingEnabled = sentenceNoCrossPage
        val tailHandlingSwitch = Switch(this).apply {
            text = getString(R.string.reader_sentence_no_cross_page)
            textSize = 15f
            setTextColor(MENU_TEXT)
            isChecked = tailHandlingEnabled
        }
        val tailHandlingHint = text(
            getString(R.string.reader_sentence_no_cross_page_hint),
            12f,
            0xA62C241B.toInt()
        ).apply {
            visibility = if (tailHandlingEnabled) View.VISIBLE else View.GONE
        }
        var pauseAfterPageEndValue = pauseAfterPageEnd
        val pauseAfterPageEndSwitch = Switch(this).apply {
            text = getString(R.string.reader_pause_after_page_end)
            textSize = 15f
            setTextColor(MENU_TEXT)
            isChecked = pauseAfterPageEndValue
        }
        tailHandlingSwitch.setOnCheckedChangeListener { _, isChecked ->
            tailHandlingEnabled = isChecked
            tailHandlingHint.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        pauseAfterPageEndSwitch.setOnCheckedChangeListener { _, isChecked ->
            pauseAfterPageEndValue = isChecked
        }
        val countLabel = text(
            getString(R.string.reader_repeat_playback_count_value, playbackCount),
            14f,
            MENU_TEXT
        )
        val countSeek = SeekBar(this).apply {
            max = MAX_AUDIO_CUE_REPEAT_COUNT
            progress = playbackCount - 1
        }
        fun setEnabledAlpha(view: View, enabled: Boolean) {
            view.isEnabled = enabled
            view.alpha = if (enabled) 1f else 0.55f
        }
        fun syncFixedEnabled() {
            setEnabledAlpha(fixedLabel, !useCueDuration)
            setEnabledAlpha(fixedSeek, !useCueDuration)
        }
        fun syncCountEnabled() {
            setEnabledAlpha(tailPauseSwitch, finiteRepeatEnabled)
            setEnabledAlpha(countLabel, finiteRepeatEnabled)
            setEnabledAlpha(countSeek, finiteRepeatEnabled)
        }
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            useCueDuration = checkedId == cueDurationButton.id
            syncFixedEnabled()
        }
        finiteSwitch.setOnCheckedChangeListener { _, isChecked ->
            finiteRepeatEnabled = isChecked
            syncCountEnabled()
        }
        tailPauseSwitch.setOnCheckedChangeListener { _, isChecked ->
            tailPauseEnabled = isChecked
        }
        followCueSwitch.setOnCheckedChangeListener { _, isChecked ->
            followCueEnabled = isChecked
        }
        fixedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                fixedSeconds = progress.coerceIn(0, MAX_AUDIO_CUE_REPEAT_FIXED_PAUSE_SECONDS)
                fixedLabel.text = getString(R.string.reader_repeat_fixed_seconds, fixedSeconds)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        countSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                playbackCount = (progress + 1).coerceIn(1, MAX_AUDIO_CUE_REPEAT_COUNT)
                countLabel.text = getString(R.string.reader_repeat_playback_count_value, playbackCount)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        syncFixedEnabled()
        syncCountEnabled()
        content.addView(modeGroup)
        content.addView(fixedLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(34)
        ).apply { topMargin = dp(6) })
        content.addView(fixedSeek, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ))
        content.addView(finiteSwitch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        content.addView(tailPauseSwitch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        content.addView(followCueSwitch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        content.addView(tailHandlingSwitch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        content.addView(tailHandlingHint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })
        content.addView(pauseAfterPageEndSwitch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        content.addView(countLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(34)
        ).apply { topMargin = dp(6) })
        content.addView(countSeek, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ))

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reader_repeat_settings_title)
            .setView(content)
            .setPositiveButton(R.string.reader_dialog_done) { _, _ ->
                audioCueRepeatPauseUsesCueDuration = useCueDuration
                audioCueRepeatFixedPauseSeconds = fixedSeconds
                audioCueRepeatFiniteEnabled = finiteRepeatEnabled
                audioCueRepeatCount = playbackCount
                audioCueRepeatTailPauseEnabled = tailPauseEnabled
                audioCueRepeatFollowCueEnabled = followCueEnabled
                val oldTailHandling = sentenceNoCrossPage
                val oldPauseAfterPageEnd = pauseAfterPageEnd
                sentenceNoCrossPage = tailHandlingEnabled
                pauseAfterPageEnd = pauseAfterPageEndValue
                // 按钮不再管这个功能时，把运行状态一并收掉（不落盘，退出重进即回到默认关）
                if (!pauseAfterPageEndValue) {
                    pageEndPauseActive = false
                }
                val relayoutNeeded = tailHandlingEnabled != oldTailHandling ||
                    pauseAfterPageEndValue != oldPauseAfterPageEnd
                if (!pageEndPauseActive || relayoutNeeded) {
                    // 功能没在生效 / 重新分页会换掉页面与句尾：先解除武装并恢复完整音频，
                    // 重新分页后由 sync 按新的页面重新武装
                    releasePageEndPauseClipIfActive()
                }
                audioCueRepeatRemainingCount = initialAudioCueRepeatRemainingCount()
                // 句尾处理（排版规则）与读完此页暂停需要重新分页才能生效
                if (relayoutNeeded) {
                    logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
                        "settings changed tail=$tailHandlingEnabled pause=$pauseAfterPageEndValue -> relayout"
                    }
                    requestBookRelayout(immediate = true)
                }
                persistReaderSettings(updateAnchor = false)
                updateAudioCueLoopLabel()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attr = attributes
            attr.dimAmount = 0f
            attributes = attr
        }
    }

    private fun currentAudioCueRepeatPauseLabel(): String {
        return if (audioCueRepeatPauseUsesCueDuration) {
            getString(R.string.reader_repeat_pause_follow_cue)
        } else {
            getString(R.string.reader_repeat_fixed_seconds, audioCueRepeatFixedPauseSeconds)
        }
    }

    private fun initialAudioCueRepeatRemainingCount(): Int {
        if (!audioCueRepeatFiniteEnabled) return 0
        return (audioCueRepeatCount.coerceIn(1, MAX_AUDIO_CUE_REPEAT_COUNT) - 1).coerceAtLeast(0)
    }

    private fun audioCueRepeatPauseMs(startMs: Long, endMs: Long, currentPlayer: ExoPlayer): Long {
        return if (audioCueRepeatPauseUsesCueDuration) {
            val speed = currentPlayer.playbackParameters.speed.takeIf { it > 0f } ?: 1f
            ((endMs - startMs).coerceAtLeast(0L).toFloat() / speed).toLong()
        } else {
            audioCueRepeatFixedPauseSeconds
                .coerceIn(0, MAX_AUDIO_CUE_REPEAT_FIXED_PAUSE_SECONDS)
                .toLong() * 1_000L
        }.coerceIn(0L, MAX_AUDIO_CUE_REPEAT_FIXED_PAUSE_SECONDS * 1_000L)
    }

    private fun nextCueRepeatTarget(currentCueIndex: Int): Int? {
        val nextCueIndex = currentCueIndex + 1
        return nextCueIndex.takeIf { it in cues.indices }
    }

    private fun handleAudioCueLoopClipEnded() {
        if (!audioCueLoopEnabled || !audioCueLoopClipActive || audioCueLoopPausedForRepeat) return
        val currentPlayer = player ?: return
        val window = audioCueLoopWindow ?: return
        val startMs = window.first
        val endMs = window.second
        if (audioCueRepeatFiniteEnabled) {
            if (audioCueRepeatRemainingCount <= 0) {
                val currentCueIndex = cues.indexOfFirst { it.startMs == startMs && it.endMs == endMs }
                    .takeIf { it >= 0 }
                    ?: audioCueIndex.takeIf { it in cues.indices }
                    ?: currentCueLoopIndexForPosition(endMs)
                val pauseMs = audioCueRepeatPauseMs(startMs, endMs, currentPlayer)
                val nextCueTarget = if (audioCueRepeatFollowCueEnabled) {
                    nextCueRepeatTarget(currentCueIndex)
                } else {
                    null
                }
                if (nextCueTarget != null) {
                    val nextCueIndex = nextCueTarget
                    if (!audioCueRepeatTailPauseEnabled || pauseMs <= 0L) {
                        switchAudioCueLoopToCue(nextCueIndex)
                    } else {
                        audioCueLoopPauseAction = AudioCueLoopPauseAction.SWITCH_CUE
                        audioCueLoopPauseCueIndex = nextCueIndex
                        launchAudioCueRepeatPause(pauseMs) {
                            switchAudioCueLoopToCue(nextCueIndex)
                        }
                    }
                    return
                }
                if (!audioCueRepeatTailPauseEnabled || pauseMs <= 0L) {
                    endAudioCueLoopAt(endMs)
                    return
                }
                audioCueLoopPauseAction = AudioCueLoopPauseAction.END_LOOP
                launchAudioCueRepeatPause(pauseMs) {
                    endAudioCueLoopAt(endMs)
                }
                return
            }
            audioCueRepeatRemainingCount -= 1
        }
        val pauseMs = audioCueRepeatPauseMs(startMs, endMs, currentPlayer)
        if (pauseMs <= 0L) {
            logDebug(AUDIO_CUE_LOOP_CLIP_LOG_TAG) { "clipRestart immediate cueIndex=$audioCueIndex startMs=$startMs endMs=$endMs" }
            currentPlayer.seekTo(0L)
            currentPlayer.play()
            return
        }
        launchAudioCueRepeatPause(pauseMs) {
            if (!audioCueLoopEnabled || !audioCueLoopClipActive) return@launchAudioCueRepeatPause
            val livePlayer = player ?: return@launchAudioCueRepeatPause
            logDebug(AUDIO_CUE_LOOP_CLIP_LOG_TAG) {
                "clipRestart afterPause cueIndex=$audioCueIndex startMs=$audioCueLoopClipBaseMs " +
                "endMs=$audioCueLoopClipEndMs pauseMs=$pauseMs"
            }
            livePlayer.seekTo(0L)
            livePlayer.play()
            BookReaderFloatingBridge.notifyPlaybackPosition(audioCueLoopClipBaseMs)
            publishReaderPlaybackBridgeSnapshot(notifyState = true)
            syncToAudioPositionAt(audioCueLoopClipBaseMs, allowPageJump = true, forceReveal = false)
        }
    }

    private fun launchAudioCueRepeatPause(pauseMs: Long, block: suspend (ExoPlayer) -> Unit) {
        val generation = ++audioCueRepeatDelayGeneration
        audioCueLoopPausedForRepeat = true
        updateAudioControlLabels()
        audioCueRepeatDelayJob = lifecycleScope.launch {
            try {
                delay(pauseMs)
                if (!audioCueLoopEnabled || generation != audioCueRepeatDelayGeneration) return@launch
                val livePlayer = player ?: return@launch
                block(livePlayer)
            } finally {
                if (generation == audioCueRepeatDelayGeneration) {
                    audioCueLoopPausedForRepeat = false
                    audioCueRepeatDelayJob = null
                    audioCueLoopPauseAction = null
                    audioCueLoopPauseCueIndex = -1
                    updateAudioControlLabels()
                }
            }
        }
    }

    private fun switchAudioCueLoopToCue(cueIndex: Int) {
        val cue = cues.getOrNull(cueIndex) ?: return
        logDebug(AUDIO_CUE_LOOP_CLIP_LOG_TAG) { "clipSwitch cueIndex=$cueIndex startMs=${cue.startMs} endMs=${cue.endMs}" }
        applyAudioCueLoopWindow(cueIndex, cue, startFromCueStart = true)
    }

    private fun endAudioCueLoopAt(absoluteMs: Long) {
        val wasClipActive = audioCueLoopClipActive
        disableAudioCueLoop(updateUi = true)
        val currentPlayer = player ?: return
        logDebug(AUDIO_CUE_LOOP_CLIP_LOG_TAG) {
            "clipEnd loopStop absoluteMs=$absoluteMs wasClipActive=$wasClipActive " +
            "nextCueStart=${cues.getOrNull(audioCueIndex + 1)?.startMs}"
        }
        if (!wasClipActive) {
            currentPlayer.seekTo(absoluteMs.coerceAtLeast(0L))
        }
        currentPlayer.play()
        BookReaderFloatingBridge.notifyPlaybackPosition(absoluteMs)
        publishReaderPlaybackBridgeSnapshot(notifyState = true)
        syncToAudioPositionAt(absoluteMs, allowPageJump = true, forceReveal = false)
    }

    private fun cancelAudioCueRepeatDelay() {
        audioCueRepeatDelayGeneration++
        audioCueRepeatDelayJob?.cancel()
        audioCueRepeatDelayJob = null
        audioCueLoopPausedForRepeat = false
    }

    private fun currentCueLoopIndexForPosition(positionMs: Long): Int {
        val exactIndex = findEbookCueIndexAtTime(cues, positionMs)
        if (exactIndex >= 0) return exactIndex
        return cues.indexOfLast { it.startMs <= positionMs }
    }

    private fun seekToAdjacentCue(delta: Int) {
        val currentPlayer = player
        if (currentPlayer == null) {
            logDebug(READER_PAUSED_SEEK_LOG_TAG) { "legado adjacentCue ignored reason=no-player delta=$delta" }
            Toast.makeText(this, R.string.reader_no_audio, Toast.LENGTH_SHORT).show()
            return
        }
        if (cues.isEmpty()) {
            if (srtUri == null) {
                logDebug(READER_PAUSED_SEEK_LOG_TAG) { "legado adjacentCue ignored reason=no-srt delta=$delta" }
                Toast.makeText(this, R.string.reader_no_srt, Toast.LENGTH_SHORT).show()
                return
            }
            logDebug(READER_PAUSED_SEEK_LOG_TAG) { "legado adjacentCue loading-srt delta=$delta" }
            loadSrtSyncIfNeeded(force = true) { success ->
                if (success) {
                    logDebug(READER_PAUSED_SEEK_LOG_TAG) { "legado adjacentCue retry-after-srt delta=$delta cues=${cues.size}" }
                    seekToAdjacentCue(delta)
                } else {
                    logDebug(READER_PAUSED_SEEK_LOG_TAG) {
                        "legado adjacentCue srt-load-failed delta=$delta error=${srtLoadError.orEmpty().take(80)}"
                    }
                    Toast.makeText(
                        this,
                        srtLoadError ?: readerString(R.string.reader_srt_parse_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            Toast.makeText(this, R.string.reader_srt_loading, Toast.LENGTH_SHORT).show()
            return
        }
        val position = currentAudioPositionMs() ?: 0L
        val exactIndex = findEbookCueIndexAtTime(cues, position)
        val beforeIndex = cues.indexOfLast { it.startMs <= position }
        val baseIndex = if (exactIndex >= 0) exactIndex else beforeIndex
        val targetIndex = findAdjacentCueIndex(baseIndex, delta)
        seekToCueIndex(targetIndex)
    }

    private fun seekToCueIndex(targetIndex: Int, cancelRepeatDelay: Boolean = true) {
        val currentPlayer = player ?: return
        if (targetIndex !in cues.indices) return
        // 用户主动 cue 跳转：解除读完此页暂停并继续播放
        clearPageEndPauseForSeek()
        val targetMs = cues[targetIndex].startMs.coerceAtLeast(0L)
        val targetCue = cues.getOrNull(targetIndex)
        logDebug(READER_PAUSED_SEEK_LOG_TAG) {
            "legado cueJump request targetIndex=$targetIndex targetMs=$targetMs " +
            "audioCue=$audioCueIndex audioCueMs=${cues.getOrNull(audioCueIndex)?.startMs} " +
            "playing=${currentPlayer.isPlaying} " +
            "playWhenReady=${currentPlayer.playWhenReady} state=${currentPlayer.playbackState} " +
            "duration=${currentAudioDurationMs()} cue=${targetCue?.text.orEmpty().replace('\n', ' ').take(48)}"
        }
        val resumeAfterRepeatPause = audioCueLoopPausedForRepeat
        val shouldResume = resumeAfterRepeatPause || currentPlayer.playWhenReady
        if (audioCueLoopEnabled) {
            if (cancelRepeatDelay) {
                updateAudioCueLoopWindow(targetIndex, startFromCueStart = true, forcePlay = shouldResume)
            } else {
                applyAudioCueLoopWindow(targetIndex, targetCue, startFromCueStart = true, forcePlay = shouldResume)
            }
        } else {
            seekAudioPlayerTo(currentPlayer, targetMs)
        }
        logDebug(READER_PAUSED_SEEK_LOG_TAG) {
            "legado cueJump after-seek-immediate targetMs=$targetMs actual=${currentPlayer.currentPosition} " +
            "playing=${currentPlayer.isPlaying} playWhenReady=${currentPlayer.playWhenReady} state=${currentPlayer.playbackState}"
        }
        if (resumeAfterRepeatPause) {
            currentPlayer.play()
        }
        activeCueIndex = if (cueMatchesByCueIndex.containsKey(targetIndex)) targetIndex else -1
        BookReaderFloatingBridge.notifyPlaybackPosition(targetMs)
        persistAudioPlaybackSnapshotAt(targetMs)
        syncToAudioPositionAt(
            positionMs = targetMs,
            allowPageJump = true,
            forceReveal = true,
            preferredCueIndex = targetIndex
        )
        window.decorView.postDelayed(
            {
                logDebug(READER_PAUSED_SEEK_LOG_TAG) {
                    "legado cueJump verify targetMs=$targetMs actual=${currentPlayer.currentPosition} " +
                    "delta=${playerPositionMs(currentPlayer) - targetMs} playing=${currentPlayer.isPlaying} " +
                    "playWhenReady=${currentPlayer.playWhenReady} state=${currentPlayer.playbackState}"
                }
            },
            350L
        )
    }

    private fun findAdjacentCueIndex(fallbackBaseIndex: Int, delta: Int): Int {
        if (cues.isEmpty()) return -1
        val steps = abs(delta).coerceAtLeast(1)
        val baseIndex = when {
            audioCueIndex in cues.indices -> audioCueIndex
            fallbackBaseIndex in cues.indices -> fallbackBaseIndex
            delta < 0 -> cues.lastIndex
            else -> 0
        }
        var targetIndex = baseIndex
        repeat(steps) {
            targetIndex = if (delta < 0) {
                (targetIndex - 1).coerceAtLeast(0)
            } else {
                (targetIndex + 1).coerceAtMost(cues.lastIndex)
            }
        }
        return targetIndex
    }

    private fun currentAudioPositionMs(): Long? {
        val currentPlayer = player ?: return null
        return playerPositionMs(currentPlayer)
    }

    /** 播放器 raw 位置 → 正文绝对位置（与显示侧共用同一处换算；裁剪真值=播放器实配，不是本地旗标）。 */
    private fun playerPositionMs(currentPlayer: ExoPlayer): Long =
        BookReaderPlaybackSession.toAbsolutePositionMs(currentPlayer, currentPlayer.currentPosition)

    /**
     * 给 [currentAudioMediaItem]（本地旗标决定裁不裁）算**窗口内**起点，只用在 `setMediaItem`：那一刻
     * 播放器采用的就是这份"意图"，所以口径跟本地旗标走。对**已经装上的** item 做 seek 不要用它，走
     * [BookReaderPlaybackSession.toPlayerPositionMs]（按播放器实配换算）。
     */
    private fun audioPositionInClipFor(absoluteMs: Long): Long {
        val clip = activeAudioClipRangeMs()
        return if (clip != null) {
            (absoluteMs - clip.first)
                .coerceIn(0L, (clip.second - clip.first).coerceAtLeast(1L))
        } else {
            absoluteMs.coerceAtLeast(0L)
        }
    }

    /**
     * 当前生效的播放窗口（绝对 ms 起止）：逐句重复与页尾整页裁剪互斥，不会同时存在。
     * @return null 表示播放器播放的是完整音频
     */
    private fun activeAudioClipRangeMs(): Pair<Long, Long>? = when {
        audioCueLoopClipActive -> audioCueLoopClipBaseMs to audioCueLoopClipEndMs
        else -> pageEndPauseRange
    }

    private fun seekAudioPlayerTo(currentPlayer: ExoPlayer, absoluteMs: Long) {
        currentPlayer.seekTo(BookReaderPlaybackSession.toPlayerPositionMs(currentPlayer, absoluteMs))
    }

    private fun clearCurrentChapterImageStops() {
        currentChapterImageStops = emptyList()
        currentChapterImageStopIndex = 0
        currentChapterImageStopChapterIndex = -1
    }

    private fun rebuildCurrentChapterImageStops() {
        val loadedDocument = document ?: run {
            clearCurrentChapterImageStops()
            return
        }
        val chapterIndex = pages.firstOrNull()?.chapterIndex ?: run {
            clearCurrentChapterImageStops()
            return
        }
        val chapter = loadedDocument.chapters.getOrNull(chapterIndex) ?: run {
            clearCurrentChapterImageStops()
            return
        }
        if (chapter.images.isEmpty()) {
            clearCurrentChapterImageStops()
            currentChapterImageStopChapterIndex = chapterIndex
            return
        }
        val chapterMatches = cueMatchesByCueIndex.values
            .asSequence()
            .filter { it.chapterIndex == chapterIndex }
            .sortedWith(compareBy<EbookCueMatch> { it.rawStart }.thenBy { it.cueIndex })
            .toList()
        val seenTailCues = mutableSetOf<Int>()
        currentChapterImageStops = chapter.images.keys
            .sorted()
            .mapNotNull { imagePosition ->
                // 章节题图页（目录指向的纯图片页，如「とあるスイーツの店にて」那张）
                // 是章节的排版开头，不是插图：不建停点，听读路过时不停。
                if (chapter.images[imagePosition]?.origin == EbookImageOrigin.SECTION_TITLE_PAGE) {
                    logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
                        "imageStop: skip section title page image chapter=$chapterIndex " +
                        "position=$imagePosition"
                    }
                    return@mapNotNull null
                }
                val imagePageIndex = findPageIndexForChapterPosition(chapterIndex, imagePosition)
                if (imagePageIndex < 0) return@mapNotNull null
                val nextCue = chapterMatches.firstOrNull { it.rawStart >= imagePosition }
                val previousCue = chapterMatches.lastOrNull { it.rawEnd <= imagePosition }
                val tailCueIndex = previousCue?.cueIndex?.takeIf { nextCue == null }
                // 同一句之后挂着多张图（卷末插图）时只留第一张的停点：
                // tail 触发不要求 cue 变化，否则恢复播放后会立刻被下一张连着停。
                if (tailCueIndex != null && !seenTailCues.add(tailCueIndex)) {
                    return@mapNotNull null
                }
                ReaderChapterImageStop(
                    target = ReaderImageStopTarget(chapterIndex, imagePosition),
                    pageIndex = imagePageIndex,
                    triggerCueIndex = nextCue?.cueIndex,
                    tailCueIndex = tailCueIndex,
                    tailCueEndMs = tailCueIndex
                        ?.let { cues.getOrNull(it)?.endMs }
                )
            }
        currentChapterImageStopChapterIndex = chapterIndex
        syncCurrentChapterImageStopIndex()
        maybePauseForImageStopAfterChapterLoad()
    }

    /** 这一章里某个位置的图的来源（见 [EbookImageOrigin]） */
    private fun imageOriginAt(chapterIndex: Int, imagePosition: Int): EbookImageOrigin? {
        return document?.chapters?.getOrNull(chapterIndex)?.images?.get(imagePosition)?.origin
    }

    /**
     * SRT 章节标记 cue（以 ＊ 开头、文本是章节标题的那条，如「＊──市井にて──」）：
     * 翻到该章节的第一页 —— 章节题图（把标题做成图片的那种）就能像翻页一样被看到。
     * 只翻页、不改播放状态，所以不会在这里暂停。
     *
     * @return true 表示已翻到目标章节的第一页（或已发起该章节的加载）
     */
    private fun showSectionTitlePageForCue(cueIndex: Int): Boolean {
        val cue = cues.getOrNull(cueIndex) ?: return false
        val markerTitle = sectionMarkerTitle(cue.text) ?: return false
        val chapters = document?.chapters ?: return false
        val currentChapter = pages.getOrNull(pageIndex)?.chapterIndex ?: 0
        // 同名小节（如多处的「断章一」）取当前章之后最近的一个
        val candidates = chapters.indices.filter { index ->
            normalizeSectionTitle(chapters[index].title) == markerTitle
        }
        val targetChapter = candidates.firstOrNull { it >= currentChapter }
            ?: candidates.lastOrNull()
            ?: return false
        val targetPage = pages.indexOfFirst { it.chapterIndex == targetChapter }
        if (targetPage < 0) {
            val anchor = ReaderPageAnchor(targetChapter, 0)
            if (pendingAudioSyncLoadAnchor == anchor) return false
            pendingAudioSyncLoadAnchor = anchor
            logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
                "sectionMarker: load chapter=$targetChapter title=$markerTitle"
            }
            loadDisplayedBook(anchor = anchor, forceDocumentReload = false)
            return true
        }
        if (targetPage == pageIndex) return false
        val forward = targetPage > pageIndex
        logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
            "sectionMarker: show page=$targetPage chapter=$targetChapter title=$markerTitle"
        }
        pageIndex = targetPage
        activeCueIndex = -1
        renderCurrentPage(forward = forward, persistAnchor = true)
        return true
    }

    /** ＊ 开头的 SRT 章节标记 cue → 归一化后的章节标题；不是章节标记则返回 null */
    private fun sectionMarkerTitle(cueText: String): String? {
        val trimmed = cueText.trim()
        if (!trimmed.startsWith(SECTION_MARKER_PREFIX)) return null
        return normalizeSectionTitle(trimmed.removePrefix(SECTION_MARKER_PREFIX))
            .takeIf { it.isNotBlank() }
    }

    /**
     * 比较章节标记与章节标题时用：去掉两边装饰用的破折号与空白，
     * 让「＊──市井にて──」能和章节标题「──市井にて──」对上。
     */
    private fun normalizeSectionTitle(value: String): String {
        return value.filterNot { it.isWhitespace() || it in SECTION_TITLE_DECORATIONS }
    }

    /**
     * 跨章图片停点的补判。
     *
     * 图片停点的触发点是「图片之后的第一句」。跨章时这个触发点发生在**新章分页完成之前**：
     * 那一次 sync 里 pages 还是上一章、currentChapterImageStops 也是上一章的，等新章页就绪
     * 时已经不再有 cue 变化 → 靠 maybePausePlaybackForImage 永远漏掉。
     * 「独立成页的插图」被并进章节正文开头后就是这种情况，表现为"遇图暂停没生效"。
     *
     * 所以新章停点表建好后补判一次：播放位置刚进入触发句 → 立刻按图片停点暂停。
     * 只对**插图页来源**的图补判（见 [EbookImageOrigin]）：
     * - 章节内部的图保持改之前的行为（章首的题图本来就不触发、中段/章末照旧触发）；
     * - 章节题图页（目录指向的纯图片页）不建停点，也就不会被补判截停。
     * 并且只在触发句开头的一小段窗口内补判，避免 seek 到章中间时被很早以前的插图截停。
     */
    private fun maybePauseForImageStopAfterChapterLoad() {
        if (!stopPlaybackOnImage || !isAudioPlaying()) return
        val cueIndex = audioCueIndex
        if (cueIndex < 0) return
        val currentPosition = currentAudioPositionMs() ?: return
        val stop = currentChapterImageStops.firstOrNull { candidate ->
            candidate.target.key != lastImageStopKey &&
                candidate.target.chapterIndex == currentChapterImageStopChapterIndex &&
                candidate.triggerCueIndex == cueIndex &&
                imageOriginAt(candidate.target.chapterIndex, candidate.target.imagePosition) ==
                EbookImageOrigin.ILLUSTRATION_PAGE
        } ?: return
        val triggerCue = cues.getOrNull(cueIndex) ?: return
        if (currentPosition > triggerCue.startMs + IMAGE_STOP_CATCH_UP_WINDOW_MS) return
        logDebug(READER_PAUSED_SEEK_LOG_TAG) {
            "legado pauseAtImage(catchUp) chapter=${stop.target.chapterIndex} " +
            "imagePosition=${stop.target.imagePosition} cue=$cueIndex actual=${player?.currentPosition}"
        }
        pausePlaybackAtImage(stop.target)
    }

    private fun syncCurrentChapterImageStopIndex() {
        if (currentChapterImageStops.isEmpty()) {
            currentChapterImageStopIndex = 0
            return
        }
        val currentPage = pages.getOrNull(pageIndex)
        val currentMatch = currentPage?.let(::currentPageCueMatch)
        val referencePosition = currentMatch?.rawEnd ?: currentPage?.charStart ?: 0
        val referencePageIndex = pageIndex
        val nextIndex = currentChapterImageStops.indexOfFirst { stop ->
            stop.target.key != lastImageStopKey && (
                stop.pageIndex > referencePageIndex ||
                    stop.target.imagePosition > referencePosition
                )
        }
        currentChapterImageStopIndex = if (nextIndex >= 0) nextIndex else currentChapterImageStops.size
    }

    private fun advanceCurrentChapterImageStopIndex(targetKey: String) {
        while (
            currentChapterImageStopIndex < currentChapterImageStops.size &&
            currentChapterImageStops[currentChapterImageStopIndex].target.key == targetKey
        ) {
            currentChapterImageStopIndex += 1
        }
    }

    /**
     * 读完此页暂停：刚播完的 cue 是本页最后一句时暂停播放，
     * 翻页后自动继续（resumePlaybackFromPageEndPause）。
     */
    private var pageEndPausePending = false

    /**
     * 读完此页暂停是否已武装（整页裁剪 或 句尾监视兜底）。
     *
     * 主路径是「整页裁剪」：在暂停态把播放窗口（MediaItem ClippingConfiguration）裁到
     * [本页起点, 本页最后一句句尾]，播到页尾由解码层直接截断（STATE_ENDED → 暂停）。
     * 这样既不重读最后一句（裁剪不在播放中途应用，不会有音频重载），
     * 也不会读进下一页第一句（数据层截断，不受音频 HAL/蓝牙缓冲影响）。
     *
     * 句尾监视（pageEndPauseWatchJob）只在无法于暂停态武装时兜底：
     * 播放中途 seek、阅读中途开启功能等场景，退化为轮询位置后暂停。
     */
    private var pageEndPauseClipActive = false
    private var pageEndPauseWatchJob: Job? = null
    private var pageEndPauseWatchEndMs: Long = -1L

    /** 播放器的 MediaItem 当前被裁剪到的本页窗口（绝对 ms 起止）；null 表示播的是完整音频 */
    private var pageEndPauseRange: Pair<Long, Long>? = null

    /**
     * 读完此页暂停：保证「本页」已武装。
     *
     * 暂停态 → 整页裁剪（无声重载，播到页尾由解码层精确截断，零泄漏零重读）；
     * 播放态 → 不能重载（会把当前句从头重读），退化为句尾监视兜底。
     */
    private fun refreshPageEndPauseArming(absoluteMs: Long) {
        if (!pageEndPauseActive || pageEndPausePending) return
        val page = pageForAudioPosition(absoluteMs)
        val range = page?.let { pageEndPauseRangeForPage(it, absoluteMs) }
        if (page == null || range == null) {
            releasePageEndPauseClipIfActive()
            return
        }
        val appliedRange = pageEndPauseRange
        if (
            appliedRange != null &&
            appliedRange.second == range.second &&
            absoluteMs >= appliedRange.first &&
            absoluteMs < range.second
        ) {
            // 已按本页窗口武装：等 STATE_ENDED 精确暂停，无需重复处理
            return
        }
        val currentPlayer = player ?: return
        val isSilent = !currentPlayer.isPlaying && !currentPlayer.playWhenReady
        if (isSilent) {
            applyPageEndPauseRangeClip(range.first, range.second, absoluteMs)
            return
        }
        // 播放中：恢复完整音频（当前位置起继续，不重读已播部分）+ 句尾监视兜底
        restoreFullAudioFromPageEndPauseClip(absoluteMs)
        pageEndPauseClipActive = true
        startPageEndPauseWatch(range.second)
    }

    /**
     * 播放位置所在的页：按「即将要播的那一句」判断。
     *
     * 位置正好落在句尾（= 页尾暂停点）时必须算作下一句：`findEbookCueIndexAtTime` 在
     * 句间有间隙（cue.endMs < 下一句 startMs）时会返回刚播完的那一句，那样算出来的
     * 「页尾」就是已经播过的位置，会武装不上。所以这里向前跳过已播完的 cue。
     *
     * 也不能用 pageIndex：用户可能一边播一边翻页（显示页被翻走），
     * 这时按显示页武装会把「页尾」算到别的页上。
     */
    private fun pageForAudioPosition(positionMs: Long): TextPage? {
        var index = findEbookCueIndexAtTime(cues, positionMs).coerceAtLeast(0)
        while (index < cues.size && cues[index].endMs <= positionMs) {
            index += 1
        }
        val match = cueMatchesByCueIndex[index]
        // 下一句没匹配到文本时退回显示页（与 sync 一致：此时页面本来就不跟音频走）
        return match?.let { findTextPageForMatch(it) }?.let { pages.getOrNull(it) }
            ?: pages.getOrNull(pageIndex)
    }

    /**
     * 本页播放窗口（绝对 ms）：起点 = 当前播放位置，终点 = 本页最后一句句尾。
     * 起点取当前位置（而不是页首），保证武装动作不改变正在读的内容。
     * @return null 表示本页没有可用的句尾（不武装）
     */
    private fun pageEndPauseRangeForPage(page: TextPage, fromMs: Long): Pair<Long, Long>? {
        val lastMatch = lastMatchOnPage(page) ?: return null
        val cue = cues.getOrNull(lastMatch.cueIndex) ?: return null
        val startMs = fromMs.coerceAtLeast(0L)
        val endMs = cue.endMs.coerceAtLeast(0L)
        if (endMs <= startMs + 1L) return null
        return startMs to endMs
    }

    /**
     * 暂停态武装整页裁剪：把 MediaItem 换成 [startMs, endMs] 窗口。
     * 调用方必须保证此刻没有声音在播（翻页续播 / 暂停中按播放 / 开启功能）——
     * 播放中重载会让当前句从头重读，所以播放中要改走句尾监视。
     */
    private fun applyPageEndPauseRangeClip(startMs: Long, endMs: Long, resumeAbsoluteMs: Long) {
        val currentPlayer = player ?: return
        if (audioUri == null) return
        val positionInClip = (resumeAbsoluteMs - startMs).coerceAtLeast(0L)
        pageEndPauseRange = startMs to endMs
        pageEndPauseClipActive = true
        cancelPageEndPauseWatch()
        val page = pages.getOrNull(pageIndex)
        logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
            "pageEndPause: arm page clip startMs=$startMs endMs=$endMs positionInClip=$positionInClip " +
            "page=${page?.chapterIndex}:${page?.charStart} playWhenReady=${currentPlayer.playWhenReady}"
        }
        currentPlayer.setMediaItem(currentAudioMediaItem(), positionInClip)
        currentPlayer.prepare()
        BookReaderFloatingBridge.notifyPlaybackPosition(resumeAbsoluteMs)
        publishReaderPlaybackBridgeSnapshot(notifyState = true)
    }

    /**
     * 解除整页裁剪：恢复完整音频（从 absoluteMs 继续）。播放器上本来就没有裁剪时只清状态、不重载。
     * 判据取**播放器实配**而不是本地旗标：按钮路径可能已经把旗标清掉、而播放器上还留着别的裁剪
     * （例如逐句重复的窗口），那时按旗标判断会漏掉这次重载，播放就被留在旧窗口里。
     */
    private fun restoreFullAudioFromPageEndPauseClip(absoluteMs: Long) {
        val playerHasClip = player?.let { BookReaderPlaybackSession.activeClipRangeMs(it) } != null
        pageEndPauseRange = null
        if (!playerHasClip) return
        logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
            "pageEndPause: restore full audio at=${absoluteMs.coerceAtLeast(0L)}"
        }
        reloadAudioAt(absoluteMs)
    }

    /**
     * 对账：播放器上**实际**没有裁剪，而本地旗标还说有 —— 说明别的界面（主页 / 播放器页）换过音频
     * （`prepareAudioIfNeeded` 写入的是无裁剪的 MediaItem），本地旗标过期了。
     *
     * 以播放器为唯一真相清掉本地裁剪状态，清理动作复用 [disableAudioCueLoop]（`reloadAudio = false`：
     * 播放器已经在放新的完整音频了，不该再换一次 MediaItem）。那句"位置也不取 `currentAudioPositionMs()`"
     * 说的是**不要拿它定位**：这里的假位置只会落进一个不会被读取的局部变量。
     *
     * 只对账"真有裁剪窗口"的那两路（重复窗口 / 页尾裁剪）：页尾在**播放态是故意不裁剪**的，只挂句尾
     * 监视（`pageEndPauseClipActive=true` 而 `pageEndPauseRange=null`），拿它当判据会把监视每个同步
     * tick 清一次，功能直接失效。
     */
    private fun reconcileAudioClipStateWithPlayer() {
        val currentPlayer = player ?: return
        if (BookReaderPlaybackSession.activeClipRangeMs(currentPlayer) != null) return
        if (!audioCueLoopClipActive && pageEndPauseRange == null) return
        logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
            "audioClip: player has no clip -> drop stale local clip state " +
                "loop=$audioCueLoopClipActive pageEndRange=${pageEndPauseRange != null} " +
                "raw=${currentPlayer.currentPosition}"
        }
        disableAudioCueLoop(updateUi = true, reloadAudio = false)
    }

    /** 句尾监视（兜底）：轮询播放位置，到达句尾（endMs）时暂停；会有下游缓冲的一点泄漏 */
    private fun startPageEndPauseWatch(endMs: Long) {
        val safeEndMs = endMs.coerceAtLeast(1L)
        if (pageEndPauseWatchEndMs == safeEndMs && pageEndPauseWatchJob?.isActive == true) return
        pageEndPauseWatchJob?.cancel()
        pageEndPauseWatchJob = null
        pageEndPauseWatchEndMs = safeEndMs
        pageEndPauseWatchJob = lifecycleScope.launch {
            while (isActive) {
                if (!pageEndPauseActive || pageEndPausePending) return@launch
                if (pageEndPauseWatchEndMs != safeEndMs) return@launch
                val currentPlayer = player ?: return@launch
                if (!currentPlayer.isPlaying) {
                    delay(150)
                    continue
                }
                val positionMs = currentAudioPositionMs() ?: return@launch
                if (positionMs >= safeEndMs) {
                    logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
                        "pageEndPause: WATCH reached tail endMs=$safeEndMs pos=$positionMs -> paused (page end)"
                    }
                    handlePageEndPauseClipEnded()
                    return@launch
                }
                val remaining = safeEndMs - positionMs
                // 远离句尾时低频轮询，接近句尾时高频（~40ms），尽量减小暂停点误差
                delay(if (remaining > 800L) 120L else 40L)
            }
        }
    }

    private fun cancelPageEndPauseWatch() {
        pageEndPauseWatchJob?.cancel()
        pageEndPauseWatchJob = null
        pageEndPauseWatchEndMs = -1L
    }

    /**
     * 读完此页暂停到点：停在当前页。
     * 整页裁剪路径由播放器 STATE_ENDED 触发（解码层精确停在句尾），
     * 句尾监视路径由轮询触发（可能已读进下一句一点点）。
     */
    private fun handlePageEndPauseClipEnded() {
        pageEndPausePending = true
        logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
            "pageEndPause: PAUSE lastCue=$audioCueIndex clipApplied=${pageEndPauseRange != null} " +
            "pos=${currentAudioPositionMs()} -> paused (page end)"
        }
        player?.pause()
        BookReaderFloatingBridge.notifyPlaybackState(false)
        updateAudioControlLabels()
        persistAudioPlaybackSnapshot()
    }

    /** 解除读完此页暂停（设置关闭/seek/关闭重复等）：停止监视并恢复完整音频 */
    private fun releasePageEndPauseClipIfActive() {
        if (!pageEndPauseClipActive && pageEndPauseRange == null) return
        val absoluteMs = currentAudioPositionMs() ?: pageEndPauseRange?.first ?: 0L
        pageEndPauseClipActive = false
        cancelPageEndPauseWatch()
        restoreFullAudioFromPageEndPauseClip(absoluteMs.coerceAtLeast(0L))
        logDebug(M9_SENTENCE_TAIL_LOG_TAG) { "pageEndPause: release (stop watch, restore full audio)" }
    }

    /** 用户主动 seek/跳转：解除读完此页暂停（整页裁剪 + pending），并继续播放 */
    private fun clearPageEndPauseForSeek() {
        // 只有「被读完此页暂停停下的」才在 seek 后自动续播；普通暂停不动
        val hadState = pageEndPausePending
        releasePageEndPauseClipIfActive()
        pageEndPausePending = false
        if (!hadState) return
        logDebug(M9_SENTENCE_TAIL_LOG_TAG) { "pageEndPause: cleared by seek" }
        val currentPlayer = player ?: return
        if (!currentPlayer.isPlaying) {
            currentPlayer.play()
            BookReaderFloatingBridge.notifyPlaybackState(true)
            updateAudioControlLabels()
        }
    }

    /** @return true 表示已触发「读完此页暂停」（调用方应保持当前页、不再翻页） */
    private fun maybePauseForPageEnd(justFinishedCueIndex: Int): Boolean {
        logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
            "pageEndPause: check cue=$justFinishedCueIndex active=$pageEndPauseActive setting=$pauseAfterPageEnd"
        }
        if (!pageEndPauseActive) return false
        val finishedMatch = cueMatchesByCueIndex[justFinishedCueIndex]
        if (finishedMatch == null) {
            logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
                "pageEndPause: no match for cue=$justFinishedCueIndex"
            }
            return false
        }
        val currentPage = pages.getOrNull(pageIndex)
        if (currentPage == null) {
            logDebug(M9_SENTENCE_TAIL_LOG_TAG) { "pageEndPause: no current page (pageIndex=$pageIndex)" }
            return false
        }
        if (finishedMatch.chapterIndex != currentPage.chapterIndex) {
            logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
                "pageEndPause: chapter mismatch match.chapter=${finishedMatch.chapterIndex} " +
                "page.chapter=${currentPage.chapterIndex}"
            }
            return false
        }
        if (!isAudioPlaying()) {
            logDebug(M9_SENTENCE_TAIL_LOG_TAG) { "pageEndPause: not playing, skip" }
            return false
        }
        // 该句是否本页最后一句
        val lastOnPage = lastMatchOnPage(currentPage)
        if (lastOnPage?.cueIndex != justFinishedCueIndex) {
            logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
                "pageEndPause: not last on page cue=$justFinishedCueIndex last=${lastOnPage?.cueIndex}"
            }
            return false
        }
        logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
            "pageEndPause: PAUSE page=${currentPage.chapterIndex}:${currentPage.charStart} " +
            "lastCue=$justFinishedCueIndex"
        }
        pageEndPausePending = true
        player?.pause()
        BookReaderFloatingBridge.notifyPlaybackState(false)
        updateAudioControlLabels()
        persistAudioPlaybackSnapshot()
        return true
    }

    /**
     * 读完此页暂停的恢复：翻页后自动继续播放。
     * 此刻播放器是暂停态（页尾刚停），所以可以安全地把播放窗口换成新页的整页裁剪：
     * 页尾再停，就是解码层精确停在句尾，不会读进下一页第一句。
     */
    private fun resumePlaybackFromPageEndPause() {
        if (!pageEndPausePending) {
            logDebug(M9_SENTENCE_TAIL_LOG_TAG) { "pageEndPause: resume skip (no pending pause)" }
            return
        }
        pageEndPausePending = false
        val currentPlayer = player ?: run {
            releasePageEndPauseClipIfActive()
            logDebug(M9_SENTENCE_TAIL_LOG_TAG) { "pageEndPause: resume skip (no player)" }
            return
        }
        val absoluteMs = currentAudioPositionMs() ?: 0L
        logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
            "pageEndPause: resume play from=$absoluteMs isPlaying=${currentPlayer.isPlaying}"
        }
        // 暂停态：重新武装成新页的整页裁剪；无法武装时（本页无句尾）自动恢复完整音频
        refreshPageEndPauseArming(absoluteMs)
        if (!currentPlayer.isPlaying) {
            currentPlayer.play()
            BookReaderFloatingBridge.notifyPlaybackState(true)
            updateAudioControlLabels()
        }
    }

    private fun maybePausePlaybackForImage(
        currentPosition: Long,
        cueIndex: Int,
        previousCueIndex: Int,
        cueChanged: Boolean,
        match: EbookCueMatch,
        allowPageJump: Boolean
    ): Boolean {
        if (!stopPlaybackOnImage || !allowPageJump || !isAudioPlaying()) return false
        if (match.chapterIndex != currentChapterImageStopChapterIndex) {
            // 跨章：上一章末尾图片（图后无 cue）的收尾暂停仍生效
            val carryOverStop = currentChapterImageStops.firstOrNull { stop ->
                stop.target.key != lastImageStopKey &&
                    stop.tailCueIndex?.let { tailCueIndex ->
                        previousCueIndex == tailCueIndex &&
                            currentPosition >= (stop.tailCueEndMs ?: Long.MAX_VALUE)
                    } == true
            }
            if (carryOverStop != null) {
                currentChapterImageStopIndex =
                    currentChapterImageStops.indexOf(carryOverStop).coerceAtLeast(0) + 1
                pausePlaybackAtImage(carryOverStop.target)
                return true
            }
            rebuildCurrentChapterImageStops()
        }
        // 全量扫描未触发的图片停点（不依赖当前章内指针位置）：纵书每页容量大，
        // 播放 cue 到达图片页时显示页往往已越过图片页，指针式"已过"判定会跳过
        // 停点导致直接越过图片；按 cue 时序判断则与布局无关。
        for ((index, stop) in currentChapterImageStops.withIndex()) {
            if (stop.target.key == lastImageStopKey) continue
            val crossedCueTrigger = stop.triggerCueIndex?.let { triggerCueIndex ->
                if (previousCueIndex < 0) {
                    // 刚进入书本、恢复进度后按播放（还没有"上一句"）时，只有**当前这句就是
                    // 触发句**才算越过。否则本章里更早的每一张图都满足 cueIndex >= 触发句，
                    // 会被当成"刚刚越过"——一按播放就跳到那张图并触发遇图暂停。
                    cueIndex == triggerCueIndex
                } else {
                    cueChanged && cueIndex >= triggerCueIndex && previousCueIndex < triggerCueIndex
                }
            } == true
            val reachedTailTrigger = stop.tailCueIndex?.let { tailCueIndex ->
                cueIndex == tailCueIndex &&
                    currentPosition >= (stop.tailCueEndMs ?: Long.MAX_VALUE)
            } == true
            if (crossedCueTrigger || reachedTailTrigger) {
                currentChapterImageStopIndex = index + 1
                pausePlaybackAtImage(stop.target)
                return true
            }
        }
        return false
    }

    private fun isAudioPlaying(): Boolean {
        return player?.isPlaying == true
    }

    private fun isAudioPlaybackRequested(): Boolean {
        val currentPlayer = player ?: return false
        if (audioCueLoopPausedForRepeat) return true
        return currentPlayer.playWhenReady || currentPlayer.isPlaying
    }

    private fun currentAudioPlaybackSpeed(): Float {
        return player?.playbackParameters?.speed ?: 1f
    }

    private fun setAudioPlaybackSpeed(speed: Float) {
        val normalized = speed.coerceIn(0.5f, 3.0f)
        player?.playbackParameters = PlaybackParameters(normalized)
        BookReaderFloatingBridge.notifyPlaybackSpeed(normalized)
    }

    private fun currentSharedReaderPlaybackKey(): String? {
        val uri = audioUri ?: return null
        return buildReaderAudioPlaybackKey(
            title = currentReaderTitle(),
            audioUri = uri,
            srtUri = srtUri
        )
    }

    private fun persistAudioPlaybackSnapshot(allowZeroPositionWrite: Boolean = false) {
        val currentPlayer = player ?: return
        val positionMs = playerPositionMs(currentPlayer)
        persistAudioPlaybackSnapshotAt(
            positionMs = positionMs,
            allowZeroPositionWrite = allowZeroPositionWrite
        )
    }

    private fun persistAudioPlaybackSnapshotAt(
        positionMs: Long,
        allowZeroPositionWrite: Boolean = false
    ) {
        val currentPlayer = player ?: return
        if (currentSharedReaderPlaybackKey() == null) return
        val durationMs = currentAudioDurationMs() ?: pendingAudioRestoreDurationMs
        if (durationMs <= 0L) {
            return
        }
        val safePositionMs = positionMs.coerceIn(0L, durationMs)
        if (!allowZeroPositionWrite && safePositionMs == lastSavedPlaybackPositionMs) {
            return
        }
        lastSavedPlaybackPositionMs = safePositionMs
        saveReaderAudioPlaybackProgress(
            context = this,
            title = currentReaderTitle(),
            audioUri = audioUri,
            srtUri = srtUri,
            positionMs = safePositionMs,
            durationMs = durationMs.coerceAtLeast(0L),
            allowZeroPositionWrite = allowZeroPositionWrite
        )
    }

    private fun currentReaderBottomPaddingPx(): Int {
        return dp(readerPaddingBottomDp) + playbackBarEffectiveHeightPx()
    }

    private fun playbackBarEffectiveHeightPx(): Int {
        return if (playbackBarPinnedVisible) playbackBarHeightPx.coerceAtLeast(dp(62)) else 0
    }

    private fun currentPageAnchor(includeCueMatch: Boolean = false): ReaderPageAnchor? {
        val page = pages.getOrNull(pageIndex) ?: return null
        return ReaderPageAnchor(
            chapterIndex = page.chapterIndex,
            charPosition = currentAnchorCharPosition(page, includeCueMatch)
        )
    }

    private fun currentAnchorCharPosition(page: TextPage, includeCueMatch: Boolean): Int {
        val cueMatch = currentPageCueMatch(page)
        if (
            includeCueMatch &&
            cueMatch != null &&
            cueMatch.chapterIndex == page.chapterIndex &&
            cueMatch.rawStart >= page.charStart &&
            cueMatch.rawStart < page.charEnd
        ) {
            return cueMatch.rawStart.coerceIn(page.charStart, page.charEnd.coerceAtLeast(page.charStart))
        }
        val middle = page.charStart + ((page.charEnd - page.charStart).coerceAtLeast(0) / 2)
        return middle.coerceIn(page.charStart, page.charEnd.coerceAtLeast(page.charStart))
    }

    private fun pageIndexForAnchor(loadedPages: List<TextPage>, anchor: ReaderPageAnchor): Int {
        if (loadedPages.isEmpty()) return 0
        loadedPages.indexOfFirst { page ->
            page.chapterIndex == anchor.chapterIndex && page.containPos(anchor.charPosition)
        }.takeIf { it >= 0 }?.let { return it }
        loadedPages.indexOfLast { page ->
            page.chapterIndex == anchor.chapterIndex && page.charStart <= anchor.charPosition
        }.takeIf { it >= 0 }?.let { return it }
        loadedPages.indexOfFirst { page ->
            page.chapterIndex >= anchor.chapterIndex
        }.takeIf { it >= 0 }?.let { return it }
        return loadedPages.lastIndex
    }

    private fun applyReadViewViewportInset(reload: Boolean) {
        readView.setReaderPadding(
            dp(readerPaddingLeftDp),
            dp(readerPaddingTopDp),
            dp(readerPaddingRightDp),
            currentReaderBottomPaddingPx()
        )
        if (reload) {
            if (document == null && pages.isEmpty()) {
                logDebug(LEGADO_READER_LOG_TAG) { "readerRelayout skipped pending initial load" }
                return
            }
            val anchor = currentPageAnchor() ?: pendingRestoreAnchor
            readView.post {
                relayoutCurrentDocument(anchor)
            }
        }
    }

    private fun requestBookRelayout(immediate: Boolean = false) {
        persistReaderSettings()
        reloadBookJob?.cancel()
        val anchor = currentPageAnchor() ?: pendingRestoreAnchor
        if (immediate) {
            relayoutCurrentDocument(anchor)
            return
        }
        reloadBookJob = lifecycleScope.launch {
            delay(90)
            relayoutCurrentDocument(anchor)
        }
    }

    private fun relayoutCurrentDocument(anchor: ReaderPageAnchor?) {
        val loaded = document
        if (loaded == null) {
            loadDisplayedBook(anchor = anchor, forceDocumentReload = false)
            return
        }
        val pageWidth = readView.contentWidth.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dp(44))
        paginationJob?.cancel()
        clearChapterPageCache()
        paginationJob = lifecycleScope.launch {
            updateSimulatedUnlockedChapterCount(loaded)
            val safeAnchor = anchor?.let { clampAnchorToUnlocked(it, loaded) }
            val relayoutResult = runCatching {
                val centerChapterIndex = safeAnchor?.chapterIndex
                    ?: pages.getOrNull(pageIndex)?.chapterIndex
                    ?: 0
                getOrPaginateChapterPages(
                    document = loaded,
                    chapterIndex = clampChapterToUnlocked(centerChapterIndex, loaded),
                    contentWidthPx = pageWidth.coerceAtLeast(1)
                )
            }
            val loadedPages = relayoutResult.getOrNull()
            if (loadedPages == null) {
                val error = relayoutResult.exceptionOrNull()
                if (error is CancellationException) return@launch
                Log.w(LEGADO_READER_LOG_TAG, "relayoutCurrentDocument failed", error)
                return@launch
            }
            pages = loadedPages
            pageIndex = safeAnchor?.let { pageIndexForAnchor(loadedPages, it) } ?: pageIndex.coerceIn(0, pages.lastIndex)
            rebuildCurrentChapterImageStops()
            renderCurrentPage()
            if (cueMatchesByCueIndex.isNotEmpty()) {
                syncToAudioPosition(allowPageJump = isAudioPlaying())
            } else {
                logDebug(LEGADO_READER_LOG_TAG) {
                    "relayoutCurrentDocument no in-memory matches; trying persisted restore"
                }
                restorePersistedMatchIfPossible()
            }
            val centerChapterIndex = pages.getOrNull(pageIndex)?.chapterIndex ?: anchor?.chapterIndex ?: 0
            preloadAdjacentChapters(
                document = loaded,
                centerChapterIndex = centerChapterIndex,
                contentWidthPx = pageWidth.coerceAtLeast(1)
            )
        }
    }

    private fun currentReaderMatchStoreKey(): String? {
        val bookUri = importedBook?.uri?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val stableSource = buildString {
            append("ebook=").append(bookUri)
            append("|audio=").append(audioUri?.toString().orEmpty())
            append("|srt=").append(srtUri?.toString().orEmpty())
            append("|charset=").append(preferredCharsetName.orEmpty())
        }
        return buildDictionaryCacheKey(stableSource, currentReaderTitle())
    }

    private fun cueDebugText(cue: EbookSrtCue?): String {
        return cue
            ?.text
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(48)
            .orEmpty()
    }

    private fun cueDebugSummary(cueIndex: Int): String {
        val cue = cues.getOrNull(cueIndex)
        return if (cue == null) {
            "cue=$cueIndex missing"
        } else {
            "cue=$cueIndex time=${cue.startMs}-${cue.endMs} text=${cueDebugText(cue)}"
        }
    }

    private fun matchDebugSummary(match: EbookCueMatch?): String {
        return if (match == null) {
            "match=null"
        } else {
            "match cue=${match.cueIndex} chapter=${match.chapterIndex} raw=${match.rawStart}-${match.rawEnd}"
        }
    }

    private fun chapterSeekTextSyncPositionMs(targetMs: Long): Long {
        if (cues.isEmpty() || cueMatchesByCueIndex.isEmpty()) return targetMs
        val exactIndex = findEbookCueIndexAtTime(cues, targetMs)
        val exactCue = cues.getOrNull(exactIndex)
        if (
            exactCue != null &&
            targetMs in exactCue.startMs..exactCue.endMs &&
            cueMatchesByCueIndex[exactIndex] != null
        ) {
            return targetMs
        }
        val nextIndex = cues.indices.firstOrNull { index ->
            val cue = cues[index]
            cue.startMs >= targetMs &&
                cue.startMs - targetMs <= M4B_CHAPTER_TEXT_SYNC_LOOKAHEAD_MS &&
                cueMatchesByCueIndex[index] != null
        } ?: return targetMs
        return cues[nextIndex].startMs
    }

    private fun nearbyMatchedCueSummary(cueIndex: Int): String {
        val previous = cueMatchesByCueIndex.keys.filter { it < cueIndex }.maxOrNull()
        val next = cueMatchesByCueIndex.keys.filter { it > cueIndex }.minOrNull()
        return "prev=${previous?.let { cueDebugSummary(it) + " " + matchDebugSummary(cueMatchesByCueIndex[it]) } ?: "none"}; " +
            "next=${next?.let { cueDebugSummary(it) + " " + matchDebugSummary(cueMatchesByCueIndex[it]) } ?: "none"}"
    }

    private fun unmatchedCueDebugSample(data: EbookMatchData, limit: Int = 5): String {
        if (data.unmatched <= 0) return "none"
        val matched = data.matches.mapTo(mutableSetOf()) { it.cueIndex }
        return cues
            .asSequence()
            .mapIndexedNotNull { index, cue ->
                if (index in matched || shouldSkipEbookCueForMatching(cue)) null else cueDebugSummary(index)
            }
            .take(limit)
            .joinToString(separator = " | ")
            .ifBlank { "none" }
    }

    private suspend fun restorePersistedMatchIfPossible() {
        if (document == null) {
            logDebug(LEGADO_READER_LOG_TAG) { "restoreMatch skipped document=null" }
            return
        }
        if (cues.isEmpty()) {
            logDebug(LEGADO_READER_LOG_TAG) { "restoreMatch skipped cues empty" }
            return
        }
        if (cueMatchesByCueIndex.isNotEmpty()) {
            logDebug(LEGADO_READER_LOG_TAG) {
                "restoreMatch skipped alreadyLoaded matches=${cueMatchesByCueIndex.size}"
            }
            return
        }
        val storeKey = currentReaderMatchStoreKey() ?: run {
            logDebug(LEGADO_READER_LOG_TAG) { "restoreMatch skipped storeKey=null" }
            return
        }
        logDebug(LEGADO_READER_LOG_TAG) {
            "restoreMatch try key=${storeKey.take(48)} cues=${cues.size} book=${importedBook?.title}"
        }
        logDebug(LEGADO_MATCH_LOG_TAG) {
            "restore try key=${storeKey.take(48)} cues=${cues.size} existingMatches=${cueMatchesByCueIndex.size}"
        }
        val restoreStartMs = SystemClock.elapsedRealtime()
        val snapshot = withContext(Dispatchers.IO) {
            loadLegadoReaderMatchSnapshotOrNull(this@LegadoReaderActivity, storeKey)
        } ?: run {
            logDebug(LEGADO_READER_LOG_TAG) { "restoreMatch miss key=${storeKey.take(48)}" }
            logDebug(LEGADO_MATCH_LOG_TAG) { "restore miss key=${storeKey.take(48)}" }
            return
        }
        logDebug(LEGADO_MATCH_LOG_TAG) {
            "restore hit storedMatches=${snapshot.matches.size} totalCues=${snapshot.totalCues} unmatched=${snapshot.unmatched}"
        }
        // 章节结构变了（或旧快照没记签名）→ 快照里的 chapterIndex 已经指错章节，
        // 直接用会出现"显示的不是这一段"：重新匹配一次。
        val signature = currentDocumentSignature()
        if (snapshot.documentSignature != signature) {
            logDebug(LEGADO_READER_LOG_TAG) {
                "restoreMatch stale structure stored=${snapshot.documentSignature.ifEmpty { "(none)" }} " +
                "current=${signature.ifEmpty { "(none)" }} -> rematch"
            }
            rematchEbookCuesInPlace(reason = "structureChanged")
            return
        }
        val restoredMatches = withContext(Dispatchers.Default) {
            snapshot.matches.filter { match ->
                cues.getOrNull(match.cueIndex)
                    ?.let { cue -> !shouldSkipEbookCueForMatching(cue) }
                    ?: false
            }
        }
        if (restoredMatches.isEmpty()) {
            logDebug(LEGADO_READER_LOG_TAG) {
                "restoreMatch skipped after sanitize removed=${snapshot.matches.size} key=${storeKey.take(48)}"
            }
            return
        }
        val removedMatches = snapshot.matches.size - restoredMatches.size
        val restoredUnmatched = (snapshot.unmatched + removedMatches)
            .coerceAtMost(snapshot.totalCues.coerceAtLeast(0))
        if (removedMatches > 0) {
            logDebug(LEGADO_READER_LOG_TAG) {
                "restoreMatch sanitized removed=$removedMatches matches=${snapshot.matches.size}->${restoredMatches.size}"
            }
        }
        val matchesByCueIndex = withContext(Dispatchers.Default) {
            restoredMatches.associateBy { it.cueIndex }
        }
        cueMatchesByCueIndex = matchesByCueIndex
        matchData = EbookMatchData(
            matches = restoredMatches,
            unmatched = restoredUnmatched,
            totalCues = snapshot.totalCues
        )
        audioCueIndex = -1
        activeCueIndex = -1
        rebuildCurrentChapterImageStops()
        logDebug(LEGADO_READER_LOG_TAG) {
            "restoreMatch applied=${SystemClock.elapsedRealtime() - restoreStartMs}ms " +
            "matches=${restoredMatches.size} totalCues=${snapshot.totalCues} unmatched=$restoredUnmatched"
        }
        val restoredData = matchData ?: return
        logDebug(LEGADO_MATCH_LOG_TAG) {
            "restore applied matches=${restoredMatches.size} totalCues=${snapshot.totalCues} unmatched=$restoredUnmatched " +
            "sampleUnmatched=${unmatchedCueDebugSample(restoredData)}"
        }
        revealPendingPlayerOpenAudioPositionIfNeeded()
        relayoutIfPagesLackSentenceBoundaries()
    }

    private fun revealPendingPlayerOpenAudioPositionIfNeeded() {
        if (!pendingPlayerOpenAudioReveal) return
        if (cueMatchesByCueIndex.isEmpty()) {
            logDebug(LEGADO_READER_LOG_TAG) { "readerProgress revealFromPlayer skipped matches empty" }
            return
        }
        // 匹配可能早于首屏分页到齐（首屏等匹配那条路走的就是这个顺序）：没有页面就无从定位，
        // 这时别把标记消费掉，留给首屏之后的 sync。
        if (pages.isEmpty()) {
            logDebug(LEGADO_READER_LOG_TAG) { "readerProgress revealFromPlayer skipped pages empty" }
            return
        }
        pendingPlayerOpenAudioReveal = false
        logDebug(LEGADO_READER_LOG_TAG) {
            "readerProgress revealFromPlayer position=${currentAudioPositionMs()} playing=${isAudioPlaying()}"
        }
        syncToAudioPosition(allowPageJump = true, forceReveal = true)
    }

    private fun persistCurrentMatchSnapshot() {
        val current = matchData ?: run {
            logDebug(LEGADO_READER_LOG_TAG) { "persistMatch skipped matchData=null" }
            return
        }
        if (current.matches.isEmpty() || current.totalCues <= 0) {
            logDebug(LEGADO_READER_LOG_TAG) {
                "persistMatch skipped invalid matches=${current.matches.size} totalCues=${current.totalCues}"
            }
            return
        }
        val storeKey = currentReaderMatchStoreKey() ?: run {
            logDebug(LEGADO_READER_LOG_TAG) { "persistMatch skipped storeKey=null" }
            return
        }
        logDebug(LEGADO_READER_LOG_TAG) {
            "persistMatch saving matches=${current.matches.size} totalCues=${current.totalCues} key=${storeKey.take(48)}"
        }
        saveLegadoReaderMatchSnapshot(
            context = this,
            storeKey = storeKey,
            snapshot = LegadoReaderMatchSnapshot(
                matches = current.matches,
                unmatched = current.unmatched,
                totalCues = current.totalCues,
                documentSignature = currentDocumentSignature()
            )
        )
    }

    /**
     * 书籍结构签名：章节数 + 全书字数。
     * 匹配结果里的 chapterIndex 是"第几章"，只要章节结构变了（例如把独立图片页并进相邻章节、
     * 或按目录锚点切章），旧快照就会指到别的章节 —— 靠这个签名判定失效并重新匹配。
     */
    private fun currentDocumentSignature(): String {
        val chapters = document?.chapters ?: return ""
        if (chapters.isEmpty()) return ""
        val chars = chapters.sumOf { it.text.length }
        return "${chapters.size}:$chars"
    }

    /** 重新跑一遍 SRT 匹配并就地应用（结构变化后自动补做，避免旧快照指错章节） */
    private suspend fun rematchEbookCuesInPlace(reason: String) {
        val loadedDocument = document ?: return
        val currentCues = cues
        if (currentCues.isEmpty()) return
        val startMs = SystemClock.elapsedRealtime()
        val data = withContext(Dispatchers.Default) {
            runCatching {
                matchEbookCuesData(
                    document = loadedDocument,
                    cues = currentCues,
                    searchWindow = matchSearchWindow
                )
            }.getOrNull()
        } ?: run {
            Log.w(LEGADO_READER_LOG_TAG, "rematch failed reason=$reason")
            return
        }
        applyMatchDataInPlace(data)
        logDebug(LEGADO_READER_LOG_TAG) {
            "rematch applied reason=$reason matches=${data.matches.size} totalCues=${data.totalCues} " +
            "unmatched=${data.unmatched} elapsed=${SystemClock.elapsedRealtime() - startMs}ms"
        }
    }

    /** 把一份匹配结果就地生效：写快照、重建图片停点、跟随播放位置刷新显示 */
    private fun applyMatchDataInPlace(data: EbookMatchData) {
        matchData = data
        cueMatchesByCueIndex = data.matches.associateBy { it.cueIndex }
        persistCurrentMatchSnapshot()
        audioCueIndex = -1
        activeCueIndex = -1
        rebuildCurrentChapterImageStops()
        syncToAudioPosition(allowPageJump = isAudioPlaying())
        renderCurrentPage()
        revealPendingPlayerOpenAudioPositionIfNeeded()
        relayoutIfPagesLackSentenceBoundaries()
    }

    private fun restoreReaderSettings() {
        val state = loadLegadoReaderPersistedState(this)
        readerTextSizeSp = state.textSizeSp
        readerLineSpacingDp = state.lineSpacingDp
        readerParagraphSpacingDp = state.paragraphSpacingDp
        readerLetterSpacingDp = state.letterSpacingDp
        readerTextWeight = state.textWeight
        readerTypefaceIndex = state.typefaceIndex
        readerTypeface = readerTypefaceForIndex(readerTypefaceIndex)
        readerParagraphIndentCount = state.paragraphIndentCount
        readerPaddingDp = state.paddingDp
        readerLayoutMode = state.layoutMode
        readerPageAnim = state.pageAnim
        readerStyleConfigs = state.readerStyleConfigs
            .takeIf { it.isNotEmpty() }
            ?.toMutableList()
            ?: defaultLegadoReaderStyleConfigs().toMutableList()
        readerStyleSelectDefault = state.readerStyleSelect.coerceIn(0, readerStyleConfigs.lastIndex)
        readerStyleSelect = readerStyleSelectDefault
        if (
            state.layoutMode != M9LayoutMode.HORIZONTAL &&
            !readerStyleConfigsHaveLayoutMode(this) &&
            readerStyleSelectDefault in readerStyleConfigs.indices
        ) {
            val defaultPreset = readerStyleConfigs[readerStyleSelectDefault]
            if (defaultPreset.layoutMode == M9LayoutMode.HORIZONTAL) {
                readerStyleConfigs[readerStyleSelectDefault] =
                    defaultPreset.copy(layoutMode = state.layoutMode)
            }
        }
        readerNightMode = state.readerNightMode
        applySelectedReaderStyleFields()
        readerPaddingTopDp = state.readerPaddingTopDp
        readerPaddingBottomDp = state.readerPaddingBottomDp
        readerPaddingLeftDp = state.readerPaddingLeftDp
        readerPaddingRightDp = state.readerPaddingRightDp
        readerCueHighlightColor = state.cueHighlightColor
        hideStatusBar = state.hideStatusBar
        readBodyToLh = state.readBodyToLh
        hideNavigationBar = state.hideNavigationBar
        showBrightnessView = state.showBrightnessView
        brightnessAuto = state.brightnessAuto
        brightnessValue = state.brightnessValue
        brightnessPanelOnRight = state.brightnessPanelOnRight
        topBarMode = state.topBarMode
        tipTopBarLeft = state.tipTopBarLeft
        tipTopBarMiddle = state.tipTopBarMiddle
        tipTopBarRight = state.tipTopBarRight
        bodyTitleMode = state.bodyTitleMode
        bodyTitleSizeAddSp = state.bodyTitleSizeAddSp
        bodyTitleTopSpacingDp = state.bodyTitleTopSpacingDp
        bodyTitleBottomSpacingDp = state.bodyTitleBottomSpacingDp
        headerMode = state.headerMode
        footerMode = state.footerMode
        tipHeaderLeft = state.tipHeaderLeft
        tipHeaderMiddle = state.tipHeaderMiddle
        tipHeaderRight = state.tipHeaderRight
        tipFooterLeft = state.tipFooterLeft
        tipFooterMiddle = state.tipFooterMiddle
        tipFooterRight = state.tipFooterRight
        readerInfoAlternateSlots.clear()
        readerInfoAlternateSlots.addAll(state.readerInfoAlternateSlots)
        tipColorMode = state.tipColorMode
        tipDividerColorMode = state.tipDividerColorMode
        tipDividerColor = state.tipDividerColor
        headerPaddingTopDp = state.headerPaddingTopDp
        headerPaddingBottomDp = state.headerPaddingBottomDp
        headerPaddingLeftDp = state.headerPaddingLeftDp
        headerPaddingRightDp = state.headerPaddingRightDp
        footerPaddingTopDp = state.footerPaddingTopDp
        footerPaddingBottomDp = state.footerPaddingBottomDp
        footerPaddingLeftDp = state.footerPaddingLeftDp
        footerPaddingRightDp = state.footerPaddingRightDp
        showHeaderLine = state.showHeaderLine
        showFooterLine = state.showFooterLine
        useZhLayout = state.useZhLayout
        textFullJustify = state.textFullJustify
        textBottomJustify = state.textBottomJustify
        clickRegionActions =
            if (state.layoutMode == M9LayoutMode.VERTICAL &&
                state.clickRegionActions == ReadView.defaultClickRegionActions(M9LayoutMode.HORIZONTAL)
            ) {
                ReadView.defaultClickRegionActions(M9LayoutMode.VERTICAL)
            } else {
                state.clickRegionActions
            }
        progressByChapter = state.progressByChapter
        keepScreenOn = state.keepScreenOn
        noAnimScrollPage = state.noAnimScrollPage
        disableReturnKey = state.disableReturnKey
        readBarStyleFollowPage = state.readBarStyleFollowPage
        playbackBarPinnedVisible = state.playbackBarPinnedVisible
        crossPageCueWindowEnabled = state.crossPageCueWindowEnabled
        crossPageCueWindowMode = state.crossPageCueWindowMode
        stopPlaybackOnImage = state.stopPlaybackOnImage
        imagePauseSeconds = state.imagePauseSeconds
        audioCueRepeatPauseUsesCueDuration = state.audioCueRepeatPauseUsesCueDuration
        audioCueRepeatFixedPauseSeconds = state.audioCueRepeatFixedPauseSeconds
        audioCueRepeatFiniteEnabled = state.audioCueRepeatFiniteEnabled
        audioCueRepeatCount = state.audioCueRepeatCount
        audioCueRepeatTailPauseEnabled = state.audioCueRepeatTailPauseEnabled
        audioCueRepeatFollowCueEnabled = state.audioCueRepeatFollowCueEnabled
        sentenceNoCrossPage = state.sentenceNoCrossPage
        pauseAfterPageEnd = state.pauseAfterPageEnd
        audioCueRepeatRemainingCount = initialAudioCueRepeatRemainingCount()
        if (::readView.isInitialized) {
            readView.selectionJumpToCueEnabled = hasReaderSelectionCueJump()
            readView.canJumpSelectionToCue = { selection -> hasReaderSelectionCueJump(selection) }
        }
        verticalControlDirectionReversed = state.verticalControlDirectionReversed
        verticalProgressDirectionReversed = state.verticalProgressDirectionReversed
        selectionPrimaryActionKey = state.selectionPrimaryActionKey
        chapterSourceMode = state.chapterSourceMode
        showRubyText = state.showRubyText
        preferredCharsetName = state.preferredCharsetName
        pendingRestoreAnchor = ReaderPageAnchor(
            chapterIndex = state.currentChapterIndex,
            charPosition = state.currentCharPosition
        )
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun attachSavedAnchorIfNeeded() {
        val bookUri = importedBook?.uri?.toString()
        loadLegadoReaderBookAnchor(this, bookUri)?.let { anchor ->
            pendingRestoreAnchor = ReaderPageAnchor(
                chapterIndex = anchor.chapterIndex,
                charPosition = anchor.charPosition
            )
            return
        }
        val persisted = loadLegadoReaderPersistedState(this)
        if (bookUri != null && persisted.currentBookUri == bookUri) {
            return
        }
        pendingRestoreAnchor = null
    }

    private fun resolveBookReaderStyleIfNeeded() {
        val bookUri = importedBook?.uri?.toString() ?: return
        val bookStyle = loadLegadoReaderBookStyleSelect(this, bookUri) ?: return
        if (bookStyle !in readerStyleConfigs.indices) return
        if (readerStyleSelect == bookStyle) return
        readerStyleSelect = bookStyle
        applySelectedReaderStyleFields()
    }

    private fun persistReaderSettings(
        updateAnchor: Boolean = false,
        anchorOverride: ReaderPageAnchor? = null
    ) {
        pruneReaderInfoAlternateSlots()
        val previous = loadLegadoReaderPersistedState(this)
        val bookUri = importedBook?.uri?.toString()
        val anchor = if (updateAnchor) anchorOverride ?: currentPageAnchor(includeCueMatch = false) else null
        val commitAnchorImmediately = updateAnchor && anchor != null
        if (updateAnchor && anchor != null) {
            saveLegadoReaderBookAnchor(
                this,
                bookUri,
                LegadoReaderBookAnchor(
                    chapterIndex = anchor.chapterIndex,
                    charPosition = anchor.charPosition
                ),
                commitImmediately = true
            )
            saveEbookReadingProgressPercent(
                context = this,
                bookUri = bookUri,
                progressPercent = currentReadingProgressPercent()
            )
        }
        val persistedBookUri = if (updateAnchor && anchor != null) bookUri else previous.currentBookUri
        val persistedChapterIndex = if (updateAnchor && anchor != null) {
            anchor.chapterIndex
        } else {
            previous.currentChapterIndex
        }
        val persistedCharPosition = if (updateAnchor && anchor != null) {
            anchor.charPosition
        } else {
            previous.currentCharPosition
        }
        saveLegadoReaderPersistedState(
            this,
            LegadoReaderPersistedState(
                textSizeSp = readerTextSizeSp,
                lineSpacingDp = readerLineSpacingDp,
                paragraphSpacingDp = readerParagraphSpacingDp,
                letterSpacingDp = readerLetterSpacingDp,
                textWeight = readerTextWeight,
                typefaceIndex = readerTypefaceIndex,
                paragraphIndentCount = readerParagraphIndentCount,
                paddingDp = readerPaddingDp,
                readerPaddingTopDp = readerPaddingTopDp,
                readerPaddingBottomDp = readerPaddingBottomDp,
                readerPaddingLeftDp = readerPaddingLeftDp,
                readerPaddingRightDp = readerPaddingRightDp,
                layoutMode = readerLayoutMode,
                pageAnim = readerPageAnim,
                readerStyleSelect = readerStyleSelectDefault,
                readerNightMode = readerNightMode,
                readerStyleConfigs = readerStyleConfigs.toList(),
                cueHighlightColor = readerCueHighlightColor,
                hideStatusBar = hideStatusBar,
                readBodyToLh = readBodyToLh,
                hideNavigationBar = hideNavigationBar,
                showBrightnessView = showBrightnessView,
                brightnessAuto = brightnessAuto,
                brightnessValue = brightnessValue,
                brightnessPanelOnRight = brightnessPanelOnRight,
                topBarMode = topBarMode,
                tipTopBarLeft = tipTopBarLeft,
                tipTopBarMiddle = tipTopBarMiddle,
                tipTopBarRight = tipTopBarRight,
                bodyTitleMode = bodyTitleMode,
                bodyTitleSizeAddSp = bodyTitleSizeAddSp,
                bodyTitleTopSpacingDp = bodyTitleTopSpacingDp,
                bodyTitleBottomSpacingDp = bodyTitleBottomSpacingDp,
                headerMode = headerMode,
                footerMode = footerMode,
                tipHeaderLeft = tipHeaderLeft,
                tipHeaderMiddle = tipHeaderMiddle,
                tipHeaderRight = tipHeaderRight,
                tipFooterLeft = tipFooterLeft,
                tipFooterMiddle = tipFooterMiddle,
                tipFooterRight = tipFooterRight,
                readerInfoAlternateSlots = readerInfoAlternateSlots.toSet(),
                tipColorMode = tipColorMode,
                tipDividerColorMode = tipDividerColorMode,
                tipDividerColor = tipDividerColor,
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
                useZhLayout = useZhLayout,
                textFullJustify = textFullJustify,
                textBottomJustify = textBottomJustify,
                clickRegionActions = clickRegionActions,
                progressByChapter = progressByChapter,
                keepScreenOn = keepScreenOn,
                noAnimScrollPage = noAnimScrollPage,
                disableReturnKey = disableReturnKey,
                readBarStyleFollowPage = readBarStyleFollowPage,
                playbackBarPinnedVisible = playbackBarPinnedVisible,
                crossPageCueWindowEnabled = crossPageCueWindowEnabled,
                crossPageCueWindowMode = crossPageCueWindowMode,
                stopPlaybackOnImage = stopPlaybackOnImage,
                imagePauseSeconds = imagePauseSeconds,
                audioCueRepeatPauseUsesCueDuration = audioCueRepeatPauseUsesCueDuration,
                audioCueRepeatFixedPauseSeconds = audioCueRepeatFixedPauseSeconds,
                audioCueRepeatFiniteEnabled = audioCueRepeatFiniteEnabled,
                audioCueRepeatCount = audioCueRepeatCount,
                audioCueRepeatTailPauseEnabled = audioCueRepeatTailPauseEnabled,
                audioCueRepeatFollowCueEnabled = audioCueRepeatFollowCueEnabled,
                sentenceNoCrossPage = sentenceNoCrossPage,
                pauseAfterPageEnd = pauseAfterPageEnd,
                verticalControlDirectionReversed = verticalControlDirectionReversed,
                verticalProgressDirectionReversed = verticalProgressDirectionReversed,
                selectionPrimaryActionKey = selectionPrimaryActionKey,
                chapterSourceMode = chapterSourceMode,
                showRubyText = showRubyText,
                preferredCharsetName = preferredCharsetName,
                currentBookUri = persistedBookUri,
                currentChapterIndex = persistedChapterIndex,
                currentCharPosition = persistedCharPosition
            ),
            commitImmediately = commitAnchorImmediately
        )
    }

    private fun currentReadingProgressPercent(): Int {
        val page = pages.getOrNull(pageIndex) ?: return 0
        if (page.documentCharCount > 0 && page.documentCharEnd > 0) {
            return ((page.documentCharEnd.toLong() * 100L) / page.documentCharCount.toLong())
                .toInt()
                .coerceIn(0, 100)
        }
        if (pages.isEmpty()) return 0
        return (((pageIndex.coerceIn(0, pages.lastIndex) + 1) * 100L) / pages.size)
            .toInt()
            .coerceIn(0, 100)
    }

    private fun showSasayakiMatchDialog() {
        val loadedDocument = document
        if (loadedDocument == null || pages.isEmpty()) {
            Toast.makeText(this, R.string.reader_ebook_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        if (cues.isEmpty()) {
            if (srtUri == null) {
                Toast.makeText(this, R.string.reader_no_srt, Toast.LENGTH_SHORT).show()
                return
            }
            loadSrtSyncIfNeeded(force = true) { success ->
                if (success) {
                    showSasayakiMatchDialog()
                } else {
                    Toast.makeText(
                        this,
                        srtLoadError ?: readerString(R.string.reader_srt_parse_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            Toast.makeText(this, R.string.reader_srt_loading, Toast.LENGTH_SHORT).show()
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val summaryText = text(matchSummaryText(), 14f, MENU_TEXT).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val windowText = text(
            getString(R.string.reader_match_search_window, matchSearchWindow),
            14f,
            MENU_TEXT
        )
        val cueHighlightButton = styleConfigButton(
            R.string.reader_match_highlight_color,
            readerCueHighlightColor
        )
        val crossPageWindowModeGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (crossPageCueWindowEnabled) View.VISIBLE else View.GONE
        }.also { group ->
            val radioGroup = RadioGroup(this).apply {
                orientation = RadioGroup.HORIZONTAL
            }
            val options = listOf(
                CrossPageCueWindowMode.OVERLAY to getString(R.string.reader_cross_page_cue_window_overlay),
                CrossPageCueWindowMode.TEMP_PAGE to getString(R.string.reader_cross_page_cue_window_temp_page)
            )
            options.forEach { (mode, label) ->
                radioGroup.addView(RadioButton(this).apply {
                    id = View.generateViewId()
                    text = label
                    textSize = 14f
                    setTextColor(MENU_TEXT)
                    isChecked = crossPageCueWindowMode == mode
                    setOnClickListener {
                        if (crossPageCueWindowMode != mode) {
                            crossPageCueWindowMode = mode
                            renderCurrentPage()
                            persistReaderSettings(updateAnchor = false)
                        }
                    }
                })
            }
            group.addView(radioGroup)
        }
        val crossPageWindowCheck = CheckBox(this).apply {
            text = readerString(R.string.reader_cross_page_cue_window)
            setTextColor(MENU_TEXT)
            textSize = 14f
            isChecked = crossPageCueWindowEnabled
            setOnCheckedChangeListener { _, checked ->
                crossPageCueWindowEnabled = checked
                if (!checked) hideCrossPageCueWindow() else renderCurrentPage()
                crossPageWindowModeGroup.visibility = if (checked) View.VISIBLE else View.GONE
                persistReaderSettings(updateAnchor = false)
            }
        }
        val stopOnImageCheck = CheckBox(this).apply {
            text = readerString(R.string.reader_stop_on_image)
            setTextColor(MENU_TEXT)
            textSize = 14f
            isChecked = stopPlaybackOnImage
        }
        val imagePauseContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (stopPlaybackOnImage) View.VISIBLE else View.GONE
        }
        val imagePauseLabel = text(
            readerString(R.string.reader_image_pause_seconds),
            14f,
            MENU_TEXT
        )
        val imagePauseInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(imagePauseSeconds.toString())
            setTextColor(MENU_TEXT)
            textSize = 14f
            isEnabled = stopPlaybackOnImage
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val value = s?.toString()?.toIntOrNull() ?: return
                    imagePauseSeconds = value.coerceIn(0, MAX_IMAGE_PAUSE_SECONDS)
                    persistReaderSettings(updateAnchor = false)
                }
            })
        }
        stopOnImageCheck.setOnCheckedChangeListener { _, checked ->
            stopPlaybackOnImage = checked
            imagePauseInput.isEnabled = checked
            imagePauseContainer.visibility = if (checked) View.VISIBLE else View.GONE
            if (!checked) {
                lastImageStopKey = null
                clearImagePauseResume()
            }
            persistReaderSettings(updateAnchor = false)
        }
        val reverseControlCheck = CheckBox(this).apply {
            text = readerString(R.string.reader_reverse_vertical_controls)
            setTextColor(MENU_TEXT)
            textSize = 14f
            isChecked = verticalControlDirectionReversed
            setOnCheckedChangeListener { _, checked ->
                verticalControlDirectionReversed = checked
                applyDirectionSettings()
                persistReaderSettings(updateAnchor = false)
            }
        }
        val reverseProgressCheck = CheckBox(this).apply {
            text = readerString(R.string.reader_reverse_vertical_progress)
            setTextColor(MENU_TEXT)
            textSize = 14f
            isChecked = verticalProgressDirectionReversed
            setOnCheckedChangeListener { _, checked ->
                verticalProgressDirectionReversed = checked
                applyDirectionSettings()
                persistReaderSettings(updateAnchor = false)
            }
        }
        val chapterSourceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val chapterSourceLabel = text(readerString(R.string.reader_chapter_source), 14f, MENU_TEXT)
        val chapterSourceValue = text(currentChapterSourceSummary(), 14f, MENU_TEXT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        chapterSourceRow.addView(
            chapterSourceLabel,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        chapterSourceRow.addView(
            chapterSourceValue,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        chapterSourceRow.setOnClickListener {
            val options = arrayOf(
                readerString(R.string.reader_chapter_source_book),
                readerString(R.string.reader_chapter_source_m4b)
            )
            AlertDialog.Builder(this)
                .setTitle(R.string.reader_chapter_source)
                .setSingleChoiceItems(
                    options,
                    if (chapterSourceMode == ReaderChapterSourceMode.BOOK) 0 else 1
                ) { sourceDialog, which ->
                    if (which == 1) {
                        ensureM4bChaptersLoaded { success ->
                            runOnUiThread {
                                if (!success) {
                                    chapterSourceMode = ReaderChapterSourceMode.BOOK
                                    chapterSourceValue.text = currentChapterSourceSummary()
                                    Toast.makeText(
                                        this,
                                        R.string.reader_chapter_source_m4b_unavailable,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    chapterSourceMode = ReaderChapterSourceMode.M4B
                                    chapterSourceValue.text = currentChapterSourceSummary()
                                    updateProgressSeekBar()
                                    updateChapterTitleSurfaces()
                                }
                                persistReaderSettings(updateAnchor = false)
                                sourceDialog.dismiss()
                            }
                        }
                    } else {
                        chapterSourceMode = ReaderChapterSourceMode.BOOK
                        chapterSourceValue.text = currentChapterSourceSummary()
                        updateProgressSeekBar()
                        updateChapterTitleSurfaces()
                        persistReaderSettings(updateAnchor = false)
                        sourceDialog.dismiss()
                    }
                }
                .show()
        }
        val seekBar = SeekBar(this).apply {
            max = MATCH_SEARCH_WINDOW_MAX - MATCH_SEARCH_WINDOW_MIN
            progress = (matchSearchWindow - MATCH_SEARCH_WINDOW_MIN).coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        matchSearchWindow = MATCH_SEARCH_WINDOW_MIN + progress
                        windowText.text = getString(
                            R.string.reader_match_search_window,
                            matchSearchWindow
                        )
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        container.addView(summaryText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))
        container.addView(windowText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)))
        container.addView(seekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        container.addView(cueHighlightButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(42)
        ).apply {
            topMargin = dp(8)
        })
        container.addView(crossPageWindowCheck, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ))
        container.addView(crossPageWindowModeGroup, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(4)
        })
        container.addView(stopOnImageCheck, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ))
        imagePauseContainer.addView(imagePauseLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(28)
        ))
        imagePauseContainer.addView(imagePauseInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ))
        container.addView(imagePauseContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        container.addView(reverseControlCheck, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ))
        container.addView(reverseProgressCheck, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44)
        ))
        container.addView(chapterSourceRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(10)
        })
        cueHighlightButton.setOnClickListener {
            showReaderColorPicker(READER_CUE_HIGHLIGHT_DIALOG_ID, readerCueHighlightColor) { color ->
                readerCueHighlightColor = color
                readView.setCueHighlightColor(readerCueHighlightColor)
                bindStyleConfigButton(
                    cueHighlightButton,
                    R.string.reader_match_highlight_color,
                    readerCueHighlightColor
                )
                renderCurrentPage()
                persistReaderSettings()
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.reader_match_title)
            .setView(container)
            .setPositiveButton(R.string.reader_match_start, null)
            .setNegativeButton(R.string.reader_dialog_done, null)
            .create()
        dialog.setOnShowListener {
            val startButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            startButton.setOnClickListener {
                startButton.isEnabled = false
                summaryText.text = readerString(R.string.reader_match_in_progress)
                lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.Default) {
                            matchEbookCuesData(
                                document = loadedDocument,
                                cues = cues,
                                searchWindow = matchSearchWindow
                            )
                        }
                    }.onSuccess { data ->
                        applyMatchDataInPlace(data)
                        logDebug(LEGADO_READER_LOG_TAG) {
                            "manual match success matches=${data.matches.size} totalCues=${data.totalCues} unmatched=${data.unmatched}"
                        }
                        logDebug(LEGADO_MATCH_LOG_TAG) {
                            "manual success rate=${data.matchRateText} matches=${data.matches.size} totalCues=${data.totalCues} " +
                            "unmatched=${data.unmatched} sampleUnmatched=${unmatchedCueDebugSample(data)}"
                        }
                        summaryText.text = matchSummaryText()
                    }.onFailure { error ->
                        summaryText.text = getString(
                            R.string.reader_match_failed,
                            error.message ?: error.javaClass.simpleName
                        )
                    }
                    startButton.isEnabled = true
                }
            }
        }
        dialog.show()
    }

    private fun matchSummaryText(): String {
        val current = matchData
        return if (current == null) {
            getString(R.string.reader_match_summary_unmatched, cues.size)
        } else {
            getString(
                R.string.reader_match_summary_matched,
                current.matchRateText,
                current.matches.size,
                current.totalCues
            )
        }
    }

    private fun loadSrtSyncIfNeeded(
        force: Boolean = false,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val uri = srtUri ?: return
        val uriText = uri.toString()
        if (!force && loadedSrtUriText == uriText && cues.isNotEmpty()) {
            publishReaderSubtitleBridgeSnapshot(clearWhenMissing = false)
            onComplete?.invoke(true)
            return
        }
        // 已经在加载：in-flight 的 deferred 就是唯一状态（不再另设布尔旗标）。语义是"在加载"，
        // 不是"加载失败"——有等待方就等这一趟结束、回它的真实结果，没有等待方直接返回。
        // force 在这里**有意一并合并**：走到这一步的调用方（重复按钮 / 跳句 / 目录）要的是"SRT 现在
        // 可用"，不是"重新解析磁盘"；而换字幕走的是另一个 Activity 实例（srtUri 只在 onCreate 赋值
        // 一次），所以不会等到别的 URI 的那一趟。别按字面给 force 再加"排队重跑一次"。
        val inFlight = srtLoadInFlight
        if (inFlight != null) {
            if (onComplete != null) {
                lifecycleScope.launch { onComplete(inFlight.await()) }
            }
            return
        }
        srtLoadError = null
        val loadResult = CompletableDeferred<Boolean>()
        srtLoadInFlight = loadResult
        lifecycleScope.launch {
            val srtStartMs = SystemClock.elapsedRealtime()
            runCatching {
                val cachedSnapshot = if (force) {
                    null
                } else {
                    withContext(Dispatchers.IO) {
                        loadLegadoReaderSrtSnapshotOrNull(this@LegadoReaderActivity, uri)
                    }
                }
                if (cachedSnapshot != null) {
                    logDebug(LEGADO_READER_LOG_TAG) {
                        "loadSrtSyncIfNeeded cacheHit=${SystemClock.elapsedRealtime() - srtStartMs}ms " +
                        "cues=${cachedSnapshot.cues.size} uri=$uriText"
                    }
                    logDebug(LEGADO_MATCH_LOG_TAG) {
                        "srt cacheHit cues=${cachedSnapshot.cues.size} first=${cachedSnapshot.cues.firstOrNull()?.startMs} " +
                        "last=${cachedSnapshot.cues.lastOrNull()?.startMs} uri=${uriText.take(80)}"
                    }
                    cachedSnapshot.cues
                } else {
                    val loadedCues = parseEbookSrt(contentResolver, uri)
                    withContext(Dispatchers.IO) {
                        saveLegadoReaderSrtSnapshot(this@LegadoReaderActivity, uri, loadedCues)
                    }
                    logDebug(LEGADO_READER_LOG_TAG) {
                        "loadSrtSyncIfNeeded parsed=${SystemClock.elapsedRealtime() - srtStartMs}ms " +
                        "cues=${loadedCues.size} uri=$uriText force=$force"
                    }
                    logDebug(LEGADO_MATCH_LOG_TAG) {
                        "srt parsed cues=${loadedCues.size} first=${loadedCues.firstOrNull()?.startMs} " +
                        "last=${loadedCues.lastOrNull()?.startMs} force=$force uri=${uriText.take(80)}"
                    }
                    loadedCues
                }
            }.onSuccess { loadedCues ->
                cues = loadedCues
                loadedSrtUriText = uriText
                cueMatchesByCueIndex = emptyMap()
                matchData = null
                audioCueIndex = -1
                activeCueIndex = -1
                logDebug(LEGADO_READER_LOG_TAG) {
                    "loadSrtSyncIfNeeded ready=${SystemClock.elapsedRealtime() - srtStartMs}ms " +
                    "cues=${loadedCues.size} uri=$uriText reset in-memory match cache"
                }
                logDebug(LEGADO_MATCH_LOG_TAG) {
                    "srt ready cues=${loadedCues.size} resetMatches=true sampleFirst=${cueDebugText(loadedCues.firstOrNull())}"
                }
                srtLoadError = if (loadedCues.isEmpty()) {
                    readerString(R.string.reader_srt_parse_failed_detail)
                } else {
                    null
                }
                publishReaderSubtitleBridgeSnapshot(clearWhenMissing = true)
                if (loadedCues.isNotEmpty()) {
                    logDebug(LEGADO_READER_LOG_TAG) {
                        "loadSrtSyncIfNeeded trying persisted restore after SRT load"
                    }
                    restorePersistedMatchIfPossible()
                }
                onComplete?.invoke(loadedCues.isNotEmpty())
                loadResult.complete(loadedCues.isNotEmpty())
            }.onFailure { error ->
                Log.w(
                    LEGADO_READER_LOG_TAG,
                    "loadSrtSyncIfNeeded failed after ${SystemClock.elapsedRealtime() - srtStartMs}ms uri=$uriText",
                    error
                )
                srtLoadError = getString(
                    R.string.reader_srt_load_failed,
                    error.message ?: error.javaClass.simpleName
                )
                cues = emptyList()
                loadedSrtUriText = null
                publishReaderSubtitleBridgeSnapshot(clearWhenMissing = true)
                onComplete?.invoke(false)
                loadResult.complete(false)
            }
            srtLoadInFlight = null
            if (srtLoadError != null && force) {
                Toast.makeText(this@LegadoReaderActivity, srtLoadError, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = lifecycleScope.launch {
            var lastProgressSaveAt = 0L
            var timerFadeApplied = false
            while (true) {
                delay(350L)
                val currentPlayer = player
                audioStopAtMs?.let { stopAt ->
                    val remainingMs = stopAt - System.currentTimeMillis()
                    if (
                        audioTimerFadeOutEnabled &&
                        remainingMs in 1L until BOOK_READER_SLEEP_FADE_OUT_MS &&
                        isAudioPlaybackRequested()
                    ) {
                        currentPlayer?.volume = sleepFadeVolume(remainingMs, BOOK_READER_SLEEP_FADE_OUT_MS)
                        timerFadeApplied = true
                    } else if (timerFadeApplied && remainingMs > BOOK_READER_SLEEP_FADE_OUT_MS) {
                        currentPlayer?.volume = 1f
                        timerFadeApplied = false
                    }
                    if (remainingMs <= 0L) {
                        cancelAudioCueRepeatDelay()
                        if (currentPlayer != null && audioTimerFadeOutEnabled) {
                            pauseWithSleepFadeRewind(currentPlayer)
                        } else {
                            currentPlayer?.volume = 1f
                            currentPlayer?.pause()
                        }
                        BookReaderFloatingBridge.notifyPlaybackState(false)
                        audioStopAtMs = null
                        audioTimerSelectedMinutes = 0
                        if (::audioTimerSeekBar.isInitialized) {
                            audioTimerSeekBar.progress = 0
                        }
                        timerFadeApplied = false
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastProgressSaveAt >= 2_500L) {
                    persistAudioPlaybackSnapshot()
                    lastProgressSaveAt = now
                }
                publishReaderPlaybackBridgeSnapshot(notifyState = false)
                updateAudioControlLabels()
                if (isAudioPlaying()) {
                    syncToAudioPosition(allowPageJump = true)
                }
            }
        }
    }

    private fun syncToAudioPosition(
        allowPageJump: Boolean = true,
        forceReveal: Boolean = false
    ) {
        lockedAudioSeekSyncTargetMs()?.let { targetMs ->
            syncToAudioPositionAt(
                positionMs = targetMs,
                allowPageJump = allowPageJump,
                forceReveal = forceReveal
            )
            return
        }
        val currentPosition = currentAudioPositionMs() ?: run {
            if (forceReveal) {
                logDebug(LEGADO_MATCH_LOG_TAG) { "sync skipped currentPosition=null" }
            }
            return
        }
        syncToAudioPositionAt(
            positionMs = currentPosition,
            allowPageJump = allowPageJump,
            forceReveal = forceReveal
        )
    }

    private fun syncToAudioPositionAt(
        positionMs: Long,
        allowPageJump: Boolean = true,
        forceReveal: Boolean = false,
        preferredCueIndex: Int? = null
    ) {
        if (cues.isEmpty() || pages.isEmpty()) {
            if (forceReveal) {
                logDebug(LEGADO_MATCH_LOG_TAG) {
                    "sync skipped empty cues=${cues.size} pages=${pages.size} allowPageJump=$allowPageJump forceReveal=$forceReveal"
                }
            }
            return
        }
        val currentPosition = positionMs.coerceAtLeast(0L)
        val cueIndex = preferredCueIndex
            ?.takeIf { it in cues.indices }
            ?: findEbookCueIndexAtTime(cues, currentPosition)
        if (cueIndex < 0) {
            if (forceReveal) {
                logDebug(LEGADO_MATCH_LOG_TAG) {
                    "sync noCue position=$currentPosition cues=${cues.size} first=${cues.firstOrNull()?.startMs} last=${cues.lastOrNull()?.endMs}"
                }
            }
            val changed = activeCueIndex != -1
            audioCueIndex = -1
            activeCueIndex = -1
            if (textSelectionActive && !forceReveal) {
                updateDisplayedCueHighlightOnly()
                return
            }
            if (changed) {
                renderCurrentPage()
            } else {
                hideCrossPageCueWindow()
            }
            return
        }
        val previousAudioCueIndex = audioCueIndex
        val cueChanged = cueIndex != previousAudioCueIndex
        if (!audioCueLoopClipActive) {
            audioCueIndex = cueIndex
        }
        val match = cueMatchesByCueIndex[cueIndex]
        // 外部播放键（悬浮字幕 / 通知 / 手表 / 耳机）在页尾暂停后恢复播放：播放器已经在放了，但
        // "翻页续播"这条流程没走。这里补上和阅读器自己的播放键同一处理——翻到下一页再继续；
        // 否则画面仍停在旧页、"刚播完的那一句"仍被认作本页最后一句，下面的兜底检查会在下一拍又把
        // 它停住（表现就是"点了播放又停一下，得再点一次"，同 6397 那段注释）。
        if (pageEndPausePending && isAudioPlaybackRequested()) {
            logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
                "pageEndPause: external resume while pending -> move page"
            }
            movePage(1)
            // 本章最后一页会走换章加载那条路（movePage 里不会调 resume）→ 这里把 pending 收掉，
            // 免得每一拍都再翻一次页。
            pageEndPausePending = false
            return
        }
        if (!cueChanged && !forceReveal) {
            if (match != null && maybePausePlaybackForImage(
                    currentPosition = currentPosition,
                    cueIndex = cueIndex,
                    previousCueIndex = previousAudioCueIndex,
                    cueChanged = false,
                    match = match,
                    allowPageJump = allowPageJump
                )
            ) {
                return
            }
            return
        }
        if (forceReveal || match == null) {
            logDebug(LEGADO_MATCH_LOG_TAG) {
                "sync position=$currentPosition ${cueDebugSummary(cueIndex)} ${matchDebugSummary(match)} " +
                "matches=${cueMatchesByCueIndex.size}/${cues.size} allowPageJump=$allowPageJump " +
                "forceReveal=$forceReveal preferred=$preferredCueIndex"
            }
        }
        if (textSelectionActive && !forceReveal) {
            activeCueIndex = if (match != null) cueIndex else -1
            updateDisplayedCueHighlightOnly()
            return
        }
        if (match == null) {
            logDebug(LEGADO_MATCH_LOG_TAG) {
                "sync missingMatch ${cueDebugSummary(cueIndex)} nearby=${nearbyMatchedCueSummary(cueIndex)}"
            }
            activeCueIndex = -1
            // SRT 里用 ＊ 标注章节题图的位置（如「＊──市井にて──」）：
            // 这时直接翻到该章节的第一页，让题图像翻页一样被看到；
            // 只翻页不暂停（这类题图不算插图，不触发遇图暂停）。
            if (allowPageJump && showSectionTitlePageForCue(cueIndex)) {
                return
            }
            updateDisplayedCueHighlightOnly()
            return
        }
        if (maybePausePlaybackForImage(
                currentPosition = currentPosition,
                cueIndex = cueIndex,
                previousCueIndex = previousAudioCueIndex,
                cueChanged = cueChanged,
                match = match,
                allowPageJump = allowPageJump
            )
        ) {
            return
        }
        // 读完此页暂停：刚播完的上一句是本页最后一句 → 暂停，翻页后自动继续
        // （主要精确停止靠整页裁剪的 STATE_ENDED；这里保留作兜底，
        //   覆盖武装未生效/seek 直接跳到最后一句的场景）
        if (cueChanged && previousAudioCueIndex >= 0) {
            if (maybePauseForPageEnd(previousAudioCueIndex)) {
                // 兜底暂停已触发：画面保持当前页（最后一句所在页），不再翻到下一页
                logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
                    "pageEndPause: keep current page after fallback pause"
                }
                return
            }
            // cue 刚变化：让下一次"未变化"的日志立刻输出，便于看清变化后的上下文
            lastSyncSkipLogAtMs = 0L
        } else {
            // 这里是每 350ms 一次的 tick 最常见的分支。曾经每 tick 打一条，约 171 行/分钟，
            // 会在 ~70 秒内把 logcat 的 200 行窗口冲满，导致导出诊断时看不到有用的行；
            // 现在 5 秒最多一条，并把期间的次数聚合进同一条日志。
            val nowMs = SystemClock.elapsedRealtime()
            syncSkipLogCount += 1
            if (nowMs - lastSyncSkipLogAtMs >= SYNC_SKIP_LOG_INTERVAL_MS) {
                logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
                    "pageEndPause: sync skip cueChanged=$cueChanged prev=$previousAudioCueIndex" +
                    " (x$syncSkipLogCount)"
                }
                lastSyncSkipLogAtMs = nowMs
                syncSkipLogCount = 0
            }
        }
        // 读完此页暂停：保证「本页」已武装——暂停态换成本页整页裁剪（播到页尾由解码层
        // 精确截断，既不重读最后一句也不读进下一页第一句）；播放中途无法重载时退化为
        // 句尾监视兜底。功能关闭时解除武装并恢复完整音频。
        if (!pageEndPauseActive) {
            releasePageEndPauseClipIfActive()
        } else {
            refreshPageEndPauseArming(currentPosition)
        }
        activeCueIndex = cueIndex
        if (!allowPageJump) {
            renderCurrentPage()
            return
        }
        val matchAnchor = ReaderPageAnchor(match.chapterIndex, match.rawStart)
        val startPageIndex = findTextPageForMatch(match)
        if (startPageIndex == null) {
            logDebug(LEGADO_MATCH_LOG_TAG) {
                "sync noPage ${matchDebugSummary(match)} pageIndex=$pageIndex pages=${pages.size}"
            }
            activeCueIndex = cueIndex
            persistReaderAnchor(matchAnchor)
            if (pendingAudioSyncLoadAnchor == matchAnchor && paginationJob?.isActive == true) {
                return
            }
            pendingAudioSyncLoadAnchor = matchAnchor
            loadDisplayedBook(
                anchor = matchAnchor,
                forceDocumentReload = false
            )
            return
        }
        if (startPageIndex == pageIndex) {
            if (forceReveal) {
                logDebug(LEGADO_MATCH_LOG_TAG) {
                    "sync samePage ${matchDebugSummary(match)} pageIndex=$pageIndex"
                }
            }
            updateDisplayedCueHighlightOnly()
            persistReaderAnchor(matchAnchor)
            return
        }
        val previousPageIndex = pageIndex
        pageIndex = startPageIndex
        if (forceReveal) {
            logDebug(LEGADO_MATCH_LOG_TAG) {
                "sync jumpPage ${matchDebugSummary(match)} page=$previousPageIndex->$startPageIndex"
            }
        }
        renderCurrentPage(forward = startPageIndex >= previousPageIndex)
        persistReaderAnchor(matchAnchor)
    }

    private fun updateDisplayedCueHighlightOnly() {
        val page = pages.getOrNull(pageIndex) ?: return
        val match = currentPageCueMatch(page)
        readView.setCueHighlight(highlightTextPage(page, match))
        updateCrossPageCueWindow(page, match)
        updateChapterTitleSurfaces()
    }

    private fun pausePlaybackAtImage(target: ReaderImageStopTarget) {
        logDebug(READER_PAUSED_SEEK_LOG_TAG) {
            "legado pauseAtImage chapter=${target.chapterIndex} position=${target.imagePosition} " +
            "page=$pageIndex actual=${player?.currentPosition} audioCue=$audioCueIndex"
        }
        lastImageStopKey = target.key
        advanceCurrentChapterImageStopIndex(target.key)
        player?.pause()
        BookReaderFloatingBridge.notifyPlaybackState(false)
        activeCueIndex = -1
        // 遇图暂停会把画面切到图片页，但"读完此页暂停"的武装还停在**上一页**：它的终点
        // （上一页末句句尾）此刻已经被越过，恢复播放后要么被句尾监视的第一轮轮询立刻判到
        // （positionMs >= safeEndMs），要么整页裁剪让播放器一恢复就 STATE_ENDED —— 表现为
        // "继续播放后的第一个 cue 不播放"。这里先解除，恢复播放时再按当前 cue 重新武装
        // （resumeFromImagePause 里的强制同步会做这件事）。
        val armedClip = pageEndPauseRange != null
        val armedWatchEnd = pageEndPauseWatchEndMs
        releasePageEndPauseClipIfActive()
        logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
            "imagePause: release page-end arming clip=$armedClip watchEnd=$armedWatchEnd page=$pageIndex"
        }
        findImagePageIndex(target)?.let { imagePage ->
            if (imagePage != pageIndex) pageIndex = imagePage
        }
        imagePausePageIndex = pageIndex
        renderCurrentPage()
        updateAudioControlLabels()
        persistAudioPlaybackSnapshot()
        scheduleImagePauseResume()
    }

    private fun scheduleImagePauseResume() {
        imagePauseResumeJob?.cancel()
        imagePauseResumeJob = null
        val delayMs = imagePauseSeconds.coerceIn(0, MAX_IMAGE_PAUSE_SECONDS) * 1000L
        if (delayMs <= 0L) return
        imagePauseResumeJob = lifecycleScope.launch {
            delay(delayMs)
            resumeFromImagePause()
        }
    }

    private fun resumeImagePauseIfPageLeft() {
        val pausedPageIndex = imagePausePageIndex ?: return
        if (pageIndex != pausedPageIndex) {
            // 用户自己翻页离开了图片页：不要把他拽回 cue 所在页
            resumeFromImagePause(revealCuePage = false)
        }
    }

    /**
     * 图片暂停结束、继续播放。
     *
     * 暂停时画面被切到图片页，而播放位置还停在**触发暂停的那一句**；恢复播放时如果只 play()，
     * 同步逻辑会因为"cue 没变"直接返回（[syncToAudioPositionAt] 里 `!cueChanged && !forceReveal`
     * 那个分支），画面就留在图片页上，要等**下一个 cue** 变化才跟随翻页——也就是"慢一个 cue
     * 才翻到下一页"。所以恢复播放后强制按当前 cue 重新定位一次页面。
     */
    private fun resumeFromImagePause(revealCuePage: Boolean = true) {
        val hadPendingPause = imagePausePageIndex != null
        clearImagePauseResume()
        if (!hadPendingPause) return
        val currentPlayer = player ?: return
        if (!currentPlayer.isPlaying) {
            currentPlayer.play()
            publishReaderPlaybackBridgeSnapshot(notifyState = true)
            updateAudioControlLabels()
        }
        if (revealCuePage) {
            logDebug(M9_SENTENCE_TAIL_LOG_TAG) {
                "imagePause: resume pos=${currentPlayer.currentPosition} cue=$audioCueIndex " +
                "clip=${pageEndPauseRange != null} watchEnd=$pageEndPauseWatchEndMs page=$pageIndex"
            }
            syncToAudioPosition(allowPageJump = true, forceReveal = true)
        }
    }

    private fun clearImagePauseResume() {
        imagePauseResumeJob?.cancel()
        imagePauseResumeJob = null
        imagePausePageIndex = null
    }

    private fun findImagePageIndex(target: ReaderImageStopTarget): Int? {
        return pages.indexOfFirst { page ->
            page.chapterIndex == target.chapterIndex &&
                target.imagePosition >= page.charStart &&
                target.imagePosition < page.charEnd
        }.takeIf { it >= 0 }
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        if (dialogId != pendingReaderColorDialogId) return
        val callback = pendingReaderColorSelected ?: return
        pendingReaderColorDialogId = -1
        pendingReaderColorSelected = null
        callback(color)
    }

    override fun onDialogDismissed(dialogId: Int) {
        if (dialogId != pendingReaderColorDialogId) return
        pendingReaderColorDialogId = -1
        pendingReaderColorSelected = null
    }

    override fun onDestroy() {
        tipConfigDialog?.setOnDismissListener(null)
        tipConfigDialog?.dismiss()
        tipConfigDialog = null
        reloadBookJob?.cancel()
        paginationJob?.cancel()
        chapterPreloadJob?.cancel()
        m4bChapterLoadJob?.cancel()
        syncJob?.cancel()
        audioCueRepeatDelayJob?.cancel()
        audioCueLoopEnabled = false
        restoreAudioCueLoopMediaIfNeeded()
        imagePauseResumeJob?.cancel()
        floatingOverlayStartJob?.cancel()
        ReaderPlaybackScreenVisibility.markHidden(this)
        persistAudioPlaybackSnapshot()
        BookReaderFloatingBridge.removePlaybackStateListener(sharedPlaybackStateListener)
        BookReaderFloatingBridge.removePlaybackPositionListener(sharedPlaybackPositionListener)
        audioPlayerLoopListener?.let { player?.removeListener(it) }
        audioPlayerLoopListener = null
        player = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = value.dp(this)

    companion object {
        const val EXTRA_EBOOK_TITLE = "extra_ebook_title"
        const val EXTRA_EBOOK_URI = "extra_ebook_uri"
        const val EXTRA_EBOOK_NAME = "extra_ebook_name"
        const val EXTRA_EBOOK_FORMAT = "extra_ebook_format"
        const val EXTRA_AUDIO_URI = "extra_audio_uri"
        const val EXTRA_SRT_URI = "extra_srt_uri"
        const val EXTRA_AUDIO_POSITION_MS = "extra_audio_position_ms"
        const val EXTRA_AUDIO_DURATION_MS = "extra_audio_duration_ms"
        const val EXTRA_RETURN_TO_PLAYER_ON_BACK = "extra_return_to_player_on_back"
        private const val DEFAULT_MATCH_SEARCH_WINDOW = 200
        private const val MATCH_SEARCH_WINDOW_MIN = 50
        private const val MATCH_SEARCH_WINDOW_MAX = 1000
        private const val READER_PAGE_BG = 0xFFF3E7CF.toInt()
        private const val READER_TEXT = 0xFF2C241B.toInt()
        private const val READER_TIP = 0xFF7D6E5C.toInt()
        private const val READER_TEXT_COLOR_DIALOG_ID = 121
        private const val READER_BG_COLOR_DIALOG_ID = 122
        private const val READER_CUE_HIGHLIGHT_DIALOG_ID = 123
        private const val READER_TIP_COLOR_DIALOG_ID = 124
        private const val READER_TIP_DIVIDER_COLOR_DIALOG_ID = 125
        private const val LEGADO_COLOR_PICKER_IMAGE_BG_FALLBACK = 0xFF015A86.toInt()
        private const val AUDIO_SEEK_VERIFY_DELAY_MS = 450L
        private const val AUDIO_SEEK_RECOVER_VERIFY_DELAY_MS = 650L
        private const val AUDIO_SEEK_SETTLE_VERIFY_DELAY_MS = 1_400L
        private const val AUDIO_SEEK_FALLBACK_TOLERANCE_MS = 1_500L
        private const val AUDIO_SEEK_SYNC_LOCK_MS = 3_500L
        private const val AUDIO_SEEK_SYNC_LOCK_AFTER_DISPLAY_MS = 2_000L
        private const val AUDIO_SEEK_DISPLAY_CLEAR_TOLERANCE_MS = 10_000L
        private const val AUDIO_SEEK_STALE_POSITION_TOLERANCE_MS = 30_000L
        private const val AUDIO_TIMER_MAX_MINUTES = 180
        private const val M4B_CHAPTER_TEXT_SYNC_LOOKAHEAD_MS = 8_000L
        private const val MENU_TEXT = 0xFF2C241B.toInt()
    }
}
