package moe.tekuza.m9player.hoshi.features.dictionary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 弹窗 = Kotlin 注入的 HTML/JS × 原生 bridge × assets 里的 popup.js/popup.css。
 *
 * 这里**只**断言跨语言契约（`window.*` 注入名、`webkit.messageHandlers` / handler 名、
 * `uri.scheme` 约定）——一侧是 Kotlin 字符串、另一侧是 JS，编译器管不到，改名就会静默失灵。
 * 纯视觉项（CSS 像素值、颜色常量、SVG path、布局参数）与同语言标识符不进这个文件：
 * 它们改样式就红、真回归不红，属于口味而不是契约。
 */
class LookupPopupHtmlShimTest {
    @Test
    fun lookupPopupHtmlExposesDictionaryCollapseAndCustomCssSettings() {
        val htmlSource = File("src/main/java/moe/tekuza/m9player/hoshi/features/dictionary/LookupPopupHtml.kt").readText()
        val bridgeSource = File("src/main/java/moe/tekuza/m9player/hoshi/features/dictionary/PopupWebViewMessages.kt").readText()
        val popupViewSource = File("src/main/java/moe/tekuza/m9player/hoshi/features/dictionary/LookupPopupView.kt").readText()
        val popupScript = File("src/main/assets/hoshi-popup/popup.js").readText()

        assertTrue(htmlSource.contains("""window.collapsedDictionaries = ${'$'}collapsedDictionaries;"""))
        assertTrue(htmlSource.contains("""window.customCSS = ${'$'}{JSONObject.quote(normalizedSettings.customCSS)};"""))
        assertTrue(htmlSource.contains("""window.dictionaryMediaRequestEndpoint = "https://hoshi.local/image";"""))
        assertTrue(bridgeSource.contains("""uri.scheme == "https"""))
        assertFalse(bridgeSource.contains("""uri.scheme == "image"""))
        assertFalse(popupViewSource.contains("""uri.scheme == "image"""))
        assertFalse(popupScript.contains("image://"))
        assertFalse(popupScript.contains("showImagePreview"))
    }

    @Test
    fun androidWebKitShimExposesHandlersPopupJsCallsDuringEntryRender() {
        val htmlSource = File("src/main/java/moe/tekuza/m9player/hoshi/features/dictionary/LookupPopupHtml.kt").readText()

        assertTrue(htmlSource.contains("duplicateCheck: { postMessage: async function(expression)"))
        assertTrue(htmlSource.contains("viewDuplicate: { postMessage: function(noteIds)"))
        assertTrue(htmlSource.contains("mineEntry: { postMessage: async function(content)"))
        assertTrue(htmlSource.contains("getEntry: { postMessage: async function(index)"))
        assertTrue(htmlSource.contains("playWordAudio: { postMessage: function(content)"))
        assertTrue(htmlSource.contains("window.HoshiAndroidPopupBridge"))
        assertTrue(htmlSource.contains("makeRequestId(name)"))
        assertTrue(htmlSource.contains("window.HoshiPopup.mineEntryAsync(requestId, body)"))
        assertTrue(htmlSource.contains("window.HoshiPopup.duplicateCheckAsync(requestId, body)"))
    }

    @Test
    fun popupDuplicateSearchButtonIsWiredToTheBridge() {
        val popupJsSource = File("src/main/assets/hoshi-popup/popup.js").readText()
        val bridgeSource = File("src/main/java/moe/tekuza/m9player/hoshi/features/dictionary/PopupWebViewMessages.kt").readText()

        assertTrue(popupJsSource.contains("createDuplicateSearchButton("))
        assertTrue(popupJsSource.contains("className: 'mine-button-stack'"))
        assertTrue(popupJsSource.contains("mineButtonStack.appendChild(mineButton)"))
        assertTrue(popupJsSource.contains("mineButtonStack.appendChild(duplicateSearchButton)"))
        assertTrue(popupJsSource.contains("function stopDuplicateSearchPointerEvent(event)"))
        assertTrue(popupJsSource.contains("onpointerdown: stopDuplicateSearchPointerEvent"))
        assertTrue(popupJsSource.contains("event.stopPropagation();"))
        // 迁移收尾：旧的按钮挂载点不得回来（否则会出现两颗按钮）
        assertTrue(!popupJsSource.contains("buttonsContainer.appendChild(duplicateSearchButton)"))
        assertTrue(!popupJsSource.contains("entryDiv.appendChild(header.duplicateSearchButton)"))
        // JS 侧调用名 ↔ Kotlin 侧 handler 名：跨语言契约
        assertTrue(popupJsSource.contains("webkit.messageHandlers.viewDuplicate.postMessage(noteIds)"))
        assertTrue(popupJsSource.contains("normalizeDuplicateCheckResult("))
        assertTrue(bridgeSource.contains("fun duplicateCheck(expression: String): String"))
        assertTrue(bridgeSource.contains("fun duplicateCheckAsync(requestId: String, expression: String)"))
        assertTrue(bridgeSource.contains("fun mineEntryAsync(requestId: String, content: String)"))
        assertTrue(bridgeSource.contains("postAsyncBridgeResult(requestId"))
        assertTrue(bridgeSource.contains("fun viewDuplicate(noteIdsJson: String): Boolean"))
    }

    @Test
    fun popupScopesDictionaryCssBeforeInjectingIt() {
        val popupJsSource = File("src/main/assets/hoshi-popup/popup.js").readText()

        assertTrue(popupJsSource.contains("function scopeCssRules(css, scopeSelector)"))
        assertTrue(popupJsSource.contains("const scopedDictStyle = constructDictCss(dictStyle, dictName);"))
        assertTrue(popupJsSource.contains("textContent: [scopedDictStyle, defaultDictStyle].filter(Boolean).join('\\n')"))
        // 迁移收尾：词典 CSS 未加 scope 直接注入的写法不得回来
        assertTrue(!popupJsSource.contains("""${'$'}{dictStyle}
                color: var(--text-color)"""))
    }

    @Test
    fun mainLookupPreviewSentenceIsNoLongerCappedToFixedHeight() {
        val mainActivitySource = File("src/main/java/moe/tekuza/m9player/MainActivity.kt").readText()
        val collectionsPopupSource = File("src/main/java/moe/tekuza/m9player/MainCollectionsHoshiPopup.kt").readText()

        assertTrue(!mainActivitySource.contains("""heightIn(min = 96.dp, max = 180.dp)"""))
        assertTrue(!collectionsPopupSource.contains("""heightIn(min = 96.dp, max = 180.dp)"""))
        assertTrue(collectionsPopupSource.contains("""wrapContentHeight()"""))
    }
}
