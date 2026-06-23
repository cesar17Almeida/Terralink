package com.astralink.terralink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** One row of a [SpecTable]: a muted label on the left, its value on the right. */
data class Spec(val label: String, val value: String)

/**
 * A compact read-only table for key/value specs (schedule, hardware, ...). Rows
 * sit in a softly tinted rounded surface separated by hairline dividers -- a
 * tidy, minimalist alternative to a loose list of label/value rows.
 */
@Composable
fun SpecTable(rows: List<Spec>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column {
            rows.forEachIndexed { i, row ->
                if (i > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 1.dp,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = row.value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

/**
 * Card/section heading: a small coloured accent bar + the title. The accent ties
 * each card to a theme colour while keeping a quiet, minimalist look. Defaults to
 * the primary colour.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 16.dp)
                .background(accent, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
