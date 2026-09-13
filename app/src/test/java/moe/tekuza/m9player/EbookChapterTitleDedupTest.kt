package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文开头的章节标题去重：标题已经由页眉显示一次，正文里再来一遍就是重复。
 *
 * 《心理学原理》把章节标题排成普通 `<p>`（不是 h1/h2，剥不掉），所以"第三章 大脑活动的一般条件
 * Chapter 3 …"会在页眉和正文各出现一次。这里比对两侧文本，把正文开头那一段切掉。
 */
class EbookChapterTitleDedupTest {
    private val title = "第三章 大脑活动的一般条件  Chapter 3  On Some General Conditions of Brain-Activity"

    @Test
    fun stripsTheLeadingTitleAndKeepsTheProse() {
        val text = "第三章 大脑活动的一般条件  Chapter 3  On Some General Conditions of Brain-Activity\n\n大脑功能所依赖的神经组织…"

        val cut = leadingChapterTitleLength(text, title)

        assertEquals("大脑功能所依赖的神经组织…", text.substring(cut))
    }

    @Test
    fun matchesAcrossNbspAndRepeatedSpaces() {
        // 书里用 &#160; 排版；正文侧 NBSP 已被 normalizeReaderWhitespace 换成普通空格，
        // 标题侧由 cleanTocTitle 把连续空白压成一个空格
        val text = "第三章\u00A0 大脑活动的一般条件\u00A0\u00A0 Chapter 3\u00A0 On Some General Conditions of Brain-Activity\n正文。"

        assertEquals("正文。", text.substring(leadingChapterTitleLength(text, title)))
    }

    @Test
    fun wholeBodyIsJustTheTitle() {
        val cut = leadingChapterTitleLength(title, title)

        assertEquals(title.length, cut)
    }

    @Test
    fun doesNotStripWhenTheTitleIsOnlyAPrefix() {
        // "第一章" 后面紧跟"的"，说明正文并不是在重复标题，而是碰巧以它开头
        assertEquals(0, leadingChapterTitleLength("第一章的内容如下。", "第一章"))
    }

    @Test
    fun doesNotStripOnDifferentText() {
        assertEquals(0, leadingChapterTitleLength("大脑功能所依赖的神经组织…", title))
    }

    @Test
    fun doesNotStripWhenTextShorterThanTitle() {
        assertEquals(0, leadingChapterTitleLength("第三章", title))
    }

    @Test
    fun handlesBlankInputs() {
        assertEquals(0, leadingChapterTitleLength("正文", ""))
        assertEquals(0, leadingChapterTitleLength("", title))
        assertEquals(0, leadingChapterTitleLength("   ", title))
    }

    @Test
    fun swallowsSurroundingBlankLinesAfterTheTitle() {
        val text = "\n\n第三章 大脑活动的一般条件  Chapter 3  On Some General Conditions of Brain-Activity\n\n\n正文。"

        val cut = leadingChapterTitleLength(text, title)

        assertTrue(text.substring(cut).startsWith("正文"))
    }
}
