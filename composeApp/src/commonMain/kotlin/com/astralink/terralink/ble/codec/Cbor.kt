package com.astralink.terralink.ble.codec

import com.astralink.terralink.ble.protocol.MAX_CONTROL_MSG_BYTES
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

class CodecError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@OptIn(ExperimentalSerializationApi::class)
val SaviaCbor: Cbor = Cbor {
    // Forward-compat: future protocol versions may add fields we don't know about.
    ignoreUnknownKeys = true
    // CRITICAL for interop with the Pi: kotlinx-serialization omits fields
    // that hold their declared default by default, which would drop `v` and
    // `op` (both have defaults) from every control message. The Python side
    // would then see `v=None` and reject the request as a bad protocol
    // version. Force defaults to be encoded so the wire stays self-describing.
    encodeDefaults = true
}

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> encode(msg: T): ByteArray {
    val bytes = try {
        SaviaCbor.encodeToByteArray(msg)
    } catch (e: SerializationException) {
        throw CodecError("CBOR encode failed: ${e.message}", e)
    }
    if (bytes.size > MAX_CONTROL_MSG_BYTES) {
        throw CodecError(
            "encoded message is ${bytes.size} B, exceeds MAX_CONTROL_MSG_BYTES=$MAX_CONTROL_MSG_BYTES"
        )
    }
    return bytes
}

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> decode(data: ByteArray): T {
    if (data.size > MAX_CONTROL_MSG_BYTES) {
        throw CodecError(
            "incoming message is ${data.size} B, exceeds MAX_CONTROL_MSG_BYTES=$MAX_CONTROL_MSG_BYTES"
        )
    }
    return try {
        SaviaCbor.decodeFromByteArray(data)
    } catch (e: SerializationException) {
        throw CodecError("CBOR decode failed: ${e.message}", e)
    }
}
