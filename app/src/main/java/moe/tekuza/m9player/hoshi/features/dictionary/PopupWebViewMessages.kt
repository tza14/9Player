package moe.tekuza.m9player.hoshi.features.dictionary

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import de.manhhao.hoshi.LookupResult
import moe.tekuza.m9player.hoshi.dictionary.HoshiDictionaryQuerySession
import moe.tekuza.m9player.hoshi.dictionary.LookupEngine
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionData
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionRect
import moe.tekuza.m9player.AnkiDuplicateCheckResult
import moe.tekuza.m9player.logDebug
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val HOSHI_LOOKUP_POPUP_LOG_TAG = "HoshiLookupPopup"

internal class PopupWebViewCallbacks(
    val onTapOutside: () -> Unit = {},
    val onSwipeDismiss: () -> Unit = {},
    val onOpenLink: (String) -> Unit = {},
    val onImageTap: (String) -> Unit = {},
    val onMineEntry: (String) -> Boolean = { false },
    val onMineEntryAsync: ((String, (Boolean) -> Unit) -> Unit)? = null,
    val onDuplicateCheck: (String) -> AnkiDuplicateCheckResult = { AnkiDuplicateCheckResult() },
    val onDuplicateCheckAsync: ((String, (AnkiDuplicateCheckResult) -> Unit) -> Unit)? = null,
    val onViewDuplicate: (List<Long>) -> Boolean = { false },
    val onRangeSelection: () -> Unit = {},
    val onPlayWordAudio: (String, String?, String?) -> Unit = { _, _, _ -> },
    val onCloseAll: () -> Unit = {},
    val onTextSelected: (ReaderSelectionData) -> Int? = { null },
    val onLookupRedirect: (String) -> List<LookupResult> = { query -> LookupEngine.lookup(query) },
    val onLookupRedirected: (ReaderSelectionData, List<LookupResult>) -> Unit = { _, _ -> },
    val isLookupPopupActive: () -> Boolean = { true },
    val onHistoryChanged: (Int, Int) -> Unit = { _, _ -> },
    val onContentReady: () -> Unit = {},
)

internal fun PopupWebViewCallbacks.withAdditionalImageTap(
    handler: (String) -> Unit,
): PopupWebViewCallbacks = PopupWebViewCallbacks(
    onTapOutside = onTapOutside,
    onSwipeDismiss = onSwipeDismiss,
    onOpenLink = onOpenLink,
    onImageTap = { src ->
        onImageTap(src)
        if (isHoshiPreviewImageCandidate(src)) {
            handler(src)
        }
    },
    onMineEntry = onMineEntry,
    onMineEntryAsync = onMineEntryAsync,
    onDuplicateCheck = onDuplicateCheck,
    onDuplicateCheckAsync = onDuplicateCheckAsync,
    onViewDuplicate = onViewDuplicate,
    onRangeSelection = onRangeSelection,
    onPlayWordAudio = onPlayWordAudio,
    onCloseAll = onCloseAll,
    onTextSelected = onTextSelected,
    onLookupRedirect = onLookupRedirect,
    onLookupRedirected = onLookupRedirected,
    isLookupPopupActive = isLookupPopupActive,
    onHistoryChanged = onHistoryChanged,
    onContentReady = onContentReady,
)

internal class PopupWebViewCallbackHolder(
    var callbacks: PopupWebViewCallbacks,
) {
    private val closed = AtomicBoolean(false)
    private val lookupGeneration = AtomicLong(0L)

    fun close() {
        closed.set(true)
        lookupGeneration.incrementAndGet()
    }

    fun isClosed(): Boolean = closed.get()

    fun beginLookup(): Long = lookupGeneration.incrementAndGet()

    fun isLookupActive(generation: Long): Boolean =
        !closed.get() && lookupGeneration.get() == generation && callbacks.isLookupPopupActive()
}

internal class PopupLookupResultsHolder(
    var results: List<LookupResult>,
)

internal data class PopupWebViewOffsetState(
    var selectionOffsetX: Double = 0.0,
    var selectionOffsetY: Double = 0.0,
    var windowOffsetAdjustmentX: Double = 0.0,
    var windowOffsetAdjustmentY: Double = 0.0,
)

internal class PopupContentReadyGate {
    private var generation = 0L
    private var requestId = 0L
    private var closed = false

    internal val isClosed: Boolean
        get() = closed

    fun close() {
        closed = true
        generation += 1
        requestId += 1
    }

    fun reset() {
        if (closed) return
        generation += 1
        requestId += 1
    }

    fun awaitReadyToDraw(webView: WebView, onReady: () -> Unit) {
        if (closed) return
        val currentGeneration = generation
        val currentRequestId = requestId + 1
        requestId = currentRequestId
        runCatching {
            webView.postVisualStateCallback(
                currentRequestId,
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (closed || generation != currentGeneration || this@PopupContentReadyGate.requestId != currentRequestId) {
                            return
                        }
                        onReady()
                    }
                },
            )
        }
    }
}

internal class PopupMessageWebViewClient(
    private val callbackHolder: PopupWebViewCallbackHolder,
    private val assets: LookupPopupAssets? = null,
    private val imageRequestHandler: DictionaryImageRequestHandler = DictionaryImageRequestHandler(),
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        handlePopupUrl(request.url)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        handlePopupUrl(Uri.parse(url))

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        handleAssetRequest(request.url)
            ?: imageRequestHandler.handleImageRequest(request.url)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
        Uri.parse(url).let { uri ->
            handleAssetRequest(uri)
                ?: imageRequestHandler.handleImageRequest(uri)
        }

    private fun handleAssetRequest(uri: Uri): WebResourceResponse? {
        val assets = assets ?: return null
        if (uri.host != "hoshi.local" || !uri.path.orEmpty().startsWith("/popup/")) return null
        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) { "asset request uri=$uri" }
        val content = when (uri.lastPathSegment) {
            "popup.css" -> assets.popupCss
            "selection.js" -> assets.selectionJs
            "popup.js" -> assets.popupJs
            else -> return null
        }
        val mimeType = when (uri.lastPathSegment) {
            "popup.css" -> "text/css"
            else -> "application/javascript"
        }
        return WebResourceResponse(
            mimeType,
            "UTF-8",
            ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun handlePopupUrl(uri: Uri): Boolean {
        if (uri.scheme != "hoshi-popup") {
            logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) { "blocked popup navigation uri=$uri" }
            return true
        }
        when (uri.host) {
            "tapOutside" -> callbackHolder.callbacks.onTapOutside()
            "swipeDismiss" -> callbackHolder.callbacks.onSwipeDismiss()
        }
        return true
    }
}

internal class DictionaryImageRequestHandler(
    private val loadMedia: (dictionary: String, path: String) -> ByteArray? = { dictionary, path ->
        HoshiDictionaryQuerySession.getMediaFile(dictionary, path)
    },
) {
    fun handleImageRequest(uri: Uri): WebResourceResponse? {
        val isDictionaryImageEndpoint = uri.scheme == "https" &&
            uri.host == "hoshi.local" &&
            uri.path == "/image"
        if (!isDictionaryImageEndpoint) return null
        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) { "image request uri=$uri" }
        val dictionary = uri.getQueryParameter("dictionary").orEmpty()
        val mediaPath = uri.getQueryParameter("path").orEmpty()
        if (dictionary.isBlank() || mediaPath.isBlank()) return null
        val data = loadMedia(dictionary, mediaPath)?.takeIf { it.isNotEmpty() }
        if (data == null) {
            logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) { "image miss dictionary=$dictionary path=$mediaPath" }
            return null
        }
        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
            "image hit dictionary=$dictionary path=$mediaPath bytes=${data.size} mime=${dictionaryImageMimeType(mediaPath)}"
        }

        return WebResourceResponse(
            dictionaryImageMimeType(mediaPath),
            null,
            ByteArrayInputStream(data),
        ).apply {
            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
        }
    }
}

internal fun dictionaryImageMimeType(path: String): String =
    when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "heic" -> "image/heic"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

internal class PopupWebViewBridge(
    private val webView: WebView,
    private val callbackHolder: PopupWebViewCallbackHolder,
    private val lookupResultsHolder: PopupLookupResultsHolder = PopupLookupResultsHolder(emptyList()),
    private val offsetState: PopupWebViewOffsetState = PopupWebViewOffsetState(),
    private val contentReadyGate: PopupContentReadyGate? = null,
    private val onShellReady: () -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class PopupSelectionOffset(
        val x: Double,
        val y: Double,
    )

    private fun currentSelectionOffset(): PopupSelectionOffset {
        val selectionOffsetX = offsetState.selectionOffsetX
        val selectionOffsetY = offsetState.selectionOffsetY
        val windowOffsetAdjustmentX = offsetState.windowOffsetAdjustmentX
        val windowOffsetAdjustmentY = offsetState.windowOffsetAdjustmentY
        if (
            selectionOffsetX != 0.0 ||
            selectionOffsetY != 0.0 ||
            windowOffsetAdjustmentX != 0.0 ||
            windowOffsetAdjustmentY != 0.0
        ) {
            return PopupSelectionOffset(
                x = selectionOffsetX + windowOffsetAdjustmentX,
                y = selectionOffsetY + windowOffsetAdjustmentY,
            )
        }
        val location = IntArray(2)
        return runCatching {
            webView.getLocationOnScreen(location)
            val density = webView.resources.displayMetrics.density.toDouble().coerceAtLeast(0.1)
            PopupSelectionOffset(
                x = location[0] / density,
                y = location[1] / density,
            )
        }.getOrElse {
            PopupSelectionOffset(
                x = selectionOffsetX,
                y = selectionOffsetY,
            )
        }
    }

    @JavascriptInterface
    fun getEntry(index: Int): String? {
        if (callbackHolder.isClosed()) return null
        return lookupResultsHolder.results.getOrNull(index)?.let { LookupPopupHtml.entryJsonString(it) }
    }

    @JavascriptInterface
    fun shellReady() {
        if (callbackHolder.isClosed()) return
        mainHandler.post {
            if (contentReadyGate?.isClosed == true) return@post
            onShellReady()
        }
    }

    @JavascriptInterface
    fun lookupRedirect(query: String): Int {
        if (callbackHolder.isClosed()) return 0
        val lookupGeneration = callbackHolder.beginLookup()
        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) { "lookupRedirect received query='${query.take(48)}'" }
        val results = callbackHolder.callbacks.onLookupRedirect(query)
        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
            "lookupRedirect query='${query.take(32)}' resultCount=${results.size}"
        }
        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) { "lookupRedirect completed query='${query.take(48)}' results=${results.size}" }
        if (results.isNotEmpty()) {
            val offset = currentSelectionOffset()
            val selection = ReaderSelectionData(
                text = query,
                sentence = query,
                rect = ReaderSelectionRect(offset.x, offset.y, 1.0, 1.0),
                normalizedOffset = 0,
                sentenceOffset = 0,
            )
            mainHandler.post {
                if (!callbackHolder.isLookupActive(lookupGeneration)) return@post
                callbackHolder.callbacks.onLookupRedirected(selection, results)
            }
        }
        return 0
    }

    @JavascriptInterface
    fun lookupRedirectAt(message: String): Int {
        if (callbackHolder.isClosed()) return 0
        val payload = runCatching { JSONObject(message) }.getOrNull() ?: return 0
        val query = payload.optString("query").takeIf { it.isNotBlank() } ?: return 0
        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) { "lookupRedirectAt received query='${query.take(48)}'" }
        val offset = currentSelectionOffset()
        val selection = payload.toSelectionData(offset.x, offset.y)?.copy(
            text = query,
            sentence = payload.optString("sentence").ifBlank { query },
            normalizedOffset = 0,
            sentenceOffset = 0,
        ) ?: return 0
        val lookupGeneration = callbackHolder.beginLookup()
        val results = callbackHolder.callbacks.onLookupRedirect(query)
        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
            "lookupRedirectAt query='${query.take(32)}' resultCount=${results.size} rect=${selection.rect.x},${selection.rect.y} ${selection.rect.width}x${selection.rect.height}"
        }
        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) { "lookupRedirectAt completed query='${query.take(48)}' results=${results.size}" }
        if (results.isNotEmpty()) {
            mainHandler.post {
                if (!callbackHolder.isLookupActive(lookupGeneration)) return@post
                callbackHolder.callbacks.onLookupRedirected(selection, results)
            }
        }
        return 0
    }

    @JavascriptInterface
    fun postMessage(message: String) {
        if (callbackHolder.isClosed()) return
        val payload = runCatching { JSONObject(message) }.getOrNull() ?: return
        val callbacks = callbackHolder.callbacks
        when (payload.optString("name")) {
            "openLink" -> payload.optString("body").takeIf { it.isNotBlank() }?.let(callbacks.onOpenLink)
            "debug" -> payload.optJSONObject("body")?.let { body ->
                logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
                    "popupDebug name=${body.optString("name").takeIf { it.isNotBlank() } ?: "unknown"} body=$body"
                }
            }
            "imageTap" -> payload.optJSONObject("body")?.optString("src")?.takeIf { it.isNotBlank() }?.let { src ->
                logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) { "imageTap src=$src" }
                callbacks.onImageTap(src)
            }
            "rangeSelection" -> mainHandler.post(callbacks.onRangeSelection)
            "playWordAudio" -> payload.optJSONObject("body")?.let { body ->
                val term = body.optString("term").takeIf { it.isNotBlank() } ?: return@let
                val reading = body.optString("reading").takeIf { it.isNotBlank() }
                val url = body.optString("url").takeIf { it.isNotBlank() } ?: ""
                mainHandler.post { callbacks.onPlayWordAudio(url, term, reading) }
            }
            "tapOutside" -> mainHandler.post {
                if (contentReadyGate?.isClosed == true) return@post
                callbacks.onTapOutside()
                webView.evaluateJavascript("window.hoshiSelection.clearSelection()", null)
            }
            "swipeDismiss" -> mainHandler.post(callbacks.onSwipeDismiss)
            "contentReady" -> mainHandler.post {
                logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) { "contentReady received" }
                val gate = contentReadyGate
                if (gate != null) {
                    gate.awaitReadyToDraw(webView, callbacks.onContentReady)
                } else {
                    callbacks.onContentReady()
                }
            }
            "contentReadyToDraw" -> mainHandler.post {
                logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) { "contentReadyToDraw received" }
                if (contentReadyGate?.isClosed != true) callbacks.onContentReady()
            }
            "textSelected" -> payload.optJSONObject("body")?.let { body ->
                val offset = currentSelectionOffset()
                body.toSelectionData(offset.x, offset.y)?.let { selection ->
                    val rect = body.optJSONObject("rect")
                    val rawX = rect?.optDouble("x")
                    val rawY = rect?.optDouble("y")
                    val rawWidth = rect?.optDouble("width")
                    val rawHeight = rect?.optDouble("height")
                    logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
                        "textSelected text='${selection.text.take(32)}' " +
                            "webViewOffset(windowDp)=${offset.x},${offset.y} " +
                            "selectedRect(screenDp)=${selection.rect.x},${selection.rect.y} ${selection.rect.width}x${selection.rect.height} " +
                            "selectedRect(rawDp)=${rawX},${rawY} ${rawWidth}x${rawHeight} " +
                            "raw=${rect?.toString()}"
                    }
                    mainHandler.post {
                        if (contentReadyGate?.isClosed == true) return@post
                        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
                            "textSelected dispatch text='${selection.text.take(48)}' sentenceLen=${selection.sentence.length} " +
                            "sentenceOffset=${selection.sentenceOffset}"
                        }
                        val highlightCount = callbacks.onTextSelected(selection) ?: return@post
                        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) { "textSelected handled highlightCount=$highlightCount" }
                        webView.evaluateJavascript("window.hoshiSelection.highlightSelection($highlightCount)", null)
                    }
                }
            }
            "historyChanged" -> payload.optJSONObject("body")?.let { body ->
                val backCount = body.optInt("backCount", 0).coerceAtLeast(0)
                val forwardCount = body.optInt("forwardCount", 0).coerceAtLeast(0)
                mainHandler.post {
                    if (!callbackHolder.isClosed()) {
                        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) { "historyChanged back=$backCount forward=$forwardCount" }
                        callbacks.onHistoryChanged(backCount, forwardCount)
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun mineEntry(content: String): Boolean {
        if (callbackHolder.isClosed()) return false
        val callbacks = callbackHolder.callbacks
        return runCatching {
            logDebug("AnkiExportDebug") {
                "bridge mineEntry dispatch contentSize=${content.length}"
            }
            val accepted = callbacks.onMineEntry(content)
            logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
                "mineEntry accepted=$accepted contentSize=${content.length}"
            }
            accepted
        }.getOrElse {
            Log.w("HoshiLookupPopup", "mineEntry failed", it)
            false
        }
    }

    @JavascriptInterface
    fun mineEntryAsync(requestId: String, content: String) {
        if (requestId.isBlank() || callbackHolder.isClosed()) return
        val callbacks = callbackHolder.callbacks
        val asyncHandler = callbacks.onMineEntryAsync
        if (asyncHandler == null) {
            postAsyncBridgeResult(requestId, mineEntry(content))
            return
        }
        runCatching {
            logDebug("AnkiExportDebug") {
                "bridge mineEntryAsync dispatch requestId=$requestId contentSize=${content.length}"
            }
            asyncHandler(content) { accepted ->
                postAsyncBridgeResult(requestId, accepted)
            }
        }.onFailure {
            Log.w("HoshiLookupPopup", "mineEntryAsync failed", it)
            postAsyncBridgeResult(requestId, false)
        }
    }

    @JavascriptInterface
    fun duplicateCheck(expression: String): String {
        if (callbackHolder.isClosed()) return """{"duplicate":false,"noteIds":[]}"""
        val callbacks = callbackHolder.callbacks
        return runCatching {
            val result = callbacks.onDuplicateCheck(expression)
            logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
                "duplicateCheck expression='${expression.take(32)}' duplicated=${result.duplicate} noteIds=${result.noteIds.size} allowAdd=${result.allowAdd}"
            }
            JSONObject()
                .put("duplicate", result.duplicate)
                .put("allowAdd", result.allowAdd)
                .put("preventAdd", result.preventAdd)
                .put("noteIds", JSONArray().apply { result.noteIds.forEach { put(it) } })
                .toString()
        }.getOrElse {
            Log.w("HoshiLookupPopup", "duplicateCheck failed", it)
            """{"duplicate":false,"noteIds":[]}"""
        }
    }

    @JavascriptInterface
    fun duplicateCheckAsync(requestId: String, expression: String) {
        if (requestId.isBlank() || callbackHolder.isClosed()) return
        val callbacks = callbackHolder.callbacks
        val asyncHandler = callbacks.onDuplicateCheckAsync
        if (asyncHandler == null) {
            postAsyncBridgeResult(requestId, JSONObject(duplicateCheck(expression)))
            return
        }
        runCatching {
            asyncHandler(expression) { result ->
                logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
                    "duplicateCheckAsync expression='${expression.take(32)}' duplicated=${result.duplicate} noteIds=${result.noteIds.size} allowAdd=${result.allowAdd}"
                }
                postAsyncBridgeResult(requestId, result.toPopupJson())
            }
        }.onFailure {
            Log.w("HoshiLookupPopup", "duplicateCheckAsync failed", it)
            postAsyncBridgeResult(requestId, AnkiDuplicateCheckResult().toPopupJson())
        }
    }

    @JavascriptInterface
    fun viewDuplicate(noteIdsJson: String): Boolean {
        if (callbackHolder.isClosed()) return false
        val noteIds = runCatching {
            val array = JSONArray(noteIdsJson)
            List(array.length()) { index -> array.optLong(index, 0L) }
                .filter { it > 0L }
                .distinct()
        }.getOrDefault(emptyList())
        if (noteIds.isEmpty()) return false
        return runCatching {
            callbackHolder.callbacks.onViewDuplicate(noteIds)
        }.getOrElse {
            Log.w("HoshiLookupPopup", "viewDuplicate failed", it)
            false
        }
    }

    private fun postAsyncBridgeResult(requestId: String, value: Any) {
        val payload = JSONObject()
            .put("requestId", requestId)
            .put("ok", true)
            .put("value", value)
            .toString()
        mainHandler.post {
            if (callbackHolder.isClosed()) return@post
            webView.evaluateJavascript(
                "window.HoshiAndroidPopupBridge && window.HoshiAndroidPopupBridge.resolve(${JSONObject.quote(payload)})",
                null,
            )
        }
    }
}

private fun AnkiDuplicateCheckResult.toPopupJson(): JSONObject =
    JSONObject()
        .put("duplicate", duplicate)
        .put("allowAdd", allowAdd)
        .put("preventAdd", preventAdd)
        .put("noteIds", JSONArray().apply { noteIds.forEach { put(it) } })

private fun JSONObject.toSelectionData(
    offsetX: Double,
    offsetY: Double,
): ReaderSelectionData? {
    val rect = optJSONObject("rect") ?: return null
    return ReaderSelectionData(
        text = optString("text"),
        sentence = optString("sentence"),
        rect = ReaderSelectionRect(
            x = offsetX + rect.optDouble("x"),
            y = offsetY + rect.optDouble("y"),
            width = rect.optDouble("width"),
            height = rect.optDouble("height"),
        ),
        normalizedOffset = opt("normalizedOffset")?.let { if (it == JSONObject.NULL) null else (it as? Number)?.toInt() },
        sentenceOffset = opt("sentenceOffset")?.let { if (it == JSONObject.NULL) null else (it as? Number)?.toInt() },
    )
}
