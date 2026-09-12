package moe.tekuza.m9player.legado.reader.entities

import android.graphics.Paint
import moe.tekuza.m9player.EbookRubyKind
import kotlin.math.max

internal data class RubyGlyphBox(
    val text: String,
    val start: Float,
    val end: Float
)

internal object RubyLayoutEngine {
    const val TEXT_RATIO: Float = 0.5f
    const val RESERVE_RATIO: Float = 0.62f
    const val VERTICAL_UNIT_RATIO: Float = 0.88f
    const val MIN_UNIT_RATIO: Float = 0.62f
    const val EDGE_OVERHANG_EM: Float = 0.55f
    const val SEGMENT_OVERHANG_EM: Float = 0.18f
    const val MIN_SCALE: Float = 0.78f
    const val GAP_EM: Float = 0.08f
    const val DISTRIBUTE_THRESHOLD: Float = 0.82f
    private const val GROUP_VERTICAL_OVERHANG_EM = 1.0f

    fun rubyTextSize(baseTextSize: Float): Float {
        return (baseTextSize * TEXT_RATIO).coerceAtLeast(8f)
    }

    fun codePointStrings(text: String): List<String> {
        return text.codePoints()
            .toArray()
            .map { String(Character.toChars(it)) }
    }

    fun fitHorizontalRubyText(
        paint: Paint,
        annotation: String,
        baseWidth: Float,
        beforeOverhang: Float,
        afterOverhang: Float,
        originalSize: Float
    ) {
        val allowedWidth = (baseWidth + beforeOverhang + afterOverhang).coerceAtLeast(baseWidth)
        val measured = paint.measureText(annotation).coerceAtLeast(1f)
        if (measured <= allowedWidth) return
        paint.textSize = (paint.textSize * allowedWidth / measured)
            .coerceAtLeast(originalSize * TEXT_RATIO * MIN_SCALE)
    }

    fun shouldDistributeHorizontal(annotation: String, baseWidth: Float, paint: Paint): Boolean {
        val count = annotation.codePointCount(0, annotation.length)
        if (count <= 1) return false
        return paint.measureText(annotation) < baseWidth * DISTRIBUTE_THRESHOLD
    }

    /**
     * ルビが親文字からはみ出すとき、隣接する 1 文字に掛けてよい量（JLREQ 3.3.8）。
     * 汉字等（cl-19）には掛けない／仮名・長音記号・小書き仮名にはルビ 1 字分まで／
     * 約物にはその半分まで。
     */
    fun allowedRubyOverhang(adjacent: String, rubySize: Float): Float {
        val first = adjacent.firstOrNull() ?: return 0f
        return when {
            isJapaneseIdeograph(first) -> 0f
            first in RUBY_FULL_OVERHANG_CHARS -> rubySize
            first in RUBY_PUNCTUATION_OVERHANG_CHARS -> rubySize * 0.5f
            else -> rubySize * EDGE_OVERHANG_EM
        }
    }

    /**
     * 分割ルビ（&lt;rb&gt;/&lt;rt&gt;）を含めた掛かり量の決定。
     *
     * - 同じ親文字群の内側（熟語ルビの字間）は分割ルビの組み方を崩さないよう抑える（JLREQ 3.3.7）。
     * - 隣の字が自分もルビを持つ場合も、2 つのルビ文字列が触れないよう抑える（JLREQ Fig.137/138）。
     * - それ以外は、分割ルビでも文字クラスごとの掛かり量まで掛けてよい。モノルビで親文字より
     *   ルビが長い場合（例：眦←まなじり）はベタ組のまま前後の仮名に掛かる（JLREQ 3.3.5 / 3.3.8）。
     */
    fun rubyOverhang(
        adjacent: String,
        rubySize: Float,
        segmented: Boolean,
        insideBaseGroup: Boolean,
        adjacentHasRuby: Boolean
    ): Float {
        if (segmented && (insideBaseGroup || adjacentHasRuby)) {
            return rubySize * SEGMENT_OVERHANG_EM
        }
        return allowedRubyOverhang(adjacent, rubySize)
    }

    fun verticalGlyphBoxes(
        annotation: String,
        baseColumns: List<TextColumn>,
        top: Float,
        bottom: Float,
        rubySize: Float,
        beforeOverhang: Float,
        afterOverhang: Float,
        rubyKind: EbookRubyKind,
        segmented: Boolean
    ): List<RubyGlyphBox> {
        val rubyChars = codePointStrings(annotation)
        if (rubyChars.isEmpty()) return emptyList()
        if (shouldAttachPerBase(rubyChars, baseColumns, rubyKind, segmented)) {
            val unitHeight = rubySize * VERTICAL_UNIT_RATIO
            return rubyChars.mapIndexed { index, rubyChar ->
                val base = baseColumns[index]
                val center = (base.start + base.end) * 0.5f
                RubyGlyphBox(rubyChar, center - unitHeight * 0.5f, center + unitHeight * 0.5f)
            }
        }

        val baseHeight = (bottom - top).coerceAtLeast(1f)
        val groupOverhang = if (rubyKind == EbookRubyKind.GROUP || (rubyKind == EbookRubyKind.JUKUGO && !segmented)) {
            rubySize * GROUP_VERTICAL_OVERHANG_EM
        } else {
            0f
        }
        val allowedBefore = max(beforeOverhang, groupOverhang)
        val allowedAfter = max(afterOverhang, groupOverhang)
        val naturalUnitHeight = rubySize * VERTICAL_UNIT_RATIO
        val fillsBaseSpan = usesBaseSpanDistribution(rubyKind, segmented)
        val preferredUnitHeight = if (fillsBaseSpan) {
            baseHeight / rubyChars.size
        } else {
            naturalUnitHeight
        }
        val naturalHeight = preferredUnitHeight * rubyChars.size
        val allowedHeight = (baseHeight + allowedBefore + allowedAfter).coerceAtLeast(baseHeight)
        val unitHeight = if (naturalHeight > allowedHeight) {
            (allowedHeight / rubyChars.size).coerceAtLeast(rubySize * MIN_UNIT_RATIO)
        } else {
            preferredUnitHeight
        }
        val totalHeight = unitHeight * rubyChars.size
        val minY = top - if (fillsBaseSpan) 0f else allowedBefore
        val maxY = bottom + if (fillsBaseSpan) 0f else allowedAfter - totalHeight
        val centeredY = (top + bottom) * 0.5f - totalHeight * 0.5f
        var y = if (maxY >= minY) centeredY.coerceIn(minY, maxY) else centeredY
        return rubyChars.map { rubyChar ->
            RubyGlyphBox(rubyChar, y, y + unitHeight).also {
                y += unitHeight
            }
        }
    }

    private fun usesBaseSpanDistribution(rubyKind: EbookRubyKind, segmented: Boolean): Boolean {
        return !segmented && (rubyKind == EbookRubyKind.GROUP || rubyKind == EbookRubyKind.JUKUGO)
    }

    private val JAPANESE_IDEOGRAPH_BLOCKS = setOf(
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    )

    private fun isJapaneseIdeograph(char: Char): Boolean =
        Character.UnicodeBlock.of(char) in JAPANESE_IDEOGRAPH_BLOCKS

    /** 仮名（小書き仮名を含む）。長音記号 'ー' は U+30FC でカタカナ範囲の外なので個別に足す。 */
    private val RUBY_FULL_OVERHANG_CHARS = setOf('ー') + ('ぁ'..'ん') + ('ァ'..'ヶ')

    private val RUBY_PUNCTUATION_OVERHANG_CHARS = setOf(
        '、', '。', '，', '．', '・', '：', '；', '！', '？',
        '「', '『', '（', '《', '〈', '［', '〔',
        '」', '』', '）', '》', '〉', '］', '〕'
    )

    private fun shouldAttachPerBase(
        rubyChars: List<String>,
        baseColumns: List<TextColumn>,
        rubyKind: EbookRubyKind,
        segmented: Boolean
    ): Boolean {
        if (rubyChars.size <= 1 || rubyChars.size != baseColumns.size) return false
        if (baseColumns.any { it.charData.codePointCount(0, it.charData.length) != 1 }) return false
        return segmented || rubyKind == EbookRubyKind.MONO || rubyKind == EbookRubyKind.JUKUGO
    }
}
