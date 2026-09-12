package moe.tekuza.m9player

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.widget.Toast
import java.util.Locale
import moe.tekuza.m9player.ui.theme.TsetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AnkiSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TsetTheme {
                AnkiSettingsScreen(onBack = { finish() })
            }
        }
    }
}

private val ANKI_FIELD_VARIABLE_CHOICES = listOf(
    "",
    "{audio}",
    "{cut-audio}",
    "{cloze-body}",
    "{cloze-body-kana}",
    "{cloze-prefix}",
    "{cloze-suffix}",
    "{dictionary}",
    "{dictionary-alias}",
    "{book-title}",
    "{expression}",
    "{frequencies}",
    "{frequency-harmonic-occurrence}",
    "{frequency-harmonic-rank}",
    "{frequency-average-occurrence}",
    "{frequency-average-rank}",
    "{pitch-accents}",
    "{pitch-accent-positions}",
    "{pitch-accent-categories}",
    "{reading}",
    "{furigana}",
    "{furigana-plain}",
    "{glossary}",
    "{glossary-brief}",
    "{glossary-no-dictionary}",
    "{glossary-plain}",
    "{glossary-plain-no-dictionary}",
    "{glossary-first}",
    "{glossary-first-brief}",
    "{glossary-first-no-dictionary}",
    "{single-frequency-DICT-NAME}",
    "{single-frequency-number-DICT-NAME}",
    "{popup-selection-text}",
    "{search-query}",
    "{sentence}"
)

private const val LAPIS_MODEL_NAME = "Lapis"

private val LAPIS_DEFAULT_FIELD_TEMPLATES = mapOf(
    "Expression" to "{expression}",
    "ExpressionFurigana" to "{furigana-plain}",
    "ExpressionReading" to "{reading}",
    "ExpressionAudio" to "{audio}",
    "SelectionText" to "",
    "MainDefinition" to "{glossary-first}",
    "DefinitionPicture" to "",
    "Sentence" to "{cloze-prefix}<b>{cloze-body}</b>{cloze-suffix}",
    "SentenceFurigana" to "",
    "SentenceAudio" to "{cut-audio}",
    "Picture" to "",
    "Glossary" to "{glossary}",
    "Hint" to "",
    "IsWordAndSentenceCard" to "",
    "IsClickCard" to "x",
    "IsSentenceCard" to "",
    "IsAudioCard" to "",
    "PitchPosition" to "{pitch-accent-positions}",
    "PitchCategories" to "",
    "Frequency" to "{frequencies}",
    "FreqSort" to "{frequency-harmonic-rank}",
    "MiscInfo" to "{book-title}"
)

private const val ANKI_MODEL_TEMPLATE_SNAPSHOTS_KEY = "anki_model_template_snapshots_json"
private const val ANKI_EXPORT_PREFS_NAME = "anki_export_config"
private const val ANKI_CONFIG_LOG_TAG = "AnkiConfig"

@Composable
private fun AnkiSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var ankiPermissionGranted by remember { mutableStateOf(hasAnkiReadWritePermission(context)) }
    var ankiDeckName by remember { mutableStateOf("Default") }
    var ankiModelName by remember { mutableStateOf("") }
    var ankiTagsInput by remember { mutableStateOf(DEFAULT_ANKI_TAG) }
    var ankiDecks by remember { mutableStateOf<List<String>>(emptyList()) }
    var ankiModels by remember { mutableStateOf<List<AnkiModelTemplate>>(emptyList()) }
    var ankiModelFields by remember { mutableStateOf<List<String>>(emptyList()) }
    val ankiFieldTemplates = remember { mutableStateMapOf<String, String>() }
    val ankiModelTemplateSnapshots = remember { mutableStateMapOf<String, Map<String, String>>() }
    val dictionarySpecificGlossaryChoices = remember {
        resolveImportedDictionaryNamesForAnki(context)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .map { dictName ->
                val safeName = dictName
                    .replace("{", "")
                    .replace("}", "")
                    .lowercase(Locale.ROOT)
                    .replace(Regex("[\\s\\p{Punct}\\p{S}]+"), "-")
                    .trim('-')
                    .ifBlank { dictName }
                "{single-glossary-$safeName}"
            }
    }
    val fieldVariableChoices = remember(dictionarySpecificGlossaryChoices) {
        val baseChoices = ANKI_FIELD_VARIABLE_CHOICES
            .filterNot { it.contains("-brief", ignoreCase = true) }
            .distinct()
        val extraChoices = dictionarySpecificGlossaryChoices
            .filterNot { baseChoices.contains(it) }
        baseChoices + extraChoices
    }
    var ankiLoading by remember { mutableStateOf(false) }
    var ankiError by remember { mutableStateOf<String?>(null) }
    var awaitingExternalAnkiPermission by remember { mutableStateOf(false) }
    var duplicateCheckEnabled by remember { mutableStateOf(true) }
    var duplicateScope by remember { mutableStateOf("deck") }
    var duplicateAction by remember { mutableStateOf("prevent") }
    var lookupExportFullSentence by remember { mutableStateOf(false) }
    var lookupRangeSelectionEnabled by remember { mutableStateOf(false) }
    var showClearFieldTemplatesConfirm by remember { mutableStateOf(false) }
    var showApplyLapisDefaultsConfirm by remember { mutableStateOf(false) }

    val requestAnkiPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            ankiPermissionGranted = granted || hasAnkiReadWritePermission(context)
        }
    val requestExternalAnkiPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            awaitingExternalAnkiPermission = false
            ankiPermissionGranted = hasAnkiReadWritePermission(context)
            if (!ankiPermissionGranted) {
                ankiError = ankiAvailabilityUiMessage(context, requirePermission = true)
            }
        }

    fun persistAnkiConfig() {
        val currentModelName = ankiModelName.trim()
        if (currentModelName.isNotBlank() && ankiModelFields.isNotEmpty()) {
            ankiModelTemplateSnapshots[currentModelName] = ankiFieldTemplates.toMap()
        }
        logDebug(ANKI_CONFIG_LOG_TAG) {
            "Settings persist request deck='$ankiDeckName' model='$ankiModelName' tagsLen=${ankiTagsInput.length} fieldCount=${ankiFieldTemplates.size} snapshotCount=${ankiModelTemplateSnapshots.size}"
        }
        savePersistedAnkiConfig(
            context = context,
            config = buildPersistedAnkiConfig(
                deckName = ankiDeckName,
                modelName = ankiModelName,
                tags = ankiTagsInput,
                fieldTemplates = ankiFieldTemplates.toMap()
            )
        )
        saveAnkiModelTemplateSnapshots(context, ankiModelTemplateSnapshots.toMap())
        saveAnkiDuplicateConfig(
            context = context,
            config = AnkiDuplicateConfig(
                enabled = duplicateCheckEnabled,
                scope = duplicateScope,
                action = duplicateAction
            )
        )
    }

    fun syncTemplatesWithModelFields(
        fields: List<String>,
        modelTemplates: Map<String, String> = emptyMap()
    ) {
        ankiModelFields = fields
        syncAnkiFieldTemplates(
            target = ankiFieldTemplates,
            fields = fields,
            clearExisting = true,
            modelTemplates = modelTemplates
        )
    }

    fun selectAnkiModel(modelName: String) {
        val nextModelName = modelName.trim()
        val previousModelName = ankiModelName.trim()
        if (previousModelName.isNotBlank()) {
            ankiModelTemplateSnapshots[previousModelName] = ankiFieldTemplates.toMap()
        }
        ankiModelName = nextModelName
        val model = ankiModels.firstOrNull { it.name == nextModelName }
        val restoredTemplates = ankiModelTemplateSnapshots[nextModelName].orEmpty()
        logDebug(ANKI_CONFIG_LOG_TAG) {
            "Settings select model='$nextModelName' previous='$previousModelName' found=${model != null} fieldCount=${model?.fields?.size ?: 0} restoredTemplateCount=${restoredTemplates.size}"
        }
        syncTemplatesWithModelFields(
            fields = model?.fields ?: emptyList(),
            modelTemplates = restoredTemplates
        )
        persistAnkiConfig()
    }

    fun refreshAnkiCatalog() {
        val availabilityMessage = ankiAvailabilityUiMessage(context, requirePermission = true)
        if (availabilityMessage != null) {
            ankiError = availabilityMessage
            ankiDecks = emptyList()
            ankiModels = emptyList()
            ankiModelFields = emptyList()
            return
        }

        scope.launch {
            ankiLoading = true
            ankiError = null
            val result = withContext(Dispatchers.IO) {
                loadResolvedAnkiCatalogResult(
                    context = context,
                    currentDeckName = ankiDeckName,
                    currentModelName = ankiModelName,
                    defaultDeckName = context.getString(R.string.anki_default_deck)
                )
            }
            ankiLoading = false

            when (result) {
                is AnkiCatalogLoadResult.Success -> {
                    val resolvedCatalog = result.data
                    val resolvedModelName = resolvedCatalog.selection.modelName
                    val modelInCatalog = isAnkiModelInCatalog(
                        catalog = AnkiCatalog(
                            decks = resolvedCatalog.decks,
                            models = resolvedCatalog.models
                        ),
                        modelName = resolvedModelName
                    )
                    logDebug(ANKI_CONFIG_LOG_TAG) {
                        "Settings refresh success currentDeck='$ankiDeckName' currentModel='$ankiModelName' resolvedDeck='${resolvedCatalog.selection.deckName}' resolvedModel='$resolvedModelName' modelInCatalog=$modelInCatalog modelCount=${resolvedCatalog.models.size}"
                    }
                    ankiDecks = resolvedCatalog.decks
                    ankiModels = resolvedCatalog.models
                    ankiDeckName = resolvedCatalog.selection.deckName
                    if (modelInCatalog) {
                        selectAnkiModel(resolvedModelName)
                    } else {
                        logDebug(ANKI_CONFIG_LOG_TAG) {
                            "Settings refresh kept existing model='$ankiModelName' because it is not in catalog; snapshots and templates were not cleared"
                        }
                    }
                }
                is AnkiCatalogLoadResult.Failure -> {
                    ankiError = result.message
                    logDebug(ANKI_CONFIG_LOG_TAG) {
                        "Settings refresh failed message='${result.message}'"
                    }
                }
            }
        }
    }

    fun clearCurrentFieldTemplates() {
        ankiModelFields.forEach { field ->
            ankiFieldTemplates[field] = ""
        }
        persistAnkiConfig()
    }

    fun applyLapisDefaultTemplates() {
        val lapisModel = ankiModels.firstOrNull { it.name == LAPIS_MODEL_NAME }
        if (lapisModel == null) {
            Toast.makeText(context, context.getString(R.string.anki_lapis_model_missing), Toast.LENGTH_SHORT).show()
            return
        }
        val missingFields = LAPIS_DEFAULT_FIELD_TEMPLATES.keys
            .filterNot { required -> lapisModel.fields.any { it == required } }
        if (missingFields.isNotEmpty()) {
            Toast.makeText(
                context,
                context.getString(R.string.anki_lapis_fields_invalid, missingFields.take(3).joinToString(", ")),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val previousModelName = ankiModelName.trim()
        if (previousModelName.isNotBlank()) {
            ankiModelTemplateSnapshots[previousModelName] = ankiFieldTemplates.toMap()
        }
        ankiModelName = lapisModel.name
        syncTemplatesWithModelFields(
            fields = lapisModel.fields,
            modelTemplates = LAPIS_DEFAULT_FIELD_TEMPLATES
        )
        ankiModelTemplateSnapshots[lapisModel.name] = ankiFieldTemplates.toMap()
        persistAnkiConfig()
    }

    LaunchedEffect(Unit) {
        val persistedAnki = loadPersistedAnkiConfig(context)
        val duplicateConfig = loadAnkiDuplicateConfig(context)
        val audiobookConfig = loadAudiobookSettingsConfig(context)
        ankiDeckName = persistedAnki.deckName
        ankiModelName = persistedAnki.modelName
        ankiTagsInput = persistedAnki.tags
        duplicateCheckEnabled = duplicateConfig.enabled
        duplicateScope = duplicateConfig.scope
        duplicateAction = duplicateConfig.action
        lookupExportFullSentence = audiobookConfig.lookupExportFullSentence
        lookupRangeSelectionEnabled = audiobookConfig.lookupRangeSelectionEnabled
        ankiModelTemplateSnapshots.clear()
        loadAnkiModelTemplateSnapshots(context).forEach { (modelName, templates) ->
            ankiModelTemplateSnapshots[modelName] = templates
        }
        if (persistedAnki.modelName.isNotBlank()) {
            ankiModelTemplateSnapshots[persistedAnki.modelName] = persistedAnki.fieldTemplates
        }
        syncAnkiFieldTemplates(
            target = ankiFieldTemplates,
            fields = persistedAnki.fieldTemplates.keys.toList(),
            clearExisting = true,
            modelTemplates = persistedAnki.fieldTemplates
        )
    }

    LaunchedEffect(ankiPermissionGranted) {
        if (ankiPermissionGranted) {
            refreshAnkiCatalog()
        }
    }

    DisposableEffect(context, awaitingExternalAnkiPermission) {
        val activity = context as? AppCompatActivity
        val observer = activity?.let {
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && awaitingExternalAnkiPermission) {
                    awaitingExternalAnkiPermission = false
                    ankiPermissionGranted = hasAnkiReadWritePermission(context)
                }
            }
        }
        if (observer != null) {
            activity.lifecycle.addObserver(observer)
        }
        onDispose {
            if (observer != null) {
                activity.lifecycle.removeObserver(observer)
            }
        }
    }

    if (showClearFieldTemplatesConfirm) {
        AlertDialog(
            onDismissRequest = { showClearFieldTemplatesConfirm = false },
            title = { Text(stringResource(R.string.anki_clear_field_variables_confirm_title)) },
            text = { Text(stringResource(R.string.anki_clear_field_variables_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearFieldTemplatesConfirm = false
                        clearCurrentFieldTemplates()
                    }
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearFieldTemplatesConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showApplyLapisDefaultsConfirm) {
        AlertDialog(
            onDismissRequest = { showApplyLapisDefaultsConfirm = false },
            title = { Text(stringResource(R.string.anki_lapis_defaults_confirm_title)) },
            text = { Text(stringResource(R.string.anki_lapis_defaults_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showApplyLapisDefaultsConfirm = false
                        applyLapisDefaultTemplates()
                    }
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showApplyLapisDefaultsConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    SettingsScaffold(
        title = stringResource(R.string.anki_title),
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = hoshiCardBackgroundColor())
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val availabilityState = detectAnkiAvailability(context, requirePermission = true)
                val statusText = when (availabilityState) {
                    AnkiAvailabilityState.NOT_INSTALLED -> stringResource(R.string.anki_not_installed)
                    AnkiAvailabilityState.API_UNAVAILABLE -> stringResource(R.string.anki_api_unavailable)
                    AnkiAvailabilityState.PERMISSION_MISSING -> stringResource(R.string.anki_authorize_first)
                    AnkiAvailabilityState.READY -> stringResource(R.string.anki_access_granted)
                }

                Text(statusText)

                if (availabilityState == AnkiAvailabilityState.PERMISSION_MISSING) {
                    OutlinedButton(
                        onClick = {
                            val launchedIntent = createAnkiPermissionRequestIntent(context)
                            if (launchedIntent != null) {
                                awaitingExternalAnkiPermission = true
                                requestExternalAnkiPermissionLauncher.launch(launchedIntent)
                            } else {
                                requestAnkiPermissionLauncher.launch(ANKI_READ_WRITE_PERMISSION)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.anki_access_button_request))
                    }
                } else if (availabilityState == AnkiAvailabilityState.API_UNAVAILABLE) {
                    OutlinedButton(
                        onClick = {
                            if (!openAnkiDroidApp(context)) {
                                ankiError = context.getString(R.string.anki_not_installed)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.anki_open_app_button))
                    }
                }

                OutlinedButton(
                    onClick = { refreshAnkiCatalog() },
                    enabled = availabilityState == AnkiAvailabilityState.READY && !ankiLoading
                ) {
                    Text(if (ankiLoading) stringResource(R.string.common_loading) else stringResource(R.string.anki_refresh_catalog))
                }
                if (ankiError != null) {
                    Text(stringResource(R.string.anki_error_prefix, ankiError.orEmpty()), color = MaterialTheme.colorScheme.error)
                }

                AnkiListSelector(
                    label = stringResource(R.string.anki_deck_label),
                    value = ankiDeckName,
                    options = ankiDecks,
                    placeholder = stringResource(R.string.anki_deck_placeholder),
                    onValueChange = { selectedDeck ->
                        ankiDeckName = selectedDeck
                        persistAnkiConfig()
                    }
                )

                AnkiListSelector(
                    label = stringResource(R.string.anki_model_label),
                    value = ankiModelName,
                    options = ankiModels.map { it.name },
                    placeholder = stringResource(R.string.anki_model_placeholder),
                    onValueChange = { selectedModel ->
                        selectAnkiModel(selectedModel)
                    }
                )

                AnkiTagInput(
                    value = ankiTagsInput,
                    options = listOf("⑨"),
                    onValueChange = {
                        ankiTagsInput = it
                        persistAnkiConfig()
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.audiobook_lookup_full_sentence),
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )
                    Switch(
                        checked = lookupExportFullSentence,
                        onCheckedChange = { checked ->
                            saveLookupExportFullSentence(context, checked)
                            lookupExportFullSentence = checked
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.audiobook_lookup_range_selection_enable),
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )
                    Switch(
                        checked = lookupRangeSelectionEnabled,
                        onCheckedChange = { checked ->
                            saveLookupRangeSelectionEnabled(context, checked)
                            lookupRangeSelectionEnabled = checked
                        }
                    )
                }

                val scopeDeckLabel = stringResource(R.string.anki_duplicate_scope_deck)
                val scopeAllLabel = stringResource(R.string.anki_duplicate_scope_all)
                val actionPreventLabel = stringResource(R.string.anki_duplicate_action_prevent)
                val actionAddLabel = stringResource(R.string.anki_duplicate_action_add)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.anki_duplicate_check_label),
                            modifier = Modifier.weight(1f).padding(end = 12.dp)
                        )
                        Switch(
                            checked = duplicateCheckEnabled,
                            onCheckedChange = {
                                duplicateCheckEnabled = it
                                persistAnkiConfig()
                            }
                        )
                    }
                    if (duplicateCheckEnabled) {
                        AnkiListSelector(
                            label = stringResource(R.string.anki_duplicate_scope_label),
                            value = duplicateScope,
                            options = listOf("deck", "all"),
                            placeholder = "deck",
                            onValueChange = {
                                duplicateScope = it
                                persistAnkiConfig()
                            },
                            optionLabel = { key ->
                                when (key) {
                                    "all" -> scopeAllLabel
                                    else -> scopeDeckLabel
                                }
                            },
                            valueLabel = { key ->
                                when (key) {
                                    "all" -> scopeAllLabel
                                    else -> scopeDeckLabel
                                }
                            }
                        )
                        AnkiListSelector(
                            label = stringResource(R.string.anki_duplicate_action_label),
                            value = duplicateAction,
                            options = listOf("prevent", "add"),
                            placeholder = "prevent",
                            onValueChange = {
                                duplicateAction = it
                                persistAnkiConfig()
                            },
                            optionLabel = { key ->
                                when (key) {
                                    "add" -> actionAddLabel
                                    else -> actionPreventLabel
                                }
                            },
                            valueLabel = { key ->
                                when (key) {
                                    "add" -> actionAddLabel
                                    else -> actionPreventLabel
                                }
                            }
                        )
                    }
                }

                Text(stringResource(R.string.anki_field_variables), style = MaterialTheme.typography.titleSmall)
                if (ankiModelFields.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { showClearFieldTemplatesConfirm = true }
                    ) {
                        Text(stringResource(R.string.anki_clear_field_variables))
                    }
                }
                OutlinedButton(
                    onClick = { showApplyLapisDefaultsConfirm = true }
                ) {
                    Text(stringResource(R.string.anki_use_lapis_default_templates))
                }
                Text(
                    stringResource(R.string.anki_field_variable_help)
                )
                ankiModelFields.forEach { field ->
                    val selectedValue = ankiFieldTemplates[field].orEmpty()
                    AnkiFieldVariableInput(
                        fieldName = field,
                        value = selectedValue,
                        options = fieldVariableChoices,
                        onValueChange = { value ->
                            ankiFieldTemplates[field] = value
                            val currentModelName = ankiModelName.trim()
                            if (currentModelName.isNotBlank()) {
                                ankiModelTemplateSnapshots[currentModelName] = ankiFieldTemplates.toMap()
                            }
                            persistAnkiConfig()
                        }
                    )
                }
            }
        }
        }
    }
}

private fun loadAnkiModelTemplateSnapshots(context: Context): Map<String, Map<String, String>> {
    val raw = context.getSharedPreferences(ANKI_EXPORT_PREFS_NAME, Context.MODE_PRIVATE)
        .getString(ANKI_MODEL_TEMPLATE_SNAPSHOTS_KEY, null)
        ?: return emptyMap()
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
    val snapshots = linkedMapOf<String, Map<String, String>>()
    val modelKeys = root.keys()
    while (modelKeys.hasNext()) {
        val modelName = modelKeys.next().trim()
        if (modelName.isBlank()) continue
        val fieldsObj = root.optJSONObject(modelName) ?: continue
        val fields = linkedMapOf<String, String>()
        val fieldKeys = fieldsObj.keys()
        while (fieldKeys.hasNext()) {
            val fieldName = fieldKeys.next().trim()
            if (fieldName.isBlank()) continue
            fields[fieldName] = fieldsObj.optString(fieldName).trim()
        }
        snapshots[modelName] = fields
    }
    return snapshots
}

private fun saveAnkiModelTemplateSnapshots(
    context: Context,
    snapshots: Map<String, Map<String, String>>
) {
    val root = JSONObject()
    snapshots.forEach { (modelName, fields) ->
        if (modelName.isBlank()) return@forEach
        val fieldsObj = JSONObject()
        fields.forEach { (fieldName, template) ->
            if (fieldName.isBlank()) return@forEach
            fieldsObj.put(fieldName, template)
        }
        root.put(modelName, fieldsObj)
    }
    context.getSharedPreferences(ANKI_EXPORT_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(ANKI_MODEL_TEMPLATE_SNAPSHOTS_KEY, root.toString())
        .apply()
}

private fun resolveImportedDictionaryNamesForAnki(context: Context): List<String> {
    val imports = loadPersistedImports(context)
    return imports.dictionaries.mapNotNull { ref ->
        val fromMeta = ref.cacheKey
            ?.takeIf { it.isNotBlank() }
            ?.let { cacheKey -> loadDictionaryFromStorage(context, cacheKey)?.name }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val fallback = ref.name
            .trim()
            .removeSuffix(".zip")
            .removeSuffix(".mdd")
            .takeIf { it.isNotBlank() }
        fromMeta ?: fallback
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnkiTagInput(
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val distinctOptions = remember(options) {
        options.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }
    ExposedDropdownMenuBox(
        expanded = expanded && distinctOptions.isNotEmpty(),
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(type = MenuAnchorType.PrimaryEditable),
            label = { Text(stringResource(R.string.anki_tags_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded && distinctOptions.isNotEmpty(),
            onDismissRequest = { expanded = false }
        ) {
            distinctOptions.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice) },
                    onClick = {
                        expanded = false
                        val tokens = value
                            .split(',', ' ', '\n', '\t')
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .toMutableList()
                        if (tokens.none { it == choice }) {
                            tokens.add(choice)
                        }
                        onValueChange(tokens.joinToString(" "))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnkiListSelector(
    label: String,
    value: String,
    options: List<String>,
    placeholder: String,
    onValueChange: (String) -> Unit,
    optionLabel: (String) -> String = { it },
    valueLabel: (String) -> String = { it }
) {
    val distinctOptions = remember(options) {
        options
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }
    var expanded by remember(label) { mutableStateOf(false) }

    if (distinctOptions.isEmpty()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true
        )
        return
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = valueLabel(value),
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(type = MenuAnchorType.PrimaryNotEditable),
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            distinctOptions.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(optionLabel(choice)) },
                    onClick = {
                        expanded = false
                        onValueChange(choice)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnkiFieldVariableInput(
    fieldName: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit
) {
    val distinctOptions = remember(options) { options.distinct() }
    var expanded by remember(fieldName) { mutableStateOf(false) }
    val filteredOptions = remember(distinctOptions, value) {
        val query = value.trim()
        if (query.isBlank()) {
            distinctOptions
        } else {
            val startsWithMatches = distinctOptions.filter { it.startsWith(query, ignoreCase = true) }
            val containsMatches = distinctOptions.filter {
                !it.startsWith(query, ignoreCase = true) && it.contains(query, ignoreCase = true)
            }
            startsWithMatches + containsMatches
        }
    }

    ExposedDropdownMenuBox(
        expanded = expanded && filteredOptions.isNotEmpty(),
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { next ->
                onValueChange(next)
                expanded = true
            },
            readOnly = false,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(type = MenuAnchorType.PrimaryEditable),
            label = { Text(fieldName) },
            placeholder = { Text(stringResource(R.string.anki_field_placeholder)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded && filteredOptions.isNotEmpty(),
            onDismissRequest = { expanded = false }
        ) {
            filteredOptions.forEach { choice ->
                val text = choice.ifBlank { stringResource(R.string.common_empty_option) }
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onValueChange(choice)
                    }
                )
            }
        }
    }
}




