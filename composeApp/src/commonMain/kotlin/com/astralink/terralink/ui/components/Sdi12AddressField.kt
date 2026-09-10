// The SDI-12 address field, which asks the probe before it asks the installer.
// The address lives inside the sensor (factory default '0'), so making someone
// read it off a datasheet is a question the station can answer itself: `?!` means
// "whoever is there, state your address" and works whenever a single probe sits on
// the wire -- which is always true here, since every SDI-12 sensor gets its own GPIO.
package com.astralink.terralink.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** How many times to ask the probe before falling back to asking the installer. */
const val SDI12_PROBE_ATTEMPTS = 3

/** Pause between attempts: a probe that was still powering up may answer the next one. */
private const val PROBE_RETRY_DELAY_MS = 800L

/**
 * Legal SDI-12 addresses are '0'-'9', 'A'-'Z', 'a'-'z' (62 of them). Explicit
 * ranges, not Char.isDigit()/isLetter(), which are Unicode-aware and would accept
 * characters the protocol cannot put on the wire.
 */
fun isValidSdi12Address(text: String): Boolean =
    text.length == 1 && (text[0] in '0'..'9' || text[0] in 'A'..'Z' || text[0] in 'a'..'z')

/**
 * The reply to `?!` is the address on its own. Anything else -- the firmware's
 * "(sin respuesta)" when nothing answered, or the garbage two probes talking over
 * each other produce -- is not an address, and we say so instead of guessing.
 */
fun parseSdi12Address(lines: List<String>): String? =
    lines.map { it.trim() }.firstOrNull { isValidSdi12Address(it) }

private sealed class ProbeState {
    data object Asking : ProbeState()
    data class Found(val address: String) : ProbeState()
    data object Unanswered : ProbeState()     // gave up; the installer decides
    data object Manual : ProbeState()         // installer took over
}

/**
 * @param gpio the data pin already chosen in step 2; null disables probing.
 * @param onProbe one `?!` round trip, or null when there is no live station
 *        (the field then behaves as a plain text input).
 */
@Composable
fun Sdi12AddressField(
    value: String,
    onValueChange: (String) -> Unit,
    gpio: Int?,
    onProbe: (suspend (gpio: Int) -> String?)?,
    modifier: Modifier = Modifier,
    supporting: (@Composable () -> Unit)? = null,
) {
    // Callbacks are usually built inline at the call site, so a new lambda arrives on
    // every recomposition. Keying state or the effect on them would restart the probe
    // forever; the pin is the only thing that should.
    val probe by rememberUpdatedState(onProbe)
    val emit by rememberUpdatedState(onValueChange)
    val current by rememberUpdatedState(value)
    val canProbe = gpio != null && onProbe != null

    var state by remember(gpio) {
        mutableStateOf(if (canProbe) ProbeState.Asking else ProbeState.Manual)
    }
    var attempt by remember(gpio) { mutableStateOf(1) }

    // Restarts when the pin changes -- which also cancels a probe of the old pin --
    // and when the installer asks to retry. Leaving Asking cancels the loop, so
    // taking over manually mid-probe can never overwrite what they just typed.
    LaunchedEffect(gpio, state is ProbeState.Asking) {
        val fn = probe
        if (state !is ProbeState.Asking || gpio == null || fn == null) return@LaunchedEffect
        repeat(SDI12_PROBE_ATTEMPTS) { i ->
            attempt = i + 1
            // NOT runCatching: it would swallow CancellationException too, and the
            // loop would carry on after the installer took over -- overwriting what
            // they just typed on the last iteration, where no delay() re-checks it.
            val found = try {
                fn(gpio)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                null
            }
            if (found != null) {
                emit(found)
                state = ProbeState.Found(found)
                return@LaunchedEffect
            }
            if (i < SDI12_PROBE_ATTEMPTS - 1) delay(PROBE_RETRY_DELAY_MS)
        }
        // Nothing answered. Fall back to the factory default and let the installer
        // confirm or correct it, rather than blocking on a value we can't read.
        if (current.isBlank()) emit("0")
        state = ProbeState.Unanswered
    }

    Column(modifier.fillMaxWidth()) {
        when (val st = state) {
            is ProbeState.Asking -> ProbeCard(ProbeScene.ASKING) {
                Text(
                    "Preguntando su dirección a la sonda…",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Comando ?! en GP$gpio · la sonda contesta con su dirección.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AttemptDots(attempt)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Intento $attempt de $SDI12_PROBE_ATTEMPTS",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { state = ProbeState.Manual }) { Text("Escribirla") }
                }
            }

            is ProbeState.Found -> ProbeCard(ProbeScene.FOUND) {
                Text(
                    "DIRECCIÓN DETECTADA",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AddressBadge(st.address)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "La sonda respondió al comando ?! en GP$gpio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { state = ProbeState.Manual }) { Text("Cambiar") }
                }
            }

            ProbeState.Unanswered -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProbeCard(ProbeScene.FAILED) {
                    Text(
                        "La sonda no contestó",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Tras $SDI12_PROBE_ATTEMPTS intentos en GP$gpio. Comprueba el cableado " +
                            "(datos, 3V3 y GND), o déjala en 0, el valor de fábrica.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = { attempt = 1; state = ProbeState.Asking }) {
                        Text("Reintentar")
                    }
                }
                AddressInput(value, onValueChange, supporting)
            }

            ProbeState.Manual -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AddressInput(value, onValueChange, supporting)
                // Only worth offering when there is a live station on a chosen pin.
                if (canProbe) {
                    TextButton(onClick = { attempt = 1; state = ProbeState.Asking }) {
                        Text("Detectarla automáticamente")
                    }
                }
            }
        }
    }
}

/** The illustration over its caption: one card for every moment of the handshake. */
@Composable
private fun ProbeCard(scene: ProbeScene, content: @Composable ColumnScope.() -> Unit) {
    val found = scene == ProbeScene.FOUND
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
        border = BorderStroke(
            width = if (found) 1.5.dp else 1.dp,
            color = if (found) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Sdi12ProbeAnimation(scene)
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** One dot per attempt: filled up to the one running, so the wait has an end in sight. */
@Composable
private fun AttemptDots(attempt: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(SDI12_PROBE_ATTEMPTS) { i ->
            val done = i < attempt
            Box(
                Modifier
                    .size(if (done) 8.dp else 6.dp)
                    .background(
                        if (done) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
            )
        }
    }
}

/** The address the probe gave back, as a plate: one character, monospaced. */
@Composable
private fun AddressBadge(address: String) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            address,
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun AddressInput(
    value: String,
    onValueChange: (String) -> Unit,
    supporting: (@Composable () -> Unit)?,
) {
    val invalid = value.isNotBlank() && !isValidSdi12Address(value)
    TerraTextField(
        value = value,
        onValueChange = { onValueChange(it.take(1)) },
        label = "Dirección SDI-12",
        isError = invalid,
        supportingText = {
            if (invalid) {
                Text(
                    "Sólo un carácter: 0-9, A-Z o a-z.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                supporting?.invoke() ?: Text("Normalmente '0'")
            }
        },
    )
}
