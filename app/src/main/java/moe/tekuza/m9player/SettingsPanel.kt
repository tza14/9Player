package moe.tekuza.m9player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsPanel(
    selectedAppLanguageLabel: String,
    versionName: String,
    onAudiobookClick: () -> Unit,
    onControlModeClick: () -> Unit,
    onAudiobookUiClick: () -> Unit,
    onFontClick: () -> Unit,
    onControllerClick: () -> Unit,
    onAnkiClick: () -> Unit,
    onDictionaryClick: () -> Unit,
    onAdvancedOverlayClick: () -> Unit,
    onAdvancedStatisticsClick: () -> Unit,
    onAdvancedOtherClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onGuideClick: () -> Unit,
    onExportDiagnosticsClick: () -> Unit,
    onUpdateClick: () -> Unit,
    onVersionClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsSection(
            title = stringResource(R.string.settings_section_reading)
        ) {
            SettingsListItem(
                icon = Icons.Outlined.AutoStories,
                title = stringResource(R.string.settings_audiobook_title),
                onClick = onAudiobookClick
            )
            SettingsListItem(
                icon = Icons.AutoMirrored.Outlined.MenuBook,
                title = stringResource(R.string.settings_control_mode_title),
                onClick = onControlModeClick
            )
            SettingsListItem(
                iconPainter = painterResource(R.drawable.ic_grid_layout_side),
                title = stringResource(R.string.settings_audiobook_ui_title),
                subtitle = stringResource(R.string.settings_audiobook_ui_subtitle),
                onClick = onAudiobookUiClick
            )
            SettingsListItem(
                icon = Icons.Outlined.FontDownload,
                title = stringResource(R.string.settings_font_title),
                onClick = onFontClick
            )
            SettingsListItem(
                icon = Icons.Outlined.SportsEsports,
                title = stringResource(R.string.settings_controller_title),
                onClick = onControllerClick,
                showDivider = false
            )
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_learning)
        ) {
            SettingsListItem(
                icon = Icons.Outlined.LibraryAdd,
                title = stringResource(R.string.settings_anki_title),
                onClick = onAnkiClick,
                showDivider = true
            )
            SettingsListItem(
                icon = Icons.Outlined.Translate,
                title = stringResource(R.string.settings_dictionary_title),
                onClick = onDictionaryClick,
                showDivider = false
            )
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_system)
        ) {
            SettingsListItem(
                icon = Icons.Outlined.Language,
                title = stringResource(R.string.settings_language_title),
                value = selectedAppLanguageLabel,
                onClick = onLanguageClick,
                showDivider = false
            )
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_advanced)
        ) {
            SettingsListItem(
                icon = Icons.Outlined.Layers,
                title = stringResource(R.string.audiobook_overlay_title),
                onClick = onAdvancedOverlayClick
            )
            SettingsListItem(
                icon = Icons.Outlined.QueryStats,
                title = stringResource(R.string.settings_statistics_title),
                onClick = onAdvancedStatisticsClick
            )
            SettingsListItem(
                icon = Icons.Outlined.Science,
                title = stringResource(R.string.settings_other_title),
                onClick = onAdvancedOtherClick,
                showDivider = false
            )
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_about)
        ) {
            SettingsListItem(
                icon = Icons.Outlined.Link,
                title = stringResource(R.string.settings_guide_title),
                onClick = onGuideClick
            )
            SettingsListItem(
                icon = Icons.Outlined.BugReport,
                title = stringResource(R.string.settings_export_diagnostics_title),
                subtitle = stringResource(R.string.settings_export_diagnostics_subtitle),
                onClick = onExportDiagnosticsClick
            )
            SettingsListItem(
                icon = Icons.Outlined.Download,
                title = stringResource(R.string.settings_update_title),
                onClick = onUpdateClick
            )
            SettingsListItem(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.settings_version_title),
                value = versionName,
                onClick = onVersionClick,
                showDivider = false
            )
        }
    }
}
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            content()
        }
    }
}
