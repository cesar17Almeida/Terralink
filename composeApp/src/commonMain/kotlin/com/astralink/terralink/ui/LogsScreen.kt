// Live view of the station's log ring. The phone polls it every few seconds and
// stitches each window onto what it already showed, so the screen keeps more
// history than the 48 lines the station retains. Uplink attempts and downlinks
// stand out from the rest, and the list follows the newest line until the reader
// drags away or switches auto-scroll off.
package com.astralink.terralink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.astralink.terralink.ble.session.ActiveSession
import com.astralink.terralink.model.SavedStation
import com.astralink.terralink.ui.components.BackIconButton
import com.astralink.terralink.ui.components.TerraIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Poll cadence; a window is ~48 short lines, cheap over the chunked data channel. */
private const val POLL_MS = 3000L

/** What a line is about, for the marker on its row and the "Solo LoRa" filter. */
private enum class LogKind { UPLINK, DOWNLINK, LORA, OTHER }

private sealed class LogRow {
    data class Line(
        val time: String,        // "HH:MM:SS" or "+Ns" as stamped by the station; "" if none
        val tag: String,         // "LoRa", "BLE", "clock" ... ("" when the line has no tag)
        val message: String,
        val kind: LogKind,
        val warn: Boolean,
    ) : LogRow()

    /** The ring rolled over completely between two polls: lines were missed here. */
    data object Gap : LogRow()
}

private val STAMP = Regex("""^(\d{2}:\d{2}:\d{2}|\+\d+s) (.*)$""")
private val TAGGED = Regex("""^([A-Za-z][A-Za-z0-9-]*(?: downlink)?): (.*)$""")

/** One ring line -> its row. The station writes "[stamp ][! ]tag: message". */
private fun parseLine(raw: String): LogRow.Line {
    var rest = raw
    var time = ""
    STAMP.matchEntire(rest)?.let { time = it.groupValues[1]; rest = it.groupValues[2] }
    val warn = rest.startsWith("! ")
    if (warn) rest = rest.removePrefix("! ")
    var tag = ""
    var message = rest
    TAGGED.matchEntire(rest)?.let { tag = it.groupValues[1]; message = it.groupValues[2] }
    val lower = rest.lowercase()
    val kind = when {
        tag.equals("LoRa downlink", ignoreCase = true) -> LogKind.DOWNLINK
        "uplink" in lower || "cycle due" in lower -> LogKind.UPLINK
        "downlink" in lower || "signal rssi" in lower -> LogKind.DOWNLINK
        tag.equals("LoRa", ignoreCase = true) -> LogKind.LORA
        else -> LogKind.OTHER
    }
    return LogRow.Line(time, tag, message, kind, warn)
}

/**
 * Lines of [fresh] not already shown. The ring is a sliding window, so its head
 * overlaps the tail of the previous poll; the widest overlap wins. No overlap while
 * history exists means the ring rolled over completely -- reported as a gap.
 */
private fun newLines(previous: List<String>, fresh: List<String>): Pair<List<String>, Boolean> {
    for (k in minOf(previous.size, fresh.size) downTo 1) {
        if (previous.subList(previous.size - k, previous.size) == fresh.subList(0, k)) {
            return fresh.drop(k) to false
        }
    }
    return fresh to (previous.isNotEmpty() && fresh.isNotEmpty())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    station: SavedStation,
    active: ActiveSession,
    onBack: () -> Unit,
) {
    val rows = remember { mutableStateListOf<LogRow>() }
    var window by remember { mutableStateOf<List<String>>(emptyList()) }   // last poll, verbatim
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var follow by remember { mutableStateOf(true) }
    var onlyLora by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Poll for as long as the screen is open; a failed poll shows a banner and
    // keeps trying, so a BLE hiccup never leaves the list frozen without a word.
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val fresh = active.requestLogs()
                val (added, gap) = newLines(window, fresh)
                window = fresh
                if (gap) rows.add(LogRow.Gap)
                added.forEach { rows.add(parseLine(it)) }
                error = null
            } catch (e: Throwable) {
                error = e.message ?: "No se pudieron leer los logs"
            }
            loaded = true
            delay(POLL_MS)
        }
    }

    val visible = if (onlyLora) rows.filter { it is LogRow.Gap || (it as LogRow.Line).kind != LogKind.OTHER } else rows

    // Follow the newest line while auto-scroll is on.
    LaunchedEffect(visible.size, follow) {
        if (follow && visible.isNotEmpty()) listState.animateScrollToItem(visible.lastIndex)
    }
    // A drag by the reader switches auto-scroll off, so the next poll doesn't yank
    // the list away from what they are reading. The button below puts it back.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) follow = false
        }
    }
    fun resumeFollow() {
        follow = true
        scope.launch { if (visible.isNotEmpty()) listState.animateScrollToItem(visible.lastIndex) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { BackIconButton(onClick = onBack) },
                actions = {
                    TextButton(onClick = { rows.clear() }, enabled = rows.isNotEmpty()) { Text("Limpiar") }
                },
            )
        },
        floatingActionButton = {
            if (!follow) {
                SmallFloatingActionButton(onClick = ::resumeFollow) {
                    Icon(TerraIcons.ArrowDownward, contentDescription = "Ir al final y seguir")
                }
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            LogsToolbar(
                follow = follow, onFollow = { if (it) resumeFollow() else follow = false },
                onlyLora = onlyLora, onOnlyLora = { onlyLora = it },
                live = loaded && error == null,
            )
            error?.let { LogsBanner(it) }
            when {
                !loaded -> Centered { CircularProgressIndicator() }
                visible.isEmpty() -> Centered {
                    Text(
                        if (onlyLora) "Sin líneas LoRa todavía" else "Sin logs todavía",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Se actualiza cada ${POLL_MS / 1000} s", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 8.dp, end = 8.dp, top = 4.dp, bottom = 88.dp,
                    ),
                ) {
                    itemsIndexed(visible) { _, row ->
                        when (row) {
                            is LogRow.Line -> LogLineRow(row)
                            LogRow.Gap -> GapRow()
                        }
                    }
                }
            }
        }
    }
}

/** Auto-scroll and filter chips, with a live dot that says the poll is answering. */
@Composable
private fun LogsToolbar(
    follow: Boolean,
    onFollow: (Boolean) -> Unit,
    onlyLora: Boolean,
    onOnlyLora: (Boolean) -> Unit,
    live: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(selected = follow, onClick = { onFollow(!follow) }, label = { Text("Auto-scroll") })
        FilterChip(selected = onlyLora, onClick = { onOnlyLora(!onlyLora) }, label = { Text("Solo LoRa") })
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(8.dp).background(
                if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                CircleShape,
            ),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (live) "En vivo" else "Sin enlace",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LogsBanner(message: String) {
    Text(
        "$message · reintentando cada ${POLL_MS / 1000} s",
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
    )
}

/** One line: stamp, a marker for what it is, the message, and its tag underneath.
 *  Uplinks and downlinks get an arrow and a heavier weight; warnings go red. */
@Composable
private fun LogLineRow(l: LogRow.Line) {
    val cs = MaterialTheme.colorScheme
    val (icon, tint) = when (l.kind) {
        LogKind.UPLINK -> TerraIcons.ArrowUpward to cs.primary
        LogKind.DOWNLINK -> TerraIcons.ArrowDownward to cs.tertiary
        LogKind.LORA -> TerraIcons.Antenna to cs.onSurfaceVariant
        LogKind.OTHER -> null to cs.onSurfaceVariant
    }
    val emphasis = l.kind == LogKind.UPLINK || l.kind == LogKind.DOWNLINK
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .background(if (l.warn) cs.errorContainer.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            l.time.ifEmpty { "—" },
            modifier = Modifier.width(64.dp).padding(top = 2.dp),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = cs.onSurfaceVariant),
        )
        Box(Modifier.size(18.dp).padding(top = 1.dp), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp),
                    tint = if (l.warn) cs.error else tint)
            } else {
                Box(Modifier.size(5.dp).background(cs.outlineVariant, CircleShape))
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                l.message,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 17.sp,
                    fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (l.warn) cs.error else cs.onSurface,
                ),
            )
            if (l.tag.isNotEmpty()) {
                Text(
                    l.tag.uppercase(),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 8.5.sp, letterSpacing = 0.1.em,
                        color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                    ),
                )
            }
        }
    }
}

@Composable
private fun GapRow() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            "  líneas perdidas entre lecturas  ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}
