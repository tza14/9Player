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

    fun currentPositionMs(): Long = player?.currentPosition?.coerceAtLeast(0L) ?: 0L

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

    fun seekToPosition(positionMs: Long): Long? {
        val sharedPlayer = player ?: return null
        val targetMs = positionMs.coerceAtLeast(0L)
        logDebug(READER_PAUSED_SEEK_LOG_TAG) {
            "session seekToPosition request target=$targetMs ${sharedPlayer.seekStateForLog()}"
        }
        sharedPlayer.seekTo(targetMs)
        logDebug(READER_PAUSED_SEEK_LOG_TAG) {
            "session seekToPosition immediate target=$targetMs ${sharedPlayer.seekStateForLog()}"
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
