package com.nfaalerts.collector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.nfaalerts.collector.ui.theme.RgdsTheme

@Composable
internal fun RgdsCard(
    title: String,
    subtitle: String,
    badgeLabel: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = RgdsTheme.elevation.card,
        modifier =
            modifier
                .fillMaxWidth()
                .sizeIn(minHeight = RgdsTheme.spacing.cardMinHeight),
    ) {
        Column(
            modifier = Modifier.padding(RgdsTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(RgdsTheme.spacing.cardGap),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RgdsTheme.spacing.cardGap),
            ) {
                RgdsCardBadge(badgeLabel)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(RgdsTheme.spacing.xxs),
                ) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(subtitle, style = MaterialTheme.typography.bodyLarge)
                }
            }
            content()
        }
    }
}

@Composable
internal fun RgdsCardBadge(
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = RgdsTheme.spacing.iconHuge,
) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.size(size),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = label.take(3).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun RgdsEmptyState(
    title: String,
    message: String,
    badgeLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    RgdsCard(
        title = title,
        subtitle = message,
        badgeLabel = badgeLabel,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
        content = content,
    )
}
