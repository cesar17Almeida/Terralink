package com.astralink.terralink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One row of an iOS-style settings list: a tinted rounded icon tile, a title (with
 * an optional second line), an optional muted value on the right, and a chevron.
 */
data class SettingsRowSpec(
    val icon: ImageVector,
    val title: String,
    val onClick: () -> Unit,
    val value: String? = null,
    val subtitle: String? = null,
    val container: Color? = null,   // icon tile fill; defaults to surfaceVariant
    val content: Color? = null,     // icon tint; defaults to onSurfaceVariant
    val showChevron: Boolean = true,
)

// Left inset so dividers line up with the title, not the icon tile.
private val ROW_TEXT_INSET = 58.dp

/**
 * A grouped, inset settings block (the iOS "Settings" look): an optional uppercase
 * header above a rounded card whose rows are split by hairline dividers. Rows are
 * declarative so the divider logic stays in one place.
 */
@Composable
fun SettingsGroup(
    rows: List<SettingsRowSpec>,
    modifier: Modifier = Modifier,
    header: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                text = header.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column {
                rows.forEachIndexed { i, row ->
                    if (i > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = ROW_TEXT_INSET),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 1.dp,
                        )
                    }
                    SettingsRow(row)
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(row: SettingsRowSpec) {
    val tile = row.container ?: MaterialTheme.colorScheme.surfaceVariant
    val onTile = row.content ?: MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = row.onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(30.dp).background(tile, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(row.icon, contentDescription = null, tint = onTile, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (row.subtitle != null) {
                Text(
                    text = row.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (row.value != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = row.value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.showChevron) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = TerraIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
