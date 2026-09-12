package moe.tekuza.m9player

import android.content.Context
import androidx.compose.runtime.snapshots.SnapshotStateMap
import org.json.JSONObject

internal data class PersistedAnkiConfig(
    val deckName: String,
    val modelName: String,
    val tags: String,
    val fieldTemplates: Map<String, String>
)

internal data class ResolvedAnkiCatalogSelection(
    val deckName: String,
    val modelName: String
)

internal data class ResolvedAnkiCatalogData(
    val decks: List<String>,
    val models: List<AnkiModelTemplate>,
    val selection: ResolvedAnkiCatalogSelection
)

internal sealed interface AnkiCatalogLoadResult {
    data class Success(val data: ResolvedAnkiCatalogData) : AnkiCatalogLoadResult
    data class Failure(val message: String, val cause: Throwable? = null) : AnkiCatalogLoadResult
}

private const val ANKI_PREFS_NAME = "anki_export_config"
private const val ANKI_KEY_STATE = "anki_state_json"
private const val ANKI_CONFIG_LOG_TAG = "AnkiConfig"
internal const val DEFAULT_ANKI_TAG = "⑨"

internal fun loadPersistedAnkiConfig(context: Context): PersistedAnkiConfig {
    val prefs = context.getSharedPreferences(ANKI_PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(ANKI_KEY_STATE, null) ?: return PersistedAnkiConfig(
        deckName = "Default",
        modelName = "",
        tags = DEFAULT_ANKI_TAG,
        fieldTemplates = emptyMap()
    )

    val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return PersistedAnkiConfig(
        deckName = "Default",
        modelName = "",
        tags = DEFAULT_ANKI_TAG,
        fieldTemplates = emptyMap()
    )

    val fieldsObj = obj.optJSONObject("fieldTemplates") ?: JSONObject()
    val templates = mutableMapOf<String, String>()
    val keys = fieldsObj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = fieldsObj.optString(key).trim()
        if (key.isNotBlank()) templates[key] = value
    }

    val config = PersistedAnkiConfig(
        deckName = obj.optString("deckName").trim().ifBlank { "Default" },
        modelName = obj.optString("modelName").trim(),
        tags = if (obj.has("tags")) {
            obj.optString("tags").trim()
        } else {
            DEFAULT_ANKI_TAG
        },
        fieldTemplates = templates
    )
    logDebug(ANKI_CONFIG_LOG_TAG) {
        "load persisted deck='${config.deckName}' model='${config.modelName}' tagsLen=${config.tags.length} fieldCount=${config.fieldTemplates.size}"
    }
    return config
}

internal fun savePersistedAnkiConfig(context: Context, config: PersistedAnkiConfig) {
    clearPreparedAnkiExportCache()
    logDebug(ANKI_CONFIG_LOG_TAG) {
        "save persisted deck='${config.deckName}' model='${config.modelName}' tagsLen=${config.tags.length} fieldCount=${config.fieldTemplates.size}"
    }
    val obj = JSONObject().apply {
        put("deckName", config.deckName)
        put("modelName", config.modelName)
        put("tags", config.tags)
        put(
            "fieldTemplates",
            JSONObject().apply {
                config.fieldTemplates.forEach { (field, template) ->
                    put(field, template)
                }
            }
        )
    }
    context.getSharedPreferences(ANKI_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(ANKI_KEY_STATE, obj.toString())
        .apply()
}

internal fun buildPersistedAnkiConfig(
    deckName: String,
    modelName: String,
    tags: String,
    fieldTemplates: Map<String, String>
): PersistedAnkiConfig {
    return PersistedAnkiConfig(
        deckName = deckName,
        modelName = modelName,
        tags = tags,
        fieldTemplates = fieldTemplates
    )
}

internal fun syncAnkiFieldTemplates(
    target: SnapshotStateMap<String, String>,
    fields: List<String>,
    clearExisting: Boolean = false,
    modelTemplates: Map<String, String> = emptyMap()
) {
    if (clearExisting) {
        target.clear()
    }
    val keep = fields.toSet()
    target.keys
        .filter { it !in keep }
        .forEach { target.remove(it) }
    fields.forEach { field ->
        target[field] = if (clearExisting || !target.containsKey(field)) {
            modelTemplates[field].orEmpty()
        } else {
            target[field].orEmpty()
        }
    }
}

internal fun resolveAnkiCatalogSelection(
    currentDeckName: String,
    currentModelName: String,
    catalog: AnkiCatalog,
    defaultDeckName: String
): ResolvedAnkiCatalogSelection {
    val resolvedDeckName = currentDeckName.ifBlank {
        catalog.decks.firstOrNull() ?: defaultDeckName
    }
    val resolvedModelName = when {
        currentModelName.isNotBlank() && catalog.models.any { it.name == currentModelName } -> currentModelName
        currentModelName.isNotBlank() -> currentModelName
        catalog.models.isNotEmpty() -> catalog.models.first().name
        else -> ""
    }
    return ResolvedAnkiCatalogSelection(
        deckName = resolvedDeckName,
        modelName = resolvedModelName
    )
}

internal fun loadResolvedAnkiCatalog(
    context: Context,
    currentDeckName: String,
    currentModelName: String,
    defaultDeckName: String
): ResolvedAnkiCatalogData {
    val catalog = loadAnkiCatalog(context)
    return ResolvedAnkiCatalogData(
        decks = catalog.decks,
        models = catalog.models,
        selection = resolveAnkiCatalogSelection(
            currentDeckName = currentDeckName,
            currentModelName = currentModelName,
            catalog = catalog,
            defaultDeckName = defaultDeckName
        )
    )
}

internal fun loadResolvedAnkiCatalogResult(
    context: Context,
    currentDeckName: String,
    currentModelName: String,
    defaultDeckName: String
): AnkiCatalogLoadResult {
    return try {
        AnkiCatalogLoadResult.Success(
            loadResolvedAnkiCatalog(
                context = context,
                currentDeckName = currentDeckName,
                currentModelName = currentModelName,
                defaultDeckName = defaultDeckName
            )
        )
    } catch (error: Throwable) {
        AnkiCatalogLoadResult.Failure(
            message = explainAnkiCatalogLoadFailure(context, error),
            cause = error
        )
    }
}

internal fun isAnkiModelInCatalog(catalog: AnkiCatalog, modelName: String): Boolean {
    val normalized = modelName.trim()
    if (normalized.isBlank()) return false
    return catalog.models.any { it.name == normalized }
}

private fun explainAnkiCatalogLoadFailure(context: Context, error: Throwable): String {
    return when (val result = classifyAnkiExportFailure(context, error)) {
        is AnkiExportResult.NotAvailable -> result.message
        is AnkiExportResult.InvalidConfig -> result.message
        is AnkiExportResult.Failed -> {
            val message = error.message?.trim().orEmpty()
            if (message.isBlank()) context.getString(R.string.anki_load_failed) else message
        }
        is AnkiExportResult.DuplicateSkipped -> result.message
        AnkiExportResult.Added -> context.getString(R.string.anki_load_failed)
    }
}

internal fun buildCurrentAnkiExportConfigOrNull(
    deckName: String,
    modelName: String,
    tags: String,
    models: List<AnkiModelTemplate>,
    fieldTemplates: Map<String, String>,
    defaultDeckName: String = "Default"
): AnkiExportConfig? {
    if (modelName.isBlank()) return null
    val model = models.firstOrNull { it.name == modelName } ?: return null
    val templates = model.fields.associateWith { field ->
        fieldTemplates[field].orEmpty()
    }
    return AnkiExportConfig(
        deckName = deckName.ifBlank { defaultDeckName },
        modelName = model.name,
        fieldTemplates = templates,
        tags = parseAnkiTags(tags),
        model = model
    )
}

