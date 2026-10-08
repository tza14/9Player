package moe.tekuza.m9player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class PlaybackNotificationController(
    context: Context,
    private val player: Player,
    private val title: String,
    private val contentIntent: PendingIntent?,
    // 进度口径由调用方给：裁剪态（读完此页暂停 / 逐句重复）下 player 的 position/duration 只是窗口内的值，
    // 共享播放器路径要传「正文绝对位置 + 整轨时长」，否则通知里的百分比会按一页来算。
    private val currentPositionMsProvider: () -> Long,
    private val durationMsProvider: () -> Long,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressJob: Job? = null

    private val routedPlayer = object : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands {
            return super.getAvailableCommands()
                .buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()
        }

        // 读方向：通知 / 锁屏 / 系统媒体控件看到的是**正文绝对位置 + 整轨时长**。
        // 裁剪态下播放器自己的 position/duration 都只是窗口内的值，进度条会显示成"一小段"。
        override fun getCurrentPosition(): Long = currentPositionMsProvider().coerceAtLeast(0L)

        override fun getDuration(): Long = durationMsProvider().coerceAtLeast(0L)

        // 写方向：外部（锁屏拖拽 / 通知按钮 / 车机）给的也是绝对位置 ⇒ **只在这一处**换算回
        // 窗口内位置，绝不在别处再算一遍（两处各算一次就会双倍偏移）。
        override fun seekTo(positionMs: Long) {
            super.seekTo(BookReaderPlaybackSession.toPlayerPositionMs(player, positionMs.coerceAtLeast(0L)))
        }

        // seekBy 在 media3 里是扩展函数（不能 override）⇒ 用自己的私有实现走同一条绝对口径。
        private fun seekByAbsolute(offsetMs: Long) {
            seekTo(currentPositionMsProvider() + offsetMs)
        }

        override fun seekBack() {
            seekByAbsolute(-DEFAULT_NOTIFICATION_SEEK_MS)
        }

        override fun seekForward() {
            seekByAbsolute(DEFAULT_NOTIFICATION_SEEK_MS)
        }

        override fun seekToNextMediaItem() {
            seekByAbsolute(DEFAULT_NOTIFICATION_SEEK_MS)
        }

        override fun seekToPreviousMediaItem() {
            seekByAbsolute(-DEFAULT_NOTIFICATION_SEEK_MS)
        }

        override fun seekToNext() {
            seekByAbsolute(DEFAULT_NOTIFICATION_SEEK_MS)
        }

        override fun seekToPrevious() {
            seekByAbsolute(-DEFAULT_NOTIFICATION_SEEK_MS)
        }
    }
    private val mediaSession = MediaSession.Builder(appContext, routedPlayer)
        .setId("bookreader-${System.currentTimeMillis()}-${System.identityHashCode(this)}")
        .build()
    private val notificationManager = PlayerNotificationManager.Builder(
        appContext,
        NOTIFICATION_ID,
        CHANNEL_ID
    )
        .setMediaDescriptionAdapter(
            object : PlayerNotificationManager.MediaDescriptionAdapter {
                override fun getCurrentContentTitle(player: Player): CharSequence {
                    return title.ifBlank { appContext.getString(R.string.app_name) }
                }

                override fun createCurrentContentIntent(player: Player): PendingIntent? {
                    return contentIntent
                }

                override fun getCurrentContentText(player: Player): CharSequence {
                    val durationMs = durationMsProvider().takeIf { it > 0L }
                    if (durationMs == null) {
                        return appContext.getString(R.string.playback_progress_unknown)
                    }
                    val currentMs = currentPositionMsProvider().coerceIn(0L, durationMs)
                    val percent = ((currentMs.toDouble() / durationMs.toDouble()) * 100.0)
                        .toInt()
                        .coerceIn(0, 100)
                    return appContext.getString(
                        R.string.playback_progress_template,
                        percent,
                        formatPlaybackTime(currentMs),
                        formatPlaybackTime(durationMs)
                    )
                }

                override fun getCurrentLargeIcon(
                    player: Player,
                    callback: PlayerNotificationManager.BitmapCallback
                ): Bitmap? {
                    return null
                }
            }
        )
        .build()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncProgressLoop()
            notificationManager.invalidate()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncProgressLoop()
            notificationManager.invalidate()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            notificationManager.invalidate()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            notificationManager.invalidate()
        }
    }

    init {
        ensureNotificationChannel(appContext)
        notificationManager.setMediaSessionToken(mediaSession.sessionCompatToken)
        notificationManager.setUseNextAction(true)
        notificationManager.setUsePreviousAction(true)
        notificationManager.setUseFastForwardAction(false)
        notificationManager.setUseRewindAction(false)
        notificationManager.setPlayer(routedPlayer)
        player.addListener(playerListener)
        syncProgressLoop()
        notificationManager.invalidate()
    }

    fun release() {
        progressJob?.cancel()
        progressJob = null
        player.removeListener(playerListener)
        notificationManager.setPlayer(null)
        mediaSession.release()
        scope.cancel()
    }

    private fun syncProgressLoop() {
        if (!player.isPlaying || durationMsProvider() <= 0L) {
            progressJob?.cancel()
            progressJob = null
            return
        }
        if (progressJob?.isActive == true) return

        progressJob = scope.launch {
            while (isActive && player.isPlaying) {
                notificationManager.invalidate()
                delay(PROGRESS_REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun ensureNotificationChannel(context: Context) {
        val notificationService = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val existing = notificationService.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.playback_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.playback_notification_channel_description)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationService.createNotificationChannel(channel)
    }

    private fun seekBy(deltaMs: Long) {
        val durationMs = player.duration.takeIf { it > 0L }
        val target = if (durationMs != null) {
            (player.currentPosition + deltaMs).coerceIn(0L, durationMs)
        } else {
            (player.currentPosition + deltaMs).coerceAtLeast(0L)
        }
        player.seekTo(target)
        notificationManager.invalidate()
    }

    companion object {
        private const val CHANNEL_ID = "book_playback"
        private const val NOTIFICATION_ID = 31_001
        private const val PROGRESS_REFRESH_INTERVAL_MS = 1_000L
        private const val DEFAULT_NOTIFICATION_SEEK_MS = 10_000L

    }
}
