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
        if (action != Intent.ACTION_LOCKED_BOOT_COMPLETED && action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        Log.i(TAG, "Boot event received: $action. Direct Boot aware resumption active.")

        val storageHelper = DirectBootStorageHelper(context)
        Log.i(TAG, "Device unlocked state: ${storageHelper.isUserUnlocked}")

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
