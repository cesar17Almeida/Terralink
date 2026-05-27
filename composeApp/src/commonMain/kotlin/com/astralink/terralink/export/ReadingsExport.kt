package com.astralink.terralink.export

import com.astralink.terralink.ble.protocol.Reading
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Pure serializers for the readings list. Output is plain String so the
 * platform exporter just writes it to disk and triggers the share sheet.
 */

private val csvJson = Json { prettyPrint = false; ignoreUnknownKeys = true }
private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

const val CSV_MIME = "text/csv"
const val JSON_MIME = "application/json"

/**
 * RFC-4180-ish CSV: header row + one record per line. We don't quote
 * anything because the schema is integers/floats/short identifiers with
 * no commas or newlines, but we keep the function defensive in case
 * `kind` grows.
 */
fun readingsToCsv(readings: List<Reading>): String {
    val sb = StringBuilder()
    sb.append("ts_ms,port,kind,value,depth_cm\n")
    for (r in readings) {
        sb.append(r.tsMs).append(',')
        sb.append(r.port).append(',')
        sb.append(csvField(r.kind)).append(',')
        sb.append(r.value).append(',')
        sb.append(r.depthCm?.toString() ?: "")
        sb.append('\n')
    }
    return sb.toString()
}

private fun csvField(s: String): String =
    if (s.contains(',') || s.contains('"') || s.contains('\n')) {
        "\"" + s.replace("\"", "\"\"") + "\""
    } else s

/** JSON array of {ts_ms, port, kind, value, depth_cm} objects. */
fun readingsToJson(readings: List<Reading>, pretty: Boolean = true): String =
    (if (pretty) prettyJson else csvJson).encodeToString(readings)

/** "savia-readings-<station>-<epoch ms>.csv" / ".json" naming helper. */
fun exportFileName(stationId: String, extension: String, nowMs: Long): String {
    val safeId = stationId.replace(':', '-').replace('/', '-')
    return "savia-readings-$safeId-$nowMs.$extension"
}
