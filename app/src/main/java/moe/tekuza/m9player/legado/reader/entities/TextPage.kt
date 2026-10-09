package moe.tekuza.m9player.legado.reader.entities

import java.text.DecimalFormat
import moe.tekuza.m9player.legado.reader.M9LayoutMode

internal data class TextPage(
    var index: Int = 0,
    var text: String = "",
    var title: String = "",
    var chapterSize: Int = 0,
    var chapterIndex: Int = 0,
    var pageInChapter: Int = 0,
    var chapterPageCount: Int = 1,
    var globalIndex: Int = 0,
    var totalPages: Int = 1,
    var charStart: Int = 0,
    var charEnd: Int = 0,
    var documentCharStart: Int = 0,
    var documentCharEnd: Int = 0,
    var documentCharCount: Int = 0,
    var isVolume: Boolean = false,
    /**
     * 这一页是按哪种排版模式排出来的。**不能只看行**：卷页（[isVolume]）一行都没有，
     * 而绘制端要靠它决定标题走竖排引擎还是横排 StaticLayout（见 `ContentTextView.onDraw`）。
     * 由 `TextPageFactory.layoutChapter` 按 `config.layoutMode` 统一盖章。
     */
    var layoutMode: M9LayoutMode = M9LayoutMode.HORIZONTAL,
    /**
     * 占位页（邻章未排好时临时顶上的"假页"，照 legado 的 nextPage 语义）。
     * 它没有正文行、没有真实字符区间 ⇒ 命中/选区/进度都不认它，只用于让拖动有内容跟着走。
     */
    var isPlaceholder: Boolean = false,
    var height: Float = 0f,
    var width: Float = 0f,
    /**
     * 沿滚动轴的真实跨度缓存（见 `ContentTextView.pageScrollExtent`）。
     * 布局在绘制之前就完成，页对象在重新分页时整体重建 ⇒ 首次绘制算一次即可，无需失效逻辑。
     */
    var scrollSpanCache: Float = 0f,
    /**
     * [scrollSpanCache] 是按哪个视口算出来的。跨度里含"视口/4"的下限 ⇒ 视口变了（切排版模式、
     * 章首页有无标题预留）就必须重算，否则会沿用旧跨度，点击/滚动步长偏几个百分点。
     */
    var scrollSpanViewport: Float = 0f,
    private val textLines: ArrayList<TextLine> = arrayListOf()
) {
    val lines: List<TextLine> get() = textLines
    val lineSize: Int get() = textLines.size

    val readProgress: String
        get() {
            if (documentCharCount > 0 && documentCharEnd > 0) {
                val value = documentCharEnd.toDouble() / documentCharCount.toDouble()
                return readProgressFormatter.format(value.coerceIn(0.0, 1.0))
            }
            if (totalPages <= 0) return "0.0%"
            val value = (globalIndex + 1).toDouble() / totalPages.toDouble()
            return readProgressFormatter.format(value.coerceIn(0.0, 1.0))
        }

    fun addLine(line: TextLine) {
        textLines += line
    }

    fun getLine(index: Int): TextLine = textLines.getOrElse(index) { textLines.last() }

    fun containPos(chapterPos: Int): Boolean = chapterPos in charStart..<charEnd

    companion object {
        private val readProgressFormatter = DecimalFormat("0.0%")
    }
}
