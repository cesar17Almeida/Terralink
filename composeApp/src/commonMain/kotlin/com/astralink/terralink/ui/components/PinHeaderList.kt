// The 40-pin header as a list: one pin per row with its physical number and its
// alternate functions, drawn either side of the board's silhouette. Replaces the
// canvas board in browse mode, where reading the pin names matters more than the
// board's shape. Tapping a pin selects it; the filter dims what doesn't match.
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** The three buckets the pin map counts, filters and colours by. A GPIO the
 *  firmware reserved (radio, wake button, LoRa UART) reads as system too: the
 *  installer can't have it either way. */
enum class PinState { FREE, USED, SYSTEM }

fun PinCell.pinState(): PinState = when {
    role != PinRole.GPIO || live == PinLive.RESERVED -> PinState.SYSTEM
    live == PinLive.IN_USE -> PinState.USED
    else -> PinState.FREE
}

/** BROWSE just reports the map (colour = state). PICK is the sensor wizard: the
 *  pin the installer chose reads as *theirs*, in the selection colour, so it never
 *  reads as one more occupied pin. */
enum class PinListMode { BROWSE, PICK }

/** Tones shared by the pin map's list and its detail bar, derived from the theme
 *  so this screen stays in the app's palette instead of carrying its own hexes. */
internal data class PinTones(
    val accent: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    val ghost: Color,
    val hairline: Color,
    val board: Color,
    val boardEdge: Color,
    val freeDot: Color,
    val freeEdge: Color,
    val sysDot: Color,
    val sysInk: Color,
    val pick: Color,
)

@Composable
internal fun pinTones(): PinTones {
    val cs = MaterialTheme.colorScheme
    return PinTones(
        accent = cs.primary,
        ink = cs.onSurface,
        muted = cs.onSurfaceVariant,
        faint = cs.onSurfaceVariant.copy(alpha = 0.55f),
        ghost = cs.onSurfaceVariant.copy(alpha = 0.38f),
        hairline = cs.outlineVariant.copy(alpha = 0.5f),
        board = cs.surfaceVariant.copy(alpha = 0.45f),
        boardEdge = cs.outlineVariant.copy(alpha = 0.6f),
        freeDot = cs.surfaceContainerLowest,
        freeEdge = cs.outline.copy(alpha = 0.5f),
        sysDot = cs.outlineVariant.copy(alpha = 0.85f),
        sysInk = cs.onSurfaceVariant.copy(alpha = 0.48f),
        pick = cs.tertiary,
    )
}

internal val Mono = FontFamily.Monospace

private val BOARD_WIDTH = 62.dp
private val DOT_BOX = 19.dp     // 11 dp pad + the 4 dp selection ring on each side
private val DOT = 11.dp
private val NUM_WIDTH = 15.dp
private const val DIM_ALPHA = 0.15f

/**
 * @param cells the merged header (see [mergePinmap]).
 * @param selected physical pin number currently open in the detail bar.
 * @param isVisible false dims the pin instead of hiding it, so the board keeps
 *        its shape while a filter is on.
 */
@Composable
fun PinHeaderList(
    cells: List<PinCell>,
    selected: Int?,
    onSelect: (PinCell) -> Unit,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 44.dp,
    showFunctions: Boolean = true,
    mode: PinListMode = PinListMode.BROWSE,
    isVisible: (PinCell) -> Boolean = { true },
) {
    val t = pinTones()
    val left = remember(cells) { cells.filter { it.side == PinSide.LEFT }.sortedBy { it.order } }
    val right = remember(cells) { cells.filter { it.side == PinSide.RIGHT }.sortedBy { it.order } }
    val boardShape = RoundedCornerShape(12.dp)

    Box(modifier.fillMaxWidth()) {
        // Backdrop, painted under the rows: the USB tab first, then the board over it.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 22.dp)
                .size(width = 36.dp, height = 11.dp)
                .background(t.board, RoundedCornerShape(3.dp, 3.dp, 1.dp, 1.dp))
                .border(1.dp, t.boardEdge, RoundedCornerShape(3.dp, 3.dp, 1.dp, 1.dp)),
        )
        Column(
            Modifier.matchParentSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            Box(
                Modifier
                    .weight(1f)
                    .width(BOARD_WIDTH)
                    .background(t.board, boardShape)
                    .border(1.dp, t.boardEdge, boardShape),
            )
        }

        Column(Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(44.dp))
            left.forEachIndexed { i, l ->
                PinRow(
                    left = l,
                    right = right.getOrNull(i) ?: l,
                    rowHeight = rowHeight,
                    showFunctions = showFunctions,
                    selected = selected,
                    onSelect = onSelect,
                    isVisible = isVisible,
                    mode = mode,
                    tones = t,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "RP2040",
                modifier = Modifier.fillMaxWidth(),
                style = TextStyle(
                    fontFamily = Mono, fontSize = 7.sp, letterSpacing = 0.24.em,
                    color = t.ghost, textAlign = TextAlign.Center,
                ),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PinRow(
    left: PinCell,
    right: PinCell,
    rowHeight: Dp,
    showFunctions: Boolean,
    selected: Int?,
    onSelect: (PinCell) -> Unit,
    isVisible: (PinCell) -> Boolean,
    mode: PinListMode,
    tones: PinTones,
) {
    Row(
        Modifier.fillMaxWidth().height(rowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PinLabel(
            cell = left, alignEnd = true, dim = !isVisible(left),
            isSelected = selected == left.physical, showFunctions = showFunctions,
            mode = mode, tones = tones, onClick = { onSelect(left) },
            modifier = Modifier.weight(1f),
        )
        Row(
            Modifier.width(BOARD_WIDTH).padding(horizontal = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PinDot(left, selected == left.physical, !isVisible(left), mode, tones) { onSelect(left) }
            PinDot(right, selected == right.physical, !isVisible(right), mode, tones) { onSelect(right) }
        }
        PinLabel(
            cell = right, alignEnd = false, dim = !isVisible(right),
            isSelected = selected == right.physical, showFunctions = showFunctions,
            mode = mode, tones = tones, onClick = { onSelect(right) },
            modifier = Modifier.weight(1f),
        )
    }
}

/** One pin's text block. [alignEnd] is the left column: number outboard, name
 *  right-aligned against the board. The right column mirrors it. */
@Composable
private fun PinLabel(
    cell: PinCell,
    alignEnd: Boolean,
    dim: Boolean,
    isSelected: Boolean,
    showFunctions: Boolean,
    mode: PinListMode,
    tones: PinTones,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = cell.pinState()
    val chosen = isSelected && mode == PinListMode.PICK
    val highlight = if (mode == PinListMode.PICK) tones.pick else tones.accent
    val shape = RoundedCornerShape(9.dp)
    val number = @Composable {
        Text(
            cell.physical.toString().padStart(2, '0'),
            modifier = Modifier.width(NUM_WIDTH),
            style = TextStyle(
                fontFamily = Mono, fontSize = 8.sp, letterSpacing = 0.04.em, color = tones.ghost,
                textAlign = if (alignEnd) TextAlign.Start else TextAlign.End,
            ),
        )
    }

    Row(
        modifier = modifier
            .alpha(if (dim) DIM_ALPHA else 1f)
            .clip(shape)
            .background(if (isSelected) highlight.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(
                start = if (alignEnd) 3.dp else 11.dp,
                end = if (alignEnd) 11.dp else 3.dp,
                top = 5.dp, bottom = 5.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (alignEnd) {
            number()
            Spacer(Modifier.width(9.dp))
        }
        Column(
            Modifier.weight(1f),
            horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        ) {
            Text(
                cell.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = Mono,
                    fontSize = 12.5.sp,
                    letterSpacing = 0.01.em,
                    fontWeight = if (state == PinState.USED || chosen) FontWeight.SemiBold
                        else FontWeight.Medium,
                    color = when {
                        chosen -> tones.pick
                        state == PinState.USED -> tones.accent
                        state == PinState.SYSTEM -> tones.sysInk
                        else -> tones.ink
                    },
                ),
            )
            if (showFunctions && cell.fns.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    cell.fns,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontFamily = Mono, fontSize = 8.sp,
                        letterSpacing = 0.02.em, color = tones.faint,
                    ),
                )
            }
        }
        if (!alignEnd) {
            Spacer(Modifier.width(9.dp))
            number()
        }
    }
}

@Composable
private fun PinDot(
    cell: PinCell,
    isSelected: Boolean,
    dim: Boolean,
    mode: PinListMode,
    tones: PinTones,
    onClick: () -> Unit,
) {
    val chosen = isSelected && mode == PinListMode.PICK
    val highlight = if (mode == PinListMode.PICK) tones.pick else tones.accent
    val (fill, edge) = when {
        chosen -> tones.pick to tones.pick
        cell.pinState() == PinState.USED -> tones.accent to tones.accent
        cell.pinState() == PinState.SYSTEM -> tones.sysDot to tones.sysDot
        else -> tones.freeDot to tones.freeEdge
    }
    Box(
        Modifier
            .size(DOT_BOX)
            .alpha(if (dim) DIM_ALPHA else 1f)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Box(Modifier.size(DOT_BOX).background(highlight.copy(alpha = 0.2f), CircleShape))
        }
        Box(Modifier.size(DOT).background(fill, CircleShape).border(1.5.dp, edge, CircleShape))
    }
}
