package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 遇图暂停里"上一句到当前句之间跨过的插图"这条判定。
 *
 * 背景（真实数据，無職転生 第2巻）：插图页 part0010.xhtml 是独立的纯图片 spine 页，
 * 解析成一章（正文只有图片占位符、无 cue），上一句在上一章末尾、下一句在下一章开头。
 * 停点表给这种章算不出触发句，只能靠这里的跨章区间判定。
 */
class EbookCrossedImageTest {
    private fun image(origin: EbookImageOrigin = EbookImageOrigin.ILLUSTRATION_PAGE) =
        EbookImageRef(
            path = "OEBPS/pic-$origin.jpg",
            altText = "",
            mediaType = "image/jpeg",
            origin = origin
        )

    private fun textChapter(title: String, text: String) =
        EbookChapter(title = title, text = text, sourcePath = "OEBPS/$title.xhtml")

    /** 纯图片页：正文只有图片占位符（与解析结果一致） */
    private fun imagePage(title: String, origin: EbookImageOrigin = EbookImageOrigin.ILLUSTRATION_PAGE) =
        EbookChapter(
            title = title,
            text = EBOOK_IMAGE_MARKER.toString(),
            sourcePath = "OEBPS/$title.xhtml",
            images = mapOf(0 to image(origin)),
            titleFromMarkup = false
        )

    @Test
    fun findCrossedEbookImage_returnsIllustrationPageBetweenTwoSentences() {
        val previousText = "「歳は関係ないと思いますけど」「なに!?　私に文句があるわけ!?」"
        val chapters = listOf(
            textChapter("part0009", previousText),
            imagePage("part0010"),
            textChapter("part0011", "どでかい声だった。耳がキンキンする。")
        )

        // 上一句 = 「なに!?　私に文句があるわけ!?」（16 起、到句尾 33），下一句 = 下一章开头的「どでかい声だった。」
        val crossing = findCrossedEbookImage(
            chapters = chapters,
            previousMatch = EbookCueMatch(cueIndex = 496, chapterIndex = 0, rawStart = 16, rawEnd = 33),
            currentMatch = EbookCueMatch(cueIndex = 497, chapterIndex = 2, rawStart = 0, rawEnd = 8)
        )

        assertEquals(EbookImageCrossing(chapterIndex = 1, imagePosition = 0), crossing)
    }

    @Test
    fun findCrossedEbookImage_ignoresImagesBeforeThePreviousSentenceEnd() {
        val chapters = listOf(
            textChapter("ch1", "前の段落。").copy(
                images = mapOf(3 to image(), 9 to image())
            ),
            textChapter("ch2", "次の段落。")
        )

        // 上一句句尾在 8：位置 3 的图在句子里（读过它了），位置 9 的图在句尾之后 → 停它
        assertEquals(
            EbookImageCrossing(chapterIndex = 0, imagePosition = 9),
            findCrossedEbookImage(
                chapters = chapters,
                previousMatch = EbookCueMatch(cueIndex = 0, chapterIndex = 0, rawStart = 0, rawEnd = 8),
                currentMatch = EbookCueMatch(cueIndex = 1, chapterIndex = 1, rawStart = 0, rawEnd = 5)
            )
        )
    }

    @Test
    fun findCrossedEbookImage_ignoresImagesAfterTheCurrentSentenceStart() {
        val chapters = listOf(
            textChapter("ch1", "前の段落。"),
            textChapter("ch2", "次の段落。").copy(images = mapOf(1 to image(), 30 to image()))
        )

        // 当前句句首在 5：位置 1 的图在它之前（被跨过），位置 30 的图还在后面
        assertEquals(
            EbookImageCrossing(chapterIndex = 1, imagePosition = 1),
            findCrossedEbookImage(
                chapters = chapters,
                previousMatch = EbookCueMatch(cueIndex = 0, chapterIndex = 0, rawStart = 0, rawEnd = 5),
                currentMatch = EbookCueMatch(cueIndex = 1, chapterIndex = 1, rawStart = 5, rawEnd = 11)
            )
        )
        assertNull(
            findCrossedEbookImage(
                chapters = chapters,
                previousMatch = EbookCueMatch(cueIndex = 0, chapterIndex = 0, rawStart = 0, rawEnd = 5),
                currentMatch = EbookCueMatch(cueIndex = 1, chapterIndex = 1, rawStart = 0, rawEnd = 11)
            )
        )
    }

    @Test
    fun findCrossedEbookImage_skipsSectionTitlePages() {
        // 目录指向的纯图片页是章节题图，不是插图：不停，继续看后面的章节
        val chapters = listOf(
            textChapter("ch1", "前の段落。"),
            imagePage("part0008", EbookImageOrigin.SECTION_TITLE_PAGE),
            imagePage("part0010"),
            textChapter("ch2", "次の段落。")
        )

        assertEquals(
            EbookImageCrossing(chapterIndex = 2, imagePosition = 0),
            findCrossedEbookImage(
                chapters = chapters,
                previousMatch = EbookCueMatch(cueIndex = 0, chapterIndex = 0, rawStart = 0, rawEnd = 5),
                currentMatch = EbookCueMatch(cueIndex = 1, chapterIndex = 3, rawStart = 0, rawEnd = 5)
            )
        )
    }

    @Test
    fun findCrossedEbookImage_skipsTheImageThatJustPausedAndKeepsLooking() {
        val chapters = listOf(
            textChapter("ch1", "前の段落。"),
            imagePage("part0010"),
            imagePage("part0012"),
            textChapter("ch2", "次の段落。")
        )
        val previousMatch = EbookCueMatch(cueIndex = 0, chapterIndex = 0, rawStart = 0, rawEnd = 5)
        val currentMatch = EbookCueMatch(cueIndex = 1, chapterIndex = 3, rawStart = 0, rawEnd = 5)
        val justPaused = EbookImageCrossing(chapterIndex = 1, imagePosition = 0)

        assertEquals(
            EbookImageCrossing(chapterIndex = 2, imagePosition = 0),
            findCrossedEbookImage(
                chapters = chapters,
                previousMatch = previousMatch,
                currentMatch = currentMatch,
                isExcluded = { it == justPaused }
            )
        )
    }

    @Test
    fun findCrossedEbookImage_returnsNullWhenSentencesGoBackwards() {
        val chapters = listOf(
            textChapter("ch1", "前の段落。").copy(images = mapOf(0 to image())),
            textChapter("ch2", "次の段落。")
        )

        // 跨章回退：当前句在前一章
        assertNull(
            findCrossedEbookImage(
                chapters = chapters,
                previousMatch = EbookCueMatch(cueIndex = 9, chapterIndex = 1, rawStart = 0, rawEnd = 5),
                currentMatch = EbookCueMatch(cueIndex = 3, chapterIndex = 0, rawStart = 0, rawEnd = 5)
            )
        )
        // 同一章里往前跳（回退）也不停
        assertNull(
            findCrossedEbookImage(
                chapters = chapters,
                previousMatch = EbookCueMatch(cueIndex = 9, chapterIndex = 0, rawStart = 5, rawEnd = 10),
                currentMatch = EbookCueMatch(cueIndex = 3, chapterIndex = 0, rawStart = 2, rawEnd = 5)
            )
        )
    }

    @Test
    fun findCrossedEbookImage_ignoresChaptersAfterTheCurrentSentence() {
        val chapters = listOf(
            textChapter("ch1", "前の段落。"),
            textChapter("ch2", "次の段落。"),
            imagePage("part0012")
        )

        assertNull(
            findCrossedEbookImage(
                chapters = chapters,
                previousMatch = EbookCueMatch(cueIndex = 0, chapterIndex = 0, rawStart = 0, rawEnd = 5),
                currentMatch = EbookCueMatch(cueIndex = 1, chapterIndex = 1, rawStart = 0, rawEnd = 5)
            )
        )
    }

    @Test
    fun findCrossedEbookImage_toleratesMissingChapters() {
        val chapters = listOf(
            textChapter("ch1", "前の段落。").copy(images = mapOf(1 to image()))
        )

        assertEquals(
            EbookImageCrossing(chapterIndex = 0, imagePosition = 1),
            findCrossedEbookImage(
                chapters = chapters,
                previousMatch = EbookCueMatch(cueIndex = 0, chapterIndex = 0, rawStart = 0, rawEnd = 0),
                currentMatch = EbookCueMatch(cueIndex = 1, chapterIndex = 5, rawStart = 0, rawEnd = 0)
            )
        )
    }
}
