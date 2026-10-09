package moe.tekuza.m9player.legado.reader

import android.graphics.Typeface
import android.text.TextPaint

/** 卷/章节标题字号 = 正文字号 × 该比例（布局与绘制共用） */
internal const val READER_TITLE_SCALE = 1.2f

internal data class M9ReadBookConfig(
    var textSizePx: Float,
    var lineSpacingPx: Float,
    var paragraphSpacingPx: Float,
    var textColor: Int,
    var tipColor: Int,
    var backgroundColor: Int,
    var useZhLayout: Boolean = true,
    var textBottomJustify: Boolean = true,
    var textFullJustify: Boolean = true,
    var paragraphIndent: String = "",
    var letterSpacingPx: Float = 0f,
    var textWeight: M9TextWeight = M9TextWeight.NORMAL,
    var typeface: Typeface? = null,
    var paddingLeftPx: Int = 22,
    var paddingTopPx: Int = 34,
    var paddingRightPx: Int = 22,
    var paddingBottomPx: Int = 22,
    var showHeaderFooter: Boolean = true,
    var layoutMode: M9LayoutMode = M9LayoutMode.HORIZONTAL,
    var pageAnim: M9PageAnim = M9PageAnim.NONE,
    var showRubyText: Boolean = true,
    /**
     * **章首标题**的字号（px）—— 与宿主 `PageView.applyBodyTitleStyle` 推给绘制端的
     * `contentTextSizeSp + bodyTitleSizeAddSp` 是同一个值。
     *
     * 为什么排版要知道它：竖排的章首标题吃掉最右几列宽度（见
     * `VerticalTextChapterLayout.titleColumnReserve`），量这份宽度必须用**真正画出来的那个字号**。
     * 用 [textSizePx] × [READER_TITLE_SCALE]（那是**卷标题**口径）去量，量出来的列数与绘制侧对不上，
     * 长标题就会因为测量多留一列而被提前省略。
     * 默认取 [textSizePx]：宿主没设置时（单测等）至少不会退到"字号更大"的卷标题口径。
     */
    var bodyTitleSizePx: Float = textSizePx
)

internal enum class M9LayoutMode {
    HORIZONTAL,
    VERTICAL
}

internal enum class M9PageAnim {
    COVER,
    SLIDE,
    SIMULATION,
    SCROLL,
    NONE
}

internal enum class M9TextWeight(val androidWeight: Int) {
    NORMAL(400),
    BOLD(700),
    LIGHT(300);

    companion object {
        fun fromIndex(index: Int): M9TextWeight = when (index) {
            1 -> BOLD
            2 -> LIGHT
            else -> NORMAL
        }
    }
}

internal fun TextPaint.applyM9TextWeight(weight: M9TextWeight, baseTypeface: Typeface?) {
    typeface = Typeface.create(baseTypeface ?: Typeface.DEFAULT, weight.androidWeight, false)
    isFakeBoldText = false
}
