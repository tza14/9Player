package moe.tekuza.m9player

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

private data class EpubCoverOpfItem(
    val href: String,
    val mediaType: String?,
    val properties: String?
)

private data class EpubCoverOpfData(
    val manifest: Map<String, EpubCoverOpfItem>,
    val coverId: String?
)

internal fun resolveEmbeddedCoverUriForEpub(
    context: Context,
    ebookUri: Uri?,
    ebookDisplayName: String?,
    ebookFormat: String?
): Uri? {
    val sourceUri = ebookUri ?: return null
    val displayName = ebookDisplayName?.trim().orEmpty()
    val isEpub = ebookFormat.equals("EPUB", ignoreCase = true) ||
        displayName.endsWith(".epub", ignoreCase = true) ||
        context.contentResolver.getType(sourceUri).orEmpty().equals("application/epub+zip", ignoreCase = true)
    if (!isEpub) return null

    val coverDir = File(File(context.filesDir, "books"), "covers")
    if (!coverDir.exists()) coverDir.mkdirs()
    val cacheKey = buildDictionaryCacheKey("epub-cover|$sourceUri", displayName.ifBlank { sourceUri.toString() })
    val existing = coverDir.listFiles()
        ?.firstOrNull { it.nameWithoutExtension == "cover-$cacheKey" }
    if (existing != null && existing.exists() && existing.length() > 0L) {
        return Uri.fromFile(existing)
    }

    return runCatching {
        extractEpubCoverBytes(context, sourceUri)?.let { cover ->
            val ext = detectEpubCoverFileExtension(cover.bytes, cover.path, cover.mediaType)
            val outFile = File(coverDir, "cover-$cacheKey.$ext")
            outFile.writeBytes(cover.bytes)
            if (outFile.exists() && outFile.length() > 0L) Uri.fromFile(outFile) else null
        }
    }.getOrNull()
}

private data class EpubCoverBytes(
    val path: String,
    val mediaType: String?,
    val bytes: ByteArray
)

private fun extractEpubCoverBytes(context: Context, uri: Uri): EpubCoverBytes? {
    val xmlEntries = linkedMapOf<String, ByteArray>()
    val imageCandidates = linkedSetOf<String>()
    val input = openEpubCoverInputStream(context, uri) ?: return null
    // 走中央目录读（见 withEpubZipFile）：流式读法对"stored + 本地头尺寸为 0"的书取不到任何条目
    withZipFileFromStream(input, context.cacheDir) { zip ->
        var entryCount = 0
        var totalBytes = 0L
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            entryCount += 1
            requireEpubReaderMemoryEntryBudget(entryCount)
            val normalized = normalizeSafeEpubArchivePath(entry.name)
            if (normalized.isNullOrBlank() || entry.isDirectory) continue
            if (isRasterEpubImagePath(normalized)) {
                imageCandidates += normalized
                continue
            }
            val shouldReadXml = normalized.equals("META-INF/container.xml", ignoreCase = true) ||
                normalized.endsWith(".opf", ignoreCase = true)
            if (!shouldReadXml) continue
            requireKnownEpubEntrySize(entry.size, maxEntryBytes = EPUB_READER_MEMORY_MAX_ENTRY_BYTES)
            val remaining = EPUB_READER_MEMORY_MAX_TOTAL_BYTES - totalBytes
            val bytes = zip.getInputStream(entry).use { stream ->
                stream.readBytesLimited(
                    maxEntryBytes = EPUB_READER_MEMORY_MAX_ENTRY_BYTES,
                    remainingTotalBytes = remaining
                )
            }
            totalBytes += bytes.size
            xmlEntries[normalized] = bytes
        }
    }

    val containerXml = xmlEntries["META-INF/container.xml"]?.toString(StandardCharsets.UTF_8)
    val opfPath = containerXml?.let { parseEpubCoverContainerRootFile(it) }
        ?: xmlEntries.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
        ?: return fallbackEpubCoverImage(context, uri, imageCandidates)
    val opfXml = xmlEntries[opfPath]?.toString(StandardCharsets.UTF_8)
        ?: return fallbackEpubCoverImage(context, uri, imageCandidates)
    val opf = parseEpubCoverOpf(opfXml)
    val opfBasePath = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
    val coverPath = findEpubCoverPath(opf, opfBasePath)
    if (coverPath != null) {
        readEpubCoverImage(context, uri, coverPath)?.let { return it }
    }
    return fallbackEpubCoverImage(context, uri, imageCandidates)
}

private fun readEpubCoverImage(
    context: Context,
    uri: Uri,
    targetPath: String
): EpubCoverBytes? {
    val normalizedTarget = targetPath.normalizeEpubCoverZipPath()
    val input = openEpubCoverInputStream(context, uri) ?: return null
    var found: EpubCoverBytes? = null
    withZipFileFromStream(input, context.cacheDir) { zip ->
        val entries = zip.entries()
        var entryCount = 0
        while (found == null && entries.hasMoreElements()) {
            val entry = entries.nextElement()
            entryCount += 1
            requireEpubReaderMemoryEntryBudget(entryCount)
            val normalized = normalizeSafeEpubArchivePath(entry.name)
            if (normalized.isNullOrBlank() || entry.isDirectory) continue
            if (!normalized.equals(normalizedTarget, ignoreCase = true)) continue
            requireKnownEpubEntrySize(entry.size, maxEntryBytes = EPUB_READER_MEMORY_MAX_ENTRY_BYTES)
            val bytes = zip.getInputStream(entry).use { stream ->
                stream.readBytesLimited(
                    maxEntryBytes = EPUB_READER_MEMORY_MAX_ENTRY_BYTES,
                    remainingTotalBytes = EPUB_READER_MEMORY_MAX_ENTRY_BYTES
                )
            }
            found = EpubCoverBytes(
                path = normalized,
                mediaType = mediaTypeFromEpubImagePath(normalized),
                bytes = bytes
            )
        }
    }
    return found
}

private fun openEpubCoverInputStream(context: Context, uri: Uri) =
    if (uri.scheme.equals("file", ignoreCase = true)) {
        runCatching {
            val path = uri.path ?: return@runCatching null
            File(path).inputStream()
        }.getOrNull()
    } else {
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: runCatching {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@runCatching null
                ParcelFileDescriptor.AutoCloseInputStream(pfd)
            }.getOrNull()
    }

private fun parseEpubCoverContainerRootFile(xml: String): String? {
    val parser = Xml.newPullParser()
    parser.setInput(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)), "UTF-8")
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
            return parser.getAttributeValue(null, "full-path")?.normalizeEpubCoverZipPath()
        }
    }
    return null
}

private fun parseEpubCoverOpf(xml: String): EpubCoverOpfData {
    val parser = Xml.newPullParser()
    parser.setInput(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)), "UTF-8")
    val manifest = linkedMapOf<String, EpubCoverOpfItem>()
    var coverId: String? = null
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType != XmlPullParser.START_TAG) continue
        when (parser.name) {
            "meta" -> {
                val name = parser.getAttributeValue(null, "name").orEmpty()
                if (name.equals("cover", ignoreCase = true)) {
                    coverId = parser.getAttributeValue(null, "content")?.trim()?.takeIf { it.isNotBlank() }
                }
            }
            "item" -> {
                val id = parser.getAttributeValue(null, "id").orEmpty()
                val href = parser.getAttributeValue(null, "href").orEmpty()
                if (id.isNotBlank() && href.isNotBlank()) {
                    manifest[id] = EpubCoverOpfItem(
                        href = href,
                        mediaType = parser.getAttributeValue(null, "media-type"),
                        properties = parser.getAttributeValue(null, "properties")
                    )
                }
            }
        }
    }
    return EpubCoverOpfData(manifest = manifest, coverId = coverId)
}

private fun findEpubCoverPath(opf: EpubCoverOpfData, opfBasePath: String): String? {
    fun itemPath(item: EpubCoverOpfItem): String? {
        if (!item.mediaType.orEmpty().startsWith("image/", ignoreCase = true)) return null
        if (!isRasterEpubImagePath(item.href)) return null
        return resolveEpubCoverPath(opfBasePath, item.href)
    }
    opf.coverId?.let { coverId ->
        opf.manifest[coverId]?.let { itemPath(it) }?.let { return it }
    }
    opf.manifest.values.firstOrNull { item ->
        item.properties
            ?.split(Regex("\\s+"))
            ?.any { it.equals("cover-image", ignoreCase = true) } == true
    }?.let { itemPath(it) }?.let { return it }
    opf.manifest.entries.firstOrNull { (id, item) ->
        item.mediaType.orEmpty().startsWith("image/", ignoreCase = true) &&
            (id.contains("cover", ignoreCase = true) || item.href.contains("cover", ignoreCase = true))
    }?.value?.let { itemPath(it) }?.let { return it }
    return null
}

private fun fallbackEpubCoverImage(
    context: Context,
    uri: Uri,
    images: Collection<String>
): EpubCoverBytes? {
    val candidate = images.firstOrNull { path ->
        val name = path.substringAfterLast('/')
        name.contains("cover", ignoreCase = true) ||
            name.contains("front", ignoreCase = true) ||
            name.contains("title", ignoreCase = true)
    } ?: images.firstOrNull()
    return candidate?.let { readEpubCoverImage(context, uri, it) }
}

private fun resolveEpubCoverPath(basePath: String, href: String): String {
    val decoded = runCatching { URLDecoder.decode(href.substringBefore('#'), "UTF-8") }
        .getOrElse { href.substringBefore('#') }
    val combined = if (basePath.isBlank()) decoded else "$basePath/$decoded"
    val out = ArrayDeque<String>()
    combined.normalizeEpubCoverZipPath().split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeLast()
            else -> out.addLast(part)
        }
    }
    return out.joinToString("/")
}

private fun String.normalizeEpubCoverZipPath(): String {
    return trim()
        .replace('\\', '/')
        .removePrefix("/")
}

private fun isRasterEpubImagePath(path: String): Boolean {
    val lower = path.substringBefore('#').lowercase(Locale.ROOT)
    return lower.endsWith(".jpg") ||
        lower.endsWith(".jpeg") ||
        lower.endsWith(".png") ||
        lower.endsWith(".webp") ||
        lower.endsWith(".gif") ||
        lower.endsWith(".bmp")
}

private fun mediaTypeFromEpubImagePath(path: String): String {
    return when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }
}

private fun detectEpubCoverFileExtension(bytes: ByteArray, path: String, mediaType: String?): String {
    val normalizedMime = mediaType.orEmpty().lowercase(Locale.ROOT)
    return when {
        normalizedMime.contains("png") -> "png"
        normalizedMime.contains("webp") -> "webp"
        normalizedMime.contains("gif") -> "gif"
        normalizedMime.contains("bmp") -> "bmp"
        bytes.size >= 4 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() -> "png"
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "jpg"
        bytes.size >= 4 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() -> "webp"
        else -> path.substringAfterLast('.', "jpg").lowercase(Locale.ROOT).takeIf { isRasterEpubImagePath("x.$it") }
            ?: "jpg"
    }
}
