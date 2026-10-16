package com.voit.feature.commands

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voit.design.component.VoitButton
import com.voit.design.component.VoitIconButton
import com.voit.design.component.VoitScaffold
import com.voit.design.component.VoitSectionHeader
import com.voit.design.component.VoitTextField
import com.voit.design.theme.VoitTheme
import com.voit.domain.ConfirmationPolicy

/**
 * Command editor. The user types a name, an intent, trigger phrases and a confirmation
 * policy, then adds actions ONLY by selecting from the allow-listed registry chips. There
 * is deliberately no free-text action field: a command can never contain arbitrary code.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandEditorScreen(
    viewModel: CommandsViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val draft = state.draft
    val spacing = VoitTheme.spacing

    VoitScaffold(
        modifier = modifier,
        title = if (draft.id == null) "New command" else "Edit command",
        onNavigateBack = onDone,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            VoitTextField(value = draft.name, onValueChange = viewModel::setName, label = "Name")
            VoitTextField(
                value = draft.intent,
                onValueChange = viewModel::setIntent,
                label = "Intent (canonical name)",
            )
            VoitTextField(
                value = draft.triggerPhrases.joinToString(", "),
                onValueChange = viewModel::setTriggerPhrases,
                label = "Trigger phrases (comma separated)",
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = if (draft.enabled) "Enabled" else "Disabled",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = draft.enabled,
                    onCheckedChange = viewModel::setEnabled,
                )
            }

            VoitSectionHeader(text = "Confirmation policy")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                ConfirmationPolicy.entries.forEach { policy ->
                    FilterChip(
                        selected = draft.confirmationPolicy == policy,
                        onClick = { viewModel.setConfirmationPolicy(policy) },
                        label = { Text(policy.name) },
                    )
                }
            }

            VoitSectionHeader(text = "Add action (allow-listed only)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                state.availableActions.forEach { type ->
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.addAction(type) },
                        label = { Text(type.name) },
                    )
                }
            }

            VoitSectionHeader(text = "Command actions (${draft.actions.size}/${CommandsViewModel.MAX_ACTIONS})")
            if (draft.actions.isEmpty()) {
                Text(
                    text = "No actions yet. Pick from the chips above.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            draft.actions.forEachIndexed { index, action ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}. ${action.type.name}",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        VoitIconButton(
                            icon = Icons.Filled.Delete,
                            contentDescription = "Remove ${action.type.name}",
                            onClick = { viewModel.removeActionAt(index) },
                        )
                    }
                    // Per-action argument inputs, one per declared schema parameter. Each is a
                    // plain text field; the ViewModel parses the raw string into the typed
                    // ParamValue at save time (so free text is never passed untyped).
                    viewModel.paramNames(action.type).forEach { param ->
                        val required = viewModel.isParamRequired(action.type, param)
                        VoitTextField(
                            value = action.rawArgs[param].orEmpty(),
                            onValueChange = { viewModel.setActionArg(index, param, it) },
                            label = param + if (required) " (required)" else " (optional)",
                        )
                    }
                }
            }

            state.error?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            VoitButton(
                text = "Save command",
                onClick = { viewModel.saveDraft(onSaved = onDone) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
