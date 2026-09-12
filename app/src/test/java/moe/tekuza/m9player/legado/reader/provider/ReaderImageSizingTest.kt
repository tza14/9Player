package moe.tekuza.m9player.legado.reader.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderImageSizingTest {
    private val pageWidth = 1000f
    private val pageHeight = 1800f

    @Test
    fun fitReaderStandaloneImageSize_scalesSmallIllustrationUpToFillPage() {
        // 720×1024 的整页插画：手机页面上原来只按原尺寸画（占小半页），现在应铺满宽度
        val (width, height) = fitReaderStandaloneImageSize(
            sourceWidth = 720f,
            sourceHeight = 1024f,
            maxWidth = pageWidth,
            maxHeight = pageHeight
        )

        assertEquals(pageWidth, width, 0.5f)
        assertEquals(1024f * pageWidth / 720f, height, 0.5f)
        assertTrue(height <= pageHeight)
    }

    @Test
    fun fitReaderStandaloneImageSize_shrinksOversizedSpread() {
        // 1441×1024 的跨页图：按宽度缩小，不变形
        val (width, height) = fitReaderStandaloneImageSize(
            sourceWidth = 1441f,
            sourceHeight = 1024f,
            maxWidth = pageWidth,
            maxHeight = pageHeight
        )

        assertEquals(pageWidth, width, 0.5f)
        assertEquals(1024f * pageWidth / 1441f, height, 0.5f)
        assertTrue(height <= pageHeight)
    }

    @Test
    fun fitReaderStandaloneImageSize_capsUpscaleForTinyOrnament() {
        val (width, height) = fitReaderStandaloneImageSize(
            sourceWidth = 40f,
            sourceHeight = 40f,
            maxWidth = pageWidth,
            maxHeight = pageHeight
        )

        assertEquals(40f * READER_IMAGE_MAX_UPSCALE, width, 0.01f)
        assertEquals(40f * READER_IMAGE_MAX_UPSCALE, height, 0.01f)
    }

    @Test
    fun fitReaderStandaloneImageSize_keepsAspectRatioAndFitsBox() {
        val (width, height) = fitReaderStandaloneImageSize(
            sourceWidth = 800f,
            sourceHeight = 2000f,
            maxWidth = pageWidth,
            maxHeight = pageHeight
        )

        assertEquals(800f / 2000f, width / height, 0.001f)
        assertTrue(width <= pageWidth)
        assertEquals(pageHeight, height, 0.5f)
    }

    @Test
    fun fitReaderStandaloneImageSize_fallsBackToBoxForUnknownSource() {
        val (width, height) = fitReaderStandaloneImageSize(
            sourceWidth = 0f,
            sourceHeight = 0f,
            maxWidth = pageWidth,
            maxHeight = pageHeight
        )

        assertEquals(pageWidth, width, 0.01f)
        assertEquals(pageHeight, height, 0.01f)
    }
}
