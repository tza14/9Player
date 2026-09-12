package moe.tekuza.m9player

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun AppLanguageDialog(
    selectedAppLanguage: AppLanguageOption,
    onDismiss: () -> Unit,
    onSelectLanguage: (AppLanguageOption) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppLanguageOption.entries.forEach { option ->
                    OutlinedButton(
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        onClick = { onSelectLanguage(option) }
                    ) {
                        val prefix = if (selectedAppLanguage == option) "✓ " else ""
                        Text(prefix + option.displayLabel(androidx.compose.ui.platform.LocalContext.current))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}

@Composable
internal fun ImportGuideDialog(
    onKeepOriginal: () -> Unit,
    onAutoMove: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.import_mode_title)) },
        text = { Text(stringResource(R.string.import_mode_first_launch_message)) },
        dismissButton = {
            OutlinedButton(onClick = onKeepOriginal) {
                Text(stringResource(R.string.import_mode_keep_original))
            }
        },
        confirmButton = {
            Button(onClick = onAutoMove) {
                Text(stringResource(R.string.import_mode_auto_move))
            }
        }
    )
}

@Composable
internal fun AutoUpdateFirstPromptDialog(
    onSkip: () -> Unit,
    onEnable: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.update_first_prompt_title)) },
        text = { Text(stringResource(R.string.update_first_prompt_message)) },
        dismissButton = {
            OutlinedButton(onClick = onSkip) {
                Text(stringResource(R.string.update_first_prompt_skip))
            }
        },
        confirmButton = {
            Button(onClick = onEnable) {
                Text(stringResource(R.string.update_first_prompt_enable))
            }
        }
    )
}

@Composable
internal fun AppUpdateLaunchPromptDialog(
    releaseDisplayName: String,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_launch_prompt_title)) },
        text = { Text(stringResource(R.string.update_launch_prompt_message, releaseDisplayName)) },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_launch_prompt_negative))
            }
        },
        confirmButton = {
            Button(onClick = onDownload) {
                Text(stringResource(R.string.update_launch_prompt_positive))
            }
        }
    )
}

@Composable
internal fun ClearCollectionsDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.collections_clear_title)) },
        text = { Text(stringResource(R.string.collections_clear_message)) },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.collections_clear_confirm))
            }
        }
    )
}

@Composable
internal fun DeleteBooksConfirmDialog(
    deleteSourceFiles: Boolean,
    onDeleteSourceFilesChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_books_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.delete_books_message))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Checkbox(
                        checked = deleteSourceFiles,
                        onCheckedChange = onDeleteSourceFilesChange
                    )
                    Text(stringResource(R.string.delete_books_delete_source_files))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.common_delete))
            }
        }
    )
}

@Composable
internal fun AddBookDialog(
    folderName: String?,
    folderUri: Uri?,
    audioName: String?,
    audioUri: Uri?,
    srtName: String?,
    ebookEnabled: Boolean,
    ebookOnlyImportEnabled: Boolean,
    ebookName: String?,
    ebookUri: Uri?,
    autoMoveToAudiobookFolder: Boolean,
    srtLoading: Boolean,
    onPickFolder: () -> Unit,
    onClearFolderSelection: () -> Unit,
    onPickAudio: () -> Unit,
    onPickSrt: () -> Unit,
    onPickEbook: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_book_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    context.getString(
                        R.string.add_book_folder_label,
                        folderName ?: context.getString(R.string.common_not_selected)
                    )
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickFolder) {
                        Text(stringResource(R.string.add_book_pick_folder))
                    }
                    OutlinedButton(
                        onClick = onClearFolderSelection,
                        enabled = folderUri != null
                    ) {
                        Text(stringResource(R.string.common_clear))
                    }
                }

                Text(
                    context.getString(
                        R.string.add_book_audio_label,
                        audioName ?: context.getString(R.string.common_not_selected)
                    )
                )
                OutlinedButton(
                    onClick = onPickAudio,
                    enabled = folderUri != null || !autoMoveToAudiobookFolder
                ) {
                    Text(stringResource(R.string.add_book_pick_audio))
                }

                Text(
                    context.getString(
                        R.string.add_book_srt_label,
                        srtName ?: context.getString(R.string.common_not_selected)
                    )
                )
                OutlinedButton(
                    onClick = onPickSrt,
                    enabled = folderUri != null || !autoMoveToAudiobookFolder
                ) {
                    Text(stringResource(R.string.add_book_pick_srt))
                }

                if (ebookEnabled) {
                    Text(
                        context.getString(
                            R.string.add_book_ebook_label,
                            ebookName ?: context.getString(R.string.common_not_selected)
                        )
                    )
                    OutlinedButton(
                        onClick = onPickEbook,
                        enabled = ebookOnlyImportEnabled || (audioUri != null && srtName != null)
                    ) {
                        Text(stringResource(R.string.add_book_pick_ebook))
                    }
                    if (ebookOnlyImportEnabled) {
                        Text(stringResource(R.string.add_book_ebook_only_enabled_hint))
                    } else if (audioUri == null || srtName == null) {
                        Text(stringResource(R.string.add_book_ebook_requires_audio_srt))
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = (folderUri != null || !autoMoveToAudiobookFolder) &&
                    (audioUri != null || (ebookOnlyImportEnabled && ebookUri != null)) &&
                    !srtLoading
            ) {
                Text(stringResource(R.string.common_confirm))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiagnosticsExportSheet(
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_export_diagnostics_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
            SettingsListItem(
                icon = Icons.Outlined.Download,
                title = stringResource(R.string.settings_export_diagnostics_download),
                subtitle = stringResource(R.string.settings_export_diagnostics_download_subtitle),
                onClick = onDownload,
                showDivider = false
            )
            SettingsListItem(
                icon = Icons.Outlined.Share,
                title = stringResource(R.string.settings_export_diagnostics_share),
                subtitle = stringResource(R.string.settings_export_diagnostics_share_subtitle),
                onClick = onShare,
                showDivider = false
            )
        }
    }
}
