package moe.tekuza.m9player

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.text.Html
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.max

private const val EBOOK_READER_CORE_LOG_TAG = "EbookReaderCore"

internal data class EbookDocument(
    val title: String,
    val format: String,
    val chapters: List<EbookChapter>
)

/**
 * EPUB 目录（ncx/nav）里的一条：标题 + 目标文件 + 锚点。
 * 同一文件可能有多条、用锚点区分小节 —— 解析时按锚点把它切成多章。
 */
internal data class EpubTocEntry(
    val title: String,
    val path: String,
    val fragment: String = ""
)

internal data class EbookChapter(
    val title: String,
    val text: String,
    val sourcePath: String? = null,
    val images: Map<Int, EbookImageRef> = emptyMap(),
    val rubySpans: List<EbookRubySpan> = emptyList(),
    val isVolume: Boolean = false,
    /**
     * 标题是否来自目录条目或正文标题元素（h1-h3）。
     * false 表示只是 `<title>` 兜底 —— calibre 常常把它写成 part0012 这种文件名，
     * 这种页不是真正的章节，解析时会并入相邻章节（见 mergeSpinePartsIntoChapters）。
     */
    val titleFromMarkup: Boolean = true
)

/**
 * 卷/部分标题模式，如"第一部分 睡眠这件事"、"第三部分 梦的产生和原因"、
 * "卷二 春"、"卷首"、"Part One"等。匹配的章节在目录中灰色特殊显示，且正文不重复标题。
 */
private val volumeTitlePattern = Regex(
    pattern = "^第\\s*[0-9〇零一二三四五六七八九十百千万两]+\\s*[卷部篇编][\\s　]*.*$|" +
        "^卷首[\\s　]*$|" +
        "^part\\s+\\S+.*$",
    options = setOf(RegexOption.IGNORE_CASE)
)

private fun String.isVolumeTitle(): Boolean = volumeTitlePattern.matches(trim())

internal data class EbookRubySpan(
    val start: Int,
    val end: Int,
    val text: String,
    val kind: EbookRubyKind = EbookRubyKind.UNKNOWN,
    val segments: List<EbookRubySegment> = emptyList()
)

internal enum class EbookRubyKind {
    MONO,
    GROUP,
    JUKUGO,
    UNKNOWN
}

internal data class EbookRubySegment(
    val baseStart: Int,
    val baseEnd: Int,
    val text: String
)

/**
 * 一张图在电子书里是"怎么排的"，决定听读时是否要在它上面停下来：
 * - [INLINE]：写在有正文的章节里（可能是正文中段的插画，也可能是章节开头的题图）；
 * - [ILLUSTRATION_PAGE]：来自"纯图片 spine 页"（整页只有图、没有正文）且这页不被目录指向
 *   —— 出版方专门为这张插图排的一页，是最典型的"停下来看看"的对象；
 * - [SECTION_TITLE_PAGE]：来自"纯图片 spine 页"但该页被目录条目指向（= 章节题图页/表纸），
 *   它是章节的排版开头，不是插图，不该触发遇图暂停。
 */
internal enum class EbookImageOrigin {
    INLINE,
    ILLUSTRATION_PAGE,
    SECTION_TITLE_PAGE
}

internal data class EbookImageRef(
    val path: String,
    val altText: String,
    val mediaType: String?,
    val bytes: ByteArray? = null,
    val filePath: String? = null,
    val origin: EbookImageOrigin = EbookImageOrigin.INLINE
) {
    fun readBytes(): ByteArray? =
        bytes ?: filePath?.let { path -> File(path).takeIf { it.isFile }?.readBytes() }

    fun cacheIdentity(): String {
        val file = filePath?.let(::File)
        return if (file != null && file.isFile) {
            "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        } else {
            "${path}:${bytes?.size ?: 0}"
        }
    }
}

internal data class EbookSrtCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

internal data class EbookCueMatch(
    val cueIndex: Int,
    val chapterIndex: Int,
    val rawStart: Int,
    val rawEnd: Int
)

internal data class EbookMatchData(
    val matches: List<EbookCueMatch>,
    val unmatched: Int,
    val totalCues: Int
) {
    val matchRateText: String
        get() {
            if (totalCues <= 0) return "0%"
            val rate = matches.size.toDouble() / totalCues.toDouble() * 100.0
            return String.format(Locale.US, "%.1f%%", rate)
        }
}

internal fun shouldSkipEbookCueForMatching(cue: EbookSrtCue): Boolean {
    return shouldSkipEbookCueForMatching(cue, cue.text.filteredReaderCodePoints())
}

private fun shouldSkipEbookCueForMatching(cue: EbookSrtCue, filteredText: List<Int>): Boolean {
    return filteredText.isEmpty() || (cue.text.startsWith("＊") && filteredText.size < 5)
}

internal suspend fun loadEbookDocument(
    context: Context,
    book: LocalReaderBook,
    preferredCharsetName: String? = null
): EbookDocument = withContext(Dispatchers.IO) {
    val displayTitle = book.title.ifBlank { "Untitled Book" }
    when (book.format.uppercase(Locale.US)) {
        "EPUB" -> loadEpubDocument(context, book.uri, displayTitle, preferredCharsetName)
        "TXT" -> loadTxtDocument(context.contentResolver, book.uri, displayTitle, preferredCharsetName)
        else -> {
            val mimeFormat = inferLocalReaderBookFormat(displayTitle, context.contentResolver.getType(book.uri))
            if (mimeFormat == "EPUB") {
                loadEpubDocument(context, book.uri, displayTitle, preferredCharsetName)
            } else {
                loadTxtDocument(context.contentResolver, book.uri, displayTitle, preferredCharsetName)
            }
        }
    }
}

internal suspend fun parseEbookSrt(
    contentResolver: ContentResolver,
    uri: Uri
): List<EbookSrtCue> = withContext(Dispatchers.IO) {
    val raw = contentResolver.openInputStream(uri)?.use { input ->
        input.readBytes().decodeTextFile()
    }.orEmpty()
    parseEbookSrtText(raw)
}

internal fun matchEbookCuesData(
    document: EbookDocument,
    cues: List<EbookSrtCue>,
    searchWindow: Int = 200
): EbookMatchData {
    val index = buildMatchingIndex(document)
    val source = index.source.codePoints().toArray().toList()
    val chapterRanges = index.chapterRanges
    val chapterMaps = index.chapterMaps

    var cursor = 0
    var minStart: Int? = null
    cues.take(15).forEach { cue ->
        if (cue.text.startsWith("＊")) return@forEach
        val text = cue.text.filteredReaderCodePoints()
        if (text.size < 6) return@forEach
        val found = findCodePointText(source, text, start = 0, end = source.size) ?: return@forEach
        minStart = minOf(minStart ?: found, found)
    }
    minStart?.let { cursor = it }

    val matches = mutableListOf<EbookCueMatch>()
    var unmatched = 0
    cues.forEachIndexed { cueIndex, cue ->
        val text = cue.text.filteredReaderCodePoints()
        if (shouldSkipEbookCueForMatching(cue, text)) {
            unmatched += 1
            return@forEachIndexed
        }
        val start = findCodePointText(
            source = source,
            text = text,
            start = cursor,
            end = minOf(source.size, cursor + text.size + searchWindow)
        )
        if (start == null) {
            unmatched += 1
            return@forEachIndexed
        }
        val end = start + text.size
        val match = resolveCueMatch(
            document = document,
            cueIndex = cueIndex,
            filteredStart = start,
            filteredEndExclusive = end,
            chapterRanges = chapterRanges,
            chapterMaps = chapterMaps
        )
        if (match == null) {
            unmatched += 1
            return@forEachIndexed
        }
        matches += match
        cursor = end
    }
    return EbookMatchData(
        matches = matches,
        unmatched = unmatched,
        totalCues = cues.size
    )
}

private data class EbookMatchingIndex(
    val source: String,
    val chapterMaps: List<FilteredTextMap>,
    val chapterRanges: List<IntRange>
) {
    val sourceCodePointSize: Int get() = source.codePointCount(0, source.length)
}

private fun buildMatchingIndex(document: EbookDocument): EbookMatchingIndex {
    val chapterMaps = document.chapters.map { chapter ->
        buildFilteredTextMap(chapter.text)
    }
    val source = StringBuilder()
    val chapterRanges = mutableListOf<IntRange>()
    var codePointStart = 0
    chapterMaps.forEach { map ->
        source.append(map.filtered)
        val length = map.filtered.codePointCount(0, map.filtered.length)
        chapterRanges += codePointStart until (codePointStart + length)
        codePointStart += length
    }
    return EbookMatchingIndex(
        source = source.toString(),
        chapterMaps = chapterMaps,
        chapterRanges = chapterRanges
    )
}

private fun resolveCueMatch(
    document: EbookDocument,
    cueIndex: Int,
    filteredStart: Int,
    filteredEndExclusive: Int,
    chapterRanges: List<IntRange>,
    chapterMaps: List<FilteredTextMap>
): EbookCueMatch? {
    val chapterIndex = chapterRanges.indexOfFirst { filteredStart in it }
    if (chapterIndex < 0 || filteredEndExclusive > chapterRanges[chapterIndex].last + 1) return null
    val localStart = filteredStart - chapterRanges[chapterIndex].first
    val localEnd = (filteredEndExclusive - chapterRanges[chapterIndex].first - 1).coerceAtLeast(localStart)
    val map = chapterMaps[chapterIndex]
    val rawStart = map.rawIndices.getOrNull(localStart) ?: return null
    val rawEnd = (map.rawIndices.getOrNull(localEnd)?.let { index ->
        document.chapters[chapterIndex].text.offsetByCodePoints(index, 1)
    } ?: rawStart).coerceAtLeast(rawStart)
    return EbookCueMatch(
        cueIndex = cueIndex,
        chapterIndex = chapterIndex,
        rawStart = rawStart,
        rawEnd = rawEnd
    )
}

internal fun findEbookCueIndexAtTime(cues: List<EbookSrtCue>, timeMs: Long): Int {
    if (cues.isEmpty()) return -1
    var low = 0
    var high = cues.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        val cue = cues[mid]
        when {
            timeMs < cue.startMs -> high = mid - 1
            timeMs >= cue.endMs -> low = mid + 1
            else -> return mid
        }
    }
    return (low - 1).coerceIn(-1, cues.lastIndex)
}

private fun loadTxtDocument(
    contentResolver: ContentResolver,
    uri: Uri,
    fallbackTitle: String,
    preferredCharsetName: String?
): EbookDocument {
    val raw = contentResolver.openInputStream(uri)?.use { input ->
        input.readBytes().decodeTextFile(preferredCharsetName)
    }.orEmpty()
    val chapters = splitTxtChapters(raw)
    return EbookDocument(
        title = fallbackTitle,
        format = "TXT",
        chapters = chapters.ifEmpty { listOf(EbookChapter(fallbackTitle, raw)) }
    )
}

private fun loadEpubDocument(
    context: Context,
    uri: Uri,
    fallbackTitle: String,
    preferredCharsetName: String?
): EbookDocument {
    val cacheRoot = runCatching {
        ensureEpubReaderCache(context, uri, fallbackTitle)
    }.onFailure { error ->
        Log.w(EBOOK_READER_CORE_LOG_TAG, "loadEpubDocument cache unavailable, falling back to zip uri=$uri", error)
    }.getOrNull()
    if (cacheRoot != null) {
        return loadEpubDocumentFromCache(cacheRoot, fallbackTitle, preferredCharsetName)
    }
    return loadEpubDocumentFromZip(context.contentResolver, uri, fallbackTitle, preferredCharsetName)
}

private fun loadEpubDocumentFromZip(
    contentResolver: ContentResolver,
    uri: Uri,
    fallbackTitle: String,
    preferredCharsetName: String?
): EbookDocument {
    val startMs = SystemClock.elapsedRealtime()
    val entries = linkedMapOf<String, ByteArray>()
    var entryCount = 0
    var htmlEntryCount = 0
    var imageEntryCount = 0
    var imageBytes = 0L
    var readerBytes = 0L
    val zipStartMs = SystemClock.elapsedRealtime()
    contentResolver.openInputStream(uri)?.use { input ->
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    entryCount += 1
                    requireEpubEntryBudget(entryCount)
                    requireEpubReaderMemoryEntryBudget(entryCount)
                    requireKnownEpubEntrySize(
                        size = entry.size,
                        maxEntryBytes = EPUB_READER_MEMORY_MAX_ENTRY_BYTES
                    )
                    val path = normalizeSafeEpubArchivePath(entry.name)
                    if (path != null && path.isReaderEpubEntry()) {
                        val bytes = zip.readBytesLimited(
                            maxEntryBytes = EPUB_READER_MEMORY_MAX_ENTRY_BYTES,
                            remainingTotalBytes = EPUB_READER_MEMORY_MAX_TOTAL_BYTES - readerBytes
                        )
                        entries[path] = bytes
                        readerBytes += bytes.size.toLong()
                        if (path.isEpubImagePath()) {
                            imageEntryCount += 1
                            imageBytes += bytes.size.toLong()
                        } else if (
                            path.endsWith(".xhtml", true) ||
                            path.endsWith(".html", true) ||
                            path.endsWith(".htm", true)
                        ) {
                            htmlEntryCount += 1
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
    logDebug(EBOOK_READER_CORE_LOG_TAG) {
        "loadEpubDocument zipScan=${SystemClock.elapsedRealtime() - zipStartMs}ms " +
        "entries=$entryCount readerEntries=${entries.size} htmlEntries=$htmlEntryCount " +
        "imageEntries=$imageEntryCount readerBytes=$readerBytes imageBytes=$imageBytes uri=$uri"
    }
    if (entries.isEmpty()) {
        logDebug(EBOOK_READER_CORE_LOG_TAG) {
            "loadEpubDocument empty total=${SystemClock.elapsedRealtime() - startMs}ms uri=$uri"
        }
        return EbookDocument(fallbackTitle, "EPUB", listOf(EbookChapter(fallbackTitle, "")))
    }
    val container = entries["META-INF/container.xml"]?.toString(StandardCharsets.UTF_8)
    val opfPath = container?.let(::parseContainerRootFile)
        ?: entries.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
        ?: return fallbackHtmlEpub(entries, fallbackTitle, preferredCharsetName)
    val opfText = entries[opfPath]?.toString(StandardCharsets.UTF_8)
        ?: return fallbackHtmlEpub(entries, fallbackTitle, preferredCharsetName)
    val opf = parseOpf(opfText)
    val basePath = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
    val title = opf.title.ifBlank { fallbackTitle }
    val epubImages = buildEpubImageMap(entries, opf.manifest, basePath)
    val tocEntries = buildEpubTocEntries(entries, opf, basePath, preferredCharsetName)
    val chapters = mergeSpinePartsIntoChapters(
        opf.spineIds.mapIndexedNotNull { index, id ->
            val item = opf.manifest[id] ?: return@mapIndexedNotNull null
            val path = resolveEpubPath(basePath, item.href)
            val bytes = entries[path] ?: return@mapIndexedNotNull null
            val html = bytes.decodeTextFile(preferredCharsetName)
            buildEpubChaptersFromHtml(
                html = html,
                path = path,
                imageResources = epubImages,
                tocEntriesForFile = tocEntries.filter { it.path == path },
                fallbackTitle = fallbackEpubChapterTitle(html, isFirstSpineItem = index == 0),
                isFirstSpineItem = index == 0
            )
        }.flatten().ifEmpty {
            htmlEntries(entries).mapIndexed { index, (path, bytes) ->
                val html = bytes.decodeTextFile(preferredCharsetName)
                buildEpubChaptersFromHtml(
                    html = html,
                    path = path,
                    imageResources = epubImages,
                    tocEntriesForFile = emptyList(),
                    fallbackTitle = fallbackEpubChapterTitle(html, isFirstSpineItem = index == 0),
                    isFirstSpineItem = index == 0
                )
            }.flatten().filter { it.text.isNotBlank() || it.isVolume }
        }
    )
    logDebug(EBOOK_READER_CORE_LOG_TAG) {
        "loadEpubDocument parsed total=${SystemClock.elapsedRealtime() - startMs}ms " +
        "chapters=${chapters.size} images=${epubImages.size} title=$title"
    }
    return EbookDocument(
        title = title,
        format = "EPUB",
        chapters = chapters.ifEmpty { listOf(EbookChapter(title, "")) }
    )
}

private fun loadEpubDocumentFromCache(
    root: File,
    fallbackTitle: String,
    preferredCharsetName: String?
): EbookDocument {
    val startMs = SystemClock.elapsedRealtime()
    val container = root.resolveSafeEpubPath("META-INF/container.xml")
        ?.takeIf { it.isFile }
        ?.readText(StandardCharsets.UTF_8)
    val opfPath = container?.let(::parseContainerRootFile)
        ?: root.walkTopDown()
            .firstOrNull { it.isFile && it.name.endsWith(".opf", ignoreCase = true) }
            ?.relativeTo(root)
            ?.invariantSeparatorsPath
        ?: return fallbackHtmlEpubFromCache(root, fallbackTitle, preferredCharsetName)
    val opfText = root.resolveSafeEpubPath(opfPath)
        ?.takeIf { it.isFile }
        ?.readText(StandardCharsets.UTF_8)
        ?: return fallbackHtmlEpubFromCache(root, fallbackTitle, preferredCharsetName)
    val opf = parseOpf(opfText)
    val basePath = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
    val title = opf.title.ifBlank { fallbackTitle }
    val epubImages = buildEpubImageMapFromCache(root, opf.manifest, basePath)
    val tocEntries = buildEpubTocEntriesFromCache(root, opf, basePath, preferredCharsetName)
    val chapters = mergeSpinePartsIntoChapters(
        opf.spineIds.mapIndexedNotNull { index, id ->
            val item = opf.manifest[id] ?: return@mapIndexedNotNull null
            val path = resolveEpubPath(basePath, item.href)
            val file = root.resolveSafeEpubPath(path)?.takeIf { it.isFile } ?: return@mapIndexedNotNull null
            val html = file.readBytes().decodeTextFile(preferredCharsetName)
            buildEpubChaptersFromHtml(
                html = html,
                path = path,
                imageResources = epubImages,
                tocEntriesForFile = tocEntries.filter { it.path == path },
                fallbackTitle = fallbackEpubChapterTitle(html, isFirstSpineItem = index == 0),
                isFirstSpineItem = index == 0
            )
        }.flatten().ifEmpty {
            htmlFiles(root).mapIndexed { index, file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                val html = file.readBytes().decodeTextFile(preferredCharsetName)
                buildEpubChaptersFromHtml(
                    html = html,
                    path = path,
                    imageResources = epubImages,
                    tocEntriesForFile = emptyList(),
                    fallbackTitle = fallbackEpubChapterTitle(html, isFirstSpineItem = index == 0),
                    isFirstSpineItem = index == 0
                )
            }.flatten().filter { it.text.isNotBlank() || it.isVolume }
        }
    )
    logDebug(EBOOK_READER_CORE_LOG_TAG) {
        "loadEpubDocument cacheParsed total=${SystemClock.elapsedRealtime() - startMs}ms " +
        "chapters=${chapters.size} images=${epubImages.size} root=${root.name}"
    }
    return EbookDocument(
        title = title,
        format = "EPUB",
        chapters = chapters.ifEmpty { listOf(EbookChapter(title, "")) }
    )
}

private fun fallbackHtmlEpub(
    entries: Map<String, ByteArray>,
    fallbackTitle: String,
    preferredCharsetName: String?
): EbookDocument {
    val epubImages = buildEpubImageMap(entries, emptyMap(), "")
    val chapters = mergeSpinePartsIntoChapters(
        htmlEntries(entries).mapIndexed { index, (path, bytes) ->
            val html = bytes.decodeTextFile(preferredCharsetName)
            buildEpubChaptersFromHtml(
                html = html,
                path = path,
                imageResources = epubImages,
                tocEntriesForFile = emptyList(),
                fallbackTitle = fallbackEpubChapterTitle(html, isFirstSpineItem = index == 0),
                isFirstSpineItem = index == 0
            )
        }.flatten().filter { it.text.isNotBlank() }
    )
    return EbookDocument(fallbackTitle, "EPUB", chapters.ifEmpty { listOf(EbookChapter(fallbackTitle, "")) })
}

private fun fallbackHtmlEpubFromCache(
    root: File,
    fallbackTitle: String,
    preferredCharsetName: String?
): EbookDocument {
    val epubImages = buildEpubImageMapFromCache(root, emptyMap(), "")
    val chapters = mergeSpinePartsIntoChapters(
        htmlFiles(root).mapIndexed { index, file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            val html = file.readBytes().decodeTextFile(preferredCharsetName)
            buildEpubChaptersFromHtml(
                html = html,
                path = path,
                imageResources = epubImages,
                tocEntriesForFile = emptyList(),
                fallbackTitle = fallbackEpubChapterTitle(html, isFirstSpineItem = index == 0),
                isFirstSpineItem = index == 0
            )
        }.flatten().filter { it.text.isNotBlank() }
    )
    return EbookDocument(fallbackTitle, "EPUB", chapters.ifEmpty { listOf(EbookChapter(fallbackTitle, "")) })
}

private fun buildEpubChapterFromHtml(
    html: String,
    path: String,
    title: String,
    imageResources: Map<String, EpubImageResource>,
    titleFromMarkup: Boolean
): EbookChapter {
    val content = htmlToReaderContent(
        html = html,
        htmlBasePath = path.substringBeforeLast('/', missingDelimiterValue = ""),
        imageResources = imageResources,
        chapterTitle = title
    )
    return EbookChapter(
        title = title,
        text = content.text,
        sourcePath = path,
        images = content.images,
        rubySpans = content.rubySpans,
        isVolume = content.text.isBlank() && title.isVolumeTitle(),
        titleFromMarkup = titleFromMarkup
    )
}

/**
 * 把一个 spine 项的 xhtml 变成一章或多章。
 *
 * 目录里可能有多条指向**同一个文件**、用锚点区分小节的条目（例：無職転生 的
 * part0009.xhtml 里 #a5HS/#a5HT/#a5HU 分别是 第一話/第二話/第三話）。这种就按锚点在
 * html 里的位置把 html 切成多段、各自解析成章节（legado 的做法），标题取各自的目录条目。
 *
 * 关键：切分在 **html 层**做，不是先转成正文再按字符偏移切。这样每段正文里的图片位置与
 * 注音 span 偏移都是各自独立算出来的，不会出现整体平移错位（我们为此专门修过 bug）。
 *
 * 锚点之前的那一段没有目录条目 → 标题退化成 <title>（part0009 这种文件名）→ 会被
 * [mergeSpinePartsIntoChapters] 当成"无标题页"并进前一章，正好让上一节（如 プロローグ）
 * 的正文接上。
 */
private fun buildEpubChaptersFromHtml(
    html: String,
    path: String,
    imageResources: Map<String, EpubImageResource>,
    tocEntriesForFile: List<EpubTocEntry>,
    fallbackTitle: String,
    isFirstSpineItem: Boolean
): List<EbookChapter> {
    // 只有**带锚点**的条目才对应一段；不带锚点的条目（例：part0007 的「第一章 幼年期」）
    // 是整文件的标题，用来给"锚点之前那一段"命名。
    val anchoredEntries = tocEntriesForFile.filter { it.fragment.isNotBlank() }
    val anchors = anchoredEntries.map { it.fragment }
    val fileEntry = tocEntriesForFile.firstOrNull { it.fragment.isBlank() }
    val segments = splitHtmlAtAnchors(html, anchors)
    if (anchors.isEmpty() || segments.size != anchors.size + 1) {
        // 没有可用的锚点位置：整个文件一章，行为与以前一致
        val chapter = buildEpubChapterFromHtml(
            html = html,
            path = path,
            title = fileEntry?.title ?: tocEntriesForFile.firstOrNull()?.title ?: fallbackTitle,
            imageResources = imageResources,
            titleFromMarkup = tocEntriesForFile.isNotEmpty() || extractHtmlHeading(html) != null
        )
        val kept = chapter
            .markImageOrigin(isSectionTitlePage = tocEntriesForFile.isNotEmpty())
            .takeIf { it.text.isNotBlank() || it.isVolume }
        return listOfNotNull(kept)
    }

    val chapters = mutableListOf<EbookChapter>()
    val leadingHtml = segments.first()
    val leading = buildEpubChapterFromHtml(
        html = leadingHtml,
        path = path,
        title = fileEntry?.title
            ?: fallbackEpubChapterTitle(leadingHtml, isFirstSpineItem = isFirstSpineItem),
        imageResources = imageResources,
        titleFromMarkup = fileEntry != null || extractHtmlHeading(leadingHtml) != null
    )
    if (leading.text.isNotBlank() || leading.isVolume) {
        chapters += leading.markImageOrigin(isSectionTitlePage = fileEntry != null)
    }
    anchoredEntries.forEachIndexed { index, entry ->
        val segmentHtml = segments[index + 1]
        val chapter = buildEpubChapterFromHtml(
            html = segmentHtml,
            path = path,
            title = entry.title,
            imageResources = imageResources,
            titleFromMarkup = true
        )
        if (chapter.text.isNotBlank() || chapter.isVolume) {
            chapters += chapter.markImageOrigin(isSectionTitlePage = true)
        }
    }
    return chapters
}

/**
 * 按目录锚点把一份 xhtml 切成多段，返回 [锚点之前的正文, 锚点1 起, 锚点2 起, …]。
 * 找不到位置的锚点会被忽略（切出来的段数与可用锚点数不一致时，调用方退回"整文件一章"）。
 */
internal fun splitHtmlAtAnchors(html: String, anchors: List<String>): List<String> {
    val cuts = anchors.mapNotNull { anchor -> anchorElementStart(html, anchor) }
        .filter { it in 1 until html.length }
        .distinct()
        .sorted()
    if (cuts.isEmpty()) return listOf(html)
    val segments = mutableListOf<String>()
    var start = 0
    cuts.forEach { cut ->
        segments += html.substring(start, cut)
        start = cut
    }
    segments += html.substring(start)
    return segments
}

/**
 * 找到包含锚点 `id="X"` 的那个元素的起始位置，用来断开 html。
 * 从锚点往前找最近的开始标签，取"在锚点之后才闭合"的最外层那个（限制在**一段距离内**，
 * 避免一直回溯到 body/html），这样每段的标签是平衡的，也不会把上一段的样式包住。
 */
private fun anchorElementStart(html: String, anchor: String): Int? {
    val idIndex = listOf("id=\"$anchor\"", "id='$anchor'")
        .map { html.indexOf(it) }
        .filter { it >= 0 }
        .minOrNull() ?: return null
    val anchorTagEnd = html.indexOf('>', idIndex).takeIf { it >= 0 } ?: return null
    var cursor = anchorTagEnd
    var best: Int? = null
    while (true) {
        val tagStart = html.lastIndexOf('<', cursor - 1).takeIf { it >= 0 } ?: break
        if (anchorTagEnd - tagStart > ANCHOR_LOOKBACK_LIMIT) break
        val match = HTML_BLOCK_TAG_REGEX.find(html, tagStart)?.takeIf { it.range.first == tagStart }
        if (match != null) {
            val name = match.groupValues[1].lowercase()
            val openEnd = html.indexOf('>', tagStart).takeIf { it >= 0 } ?: break
            val openTag = html.substring(tagStart, openEnd + 1)
            val selfClosing = openTag.trimEnd().endsWith("/>")
            val closeIndex = html.indexOf("</$name", openEnd)
            if (selfClosing || (closeIndex > anchorTagEnd)) {
                best = tagStart
            }
        }
        cursor = tagStart
    }
    return best
}

private const val ANCHOR_LOOKBACK_LIMIT = 400
private val HTML_BLOCK_TAG_REGEX = Regex("(?i)<(div|section|article|p|h[1-6]|li|blockquote|td|tr|table)\\b")


private fun fallbackEpubChapterTitle(html: String, isFirstSpineItem: Boolean): String {
    val title = extractHtmlTitle(html)
    return if (title.isBlank() && isFirstSpineItem) "Cover" else title
}

/**
 * 把「不是真正章节」的 spine 页并入相邻章节，避免目录里出现 part0012 这种条目。
 *
 * 两类这样的页：
 * 1. **纯图片页**（很多 EPUB 把每一话的扉絵/卷首口絵做成独立 xhtml）：并入**后面第一个**
 *    正文章节的开头（扉絵该在的位置）；后面没有正文章节时（卷末插图）并入前一章末尾。
 * 2. **无标题页**（标题只是 calibre 的 `<title>` part0012 这类文件名，且没有目录条目、
 *    没有 h1-h3 标题）：这类是书名页/题词/正文续篇/作者简介等，并入**前一章**末尾；
 *    前面还没有正文章节时并入后一章开头。
 * 3. 既没有正文也没有图片的空页（封面/书名页）直接丢掉。
 *
 * 图片与注音 span 的位置是按章节正文的字符偏移记录的，拼接时必须整体平移，
 * 否则插图会画到错误的行上。
 *
 * 守卫：如果这本书的"可信标题"覆盖率过低（例如 nav 只标了头几章、后面全是 calibre
 * 拆分文件的正文），说明标题信息不可信，此时**不做任何合并**，否则会把后面的正文
 * 全部并进最后一章，目录直接缩水。
 */
internal fun mergeSpinePartsIntoChapters(chapters: List<EbookChapter>): List<EbookChapter> {
    if (chapters.isEmpty()) return chapters
    // 守卫要按**文件**算，不能按章节算：按目录锚点切章之后，每个文件都会多出若干
    // "锚点之前的续段"（它们本来就没有目录条目），按章节算会把覆盖率压到半数以下，
    // 于是整本书都不敢合并了。同一个 sourcePath 的段落归为一组。
    val textFileGroups = chapters
        .filterNot { it.hasNoReaderText() && it.images.isNotEmpty() }
        .groupBy { it.sourcePath ?: it.title }
    val titledFileCount = textFileGroups.count { (_, group) -> group.any { it.titleFromMarkup } }
    if (titledFileCount * 2 < textFileGroups.size) return chapters

    val result = mutableListOf<EbookChapter>()
    val pending = mutableListOf<EbookChapter>()
    chapters.forEach { chapter ->
        when {
            // 既没有正文也没有图片的空页（封面/书名页等）：丢掉
            chapter.hasNoReaderText() && chapter.images.isEmpty() && !chapter.isVolume -> Unit
            // 纯图片页：攒起来，并入后面第一个正文章节的开头
            chapter.hasNoReaderText() && chapter.images.isNotEmpty() -> pending += chapter
            // 无标题页（partXXXX）：并入前一章末尾
            chapter.hasGeneratedTitle() -> {
                val previous = result.lastOrNull()
                if (previous == null) {
                    pending += chapter
                } else {
                    result[result.lastIndex] =
                        mergeChapterParts(listOf(previous) + pending + chapter, previous)
                    pending.clear()
                }
            }
            pending.isEmpty() -> result += chapter
            else -> {
                // 待并入的纯图片页若带目录/标题元素标题，它就是这一章的开头（章节题图页）：
                // 身份取它，标题才会是目录里的章节名，而不是后面正文文件的 <title>
                // （很多书的正文文件 <title> 写的是书名，例如「とあるスイーツの店にて」这一章）。
                val identity = pending.firstOrNull { it.titleFromMarkup } ?: chapter
                result += mergeChapterParts(pending + chapter, identity)
                pending.clear()
            }
        }
    }
    if (pending.isNotEmpty()) {
        val previous = result.lastOrNull()
        if (previous == null) {
            result += pending.toList()
        } else {
            result[result.lastIndex] = mergeChapterParts(listOf(previous) + pending, previous)
        }
        pending.clear()
    }
    return result
}

/**
 * 是否是「文件名式标题」的页：标题只是 `<title>` 兜底（calibre 写成的 part0012 这种），
 * 不是来自目录条目或正文标题元素。这种页不是真正的章节。
 */
private fun EbookChapter.hasGeneratedTitle(): Boolean {
    if (titleFromMarkup) return false
    val trimmed = title.trim()
    // 连 <title> 都没有的页：同样不该单独成章
    if (trimmed.isEmpty()) return true
    if (GENERATED_CHAPTER_TITLE_PATTERN.matches(trimmed)) return true
    val fileStem = sourcePath?.substringAfterLast('/')?.substringBeforeLast('.')
    return fileStem != null && trimmed.equals(fileStem, ignoreCase = true)
}

/** calibre 等工具拆文件时生成的标题样式：part0004 / text0012 / chapter3 … */
private val GENERATED_CHAPTER_TITLE_PATTERN = Regex(
    pattern = "^(part|text|split|index|chapter|ch|sec|section|page|pg|p|c)[-_]?\\d+$",
    option = RegexOption.IGNORE_CASE
)

/** 判断章节正文是否只有图片占位符与空白（即这一页只有插图） */
internal fun EbookChapter.hasNoReaderText(): Boolean {
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (codePoint != EBOOK_IMAGE_MARKER.code && !Character.isWhitespace(codePoint)) return false
        index += Character.charCount(codePoint)
    }
    return true
}

/**
 * 给这一章的图打上来源标记（见 [EbookImageOrigin]）。
 * [isSectionTitlePage] = 该 spine 项被目录条目指向（这一页就是某个章节的开头，即题图页）。
 *
 * 判定只看"这一页有没有正文"，与图片长什么样无关：
 * 有正文 → 图都是章节内部的图；没有正文 → 整页是出版方为图排的一页。
 */
internal fun EbookChapter.markImageOrigin(isSectionTitlePage: Boolean): EbookChapter {
    if (images.isEmpty()) return this
    val origin = when {
        !hasNoReaderText() -> EbookImageOrigin.INLINE
        isSectionTitlePage -> EbookImageOrigin.SECTION_TITLE_PAGE
        else -> EbookImageOrigin.ILLUSTRATION_PAGE
    }
    if (images.values.all { it.origin == origin }) return this
    return copy(images = images.mapValues { (_, image) -> image.copy(origin = origin) })
}

/**
 * 按顺序拼接多个片段为一张章节卡片，章节身份（标题/来源路径/卷页标记）取 [identity]。
 * 图片与注音 span 的偏移量随拼接位置整体平移。
 */
private fun mergeChapterParts(parts: List<EbookChapter>, identity: EbookChapter): EbookChapter {
    val builder = StringBuilder()
    val images = linkedMapOf<Int, EbookImageRef>()
    val rubySpans = mutableListOf<EbookRubySpan>()
    parts.forEach { part ->
        if (builder.isNotEmpty() && part.text.isNotEmpty()) {
            builder.append("\n\n")
        }
        val offset = builder.length
        part.images.forEach { (position, image) -> images[position + offset] = image }
        part.rubySpans.forEach { span -> rubySpans += span.shiftedBy(offset) }
        builder.append(part.text)
    }
    return identity.copy(
        text = builder.toString(),
        images = images,
        rubySpans = rubySpans
    )
}

/**
 * 平移注音 span 的位置。
 *
 * 注意：只平移 span 自身的 [EbookRubySpan.start]/[EbookRubySpan.end]，
 * **segment 的 baseStart/baseEnd 不能动** —— 它们是相对 span.start 的偏移
 * （见 RubyPlacement.absoluteStart = span.start + segment.baseStart）。
 * 一起平移会让注音多偏一份，画到后面好几个字上。
 */
private fun EbookRubySpan.shiftedBy(offset: Int): EbookRubySpan {
    if (offset == 0) return this
    return copy(
        start = start + offset,
        end = end + offset
    )
}

private fun htmlEntries(entries: Map<String, ByteArray>): List<Pair<String, ByteArray>> {
    return entries
        .filterKeys { path ->
            path.endsWith(".xhtml", true) ||
                path.endsWith(".html", true) ||
                path.endsWith(".htm", true)
        }
        .toList()
        .sortedBy { it.first }
}

private fun htmlFiles(root: File): List<File> {
    val canonicalRoot = root.canonicalFile
    return root.walkTopDown()
        .filter { file ->
            file.isFile && (
                file.name.endsWith(".xhtml", true) ||
                    file.name.endsWith(".html", true) ||
                    file.name.endsWith(".htm", true)
                )
        }
        .filter { file ->
            val canonical = file.canonicalFile
            canonical.path == canonicalRoot.path || canonical.path.startsWith(canonicalRoot.path + File.separator)
        }
        .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
        .toList()
}

private fun String.isReaderEpubEntry(): Boolean {
    return equals("META-INF/container.xml", ignoreCase = true) ||
        endsWith(".opf", ignoreCase = true) ||
        endsWith(".xhtml", ignoreCase = true) ||
        endsWith(".html", ignoreCase = true) ||
        endsWith(".htm", ignoreCase = true) ||
        isEpubImagePath()
}

private fun parseContainerRootFile(xml: String): String? {
    val parser = Xml.newPullParser()
    parser.setInput(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)), "UTF-8")
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
            return parser.getAttributeValue(null, "full-path")?.normalizeZipPath()
        }
    }
    return null
}

private data class OpfItem(
    val href: String,
    val mediaType: String?,
    val properties: String? = null
)
private data class OpfData(
    val title: String,
    val manifest: Map<String, OpfItem>,
    val spineIds: List<String>
)

private fun parseOpf(xml: String): OpfData {
    val parser = Xml.newPullParser()
    parser.setInput(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)), "UTF-8")
    val manifest = linkedMapOf<String, OpfItem>()
    val spine = mutableListOf<String>()
    var title = ""
    var currentTag = ""
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                currentTag = parser.name.orEmpty()
                when (currentTag) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id").orEmpty()
                        val href = parser.getAttributeValue(null, "href").orEmpty()
                        if (id.isNotBlank() && href.isNotBlank()) {
                            manifest[id] = OpfItem(
                                href = href,
                                mediaType = parser.getAttributeValue(null, "media-type"),
                                properties = parser.getAttributeValue(null, "properties")
                            )
                        }
                    }
                    "itemref" -> {
                        val idRef = parser.getAttributeValue(null, "idref").orEmpty()
                        if (idRef.isNotBlank()) spine += idRef
                    }
                }
            }
            XmlPullParser.TEXT -> {
                if (currentTag.endsWith("title") && title.isBlank()) {
                    title = parser.text.orEmpty().trim()
                }
            }
            XmlPullParser.END_TAG -> currentTag = ""
        }
    }
    val readableSpine = spine.filter { id ->
        val type = manifest[id]?.mediaType.orEmpty()
        type.contains("html", ignoreCase = true) || type.contains("xhtml", ignoreCase = true) || type.isBlank()
    }
    return OpfData(title = title, manifest = manifest, spineIds = readableSpine.ifEmpty { spine })
}

private fun buildEpubTocEntries(
    entries: Map<String, ByteArray>,
    opf: OpfData,
    opfBasePath: String,
    preferredCharsetName: String?
): List<EpubTocEntry> {
    val navItem = opf.manifest.values.firstOrNull { item ->
        item.properties
            ?.split(Regex("\\s+"))
            ?.any { it.equals("nav", ignoreCase = true) } == true
    }
    val navEntries = navItem
        ?.let { item -> resolveEpubPath(opfBasePath, item.href) }
        ?.let { path ->
            entries[path]
                ?.decodeTextFile(preferredCharsetName)
                ?.let { html -> parseNavHtmlToc(html, path.substringBeforeLast('/', missingDelimiterValue = "")) }
        }
        .orEmpty()
    val ncxItem = opf.manifest.values.firstOrNull { item ->
        val mediaType = item.mediaType.orEmpty()
        mediaType.contains("dtbncx", ignoreCase = true) ||
            item.href.endsWith(".ncx", ignoreCase = true)
    }
    val ncxEntries = ncxItem
        ?.let { item -> resolveEpubPath(opfBasePath, item.href) }
        ?.let { path ->
            entries[path]
                ?.decodeTextFile(preferredCharsetName)
                ?.let { xml -> parseNcxToc(xml, path.substringBeforeLast('/', missingDelimiterValue = "")) }
        }
        .orEmpty()
    return ncxEntries + navEntries
}

private fun buildEpubTocEntriesFromCache(
    root: File,
    opf: OpfData,
    opfBasePath: String,
    preferredCharsetName: String?
): List<EpubTocEntry> {
    fun readText(path: String): String? =
        root.resolveSafeEpubPath(path)
            ?.takeIf { it.isFile }
            ?.readBytes()
            ?.decodeTextFile(preferredCharsetName)

    val navEntries = opf.manifest.values.firstOrNull { item ->
        item.properties
            ?.split(Regex("\\s+"))
            ?.any { it.equals("nav", ignoreCase = true) } == true
    }
        ?.let { item -> resolveEpubPath(opfBasePath, item.href) }
        ?.let { path -> readText(path)?.let { html -> parseNavHtmlToc(html, path.substringBeforeLast('/', missingDelimiterValue = "")) } }
        .orEmpty()

    val ncxEntries = opf.manifest.values.firstOrNull { item ->
        val mediaType = item.mediaType.orEmpty()
        mediaType.contains("dtbncx", ignoreCase = true) ||
            item.href.endsWith(".ncx", ignoreCase = true)
    }
        ?.let { item -> resolveEpubPath(opfBasePath, item.href) }
        ?.let { path -> readText(path)?.let { xml -> parseNcxToc(xml, path.substringBeforeLast('/', missingDelimiterValue = "")) } }
        .orEmpty()
    return ncxEntries + navEntries
}

private fun parseNcxToc(xml: String, opfBasePath: String): List<EpubTocEntry> {
    val parser = Xml.newPullParser()
    parser.setInput(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)), "UTF-8")
    val entries = mutableListOf<EpubTocEntry>()
    var inNavPoint = false
    var inNavLabel = false
    var inText = false
    var currentTitle = ""
    var currentSrc = ""
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "navPoint" -> {
                    inNavPoint = true
                    currentTitle = ""
                    currentSrc = ""
                }
                "navLabel" -> if (inNavPoint) inNavLabel = true
                "text" -> if (inNavLabel) inText = true
                "content" -> if (inNavPoint) {
                    currentSrc = parser.getAttributeValue(null, "src").orEmpty()
                }
            }
            XmlPullParser.TEXT -> if (inText) {
                currentTitle += parser.text.orEmpty()
            }
            XmlPullParser.END_TAG -> when (parser.name) {
                "text" -> inText = false
                "navLabel" -> inNavLabel = false
                "navPoint" -> {
                    val title = currentTitle.cleanTocTitle()
                    if (title.isNotBlank() && currentSrc.isNotBlank()) {
                        tocEntryOf(opfBasePath, currentSrc, title)?.let { entries += it }
                    }
                    inNavPoint = false
                    inNavLabel = false
                    inText = false
                }
            }
        }
    }
    return entries
}

private fun parseNavHtmlToc(html: String, opfBasePath: String): List<EpubTocEntry> {
    val navBlock = Regex("""(?is)<nav\b(?=[^>]*(?:epub:type|type)\s*=\s*['"]?toc\b)[^>]*>(.*?)</nav>""")
        .find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?: html
    return Regex("""(?is)<a\b[^>]*href\s*=\s*(['"])(.*?)\1[^>]*>(.*?)</a>""")
        .findAll(navBlock)
        .mapNotNull { match ->
            val href = Html.fromHtml(match.groupValues[2], Html.FROM_HTML_MODE_LEGACY)
                .toString()
                .trim()
            val title = Html.fromHtml(match.groupValues[3], Html.FROM_HTML_MODE_LEGACY)
                .toString()
                .cleanTocTitle()
            if (href.isBlank() || title.isBlank()) null else tocEntryOf(opfBasePath, href, title)
        }
        .toList()
}

/** 把目录里的 src（可能带 #锚点）解析成"文件路径 + 锚点" */
private fun tocEntryOf(opfBasePath: String, src: String, title: String): EpubTocEntry? {
    val resolved = resolveEpubPath(opfBasePath, src)
    if (resolved.isBlank()) return null
    val path = resolved.substringBefore('#')
    val fragment = resolved.substringAfter('#', "")
        .trim()
        .let { fragment ->
            runCatching { java.net.URLDecoder.decode(fragment, "UTF-8") }.getOrDefault(fragment)
        }
        .takeIf { it.isNotBlank() }
        .orEmpty()
    return EpubTocEntry(title = title, path = path, fragment = fragment)
}

private fun String.cleanTocTitle(): String {
    return replace(Regex("\\s+"), " ").trim()
}

private fun splitTxtChapters(text: String): List<EbookChapter> {
    val normalized = text.normalizeReaderWhitespace()
    if (normalized.isBlank()) return emptyList()
    val chapterRegex = Regex(
        pattern = """(?m)^\s*((第[0-9０-９一二三四五六七八九十百千万〇零两]{1,8}[章节章回].{0,32})|(Chapter\s+\d+.{0,32}))\s*$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )
    val matches = chapterRegex.findAll(normalized).toList()
    if (matches.size < 2) {
        return listOf(EbookChapter("Body", normalized))
    }
    val chapters = mutableListOf<EbookChapter>()
    matches.forEachIndexed { index, match ->
        val start = match.range.first
        val end = matches.getOrNull(index + 1)?.range?.first ?: normalized.length
        val body = normalized.substring(start, end).trim()
        if (body.isNotBlank()) {
            chapters += EbookChapter(match.value.trim(), body)
        }
    }
    return chapters
}

private fun parseEbookSrtText(raw: String): List<EbookSrtCue> {
    val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
    val blocks = normalized.split(Regex("\n{2,}"))
    val cues = mutableListOf<EbookSrtCue>()
    blocks.forEach { block ->
        val lines = block.lines().map { it.trim() }.filter { it.isNotBlank() }
        val timeIndex = lines.indexOfFirst { it.contains("-->") }
        if (timeIndex < 0) return@forEach
        val parts = lines[timeIndex].split("-->")
        if (parts.size < 2) return@forEach
        val start = parseSrtTimestamp(parts[0].trim()) ?: return@forEach
        val end = parseSrtTimestamp(parts[1].trim().substringBefore(' ')) ?: return@forEach
        val text = lines.drop(timeIndex + 1)
            .joinToString("\n")
            .let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
            .trim()
        if (text.isNotBlank()) cues += EbookSrtCue(start, end, text)
    }
    return cues.sortedBy { it.startMs }
}

private fun parseSrtTimestamp(raw: String): Long? {
    val normalized = raw.replace(',', '.')
    val parts = normalized.split(':')
    if (parts.size != 3) return null
    val secondsParts = parts[2].split('.')
    val hours = parts[0].toLongOrNull() ?: return null
    val minutes = parts[1].toLongOrNull() ?: return null
    val seconds = secondsParts.getOrNull(0)?.toLongOrNull() ?: return null
    val millis = secondsParts.getOrNull(1)
        ?.padEnd(3, '0')
        ?.take(3)
        ?.toLongOrNull()
        ?: 0L
    return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
}

private data class FilteredTextMap(
    val filtered: String,
    val rawIndices: List<Int>
)

private fun buildFilteredTextMap(raw: String): FilteredTextMap {
    val filtered = StringBuilder()
    val rawIndices = mutableListOf<Int>()
    var offset = 0
    while (offset < raw.length) {
        val codePoint = raw.codePointAt(offset)
        if (codePoint.isReaderChar()) {
            filtered.appendCodePoint(codePoint)
            rawIndices += offset
        }
        offset += Character.charCount(codePoint)
    }
    return FilteredTextMap(filtered.toString(), rawIndices)
}

private fun String.filteredReaderMatchText(): String {
    val builder = StringBuilder()
    var offset = 0
    while (offset < length) {
        val codePoint = codePointAt(offset)
        if (codePoint.isReaderChar()) {
            builder.appendCodePoint(codePoint)
        }
        offset += Character.charCount(codePoint)
    }
    return builder.toString()
}

internal fun Int.isReaderChar(): Boolean =
    when (this) {
        in '0'.code..'9'.code,
        in 'A'.code..'Z'.code,
        in 'a'.code..'z'.code,
        '○'.code,
        '◯'.code,
        in '々'.code..'〇'.code,
        '〻'.code,
        in 'ぁ'.code..'ゖ'.code,
        in 'ゝ'.code..'ゞ'.code,
        in 'ァ'.code..'ヺ'.code,
        'ー'.code,
        in '０'.code..'９'.code,
        in 'Ａ'.code..'Ｚ'.code,
        in 'ａ'.code..'ｚ'.code,
        in 'ｦ'.code..'ﾝ'.code,
        in 0x2E80..0x2FDF,
        in 0x3400..0x4DBF,
        in 0x4E00..0x9FFF,
        in 0x20000..0x2A6DF,
        in 0x2A700..0x2B73F,
        in 0x2B740..0x2B81F,
        in 0x2B820..0x2CEAF,
        in 0x2CEB0..0x2EBEF,
        in 0x30000..0x3134F,
        in 0x31350..0x323AF -> true
        else -> false
    }

internal const val EBOOK_IMAGE_MARKER: Char = '\uFFFC'

/** 句首可能出现的引号/括号：扩句时"句子开头"要连它们一起算 */
private const val SENTENCE_LEADING_MARKS = "「『（(［[｛{〈《【〔“‘\"'"

/** 句尾可能出现的标点：扩句时"句子结尾"要连它们一起算 */
private const val SENTENCE_TRAILING_MARKS = "」』）)］]｝}〉》】〕。、，．：；！？…‥—―～〜”’\"'"

/**
 * 把 SRT 匹配到的范围扩成"带引号的整句"。
 *
 * 匹配是拿「可读字符」去比对的（[isReaderChar] 不含 CJK 标点），所以
 * 「戦闘用しかないのは、…理由もあります」这种 cue 匹配到的范围是从 `戦` 开始、
 * 到 `す` 结束，句首的 `「` 与句尾的 `」` 都不在范围内。
 * 直接拿它当"句子边界"，「句子不跨页」就会把开引号单独留在上一页 —— 所以这里
 * 按正文把两端扩到引号/括号之外。
 */
internal fun expandCueRangeToSentence(text: String, start: Int, end: Int): Pair<Int, Int> {
    if (text.isEmpty()) return start to end
    var from = start.coerceIn(0, text.length)
    var to = end.coerceIn(from, text.length)
    while (from > 0 && text[from - 1] in SENTENCE_LEADING_MARKS) from -= 1
    while (to < text.length && text[to] in SENTENCE_TRAILING_MARKS) to += 1
    return from to to
}

private data class HtmlImageTag(
    val src: String,
    val altText: String
)

private data class ReaderHtmlContent(
    val text: String,
    val images: Map<Int, EbookImageRef>,
    val rubySpans: List<EbookRubySpan> = emptyList()
)

private data class EpubImageResource(
    val mediaType: String?,
    val bytes: ByteArray? = null,
    val filePath: String? = null
)

private data class ParsedReaderText(
    val text: String,
    val rubySpans: List<EbookRubySpan>
)

private data class NormalizedTextMap(
    val text: String,
    val rawToNormalized: IntArray
)

private fun buildEpubImageMap(
    entries: Map<String, ByteArray>,
    manifest: Map<String, OpfItem>,
    opfBasePath: String
): Map<String, EpubImageResource> {
    val images = linkedMapOf<String, EpubImageResource>()
    manifest.values.forEach { item ->
        val mediaType = item.mediaType.orEmpty()
        if (mediaType.startsWith("image/", ignoreCase = true)) {
            val path = resolveEpubPath(opfBasePath, item.href)
            entries[path]?.let { bytes ->
                images[path] = EpubImageResource(mediaType = item.mediaType, bytes = bytes)
            }
        }
    }
    entries.forEach { (path, bytes) ->
        if (path.isEpubImagePath()) {
            images.putIfAbsent(
                path,
                EpubImageResource(mediaType = path.mediaTypeFromExtension(), bytes = bytes)
            )
        }
    }
    return images
}

private fun buildEpubImageMapFromCache(
    root: File,
    manifest: Map<String, OpfItem>,
    opfBasePath: String
): Map<String, EpubImageResource> {
    val images = linkedMapOf<String, EpubImageResource>()
    manifest.values.forEach { item ->
        val mediaType = item.mediaType.orEmpty()
        val path = resolveEpubPath(opfBasePath, item.href)
        if (mediaType.startsWith("image/", ignoreCase = true) || path.isEpubImagePath()) {
            val file = root.resolveSafeEpubPath(path) ?: return@forEach
            if (file.isFile) {
                images[path] = EpubImageResource(
                    mediaType = item.mediaType ?: path.mediaTypeFromExtension(),
                    filePath = file.absolutePath
                )
            }
        }
    }
    root.walkTopDown()
        .filter { it.isFile && it.name.isEpubImagePath() }
        .forEach { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            images.putIfAbsent(
                path,
                EpubImageResource(
                    mediaType = path.mediaTypeFromExtension(),
                    filePath = file.absolutePath
                )
            )
        }
    return images
}

private fun htmlToReaderContent(
    html: String,
    htmlBasePath: String,
    imageResources: Map<String, EpubImageResource>,
    chapterTitle: String
): ReaderHtmlContent {
    // 按目录锚点切开的分段可能没有 </body>（正文被切在中间），所以闭合标签是可选的：
    // 否则会回退成整段 html，把 <head><title> 的文字（很多书里是书名）漏进正文。
    var body = Regex("(?is)<body[^>]*>(.*?)(?:</body>|$)").find(html)?.groupValues?.getOrNull(1) ?: html
    body = Regex("(?is)<(script|style)[^>]*>.*?</\\1>").replace(body, "")
    body = Regex("(?is)<head\\b[^>]*>.*?</head>").replace(body, "")
    // 章级标题元素（h1/h2）从正文剥离：章节标题只由阅读器头部
    // （PageView.bodyTitleView）显示一次，避免与章节标题重复显示两次。
    // 参考实现 legado 会剥离全部 h1-h6；这里只剥 h1/h2，保留 h3+ 小节标题。
    // 必须在图片/ruby 替换之前执行，保证被剥掉的元素不会产生悬空标记。
    val headingStripped = Regex("(?is)<h[12]\\b(?!/)[^>]*>.*?</h[12]>").replace(body, "")
    // 只有标题没有正文的页面（剥掉 h1/h2 后为空）：
    // - 卷页（"第一部分 xxx"等）：正文留空，标题只由阅读器头部显示一次；
    // - 其他页面：保留原标题文本，避免整章被上层按空章节丢弃。
    body = if (headingStripped.replace(Regex("<[^>]*>"), "").isBlank()) {
        if (chapterTitle.isVolumeTitle()) "" else body
    } else {
        headingStripped
    }
    val rubyTexts = linkedMapOf<Int, ParsedRubyHtml>()
    var rubyId = 0
    body = Regex("(?is)<ruby\\b[^>]*>.*?</ruby>").replace(body) { match ->
        val rubyHtml = match.value
        val ruby = parseRubyHtml(rubyHtml, htmlBasePath)
        if (ruby.baseText.isBlank() || ruby.annotation.isBlank()) {
            ruby.baseText.escapeHtmlText()
        } else {
            val id = rubyId++
            rubyTexts[id] = ruby
            "$RUBY_START_MARKER$id$RUBY_MARKER_TERMINATOR${ruby.baseText.escapeHtmlText()}$RUBY_END_MARKER$id$RUBY_MARKER_TERMINATOR"
        }
    }
    val imageTags = mutableListOf<HtmlImageTag>()
    body = Regex("(?is)<svg\\b[^>]*>.*?</svg>|<img\\b[^>]*>|<image\\b[^>]*>").replace(body) { match ->
        val tag = match.value
        val src = tag.htmlImageSource()
        if (src.isBlank()) {
            ""
        } else {
            imageTags += HtmlImageTag(
                src = src,
                altText = tag.htmlAttribute("alt").ifBlank { tag.htmlAttribute("title") }
            )
            "<br/>$EBOOK_IMAGE_MARKER<br/>"
        }
    }
    body = body
        .replace(Regex("(?is)<rt[^>]*>.*?</rt>"), "")
        .replace(Regex("(?is)<rp[^>]*>.*?</rp>"), "")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p\\s*>"), "\n\n")
        .replace(Regex("(?i)</h[1-6]\\s*>"), "\n\n")
        .replace(Regex("(?i)</div\\s*>"), "\n")
    val parsedText = Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY)
        .toString()
        .parseRubyMarkers(rubyTexts)
        .normalizeReaderWhitespace()
    val text = parsedText.text
    val images = linkedMapOf<Int, EbookImageRef>()
    var searchStart = 0
    imageTags.forEach { tag ->
        val markerPosition = text.indexOf(EBOOK_IMAGE_MARKER, searchStart)
        if (markerPosition < 0) return@forEach
        searchStart = markerPosition + 1
        val imagePath = resolveEpubPath(htmlBasePath, tag.src)
        val resource = imageResources[imagePath] ?: return@forEach
        images[markerPosition] = EbookImageRef(
            path = imagePath,
            altText = tag.altText,
            mediaType = resource.mediaType,
            bytes = resource.bytes,
            filePath = resource.filePath
        )
    }
    return ReaderHtmlContent(text = text, images = images, rubySpans = parsedText.rubySpans)
}

private const val RUBY_START_MARKER: Char = '\uE100'
private const val RUBY_END_MARKER: Char = '\uE101'
private const val RUBY_MARKER_TERMINATOR: Char = '\uE102'

private data class ParsedRubyHtml(
    val baseText: String,
    val annotation: String,
    val kind: EbookRubyKind,
    val segments: List<EbookRubySegment> = emptyList()
)

private fun parseRubyHtml(rubyHtml: String, sourcePath: String): ParsedRubyHtml {
    val rbTexts = Regex("(?is)<rb\\b[^>]*>(.*?)</rb>")
        .findAll(rubyHtml)
        .map { it.groupValues[1].htmlText() }
        .filter { it.isNotBlank() }
        .toList()
    val rtTexts = Regex("(?is)<rt\\b[^>]*>(.*?)</rt>")
        .findAll(rubyHtml)
        .map { it.groupValues[1].htmlText() }
        .filter { it.isNotBlank() }
        .toList()
    if (rbTexts.isNotEmpty() && rtTexts.isNotEmpty()) {
        if (rbTexts.size == rtTexts.size) {
            val segments = mutableListOf<EbookRubySegment>()
            val baseBuilder = StringBuilder()
            val annotationBuilder = StringBuilder()
            rbTexts.zip(rtTexts).forEach { (base, annotation) ->
                val start = baseBuilder.length
                baseBuilder.append(base)
                val end = baseBuilder.length
                annotationBuilder.append(annotation)
                segments += EbookRubySegment(start, end, annotation)
            }
            val base = baseBuilder.toString()
            val annotation = annotationBuilder.toString()
            return ParsedRubyHtml(
                baseText = base,
                annotation = annotation,
                kind = rubyKindFor(base, segments),
                segments = segments
            )
        }
        logRubyWarning(
            sourcePath = sourcePath,
            reason = "rb/rt count mismatch rb=${rbTexts.size} rt=${rtTexts.size}",
            rubyHtml = rubyHtml
        )
    }
    val baseText = rubyBaseText(rubyHtml)
    val annotation = rubyAnnotationText(rubyHtml)
    if (baseText.isBlank()) {
        logRubyWarning(sourcePath, "empty base", rubyHtml)
    }
    if (annotation.isBlank()) {
        logRubyWarning(sourcePath, "empty annotation", rubyHtml)
    }
    return ParsedRubyHtml(
        baseText = baseText,
        annotation = annotation,
        kind = if (Character.codePointCount(baseText, 0, baseText.length) == 1) EbookRubyKind.MONO else EbookRubyKind.GROUP
    )
}

private fun rubyKindFor(baseText: String, segments: List<EbookRubySegment>): EbookRubyKind {
    if (segments.size == 1 && Character.codePointCount(baseText, 0, baseText.length) == 1) return EbookRubyKind.MONO
    if (segments.size > 1 && segments.all { segment ->
            Character.codePointCount(baseText, segment.baseStart, segment.baseEnd) == 1
        }
    ) {
        return EbookRubyKind.JUKUGO
    }
    return EbookRubyKind.GROUP
}

private fun rubyBaseText(rubyHtml: String): String {
    val baseHtml = rubyHtml
        .replace(Regex("(?is)<rt\\b[^>]*>.*?</rt>"), "")
        .replace(Regex("(?is)<rp\\b[^>]*>.*?</rp>"), "")
        .replace(Regex("(?is)</?ruby\\b[^>]*>"), "")
        .replace(Regex("(?is)</?rb\\b[^>]*>"), "")
    return baseHtml.htmlText().trim()
}

private fun rubyAnnotationText(rubyHtml: String): String {
    return Regex("(?is)<rt\\b[^>]*>(.*?)</rt>")
        .findAll(rubyHtml)
        .joinToString("") { match ->
            match.groupValues[1].htmlText().trim()
        }
        .trim()
}

private fun String.htmlText(): String =
    Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY).toString()

private fun logRubyWarning(sourcePath: String, reason: String, rubyHtml: String) {
    val snippet = rubyHtml
        .replace(Regex("\\s+"), " ")
        .take(120)
    Log.w(EBOOK_READER_CORE_LOG_TAG, "ruby parse warning source=$sourcePath reason=$reason html=$snippet")
}

private fun String.escapeHtmlText(): String {
    return replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

private fun String.parseRubyMarkers(rubyTexts: Map<Int, ParsedRubyHtml>): ParsedReaderText {
    if (rubyTexts.isEmpty()) return ParsedReaderText(this, emptyList())
    val out = StringBuilder(length)
    val activeStarts = linkedMapOf<Int, Int>()
    val spans = mutableListOf<EbookRubySpan>()
    var index = 0
    while (index < length) {
        when (this[index]) {
            RUBY_START_MARKER -> {
                val marker = readRubyMarkerId(index)
                if (marker != null) {
                    activeStarts[marker.first] = out.length
                    index = marker.second
                } else {
                    out.append(this[index])
                    index += 1
                }
            }
            RUBY_END_MARKER -> {
                val marker = readRubyMarkerId(index)
                if (marker != null) {
                    val start = activeStarts.remove(marker.first)
                    val ruby = rubyTexts[marker.first]
                    if (start != null && ruby != null && out.length > start) {
                        spans += EbookRubySpan(
                            start = start,
                            end = out.length,
                            text = ruby.annotation,
                            kind = ruby.kind,
                            segments = ruby.segments
                        )
                    }
                    index = marker.second
                } else {
                    out.append(this[index])
                    index += 1
                }
            }
            else -> {
                out.append(this[index])
                index += 1
            }
        }
    }
    return ParsedReaderText(out.toString(), spans)
}

private fun String.readRubyMarkerId(markerIndex: Int): Pair<Int, Int>? {
    val end = indexOf(RUBY_MARKER_TERMINATOR, startIndex = markerIndex + 1)
    if (end < 0) return null
    val id = substring(markerIndex + 1, end).toIntOrNull() ?: return null
    return id to (end + 1)
}

private fun ParsedReaderText.normalizeReaderWhitespace(): ParsedReaderText {
    val normalized = text.normalizeReaderWhitespaceWithMap()
    val spans = rubySpans.mapNotNull { span ->
        val start = normalized.firstMappedAtOrAfter(span.start)
        val end = normalized.lastMappedBefore(span.end)?.plus(1)
        if (start != null && end != null && end > start) {
            span.copy(start = start, end = end)
        } else {
            null
        }
    }
    return ParsedReaderText(normalized.text, spans)
}

private fun String.normalizeReaderWhitespaceWithMap(): NormalizedTextMap {
    val lineBreaksNormalized = mutableListOf<IndexedChar>()
    var index = 0
    while (index < length) {
        val char = this[index]
        when (char) {
            '\r' -> {
                lineBreaksNormalized += IndexedChar('\n', index)
                if (getOrNull(index + 1) == '\n') index += 1
            }
            '\u00A0' -> lineBreaksNormalized += IndexedChar(' ', index)
            else -> lineBreaksNormalized += IndexedChar(char, index)
        }
        index += 1
    }

    val lineTrimmed = mutableListOf<IndexedChar>()
    var lineStart = 0
    while (lineStart < lineBreaksNormalized.size) {
        var lineEnd = lineStart
        while (lineEnd < lineBreaksNormalized.size && lineBreaksNormalized[lineEnd].char != '\n') {
            lineEnd += 1
        }
        var trimmedEnd = lineEnd
        while (trimmedEnd > lineStart && lineBreaksNormalized[trimmedEnd - 1].char.isWhitespace()) {
            trimmedEnd -= 1
        }
        for (i in lineStart until trimmedEnd) {
            lineTrimmed += lineBreaksNormalized[i]
        }
        if (lineEnd < lineBreaksNormalized.size && lineBreaksNormalized[lineEnd].char == '\n') {
            lineTrimmed += lineBreaksNormalized[lineEnd]
        }
        lineStart = lineEnd + 1
    }

    val collapsed = mutableListOf<IndexedChar>()
    var collapsedIndex = 0
    while (collapsedIndex < lineTrimmed.size) {
        val char = lineTrimmed[collapsedIndex]
        if (char.char != '\n') {
            collapsed += char
            collapsedIndex += 1
            continue
        }
        var runEnd = collapsedIndex
        while (runEnd < lineTrimmed.size && lineTrimmed[runEnd].char == '\n') {
            runEnd += 1
        }
        val keep = minOf(2, runEnd - collapsedIndex)
        for (i in 0 until keep) {
            collapsed += lineTrimmed[collapsedIndex + i]
        }
        collapsedIndex = runEnd
    }

    var trimStart = 0
    var trimEnd = collapsed.size
    while (trimStart < trimEnd && collapsed[trimStart].char.isWhitespace()) trimStart += 1
    while (trimEnd > trimStart && collapsed[trimEnd - 1].char.isWhitespace()) trimEnd -= 1
    val finalChars = collapsed.subList(trimStart, trimEnd)
    val rawToNormalized = IntArray(length + 1) { -1 }
    val builder = StringBuilder(finalChars.size)
    finalChars.forEachIndexed { normalizedIndex, indexedChar ->
        builder.append(indexedChar.char)
        if (indexedChar.rawIndex in rawToNormalized.indices && rawToNormalized[indexedChar.rawIndex] < 0) {
            rawToNormalized[indexedChar.rawIndex] = normalizedIndex
        }
    }
    rawToNormalized[length] = builder.length
    return NormalizedTextMap(builder.toString(), rawToNormalized)
}

private data class IndexedChar(
    val char: Char,
    val rawIndex: Int
)

private fun NormalizedTextMap.firstMappedAtOrAfter(rawIndex: Int): Int? {
    var index = rawIndex.coerceIn(0, rawToNormalized.lastIndex)
    while (index < rawToNormalized.size) {
        val mapped = rawToNormalized[index]
        if (mapped >= 0) return mapped
        index += 1
    }
    return null
}

private fun NormalizedTextMap.lastMappedBefore(rawEnd: Int): Int? {
    var index = (rawEnd - 1).coerceIn(0, rawToNormalized.lastIndex)
    while (index >= 0) {
        val mapped = rawToNormalized[index]
        if (mapped >= 0) return mapped
        index -= 1
    }
    return null
}

private fun extractHtmlHeading(html: String): String? {
    return Regex("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>").find(html)?.groupValues?.getOrNull(1)
        ?.replace(Regex("<[^>]+>"), "")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

private fun extractHtmlTitle(html: String): String {
    val title = extractHtmlHeading(html)
        ?: Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.getOrNull(1)
    return title
        ?.replace(Regex("<[^>]+>"), "")
        ?.trim()
        .orEmpty()
}

private fun ByteArray.decodeTextFile(preferredCharsetName: String? = null): String {
    preferredCharsetName
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { charsetName ->
            runCatching { return toString(Charset.forName(charsetName)) }
        }
    if (size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()) {
        return copyOfRange(3, size).toString(StandardCharsets.UTF_8)
    }
    if (size >= 2 && this[0] == 0xFF.toByte() && this[1] == 0xFE.toByte()) {
        return copyOfRange(2, size).toString(Charset.forName("UTF-16LE"))
    }
    if (size >= 2 && this[0] == 0xFE.toByte() && this[1] == 0xFF.toByte()) {
        return copyOfRange(2, size).toString(Charset.forName("UTF-16BE"))
    }
    val utf8 = toString(StandardCharsets.UTF_8)
    if (utf8.count { it == '\uFFFD' } <= max(2, utf8.length / 100)) return utf8
    return runCatching { toString(Charset.forName("Shift_JIS")) }.getOrElse { utf8 }
}

private fun String.normalizeReaderWhitespace(): String {
    return replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('\u00A0', ' ')
        .lines()
        .joinToString("\n") { line -> line.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

private fun String.normalizeZipPath(): String {
    return trim()
        .replace('\\', '/')
        .removePrefix("/")
}

private fun resolveEpubPath(basePath: String, href: String): String {
    val decoded = runCatching { URLDecoder.decode(href.substringBefore('#'), "UTF-8") }
        .getOrElse { href.substringBefore('#') }
    val combined = if (basePath.isBlank()) decoded else "$basePath/$decoded"
    val out = ArrayDeque<String>()
    combined.normalizeZipPath().split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeLast()
            else -> out.addLast(part)
        }
    }
    return out.joinToString("/")
}

private fun File.resolveSafeEpubPath(path: String): File? {
    val root = canonicalFile
    val target = resolve(path.normalizeZipPath()).canonicalFile
    return if (target.path == root.path || target.path.startsWith(root.path + File.separator)) {
        target
    } else {
        null
    }
}

private fun String.htmlAttribute(name: String): String {
    val pattern = Regex("""(?is)\b${Regex.escape(name)}\s*=\s*(['"])(.*?)\1""")
    return pattern.find(this)?.groupValues?.getOrNull(2)
        ?.let { value ->
            runCatching { URLDecoder.decode(value.substringBefore('#'), "UTF-8") }
                .getOrElse { value.substringBefore('#') }
        }
        .orEmpty()
}

private fun String.htmlImageSource(): String {
    return htmlAttribute("src")
        .ifBlank { htmlAttribute("xlink:href") }
        .ifBlank { htmlAttribute("href") }
}

private fun String.isEpubImagePath(): Boolean {
    val lower = lowercase(Locale.US).substringBefore('?').substringBefore('#')
    return lower.endsWith(".png") ||
        lower.endsWith(".jpg") ||
        lower.endsWith(".jpeg") ||
        lower.endsWith(".webp") ||
        lower.endsWith(".gif")
}

private fun String.mediaTypeFromExtension(): String? {
    return when (lowercase(Locale.US).substringBefore('?').substringBefore('#').substringAfterLast('.')) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> null
    }
}

private fun String.filteredReaderCodePoints(): List<Int> =
    filteredReaderMatchText().codePoints().toArray().toList()

private fun findCodePointText(source: List<Int>, text: List<Int>, start: Int, end: Int): Int? {
    if (text.isEmpty()) return null
    var index = start.coerceAtLeast(0)
    val last = end.coerceAtMost(source.size) - text.size
    while (index <= last) {
        var matched = true
        for (i in text.indices) {
            if (source[index + i] != text[i]) {
                matched = false
                break
            }
        }
        if (matched) return index
        index += 1
    }
    return null
}
