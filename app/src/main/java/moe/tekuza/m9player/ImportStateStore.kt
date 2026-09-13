package moe.tekuza.m9player

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class PersistedDictionaryRef(
    val uri: String,
    val name: String,
    val cacheKey: String? = null,
    val dictionaryType: String = "Term",
    val enabled: Boolean = true
)

internal data class PersistedReaderBook(
    val id: String,
    val title: String,
    val audioUri: String?,
    val audioName: String?,
    val srtUri: String?,
    val srtName: String?,
    val ebookUri: String? = null,
    val ebookName: String? = null,
    val ebookFormat: String? = null,
    val audioCoverUri: String? = null,
    val ebookCoverUri: String? = null,
    val coverFocus: String? = null,
    val startBookCoverZoom: Double? = null,
    val startBookCoverAnchorXPx: Int? = null,
    val startBookCoverAnchorYPx: Int? = null,
    val centerBookCoverZoom: Double? = null,
    val centerBookCoverAnchorXPx: Int? = null,
    val centerBookCoverAnchorYPx: Int? = null,
    val bookCoverZoom: Double? = null,
    val bookCoverAnchorXPx: Int? = null,
    val bookCoverAnchorYPx: Int? = null,
    val bookCoverViewportX: Double? = null,
    val bookCoverViewportY: Double? = null,
    /** 导入时间（毫秒）。旧数据没有这个字段，读出来是 0 —— 排序时按书名处理，无需迁移。 */
    val addedAtMs: Long = 0L
)

internal data class PersistedImports(
    val audioUri: String?,
    val audioName: String?,
    val srtUri: String?,
    val srtName: String?,
    val audiobookFolderUri: String? = null,
    val audiobookFolderName: String? = null,
    val autoMoveToAudiobookFolder: Boolean = true,
    val keepSourceFilesWhenAutoMove: Boolean = false,
    val importOnboardingCompleted: Boolean = false,
    val books: List<PersistedReaderBook> = emptyList(),
    val selectedBookId: String? = null,
    val homeLibraryView: String = "BOOKSHELF",
    val homeCoverAspect: String = "SQUARE",
    val homeLibrarySort: String = "RECENT",
    val dictionaries: List<PersistedDictionaryRef>
)

private const val PREFS_NAME = "reader_sync_imports"
private const val KEY_STATE_JSON = "state_json"
private const val KEY_STATE_VERSION = "state_version"
private const val KEY_DICTIONARY_VERSION = "dictionary_version"

internal fun loadPersistedImports(context: Context): PersistedImports {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_STATE_JSON, null) ?: return PersistedImports(
        audioUri = null,
        audioName = null,
        srtUri = null,
        srtName = null,
        audiobookFolderUri = null,
        audiobookFolderName = null,
        autoMoveToAudiobookFolder = true,
        keepSourceFilesWhenAutoMove = false,
        importOnboardingCompleted = false,
        books = emptyList(),
        selectedBookId = null,
        homeLibraryView = "BOOKSHELF",
        homeCoverAspect = "SQUARE",
        dictionaries = emptyList()
    )

    val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return PersistedImports(
        audioUri = null,
        audioName = null,
        srtUri = null,
        srtName = null,
        audiobookFolderUri = null,
        audiobookFolderName = null,
        autoMoveToAudiobookFolder = true,
        keepSourceFilesWhenAutoMove = false,
        importOnboardingCompleted = false,
        books = emptyList(),
        selectedBookId = null,
        homeLibraryView = "BOOKSHELF",
        homeCoverAspect = "SQUARE",
        dictionaries = emptyList()
    )

    val dictionaries = mutableListOf<PersistedDictionaryRef>()
    val array = obj.optJSONArray("dictionaries") ?: JSONArray()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val uri = item.optString("uri").trim()
        if (uri.isBlank()) continue
        val name = item.optString("name").trim()
        val cacheKey = item.optString("cacheKey").trim().ifBlank { null }
        val dictionaryType = item.optString("dictionaryType").trim().ifBlank { "Term" }
        val enabled = item.optBoolean("enabled", true)
        dictionaries += PersistedDictionaryRef(
            uri = uri,
            name = name,
            cacheKey = cacheKey,
            dictionaryType = dictionaryType,
            enabled = enabled
        )
    }

    val books = mutableListOf<PersistedReaderBook>()
    val booksArray = obj.optJSONArray("books") ?: JSONArray()
    for (i in 0 until booksArray.length()) {
        val item = booksArray.optJSONObject(i) ?: continue
        val id = item.optString("id").trim()
        val audioUri = item.optString("audioUri").trim().ifBlank { null }
        val srtUri = item.optString("srtUri").trim().ifBlank { null }
        val ebookUri = item.optString("ebookUri").trim().ifBlank { null }
        if (audioUri.isNullOrBlank() && ebookUri.isNullOrBlank()) continue
        val audioName = item.optString("audioName").trim().ifBlank { null }
        val srtName = item.optString("srtName").trim().ifBlank { null }
        val ebookName = item.optString("ebookName").trim().ifBlank { null }
        val ebookFormat = item.optString("ebookFormat").trim().ifBlank { null }
        val audioCoverUri = item.optString("audioCoverUri").trim().ifBlank { null }
        val ebookCoverUri = item.optString("ebookCoverUri").trim().ifBlank { null }
        val coverFocus = item.optString("coverFocus").trim().ifBlank { null }
        val startBookCoverZoom = item.optDouble("startBookCoverZoom").takeIf { !it.isNaN() }
        val startBookCoverAnchorXPx = item.optInt("startBookCoverAnchorXPx").takeIf { it >= 0 }
        val startBookCoverAnchorYPx = item.optInt("startBookCoverAnchorYPx").takeIf { it >= 0 }
        val centerBookCoverZoom = item.optDouble("centerBookCoverZoom").takeIf { !it.isNaN() }
        val centerBookCoverAnchorXPx = item.optInt("centerBookCoverAnchorXPx").takeIf { it >= 0 }
        val centerBookCoverAnchorYPx = item.optInt("centerBookCoverAnchorYPx").takeIf { it >= 0 }
        val bookCoverZoom = item.optDouble("bookCoverZoom").takeIf { !it.isNaN() }
        val bookCoverAnchorXPx = item.optInt("bookCoverAnchorXPx").takeIf { it >= 0 }
        val bookCoverAnchorYPx = item.optInt("bookCoverAnchorYPx").takeIf { it >= 0 }
        val bookCoverViewportX = item.optDouble("bookCoverViewportX").takeIf { !it.isNaN() }
        val bookCoverViewportY = item.optDouble("bookCoverViewportY").takeIf { !it.isNaN() }
        val fallbackTitle = audioName
            ?.substringBeforeLast('.')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: ebookName
                ?.substringBeforeLast('.')
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: "Untitled Book"
        val title = item.optString("title").trim().ifBlank { fallbackTitle }
        books += PersistedReaderBook(
            id = id.ifBlank { "${audioUri ?: ebookUri.orEmpty()}|${srtUri.orEmpty()}" },
            title = title.ifBlank { "Untitled Book" },
            audioUri = audioUri,
            audioName = audioName,
            srtUri = srtUri,
            srtName = srtName,
            ebookUri = ebookUri,
            ebookName = ebookName,
            ebookFormat = ebookFormat,
            audioCoverUri = audioCoverUri,
            ebookCoverUri = ebookCoverUri,
            coverFocus = coverFocus,
            startBookCoverZoom = startBookCoverZoom,
            startBookCoverAnchorXPx = startBookCoverAnchorXPx,
            startBookCoverAnchorYPx = startBookCoverAnchorYPx,
            centerBookCoverZoom = centerBookCoverZoom,
            centerBookCoverAnchorXPx = centerBookCoverAnchorXPx,
            centerBookCoverAnchorYPx = centerBookCoverAnchorYPx,
            bookCoverZoom = bookCoverZoom,
            bookCoverAnchorXPx = bookCoverAnchorXPx,
            bookCoverAnchorYPx = bookCoverAnchorYPx,
            bookCoverViewportX = bookCoverViewportX,
            bookCoverViewportY = bookCoverViewportY,
            addedAtMs = item.optLong("addedAtMs", 0L)
        )
    }

    return PersistedImports(
        audioUri = obj.optString("audioUri").trim().ifBlank { null },
        audioName = obj.optString("audioName").trim().ifBlank { null },
        srtUri = obj.optString("srtUri").trim().ifBlank { null },
        srtName = obj.optString("srtName").trim().ifBlank { null },
        audiobookFolderUri = obj.optString("audiobookFolderUri").trim().ifBlank { null },
        audiobookFolderName = obj.optString("audiobookFolderName").trim().ifBlank { null },
        autoMoveToAudiobookFolder = obj.optBoolean("autoMoveToAudiobookFolder", true),
        keepSourceFilesWhenAutoMove = obj.optBoolean("keepSourceFilesWhenAutoMove", false),
        importOnboardingCompleted = obj.optBoolean("importOnboardingCompleted", false),
        books = books,
        selectedBookId = obj.optString("selectedBookId").trim().ifBlank { null },
        homeLibraryView = obj.optString("homeLibraryView").trim().ifBlank { "BOOKSHELF" },
        homeCoverAspect = obj.optString("homeCoverAspect").trim().ifBlank { "SQUARE" },
        homeLibrarySort = obj.optString("homeLibrarySort").trim().ifBlank { "RECENT" },
        dictionaries = dictionaries
    )
}

internal fun savePersistedImports(context: Context, state: PersistedImports) {
    val obj = JSONObject().apply {
        put("audioUri", state.audioUri ?: "")
        put("audioName", state.audioName ?: "")
        put("srtUri", state.srtUri ?: "")
        put("srtName", state.srtName ?: "")
        put("audiobookFolderUri", state.audiobookFolderUri ?: "")
        put("audiobookFolderName", state.audiobookFolderName ?: "")
        put("autoMoveToAudiobookFolder", state.autoMoveToAudiobookFolder)
        put("keepSourceFilesWhenAutoMove", state.keepSourceFilesWhenAutoMove)
        put("importOnboardingCompleted", state.importOnboardingCompleted)
        put(
            "books",
            JSONArray().apply {
                state.books.forEach { book ->
                    put(JSONObject().apply {
                        put("id", book.id)
                        put("title", book.title)
                        put("audioUri", book.audioUri ?: "")
                        put("audioName", book.audioName ?: "")
                        put("srtUri", book.srtUri ?: "")
                        put("srtName", book.srtName ?: "")
                        put("ebookUri", book.ebookUri ?: "")
                        put("ebookName", book.ebookName ?: "")
                        put("ebookFormat", book.ebookFormat ?: "")
                        put("audioCoverUri", book.audioCoverUri ?: "")
                        put("ebookCoverUri", book.ebookCoverUri ?: "")
                        put("coverFocus", book.coverFocus ?: "")
                        put("startBookCoverZoom", book.startBookCoverZoom ?: JSONObject.NULL)
                        put("startBookCoverAnchorXPx", book.startBookCoverAnchorXPx ?: JSONObject.NULL)
                        put("startBookCoverAnchorYPx", book.startBookCoverAnchorYPx ?: JSONObject.NULL)
                        put("centerBookCoverZoom", book.centerBookCoverZoom ?: JSONObject.NULL)
                        put("centerBookCoverAnchorXPx", book.centerBookCoverAnchorXPx ?: JSONObject.NULL)
                        put("centerBookCoverAnchorYPx", book.centerBookCoverAnchorYPx ?: JSONObject.NULL)
                        put("bookCoverZoom", book.bookCoverZoom ?: JSONObject.NULL)
                        put("bookCoverAnchorXPx", book.bookCoverAnchorXPx ?: JSONObject.NULL)
                        put("bookCoverAnchorYPx", book.bookCoverAnchorYPx ?: JSONObject.NULL)
                        // 少了这一行，"最近"排序里的导入时间在重启后就是 0，刚导入的书会掉回列表底部
                        put("addedAtMs", book.addedAtMs)
                    })
                }
            }
        )
        put("selectedBookId", state.selectedBookId ?: "")
        put("homeLibraryView", state.homeLibraryView)
        put("homeCoverAspect", state.homeCoverAspect)
        put("homeLibrarySort", state.homeLibrarySort)
        put(
            "dictionaries",
            JSONArray().apply {
                state.dictionaries.forEach { ref ->
                    put(JSONObject().apply {
                        put("uri", ref.uri)
                        put("name", ref.name)
                        put("cacheKey", ref.cacheKey ?: "")
                        put("dictionaryType", ref.dictionaryType)
                        put("enabled", ref.enabled)
                    })
                }
            }
        )
    }
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val nextVersion = prefs.getLong(KEY_STATE_VERSION, 0L) + 1L
    prefs.edit()
        .putString(KEY_STATE_JSON, obj.toString())
        .putLong(KEY_STATE_VERSION, nextVersion)
        .apply()
}

internal fun loadPersistedImportsVersion(context: Context): Long {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getLong(KEY_STATE_VERSION, 0L)
}

internal fun loadDictionaryDataVersion(context: Context): Long {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getLong(KEY_DICTIONARY_VERSION, 0L)
}

internal fun bumpDictionaryDataVersion(context: Context): Long {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val next = prefs.getLong(KEY_DICTIONARY_VERSION, 0L) + 1L
    prefs.edit().putLong(KEY_DICTIONARY_VERSION, next).apply()
    return next
}

internal fun registerDictionaryDataVersionListener(
    context: Context,
    onChanged: (Long) -> Unit
): SharedPreferences.OnSharedPreferenceChangeListener {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, key ->
        if (key == KEY_DICTIONARY_VERSION) {
            onChanged(changedPrefs.getLong(KEY_DICTIONARY_VERSION, 0L))
        }
    }
    prefs.registerOnSharedPreferenceChangeListener(listener)
    return listener
}

internal fun unregisterDictionaryDataVersionListener(
    context: Context,
    listener: SharedPreferences.OnSharedPreferenceChangeListener
) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .unregisterOnSharedPreferenceChangeListener(listener)
}

