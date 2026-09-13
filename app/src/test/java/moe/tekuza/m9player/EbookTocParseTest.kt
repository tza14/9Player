package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目录解析：NCX（navPoint 嵌套）与 EPUB3 nav（ol/li 嵌套）。
 *
 * 回归点：《心理学原理》的 toc.ncx 是 **10 个大章节各带 1 个子节点** 的嵌套结构。
 * 老实现用一对 `currentTitle`/`currentSrc` 而不是栈，子节点的 START_TAG 会把父节点刚读到
 * 的数据清掉，于是父节点 END 时又照子节点产出一条：20 个 navPoint 解析成 20 条、但只有
 * 10 个不同锚点，**10 个大章节标题全部丢失**（"心理学的范围 Chapter 1 …" 这些条目在
 * 阅读器里根本不存在，只有"第一章 …"）。这就是"legadoF/Hoshi 认得出大章节、我们没有"的原因。
 */
class EbookTocParseTest {
    // ---- NCX ----
    // parseNcxToc 走 DOM，所以这里能直接喂 NCX 字符串：连实体解码（&#160;）和命名空间
    // 都是真实路径，不需要手搭事件流。

    private fun ncx(body: String) = """
        <?xml version='1.0' encoding='utf-8'?>
        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
        <navMap>$body</navMap>
        </ncx>
    """.trimIndent()

    private fun navPoint(title: String, src: String, children: String = "") = """
        <navPoint><navLabel><text>$title</text></navLabel><content src="$src"/>$children</navPoint>
    """.trimIndent()

    /** 《心理学原理》的形状：每个大章节各带一个子节点，标题里用 &#160; 做间距 */
    private val nestedNcx = ncx(
        navPoint("前&#160; 言", "Text/part0001.xhtml#toc-anchor", navPoint("詹姆斯和他的作品", "Text/part0001.xhtml#toc-anchor-10")) +
            navPoint(
                "心理学的范围&#160; Chapter 1",
                "Text/part0001.xhtml#toc-anchor-1",
                navPoint("第一章&#160; 心理学的范围", "Text/part0001.xhtml#toc-anchor-11")
            )
    )

    @Test
    fun parseNcxToc_keepsParentTitlesAndDoesNotDuplicateChildren() {
        val entries = parseNcxToc(nestedNcx, "OEBPS")

        assertEquals(4, entries.size)
        // 父条目在前、子条目在后（前序），父标题没有被子节点顶掉；&#160; 也被归一化成普通空格
        assertEquals(
            listOf("前 言", "詹姆斯和他的作品", "心理学的范围 Chapter 1", "第一章 心理学的范围"),
            entries.map { it.title }
        )
        assertEquals(
            listOf("toc-anchor", "toc-anchor-10", "toc-anchor-1", "toc-anchor-11"),
            entries.map { it.fragment }
        )
        // 老实现（一对 currentTitle/currentSrc）会在这里给出
        // ["詹姆斯", "詹姆斯", "第一章", "第一章"] —— 只有 2 个不同锚点，父标题全丢
        assertEquals(4, entries.map { it.fragment }.distinct().size)
    }

    @Test
    fun parseNcxToc_recordsLevelAndGroupFromNesting() {
        val entries = parseNcxToc(nestedNcx, "OEBPS")

        assertEquals(listOf(0, 1, 0, 1), entries.map { it.level })
        // 有子条目的那两条是"大章节"，叶子不是
        assertEquals(listOf(true, false, true, false), entries.map { it.isGroup })
    }

    @Test
    fun parseNcxToc_handlesDeeperNesting() {
        val deep = ncx(
            navPoint("卷", "a.xhtml", navPoint("章", "b.xhtml", navPoint("节", "c.xhtml")))
        )

        val entries = parseNcxToc(deep, "")

        assertEquals(listOf("卷", "章", "节"), entries.map { it.title })
        assertEquals(listOf(0, 1, 2), entries.map { it.level })
        assertEquals(listOf(true, true, false), entries.map { it.isGroup })
    }

    @Test
    fun parseNcxToc_keepsEverySiblingUnderTheSameParent() {
        val twoChildren = ncx(
            navPoint(
                "父",
                "a.xhtml",
                navPoint("子一", "a.xhtml#c1") + navPoint("子二", "a.xhtml#c2")
            )
        )

        val entries = parseNcxToc(twoChildren, "")

        assertEquals(listOf("父", "子一", "子二"), entries.map { it.title })
        assertEquals(listOf(0, 1, 1), entries.map { it.level })
        assertTrue(entries[0].isGroup)
    }

    @Test
    fun parseNcxToc_skipsEntriesWithoutTitleOrTarget() {
        val broken = ncx(
            navPoint("", "a.xhtml#c1") + navPoint("有标题但没目标", "") + navPoint("正常", "b.xhtml#c2")
        )

        val entries = parseNcxToc(broken, "")

        assertEquals(listOf("正常"), entries.map { it.title })
    }

    @Test
    fun parseNcxToc_handlesNamespacePrefixedNcx() {
        // 节点按 localName 匹配，所以 <ncx:navPoint> 这种带前缀的 NCX 也要认
        val prefixed = """
            <?xml version='1.0' encoding='utf-8'?>
            <ncx:ncx xmlns:ncx="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
            <ncx:navMap>
            <ncx:navPoint><ncx:navLabel><ncx:text>第一章</ncx:text></ncx:navLabel><ncx:content src="a.xhtml#c1"/></ncx:navPoint>
            </ncx:navMap>
            </ncx:ncx>
        """.trimIndent()

        val entries = parseNcxToc(prefixed, "")

        assertEquals(listOf("第一章"), entries.map { it.title })
        assertEquals(listOf("c1"), entries.map { it.fragment })
    }

    @Test
    fun parseNcxToc_doesNotFetchTheDoctypeDtdOfRealWorldNcxFiles() {
        // 真实 EPUB2 的 NCX 常带 NISO 的 DOCTYPE；EntityResolver 会把外部实体吞掉，
        // 所以既不能去下载它，也不能像 disallow-doctype-decl 那样把整份目录判死
        val withDoctype = """
            <?xml version='1.0' encoding='utf-8'?>
            <!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
            <navMap>
            <navPoint><navLabel><text>第一章</text></navLabel><content src="a.xhtml#c1"/></navPoint>
            </navMap>
            </ncx>
        """.trimIndent()

        val entries = parseNcxToc(withDoctype, "")

        assertEquals(listOf("第一章"), entries.map { it.title })
    }

    @Test
    fun parseNcxToc_returnsEmptyForBrokenXml() {
        assertEquals(emptyList<EpubTocEntry>(), parseNcxToc("<ncx><navMap>", "OEBPS"))
    }

    // ---- EPUB3 nav ----

    /** 真实形状：`<li><a href>` + 嵌套 `<ol>`；最后一条父节点没有链接（`<span>`） */
    private val navHtml = """
        <nav epub:type="toc"><ol>
          <li><a href="part0001.xhtml#toc-anchor-1">心理学的范围</a>
            <ol><li><a href="part0001.xhtml#toc-anchor-11">第一章 心理学的范围</a></li></ol>
          </li>
          <li><span>第二部</span>
            <ol><li><a href="part0001.xhtml#toc-anchor-12">第二章 大脑的功能</a></li></ol>
          </li>
        </ol></nav>
    """.trimIndent()

    @Test
    fun parseEpubNavTocItems_levelsFollowOlNesting() {
        val items = parseEpubNavTocItems(navHtml)

        assertEquals(
            listOf("心理学的范围", "第一章 心理学的范围", "第二章 大脑的功能"),
            items.map { it.title }
        )
        assertEquals(listOf(0, 1, 1), items.map { it.level })
        assertEquals(listOf(true, false, false), items.map { it.isGroup })
        // 没有 <a> 的父条目（`<span>第二部</span>`）自己不产出行，但它的 <ol> 照样算层级，
        // 所以下面这条子条目的缩进仍然是对的
        assertEquals("part0001.xhtml#toc-anchor-12", items.last().href)
    }

    @Test
    fun parseEpubNavTocItems_flatListStaysAtTopLevel() {
        val flat = """<nav epub:type="toc"><ol>
            <li><a href="a.xhtml">A</a></li>
            <li><a href="b.xhtml#s">B</a></li>
        </ol></nav>"""

        val items = parseEpubNavTocItems(flat)

        assertEquals(listOf(0, 0), items.map { it.level })
        assertEquals(listOf(false, false), items.map { it.isGroup })
    }
}
