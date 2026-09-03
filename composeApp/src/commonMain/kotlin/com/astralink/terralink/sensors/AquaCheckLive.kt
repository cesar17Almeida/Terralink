// A live pass over an AquaCheck probe, driven from the phone through the station's
// raw SDI-12 console (op "sdi12"): the station only bit-bangs one command per
// request, so the M!/D! handshake -- and the probe's own settling time -- happen
// here. Nothing is stored on the station; this is a technician's eye on the soil.
//
// Facts from the AquaCheck datasheet (v1.9) that shape this reader:
//   - aM! answers "atttn" and measures for ttt s; the bus MUST stay quiet meanwhile.
//   - aM1! returns the temperatures taken with that moisture reading (ttt = 0).
//   - A D reply with only the address means "no more values".
//   - Moisture is SFU (0 air, 100 water, valid -5..120); temperature -20..50 C.
//   - The probe will not measure more than once a minute: asked sooner it repeats
//     its last valid reading.
package com.astralink.terralink.sensors

import com.astralink.terralink.util.nowMs
import kotlinx.coroutines.delay

/** One complete pass: moisture and temperature per sensor, shallowest first. */
data class AquaCheckSample(
    /** Sensors the probe announced in its aM! header; the lists below have this size. */
    val sensorCount: Int,
    /** SFU per sensor (the firmware stores it as VWC = value/100); null = never received. */
    val moisture: List<Float?>,
    /** Degrees C per sensor; all null when the probe declined the temperature set. */
    val temperatureC: List<Float?>,
    /** Replies that came back silent or garbled and had to be asked again. */
    val retries: Int,
    val tookMs: Long,
)

/** Where a pass is, so the screen can say what the probe is doing right now. */
enum class AquaCheckPhase { MOISTURE_START, MOISTURE_WAIT, MOISTURE_READ, TEMPERATURE_START, TEMPERATURE_READ }

class AquaCheckReadError(message: String) : Exception(message)

/** Depth (cm) of each sensor, top -> bottom: the 1120-0404 SKU marks them every 10 cm
 *  (10/20/30/40), which is also the order the firmware stores readings in. */
fun aquaCheckDepthsCm(count: Int): List<Int> = List(count) { 10 * (it + 1) }

/** The probe measures at most once a minute (datasheet, "Power"); faster asks repeat. */
const val AQUACHECK_MIN_PERIOD_S = 60

val AQUACHECK_MOISTURE_RANGE = -5f..120f
val AQUACHECK_TEMPERATURE_RANGE = -20f..50f

/** Extra settling on top of the probe's announced delay: the reply reached us a
 *  little after the probe started counting, and a D! sent early aborts the cycle. */
private const val SETTLE_MS = 300L

/** The probe never spreads 4 values over more than two D replies; 10 is the protocol cap. */
private const val MAX_D_COMMANDS = 10

/** How many times one command is asked before the pass gives up on it. */
private const val ATTEMPTS = 3
private const val RETRY_GAP_MS = 400L

/**
 * Runs `aM!` (moisture) then `aM1!` (temperature) through [transact], which sends one
 * raw command and returns the first reply line ("" when the probe stayed silent).
 * Every reply is checked against the probe's own format and asked again when it is
 * not right; a value that never arrives stays null rather than shrinking the probe.
 */
class AquaCheckLiveReader(
    private val transact: suspend (cmd: String) -> String,
    private val addr: Char = '0',
    private val sleep: suspend (ms: Long) -> Unit = { delay(it) },
) {
    private var retries = 0

    suspend fun read(onPhase: (AquaCheckPhase) -> Unit = {}): AquaCheckSample {
        val t0 = nowMs()
        retries = 0
        onPhase(AquaCheckPhase.MOISTURE_START)
        val moistureHeader = header("M") ?: throw AquaCheckReadError("La sonda no responde a ${addr}M!")
        if (moistureHeader.count == 0) throw AquaCheckReadError("La sonda no anuncia sensores de humedad")
        if (moistureHeader.delayS > 0) {
            onPhase(AquaCheckPhase.MOISTURE_WAIT)
            sleep(moistureHeader.delayS * 1000L + SETTLE_MS)
        }
        onPhase(AquaCheckPhase.MOISTURE_READ)
        val moisture = collect(moistureHeader.count, AQUACHECK_MOISTURE_RANGE)

        onPhase(AquaCheckPhase.TEMPERATURE_START)
        val n = moistureHeader.count
        val temperatureHeader = header("M1")
        val temperature: List<Float?> = if (temperatureHeader == null || temperatureHeader.count == 0) {
            List(n) { null }
        } else {
            if (temperatureHeader.delayS > 0) sleep(temperatureHeader.delayS * 1000L + SETTLE_MS)
            onPhase(AquaCheckPhase.TEMPERATURE_READ)
            collect(temperatureHeader.count, AQUACHECK_TEMPERATURE_RANGE).fitTo(n)
        }
        return AquaCheckSample(n, moisture, temperature, retries, nowMs() - t0)
    }

    /** `aM!` / `aM1!`, asked again when the header does not parse. Null = the probe never answered. */
    private suspend fun header(verb: String): Sdi12MeasureHeader? {
        repeat(ATTEMPTS) { attempt ->
            if (attempt > 0) { retries++; sleep(RETRY_GAP_MS) }
            val h = parseSdi12MeasureHeader(transact("$addr$verb!"))
            if (h != null && h.count in 0..9) return h
        }
        return null
    }

    /** `aD0!`, `aD1!`... until [count] values or the probe says it has no more. */
    private suspend fun collect(count: Int, range: ClosedFloatingPointRange<Float>): List<Float?> {
        val out = ArrayList<Float?>(count)
        var d = 0
        while (out.size < count && d < MAX_D_COMMANDS) {
            var got: List<Float>? = null
            for (attempt in 0 until ATTEMPTS) {
                if (attempt > 0) { retries++; sleep(RETRY_GAP_MS) }
                when (val r = parseSdi12DataReply(transact("${addr}D$d!"), addr, range)) {
                    is Sdi12DataReply.Values -> { got = r.values; break }
                    Sdi12DataReply.Corrupt -> Unit
                }
            }
            if (got.isNullOrEmpty()) break      // three bad replies, or the probe is done
            out.addAll(got.take(count - out.size))
            d++
        }
        return out.fitTo(count)
    }
}

/** Pads with nulls (or trims) so a list lines up with the probe's sensor count. */
private fun List<Float?>.fitTo(n: Int): List<Float?> = List(n) { getOrNull(it) }
