package com.antitheft.guardian.commands

import android.content.Context
import android.util.Log
import com.antitheft.guardian.admin.AntiTheftDeviceAdminReceiver
import com.antitheft.guardian.db.DirectBootStorageHelper
import com.antitheft.guardian.location.LocationCaptureManager
import com.antitheft.guardian.location.TrackingForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object CommandExecutionManager {
    private const val TAG = "CommandExecutionManager"
    private val scope = CoroutineScope(Dispatchers.IO)

    fun executeCommand(
        context: Context,
        commandType: String,
        parametersJson: String = "{}"
    ): Boolean {
        Log.i(TAG, "Dispatching verified recovery command: $commandType")
        return when (commandType.uppercase()) {
            "LOCATE" -> executeLocate(context)
            "RING" -> executeRing(context)
            "STOP_RING" -> executeStopRing()
            "LOCK" -> executeLock(context)
            "STOLEN_MODE" -> executeStolenMode(context, true)
            "RECOVERED" -> executeStolenMode(context, false)
            else -> {
                Log.w(TAG, "Unknown recovery command type: $commandType")
                false
            }
        }
    }

    fun executeLocate(context: Context): Boolean {
        Log.i(TAG, "Executing LOCATE: capturing fresh GPS fix and triggering immediate sync.")
        val captureManager = LocationCaptureManager(context)
        scope.launch {
            captureManager.captureCurrentLocation()
            captureManager.enqueueSyncWork()
        }
        return true
    }

    fun executeRing(context: Context): Boolean {
        Log.i(TAG, "Executing RING: starting high-priority alarm siren.")
        AlarmSoundManager.startAlarm(context)
        return true
    }

    fun executeStopRing(): Boolean {
        Log.i(TAG, "Executing STOP_RING: silencing alarm.")
        AlarmSoundManager.stopAlarm()
        return true
    }

    fun executeLock(context: Context): Boolean {
        Log.i(TAG, "Executing LOCK: locking phone screen via DevicePolicyManager.")
        return AntiTheftDeviceAdminReceiver.lockDevice(context)
    }

    fun executeStolenMode(context: Context, enabled: Boolean): Boolean {
        Log.i(TAG, "Executing STOLEN_MODE: setting stolen mode to $enabled")
        val storage = DirectBootStorageHelper(context)
        storage.setStolenMode(enabled)

        if (enabled) {
            // Lock phone immediately on stolen mode activation
            AntiTheftDeviceAdminReceiver.lockDevice(context)
            // Trigger an immediate fix
            executeLocate(context)
        }

        // Restart or update tracking service with new stolen mode cadence
        TrackingForegroundService.start(context)
        return true
    }
}
