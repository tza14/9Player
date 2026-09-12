package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 崩溃诊断（A 档，照 Hoshi）：崩溃文件只在崩溃那一刻写，退出记录在导出时读系统数据。
 * 这里测的都是纯逻辑：退出原因映射、trace 可读性过滤、超长截断、崩溃文件的保留与排序。
 */
class CrashDiagnosticsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun crashFile(timestampMillis: Long, text: String = "stack"): File {
        val dir = tempFolder.root
        return File(dir, "crash-$timestampMillis.txt").apply { writeText(text) }
    }

    @Test
    fun processExitReasonLabelsCoverTheInterestingReasons() {
        assertEquals("ANR", processExitReasonLabel(6))          // REASON_ANR
        assertEquals("Java crash", processExitReasonLabel(4))    // REASON_CRASH
        assertEquals("Native crash", processExitReasonLabel(5))  // REASON_CRASH_NATIVE
        assertEquals("Low memory kill", processExitReasonLabel(3))
        assertEquals("User requested", processExitReasonLabel(10))
        assertEquals("Unknown", processExitReasonLabel(-12345))
    }

    @Test
    fun plainStackTraceTextIsReadable() {
        val text = """
            ----- pid 1234 at 2026-09-11 22:00:00 -----
            Cmd line: moe.tekuza.m9player
            "main" prio=5 tid=1 Native
              | group="main" sCount=1 dsCount=0 flags=1 obj=0x12c00000 self=0x7f8a
              at moe.tekuza.m9player.LegadoReaderActivity.onResume(LegadoReaderActivity.kt:120)
        """.trimIndent()
        assertTrue(isReadableDiagnosticText(text))
    }

    @Test
    fun binaryDumpIsNotReadable() {
        // 替换字符 + 控制字符占多数 → 判定为二进制垃圾，应当丢弃
        val binary = buildString {
            repeat(60) { append('\uFFFD') }
            repeat(20) { append('\u0001') }
            append("abc")
        }
        assertFalse(isReadableDiagnosticText(binary))
        assertFalse(isReadableDiagnosticText(""))
        assertFalse(isReadableDiagnosticText("   "))
    }

    @Test
    fun longTraceKeepsHeadAndTailAndStaysBounded() {
        val long = "H".repeat(500) + "M".repeat(2000) + "T".repeat(500)

        val truncated = truncateDiagnosticText(long, maxChars = 200)

        assertTrue(truncated.length < long.length)
        assertTrue(truncated.startsWith("H".repeat(50)))
        assertTrue(truncated.trimEnd().endsWith("T".repeat(50)))
        assertTrue(truncated.contains("truncated"))
    }

    @Test
    fun shortTraceIsUntouched() {
        val short = "small trace"
        assertEquals(short, truncateDiagnosticText(short))
    }

    @Test
    fun capturedCrashesAreSortedNewestFirstAndPruned() {
        val dir = tempFolder.root
        (1L..7L).forEach { crashFile(timestampMillis = 1_700_000_000_000L + it) }

        pruneCapturedCrashDiagnostics(dir, keep = 5)

        val remaining = dir.listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(5, remaining.size)
        assertFalse(remaining.any { it.contains("1700000000001") })
        assertFalse(remaining.any { it.contains("1700000000002") })

        val loaded = loadCapturedCrashDiagnostics(dir, maxRecords = 5)
        assertEquals(5, loaded.size)
        assertEquals(1_700_000_000_007L, loaded.first().timestampMillis)
        assertEquals(1_700_000_000_003L, loaded.last().timestampMillis)
    }

    @Test
    fun missingCrashDirectoryYieldsNoRecords() {
        val missing = File(tempFolder.root, "nope")
        assertTrue(loadCapturedCrashDiagnostics(missing).isEmpty())
        pruneCapturedCrashDiagnostics(missing) // 不应抛异常
    }

    @Test
    fun savedCrashFileCarriesStackAndLogTail() {
        val dir = File(tempFolder.root, "crashes")

        saveCapturedCrashDiagnostic(
            diagnosticsDir = dir,
            thread = Thread.currentThread(),
            throwable = IllegalStateException("boom from test"),
            timestampMillis = 1_700_000_123_456L,
            packageName = "moe.tekuza.m9player.debug",
            versionName = "1.7.6",
            versionCode = 53,
            sdkInt = 36,
            deviceSummary = "Test Device (Android 16)",
            recentLogs = "09-11 22:00:00.000 D/M9Diagnostics: crash diagnostics installed"
        )

        val file = dir.listFiles().orEmpty().single()
        assertEquals("crash-1700000123456.txt", file.name)
        val text = file.readText()
        assertTrue(text.contains("Package=moe.tekuza.m9player.debug"))
        assertTrue(text.contains("Version=1.7.6 (53)"))
        assertTrue(text.contains("IllegalStateException: boom from test"))
        assertTrue(text.contains("[Recent Logs]"))
        assertTrue(text.contains("crash diagnostics installed"))
    }

    @Test
    fun savingSixCrashesKeepsOnlyTheNewestFive() {
        val dir = File(tempFolder.root, "crashes")
        (1L..6L).forEach { index ->
            saveCapturedCrashDiagnostic(
                diagnosticsDir = dir,
                thread = Thread.currentThread(),
                throwable = RuntimeException("crash $index"),
                timestampMillis = 1_700_000_000_000L + index,
                packageName = "pkg",
                versionName = "1.7.6",
                versionCode = 53,
                sdkInt = 36,
                deviceSummary = "device",
                recentLogs = ""
            )
        }

        val names = dir.listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(5, names.size)
        assertFalse(names.any { it.contains("1700000000001") })
    }
}
