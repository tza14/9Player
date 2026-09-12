package moe.tekuza.m9player

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.tekuza.m9player.ui.theme.TsetTheme

private const val BOOK_UI_MODE_LOG_TAG = "BookUiMode"

class AudiobookSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TsetTheme {
                AudiobookSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AudiobookSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(loadAudiobookSettingsConfig(context)) }
    var importState by remember { mutableStateOf(loadPersistedImports(context)) }
    var inputSeconds by remember { mutableStateOf((config.seekStepMillis / 1000L).toString()) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var importGuideVisible by remember { mutableStateOf(!importState.importOnboardingCompleted) }
    var lookupAudioImporting by remember { mutableStateOf(false) }
    var lookupAudioImportStage by remember { mutableStateOf(context.getString(R.string.audiobook_import_stage_preparing)) }
    var lookupAudioImportCopiedBytes by remember { mutableStateOf(0L) }
    var lookupAudioImportTotalBytes by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    val refreshConfig = {
        config = loadAudiobookSettingsConfig(context)
        importState = loadPersistedImports(context)
        inputSeconds = (config.seekStepMillis / 1000L).toString()
    }
    val pickLookupAudioLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) {
                statusText = context.getString(R.string.common_not_selected)
                Toast.makeText(context, context.getString(R.string.common_not_selected), Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            persistLookupAudioReadPermission(context, uri)
            statusText = context.getString(R.string.audiobook_lookup_audio_importing)
            Toast.makeText(context, context.getString(R.string.audiobook_lookup_audio_importing), Toast.LENGTH_SHORT).show()
            scope.launch {
                lookupAudioImporting = true
                lookupAudioImportStage = context.getString(R.string.audiobook_lookup_audio_prepare)
                lookupAudioImportCopiedBytes = 0L
                lookupAudioImportTotalBytes = null
                try {
                    val imported = withContext(Dispatchers.IO) {
                        importLookupAudioDatabase(
                            context = context,
                            sourceUri = uri,
                            onStageChanged = { stage ->
                                scope.launch(Dispatchers.Main) {
                                    lookupAudioImportStage = stage
                                }
                            },
                            onCopyProgress = { copiedBytes, totalBytes ->
                                scope.launch(Dispatchers.Main) {
                                    lookupAudioImportCopiedBytes = copiedBytes
                                    lookupAudioImportTotalBytes = totalBytes
                                }
                            }
                        )
                    }
                    if (imported != null) {
                        saveLookupLocalAudioUri(context, imported)
                        refreshConfig()
                        val selectedName = queryLookupAudioDisplayName(context, imported)
                        statusText = context.getString(R.string.audiobook_lookup_audio_imported, selectedName)
                        Toast.makeText(context, context.getString(R.string.audiobook_lookup_audio_imported_short), Toast.LENGTH_SHORT).show()
                    } else {
                        statusText = context.getString(R.string.audiobook_lookup_audio_import_failed)
                        Toast.makeText(
                            context,
                            context.getString(R.string.audiobook_lookup_audio_import_failed_toast),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } finally {
                    lookupAudioImporting = false
                }
            }
        }
    fun updateStep(seconds: Int) {
        val millis = seconds.coerceIn(1, 300) * 1000L
        saveAudiobookSeekStepMillis(context, millis)
        refreshConfig()
        statusText = context.getString(R.string.audiobook_seek_saved, seconds)
    }
    fun updateAutoMove(enabled: Boolean) {
        savePersistedImports(
            context,
            importState.copy(
                autoMoveToAudiobookFolder = enabled,
                importOnboardingCompleted = true
            )
        )
        refreshConfig()
        statusText = if (enabled) {
            context.getString(R.string.audiobook_import_saved_auto)
        } else {
            context.getString(R.string.audiobook_import_saved_keep)
        }
    }

    if (importGuideVisible) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.import_mode_title)) },
            text = {
                Text(stringResource(R.string.audiobook_import_onboarding_message))
            },
            confirmButton = {
                Button(
                    onClick = {
                        updateAutoMove(true)
                        importGuideVisible = false
                    }
                ) {
                    Text(stringResource(R.string.import_mode_auto_move))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        updateAutoMove(false)
                        importGuideVisible = false
                    }
                ) {
                    Text(stringResource(R.string.import_mode_keep_original))
                }
            }
        )
    }

    if (lookupAudioImporting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.audiobook_lookup_audio_import_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(lookupAudioImportStage)
                    val totalBytes = lookupAudioImportTotalBytes
                    if (totalBytes != null && totalBytes > 0L) {
                        val progress = (lookupAudioImportCopiedBytes.toFloat() / totalBytes.toFloat())
                            .coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${formatBytes(lookupAudioImportCopiedBytes)} / ${formatBytes(totalBytes)}")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(formatBytes(lookupAudioImportCopiedBytes))
                    }
                }
            },
            confirmButton = {}
        )
    }

    SettingsScaffold(
        title = stringResource(R.string.audiobook_settings_title),
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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.audiobook_seek_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.audiobook_seek_current, (config.seekStepMillis / 1000L).toInt()))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30).forEach { seconds ->
                        OutlinedButton(onClick = { updateStep(seconds) }) {
                            Text("${seconds}s")
                        }
                    }
                }

                OutlinedTextField(
                    value = inputSeconds,
                    onValueChange = { value ->
                        inputSeconds = value.filter { it.isDigit() }.take(3)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.audiobook_seek_custom_label)) },
                    singleLine = true
                )

                Button(
                    onClick = {
                        val seconds = inputSeconds.toIntOrNull()
                        if (seconds == null || seconds <= 0) {
                            statusText = context.getString(R.string.audiobook_seek_invalid)
                        } else {
                            updateStep(seconds)
                        }
                    }
                ) {
                    Text(stringResource(R.string.common_save))
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = hoshiCardBackgroundColor())
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.audiobook_import_mode_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (importState.autoMoveToAudiobookFolder) {
                        stringResource(R.string.audiobook_import_current_auto)
                    } else {
                        stringResource(R.string.audiobook_import_current_keep)
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { updateAutoMove(true) }) {
                        Text(stringResource(R.string.import_mode_auto_move))
                    }
                    OutlinedButton(onClick = { updateAutoMove(false) }) {
                        Text(stringResource(R.string.import_mode_keep_original))
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = hoshiCardBackgroundColor())
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.audiobook_lookup_title), style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.audiobook_pause_on_lookup),
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )
                    Switch(
                        checked = config.pausePlaybackOnLookup,
                        onCheckedChange = { checked ->
                            saveAudiobookPausePlaybackOnLookup(context, checked)
                            refreshConfig()
                            statusText = if (checked) {
                                context.getString(R.string.audiobook_pause_on_lookup_enabled)
                            } else {
                                context.getString(R.string.audiobook_pause_on_lookup_disabled)
                            }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.audiobook_play_audio),
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )
                    Switch(
                        checked = config.lookupPlaybackAudioEnabled,
                        onCheckedChange = { checked ->
                            saveLookupPlaybackAudioEnabled(context, checked)
                            refreshConfig()
                            statusText = if (checked) {
                                context.getString(R.string.audiobook_play_audio_enabled)
                            } else {
                                context.getString(R.string.audiobook_play_audio_disabled)
                            }
                        }
                    )
                }
                if (config.lookupPlaybackAudioEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.audiobook_autoplay_lookup_audio),
                            modifier = Modifier.weight(1f).padding(end = 12.dp)
                        )
                        Switch(
                            checked = config.lookupPlaybackAudioAutoPlay,
                            onCheckedChange = { checked ->
                                saveLookupPlaybackAudioAutoPlay(context, checked)
                                refreshConfig()
                                statusText = if (checked) {
                                    context.getString(R.string.audiobook_autoplay_lookup_audio_enabled)
                                } else {
                                    context.getString(R.string.audiobook_autoplay_lookup_audio_disabled)
                                }
                            }
                        )
                    }
                    Text(stringResource(R.string.audiobook_lookup_audio_source))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                saveLookupAudioMode(context, LookupAudioMode.LOCAL_TTS)
                                refreshConfig()
                                statusText = context.getString(R.string.audiobook_lookup_audio_switched_tts)
                            }
                        ) {
                            Text(stringResource(R.string.audiobook_lookup_audio_source_tts))
                        }
                        OutlinedButton(
                            onClick = {
                                saveLookupAudioMode(context, LookupAudioMode.LOCAL_AUDIO)
                                refreshConfig()
                                statusText = context.getString(R.string.audiobook_lookup_audio_switched_local)
                            }
                        ) {
                            Text(stringResource(R.string.audiobook_lookup_audio_source_local))
                        }
                    }
                    Text(
                    if (config.lookupAudioMode == LookupAudioMode.LOCAL_TTS) {
                        stringResource(R.string.audiobook_lookup_audio_current_tts)
                    } else {
                        stringResource(R.string.audiobook_lookup_audio_current_db)
                    }
                )
                if (config.lookupAudioMode == LookupAudioMode.LOCAL_AUDIO) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                pickLookupAudioLauncher.launch("*/*")
                            },
                            enabled = !lookupAudioImporting
                        ) {
                            Text(stringResource(R.string.audiobook_lookup_audio_import_title))
                        }
                            OutlinedButton(
                                onClick = {
                                    deleteImportedLookupAudioDatabaseIfAny(context, config.lookupLocalAudioUri)
                                    saveLookupLocalAudioUri(context, null)
                                    refreshConfig()
                                    statusText = context.getString(R.string.audiobook_lookup_audio_cleared)
                            },
                                enabled = !lookupAudioImporting
                        ) {
                            Text(stringResource(R.string.common_clear))
                        }
                    }
                        val selectedLookupAudioName = config.lookupLocalAudioUri?.let { uri ->
                            queryLookupAudioDisplayName(context, uri)
                        }
                        Text(
                            selectedLookupAudioName?.let { context.getString(R.string.audiobook_lookup_audio_database_current, it) }
                                ?: stringResource(R.string.audiobook_lookup_audio_database_none)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.audiobook_lookup_root_full_width_enable),
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )
                    Switch(
                        checked = config.lookupRootFullWidthEnabled,
                        onCheckedChange = { checked ->
                            saveLookupRootFullWidthEnabled(context, checked)
                            refreshConfig()
                            statusText = if (checked) {
                                context.getString(R.string.audiobook_lookup_root_full_width_enabled)
                            } else {
                                context.getString(R.string.audiobook_lookup_root_full_width_disabled)
                            }
                        }
                    )
                }
                Text(stringResource(R.string.audiobook_active_cue_position))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            saveAudiobookActiveCueDisplayAtTop(context, false)
                            refreshConfig()
                            statusText = context.getString(R.string.audiobook_active_cue_saved_middle)
                        }
                    ) {
                        Text(stringResource(R.string.audiobook_active_cue_middle))
                    }
                    OutlinedButton(
                        onClick = {
                            saveAudiobookActiveCueDisplayAtTop(context, true)
                            refreshConfig()
                            statusText = context.getString(R.string.audiobook_active_cue_saved_top)
                        }
                    ) {
                        Text(stringResource(R.string.audiobook_active_cue_top))
                    }
                }
                Text(
                    if (config.activeCueDisplayAtTop) {
                        stringResource(R.string.audiobook_active_cue_current_top)
                    } else {
                        stringResource(R.string.audiobook_active_cue_current_middle)
                    }
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = hoshiCardBackgroundColor())
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.audiobook_overlay_subtitle_style), style = MaterialTheme.typography.titleMedium)
                SubtitleWritingModeSelector(
                    selected = config.bookSubtitleWritingMode,
                    onSelected = { mode ->
                        if (mode != config.bookSubtitleWritingMode) {
                            logDebug(BOOK_UI_MODE_LOG_TAG) { "settings screen writing mode changed -> $mode" }
                            saveAudiobookBookSubtitleWritingMode(context, mode)
                            refreshConfig()
                        }
                    }
                )
            }
        }

        statusText?.let {
            Text(it)
        }
        }
    }
}

private fun persistLookupAudioReadPermission(context: android.content.Context, uri: Uri) {
    val resolver = context.contentResolver
    val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    try {
        resolver.takePersistableUriPermission(uri, readWriteFlags)
        return
    } catch (_: SecurityException) {
        // Ignore and fall back to read-only permission.
    }
    try {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: SecurityException) {
        // Some providers do not support persistable permission.
    }
}

private fun queryLookupAudioDisplayName(context: android.content.Context, uri: Uri): String {
    if (uri.scheme.equals("file", ignoreCase = true)) {
        val name = uri.path.orEmpty().substringAfterLast('/')
        if (name.isNotBlank()) return name
    }
    val fromQuery = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)
            } else {
                null
            }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
    return fromQuery ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank {
        context.getString(R.string.audiobook_unknown_file)
    }
}

private fun importLookupAudioDatabase(
    context: android.content.Context,
    sourceUri: Uri,
    onStageChanged: ((String) -> Unit)? = null,
    onCopyProgress: ((copiedBytes: Long, totalBytes: Long?) -> Unit)? = null
): Uri? {
    val dir = File(context.filesDir, "lookup_audio_db")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    val target = File(dir, "android.db")
    val temp = File(dir, "android.db.tmp")
    val totalBytes = queryLookupAudioSourceSize(context, sourceUri)

    val copied = runCatching {
        onStageChanged?.invoke(context.getString(R.string.audiobook_import_stage_copy))
        var copiedBytes = 0L
        var lastProgressEmitAt = 0L
        onCopyProgress?.invoke(0L, totalBytes)
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copiedBytes += read
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgressEmitAt >= 120L) {
                        onCopyProgress?.invoke(copiedBytes, totalBytes)
                        lastProgressEmitAt = now
                    }
                }
                output.flush()
            }
        } ?: return null
        onCopyProgress?.invoke(copiedBytes, totalBytes)
        if (temp.length() <= 0L) {
            runCatching { temp.delete() }
            return null
        }
        true
    }.getOrDefault(false)
    if (!copied) return null

    onStageChanged?.invoke(context.getString(R.string.audiobook_import_stage_validate))
    val valid = runCatching {
        SQLiteDatabase.openDatabase(temp.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            var hasEntries = false
            var hasAndroid = false
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('entries','android')",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    when (cursor.getString(0)?.trim()?.lowercase()) {
                        "entries" -> hasEntries = true
                        "android" -> hasAndroid = true
                    }
                }
            }
            hasEntries && hasAndroid
        }
    }.getOrDefault(false)

    if (!valid) {
        runCatching { temp.delete() }
        return null
    }

    onStageChanged?.invoke(context.getString(R.string.audiobook_import_stage_write))
    runCatching { if (target.exists()) target.delete() }
    val moved = runCatching { temp.renameTo(target) }.getOrDefault(false)
    if (!moved) {
        val copiedFallback = runCatching {
            temp.inputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        }.getOrDefault(false)
        runCatching { temp.delete() }
        if (!copiedFallback) {
            runCatching { target.delete() }
            return null
        }
    }
    onStageChanged?.invoke(context.getString(R.string.audiobook_import_stage_done))
    return Uri.fromFile(target)
}

private fun queryLookupAudioSourceSize(context: android.content.Context, uri: Uri): Long? {
    if (uri.scheme.equals("file", ignoreCase = true)) {
        val path = uri.path.orEmpty()
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        return file.length().takeIf { it > 0L }
    }
    val result = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                cursor.getLong(index)
            } else {
                null
            }
        }
    }.getOrNull()
    return result?.takeIf { it > 0L }
}

private fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        safe >= gb -> String.format(Locale.US, "%.2f GB", safe / gb)
        safe >= mb -> String.format(Locale.US, "%.2f MB", safe / mb)
        safe >= kb -> String.format(Locale.US, "%.2f KB", safe / kb)
        else -> "$safe B"
    }
}

private fun deleteImportedLookupAudioDatabaseIfAny(context: android.content.Context, uri: Uri?) {
    val target = uri ?: return
    if (!target.scheme.equals("file", ignoreCase = true)) return
    val path = target.path ?: return
    val importedDir = File(context.filesDir, "lookup_audio_db").absolutePath
    if (!path.startsWith(importedDir, ignoreCase = true)) return
    runCatching { File(path).delete() }
}

