package moe.tekuza.m9player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xiaomi.xms.wearable.Wearable
import com.xiaomi.xms.wearable.auth.Permission
import com.xiaomi.xms.wearable.message.MessageApi
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener
import com.xiaomi.xms.wearable.node.NodeApi
import com.xiaomi.xms.wearable.service.OnServiceConnectionListener
import com.xiaomi.xms.wearable.service.ServiceApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.nio.charset.StandardCharsets

internal fun startWearableBridgeService(context: Context) {
    if (!loadWearableFeatureEnabled(context)) return
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) !=
        PackageManager.PERMISSION_GRANTED
    ) return
    runCatching {
        ContextCompat.startForegroundService(
            context,
            Intent(context, WearableBridgeService::class.java)
        )
    }.onFailure { Log.w(WearableBridgeService.TAG, "start failed", it) }
}

internal fun stopWearableBridgeService(context: Context) {
    context.stopService(Intent(context, WearableBridgeService::class.java))
}

class WearableBridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val registeredNodeIds = linkedSetOf<String>()
    private val registeringNodeIds = linkedSetOf<String>()
    private var nodeApi: NodeApi? = null
    private var messageApi: MessageApi? = null
    private var serviceApi: ServiceApi? = null

    private val messageListener = OnMessageReceivedListener { nodeId, bytes ->
        val command = WearableCommandProtocol.parse(bytes)
        if (command == null) {
            send(nodeId, response(null, false, getString(R.string.wearable_error_invalid_command)))
        } else {
            handle(nodeId, command)
        }
    }

    private val serviceConnectionListener = object : OnServiceConnectionListener {
        override fun onServiceConnected() = registerConnectedNodes()

        override fun onServiceDisconnected() {
            synchronized(this@WearableBridgeService) {
                registeredNodeIds.clear()
                registeringNodeIds.clear()
            }
            updateNotification(getString(R.string.wearable_status_waiting_connection))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.wearable_status_connecting)))
        runCatching {
            nodeApi = Wearable.getNodeApi(applicationContext)
            messageApi = Wearable.getMessageApi(applicationContext)
            serviceApi = Wearable.getServiceApi(applicationContext).also {
                it.registerServiceConnectionListener(serviceConnectionListener)
            }
            registerConnectedNodes()
        }.onFailure {
            Log.w(TAG, "Xiaomi wearable SDK unavailable", it)
            updateNotification(getString(R.string.wearable_error_install_health))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!loadWearableFeatureEnabled(this) || BookReaderPlaybackSession.currentAudioUri() == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        registerConnectedNodes()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val nodesToRemove = synchronized(this) {
            registeredNodeIds.toList().also {
                registeredNodeIds.clear()
                registeringNodeIds.clear()
            }
        }
        nodesToRemove.forEach { nodeId -> messageApi?.removeListener(nodeId) }
        serviceApi?.unregisterServiceConnectionListener(serviceConnectionListener)
        scope.cancel()
        super.onDestroy()
    }

    private fun registerConnectedNodes() {
        val nodes = nodeApi ?: return
        nodes.connectedNodes
            .addOnSuccessListener { connected ->
                if (connected.isEmpty()) {
                    updateNotification(getString(R.string.wearable_status_not_connected))
                    return@addOnSuccessListener
                }
                connected.forEach { node -> ensurePermissionAndListen(node.id) }
            }
            .addOnFailureListener {
                Log.w(TAG, "get connected nodes failed", it)
                updateNotification(getString(R.string.wearable_status_waiting_connection))
            }
    }

    private fun ensurePermissionAndListen(nodeId: String) {
        if (nodeId.isBlank()) return
        synchronized(this) {
            if (nodeId in registeredNodeIds || !registeringNodeIds.add(nodeId)) return
        }
        val auth = runCatching { Wearable.getAuthApi(applicationContext) }.getOrNull()
        if (auth == null) {
            synchronized(this) { registeringNodeIds.remove(nodeId) }
            return
        }
        auth.checkPermission(nodeId, Permission.DEVICE_MANAGER)
            .addOnSuccessListener { granted ->
                if (granted) {
                    addMessageListener(nodeId)
                } else {
                    auth.requestPermission(nodeId, Permission.DEVICE_MANAGER)
                        .addOnSuccessListener { addMessageListener(nodeId) }
                        .addOnFailureListener {
                            synchronized(this) { registeringNodeIds.remove(nodeId) }
                            Log.w(TAG, "wearable permission denied", it)
                            updateNotification(getString(R.string.wearable_error_permission))
                        }
                }
            }
            .addOnFailureListener {
                synchronized(this) { registeringNodeIds.remove(nodeId) }
                Log.w(TAG, "permission check failed", it)
            }
    }

    private fun addMessageListener(nodeId: String) {
        val api = messageApi
        if (api == null) {
            synchronized(this) { registeringNodeIds.remove(nodeId) }
            return
        }
        api.addListener(nodeId, messageListener)
            ?.addOnSuccessListener {
                synchronized(this) {
                    registeringNodeIds.remove(nodeId)
                    registeredNodeIds += nodeId
                }
                updateNotification(getString(R.string.wearable_status_enabled))
                send(nodeId, currentStateResponse(null, getString(R.string.wearable_status_enabled)))
            }
            ?.addOnFailureListener {
                synchronized(this) { registeringNodeIds.remove(nodeId) }
                Log.w(TAG, "message listener failed", it)
            }
    }

    private fun handle(nodeId: String, request: WearableCommand) {
        scope.launch {
            when (request.command) {
                "GET_STATE" -> send(nodeId, currentStateResponse(request, getString(R.string.wearable_status_synced)))
                "PLAY_PAUSE" -> {
                    BookReaderFloatingBridge.togglePlayPause()
                    send(
                        nodeId,
                        currentStateResponse(
                            request,
                            getString(
                                if (BookReaderFloatingBridge.isPlaying()) {
                                    R.string.wearable_status_playing
                                } else {
                                    R.string.wearable_status_paused
                                }
                            )
                        )
                    )
                }
                "SEEK_PREVIOUS" -> {
                    logDebug(TAG) { "received wearable seek step=-1" }
                    BookReaderFloatingBridge.seekAdjacent(applicationContext, -1)
                    send(nodeId, currentStateResponse(request, getString(R.string.wearable_status_seek_previous)))
                }
                "SEEK_NEXT" -> {
                    logDebug(TAG) { "received wearable seek step=1" }
                    BookReaderFloatingBridge.seekAdjacent(applicationContext, 1)
                    send(nodeId, currentStateResponse(request, getString(R.string.wearable_status_seek_next)))
                }
                "SET_SPEED" -> {
                    val speed = request.value?.toFloat()?.coerceIn(0.5f, 3f) ?: 1f
                    BookReaderFloatingBridge.setPlaybackSpeed(speed)
                    send(
                        nodeId,
                        currentStateResponse(request, getString(R.string.wearable_status_speed, speed))
                    )
                }
                "SET_TIMER" -> {
                    val minutes = request.value?.toLong()?.coerceIn(1L, 180L) ?: 0L
                    if (BookReaderFloatingBridge.requestSleepTimer(minutes.toInt())) {
                        val message = if (minutes == 0L) {
                            getString(R.string.wearable_status_timer_cancelled)
                        } else {
                            getString(R.string.wearable_status_timer_set, minutes)
                        }
                        send(
                            nodeId,
                            currentStateResponse(request, message)
                        )
                    } else {
                        send(nodeId, response(request, false, getString(R.string.wearable_error_player_not_ready)))
                    }
                }
                "CLEAR_TIMER" -> {
                    if (BookReaderFloatingBridge.requestSleepTimer(0)) {
                        send(nodeId, currentStateResponse(request, getString(R.string.wearable_status_timer_cancelled)))
                    } else {
                        send(nodeId, response(request, false, getString(R.string.wearable_error_player_not_ready)))
                    }
                }
                "COLLECT_CURRENT" -> {
                    val result = BookReaderFloatingBridge.requestControlCollect()
                    send(
                        nodeId,
                        response(
                            request,
                            result != null,
                            getString(
                                if (result != null) {
                                    R.string.wearable_status_control_processed
                                } else {
                                    R.string.wearable_error_no_subtitle
                                }
                            )
                        ).apply {
                            put("keepScreenOnMs", result?.keepScreenOnMs?.plus(1_000L) ?: 0L)
                        }
                    )
                }
            }
        }
    }

    private fun currentStateResponse(request: WearableCommand?, message: String): JSONObject {
        val cue = BookReaderFloatingBridge.currentCue()
        return response(request, true, message, cue?.text).apply {
            put("title", BookReaderFloatingBridge.currentBookTitle().orEmpty())
            put("playing", BookReaderFloatingBridge.isPlaying())
            put("speed", BookReaderFloatingBridge.currentPlaybackSpeed())
            put("positionMs", BookReaderFloatingBridge.currentPlaybackPositionMs())
            put("durationMs", BookReaderFloatingBridge.currentPlaybackDurationMs())
            put("timerRemainingMs", BookReaderFloatingBridge.currentSleepTimerRemainingMs())
        }
    }

    private fun response(
        request: WearableCommand?,
        ok: Boolean,
        message: String,
        cue: String? = null
    ): JSONObject = JSONObject().apply {
        put("type", "response")
        put("command", request?.command.orEmpty())
        put("requestId", request?.requestId.orEmpty())
        put("ok", ok)
        put("message", message)
        cue?.takeIf { it.isNotBlank() }?.let { put("cue", it.take(300)) }
    }

    private fun send(nodeId: String, payload: JSONObject) {
        messageApi?.sendMessage(nodeId, payload.toString().toByteArray(StandardCharsets.UTF_8))
            ?.addOnFailureListener { Log.w(TAG, "send response failed", it) }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wearable_notification_title),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_overlay_play)
        .setContentTitle(getString(R.string.wearable_notification_title))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        internal const val TAG = "WearableBridge"
        private const val CHANNEL_ID = "wearable_bridge"
        private const val NOTIFICATION_ID = 31_002
    }
}

internal data class WearableCommand(val command: String, val requestId: String, val value: Double? = null)

internal object WearableCommandProtocol {
    private val allowed = setOf("GET_STATE", "PLAY_PAUSE", "SEEK_PREVIOUS", "SEEK_NEXT", "SET_SPEED", "SET_TIMER", "CLEAR_TIMER", "COLLECT_CURRENT")

    fun parse(bytes: ByteArray): WearableCommand? {
        if (bytes.isEmpty() || bytes.size > 8_192) return null
        return runCatching {
            val json = JSONObject(String(bytes, StandardCharsets.UTF_8))
            validate(json.optString("command"), json.optString("requestId"), json.optDouble("value", Double.NaN).takeIf { it.isFinite() })
        }.getOrNull()
    }

    internal fun validate(command: String, requestId: String = "", value: Double? = null): WearableCommand? {
        val normalized = command.trim().uppercase()
        return normalized.takeIf { it in allowed }?.let { WearableCommand(it, requestId.take(64), value) }
    }
}
