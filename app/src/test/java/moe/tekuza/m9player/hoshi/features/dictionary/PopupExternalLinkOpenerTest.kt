package moe.tekuza.m9player.hoshi.features.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PopupExternalLinkOpenerTest {
    @Test
    fun acceptsBrowserAndPlatformLinksButRejectsUnsafeSchemes() {
        assertEquals("https://example.com/path", externalBrowserUrl(" https://example.com/path "))
        assertEquals("mailto:test@example.com", externalBrowserUrl("mailto:test@example.com"))
        assertEquals("tel:+123", externalBrowserUrl("tel:+123"))
        assertNull(externalBrowserUrl("javascript:alert(1)"))
        assertNull(externalBrowserUrl("intent://example.com"))
        assertNull(externalBrowserUrl("https:///missing-host"))
    }
}
