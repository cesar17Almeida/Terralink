package com.astralink.terralink.ble

import android.content.Context

/**
 * Holds the application Context that BleClient and friends need to access
 * the Bluetooth system service. MainActivity calls init(applicationContext)
 * before the first BleClient() is constructed.
 */
object AndroidBleContext {
    private var _appContext: Context? = null

    fun init(context: Context) {
        _appContext = context.applicationContext
    }

    val appContext: Context
        get() = _appContext ?: throw IllegalStateException(
            "AndroidBleContext not initialized. " +
            "Call AndroidBleContext.init(applicationContext) from MainActivity.onCreate."
        )
}
