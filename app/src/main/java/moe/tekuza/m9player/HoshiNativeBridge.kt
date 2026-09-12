package moe.tekuza.m9player

import org.json.JSONObject

internal data class HoshiImportResult(
    val success: Boolean,
    val title: String,
    val termCount: Long,
    val metaCount: Long,
    val frequencyCount: Long,
    val pitchCount: Long,
    val mediaCount: Long,
    val dictPath: String,
    val errors: List<String>
)

internal object HoshiNativeBridge {
    private const val NATIVE_LIBRARY_NAME = "tset_native"

    private val loaded: Boolean = runCatching {
        System.loadLibrary(NATIVE_LIBRARY_NAME)
        true
    }.getOrElse { false }

    internal val isAvailable: Boolean
        get() = loaded

    internal fun importZip(zipPath: String, outputDir: String, lowRam: Boolean = false): HoshiImportResult {
        if (!loaded) {
            return HoshiImportResult(
                success = false,
                title = "",
                termCount = 0L,
                metaCount = 0L,
                frequencyCount = 0L,
                pitchCount = 0L,
                mediaCount = 0L,
                dictPath = "",
                errors = listOf("Native hoshidicts library not loaded")
            )
        }
        val raw = runCatching {
            nativeImportZip(zipPath, outputDir, lowRam)
        }.getOrElse { throwable ->
            return HoshiImportResult(
                success = false,
                title = "",
                termCount = 0L,
                metaCount = 0L,
                frequencyCount = 0L,
                pitchCount = 0L,
                mediaCount = 0L,
                dictPath = "",
                errors = listOf(throwable.message ?: "native import failed")
            )
        }
        return parseImportResult(raw)
    }

    private fun parseImportResult(raw: String): HoshiImportResult {
        return runCatching {
            val json = JSONObject(raw)
            val errors = mutableListOf<String>()
            val errorsArray = json.optJSONArray("errors")
            if (errorsArray != null) {
                for (i in 0 until errorsArray.length()) {
                    val value = errorsArray.optString(i).trim()
                    if (value.isNotBlank()) errors += value
                }
            }
            val topError = json.optString("error").trim()
            if (topError.isNotBlank()) errors += topError
            HoshiImportResult(
                success = json.optBoolean("success", false),
                title = json.optString("title").trim(),
                termCount = json.optLong("termCount", 0L).coerceAtLeast(0L),
                metaCount = json.optLong("metaCount", 0L).coerceAtLeast(0L),
                frequencyCount = json.optLong("frequencyCount", 0L).coerceAtLeast(0L),
                pitchCount = json.optLong("pitchCount", 0L).coerceAtLeast(0L),
                mediaCount = json.optLong("mediaCount", 0L).coerceAtLeast(0L),
                dictPath = json.optString("dictPath").trim(),
                errors = errors
            )
        }.getOrElse {
            HoshiImportResult(
                success = false,
                title = "",
                termCount = 0L,
                metaCount = 0L,
                frequencyCount = 0L,
                pitchCount = 0L,
                mediaCount = 0L,
                dictPath = "",
                errors = listOf("Invalid native import result")
            )
        }
    }

    @JvmStatic
    private external fun nativeImportZip(zipPath: String, outputDir: String, lowRam: Boolean): String

}
