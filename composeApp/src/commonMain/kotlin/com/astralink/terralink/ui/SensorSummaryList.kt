// A read-only list of the sensors a station already has: an icon per type, its
// name and where it is plugged in. Used where the sensors are shown but not
// edited -- the first-run wizard's sensor step -- so nothing here is clickable.
package com.astralink.terralink.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ble.protocol.SensorInfo
import com.astralink.terralink.ui.components.TerraIcons

/** Left inset so dividers line up with the title, not the icon tile. */
private val ROW_TEXT_INSET = 58.dp

/** The glyph that stands for a sensor type in lists and summaries. */
internal fun sensorTypeIcon(type: String): ImageVector = when (type) {
    "sdi12_aquacheck", "sdi12_generic" -> TerraIcons.Layers
    "analog_linear" -> TerraIcons.ShowChart
    "onewire_ds18b20" -> TerraIcons.Thermostat
    "dht11" -> TerraIcons.WaterDrop
    "hc_sr04" -> TerraIcons.Straighten
    "actuator" -> TerraIcons.ToggleOn
    else -> TerraIcons.Sensors
}

/** Where a sensor sits, in one line: pin, slot and whatever else identifies it. */
internal fun sensorPlacementLine(sensor: SensorInfo): String = buildString {
    append("GP${sensor.gpio}")
    sensor.gpio2?.let { append(" · echo GP$it") }
    append(" · puerto ${sensor.port}")
    if (sensor.type.startsWith("sdi12") && sensor.addr.isNotBlank()) append(" · addr ${sensor.addr}")
}

@Composable
internal fun SensorSummaryList(sensors: List<SensorInfo>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column {
            sensors.forEachIndexed { i, sensor ->
                if (i > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = ROW_TEXT_INSET),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
                SensorSummaryRow(sensor)
            }
        }
    }
}

@Composable
private fun SensorSummaryRow(sensor: SensorInfo) {
    val output = isOutputType(sensor.type)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                sensorTypeIcon(sensor.type), contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                sensorTypeLabel(sensor.type),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                sensorPlacementLine(sensor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // An actuator has no cadence: saying so beats leaving the column blank.
        Text(
            when {
                output -> "salida"
                sensor.intervalS > 0 -> "cada ${intervalHuman(sensor.intervalS)}"
                else -> "cadencia global"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
