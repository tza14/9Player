package moe.tekuza.m9player

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val SHIZUKU_BT_TAG = "ShizukuBluetooth"
private val SHIZUKU_BLUETOOTH_ADDRESS_REGEX = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

internal enum class SleepBluetoothOutcome {
    TARGET_DISCONNECTED,
    BLUETOOTH_DISABLED,
    FAILED
}

internal data class SleepBluetoothActionResult(
    val outcome: SleepBluetoothOutcome,
    val detail: String
)

internal fun tryDisconnectTargetControllerThenDisableBluetooth(
    context: Context,
    targetAddress: String?,
    allowDisableBluetoothFallback: Boolean
): SleepBluetoothActionResult {
    val address = targetAddress
        ?.trim()
        ?.uppercase()
        ?.takeIf { isBluetoothAddress(it) }
    logDebug(SHIZUKU_BT_TAG) {
        "disconnect request address=$address allowDisableBluetoothFallback=$allowDisableBluetoothFallback"
    }

    if (!Shizuku.pingBinder()) {
        logDebug(SHIZUKU_BT_TAG) { "Shizuku binder not ready, requesting provider binder" }
        runCatching { ShizukuProvider.requestBinderForNonProviderProcess(context) }
        val start = System.currentTimeMillis()
        while (!Shizuku.pingBinder() && System.currentTimeMillis() - start < 2_000L) {
            try {
                Thread.sleep(120L)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    if (!address.isNullOrBlank()) {
        val profilesToTry = listOf(
            4,
            BluetoothProfile.A2DP,
            BluetoothProfile.HEADSET,
            BluetoothProfile.GATT,
            BluetoothProfile.GATT_SERVER
        )
        for (profile in profilesToTry) {
            logDebug(SHIZUKU_BT_TAG) { "trying profile disconnect profile=$profile address=$address" }
            if (disconnectDeviceViaProfile(context, address, profile)) {
                logDebug(SHIZUKU_BT_TAG) { "profile disconnect succeeded profile=$profile address=$address" }
                return SleepBluetoothActionResult(
                    outcome = SleepBluetoothOutcome.TARGET_DISCONNECTED,
                    detail = "Disconnected controller $address via profile $profile"
                )
            }
        }

        val disconnectCommands = listOf(
            "cmd bluetooth_manager disconnect $address",
            "cmd bluetooth_manager disconnect --address $address",
            "cmd bluetooth_manager disconnect-device $address"
        )
        for (command in disconnectCommands) {
            logDebug(SHIZUKU_BT_TAG) { "trying shell disconnect command=$command" }
            val result = runShizukuShell(command)
            if (result.exitCode == 0) {
                logDebug(SHIZUKU_BT_TAG) { "shell disconnect succeeded command=$command" }
                return SleepBluetoothActionResult(
                    outcome = SleepBluetoothOutcome.TARGET_DISCONNECTED,
                    detail = "Disconnected controller $address"
                )
            }
            Log.w(
                SHIZUKU_BT_TAG,
                "shell disconnect failed command=$command exit=${result.exitCode} stderr=${result.stderr}"
            )
        }

        logDebug(SHIZUKU_BT_TAG) { "trying removeBond fallback address=$address" }
        if (unpairDevice(context, address)) {
            logDebug(SHIZUKU_BT_TAG) { "removeBond succeeded address=$address" }
            return SleepBluetoothActionResult(
                outcome = SleepBluetoothOutcome.TARGET_DISCONNECTED,
                detail = "Unpaired controller $address to force disconnect"
            )
        }
        Log.w(SHIZUKU_BT_TAG, "all targeted disconnect strategies failed address=$address")
    }

    if (!allowDisableBluetoothFallback) {
        val reason = if (address.isNullOrBlank()) {
            context.getString(R.string.shizuku_bluetooth_controller_not_found)
        } else {
            context.getString(R.string.shizuku_bluetooth_disconnect_all_failed, address)
        }
        return SleepBluetoothActionResult(
            outcome = SleepBluetoothOutcome.FAILED,
            detail = context.getString(R.string.shizuku_bluetooth_skip_disable_with_reason, reason)
        )
    }

    if (!Shizuku.pingBinder()) {
        Log.w(SHIZUKU_BT_TAG, "fallback bluetooth disable skipped because Shizuku is not running")
        return SleepBluetoothActionResult(
            outcome = SleepBluetoothOutcome.FAILED,
            detail = "Shizuku not running"
        )
    }
    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
        Log.w(SHIZUKU_BT_TAG, "fallback bluetooth disable skipped because Shizuku permission denied")
        return SleepBluetoothActionResult(
            outcome = SleepBluetoothOutcome.FAILED,
            detail = "Shizuku permission denied"
        )
    }

    val disableCommands = listOf(
        "cmd bluetooth_manager disable",
        "svc bluetooth disable"
    )
    disableCommands.forEach { command ->
        logDebug(SHIZUKU_BT_TAG) { "trying bluetooth disable fallback command=$command" }
        val result = runShizukuShell(command)
        if (result.exitCode == 0) {
            logDebug(SHIZUKU_BT_TAG) { "bluetooth disable fallback succeeded command=$command" }
            return SleepBluetoothActionResult(
                outcome = SleepBluetoothOutcome.BLUETOOTH_DISABLED,
                detail = "Bluetooth disabled"
            )
        }
        Log.w(
            SHIZUKU_BT_TAG,
            "bluetooth disable fallback failed command=$command exit=${result.exitCode} stderr=${result.stderr}"
        )
    }

    Log.w(SHIZUKU_BT_TAG, "disconnect flow failed for address=$address")
    return SleepBluetoothActionResult(
        outcome = SleepBluetoothOutcome.FAILED,
        detail = context.getString(R.string.shizuku_bluetooth_disconnect_and_disable_failed)
    )
}

private fun disconnectDeviceViaProfile(
    context: Context,
    address: String,
    profile: Int
): Boolean {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
    val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false
    var disconnected = false
    val latch = CountDownLatch(1)
    val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
            try {
                val disconnectMethod = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                disconnectMethod.isAccessible = true
                disconnected = (disconnectMethod.invoke(proxy, device) as? Boolean) == true
            } catch (_: Exception) {
                disconnected = false
                Log.w(SHIZUKU_BT_TAG, "profile disconnect reflection failed profile=$profile address=$address")
            } finally {
                runCatching { adapter.closeProfileProxy(profileId, proxy) }
                latch.countDown()
            }
        }

        override fun onServiceDisconnected(profileId: Int) {
            latch.countDown()
        }
    }

    val bound = runCatching { adapter.getProfileProxy(context, listener, profile) }.getOrDefault(false)
    if (bound) {
        latch.await(3, TimeUnit.SECONDS)
    } else {
        Log.w(SHIZUKU_BT_TAG, "getProfileProxy failed profile=$profile address=$address")
    }
    return disconnected
}

private fun unpairDevice(context: Context, address: String): Boolean {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
    val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false
    return try {
        val removeBondMethod = device.javaClass.getMethod("removeBond")
        removeBondMethod.isAccessible = true
        (removeBondMethod.invoke(device) as? Boolean) == true
    } catch (_: Exception) {
        Log.w(SHIZUKU_BT_TAG, "removeBond failed address=$address")
        false
    }
}

private data class ShizukuShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

private fun runShizukuShell(command: String): ShizukuShellResult {
    return runCatching {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(
            null,
            arrayOf("sh", "-c", command),
            null,
            null
        ) as Process
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        ShizukuShellResult(exitCode = exit, stdout = stdout, stderr = stderr)
    }.getOrElse { error ->
        ShizukuShellResult(
            exitCode = -1,
            stdout = "",
            stderr = error.message.orEmpty()
        )
    }
}

private fun isBluetoothAddress(value: String): Boolean {
    return SHIZUKU_BLUETOOTH_ADDRESS_REGEX.matches(value)
}

