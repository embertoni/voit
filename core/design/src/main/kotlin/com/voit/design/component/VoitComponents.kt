package com.voit.design.component

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import com.voit.design.theme.VoitTheme

/**
 * Reusable monochrome components. All interactive elements set content descriptions and
 * keep comfortable (>= 48dp) touch targets. State is NEVER signalled by colour alone;
 * callers pass explicit labels / icons / shapes.
 */

/** A standard screen scaffold with an optional [VoitTopBar] already wired. */
@Composable
fun VoitScaffold(
    modifier: Modifier = Modifier,
    title: String? = null,
    onNavigateBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            if (title != null) {
                VoitTopBar(title = title, onNavigateBack = onNavigateBack, actions = actions)
            }
        },
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoitTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = { Text(text = title, style = MaterialTheme.typography.headlineSmall) },
        navigationIcon = {
            if (onNavigateBack != null) {
                VoitIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    onClick = onNavigateBack,
                )
            }
        },
        actions = { Row { actions() } },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

/** A primary action button (outlined, flat) with a mandatory accessible label. */
@Composable
fun VoitButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .defaultMinSize(minHeight = VoitTheme.spacing.minTouchTarget)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** An icon-only button with a required content description for TalkBack. */
@Composable
fun VoitIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.sizeIn(
            minWidth = VoitTheme.spacing.minTouchTarget,
            minHeight = VoitTheme.spacing.minTouchTarget,
        ),
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

/** A single-line / multiline text field with a visible label (also used as a11y label). */
@Composable
fun VoitTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        shape = MaterialTheme.shapes.small,
        textStyle = MaterialTheme.typography.bodyLarge,
    )
}

/**
 * A flat list row. [leading] can carry an icon/shape marker so meaning is never conveyed
 * by colour alone. The whole row exposes a single accessible description when provided.
 */
@Composable
fun VoitListItem(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    overline: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    rowContentDescription: String? = null,
) {
    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = VoitTheme.spacing.minTouchTarget)
        .let { m -> if (onClick != null) m.clickable { onClick() } else m }
        .padding(horizontal = VoitTheme.spacing.md, vertical = VoitTheme.spacing.sm)
        .let { m ->
            if (rowContentDescription != null) {
                m.semantics { contentDescription = rowContentDescription }
            } else {
                m
            }
        }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VoitTheme.spacing.md),
    ) {
        if (leading != null) {
            Box(contentAlignment = Alignment.Center) { leading() }
        }
        Column(modifier = Modifier.weight(1f)) {
            if (overline != null) {
                Text(text = overline, style = MaterialTheme.typography.labelSmall)
            }
            Text(text = headline, style = MaterialTheme.typography.titleMedium)
            if (supporting != null) {
                Text(text = supporting, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (trailing != null) {
            Box(contentAlignment = Alignment.Center) { trailing() }
        }
    }
}

/** A section header separating groups of content. Uppercase label, generous top space. */
@Composable
fun VoitSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = VoitTheme.spacing.md,
                end = VoitTheme.spacing.md,
                top = VoitTheme.spacing.lg,
                bottom = VoitTheme.spacing.xs,
            ),
    )
}
