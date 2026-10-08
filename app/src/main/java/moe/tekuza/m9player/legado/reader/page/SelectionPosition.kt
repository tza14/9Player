package moe.tekuza.m9player.legado.reader.page

import moe.tekuza.m9player.legado.reader.entities.TextPage

/**
 * 选区端点（照 legado 的 `TextPos`，但页身份用**绝对**编号）。
 *
 * 为什么要有它：此前选区 = "章节下标区间 + 一份页内镜像"，两个表示必须手工保持同步 ⇒
 * 页边界处命中一抖，端点就在两页之间翻转（真机现象：拖动时一直闪上一页、手柄消失、切片忽有忽无）。
 *
 * 现在端点**自带页身份**（chapterIndex + pageInChapter）；[chapterOffset] 是构造时算好的派生常量，
 * 任何地方都不重算 —— 排序与"取出被选文字"只用它，几何（line/column）只在自己的那一页里有意义。
 *
 * @param chapterOffset 该端点在章节原文里的下标（= page.charStart + column.sourceStart）
 */
internal data class SelectionPosition(
    val chapterIndex: Int,
    val pageInChapter: Int,
    val chapterOffset: Int,
    val lineIndex: Int,
    val columnIndex: Int
) {
    /**
     * 单一全局序（legado 的 `TextPos.compare`）：只比章节下标 ⇒ 跨页/跨行都只有一个含义。
     * 命中与更新的所有"谁在前"判断都必须走这里，不许各自换算。
     */
    fun compareTo(other: SelectionPosition): Int = chapterOffset.compareTo(other.chapterOffset)

    /** 是否与另一端点指向同一处（同页同列 ⇒ 同一章节下标）。 */
    fun sameAs(other: SelectionPosition): Boolean = chapterOffset == other.chapterOffset
}

/**
 * 由"命中结果"构造端点：页身份来自命中页，章节下标 = 命中页 charStart + 页内列起点。
 * 这是端点唯一的来源（不再有"用当前页 + 页内镜像"的换算）。
 */
internal fun selectionPositionOf(
    page: TextPage,
    lineIndex: Int,
    columnIndex: Int,
    sourceStart: Int
): SelectionPosition = SelectionPosition(
    chapterIndex = page.chapterIndex,
    pageInChapter = page.pageInChapter,
    chapterOffset = page.charStart + sourceStart,
    lineIndex = lineIndex,
    columnIndex = columnIndex
)

/** [start] 与 [end] 排序后的区间（含端点）。 */
internal fun orderedPositions(
    start: SelectionPosition,
    end: SelectionPosition
): Pair<SelectionPosition, SelectionPosition> =
    if (start.compareTo(end) <= 0) start to end else end to start

/** 两个端点覆盖的章节区间（含端点），供"取出被选文字"与按页求交使用。 */
internal fun positionsChapterRange(
    start: SelectionPosition,
    end: SelectionPosition
): IntRange {
    val (first, last) = orderedPositions(start, end)
    return first.chapterOffset..last.chapterOffset
}
