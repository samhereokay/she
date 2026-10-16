package com.antitheft.guardian.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import com.antitheft.common.protocol.SmsPduSerializer
import com.antitheft.guardian.db.DirectBootStorageHelper
import com.antitheft.guardian.location.LocationCaptureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles inbound SMS commands sent by Owner Admin.
 * Rejects unauthenticated or replayed messages silently.
 */
class SmsCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val storageHelper = DirectBootStorageHelper(context)
        val sharedSecret = storageHelper.getPairingSecret()
        val trustedPhone = storageHelper.getTrustedPhoneNumber()

        for (sms in messages) {
            val body = sms.messageBody ?: continue
            val command = SmsPduSerializer.parseInboundCommand(body, sharedSecret) ?: continue

            // 1. Verify HMAC
            if (!command.isHmacValid) {
                Log.w(TAG, "Inbound command failed HMAC verification! Dropping silently.")
                continue
            }

            // 2. Anti-Replay: Check monotonic sequence counter
            val lastCounter = storageHelper.getNextSequenceNumber()
            if (command.sequenceNumber <= lastCounter) {
                Log.w(TAG, "Inbound command has replayed counter ${command.sequenceNumber} <= $lastCounter! Dropping.")
                continue
            }

            // 3. Expiration window check
            val nowEpoch = System.currentTimeMillis() / 1000
            if (nowEpoch > command.expiryEpochSec) {
                Log.w(TAG, "Inbound command expired! Current: $nowEpoch, Expiry: ${command.expiryEpochSec}")
                continue
            }

            Log.i(TAG, "Authenticated command received: ${command.commandType}")

            // Execute command
            when (command.commandType) {
                "LOCATE" -> {
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val captureManager = LocationCaptureManager(context)
                            val locEntity = captureManager.captureCurrentLocation()
                            
                            if (locEntity != null && trustedPhone.isNotBlank()) {
                                val outSeq = storageHelper.getNextSequenceNumber()
                                val responsePdu = SmsPduSerializer.formatLocationReport(
                                    alias = storageHelper.getDeviceAlias(),
                                    latitude = locEntity.latitude,
                                    longitude = locEntity.longitude,
                                    accuracyMeters = locEntity.accuracy.toInt(),
                                    captureEpochSec = locEntity.captureTimeUtc,
                                    sequenceNumber = outSeq,
                                    sharedSecretKeyBytes = sharedSecret
                                )
                                val smsManager = context.getSystemService(SmsManager::class.java)
                                    ?: SmsManager.getDefault()
                                smsManager.sendTextMessage(trustedPhone, null, responsePdu, null, null)
                                Log.i(TAG, "Dispatched locate response via SMS.")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed executing LOCATE command", e)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "SmsCommandReceiver"
    }
}
