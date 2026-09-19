package moe.tekuza.m9player

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 崩溃诊断采集（照 Hoshi-Reader-Android 的做法，无开关、日常开销≈0）。
 *
 * 只有两件事，都不做"持续采集"：
 * 1. Application 启动时注册一个 [Thread.UncaughtExceptionHandler]：**仅在崩溃那一刻**把
 *    「时间 / 版本 / 设备 / 线程 / 堆栈 / 崩溃前最后一段本进程日志」写成一份文本文件，只保留最近 5 份。
 * 2. 「导出诊断」时读取系统保留的进程退出记录（Android 11+）：
 *    ANR / Java 崩溃 / Native 崩溃 / 低内存等原因 + 时间 + PSS/RSS + 系统提供的 trace，
 *    trace 经过「读取上限 → 掐头去尾截断 → 可读性过滤」三重处理，避免把二进制垃圾塞进报告。
 *
 * 没有开关：这些内容始终只写在应用私有目录里，只有在用户主动「导出诊断」时才会离开设备。
 */
private const val CRASH_DIAGNOSTICS_DIR = "diagnostics/crashes"
private const val CRASH_DIAGNOSTICS_LOG_TAG = "M9Diagnostics"
private const val CRASH_FILE_PREFIX = "crash-"
private const val CRASH_LOG_TAIL_LINES = 150
private const val MAX_CAPTURED_CRASH_FILES = 5
private const val MAX_EXIT_RECORDS = 10
private const val MAX_TRACE_CHARS = 12_000
private const val MAX_TRACE_BYTES = 64_000

internal data class CapturedCrashRecord(
    val timestampMillis: Long,
    val text: String
)

/** 注册崩溃采集（幂等；由 Application.onCreate 调用）。 */
internal fun installCrashDiagnostics(context: Context) {
    val appContext = context.applicationContext
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    if (previous is CrashDiagnosticsHandler) return
    Thread.setDefaultUncaughtExceptionHandler(CrashDiagnosticsHandler(appContext, previous))
    logDebug(CRASH_DIAGNOSTICS_LOG_TAG) {
        "crash diagnostics installed (records stack + last $CRASH_LOG_TAIL_LINES log lines on crash)"
    }
}

/** 崩溃文件 + 系统退出记录两段（接进「导出诊断」报告）。 */
internal fun buildCrashDiagnosticsReport(context: Context): String = buildString {
    appendLine("[Captured Crashes]")
    val crashes = loadCapturedCrashDiagnostics(crashDiagnosticsDir(context))
    if (crashes.isEmpty()) {
        appendLine("(none)")
    } else {
        crashes.forEachIndexed { index, crash ->
            appendLine("Crash ${index + 1} time=${formatDiagnosticTime(crash.timestampMillis)}")
            appendLine(truncateDiagnosticText(crash.text))
            if (index != crashes.lastIndex) appendLine()
        }
    }
    appendLine()
    appendLine("[Process Exits]")
    appendLine(buildProcessExitSection(context))
}

internal fun saveCapturedCrashDiagnostic(
    diagnosticsDir: File,
    thread: Thread,
    throwable: Throwable,
    timestampMillis: Long,
    packageName: String,
    versionName: String,
    versionCode: Long,
    sdkInt: Int,
    deviceSummary: String,
    recentLogs: String
): File {
    diagnosticsDir.mkdirs()
    val file = File(diagnosticsDir, "$CRASH_FILE_PREFIX$timestampMillis.txt")
    file.writeText(
        buildString {
            appendLine("9Player captured crash")
            appendLine("Time=${formatDiagnosticTime(timestampMillis)}")
            appendLine("Package=$packageName")
            appendLine("Version=$versionName ($versionCode)")
            appendLine("AndroidSDK=$sdkInt")
            appendLine("Device=$deviceSummary")
            appendLine("Thread=${thread.name}")
            appendLine()
            appendLine("[Stack]")
            appendLine(throwable.stackTraceText())
            if (recentLogs.isNotBlank()) {
                appendLine()
                appendLine("[Recent Logs]")
                appendLine(recentLogs)
            }
        },
        Charsets.UTF_8
    )
    pruneCapturedCrashDiagnostics(diagnosticsDir)
    return file
}

internal fun loadCapturedCrashDiagnostics(
    diagnosticsDir: File,
    maxRecords: Int = MAX_CAPTURED_CRASH_FILES
): List<CapturedCrashRecord> =
    crashFiles(diagnosticsDir)
        .take(maxRecords)
        .mapNotNull { file ->
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@mapNotNull null
            CapturedCrashRecord(
                timestampMillis = file.name
                    .removePrefix(CRASH_FILE_PREFIX)
                    .removeSuffix(".txt")
                    .toLongOrNull()
                    ?: file.lastModified(),
                text = text.trim()
            )
        }

internal fun pruneCapturedCrashDiagnostics(
    diagnosticsDir: File,
    keep: Int = MAX_CAPTURED_CRASH_FILES
) {
    crashFiles(diagnosticsDir).drop(keep).forEach { runCatching { it.delete() } }
}

/** 崩溃文件按文件名（时间戳）倒序，最新的在前。 */
private fun crashFiles(diagnosticsDir: File): List<File> =
    diagnosticsDir
        .listFiles { file ->
            file.isFile && file.name.startsWith(CRASH_FILE_PREFIX) && file.extension == "txt"
        }
        .orEmpty()
        .sortedByDescending { it.name }

private class CrashDiagnosticsHandler(
    private val context: Context,
    private val previous: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching {
            saveCapturedCrashDiagnostic(
                diagnosticsDir = crashDiagnosticsDir(context),
                thread = thread,
                throwable = throwable,
                timestampMillis = System.currentTimeMillis(),
                packageName = context.packageName,
                versionName = resolveAppVersionName(context),
                versionCode = resolveAppVersionCode(context),
                sdkInt = Build.VERSION.SDK_INT,
                deviceSummary = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
                recentLogs = recentLogsForDiagnostics(CRASH_LOG_TAIL_LINES)
            )
        }
        previous?.uncaughtException(thread, throwable) ?: run {
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10)
        }
    }
}

private fun crashDiagnosticsDir(context: Context): File =
    File(context.filesDir, CRASH_DIAGNOSTICS_DIR)

private fun buildProcessExitSection(context: Context): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        return "Process exit history is available on Android 11 and later."
    }
    val records = runCatching {
        context.getSystemService(ActivityManager::class.java)
            ?.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS)
            .orEmpty()
    }.getOrDefault(emptyList())
    if (records.isEmpty()) return "(no recent process exits recorded by Android)"
    return records.mapIndexed { index, exit ->
        buildString {
            appendLine("Exit ${index + 1} time=${formatDiagnosticTime(exit.timestamp)}")
            appendLine("Reason=${processExitReasonLabel(exit.reason)}")
            appendLine("Status=${exit.status} Importance=${exit.importance} PSS=${exit.pss}kB RSS=${exit.rss}kB")
            exit.description?.takeIf { it.isNotBlank() }?.let { appendLine("Description=$it") }
            exit.readTraceText()?.let {
                appendLine("Trace:")
                appendLine(truncateDiagnosticText(it))
            }
        }.trimEnd()
    }.joinToString("\n\n")
}

private fun ApplicationExitInfo.readTraceText(): String? {
    val stream: InputStream = runCatching { traceInputStream }.getOrNull() ?: return null
    return runCatching {
        stream.use { it.readBoundedBytes(MAX_TRACE_BYTES) }
            .toString(Charsets.UTF_8)
            .takeIf(::isReadableDiagnosticText)
    }.getOrNull()
}

internal fun processExitReasonLabel(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_ANR -> "ANR"
    ApplicationExitInfo.REASON_CRASH -> "Java crash"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory kill"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource usage"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Initialization failure"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "User requested"
    ApplicationExitInfo.REASON_SIGNALED -> "Signaled"
    ApplicationExitInfo.REASON_OTHER -> "Other"
    else -> "Unknown"
}

/**
 * 可读性过滤：ANR/崩溃 trace 里常夹着二进制内存转储，直接塞进报告只会污染内容。
 * 替换字符/控制字符占比 > 5%，或可识别字符 < 75%，就判定为不可读并丢弃。
 */
internal fun isReadableDiagnosticText(text: String): Boolean {
    if (text.isBlank()) return false
    val length = text.length
    val badCharacters = text.count {
        it == '\uFFFD' || (Character.isISOControl(it) && it != '\n' && it != '\r' && it != '\t')
    }
    if (badCharacters.toDouble() / length.toDouble() > 0.05) return false
    val recognizable = text.count {
        it.isLetterOrDigit() || it.isWhitespace() || it in READABLE_EXTRA_CHARS
    }
    return recognizable.toDouble() / length.toDouble() >= 0.75
}

/** 超长文本掐头去尾，保留首尾各一半，中间标注截断。 */
internal fun truncateDiagnosticText(
    text: String,
    maxChars: Int = MAX_TRACE_CHARS
): String {
    if (text.length <= maxChars) return text
    val headLength = maxChars / 2
    val tailLength = maxChars - headLength
    return buildString {
        append(text.take(headLength))
        appendLine()
        appendLine("[truncated to first and last $maxChars characters]")
        append(text.takeLast(tailLength))
    }
}

/** 等 logcat 子进程退出的上限：等不到就放弃并强杀，绝不把调用方（可能在主线程）挂住。 */
private const val OWN_PROCESS_LOG_TIMEOUT_MS = 1_500L

/**
 * 「最近日志」的唯一口径：先取应用内环形日志（不依赖 logcat 子进程；release 构建里为空），
 * 为空才退回读本进程 logcat。崩溃文件与「导出诊断」共用这一份，别再各写一遍。
 */
internal fun recentLogsForDiagnostics(maxLines: Int): String =
    inAppLogSnapshot().ifBlank { readOwnProcessLogs(maxLines) }

/** 读本进程日志（崩溃采集与导出诊断共用）。不需要额外权限：读的是自己进程的 logcat。 */
internal fun readOwnProcessLogs(maxLines: Int): String {
    return runCatching {
        val process = ProcessBuilder(
            "logcat",
            "-d",
            "-t",
            maxLines.toString(),
            "--pid=${android.os.Process.myPid()}",
            "*:V"
        ).redirectErrorStream(true).start()
        // 先等进程退出再读管道：某些 ROM 上 logcat 会挂住不退出，直接 read 会一直阻塞
        //（实测：导出诊断在主线程 → 输入超时 5s → ANR）。超时就放弃，宁可没有日志。
        if (!process.waitFor(OWN_PROCESS_LOG_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return@runCatching ""
        }
        process.inputStream.bufferedReader().use { it.readText().trim() }
    }.getOrDefault("")
}

private fun formatDiagnosticTime(timestampMillis: Long): String =
    Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()).format(DIAGNOSTIC_TIME_FORMAT)

private fun InputStream.readBoundedBytes(maxBytes: Int): ByteArray {
    val buffer = ByteArray(maxBytes)
    var offset = 0
    while (offset < maxBytes) {
        val read = read(buffer, offset, maxBytes - offset)
        if (read == -1) break
        offset += read
    }
    return buffer.copyOf(offset)
}

private fun Throwable.stackTraceText(): String =
    StringWriter().use { writer ->
        PrintWriter(writer).use { printStackTrace(it) }
        writer.toString()
    }

private val READABLE_EXTRA_CHARS = setOf(
    '.', ',', ':', ';', '/', '\\', '-', '_', '+', '#', '$', '%',
    '(', ')', '[', ']', '{', '}', '<', '>', '=', '"', '\''
)

private val DIAGNOSTIC_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
