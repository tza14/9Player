package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EbookSpinePartMergeTest {
    private val image = EbookImageRef(path = "OEBPS/pic.jpg", altText = "", mediaType = "image/jpeg")

    /**
     * 纯图片页：正文只有一个图片占位符，没有文字。
     * 默认 titleFromMarkup = false（目录没指向它、文件里也没有标题元素）——
     * 即"插图页"，合并时身份取后面的正文章节。
     */
    private fun imagePage(title: String, imageCount: Int = 1): EbookChapter {
        val text = (1..imageCount).joinToString("\n\n") { EBOOK_IMAGE_MARKER.toString() }
        return EbookChapter(
            title = "$title.xhtml",
            text = text,
            sourcePath = "OEBPS/$title.xhtml",
            images = (0 until imageCount).associateWith { position ->
                image.copy(path = "OEBPS/$title-$position.jpg")
            },
            titleFromMarkup = false
        )
    }

    private fun textChapter(title: String, text: String): EbookChapter =
        EbookChapter(title = title, text = text, sourcePath = "OEBPS/$title.xhtml")

    @Test
    fun mergeSpinePartsIntoChapters_foldsImagePageIntoFollowingChapter() {
        val imagePage = imagePage("part0012")
        val merged = mergeSpinePartsIntoChapters(
            listOf(
                textChapter("ch1", "第一话的正文。"),
                imagePage,
                textChapter("ch2", "第二话的正文。")
            )
        )

        assertEquals(listOf("ch1", "ch2"), merged.map { it.title })
        val second = merged[1]
        assertTrue(second.text.startsWith(EBOOK_IMAGE_MARKER.toString()))
        assertTrue(second.text.endsWith("第二话的正文。"))
        // 图片占位符在开头，偏移 0；正文文字被推到占位符之后
        assertEquals(setOf(0), second.images.keys)
        assertEquals(imagePage.images.getValue(0).path, second.images.getValue(0).path)
    }

    @Test
    fun mergeSpinePartsIntoChapters_appendsTrailingImagePagesToPreviousChapter() {
        val merged = mergeSpinePartsIntoChapters(
            listOf(
                textChapter("ch1", "正文。"),
                imagePage("part0030"),
                imagePage("part0031")
            )
        )

        assertEquals(listOf("ch1"), merged.map { it.title })
        val only = merged.single()
        assertEquals(2, only.images.size)
        assertTrue(only.text.endsWith(EBOOK_IMAGE_MARKER.toString()))
        // 两张图按 spine 顺序排在正文之后，偏移单调递增
        val positions = only.images.keys.sorted()
        assertEquals(2, positions.size)
        assertTrue(positions[0] > "正文。".length)
        assertEquals(only.text.length - 1, positions[1])
    }

    @Test
    fun mergeSpinePartsIntoChapters_keepsLeadingImagePagesInSpineOrder() {
        val merged = mergeSpinePartsIntoChapters(
            listOf(
                imagePage("plate1"),
                imagePage("plate2"),
                textChapter("ch1", "正文。")
            )
        )

        val only = merged.single()
        assertEquals("ch1", only.title)
        val markerCount = only.text.count { it == EBOOK_IMAGE_MARKER }
        assertEquals(2, markerCount)
        // 输出顺序与 spine 一致：先 plate1 后 plate2
        assertEquals(setOf(0, only.text.lastIndexOf(EBOOK_IMAGE_MARKER)), only.images.keys)
        assertEquals("OEBPS/plate1-0.jpg", only.images.getValue(0).path)
        assertEquals(
            "OEBPS/plate2-0.jpg",
            only.images.getValue(only.text.lastIndexOf(EBOOK_IMAGE_MARKER)).path
        )
    }

    @Test
    fun mergeSpinePartsIntoChapters_keepsVolumePageAndDropsEmptyPage() {
        val volumePage = EbookChapter(title = "第一章 幼年期", text = "", isVolume = true)
        val emptyPage = EbookChapter(title = "titlepage.xhtml", text = "")

        val merged = mergeSpinePartsIntoChapters(listOf(volumePage, emptyPage, textChapter("ch1", "正文。")))

        assertEquals(listOf("第一章 幼年期", "ch1"), merged.map { it.title })
        assertTrue(merged[0].isVolume)
    }

    @Test
    fun mergeSpinePartsIntoChapters_shiftsRubySpansOfFollowingChapter() {
        val ruby = EbookRubySpan(
            start = 0,
            end = 2,
            text = "きゆう",
            kind = EbookRubyKind.GROUP,
            segments = listOf(EbookRubySegment(baseStart = 0, baseEnd = 1, text = "き"))
        )
        val target = textChapter("ch1", "杞憂の話。").copy(rubySpans = listOf(ruby))

        val merged = mergeSpinePartsIntoChapters(listOf(imagePage("part0012"), target))

        val shifted = merged.single().rubySpans.single()
        val offset = merged.single().text.indexOf("杞憂")
        assertEquals(offset, shifted.start)
        assertEquals(offset + 2, shifted.end)
        // segment 的 baseStart/baseEnd 是相对 span.start 的偏移，平移时必须保持不变，
        // 否则注音会多偏一份（RubyPlacement.absoluteStart = span.start + segment.baseStart）
        assertEquals(0, shifted.segments.single().baseStart)
        assertEquals(offset + shifted.segments.single().baseStart, merged.single().text.indexOf("杞"))
    }

    @Test
    fun mergeSpinePartsIntoChapters_keepsRubyOnItsBaseCharacter() {
        // 真实形状：扉絵（纯图片页）并进章节开头后，章节正文里的「溢」注音仍应贴在「溢」上。
        val baseText = "思わずそんな言葉が溢れる。"
        val rubyIndex = baseText.indexOf("溢")
        val chapter = textChapter("第三話", baseText).copy(
            rubySpans = listOf(
                EbookRubySpan(
                    start = rubyIndex,
                    end = rubyIndex + 1,
                    text = "あふ",
                    kind = EbookRubyKind.MONO,
                    segments = listOf(EbookRubySegment(baseStart = 0, baseEnd = 1, text = "あふ"))
                )
            )
        )

        val merged = mergeSpinePartsIntoChapters(listOf(imagePage("part0008"), chapter))

        val mergedChapter = merged.single()
        val span = mergedChapter.rubySpans.single()
        val segment = span.segments.single()
        // RubyPlacement 的算法：绝对位置 = span.start + segment.baseStart
        assertEquals(mergedChapter.text.indexOf("溢"), span.start + segment.baseStart)
        assertEquals(mergedChapter.text.indexOf("溢") + 1, span.start + segment.baseEnd)
    }

    @Test
    fun mergeSpinePartsIntoChapters_keepsBookWithoutTextChaptersIntact() {
        val chapters = listOf(imagePage("plate1"), imagePage("plate2"))

        val merged = mergeSpinePartsIntoChapters(chapters)

        assertEquals(2, merged.size)
        assertEquals(listOf("plate1.xhtml", "plate2.xhtml"), merged.map { it.title })
    }

    /** 无标题页（<title> 兜底得到 part0012 这种文件名，不是目录/标题元素给的） */
    private fun untitledChapter(stem: String, text: String): EbookChapter = EbookChapter(
        title = stem,
        text = text,
        sourcePath = "OEBPS/$stem.xhtml",
        titleFromMarkup = false
    )

    @Test
    fun mergeSpinePartsIntoChapters_foldsUntitledPageIntoPreviousChapter() {
        val merged = mergeSpinePartsIntoChapters(
            listOf(
                textChapter("ch1", "第一话的正文。"),
                untitledChapter("part0027", "第一话剩下的正文。"),
                textChapter("ch2", "第二话的正文。")
            )
        )

        assertEquals(listOf("ch1", "ch2"), merged.map { it.title })
        assertEquals("第一话的正文。\n\n第一话剩下的正文。", merged[0].text)
    }

    @Test
    fun mergeSpinePartsIntoChapters_foldsLeadingUntitledPageIntoFollowingChapter() {
        val merged = mergeSpinePartsIntoChapters(
            listOf(
                untitledChapter("part0004", "书名页。"),
                textChapter("ch1", "第一话的正文。")
            )
        )

        assertEquals(listOf("ch1"), merged.map { it.title })
        assertTrue(merged.single().text.startsWith("书名页。"))
    }

    @Test
    fun mergeSpinePartsIntoChapters_foldsUntitledPageAndItsImagesIntoPreviousChapter() {
        val merged = mergeSpinePartsIntoChapters(
            listOf(
                textChapter("番外編", "番外編标题。"),
                untitledChapter("part0029", "番外編正文。"),
                imagePage("part0030"),
                untitledChapter("part0035", "作者简介。"),
                textChapter("奥付", "奥付。")
            )
        )

        assertEquals(listOf("番外編", "奥付"), merged.map { it.title })
        val merged0 = merged[0]
        // 顺序：前章正文 -> 无标题正文 -> 图片 -> 无标题正文
        assertEquals("番外編标题。\n\n番外編正文。\n\n${EBOOK_IMAGE_MARKER}\n\n作者简介。", merged0.text)
        assertEquals(setOf(merged0.text.indexOf(EBOOK_IMAGE_MARKER)), merged0.images.keys)
    }

    @Test
    fun mergeSpinePartsIntoChapters_doesNotMergeWhenBookHasTooFewRealTitles() {
        // nav 只标了第一章、后面都是 calibre 拆分文件的书：宁可不合并，也不能把正文全吞进一章
        val chapters = listOf(
            textChapter("ch1", "第一章正文。"),
            untitledChapter("part0002", "第二章正文。"),
            untitledChapter("part0003", "第三章正文。"),
            untitledChapter("part0004", "第四章正文。")
        )

        val merged = mergeSpinePartsIntoChapters(chapters)

        assertEquals(4, merged.size)
        assertEquals(listOf("ch1", "part0002", "part0003", "part0004"), merged.map { it.title })
    }

    @Test
    fun mergeSpinePartsIntoChapters_keepsGeneratedTitleWhenItCameFromNavigation() {
        val fromNavigation = EbookChapter(
            title = "part0012",
            text = "正文。",
            sourcePath = "OEBPS/part0012.xhtml",
            titleFromMarkup = true
        )

        val merged = mergeSpinePartsIntoChapters(listOf(textChapter("ch1", "第一章。"), fromNavigation))

        assertEquals(2, merged.size)
    }

    /** 「とあるスイーツの店にて」那种：题图页被目录指向，正文页的 <title> 是书名 */
    private fun sectionTitleImagePage(title: String): EbookChapter = EbookChapter(
        title = title,
        text = EBOOK_IMAGE_MARKER.toString(),
        sourcePath = "OEBPS/Text/part0031.xhtml",
        images = mapOf(0 to image.copy(origin = EbookImageOrigin.SECTION_TITLE_PAGE)),
        titleFromMarkup = true
    )

    @Test
    fun mergeSpinePartsIntoChapters_usesSectionTitlePageAsChapterHead() {
        val titlePage = sectionTitleImagePage("とあるスイーツの店にて")
        val body = textChapter("誰が勇者を殺したか【電子特別版】", "　最近の王都はちょっと騒がしい。")

        val merged = mergeSpinePartsIntoChapters(listOf(titlePage, body))

        assertEquals(1, merged.size)
        // 身份取带目录标题的题图页 → 章节标题是目录里的章节名，而不是正文文件的 <title>（书名）
        assertEquals("とあるスイーツの店にて", merged.single().title)
        assertTrue(merged.single().text.endsWith(body.text))
        assertEquals(
            EbookImageOrigin.SECTION_TITLE_PAGE,
            merged.single().images.getValue(0).origin
        )
    }

    @Test
    fun mergeSpinePartsIntoChapters_groupsSplitSegmentsByFileForTheGuard() {
        // 同一个文件切出来的多段（1 段带目录标题 + 3 段无标题续段）算作"1 个文件"：
        // 按章节算的话覆盖率会掉到 2/5，守卫会误判成"目录不可信"而整本不合并。
        val file = "OEBPS/part0009.xhtml"
        fun spill(text: String) = EbookChapter(
            title = "part0009",
            text = text,
            sourcePath = file,
            titleFromMarkup = false
        )
        val titled = EbookChapter(
            title = "第一話",
            text = "第一話の本文。",
            sourcePath = file,
            titleFromMarkup = true
        )

        val merged = mergeSpinePartsIntoChapters(
            listOf(titled, spill("続き。"), spill("さらに続き。"), spill("まだ続く。"), textChapter("ch2", "第二章。"))
        )

        assertEquals(listOf("第一話", "ch2"), merged.map { it.title })
        assertEquals("第一話の本文。\n\n続き。\n\nさらに続き。\n\nまだ続く。", merged[0].text)
    }

    @Test
    fun markImageOrigin_classifiesInlineIllustrationAndSectionTitlePages() {
        val inline = textChapter("ch1", "正文。").copy(images = mapOf(0 to image))
        assertEquals(
            EbookImageOrigin.INLINE,
            inline.markImageOrigin(isSectionTitlePage = false).images.getValue(0).origin
        )
        assertEquals(
            EbookImageOrigin.INLINE,
            inline.markImageOrigin(isSectionTitlePage = true).images.getValue(0).origin
        )

        val illustrationPage = imagePage("part0008")
        assertEquals(
            EbookImageOrigin.ILLUSTRATION_PAGE,
            illustrationPage.markImageOrigin(isSectionTitlePage = false).images.getValue(0).origin
        )
        assertEquals(
            EbookImageOrigin.SECTION_TITLE_PAGE,
            illustrationPage.markImageOrigin(isSectionTitlePage = true).images.getValue(0).origin
        )
    }
}
