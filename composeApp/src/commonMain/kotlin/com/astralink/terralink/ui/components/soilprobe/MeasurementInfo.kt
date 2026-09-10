package com.astralink.terralink.ui.components.soilprobe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ui.components.TerraDialog
import com.astralink.terralink.ui.components.TerraIcons

/** The (i) in the card header: opens the note explaining what the numbers mean. */
@Composable
fun MeasurementInfoButton(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier.size(32.dp)) {
        Icon(
            imageVector = TerraIcons.Info,
            contentDescription = "Qué significan estos valores",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
    if (open) MeasurementInfoDialog(onDismiss = { open = false })
}

@Composable
private fun MeasurementInfoDialog(onDismiss: () -> Unit) {
    TerraDialog(
        onDismiss = onDismiss,
        title = "Qué estás viendo",
        confirmText = "Entendido",
        onConfirm = onDismiss,
        dismissText = null,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            InfoBlock(
                swatch = MaterialTheme.colorScheme.primary,
                title = "Humedad en SFU",
                body = "Unidad propia de la sonda AquaCheck: ${MOISTURE_SCALE_MAX.toInt()} es agua y 0 " +
                    "es aire, así que cuanta más agua retiene el suelo, mayor es el número. " +
                    "La barra gruesa lo sitúa sobre ese máximo.",
            )
            InfoBlock(
                swatch = TemperatureBarColor,
                title = "Temperatura",
                body = "Grados centígrados del suelo a esa misma profundidad. " +
                    "La barra fina la sitúa entre ${TEMPERATURE_SCALE_MIN.toInt()} y " +
                    "${TEMPERATURE_SCALE_MAX.toInt()} °C.",
            )
            Text(
                text = "Cada fila es un sensor de la sonda, del más superficial al más profundo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoBlock(swatch: Color, title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(swatch, RoundedCornerShape(2.dp)))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
