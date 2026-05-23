package com.astralink.terralink.ble.codec

import com.astralink.terralink.ble.protocol.DATA_CHUNK_BYTES
import com.astralink.terralink.ble.protocol.DataChunkMsg
import com.astralink.terralink.ble.protocol.Op
import com.astralink.terralink.ble.protocol.PROTOCOL_VERSION
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import kotlin.math.max

// Mirror of savia/ble/codec.py chunked_encode / chunked_decode.

@OptIn(ExperimentalSerializationApi::class)
fun chunkedEncode(payload: ByteArray, chunkSize: Int = DATA_CHUNK_BYTES): List<ByteArray> {
    if (chunkSize <= 0) throw CodecError("chunkSize must be positive")
    val total = max(1, (payload.size + chunkSize - 1) / chunkSize)
    val out = ArrayList<ByteArray>(total)
    for (seq in 0 until total) {
        val start = seq * chunkSize
        val end = minOf(start + chunkSize, payload.size)
        val slice = if (start >= end) ByteArray(0) else payload.copyOfRange(start, end)
        val msg = DataChunkMsg(
            v = PROTOCOL_VERSION,
            op = Op.CHUNK,
            s = seq,
            t = total,
            eof = seq == total - 1,
            p = slice,
        )
        out.add(SaviaCbor.encodeToByteArray(DataChunkMsg.serializer(), msg))
    }
    return out
}

@OptIn(ExperimentalSerializationApi::class)
fun chunkedDecode(frames: List<ByteArray>): ByteArray {
    if (frames.isEmpty()) throw CodecError("no frames to decode")

    val out = ArrayList<Byte>(frames.sumOf { it.size })
    var expectedTotal: Int? = null

    for ((i, raw) in frames.withIndex()) {
        val msg = try {
            SaviaCbor.decodeFromByteArray(DataChunkMsg.serializer(), raw)
        } catch (e: SerializationException) {
            throw CodecError("frame $i: CBOR decode failed: ${e.message}", e)
        }
        if (msg.v != PROTOCOL_VERSION) throw CodecError("frame $i: unexpected version ${msg.v}")
        if (msg.op != Op.CHUNK) throw CodecError("frame $i: unexpected op ${msg.op}")
        if (msg.s != i) throw CodecError("frame $i: out-of-order seq ${msg.s}")
        if (msg.t < 1) throw CodecError("frame $i: invalid total ${msg.t}")
        if (expectedTotal == null) expectedTotal = msg.t
        else if (msg.t != expectedTotal) throw CodecError("frame $i: total changed $expectedTotal -> ${msg.t}")
        val expectEof = i == expectedTotal - 1
        if (msg.eof != expectEof) throw CodecError("frame $i: eof mismatch")
        for (b in msg.p) out.add(b)
    }

    val total = expectedTotal!!
    if (frames.size != total) {
        throw CodecError("frame count ${frames.size} != declared total $total")
    }
    return out.toByteArray()
}
