package moe.tekuza.m9player.legado.reader.page

import moe.tekuza.m9player.legado.reader.entities.TextPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "章区间 ∩ 页 ⇒ 页内切片"的口径守卫（纯 JVM，不需要 android.*）。
 * 背景：这条换算出错时的症状是"高亮碎成几块 / 选区上方多一个框"，真机日志抓到过
 * `drawPage pageInChapter=6 slice=159..208 hostSel=0..29` —— 区间之外的页拿到了切片。
 * 所以这里**只**允许交集，任何"回退"都不许存在（早期状态的兜底只对宿主页，在调用点做）。
 */
class SelectionSliceTest {

    private fun page(start: Int, end: Int) = TextPage(charStart = start, charEnd = end)

    @Test
    fun chapterRangeInsidePageIsConvertedToPageLocal() {
        assertEquals(28..88, pageLocalSelectionSlice(3203..3263, page(3175, 3275)))
    }

    @Test
    fun chapterRangeCrossingPageStartIsClippedToPageStart() {
        assertEquals(0..9, pageLocalSelectionSlice(1000..1009, page(1000, 1100)))
    }

    @Test
    fun chapterRangeCrossingPageEndIsClippedToPageEnd() {
        assertEquals(90..99, pageLocalSelectionSlice(1090..1200, page(1000, 1100)))
    }

    @Test
    fun pageEntirelyBeforeTheRangePaintsNothing() {
        // 真机回归用例：range 在 1127..1156，而这张页是 918..1127 ⇒ 必须 null
        assertNull(pageLocalSelectionSlice(1127..1156, page(918, 1127)))
    }

    @Test
    fun pageEntirelyAfterTheRangePaintsNothing() {
        assertNull(pageLocalSelectionSlice(1000..1100, page(2000, 2100)))
    }

    @Test
    fun withoutChapterRangeNothingIsPainted() {
        assertNull(pageLocalSelectionSlice(null, page(1000, 1100)))
    }

    @Test
    fun withoutPageNothingIsPainted() {
        assertNull(pageLocalSelectionSlice(1000..1100, null))
    }

    @Test
    fun degenerateEmptyPagePaintsNothing() {
        assertNull(pageLocalSelectionSlice(1000..1100, page(1000, 1000)))
    }
}
