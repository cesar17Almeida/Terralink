package com.astralink.terralink.ble.codec

import com.astralink.terralink.ble.protocol.DataChunkMsg
import com.astralink.terralink.ble.protocol.MAX_CONTROL_MSG_BYTES
import com.astralink.terralink.ble.protocol.Op
import com.astralink.terralink.ble.protocol.PROTOCOL_VERSION
import com.astralink.terralink.ble.protocol.TimeSyncMsg
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}
