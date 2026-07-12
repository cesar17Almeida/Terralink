package com.astralink.terralink.ble.codec

import com.astralink.terralink.ble.protocol.ConfigClearCoordsMsg
import com.astralink.terralink.ble.protocol.ConfigPatchMsg
import com.astralink.terralink.ble.protocol.ConfigSnapshotMsg
import com.astralink.terralink.ble.protocol.DataChunkMsg
import com.astralink.terralink.ble.protocol.DeviceInfo
import com.astralink.terralink.ble.protocol.MAX_CONTROL_MSG_BYTES
import com.astralink.terralink.ble.protocol.Op
import com.astralink.terralink.ble.protocol.PROTOCOL_VERSION
import com.astralink.terralink.ble.protocol.SensorInfo
import com.astralink.terralink.ble.protocol.TimeSyncMsg
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CborTest {

    @Test
    fun encodeDecodeRoundtrip() {
        val msg = TimeSyncMsg(ms = 1_700_000_000_000L)
        val bytes = encode(msg)
        val decoded = decode<TimeSyncMsg>(bytes)
        assertEquals(msg, decoded)
    }

    @Test
    fun encodeRejectsOversize() {
        // TimeSyncMsg.ms is 8 bytes; we need to force a big payload another way.
        // Use a DataChunkMsg with a payload larger than the cap.
        val big = DataChunkMsg(
            v = PROTOCOL_VERSION, op = Op.CHUNK, s = 0, t = 1, eof = true,
            p = ByteArray(MAX_CONTROL_MSG_BYTES + 1),
        )
        assertFailsWith<CodecError> { encode(big) }
    }

    @Test
    fun decodeRejectsOversize() {
        val raw = ByteArray(MAX_CONTROL_MSG_BYTES + 1)
        assertFailsWith<CodecError> { decode<TimeSyncMsg>(raw) }
    }

    @Test
    fun chunkedEncodeYieldsCorrectFrameCount() {
        val payload = ByteArray(1000) { 'A'.code.toByte() }
        val chunks = chunkedEncode(payload, chunkSize = 300)
        assertEquals(4, chunks.size)  // 300+300+300+100
        val decoded = chunks.map { decode<DataChunkMsg>(it) }
        assertEquals(listOf(0, 1, 2, 3), decoded.map { it.s })
        assertTrue(decoded.all { it.t == 4 })
        assertEquals(listOf(false, false, false, true), decoded.map { it.eof })
        val joined = decoded.fold(ByteArray(0)) { acc, m -> acc + m.p }
        assertContentEquals(payload, joined)
    }

    @Test
    fun chunkedEncodeHandlesEmptyPayload() {
        val chunks = chunkedEncode(ByteArray(0))
        assertEquals(1, chunks.size)
        val msg = decode<DataChunkMsg>(chunks[0])
        assertEquals(0, msg.s)
        assertEquals(1, msg.t)
        assertTrue(msg.eof)
        assertEquals(0, msg.p.size)
    }

    @Test
    fun chunkedRoundtrip() {
        val payload = ByteArray(1280) { (it % 256).toByte() }
        val chunks = chunkedEncode(payload, chunkSize = 200)
        val rebuilt = chunkedDecode(chunks)
        assertContentEquals(payload, rebuilt)
    }

    @Test
    fun chunkedDecodeDetectsReorder() {
        val payload = ByteArray(500) { 'X'.code.toByte() }
        val chunks = chunkedEncode(payload, chunkSize = 50).toMutableList()
        val tmp = chunks[1]; chunks[1] = chunks[2]; chunks[2] = tmp
        val err = assertFailsWith<CodecError> { chunkedDecode(chunks) }
        assertTrue(err.message!!.contains("out-of-order"))
    }

    @Test
    fun chunkedDecodeRejectsEmptyInput() {
        val err = assertFailsWith<CodecError> { chunkedDecode(emptyList()) }
        assertTrue(err.message!!.contains("no frames"))
    }

    @Test
    fun configPatchOmitsUnchangedNullFields() {
        // A name-only patch must stay SPARSE: only the changed field (plus the
        // mandatory v/op) reaches the wire. Otherwise a name save would also
        // carry `deep_sleep: null` etc., which the firmware can misapply.
        // CBOR map keys are raw UTF-8, so they appear verbatim in the bytes.
        val bytes = encodeSparse(ConfigPatchMsg(name = "Parcela 3"))
        val text = bytes.decodeToString()
        assertTrue(text.contains("name"), "the changed field must be present")
        assertTrue(text.contains("op"), "op (forced via @EncodeDefault) must stay on the wire")
        assertFalse(text.contains("deep_sleep"), "deep_sleep must be omitted when unchanged")
        assertFalse(text.contains("sleep_s"), "sleep_s must be omitted when unchanged")
        assertFalse(text.contains("log_level"), "log_level must be omitted when unchanged")
        // And it still round-trips the fields that ARE present.
        val back = decode<ConfigPatchMsg>(bytes)
        assertEquals("Parcela 3", back.name)
        assertEquals(null, back.deepSleep)
    }

    // The NEW config-patch fields must also stay off the wire for a name-only save,
    // otherwise the firmware could misapply them (e.g. clear coords by mistake).
    @Test
    fun configPatchOmitsNewFieldsWhenNameOnly() {
        val text = encodeSparse(ConfigPatchMsg(name = "Parcela 3")).decodeToString()
        listOf("lat", "lon", "inference_mode", "utc_offset_min", "irrigation_hour",
            "lora_period_s", "daily_hour").forEach {
            assertFalse(text.contains(it), "$it must be omitted when unchanged")
        }
    }

    // Setting coords sends both lat + lon as numbers.
    @Test
    fun configPatchIncludesCoordsWhenSet() {
        val bytes = encodeSparse(ConfigPatchMsg(lat = 39.47, lon = -0.37))
        val text = bytes.decodeToString()
        assertTrue(text.contains("lat"))
        assertTrue(text.contains("lon"))
        val back = decode<ConfigPatchMsg>(bytes)
        assertEquals(39.47, back.lat)
        assertEquals(-0.37, back.lon)
    }

    // Clearing coords needs EXPLICIT CBOR nulls on the wire (a sparse patch would just
    // omit them, which the firmware reads as "leave untouched"). ConfigClearCoordsMsg
    // forces lat/lon present-null via @EncodeDefault(ALWAYS).
    @Test
    fun clearCoordsEmitsExplicitNullLatLon() {
        val bytes = encodeSparse(ConfigClearCoordsMsg())
        val text = bytes.decodeToString()
        assertTrue(text.contains("lat"), "lat key must be present (explicit null)")
        assertTrue(text.contains("lon"), "lon key must be present (explicit null)")
        val back = decode<ConfigPatchMsg>(bytes)
        assertEquals(null, back.lat)
        assertEquals(null, back.lon)
    }

    // Full config snapshot round-trips the new keys: coords (number + null), the schedule
    // fields, inference flags, and per-sensor gpio2/unit (present and absent).
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun configSnapshotRoundtripWithNewFields() {
        val snap = ConfigSnapshotMsg(
            device = DeviceInfo(model = "Raspberry Pi Pico WH", mcu = "RP2040", fw = "0.1.0-c"),
            name = "Parcela",
            sleepS = 300,
            deepSleep = false,
            wakeGpio = 14,
            loraPeriodS = 1800,
            inferenceMode = "local",
            inferDev = true,
            utcOffsetMin = -180,   // negative offset must survive
            irrigationHour = 7,
            lat = 39.47,
            lon = -0.37,
            sensors = listOf(
                SensorInfo(port = 1, gpio = 2, type = "hc_sr04", addr = "", gpio2 = 3, unit = "mm"),
                SensorInfo(port = 2, gpio = 5, type = "sdi12_aquacheck", addr = "0"),
            ),
        )
        val decoded = SaviaCbor.decodeFromByteArray<ConfigSnapshotMsg>(SaviaCbor.encodeToByteArray(snap))
        assertEquals(snap, decoded)
        assertEquals(3, decoded.sensors[0].gpio2)
        assertEquals("mm", decoded.sensors[0].unit)
        assertEquals(null, decoded.sensors[1].gpio2)
        assertEquals(null, decoded.sensors[1].unit)
        assertEquals(-180, decoded.utcOffsetMin)

        // …and with coordinates cleared (lat/lon null).
        val cleared = snap.copy(lat = null, lon = null)
        val back = SaviaCbor.decodeFromByteArray<ConfigSnapshotMsg>(SaviaCbor.encodeToByteArray(cleared))
        assertEquals(null, back.lat)
        assertEquals(null, back.lon)
    }
}
