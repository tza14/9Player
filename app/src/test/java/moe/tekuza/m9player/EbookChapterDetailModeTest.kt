package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "章节详细度"设置：目录为准（默认）/ 目录 + 正文标题（显示更多）。
 *
 * 形状全部取自真书《心理學經典作品集》（third_party/123）：那一本里同一个
 * `<h3 class="kindle-cn-heading2">` 章、卷、小节三种都用（`第一章　生活的意義` /
 * `第一卷　群體心理` / `1.趨向感受的來源`），class 完全一样 —— 所以判据必须是标题**文本**的形状，
 * 不能是 h1-h3 的级别。只按级别切会把 45 个 `1.xxx` 编号小节灌进目录。
 */
class EbookChapterDetailModeTest {

    // ---------------- 标题分类器 ----------------

    @Test
    fun classifyEpubHeading_readsChapterTitles() {
        assertEquals(EpubHeadingKind.CHAPTER, classifyEpubHeading("第一章　生活的意義"))
        assertEquals(EpubHeadingKind.CHAPTER, classifyEpubHeading("第七章 學校的影響"))
        assertEquals(EpubHeadingKind.CHAPTER, classifyEpubHeading("第十二章　愛情與婚姻"))
        assertEquals(EpubHeadingKind.CHAPTER, classifyEpubHeading("序"))
        assertEquals(EpubHeadingKind.CHAPTER, classifyEpubHeading("譯者序"))
        assertEquals(EpubHeadingKind.CHAPTER, classifyEpubHeading("後記"))
    }

    @Test
    fun classifyEpubHeading_readsVolumeTitles() {
        assertEquals(EpubHeadingKind.VOLUME, classifyEpubHeading("第一卷　群體心理"))
        assertEquals(EpubHeadingKind.VOLUME, classifyEpubHeading("第三部分 梦的产生和原因"))
        assertEquals(EpubHeadingKind.VOLUME, classifyEpubHeading("BOOK I \u00a0 THE MIND OF CROWDS"))
        assertEquals(EpubHeadingKind.VOLUME, classifyEpubHeading("Book Two"))
    }

    @Test
    fun classifyEpubHeading_rejectsNumberedSectionsAndPlainText() {
        // part0003_split_00N 里的小节：和章标题同一个标签、同一个 class，只有文本形状不同
        assertEquals(EpubHeadingKind.OTHER, classifyEpubHeading("1.趨向感受的來源"))
        assertEquals(EpubHeadingKind.OTHER, classifyEpubHeading("2、行為下的自我變化"))
        assertEquals(EpubHeadingKind.OTHER, classifyEpubHeading("青春期的特點"))
        assertEquals(EpubHeadingKind.OTHER, classifyEpubHeading(""))
        assertEquals(EpubHeadingKind.OTHER, classifyEpubHeading("   "))
    }

    // ---------------- "目录 + 正文标题"档：按标题形状切 ----------------

    /** part0020_split_000.html 的真实形状：连排章级 h3，目录里一条条目都没有 */
    private val squeezedVolumeHtml = """
        <body><h3 class="kindle-cn-heading2">第一章　生活的意義</h3>
        <p class="calibre4">第一章正文。</p>
        <h3 class="kindle-cn-heading2">第二章　心靈和肉體</h3>
        <p class="calibre4">第二章正文。</p>
        <h3 class="kindle-cn-heading2">第三章　自卑感與優越感</h3>
        <p class="calibre4">第三章正文。</p>
        </body>
    """.trimIndent()

    @Test
    fun epubHtmlSegmentsWithHeadings_splitsTheSqueezedChapters() {
        val segments = epubHtmlSegmentsWithHeadings(squeezedVolumeHtml, emptyList(), emptySet())

        // 首段（第一个标题之前，只有标签、没有正文）+ 三章
        assertEquals(4, segments.size)
        assertEquals(
            listOf(null, "第一章 生活的意義", "第二章 心靈和肉體", "第三章 自卑感與優越感"),
            segments.map { it.heading?.title }
        )
        assertNull(segments[0].heading)
        // 每段的正文归自己、不串段
        val parts = segments.map { squeezedVolumeHtml.substring(it.start, it.end) }
        assertFalse(parts[0].contains("生活的意義"))
        assertTrue(parts[1].contains("第一章正文"))
        assertFalse(parts[1].contains("第二章正文"))
        assertTrue(parts[2].contains("第二章正文"))
        assertTrue(parts[3].contains("第三章正文"))
    }

    @Test
    fun epubHtmlSegmentsWithHeadings_doesNotSplitNumberedSections() {
        val html = """
            <body><h2 class="kindle-cn-heading1">第二章　激發良好自我意識的行為</h2>
            <h3 class="kindle-cn-heading2">1.良好自我感受的來源</h3><p>甲。</p>
            <h3 class="kindle-cn-heading2">2.不同自我的競爭與衝突</h3><p>乙。</p>
            </body>
        """.trimIndent()

        // 整文件条目已经命名了这个 h2 ⇒ 不再照着标题切一刀；两个 h3 是编号小节 ⇒ 不认。
        // 一条切点都没有时返回空，调用方退回"整文件一章" —— 与加这一档之前完全一致。
        assertEquals(
            emptyList<EpubHtmlSegment>(),
            epubHtmlSegmentsWithHeadings(html, emptyList(), setOf("第二章 激發良好自我意識的行為"))
        )
    }

    /** 《苏珊·福沃德心理学经典作品集》：`第一部分 X` 与它的 `第一章 Y` 共用一个锚点 */
    private val sharedAnchorHtml = """
        <body><h2 class="first">第一部分 疯狂的控制型关系</h2><h3 id="sigil_toc_id_1">第一章 从天而降的真命天子</h3><p>一见钟情是非常美好的爱情体验。</p><h3 id="sigil_toc_id_57">第二章 当蜜月结束时</h3><p>蜜月总会结束。</p></body>
    """.trimIndent()

    @Test
    fun epubHtmlSegmentsWithHeadings_keepsAnchorSplitWhenEveryHeadingIsInTheToc() {
        // 回归保护：三个标题都被目录覆盖 ⇒ 标题切点一个都不该加。多切一刀会把
        // precedingTitleBlockStart 定位出来的"部分标题页"顶掉，第一章整章消失。
        val entries = listOf(
            tocEntryOf("OEBPS", "part.xhtml#sigil_toc_id_1", "第一部分 疯狂的控制型关系"),
            tocEntryOf("OEBPS", "part.xhtml#sigil_toc_id_1", "第一章 从天而降的真命天子"),
            tocEntryOf("OEBPS", "part.xhtml#sigil_toc_id_57", "第二章 当蜜月结束时")
        ).map { requireNotNull(it) }

        val anchorsOnly = epubHtmlSegments(sharedAnchorHtml, entries)
        val withHeadings = epubHtmlSegmentsWithHeadings(
            html = sharedAnchorHtml,
            entries = entries,
            coveredTitles = entries.map { it.title.cleanForTest() }.toSet()
        )

        assertEquals(anchorsOnly.size, withHeadings.size)
        assertEquals(anchorsOnly.map { it.start }, withHeadings.map { it.start })
        assertEquals(anchorsOnly.map { it.end }, withHeadings.map { it.end })
        assertEquals(
            anchorsOnly.map { it.entry?.title },
            withHeadings.map { it.entry?.title }
        )
    }

    /** 测试里也走一遍归一化（生产代码用的是同一个 `cleanTocTitle`） */
    private fun String.cleanForTest(): String = replace(Regex("[\\s\\p{Zs}]+"), " ").trim()

    // ---------------- "目录为准"档：被压扁的文件并入上一章 ----------------

    @Test
    fun uncoveredFileMergesIntoPrevious_onlyWhenItsFirstHeadingIsChapterShaped() {
        // part0020_split_000：首标题是章 ⇒ 并（目录里不再出现"第一章 生活的意義"这种假条目）
        assertTrue(
            epubUncoveredFileMergesIntoPrevious("<body><h3>第一章　生活的意義</h3><p>甲</p></body>")
        )
        // part0011 / part0015：首标题是卷 / BOOK ⇒ 不并（那是这个文件真正开始的地方）
        assertFalse(
            epubUncoveredFileMergesIntoPrevious("<body><h3>第一卷　群體心理</h3><p>甲</p></body>")
        )
        assertFalse(
            epubUncoveredFileMergesIntoPrevious(
                "<body><h3>BOOK I \u00a0 THE MIND OF CROWDS</h3><p>甲</p></body>"
            )
        )
        // 没有标题：这里不管，交给原有的"无标题页"规则
        assertFalse(epubUncoveredFileMergesIntoPrevious("<body><p>只有正文。</p></body>"))
    }

    @Test
    fun mergeSpinePartsIntoChapters_foldsTheSqueezedFileIntoThePreviousChapter() {
        val chapters = listOf(
            EbookChapter(
                title = "譯者序",
                text = "譯者序正文。",
                sourcePath = "text/part0019.html",
                titleFromMarkup = true
            ),
            EbookChapter(
                title = "第一章　生活的意義",
                text = "第一章正文。",
                sourcePath = "text/part0020_split_000.html",
                titleFromMarkup = true,
                mergesIntoPrevious = true
            )
        )

        val merged = mergeSpinePartsIntoChapters(chapters)

        // 正文一页不少，但只剩一条目录项（名字是上一章的）—— legado 里 譯者序 那一章
        // 本来也就是这么一直延伸到全书结束的
        assertEquals(1, merged.size)
        assertEquals("譯者序", merged[0].title)
        assertTrue(merged[0].text.contains("譯者序正文"))
        assertTrue(merged[0].text.contains("第一章正文"))
        assertFalse(merged[0].mergesIntoPrevious)
    }

    // ---------------- 未覆盖文件的目录缩进 ----------------

    @Test
    fun inheritedUncoveredLevels_indentsUncoveredFilesUnderThePrecedingVolume() {
        val toc = listOf(
            tocEntry("text/part0009.html", "烏合之眾", level = 0, isGroup = true),
            tocEntry("text/part0010.html", "烏合之眾：大眾心理研究", level = 1),
            tocEntry("text/part0014.html", "THE CROWD：study of the popular Mind", level = 1),
            tocEntry("text/part0018.html", "自卑與超越", level = 0, isGroup = true),
            tocEntry("text/part0019.html", "譯者序", level = 1)
        )
        val levels = inheritedUncoveredLevels(
            spinePaths = listOf(
                "text/part0009.html", "text/part0010.html",
                "text/part0011.html", "text/part0012.html", "text/part0013.html",
                "text/part0014.html", "text/part0015.html",
                "text/part0018.html", "text/part0019.html",
                "text/part0020_split_000.html"
            ),
            tocEntries = toc
        )

        // 卷（还有子条目）⇒ 它后面的未覆盖文件继承到子层级 1
        assertEquals(1, levels["text/part0011.html"])
        assertEquals(1, levels["text/part0012.html"])
        assertEquals(1, levels["text/part0013.html"])
        assertEquals(1, levels["text/part0015.html"])
        // 自卑與超越那一卷的 譯者序 是 level 1 ⇒ 后面目录没列的 part0020_* 也是 1
        assertEquals(1, levels["text/part0020_split_000.html"])
        // 文件自己有条目时用的是条目层级（见 buildEpubChaptersFromHtml），这里只是"从前面继承到的值"
        assertEquals(0, levels["text/part0009.html"])
    }

    private fun tocEntry(
        path: String,
        title: String,
        level: Int,
        isGroup: Boolean = false
    ): EpubTocEntry = EpubTocEntry(title = title, path = path, level = level, isGroup = isGroup)
}
