package com.voit.feature.commands

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voit.design.component.VoitIconButton
import com.voit.design.component.VoitListItem
import com.voit.design.component.VoitScaffold
import com.voit.design.theme.VoitTheme

/**
 * Command list. Commands are built only from the typed allow-listed registry (see the
 * editor). Opening the editor is how a command is created or changed.
 */
@Composable
fun CommandsScreen(
    viewModel: CommandsViewModel,
    onNewCommand: () -> Unit,
    onEditCommand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = VoitTheme.spacing

    VoitScaffold(
        modifier = modifier,
        title = "Commands",
        floatingActionButton = {
            FloatingActionButton(onClick = {
                viewModel.startNewDraft()
                onNewCommand()
            }) {
                Icon(Icons.Filled.Add, contentDescription = "New command")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (state.commands.isEmpty()) {
                    item {
                        Text(
                            text = "No commands yet. Tap + to build one from the allow-listed actions.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(spacing.md),
                        )
                    }
                }
                items(state.commands, key = { it.id }) { command ->
                    VoitListItem(
                        headline = command.name,
                        supporting = "Intent: ${command.intent} · ${command.actions.size} action(s)",
                        overline = if (!command.enabled) "DISABLED" else null,
                        trailing = {
                            VoitIconButton(
                                icon = Icons.Filled.Delete,
                                contentDescription = "Delete command ${command.name}",
                                onClick = { viewModel.deleteCommand(command.id) },
                            )
                        },
                        onClick = {
                            viewModel.editExisting(command)
                            onEditCommand()
                        },
                        rowContentDescription = "Command ${command.name}, intent ${command.intent}",
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
