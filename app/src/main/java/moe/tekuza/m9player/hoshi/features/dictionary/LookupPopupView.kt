package moe.tekuza.m9player.hoshi.features.dictionary

import android.annotation.SuppressLint
import android.net.Uri
import android.os.SystemClock
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import de.manhhao.hoshi.LookupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.tekuza.m9player.hoshi.webview.applyHoshiWebViewSecurityDefaults
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionData
import moe.tekuza.m9player.AnkiDuplicateCheckResult
import moe.tekuza.m9player.DICTIONARY_MEDIA_RESPONSE_MAX_BYTES
import moe.tekuza.m9player.HoshiCardBackground
import moe.tekuza.m9player.HoshiDarkCardBackground
import moe.tekuza.m9player.HoshiDarkPopupBorder
import moe.tekuza.m9player.R
import moe.tekuza.m9player.destroyWebViewSafely
import moe.tekuza.m9player.logDebug
import moe.tekuza.m9player.ZoomableImagePreview
import moe.tekuza.m9player.hoshi.dictionary.HoshiDictionaryQuerySession

private val LookupPopupActionBarHeight = 44.dp
private const val HOSHI_LOOKUP_POPUP_LOG_TAG = "HoshiLookupPopup"

@Composable
internal fun LookupPopupView(
    state: LookupPopupState,
    onSwipeDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    lookupStartedAtNanos: Long = SystemClock.elapsedRealtimeNanos(),
    clearSelectionSignal: Int = 0,
    backSignal: Int = 0,
    forwardSignal: Int = 0,
    onTapOutside: () -> Unit = onSwipeDismiss,
    onTextSelected: (ReaderSelectionData) -> Int? = { null },
    onRangeSelection: (() -> Unit)? = null,
    onMineEntry: ((String) -> Boolean)? = null,
    onMineEntryAsync: ((String, (Boolean) -> Unit) -> Unit)? = null,
    onDuplicateCheck: ((String) -> AnkiDuplicateCheckResult)? = null,
    onDuplicateCheckAsync: ((String, (AnkiDuplicateCheckResult) -> Unit) -> Unit)? = null,
    onViewDuplicate: ((List<Long>) -> Boolean)? = null,
    onPlayWordAudio: ((String, String?, String?) -> Unit)? = null,
    onCloseAll: (() -> Unit)? = null,
    showActionBar: Boolean = false,
    showCloseAll: Boolean = false,
    warmShell: Boolean = false,
    contentResetKey: Any? = null,
    isPopupActive: Boolean = true,
    isContentVisible: Boolean = true,
    onLookupRedirect: (String) -> List<LookupResult> = { query ->
        moe.tekuza.m9player.hoshi.dictionary.LookupEngine.lookup(
            query,
            state.dictionarySettings.maxResults,
            state.dictionarySettings.scanLength,
        )
    },
    onLookupRedirected: (ReaderSelectionData) -> Unit = {},
    isLookupPopupActive: () -> Boolean = { true },
    platformPopupHost: Boolean = false,
    extraBottomInsetDp: Double = 0.0,
) {
    if (state.results.isEmpty() && !warmShell) {
        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
            "view skipped reason=empty_results selection='${state.selection.text.take(32)}' rect=${state.selection.rect.x},${state.selection.rect.y} ${state.selection.rect.width}x${state.selection.rect.height}"
        }
        return
    }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val assets = remember(context) { LookupPopupAssets.load(context) }
    val htmlResults = if (warmShell) emptyList() else state.results
    val html = remember(
        htmlResults,
        state.dictionaryStyles,
        state.dictionarySettings,
        state.swipeToDismiss,
        state.swipeThreshold,
        state.darkMode,
        state.eInkMode,
        state.audioSettings,
        state.showPlayAudio,
        state.showRangeSelection,
    ) {
        LookupPopupHtml.render(
            results = htmlResults,
            assets = assets,
            dictionaryStyles = state.dictionaryStyles,
            settings = state.dictionarySettings,
            audioSettings = state.audioSettings,
            showPlayAudio = state.showPlayAudio,
            swipeToDismiss = state.swipeToDismiss,
            swipeThreshold = state.swipeThreshold,
            darkMode = state.darkMode,
            eInkMode = state.eInkMode,
            showRangeSelection = state.showRangeSelection,
        )
    }
    var contentReady by remember(html, contentResetKey) { mutableStateOf(false) }
    var firstPaintLogged by remember(contentResetKey, lookupStartedAtNanos) { mutableStateOf(false) }
    var requestedBackSignal by remember { mutableStateOf(backSignal) }
    var requestedForwardSignal by remember { mutableStateOf(forwardSignal) }
    var historyBackCount by remember(contentResetKey) { mutableStateOf(0) }
    var historyForwardCount by remember(contentResetKey) { mutableStateOf(0) }
    val frame = remember(
        state.selection.rect,
        state.avoidRects,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        state.width,
        state.height,
        state.isVertical,
        state.isFullWidth,
        state.topInset,
        state.bottomInset,
        extraBottomInsetDp,
    ) {
        LookupPopupLayout(
            selectionRect = state.selection.rect,
            avoidRects = state.avoidRects,
            screenWidth = configuration.screenWidthDp.toDouble(),
            screenHeight = configuration.screenHeightDp.toDouble(),
            maxWidth = state.width.toDouble(),
            maxHeight = state.height.toDouble() + if (showActionBar) LookupPopupActionBarHeight.value.toDouble() else 0.0,
            isVertical = state.isVertical,
            isFullWidth = state.isFullWidth,
            topInset = state.topInset,
            bottomInset = state.bottomInset + extraBottomInsetDp,
        ).calculate()
    }
    val frameX = frame.centerX - frame.width / 2
    val frameY = frame.centerY - frame.height / 2
    val effectiveFrameX = if (isPopupActive) frameX else -10000.0
    val effectiveFrameY = if (isPopupActive) frameY else -10000.0
    val popupShape = if (state.eInkMode) RectangleShape else RoundedCornerShape(18.dp)
    val popupBackground = if (state.darkMode) HoshiDarkCardBackground else HoshiCardBackground
    val contentBackground = if (state.darkMode) HoshiDarkCardBackground else HoshiCardBackground
    val popupBorder = when {
        state.eInkMode && state.darkMode -> Color.White
        state.eInkMode -> Color.Black
        state.darkMode -> HoshiDarkPopupBorder
        else -> Color(0x477A7F87)
    }
    logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
        "view active=$isPopupActive visible=$isContentVisible warmShell=$warmShell showActionBar=$showActionBar popupActionBar=${state.popupActionBar} hasCloseAll=${onCloseAll != null} results=${state.results.size} " +
            "selectionRect(screenDp)=${state.selection.rect.x},${state.selection.rect.y} ${state.selection.rect.width}x${state.selection.rect.height} " +
            "avoidRects=${state.avoidRects.size} " +
            "popupFrame(screenDp)=${frameX},${frameY} ${frame.width}x${frame.height} " +
            "popupTopGapDp=${frameY - (state.selection.rect.y + state.selection.rect.height)}"
    }
    val densityScale = density.density.coerceAtLeast(0.1f)
    @Composable
    fun PopupCard() {
        Surface(
            modifier = Modifier
                .then(
                    if (platformPopupHost) {
                        Modifier
                    } else {
                        Modifier.offset {
                            IntOffset(
                                x = (effectiveFrameX * densityScale).toInt(),
                                y = (effectiveFrameY * densityScale).toInt(),
                            )
                        }
                    }
                )
                .then(if (isPopupActive) Modifier.width(frame.width.dp).height(frame.height.dp) else Modifier.size(1.dp))
                .alpha(if (contentReady && isPopupActive && isContentVisible) 1f else 0f)
                .clip(popupShape)
                .background(popupBackground),
            shape = popupShape,
            color = popupBackground,
            border = BorderStroke(1.dp, popupBorder),
            tonalElevation = 0.dp,
            shadowElevation = if (state.eInkMode) 0.dp else 10.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(10.dp),
                    shape = popupShape,
                    color = contentBackground,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    LookupPopupWebView(
                        html = html,
                        results = state.results,
                        assets = assets,
                        darkMode = state.darkMode,
                        backgroundColor = contentBackground,
                        selectionOffsetX = frameX + 10.0,
                        selectionOffsetY = frameY + 10.0,
                        clearSelectionSignal = clearSelectionSignal,
                        backSignal = requestedBackSignal,
                        forwardSignal = requestedForwardSignal,
                        warmShell = warmShell,
                        callbacks = PopupWebViewCallbacks(
                            onTapOutside = onTapOutside,
                            onSwipeDismiss = onSwipeDismiss,
                            onRangeSelection = onRangeSelection ?: {},
                            onPlayWordAudio = onPlayWordAudio ?: { _, _, _ -> },
                            onImageTap = { src ->
                                if (isHoshiPreviewImageCandidate(src)) {
                                    context.openHoshiImagePreview(src)
                                }
                            },
                            onMineEntry = onMineEntry ?: { false },
                            onMineEntryAsync = onMineEntryAsync,
                            onDuplicateCheck = onDuplicateCheck ?: { AnkiDuplicateCheckResult() },
                            onDuplicateCheckAsync = onDuplicateCheckAsync,
                            onViewDuplicate = onViewDuplicate ?: { false },
                            onOpenLink = { context.openPopupExternalLink(it) },
                            onTextSelected = onTextSelected,
                            onLookupRedirect = onLookupRedirect,
                            isLookupPopupActive = isLookupPopupActive,
                            onLookupRedirected = { selection, results ->
                                logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
                                    "redirected callback text='${selection.text.take(48)}' results=${results.size}"
                                }
                                logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
                                    "redirected query='${selection.text.take(32)}' resultCount=${results.size} freqCount=${results.firstOrNull()?.term?.frequencies?.size ?: 0} pitchCount=${results.firstOrNull()?.term?.pitches?.size ?: 0} rect=${selection.rect.x},${selection.rect.y} ${selection.rect.width}x${selection.rect.height}"
                                }
                                onLookupRedirected(selection)
                            },
                            onHistoryChanged = { backCount, forwardCount ->
                                logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
                                    "history state updated back=$backCount forward=$forwardCount"
                                }
                                historyBackCount = backCount
                                historyForwardCount = forwardCount
                            },
                            onContentReady = {
                                logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
                                    "content ready text='${state.selection.text.take(48)}' warmShell=$warmShell active=$isPopupActive visible=$isContentVisible"
                                }
                                contentReady = true
                                if (!firstPaintLogged && isPopupActive && isContentVisible) {
                                    firstPaintLogged = true
                                    logDebug("HoshiLookupPerf") {
                                        "popupFirstPaint elapsedMs=${(SystemClock.elapsedRealtimeNanos() - lookupStartedAtNanos) / 1_000_000.0} " +
                                            "query='${state.selection.text.take(32)}' results=${state.results.size} warmShell=$warmShell"
                                    }
                                }
                            },
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (showActionBar) {
                    LookupPopupActionBar(
                        onClose = onSwipeDismiss,
                        showCloseAll = showCloseAll,
                        onCloseAll = onCloseAll,
                        historyBackCount = historyBackCount,
                        historyForwardCount = historyForwardCount,
                        onNavigateBack = { requestedBackSignal += 1 },
                        onNavigateForward = { requestedForwardSignal += 1 },
                    )
                }
            }
        }
    }

    if (platformPopupHost) {
        Popup(
            popupPositionProvider = remember(effectiveFrameX, effectiveFrameY, densityScale) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: androidx.compose.ui.unit.IntRect,
                        windowSize: androidx.compose.ui.unit.IntSize,
                        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                        popupContentSize: androidx.compose.ui.unit.IntSize,
                    ): IntOffset = IntOffset(
                        x = (effectiveFrameX * densityScale).toInt(),
                        y = (effectiveFrameY * densityScale).toInt(),
                    )
                }
            },
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                clippingEnabled = false,
            ),
        ) {
            PopupCard()
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            PopupCard()
        }
    }
}

@Composable
private fun LookupPopupActionBar(
    onClose: () -> Unit,
    showCloseAll: Boolean,
    onCloseAll: (() -> Unit)?,
    historyBackCount: Int,
    historyForwardCount: Int,
    onNavigateBack: () -> Unit,
    onNavigateForward: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LookupPopupActionBarHeight)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onClose,
            colors = ButtonDefaults.textButtonColors(
                contentColor = Color(0xFF32679A)
            ),
            contentPadding = ButtonDefaults.ContentPadding
        ) {
            Text(text = stringResource(R.string.common_close))
        }
        if (historyBackCount > 0) {
            TextButton(
                onClick = onNavigateBack,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF32679A)),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text(text = "←")
            }
        }
        if (historyForwardCount > 0) {
            TextButton(
                onClick = onNavigateForward,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF32679A)),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text(text = "→")
            }
        }
        if (showCloseAll && onCloseAll != null) {
            TextButton(
                onClick = onCloseAll,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color(0xFF32679A)
                ),
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                Text(text = stringResource(R.string.common_close_all))
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LookupPopupWebView(
    html: String,
    results: List<LookupResult>,
    assets: LookupPopupAssets,
    darkMode: Boolean,
    backgroundColor: Color,
    selectionOffsetX: Double,
    selectionOffsetY: Double,
    clearSelectionSignal: Int,
    backSignal: Int = 0,
    forwardSignal: Int = 0,
    warmShell: Boolean = false,
    callbacks: PopupWebViewCallbacks,
    modifier: Modifier = Modifier,
) {
    val callbackHolder = remember { PopupWebViewCallbackHolder(callbacks) }
    callbackHolder.callbacks = callbacks
    val lookupResultsHolder = remember { PopupLookupResultsHolder(results) }
    val contentReadyGate = remember { PopupContentReadyGate() }
    val webViewHolder = remember { mutableStateOf<WebView?>(null) }
    val offsetState = remember {
        PopupWebViewOffsetState(
            selectionOffsetX = selectionOffsetX,
            selectionOffsetY = selectionOffsetY,
        )
    }
    var loadedHtml by remember { mutableStateOf<String?>(null) }
    var appliedClearSelectionSignal by remember { mutableStateOf(clearSelectionSignal) }
    var appliedBackSignal by remember { mutableStateOf(backSignal) }
    var appliedForwardSignal by remember { mutableStateOf(forwardSignal) }
    var shellReady by remember { mutableStateOf(false) }
    var appliedWarmResults by remember { mutableStateOf<List<LookupResult>?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            contentReadyGate.close()
            webViewHolder.value?.evaluateJavascript(
                "window.HoshiAndroidPopupBridge && window.HoshiAndroidPopupBridge.cancelAll(null)",
                null,
            )
            callbackHolder.close()
            webViewHolder.value?.let(::destroyWebViewSafely)
            webViewHolder.value = null
        }
    }
    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
        factory = { context ->
            WebView(context).apply {
                webViewHolder.value = this
                applyHoshiWebViewSecurityDefaults()
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(backgroundColor.toArgb())
                addJavascriptInterface(
                    PopupWebViewBridge(
                        webView = this,
                        callbackHolder = callbackHolder,
                        lookupResultsHolder = lookupResultsHolder,
                        offsetState = offsetState,
                        contentReadyGate = contentReadyGate,
                        onShellReady = { shellReady = true },
                    ),
                    "HoshiPopup",
                )
                webViewClient = PopupMessageWebViewClient(callbackHolder, assets)
            }
        },
        update = { webView ->
            callbackHolder.callbacks = callbacks
            offsetState.selectionOffsetX = selectionOffsetX
            offsetState.selectionOffsetY = selectionOffsetY
            webView.setBackgroundColor(backgroundColor.toArgb())
            if (loadedHtml != html) {
                loadedHtml = html
                shellReady = false
                appliedWarmResults = null
                contentReadyGate.reset()
                if (!warmShell) {
                    lookupResultsHolder.results = results
                }
                webView.loadDataWithBaseURL(
                    "https://hoshi.local/popup/",
                    html,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            if (warmShell && shellReady && appliedWarmResults !== results) {
                appliedWarmResults = results
                lookupResultsHolder.results = results
                contentReadyGate.reset()
                val initialEntries = results.firstOrNull()
                    ?.let(LookupPopupHtml::entryJsonString)
                    ?.let { "[$it]" }
                    ?: "[]"
                webView.evaluateJavascript(
                    "window.hoshiSelection && window.hoshiSelection.clearSelection();" +
                        "window.replacePopupResults && window.replacePopupResults(${results.size}, $initialEntries)",
                    null,
                )
            } else if (!warmShell && shellReady) {
                lookupResultsHolder.results = results
            }
            if (appliedClearSelectionSignal != clearSelectionSignal) {
                appliedClearSelectionSignal = clearSelectionSignal
                webView.evaluateJavascript("window.hoshiSelection.clearSelection()", null)
            }
            if (appliedBackSignal != backSignal) {
                appliedBackSignal = backSignal
                webView.evaluateJavascript("window.navigateBack()", null)
            }
            if (appliedForwardSignal != forwardSignal) {
                appliedForwardSignal = forwardSignal
                webView.evaluateJavascript("window.navigateForward()", null)
            }
        },
    )
}

@Composable
internal fun HoshiImagePreview(
    imageUrl: String,
    onUnavailable: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val previewState = produceState<Pair<Boolean, ByteArray?>>(initialValue = false to null, key1 = imageUrl) {
        value = true to withContext(Dispatchers.IO) {
            loadHoshiPreviewImageBytes(imageUrl)
        }
    }.value
    val imageBytes = previewState.second
    if (previewState.first && imageBytes == null) {
        LaunchedEffect(imageUrl) { onUnavailable() }
    }
    if (imageBytes != null) {
        ZoomableImagePreview(
            imageBytes = imageBytes,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier)
    }
}

private fun loadHoshiPreviewImageBytes(rawUrl: String): ByteArray? {
    val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null
    val dictionary: String
    val path: String
    when {
        uri.scheme == "https" && uri.host == "hoshi.local" && uri.path == "/image" -> {
            dictionary = uri.getQueryParameter("dictionary").orEmpty()
            path = uri.getQueryParameter("path").orEmpty()
        }
        else -> return null
    }
    if (dictionary.isBlank() || path.isBlank()) return null
    return runCatching {
        val data = HoshiDictionaryQuerySession.getMediaFile(dictionary, path)
            ?.takeIf { it.isNotEmpty() }
            ?.takeIf { it.size.toLong() <= DICTIONARY_MEDIA_RESPONSE_MAX_BYTES }
            ?: return null
        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
            "imagePreview bytes hit dictionary=$dictionary path=$path bytes=${data.size}"
        }
        data
    }.getOrNull()
}

internal fun isHoshiPreviewImageCandidate(rawUrl: String): Boolean {
    val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
    val path = when {
        uri.scheme == "https" && uri.host == "hoshi.local" && uri.path == "/image" -> {
            uri.getQueryParameter("path").orEmpty()
        }
        else -> return false
    }
    return when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> true
        else -> false
    }
}
