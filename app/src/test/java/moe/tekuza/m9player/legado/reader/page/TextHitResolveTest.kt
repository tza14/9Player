package moe.tekuza.m9player.legado.reader.page

import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.entities.TextColumn
import moe.tekuza.m9player.legado.reader.entities.TextLine
import moe.tekuza.m9player.legado.reader.entities.TextPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 命中取点的规则守卫（纯 JVM，不需要 android.*）。
 *
 * 规则只剩三步（照 legado 的 `touch()`）：**先按槽位定页 → 在这一页里找精确命中 → 都没中就取该页最近的字**。
 * 这里把每一步的判据钉住，尤其是"槽位定页"——它是真机 `geomDrag` 数据证实过的唯一归属依据
 *（各页 offset 逐位自洽：off_{n+1} = off_n + span_n），也是删掉 snap / 方向裁决那两套补丁的前提。
 */
class TextHitResolveTest {

    /** 造一页：一行一字，行带 [lineTop, lineBottom]，字跨 [colStart, colEnd]。 */
    private fun page(
        charStart: Int,
        charEnd: Int,
        lineTop: Float,
        lineBottom: Float,
        colStart: Float,
        colEnd: Float,
        sourceStart: Int = 0,
        sourceEnd: Int = 1
    ): TextPage {
        val line = TextLine(
            text = "x",
            lineTop = lineTop,
            lineBase = lineBottom,
            lineBottom = lineBottom,
            layoutMode = M9LayoutMode.HORIZONTAL
        )
        line.addColumn(
            TextColumn(
                start = colStart,
                end = colEnd,
                charData = "x",
                sourceStart = sourceStart,
                sourceEnd = sourceEnd
            )
        )
        return TextPage(charStart = charStart, charEnd = charEnd).apply { addLine(line) }
    }

    private fun hit(candidates: List<Pair<TextPage, Float>>, x: Float, y: Float) =
        resolveTextHit(candidates, x, y, paddingLeft = 0f, paddingTop = 0f, horizontal = false)

    @Test
    fun exactHitOnTheOwningPageWins() {
        val owner = page(100, 200, 0f, 50f, 0f, 30f)
        val result = hit(listOf(owner to 0f), x = 10f, y = 20f)
        assertEquals(owner, result?.page)
        assertEquals(0, result?.columnIndex)
    }

    @Test
    fun thePageWhoseSlotContainsTheTouchOwnsIt() {
        // 槽位：中心页 [0, 1836)，下一页 [1836, …)。触点 y=1900 ⇒ 属于下一页。
        val centre = page(100, 200, 0f, 50f, 0f, 30f)
        val next = page(200, 300, 0f, 50f, 0f, 30f)
        val result = hit(listOf(centre to 0f, next to 1836f), x = 10f, y = 1900f)
        assertEquals(next, result?.page)
    }

    @Test
    fun aTouchInsideTheOwningSlotHitsEvenIfTheCentrePageAlsoHasTextThere() {
        // 中心页的行带是屏幕坐标 0..50（offset 0）；下一页在 1836 之后。
        // 触点 1856 只属于下一页 ⇒ 命中必须落在下一页，哪怕中心页"看起来"也在附近。
        val centre = page(100, 200, 0f, 50f, 0f, 30f)
        val next = page(200, 300, 0f, 50f, 0f, 30f)
        val result = hit(listOf(centre to 0f, next to 1836f), x = 10f, y = 1856f)
        assertEquals(next, result?.page)
    }

    @Test
    fun nearestFallbackFollowsThePageSlotNotTheCentrePage() {
        // 手指深入下一页的**槽位**（offset 1000 起），但那一带是页底空白（行带在上面，不含触点）⇒
        // 兜底必须落在下一页 —— 只认中心页会把端子拽回上一页（真机：拖到下一页却停在中页末尾）。
        val centre = page(100, 200, 0f, 100f, 0f, 30f)
        val lower = page(200, 300, 0f, 100f, 0f, 30f)
        val result = hit(listOf(centre to 0f, lower to 1000f), x = 10f, y = 1200f)
        assertEquals(lower, result?.page)
    }

    @Test
    fun nearestFallbackUsesTheTopmostPageWhenThePointIsAboveAllSlots() {
        val centre = page(100, 200, 0f, 100f, 0f, 30f)
        val lower = page(200, 300, 0f, 100f, 0f, 30f)
        val result = hit(listOf(centre to 500f, lower to 1500f), x = 10f, y = 20f)
        assertEquals(centre, result?.page)
    }

    @Test
    fun betweenLinesAlsoStaysOnTheOwningPage() {
        val centre = page(100, 200, 0f, 50f, 0f, 30f)
        val result = hit(listOf(centre to 0f), x = 10f, y = 70f)
        assertEquals(centre, result?.page)
    }

    @Test
    fun emptyCandidatesGiveNull() {
        assertNull(hit(emptyList(), x = 10f, y = 10f))
    }

    @Test
    fun pageForChapterPositionPicksTheOwningPage() {
        val first = page(100, 200, 0f, 10f, 0f, 5f)
        val second = page(200, 300, 0f, 10f, 0f, 5f)
        assertEquals(second, pageForChapterPosition(listOf(first, second), 250))
        assertNull(pageForChapterPosition(listOf(first, second), 99))
    }
}
