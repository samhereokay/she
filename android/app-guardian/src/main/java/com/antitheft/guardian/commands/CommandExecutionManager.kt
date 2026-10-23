package com.antitheft.guardian.commands

import android.content.Context
import android.content.Intent
import android.util.Log
import com.antitheft.guardian.control.DeviceControl
import com.antitheft.guardian.control.LiteDeviceControl
import com.antitheft.guardian.db.DirectBootStorageHelper
import com.antitheft.guardian.location.LocationCaptureManager
import com.antitheft.guardian.location.TrackingForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object CommandExecutionManager {
    private const val TAG = "CommandExecutionManager"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val deviceControl: DeviceControl = LiteDeviceControl()

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
            "STOLEN_MODE", "LOST_MODE" -> executeStolenMode(context, true, parametersJson)
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
        Log.i(TAG, "Executing LOCK: checking device control support.")
        if (deviceControl.isDeviceOwner(context)) {
            deviceControl.applyTheftRestrictions(context)
            val info = "STOLEN DEVICE: Locked by owner. Return immediately."
            deviceControl.setLockScreenInfo(context, info)
        }
        return deviceControl.lockDevice(context)
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
            if (deviceControl.isDeviceOwner(context)) {
                deviceControl.applyTheftRestrictions(context)
                deviceControl.setLockScreenInfo(context, customMsg)
                deviceControl.enterLockTaskStolenMode(context)
            }
            // Lock phone immediately on stolen mode activation
            deviceControl.lockDevice(context)
            // Trigger an immediate fix
            executeLocate(context)
        } else {
            // Silences alarm and TTS immediately
            AlarmSoundManager.stopAlarm()

            // Clear theft restrictions upon de-escalation/recovery
            if (deviceControl.isDeviceOwner(context)) {
                deviceControl.clearTheftRestrictions(context)
                deviceControl.setLockScreenInfo(context, null)
                deviceControl.exitLockTaskMode(context)
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
        if (deviceControl.isDeviceOwner(context)) {
            deviceControl.clearTheftRestrictions(context)
            deviceControl.setLockScreenInfo(context, null)
            deviceControl.exitLockTaskMode(context)
        }
        return true
    }

    fun executeClearDeviceOwner(context: Context): Boolean {
        Log.i(TAG, "Executing verified CLEAR_DEVICE_OWNER command: deprovisioning Device Owner.")
        return deviceControl.clearDeviceOwner(context)
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

            // CRITICAL SECURITY ENFORCEMENT: SEND_VERIFY_SMS can ONLY target the handset's configured owner number.
            val storage = com.antitheft.guardian.db.DirectBootStorageHelper(context)
            val configuredOwnerPhone = storage.getRawTrustedPhoneNumber()
            if (configuredOwnerPhone.isBlank()) {
                Log.w(TAG, "SEND_VERIFY_SMS rejected: no trusted owner phone number configured on handset.")
                return false
            }

            val normalizedTo = com.antitheft.common.protocol.PhoneNumberNormalizer.normalizeToE164(to)
            val normalizedOwner = com.antitheft.common.protocol.PhoneNumberNormalizer.normalizeToE164(configuredOwnerPhone)
            if (normalizedTo != normalizedOwner) {
                Log.w(TAG, "SEND_VERIFY_SMS rejected: recipient $normalizedTo does not match configured owner number $normalizedOwner.")
                return false
            }

            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "SEND_VERIFY_SMS failed: android.permission.SEND_SMS is not granted (blocked by Play Protect or Android 13+ restricted settings).")
                return false
            }

            val smsManager = context.getSystemService(android.telephony.SmsManager::class.java)
                ?: android.telephony.SmsManager.getDefault()
            smsManager.sendTextMessage(normalizedTo, null, body, null, null)

            // CRITICAL SECURITY INVARIANT: NEVER log the message body (contains OTP)
            Log.i(TAG, "SEND_VERIFY_SMS: Verification challenge SMS dispatched successfully to owner.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "SEND_VERIFY_SMS dispatch failed", e)
            false
        }
    }
}
