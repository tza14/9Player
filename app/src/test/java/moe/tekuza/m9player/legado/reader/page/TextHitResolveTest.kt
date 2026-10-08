package moe.tekuza.m9player.legado.reader.page

import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.entities.TextColumn
import moe.tekuza.m9player.legado.reader.entities.TextLine
import moe.tekuza.m9player.legado.reader.entities.TextPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命中取点的规则守卫（纯 JVM，不需要 android.*）。
 *
 * 这些规则是拿真机日志换来的：以前命中会改写"当前页"，于是下一次命中依赖上一次 ⇒
 * 页边界处 `hostPage` 在 19/20 之间交替，配合端点交换把选区来回翻转。现在取点是纯函数，
 * 顺序（= 候选页优先级）与兜底范围都被这里钉住。
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
    fun exactHitOnTheCentrePageWins() {
        // charStart 只是标记，用来确认命中的是哪一页
        val centre = page(100, 200, 0f, 50f, 0f, 30f)
        val result = hit(listOf(centre to 0f), x = 10f, y = 20f)
        assertEquals(centre, result?.page)
        assertEquals(0, result?.columnIndex)
    }

    @Test
    fun exactHitOnANeighbourPageIsFound() {
        // 中心页这一行在 y=0..50，邻页在 y=200..250 ⇒ y=220 必须落到邻页（跨页拖手柄靠它）
        val centre = page(100, 200, 0f, 50f, 0f, 30f)
        val neighbour = page(200, 300, 200f, 250f, 0f, 30f)
        val result = hit(listOf(centre to 0f, neighbour to 0f), x = 10f, y = 220f)
        assertEquals(neighbour, result?.page)
    }

    @Test
    fun overlappingBandsBelongToTheFirstCandidate() {
        // 页带区重叠（同一 y 两页都能精确命中）⇒ 归候选顺序里最靠前的那页 —— 顺序即优先级，可预测
        val first = page(100, 200, 0f, 100f, 0f, 30f)
        val second = page(200, 300, 0f, 100f, 0f, 30f)
        val result = hit(listOf(first to 0f, second to 0f), x = 10f, y = 50f)
        assertEquals(first, result?.page)
    }

    @Test
    fun preferPositionSnapsToTheNearerPage() {
        // 拖手柄时的吸附：两页都能精确命中（手指压在页边界），取离当前端点更近的那个。
        // 没有它，端点会在本页尾字与下页首字之间随微抖来回跳（真机现象："拖动时一直闪上一页"）。
        val first = page(100, 200, 0f, 100f, 0f, 30f, sourceStart = 90, sourceEnd = 91) // 位置 190
        val second = page(200, 300, 0f, 100f, 0f, 30f, sourceStart = 0, sourceEnd = 1)  // 位置 200
        val candidates = listOf(first to 0f, second to 0f)

        // 当前端点在 200 附近 ⇒ 吸附到 second（尽管 first 在候选顺序里更靠前）
        assertEquals(
            second,
            resolveTextHit(candidates, 10f, 50f, 0f, 0f, horizontal = false, preferChapterPosition = 200)?.page
        )
        // 当前端点在 190 附近 ⇒ 吸附回 first
        assertEquals(
            first,
            resolveTextHit(candidates, 10f, 50f, 0f, 0f, horizontal = false, preferChapterPosition = 190)?.page
        )
    }

    @Test
    fun preferPositionDoesNotOverrideAnExactHitOnASinglePage() {
        val only = page(100, 200, 0f, 100f, 0f, 30f, sourceStart = 10, sourceEnd = 11)
        val result = resolveTextHit(
            listOf(only to 0f), 10f, 50f, 0f, 0f, horizontal = false, preferChapterPosition = 99999
        )
        assertEquals(only, result?.page)
    }

    @Test
    fun belowTheLastLineFallsBackToTheNearestColumnOfTheCentrePage() {
        // 手指在页底空白：不许跳到别页，只在中心页里取最近
        val centre = page(100, 200, 0f, 100f, 0f, 30f)
        val other = page(200, 300, 200f, 300f, 0f, 30f)
        val result = hit(listOf(centre to 0f, other to 0f), x = 10f, y = 1000f)
        assertEquals(centre, result?.page)
    }

    @Test
    fun betweenLinesAlsoStaysOnTheCentrePage() {
        val centre = page(100, 200, 0f, 50f, 0f, 30f)
        val result = hit(listOf(centre to 0f), x = 10f, y = 70f)
        assertEquals(centre, result?.page)
    }

    @Test
    fun candidateOffsetShiftsTheBand() {
        // 候选页带自己的滚动偏移：同一屏幕位置在不同偏移下命中不同行
        val shifted = page(100, 200, 300f, 350f, 0f, 30f)
        // 偏移 -300 ⇒ 屏幕 y=20 对应内容 y=320 ⇒ 落在 300..350 内
        val result = hit(listOf(shifted to -300f), x = 10f, y = 20f)
        assertEquals(shifted, result?.page)
    }

    @Test
    fun emptyCandidatesGiveNull() {
        assertNull(hit(emptyList(), x = 10f, y = 10f))
    }

    @Test
    fun bandFilterNeverChangesAnExactHit() {
        // 收窄候选只省扫描量：精确命中按定义落在某一行内，而行必然在该页带区内。
        // 触点落在**行间空隙**（带区内、行外）时，仍然由"中心页取最近"兜住。
        val centre = page(100, 200, 0f, 100f, 0f, 30f)
        val result = hit(listOf(centre to 0f), x = 10f, y = 150f) // 行外、带区外 ⇒ 回退全量
        assertEquals(centre, result?.page)
    }

    @Test
    fun bandFilterFallsBackToTheWholeListWhenNothingContainsThePoint() {
        // 邻页被自己的偏移推到很远（触点在它带区外）⇒ 它不该参与，但中心页必须仍能给出结果
        val centre = page(100, 200, 0f, 100f, 0f, 30f)
        val far = page(200, 300, 0f, 100f, 0f, 30f)
        val result = hit(listOf(centre to 0f, far to -100000f), x = 10f, y = 900f)
        assertEquals(centre, result?.page)
    }

    @Test
    fun isWithinPageBandChecksTheScrollAxisWithOneLineTolerance() {
        val target = page(100, 200, 0f, 100f, 0f, 30f)
        // 带区内
        assertTrue(isWithinPageBand(target, 0f, 10f, 50f, 0f, 0f))
        // 超出一行高的容差 ⇒ 不算这一页（曾经轴判写反，y=1000 也判"在内"）
        assertFalse(isWithinPageBand(target, 0f, 10f, 1000f, 0f, 0f))
        // 邻页在 y=200..250：触点 220 只属于邻页，中心页（0..50）必须被滤掉
        val centre = page(100, 200, 0f, 50f, 0f, 30f)
        val neighbour = page(200, 300, 200f, 250f, 0f, 30f)
        assertFalse(isWithinPageBand(centre, 0f, 10f, 220f, 0f, 0f))
        assertTrue(isWithinPageBand(neighbour, 0f, 10f, 220f, 0f, 0f))
        // 超出带区但在一行高的容差内 ⇒ 仍算这一页（手指略微越过页底不应丢命中）
        assertTrue(isWithinPageBand(target, 0f, 10f, 180f, 0f, 0f))
    }

    @Test
    fun candidatesWithBandFirstReordersWithoutDropping() {
        // 手指在中心页（带区 0..50）之外的 y=220 上：邻页（200..250）应被提到前面，
        // 但**两页都必须在列表里**（不是丢弃）—— 否则带区判定一旦有误就会丢命中。
        val centre = page(100, 200, 0f, 50f, 0f, 30f)
        val neighbour = page(200, 300, 200f, 250f, 0f, 30f)
        val reordered = candidatesWithBandFirst(
            listOf(centre to 0f, neighbour to 0f), 10f, 220f, 0f, 0f
        )
        assertEquals(2, reordered.size)
        assertEquals(neighbour, reordered[0].first)
        assertEquals(centre, reordered[1].first)
    }

    @Test
    fun candidatesWithBandFirstKeepsTheOriginalListWhenOneSideIsEmpty() {
        // 所有候选都在带区内（或都不在）⇒ 原样返回，零风险路径
        val centre = page(100, 200, 0f, 50f, 0f, 30f)
        val other = page(200, 300, 0f, 50f, 0f, 30f)
        val same = listOf(centre to 0f, other to 0f)
        assertEquals(same, candidatesWithBandFirst(same, 10f, 20f, 0f, 0f))
        val far = listOf(centre to 0f, other to -100000f)
        assertEquals(far, candidatesWithBandFirst(far, 10f, 9999f, 0f, 0f))
    }

    @Test
    fun reorderingDoesNotChangeTheWinner() {
        // 带区外的页不可能精确命中 ⇒ 重排前后的赢家相同（用邻页能精确命中、中心页不能的构造验证）
        val centre = page(100, 200, 0f, 50f, 0f, 30f)
        val neighbour = page(200, 300, 200f, 250f, 0f, 30f)
        val result = hit(listOf(centre to 0f, neighbour to 0f), x = 10f, y = 220f)
        assertEquals(neighbour, result?.page)
    }

    @Test
    fun pageForChapterPositionPicksTheOwningPage() {
        val first = page(100, 200, 0f, 10f, 0f, 5f)
        val second = page(200, 300, 0f, 10f, 0f, 5f)
        assertEquals(second, pageForChapterPosition(listOf(first, second), 250))
        assertNull(pageForChapterPosition(listOf(first, second), 99))
    }
}
