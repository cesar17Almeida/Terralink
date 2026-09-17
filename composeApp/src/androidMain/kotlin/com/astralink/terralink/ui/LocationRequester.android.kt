package com.astralink.terralink.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Holds the caller waiting on the permission prompt (no recomposition needed). */
private class PendingLocation {
    var callback: ((GeoCoords?) -> Unit)? = null
}

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

/**
 * Android LocationRequester: last-known fix via LocationManager (no Play Services). Asks for
 * fine + coarse together, as Android 12+ ignores a lone fine request; a denial delivers null.
 */
@Composable
actual fun rememberLocationRequester(): LocationRequester? {
    val context = LocalContext.current
    val pending = remember { PendingLocation() }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val cb = pending.callback
        pending.callback = null
        cb?.invoke(if (grants.values.any { it }) lastKnownCoords(context) else null)
    }
    return remember(context) {
        object : LocationRequester {
            override fun request(onResult: (GeoCoords?) -> Unit) {
                if (hasAnyLocation(context)) {
                    onResult(lastKnownCoords(context))
                } else {
                    pending.callback = onResult
                    launcher.launch(LOCATION_PERMISSIONS)
                }
            }
        }
    }
}

private fun hasAnyLocation(ctx: Context): Boolean = LOCATION_PERMISSIONS.any {
    ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
}

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
