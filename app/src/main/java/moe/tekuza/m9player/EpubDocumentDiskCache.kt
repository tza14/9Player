package moe.tekuza.m9player

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 解析结果的落盘缓存。
 *
 * 背景（实测，模拟机）：`loadEpubDocumentFromCache` 花 **571ms**（20 章 / 19 图）——"解压条目"的缓存
 * 已经命中（`EpubReaderCacheStore`），慢的是**每次启动都重新把 HTML 转成正文并分章**。把这一步的结果
 * 落盘后，第二次打开可以直接读回来（目标 <100ms）。
 *
 * 设计要点：
 * - 正文走**每章一个纯文本文件**（避免给整本书做 JSON 转义，读写都便宜）；
 * - 元数据（章名/层级/图片/ruby）走 TSV（比 JSON 快得多，ruby 多的书差别明显）；
 * - 列表类字段用 TSV 行 + `\\t`/`\\n` 转义；
 * - **失败安全**：写完后立刻回读并与内存中的文档比对指纹（章数、每章字数、图片数、ruby 数、层级/卷标记），
 *   任何不一致就删掉缓存、退回内存里这份解析结果。这样将来实体加字段忘了同步，最坏也只是"缓存不生效"，
 *   不会读出错误的书。
 */
private const val EPUB_DOC_CACHE_VERSION = 1
private const val EPUB_DOC_DIR = "reader_doc_v1"
private const val EPUB_DOC_MANIFEST = "manifest.json"

/** 与 EbookReaderCore 里的 tag 同名（那里的是文件私有），日志仍归到同一个 tag 下便于过滤。 */
private const val DOC_CACHE_LOG_TAG = "EbookReaderCore"

/**
 * 解析缓存的失效键：条目缓存目录里 `reader_index.json` 的文本指纹 + charset + 版本。
 * 条目缓存因换书/换 charset 而重建时这个值就变 ⇒ 解析缓存自然失效（不必额外读源文件 mtime）。
 * 读不到 index 时返回 null ⇒ 完全不用解析缓存（宁可不省时间，也不能读错书）。
 */
internal fun epubDocumentCacheStamp(root: File, preferredCharsetName: String?): String? {
    val index = File(root, "reader_index.json")
    if (!index.isFile) return null
    return runCatching {
        val text = index.readText(StandardCharsets.UTF_8)
        "$EPUB_DOC_CACHE_VERSION:${text.hashCode()}:${text.length}:${preferredCharsetName.orEmpty()}"
    }.getOrNull()
}

private fun docCacheDir(root: File): File = File(root, EPUB_DOC_DIR)

/**
 * 读缓存。任何异常/版本不符/指纹不符都返回 null（调用方走正常解析）。
 *
 * @param stamp 失效键的一部分：缓存目录里 `reader_index.json` 的文本指纹 + charset。
 *              条目缓存重建（换书/换 charset）时这个值会变 ⇒ 解析缓存自然失效。
 */
internal fun loadCachedEbookDocument(root: File, stamp: String): EbookDocument? {
    val dir = docCacheDir(root)
    return runCatching {
        val manifestFile = File(dir, EPUB_DOC_MANIFEST)
        if (!manifestFile.isFile) return null
        val manifest = JSONObject(manifestFile.readText(StandardCharsets.UTF_8))
        if (manifest.optInt("version", -1) != EPUB_DOC_CACHE_VERSION) return null
        if (manifest.optString("stamp") != stamp) return null

        val chaptersJson = manifest.getJSONArray("chapters")
        val chapters = ArrayList<EbookChapter>(chaptersJson.length())
        for (index in 0 until chaptersJson.length()) {
            val item = chaptersJson.getJSONObject(index)
            val body = File(dir, bodyName(index))
            if (!body.isFile) return null
            val text = body.readText(StandardCharsets.UTF_8)
            val images = readImages(File(dir, imageName(index)))
            val ruby = readRuby(File(dir, rubyName(index)))
            chapters += EbookChapter(
                title = item.getString("title"),
                text = text,
                sourcePath = item.optString("sourcePath").takeIf { it.isNotEmpty() },
                images = images,
                rubySpans = ruby,
                isVolume = item.optBoolean("isVolume"),
                level = item.optInt("level"),
                isGroup = item.optBoolean("isGroup"),
                titleFromMarkup = item.optBoolean("titleFromMarkup", true)
            )
        }
        val document = EbookDocument(
            title = manifest.getString("title"),
            format = manifest.getString("format"),
            chapters = chapters
        )
        // 回读校验：内容对不上就当作没有缓存（宁可不省时间，也不能读出错的书）。
        if (!fingerprintMatches(document, manifest)) return null
        logDebug(DOC_CACHE_LOG_TAG) {
            "docCache hit chapters=${document.chapters.size} dir=${dir.name}"
        }
        document
    }.getOrNull()
}

/**
 * 写缓存。写完后回读校验，不一致就把缓存文件删掉（只记日志，绝不影响本次阅读）。
 * 图片带内联字节（data: URI）时直接放弃缓存 —— 那种图不是文件后端，序列化代价与风险都不划算。
 */
internal fun saveCachedEbookDocument(root: File, document: EbookDocument, stamp: String) {
    if (document.chapters.any { chapter -> chapter.images.values.any { it.bytes != null } }) {
        logDebug(DOC_CACHE_LOG_TAG) { "docCache skip (inline image bytes)" }
        return
    }
    val dir = docCacheDir(root)
    runCatching {
        dir.mkdirs()
        document.chapters.forEachIndexed { index, chapter ->
            File(dir, bodyName(index)).writeText(chapter.text, StandardCharsets.UTF_8)
            writeImages(File(dir, imageName(index)), chapter.images)
            writeRuby(File(dir, rubyName(index)), chapter.rubySpans)
        }
        // 章数变少时清掉多余的旧文件，避免下次读到陈旧分片。
        cleanStaleShards(dir, document.chapters.size)
        val manifest = JSONObject().apply {
            put("version", EPUB_DOC_CACHE_VERSION)
            put("stamp", stamp)
            put("title", document.title)
            put("format", document.format)
            put("chapters", JSONArray().apply {
                document.chapters.forEach { chapter ->
                    put(JSONObject().apply {
                        put("title", chapter.title)
                        put("sourcePath", chapter.sourcePath.orEmpty())
                        put("isVolume", chapter.isVolume)
                        put("level", chapter.level)
                        put("isGroup", chapter.isGroup)
                        put("titleFromMarkup", chapter.titleFromMarkup)
                        put("textLength", chapter.text.length)
                        put("imageCount", chapter.images.size)
                        put("rubyCount", chapter.rubySpans.size)
                    })
                }
            })
        }
        File(dir, EPUB_DOC_MANIFEST).writeText(manifest.toString(), StandardCharsets.UTF_8)

        val verified = loadCachedEbookDocument(root, stamp)
        if (verified == null) {
            dir.deleteRecursively()
            Log.w(DOC_CACHE_LOG_TAG, "docCache verify failed; cache discarded")
        } else {
            logDebug(DOC_CACHE_LOG_TAG) {
                "docCache stored chapters=${document.chapters.size} dir=${dir.name}"
            }
        }
    }.onFailure { error ->
        Log.w(DOC_CACHE_LOG_TAG, "docCache write failed: ${error.message}")
        dir.deleteRecursively()
    }
}

/** 指纹：章数 + 每章 title/字数/图片数/ruby 数/层级/卷标记，全部来自 manifest。 */
private fun fingerprintMatches(document: EbookDocument, manifest: JSONObject): Boolean {
    val chaptersJson = manifest.optJSONArray("chapters") ?: return false
    if (chaptersJson.length() != document.chapters.size) return false
    document.chapters.forEachIndexed { index, chapter ->
        val item = chaptersJson.optJSONObject(index) ?: return false
        if (item.optString("title") != chapter.title) return false
        if (item.optInt("textLength", -1) != chapter.text.length) return false
        if (item.optInt("imageCount", -1) != chapter.images.size) return false
        if (item.optInt("rubyCount", -1) != chapter.rubySpans.size) return false
        if (item.optBoolean("isVolume") != chapter.isVolume) return false
        if (item.optInt("level") != chapter.level) return false
        if (item.optBoolean("isGroup") != chapter.isGroup) return false
        if (item.optBoolean("titleFromMarkup", true) != chapter.titleFromMarkup) return false
    }
    return true
}

private fun bodyName(index: Int): String = "body_%04d.txt".format(index)

private fun imageName(index: Int): String = "image_%04d.tsv".format(index)

private fun rubyName(index: Int): String = "ruby_%04d.tsv".format(index)

private fun cleanStaleShards(dir: File, chapterCount: Int) {
    val prefixes = listOf("body_", "image_", "ruby_")
    dir.listFiles().orEmpty().forEach { file ->
        val prefix = prefixes.firstOrNull { file.name.startsWith(it) } ?: return@forEach
        val index = file.name.removePrefix(prefix).substringBefore('.').toIntOrNull() ?: return@forEach
        if (index >= chapterCount) file.delete()
    }
}

private fun writeImages(file: File, images: Map<Int, EbookImageRef>) {
    if (images.isEmpty()) {
        file.delete()
        return
    }
    val lines = images.entries.sortedBy { it.key }.joinToString(separator = "\n") { (offset, ref) ->
        listOf(
            offset.toString(),
            escape(ref.path),
            escape(ref.altText),
            escape(ref.mediaType.orEmpty()),
            escape(ref.filePath.orEmpty()),
            ref.origin.name
        ).joinToString(separator = "\t")
    }
    file.writeText(lines, StandardCharsets.UTF_8)
}

private fun readImages(file: File): Map<Int, EbookImageRef> {
    if (!file.isFile) return emptyMap()
    val out = LinkedHashMap<Int, EbookImageRef>()
    file.readText(StandardCharsets.UTF_8).lineSequence().forEach { line ->
        if (line.isEmpty()) return@forEach
        val parts = line.split('\t')
        if (parts.size < 6) return@forEach
        val offset = parts[0].toIntOrNull() ?: return@forEach
        out[offset] = EbookImageRef(
            path = unescape(parts[1]),
            altText = unescape(parts[2]),
            mediaType = unescape(parts[3]).takeIf { it.isNotEmpty() },
            bytes = null,
            filePath = unescape(parts[4]).takeIf { it.isNotEmpty() },
            origin = runCatching { EbookImageOrigin.valueOf(parts[5]) }
                .getOrDefault(EbookImageOrigin.INLINE)
        )
    }
    return out
}

private fun writeRuby(file: File, spans: List<EbookRubySpan>) {
    if (spans.isEmpty()) {
        file.delete()
        return
    }
    val lines = spans.joinToString(separator = "\n") { span ->
        val head = listOf(
            span.start.toString(),
            span.end.toString(),
            escape(span.text),
            span.kind.name,
            span.segments.size.toString()
        )
        val segments = span.segments.flatMap { segment ->
            listOf(segment.baseStart.toString(), segment.baseEnd.toString(), escape(segment.text))
        }
        (head + segments).joinToString(separator = "\t")
    }
    file.writeText(lines, StandardCharsets.UTF_8)
}

private fun readRuby(file: File): List<EbookRubySpan> {
    if (!file.isFile) return emptyList()
    val out = ArrayList<EbookRubySpan>()
    file.readText(StandardCharsets.UTF_8).lineSequence().forEach { line ->
        if (line.isEmpty()) return@forEach
        val parts = line.split('\t')
        if (parts.size < 5) return@forEach
        val start = parts[0].toIntOrNull() ?: return@forEach
        val end = parts[1].toIntOrNull() ?: return@forEach
        val segmentCount = parts[4].toIntOrNull() ?: 0
        val segments = ArrayList<EbookRubySegment>(segmentCount)
        var cursor = 5
        repeat(segmentCount) {
            if (cursor + 2 >= parts.size + 0 && cursor + 2 > parts.lastIndex) return@forEach
            val baseStart = parts[cursor].toIntOrNull()
            val baseEnd = parts.getOrNull(cursor + 1)?.toIntOrNull()
            val text = parts.getOrNull(cursor + 2)
            if (baseStart == null || baseEnd == null || text == null) return@forEach
            segments += EbookRubySegment(baseStart = baseStart, baseEnd = baseEnd, text = unescape(text))
            cursor += 3
        }
        out += EbookRubySpan(
            start = start,
            end = end,
            text = unescape(parts[2]),
            kind = runCatching { EbookRubyKind.valueOf(parts[3]) }.getOrDefault(EbookRubyKind.UNKNOWN),
            segments = segments
        )
    }
    return out
}

private fun escape(value: String): String =
    value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

private fun unescape(value: String): String {
    val out = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '\\' && index + 1 < value.length) {
            when (value[index + 1]) {
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                '\\' -> out.append('\\')
                else -> out.append(value[index + 1])
            }
            index += 2
        } else {
            out.append(char)
            index += 1
        }
    }
    return out.toString()
}
