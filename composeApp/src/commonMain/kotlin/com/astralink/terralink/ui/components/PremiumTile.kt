package com.astralink.terralink.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimal action tile for DeviceScreen. Flat OutlinedCard (no shadow),
 * thin border, monochrome glyph, light title weight. Compact 130 dp
 * height so two rows of tiles share the same vertical space the old
 * elevated tiles used for one. Disabled tiles fade and lose ripple.
 */
@Composable
fun PremiumTile(
    title: String,
    caption: String,
    glyph: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
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
            Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = glyph,
                    fontSize = 22.sp,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
