package moe.tekuza.m9player.legado.reader.page

import moe.tekuza.m9player.legado.reader.entities.TextPage

/**
 * "章节区间 ∩ 某一页" ⇒ 该页的**页内**高亮区间。
 *
 * 为什么需要换算：`TextColumn.sourceStart/sourceEnd`（以及 `TextPage.charStart/charEnd`）都是
 * **章节下标**，而绘制/命中读的是**页内下标** —— 实测 `colSource=0..213` 对 `pageCharStart=13831`。
 *
 * 口径（本次明确下来的，别改回旧行为）：
 * - 有章区间时**只**返回交集；交集为空 ⇒ 返回 null，调用方**什么都不画**。
 *   旧实现这里会回退到"宿主页的页内区间"，于是章区间之外的邻页会按同一个同下标画出**游离高亮**
 *   （用户看到"上面多一个框 / 碎成几块"）。
 * - 没有章区间（早期状态）⇒ 原样返回 [legacyPageRange]。
 */
internal fun pageLocalSelectionSlice(
    chapterRange: IntRange?,
    page: TextPage?,
    legacyPageRange: IntRange?
): IntRange? {
    if (chapterRange == null) return legacyPageRange
    if (page == null) return null
    val start = maxOf(chapterRange.first, page.charStart) - page.charStart
    val end = minOf(chapterRange.last, page.charEnd - 1) - page.charStart
    // end >= 0：排除"页完全在区间之前"这种退化情况（charEnd <= charStart 的空页）。
    return if (start <= end && end >= 0) start..end else null
}
