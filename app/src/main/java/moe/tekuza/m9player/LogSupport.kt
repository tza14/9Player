package moe.tekuza.m9player

import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

/**
 * verbose 日志门控：release 构建里完全不执行（**连消息字符串都不拼**，因为消息是 lambda）。
 * `Log.w` / `Log.e` 不经过这里，永远保留——它们是崩溃文件与诊断报告里唯一有价值的上下文。
 *
 * 开关来自各变体的 `VERBOSE_LOGS`：debug 与 debugSuffix（⑨debug 测试包）= true，release = false。
 * 注意不要用 BuildConfig.DEBUG 代替：debugSuffix 是 debuggable = false，那会连带把 ⑨debug
 * 的日志一起关掉。
 */
internal inline fun logDebug(tag: String, message: () -> String) {
    if (BuildConfig.VERBOSE_LOGS) {
        val text = message()
        Log.d(tag, text)
        appendInAppLog(tag, text)
    }
}

/** 应用内环形缓冲的容量与单条上限：够定位问题，又不至于占内存/撑爆诊断文件。 */
private const val IN_APP_LOG_CAPACITY = 400
private const val IN_APP_LOG_MAX_CHARS = 300
private val IN_APP_LOG_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private val inAppLogs = ArrayDeque<String>(IN_APP_LOG_CAPACITY)
private val inAppLogLock = Any()

/**
 * 记一条应用内日志。「导出诊断」用它，**不再依赖 `logcat` 子进程**——那玩意在 Android 16 上
 * 返回空（实测 `Recent Logs` 是空的），而且曾在主线程阻塞到 ANR。
 *
 * `internal` 而非 `private`：调用方 [logDebug] 是 inline 的，不能访问私有 API。
 */
internal fun appendInAppLog(tag: String, text: String) {
    val line = buildString {
        append(LocalTime.now().format(IN_APP_LOG_TIME_FORMAT))
        append(' ')
        append(tag)
        append(": ")
        append(text.take(IN_APP_LOG_MAX_CHARS))
    }
    synchronized(inAppLogLock) {
        if (inAppLogs.size >= IN_APP_LOG_CAPACITY) inAppLogs.removeFirst()
        inAppLogs.addLast(line)
    }
}

/** 导出诊断用：最近的应用内日志（时间正序，`\n` 分隔）。release 构建里为空（verbose 日志整体关闭）。 */
internal fun inAppLogSnapshot(): String = synchronized(inAppLogLock) { inAppLogs.joinToString("\n") }
