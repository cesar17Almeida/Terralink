// Config write outcome: the station answers every `…0013` patch with a notify
// ack (`config_ok` / `config_err` + reason). This file turns that reason into
// something a user can act on, and provides the fallback check used when no ack
// arrives at all (locked station, or a build with the notify path off).
package com.astralink.terralink.ble.session

import com.astralink.terralink.ble.protocol.ConfigPatchMsg
import com.astralink.terralink.ble.protocol.ConfigSnapshotMsg
import com.astralink.terralink.util.formatTimeOfDay
import com.astralink.terralink.util.secondsToHuman
import kotlin.math.abs

/** The station received the patch and refused it. [raw] is the firmware's own reason. */
class ConfigRejected(val raw: String) : RuntimeException(configErrorText(raw))

/** The station never acked and the snapshot proves the change didn't land. */
class ConfigNotApplied(val fields: List<String>) : RuntimeException(
    "La estación no aplicó ${fields.joinToString(", ")}. " +
        "Vuelve a intentarlo; si persiste, reconecta con la estación."
)

// --- firmware reason -> user-facing text -------------------------------------

// Reasons the firmware can put in config_err (see handle_config_write in
// savia_c/src/ble/ble_gatt.c and pinmap_assign_str). Anything unknown falls
// through verbatim so a newer firmware is never silenced by an old app.
private val FIELD_ERRORS = mapOf(
    "bad patch" to "La estación no entendió la petición. Comprueba que lleva la última versión de savia_c.",
    "no config" to "La estación aún no tiene configuración cargada.",
    "auth required" to "La estación está bloqueada: introduce la contraseña antes de guardar.",
    "sleep_s out of range" to "Tiempo de sueño fuera de rango (10 s – 24 h).",
    "capture_s out of range" to "Cadencia de captura fuera de rango (60 s – 24 h).",
    "daily_hour out of range" to "Hora de predicción fuera de rango (0–23).",
    "daily_min out of range" to "Minuto de predicción fuera de rango (0–59).",
    "name empty" to "El nombre de la estación no puede estar vacío.",
    "log_level out of range" to "Nivel de registro inválido (0 = depuración, 1 = info).",
    "lora_period_s out of range" to "Periodo de LoRa fuera de rango (5 min – 24 h).",
    "no_local_inference" to "Este firmware no lleva el modelo embebido: no puede inferir en la propia " +
        "estación. Deja el modo en «Reenviar» o flashea una compilación con inferencia.",
    "utc_offset out of range" to "Desfase horario fuera de rango (UTC−12:00 … UTC+14:00).",
    "coords out of range" to "Coordenadas fuera de rango.",
    "coords need lat+lon" to "Hay que enviar latitud y longitud juntas.",
)

// Per-sensor reasons: the firmware prefixes them with "sensor <index>: ".
private val SENSOR_ERRORS = mapOf(
    "pin occupied" to "ese pin ya lo usa otro sensor.",
    "pin reserved" to "ese pin está reservado por el sistema (radio, botón de encendido o UART de LoRa).",
    "pin incapable" to "ese pin no sirve para este tipo de sensor (el analógico solo funciona en GP26–GP28).",
    "gpio out of range" to "ese pin no existe en la placa.",
    "interval_s out of range" to "su cadencia propia está fuera de rango (60 s – 24 h).",
)

/** Firmware reason -> Spanish. Unknown reasons pass through as-is. */
fun configErrorText(raw: String): String {
    val reason = raw.trim()
    FIELD_ERRORS[reason]?.let { return it }

    // "sensor <i>: <reason>" -- i is the 0-based slot, shown as its 1-based port.
    if (reason.startsWith("sensor ")) {
        val colon = reason.indexOf(':')
        if (colon > 0) {
            val index = reason.substring("sensor ".length, colon).trim().toIntOrNull()
            val detail = reason.substring(colon + 1).trim()
            if (index != null) {
                val label = SENSOR_ERRORS[detail] ?: "$detail."
                return "Sensor ${index + 1}: $label"
            }
        }
    }
    return "La estación rechazó el cambio: $reason"
}

// --- fallback: did the patch actually land? ----------------------------------

// Coordinates travel as degrees x 1e-7 ints, so the value read back is rounded.
private const val COORD_EPSILON = 1e-6

/**
 * Fields this patch asked for that the station's snapshot does NOT reflect.
 * Only used when no ack arrived: it can't explain WHY, but it proves whether the
 * write took effect, which is what separates "guardado" from a silent no-op.
 */
internal fun ConfigPatchMsg.notAppliedIn(snapshot: ConfigSnapshotMsg): List<String> {
    val missing = mutableListOf<String>()
    fun check(asked: Any?, got: Any?, label: String) {
        if (asked != null && asked != got) missing.add(label)
    }
    check(name, snapshot.name, "el nombre")
    check(sleepS, snapshot.sleepS, "el tiempo de sueño")
    check(deepSleep, snapshot.deepSleep, "el ahorro de energía")
    check(captureS, snapshot.captureS, "la cadencia de captura")
    check(dailyHour, snapshot.dailyHour, "la hora de predicción")
    check(dailyMin, snapshot.dailyMin, "el minuto de predicción")
    check(mock, snapshot.mockEnabled, "el modo de datos simulados")
    check(logLevel, snapshot.logLevel, "el nivel de registro")
    check(loraPeriodS, snapshot.loraPeriodS, "el periodo de LoRa")
    check(lora, snapshot.lora, "el módulo LoRa")
    check(loraTx, snapshot.loraTx, "el pin TX de LoRa")
    check(loraRx, snapshot.loraRx, "el pin RX de LoRa")
    check(inferenceMode, snapshot.inferenceMode, "el modo de inferencia")
    check(utcOffsetMin, snapshot.utcOffsetMin, "el desfase horario")

    val askedLat = lat
    val askedLon = lon
    if (askedLat != null && askedLon != null) {
        val gotLat = snapshot.lat
        val gotLon = snapshot.lon
        if (gotLat == null || gotLon == null ||
            abs(gotLat - askedLat) > COORD_EPSILON || abs(gotLon - askedLon) > COORD_EPSILON
        ) {
            missing.add("las coordenadas")
        }
    }

    // Sensors are swapped as a whole table; compare the identity of each slot
    // (type + pins), which is what a rejection would have left untouched. Matched
    // BY PORT, not by list position: the port is the slot the sensor was asked to
    // occupy, so a station that placed it somewhere else has not applied the patch
    // -- and comparing positionally would call that a success.
    val askedSensors = sensors
    if (askedSensors != null) {
        val got = snapshot.sensors.associateBy { it.port }
        val same = askedSensors.size == snapshot.sensors.size && askedSensors.all { asked ->
            val g = got[asked.port]
            g != null && asked.type == g.type && asked.gpio == g.gpio && asked.gpio2 == g.gpio2
        }
        if (!same) missing.add("los sensores")
    }
    return missing
}

// --- what a save actually changed --------------------------------------------

/** How many changes the confirmation names before collapsing to a count. */
private const val SUMMARY_MAX_PARTS = 3

/**
 * Confirmation text for a saved patch, naming ONLY the settings that travelled.
 * Values are read from [snapshot] -- the station's confirmed state -- so the text
 * reports what the station now holds, not what the app asked for.
 *
 * A patch is sparse (see ActiveSession.writeConfig): a save that only moved the
 * prediction time carries nothing else, and the confirmation must say nothing
 * else either. Naming untouched settings reads as if they had been rewritten.
 */
internal fun ConfigPatchMsg.appliedSummary(snapshot: ConfigSnapshotMsg): String {
    val parts = mutableListOf<String>()
    if (name != null) parts += "nombre «${snapshot.name}»"
    if (dailyHour != null || dailyMin != null) {
        parts += "predicción a las ${formatTimeOfDay(snapshot.dailyHour, snapshot.dailyMin)}"
    }
    if (inferenceMode != null) {
        parts += if (snapshot.inferenceMode == "local") "modelo en la estación"
                 else "modelo en la nube/app"
    }
    if (deepSleep != null) parts += "ahorro ${onOff(snapshot.deepSleep)}"
    if (sleepS != null) parts += "sueño ${secondsToHuman(snapshot.sleepS)}"
    if (captureS != null) parts += "captura cada ${secondsToHuman(snapshot.captureS)}"
    if (loraPeriodS != null) parts += "LoRa cada ${secondsToHuman(snapshot.loraPeriodS)}"
    if (lora != null || loraTx != null || loraRx != null) {
        parts += if (snapshot.lora) "LoRa en GP${snapshot.loraTx}/GP${snapshot.loraRx}" else "LoRa apagado"
    }
    if (lat != null && lon != null) parts += "ubicación"
    if (sensors != null) parts += "sensores"
    if (mock != null) parts += "datos simulados ${onOff(snapshot.mockEnabled)}"
    if (logLevel != null) parts += "logs ${if (snapshot.logLevel == 0) "Debug" else "Info"}"
    // utcOffsetMin is written silently on connect, never as part of a user save.

    return when {
        parts.isEmpty() -> "Sin cambios que guardar"
        parts.size <= SUMMARY_MAX_PARTS -> "Aplicado ✓ · " + parts.joinToString(" · ")
        else -> "Aplicado ✓ · ${parts.size} ajustes"
    }
}

private fun onOff(v: Boolean) = if (v) "ON" else "OFF"
