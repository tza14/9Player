package moe.tekuza.m9player.legado.reader.page

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
