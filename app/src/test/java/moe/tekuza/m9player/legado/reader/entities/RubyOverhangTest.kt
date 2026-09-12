package moe.tekuza.m9player.legado.reader.entities

import moe.tekuza.m9player.EbookRubyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ルビの掛かり量（JLREQ 3.3.5 / 3.3.7 / 3.3.8）と、それを使った縦組の字送り。
 *
 * 実例：無職転生 の「眦」には `&lt;rb&gt;眦&lt;/rb&gt;&lt;rt&gt;まなじり&lt;/rt&gt;` が付く。
 * 親文字 1 字・ルビ 4 字なので、ベタ組のまま前後の仮名に掛からないと字形が重なる。
 */
class RubyOverhangTest {
    private val rubySize = 26f
    private val baseHeight = 52f

    private fun overhangBeforeKana() =
        RubyLayoutEngine.rubyOverhang("の", rubySize, segmented = true, insideBaseGroup = false, adjacentHasRuby = false)

    private fun overhangAfterKana() =
        RubyLayoutEngine.rubyOverhang("が", rubySize, segmented = true, insideBaseGroup = false, adjacentHasRuby = false)

    @Test
    fun segmentedMonoRubyMayOverhangAdjacentKana() {
        assertEquals(rubySize, overhangBeforeKana(), 0.001f)
        assertEquals(rubySize, overhangAfterKana(), 0.001f)
    }

    @Test
    fun rubyNeverOverhangsAdjacentIdeograph() {
        assertEquals(
            0f,
            RubyLayoutEngine.rubyOverhang("漢", rubySize, segmented = true, insideBaseGroup = false, adjacentHasRuby = false),
            0.001f
        )
        assertEquals(
            0f,
            RubyLayoutEngine.rubyOverhang("漢", rubySize, segmented = false, insideBaseGroup = false, adjacentHasRuby = false),
            0.001f
        )
    }

    @Test
    fun rubyOverhangsPunctuationByHalfARubyCharacter() {
        assertEquals(
            rubySize * 0.5f,
            RubyLayoutEngine.rubyOverhang("「", rubySize, segmented = true, insideBaseGroup = false, adjacentHasRuby = false),
            0.001f
        )
    }

    @Test
    fun jukugoRubyKeepsItsOwnSpacingInsideTheBaseGroup() {
        val insideGroup =
            RubyLayoutEngine.rubyOverhang("乳", rubySize, segmented = true, insideBaseGroup = true, adjacentHasRuby = true)
        assertEquals(rubySize * RubyLayoutEngine.SEGMENT_OVERHANG_EM, insideGroup, 0.001f)
    }

    @Test
    fun adjacentRubyRunBlocksTheOverhang() {
        val nextToRuby =
            RubyLayoutEngine.rubyOverhang("の", rubySize, segmented = true, insideBaseGroup = false, adjacentHasRuby = true)
        assertEquals(rubySize * RubyLayoutEngine.SEGMENT_OVERHANG_EM, nextToRuby, 0.001f)
    }

    @Test
    fun unsegmentedRubyBehaviourIsUnchanged() {
        assertEquals(
            rubySize,
            RubyLayoutEngine.rubyOverhang("の", rubySize, segmented = false, insideBaseGroup = false, adjacentHasRuby = false),
            0.001f
        )
    }

    @Test
    fun manajiriFitsSolidWithoutGlyphOverlap() {
        val count = 4
        val naturalHeight = rubySize * RubyLayoutEngine.VERTICAL_UNIT_RATIO * count
        val allowedHeight = baseHeight + overhangBeforeKana() + overhangAfterKana()
        assertTrue(
            "ベタ組のルビ文字列が親文字＋掛かり量に収まること（実測 $naturalHeight vs $allowedHeight）",
            naturalHeight <= allowedHeight
        )

        val column = TextColumn(
            start = 100f,
            end = 100f + baseHeight,
            charData = "眦",
            sourceStart = 0,
            sourceEnd = 1,
            rubyText = "まなじり",
            rubySourceStart = 0,
            rubySourceEnd = 1
        )
        val boxes = RubyLayoutEngine.verticalGlyphBoxes(
            annotation = "まなじり",
            baseColumns = listOf(column),
            top = column.start,
            bottom = column.end,
            rubySize = rubySize,
            beforeOverhang = overhangBeforeKana(),
            afterOverhang = overhangAfterKana(),
            rubyKind = EbookRubyKind.MONO,
            segmented = true
        )
        assertEquals(count, boxes.size)
        val unit = boxes[1].start - boxes[0].start
        assertTrue(
            "字送りが自然字高を下回らない（＝字形が重ならない）こと（実測 $unit）",
            unit >= rubySize * RubyLayoutEngine.VERTICAL_UNIT_RATIO - 0.001f
        )
        assertTrue("上（前の仮名）へ掛かること", boxes.first().start < column.start)
        assertTrue("下（後の仮名）へ掛かること", boxes.last().end > column.end)
    }
}
