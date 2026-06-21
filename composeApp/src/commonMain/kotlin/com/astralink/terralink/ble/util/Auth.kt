package com.astralink.terralink.ble.util

// Challenge-response helpers, matching the firmware (savia/auth.c):
//   auth_key = SHA256(password);  proof = SHA256(auth_key || nonce)

/** The 32-byte key the station stores: SHA256(password). */
fun passwordKey(password: String): ByteArray = sha256(password.encodeToByteArray())

/** The proof for a challenge: SHA256(key || nonce). */
fun authProof(key: ByteArray, nonce: ByteArray): ByteArray = sha256(key + nonce)
