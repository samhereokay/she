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
            "SEND_VERIFY_SMS" -> executeSendVerifySms(context, parametersJson)
            "SYNC_VERIFIED_PHONE" -> executeSyncVerifiedPhone(context, parametersJson)
            else -> {
                Log.w(TAG, "Unknown recovery command type: $commandType")
                false
            }
        }
    }

    fun executeSyncVerifiedPhone(context: Context, parametersJson: String): Boolean {
        return try {
            val json = com.google.gson.JsonParser.parseString(parametersJson).asJsonObject
            val phone = json.get("phone")?.asString
            if (!phone.isNullOrBlank()) {
                val storageHelper = DirectBootStorageHelper(context)
                storageHelper.setTrustedPhoneNumberVerified(phone, true)
                Log.i(TAG, "SYNC_VERIFIED_PHONE: Updated and verified trusted phone on handset.")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing SYNC_VERIFIED_PHONE", e)
            false
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
            // Parse custom message and test mode flag
            var isTestMode = false
            var customMsg = "THIS DEVICE IS REPORTED STOLEN. Call owner to return."
            try {
                val obj = org.json.JSONObject(parametersJson)
                isTestMode = obj.optBoolean("test_mode", false)
                customMsg = obj.optString("lock_message", customMsg)
            } catch (e: Exception) {
                // Default to production OG mode
            }

            if (!isTestMode) {
                // Real OG Mode: Full Siren + TTS Voice Alert
                AlarmSoundManager.startAlarm(context, "Warning. This phone is reported stolen. Location is being broadcast. Return to owner immediately.")
                
                // Dispatch emergency SMS with live GPS link to trusted contact
                scope.launch {
                    try {
                        val trustedPhone = storage.getTrustedPhoneNumber()
                        val replyPhone = com.antitheft.common.protocol.PhoneNumberNormalizer.normalizeForSmsDispatch(trustedPhone)
                        if (replyPhone.isNotBlank()) {
                            val captureManager = LocationCaptureManager(context)
                            val loc = captureManager.captureCurrentLocation(timeoutMs = 5000)
                                ?: captureManager.getLastKnownLocation()
                            val coordsStr = if (loc != null) "https://maps.google.com/?q=${loc.latitude},${loc.longitude} (±${loc.accuracy.toInt()}m)" else "Location acquiring..."
                            val smsText = "[AntiTheft ALERT] Handset '${storage.getDeviceAlias()}' marked STOLEN. GPS: $coordsStr. Return to owner immediately."
                            val smsManager = context.getSystemService(android.telephony.SmsManager::class.java)
                                ?: android.telephony.SmsManager.getDefault()
                            smsManager.sendTextMessage(replyPhone, null, smsText, null, null)
                            Log.i(TAG, "OG Stolen Mode: Dispatched emergency SMS to $replyPhone")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed dispatching emergency SMS", e)
                    }
                }
            } else {
                // Test Mode: Safe drill for friends (10-second alert, optional friend drill SMS)
                val testRecipientPhone = try {
                    val obj = org.json.JSONObject(parametersJson)
                    obj.optString("test_recipient_phone", "").trim()
                } catch (_: Exception) { "" }

                if (testRecipientPhone.isNotBlank()) {
                    scope.launch {
                        try {
                            val cleanTest = com.antitheft.common.protocol.PhoneNumberNormalizer.normalizeForSmsDispatch(testRecipientPhone)
                            if (cleanTest.isNotBlank()) {
                                val captureManager = LocationCaptureManager(context)
                                val loc = captureManager.captureCurrentLocation(timeoutMs = 5000)
                                    ?: captureManager.getLastKnownLocation()
                                val coordsStr = if (loc != null) "https://maps.google.com/?q=${loc.latitude},${loc.longitude} (±${loc.accuracy.toInt()}m)" else "Location acquiring..."
                                val smsText = "[AntiTheft DRILL] Safe test drill simulated on '${storage.getDeviceAlias()}'. Test GPS: $coordsStr. No real emergency."
                                val smsManager = context.getSystemService(android.telephony.SmsManager::class.java)
                                    ?: android.telephony.SmsManager.getDefault()
                                smsManager.sendTextMessage(cleanTest, null, smsText, null, null)
                                Log.i(TAG, "Test Mode: Dispatched friend drill SMS to $cleanTest")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed dispatching test drill SMS", e)
                        }
                    }
                }

                AlarmSoundManager.startAlarm(context, "Anti-Theft Test Mode activated. This is a non-destructive drill.")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    AlarmSoundManager.stopAlarm()
                }, 10000L)
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
            // Silences alarm and TTS immediately
            AlarmSoundManager.stopAlarm()

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

    fun executeSendVerifySms(context: Context, parametersJson: String): Boolean {
        return try {
            val obj = org.json.JSONObject(parametersJson)
            val to = obj.optString("to", "").trim()
            val body = obj.optString("body", "")

            // Validate recipient is valid international E.164
            if (!to.matches(Regex("^\\+[1-9]\\d{1,14}\$"))) {
                Log.w(TAG, "SEND_VERIFY_SMS rejected: invalid E.164 recipient format.")
                return false
            }
            if (body.isBlank()) {
                Log.w(TAG, "SEND_VERIFY_SMS rejected: blank message body.")
                return false
            }

            val smsManager = context.getSystemService(android.telephony.SmsManager::class.java)
                ?: android.telephony.SmsManager.getDefault()
            smsManager.sendTextMessage(to, null, body, null, null)

            // CRITICAL SECURITY INVARIANT: NEVER log the message body (contains OTP)
            Log.i(TAG, "SEND_VERIFY_SMS: Verification challenge SMS dispatched successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "SEND_VERIFY_SMS dispatch failed", e)
            false
        }
    }
}
