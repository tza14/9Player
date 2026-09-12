package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupAnkiSupportTest {

    /**
     * 隐私不变量：导出日志只带长度，不带查询词本身。
     * 用哨兵词做**功能**断言（而不是去源码里找拼法）；参数类型是 Int，编译器另外保证
     * 调用方传不进词本身。
     */
    @Test
    fun exportStartLogLineCarriesLengthsNotTheLookupTerm() {
        val term = "SENTINEL_ZZ91"

        val line = ankiExportStartLogLine(termLength = term.length, dictionaryLength = 7)

        assertFalse("日志不得包含查询词本身", line.contains("SENTINEL"))
        assertFalse(line.contains(term))
        assertTrue(line.contains("termLength=${term.length}"))
        assertTrue(line.contains("dictionaryLength=7"))
    }

    @Test
    fun exportCardLogLineCarriesLengthsNotTheCardWord() {
        val word = "SENTINEL_ZZ91"

        val line = ankiExportCardLogLine(
            wordLength = word.length,
            primaryDictionaryLength = 4,
            glossaryDictionaryCount = 2,
            glossaryDefinitionCount = 5
        )

        assertFalse("日志不得包含词本身", line.contains("SENTINEL"))
        assertTrue(line.contains("wordLength=${word.length}"))
        assertTrue(line.contains("glossaryDictionaryCount=2"))
        assertTrue(line.contains("glossaryDefinitionCount=5"))
    }

    @Test
    fun resolveLookupExportWord_prefersCurrentLookupTerm() {
        assertEquals(
            "あら",
            resolveLookupExportWord(
                popupSelectionText = "登録",
                lookupTermOverride = "あら",
                entryTerm = "登録"
            )
        )
    }

    @Test
    fun resolveLookupExportWord_fallsBackToPopupSelection() {
        assertEquals(
            "登録",
            resolveLookupExportWord(
                popupSelectionText = "登録",
                lookupTermOverride = "",
                entryTerm = "動"
            )
        )
    }
}
