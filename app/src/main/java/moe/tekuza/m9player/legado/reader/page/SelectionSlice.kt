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
 * - 章区间为 null ⇒ 返回 null（早期状态由调用方**只对宿主页**用页内区间兜底，不能让邻页也画）。
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
 * 为什么必须纯：以前命中会顺手改写"当前页"，于是下一次命中依赖上一次的结果 —— 真机上表现为
 * 页边界附近 `hostPage` 在 19/20 之间交替，配合端点交换就把选区来回翻转（用户看到的"越来越乱"）。
 *
 * 规则（与 legado 的 `touch`/`touchRough` 同形）：
 * 1. 先在**所有**候选页里找精确命中（点落在某列的跨度内）—— 跨页拖手柄靠这一遍；
 * 2. 都没有（行间、段间、页底空白）⇒ 只在**第一个候选页**里取最近的字，绝不跨页找；
 * 3. `candidates` 的顺序就是优先级 ⇒ "页带区重叠时归谁"由调用方给定、可预测、可单测。
 *
 * @param horizontal 滚动轴是否为横轴（竖排时为 true）
 */
internal fun resolveTextHit(
    candidates: List<Pair<TextPage, Float>>,
    x: Float,
    y: Float,
    paddingLeft: Float,
    paddingTop: Float,
    horizontal: Boolean,
    preferForward: Boolean? = null
): TextHitPoint? {
    // 候选**重排**：带区含触点的页排在前面，两段各自保持原顺序。
    // 为什么是重排而不是丢弃：精确命中按定义落在某行内、行必在该页带区内 ⇒ 带区外的页本来就不可能
    // 精确命中；把它们留在后面 ⇒ 与"全量按原序扫"**同一个赢家**（可证），但常见情况下少扫 8 页。
    val ordered = candidatesWithBandFirst(candidates, x, y, paddingLeft, paddingTop)
    if (preferForward == null) {
        // 无偏好（新建选区/普通命中）：候选顺序即优先级，第一个精确命中就是答案。
        ordered.forEach { (page, offset) ->
            exactHitIn(page, offset, x, y, paddingLeft, paddingTop, horizontal)?.let { return it }
        }
    } else {
        // 拖手柄且两页同时"精确命中"（行带重叠）时的裁决：**按手指移动方向**取更靠后/更靠前的那个。
        //
        // 曾经这里用"离当前端点最近"做裁决 —— 那是反馈环：裁决依据正是它要改的那个值，
        // 于是已经停在前一页的终点会把每一次新命中都拽回去（真机：向下拖，终点黏在上一页不动）。
        var best: TextHitPoint? = null
        var bestPosition = 0
        ordered.forEach { (page, offset) ->
            val hit = exactHitIn(page, offset, x, y, paddingLeft, paddingTop, horizontal)
                ?: return@forEach
            val column = page.lines.getOrNull(hit.lineIndex)?.columns?.getOrNull(hit.columnIndex) as? TextColumn
                ?: return@forEach
            val position = page.charStart + column.sourceStart
            val better = best == null ||
                (if (preferForward) position > bestPosition else position < bestPosition)
            if (better) {
                best = hit
                bestPosition = position
            }
        }
        if (best != null) return best
    }
    // 精确命中都没中（落在行间、段间或**页底空白**）⇒ 用**槽位**（各页的 offset）定出"手指在哪一页"，
    // 再在该页里取最近的字 —— 这就是 legado 的 touch()：页由 offset 决定，不由"内容带区"决定。
    //
    // 为什么不能只认中心页：手指深入下一页的槽位、但那一带是页底空白（没有字）时，内容带区并不含它，
    // 中心页兜底就会把端子拽回上一页（真机症状：拖到下一页，选区停在上一页末尾；以及"闪回上一页"）。
    // geomDrag 诊断已证实各页 offset 逐位自洽（off_{n+1} = off_n + span_n）⇒ 槽位归属唯一。
    // `horizontal` 的含义是"**偏移作用在 x 轴上**"（看上面 localX 的算法）⇒ 槽位轴就是 x；
    // 竖排横读时才是 y。这里和 isWithinPageBand 的轴判都踩过同一个坑，故写明。
    val axis = if (horizontal) x - paddingLeft else y - paddingTop
    val owner = candidates
        .filter { (_, offset) -> offset <= axis }
        .maxByOrNull { (_, offset) -> offset }
        ?: candidates.minByOrNull { (_, offset) -> offset }
        ?: return null
    val (center, centerOffset) = owner
    val localX = x - paddingLeft - (if (horizontal) centerOffset else 0f)
    val localY = y - paddingTop - (if (horizontal) 0f else centerOffset)
    var nearest: TextHitPoint? = null
    var nearestDistance = Float.MAX_VALUE
    center.lines.forEachIndexed { lineIndex, line ->
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
                nearest = TextHitPoint(center, lineIndex, columnIndex)
            }
        }
    }
    return nearest
}

/** 某一页里的**精确**命中（点在列的跨度、行带之内）；没有则 null。 */private fun exactHitIn(
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

/**
 * 触点是否落在该页的**内容带区**内（含一条行高的容差）。
 *
 * 带区 = 该页第一行的 band 起点到最后一行的 band 终点（滚动轴上的范围）；
 * 行带本身就是"这一页在屏幕上的高度范围"，所以这条判定等价于"手指是否在这一页上"。
 */
internal fun isWithinPageBand(
    page: TextPage,
    offset: Float,
    x: Float,
    y: Float,
    paddingLeft: Float,
    paddingTop: Float
): Boolean {
    val first = page.lines.firstOrNull() ?: return false
    val last = page.lines.lastOrNull() ?: return false
    val lineHeight = (first.lineBottom - first.lineTop).takeIf { it > 0f } ?: 0f
    val localX = x - paddingLeft - (if (first.layoutMode == M9LayoutMode.VERTICAL) offset else 0f)
    val localY = y - paddingTop - (if (first.layoutMode == M9LayoutMode.VERTICAL) 0f else offset)
    // 轴由**行的排版模式**决定（与 exactHitIn 同一口径）：竖排时带区在 x 轴，否则在 y 轴。
    // 曾经写成"由 horizontal 参数决定"，轴一反就会把该留的页滤掉、把不该留的留下（单测抓到过）。
    val value = if (first.layoutMode == M9LayoutMode.VERTICAL) localX else localY
    return value >= first.lineTop - lineHeight && value <= last.lineBottom + lineHeight
}

/**
 * 候选重排：带区含触点的页在前，**两段各自保持原顺序**。
 *
 * 为什么这样是安全的（可证，不是"应该"）：精确命中要求点落在某行的 band 内，而行的 band 必在该页
 * 带区之内 ⇒ 带区外的页**不可能**精确命中 ⇒ 先扫带区内、再扫带区外，与"全量按原顺序扫"得到**同一个**
 * 赢家；任一段为空时直接返回原列表（零风险路径）。
 */
internal fun candidatesWithBandFirst(
    candidates: List<Pair<TextPage, Float>>,
    x: Float,
    y: Float,
    paddingLeft: Float,
    paddingTop: Float
): List<Pair<TextPage, Float>> {
    val (inBand, outOfBand) = candidates.partition { (page, offset) ->
        isWithinPageBand(page, offset, x, y, paddingLeft, paddingTop)
    }
    return if (inBand.isEmpty() || outOfBand.isEmpty()) candidates else inBand + outOfBand
}
