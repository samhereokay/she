package com.antitheft.guardian.commands

import android.content.Context
import android.content.Intent
import android.util.Log
import com.antitheft.deviceowner.DeviceOwnerManager
import com.antitheft.guardian.db.DirectBootStorageHelper
import com.antitheft.guardian.location.LocationCaptureManager
import com.antitheft.guardian.location.TrackingForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object CommandExecutionManager {
    private const val TAG = "CommandExecutionManager"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val deviceOwnerManager = DeviceOwnerManager()

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
            "STOLEN_MODE" -> executeStolenMode(context, true, parametersJson)
            "RECOVERED", "EXIT_KIOSK" -> executeStolenMode(context, false)
            "UNPAIR" -> executeUnpair(context)
            "CLEAR_DEVICE_OWNER" -> executeClearDeviceOwner(context)
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
        Log.i(TAG, "Executing LOCK: enforcing restrictions and locking phone screen.")
        if (deviceOwnerManager.isDeviceOwner(context)) {
            deviceOwnerManager.applyTheftRestrictions(context)
            val info = "STOLEN DEVICE: Locked by owner. Return immediately."
            deviceOwnerManager.setDeviceOwnerLockScreenInfo(context, info)
        }
        return deviceOwnerManager.lockNow(context)
    }

    fun executeStolenMode(context: Context, enabled: Boolean, parametersJson: String = "{}"): Boolean {
        Log.i(TAG, "Executing STOLEN_MODE: setting stolen mode to $enabled")
        val storage = DirectBootStorageHelper(context)
        storage.setStolenMode(enabled)

        if (enabled) {
            // Parse custom message if present in parameters
            val customMsg = try {
                val obj = org.json.JSONObject(parametersJson)
                obj.optString("lock_message", "THIS DEVICE IS REPORTED STOLEN. Call owner to return.")
            } catch (e: Exception) {
                "THIS DEVICE IS REPORTED STOLEN. Call owner to return."
            }

            // Apply theft restrictions and kiosk if Device Owner
            if (deviceOwnerManager.isDeviceOwner(context)) {
                deviceOwnerManager.applyTheftRestrictions(context)
                deviceOwnerManager.setDeviceOwnerLockScreenInfo(context, customMsg)
                deviceOwnerManager.setLockTaskPackages(context, arrayOf(context.packageName))
                try {
                    val kioskIntent = Intent(context, com.antitheft.guardian.ui.GuardianMainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("INTERNAL_ENTER_KIOSK", true)
                    }
                    context.startActivity(kioskIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start kiosk activity on stolen mode activation", e)
                }
            }
            // Lock phone immediately on stolen mode activation
            deviceOwnerManager.lockNow(context)
            // Trigger an immediate fix
            executeLocate(context)
        } else {
            // Clear theft restrictions upon de-escalation/recovery
            if (deviceOwnerManager.isDeviceOwner(context)) {
                deviceOwnerManager.clearTheftRestrictions(context)
                deviceOwnerManager.setDeviceOwnerLockScreenInfo(context, null)
                try {
                    val exitKioskIntent = Intent(context, com.antitheft.guardian.ui.GuardianMainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("INTERNAL_EXIT_KIOSK", true)
                    }
                    context.startActivity(exitKioskIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to dispatch exit kiosk intent", e)
                }
            }
        }

        // Restart or update tracking service with new stolen mode cadence
        TrackingForegroundService.start(context)
        return true
    }

    fun executeUnpair(context: Context): Boolean {
        Log.i(TAG, "Executing verified UNPAIR command from registered owner. Clearing pairing credentials.")
        val storage = DirectBootStorageHelper(context)
        storage.clearPairing()
        if (deviceOwnerManager.isDeviceOwner(context)) {
            deviceOwnerManager.clearTheftRestrictions(context)
            deviceOwnerManager.setDeviceOwnerLockScreenInfo(context, null)
            try {
                val exitKioskIntent = Intent(context, com.antitheft.guardian.ui.GuardianMainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("INTERNAL_EXIT_KIOSK", true)
                }
                context.startActivity(exitKioskIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch exit kiosk intent", e)
            }
        }
        return true
    }

    fun executeClearDeviceOwner(context: Context): Boolean {
        Log.i(TAG, "Executing verified CLEAR_DEVICE_OWNER command: deprovisioning Device Owner.")
        return deviceOwnerManager.clearDeviceOwner(context)
    }
}
