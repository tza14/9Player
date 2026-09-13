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
import org.xml.sax.InputSource
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
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
    val fragment: String = "",
    /** 目录里的层级，0 = 顶层。只用于目录缩进。 */
    val level: Int = 0,
    /**
     * 这条条目下面还有子条目 —— 也就是"大章节"。照 legado 的做法：navPoint 有 children
     * 就把父条目当卷处理，目录里灰底显示、下面的子条目缩进。
     */
    val isGroup: Boolean = false
)

internal data class EbookChapter(
    val title: String,
    val text: String,
    val sourcePath: String? = null,
    val images: Map<Int, EbookImageRef> = emptyMap(),
    val rubySpans: List<EbookRubySpan> = emptyList(),
    val isVolume: Boolean = false,
    /**
     * 目录里的层级（0 = 顶层），只用于目录缩进。
     *
     * 与 [isVolume] 的分工：[isVolume] 是**排版**意义上的卷页（标题形如"第三部分 …"），
     * 会走居中卷页排版；这里纯粹是目录结构，不影响正文。
     */
    val level: Int = 0,
    /** 目录里这一条还有子条目 —— 即"大章节"，目录里灰底分组显示（照 legado）。 */
    val isGroup: Boolean = false,
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
    titleFromMarkup: Boolean,
    level: Int = 0,
    isGroup: Boolean = false
): EbookChapter {
    // "卷/大章节页"：目录结构上还有子条目的（isGroup）和标题形如"第三部分 …"的都算。
    // 两者必须用同一个判断，否则同样是"大章节"，一本书居中显示标题页、另一本
    // 却在正文里把标题又排一遍（《我们为什么要睡觉》卷标题写成 <h1> 能剥掉，
    // 《心理学原理》写成 <p> 剥不掉，于是两本书长得完全不一样）。
    val isVolumeChapter = isGroup || title.isVolumeTitle()
    val content = htmlToReaderContent(
        html = html,
        htmlBasePath = path.substringBeforeLast('/', missingDelimiterValue = ""),
        imageResources = imageResources,
        chapterTitle = title,
        isVolumeChapter = isVolumeChapter
    )
    return EbookChapter(
        title = title,
        text = content.text,
        sourcePath = path,
        images = content.images,
        rubySpans = content.rubySpans,
        isVolume = content.text.isBlank() && isVolumeChapter,
        level = level,
        isGroup = isGroup,
        titleFromMarkup = titleFromMarkup
    )
}

/**
 * 把一个 spine 项的 xhtml 变成一章或多章。
 *
 * 目录里可能有多条指向**同一个文件**、用锚点区分小节的条目（例：無職転生 的
 * part0009.xhtml 里 #a5HS/#a5HT/#a5HU 分别是 第一話/第二話/第三話）。这种就按锚点在
 * html 里的位置把 html 切成多段、各自解析成章节（legado 的做法），标题取各自的目录条目；
 * 段怎么切、边界怎么定见 [epubHtmlSegments]。
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
    isFirstSpineItem: Boolean
): List<EbookChapter> {
    // 只有**带锚点**的条目才对应一段；不带锚点的条目（例：part0007 的「第一章 幼年期」）
    // 是整文件的标题，用来给"锚点之前那一段"命名。
    val anchoredEntries = tocEntriesForFile.filter { it.fragment.isNotBlank() }
    val fileEntry = tocEntriesForFile.firstOrNull { it.fragment.isBlank() }
    // 一条锚点都定位不到（或压根没有带锚点的条目）时整个文件就是一段，命名条目取"不带锚点"
    // 的那条 —— 很多书的"第二部分 X"就是直接指向整个文件的；没有整文件条目时退化成第一条目，
    // 标题与 level/isGroup 都取自它，保持一致。这样兜底不需要单独一条分支。
    val segments = epubHtmlSegments(html, anchoredEntries).ifEmpty {
        listOf(EpubHtmlSegment(fileEntry ?: tocEntriesForFile.firstOrNull(), 0, html.length))
    }

    val chapters = mutableListOf<EbookChapter>()
    segments.forEach { segment ->
        val entry = segment.entry
        val segmentHtml = html.substring(segment.start, segment.end)
        val chapter = buildEpubChapterFromHtml(
            html = segmentHtml,
            path = path,
            title = entry?.title
                ?: fileEntry?.title
                ?: fallbackEpubChapterTitle(segmentHtml, isFirstSpineItem = isFirstSpineItem),
            imageResources = imageResources,
            // 带锚点的段由目录条目命名；"锚点之前"的续段没有条目，标题可信度看整文件条目/正文标题元素
            titleFromMarkup = entry != null || fileEntry != null || extractHtmlHeading(segmentHtml) != null,
            // 目录层级/大章节标记：带锚点的段用自己的条目，首段（锚点之前）用整文件条目
            level = (entry ?: fileEntry)?.level ?: 0,
            isGroup = (entry ?: fileEntry)?.isGroup == true
        )
        if (chapter.text.isNotBlank() || chapter.isVolume) {
            chapters += chapter.markImageOrigin(isSectionTitlePage = entry != null || fileEntry != null)
        }
    }
    return chapters
}

/**
 * 按锚点切出来的一段：正文是 html 的 `[start, end)`，[entry] 是给它命名的目录条目
 * （第一个锚点之前的那段没有条目，为 null）。
 */
internal data class EpubHtmlSegment(
    val entry: EpubTocEntry?,
    val start: Int,
    val end: Int
)

/**
 * 把 html 按目录条目的锚点切成若干段，每段带上命名它的那条条目。一条锚点都定位不到时
 * 返回空 —— 调用方据此退回"整文件一章"。
 *
 * 段的边界沿用 legado 的 start/end 模型：起点是**它自己那条条目的锚点**，终点是**下一条
 * 能定位到的锚点**。所以某条锚点在正文里找不到（错字、被改过）时不会产生段，它的正文留在
 * 相邻段里 —— 坏一条只影响它自己那一章，而不像"段数对不上锚点数就整本书退回一章"那样
 * 一坏全废（《心理学原理》整本正文排在一个 xhtml 里，曾经因此只显示 2 章）。
 */
internal fun epubHtmlSegments(html: String, entries: List<EpubTocEntry>): List<EpubHtmlSegment> {
    val cuts = anchorCutPositions(html, entries)
    if (cuts.none { it != null }) return emptyList()
    val boundaries = mutableListOf<Pair<Int, EpubTocEntry?>>(0 to null)
    cuts.forEachIndexed { index, cut -> if (cut != null) boundaries += cut to entries[index] }
    // 末尾压一个哨兵，zipWithNext 就不用再单独处理"最后一段到文末"这个边界情况
    boundaries += html.length to null
    return boundaries.zipWithNext { (start, entry), (next, _) ->
        EpubHtmlSegment(entry = entry, start = start, end = next)
    }
}

/**
 * 按目录顺序逐个条目算切点，返回**与 [entries] 一一对应**的位置；定位不到的为 null
 * —— [epubHtmlSegments] 靠这份对齐关系把"段"和"目录条目"配上，所以不能把 null 挤掉。
 *
 * 两条约束缺一不可：
 * - **不能把下一个锚点一起包进来**：有的书整篇正文外面还套着一层大 div
 *   （《心理学原理》的 `<div id="x-">`），往前回溯时会一路爬到它上面，于是好几个锚点
 *   全落到同一个位置上。
 * - **必须排在**上一个**切点之后**：段是按目录顺序挨个配标题的，切点一乱序就张冠李戴。
 *
 * 这里**不**按锚点去重：目录里"第一部分 X"和它的"第一章 Y"共用一个锚点是常见写法，
 * 去重会把后一条整章丢掉。两条各算各的切点，靠"必须递增"自然分开（见 [precedingTitleBlockStart]）。
 */
private fun anchorCutPositions(html: String, entries: List<EpubTocEntry>): List<Int?> {
    val anchorIndexes = entries.map { anchorIdIndex(html, it.fragment) }
    val cuts = mutableListOf<Int?>()
    var previousCut: Int? = null
    entries.indices.forEach { index ->
        val ownIndex = anchorIndexes[index]
        if (ownIndex == null) {
            cuts += null
            return@forEach
        }
        // 下一个**能定位到、且排在当前锚点之后**的锚点，只用来判断候选元素有没有把它一起包住。
        val nextAnchorIndex = anchorIndexes.subList(index + 1, anchorIndexes.size)
            .firstOrNull { it != null && it > ownIndex }
        val cut = anchorElementStart(
            html = html,
            idIndex = ownIndex,
            nextAnchorIndex = nextAnchorIndex,
            previousCut = previousCut,
            chapterTitle = entries[index].title
        )
        // 切点严格递增（上一个切点之后的候选才会被选中），所以不用再去重排序
        if (cut != null && cut in 1 until html.length) {
            previousCut = cut
            cuts += cut
        } else {
            cuts += null
        }
    }
    return cuts
}

/** 锚点 `id="X"` 在 html 里的位置（单双引号都认）；找不到返回 null。 */
private fun anchorIdIndex(html: String, anchor: String): Int? =
    listOf("id=\"$anchor\"", "id='$anchor'").map { html.indexOf(it) }.filter { it >= 0 }.minOrNull()

/**
 * 找到带 `id="X"` 的那个元素（`id` 位置在 [idIndex]）的起始位置，用来断开 html。
 * 从锚点往前找最近的开始标签，取"在锚点之后才闭合"的最外层那个（限制在**一段距离内**，
 * 避免一直回溯到 body/html），这样每段的标签是平衡的，也不会把上一段的样式包住。
 *
 * [nextAnchorIndex] = 下一个锚点的位置，包住它的候选元素不能用（否则两章会并成一章）；
 * [previousCut] = 上一个切点，不能退回到它前面（否则段序与目录顺序不一致，标题会配错）。
 * [chapterTitle] 用来处理"部分和它的第一章共用一个锚点"：见 [precedingTitleBlockStart]。
 */
private fun anchorElementStart(
    html: String,
    idIndex: Int,
    nextAnchorIndex: Int?,
    previousCut: Int?,
    chapterTitle: String
): Int? {
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
            if (selfClosing || closeIndex > anchorTagEnd) {
                // 自闭合元素包不住别的东西；closeIndex < 0 视为"一直开到文末"，同样算包住
                val swallowsNextAnchor = !selfClosing && nextAnchorIndex != null &&
                    tagStart < nextAnchorIndex && (closeIndex < 0 || closeIndex > nextAnchorIndex)
                val retreatsBeforePreviousCut = previousCut != null && tagStart <= previousCut
                if (!swallowsNextAnchor && !retreatsBeforePreviousCut) best = tagStart
            }
        }
        cursor = tagStart
    }
    // 锚点前面紧挨着的"同标题标题块"也算本条目，见 precedingTitleBlockStart
    val containing = best ?: return null
    val preceding = precedingTitleBlockStart(html, containing, chapterTitle) ?: return containing
    return if (previousCut == null || preceding > previousCut) preceding else containing
}

/**
 * 锚点元素**前面紧挨着**（中间只有空白）的那个标题块，如果它的文字正好等于本条目的标题，
 * 就返回它的起点 —— 否则返回 null。
 *
 * 例（《苏珊·福沃德》三册）：`<h2>第一部分 X</h2><h3 id="sigil_toc_id_1">第一章 Y</h3>`，
 * 目录里两条都指向那个 h3（部分标题自己没 id）。没有这条规则，"第一部分"只能从 h3 开始切，
 * 于是它吞掉第一章的正文、第一章整章丢失。
 */
private fun precedingTitleBlockStart(html: String, anchorElementStart: Int, chapterTitle: String): Int? {
    val title = chapterTitle.cleanTocTitle()
    if (title.isEmpty()) return null
    val windowStart = (anchorElementStart - PRECEDING_TITLE_WINDOW).coerceAtLeast(0)
    val window = html.substring(windowStart, anchorElementStart)
    // 只认"刚好在这个锚点前面结束"的那个块级元素（`\s*$` 保证是窗口里最后一个）
    val match = Regex("""(?is)<(h[1-6]|p|div)\b[^>]*>(.*?)</\1>\s*$""").find(window) ?: return null
    val text = match.groupValues[2].replace(Regex("<[^>]*>"), "").cleanTocTitle()
    if (text != title) return null
    return windowStart + match.range.first
}

private const val ANCHOR_LOOKBACK_LIMIT = 400
private const val PRECEDING_TITLE_WINDOW = 600
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

/**
 * 解析 NCX 目录，返回**前序**条目（父条目在子条目之前），`level`/`isGroup` 来自嵌套深度。
 *
 * 用 DOM 而不是 android.util.Xml：DOM 在 JDK 和 Android 上都有实现，JVM 单测里能直接跑，
 * 于是不用再为"单测跑不了 pull parser"留一层事件接缝，测试也跑的是真实 XML。
 * 节点一律按 localName 匹配（带前缀的 NCX 也认）；外部实体用 EntityResolver 吞掉
 * —— NCX 常带 NISO 的 DOCTYPE，不能让它去解析/下载 DTD。
 */
internal fun parseNcxToc(xml: String, opfBasePath: String): List<EpubTocEntry> {
    val navMap = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // 额外一层：只挡外部 DTD 加载。不用 disallow-doctype-decl —— 那会让带 NISO
            // DOCTYPE 的合法 NCX 整个解析失败（JVM 上支持该 feature，Android 上不一定，
            // 行为还会分裂）。Android 对不认识的 feature 会抛异常，所以包在 runCatching 里。
            runCatching {
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
        }
        factory.newDocumentBuilder()
            .apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
            .parse(InputSource(StringReader(xml)))
            .getElementsByTagNameNS("*", "navMap")
            .item(0) as? Element
    }.getOrNull() ?: return emptyList()
    val entries = mutableListOf<EpubTocEntry>()
    readNcxNavPoints(navMap, level = 0, opfBasePath = opfBasePath, out = entries)
    return entries
}

private fun readNcxNavPoints(
    parent: Element,
    level: Int,
    opfBasePath: String,
    out: MutableList<EpubTocEntry>
) {
    parent.childElements()
        .filter { it.localName == "navPoint" }
        .forEach { navPoint ->
            val children = navPoint.childElements()
            val title = children.firstOrNull { it.localName == "navLabel" }
                ?.textContent
                .orEmpty()
                .cleanTocTitle()
            val src = children.firstOrNull { it.localName == "content" }
                ?.getAttribute("src")
                .orEmpty()
            if (title.isNotBlank() && src.isNotBlank()) {
                tocEntryOf(opfBasePath, src, title)?.let {
                    out += it.copy(
                        level = level,
                        isGroup = children.any { child -> child.localName == "navPoint" }
                    )
                }
            }
            // 没有标题/目标的条目自己不产出，但子条目照算层级
            readNcxNavPoints(navPoint, level + 1, opfBasePath, out)
        }
}

private fun Element.childElements(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

/** EPUB3 nav 里取出的裸条目（href/标题还没做实体反转义 —— 那一步要用 android.text.Html）。 */
internal data class EpubNavTocItem(
    val href: String,
    val title: String,
    val level: Int,
    val isGroup: Boolean = false
)

private fun parseNavHtmlToc(html: String, opfBasePath: String): List<EpubTocEntry> {
    val navBlock = Regex("""(?is)<nav\b(?=[^>]*(?:epub:type|type)\s*=\s*['"]?toc\b)[^>]*>(.*?)</nav>""")
        .find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?: html
    return parseEpubNavTocItems(navBlock).mapNotNull { item ->
        val href = item.href.unescapeNavText()
        val title = item.title.unescapeNavText().cleanTocTitle()
        if (href.isBlank() || title.isBlank()) {
            null
        } else {
            tocEntryOf(opfBasePath, href, title)?.copy(level = item.level, isGroup = item.isGroup)
        }
    }
}

private fun String.unescapeNavText(): String =
    Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY).toString().trim()

/** nav 块里我们在意的标记：`<ol>`/`</ol>`，以及带 href 的 `<a>`（连标题文本一起抓）。 */
private val NAV_TOC_TOKEN_REGEX = Regex(
    """(?is)<(ol|/ol)\b[^>]*>|<a\b[^>]*href\s*=\s*(['"])(.*?)\2[^>]*>(.*?)</a>"""
)

/**
 * 按文档顺序从 nav 块里取条目，并用 `<ol>` 的嵌套深度算出层级（0 = 第一层）。
 *
 * 只认能定位到目标的 `<a href>`：没有链接的父条目（EPUB3 常见的
 * `<li><span>第一卷</span><ol>…`）自己不产出条目，但层级照算，所以子条目缩进是对的。
 */
internal fun parseEpubNavTocItems(navBlock: String): List<EpubNavTocItem> {
    val tokens = NAV_TOC_TOKEN_REGEX.findAll(navBlock).toList()
    val items = mutableListOf<EpubNavTocItem>()
    var listDepth = 0
    tokens.forEachIndexed { index, match ->
        when (val marker = match.groupValues[1].lowercase(Locale.US)) {
            "ol" -> listDepth += 1
            "/ol" -> listDepth = (listDepth - 1).coerceAtLeast(0)
            else -> {
                // 这一条后面紧跟 <ol> → 它还有子条目，是"大章节"
                val isGroup = tokens.getOrNull(index + 1)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.lowercase(Locale.US) == "ol"
                items += EpubNavTocItem(
                    href = match.groupValues[3],
                    title = match.groupValues[4],
                    level = (listDepth - 1).coerceAtLeast(0),
                    isGroup = isGroup
                )
            }
        }
    }
    return items
}

/**
 * 把目录里的 src（可能带 #锚点）解析成"文件路径 + 锚点"。
 *
 * 锚点必须从**原始 [src]** 里取：[resolveEpubPath] 解析的是文件路径，它会把 `#` 之后
 * 的部分丢掉，从它的返回值里再 `substringAfter('#')` 永远是空串。锚点一旦全空，
 * [buildEpubChaptersFromHtml] 就只剩下"一个文件一章"——像《心理学原理》这种整本正文
 * 都排在 part0001.xhtml 里的书，目录里 20 条只有 2 个 spine 文件，于是只解析出 2 章。
 */
internal fun tocEntryOf(opfBasePath: String, src: String, title: String): EpubTocEntry? {
    val path = resolveEpubPath(opfBasePath, src)
    if (path.isBlank()) return null
    val fragment = src.substringAfter('#', "")
        .trim()
        .let { raw -> runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw) }
        .takeIf { it.isNotBlank() }
        .orEmpty()
    return EpubTocEntry(title = title, path = path, fragment = fragment)
}

/** 目录标题常用 &#160;（NBSP）等 Unicode 空格做间距，而 `\s` 不匹配它们，所以连 `\p{Zs}` 一起归一化。 */
private fun String.cleanTocTitle(): String {
    return replace(Regex("[\\s\\p{Zs}]+"), " ").trim()
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
    chapterTitle: String,
    isVolumeChapter: Boolean = false
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
    // 剥掉 h1/h2 后正文为空的页面（整页只有一个标题）：先保留原标题文本，避免整章被上层
    // 当空章节丢掉。标题有没有和页眉重复，统一交给后面"切掉正文开头的标题"那一步处理
    // —— 卷/大章节页会被整段切成空，正好渲染成居中标题页。
    body = if (headingStripped.replace(Regex("<[^>]*>"), "").isBlank()) body else headingStripped
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
    // 正文开头若把章节标题又排了一遍就切掉 —— 标题已经由页眉显示过一次。
    // h1/h2 能直接剥掉（见上面的 headingStripped），但很多书（例如《心理学原理》）
    // 用普通 <p> 排标题，剥不掉，只能在这里按文本比对。
    val titleLength = leadingChapterTitleLength(parsedText.text, chapterTitle)
    val titleOnlyBody = titleLength > 0 && parsedText.text.substring(titleLength).isBlank()
    // 整章只剩标题时：卷/大章节页留空（渲染成居中标题页）；普通章节保留原样，
    // 否则会被 buildEpubChaptersFromHtml 当成空章节丢掉，目录里凭空少一章。
    val cut = if (titleOnlyBody && !isVolumeChapter) 0 else titleLength
    val text = parsedText.text.substring(cut)
    val rubySpans = if (cut == 0) {
        parsedText.rubySpans
    } else {
        parsedText.rubySpans.filter { it.start >= cut }.map { it.shiftedBy(-cut) }
    }
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
    return ReaderHtmlContent(text = text, images = images, rubySpans = rubySpans)
}

/**
 * 正文开头是不是"把章节标题又排了一遍"？返回要从正文开头切掉的字符数（0 = 不用切）。
 *
 * 章节标题由页眉显示一次（PageView.bodyTitleView），正文再来一遍就是重复。
 * 比较时按**空白段**对齐，所以书里用 `&#160;`、全角空格或连续空格排的间距不会影响判定
 * （`normalizeReaderWhitespace` 已经把 NBSP 换成了普通空格，标题侧由 [cleanTocTitle] 归一化）。
 * 标题后面必须紧跟空白，免得把"前言"这种短标题当成"前言续论"的开头误切。
 */
internal fun leadingChapterTitleLength(text: String, chapterTitle: String): Int {
    val title = chapterTitle.cleanTocTitle()
    if (title.isEmpty() || text.isEmpty()) return 0
    var textIndex = 0
    while (textIndex < text.length && text[textIndex].isWhitespace()) textIndex++
    val start = textIndex
    var titleIndex = 0
    while (titleIndex < title.length && textIndex < text.length) {
        val titleChar = title[titleIndex]
        val textChar = text[textIndex]
        if (titleChar.isWhitespace() && textChar.isWhitespace()) {
            while (titleIndex < title.length && title[titleIndex].isWhitespace()) titleIndex++
            while (textIndex < text.length && text[textIndex].isWhitespace()) textIndex++
        } else if (titleChar == textChar) {
            titleIndex++
            textIndex++
        } else {
            return 0
        }
    }
    if (titleIndex < title.length) return 0
    if (textIndex < text.length && !text[textIndex].isWhitespace()) return 0
    // 标题后面那一段空白（通常是段落换行）一起切掉
    while (textIndex < text.length && text[textIndex].isWhitespace()) textIndex++
    return if (textIndex > start) textIndex else 0
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
