package com.astralink.terralink.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The address is read off the probe (`?!`) before the installer is asked for it, so
 * a lenient parser here would put a bogus character into every SDI-12 command and
 * fail at the first measurement instead of at configuration time.
 */
class Sdi12AddressTest {

    @Test
    fun theProtocolAlphabetIsAccepted() {
        listOf("0", "9", "A", "Z", "a", "z").forEach {
            assertTrue(isValidSdi12Address(it), "'$it' is a legal SDI-12 address")
        }
    }

    @Test
    fun anythingElseIsRejected() {
        listOf("", "#", "!", " ", "-", "01", "0 ", "ab").forEach {
            assertFalse(isValidSdi12Address(it), "'$it' is not a legal SDI-12 address")
        }
    }

    @Test
    fun unicodeDigitsAndLettersAreNotAddresses() {
        // Char.isDigit()/isLetter() would accept these; the wire is 7-bit ASCII.
        listOf("٣", "ñ", "Ω", "１").forEach {
            assertFalse(isValidSdi12Address(it), "'$it' cannot travel on an SDI-12 bus")
        }
    }

    @Test
    fun theReplyToQueryIsTheAddress() {
        assertEquals("0", parseSdi12Address(listOf("0")))
        assertEquals("3", parseSdi12Address(listOf("3")))
        // The firmware hands the line back raw; framing whitespace must not defeat it.
        assertEquals("0", parseSdi12Address(listOf(" 0\r\n")))
    }

    @Test
    fun silenceIsNotAnAddress() {
        // What the firmware publishes when nothing answered on that pin.
        assertNull(parseSdi12Address(listOf("(sin respuesta)")))
        assertNull(parseSdi12Address(emptyList()))
        assertNull(parseSdi12Address(listOf("")))
    }

    @Test
    fun garbledBusDoesNotProduceAnAddress() {
        // Two probes answering `?!` at once collide; guessing one would silently
        // configure the wrong sensor, so this must fall through to asking.
        assertNull(parseSdi12Address(listOf("0+13AquaChck")))
        assertNull(parseSdi12Address(listOf("00")))
    }
}
