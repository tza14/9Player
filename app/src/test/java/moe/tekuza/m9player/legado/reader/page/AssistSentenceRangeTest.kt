package moe.tekuza.m9player.legado.reader.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 竖排里点英文弹出的气泡：应当给出**整句**，而不是被点中的那个单词。
 *
 * 实例（無職転生 巻頭の引用）：`I do not want to work, whatever it may be said by whom.`
 * 竖排中英文按单词切列、相邻列在正文里隔着空格，所以按"source 连续"合并只能得到单个单词。
 */
class AssistSentenceRangeTest {
    private fun sentenceAt(text: String, token: String): String {
        val start = text.indexOf(token)
        assertTrue("token '$token' not found", start >= 0)
        val end = start + token.length
        val range = assistSentenceRange(text, start, end)
        assertNotNull("no sentence range for '$token'", range)
        return text.substring(range!!.first, range.last + 1).trim()
    }

    @Test
    fun englishWordExpandsToTheWholeSentence() {
        val text = "I do not want to work, whatever it may be said by whom."
        assertEquals(text, sentenceAt(text, "work"))
        assertEquals(text, sentenceAt(text, "I"))
        assertEquals(text, sentenceAt(text, "whom"))
    }

    @Test
    fun onlyTheTappedSentenceIsReturned() {
        val text = "He said no. I do not want to work."
        assertEquals("I do not want to work.", sentenceAt(text, "work"))
        assertEquals("He said no.", sentenceAt(text, "said"))
    }

    @Test
    fun paragraphBreakStopsTheRangeWhenNoTerminatorExists() {
        val text = "I do not want to work\n次の段落の日本語"
        assertEquals("I do not want to work", sentenceAt(text, "work"))
    }

    @Test
    fun japaneseSentenceContainingEnglishIsReturnedWhole() {
        val text = "これは work という単語です。"
        assertEquals(text, sentenceAt(text, "work"))
    }

    @Test
    fun overlongRunIsTruncatedAroundTheTappedWord() {
        val text = "a".repeat(50) + " work " + "b".repeat(400)
        val sentence = sentenceAt(text, "work")
        assertTrue("truncated to a sane length, got ${sentence.length}", sentence.length <= 200)
        assertTrue("keeps the tapped word", sentence.contains("work"))
    }

    @Test
    fun leadingWhitespaceIsTrimmed() {
        val text = "　前の段落。\n　I do not want to work."
        assertEquals("I do not want to work.", sentenceAt(text, "work"))
    }
}
