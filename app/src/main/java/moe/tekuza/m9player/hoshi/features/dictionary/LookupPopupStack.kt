package moe.tekuza.m9player.hoshi.features.dictionary

import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import de.manhhao.hoshi.LookupResult
import moe.tekuza.m9player.hoshi.dictionary.LookupEngine
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionData
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionRect
import moe.tekuza.m9player.AnkiDuplicateCheckResult
import moe.tekuza.m9player.logDebug
import java.util.UUID

internal data class LookupPopupOptions(
    val isVertical: Boolean,
    val isFullWidth: Boolean = false,
    val width: Int = 320,
    val height: Int = 250,
    val swipeToDismiss: Boolean = false,
    val swipeThreshold: Int = 40,
    val topInset: Double = 0.0,
    val bottomInset: Double = 0.0,
    val dictionarySettings: DictionarySettings = DictionarySettings(),
    val darkMode: Boolean = false,
    val eInkMode: Boolean = false,
    val audioSettings: moe.tekuza.m9player.AudiobookSettingsConfig = moe.tekuza.m9player.AudiobookSettingsConfig(),
    val showRangeSelection: Boolean = false,
    val showPlayAudio: Boolean = false,
    val popupActionBar: Boolean = false,
)

internal data class LookupPopupItem(
    val id: String = UUID.randomUUID().toString(),
    val state: LookupPopupState,
    val clearSelectionSignal: Int = 0,
    val lookupStartedAtNanos: Long = SystemClock.elapsedRealtimeNanos(),
)

internal fun createLookupPopupItem(
    selection: ReaderSelectionData,
    options: LookupPopupOptions,
    dictionaryStyles: Map<String, String>? = null,
    lookup: (String, Int, Int) -> List<de.manhhao.hoshi.LookupResult> = LookupEngine::lookup,
): Pair<LookupPopupItem, Int>? {
    val lookupStartedAtNanos = SystemClock.elapsedRealtimeNanos()
    val settings = options.dictionarySettings.normalized()
    val styles = dictionaryStyles ?: currentDictionaryStyles()
    logDebug("HoshiLookupPopup") {
        "createLookupPopupItem selection='${selection.text.take(32)}' selectionLen=${selection.text.length} " +
            "rect=${selection.rect.x},${selection.rect.y} ${selection.rect.width}x${selection.rect.height} " +
            "showRange=${options.showRangeSelection} showAudio=${options.showPlayAudio} popupActionBar=${options.popupActionBar}"
    }
    val results = runCatching {
        lookup(selection.text, settings.maxResults, settings.scanLength)
    }.getOrDefault(emptyList())
    val first = results.firstOrNull()
    if (first == null) {
        logDebug("HoshiLookupPopup") {
            "createLookupPopupItem empty selection='${selection.text.take(32)}' scanLength=${settings.scanLength} maxResults=${settings.maxResults}"
        }
        return null
    }
    logDebug("HoshiLookupPopup") {
        "createLookupPopupItem result selection='${selection.text.take(32)}' firstTerm='${first.matched.take(32)}' results=${results.size} matchedLength=${first.matched.codePointCount(0, first.matched.length)}"
    }
    return LookupPopupItem(
        lookupStartedAtNanos = lookupStartedAtNanos,
        state = options.toPopupState(selection, results, styles, settings),
    ) to first.matched.codePointCount(0, first.matched.length)
}

private fun LookupPopupOptions.toPopupState(
    selection: ReaderSelectionData,
    results: List<LookupResult>,
    dictionaryStyles: Map<String, String>,
    settings: DictionarySettings = dictionarySettings.normalized(),
) = LookupPopupState(
    selection = selection,
    results = results,
    dictionaryStyles = dictionaryStyles,
    dictionarySettings = settings,
    isVertical = isVertical,
    isFullWidth = isFullWidth,
    width = width,
    height = height,
    swipeToDismiss = swipeToDismiss,
    swipeThreshold = swipeThreshold,
    topInset = topInset,
    bottomInset = bottomInset,
    darkMode = darkMode,
    eInkMode = eInkMode,
    audioSettings = audioSettings,
    showRangeSelection = showRangeSelection,
    showPlayAudio = showPlayAudio,
    popupActionBar = popupActionBar,
)

private fun createWarmLookupPopupItem(options: LookupPopupOptions) = LookupPopupItem(
    state = options.toPopupState(
        selection = ReaderSelectionData(
            text = "",
            sentence = "",
            rect = ReaderSelectionRect(0.0, 0.0, 1.0, 1.0),
            normalizedOffset = null,
        ),
        results = emptyList(),
        dictionaryStyles = currentDictionaryStyles(),
    ),
)

internal fun currentDictionaryStyles(): Map<String, String> =
    runCatching {
        LookupEngine.getStyles().associate { it.dictName to it.styles }
    }.getOrDefault(emptyMap())

internal fun closeChildPopups(
    popups: List<LookupPopupItem>,
    parentIndex: Int,
): List<LookupPopupItem> = popups.take(parentIndex + 1)

internal fun dismissPopupAt(
    popups: List<LookupPopupItem>,
    index: Int,
): List<LookupPopupItem> =
    if (index == 0) {
        emptyList()
    } else {
        closeChildPopups(popups, index - 1).mapIndexed { popupIndex, popup ->
            if (popupIndex == index - 1) {
                popup.copy(clearSelectionSignal = popup.clearSelectionSignal + 1)
            } else {
                popup
            }
        }
    }

@Composable
internal fun LookupPopupStackView(
    popups: List<LookupPopupItem>,
    onPopupsChange: (List<LookupPopupItem>) -> Unit,
    lookupChildPopup: (ReaderSelectionData) -> Pair<LookupPopupItem, Int>?,
    onRangeSelection: (() -> Unit)? = null,
    onPlayWordAudio: ((String, String?, String?) -> Unit)? = null,
    onMineEntry: ((String) -> Boolean)? = null,
    onMineEntryAsync: ((String, (Boolean) -> Unit) -> Unit)? = null,
    onDuplicateCheck: ((String) -> AnkiDuplicateCheckResult)? = null,
    onDuplicateCheckAsync: ((String, (AnkiDuplicateCheckResult) -> Unit) -> Unit)? = null,
    onViewDuplicate: ((List<Long>) -> Boolean)? = null,
    onCloseAll: (() -> Unit)? = null,
    onLookupRedirect: ((String) -> List<LookupResult>)? = null,
    modifier: Modifier = Modifier,
    onRootPopupDismissed: () -> Unit = {},
    warmRootShell: Boolean = true,
    warmRootOptions: LookupPopupOptions? = null,
    platformPopupHost: Boolean = false,
    extraBottomInsetDp: Double = 0.0,
) {
    var warmRootPopup by remember(warmRootOptions) {
        mutableStateOf(warmRootOptions?.let(::createWarmLookupPopupItem))
    }
    popups.firstOrNull()?.let { warmRootPopup = it }
    val displayPopups = if (popups.isNotEmpty()) {
        popups
    } else if (warmRootShell) {
        warmRootPopup?.let { root ->
            root.copy(
                state = root.state.copy(results = emptyList()),
                clearSelectionSignal = root.clearSelectionSignal,
            )
        }
            ?.let(::listOf)
            .orEmpty()
    } else {
        emptyList()
    }
    val hasVisiblePopups = popups.isNotEmpty()

    displayPopups.forEachIndexed { index, popup ->
        val isHiddenWarmRoot = warmRootShell && !hasVisiblePopups && index == 0
        key(if (warmRootShell && index == 0) "warm-root-popup" else popup.id) {
            logDebug("HoshiLookupPopup") {
                "stack view index=$index size=${popups.size} displaySize=${displayPopups.size} hiddenWarmRoot=$isHiddenWarmRoot showActionBar=${popup.state.popupActionBar} showCloseAll=${onCloseAll != null && index == popups.lastIndex && popups.size > 1} popupActionBar=${popup.state.popupActionBar}"
            }
            logDebug("AnkiExportDebug") {
                "hoshiStack callbacks index=$index size=${popups.size} hasMineEntry=${onMineEntry != null} hasDuplicateCheck=${onDuplicateCheck != null}"
            }
            LookupPopupView(
                state = popup.state,
                lookupStartedAtNanos = popup.lookupStartedAtNanos,
                clearSelectionSignal = popup.clearSelectionSignal,
                onTapOutside = {},
                onSwipeDismiss = {
                    if (index == 0) onRootPopupDismissed()
                    onPopupsChange(dismissPopupAt(popups, index))
                },
                onTextSelected = { selection ->
                    logDebug("MainHoshiResultPopup") {
                        "stack textSelected index=$index size=${popups.size} text='${selection.text.take(48)}'"
                    }
                    val nextPopups = closeChildPopups(popups, index)
                    lookupChildPopup(selection)?.let { (childPopup, highlightCount) ->
                        logDebug("MainHoshiResultPopup") {
                            "stack child lookup created index=$index childId=${childPopup.id} results=${childPopup.state.results.size}"
                        }
                        onPopupsChange(nextPopups + childPopup)
                        highlightCount
                    }
                },
                onRangeSelection = onRangeSelection,
                onPlayWordAudio = onPlayWordAudio,
                onMineEntry = onMineEntry,
                onMineEntryAsync = onMineEntryAsync,
                onDuplicateCheck = onDuplicateCheck,
                onDuplicateCheckAsync = onDuplicateCheckAsync,
                onViewDuplicate = onViewDuplicate,
                onCloseAll = onCloseAll,
                showActionBar = popup.state.popupActionBar,
                showCloseAll = onCloseAll != null && index == popups.lastIndex && popups.size > 1,
                warmShell = warmRootShell && index == 0,
                contentResetKey = if (warmRootShell && index == 0) popup.id else null,
                isPopupActive = !isHiddenWarmRoot,
                isContentVisible = !isHiddenWarmRoot,
                onLookupRedirect = onLookupRedirect ?: { query ->
                    LookupEngine.lookup(
                        query,
                        popup.state.dictionarySettings.maxResults,
                        popup.state.dictionarySettings.scanLength,
                    )
                },
                onLookupRedirected = { redirectSelection ->
                    logDebug("MainHoshiResultPopup") {
                        "stack redirect index=$index size=${popups.size} text='${redirectSelection.text.take(48)}'"
                    }
                    val nextPopups = closeChildPopups(popups, index)
                    lookupChildPopup(redirectSelection)?.let { (childPopup, _) ->
                        logDebug("MainHoshiResultPopup") {
                            "stack redirect child created index=$index childId=${childPopup.id} results=${childPopup.state.results.size}"
                        }
                        logDebug("HoshiLookupPopup") {
                            "stack push redirect parent=$index nextSize=${nextPopups.size + 1} query='${redirectSelection.text.take(32)}' rect=${redirectSelection.rect.x},${redirectSelection.rect.y} ${redirectSelection.rect.width}x${redirectSelection.rect.height}"
                        }
                        onPopupsChange(nextPopups + childPopup)
                    }
                },
                isLookupPopupActive = { popups.any { it.id == popup.id } },
                platformPopupHost = platformPopupHost,
                extraBottomInsetDp = extraBottomInsetDp,
                modifier = modifier.fillMaxSize(),
            )
        }
    }
}
