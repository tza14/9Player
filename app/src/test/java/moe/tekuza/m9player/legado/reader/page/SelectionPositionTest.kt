package moe.tekuza.m9player.legado.reader.page

import moe.tekuza.m9player.legado.reader.entities.TextPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端点表示的规则守卫（纯 JVM）。
 *
 * 这一层存在的意义：让"谁在前"只有一个答案（compareTo），让"这个端点在哪个字的哪一页"固定在
 * 端点自己身上（页身份绝对），从而在结构上消除"页边界处命中一抖 ⇒ 端点翻转/手柄消失"这一整类问题。
 */
class SelectionPositionTest {

    private fun page(chapterIndex: Int, pageInChapter: Int, charStart: Int, charEnd: Int) =
        TextPage(charStart = charStart, charEnd = charEnd).apply {
            this.chapterIndex = chapterIndex
            this.pageInChapter = pageInChapter
        }

    @Test
    fun positionCarriesAbsolutePageIdentityAndDerivedOffset() {
        val target = page(chapterIndex = 7, pageInChapter = 21, charStart = 3205, charEnd = 3389)
        val position = selectionPositionOf(target, lineIndex = 3, columnIndex = 12, sourceStart = 168)
        assertEquals(7, position.chapterIndex)
        assertEquals(21, position.pageInChapter)
        assertEquals(3205 + 168, position.chapterOffset)
        assertEquals(3, position.lineIndex)
        assertEquals(12, position.columnIndex)
    }

    @Test
    fun compareUsesOnlyTheChapterOffset() {
        val a = SelectionPosition(7, 21, 100, 0, 0)
        val b = SelectionPosition(7, 22, 200, 5, 9)
        assertTrue(a.compareTo(b) < 0)
        assertTrue(b.compareTo(a) > 0)
        assertEquals(0, a.compareTo(SelectionPosition(7, 99, 100, 42, 42)))
    }

    @Test
    fun sameAsIgnoresGeometryOfTheSameOffset() {
        val a = SelectionPosition(7, 21, 3373, 1, 2)
        val b = SelectionPosition(7, 21, 3373, 9, 9)
        assertTrue(a.sameAs(b))
        assertFalse(a.sameAs(SelectionPosition(7, 21, 3374, 1, 2)))
    }

    @Test
    fun orderedPositionsSwapsWhenTheDragCrossedOver() {
        val start = SelectionPosition(7, 22, 3400, 0, 0)
        val end = SelectionPosition(7, 21, 3300, 0, 0)
        val (first, last) = orderedPositions(start, end)
        assertEquals(3300, first.chapterOffset)
        assertEquals(3400, last.chapterOffset)
    }

    @Test
    fun positionsChapterRangeCoversBothEndpointsInclusive() {
        val a = SelectionPosition(7, 21, 3373, 0, 0)
        val b = SelectionPosition(7, 22, 3451, 0, 0)
        assertEquals(3373..3451, positionsChapterRange(a, b))
        assertEquals(3373..3451, positionsChapterRange(b, a))
    }

    @Test
    fun aSinglePointSelectionHasAZeroLengthRange() {
        val a = SelectionPosition(7, 21, 3373, 0, 0)
        assertEquals(3373..3373, positionsChapterRange(a, a))
    }
}
