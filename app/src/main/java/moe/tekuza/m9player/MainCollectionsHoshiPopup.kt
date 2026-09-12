package moe.tekuza.m9player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import de.manhhao.hoshi.LookupResult
import moe.tekuza.m9player.hoshi.features.dictionary.PopupWebViewCallbacks
import moe.tekuza.m9player.hoshi.features.dictionary.openPopupExternalLink
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionData

@Composable
internal fun MainCollectionsHoshiPopup(
    previewSentence: String,
    selectedRange: IntRange?,
    html: String,
    results: List<LookupResult>,
    clearSelectionSignal: Int,
    onClose: () -> Unit,
    onPreviewLookup: (offset: Int, layout: TextLayoutResult?, coordinates: LayoutCoordinates?) -> Unit,
    onResultTextSelected: (ReaderSelectionData) -> Int?,
    onLookupRedirect: (String) -> List<LookupResult>,
    onResultRedirected: (ReaderSelectionData, List<LookupResult>) -> Unit,
    onTapOutside: () -> Unit,
    onMineEntry: (String) -> Boolean,
    onMineEntryAsync: ((String, (Boolean) -> Unit) -> Unit)? = null,
    onDuplicateCheck: (String) -> AnkiDuplicateCheckResult,
    onDuplicateCheckAsync: ((String, (AnkiDuplicateCheckResult) -> Unit) -> Unit)? = null,
    onViewDuplicate: (List<Long>) -> Boolean,
    onPlayWordAudio: (String?, String?) -> Unit,
) {
    val context = LocalContext.current
    val rootDensity = LocalDensity.current
    val hasPopupResults = html.isNotBlank()
    val popupFooterHeight = 44.dp
    val topMargin = 72.dp
    val bottomMargin = 12.dp
    var maxPopupHeight by remember {
        mutableStateOf(520.dp + popupFooterHeight)
    }
    val popupPositionProvider = remember(rootDensity.density) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val x = ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0)
                val y = with(rootDensity) { topMargin.roundToPx() }
                val bottomMarginPx = with(rootDensity) { bottomMargin.roundToPx() }
                val minPopupHeightPx = with(rootDensity) { 260.dp.roundToPx() }
                val availableHeightPx = (windowSize.height - y - bottomMarginPx).coerceAtLeast(minPopupHeightPx)
                maxPopupHeight = with(rootDensity) { availableHeightPx.toDp() }
                logDebug("MainHoshiResultPopup") {
                    "collections host posPx=$x,$y popupSizePx=${popupContentSize.width}x${popupContentSize.height} " +
                    "windowPx=${windowSize.width}x${windowSize.height}"
                }
                return IntOffset(x, y)
            }
        }
    }
    var previewSentenceCoordinates by remember(previewSentence, selectedRange) {
        mutableStateOf<LayoutCoordinates?>(null)
    }
    var previewSentenceLayout by remember(previewSentence, selectedRange) {
        mutableStateOf<TextLayoutResult?>(null)
    }
    var resultCardOrigin by remember(html) {
        mutableStateOf(Offset.Zero)
    }
    var hostWindowOffsetDp by remember {
        mutableStateOf(Offset.Zero)
    }
    val livePopupPositionProvider = remember(rootDensity.density) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val position = popupPositionProvider.calculatePosition(
                    anchorBounds = anchorBounds,
                    windowSize = windowSize,
                    layoutDirection = layoutDirection,
                    popupContentSize = popupContentSize,
                )
                logDebug("MainHoshiResultPopup") {
                    "collections liveHostOffsetDp=${position.x / rootDensity.density},${position.y / rootDensity.density}"
                }
                hostWindowOffsetDp = Offset(
                    x = position.x / rootDensity.density,
                    y = position.y / rootDensity.density,
                )
                return position
            }
        }
    }

    Popup(
        popupPositionProvider = livePopupPositionProvider,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .then(
                    if (hasPopupResults) {
                        Modifier
                            .heightIn(min = 260.dp, max = maxPopupHeight)
                            .height(maxPopupHeight)
                    } else {
                        Modifier.wrapContentHeight()
                    }
                ),
            shape = RoundedCornerShape(18.dp),
            color = hoshiPanelBackgroundColor(),
            border = BorderStroke(1.dp, Color(0x477A7F87)),
            shadowElevation = 10.dp,
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = if (hasPopupResults) {
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                }
            ) {
                if (previewSentence.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(),
                        shape = RoundedCornerShape(14.dp),
                        color = Color.Transparent,
                    ) {
                        MainLookupClickableSentence(
                            text = buildMainHighlightedText(previewSentence, selectedRange),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier
                                .onGloballyPositioned {
                                    previewSentenceCoordinates = it
                                    val bounds = it.boundsInWindow()
                                    val densityScale = rootDensity.density.coerceAtLeast(0.1f)
                                    logDebug("MainHoshiResultPopup") {
                                        "collections preview boundsDp=${bounds.left / densityScale},${bounds.top / densityScale} " +
                                        "${bounds.width / densityScale}x${bounds.height / densityScale} selectedRange=$selectedRange"
                                    }
                                }
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            onTextLayout = { previewSentenceLayout = it },
                            onTextTap = { offset ->
                                onPreviewLookup(offset, previewSentenceLayout, previewSentenceCoordinates)
                            }
                        )
                    }
                }

                if (hasPopupResults) {
                    if (previewSentence.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .onGloballyPositioned { coordinates ->
                                val bounds = coordinates.boundsInWindow()
                                val densityScale = rootDensity.density.coerceAtLeast(0.1f)
                                resultCardOrigin = Offset(
                                    x = bounds.left / densityScale,
                                    y = bounds.top / densityScale
                                )
                            },
                        shape = RoundedCornerShape(14.dp),
                        color = hoshiCardBackgroundColor(),
                    ) {
                        MainHoshiResultWebView(
                            html = html,
                            results = results,
                            clearSelectionSignal = clearSelectionSignal,
                            selectionOffsetX = (resultCardOrigin.x + hostWindowOffsetDp.x).toDouble(),
                            selectionOffsetY = (resultCardOrigin.y + hostWindowOffsetDp.y).toDouble(),
                            callbacks = PopupWebViewCallbacks(
                                onTapOutside = onTapOutside,
                                onSwipeDismiss = onTapOutside,
                                onOpenLink = { context.openPopupExternalLink(it) },
                                onMineEntry = onMineEntry,
                                onMineEntryAsync = onMineEntryAsync,
                                onDuplicateCheck = onDuplicateCheck,
                                onDuplicateCheckAsync = onDuplicateCheckAsync,
                                onViewDuplicate = onViewDuplicate,
                                onTextSelected = onResultTextSelected,
                                onLookupRedirect = onLookupRedirect,
                                onLookupRedirected = onResultRedirected,
                                onPlayWordAudio = { _url, term, reading ->
                                    onPlayWordAudio(term, reading)
                                },
                            ),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(popupFooterHeight)
                        .padding(horizontal = 10.dp),
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
                }
            }
        }
    }
}
