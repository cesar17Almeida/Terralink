package com.astralink.terralink.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Minimal action tile for DeviceScreen. Flat OutlinedCard (no shadow), thin
 * border, monochrome vector icon, light title weight. Compact 130 dp height so
 * two tiles per row stay balanced. Disabled tiles fade and lose ripple.
 */
@Composable
fun PremiumTile(
    title: String,
    caption: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val contentColor =
        if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor =
        if (enabled) MaterialTheme.colorScheme.outlineVariant
        else MaterialTheme.colorScheme.surfaceVariant
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .height(130.dp)
            .let { if (enabled) it.clickable(onClick = onClick) else it },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
