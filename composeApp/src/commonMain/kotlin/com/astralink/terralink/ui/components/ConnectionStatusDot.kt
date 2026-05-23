package com.astralink.terralink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment

private val ConnectedColor = Color(0xFF22C55E)
private val DisconnectedColor = Color(0xFF9CA3AF)

@Composable
fun ConnectionStatusDot(connected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(10.dp)
            .background(
                color = if (connected) ConnectedColor else DisconnectedColor,
                shape = CircleShape,
            ),
    )
}

@Composable
fun ConnectionStatusChip(connected: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectionStatusDot(connected = connected)
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (connected) "Conectada" else "Desconectada",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
