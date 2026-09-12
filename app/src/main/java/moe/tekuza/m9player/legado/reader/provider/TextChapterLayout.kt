package moe.tekuza.m9player.legado.reader.provider

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.util.Log
import moe.tekuza.m9player.EBOOK_IMAGE_MARKER
import moe.tekuza.m9player.EbookImageRef
import moe.tekuza.m9player.decodeBitmapBounds
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.M9ReadBookConfig
import moe.tekuza.m9player.legado.reader.READER_TITLE_SCALE
import moe.tekuza.m9player.legado.reader.applyM9TextWeight
import moe.tekuza.m9player.legado.reader.entities.ImageColumn
import moe.tekuza.m9player.legado.reader.entities.RubyLayoutEngine
import moe.tekuza.m9player.legado.reader.entities.TextChapter
import moe.tekuza.m9player.legado.reader.entities.TextColumn
import moe.tekuza.m9player.legado.reader.entities.TextLine
import moe.tekuza.m9player.legado.reader.entities.TextPage
import kotlin.math.max

private const val SENTENCE_TAIL_LOG_TAG = "M9SentenceTail"

internal class TextChapterLayout(
    private val config: M9ReadBookConfig,
    private val visibleWidth: Int,
    private val visibleHeight: Int,
    private val firstPageReservePx: Float = 0f
) {
    private val contentPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = config.textSizePx
        color = config.textColor
        applyM9TextWeight(config.textWeight, config.typeface)
    }
    // 卷标题画笔：比正文大一号 + 加粗（与参考实现 legado 的 titlePaint 一致）
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = config.textSizePx * READER_TITLE_SCALE
        color = config.textColor
        typeface = Typeface.create(config.typeface ?: Typeface.DEFAULT, Typeface.BOLD)
    }
    private val fontMetrics = contentPaint.fontMetrics
    private val baseLineHeight = (fontMetrics.descent - fontMetrics.ascent + config.lineSpacingPx)
        .coerceAtLeast(1f)
    private val paragraphSpacing = config.paragraphSpacingPx.coerceAtLeast(0f)
    private var lineHeight = baseLineHeight
    private var rubyReservePx = 0f
    private var rubyByStart: Map<Int, RubyPlacement> = emptyMap()

    fun layout(chapter: TextChapter): TextChapter {
        rubyReservePx = if (chapter.rubySpans.isNotEmpty()) {
            (config.textSizePx * RubyLayoutEngine.RESERVE_RATIO).coerceAtLeast(8f)
        } else {
            0f
        }
        lineHeight = baseLineHeight + rubyReservePx
        rubyByStart = buildRubyPlacements(chapter.rubySpans)
        val text = chapter.text
        if (text.isBlank()) {
            // 卷页（"第一部分 xxx"等）：标题在正文区内垂直居中显示一次，
            // 与参考实现 legado 的 emptyContent 标题排版一致。
            chapter.addPage(
                if (chapter.isVolume) buildVolumeTitlePage(chapter) else TextPage(
                    index = 0,
                    pageInChapter = 0,
                    chapterPageCount = 1,
                    chapterIndex = chapter.chapterIndex,
                    chapterSize = chapter.chaptersSize,
                    title = chapter.title,
                    text = "",
                    charStart = 0,
                    charEnd = 0
                )
            )
            return chapter
        }

        val pageLineGroups = mutableListOf<MutableList<TextLine>>()
        val pageStarts = mutableListOf<Int>()
        var currentLines = mutableListOf<TextLine>()
        var pageStart = 0
        var y = 0f
        var paragraphNum = 0

        // 首页可用高度 = 页高 - 章节标题预留（正文区在标题显示时会被压缩）；
        // 第一页完成翻页后恢复整页高度，避免标题换行等导致的最下面文字被裁剪。
        fun currentPageHeight(): Float =
            if (pageLineGroups.isEmpty()) {
                (visibleHeight - firstPageReservePx).coerceAtLeast(1f)
            } else {
                visibleHeight.toFloat()
            }

        /**
         * 填充一个不含图片标记的文字段 [segmentStart, segmentEnd)。
         * 缩进只给段首第一行；段末行视为段落结尾（便于朗读/选词分组）。
         */
        fun layoutTextSegment(
            paragraphNum: Int,
            paragraphStart: Int,
            segmentStart: Int,
            segmentEnd: Int
        ) {
            val sentenceStarts = chapter.sentenceStarts
            var blockStart = segmentStart
            while (blockStart < segmentEnd) {
                // 「句子不跨页」开启时才切句子块：以 cue 句子起点切块，一个句子块（可能多行）
                // 整体放入页面，放不下则整块推到下一页；句子块内部正常断行。
                // 关闭时 sentenceStarts 为空，块退化为整段 —— 此时不能再做"整块入页"判断，
                // 否则长段会把整页切掉、在页底部留下成片空白；应回到贪婪填满（行排满才翻页）。
                val usesSentenceBlocks = sentenceStarts.isNotEmpty()
                val blockEnd = if (usesSentenceBlocks) {
                    sentenceStarts.filter { it > blockStart && it < segmentEnd }.minOrNull() ?: segmentEnd
                } else {
                    segmentEnd
                }
                if (usesSentenceBlocks) {
                    // 预测量块高度（按当前行宽逐行模拟）
                    val blockLineCount = measureTextLines(text, blockStart, blockEnd)
                    if (currentLines.isNotEmpty() && y + blockLineCount * lineHeight > currentPageHeight()) {
                        pageLineGroups += currentLines
                        pageStarts += pageStart
                        pageStart = blockStart
                        currentLines = mutableListOf()
                        y = 0f
                    }
                }
                var lineStart = blockStart
                while (lineStart < blockEnd) {
                    val firstLine = lineStart == paragraphStart
                    val indentWidth = if (firstLine) {
                        contentPaint.measureText(config.paragraphIndent)
                    } else {
                        0f
                    }
                    val count = contentPaint.breakText(
                        text,
                        lineStart,
                        blockEnd,
                        true,
                        (visibleWidth - indentWidth).coerceAtLeast(1f),
                        null
                    ).coerceAtLeast(1).let { count ->
                        val safeCount = fitBreakCountToLineWidth(
                            text = text,
                            start = lineStart,
                            paragraphEnd = blockEnd,
                            count = count,
                            startX = indentWidth
                        )
                        adjustBreakCountForZhLayout(text, lineStart, blockEnd, safeCount)
                    }
                    val lineEnd = (lineStart + count).coerceAtMost(blockEnd)
                    val lineText = text.substring(lineStart, lineEnd)
                    if (currentLines.isNotEmpty() && y + lineHeight > currentPageHeight()) {
                        // 兜底：超长句子块（超过一页）内部仍按行翻页
                        pageLineGroups += currentLines
                        pageStarts += pageStart
                        pageStart = lineStart
                        currentLines = mutableListOf()
                        y = 0f
                    }
                    val line = createLine(
                        text = lineText,
                        chapter = chapter,
                        paragraphNum = paragraphNum,
                        chapterPosition = lineStart,
                        pagePosition = max(0, lineStart - pageStart),
                        y = y,
                        isParagraphEnd = lineEnd >= segmentEnd,
                        startX = indentWidth
                    )
                    if (config.textFullJustify && !line.isParagraphEnd && lineEnd < blockEnd) {
                        justifyTextLine(line)
                    }
                    currentLines += line
                    y += lineHeight
                    lineStart = lineEnd
                }
                blockStart = blockEnd
            }
        }

        var paragraphStart = 0
        while (paragraphStart <= text.length) {
            val paragraphEnd = text.indexOf('\n', paragraphStart).let {
                if (it < 0) text.length else it
            }
            paragraphNum += 1
            var lineStart = paragraphStart
            if (lineStart == paragraphEnd) {
                y += paragraphSpacing
            }
            // 段落里可能嵌着图片标记（例如 EPUB 中 <p>文字<img>文字</p> 被归一化后
            // 标记与文字同段）。与参考实现 legado 的默认图片样式一致：按标记把段落
            // 切成若干文字段/图片段，图片始终独占一行、按比例缩放，而不是行内小图。
            var segmentStart = paragraphStart
            while (segmentStart < paragraphEnd) {
                val markerIndex = text.indexOf(EBOOK_IMAGE_MARKER, segmentStart)
                if (markerIndex < 0 || markerIndex >= paragraphEnd) {
                    layoutTextSegment(
                        paragraphNum = paragraphNum,
                        paragraphStart = paragraphStart,
                        segmentStart = segmentStart,
                        segmentEnd = paragraphEnd
                    )
                    break
                }
                if (markerIndex > segmentStart) {
                    layoutTextSegment(
                        paragraphNum = paragraphNum,
                        paragraphStart = paragraphStart,
                        segmentStart = segmentStart,
                        segmentEnd = markerIndex
                    )
                }
                val (imageWidth, imageHeight) = imageSize(chapter, markerIndex, currentPageHeight())
                if (currentLines.isNotEmpty() && y + imageHeight > currentPageHeight()) {
                    pageLineGroups += currentLines
                    pageStarts += pageStart
                    pageStart = markerIndex
                    currentLines = mutableListOf()
                    y = 0f
                }
                currentLines += createImageLine(
                    chapter = chapter,
                    chapterPosition = markerIndex,
                    pagePosition = max(0, markerIndex - pageStart),
                    y = y,
                    width = imageWidth,
                    height = imageHeight
                )
                y += imageHeight + paragraphSpacing
                segmentStart = markerIndex + 1
            }
            if (paragraphEnd == text.length) break
            y += paragraphSpacing
            paragraphStart = paragraphEnd + 1
        }
        if (currentLines.isNotEmpty()) {
            pageLineGroups += currentLines
            pageStarts += pageStart
        }

        pageLineGroups.forEachIndexed { index, lines ->
            // 底对齐只对纯文字页生效：图片行的绘制矩形使用 cross 坐标，
            // 而 justifyPageBottom 只平移 lineTop/lineBase/lineBottom，会把文字推进
            // 图片的绘制区域导致文字被图片盖住（吞字）。
            // 首页使用扣除标题预留后的可用高度（此时 pageLineGroups 已非空，
            // currentPageHeight() 已回到整页高度，需用首次计算值）。
            val firstPageHeight = (visibleHeight - firstPageReservePx).coerceAtLeast(1f)
            if (config.textBottomJustify && lines.none { line -> line.columns.any { it is ImageColumn } }) {
                justifyPageBottom(
                    lines,
                    availableHeight = if (index == 0) firstPageHeight else visibleHeight.toFloat()
                )
            }
            val rangeStart = pageStarts.getOrElse(index) { 0 }.coerceIn(0, text.length)
            val rangeEnd = pageStarts.getOrNull(index + 1)
                ?.coerceIn(rangeStart, text.length)
                ?: text.length
            val page = TextPage(
                index = index,
                pageInChapter = index,
                chapterPageCount = pageLineGroups.size,
                chapterIndex = chapter.chapterIndex,
                chapterSize = chapter.chaptersSize,
                charStart = rangeStart,
                charEnd = rangeEnd,
                title = chapter.title,
                text = text.substring(rangeStart, rangeEnd.coerceIn(rangeStart, text.length))
            )
            lines.forEach { line ->
                val rebased = line.copy(
                    pagePosition = line.chapterPosition - page.charStart
                )
                rebased.columns.forEach { column ->
                    when (column) {
                        is TextColumn -> {
                            column.sourceStart -= page.charStart
                            column.sourceEnd -= page.charStart
                            column.rubySourceStart -= page.charStart
                            column.rubySourceEnd -= page.charStart
                        }
                        is ImageColumn -> {
                            column.sourceStart -= page.charStart
                            column.sourceEnd -= page.charStart
                        }
                    }
                }
                page.addLine(rebased)
            }
            page.height = page.lines.maxOfOrNull { it.crossEnd } ?: visibleHeight.toFloat()
            page.width = visibleWidth.toFloat()
            chapter.addPage(page)
        }
        return chapter
    }

    /**
     * 卷页（"第一部分 xxx"等）标题页：标题用卷标题画笔（大一号 + 加粗），
     * 水平居中、垂直居中，正文区其余部分留空——与参考实现 legado 的
     * emptyContent 标题排版（y = (visibleHeight - 标题高) / 2）一致。
     */
    private fun buildVolumeTitlePage(chapter: TextChapter): TextPage {
        val paint = titlePaint
        val metrics = paint.fontMetrics
        val titleLineHeight = (metrics.descent - metrics.ascent).coerceAtLeast(1f)
        val maxWidth = visibleWidth.toFloat().coerceAtLeast(1f)
        val titleLines = mutableListOf<String>()
        var remaining = chapter.title.ifBlank { " " }
        while (remaining.isNotEmpty()) {
            val count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
            titleLines += remaining.substring(0, count)
            remaining = remaining.substring(count)
        }
        val totalHeight = titleLines.size * titleLineHeight
        var y = ((visibleHeight - totalHeight) / 2f).coerceAtLeast(0f)
        val page = TextPage(
            index = 0,
            pageInChapter = 0,
            chapterPageCount = 1,
            chapterIndex = chapter.chapterIndex,
            chapterSize = chapter.chaptersSize,
            title = chapter.title,
            text = chapter.title,
            charStart = 0,
            charEnd = 0
        )
        titleLines.forEach { lineText ->
            val width = paint.measureText(lineText)
            val startX = ((visibleWidth - width) / 2f).coerceAtLeast(0f)
            val line = TextLine(
                text = lineText,
                lineTop = y,
                lineBase = y + titleLineHeight - metrics.descent,
                lineBottom = y + titleLineHeight,
                crossStart = y,
                crossEnd = y + titleLineHeight,
                paragraphNum = 1,
                chapterPosition = 0,
                pagePosition = 0,
                isParagraphEnd = true,
                layoutMode = M9LayoutMode.HORIZONTAL,
                startX = startX,
                isTitle = true
            )
            var x = startX
            lineText.forEach { ch ->
                val w = paint.measureText(ch.toString())
                line.addColumn(
                    TextColumn(
                        start = x,
                        end = x + w,
                        charData = ch.toString(),
                        sourceStart = 0,
                        sourceEnd = 0
                    )
                )
                x += w
            }
            page.addLine(line)
            y += titleLineHeight
        }
        page.height = visibleHeight.toFloat()
        page.width = visibleWidth.toFloat()
        return page
    }

    private fun adjustBreakCountForZhLayout(text: String, start: Int, paragraphEnd: Int, count: Int): Int {
        if (!config.useZhLayout) return count
        if (count <= 1 || start + count >= paragraphEnd) return count
        val nextChar = text[start + count]
        return if (nextChar in noLineStartChars) count - 1 else count
    }

    private fun fitBreakCountToLineWidth(
        text: String,
        start: Int,
        paragraphEnd: Int,
        count: Int,
        startX: Float
    ): Int {
        val maxWidth = visibleWidth - startX
        if (maxWidth <= 1f) return 1
        var safeCount = count.coerceIn(1, paragraphEnd - start)
        while (safeCount > 1 && measuredLineWidth(text, start, start + safeCount) > maxWidth) {
            val previousIndex = text.offsetByCodePoints(start + safeCount, -1)
            safeCount = (previousIndex - start).coerceAtLeast(1)
        }
        return safeCount
    }

    private fun measuredLineWidth(text: String, start: Int, end: Int): Float {
        var width = 0f
        var index = start
        while (index < end) {
            val codePoint = text.codePointAt(index)
            val char = String(Character.toChars(codePoint))
            width += contentPaint.measureText(char) + config.letterSpacingPx
            index += char.length
        }
        return width
    }

    private fun justifyTextLine(line: TextLine) {
        val textColumns = line.columns.filterIsInstance<TextColumn>()
        if (textColumns.size <= 1) return
        val extra = visibleWidth - line.lineEnd
        if (extra <= 0f) return
        val gap = extra / (textColumns.size - 1)
        var offset = 0f
        textColumns.forEachIndexed { index, column ->
            column.start += offset
            column.end += offset
            if (index < textColumns.lastIndex) offset += gap
        }
    }

    /** 按当前行宽模拟断行，返回 [start, end) 需要的行数（句子块翻页预测量） */
    private fun measureTextLines(text: String, start: Int, end: Int): Int {
        var count = 0
        var cursor = start
        while (cursor < end) {
            val n = contentPaint.breakText(text, cursor, end, true, visibleWidth.toFloat(), null)
                .coerceAtLeast(1)
            cursor += n
            count += 1
        }
        return count
    }

    private fun justifyPageBottom(lines: MutableList<TextLine>, availableHeight: Float) {
        if (lines.size <= 1) return
        val lastBottom = lines.last().lineBottom
        val extra = availableHeight - lastBottom
        if (extra <= lineHeight) return
        val gap = (extra / (lines.size - 1)).coerceAtMost(lineHeight * 0.45f)
        var offset = 0f
        lines.forEachIndexed { index, line ->
            line.lineTop += offset
            line.lineBase += offset
            line.lineBottom += offset
            if (index < lines.lastIndex) offset += gap
        }
    }

    private fun createLine(
        text: String,
        chapter: TextChapter,
        paragraphNum: Int,
        chapterPosition: Int,
        pagePosition: Int,
        y: Float,
        isParagraphEnd: Boolean,
        startX: Float
    ): TextLine {
        val line = TextLine(
            text = text,
            lineTop = y,
            lineBase = y + rubyReservePx - fontMetrics.ascent,
            lineBottom = y + lineHeight,
            crossStart = y,
            crossEnd = y + lineHeight,
            paragraphNum = paragraphNum,
            chapterPosition = chapterPosition,
            pagePosition = pagePosition,
            isParagraphEnd = isParagraphEnd,
            layoutMode = M9LayoutMode.HORIZONTAL,
            startX = startX,
            rubyReservePx = rubyReservePx
        )
        var x = startX
        var local = 0
        while (local < text.length) {
            val codePoint = text.codePointAt(local)
            val char = String(Character.toChars(codePoint))
            val sourceStart = chapterPosition + local
            val sourceEnd = sourceStart + char.length
            val advance = if (codePoint == EBOOK_IMAGE_MARKER.code) {
                val image = chapter.images[sourceStart]
                if (image == null) {
                    contentPaint.measureText(char) + config.letterSpacingPx
                } else {
                    // 行内图片：按行高和图片宽高比计算宽度，跟在当前字符位置之后，
                    // 不再整行铺满（铺满会压扁图片并盖住同行前面的文字）。
                    val inlineWidth = inlineImageWidth(
                        image = image,
                        lineHeight = line.height,
                        availableWidth = (visibleWidth - x).coerceAtLeast(1f)
                    )
                    line.addColumn(
                        ImageColumn(
                            start = x,
                            end = (x + inlineWidth).coerceAtMost(visibleWidth.toFloat()),
                            image = image,
                            width = inlineWidth,
                            height = line.height,
                            sourceStart = sourceStart,
                            sourceEnd = sourceEnd
                        )
                    )
                    inlineWidth
                }
            } else {
                val width = contentPaint.measureText(char) + config.letterSpacingPx
                val ruby = rubyByStart[sourceStart]
                val rubyStart = ruby?.absoluteStart ?: sourceStart
                val rubyEnd = ruby?.absoluteEnd ?: sourceEnd
                line.addColumn(
                    TextColumn(
                        start = x,
                        end = x + width,
                        charData = char,
                        sourceStart = sourceStart,
                        sourceEnd = sourceEnd,
                        rubyText = ruby?.text,
                        rubySourceStart = rubyStart,
                        rubySourceEnd = rubyEnd,
                        rubySpan = ruby?.span
                    )
                )
                width
            }
            x += advance
            local += char.length
        }
        return line
    }

    private fun createImageLine(
        chapter: TextChapter,
        chapterPosition: Int,
        pagePosition: Int,
        y: Float,
        width: Float,
        height: Float
    ): TextLine {
        val imageStart = ((visibleWidth - width) / 2f).coerceAtLeast(0f)
        val imageEnd = (imageStart + width).coerceAtMost(visibleWidth.toFloat())
        val line = TextLine(
            text = EBOOK_IMAGE_MARKER.toString(),
            lineTop = y,
            lineBase = y + height,
            lineBottom = y + height,
            crossStart = y,
            crossEnd = y + height,
            paragraphNum = 0,
            chapterPosition = chapterPosition,
            pagePosition = pagePosition,
            isParagraphEnd = true,
            layoutMode = M9LayoutMode.HORIZONTAL,
            startX = 0f
        )
        chapter.images[chapterPosition]?.let { image ->
            line.addColumn(
                ImageColumn(
                    start = imageStart,
                    end = imageEnd,
                    image = image,
                    width = width,
                    height = height,
                    sourceStart = chapterPosition,
                    sourceEnd = chapterPosition + 1
                )
            )
        }
        return line
    }

    /**
     * 独立段落图片的显示尺寸：按原图比例缩放到铺满可用区域（允许放大，见
     * [fitReaderStandaloneImageSize]）。返回 (width, height)。
     * [availableHeight] 为当前页可用高度（首页需扣除章节标题预留）。
     */
    private fun imageSize(
        chapter: TextChapter,
        chapterPosition: Int,
        availableHeight: Float
    ): Pair<Float, Float> {
        val image = chapter.images[chapterPosition] ?: return lineHeight * 4f to visibleWidth.toFloat()
        val bounds = image.readBytes()?.let(::decodeBitmapBounds)
        val sourceWidth = bounds?.width?.toFloat()?.takeIf { it > 0f } ?: return lineHeight * 4f to visibleWidth.toFloat()
        val sourceHeight = bounds?.height?.toFloat()?.takeIf { it > 0f } ?: return lineHeight * 4f to visibleWidth.toFloat()
        return fitReaderStandaloneImageSize(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            maxWidth = visibleWidth.toFloat(),
            maxHeight = availableHeight
        )
    }

    /**
     * 行内图片宽度：以行高为基准按图片宽高比缩放，保证不变形，也不超出剩余行宽。
     */
    private fun inlineImageWidth(image: EbookImageRef, lineHeight: Float, availableWidth: Float): Float {
        val bounds = image.readBytes()?.let(::decodeBitmapBounds)
        val sourceWidth = bounds?.width?.toFloat()?.takeIf { it > 0f }
        val sourceHeight = bounds?.height?.toFloat()?.takeIf { it > 0f }
        if (sourceWidth == null || sourceHeight == null) return lineHeight
        return (lineHeight * sourceWidth / sourceHeight)
            .coerceAtLeast(lineHeight)
            .coerceAtMost(availableWidth)
    }

    private companion object {
        private val noLineStartChars = setOf(
            '、', '。', '，', '．', '：', '；', '！', '？',
            '）', ')', ']', '】', '}', '』', '」', '》',
            '…', '—', '～'
        )
    }
}
