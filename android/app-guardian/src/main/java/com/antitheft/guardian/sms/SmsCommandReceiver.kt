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

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val storageHelper = DirectBootStorageHelper(context)
                val sharedSecret = storageHelper.getPairingSecret()
                val trustedPhone = storageHelper.getTrustedPhoneNumber()

                for (sms in messages) {
                    val body = sms.messageBody ?: continue
                    val result = SmsCommandProcessor.processInboundPdu(body, storageHelper)
                    if (result.status != SmsProcessStatus.ACCEPTED || result.command == null) {
                        Log.w(TAG, "Inbound SMS rejected: ${result.status} (${result.reason}). Dropping.")
                        continue
                    }

                    val command = result.command
                    Log.i(TAG, "Authenticated command received: ${command.commandType} (Seq: ${command.sequenceNumber})")

                    // Execute command
                    when (command.commandType.uppercase()) {
                        "LOCATE" -> {
                            com.antitheft.guardian.commands.CommandExecutionManager.executeLocate(context)
                            try {
                                val replyPhone = com.antitheft.common.protocol.PhoneNumberNormalizer.normalizeForSmsDispatch(trustedPhone)
                                if (replyPhone.isBlank()) {
                                    Log.w(TAG, "Strict routing: No registered trusted phone number! Outbound SMS response dropped.")
                                } else {
                                    val captureManager = LocationCaptureManager(context)
                                    val locEntity = captureManager.captureCurrentLocation(timeoutMs = 5000)
                                        ?: captureManager.getLastKnownLocation()

                                    if (locEntity != null) {
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
                                        smsManager.sendTextMessage(replyPhone, null, responsePdu, null, null)
                                        Log.i(TAG, "Dispatched locate response via SMS strictly to trusted phone: $replyPhone.")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed executing LOCATE command", e)
                            }
                        }
                        "RING" -> {
                            com.antitheft.guardian.commands.CommandExecutionManager.executeRing(context)
                        }
                        "STOP_RING" -> {
                            com.antitheft.guardian.commands.CommandExecutionManager.executeStopRing()
                        }
                        "LOCK" -> {
                            com.antitheft.guardian.commands.CommandExecutionManager.executeLock(context)
                        }
                        "STOLEN_MODE" -> {
                            com.antitheft.guardian.commands.CommandExecutionManager.executeStolenMode(context, true)
                        }
                        "EXIT_KIOSK", "RECOVERED" -> {
                            com.antitheft.guardian.commands.CommandExecutionManager.executeStolenMode(context, false)
                        }
                        else -> {
                            Log.w(TAG, "Unknown SMS command: ${command.commandType}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed processing inbound SMS", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SmsCommandReceiver"
    }
}
