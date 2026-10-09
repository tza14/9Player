package moe.tekuza.m9player.legado.reader.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 竖排标题"每列几个字 / 一共几列"必须**同源**：测量侧（`VerticalTextChapterLayout.titleColumnReserve`
 * 预留多少列宽）与绘制侧（`ContentTextView.drawVerticalBodyTitle` 每列画几个字）都调
 * [VerticalTitleColumnPlan]。
 *
 * 这里只喂"每个 token 占几行"的纯数据 ⇒ 不需要 android.graphics，JVM 单测直接比对两侧的算术。
 */
class VerticalTitleColumnPlanTest {

    /** 纯 CJK 标题：一个字一行（与 `verticalTitleTokens` 对 CJK 的输出一致）。 */
    private fun cjkTitle(charCount: Int) = List(charCount) {
        VerticalTitleToken(text = "字", rows = 1, drawable = true, sidewaysAscii = false)
    }

    private fun cjkChar() = VerticalTitleToken(text = "字", rows = 1, drawable = true, sidewaysAscii = false)

    private fun blank() = VerticalTitleToken(text = " ", rows = 0, drawable = false, sidewaysAscii = false)

    private fun latinWord(rows: Int) =
        VerticalTitleToken(text = "Functions", rows = rows, drawable = true, sidewaysAscii = true)

    @Test
    fun rowsPerColumn_isColumnLengthOverGlyphStep() {
        // step = descent - ascent（绘制侧 drawVerticalBodyTitle 的 step），一分字距都不加。
        assertEquals(20, VerticalTitleColumnPlan.rowsPerColumn(columnLengthPx = 1000f, stepPx = 50f))
        // 除不尽就向下取整（放不下整个字就不能算一格）。
        assertEquals(19, VerticalTitleColumnPlan.rowsPerColumn(999f, 50f))
        // 退化输入：一列至少放一个字，不能返回 0（否则测量侧会算出 0 列宽、绘制侧会死循环）。
        assertEquals(1, VerticalTitleColumnPlan.rowsPerColumn(10f, 50f))
        assertEquals(1, VerticalTitleColumnPlan.rowsPerColumn(0f, 50f))
        assertEquals(1, VerticalTitleColumnPlan.rowsPerColumn(1000f, 0f))
    }

    @Test
    fun columnLengthMinusTitleTopSpacing_shrinksTheColumnOnBothSides() {
        // 同一页：正文区高 1800、字步进 50 ⇒ 满列 36 字（1800 / 50）。
        val contentHeightPx = 1800f
        val stepPx = 50f
        val titleTopSpacingPx = 60f
        assertEquals(36, VerticalTitleColumnPlan.rowsPerColumn(contentHeightPx, stepPx))
        // 绘制侧的列长是"正文区高 − 上留白"（列顶 = paddingTop + 上留白）⇒ 1740 / 50 = 34 字。
        val drawnPerColumn = VerticalTitleColumnPlan.rowsPerColumn(contentHeightPx - titleTopSpacingPx, stepPx)
        assertEquals(34, drawnPerColumn)
        // 测量侧拿到的就是同一个"扣过上留白"的列长（TextPageFactory.titleTopSpacingPx）⇒ 两侧相等。
        val reservedPerColumn = VerticalTitleColumnPlan.rowsPerColumn(contentHeightPx - titleTopSpacingPx, stepPx)
        assertEquals(drawnPerColumn, reservedPerColumn)
        // 忘记扣上留白就会多放 2 个字（多留列宽、正文右缘左移、长标题被提前省略）—— 这正是修复前的两套算术。
        assertNotEquals(drawnPerColumn, VerticalTitleColumnPlan.rowsPerColumn(contentHeightPx, stepPx))
    }

    @Test
    fun reservedColumns_equalDrawnColumns_forCjkTitle() {
        val perColumn = 6
        val titleLength = 10
        val tokens = cjkTitle(titleLength)
        val reserved = VerticalTitleColumnPlan.columnCount(tokens, perColumn)
        // 绘制侧就是逐列调 planColumn（drawVerticalBodyTitle），列边界与 columnCount 同一次规划。
        var index = 0
        var drawn = 0
        while (index < tokens.size) {
            index = VerticalTitleColumnPlan.planColumn(tokens, index, perColumn).nextIndex
            drawn += 1
        }
        assertEquals(2, reserved) // ceil(10 / 6)
        assertEquals(reserved, drawn)
    }

    @Test
    fun cjkColumnCount_isCeilOfCharsPerColumn() {
        val perColumn = 7
        (1..20).forEach { charCount ->
            val expected = (charCount + perColumn - 1) / perColumn
            assertEquals(
                "chars=$charCount",
                expected,
                VerticalTitleColumnPlan.columnCount(cjkTitle(charCount), perColumn)
            )
        }
        assertEquals(0, VerticalTitleColumnPlan.columnCount(emptyList(), perColumn))
    }

    @Test
    fun blankTokens_costNoRow_onEitherSide() {
        val tokens = listOf(blank(), cjkChar(), blank(), cjkChar())
        val perColumn = 2
        // 空白不占行 ⇒ 两个真字塞进一列。
        val plan = VerticalTitleColumnPlan.planColumn(tokens, 0, perColumn)
        assertEquals(4, plan.nextIndex)
        assertEquals(2, plan.rows)
        assertEquals(1, VerticalTitleColumnPlan.columnCount(tokens, perColumn))
    }

    @Test
    fun sidewaysLatinWord_isNeverSplitAcrossColumns() {
        // 一列 4 行：占 4 行的西文整段独占第一列，后面两字进第二列。
        val tokens = listOf(latinWord(4), cjkChar(), cjkChar())
        val perColumn = 4
        val first = VerticalTitleColumnPlan.planColumn(tokens, 0, perColumn)
        assertEquals(1, first.nextIndex)
        assertEquals(4, first.rows)
        val second = VerticalTitleColumnPlan.planColumn(tokens, first.nextIndex, perColumn)
        assertEquals(3, second.nextIndex)
        assertEquals(2, second.rows)
        assertEquals(2, VerticalTitleColumnPlan.columnCount(tokens, perColumn))
    }

    @Test
    fun columnCount_makesProgressWhenATokenIsTallerThanAColumn() {
        // 整段西文比一列还长：谁都放不下，但计数必须前进（绘制侧遇到这种列会直接收笔）。
        val tokens = listOf(latinWord(9), cjkChar())
        val plan = VerticalTitleColumnPlan.planColumn(tokens, 0, perColumn = 3)
        assertEquals(0, plan.nextIndex)
        assertEquals(0, plan.rows)
        assertEquals(2, VerticalTitleColumnPlan.columnCount(tokens, perColumn = 3))
    }

    @Test
    fun reservedColumns_alwaysCoverEveryCharacter() {
        // 预留列数 × 每列字数 ≥ 标题字数：测量侧不会比绘制侧少留一列
        //（少留的直接后果就是最后一个字没地方放、标题被省略）。
        (1..30).forEach { charCount ->
            listOf(1, 3, 7, 12).forEach { perColumn ->
                val columns = VerticalTitleColumnPlan.columnCount(cjkTitle(charCount), perColumn)
                assertTrue(
                    "chars=$charCount perColumn=$perColumn columns=$columns",
                    columns * perColumn >= charCount
                )
            }
        }
    }
}
