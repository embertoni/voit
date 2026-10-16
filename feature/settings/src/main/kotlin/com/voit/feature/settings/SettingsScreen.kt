package com.voit.feature.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voit.design.component.VoitButton
import com.voit.design.component.VoitScaffold
import com.voit.design.component.VoitSectionHeader
import com.voit.design.component.VoitTextField
import com.voit.design.theme.VoitTheme

/**
 * Settings bound to the DataStore AppSettings. Monochrome throughout; every toggle has a
 * text label (state is never colour-only). Mic/listening features are shown but clearly
 * marked not-yet-active for Phase 1, and cloud processing is disabled.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val s = state.settings
    val spacing = VoitTheme.spacing

    VoitScaffold(modifier = modifier, title = "Settings") { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            VoitSectionHeader(text = "Appearance")
            ChipRow(
                options = listOf("SYSTEM", "LIGHT", "DARK"),
                selected = s.themeMode,
                onSelect = viewModel::setThemeMode,
            )

            VoitSectionHeader(text = "Recognition")
            ChipRow(
                options = listOf("pt-BR", "en-US"),
                selected = s.recognitionLanguage,
                onSelect = viewModel::setRecognitionLanguage,
            )
            SettingTextField(
                label = "Wake phrase (placeholder, not yet active)",
                value = s.wakePhrase,
                onValueChange = viewModel::setWakePhrase,
            )

            VoitSectionHeader(text = "Review")
            SettingSwitch(
                label = "Review mode",
                description = "Show parsed results for review before committing.",
                checked = s.reviewModeEnabled,
                onCheckedChange = viewModel::setReviewMode,
            )

            VoitSectionHeader(text = "Privacy")
            SettingSwitch(
                label = "Cloud processing",
                description = "OFF in Phase 1. Voit is local-first; cloud is disabled.",
                checked = s.cloudProcessingEnabled,
                onCheckedChange = {},
                enabled = false,
            )
            SettingSwitch(
                label = "Speaker verification",
                description = "Metadata only. Not active until voice lands in a later phase.",
                checked = s.speakerVerificationEnabled,
                onCheckedChange = viewModel::setSpeakerVerification,
                enabled = false,
            )
            SettingSwitch(
                label = "Diagnostics",
                description = "Local, redacted diagnostics only. No transcripts or audio.",
                checked = s.diagnosticsEnabled,
                onCheckedChange = viewModel::setDiagnostics,
            )

            VoitSectionHeader(text = "Listening (not yet active)")
            Text(
                text = "Microphone, wake word, overlay and the foreground listening service " +
                    "are scaffolding only in Phase 1. No audio is captured on this device.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = spacing.md),
            )

            VoitSectionHeader(text = "Delete my data")
            Column(
                modifier = Modifier.padding(horizontal = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                VoitButton(
                    text = "Delete all notes",
                    onClick = viewModel::deleteAllNotes,
                    modifier = Modifier.fillMaxWidth(),
                )
                VoitButton(
                    text = "Delete all tasks",
                    onClick = viewModel::deleteAllTasks,
                    modifier = Modifier.fillMaxWidth(),
                )
                VoitButton(
                    text = "Delete all commands",
                    onClick = viewModel::deleteAllCommands,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Voice profile metadata is metadata-only and user-deletable; " +
                        "full voice-profile management arrives with the voice phase.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            state.message?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(spacing.md),
                )
            }
        }
    }
}

@Composable
private fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = VoitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(VoitTheme.spacing.sm),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(option) },
            )
        }
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = VoitTheme.spacing.md, vertical = VoitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label + if (!enabled) " (disabled)" else "",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = "$label, ${if (checked) "on" else "off"}"
            },
        )
    }
}

@Composable
private fun SettingTextField(label: String, value: String, onValueChange: (String) -> Unit) {
    Column(modifier = Modifier.padding(horizontal = VoitTheme.spacing.md)) {
        VoitTextField(value = value, onValueChange = onValueChange, label = label)
    }
}
