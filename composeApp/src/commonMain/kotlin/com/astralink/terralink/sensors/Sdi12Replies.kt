// Pure parsing of SDI-12 probe replies, as the station's raw console hands them
// back (CRLF stripped). Mirrors savia_c/src/codec/sdi12_parse.c so the app can
// drive a measurement itself, one command per BLE round trip. Formats verified on
// the real AquaCheck: "aM!" -> "atttn"; "aD0!" -> "a+016.9562+025.1937-002.3312".
package com.astralink.terralink.sensors

/** Reply to `aM!` / `aM1!`: seconds until the values are ready + how many there are. */
data class Sdi12MeasureHeader(val delayS: Int, val count: Int)

/** Parses "atttn" (e.g. "00024" = wait 2 s, 4 values). Null on a short or garbled reply. */
fun parseSdi12MeasureHeader(reply: String): Sdi12MeasureHeader? {
    if (reply.length < 5) return null
    val digits = reply.substring(1, 5)
    if (digits.any { it !in '0'..'9' }) return null
    return Sdi12MeasureHeader(
        delayS = digits.substring(0, 3).toInt(),
        count = digits[3] - '0',
    )
}

/** Parses the sign-glued floats of a D-command reply, skipping the leading address. */
fun parseSdi12Values(reply: String): List<Float> =
    sdi12ValueTokens(reply).mapNotNull { parseSignedDecimal(it) }

/** What a D reply looks like once its transport is trusted, or why it is not. */
sealed interface Sdi12DataReply {
    /** Values, in wire order. Empty when the probe answered with its bare address: no more data. */
    data class Values(val values: List<Float>) : Sdi12DataReply
    /** Silence, a wrong address, or a token that is not a signed decimal: ask again. */
    data object Corrupt : Sdi12DataReply
}

/** A well-formed AquaCheck value: sign, integer digits, a point, decimals. */
private val VALUE_TOKEN = Regex("""[+-]\d+\.\d+""")

/**
 * Strict reading of a D reply for [addr]: every token must be a signed decimal inside
 * [range]. The station's console decodes the line bit by bit with no parity check, so a
 * reply chewed by an interrupt comes back with a missing sign or a broken number; the
 * lenient parser would happily return two values out of four and call it a probe with
 * two sensors.
 */
fun parseSdi12DataReply(reply: String, addr: Char, range: ClosedFloatingPointRange<Float>): Sdi12DataReply {
    if (reply.isEmpty() || reply[0] != addr) return Sdi12DataReply.Corrupt
    if (reply.length == 1) return Sdi12DataReply.Values(emptyList())
    val tokens = sdi12ValueTokens(reply)
    val body = reply.substring(1)
    if (tokens.isEmpty() || tokens.joinToString("") != body) return Sdi12DataReply.Corrupt
    val values = ArrayList<Float>(tokens.size)
    for (t in tokens) {
        if (!VALUE_TOKEN.matches(t)) return Sdi12DataReply.Corrupt
        val v = parseSignedDecimal(t) ?: return Sdi12DataReply.Corrupt
        if (v !in range) return Sdi12DataReply.Corrupt
        values.add(v)
    }
    return Sdi12DataReply.Values(values)
}

/** Splits "a+1.5-2.25" into ["+1.5", "-2.25"]: each token starts at a sign. */
private fun sdi12ValueTokens(reply: String): List<String> {
    if (reply.length < 2) return emptyList()
    val out = ArrayList<String>()
    var start = -1
    for (i in 1 until reply.length) {
        val c = reply[i]
        if (c == '+' || c == '-') {
            if (start >= 0) out.add(reply.substring(start, i))
            start = i
        }
    }
    if (start >= 0) out.add(reply.substring(start))
    return out
}

/** "+016.9562" -> 16.9562, digit by digit: identical on every platform, no locale. */
private fun parseSignedDecimal(token: String): Float? {
    if (token.length < 2) return null
    val negative = token[0] == '-'
    var value = 0.0
    var frac = 0.1
    var inFraction = false
    var any = false
    for (i in 1 until token.length) {
        val c = token[i]
        when {
            c in '0'..'9' -> {
                val d = (c - '0').toDouble()
                if (inFraction) { value += d * frac; frac *= 0.1 } else value = value * 10 + d
                any = true
            }
            c == '.' && !inFraction -> inFraction = true
            else -> break
        }
    }
    if (!any) return null
    return (if (negative) -value else value).toFloat()
}
