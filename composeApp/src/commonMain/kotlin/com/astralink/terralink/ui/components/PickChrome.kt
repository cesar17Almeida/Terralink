// Chrome shared by the pickers that place something on the board (a sensor, the
// LoRa module): the filter chips above it and the strip that names the choice --
// or says why the last tap was refused.
package com.astralink.terralink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** "Compatibles · n" / "Todos": whether the board dims what the thing being placed can't take. */
@Composable
internal fun ScopeChips(onlyCompatible: Boolean, eligible: Int, t: PinTones, onPick: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        PickChip("Compatibles · $eligible", onlyCompatible, t.accent, t) { onPick(true) }
        PickChip("Todos", !onlyCompatible, t.accent, t) { onPick(false) }
    }
}

@Composable
internal fun PickChip(label: String, on: Boolean, accent: Color, t: PinTones, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .height(31.dp)
            .background(if (on) accent else Color.Transparent, shape)
            .border(1.dp, if (on) accent else t.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            style = TextStyle(
                fontFamily = Mono, fontSize = 8.5.sp, letterSpacing = 0.12.em,
                color = if (on) MaterialTheme.colorScheme.onPrimary else t.muted,
            ),
        )
    }
}

/**
 * Same card as the pin map's detail bar, un-elevated: it lives inside the wizard's
 * scroll, above the board, because the wizard already owns the footer.
 * @param title the choice ("GP16", "GP16 · GP17"); null = nothing chosen yet.
 * @param tag small print beside the title (the physical pin numbers).
 * @param fns the choice's functions, under the title; null or blank = none.
 * @param refused why the last tap was turned down; shown instead of the choice.
 */
@Composable
internal fun PickStrip(
    title: String?,
    tag: String?,
    fns: String?,
    refused: String?,
    placeholder: String,
    t: PinTones,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, shape)
            .border(1.dp, if (refused != null) MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                else t.hairline, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        when {
            refused != null -> Text(
                refused,
                style = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.error),
            )
            title != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(
                            title,
                            modifier = Modifier.alignByBaseline(),
                            style = TextStyle(
                                fontFamily = Mono, fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.02).em, color = t.pick,
                            ),
                        )
                        if (tag != null) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                tag,
                                modifier = Modifier.alignByBaseline(),
                                style = TextStyle(
                                    fontFamily = Mono, fontSize = 8.5.sp,
                                    letterSpacing = 0.14.em, color = t.muted.copy(alpha = 0.7f),
                                ),
                            )
                        }
                    }
                    if (!fns.isNullOrBlank()) {
                        Spacer(Modifier.height(7.dp))
                        Text(
                            fns.uppercase(),
                            style = TextStyle(
                                fontFamily = Mono, fontSize = 8.5.sp,
                                letterSpacing = 0.08.em, color = t.faint,
                            ),
                        )
                    }
                }
                Box(
                    Modifier
                        .height(23.dp)
                        .background(t.pick.copy(alpha = 0.12f), RoundedCornerShape(percent = 50))
                        .padding(horizontal = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "ELEGIDO",
                        style = TextStyle(
                            fontFamily = Mono, fontSize = 8.5.sp,
                            letterSpacing = 0.1.em, color = t.pick,
                        ),
                    )
                }
            }
            else -> Text(
                placeholder,
                style = TextStyle(
                    fontFamily = Mono, fontSize = 8.5.sp, letterSpacing = 0.12.em, color = t.faint,
                ),
            )
        }
    }
}
