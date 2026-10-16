package com.antitheft.guardian.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.antitheft.common.crypto.EcdsaSigner
import com.antitheft.guardian.crypto.KeystoreManager
import com.antitheft.guardian.db.DirectBootStorageHelper
import com.antitheft.guardian.db.GuardianDatabase
import com.antitheft.guardian.db.LocationEntity
import com.antitheft.guardian.sync.LocationSyncWorker
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.Base64

class LocationCaptureManager(private val context: Context) {

    private val hasPlayServices: Boolean by lazy {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }

    private val fusedLocationClient: FusedLocationProviderClient? by lazy {
        if (hasPlayServices) LocationServices.getFusedLocationProviderClient(context) else null
    }

    private val nativeFallback: AndroidLocationManagerFallback by lazy {
        AndroidLocationManagerFallback(context)
    }

    private val storageHelper = DirectBootStorageHelper(context)
    private val keystoreManager = KeystoreManager()
    private val db = GuardianDatabase.getInstance(context, storageHelper.getDbPassphrase())

    @SuppressLint("MissingPermission")
    suspend fun getLastKnownLocation(): LocationEntity? {
        val location: Location? = if (hasPlayServices && fusedLocationClient != null) {
            try {
                fusedLocationClient!!.lastLocation.await() ?: nativeFallback.getLastKnownLocation()
            } catch (e: Exception) {
                nativeFallback.getLastKnownLocation()
            }
        } else {
            nativeFallback.getLastKnownLocation()
        }

        return location?.let { persistLocation(it) }
    }

    @SuppressLint("MissingPermission")
    suspend fun captureCurrentLocation(timeoutMs: Long = 10000): LocationEntity? {
        val location: Location? = if (hasPlayServices && fusedLocationClient != null) {
            val cts = CancellationTokenSource()
            try {
                fusedLocationClient!!.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cts.token
                ).await() ?: nativeFallback.requestSingleFix(timeoutMs)
            } catch (e: Exception) {
                nativeFallback.requestSingleFix(timeoutMs)
            }
        } else {
            nativeFallback.requestSingleFix(timeoutMs)
        }

        return location?.let { persistLocation(it) } ?: getLastKnownLocation()
    }

    private suspend fun persistLocation(loc: Location): LocationEntity {
        val deviceId = storageHelper.getDeviceId()
        val seq = storageHelper.getNextSequenceNumber()
        val epochSec = loc.time / 1000

        val canonicalBytes = EcdsaSigner.createCanonicalBytes(
            deviceId = deviceId,
            sequenceNumber = seq,
            captureEpochSec = epochSec,
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracy = loc.accuracy
        )

        val derSignature = keystoreManager.signData(canonicalBytes)
        val sigBase64 = Base64.getEncoder().encodeToString(derSignature)

        val entity = LocationEntity(
            sequenceNumber = seq,
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracy = loc.accuracy,
            altitude = if (loc.hasAltitude()) loc.altitude else null,
            velocity = if (loc.hasSpeed()) loc.speed else null,
            captureTimeUtc = epochSec,
            signatureDerBase64 = sigBase64,
            syncStatus = LocationEntity.STATUS_PENDING
        )

        db.locationDao().insert(entity)

        // Schedule persistent WorkManager sync
        enqueueSyncWork()

        return entity
    }

    fun enqueueSyncWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<LocationSyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueue(syncRequest)
    }
}
