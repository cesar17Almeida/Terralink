package com.astralink.terralink.sensors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Replies are the ones captured from the real probe (savia_c/tools/sdi12_bringup/
 * AQUACHECK_RESPONSES.md): the live screen parses them on the phone, so a drift from
 * the firmware's parser would show as wrong depths, not as an error.
 */
class Sdi12RepliesTest {

    @Test
    fun measureHeaderCarriesDelayAndCount() {
        assertEquals(Sdi12MeasureHeader(delayS = 2, count = 4), parseSdi12MeasureHeader("00024"))
        assertEquals(Sdi12MeasureHeader(delayS = 0, count = 4), parseSdi12MeasureHeader("00004"))
        assertEquals(Sdi12MeasureHeader(delayS = 123, count = 9), parseSdi12MeasureHeader("A1239"))
    }

    @Test
    fun garbledOrSilentHeadersAreRejected() {
        assertNull(parseSdi12MeasureHeader(""))
        assertNull(parseSdi12MeasureHeader("0002"))
        assertNull(parseSdi12MeasureHeader("(sin respuesta)"))
        assertNull(parseSdi12MeasureHeader("0+043.0+04+004"))
    }

    @Test
    fun moistureValuesComeSignGlued() {
        val got = parseSdi12Values("0+016.9562+025.1937+002.3312")
        assertEquals(3, got.size)
        assertEquals(16.9562f, got[0], 0.0005f)
        assertEquals(25.1937f, got[1], 0.0005f)
        assertEquals(2.3312f, got[2], 0.0005f)
        assertEquals(listOf(2.8218f), parseSdi12Values("0+002.8218"))
    }

    @Test
    fun negativeAndIntegerValuesParse() {
        val got = parseSdi12Values("0+043.0+04-004")
        assertEquals(listOf(43.0f, 4f, -4f), got)
    }

    @Test
    fun emptyOrValuelessRepliesYieldNothing() {
        assertEquals(emptyList(), parseSdi12Values(""))
        assertEquals(emptyList(), parseSdi12Values("0"))
        assertEquals(emptyList(), parseSdi12Values("0+"))
    }

    @Test
    fun depthsFollowTheProbeLayout() {
        assertEquals(listOf(10, 20, 30, 40), aquaCheckDepthsCm(4))
        assertEquals(listOf(10, 20, 30, 40, 50, 60), aquaCheckDepthsCm(6))
    }

    // --- strict D replies: what the live reader trusts ---------------------------

    @Test
    fun strictReplyAcceptsTheProbeFormat() {
        val r = parseSdi12DataReply("0+016.9562+025.1937-002.3312", '0', AQUACHECK_MOISTURE_RANGE)
        assertTrue(r is Sdi12DataReply.Values)
        assertEquals(3, r.values.size)
        assertEquals(-2.3312f, r.values[2], 0.0005f)
        val t = parseSdi12DataReply("0+25.937+26.125+26.062", '0', AQUACHECK_TEMPERATURE_RANGE)
        assertTrue(t is Sdi12DataReply.Values)
        assertEquals(3, t.values.size)
    }

    @Test
    fun bareAddressMeansNoMoreValues() {
        assertEquals(Sdi12DataReply.Values(emptyList()), parseSdi12DataReply("0", '0', AQUACHECK_MOISTURE_RANGE))
    }

    @Test
    fun chewedRepliesAreCorruptNotShorterProbes() {
        // Silence, another address, a truncated number, a lost point, a stray byte, a
        // lost sign, a value outside the probe's own range: none of these is data.
        listOf(
            "", "(sin respuesta)", "1+016.9562", "0+016.9562+025", "0+0169562",
            "0+016.9562x+025.1937", "0016.9562", "0+500.0", "0+016.9562+",
        ).forEach {
            assertEquals(Sdi12DataReply.Corrupt, parseSdi12DataReply(it, '0', AQUACHECK_MOISTURE_RANGE), "'$it'")
        }
    }
}
