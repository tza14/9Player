package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 目录条目解析：把 ncx/nav 里的 src 拆成"文件路径 + 锚点"。
 *
 * 回归点：[resolveEpubPath] 解析的是**文件路径**，它会把 `#` 之后的部分丢掉。
 * 所以锚点只能从原始 src 里取 —— 一旦从它的返回值里取，锚点永远是空串，
 * [epubHtmlSegments] 拿不到任何锚点，《心理学原理》（整本正文都在
 * part0001.xhtml 里，目录 20 条）就只剩下"一个 spine 文件一章"，也就是 2 章。
 */
class EbookTocEntryTest {
    @Test
    fun tocEntryOf_keepsTheAnchorAfterTheHash() {
        val entry = tocEntryOf("OEBPS", "Text/part0001.xhtml#toc-anchor-1", "第一章 心理学的范围")

        assertEquals("OEBPS/Text/part0001.xhtml", entry?.path)
        assertEquals("toc-anchor-1", entry?.fragment)
        assertEquals("第一章 心理学的范围", entry?.title)
    }

    @Test
    fun tocEntryOf_withoutAnchorHasEmptyFragment() {
        val entry = tocEntryOf("OEBPS", "Text/part0007.xhtml", "第一章 幼年期")

        assertEquals("OEBPS/Text/part0007.xhtml", entry?.path)
        assertEquals("", entry?.fragment)
    }

    @Test
    fun tocEntryOf_urlDecodesTheAnchor() {
        // %E4%B8%AD%E6%96%87 = "中文"（真实书里锚点常被百分号转义，
        // 而 html 里的 id 是原文，解不开就永远匹配不上）
        val entry = tocEntryOf("OEBPS", "Text/part0001.xhtml#%E4%B8%AD%E6%96%87", "中文锚点")

        assertEquals("中文", entry?.fragment)
    }

    @Test
    fun tocEntryOf_resolvesRelativeHrefAgainstTheOpfDirectory() {
        val entry = tocEntryOf("OEBPS", "../Text/part0001.xhtml#a1", "x")

        assertEquals("Text/part0001.xhtml", entry?.path)
        assertEquals("a1", entry?.fragment)
    }

    @Test
    fun tocEntryOf_rejectsEntryWithoutAFilePath() {
        assertNull(tocEntryOf("", "#a1", "x"))
    }
}
