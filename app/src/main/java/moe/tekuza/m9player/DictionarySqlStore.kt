package moe.tekuza.m9player

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import moe.tekuza.m9player.hoshi.dictionary.HoshiDictionaryQuerySession
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.UUID

private const val DICTIONARY_ENTRY_STORE_DIR = "dictionary_entry_store"
private const val DICTIONARY_HOSHI_ROOT_DIR = "hoshidicts"
private const val DICTIONARY_HOSHI_INFO_FILE = "info.json"
private const val DICTIONARY_HOSHI_INDEX_FILE = "index.json"
private const val DICTIONARY_HOSHI_BLOBS_FILE = "blobs.bin"
private const val DICTIONARY_HOSHI_OFFSETS_FILE = "offsets.bin"
private const val DICTIONARY_HOSHI_HASH_FILE = "hash.mph"
private const val DICTIONARY_HOSHI_HASH_TABLE_FILE = "hash.table"
private const val DICTIONARY_HOSHI_STYLES_FILE = "styles.css"
private const val HOSHI_LOOKUP_PERF_LOG_TAG = "HoshiLookupPerf"
private const val HOSHI_IMPORT_PERF_LOG_TAG = "HoshiImportPerf"
private const val HOSHI_IMPORT_ARCHIVE_MAX_BYTES = 2L * 1024L * 1024L * 1024L
private const val HOSHI_IMPORT_COPY_BUFFER_BYTES = 256 * 1024

private val DICTIONARY_STORAGE_SAFE_KEY_REGEX = Regex("[^A-Za-z0-9._-]")
private var hoshiLookupPreparedKey: String? = null
private val hoshiLookupPreparedLock = Any()


private fun dictionaryStorageRootDir(context: Context): File {
    val dir = File(context.filesDir, DICTIONARY_ENTRY_STORE_DIR)
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun dictionaryStorageSafeKey(cacheKey: String): String {
    return cacheKey.trim().ifBlank { "unknown" }.replace(DICTIONARY_STORAGE_SAFE_KEY_REGEX, "_")
}

private fun dictionaryStorageDir(context: Context, cacheKey: String): File {
    val dir = File(dictionaryStorageRootDir(context), dictionaryStorageSafeKey(cacheKey))
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun dictionaryHoshiRootDir(context: Context, cacheKey: String): File {
    val dir = File(dictionaryStorageDir(context, cacheKey), DICTIONARY_HOSHI_ROOT_DIR)
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun isValidHoshiDictionaryDir(dir: File): Boolean {
    if (!dir.isDirectory) return false
    if (!File(dir, DICTIONARY_HOSHI_BLOBS_FILE).isFile) return false
    val legacy = File(dir, DICTIONARY_HOSHI_INFO_FILE).isFile &&
        File(dir, DICTIONARY_HOSHI_OFFSETS_FILE).isFile &&
        File(dir, DICTIONARY_HOSHI_HASH_FILE).isFile
    val current = File(dir, DICTIONARY_HOSHI_INDEX_FILE).isFile &&
        File(dir, DICTIONARY_HOSHI_HASH_TABLE_FILE).isFile
    return legacy || current
}

private fun inferHoshiDictionaryTypeFromPath(dir: File): HoshiDictionaryType? {
    var current: File? = dir
    while (current != null) {
        when (current.name) {
            HoshiDictionaryType.Term.directoryName -> return HoshiDictionaryType.Term
            HoshiDictionaryType.Frequency.directoryName -> return HoshiDictionaryType.Frequency
            HoshiDictionaryType.Pitch.directoryName -> return HoshiDictionaryType.Pitch
        }
        current = current.parentFile
    }
    return null
}

private fun locateHoshiDictionaryDir(
    context: Context,
    cacheKey: String,
    type: HoshiDictionaryType? = null
): File? {
    val root = File(dictionaryStorageDir(context, cacheKey), DICTIONARY_HOSHI_ROOT_DIR)
    if (!root.isDirectory) return null
    val searchRoots = if (type != null) {
        listOf(File(root, type.directoryName))
    } else {
        HoshiDictionaryType.entries.map { File(root, it.directoryName) }
    }
    return searchRoots.asSequence()
        .filter { it.isDirectory }
        .flatMap { directory ->
            directory.listFiles()?.asSequence() ?: emptySequence()
        }
        .filter { isValidHoshiDictionaryDir(it) }
        .sortedByDescending { it.lastModified() }
        .firstOrNull()
        ?: root.listFiles()
            ?.filter { isValidHoshiDictionaryDir(it) }
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull()
}

internal fun classifyHoshiDictionaryType(result: HoshiImportResult): HoshiDictionaryType {
    return when {
        result.termCount > 0L -> HoshiDictionaryType.Term
        result.frequencyCount > 0L -> HoshiDictionaryType.Frequency
        result.pitchCount > 0L -> HoshiDictionaryType.Pitch
        else -> error("Failed to detect dictionary type")
    }
}

private fun moveDictionaryDirectory(source: File, target: File) {
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

internal fun replaceDictionaryDirectory(staged: File, target: File) {
    target.parentFile?.mkdirs()
    val backup = target.takeIf(File::exists)?.let {
        File(requireNotNull(target.parentFile), ".${target.name}-replace-${UUID.randomUUID()}")
            .also { backup -> moveDictionaryDirectory(target, backup) }
    }
    try {
        moveDictionaryDirectory(staged, target)
        backup?.deleteRecursively()
    } catch (error: Throwable) {
        target.deleteRecursively()
        if (backup?.exists() == true) moveDictionaryDirectory(backup, target)
        throw error
    }
}

internal fun isLegacyDictionaryMediaDir(directory: File): Boolean =
    File(directory, "media_index.bin").isFile && !File(directory, "media.idx").isFile

internal fun usesLegacyDictionaryMediaFormat(context: Context, cacheKey: String): Boolean {
    if (cacheKey.isBlank()) return false
    return runCatching {
        locateHoshiDictionaryDir(context, cacheKey)?.let(::isLegacyDictionaryMediaDir) == true
    }.getOrDefault(false)
}

private fun deleteDictionaryStorageDir(context: Context, cacheKey: String): Boolean {
    val dir = File(dictionaryStorageRootDir(context), dictionaryStorageSafeKey(cacheKey))
    if (!dir.exists()) return false
    return runCatching { dir.deleteRecursively() }.getOrElse { false }
}

private fun clearHoshiLookupPreparation() {
    synchronized(hoshiLookupPreparedLock) {
        hoshiLookupPreparedKey = null
    }
}

internal fun invalidateDictionaryLookupCaches() {
    clearHoshiLookupPreparation()
}

private data class HoshiDictionaryBinding(
    val dictionary: LoadedDictionary,
    val dictionaryDir: File,
    val dictionaryType: HoshiDictionaryType
)

private fun collectHoshiDictionaryBindings(
    context: Context,
    dictionaries: List<LoadedDictionary>
): List<HoshiDictionaryBinding> {
    if (!HoshiNativeBridge.isAvailable) return emptyList()
    return dictionaries.mapNotNull { dictionary ->
        val cacheKey = dictionary.cacheKey.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val type = runCatching { HoshiDictionaryType.valueOf(dictionary.dictionaryType) }
            .getOrDefault(HoshiDictionaryType.Term)
        val requestedDir = locateHoshiDictionaryDir(context, cacheKey, type)
        val dir = requestedDir
            ?: locateHoshiDictionaryDir(context, cacheKey, null)
            ?: return@mapNotNull null
        val resolvedType = when {
            requestedDir != null -> type
            type != HoshiDictionaryType.Term -> type
            else -> inferHoshiDictionaryTypeFromPath(dir) ?: type
        }
        HoshiDictionaryBinding(dictionary = dictionary, dictionaryDir = dir, dictionaryType = resolvedType)
    }
}

private fun prepareHoshiLookupIfNeeded(bindings: List<HoshiDictionaryBinding>) {
    if (bindings.isEmpty()) return
    val signature = bindings.joinToString(separator = "\n") { binding ->
        "${binding.dictionary.cacheKey.trim()}\u0001${binding.dictionaryType.name}\u0001${binding.dictionaryDir.absolutePath}"
    }
    synchronized(hoshiLookupPreparedLock) {
        if (hoshiLookupPreparedKey == signature) return
        val prepareStartNs = SystemClock.elapsedRealtimeNanos()
        val termBindings = ArrayList<HoshiDictionaryBinding>()
        val freqBindings = ArrayList<HoshiDictionaryBinding>()
        val pitchBindings = ArrayList<HoshiDictionaryBinding>()
        bindings.forEach { binding ->
            when (binding.dictionaryType) {
                HoshiDictionaryType.Term -> termBindings += binding
                HoshiDictionaryType.Frequency -> freqBindings += binding
                HoshiDictionaryType.Pitch -> pitchBindings += binding
            }
        }
        val termPaths = termBindings.map { it.dictionaryDir.absolutePath }.toTypedArray()
        val freqPaths = freqBindings.map { it.dictionaryDir.absolutePath }.toTypedArray()
        val pitchPaths = pitchBindings.map { it.dictionaryDir.absolutePath }.toTypedArray()
        logDebug(HOSHI_LOOKUP_PERF_LOG_TAG) {
            "rebuildQuery start dictCount=${bindings.size} signatureHash=${signature.hashCode()}"
        }
        logDebug("HoshiLookupPopup") {
            "prepareHoshiLookup dictCount=${bindings.size} termPaths=${termPaths.size} freqPaths=${freqPaths.size} pitchPaths=${pitchPaths.size} " +
                "terms=${termBindings.joinToString { it.dictionary.name }} " +
                "freqs=${freqBindings.joinToString { it.dictionary.name }} " +
                "pitches=${pitchBindings.joinToString { it.dictionary.name }}"
        }
        HoshiDictionaryQuerySession.rebuild(
            termPaths,
            freqPaths,
            pitchPaths
        )
        hoshiLookupPreparedKey = signature
        logDebug(HOSHI_LOOKUP_PERF_LOG_TAG) {
            "rebuildQuery done elapsedMs=${(SystemClock.elapsedRealtimeNanos() - prepareStartNs) / 1_000_000L}"
        }
    }
}

internal fun prepareHoshiLookupForDictionaries(
    context: Context,
    dictionaries: List<LoadedDictionary>
): Int {
    val bindings = collectHoshiDictionaryBindings(context, dictionaries)
    prepareHoshiLookupIfNeeded(bindings)
    return bindings.size
}

internal fun loadDictionaryFromStorage(
    context: Context,
    cacheKey: String,
    dictionaryType: String = HoshiDictionaryType.Term.name,
    fallbackDisplayName: String = "Dictionary"
): LoadedDictionary? {
    if (cacheKey.isBlank()) return null
    return runCatching {
        val resolvedType = runCatching { HoshiDictionaryType.valueOf(dictionaryType) }
            .getOrDefault(HoshiDictionaryType.Term)
        val requestedDir = locateHoshiDictionaryDir(context, cacheKey, resolvedType)
        val dictionaryDir = requestedDir
            ?: locateHoshiDictionaryDir(context, cacheKey, null)
            ?: return null
        val infoFile = listOf(
            File(dictionaryDir, DICTIONARY_HOSHI_INDEX_FILE),
            File(dictionaryDir, DICTIONARY_HOSHI_INFO_FILE),
        ).firstOrNull(File::isFile)
        val infoJson = runCatching {
            infoFile?.let { JSONObject(it.readText(Charsets.UTF_8)) }
        }.getOrNull()
        val resolvedName = infoJson?.optString("title")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackDisplayName.substringBeforeLast('.').trim().ifBlank { "Dictionary" }
        val resolvedDictionaryType = when {
            requestedDir != null -> resolvedType
            resolvedType != HoshiDictionaryType.Term -> resolvedType
            else -> inferHoshiDictionaryTypeFromPath(dictionaryDir) ?: resolvedType
        }
        val resolvedCount = infoJson?.optInt("termCount", -1)
            ?.takeIf { it >= 0 }
            ?: 0
        val stylesCss = runCatching {
            val stylesFile = File(dictionaryDir, DICTIONARY_HOSHI_STYLES_FILE)
            if (stylesFile.isFile) stylesFile.readText(Charsets.UTF_8).trim().ifBlank { null } else null
        }.getOrNull()
        LoadedDictionary(
            cacheKey = cacheKey,
            name = resolvedName,
            format = "Yomichan/Migaku ZIP (hoshidicts)",
            dictionaryType = resolvedDictionaryType.name,
            entries = emptyList(),
            stylesCss = stylesCss,
            entryCount = resolvedCount
        )
    }.getOrNull()
}

internal fun loadPersistedDictionaryFromStorage(
    context: Context,
    ref: PersistedDictionaryRef,
    fallbackDisplayName: String = "Dictionary"
): Pair<PersistedDictionaryRef, LoadedDictionary>? {
    val displayName = ref.name.ifBlank { fallbackDisplayName }
    val cacheKey = ref.cacheKey ?: buildDictionaryCacheKey(ref.uri, displayName)
    val loaded = loadDictionaryFromStorage(
        context = context,
        cacheKey = cacheKey,
        dictionaryType = ref.dictionaryType,
        fallbackDisplayName = displayName
    ) ?: return null
    return ref.copy(
        name = loaded.name.ifBlank { displayName },
        cacheKey = cacheKey,
        dictionaryType = loaded.dictionaryType
    ) to loaded
}

internal fun deleteDictionaryStorage(context: Context, cacheKey: String): Boolean {
    if (cacheKey.isBlank()) return true
    val storageDeleted = deleteDictionaryStorageDir(context, cacheKey)
    if (storageDeleted) {
        clearHoshiLookupPreparation()
    }
    return storageDeleted
}

internal fun importDictionaryFromZip(
    context: Context,
    contentResolver: ContentResolver,
    uri: Uri,
    displayName: String,
    cacheKey: String,
    onProgress: ((DictionaryImportProgress) -> Unit)? = null
): LoadedDictionary {
    require(displayName.trim().lowercase(Locale.US).endsWith(".zip")) {
        "Only ZIP dictionaries are supported"
    }
    val imported = importDictionaryZipWithHoshi(
        context = context,
        contentResolver = contentResolver,
        uri = uri,
        displayName = displayName,
        cacheKey = cacheKey,
        onProgress = onProgress
    )
    clearHoshiLookupPreparation()
    return imported
}

private fun importDictionaryZipWithHoshi(
    context: Context,
    contentResolver: ContentResolver,
    uri: Uri,
    displayName: String,
    cacheKey: String,
    onProgress: ((DictionaryImportProgress) -> Unit)?
): LoadedDictionary {
    if (!HoshiNativeBridge.isAvailable) {
        error("hoshidicts native bridge unavailable")
    }

    val totalStartNs = SystemClock.elapsedRealtimeNanos()
    onProgress?.invoke(DictionaryImportProgress(stage = "准备导入", current = 0, total = 100))
    val tempZip = File.createTempFile("dict_import_", ".zip", context.cacheDir)
    var stagingTypeRoot: File? = null
    try {
        val copyStartNs = SystemClock.elapsedRealtimeNanos()
        val archiveSize = queryDictionaryImportSize(contentResolver, uri).takeIf { it > 0L }
        archiveSize?.let { size ->
            require(size <= HOSHI_IMPORT_ARCHIVE_MAX_BYTES) {
                "Dictionary archive too large: $size bytes"
            }
        }
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempZip).use { output ->
                val buffer = ByteArray(HOSHI_IMPORT_COPY_BUFFER_BYTES)
                var copied = 0L
                var lastProgress = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    require(copied <= HOSHI_IMPORT_ARCHIVE_MAX_BYTES) {
                        "Dictionary archive too large: $copied bytes"
                    }
                    archiveSize?.let { totalBytes ->
                        val progress = ((copied.toDouble() / totalBytes.toDouble()) * 30.0)
                            .toInt()
                            .coerceIn(0, 30)
                        if (progress != lastProgress) {
                            lastProgress = progress
                            onProgress?.invoke(DictionaryImportProgress(stage = "读取辞典文件", current = progress, total = 100))
                        }
                    }
                }
            }
        } ?: error("Unable to read dictionary archive")
        val copyMs = (SystemClock.elapsedRealtimeNanos() - copyStartNs) / 1_000_000L
        onProgress?.invoke(DictionaryImportProgress(stage = "分析辞典", current = 35, total = 100))

        val hoshiRoot = dictionaryHoshiRootDir(context, cacheKey)
        stagingTypeRoot = File(hoshiRoot, ".import-${UUID.randomUUID()}").also(File::mkdirs)

        onProgress?.invoke(DictionaryImportProgress(stage = "导入辞典，可能需要几分钟", current = 0, total = 0))
        onProgress?.invoke(DictionaryImportProgress(stage = "整理辞典", current = 95, total = 100))
        clearHoshiLookupPreparation()
        val lowRamImport = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice != false
        val nativeStartNs = SystemClock.elapsedRealtimeNanos()
        val nativeResult = HoshiNativeBridge.importZip(
            zipPath = tempZip.absolutePath,
            outputDir = stagingTypeRoot.absolutePath,
            lowRam = lowRamImport
        )
        val nativeMs = (SystemClock.elapsedRealtimeNanos() - nativeStartNs) / 1_000_000L
        if (!nativeResult.success) {
            val errorDetail = nativeResult.errors.firstOrNull().orEmpty()
            val message = if (errorDetail.isBlank()) "hoshidicts import failed" else "hoshidicts import failed: $errorDetail"
            error(message)
        }

        val dictionaryType = classifyHoshiDictionaryType(nativeResult)
        logDebug(HOSHI_LOOKUP_PERF_LOG_TAG) {
            "native classify import uri=${uri} type=${dictionaryType.name}"
        }
        val stagedImportedDir = nativeResult.dictPath
            .takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf(::isValidHoshiDictionaryDir)
            ?: error("hoshidicts output not found")
        require(stagedImportedDir.parentFile?.canonicalFile == stagingTypeRoot.canonicalFile) {
            "hoshidicts output escaped staging directory"
        }
        val hoshiTypeRoot = File(hoshiRoot, dictionaryType.directoryName)
        val publishStartNs = SystemClock.elapsedRealtimeNanos()
        replaceDictionaryDirectory(stagingTypeRoot, hoshiTypeRoot)
        stagingTypeRoot = null
        val publishMs = (SystemClock.elapsedRealtimeNanos() - publishStartNs) / 1_000_000L
        val importedDir = File(hoshiTypeRoot, stagedImportedDir.name)
            .takeIf(::isValidHoshiDictionaryDir)
            ?: error("hoshidicts output not found after publish")

        val dictionaryName = nativeResult.title.ifBlank {
            importedDir.name.ifBlank {
                displayName.substringBeforeLast('.').ifBlank { "Dictionary" }
            }
        }
        val stylesCss = runCatching {
            val stylesFile = File(importedDir, DICTIONARY_HOSHI_STYLES_FILE)
            if (stylesFile.isFile) {
                stylesFile.readText(Charsets.UTF_8).trim().ifBlank { null }
            } else {
                null
            }
        }.getOrNull()
        val entryCount = (nativeResult.termCount.takeIf { it > 0L } ?: nativeResult.metaCount)
            .coerceAtLeast(0L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        onProgress?.invoke(DictionaryImportProgress(stage = "完成", current = 100, total = 100))
        val totalMs = (SystemClock.elapsedRealtimeNanos() - totalStartNs) / 1_000_000L
        Log.i(
            HOSHI_IMPORT_PERF_LOG_TAG,
            "name=${displayName.take(80)} bytes=${tempZip.length()} lowRam=$lowRamImport " +
                "copyMs=$copyMs classifyMs=0 nativeMs=$nativeMs publishMs=$publishMs " +
                "otherMs=${(totalMs - copyMs - nativeMs - publishMs).coerceAtLeast(0L)} totalMs=$totalMs"
        )
        return LoadedDictionary(
            cacheKey = cacheKey,
            name = dictionaryName,
            format = "Yomichan/Migaku ZIP (hoshidicts)",
            dictionaryType = dictionaryType.name,
            entries = emptyList(),
            stylesCss = stylesCss,
            entryCount = entryCount
        )
    } finally {
        stagingTypeRoot?.deleteRecursively()
        runCatching { tempZip.delete() }
    }
}

private fun queryDictionaryImportSize(contentResolver: ContentResolver, uri: Uri): Long {
    return runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use -1L
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index < 0 || cursor.isNull(index)) -1L else cursor.getLong(index)
        } ?: -1L
    }.getOrDefault(-1L)
}

internal fun lookupDictionarySourceUriByCacheKey(context: Context, cacheKey: String): String? {
    if (cacheKey.isBlank()) return null
    return loadPersistedImports(context)
        .dictionaries
        .firstOrNull { it.cacheKey == cacheKey }
        ?.uri
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}
