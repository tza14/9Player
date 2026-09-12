package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 竖排欧文按 JLREQ §3.2.6 处理：一段欧文作为**一続きの横倒しラン**来排，
 * 词间只留欧文间隔（cl-26），只在词边界换列。
 *
 * 実例（無職転生 巻頭）：`I do not want to work, whatever it may be said by whom.`
 */
class AsciiTextRunTest {
    private val sentence = "I do not want to work, whatever it may be said by whom."

    @Test
    fun wholeSentenceIsOneSidewaysRun() {
        assertTrue(isSidewaysAsciiText(sentence))
        assertEquals(sentence.length, asciiTextRunEnd(sentence, 0, sentence.length))
    }

    @Test
    fun runKeepsPunctuationAndInternalSpaces() {
        assertEquals("work,".length, asciiTextRunEnd("work, 次", 0, "work, 次".length))
        assertTrue(isSidewaysAsciiText("work,"))
        assertTrue(isSidewaysAsciiText("I do not want"))
    }

    @Test
    fun trailingSpacesAreNotPartOfTheRun() {
        val text = "work,   ！"
        assertEquals("work,".length, asciiTextRunEnd(text, 0, text.length))
    }

    @Test
    fun japaneseTextIsNotASidewaysRun() {
        assertEquals(0, asciiTextRunEnd("ここは", 0, 3))
        assertFalse(isSidewaysAsciiText("ここは"))
        assertFalse(isSidewaysAsciiText("work。"))
    }

    @Test
    fun singleLetterStaysUpright() {
        assertFalse(isSidewaysAsciiText("A"))
        assertEquals(0, asciiTextRunEnd("A と B", 0, "A と B".length))
    }

    @Test
    fun twoToFourDigitNumbersStayTateChuYoko() {
        assertEquals(0, asciiTextRunEnd("12", 0, 2))
        assertEquals(0, asciiTextRunEnd("2024年", 0, 5))
        assertEquals(0, asciiTextRunEnd("123", 0, 3))
        // 英字が混ざれば数字でもランになる
        assertEquals("DTP 2024".length, asciiTextRunEnd("DTP 2024", 0, 8))
    }

    @Test
    fun runStopsAtJapaneseAndAtLineEnd() {
        val text = "I do not want to work\n次の段落"
        assertEquals("I do not want to work".length, asciiTextRunEnd(text, 0, text.length))
    }

    @Test
    fun asciiSpaceCharactersAreRecognised() {
        assertTrue(isAsciiRunSpaceChar(' '))
        assertTrue(isAsciiRunSpaceChar('\u00A0'))
        assertFalse(isAsciiRunSpaceChar('\u3000'))
    }
}
