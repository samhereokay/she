package com.antitheft.guardian.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Standard AOSP LocationManager fallback for devices without Google Play Services (e.g. GrapheneOS, CalyxOS, AOSP).
 * Uses native GPS_PROVIDER and NETWORK_PROVIDER directly.
 */
class AndroidLocationManagerFallback(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    fun getLastKnownLocation(): Location? {
        val gpsLoc = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } else null

        val netLoc = if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } else null

        return when {
            gpsLoc != null && netLoc != null -> if (gpsLoc.time >= netLoc.time) gpsLoc else netLoc
            gpsLoc != null -> gpsLoc
            else -> netLoc
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun requestSingleFix(timeoutMs: Long = 10000L): Location? {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        locationManager.removeUpdates(this)
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }

                    override fun onProviderDisabled(provider: String) {}
                    override fun onProviderEnabled(provider: String) {}
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }

                val provider = when {
                    locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> null
                }

                if (provider == null) {
                    continuation.resume(getLastKnownLocation())
                    return@suspendCancellableCoroutine
                }

                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())

                continuation.invokeOnCancellation {
                    locationManager.removeUpdates(listener)
                }
            }
        } ?: getLastKnownLocation()
    }
}
