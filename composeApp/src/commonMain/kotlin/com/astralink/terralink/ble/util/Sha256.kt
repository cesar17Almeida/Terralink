package com.astralink.terralink.ble.util

/**
 * Platform-provided SHA-256. JVM/Android use java.security.MessageDigest;
 * iOS uses CoreCrypto's CC_SHA256. Returns a 32-byte digest.
 */
expect fun sha256(bytes: ByteArray): ByteArray
