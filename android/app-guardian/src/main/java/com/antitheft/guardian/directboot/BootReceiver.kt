package com.antitheft.guardian.directboot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.antitheft.guardian.db.DirectBootStorageHelper
import com.antitheft.guardian.location.LocationCaptureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Resumes location listening and emergency queues upon cold reboot, even before First Unlock.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        val isBoot = action == Intent.ACTION_LOCKED_BOOT_COMPLETED || action == Intent.ACTION_BOOT_COMPLETED
        val isUpdate = action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!isBoot && !isUpdate) {
            return
        }

        Log.i(TAG, "Persistence event received: $action. Direct Boot aware resumption active.")

        val storageHelper = DirectBootStorageHelper(context)
        Log.i(TAG, "Device unlocked state: ${storageHelper.isUserUnlocked}")

        val keystoreManager = com.antitheft.guardian.crypto.KeystoreManager()
        val secInfo = keystoreManager.getSecurityInfo(context)
        Log.i(TAG, "Direct Boot Keystore Security: insideHardware=${secInfo.isInsideSecureHardware}, level=${secInfo.securityLevel}, strongBox=${secInfo.hasStrongBoxFeature}")

        // If user storage is unlocked or on app update / boot completed, start foreground tracking service
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED || storageHelper.isUserUnlocked) {
            try {
                com.antitheft.guardian.location.TrackingForegroundService.start(context)
                Log.i(TAG, "TrackingForegroundService started successfully.")
            } catch (e: Exception) {
                Log.w(TAG, "TrackingForegroundService start deferred: ${e.message}")
            }
        }

        // Capture initial boot fix and trigger sync
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val captureManager = LocationCaptureManager(context)
                captureManager.captureCurrentLocation()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to capture location on boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
