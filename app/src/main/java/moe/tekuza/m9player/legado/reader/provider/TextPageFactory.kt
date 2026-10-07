package moe.tekuza.m9player.legado.reader.provider

import moe.tekuza.m9player.isReaderChar
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.EbookDocument
import moe.tekuza.m9player.legado.reader.M9ReadBookConfig
import moe.tekuza.m9player.legado.reader.entities.TextChapter
import moe.tekuza.m9player.legado.reader.entities.TextPage

/**
 * Counts "reader characters" (digits, Latin letters, kana and CJK ideographs) in the given range,
 * excluding punctuation, whitespace, ruby/HTML residue and other non-matchable code points.
 * Counts Unicode code points, so surrogate pairs (e.g. 𛿠) count as one character.
 */
internal fun String.readerCharCount(fromIndex: Int = 0, toIndex: Int = length): Int {
    var count = 0
    var index = fromIndex.coerceIn(0, length)
    val end = toIndex.coerceIn(index, length)
    while (index < end) {
        val codePoint = Character.codePointAt(this, index)
        if (codePoint.isReaderChar()) count += 1
        index += Character.charCount(codePoint)
    }
    return count
}

internal class TextPageFactory(
    private val config: M9ReadBookConfig,
    private val emptyPageText: String,
    /** 句尾处理（句子不跨页）：按章节索引的句子起点集合（章节坐标） */
    private val sentenceStartsByChapter: Map<Int, Set<Int>> = emptyMap(),
    /** 句尾处理（句子不跨页）：按章节索引的句子结尾集合（章节坐标） */
    private val sentenceEndsByChapter: Map<Int, Set<Int>> = emptyMap()
) {
    fun createPages(
        document: EbookDocument,
        contentWidthPx: Int,
        contentHeightPx: Int,
        firstPageReservePx: Float = 0f
    ): List<TextPage> {
        val chapters = document.chapters.mapIndexed { index, chapter ->
            layoutChapter(document, index, contentWidthPx, contentHeightPx, firstPageReservePx)
        }
        val allPages = chapters.flatMap { it.pages }.ifEmpty {
            listOf(
                TextPage(
                    index = 0,
                    pageInChapter = 0,
                    chapterPageCount = 1,
                    globalIndex = 0,
                    totalPages = 1,
                    chapterIndex = 0,
                    chapterSize = 1,
                    title = document.title,
                    text = emptyPageText,
                    charStart = 0,
                    charEnd = 0
                )
            )
        }
        return assignGlobalPageNumbers(allPages, document, contentWidthPx, contentHeightPx)
    }

    /**
     * 页脚/进度的"总章数"口径覆盖：模拟阅读下应传"已解锁章数"，与目录、进度条一致
     *（legado 也是把 simulatedChapterSize 当 chapterSize 传进排版）；为空表示用真实章数。
     */
    var chapterCountOverride: Int? = null

    fun createChapterPages(
        document: EbookDocument,
        chapterIndex: Int,
        contentWidthPx: Int,
        contentHeightPx: Int,
        firstPageReservePx: Float = 0f
    ): List<TextPage> {
        val safeIndex = chapterIndex.coerceIn(0, document.chapters.lastIndex.coerceAtLeast(0))
        val pages = document.chapters.getOrNull(safeIndex)
            ?.let { layoutChapter(document, safeIndex, contentWidthPx, contentHeightPx, firstPageReservePx).pages }
            .orEmpty()
        return assignGlobalPageNumbers(
            pages.ifEmpty {
                listOf(
                    TextPage(
                        index = 0,
                        pageInChapter = 0,
                        chapterPageCount = 1,
                        globalIndex = 0,
                        totalPages = 1,
                        chapterIndex = safeIndex,
                        chapterSize = chapterCountOverride ?: document.chapters.size.coerceAtLeast(1),
                        title = document.title,
                        text = emptyPageText,
                        charStart = 0,
                        charEnd = 0
                    )
                )
            },
            document,
            contentWidthPx,
            contentHeightPx
        )
    }

    private fun layoutChapter(
        document: EbookDocument,
        index: Int,
        contentWidthPx: Int,
        contentHeightPx: Int,
        firstPageReservePx: Float
    ): TextChapter {
        val chapter = document.chapters[index]
        val textChapter = TextChapter(
            chapterIndex = index,
            title = chapter.title,
            text = chapter.text,
            chaptersSize = chapterCountOverride ?: document.chapters.size,
            images = chapter.images,
            rubySpans = if (config.showRubyText) chapter.rubySpans else emptyList(),
            isVolume = chapter.isVolume,
            sentenceStarts = sentenceStartsByChapter[index] ?: emptySet(),
            sentenceEnds = sentenceEndsByChapter[index] ?: emptySet()
        )
        val laidOut = when (config.layoutMode) {
            M9LayoutMode.HORIZONTAL -> TextChapterLayout(
                config, contentWidthPx, contentHeightPx, firstPageReservePx
            ).layout(textChapter)
            M9LayoutMode.VERTICAL -> VerticalTextChapterLayout(
                config, contentWidthPx, contentHeightPx, firstPageReservePx
            ).layout(textChapter)
        }
        if (chapter.isVolume) {
            laidOut.pages.forEach { it.isVolume = true }
        }
        return laidOut
    }

    private fun assignGlobalPageNumbers(
        pages: List<TextPage>,
        document: EbookDocument,
        contentWidthPx: Int,
        contentHeightPx: Int
    ): List<TextPage> {
        val total = pages.size.coerceAtLeast(1)
        val chapterCount = document.chapters.size.coerceAtLeast(1)
        val chapterSemanticOffsets = IntArray(chapterCount)
        var semanticTotal = 0
        document.chapters.forEachIndexed { index, chapter ->
            chapterSemanticOffsets[index] = semanticTotal
            semanticTotal += chapter.text.readerCharCount()
        }
        val rawCursor = IntArray(chapterCount)
        val semanticCursor = IntArray(chapterCount)
        return pages.mapIndexed { index, page ->
            page.globalIndex = index
            page.totalPages = total
            val chapterIndex = page.chapterIndex.takeIf { it in 0 until chapterCount } ?: 0
            val chapterText = document.chapters.getOrNull(chapterIndex)?.text.orEmpty()
            val pageStart = page.charStart.coerceIn(0, chapterText.length)
            val pageEnd = page.charEnd.coerceIn(pageStart, chapterText.length)
            val gapChars = if (pageStart >= rawCursor[chapterIndex]) {
                chapterText.readerCharCount(rawCursor[chapterIndex], pageStart)
            } else {
                0
            }
            val pageChars = chapterText.readerCharCount(pageStart, pageEnd)
            val chapterBase = chapterSemanticOffsets[chapterIndex]
            page.documentCharStart = chapterBase + semanticCursor[chapterIndex] + gapChars
            page.documentCharEnd = page.documentCharStart + pageChars
            page.documentCharCount = semanticTotal
            rawCursor[chapterIndex] = pageEnd
            semanticCursor[chapterIndex] += gapChars + pageChars
            if (page.height <= 0f) page.height = contentHeightPx.toFloat()
            if (page.width <= 0f) page.width = contentWidthPx.toFloat()
            page
        }
    }
}
