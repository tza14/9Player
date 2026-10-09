package moe.tekuza.m9player.legado.reader.provider

import moe.tekuza.m9player.VerticalTextGlyphEngine
import moe.tekuza.m9player.isSidewaysAsciiText
import kotlin.math.ceil

/**
 * 竖排标题"每列几个字 / 一共几列"的**唯一计划**。
 *
 * 测量侧（`VerticalTextChapterLayout.titleColumnReserve` 预留多少列宽）与绘制侧
 *（`ContentTextView.drawVerticalBodyTitle` 每列画几个字）都调这里 ⇒ 两边不会各写一份算术
 * 而差出一列（差一列的直接后果就是长标题提前被省略）。
 *
 * 纯 Kotlin：只看 token 的"占几行 / 是不是空白"，画笔的度量由调用方作为数据喂进来
 *（见 [verticalTitleTokens]），所以这套算术能在 JVM 单测里直接比对。
 */
internal object VerticalTitleColumnPlan {

    /** 一列的规划结果：本列吃掉 `[index, nextIndex)` 的 token，共占 [rows] 行。 */
    data class Column(val nextIndex: Int, val rows: Int)

    /** 一列能放几行：可用列长 ÷ 字步进。 */
    fun rowsPerColumn(columnLengthPx: Float, stepPx: Float): Int {
        if (stepPx <= 0f || columnLengthPx <= 0f) return 1
        return (columnLengthPx / stepPx).toInt().coerceAtLeast(1)
    }

    /**
     * 规划一列：从 [index] 起、在 [perColumn] 行内能放下的 token。
     *
     * 空白 token 跳过且不占行；整段西文（占多行）放不下就**整段留给下一列**，绝不拆词。
     * 返回这次规划的终点与占用行数；`nextIndex == index` 表示"一个都放不下"（调用方必须防死循环）。
     */
    fun planColumn(tokens: List<VerticalTitleToken>, index: Int, perColumn: Int): Column {
        val capacity = perColumn.coerceAtLeast(1)
        var cursor = index
        var rows = 0
        while (cursor < tokens.size && rows < capacity) {
            val token = tokens[cursor]
            if (!token.drawable) {
                cursor += 1
                continue
            }
            if (rows + token.rows > capacity) break
            rows += token.rows
            cursor += 1
        }
        return Column(nextIndex = cursor, rows = rows)
    }

    /**
     * 标题一共占几列。测量侧要的就是这个数 × 列宽；绘制侧逐列调 [planColumn]，
     * 列的边界因此与这里**必然一致**（同一次规划，不是两套算法）。
     */
    fun columnCount(tokens: List<VerticalTitleToken>, perColumn: Int): Int {
        var index = 0
        var columns = 0
        while (index < tokens.size) {
            val column = planColumn(tokens, index, perColumn)
            // 一个 token 都放不下（整段西文比一列还长）也必须前进，否则这里会死循环。
            index = if (column.nextIndex > index) column.nextIndex else index + 1
            columns += 1
        }
        return columns
    }
}

/**
 * 竖排标题里的一个 token（一个字 / 一段西文 / 一个拗音 / 一个空白）。
 *
 * [rows] 与 [drawable] 是**计划**要的全部信息；[text] / [sidewaysAscii] 只有绘制侧用。
 */
internal data class VerticalTitleToken(
    val text: String,
    /** 占几行：CJK 字/拗音 = 1，西文整段 = 需要的 cell 数。 */
    val rows: Int,
    /** 空白 token：跳过、不占行。 */
    val drawable: Boolean,
    /** 西文整段：绘制时整段旋转。 */
    val sidewaysAscii: Boolean
)

/**
 * 把标题切成 token 序列 —— **测量侧与绘制侧共用这一条规则**，所以"这一列能放几个 token"
 * 在两边是同一个答案。规则与 `VerticalTextGlyphEngine.nextVerticalTextToken` 一致：
 * 西文整段按 [measureText] ÷ [cellWidthPx] 占多行，代理对整体算一个 token。
 */
internal fun verticalTitleTokens(
    text: String,
    cellWidthPx: Float,
    measureText: (String) -> Float
): List<VerticalTitleToken> {
    val cell = cellWidthPx.coerceAtLeast(1f)
    val tokens = ArrayList<VerticalTitleToken>()
    var index = 0
    while (index < text.length) {
        val token = VerticalTextGlyphEngine.nextVerticalTextToken(text, index, text.length)
        if (token.text.isBlank()) {
            tokens += VerticalTitleToken(text = token.text, rows = 0, drawable = false, sidewaysAscii = false)
        } else {
            val sideways = isSidewaysAsciiText(token.text)
            tokens += VerticalTitleToken(
                text = token.text,
                rows = if (sideways) ceil(measureText(token.text) / cell).toInt().coerceAtLeast(1) else 1,
                drawable = true,
                sidewaysAscii = sideways
            )
        }
        index = token.sourceEndExclusive.coerceAtLeast(index + 1)
    }
    return tokens
}
