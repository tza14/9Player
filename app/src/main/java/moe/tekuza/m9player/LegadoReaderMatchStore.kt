package moe.tekuza.m9player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val LEGADO_READER_MATCH_LOG_TAG = "LegadoReaderMatch"
private const val LEGADO_READER_MATCH_PREFS = "legado_reader_match_store"
private const val LEGADO_READER_MATCH_KEY = "payload_json"
private const val LEGADO_READER_MATCH_VERSION = 1

internal data class LegadoReaderMatchSnapshot(
    val matches: List<EbookCueMatch>,
    val unmatched: Int,
    val totalCues: Int,
    /**
     * 快照对应的书籍结构签名（章节数 + 总字数）。
     * matches 里的 chapterIndex 是"第几章"，章节结构一变（例如把独立图片页并进相邻章节后）
     * 旧快照就会指向别的章节，必须靠它判定失效并重新匹配。
     */
    val documentSignature: String = ""
)

internal fun loadLegadoReaderMatchSnapshotOrNull(
    context: Context,
    storeKey: String
): LegadoReaderMatchSnapshot? {
    if (storeKey.isBlank()) {
        logDebug(LEGADO_READER_MATCH_LOG_TAG) { "load skipped blank storeKey" }
        return null
    }
    val raw = context.getSharedPreferences(LEGADO_READER_MATCH_PREFS, Context.MODE_PRIVATE)
        .getString(LEGADO_READER_MATCH_KEY, null)
        ?: run {
            logDebug(LEGADO_READER_MATCH_LOG_TAG) { "load miss no payload key=${storeKey.take(48)}" }
            return null
        }
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: run {
        logDebug(LEGADO_READER_MATCH_LOG_TAG) { "load failed invalid json key=${storeKey.take(48)}" }
        return null
    }
    if (root.optInt("version") != LEGADO_READER_MATCH_VERSION) {
        logDebug(LEGADO_READER_MATCH_LOG_TAG) {
            "load skipped version mismatch stored=${root.optInt("version")} expected=$LEGADO_READER_MATCH_VERSION key=${storeKey.take(48)}"
        }
        return null
    }
    val snapshots = root.optJSONObject("snapshots") ?: run {
        logDebug(LEGADO_READER_MATCH_LOG_TAG) {
            "load skipped snapshots missing key=${storeKey.take(48)}"
        }
        return null
    }
    val payload = snapshots.optJSONObject(storeKey) ?: run {
        logDebug(LEGADO_READER_MATCH_LOG_TAG) { "load miss snapshot entry key=${storeKey.take(48)}" }
        return null
    }
    val matchesPayload = payload.optJSONArray("matches") ?: JSONArray()
    val matches = buildList {
        for (index in 0 until matchesPayload.length()) {
            val item = matchesPayload.optJSONObject(index) ?: continue
            add(
                EbookCueMatch(
                    cueIndex = item.optInt("cueIndex", -1),
                    chapterIndex = item.optInt("chapterIndex", -1),
                    rawStart = item.optInt("rawStart", -1),
                    rawEnd = item.optInt("rawEnd", -1)
                )
            )
        }
    }.filter { match ->
        match.cueIndex >= 0 &&
            match.chapterIndex >= 0 &&
            match.rawStart >= 0 &&
            match.rawEnd >= match.rawStart
    }
    val totalCues = payload.optInt("totalCues", 0).coerceAtLeast(0)
    val unmatched = payload.optInt("unmatched", 0).coerceAtLeast(0)
    val documentSignature = payload.optString("documentSignature", "")
    if (matches.isEmpty() || totalCues <= 0) {
        logDebug(LEGADO_READER_MATCH_LOG_TAG) {
            "load skipped empty snapshot matches=${matches.size} totalCues=$totalCues key=${storeKey.take(48)}"
        }
        return null
    }
    val snapshot = LegadoReaderMatchSnapshot(
        matches = matches,
        unmatched = unmatched,
        totalCues = totalCues,
        documentSignature = documentSignature
    )
    logDebug(LEGADO_READER_MATCH_LOG_TAG) {
        "load hit matches=${snapshot.matches.size} totalCues=${snapshot.totalCues} unmatched=${snapshot.unmatched} " +
        "signature=${snapshot.documentSignature} key=${storeKey.take(48)}"
    }
    return snapshot
}

internal fun saveLegadoReaderMatchSnapshot(
    context: Context,
    storeKey: String,
    snapshot: LegadoReaderMatchSnapshot
) {
    if (storeKey.isBlank()) {
        logDebug(LEGADO_READER_MATCH_LOG_TAG) { "save skipped blank storeKey" }
        return
    }
    val prefs = context.getSharedPreferences(LEGADO_READER_MATCH_PREFS, Context.MODE_PRIVATE)
    val root = prefs.getString(LEGADO_READER_MATCH_KEY, null)
        ?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?.takeIf { it.optInt("version") == LEGADO_READER_MATCH_VERSION }
        ?: JSONObject().apply { put("version", LEGADO_READER_MATCH_VERSION) }
    val snapshots = root.optJSONObject("snapshots") ?: JSONObject().also { root.put("snapshots", it) }
    snapshots.put(
        storeKey,
        JSONObject().apply {
            put("unmatched", snapshot.unmatched.coerceAtLeast(0))
            put("totalCues", snapshot.totalCues.coerceAtLeast(0))
            put("documentSignature", snapshot.documentSignature)
            put(
                "matches",
                JSONArray().apply {
                    snapshot.matches.forEach { match ->
                        put(
                            JSONObject().apply {
                                put("cueIndex", match.cueIndex)
                                put("chapterIndex", match.chapterIndex)
                                put("rawStart", match.rawStart)
                                put("rawEnd", match.rawEnd)
                            }
                        )
                    }
                }
            )
        }
    )
    prefs
        .edit()
        .putString(LEGADO_READER_MATCH_KEY, root.toString())
        .apply()
    logDebug(LEGADO_READER_MATCH_LOG_TAG) {
        "save matches=${snapshot.matches.size} totalCues=${snapshot.totalCues} unmatched=${snapshot.unmatched} key=${storeKey.take(48)}"
    }
}
