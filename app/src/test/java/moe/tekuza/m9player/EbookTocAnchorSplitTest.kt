package moe.tekuza.m9player

import org.junit.Assert.assertEquals
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

    @Test
    fun splitHtmlAtAnchors_breaksAtElementStartAndKeepsTagsBalanced() {
        val segments = splitHtmlAtAnchors(html, listOf("a5HS", "a5HT"))

        assertEquals(3, segments.size)
        // 第 0 段：锚点之前的正文（不含小节标题）
        assertTrue(segments[0].contains("プロローグの続き"))
        assertTrue(!segments[0].contains("第一話"))
        // 第 1 段：从包着锚点的 <div> 开始（不是从 <p id=...> 开始，标签才平衡）
        assertTrue(segments[1].startsWith("<div class=\"class_s9k\">"))
        assertTrue(segments[1].contains("第一話"))
        assertTrue(segments[1].contains("知らない部屋"))
        assertTrue(!segments[1].contains("第二話"))
        // 第 2 段：第二话起
        assertTrue(segments[2].startsWith("<div class=\"class_s9k\">"))
        assertTrue(segments[2].contains("第二話"))
        // 每段的 div 都是成对的
        segments.forEach { segment ->
            assertEquals(
                segment.split("<div").size,
                segment.split("</div>").size
            )
        }
    }

    @Test
    fun splitHtmlAtAnchors_ignoresUnknownAnchors() {
        val segments = splitHtmlAtAnchors(html, listOf("nope", "a5HT"))

        assertEquals(2, segments.size)
        assertTrue(segments[1].contains("第二話"))
    }

    @Test
    fun splitHtmlAtAnchors_withNoUsableAnchorReturnsWholeHtml() {
        assertEquals(listOf(html), splitHtmlAtAnchors(html, emptyList()))
        assertEquals(listOf(html), splitHtmlAtAnchors(html, listOf("missing")))
    }

    @Test
    fun splitHtmlAtAnchors_handlesAnchorOnItsOwnParagraph() {
        val simple = "<body><p>甲</p><p id=\"a1\">乙</p><p>丙</p></body>"

        val segments = splitHtmlAtAnchors(simple, listOf("a1"))

        assertEquals(2, segments.size)
        assertEquals("<body><p>甲</p>", segments[0])
        assertEquals("<p id=\"a1\">乙</p><p>丙</p></body>", segments[1])
    }

    @Test
    fun splitHtmlAtAnchors_withDuplicateAnchorYieldsFewerSegmentsThanAnchors() {
        // 目录里两条指向同一个锚点：只会切一次 → 段数(3) != 锚点数+1(4)，
        // 调用方据此退回"整文件一章"，不会越界。
        val segments = splitHtmlAtAnchors(html, listOf("a5HS", "a5HS", "a5HT"))

        assertEquals(3, segments.size)
    }
}
