package com.astralink.terralink.ble.util

import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
actual fun sha256(bytes: ByteArray): ByteArray {
    val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
    if (bytes.isEmpty()) {
        // CC_SHA256 with len=0 still produces a valid digest of empty input.
        out.usePinned { outPinned ->
            CC_SHA256(null, 0u, outPinned.addressOf(0).reinterpret())
        }
        return out
    }
    bytes.usePinned { inPinned ->
        out.usePinned { outPinned ->
            CC_SHA256(
                inPinned.addressOf(0),
                bytes.size.convert(),
                outPinned.addressOf(0).reinterpret(),
            )
        }
    }
    return out
}
