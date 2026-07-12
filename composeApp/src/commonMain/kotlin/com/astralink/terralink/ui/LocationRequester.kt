package com.astralink.terralink.ui

import androidx.compose.runtime.Composable

/** A latitude/longitude pair from the device GPS/network fix. */
data class GeoCoords(val lat: Double, val lon: Double)

/**
 * Platform hook behind the "Usar mi ubicación" button. [request] triggers a runtime
 * permission prompt if needed, then delivers the device location (or null on denial /
 * no fix). Only Android supplies one; iOS/JVM return null from [rememberLocationRequester]
 * so the UI hides the affordance.
 */
interface LocationRequester {
    fun request(onResult: (GeoCoords?) -> Unit)
}

/** Non-null only where device location is supported (Android). */
@Composable
expect fun rememberLocationRequester(): LocationRequester?
