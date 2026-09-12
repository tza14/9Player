package moe.tekuza.m9player

import android.content.Context
import android.os.SystemClock

object BookReaderFloatingBridge {
    private const val READER_PAUSED_SEEK_LOG_TAG = "ReaderPausedSeek"
    private const val WEARABLE_SEEK_LOG_TAG = "WearableSeek"

    data class SubtitleTimelineCue(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val fullSentenceText: String? = null,
        val fullSentenceStartMs: Long? = null,
        val fullSentenceEndMs: Long? = null
    )

    data class CueSnapshot(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val bookTitle: String?,
        val audioUri: String?,
        val fullSentenceText: String?,
        val fullSentenceStartMs: Long?,
        val fullSentenceEndMs: Long?
    )

    interface PlaybackStateListener {
        fun onPlaybackStateChanged(isPlaying: Boolean)
    }

    interface SubtitleStateListener {
        fun onSubtitleChanged(text: String?)
    }

    interface PlaybackPositionListener {
        fun onPlaybackPositionChanged(positionMs: Long)
    }

    interface PlaybackSpeedListener {
        fun onPlaybackSpeedChanged(speed: Float)
    }

    data class ControlCollectResult(val keepScreenOnMs: Long)

    private var sleepTimerListener: ((Int) -> Boolean)? = null
    private var sleepTimerDeadlineMs: Long? = null

    private val listeners = linkedSetOf<PlaybackStateListener>()
    private val subtitleListeners = linkedSetOf<SubtitleStateListener>()
    private val playbackPositionListeners = linkedSetOf<PlaybackPositionListener>()
    private val playbackSpeedListeners = linkedSetOf<PlaybackSpeedListener>()
    private var controlCollectListener: (() -> ControlCollectResult?)? = null
    private var adjacentSeekListener: ((Int) -> Unit)? = null
    @Volatile
    private var playingSnapshot: Boolean = false
    @Volatile
    private var currentAudioUriSnapshot: String? = null
    @Volatile
    private var uiTestModeSnapshot: Boolean = false
    @Volatile
    private var subtitleSnapshot: String? = null
    @Volatile
    private var cueSnapshot: CueSnapshot? = null
    private var subtitleTimelineSnapshot: List<SubtitleTimelineCue> = emptyList()
    private var subtitleTimelineBookTitle: String? = null
    private var subtitleTimelineAudioUri: String? = null
    @Volatile
    private var subtitleTrackAvailableSnapshot: Boolean = false
    @Volatile
    private var playbackPositionSnapshot: Long = 0L
    @Volatile
    private var playbackSpeedSnapshot: Float = 1f
    @Volatile
    private var statisticsContext: Context? = null
    @Volatile
    private var currentBookKeySnapshot: String? = null
    private var lastListeningRealtimeMs: Long? = null
    private var pendingListeningMs: Long = 0L

    private data class ListeningStat(
        val context: Context,
        val bookKey: String,
        val elapsedMs: Long
    )

    fun addPlaybackStateListener(listener: PlaybackStateListener) {
        synchronized(this) {
            listeners += listener
        }
        listener.onPlaybackStateChanged(playingSnapshot)
    }

    fun removePlaybackStateListener(listener: PlaybackStateListener) {
        synchronized(this) {
            listeners -= listener
        }
    }

    fun addSubtitleStateListener(listener: SubtitleStateListener) {
        synchronized(this) {
            subtitleListeners += listener
        }
        listener.onSubtitleChanged(subtitleSnapshot)
    }

    fun removeSubtitleStateListener(listener: SubtitleStateListener) {
        synchronized(this) {
            subtitleListeners -= listener
        }
    }

    fun addPlaybackPositionListener(listener: PlaybackPositionListener) {
        synchronized(this) {
            playbackPositionListeners += listener
        }
        listener.onPlaybackPositionChanged(playbackPositionSnapshot)
    }

    fun removePlaybackPositionListener(listener: PlaybackPositionListener) {
        synchronized(this) {
            playbackPositionListeners -= listener
        }
    }

    fun addPlaybackSpeedListener(listener: PlaybackSpeedListener) {
        synchronized(this) {
            playbackSpeedListeners += listener
        }
        listener.onPlaybackSpeedChanged(playbackSpeedSnapshot)
    }

    fun removePlaybackSpeedListener(listener: PlaybackSpeedListener) {
        synchronized(this) {
            playbackSpeedListeners -= listener
        }
    }

    fun notifyPlaybackState(isPlaying: Boolean) {
        val snapshot: List<PlaybackStateListener>
        val listeningStat: ListeningStat?
        synchronized(this) {
            playingSnapshot = isPlaying
            listeningStat = if (isPlaying) {
                lastListeningRealtimeMs = SystemClock.elapsedRealtime()
                null
            } else {
                flushListeningStatLocked()
            }
            snapshot = listeners.toList()
        }
        listeningStat?.let { recordStatisticsListening(it.context, it.bookKey, it.elapsedMs) }
        snapshot.forEach { it.onPlaybackStateChanged(isPlaying) }
    }

    fun isPlaying(): Boolean = if (BookReaderPlaybackSession.currentAudioUri() != null) {
        // Keep the wearable state aligned with the reader UI. During a seek or
        // a short buffering transition ExoPlayer.isPlaying can be false even
        // though playback is still requested and will continue automatically.
        BookReaderPlaybackSession.isPlaybackRequested()
    } else {
        playingSnapshot
    }
    fun currentAudioUri(): String? = currentAudioUriSnapshot
    fun isUiTestModeActive(): Boolean = uiTestModeSnapshot
    fun currentSubtitle(): String? = synchronized(this) {
        if (subtitleTimelineSnapshot.isNotEmpty()) {
            subtitleTimelineCueAtPositionLocked(currentPlaybackPositionMs())?.text
        } else {
            subtitleSnapshot
        }
    }
    fun currentCue(): CueSnapshot? = synchronized(this) {
        if (subtitleTimelineSnapshot.isNotEmpty()) {
            subtitleTimelineCueAtPositionLocked(currentPlaybackPositionMs())
        } else {
            cueSnapshot
        }
    }
    fun currentBookTitle(): String? = synchronized(this) { subtitleTimelineBookTitle }
    fun hasSubtitleTrack(): Boolean = subtitleTrackAvailableSnapshot
    fun currentPlaybackPositionMs(): Long =
        if (BookReaderPlaybackSession.currentAudioUri() != null) BookReaderPlaybackSession.currentPositionMs() else playbackPositionSnapshot
    fun currentPlaybackDurationMs(): Long =
        if (BookReaderPlaybackSession.currentAudioUri() != null) BookReaderPlaybackSession.currentDurationMs() else 0L
    fun currentPlaybackSpeed(): Float =
        if (BookReaderPlaybackSession.currentAudioUri() != null) BookReaderPlaybackSession.currentPlaybackSpeed() else playbackSpeedSnapshot
    fun currentBookKey(): String? = currentBookKeySnapshot

    fun setCurrentBookKey(context: Context, bookKey: String?) {
        val normalized = bookKey?.trim()?.takeIf { it.isNotBlank() }
        val listeningStat: ListeningStat?
        synchronized(this) {
            val changed = currentBookKeySnapshot != normalized
            listeningStat = if (changed) flushListeningStatLocked() else null
            statisticsContext = context.applicationContext
            currentBookKeySnapshot = normalized
            if (changed) resetListeningStatLocked()
        }
        listeningStat?.let { recordStatisticsListening(it.context, it.bookKey, it.elapsedMs) }
    }

    fun setCurrentAudioUri(audioUri: String?) {
        val normalized = audioUri?.takeIf { it.isNotBlank() }
        synchronized(this) {
            currentAudioUriSnapshot = normalized
        }
    }

    fun setUiTestModeActive(active: Boolean) {
        synchronized(this) {
            uiTestModeSnapshot = active
        }
    }

    fun setSubtitleTimeline(
        bookTitle: String?,
        audioUri: String?,
        cues: List<SubtitleTimelineCue>
    ) {
        val listeners: List<SubtitleStateListener>
        val nextText: String?
        synchronized(this) {
            val previousText = subtitleSnapshot
            subtitleTimelineSnapshot = cues
                .filter { it.text.isNotBlank() && it.endMs > it.startMs }
                .sortedBy { it.startMs }
            subtitleTimelineBookTitle = bookTitle?.takeIf { it.isNotBlank() }
            subtitleTimelineAudioUri = audioUri?.takeIf { it.isNotBlank() }
            subtitleTrackAvailableSnapshot = subtitleTimelineSnapshot.isNotEmpty()
            cueSnapshot = subtitleTimelineCueAtPositionLocked(currentPlaybackPositionMs())
            subtitleSnapshot = cueSnapshot?.text
            listeners = if (previousText != subtitleSnapshot) subtitleListeners.toList() else emptyList()
            nextText = subtitleSnapshot
        }
        listeners.forEach { it.onSubtitleChanged(nextText) }
    }

    fun notifyPlaybackPosition(positionMs: Long) {
        val normalized = positionMs.coerceAtLeast(0L)
        val snapshot: List<PlaybackPositionListener>
        val subtitleSnapshotListeners: List<SubtitleStateListener>
        val subtitleText: String?
        val listeningStat: ListeningStat?
        synchronized(this) {
            playbackPositionSnapshot = normalized
            snapshot = playbackPositionListeners.toList()
            listeningStat = accumulateListeningStatLocked(SystemClock.elapsedRealtime())
            subtitleSnapshotListeners = updateTimelineCueAtPositionLocked(normalized)
            subtitleText = subtitleSnapshot
        }
        listeningStat?.let { recordStatisticsListening(it.context, it.bookKey, it.elapsedMs) }
        snapshot.forEach { it.onPlaybackPositionChanged(normalized) }
        subtitleSnapshotListeners.forEach { it.onSubtitleChanged(subtitleText) }
    }

    private fun accumulateListeningStatLocked(nowMs: Long): ListeningStat? {
        val context = statisticsContext
        val bookKey = currentBookKeySnapshot
        if (!playingSnapshot || context == null || bookKey.isNullOrBlank()) {
            resetListeningStatLocked()
            return null
        }
        val last = lastListeningRealtimeMs
        if (last == null) {
            lastListeningRealtimeMs = nowMs
            return null
        }
        val elapsed = (nowMs - last).coerceIn(0L, 10_000L)
        lastListeningRealtimeMs = nowMs
        if (elapsed <= 0L) return null
        pendingListeningMs += elapsed
        if (pendingListeningMs < 2_500L) return null
        val stat = ListeningStat(context, bookKey, pendingListeningMs)
        pendingListeningMs = 0L
        return stat
    }

    private fun flushListeningStatLocked(): ListeningStat? {
        val context = statisticsContext
        val bookKey = currentBookKeySnapshot
        val elapsed = pendingListeningMs
        pendingListeningMs = 0L
        lastListeningRealtimeMs = null
        if (context == null || bookKey.isNullOrBlank() || elapsed <= 0L) return null
        return ListeningStat(context, bookKey, elapsed)
    }

    private fun resetListeningStatLocked() {
        lastListeningRealtimeMs = null
        pendingListeningMs = 0L
    }

    fun notifyPlaybackSpeed(speed: Float) {
        val normalized = if (speed.isFinite() && speed > 0f) speed else 1f
        val snapshot: List<PlaybackSpeedListener>
        synchronized(this) {
            playbackSpeedSnapshot = normalized
            snapshot = playbackSpeedListeners.toList()
        }
        snapshot.forEach { it.onPlaybackSpeedChanged(normalized) }
    }

    fun togglePlayPause() {
        BookReaderPlaybackSession.togglePlayPause()
        notifyPlaybackState(BookReaderPlaybackSession.isPlaying())
    }

    fun setPlaying(play: Boolean) {
        BookReaderPlaybackSession.setPlaying(play)
        notifyPlaybackState(BookReaderPlaybackSession.isPlaying())
    }

    fun seekToPosition(targetPositionMs: Long) {
        val normalized = targetPositionMs.coerceAtLeast(0L)
        logDebug(READER_PAUSED_SEEK_LOG_TAG) {
            "bridge seekToPosition request target=$normalized beforeSession=${BookReaderPlaybackSession.currentPositionMs()} " +
            "beforeBridge=$playbackPositionSnapshot cue=${cueForLog(currentCue())}"
        }
        BookReaderPlaybackSession.seekToPosition(normalized)
        notifyPlaybackPosition(normalized)
        logDebug(READER_PAUSED_SEEK_LOG_TAG) {
            "bridge seekToPosition notify target=$normalized afterSession=${BookReaderPlaybackSession.currentPositionMs()} " +
            "afterBridge=$playbackPositionSnapshot cue=${cueForLog(currentCue())}"
        }
    }

    fun setControlCollectListener(listener: (() -> ControlCollectResult?)?) {
        synchronized(this) {
            controlCollectListener = listener
        }
    }

    fun setAdjacentSeekListener(listener: ((Int) -> Unit)?) {
        synchronized(this) { adjacentSeekListener = listener }
    }

    fun seekAdjacent(context: Context, step: Int) {
        val listener = synchronized(this) { adjacentSeekListener }
        logDebug(WEARABLE_SEEK_LOG_TAG) { "request step=$step path=${if (listener != null) "reader" else "fallback"}" }
        if (listener != null) {
            listener(step)
        } else {
            val stepMillis = runCatching {
                loadAudiobookSettingsConfig(context).seekStepMillis
            }.getOrNull() ?: return
            val delta = if (step < 0) -stepMillis else stepMillis
            val current = currentPlaybackPositionMs()
            val duration = currentPlaybackDurationMs()
            val target = (current + delta).coerceAtLeast(0L).let {
                if (duration > 0L) it.coerceAtMost(duration) else it
            }
            logDebug(WEARABLE_SEEK_LOG_TAG) {
                "fallback stepMs=$stepMillis current=$current duration=$duration target=$target"
            }
            seekToPosition(target)
        }
    }

    fun requestControlCollect(): ControlCollectResult? {
        val listener = synchronized(this) { controlCollectListener }
        return listener?.invoke()
    }

    fun setSleepTimerListener(listener: ((Int) -> Boolean)?) {
        synchronized(this) { sleepTimerListener = listener }
    }

    fun requestSleepTimer(minutes: Int): Boolean {
        val listener = synchronized(this) { sleepTimerListener }
        return listener?.invoke(minutes) == true
    }

    fun setSleepTimerDeadline(deadlineMs: Long?) {
        synchronized(this) { sleepTimerDeadlineMs = deadlineMs }
    }

    fun currentSleepTimerRemainingMs(): Long = synchronized(this) {
        (sleepTimerDeadlineMs?.minus(System.currentTimeMillis()) ?: 0L).coerceAtLeast(0L)
    }

    fun setPlaybackSpeed(speed: Float) {
        BookReaderPlaybackSession.setPlaybackSpeed(speed)
        notifyPlaybackSpeed(BookReaderPlaybackSession.currentPlaybackSpeed())
    }

    fun replayCurrentCue() {
        val cueStartMs = currentCue()?.startMs ?: return
        logDebug(READER_PAUSED_SEEK_LOG_TAG) {
            "bridge replayCurrentCue target=$cueStartMs beforeSession=${BookReaderPlaybackSession.currentPositionMs()} " +
            "beforeBridge=$playbackPositionSnapshot cue=${cueForLog(currentCue())}"
        }
        BookReaderPlaybackSession.seekToPosition(cueStartMs)
        notifyPlaybackPosition(cueStartMs)
        logDebug(READER_PAUSED_SEEK_LOG_TAG) {
            "bridge replayCurrentCue notify target=$cueStartMs afterSession=${BookReaderPlaybackSession.currentPositionMs()} " +
            "afterBridge=$playbackPositionSnapshot cue=${cueForLog(currentCue())}"
        }
    }

    private fun cueForLog(cue: CueSnapshot?): String {
        if (cue == null) return "null"
        return "${cue.startMs}-${cue.endMs}/${cue.text.replace('\n', ' ').take(36)}"
    }

    fun refreshSubtitleForCurrentPlaybackPosition(): CueSnapshot? {
        val positionMs = currentPlaybackPositionMs()
        val subtitleSnapshotListeners: List<SubtitleStateListener>
        val subtitleText: String?
        val cue: CueSnapshot?
        synchronized(this) {
            subtitleSnapshotListeners = updateTimelineCueAtPositionLocked(positionMs)
            subtitleText = subtitleSnapshot
            cue = cueSnapshot
        }
        subtitleSnapshotListeners.forEach { it.onSubtitleChanged(subtitleText) }
        return cue
    }

    private fun updateTimelineCueAtPositionLocked(positionMs: Long): List<SubtitleStateListener> {
        if (subtitleTimelineSnapshot.isEmpty()) return emptyList()
        val nextCue = subtitleTimelineCueAtPositionLocked(positionMs)
        if (nextCue == cueSnapshot && nextCue?.text == subtitleSnapshot) return emptyList()
        cueSnapshot = nextCue
        subtitleSnapshot = nextCue?.text
        return subtitleListeners.toList()
    }

    private fun subtitleTimelineCueAtPositionLocked(positionMs: Long): CueSnapshot? {
        val cues = subtitleTimelineSnapshot
        if (cues.isEmpty()) return null
        var low = 0
        var high = cues.lastIndex
        var previousCue: SubtitleTimelineCue? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cue = cues[mid]
            when {
                positionMs < cue.startMs -> high = mid - 1
                positionMs >= cue.endMs -> {
                    previousCue = cue
                    low = mid + 1
                }
                else -> return cue.toCueSnapshotLocked()
            }
        }
        return previousCue?.toCueSnapshotLocked()
    }

    private fun SubtitleTimelineCue.toCueSnapshotLocked(): CueSnapshot {
        return CueSnapshot(
            text = text,
            startMs = startMs,
            endMs = endMs,
            bookTitle = subtitleTimelineBookTitle,
            audioUri = subtitleTimelineAudioUri,
            fullSentenceText = fullSentenceText?.takeIf { it.isNotBlank() } ?: text,
            fullSentenceStartMs = fullSentenceStartMs ?: startMs,
            fullSentenceEndMs = fullSentenceEndMs ?: endMs
        )
    }
}

private const val TIMED_SUBTITLE_SCROLL_START_HOLD = 0.12f
private const val TIMED_SUBTITLE_SCROLL_END_HOLD = 0.18f
private const val TIMED_SUBTITLE_SCROLL_TARGET_MAX = 1.0f

internal fun mapTimedSubtitleScrollProgress(linearProgress: Float): Float {
    val progress = linearProgress.coerceIn(0f, 1f)
    val motionEnd = (1f - TIMED_SUBTITLE_SCROLL_END_HOLD).coerceAtLeast(TIMED_SUBTITLE_SCROLL_START_HOLD + 0.01f)
    if (progress <= TIMED_SUBTITLE_SCROLL_START_HOLD) return 0f
    if (progress >= motionEnd) return TIMED_SUBTITLE_SCROLL_TARGET_MAX
    val normalized = ((progress - TIMED_SUBTITLE_SCROLL_START_HOLD) / (motionEnd - TIMED_SUBTITLE_SCROLL_START_HOLD))
        .coerceIn(0f, 1f)
    val eased = if (normalized < 0.5f) {
        4f * normalized * normalized * normalized
    } else {
        1f - ((-2f * normalized + 2f) * (-2f * normalized + 2f) * (-2f * normalized + 2f) / 2f)
    }
    return (TIMED_SUBTITLE_SCROLL_TARGET_MAX * eased).coerceIn(0f, TIMED_SUBTITLE_SCROLL_TARGET_MAX)
}

