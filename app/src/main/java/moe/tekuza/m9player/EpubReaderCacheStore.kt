package moe.tekuza.m9player

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

private const val EPUB_READER_CACHE_DIR = "reader_epub_cache"
private const val EPUB_READER_INDEX_FILE = "reader_index.json"

/**
 * 缓存布局版本：读法/布局一变就 +1。已经落盘的旧缓存可能是个空壳（旧流式读法会静默只写 0 字节），
 * 只有版本对不上才会被丢弃重解，否则 stamp 命中后会永远复用那份坏缓存。
 */
private const val EPUB_READER_EXTRACTOR_VERSION = 2

internal fun ensureEpubReaderCache(
    context: Context,
    uri: Uri,
    displayName: String
): File {
    val cacheKey = buildDictionaryCacheKey(uri.toString(), displayName)
    val root = File(context.filesDir, "$EPUB_READER_CACHE_DIR/$cacheKey").canonicalFile
    val sourceStamp = buildReaderSourceStamp(context, uri)
    val indexFile = root.resolve(EPUB_READER_INDEX_FILE)
    val cachedIndex = runCatching { JSONObject(indexFile.readText()) }.getOrNull()
    if (
        root.isDirectory &&
        indexFile.isFile &&
        cachedIndex?.optString("sourceStamp") == sourceStamp &&
        cachedIndex.optInt("extractor") == EPUB_READER_EXTRACTOR_VERSION
    ) {
        return root
    }

    root.deleteRecursively()
    root.mkdirs()
    runCatching {
        // 走中央目录读（见 withEpubZipFile 的注释）：流式读法对"stored + 本地头尺寸为 0"的书会静默失败
        withEpubZipFile(context, uri) { zip ->
            var entryCount = 0
            var totalBytes = 0L
            var contentEntries = 0
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount += 1
                requireEpubEntryBudget(entryCount)
                requireKnownEpubEntrySize(entry.size)
                val normalized = normalizeSafeEpubArchivePath(entry.name)
                if (normalized.isNullOrBlank()) continue
                val output = root.resolve(normalized).canonicalFile
                require(output.path == root.path || output.path.startsWith(root.path + File.separator)) {
                    "Unsafe EPUB entry: ${entry.name}"
                }
                if (entry.isDirectory) {
                    output.mkdirs()
                    continue
                }
                output.parentFile?.mkdirs()
                zip.getInputStream(entry).use { stream ->
                    output.outputStream().use {
                        totalBytes += stream.copyToLimited(
                            output = it,
                            remainingTotalBytes = EPUB_ARCHIVE_MAX_TOTAL_BYTES - totalBytes
                        )
                    }
                }
                if (normalized.isEpubReaderContentEntry()) contentEntries += 1
            }
            // 一个可读条目都没抽出来 = 这次解包事实上失败了（旧流式读法正是在这里静默"成功"）。
            // 抛错让调用方退回直读 ZIP，比落一份"成功"的空缓存好——后者会被 stamp 命中后长期复用。
            require(contentEntries > 0) { "EPUB has no readable content entries: $uri" }
        }
        indexFile.writeText(
            JSONObject()
                .put("sourceUri", uri.toString())
                .put("sourceStamp", sourceStamp)
                .put("displayName", displayName)
                .put("extractor", EPUB_READER_EXTRACTOR_VERSION)
                .put("createdAtMs", System.currentTimeMillis())
                .toString()
        )
    }.onFailure { error ->
        root.deleteRecursively()
        throw error
    }
    return root
}

/** 缓存里"读得懂"的条目：容器 / OPF / 正文 HTML。图片不算——只有图片的书本来就打不开。 */
private fun String.isEpubReaderContentEntry(): Boolean =
    equals("META-INF/container.xml", ignoreCase = true) ||
        endsWith(".opf", ignoreCase = true) ||
        endsWith(".xhtml", ignoreCase = true) ||
        endsWith(".html", ignoreCase = true) ||
        endsWith(".htm", ignoreCase = true)
