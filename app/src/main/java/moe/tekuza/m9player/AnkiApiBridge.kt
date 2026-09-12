package moe.tekuza.m9player

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.TransformationResult
import androidx.media3.transformer.Transformer
import com.ichi2.anki.api.AddContentApi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AnkiModelTemplate(
    val id: Long,
    val name: String,
    val fields: List<String>
)

internal data class AnkiCatalog(
    val decks: List<String>,
    val models: List<AnkiModelTemplate>,
    val deckIds: Map<String, Long> = emptyMap()
)

internal data class AnkiExportConfig(
    val deckName: String,
    val modelName: String,
    val fieldTemplates: Map<String, String>,
    val tags: Set<String>,
    val deckId: Long? = null,
    val model: AnkiModelTemplate? = null
)

internal data class PreparedAnkiExport(
    val config: AnkiExportConfig,
    val requiresLookupAudio: Boolean
)

internal sealed interface AnkiExportResult {
    data object Added : AnkiExportResult
    data class DuplicateSkipped(
        val message: String
    ) : AnkiExportResult
    data class NotAvailable(
        val state: AnkiAvailabilityState,
        val message: String
    ) : AnkiExportResult
    data class InvalidConfig(
        val message: String
    ) : AnkiExportResult
    data class Failed(
        val message: String,
        val cause: Throwable? = null
    ) : AnkiExportResult
}

internal enum class ExportAnkiOutcome {
    ADDED,
    DUPLICATE_SKIPPED
}

private val TEMPLATE_VARIABLE_REGEX = Regex("\\{([^{}]+)\\}")
private val SINGLE_GLOSSARY_DICT_MARKER_REGEX = Regex("\\{single-glossary-([^{}]+)\\}", RegexOption.IGNORE_CASE)
private val SINGLE_FREQUENCY_NUMBER_DICT_MARKER_REGEX =
    Regex("\\{single-frequency-number-([^{}]+)\\}", RegexOption.IGNORE_CASE)
private val SINGLE_FREQUENCY_DICT_MARKER_REGEX = Regex("\\{single-frequency-([^{}]+)\\}", RegexOption.IGNORE_CASE)
private val NON_ALNUM_TEMPLATE_KEY_REGEX = Regex("[^a-z0-9]")
private val DICTIONARY_TOKEN_STRIP_REGEX = Regex("[\\s\\p{Punct}\\p{S}]")
private val ANKI_LINK_TAG_REGEX = Regex("(?is)<link\\b[^>]*>")
private val ANKI_IMG_TAG_REGEX = Regex("(?is)<img\\b[^>]*>")
private val ANKI_STYLE_TAG_REGEX = Regex("(?is)<style\\b[^>]*>(.*?)</style>")
private val ANKI_URI_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
private val ANKI_IMG_SRC_IN_TAG_REGEX = Regex("(?is)\\bsrc\\s*=\\s*(['\"])(.*?)\\1")
private val ANKI_TAG_SEPARATOR_REGEX = Regex("[,\\s]+")
private val ANKI_ZERO_WIDTH_REGEX = Regex("[\\u200B-\\u200D\\uFEFF]")
private val ANKI_WHITESPACE_REGEX = Regex("\\s+")
private val ANKI_MEDIA_SAFE_LABEL_REGEX = Regex("[^a-z0-9]+")
private val ANKI_SRC_ATTR_QUOTED_REGEX = Regex("(?i)\\bsrc\\s*=\\s*(['\"])(.*?)\\1")
private val ANKI_SRC_ATTR_UNQUOTED_REGEX = Regex("(?i)\\bsrc\\s*=\\s*([^\\s\"'<>`]+)")
private val ANKI_REL_ATTR_QUOTED_REGEX = Regex("(?i)\\brel\\s*=\\s*(['\"])(.*?)\\1")
private val ANKI_REL_ATTR_UNQUOTED_REGEX = Regex("(?i)\\brel\\s*=\\s*([^\\s\"'<>`]+)")
private val ANKI_HREF_ATTR_QUOTED_REGEX = Regex("(?i)\\bhref\\s*=\\s*(['\"])(.*?)\\1")
private val ANKI_HREF_ATTR_UNQUOTED_REGEX = Regex("(?i)\\bhref\\s*=\\s*([^\\s\"'<>`]+)")
private val ANKI_LEADING_GLOSSARY_INDEX_REGEX = Regex("""(<i>\()\s*\d+\s*[,，]\s*""", RegexOption.IGNORE_CASE)
private val ANKI_LEADING_DICTIONARY_LABEL_REGEX = Regex("""^<i>\([^<]*\)</i>\s*""", RegexOption.IGNORE_CASE)
private val ANKI_LI_TAG_REGEX = Regex("</?li\\b", RegexOption.IGNORE_CASE)
private val ANKI_FIRST_NUMBER_REGEX = Regex("\\d+(?:\\.\\d+)?")
private const val ANKI_AUDIO_LOG_TAG = "AnkiAudio"
private const val ANKI_EXPORT_DEBUG_TAG = "AnkiExportDebug"
private val CORE_GLOSSARY_VARIABLES = setOf(
    "definitions",
    "definition",
    "glossary",
    "glossary-no-dictionary",
    "glossary-first",
    "glossary-first-brief",
    "glossary-first-no-dictionary",
    "glossary-brief",
    "glossary-plain",
    "glossary-plain-no-dictionary"
)
private val SINGLE_GLOSSARY_VARIABLES = setOf(
    "single-glossary",
    "single-glossary-brief",
    "single-glossary-no-dictionary"
)
private val CLOZE_VARIABLES = setOf("cloze-prefix", "cloze-body", "cloze-body-kana", "cloze-suffix")
private val FURIGANA_VARIABLES = setOf("furigana", "furigana-plain", "expression-furigana")
private val FREQUENCY_VARIABLES = setOf(
    "frequency",
    "frequencies",
    "single-frequency",
    "single-frequency-number",
    "frequency-harmonic-rank",
    "frequency-harmonic-occurrence",
    "frequency-average-rank",
    "frequency-average-occurrence"
)
private val ankiPreparedExportCacheLock = Any()
private var cachedAnkiPreparedExport: Pair<PersistedAnkiConfig, PreparedAnkiExport>? = null

internal fun clearPreparedAnkiExportCache() {
    synchronized(ankiPreparedExportCacheLock) {
        cachedAnkiPreparedExport = null
    }
}

internal fun loadAnkiCatalog(context: Context): AnkiCatalog {
    ankiAvailabilityErrorMessage(context, requirePermission = true)?.let(::error)

    val api = AddContentApi(context)
    val deckMap = api.getDeckList() ?: emptyMap()
    val deckNames = deckMap
        .values
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .sortedBy { it.lowercase(Locale.ROOT) }

    val modelMap = api.getModelList(1) ?: emptyMap()
    val models = modelMap.entries.mapNotNull { (id, nameRaw) ->
        val name = nameRaw.trim()
        if (name.isBlank()) return@mapNotNull null
        val fields = (api.getFieldList(id) ?: emptyArray())
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (fields.isEmpty()) return@mapNotNull null

        AnkiModelTemplate(
            id = id,
            name = name,
            fields = fields
        )
    }.sortedBy { it.name.lowercase(Locale.ROOT) }

    return AnkiCatalog(
        decks = deckNames,
        models = models,
        deckIds = deckMap.entries.associate { (id, name) -> name.trim() to id }
    )
}

internal fun hasAnyAnkiFieldTemplate(templates: Map<String, String>): Boolean {
    return templates.values.any { it.trim().isNotBlank() }
}

internal fun parseAnkiTags(raw: String): Set<String> {
    return raw
        .split(ANKI_TAG_SEPARATOR_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
}

internal fun prepareAnkiExport(
    context: Context,
    persistedConfig: PersistedAnkiConfig,
    audioUri: Uri?,
    lookupAudioUri: Uri?
): PreparedAnkiExport {
    ankiAvailabilityErrorMessage(context, requirePermission = true)?.let(::error)
    if (persistedConfig.modelName.isBlank()) error(context.getString(R.string.error_anki_model_missing))

    synchronized(ankiPreparedExportCacheLock) {
        cachedAnkiPreparedExport
            ?.takeIf { (cachedConfig, _) -> cachedConfig == persistedConfig }
            ?.second
            ?.let { return it }
    }

    val catalog = loadAnkiCatalog(context)
    val model = catalog.models.firstOrNull { it.name == persistedConfig.modelName }
        ?: error(context.getString(R.string.error_anki_model_not_found, persistedConfig.modelName))

    val templates = model.fields.associateWith { field ->
        persistedConfig.fieldTemplates[field].orEmpty()
    }
    if (!hasAnyAnkiFieldTemplate(templates)) {
        error(context.getString(R.string.error_anki_fields_empty))
    }
    val requiresLookupAudio = extractRequiredTemplateMarkers(templates.values).needs("audio")

    val deckName = persistedConfig.deckName.ifBlank { "Default" }
    val deckId = catalog.deckIds.entries
        .firstOrNull { (name, _) -> name.equals(deckName, ignoreCase = true) }
        ?.value
        ?: runCatching { AddContentApi(context).addNewDeck(deckName) }.getOrElse { throwable ->
            error("Anki deck resolve failed for '$deckName'. ${throwableDetail(throwable)}")
        }
    val prepared = PreparedAnkiExport(
        config = AnkiExportConfig(
            deckName = deckName,
            modelName = model.name,
            fieldTemplates = templates,
            tags = parseAnkiTags(persistedConfig.tags),
            deckId = deckId,
            model = model
        ),
        requiresLookupAudio = requiresLookupAudio
    )
    synchronized(ankiPreparedExportCacheLock) {
        cachedAnkiPreparedExport = persistedConfig to prepared
    }
    return prepared
}

internal fun exportToAnkiDroidApi(
    context: Context,
    card: MinedCard,
    config: AnkiExportConfig
): ExportAnkiOutcome {
    logDebug(ANKI_EXPORT_DEBUG_TAG) {
        "export start wordLength=${card.word.length} primaryDictionaryLength=${card.dictionaryName?.length ?: 0} " +
            "glossaryByDictCount=${card.glossaryByDictionary.size} modelLength=${config.modelName.length} deckLength=${config.deckName.length}"
    }
    ankiAvailabilityErrorMessage(context, requirePermission = true)?.let(::error)

    val api = runCatching { AddContentApi(context) }.getOrElse { throwable ->
        error("Anki API init failed. ${throwableDetail(throwable)}")
    }
    val deckId = config.deckId ?: runCatching { findOrCreateDeckId(api, config.deckName) }.getOrElse { throwable ->
        error("Anki deck resolve failed for '${config.deckName}'. ${throwableDetail(throwable)}")
    }
    val model = config.model ?: runCatching { findModel(api, config.modelName) }.getOrElse { throwable ->
        error("Anki model resolve failed for '${config.modelName}'. ${throwableDetail(throwable)}")
    }
        ?: error("Anki model not found: ${config.modelName}")

    val templatesByField = model.fields.associateWith { fieldName ->
        config.fieldTemplates[fieldName].orEmpty().trim()
    }
    if (templatesByField.values.none { it.isNotBlank() }) {
        error("All field variables are empty. Configure at least one marker in Settings > Anki.")
    }
    val requiredMarkers = extractRequiredTemplateMarkers(templatesByField.values)

    val variables = runCatching {
        buildAnkiVariables(
            context = context,
            api = api,
            card = card,
            requiredMarkers = requiredMarkers
        )
    }.getOrElse { throwable ->
        error("Anki variable build failed. ${throwableDetail(throwable)}")
    }
    logDebug(ANKI_EXPORT_DEBUG_TAG) {
        "export variables dictionaryLength=${variables["dictionary"].orEmpty().length} " +
            "glossaryLen=${variables["glossary"]?.length ?: 0} singleGlossaryLen=${variables["single-glossary"]?.length ?: 0}"
    }

    val fieldValues = runCatching {
        val mediaSrcCache = mutableMapOf<String, String>()
        val dictionaryMediaByFilename = card.dictionaryMedia.associateBy { it.filename }
        model.fields.map { fieldName ->
            val template = templatesByField[fieldName].orEmpty()
            val rendered = resolveTemplate(template, variables).trim()
            rewriteHtmlForAnkiExport(
                context = context,
                api = api,
                html = rendered,
                sourceLabel = "field:$fieldName",
                mediaSrcCache = mediaSrcCache,
                dictionaryMediaByFilename = dictionaryMediaByFilename
            )
        }.toTypedArray()
    }.getOrElse { throwable ->
        error("Anki field rendering failed for model '${model.name}'. ${throwableDetail(throwable)}")
    }
    if (fieldValues.all { it.isBlank() }) {
        error("All rendered field values are empty. Check your field variables in Settings > Anki.")
    }
    logDebug(ANKI_EXPORT_DEBUG_TAG) {
        "export rendered fields=${model.fields.zip(fieldValues.asList()).joinToString(separator = "|") { (name, value) -> "$name:${value.length}" }}"
    }

    val tags = config.tags
    val duplicateConfig = loadAnkiDuplicateConfig(context)
    val duplicateKey = normalizeAnkiDuplicateKey(card.word)
    if (duplicateConfig.enabled && duplicateKey.isNotBlank()) {
        val hasDuplicate = runCatching {
            val duplicateNotes = api.findDuplicateNotes(model.id, listOf(duplicateKey))
            duplicateNotes != null && duplicateNotes.size() > 0
        }.getOrDefault(false)
        if (hasDuplicate) {
            logDebug(ANKI_EXPORT_DEBUG_TAG) {
                "export duplicate hit action=${duplicateConfig.action} scope=${duplicateConfig.scope} " +
                    "modelLength=${model.name.length} keyLength=${duplicateKey.length}"
            }
            when (duplicateConfig.action.lowercase(Locale.ROOT)) {
                "add" -> Unit
                "prevent" -> return ExportAnkiOutcome.DUPLICATE_SKIPPED
                else -> return ExportAnkiOutcome.DUPLICATE_SKIPPED
            }
        }
    }

    val noteId = runCatching {
        api.addNote(model.id, deckId, fieldValues, tags)
    }.getOrElse { throwable ->
        error(
            "Anki addNote failed. model=${model.name}, deck=${config.deckName}, " +
                "fields=${model.fields.size}, tags=${tags.joinToString(",")}. ${throwableDetail(throwable)}"
        )
    }
    if (noteId == null || noteId <= 0L) {
        val emptyFields = model.fields
            .zip(fieldValues.asList())
            .filter { (_, value) -> value.isBlank() }
            .map { (name, _) -> name }
        val detail = if (emptyFields.isEmpty()) {
            ""
        } else {
            " Empty fields: ${emptyFields.joinToString(", ")}."
        }
        error("AnkiDroid rejected the note. Check model fields and templates.$detail")
    }
    return ExportAnkiOutcome.ADDED
}

internal fun normalizeAnkiDuplicateKey(raw: String?): String {
    val normalized = Normalizer.normalize(raw.orEmpty(), Normalizer.Form.NFKC)
    return normalized
        .replace(ANKI_ZERO_WIDTH_REGEX, "")
        .replace(ANKI_WHITESPACE_REGEX, " ")
        .trim()
}

internal fun exportToAnkiDroidApiResult(
    context: Context,
    card: MinedCard,
    config: AnkiExportConfig
): AnkiExportResult {
    return try {
        when (exportToAnkiDroidApi(context, card, config)) {
            ExportAnkiOutcome.ADDED -> AnkiExportResult.Added
            ExportAnkiOutcome.DUPLICATE_SKIPPED -> {
                AnkiExportResult.DuplicateSkipped(context.getString(R.string.anki_toast_duplicate_skipped))
            }
        }
    } catch (error: Throwable) {
        classifyAnkiExportFailure(context, error)
    }
}

internal suspend fun findAnkiDuplicateNoteIdsByFirstFieldAsync(
    context: Context,
    firstFieldValue: String
): List<Long> = withContext(Dispatchers.IO) {
    val key = normalizeAnkiDuplicateKey(firstFieldValue)
    if (key.isBlank()) return@withContext emptyList()
    if (detectAnkiAvailability(context, requirePermission = true) != AnkiAvailabilityState.READY) {
        return@withContext emptyList()
    }
    runCatching {
        val persisted = loadPersistedAnkiConfig(context)
        if (persisted.modelName.isBlank()) return@runCatching emptyList()
        val api = AddContentApi(context)
        val model = findModel(api, persisted.modelName) ?: return@runCatching emptyList()
        val duplicateNotes = api.findDuplicateNotes(model.id, listOf(key))
        val duplicateIndex = duplicateNotes?.indexOfKey(0) ?: -1
        if (duplicateIndex < 0) {
            emptyList()
        } else {
            duplicateNotes.valueAt(duplicateIndex)
                .orEmpty()
                .map { it.id }
                .filter { it > 0L }
                .distinct()
        }
    }.getOrDefault(emptyList())
}

internal suspend fun checkAnkiDuplicateByFirstFieldAsync(
    context: Context,
    firstFieldValue: String
): AnkiDuplicateCheckResult = withContext(Dispatchers.IO) {
    val duplicateConfig = loadAnkiDuplicateConfig(context)
    if (!duplicateConfig.enabled) {
        return@withContext AnkiDuplicateCheckResult()
    }
    val noteIds = findAnkiDuplicateNoteIdsByFirstFieldAsync(context, firstFieldValue)
    AnkiDuplicateCheckResult(
        noteIds = noteIds,
        allowAdd = duplicateConfig.action.equals("add", ignoreCase = true)
    )
}

internal fun prepareAnkiExportResult(
    context: Context,
    persistedConfig: PersistedAnkiConfig,
    audioUri: Uri?,
    lookupAudioUri: Uri?
): Result<PreparedAnkiExport> {
    return runCatching {
        prepareAnkiExport(
            context = context,
            persistedConfig = persistedConfig,
            audioUri = audioUri,
            lookupAudioUri = lookupAudioUri
        )
    }
}

private fun findOrCreateDeckId(api: AddContentApi, deckNameRaw: String): Long {
    val deckName = deckNameRaw.trim().ifBlank { "Default" }
    val deckList = runCatching { api.getDeckList() }.getOrElse { throwable ->
        error("Anki getDeckList failed. ${throwableDetail(throwable)}")
    }
    val existingDeckId = deckList
        ?.entries
        ?.firstOrNull { it.value.equals(deckName, ignoreCase = true) }
        ?.key
    if (existingDeckId != null) return existingDeckId

    return runCatching { api.addNewDeck(deckName) }.getOrElse { throwable ->
        error("Anki addNewDeck failed for '$deckName'. ${throwableDetail(throwable)}")
    }
}

private fun findModel(api: AddContentApi, modelName: String): AnkiModelTemplate? {
    val normalizedName = modelName.trim()
    if (normalizedName.isBlank()) return null

    val modelList = runCatching { api.getModelList(1) }.getOrElse { throwable ->
        error("Anki getModelList failed. ${throwableDetail(throwable)}")
    }
    val entry = modelList
        ?.entries
        ?.firstOrNull { it.value.equals(normalizedName, ignoreCase = true) }
        ?: return null

    val fields = (runCatching { api.getFieldList(entry.key) }.getOrElse { throwable ->
        error("Anki getFieldList failed for modelId=${entry.key}. ${throwableDetail(throwable)}")
    } ?: emptyArray())
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (fields.isEmpty()) return null

    return AnkiModelTemplate(
        id = entry.key,
        name = entry.value,
        fields = fields
    )
}

private fun throwableDetail(throwable: Throwable): String {
    val message = throwable.message?.trim().orEmpty()
    val frame = throwable.stackTrace.firstOrNull()?.let { top ->
        " @${top.className}.${top.methodName}(${top.fileName ?: "Unknown"}:${top.lineNumber})"
    }.orEmpty()
    return if (message.isBlank()) {
        "${throwable.javaClass.simpleName}$frame"
    } else {
        "${throwable.javaClass.simpleName}: $message$frame"
    }
}

private fun buildAnkiVariables(
    context: Context,
    api: AddContentApi,
    card: MinedCard,
    requiredMarkers: RequiredTemplateMarkers
): Map<String, String> {
    val glossarySources = if (requiredMarkers.needsAny(CORE_GLOSSARY_VARIABLES) ||
        requiredMarkers.needsAny(SINGLE_GLOSSARY_VARIABLES) ||
        requiredMarkers.singleGlossaryTokens.isNotEmpty()
    ) {
        buildMinedCardGlossarySources(card)
    } else {
        emptyList()
    }
    logDebug(ANKI_EXPORT_DEBUG_TAG) {
        "variables sources count=${glossarySources.size} dictionaryNameLengths=${glossarySources.joinToString(separator = "|") { "${it.dictionaryName.length}:${it.definitions.size}" }}"
    }
    val primaryGlossarySource = selectPrimaryGlossarySource(card, glossarySources)
    val dictionaryName = if (requiredMarkers.needs("dictionary-name", "dictionary", "dictionary-alias")) {
        primaryGlossarySource?.dictionaryName.orEmpty()
    } else {
        ""
    }
    val glossaryHtml = if (requiredMarkers.needsAny(CORE_GLOSSARY_VARIABLES)) {
        buildStyledGlossaryFromSources(glossarySources)
    } else {
        ""
    }
    val allDefinitions = if (requiredMarkers.needsAny(CORE_GLOSSARY_VARIABLES)) {
        glossarySources.flatMap { it.definitions }
    } else {
        emptyList()
    }
    val glossaryFirst = if (requiredMarkers.needs("glossary-first-brief", "glossary-brief")) {
        buildGlossaryBriefHtml(allDefinitions)
    } else {
        ""
    }
    val glossaryNoDictionary = if (requiredMarkers.needs("glossary-no-dictionary")) {
        buildGlossaryNoDictionaryHtml(allDefinitions)
    } else {
        ""
    }
    val glossaryPlain = if (requiredMarkers.needs("glossary-plain", "glossary-plain-no-dictionary")) {
        allDefinitions.joinToString("\n")
    } else {
        ""
    }
    val singleGlossaryHtml = if (requiredMarkers.needsAny(SINGLE_GLOSSARY_VARIABLES)) {
        primaryGlossarySource?.let { source ->
            buildStyledGlossary(
                definitions = source.definitions,
                dictionaryName = source.dictionaryName,
                dictionaryCss = source.dictionaryCss
            )
        }.orEmpty()
    } else {
        ""
    }
    val singleGlossaryFirst = if (requiredMarkers.needs("single-glossary-brief")) {
        buildGlossaryBriefHtml(primaryGlossarySource?.definitions.orEmpty())
    } else {
        ""
    }
    val singleGlossaryNoDictionary = if (requiredMarkers.needs("single-glossary-no-dictionary", "glossary-first-no-dictionary")) {
        buildGlossaryNoDictionaryHtml(primaryGlossarySource?.definitions.orEmpty())
    } else {
        ""
    }
    // glossary-first is the first glossary item of the primary dictionary,
    // but it still needs the dictionary-scoped styling wrapper.
    val styledGlossaryFirst = primaryGlossarySource?.let { source ->
        val firstItemHtml = buildGlossaryFirstItemHtml(source.definitions)
        if (firstItemHtml.isBlank()) {
            ""
        } else {
            buildStyledGlossary(
                definitions = listOf(firstItemHtml),
                dictionaryName = source.dictionaryName,
                dictionaryCss = source.dictionaryCss
            )
        }
    }.orEmpty()
    logDebug(ANKI_EXPORT_DEBUG_TAG) {
        "glossary templates primaryDictionaryLength=${primaryGlossarySource?.dictionaryName?.length ?: 0} " +
            "cssLen=${primaryGlossarySource?.dictionaryCss?.length ?: 0} " +
            "rawGlossaryFirstLen=${card.glossaryFirstHtml?.length ?: 0} " +
            "glossaryLen=${glossaryHtml.length} glossaryHasScope=${glossaryHtml.contains("data-dictionary=")} " +
            "glossaryFirstLen=${styledGlossaryFirst.length} glossaryFirstHasScope=${styledGlossaryFirst.contains("data-dictionary=")} " +
            "glossaryFirstBriefLen=${glossaryFirst.length} glossaryFirstBriefHasScope=${glossaryFirst.contains("data-dictionary=")} " +
            "singleGlossaryLen=${singleGlossaryHtml.length} singleGlossaryHasScope=${singleGlossaryHtml.contains("data-dictionary=")} " +
            "singleGlossaryNoDictLen=${singleGlossaryNoDictionary.length}"
    }
    val cutAudio = if (requiredMarkers.needs("cut-audio")) {
        attachAudio(api, context, card).orEmpty()
    } else {
        ""
    }
    val lookupAudio = if (requiredMarkers.needs("audio")) {
        attachLookupAudio(api, context, card.lookupAudioUri).orEmpty()
    } else {
        ""
    }
    val popupSelectionText = if (requiredMarkers.needs("popup-selection-text")) {
        card.popupSelectionText?.trim().orEmpty().ifBlank { card.word }
    } else {
        ""
    }
    val (clozePrefix, clozeBody, clozeSuffix) = if (requiredMarkers.needsAny(CLOZE_VARIABLES)) {
        val clozeTarget = (card.popupSelectionText?.trim().orEmpty()).ifBlank { card.word }
        splitCloze(card.sentence, clozeTarget)
    } else {
        Triple("", "", "")
    }
    val needsFrequency = requiredMarkers.needsAny(FREQUENCY_VARIABLES)
    val frequencyNumber = if (needsFrequency) extractFirstNumber(card.frequency) else ""
    val expressionFurigana = if (requiredMarkers.needsAny(FURIGANA_VARIABLES)) {
        buildExpressionFurigana(card.word, card.reading)
    } else {
        ""
    }
    val singleFrequency = if (needsFrequency) card.frequency.orEmpty() else ""
    val resolvedBookTitle = if (requiredMarkers.needs("book-title")) resolveBookTitle(context, card) else ""

    val variables = mutableMapOf(
        "expression" to card.word,
        "dictionary-name" to dictionaryName,
        "dictionary" to dictionaryName,
        "dictionary-alias" to dictionaryName,
        "popup-selection-text" to popupSelectionText.ifBlank { card.word },
        "search-query" to card.word,
        "sentence" to card.sentence,
        "cloze-prefix" to clozePrefix,
        "cloze-body" to clozeBody,
        "cloze-body-kana" to (card.reading ?: clozeBody),
        "cloze-suffix" to clozeSuffix,
        "reading" to card.reading.orEmpty(),
        "furigana" to expressionFurigana,
        "furigana-plain" to expressionFurigana,
        "expression-furigana" to expressionFurigana,
        "definitions" to glossaryHtml,
        "definition" to glossaryHtml,
        "glossary" to glossaryHtml,
        "glossary-no-dictionary" to glossaryNoDictionary,
        "glossary-first" to card.glossaryFirstHtml?.trim().orEmpty().ifBlank { styledGlossaryFirst },
        "glossary-first-brief" to glossaryFirst,
        "glossary-first-no-dictionary" to singleGlossaryNoDictionary,
        "single-glossary" to singleGlossaryHtml,
        "single-glossary-brief" to singleGlossaryFirst,
        "single-glossary-no-dictionary" to singleGlossaryNoDictionary,
        "glossary-brief" to glossaryFirst,
        "glossary-plain" to glossaryPlain,
        "glossary-plain-no-dictionary" to glossaryPlain,
        "dictionary-css" to card.dictionaryCss.orEmpty(),
        "pitch" to card.pitch.orEmpty(),
        "pitch-accents" to card.pitch.orEmpty(),
        "pitch-accent-positions" to card.pitch.orEmpty(),
        "pitch-accent-categories" to card.pitch.orEmpty(),
        "frequency" to card.frequency.orEmpty(),
        "frequencies" to card.frequency.orEmpty(),
        "single-frequency" to singleFrequency,
        "single-frequency-number" to frequencyNumber,
        "frequency-harmonic-rank" to frequencyNumber,
        "frequency-harmonic-occurrence" to frequencyNumber,
        "frequency-average-rank" to frequencyNumber,
        "frequency-average-occurrence" to frequencyNumber,
        "audio" to lookupAudio,
        "cut-audio" to cutAudio,
        "book-title" to resolvedBookTitle
    )
    if (requiredMarkers.needsAny(SINGLE_GLOSSARY_VARIABLES) || requiredMarkers.singleGlossaryTokens.isNotEmpty()) {
        glossarySources.forEach { source ->
        val normalizedName = normalizeDictionaryToken(source.dictionaryName)
        if (normalizedName.isBlank()) return@forEach
        if (requiredMarkers.needs("single-glossary") || requiredMarkers.singleGlossaryTokens.contains(normalizedName)) {
            variables[templateSingleGlossaryKey("single-glossary", normalizedName)] = buildStyledGlossary(
                definitions = source.definitions,
                dictionaryName = source.dictionaryName,
                dictionaryCss = source.dictionaryCss
            )
        }
        if (requiredMarkers.needs("single-glossary-brief") || requiredMarkers.singleGlossaryTokens.contains(normalizedName)) {
            variables[templateSingleGlossaryKey("single-glossary-brief", normalizedName)] =
                buildGlossaryBriefHtml(source.definitions)
        }
        if (requiredMarkers.needs("single-glossary-no-dictionary") || requiredMarkers.singleGlossaryTokens.contains(normalizedName)) {
            variables[templateSingleGlossaryKey("single-glossary-no-dictionary", normalizedName)] =
                buildGlossaryNoDictionaryHtml(source.definitions)
        }
    }
    }
    logDebug(ANKI_EXPORT_DEBUG_TAG) {
        "variables done primaryDictionaryLength=${primaryGlossarySource?.dictionaryName?.length ?: 0} " +
            "dynamicSingleKeys=${variables.keys.count { it.startsWith("__single-glossary::") }}"
    }
    return variables
}

private data class RequiredTemplateMarkers(
    val keys: Set<String>,
    val canonicalKeys: Set<String>,
    val singleGlossaryTokens: Set<String>
) {
    fun needsAny(names: Set<String>): Boolean = names.any { needs(it) }
    fun needs(vararg names: String): Boolean = names.any { name ->
        keys.contains(name) || canonicalKeys.contains(canonicalizeTemplateKey(name))
    }
}

private data class SingleGlossaryMarkerRequest(
    val dictionaryToken: String,
    val markerKey: String
)

private fun extractRequiredTemplateMarkers(templates: Collection<String>): RequiredTemplateMarkers {
    val normalizedKeys = linkedSetOf<String>()
    val canonicalKeys = linkedSetOf<String>()
    val singleGlossaryTokens = linkedSetOf<String>()
    templates.forEach { template ->
        TEMPLATE_VARIABLE_REGEX.findAll(template).forEach { match ->
            val raw = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (raw.isBlank()) return@forEach
            normalizedKeys += raw.lowercase(Locale.ROOT)
            canonicalKeys += canonicalizeTemplateKey(raw)
        }
        SINGLE_GLOSSARY_DICT_MARKER_REGEX.findAll(template).forEach { match ->
            parseSingleGlossaryMarker(match.groupValues.getOrNull(1).orEmpty())?.let { request ->
                singleGlossaryTokens += request.dictionaryToken
                normalizedKeys += request.markerKey
                canonicalKeys += canonicalizeTemplateKey(request.markerKey)
            }
        }
    }
    return RequiredTemplateMarkers(
        keys = normalizedKeys,
        canonicalKeys = canonicalKeys,
        singleGlossaryTokens = singleGlossaryTokens
    )
}

private fun parseSingleGlossaryMarker(rawMarker: String): SingleGlossaryMarkerRequest? {
    val marker = rawMarker.trim()
    if (marker.isBlank()) return null
    val (requestedNameRaw, markerKey) = when {
        marker.endsWith("-brief", ignoreCase = true) -> {
            marker.dropLast("-brief".length).trimEnd() to "single-glossary-brief"
        }

        marker.endsWith("-no-dictionary", ignoreCase = true) -> {
            marker.dropLast("-no-dictionary".length).trimEnd() to "single-glossary-no-dictionary"
        }

        else -> marker to "single-glossary"
    }
    val dictionaryToken = normalizeDictionaryToken(requestedNameRaw)
    if (dictionaryToken.isBlank()) return null
    return SingleGlossaryMarkerRequest(dictionaryToken, markerKey)
}

private fun rewriteHtmlForAnkiExport(
    context: Context,
    api: AddContentApi,
    html: String,
    sourceLabel: String,
    mediaSrcCache: MutableMap<String, String>,
    dictionaryMediaByFilename: Map<String, MinedDictionaryMedia>
): String {
    if (html.isBlank()) return html
    if (!html.contains("<img", ignoreCase = true) && !html.contains("<link", ignoreCase = true)) {
        return html
    }

    val cssChunks = mutableListOf<String>()
    val cssSet = linkedSetOf<String>()
    var output = ANKI_STYLE_TAG_REGEX.replace(html) { match ->
        val css = match.groupValues.getOrNull(1).orEmpty().trim()
        if (css.isNotBlank() && cssSet.add(css)) {
            cssChunks += css
        }
        ""
    }
    output = ANKI_LINK_TAG_REGEX.replace(output) { match ->
        val tag = match.value
        val rel = findHtmlAttributeValue(tag, "rel")?.lowercase(Locale.ROOT).orEmpty()
        if (!rel.contains("stylesheet")) return@replace tag
        val hrefRaw = findHtmlAttributeValue(tag, "href").orEmpty()
        val hrefUri = resolveAnkiHtmlResourceUri(hrefRaw)
        if (hrefUri == null) return@replace tag
        val cssText = runCatching {
            openInputStreamForUri(context, hrefUri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull()
        if (cssText.isNullOrBlank()) return@replace tag
        val normalizedCss = cssText.trim()
        if (normalizedCss.isNotBlank() && cssSet.add(normalizedCss)) {
            cssChunks += normalizedCss
        }
        ""
    }
    if (cssChunks.isNotEmpty()) {
        output = "<style>${cssChunks.joinToString("\n")}</style>$output"
    }

    var imageIndex = 0
    output = ANKI_IMG_TAG_REGEX.replace(output) { match ->
        var tag = match.value
        var replaced = false
        tag = ANKI_SRC_ATTR_QUOTED_REGEX.replace(tag) { attrMatch ->
            val quote = attrMatch.groupValues[1]
            val rawSrc = attrMatch.groupValues[2]
            val rewritten = rewriteAnkiImageSrc(
                context = context,
                api = api,
                rawSrc = rawSrc,
                sourceLabel = sourceLabel,
                imageIndex = imageIndex,
                mediaSrcCache = mediaSrcCache,
                dictionaryMediaByFilename = dictionaryMediaByFilename
            )
            replaced = true
            imageIndex += 1
            "src=$quote${escapeHtmlAttributeAnki(rewritten)}$quote"
        }
        if (!replaced) {
            tag = ANKI_SRC_ATTR_UNQUOTED_REGEX.replace(tag) { attrMatch ->
                val rawSrc = attrMatch.groupValues[1]
                val rewritten = rewriteAnkiImageSrc(
                    context = context,
                    api = api,
                    rawSrc = rawSrc,
                    sourceLabel = sourceLabel,
                    imageIndex = imageIndex,
                    mediaSrcCache = mediaSrcCache,
                    dictionaryMediaByFilename = dictionaryMediaByFilename
                )
                imageIndex += 1
                "src=\"${escapeHtmlAttributeAnki(rewritten)}\""
            }
        }
        tag
    }
    return output
}

private fun rewriteAnkiImageSrc(
    context: Context,
    api: AddContentApi,
    rawSrc: String,
    sourceLabel: String,
    imageIndex: Int,
    mediaSrcCache: MutableMap<String, String>,
    dictionaryMediaByFilename: Map<String, MinedDictionaryMedia>
): String {
    val src = rawSrc.trim().trim('"', '\'')
    if (src.isBlank()) return rawSrc
    if (src.startsWith("#")) return rawSrc
    if (src.startsWith("//")) return rawSrc
    if (src.startsWith("data:", ignoreCase = true)) return rawSrc
    if (src.startsWith("http://", ignoreCase = true) || src.startsWith("https://", ignoreCase = true)) return rawSrc

    mediaSrcCache[src]?.let { return it }

    dictionaryMediaByFilename[src]?.let { media ->
        val resolvedSrc = addDictionaryMediaAsImageSrc(
            api = api,
            context = context,
            media = media
        ) ?: rawSrc
        mediaSrcCache[src] = resolvedSrc
        return resolvedSrc
    }

    val uri = resolveAnkiHtmlResourceUri(src) ?: return rawSrc
    val preferredName = buildPreferredImageMediaName(context, uri, sourceLabel, imageIndex)
    val resolvedSrc = addMediaAsImageSrc(
        api = api,
        context = context,
        sourceUri = uri,
        preferredName = preferredName
    ) ?: rawSrc
    mediaSrcCache[src] = resolvedSrc
    return resolvedSrc
}

private fun addMediaAsImageSrc(
    api: AddContentApi,
    context: Context,
    sourceUri: Uri,
    preferredName: String
): String? = addMediaAsImageInput(
    api = api,
    context = context,
    preferredName = preferredName
) {
    openInputStreamForUri(context, sourceUri)
}

private fun addDictionaryMediaAsImageSrc(
    api: AddContentApi,
    context: Context,
    media: MinedDictionaryMedia
): String? {
    val requestUri = Uri.Builder()
        .scheme("https")
        .authority("hoshi.local")
        .path("/image")
        .appendQueryParameter("dictionary", media.dictionary)
        .appendQueryParameter("path", media.path)
        .build()
    val payload = runCatching { loadDictionaryMediaPayload(context, requestUri) }.getOrNull() ?: return null
    val extension = media.filename.substringAfterLast('.', "png")
        .lowercase(Locale.ROOT)
        .takeIf { it.length in 1..8 && it.all { character -> character.isLetterOrDigit() } }
        ?: "png"
    return addMediaAsImageInput(
        api = api,
        context = context,
        preferredName = "hoshi_dict_${payload.bytes.contentHashCode()}.$extension"
    ) {
        java.io.ByteArrayInputStream(payload.bytes)
    }
}

private fun addMediaAsImageInput(
    api: AddContentApi,
    context: Context,
    preferredName: String,
    openInputStream: () -> InputStream?
): String? {
    val extension = preferredName.substringAfterLast('.', "png")
    val temp = createAnkiMediaTempFile(context, prefix = "anki-img", extension = extension)
    return try {
        openInputStream()?.use { input ->
            temp.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null
        if (temp.length() <= 0L) return null

        fun callAddMedia(uri: Uri, grantPermission: Boolean): String? {
            if (grantPermission && uri.scheme.equals("content", ignoreCase = true)) {
                runCatching {
                    context.grantUriPermission(
                        requireAnkiPackageName(context),
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            return try {
                val mediaTag = runCatching {
                    api.addMediaFromUri(uri, preferredName, "image")
                }.getOrNull().orEmpty()
                parseImageSrcFromAnkiTag(mediaTag).ifBlank { preferredName }
            } finally {
                if (grantPermission && uri.scheme.equals("content", ignoreCase = true)) {
                    runCatching { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
            }
        }

        val providerUri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", temp)
        }.getOrNull()
        val fromProvider = providerUri?.let { callAddMedia(it, grantPermission = true) }
        if (!fromProvider.isNullOrBlank()) return fromProvider
        null
    } catch (_: Exception) {
        null
    } finally {
        runCatching { temp.delete() }
    }
}

private fun parseImageSrcFromAnkiTag(tag: String): String {
    if (tag.isBlank()) return ""
    val matched = ANKI_IMG_SRC_IN_TAG_REGEX.find(tag)?.groupValues?.getOrNull(2).orEmpty().trim()
    return if (matched.isNotBlank()) matched else ""
}

private fun buildPreferredImageMediaName(
    context: Context,
    uri: Uri,
    sourceLabel: String,
    imageIndex: Int
): String {
    val ext = resolveImageExtension(context, uri, fallback = "png")
    val safeLabel = sourceLabel
        .lowercase(Locale.ROOT)
        .replace(ANKI_MEDIA_SAFE_LABEL_REGEX, "-")
        .trim('-')
        .ifBlank { "glossary" }
    return "$safeLabel-${System.currentTimeMillis()}-$imageIndex.$ext"
}

private fun resolveImageExtension(
    context: Context,
    uri: Uri,
    fallback: String
): String {
    val fromPath = uri.lastPathSegment
        ?.substringAfterLast('.', "")
        ?.trim()
        ?.trimStart('.')
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    if (fromPath.isNotBlank()) return fromPath

    val fromMime = runCatching { context.contentResolver.getType(uri) }
        .getOrNull()
        ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        ?.trim()
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    if (fromMime.isNotBlank()) return fromMime
    return fallback.lowercase(Locale.ROOT)
}

private fun resolveAnkiHtmlResourceUri(raw: String): Uri? {
    val src = raw.trim().trim('"', '\'')
    if (src.isBlank()) return null
    if (src.startsWith("#")) return null
    if (src.startsWith("//")) return null
    if (src.startsWith("data:", ignoreCase = true)) return null
    val uri = if (ANKI_URI_SCHEME_REGEX.containsMatchIn(src)) {
        runCatching { Uri.parse(src) }.getOrNull()
    } else {
        runCatching {
            val asFile = File(src)
            if (asFile.isAbsolute) Uri.fromFile(asFile) else null
        }.getOrNull()
    } ?: return null

    val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
    if (scheme == "http" || scheme == "https") {
        return null
    }
    return uri
}

private fun findHtmlAttributeValue(tag: String, attribute: String): String? {
    val quoted = when (attribute.lowercase(Locale.ROOT)) {
        "rel" -> ANKI_REL_ATTR_QUOTED_REGEX
        "href" -> ANKI_HREF_ATTR_QUOTED_REGEX
        "src" -> ANKI_SRC_ATTR_QUOTED_REGEX
        else -> return null
    }
    quoted.find(tag)?.let { return it.groupValues.getOrNull(2) }
    val unquoted = when (attribute.lowercase(Locale.ROOT)) {
        "rel" -> ANKI_REL_ATTR_UNQUOTED_REGEX
        "href" -> ANKI_HREF_ATTR_UNQUOTED_REGEX
        "src" -> ANKI_SRC_ATTR_UNQUOTED_REGEX
        else -> return null
    }
    return unquoted.find(tag)?.groupValues?.getOrNull(1)
}

private fun escapeHtmlAttributeAnki(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private data class MinedCardGlossarySource(
    val dictionaryName: String,
    val definitions: List<String>,
    val dictionaryCss: String?
)

private fun buildMinedCardGlossarySources(card: MinedCard): List<MinedCardGlossarySource> {
    val mapped = card.glossaryByDictionary
        .mapNotNull { dictionaryGlossary ->
            val dictionaryName = dictionaryGlossary.dictionaryName.trim()
            val definitions = dictionaryGlossary.definitions
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (dictionaryName.isBlank() || definitions.isEmpty()) {
                null
            } else {
                MinedCardGlossarySource(
                    dictionaryName = dictionaryName,
                    definitions = definitions,
                    dictionaryCss = dictionaryGlossary.dictionaryCss
                )
            }
        }
    if (mapped.isNotEmpty()) {
        logDebug(ANKI_EXPORT_DEBUG_TAG) {
            "sources using glossaryByDictionary count=${mapped.size} " +
                "dictionaryNameLengths=${mapped.joinToString("|") { it.dictionaryName.length.toString() }} " +
                "cssLens=${mapped.joinToString("|") { it.dictionaryCss?.length?.toString().orEmpty() }} " +
                "defs=${mapped.joinToString("|") { it.definitions.size.toString() }}"
        }
        return mapped
    }
    val fallbackDefinitions = card.definitions
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (fallbackDefinitions.isEmpty()) return emptyList()
    val fallback = listOf(
        MinedCardGlossarySource(
            dictionaryName = card.dictionaryName.orEmpty().ifBlank { "Dictionary" },
            definitions = fallbackDefinitions,
            dictionaryCss = card.dictionaryCss
        )
    )
    logDebug(ANKI_EXPORT_DEBUG_TAG) {
        "sources fallback dictionaryNameLength=${fallback.first().dictionaryName.length} defs=${fallbackDefinitions.size} " +
            "cssLen=${fallback.first().dictionaryCss?.length ?: 0}"
    }
    return fallback
}

private fun selectPrimaryGlossarySource(
    card: MinedCard,
    sources: List<MinedCardGlossarySource>
): MinedCardGlossarySource? {
    if (sources.isEmpty()) return null
    val preferred = normalizeDictionaryToken(card.dictionaryName.orEmpty())
    val selected = if (preferred.isBlank()) {
        sources.firstOrNull()
    } else {
        sources.firstOrNull { normalizeDictionaryToken(it.dictionaryName) == preferred }
        ?: sources.firstOrNull()
    }
    logDebug(ANKI_EXPORT_DEBUG_TAG) {
        "primary source selected preferredLength=${preferred.length} " +
            "selectedNameLength=${selected?.dictionaryName?.length ?: 0} " +
            "selectedCssLen=${selected?.dictionaryCss?.length ?: 0} " +
            "selectedDefs=${selected?.definitions?.size ?: 0} " +
            "sourceCount=${sources.size}"
    }
    return selected
}

private fun buildStyledGlossaryFromSources(sources: List<MinedCardGlossarySource>): String {
    if (sources.isEmpty()) return ""
    logDebug(ANKI_EXPORT_DEBUG_TAG) {
        "buildStyledGlossaryFromSources count=${sources.size} " +
            "dictionaryNameLengths=${sources.joinToString("|") { it.dictionaryName.length.toString() }} " +
            "cssLens=${sources.joinToString("|") { it.dictionaryCss?.length?.toString().orEmpty() }}"
    }
    return renderYomitanGlossaryHtml(
        items = sources.map { source ->
            GlossaryHtmlItem(
                dictionaryName = source.dictionaryName,
                definitions = source.definitions.map(::sanitizeAnkiDefinitionHtml),
                dictionaryCss = source.dictionaryCss
            )
        }
    )
}

private fun resolveBookTitle(context: Context, card: MinedCard): String {
    val audioName = resolveAudioDisplayName(context, card.audioUri)
    val preferred = audioName?.trim().orEmpty().ifBlank { card.bookTitle.orEmpty().trim() }
    if (preferred.isBlank()) return ""
    return preferred.substringBeforeLast('.', preferred)
}

private fun resolveAudioDisplayName(context: Context, uri: Uri?): String? {
    val target = uri ?: return null
    if (target.scheme.equals("file", ignoreCase = true)) {
        return File(target.path.orEmpty()).name.takeIf { it.isNotBlank() }
    }
    val fromQuery = runCatching {
        context.contentResolver.query(
            target,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)
            } else {
                null
            }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
    return fromQuery ?: target.lastPathSegment?.substringAfterLast('/')
}

private fun buildStyledGlossary(
    definitions: List<String>,
    dictionaryName: String?,
    dictionaryCss: String?
): String {
    logDebug(ANKI_EXPORT_DEBUG_TAG) {
        "buildStyledGlossary dictionaryNameLength=${dictionaryName?.length ?: 0} defs=${definitions.size} " +
            "cssLen=${dictionaryCss?.length ?: 0}"
    }
    return renderYomitanGlossaryHtml(
        items = listOf(
            GlossaryHtmlItem(
                dictionaryName = dictionaryName.orEmpty(),
                definitions = definitions.map(::sanitizeAnkiDefinitionHtml),
                dictionaryCss = dictionaryCss
            )
        )
    )
}

internal fun buildGlossaryFirstItemHtml(definitions: List<String>): String {
    val firstDefinition = definitions.firstOrNull()?.trim().orEmpty()
    if (firstDefinition.isBlank()) return ""
    val extracted = extractFirstGlossaryListItemHtml(firstDefinition) ?: firstDefinition
    return removeLeadingGlossaryIndex(extractInnerListItemHtml(extracted))
}

internal fun buildGlossaryBriefHtml(definitions: List<String>): String {
    return definitions.firstOrNull()
        ?.let { buildGlossaryFirstItemHtml(listOf(it)) }
        .orEmpty()
}

internal fun buildGlossaryNoDictionaryHtml(definitions: List<String>): String {
    return definitions.joinToString("<br>") { definition ->
        removeLeadingGlossaryDictionaryLabel(buildGlossaryFirstItemHtml(listOf(definition)))
    }
}

private fun sanitizeAnkiDefinitionHtml(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    return removeLeadingGlossaryIndex(trimmed)
}

private fun removeLeadingGlossaryIndex(html: String): String {
    return html.replace(ANKI_LEADING_GLOSSARY_INDEX_REGEX, "$1")
}

private fun removeLeadingGlossaryDictionaryLabel(html: String): String {
    return html.replaceFirst(ANKI_LEADING_DICTIONARY_LABEL_REGEX, "")
}

private fun extractFirstGlossaryListItemHtml(raw: String): String? {
    val startSearchIndex = raw.indexOf("<ol", ignoreCase = true).let { olIndex ->
        if (olIndex >= 0) {
            val olClose = raw.indexOf('>', olIndex)
            if (olClose >= 0) olClose + 1 else 0
        } else {
            0
        }
    }
    var match: MatchResult? = ANKI_LI_TAG_REGEX.find(raw, startSearchIndex) ?: return null
    var depth = 0
    var startIndex = -1
    while (match != null) {
        val tagStart = match.range.first
        val tagText = match.value
        val tagEnd = raw.indexOf('>', match.range.last + 1)
        if (tagEnd < 0) return null
        val isClosing = tagText.startsWith("</", ignoreCase = true)
        if (!isClosing) {
            if (depth == 0) {
                startIndex = tagStart
            }
            depth++
        } else if (depth > 0) {
            depth--
            if (depth == 0 && startIndex >= 0) {
                return raw.substring(startIndex, tagEnd + 1)
            }
        }
        match = ANKI_LI_TAG_REGEX.find(raw, tagEnd + 1)
    }
    return null
}

internal fun classifyAnkiExportFailure(
    context: Context,
    error: Throwable
): AnkiExportResult {
    val message = error.message?.trim().orEmpty()
    return when {
        message == context.getString(R.string.error_anki_not_installed) ||
            message.contains("AnkiDroid is not installed", ignoreCase = true) -> {
            AnkiExportResult.NotAvailable(AnkiAvailabilityState.NOT_INSTALLED, context.getString(R.string.error_anki_not_installed))
        }
        message == context.getString(R.string.error_anki_api_unavailable) ||
            message.contains("API is unavailable", ignoreCase = true) -> {
            AnkiExportResult.NotAvailable(AnkiAvailabilityState.API_UNAVAILABLE, context.getString(R.string.error_anki_api_unavailable))
        }
        message == context.getString(R.string.error_anki_permission_required) ||
            (message.contains("permission", ignoreCase = true) && message.contains("Anki", ignoreCase = true)) -> {
            AnkiExportResult.NotAvailable(AnkiAvailabilityState.PERMISSION_MISSING, context.getString(R.string.error_anki_permission_required))
        }
        message == context.getString(R.string.error_anki_model_missing) ||
            message == context.getString(R.string.error_anki_fields_empty) ||
            message == context.getString(R.string.error_anki_lookup_audio_missing) ||
            message == context.getString(R.string.error_anki_cut_audio_missing) ||
            message.startsWith("No Anki model configured", ignoreCase = true) ||
            message.startsWith("Configured model not found", ignoreCase = true) ||
            message.startsWith("All field variables are empty", ignoreCase = true) ||
            message.startsWith("All rendered field values are empty", ignoreCase = true) -> {
            AnkiExportResult.InvalidConfig(if (message.isBlank()) formatAnkiExportThrowable(error) else message)
        }
        else -> AnkiExportResult.Failed(formatAnkiExportThrowable(error), error)
    }
}

internal fun ankiExportResultMessage(
    context: Context,
    result: AnkiExportResult
): String {
    return when (result) {
        AnkiExportResult.Added -> context.getString(R.string.anki_toast_added)
        is AnkiExportResult.DuplicateSkipped -> result.message
        is AnkiExportResult.NotAvailable -> result.message
        is AnkiExportResult.InvalidConfig -> result.message
        is AnkiExportResult.Failed -> result.message
    }
}

private fun formatAnkiExportThrowable(error: Throwable): String {
    val message = error.message?.trim().orEmpty()
    return if (message.isBlank()) {
        error.javaClass.simpleName
    } else {
        message
    }
}

private fun attachAudio(
    api: AddContentApi,
    context: Context,
    card: MinedCard
): String? {
    val sourceUri = card.audioUri ?: return null
    val preferredName = "tset-${System.currentTimeMillis()}"
    val failures = mutableListOf<String>()
    failures += "source-scheme=${sourceUri.scheme.orEmpty()}"

    fun attemptWithUri(
        label: String,
        uri: Uri,
        grantReadPermission: Boolean
    ): String? {
        logDebug(ANKI_AUDIO_LOG_TAG) {
            "audio-attempt label=$label uri=$uri scheme=${uri.scheme.orEmpty()} last=${uri.lastPathSegment.orEmpty()} grant=$grantReadPermission"
        }
        if (grantReadPermission && uri.scheme.equals("content", ignoreCase = true)) {
            runCatching {
                context.grantUriPermission(
                    requireAnkiPackageName(context),
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.onFailure {
                failures += "$label grant-failed(${uri.scheme.orEmpty()}): ${it.message ?: it.javaClass.simpleName}"
            }
        }
        return try {
            addMediaAsAudioTag(
                api = api,
                uri = uri,
                preferredName = preferredName
            ) { reason ->
                failures += "$label $reason"
            }
        } finally {
            if (grantReadPermission && uri.scheme.equals("content", ignoreCase = true)) {
                runCatching {
                    context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
    }

    val clipFile = createCueAudioClip(
        context = context,
        sourceUri = sourceUri,
        cueStartMs = card.cueStartMs,
        cueEndMs = card.cueEndMs
    ) { reason ->
        failures += "clip $reason"
    }
    try {
        if (clipFile != null) {
            val providerUri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", clipFile)
            }.onFailure {
                failures += "clip fileprovider-uri-failed: ${it.message ?: it.javaClass.simpleName}"
            }.getOrNull()

            if (providerUri == null) {
                failures += "clip-fileprovider unavailable"
            } else {
                attemptWithUri(
                    label = "clip-fileprovider",
                    uri = providerUri,
                    grantReadPermission = true
                )?.let { return it }
            }
        } else {
            failures += "clip-not-created"
        }

        val detail = failures.distinct().joinToString(" | ").take(900)
        if (card.requireCueAudioClip) {
            error("Failed to attach cue audio clip to Anki media. $detail")
        }
        logDebug(ANKI_AUDIO_LOG_TAG) { "audio clip not attached. $detail" }
        return null
    } finally {
        clipFile?.delete()
    }
}

private fun attachLookupAudio(
    api: AddContentApi,
    context: Context,
    lookupAudioUri: Uri?
): String? {
    val sourceUri = lookupAudioUri ?: return null
    val preferredName = "lookup-${System.currentTimeMillis()}"
    val failures = mutableListOf<String>()
    failures += "source-scheme=${sourceUri.scheme.orEmpty()}"

    fun attemptWithUri(
        label: String,
        uri: Uri,
        grantReadPermission: Boolean
    ): String? {
        logDebug(ANKI_AUDIO_LOG_TAG) {
            "lookup-attempt label=$label uri=$uri scheme=${uri.scheme.orEmpty()} last=${uri.lastPathSegment.orEmpty()} grant=$grantReadPermission"
        }
        if (grantReadPermission && uri.scheme.equals("content", ignoreCase = true)) {
            runCatching {
                context.grantUriPermission(
                    requireAnkiPackageName(context),
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.onFailure {
                failures += "$label grant-failed(${uri.scheme.orEmpty()}): ${it.message ?: it.javaClass.simpleName}"
            }
        }
        return try {
            addMediaAsAudioTag(
                api = api,
                uri = uri,
                preferredName = preferredName
            ) { reason ->
                failures += "$label $reason"
            }
        } finally {
            if (grantReadPermission && uri.scheme.equals("content", ignoreCase = true)) {
                runCatching {
                    context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
    }

    val transcodedLookup = transcodeAudioToM4a(
        context = context,
        sourceUri = sourceUri,
        prefix = "lookup-tx"
    ) { reason ->
        failures += "lookup-transcode $reason"
    }
    try {
        if (transcodedLookup == null) {
            val detail = failures.distinct().joinToString(" | ").take(900)
            error("Failed to prepare lookup audio for Anki media. $detail")
        }
        val transcodedProviderUri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", transcodedLookup)
        }.onFailure {
            failures += "lookup transcoded fileprovider-uri-failed: ${it.message ?: it.javaClass.simpleName}"
        }.getOrNull()
        if (transcodedProviderUri == null) {
            val detail = failures.distinct().joinToString(" | ").take(900)
            error("Failed to expose lookup audio to Anki media. $detail")
        }
        attemptWithUri(
            label = "lookup-transcoded-fileprovider",
            uri = transcodedProviderUri,
            grantReadPermission = true
        )?.let { return it }
        val detail = failures.distinct().joinToString(" | ").take(900)
        error("Failed to attach lookup audio to Anki media. $detail")
    } finally {
        runCatching { transcodedLookup?.delete() }
    }
}

private fun addMediaAsAudioTag(
    api: AddContentApi,
    uri: Uri,
    preferredName: String,
    onAttemptFailure: (String) -> Unit = {}
): String? {
    val resolvedName = buildPreferredAudioMediaName(preferredName, uri)
    logDebug(ANKI_AUDIO_LOG_TAG) {
        "anki-addmedia-attempt uri=$uri scheme=${uri.scheme.orEmpty()} last=${uri.lastPathSegment.orEmpty()} name=$resolvedName"
    }
    val mediaTag = runCatching {
        // Per Anki API contract, mediaType must be "audio" or "image".
        api.addMediaFromUri(uri, resolvedName, "audio")
    }.onFailure {
        onAttemptFailure("exception=${it.message ?: it.javaClass.simpleName}")
    }.getOrNull()
    if (!mediaTag.isNullOrBlank()) {
        logDebug(ANKI_AUDIO_LOG_TAG) {
            "anki-addmedia-success name=$resolvedName tag=$mediaTag"
        }
        return mediaTag
    }
    logDebug(ANKI_AUDIO_LOG_TAG) {
        "anki-addmedia-null name=$resolvedName uri=$uri"
    }
    onAttemptFailure("returned-null")
    return null
}

private fun buildPreferredAudioMediaName(preferredName: String, uri: Uri): String {
    val currentExt = preferredName.substringAfterLast('.', "")
    if (currentExt.isNotBlank()) return preferredName
    val uriExt = uri.lastPathSegment
        ?.substringAfterLast('.', "")
        ?.trim()
        ?.trimStart('.')
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    return if (uriExt.isNotBlank()) "$preferredName.$uriExt" else preferredName
}

private fun openInputStreamForUri(context: Context, uri: Uri): InputStream? {
    return when (uri.scheme?.lowercase(Locale.ROOT)) {
        "dictres" -> runCatching {
            loadDictionaryMediaPayload(context, uri)?.let { payload ->
                java.io.ByteArrayInputStream(payload.bytes)
            }
        }.getOrNull()

        "file" -> runCatching {
            val path = uri.path ?: return@runCatching null
            File(path).inputStream()
        }.getOrNull()

        else -> {
            val direct = runCatching {
                context.contentResolver.openInputStream(uri)
            }.getOrNull()
            if (direct != null) return direct

            val pfd = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")
            }.getOrNull() ?: return null
            ParcelFileDescriptor.AutoCloseInputStream(pfd)
        }
    }
}

private fun createCueAudioClip(
    context: Context,
    sourceUri: Uri,
    cueStartMs: Long,
    cueEndMs: Long,
    onFailure: (String) -> Unit = {}
): File? {
    if (cueEndMs <= cueStartMs) return null
    val clipByRemux = createCueAudioClipByRemux(
        context = context,
        sourceUri = sourceUri,
        cueStartMs = cueStartMs,
        cueEndMs = cueEndMs,
        onFailure = onFailure
    )
    if (clipByRemux != null) return clipByRemux

    val clipByTransformer = createCueAudioClipByTransformer(
        context = context,
        sourceUri = sourceUri,
        cueStartMs = cueStartMs,
        cueEndMs = cueEndMs,
        onFailure = onFailure
    )
    if (clipByTransformer != null) return clipByTransformer

    onFailure("all-clip-methods-failed")
    return null
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun createCueAudioClipByRemux(
    context: Context,
    sourceUri: Uri,
    cueStartMs: Long,
    cueEndMs: Long,
    onFailure: (String) -> Unit
): File? {
    val startUs = cueStartMs.coerceAtLeast(0L) * 1000L
    val endUs = cueEndMs.coerceAtLeast(cueStartMs + 1L) * 1000L
    if (endUs - startUs < 50_000L) {
        onFailure("window-too-short")
        return null
    }

    val outputFile = createAnkiMediaTempFile(context, prefix = "cue", extension = "m4a")
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    return try {
        val contextDataSourceSet = runCatching {
            extractor.setDataSource(context, sourceUri, null)
        }.onFailure {
            onFailure("datasource-context-failed=${it.javaClass.simpleName}")
        }.isSuccess
        if (!contextDataSourceSet) {
            val path = sourceUri.path
            if (path.isNullOrBlank()) {
                onFailure("datasource-file-path-missing")
                outputFile.delete()
                return null
            }
            val pathDataSourceSet = runCatching {
                extractor.setDataSource(path)
            }.onFailure {
                onFailure("datasource-path-failed=${it.javaClass.simpleName}")
            }.isSuccess
            if (!pathDataSourceSet) {
                outputFile.delete()
                return null
            }
        }

        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            mime.startsWith("audio/")
        } ?: run {
            onFailure("no-audio-track")
            outputFile.delete()
            return null
        }

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val trackMime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        val maxInputSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
        } else {
            256 * 1024
        }

        if (trackMime.equals("audio/mpeg", ignoreCase = true)) {
            outputFile.delete()
            val mp3ClipFile = createAnkiMediaTempFile(context, prefix = "cue", extension = "mp3")
            return createCueMp3ClipBySampleCopy(
                extractor = extractor,
                outputFile = mp3ClipFile,
                maxInputSize = maxInputSize,
                startUs = startUs,
                endUs = endUs,
                onFailure = onFailure
            )
        }

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outputTrack = runCatching { muxer.addTrack(format) }.onFailure {
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            onFailure("add-track-failed mime=$mime error=${it.javaClass.simpleName}")
        }.getOrNull() ?: run {
            runCatching { muxer.release() }
            outputFile.delete()
            return null
        }
        muxer.start()

        val buffer = ByteBuffer.allocateDirect(maxInputSize)
        val bufferInfo = MediaCodec.BufferInfo()
        var wroteAnySample = false

        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        while (true) {
            bufferInfo.offset = 0
            bufferInfo.size = extractor.readSampleData(buffer, 0)
            if (bufferInfo.size <= 0) break

            val sampleTimeUs = extractor.sampleTime
            if (sampleTimeUs < 0L) break
            if (sampleTimeUs < startUs) {
                if (!extractor.advance()) break
                continue
            }
            if (sampleTimeUs > endUs) break

            bufferInfo.presentationTimeUs = sampleTimeUs - startUs
            val sampleFlags = extractor.sampleFlags
            var codecFlags = 0
            if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
            }
            if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
            }
            bufferInfo.flags = codecFlags
            muxer.writeSampleData(outputTrack, buffer, bufferInfo)
            wroteAnySample = true

            if (!extractor.advance()) break
        }

        if (!wroteAnySample) {
            onFailure("no-sample-in-window")
            runCatching { muxer.stop() }
            outputFile.delete()
            return null
        }

        muxer.stop()
        if (outputFile.length() <= 0L) {
            outputFile.delete()
            null
        } else {
            outputFile
        }
    } catch (e: Exception) {
        onFailure("remux-exception=${e.javaClass.simpleName}")
        outputFile.delete()
        null
    } finally {
        runCatching { muxer?.release() }
        runCatching { extractor.release() }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun transcodeAudioToM4a(
    context: Context,
    sourceUri: Uri,
    prefix: String,
    onFailure: (String) -> Unit
): File? {
    return try {
        val outputFile = createAnkiMediaTempFile(context, prefix = prefix, extension = "m4a")
        val completion = TransformerCompletion()

        val mediaItem = MediaItem.fromUri(sourceUri)
        val editedItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .build()
        val transformer = Transformer.Builder(context)
            .setAudioMimeType("audio/mp4a-latm")
            .setListener(completion.listener)
            .build()

        val started = runCatching {
            transformer.start(editedItem, outputFile.absolutePath)
        }.onFailure {
            onFailure("start-failed=${it.javaClass.simpleName}")
        }.isSuccess
        if (!started) {
            outputFile.delete()
            return null
        }

        val finished = runCatching {
            completion.await(120)
        }.getOrElse {
            onFailure("await-failed=${it.javaClass.simpleName}")
            false
        }
        if (!finished) {
            transformer.cancel()
            onFailure("timeout")
            outputFile.delete()
            return null
        }
        if (!completion.succeeded()) {
            onFailure("error=${completion.errorDetail()}")
            outputFile.delete()
            return null
        }
        if (outputFile.length() <= 0L) {
            onFailure("empty-output")
            outputFile.delete()
            return null
        }
        outputFile
    } catch (e: Exception) {
        onFailure("exception=${e.javaClass.simpleName}")
        null
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class TransformerCompletion {
    private val done = AtomicBoolean(false)
    private val success = AtomicBoolean(false)
    private val latch = CountDownLatch(1)
    private var error: String? = null

    val listener = object : Transformer.Listener {
        override fun onCompleted(composition: Composition, exportResult: ExportResult) = finishSuccess()

        override fun onError(
            composition: Composition,
            exportResult: ExportResult,
            exportException: ExportException
        ) = finishFailure("${exportException.errorCodeName}:${exportException.message.orEmpty()}")

        override fun onTransformationCompleted(
            mediaItem: MediaItem,
            transformationResult: TransformationResult
        ) = finishSuccess()

        override fun onTransformationError(mediaItem: MediaItem, exception: Exception) =
            finishFailure("${exception.javaClass.simpleName}:${exception.message.orEmpty()}")
    }

    fun await(timeoutSeconds: Long): Boolean = latch.await(timeoutSeconds, TimeUnit.SECONDS)

    fun succeeded(): Boolean = success.get()

    fun errorDetail(): String = error.orEmpty()

    private fun finishSuccess() {
        if (done.compareAndSet(false, true)) {
            success.set(true)
            latch.countDown()
        }
    }

    private fun finishFailure(detail: String) {
        if (done.compareAndSet(false, true)) {
            error = detail
            latch.countDown()
        }
    }
}

private fun createCueMp3ClipBySampleCopy(
    extractor: MediaExtractor,
    outputFile: File,
    maxInputSize: Int,
    startUs: Long,
    endUs: Long,
    onFailure: (String) -> Unit
): File? {
    return try {
        val buffer = ByteBuffer.allocateDirect(maxInputSize)
        var wroteAnySample = false
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        FileOutputStream(outputFile).use { out ->
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size <= 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0L) break
                if (sampleTimeUs < startUs) {
                    if (!extractor.advance()) break
                    continue
                }
                if (sampleTimeUs > endUs) break

                val bytes = ByteArray(size)
                buffer.position(0)
                buffer.get(bytes, 0, size)
                out.write(bytes)
                wroteAnySample = true

                if (!extractor.advance()) break
            }
        }
        if (!wroteAnySample || outputFile.length() <= 0L) {
            onFailure("mp3-copy-empty-output")
            outputFile.delete()
            null
        } else {
            outputFile
        }
    } catch (e: Exception) {
        onFailure("mp3-copy-exception=${e.javaClass.simpleName}")
        outputFile.delete()
        null
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun createCueAudioClipByTransformer(
    context: Context,
    sourceUri: Uri,
    cueStartMs: Long,
    cueEndMs: Long,
    onFailure: (String) -> Unit
): File? {
    return try {
        val outputFile = createAnkiMediaTempFile(context, prefix = "cue-tx", extension = "m4a")
        val completion = TransformerCompletion()

        val mediaItem = MediaItem.Builder()
            .setUri(sourceUri)
            .setClipStartPositionMs(cueStartMs.coerceAtLeast(0L))
            .setClipEndPositionMs(cueEndMs.coerceAtLeast(cueStartMs + 1L))
            .build()
        val editedItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .build()
        val transformer = Transformer.Builder(context)
            .setAudioMimeType("audio/mp4a-latm")
            .setListener(completion.listener)
            .build()

        val started = runCatching {
            transformer.start(editedItem, outputFile.absolutePath)
        }.onFailure {
            onFailure("transformer-start-failed=${it.javaClass.simpleName}")
        }.isSuccess
        if (!started) {
            outputFile.delete()
            return null
        }

        val finished = runCatching {
            completion.await(90)
        }.getOrElse {
            onFailure("transformer-await-failed=${it.javaClass.simpleName}")
            false
        }
        if (!finished) {
            transformer.cancel()
            onFailure("transformer-timeout")
            outputFile.delete()
            return null
        }
        if (!completion.succeeded()) {
            onFailure("transformer-error=${completion.errorDetail()}")
            outputFile.delete()
            return null
        }
        if (outputFile.length() <= 0L) {
            onFailure("transformer-empty-output")
            outputFile.delete()
            return null
        }
        outputFile
    } catch (e: Exception) {
        onFailure("transformer-exception=${e.javaClass.simpleName}")
        null
    }
}

private fun createAnkiMediaTempFile(
    context: Context,
    prefix: String,
    extension: String
): File {
    val dir = File(context.cacheDir, "anki_media")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    val safeExt = extension.trim().trimStart('.').ifBlank { "m4a" }
    return File(dir, "tset-$prefix-${System.currentTimeMillis()}.$safeExt")
}

private fun resolveTemplate(template: String, variables: Map<String, String>): String {
    var output = template
    output = output.replace(SINGLE_GLOSSARY_DICT_MARKER_REGEX) { match ->
        val request = parseSingleGlossaryMarker(match.groupValues.getOrNull(1).orEmpty())
            ?: return@replace ""
        variables[templateSingleGlossaryKey(request.markerKey, request.dictionaryToken)].orEmpty()
    }
    val selectedDictionaryName = normalizeDictionaryToken(
        variables["dictionary"].orEmpty().ifBlank { variables["dictionary-name"].orEmpty() }
    )
    output = output.replace(SINGLE_FREQUENCY_NUMBER_DICT_MARKER_REGEX) { match ->
        val requestedDictionaryName = normalizeDictionaryToken(match.groupValues.getOrNull(1).orEmpty())
        if (requestedDictionaryName.isBlank() || selectedDictionaryName.isBlank()) {
            ""
        } else if (requestedDictionaryName == selectedDictionaryName) {
            variables["single-frequency-number"].orEmpty()
        } else {
            ""
        }
    }
    output = output.replace(SINGLE_FREQUENCY_DICT_MARKER_REGEX) { match ->
        val requestedDictionaryName = normalizeDictionaryToken(match.groupValues.getOrNull(1).orEmpty())
        if (requestedDictionaryName.isBlank() || selectedDictionaryName.isBlank()) {
            ""
        } else if (requestedDictionaryName == selectedDictionaryName) {
            variables["single-frequency"].orEmpty()
        } else {
            ""
        }
    }
    val normalizedVariables = HashMap<String, String>(variables.size * 2)
    variables.forEach { (key, value) ->
        normalizedVariables.putIfAbsent(key, value)
        normalizedVariables.putIfAbsent(canonicalizeTemplateKey(key), value)
    }
    return output.replace(TEMPLATE_VARIABLE_REGEX) { match ->
        val key = match.groupValues.getOrNull(1).orEmpty()
        normalizedVariables[key]
            ?: normalizedVariables[canonicalizeTemplateKey(key)]
            ?: ""
    }
}

private fun templateSingleGlossaryKey(markerKey: String, normalizedDictionaryName: String): String {
    return "__${markerKey}::$normalizedDictionaryName"
}

private fun canonicalizeTemplateKey(key: String): String {
    return key
        .trim()
        .lowercase(Locale.ROOT)
        .replace(NON_ALNUM_TEMPLATE_KEY_REGEX, "")
}

private fun normalizeDictionaryToken(value: String): String {
    return value
        .trim()
        .lowercase(Locale.ROOT)
        .replace(DICTIONARY_TOKEN_STRIP_REGEX, "")
        .replace("\u8bcd\u5178", "\u8f9e\u5178")
        .replace("\u93e1", "\u955c")
}

private fun splitCloze(sentence: String, word: String): Triple<String, String, String> {
    if (sentence.isBlank() || word.isBlank()) return Triple(sentence, "", "")
    val index = sentence.indexOf(word)
    if (index < 0) return Triple(sentence, "", "")
    val prefix = sentence.substring(0, index)
    val body = sentence.substring(index, index + word.length)
    val suffix = sentence.substring(index + word.length)
    return Triple(prefix, body, suffix)
}

private fun extractFirstNumber(text: String?): String {
    if (text.isNullOrBlank()) return ""
    return ANKI_FIRST_NUMBER_REGEX.find(text)?.value.orEmpty()
}

private fun buildExpressionFurigana(expression: String, reading: String?): String {
    val exp = expression.trim()
    val rd = reading?.trim().orEmpty()
    if (exp.isBlank()) return ""
    if (rd.isBlank()) return exp
    return "$exp[$rd]"
}



