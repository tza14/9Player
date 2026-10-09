package moe.tekuza.m9player.legado.reader.provider

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import moe.tekuza.m9player.EBOOK_IMAGE_MARKER
import moe.tekuza.m9player.EbookImageRef
import moe.tekuza.m9player.VerticalTextGlyphEngine
import moe.tekuza.m9player.asciiTextRunEnd
import moe.tekuza.m9player.decodeBitmapBounds
import moe.tekuza.m9player.isAsciiRunSpaceChar
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.M9ReadBookConfig
import moe.tekuza.m9player.legado.reader.applyM9TextWeight
import moe.tekuza.m9player.legado.reader.entities.ImageColumn
import moe.tekuza.m9player.legado.reader.entities.RubyLayoutEngine
import moe.tekuza.m9player.legado.reader.entities.TextChapter
import moe.tekuza.m9player.legado.reader.entities.TextColumn
import moe.tekuza.m9player.legado.reader.entities.TextLine
import moe.tekuza.m9player.legado.reader.entities.TextPage
import kotlin.math.ceil
import kotlin.math.max

internal class VerticalTextChapterLayout(
    private val config: M9ReadBookConfig,
    private val visibleWidth: Int,
    private val visibleHeight: Int,
    private val firstPageReservePx: Float = 0f
) {
    companion object {
        /**
         * 竖排章首标题要占的**宽度**（= 列数 × 列宽）。
         *
         * 为什么不能用横排那套"标题高度"：竖排的标题是一列（或多列）竖着的字，吃掉的是**宽度**。
         *
         * 列数与每列字数**必须和绘制侧同源**（`ContentTextView.drawVerticalBodyTitle`）：
         * - 字号 = [M9ReadBookConfig.bodyTitleSizePx]，与绘制侧 `resolveBodyTitleTextSizePx` 的
         *   非卷页分支**同一个值**（宿主 `contentTextSizeSp + bodyTitleSizeAddSp`）。
         *   这份预留只服务**章首标题**（`chapterFirstPageReservePx` 对卷页返回 0、卷页标题也不占列），
         *   所以不能再用 `textSizePx * READER_TITLE_SCALE`（那是卷页标题口径）。
         * - 步进 = `descent - ascent`，与绘制侧的 `step` 同一个定义，**不含 letterSpacing**
         *  （绘制侧下一个字就是往下挪这么多；这里多算一份字距会让每列少放一个字 ⇒ 多留一列
         *   ⇒ 正文右缘左移 ⇒ 长标题提前被省略）。
         * - 列长 = 调用方给的可用列长，调用方（`TextPageFactory`）已经从正文区高度里扣掉了
         *   标题上留白 `bodyTitleTopSpacingPx`，与绘制侧 `columnTop = 上留白`（已平移坐标系）对齐。
         * - 列数走 [VerticalTitleColumnPlan]，token 规则走 [verticalTitleTokens]，与绘制侧同一份。
         */
        fun titleColumnReserve(config: M9ReadBookConfig, title: String, columnLengthPx: Float): Int {
            if (title.isEmpty() || columnLengthPx <= 0f) return 0
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = config.bodyTitleSizePx
                typeface = Typeface.create(config.typeface ?: Typeface.DEFAULT, Typeface.BOLD)
            }
            val metrics = paint.fontMetrics
            val glyphHeight = (metrics.descent - metrics.ascent).coerceAtLeast(1f)
            // 列宽必须与排版正文用的**同一个来源**（VerticalTextGlyphEngine.estimateCellWidth），
            // 否则预留与实际列宽会差一点点，长标题就会多占/少占一列。
            val glyphWidth = VerticalTextGlyphEngine.estimateCellWidth(paint)
            val columnWidth = (glyphWidth + config.lineSpacingPx).coerceAtLeast(1f)
            val perColumn = VerticalTitleColumnPlan.rowsPerColumn(columnLengthPx, glyphHeight)
            val tokens = verticalTitleTokens(title, glyphWidth) { paint.measureText(it) }
            val columns = VerticalTitleColumnPlan.columnCount(tokens, perColumn).coerceAtLeast(1)
            return (columns * columnWidth).toInt().coerceAtLeast(1)
        }
    }

    private val contentPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = config.textSizePx
        color = config.textColor
        applyM9TextWeight(config.textWeight, config.typeface)
    }
    private val fontMetrics = contentPaint.fontMetrics
    private val glyphWidth = VerticalTextGlyphEngine.estimateCellWidth(contentPaint)
    private val baseColumnWidth = (glyphWidth + config.lineSpacingPx).coerceAtLeast(1f)
    private val glyphHeight = (fontMetrics.descent - fontMetrics.ascent + config.letterSpacingPx).coerceAtLeast(1f)
    private val paragraphSpacing = (config.paragraphSpacingPx * 0.35f).coerceAtLeast(0f)
    private var columnWidth = baseColumnWidth
    private var rubyReservePx = 0f
    private var rubyByStart: Map<Int, RubyPlacement> = emptyMap()

    fun layout(chapter: TextChapter): TextChapter {
        rubyReservePx = if (chapter.rubySpans.isNotEmpty()) {
            (config.textSizePx * RubyLayoutEngine.RESERVE_RATIO).coerceAtLeast(8f)
        } else {
            0f
        }
        columnWidth = baseColumnWidth + rubyReservePx
        rubyByStart = buildRubyPlacements(chapter.rubySpans)
        val text = chapter.text
        if (text.isBlank()) {
            // 卷页（"第一部分 xxx"等）：标题在正文区垂直居中的单栏显示，
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

        val pageColumns = mutableListOf<MutableList<TextLine>>()
        val pageStarts = mutableListOf<Int>()
        var currentColumns = mutableListOf<TextLine>()
        var pageStart = 0
        // 章首标题由正文视图**自己绘制在最右几列**（竖排 ✓）⇒ 预留吃掉的是**宽度**：
        // 首页的列游标整体左移 firstPageReservePx，预算（列长）不变。原来把它当高度扣除 ⇒ 首列压标题。
        var x = (visibleWidth - columnWidth - firstPageReservePx).coerceAtLeast(0f)
        var paragraphNum = 0

        // 章首标题现在由正文视图**自己绘制在同一块区域里**（不再是占高度的独立视图）⇒ 与横排同口径：
        // 预留靠"第一列整体下移"体现，预算不再重复扣除。否则首列会压在标题上（真机已复现）。
        fun currentPageHeight(): Float = visibleHeight.toFloat()

        /**
         * 竖排句子块所需列数：模拟 buildVerticalLineEnd 逐列切行（含段首缩进），
         * 用于块级分页判断——块完整放下所需列数与布局行循环一致。
         */
        fun measureVerticalBlockColumns(
            start: Int,
            end: Int,
            firstLineStartY: Float
        ): Int {
            var cursor = start
            var columns = 0
            var y = firstLineStartY
            val pageH = currentPageHeight()
            while (cursor < end) {
                if (y > 0f) {
                    // 与行循环一致：缩进行放不下则换列
                    if (y + glyphHeight > pageH) {
                        y = 0f
                        continue
                    }
                    val firstToken = nextToken(text, cursor, end)
                    if (firstToken.length > 0 && y + tokenHeight(firstToken) > pageH) {
                        y = 0f
                        continue
                    }
                }
                val lineEnd = buildVerticalLineEnd(
                    text,
                    cursor,
                    end,
                    (pageH - y).coerceAtLeast(glyphHeight)
                )
                columns += 1
                if (lineEnd <= cursor) break
                cursor = lineEnd
                while (
                    cursor < end &&
                    isAsciiRunSpaceChar(text[cursor]) &&
                    text.getOrNull(cursor + 1)?.let(VerticalTextGlyphEngine::isAsciiWordChar) == true
                ) {
                    cursor += 1
                }
                y = 0f
            }
            return columns.coerceAtLeast(1)
        }

        /**
         * 填充一个不含图片标记的竖排文字段 [segmentStart, segmentEnd)。
         * 缩进只给段首第一行；段末行视为段落结尾。
         * NEXT_PAGE 句尾处理：按 cue 句子起点切块——一个句子块整体放入页面，
         * 放不下则整块推到下一页（列）；句子块内部正常换列（列可断在句子中间，
         * 但句子不跨页）。
         */
        fun layoutVerticalTextSegment(
            paragraphNum: Int,
            paragraphStart: Int,
            segmentStart: Int,
            segmentEnd: Int
        ) {
            val sentenceStarts = chapter.sentenceStarts
            fun indentYFor(lineStart: Int): Float {
                return if (lineStart == paragraphStart && config.paragraphIndent.isNotEmpty()) {
                    glyphHeight * config.paragraphIndent.length
                } else {
                    0f
                }
            }

            /** 章首第一列要从标题预留**左侧**开始（列从右往左排；标题自己占最右的那几列）。 */
            fun firstColumnStartY(lineStart: Int): Float = indentYFor(lineStart)
            var blockStart = segmentStart
            while (blockStart < segmentEnd) {
                // 「句子不跨页」开启时才切句子块（块 = 一句）：整句必须放进同一页，放不下就整块
                // 推到下一页；竖排每行占一列，超长块由行内 x<0 兜底翻页，块仍可跨页。
                // 关闭时 sentenceStarts 为空，块退化为整段 —— 此时不能再做"整块入页"判断，
                // 否则长段会把整页切掉、在页左侧留下成片空白；应回到贪婪填满（列排满才翻页）。
                val usesSentenceBlocks = sentenceStarts.isNotEmpty()
                val blockEnd = if (usesSentenceBlocks) {
                    sentenceStarts.filter { it > blockStart && it < segmentEnd }.minOrNull() ?: segmentEnd
                } else {
                    segmentEnd
                }
                if (usesSentenceBlocks) {
                    val blockColumns = measureVerticalBlockColumns(blockStart, blockEnd, firstColumnStartY(blockStart))
                    val remainingColumns = (x / columnWidth).toInt() + 1
                    if (currentColumns.isNotEmpty() && blockColumns > remainingColumns) {
                        pageColumns += currentColumns
                        pageStarts += pageStart
                        pageStart = blockStart
                        currentColumns = mutableListOf()
                        x = (visibleWidth - columnWidth).coerceAtLeast(0f)
                    }
                }
                var lineStart = blockStart
                var limit = blockEnd
                while (lineStart < limit) {
                    var y = firstColumnStartY(lineStart)
                    if (y + glyphHeight > currentPageHeight()) {
                        x -= columnWidth
                        y = 0f
                    }
                    if (currentColumns.isNotEmpty() && x < 0f) {
                        pageColumns += currentColumns
                        pageStarts += pageStart
                        pageStart = lineStart
                        currentColumns = mutableListOf()
                        x = (visibleWidth - columnWidth).coerceAtLeast(0f)
                        y = indentYFor(lineStart)
                    }
                    val firstToken = nextToken(text, lineStart, limit)
                    if (y + tokenHeight(firstToken) > currentPageHeight() && y > 0f) {
                        x -= columnWidth
                        y = 0f
                    }
                    if (currentColumns.isNotEmpty() && x < 0f) {
                        pageColumns += currentColumns
                        pageStarts += pageStart
                        pageStart = lineStart
                        currentColumns = mutableListOf()
                        x = (visibleWidth - columnWidth).coerceAtLeast(0f)
                        y = 0f
                    }
                    val columnSpace = currentPageHeight() - y
                    var lineEnd = buildVerticalLineEnd(text, lineStart, limit, columnSpace)
                    // 一列排完后若还有余量，就继续并入后面的**整句**（保持"一列一行"）：
                    // 否则"句子不跨页"会变成每句独占一列，整页大半空白。
                    // 但当前列是这一页的最后一列时，宁可让整句去下一页，也不把句子切开。
                    val lastColumnOfPage = x - columnWidth < 0f
                    while (lineEnd >= limit && limit < segmentEnd) {
                        val nextLimit = sentenceStarts.filter { it > limit && it < segmentEnd }
                            .minOrNull() ?: segmentEnd
                        val trialEnd = buildVerticalLineEnd(text, lineStart, nextLimit, columnSpace)
                        if (trialEnd <= lineEnd) break
                        if (lastColumnOfPage && trialEnd < nextLimit) break
                        lineEnd = trialEnd
                        limit = nextLimit
                    }
                    val lineText = text.substring(lineStart, lineEnd)
                    val line = createVerticalLine(
                        text = lineText,
                        chapter = chapter,
                        paragraphNum = paragraphNum,
                        chapterPosition = lineStart,
                        pagePosition = max(0, lineStart - pageStart),
                        x = x,
                        startY = y,
                        isParagraphEnd = lineEnd >= segmentEnd
                    )
                    currentColumns += line
                    x -= columnWidth
                    lineStart = lineEnd
                    while (
                        lineStart < limit &&
                        isAsciiRunSpaceChar(text[lineStart]) &&
                        text.getOrNull(lineStart + 1)?.let(VerticalTextGlyphEngine::isAsciiWordChar) == true
                    ) {
                        lineStart += 1
                    }
                }
                // 内层可能已经顺着并入的整句排到 blockEnd 之后，这里要跳到实际排到的位置
                blockStart = lineStart.coerceAtLeast(blockEnd)
            }
        }

        var paragraphStart = 0
        while (paragraphStart <= text.length) {
            val paragraphEnd = text.indexOf('\n', paragraphStart).let { if (it < 0) text.length else it }
            paragraphNum += 1
            // 与横排一致：段落内嵌的图片标记也切成独立图片栏（参考实现 legado 的
            // 默认图片样式即把段落按图片切段），图片独占一栏、按比例缩放。
            var segmentStart = paragraphStart
            while (segmentStart < paragraphEnd) {
                val markerIndex = text.indexOf(EBOOK_IMAGE_MARKER, segmentStart)
                if (markerIndex < 0 || markerIndex >= paragraphEnd) {
                    layoutVerticalTextSegment(
                        paragraphNum = paragraphNum,
                        paragraphStart = paragraphStart,
                        segmentStart = segmentStart,
                        segmentEnd = paragraphEnd
                    )
                    break
                }
                if (markerIndex > segmentStart) {
                    layoutVerticalTextSegment(
                        paragraphNum = paragraphNum,
                        paragraphStart = paragraphStart,
                        segmentStart = segmentStart,
                        segmentEnd = markerIndex
                    )
                }
                val imageSize = imageBlockSize(chapter, markerIndex, currentPageHeight())
                val imageLeft = x + columnWidth - imageSize.width
                if (currentColumns.isNotEmpty() && imageLeft < 0f) {
                    pageColumns += currentColumns
                    pageStarts += pageStart
                    pageStart = markerIndex
                    currentColumns = mutableListOf()
                    x = (visibleWidth - columnWidth).coerceAtLeast(0f)
                }
                currentColumns += createImageColumnLine(
                    chapter = chapter,
                    chapterPosition = markerIndex,
                    pagePosition = max(0, markerIndex - pageStart),
                    x = x,
                    width = imageSize.width,
                    height = imageSize.height
                )
                x = x + columnWidth - imageSize.width - columnWidth - paragraphSpacing
                segmentStart = markerIndex + 1
            }
            if (paragraphEnd == text.length) break
            x -= paragraphSpacing
            paragraphStart = paragraphEnd + 1
        }
        if (currentColumns.isNotEmpty()) {
            pageColumns += currentColumns
            pageStarts += pageStart
        }

        pageColumns.forEachIndexed { index, columns ->
            val rangeStart = pageStarts.getOrElse(index) { 0 }.coerceIn(0, text.length)
            val rangeEnd = pageStarts.getOrNull(index + 1)
                ?.coerceIn(rangeStart, text.length)
                ?: text.length
            val page = TextPage(
                index = index,
                pageInChapter = index,
                chapterPageCount = pageColumns.size,
                chapterIndex = chapter.chapterIndex,
                chapterSize = chapter.chaptersSize,
                charStart = rangeStart,
                charEnd = rangeEnd,
                title = chapter.title,
                text = text.substring(rangeStart, rangeEnd.coerceIn(rangeStart, text.length))
            )
            columns.forEach { line ->
                val rebased = line.copy(pagePosition = line.chapterPosition - page.charStart)
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
            page.height = visibleHeight.toFloat()
            page.width = page.lines.minOfOrNull { it.lineTop }
                ?.let { minLeft -> (visibleWidth - minLeft).coerceAtLeast(columnWidth) }
                ?: visibleWidth.toFloat()
            chapter.addPage(page)
        }
        return chapter
    }

    /**
     * 卷页（"第一部分 xxx"等）标题页（竖排）：**只标记"这是一张标题页"**（title + isVolume），
     * 一行都不生成 —— 标题由绘制端 `ContentTextView` 的标题渲染器画（走同一个竖排引擎、
     * 居中对齐，见 `drawVerticalBodyTitle`）。
     *
     * 旧实现还在这里逐字排了一行 `isTitle` 行、自己算 x/y 居中：那套算术早就没人读了
     * （绘制端按 `page.isVolume` 直接跳过所有行），留着就是"第二套近似"——字号/字距一改，
     * 它和真正画出来的标题就对不上了。已删。
     */
    private fun buildVolumeTitlePage(chapter: TextChapter): TextPage {
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
        page.height = visibleHeight.toFloat()
        page.width = visibleWidth.toFloat()
        return page
    }

    private fun createVerticalLine(
        text: String,
        chapter: TextChapter,
        paragraphNum: Int,
        chapterPosition: Int,
        pagePosition: Int,
        x: Float,
        startY: Float,
        isParagraphEnd: Boolean
    ): TextLine {
        val line = TextLine(
            text = text,
            lineTop = x,
            lineBase = x + glyphWidth / 2f,
            lineBottom = x + columnWidth,
            crossStart = startY,
            crossEnd = startY + text.length.coerceAtLeast(1) * glyphHeight,
            paragraphNum = paragraphNum,
            chapterPosition = chapterPosition,
            pagePosition = pagePosition,
            isParagraphEnd = isParagraphEnd,
            layoutMode = M9LayoutMode.VERTICAL,
            rubyReservePx = rubyReservePx
        )
        var y = startY
        var local = 0
        while (local < text.length) {
            val sourceStart = chapterPosition + local
            val token = nextToken(text, local, text.length)
            val tokenText = token.text
            val sourceEnd = sourceStart + token.length
            if (tokenText.length == 1 && tokenText.first().code == EBOOK_IMAGE_MARKER.code) {
                chapter.images[sourceStart]?.let { image ->
                    // 行内图片：以栏宽为基准按图片宽高比计算纵向高度，避免被压成
                    // 固定 4 倍字高的窄条（既变形又会盖住栏内前后文字）。
                    val availableHeight = (visibleHeight - y).coerceAtLeast(glyphHeight)
                    val blockHeight = inlineImageBlockHeight(
                        image = image,
                        columnWidth = line.width,
                        availableHeight = availableHeight
                    )
                    line.addColumn(
                        ImageColumn(
                            start = y,
                            end = (y + blockHeight).coerceAtMost(visibleHeight.toFloat()),
                            image = image,
                            width = line.width,
                            height = blockHeight,
                            sourceStart = sourceStart,
                            sourceEnd = sourceEnd
                        )
                    )
                    y += blockHeight
                }
            } else {
                val tokenHeight = if (token.measuredAdvance) {
                    // 欧文は比例送り：実測幅をそのまま使う（全角 1 字分に丸めない）
                    token.heightPx.coerceAtLeast(1f)
                } else {
                    glyphHeight * token.heightUnits.coerceAtLeast(1)
                }
                val ruby = rubyByStart[sourceStart]
                val rubyStart = ruby?.absoluteStart ?: sourceStart
                val rubyEnd = ruby?.absoluteEnd ?: sourceEnd
                line.addColumn(
                    TextColumn(
                        start = y,
                        end = y + tokenHeight,
                        charData = tokenText,
                        sourceStart = sourceStart,
                        sourceEnd = sourceEnd,
                        rubyText = ruby?.text,
                        rubySourceStart = rubyStart,
                        rubySourceEnd = rubyEnd,
                        rubySpan = ruby?.span
                    )
                )
                y += tokenHeight
            }
            local += token.length
        }
        line.crossEnd = y
        return line
    }

    private fun createImageColumnLine(
        chapter: TextChapter,
        chapterPosition: Int,
        pagePosition: Int,
        x: Float,
        width: Float,
        height: Float
    ): TextLine {
        val right = (x + columnWidth).coerceAtMost(visibleWidth.toFloat())
        val left = (right - width).coerceAtLeast(0f)
        val line = TextLine(
            text = EBOOK_IMAGE_MARKER.toString(),
            lineTop = left,
            lineBase = left + (right - left) / 2f,
            lineBottom = right,
            crossStart = 0f,
            crossEnd = height,
            chapterPosition = chapterPosition,
            pagePosition = pagePosition,
            isParagraphEnd = true,
            layoutMode = M9LayoutMode.VERTICAL
        )
        chapter.images[chapterPosition]?.let { image ->
            line.addColumn(
                ImageColumn(
                    start = 0f,
                    end = height,
                    image = image,
                    width = line.width,
                    height = height,
                    sourceStart = chapterPosition,
                    sourceEnd = chapterPosition + 1
                )
            )
        }
        return line
    }

    /**
     * 竖排独立段落图片的显示尺寸：按原图比例缩放到铺满可用区域（允许放大，见
     * [fitReaderStandaloneImageSize]），保证绘制矩形与图片同比例。
     * [availableHeight] 为当前页可用高度（首页需扣除章节标题预留）。
     */
    private fun imageBlockSize(
        chapter: TextChapter,
        chapterPosition: Int,
        availableHeight: Float
    ): ImageBlockSize {
        val bounds = chapter.images[chapterPosition]?.readBytes()?.let(::decodeBitmapBounds)
        val sourceWidth = bounds?.width?.toFloat()?.takeIf { it > 0f }
        val sourceHeight = bounds?.height?.toFloat()?.takeIf { it > 0f }
        if (sourceWidth == null || sourceHeight == null) {
            return ImageBlockSize(width = columnWidth * 4f, height = glyphHeight * 6f)
        }
        val (width, height) = fitReaderStandaloneImageSize(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            maxWidth = visibleWidth.toFloat(),
            maxHeight = availableHeight
        )
        return ImageBlockSize(width = width, height = height)
    }

    /**
     * 竖排行内图片的纵向高度：以栏宽为基准按图片宽高比缩放，保证不变形。
     */
    private fun inlineImageBlockHeight(image: EbookImageRef, columnWidth: Float, availableHeight: Float): Float {
        val bounds = image.readBytes()?.let(::decodeBitmapBounds)
        val sourceWidth = bounds?.width?.toFloat()?.takeIf { it > 0f }
        val sourceHeight = bounds?.height?.toFloat()?.takeIf { it > 0f }
        if (sourceWidth == null || sourceHeight == null) return glyphHeight * 4f
        val height = columnWidth * sourceHeight / sourceWidth
        return height.coerceAtLeast(glyphHeight).coerceAtMost(availableHeight)
    }

    /**
     * 竖排取词。欧文按 JLREQ §3.2.6 处理：一続きの欧文は横倒しのまま連続して並べ、
     * 語間は欧文間隔（cl-26）だけを空け、語の途中では折らない。
     * そこで「英数字を含む ASCII の連続区間」を 1 つの token（= 1 本の横倒しラン）にまとめる。
     * まとめないと語ごとに 1 列を使い、さらに語間の空白が 1 字分のセルを占めて
     * 語間が全角に開いてしまう（実測で語間 76px ≒ 全角 1 字、正しくは約 1/4 字）。
     * 列に収まらない場合は [fitLatinRunLength] が空白の位置で折る（語は分割されない）。
     */
    private fun nextToken(text: String, start: Int, end: Int): VerticalToken {
        val runEnd = asciiTextRunEnd(text, start, end)
        if (runEnd > start) {
            val runText = text.substring(start, runEnd)
            return measuredAdvanceToken(runText, length = runEnd - start, heightPx = latinRunHeight(runText))
        }
        val shared = VerticalTextGlyphEngine.nextVerticalTextToken(text, start, end)
        if (shared.sourceEndExclusive <= start) return VerticalToken("", 0, 0, 0f, false)
        // 和文に挟まれた欧文語間の空白も比例幅にする（全角 1 字分は空けない）
        if (shared.text.all(::isAsciiRunSpaceChar)) {
            return measuredAdvanceToken(
                text = shared.text,
                length = shared.sourceEndExclusive - start,
                heightPx = asciiSpaceAdvance()
            )
        }
        return VerticalToken(
            text = shared.text,
            length = shared.sourceEndExclusive - start,
            heightUnits = 1,
            heightPx = glyphHeight,
            measuredAdvance = false
        )
    }

    private fun measuredAdvanceToken(text: String, length: Int, heightPx: Float): VerticalToken {
        val height = heightPx.coerceAtLeast(1f)
        val units = ceil((height / glyphHeight).coerceAtLeast(1f).toDouble()).toInt().coerceAtLeast(1)
        return VerticalToken(
            text = text,
            length = length,
            heightUnits = units,
            heightPx = height,
            measuredAdvance = true
        )
    }

    /** 欧文の語間（cl-26）の送り量：プロポーショナルな空白幅。 */
    private fun asciiSpaceAdvance(): Float {
        return (contentPaint.measureText(" ") + config.letterSpacingPx).coerceAtLeast(1f)
    }

    private fun latinRunHeight(text: String): Float {
        return (contentPaint.measureText(text) + config.letterSpacingPx + config.textSizePx * 0.22f)
            .coerceAtLeast(glyphHeight)
    }

    private fun tokenHeight(token: VerticalToken): Float {
        return if (token.measuredAdvance) {
            token.heightPx
        } else {
            glyphHeight * token.heightUnits.coerceAtLeast(1)
        }
    }

    private fun buildVerticalLineEnd(text: String, start: Int, paragraphEnd: Int, availableHeight: Float): Int {
        val maxHeight = availableHeight.coerceAtLeast(glyphHeight)
        var cursor = start
        var usedHeight = 0f
        var lastEnd = start
        val tokenStarts = ArrayList<Int>()
        while (cursor < paragraphEnd) {
            val token = nextToken(text, cursor, paragraphEnd)
            if (token.length <= 0) break
            val height = tokenHeight(token)
            if (usedHeight > 0f && usedHeight + height > maxHeight) {
                break
            }
            if (usedHeight == 0f && height > maxHeight) {
                val count = if (token.measuredAdvance) {
                    fitLatinRunLength(token.text, maxHeight)
                } else {
                    1
                }
                return (cursor + count).coerceIn(cursor + 1, paragraphEnd)
            }
            tokenStarts += cursor
            cursor += token.length
            usedHeight += height
            lastEnd = cursor
        }
        var adjustedEnd = lastEnd
        var acceptedCount = tokenStarts.size
        while (acceptedCount > 1) {
            val endsWithForbidden = adjustedEnd > start &&
                VerticalTextGlyphEngine.isNoColumnEnd(text[adjustedEnd - 1])
            val nextStartsForbidden = adjustedEnd < paragraphEnd &&
                VerticalTextGlyphEngine.isNoColumnStart(text[adjustedEnd])
            if (!endsWithForbidden && !nextStartsForbidden) break
            adjustedEnd = tokenStarts[acceptedCount - 1]
            acceptedCount -= 1
        }
        return if (adjustedEnd > start) adjustedEnd else (start + 1).coerceAtMost(paragraphEnd)
    }

    private fun fitLatinRunLength(text: String, maxHeightPx: Float): Int {
        val width = maxHeightPx.coerceAtLeast(glyphHeight)
        val count = contentPaint.breakText(text, true, width, null).coerceAtLeast(1)
        if (count >= text.length) return count
        val lastSpaceBeforeBreak = text
            .substring(0, count)
            .indexOfLast(::isAsciiRunSpaceChar)
        if (lastSpaceBeforeBreak >= 0) {
            return (lastSpaceBeforeBreak + 1).coerceAtLeast(1)
        }
        return count.coerceIn(1, text.length)
    }

    private data class VerticalToken(
        val text: String,
        val length: Int,
        val heightUnits: Int,
        val heightPx: Float,
        /** true なら送り量は実測値（欧文の横倒しラン・欧文語間の空白）、false なら全角 1 字分。 */
        val measuredAdvance: Boolean
    )

    private data class ImageBlockSize(
        val width: Float,
        val height: Float
    )
}
