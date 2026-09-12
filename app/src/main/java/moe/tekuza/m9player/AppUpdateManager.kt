package moe.tekuza.m9player

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

private const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/tza14/9Player/releases/latest"
private const val UPDATE_APK_CACHE_DIR = "update_apk"
private const val UPDATE_APK_DOWNLOAD_SUFFIX = ".download"
private val UPDATE_APK_VERSION_REGEX = Regex(
    """(?:^|[-_])v?(\d+(?:\.\d+)+)(?:[-_][^.]*)?\.apk$""",
    RegexOption.IGNORE_CASE
)
private val INVALID_FILE_NAME_CHARS_REGEX = Regex("""[\\/:*?"<>|]""")

internal data class AppUpdateRelease(
    val tagName: String,
    val displayName: String,
    val pageUrl: String,
    val apkName: String,
    val apkUrl: String
)

internal sealed interface AppUpdateCheckResult {
    data class UpdateAvailable(val release: AppUpdateRelease) : AppUpdateCheckResult
    data class UpToDate(val latestVersion: String) : AppUpdateCheckResult
    data class NoApkAsset(val latestVersion: String, val pageUrl: String) : AppUpdateCheckResult
    data class Failed(val message: String) : AppUpdateCheckResult
}

internal suspend fun checkLatestAppUpdate(context: Context): AppUpdateCheckResult = withContext(Dispatchers.IO) {
    runCatching {
        cleanupInstalledOrOldUpdateApks(context)
        val connection = (URL(GITHUB_LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "9Player/${resolveAppVersionName(context)}")
        }
        try {
            connection.requireSuccessfulResponse()
            connection.inputStream.bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                val tagName = json.optString("tag_name").ifBlank { json.optString("name") }
                val displayName = json.optString("name").ifBlank { tagName }
                val pageUrl = json.optString("html_url")
                val assets = json.optJSONArray("assets")
                var apkName = ""
                var apkUrl = ""
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name")
                        val url = asset.optString("browser_download_url")
                        if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                            apkName = name
                            apkUrl = url
                            break
                        }
                    }
                }
                val currentVersion = resolveAppVersionName(context)
                if (compareAppVersions(tagName, currentVersion) <= 0) {
                    AppUpdateCheckResult.UpToDate(tagName.ifBlank { currentVersion })
                } else if (apkUrl.isBlank()) {
                    AppUpdateCheckResult.NoApkAsset(tagName, pageUrl)
                } else {
                    AppUpdateCheckResult.UpdateAvailable(
                        AppUpdateRelease(
                            tagName = tagName,
                            displayName = displayName,
                            pageUrl = pageUrl,
                            apkName = apkName,
                            apkUrl = apkUrl
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }.getOrElse { error ->
        AppUpdateCheckResult.Failed(error.message ?: error.javaClass.simpleName)
    }
}

internal suspend fun downloadAppUpdateApk(
    context: Context,
    release: AppUpdateRelease,
    onProgress: (Float?) -> Unit
): Result<File> = withContext(Dispatchers.IO) {
    runCatching {
        val updateDir = updateApkCacheDir(context)
        val outputFile = File(updateDir, updateApkFileName(release))
        val tempFile = File(updateDir, "${outputFile.name}$UPDATE_APK_DOWNLOAD_SUFFIX")
        cleanupUpdateApksExcept(updateDir, outputFile)
        if (tempFile.exists()) tempFile.delete()
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "9Player/${resolveAppVersionName(context)}")
        }
        try {
            connection.requireSuccessfulResponse()
            val total = connection.contentLengthLong.takeIf { it > 0L }
            var copied = 0L
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastProgressEmitAt = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgressEmitAt >= 120L) {
                            onProgress(total?.let { (copied.toFloat() / it.toFloat()).coerceIn(0f, 1f) })
                            lastProgressEmitAt = now
                        }
                    }
                    onProgress(total?.let { (copied.toFloat() / it.toFloat()).coerceIn(0f, 1f) })
                }
            }
            if (copied <= 0L) error("Downloaded APK is empty")
            if (total != null && copied != total) {
                error("Downloaded APK size mismatch: expected $total bytes, got $copied bytes")
            }
            if (outputFile.exists() && !outputFile.delete()) {
                error("Unable to replace cached APK: ${outputFile.name}")
            }
            if (!tempFile.renameTo(outputFile)) {
                tempFile.copyTo(outputFile, overwrite = true)
                runCatching { tempFile.delete() }
            }
            validateDownloadedUpdateApk(context, outputFile)
            outputFile
        } catch (error: Throwable) {
            runCatching { tempFile.delete() }
            runCatching { outputFile.delete() }
            throw error
        } finally {
            connection.disconnect()
        }
    }
}

internal fun launchAppUpdateInstall(context: Context, apkFile: File): Boolean {
    if (!apkFile.isFile) return false
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
    }
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

private fun cleanupInstalledOrOldUpdateApks(context: Context) {
    cleanupInstalledOrOldUpdateApks(
        updateDir = updateApkCacheDir(context),
        currentVersion = resolveAppVersionName(context)
    )
}

internal fun cleanupInstalledOrOldUpdateApks(updateDir: File, currentVersion: String) {
    updateDir.listFiles { file ->
        file.isFile && file.extension.equals("apk", ignoreCase = true)
    }?.forEach { file ->
        val apkVersion = file.name.extractUpdateApkVersion() ?: return@forEach
        if (compareAppVersions(apkVersion, currentVersion) <= 0) {
            file.delete()
        }
    }
}

internal fun cleanupUpdateApksExcept(updateDir: File, keepFile: File) {
    updateDir.mkdirs()
    val keepPath = keepFile.toPath().toAbsolutePath().normalize().toString()
    updateDir.listFiles { file ->
        file.isFile && (
            file.extension.equals("apk", ignoreCase = true) ||
                file.name.endsWith(UPDATE_APK_DOWNLOAD_SUFFIX, ignoreCase = true)
            )
    }?.forEach { file ->
        if (file.toPath().toAbsolutePath().normalize().toString() != keepPath) {
            file.delete()
        }
    }
}

private fun HttpURLConnection.requireSuccessfulResponse() {
    val code = responseCode
    if (code !in 200..299) {
        val status = responseMessage?.takeIf { it.isNotBlank() } ?: "HTTP $code"
        error("Update request failed: $code $status")
    }
}

private fun validateDownloadedUpdateApk(context: Context, apkFile: File) {
    val packageManager = context.packageManager
    val flags = packageSignatureFlags()
    val archiveInfo = packageManager.getPackageArchiveInfoCompat(apkFile.absolutePath, flags)
        ?: error("Downloaded APK is not a readable Android package")
    if (archiveInfo.packageName != context.packageName) {
        error("Downloaded APK package mismatch: ${archiveInfo.packageName}")
    }

    val installedInfo = packageManager.getPackageInfoCompat(context.packageName, flags)
    if (archiveInfo.longVersionCodeCompat() <= installedInfo.longVersionCodeCompat()) {
        error("Downloaded APK is not newer than the installed app")
    }

    val archiveSignatures = archiveInfo.signatureDigests()
    val installedSignatures = installedInfo.signatureDigests()
    if (archiveSignatures.isEmpty() || installedSignatures.isEmpty() || archiveSignatures != installedSignatures) {
        error("Downloaded APK signature does not match the installed app")
    }
}

private fun packageSignatureFlags(): Int = PackageManager.GET_SIGNING_CERTIFICATES

@Suppress("DEPRECATION")
private fun PackageManager.getPackageArchiveInfoCompat(path: String, flags: Int): PackageInfo? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        getPackageArchiveInfo(path, flags)
    }

@Suppress("DEPRECATION")
private fun PackageManager.getPackageInfoCompat(packageName: String, flags: Int): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        getPackageInfo(packageName, flags)
    }

private fun PackageInfo.longVersionCodeCompat(): Long = longVersionCode

private fun PackageInfo.signatureDigests(): Set<String> {
    return signingInfo?.apkContentsSigners.orEmpty()
        .map { signature -> sha256Hex(signature.toByteArray()) }
        .toSet()
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun updateApkCacheDir(context: Context): File {
    return File(context.cacheDir, UPDATE_APK_CACHE_DIR).apply { mkdirs() }
}

private fun String.extractUpdateApkVersion(): String? {
    return UPDATE_APK_VERSION_REGEX.find(this)
        ?.groupValues
        ?.getOrNull(1)
}

private fun updateApkFileName(release: AppUpdateRelease): String {
    val releaseName = release.apkName.sanitizeFileName()
    if (releaseName.extractUpdateApkVersion() != null) return releaseName
    val tagName = release.tagName.sanitizeFileName().ifBlank { "update" }
    return "9player-$tagName.apk"
}

private fun compareAppVersions(leftRaw: String, rightRaw: String): Int {
    val left = leftRaw.toVersionParts()
    val right = rightRaw.toVersionParts()
    val max = maxOf(left.size, right.size)
    for (i in 0 until max) {
        val diff = (left.getOrNull(i) ?: 0) - (right.getOrNull(i) ?: 0)
        if (diff != 0) return diff
    }
    return 0
}

private fun String.toVersionParts(): List<Int> {
    return trim()
        .lowercase(Locale.US)
        .removePrefix("v")
        .split('.', '-', '_')
        .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }
}

private fun String.sanitizeFileName(): String {
    return replace(INVALID_FILE_NAME_CHARS_REGEX, "_")
}
