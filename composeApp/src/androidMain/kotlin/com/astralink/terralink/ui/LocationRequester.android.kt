package com.astralink.terralink.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Android LocationRequester: last-known fix via the framework LocationManager (no new
 * dependency, no Google Play Services). Requests ACCESS_FINE_LOCATION at runtime the
 * first time; a denial delivers null.
 */
@Composable
actual fun rememberLocationRequester(): LocationRequester? {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<((GeoCoords?) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val cb = pending
        pending = null
        cb?.invoke(if (granted) lastKnownCoords(context) else null)
    }
    return remember(context) {
        object : LocationRequester {
            override fun request(onResult: (GeoCoords?) -> Unit) {
                if (hasFineLocation(context)) {
                    onResult(lastKnownCoords(context))
                } else {
                    pending = onResult
                    launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        }
    }
}

private fun hasFineLocation(ctx: Context): Boolean =
    ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

// Newest last-known fix across the enabled providers. Returns null with no cached fix.
private fun lastKnownCoords(ctx: Context): GeoCoords? {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    var best: Location? = null
    for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
        val loc = try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null } ?: continue
        if (best == null || loc.time > best.time) best = loc
    }
    return best?.let { GeoCoords(it.latitude, it.longitude) }
}
