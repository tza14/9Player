package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Test

class EbookSentenceRangeTest {
    private val text = "「戦闘用しかないのは、主に戦いの中でしか使われてこなかったというのもありますが……。" +
        "魔術に頼らなくても、身近なものを使えば実現できるという理由もあります」"

    @Test
    fun expandCueRangeToSentence_includesLeadingAndTrailingQuotes() {
        // 匹配只能拿到"可读字符"的范围（不含「」），扩句后应连引号一起算
        val matchedStart = text.indexOf('戦')
        val matchedEnd = text.indexOf('」')

        val (from, to) = expandCueRangeToSentence(text, matchedStart, matchedEnd)

        assertEquals(text.indexOf('「'), from)
        assertEquals(text.length, to)
        assertEquals("「", text.substring(from, from + 1))
        assertEquals("」", text.substring(to - 1, to))
    }

    @Test
    fun expandCueRangeToSentence_stopsAtPreviousSentencesClosingMark() {
        val dialogue = "「前の台詞です」「次の台詞です」"
        val nextStart = dialogue.indexOf('次')

        val (from, _) = expandCueRangeToSentence(dialogue, nextStart, dialogue.length - 1)

        // 只吃下自己的开引号，不会把上一句的 」 也吞掉
        assertEquals(dialogue.indexOf('「', nextStart - 1), from)
        assertEquals("「", dialogue.substring(from, from + 1))
    }

    @Test
    fun expandCueRangeToSentence_includesTrailingPeriod() {
        val plain = "人族は発明大好きな人種らしい。"
        val end = plain.indexOf('。')

        val (from, to) = expandCueRangeToSentence(plain, 0, end)

        assertEquals(0, from)
        assertEquals(plain.length, to)
    }

    @Test
    fun expandCueRangeToSentence_clampsToTextBounds() {
        assertEquals(0 to 0, expandCueRangeToSentence("", 0, 0))
        // 越界的位置被夹回正文内；句尾的省略号仍被收进句子
        assertEquals(1 to 1, expandCueRangeToSentence("…", 5, 9))
        assertEquals(0 to 1, expandCueRangeToSentence("…", 0, 0))
    }
}
