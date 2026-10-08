package moe.tekuza.m9player.legado.reader.page

import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.entities.TextColumn
import moe.tekuza.m9player.legado.reader.entities.TextPage

/**
 * "章节区间 ∩ 某一页" ⇒ 该页的**页内**高亮区间。
 *
 * 为什么需要换算：`TextColumn.sourceStart/sourceEnd`（以及 `TextPage.charStart/charEnd`）都是
 * **章节下标**，而绘制/命中读的是**页内下标** —— 实测 `colSource=0..213` 对 `pageCharStart=13831`。
 *
 * 口径（别再放宽）：
 * - 交集为空 ⇒ 返回 null，调用方**什么都不画**；
 * - 章区间为 null ⇒ 返回 null。
 *
 * 历史教训：这里曾经在"交集为空"时回退成传入的页内区间，于是**章区间之外的邻页**会按同一个
 * 同标画出游离高亮（用户看到"选区上方多一个框 / 碎成几块"）；真机日志抓到过
 * `drawPage pageInChapter=6 slice=159..208 hostSel=0..29` 这种"区间外页面拿到切片"的证据。
 */
internal fun pageLocalSelectionSlice(
    chapterRange: IntRange?,
    page: TextPage?
): IntRange? {
    if (chapterRange == null) return null
    if (page == null) return null
    val start = maxOf(chapterRange.first, page.charStart) - page.charStart
    val end = minOf(chapterRange.last, page.charEnd - 1) - page.charStart
    // end >= 0：排除"页完全在区间之前"这种退化情况（charEnd <= charStart 的空页）。
    return if (start <= end && end >= 0) start..end else null
}

/**
 * 章节位置落在页表里的哪一页（端点解析用）。
 *
 * 端点必须**自带页身份**（legado 的 TextPos(relativePagePos, …) 就是这个意思）：
 * 拿"当前页"去解析另一个端点，跨页选区时必然失败 —— 真机症状就是"拖到下一页后手柄消失"
 *（selectionBounds 返回 null ⇒ ReadView 把两个手柄设为 GONE）。
 */
internal fun pageForChapterPosition(pages: List<TextPage?>, chapterPosition: Int): TextPage? =
    pages.firstOrNull { page ->
        page != null && chapterPosition >= page.charStart && chapterPosition < page.charEnd
    }

/** 命中结果：页 + 行号 + 列号（纯数据）。 */
internal data class TextHitPoint(
    val page: TextPage,
    val lineIndex: Int,
    val columnIndex: Int
)

/**
 * 触摸点 → (页, 行, 列)。**纯函数**：不改任何状态，输入输出都能单测。
 *
 * 规则（照 legado 的 `touch()`，一步一步来，没有任何 tie-break）：
 * 1. **先按槽位定页**：各页的 `offset` 是它在滚动轴上的起点 ⇒ 取 `offset ≤ 触点轴坐标` 中最靠下的
 *    那一页；都比触点低则取最上一页。真机 `geomDrag` 诊断已证实各页 offset 逐位自洽
 *    （`off_{n+1} = off_n + span_n`）⇒ 槽位归属**唯一**。
 * 2. 再在**这一页里**找精确命中（点落在某列的跨度、行带之内）；
 * 3. 都没中（行间、段间、页底空白）⇒ 在同一页里取**最近的字**。
 *
 * 为什么不再需要"多候选 + 吸附/方向裁决"：一个触点只属于一页（第 1 步已定），别的页不可能含它 ⇒
 * 平局不存在。历史上那两套机制都是给"页归属不确定"打的补丁，最后都变成了新的 bug 源
 *（吸附参照它自己要改的端点 ⇒ 反馈环；方向裁决又引入轴判错误）。
 *
 * @param horizontal 滚动轴是否为横轴（= offset 作用在 x 上；竖排横读时才是 y）
 */
internal fun resolveTextHit(
    candidates: List<Pair<TextPage, Float>>,
    x: Float,
    y: Float,
    paddingLeft: Float,
    paddingTop: Float,
    horizontal: Boolean
): TextHitPoint? {
    val axis = if (horizontal) x - paddingLeft else y - paddingTop
    val (page, offset) = candidates
        .filter { (_, candidateOffset) -> candidateOffset <= axis }
        .maxByOrNull { (_, candidateOffset) -> candidateOffset }
        ?: candidates.minByOrNull { (_, candidateOffset) -> candidateOffset }
        ?: return null

    exactHitIn(page, offset, x, y, paddingLeft, paddingTop, horizontal)?.let { return it }

    val localX = x - paddingLeft - (if (horizontal) offset else 0f)
    val localY = y - paddingTop - (if (horizontal) 0f else offset)
    var nearest: TextHitPoint? = null
    var nearestDistance = Float.MAX_VALUE
    page.lines.forEachIndexed { lineIndex, line ->
        val crossDistance = when (line.layoutMode) {
            M9LayoutMode.HORIZONTAL -> distanceToRange(localY, line.lineTop, line.lineBottom)
            M9LayoutMode.VERTICAL -> distanceToRange(localX, line.lineTop, line.lineBottom)
        }
        line.columns.forEachIndexed { columnIndex, column ->
            if (column !is TextColumn) return@forEachIndexed
            val alongDistance = when (line.layoutMode) {
                M9LayoutMode.HORIZONTAL -> distanceToRange(localX, column.start, column.end)
                M9LayoutMode.VERTICAL -> distanceToRange(localY, column.start, column.end)
            }
            val distance = crossDistance * crossDistance + alongDistance * alongDistance
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearest = TextHitPoint(page, lineIndex, columnIndex)
            }
        }
    }
    return nearest
}

/** 某一页里的**精确**命中（点在列的跨度、行带之内）；没有则 null。 */
private fun exactHitIn(
    page: TextPage,
    offset: Float,
    x: Float,
    y: Float,
    paddingLeft: Float,
    paddingTop: Float,
    horizontal: Boolean
): TextHitPoint? {
    val localX = x - paddingLeft - (if (horizontal) offset else 0f)
    val localY = y - paddingTop - (if (horizontal) 0f else offset)
    page.lines.forEachIndexed { lineIndex, line ->
        val inLineBounds = when (line.layoutMode) {
            M9LayoutMode.HORIZONTAL -> localY >= line.lineTop && localY <= line.lineBottom
            M9LayoutMode.VERTICAL -> localX >= line.lineTop && localX <= line.lineBottom
        }
        if (!inLineBounds) return@forEachIndexed
        line.columns.forEachIndexed { columnIndex, column ->
            if (column !is TextColumn) return@forEachIndexed
            val hit = when (line.layoutMode) {
                M9LayoutMode.HORIZONTAL -> localX >= column.start && localX <= column.end
                M9LayoutMode.VERTICAL -> localY >= column.start && localY <= column.end
            }
            if (hit) return TextHitPoint(page, lineIndex, columnIndex)
        }
    }
    return null
}

/** 点到闭区间的距离（落在区间内为 0）。 */
internal fun distanceToRange(value: Float, start: Float, end: Float): Float = when {
    value < start -> start - value
    value > end -> value - end
    else -> 0f
}
