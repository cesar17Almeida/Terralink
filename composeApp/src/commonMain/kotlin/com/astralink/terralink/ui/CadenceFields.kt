// The cadence step: how often the station wakes up, reads this sensor and stores
// the value. It is the one setting whose cost is not obvious from its number, so
// it is drawn rather than only typed -- a clock, a rhythm strip and the day's total.
package com.astralink.terralink.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.astralink.terralink.ui.components.CadenceClock
import com.astralink.terralink.ui.components.CadenceRhythm
import com.astralink.terralink.ui.components.TerraIcons
import com.astralink.terralink.ui.components.TerraTextField
import com.astralink.terralink.ui.components.cadenceWindowS

/** Presets: the intervals a field install actually uses, coarse enough to tap. */
private val CADENCE_PRESETS = listOf(
    60 to "1 min", 300 to "5 min", 900 to "15 min",
    1800 to "30 min", 3600 to "1 h", 21_600 to "6 h",
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun CadenceFields(draft: SensorDraft, onChange: (SensorDraft) -> Unit) {
    val custom = !draft.followGlobal
    val v = draft.intervalText.trim().toIntOrNull()
    val valid = v != null && v in INTERVAL_MIN_S..INTERVAL_MAX_S

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CadenceExplainer()

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CadenceModeCard(
                icon = TerraIcons.Sync,
                title = "La de la estación",
                caption = "El mismo ritmo que el resto",
                selected = draft.followGlobal,
                modifier = Modifier.weight(1f),
            ) { onChange(draft.copy(followGlobal = true)) }
            CadenceModeCard(
                icon = TerraIcons.Schedule,
                title = "Personalizada",
                caption = "Sólo para este sensor",
                selected = custom,
                modifier = Modifier.weight(1f),
            ) { onChange(draft.copy(followGlobal = false)) }
        }

        if (!custom) {
            CadenceNote(
                "Usará la cadencia global de la estación (Configuración → Captura de sensores). " +
                    "Cambiarla allí afecta a todos los sensores que no tengan la suya.",
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CADENCE_PRESETS.forEach { (s, l) ->
                    FilterChip(
                        selected = v == s,
                        onClick = { onChange(draft.copy(intervalText = s.toString())) },
                        label = { Text(l) },
                    )
                }
            }
            TerraTextField(
                value = draft.intervalText,
                onValueChange = { onChange(draft.copy(intervalText = it.filter { c -> c.isDigit() })) },
                label = "Intervalo (segundos)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = draft.intervalText.isNotBlank() && !valid,
                supportingText = {
                    if (draft.intervalText.isNotBlank() && !valid) {
                        Text("Entre $INTERVAL_MIN_S s (1 min) y $INTERVAL_MAX_S s (24 h)")
                    } else {
                        Text(v?.let { "= ${intervalHuman(it)}" } ?: "")
                    }
                },
            )
            if (valid) CadencePreview(v)
            // The probe's own limit, not the firmware's: asking faster returns the same value.
            if (draft.type == "sdi12_aquacheck") {
                CadenceNote("La sonda AquaCheck admite como mucho una lectura por minuto.")
            }
        }
    }
}

/** What a cadence *is*, once, at the top of the step. */
@Composable
private fun CadenceExplainer() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CadenceClock()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Cada cuánto se mide",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "La estación despierta, lee este sensor y guarda el valor. Más a menudo " +
                    "da más detalle y gasta más batería.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The chosen interval as a rhythm plus the two numbers that make it concrete. */
@Composable
private fun CadencePreview(intervalS: Int) {
    val perDay = 86_400 / intervalS
    val windowLabel = if (cadenceWindowS(intervalS) == 3600) "+1 h" else "+24 h"
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Una lectura cada ${intervalHuman(intervalS)} · $perDay al día",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            CadenceRhythm(intervalS, Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CadenceTimeLabel("ahora")
                CadenceTimeLabel(windowLabel)
            }
            if (intervalS < 300) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Muy frecuente: la estación duerme menos y la batería dura bastante menos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CadenceTimeLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CadenceNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Global vs. own cadence, as two boxes: the fork is a choice, not a checkbox. */
@Composable
private fun CadenceModeCard(
    icon: ImageVector,
    title: String,
    caption: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedCard(
        modifier = modifier.height(112.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) scheme.primaryContainer else scheme.surface,
        ),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) scheme.primary else scheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Icon(
                icon, contentDescription = null, modifier = Modifier.size(24.dp),
                tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
            )
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) scheme.onPrimaryContainer.copy(alpha = 0.8f)
                else scheme.onSurfaceVariant,
            )
        }
    }
}
