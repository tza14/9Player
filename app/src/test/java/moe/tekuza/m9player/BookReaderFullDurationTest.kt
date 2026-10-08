package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 整轨时长决策：读完此页暂停 / 逐句重复把播放器裁成一小段后，手表与通知的进度必须仍按整本书算
 * （见 BookReaderPlaybackSession.currentFullDurationMs / publishFullDurationMs）。
 */
class BookReaderFullDurationTest {
    private val fullTrackMs = 9_837_000L // 2:43:57
    private val pageClipMs = 34_000L
    private val cueClipMs = 6_000L

    @Test
    fun withoutClipPlayerDurationIsTheFullTrack() {
        assertEquals(fullTrackMs, resolveFullDurationMs(fullTrackMs, hasActiveClip = false, publishedDurationMs = 0L))
    }

    @Test
    fun clippedPlaybackUsesPublishedFullDurationInsteadOfClipLength() {
        assertEquals(fullTrackMs, resolveFullDurationMs(pageClipMs, hasActiveClip = true, publishedDurationMs = fullTrackMs))
        assertEquals(fullTrackMs, resolveFullDurationMs(cueClipMs, hasActiveClip = true, publishedDurationMs = fullTrackMs))
    }

    @Test
    fun clippedWithoutPublishedDurationStaysUnknownRatherThanTheClipLength() {
        // 关键：绝不能用窗口长度冒充整轨时长 —— 调用方会拿它去夹"绝对目标"。
        assertEquals(0L, resolveFullDurationMs(pageClipMs, hasActiveClip = true, publishedDurationMs = 0L))
        assertEquals(0L, resolveFullDurationMs(cueClipMs, hasActiveClip = true, publishedDurationMs = -5L))
    }

    @Test
    fun publishedDurationWinsEvenBeforePlayerReportsDuration() {
        assertEquals(fullTrackMs, resolveFullDurationMs(0L, hasActiveClip = true, publishedDurationMs = fullTrackMs))
        assertEquals(fullTrackMs, resolveFullDurationMs(0L, hasActiveClip = false, publishedDurationMs = fullTrackMs))
    }

    @Test
    fun unknownEverythingStaysZero() {
        assertEquals(0L, resolveFullDurationMs(0L, hasActiveClip = true, publishedDurationMs = 0L))
        assertEquals(0L, resolveFullDurationMs(0L, hasActiveClip = false, publishedDurationMs = 0L))
    }

    @Test
    fun nonPositiveValuesAreIgnored() {
        assertEquals(0L, resolveFullDurationMs(-1L, hasActiveClip = false, publishedDurationMs = -5L))
        // 裁剪态 + 未发布 ⇒ 0（未知），不是 raw
        assertEquals(0L, resolveFullDurationMs(fullTrackMs, hasActiveClip = true, publishedDurationMs = -5L))
    }
}
