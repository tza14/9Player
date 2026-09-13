package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EbookTocAnchorSplitTest {
    /** 無職転生 part0009.xhtml 的真实形状：小节标题包在 div 里、带目录锚点 */
    private val html = """
        <html><head><title>part0009</title></head><body>
        <p class="class_s2">　プロローグの続き。思わずそんな言葉が溢れる。</p>
        <div class="class_s9k"><p id="a5HS" class="class_s1z">第一話「もしかして：異世界」</p></div>
        <p class="class_s3p">　目が覚めると、そこは知らない部屋だった。</p>
        <div class="class_s9k"><p id="a5HT" class="class_s1z">第二話「ドン引きのメイドさん」</p></div>
        <p class="class_s3p">　翌日、メイドが来た。</p>
        </body></html>
    """.trimIndent()

    /** 目录条目（标题无所谓，断言只看 fragment） */
    private fun tocEntriesOf(vararg fragments: String): List<EpubTocEntry> =
        fragments.mapIndexed { index, fragment ->
            requireNotNull(tocEntryOf("OEBPS", "Text/part0001.xhtml#$fragment", "第${index + 1}条"))
        }

    /** 把段还原成 html 片段，断言起来跟以前一样直观 */
    private fun List<EpubHtmlSegment>.textsIn(source: String): List<String> =
        map { source.substring(it.start, it.end) }

    @Test
    fun epubHtmlSegments_breaksAtElementStartAndKeepsTagsBalanced() {
        val segments = epubHtmlSegments(html, tocEntriesOf("a5HS", "a5HT"))
        val parts = segments.textsIn(html)

        assertEquals(3, parts.size)
        // 第 0 段：锚点之前的正文（不含小节标题）
        assertTrue(parts[0].contains("プロローグの続き"))
        assertTrue(!parts[0].contains("第一話"))
        // 第 1 段：从包着锚点的 <div> 开始（不是从 <p id=...> 开始，标签才平衡）
        assertTrue(parts[1].startsWith("<div class=\"class_s9k\">"))
        assertTrue(parts[1].contains("第一話"))
        assertTrue(parts[1].contains("知らない部屋"))
        assertTrue(!parts[1].contains("第二話"))
        // 第 2 段：第二话起
        assertTrue(parts[2].startsWith("<div class=\"class_s9k\">"))
        assertTrue(parts[2].contains("第二話"))
        // 每段的 div 都是成对的
        parts.forEach { part ->
            assertEquals(part.split("<div").size, part.split("</div>").size)
        }
        // 段与目录条目一一对应（第 0 段是"锚点之前"，没有条目）
        assertEquals(listOf(null, "a5HS", "a5HT"), segments.map { it.entry?.fragment })
    }

    @Test
    fun epubHtmlSegments_ignoresUnknownAnchors() {
        val parts = epubHtmlSegments(html, tocEntriesOf("nope", "a5HT")).textsIn(html)

        assertEquals(2, parts.size)
        assertTrue(parts[1].contains("第二話"))
    }

    @Test
    fun epubHtmlSegments_handlesAnchorOnItsOwnParagraph() {
        val simple = "<body><p>甲</p><p id=\"a1\">乙</p><p>丙</p></body>"

        val parts = epubHtmlSegments(simple, tocEntriesOf("a1")).textsIn(simple)

        assertEquals(2, parts.size)
        assertEquals("<body><p>甲</p>", parts[0])
        assertEquals("<p id=\"a1\">乙</p><p>丙</p></body>", parts[1])
    }

    /**
     * 《心理学原理》part0001.xhtml 的真实形状：**整本书的正文全都排在这一个文件里**，
     * 外面还套着一层大 div（`<div id="x-">`），前言那个 div 里紧挨着两个目录锚点。
     */
    private val wrappedHtml = """
        <html><head><title>part0001</title></head><body>
        <div id="x-">
        <div class="text"><p class="t" id="toc-anchor">前 言</p><p class="t" id="toc-anchor-10">詹姆斯和他的作品</p><p>序言正文。</p><div style="page-break-after:always"></div></div>
        <div class="text"><p class="t" id="toc-anchor-1">心理学的范围</p><p>第一章正文。</p></div>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun epubHtmlSegments_doesNotCollapseAnchorsOntoTheWrapperDiv() {
        val parts = epubHtmlSegments(
            wrappedHtml,
            tocEntriesOf("toc-anchor", "toc-anchor-10", "toc-anchor-1")
        ).textsIn(wrappedHtml)

        // 3 个锚点必须切出 4 段，而且每段都从正确的元素开始。往前回溯时若爬到外层大 div 上，
        // 两个前言锚点会先落到同一处、再被"必须递增"的约束推回 <p> 上，标题就跟着错位。
        assertEquals(4, parts.size)
        assertTrue(parts[1].contains("前 言"))
        assertTrue(!parts[1].contains("詹姆斯"))
        assertTrue(!parts[1].contains("序言正文"))
        assertTrue(parts[2].contains("詹姆斯"))
        assertTrue(parts[2].contains("序言正文"))
        assertTrue(!parts[2].contains("心理学的范围"))
        assertTrue(parts[3].contains("心理学的范围"))
        assertTrue(parts[3].contains("第一章正文"))
    }

    @Test
    fun epubHtmlSegments_keepsTheOtherChaptersWhenOneAnchorIsMissing() {
        // 目录里中间那条锚点在正文里根本定位不到（错字、或者被改过）：只有它自己并进邻章，
        // 其余章节照切。修复前是"段数对不上锚点数就整本书退回一章"，《心理学原理》
        // （整本正文排在一个文件里、目录 20 条）因此只剩下 2 章。
        val segments = epubHtmlSegments(
            wrappedHtml,
            tocEntriesOf("toc-anchor", "toc-anchor-typo", "toc-anchor-1")
        )

        assertEquals(3, segments.size)
        assertNull(segments[0].entry)
        assertEquals("toc-anchor", segments[1].entry?.fragment)
        assertEquals("toc-anchor-1", segments[2].entry?.fragment)
        // 定位不到的那条没有自己的段，它的正文留在了上一段的末尾
        val firstChapter = wrappedHtml.substring(segments[1].start, segments[1].end)
        assertTrue(firstChapter.contains("前 言"))
        assertTrue(firstChapter.contains("詹姆斯"))
        assertTrue(!firstChapter.contains("心理学的范围"))
    }

    @Test
    fun epubHtmlSegments_givesNoSegmentToADuplicateAnchor() {
        // 两条目录条目指向同一个锚点：第二条没有自己的段（正文不会重复出现），
        // 但**不影响**其余章节照常切分。这条同时钉住 anchorCutPositions 里的
        // usedAnchorIndexes —— 删掉它这里会变成 4 段。
        val segments = epubHtmlSegments(
            wrappedHtml,
            tocEntriesOf("toc-anchor", "toc-anchor", "toc-anchor-1")
        )

        assertEquals(3, segments.size)
        assertEquals("toc-anchor", segments[1].entry?.fragment)
        assertEquals("toc-anchor-1", segments[2].entry?.fragment)
    }

    @Test
    fun epubHtmlSegments_returnsEmptyWhenNoAnchorCanBeLocated() {
        // 一条都定位不到：调用方据此退回"整文件一章"
        assertEquals(
            emptyList<EpubHtmlSegment>(),
            epubHtmlSegments(wrappedHtml, tocEntriesOf("nope", "also-nope"))
        )
    }
}
