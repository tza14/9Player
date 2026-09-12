package moe.tekuza.m9player

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val LEGADO_READER_SRT_LOG_TAG = "LegadoReaderSrt"
private const val LEGADO_READER_SRT_CACHE_DIR = "legado_reader_srt_cache"
private const val LEGADO_READER_SRT_VERSION = 1

internal data class LegadoReaderSrtSnapshot(
    val cues: List<EbookSrtCue>
)

internal fun loadLegadoReaderSrtSnapshotOrNull(
    context: Context,
    uri: Uri
): LegadoReaderSrtSnapshot? {
    val sourceStamp = buildReaderSourceStamp(context, uri)
    val file = legadoReaderSrtCacheFile(context, uri)
    if (!file.isFile) {
        logDebug(LEGADO_READER_SRT_LOG_TAG) { "load miss no file uri=$uri" }
        return null
    }
    val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: run {
        logDebug(LEGADO_READER_SRT_LOG_TAG) { "load failed invalid json uri=$uri" }
        return null
    }
    if (root.optInt("version") != LEGADO_READER_SRT_VERSION) {
        logDebug(LEGADO_READER_SRT_LOG_TAG) { "load skipped version mismatch uri=$uri" }
        return null
    }
    val cachedStamp = root.optString("sourceStamp")
    if (cachedStamp != sourceStamp) {
        logDebug(LEGADO_READER_SRT_LOG_TAG) { "load skipped stale cache uri=$uri" }
        return null
    }
    val cuesPayload = root.optJSONArray("cues") ?: JSONArray()
    val cues = buildList {
        for (index in 0 until cuesPayload.length()) {
            val item = cuesPayload.optJSONObject(index) ?: continue
            val text = item.optString("text").takeIf { it.isNotBlank() } ?: continue
            add(
                EbookSrtCue(
                    startMs = item.optLong("startMs", -1L),
                    endMs = item.optLong("endMs", -1L),
                    text = text
                )
            )
        }
    }.filter { cue ->
        cue.startMs >= 0L && cue.endMs >= cue.startMs
    }
    if (cues.isEmpty()) {
        logDebug(LEGADO_READER_SRT_LOG_TAG) { "load skipped empty cues uri=$uri" }
        return null
    }
    logDebug(LEGADO_READER_SRT_LOG_TAG) { "load hit cues=${cues.size} uri=$uri" }
    return LegadoReaderSrtSnapshot(cues = cues)
}

internal fun saveLegadoReaderSrtSnapshot(
    context: Context,
    uri: Uri,
    cues: List<EbookSrtCue>
) {
    if (cues.isEmpty()) {
        logDebug(LEGADO_READER_SRT_LOG_TAG) { "save skipped empty cues uri=$uri" }
        return
    }
    val file = legadoReaderSrtCacheFile(context, uri)
    file.parentFile?.mkdirs()
    val sourceStamp = buildReaderSourceStamp(context, uri)
    val root = JSONObject()
        .put("version", LEGADO_READER_SRT_VERSION)
        .put("sourceUri", uri.toString())
        .put("sourceStamp", sourceStamp)
        .put(
            "cues",
            JSONArray().apply {
                cues.forEach { cue ->
                    put(
                        JSONObject()
                            .put("startMs", cue.startMs)
                            .put("endMs", cue.endMs)
                            .put("text", cue.text)
                    )
                }
            }
        )
    file.writeText(root.toString())
    logDebug(LEGADO_READER_SRT_LOG_TAG) { "save cues=${cues.size} uri=$uri" }
}

private fun legadoReaderSrtCacheFile(context: Context, uri: Uri): File {
    val key = buildDictionaryCacheKey(uri.toString(), "legado-reader-srt")
    return File(context.filesDir, "$LEGADO_READER_SRT_CACHE_DIR/$key.json")
}
