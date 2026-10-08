package moe.tekuza.m9player.legado.reader.page

import moe.tekuza.m9player.legado.reader.entities.TextPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "章区间 ∩ 页 ⇒ 页内切片"的口径守卫（纯 JVM，不需要 android.*）。
 * 背景：这条换算出错时的症状是"高亮碎成几块 / 选区上方多一个框"，而它以前散在两个调用点里、没有测试。
 */
class SelectionSliceTest {

    private fun page(start: Int, end: Int) = TextPage(charStart = start, charEnd = end)

    @Test
    fun chapterRangeInsidePageIsConvertedToPageLocal() {
        assertEquals(28..88, pageLocalSelectionSlice(3203..3263, page(3175, 3275), null))
    }

    @Test
    fun chapterRangeCrossingPageStartIsClippedToPageStart() {
        assertEquals(0..9, pageLocalSelectionSlice(1000..1009, page(1000, 1100), null))
    }

    @Test
    fun chapterRangeCrossingPageEndIsClippedToPageEnd() {
        assertEquals(90..99, pageLocalSelectionSlice(1090..1200, page(1000, 1100), null))
    }

    @Test
    fun pageEntirelyBeforeTheRangePaintsNothing() {
        assertNull(pageLocalSelectionSlice(2000..2100, page(1000, 1100), 5..9))
    }

    @Test
    fun pageEntirelyAfterTheRangePaintsNothing() {
        assertNull(pageLocalSelectionSlice(1000..1100, page(2000, 2100), 5..9))
    }

    @Test
    fun withoutChapterRangeTheLegacyPageRangeIsUsed() {
        assertEquals(5..9, pageLocalSelectionSlice(null, page(1000, 1100), 5..9))
    }

    @Test
    fun chapterRangeWithoutPagePaintsNothing() {
        assertNull(pageLocalSelectionSlice(1000..1100, null, 5..9))
    }

    @Test
    fun degenerateEmptyPagePaintsNothing() {
        assertNull(pageLocalSelectionSlice(1000..1100, page(1000, 1000), 0..3))
    }
}
