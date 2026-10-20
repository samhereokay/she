package com.antitheft.guardian.location

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.telephony.SmsManager
import android.util.Log
import com.antitheft.common.protocol.SmsPduSerializer
import com.antitheft.guardian.db.DirectBootStorageHelper
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Two-stage low-battery emergency beacon receiver.
 * Triggered by Intent.ACTION_BATTERY_LOW (manifest-exempt under Android 8.0+ broadcast rules).
 *
 * Stage 1: Transmits last-known location immediately without waiting for GPS lock.
 * Stage 2: Within goAsync(), attempts a 5-second fresh GPS fix and dispatches update if resolved.
 */
class LowBatteryReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BATTERY_LOW) return

        Log.w(TAG, "Low battery event received! Initiating emergency location dispatch.")

        val storageHelper = DirectBootStorageHelper(context)
        val trustedPhone = storageHelper.getTrustedPhoneNumber()
        val sharedSecret = storageHelper.getPairingSecret()
        val alias = storageHelper.getDeviceAlias()
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val smsManager = context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()

        // -------------------------------------------------------------
        // Stage 1: Immediate last-known location dispatch (Zero Latency)
        // -------------------------------------------------------------
        fusedClient.lastLocation.addOnSuccessListener { lastLoc: Location? ->
            if (lastLoc != null && trustedPhone.isNotBlank()) {
                val seq = storageHelper.getNextSequenceNumber()
                val captureEpoch = lastLoc.time / 1000
                
                val pdu = SmsPduSerializer.formatLocationReport(
                    alias = alias,
                    latitude = lastLoc.latitude,
                    longitude = lastLoc.longitude,
                    accuracyMeters = lastLoc.accuracy.toInt(),
                    captureEpochSec = captureEpoch,
                    sequenceNumber = seq,
                    sharedSecretKeyBytes = sharedSecret
                )
                
                try {
                    smsManager.sendTextMessage(trustedPhone, null, pdu, null, null)
                    Log.i(TAG, "Stage 1: Last-known location beacon dispatched via SMS.")
                } catch (e: Exception) {
                    Log.e(TAG, "Stage 1: SMS dispatch failed", e)
                }
            }
        }

        // -------------------------------------------------------------
        // Stage 2: Opportunistic fresh GPS fix within goAsync() (5s timeout)
        // -------------------------------------------------------------
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cts = CancellationTokenSource()
                val freshLoc: Location? = withTimeoutOrNull(5000L) {
                    fusedClient.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        cts.token
                    ).await()
                }

                if (freshLoc != null && trustedPhone.isNotBlank()) {
                    val seq = storageHelper.getNextSequenceNumber()
                    val captureEpoch = freshLoc.time / 1000

                    val pdu = SmsPduSerializer.formatLocationReport(
                        alias = alias,
                        latitude = freshLoc.latitude,
                        longitude = freshLoc.longitude,
                        accuracyMeters = freshLoc.accuracy.toInt(),
                        captureEpochSec = captureEpoch,
                        sequenceNumber = seq,
                        sharedSecretKeyBytes = sharedSecret
                    )

                    try {
                        smsManager.sendTextMessage(trustedPhone, null, pdu, null, null)
                        Log.i(TAG, "Stage 2: Fresh GPS beacon dispatched via SMS.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Stage 2: SMS dispatch failed", e)
                    }
                }

                // Enqueue expedited backend sync and heartbeat
                val captureManager = LocationCaptureManager(context)
                captureManager.enqueueSyncWork()
                try {
                    com.antitheft.guardian.sync.LocationSyncWorker.sendHeartbeatNow(context)
                } catch (_: Exception) {}

            } catch (e: Exception) {
                Log.e(TAG, "Stage 2 execution exception", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "LowBatteryReceiver"
    }
}
