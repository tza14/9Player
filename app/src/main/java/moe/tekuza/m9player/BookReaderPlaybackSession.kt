package moe.tekuza.m9player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object BookReaderPlaybackSession {
    private const val SEEK_INCREMENT_MS = 10_000L
    private const val READER_PAUSED_SEEK_LOG_TAG = "ReaderPausedSeek"

    @Volatile
    private var player: ExoPlayer? = null

    @Volatile
    private var currentAudioUriText: String? = null

    @Synchronized
    fun acquirePlayer(context: Context): ExoPlayer {
        val existing = player
        if (existing != null) return existing
        return ExoPlayer.Builder(context.applicationContext)
            .setSeekBackIncrementMs(SEEK_INCREMENT_MS)
            .setSeekForwardIncrementMs(SEEK_INCREMENT_MS)
            .build()
            .also { sharedPlayer ->
                sharedPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                        .build(),
                    true
                )
                sharedPlayer.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        BookReaderFloatingBridge.notifyPlaybackState(isPlaying)
                        if (loadWearableFeatureEnabled(context)) startWearableBridgeService(context)
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        if (
                            reason == Player.DISCONTINUITY_REASON_SEEK ||
                            reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                        ) {
                            logDebug(READER_PAUSED_SEEK_LOG_TAG) {
                                "session discontinuity reason=$reason old=${oldPosition.positionMs} " +
                                "new=${newPosition.positionMs} ${sharedPlayer.seekStateForLog()}"
                            }
                        }
                    }
                })
                player = sharedPlayer
            }
    }

    fun currentAudioUri(): String? = currentAudioUriText

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun isPlaybackRequested(): Boolean = player?.let { it.playWhenReady || it.isPlaying } == true

    /**
     * 播放器上**实际生效**的裁剪窗口（绝对 ms 起止）；没有裁剪时 null —— "有没有裁剪"的唯一真相。
     * 裁剪是本项目自己通过 [MediaItem.ClippingConfiguration] 施加的（读完此页暂停 / 逐句重复），
     * 但别的界面（主页 / 播放器页）换音频时会写入无裁剪的 MediaItem，阅读器本地旗标可能已经过期，
     * 所以阅读器的对账与显示侧的位置换算都以它为准。
     */
    fun activeClipRangeMs(sharedPlayer: Player?): Pair<Long, Long>? {
        val clipping = sharedPlayer?.currentMediaItem?.clippingConfiguration ?: return null
        // 未裁剪的 MediaItem 也带 ClippingConfiguration：Builder 只把 endPositionUs 设成 TIME_UNSET，
        // startPositionUs 留 0。所以「两个都是 TIME_UNSET」判不出未裁剪 —— 那样 start=0/end=未设
        // 会被当成 [0, 末尾] 的裁剪窗口，使"播放中每次 cue 变化都重载一次播放器"（正在读的那句
        // 从头重读）。只有 start>0 或 end>0 才算真的裁过。
        val start = clipping.startPositionMs
        val end = clipping.endPositionMs
        val safeStart = if (start > 0L) start else 0L
        val safeEnd = if (end > 0L) end else Long.MAX_VALUE
        if (safeStart == 0L && safeEnd == Long.MAX_VALUE) return null
        return safeStart to safeEnd
    }

    /**
     * 播放器 raw 位置 → 正文绝对位置（裁剪态下加回窗口起点），给显示侧与阅读器的**读**方向用。
     * 与 [toPlayerPositionMs] 互为反向，两边都只在这一层换算一次：各算一次会双倍偏移（实测教训：
     * 目标被减成负数 → 夹到窗口起点 → 用户看到"音频回到最一开始 + 前进后退失效"）。
     */
    fun toAbsolutePositionMs(sharedPlayer: Player?, rawPositionMs: Long): Long {
        val clip = activeClipRangeMs(sharedPlayer) ?: return rawPositionMs.coerceAtLeast(0L)
        return clip.first + rawPositionMs.coerceAtLeast(0L)
    }

    /**
     * 正文绝对位置 → 播放器 raw 位置（[toAbsolutePositionMs] 的反向），**写方向（seek）的唯一换算点**。
     *
     * 判据取播放器**实配**的裁剪窗口，而不是调用方的本地旗标：seek 的解释方式由已经装上的 item 决定，
     * 本地旗标可能已经过期（别的界面换过音频）。
     */
    fun toPlayerPositionMs(sharedPlayer: Player?, absoluteMs: Long): Long {
        val clip = activeClipRangeMs(sharedPlayer) ?: return absoluteMs.coerceAtLeast(0L)
        return (absoluteMs - clip.first).coerceIn(0L, (clip.second - clip.first).coerceAtLeast(1L))
    }

    fun currentPositionMs(): Long {
        val sharedPlayer = player ?: return 0L
        return toAbsolutePositionMs(sharedPlayer, sharedPlayer.currentPosition.coerceAtLeast(0L))
    }

    /**
     * 注意：裁剪态下这是**裁剪段**时长（手表进度会显示成一小段）；完整时长目前没有发布路径，
     * 需要时要由阅读器把裁剪前的完整时长发到 [BookReaderFloatingBridge]。
     */
    fun currentDurationMs(): Long = player?.duration?.takeIf { it > 0L } ?: 0L

    fun currentPlaybackSpeed(): Float = player?.playbackParameters?.speed ?: 1f

    fun setPlaying(play: Boolean) {
        val sharedPlayer = player ?: return
        if (play) {
            sharedPlayer.play()
        } else {
            sharedPlayer.pause()
        }
    }

    fun togglePlayPause() {
        val sharedPlayer = player ?: return
        if (sharedPlayer.playWhenReady || sharedPlayer.isPlaying) {
            sharedPlayer.pause()
        } else {
            sharedPlayer.play()
        }
    }

    /** [positionMs] 是**正文绝对位置**（与 [currentPositionMs] 同一口径）；换算成播放器 raw 后再 seek。 */
    fun seekToPosition(positionMs: Long): Long? {
        val sharedPlayer = player ?: return null
        val targetMs = positionMs.coerceAtLeast(0L)
        val rawTargetMs = toPlayerPositionMs(sharedPlayer, targetMs)
        logDebug(READER_PAUSED_SEEK_LOG_TAG) {
            "session seekToPosition request target=$targetMs raw=$rawTargetMs ${sharedPlayer.seekStateForLog()}"
        }
        sharedPlayer.seekTo(rawTargetMs)
        logDebug(READER_PAUSED_SEEK_LOG_TAG) {
            "session seekToPosition immediate target=$targetMs raw=$rawTargetMs ${sharedPlayer.seekStateForLog()}"
        }
        return targetMs
    }

    private fun Player.seekStateForLog(): String {
        return "actual=$currentPosition duration=$duration playWhenReady=$playWhenReady " +
            "isPlaying=$isPlaying state=$playbackState suppression=$playbackSuppressionReason"
    }

    fun setPlaybackSpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed.coerceIn(0.5f, 3.0f))
    }

    @Synchronized
    fun prepareAudioIfNeeded(
        context: Context,
        audioUri: Uri,
        restorePositionMs: Long = 0L,
        forceSeekOnSameAudio: Boolean = false
    ): ExoPlayer {
        val sharedPlayer = acquirePlayer(context)
        val targetUriText = audioUri.toString()
        val currentUriText = currentAudioUriText
        if (currentUriText != targetUriText) {
            sharedPlayer.setMediaItem(MediaItem.fromUri(audioUri))
            sharedPlayer.prepare()
            sharedPlayer.seekTo(restorePositionMs.coerceAtLeast(0L))
            currentAudioUriText = targetUriText
            return sharedPlayer
        }
        if (
            forceSeekOnSameAudio &&
            restorePositionMs > 0L &&
            abs(sharedPlayer.currentPosition - restorePositionMs) > 800L
        ) {
            sharedPlayer.seekTo(restorePositionMs.coerceAtLeast(0L))
        }
        return sharedPlayer
    }
}
