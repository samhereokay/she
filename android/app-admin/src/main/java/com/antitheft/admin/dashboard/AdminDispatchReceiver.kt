package com.antitheft.admin.dashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.antitheft.admin.BuildConfig
import com.antitheft.admin.auth.AdminSessionManager
import com.antitheft.admin.crypto.AdminKeystoreManager
import com.antitheft.admin.network.ApiClient
import com.antitheft.admin.network.CommandDispatchRequest
import com.antitheft.admin.network.RegisterTrustedDeviceRequest
import com.antitheft.common.crypto.EcdsaSigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.UUID

/**
 * Background command dispatch receiver for Admin companion app.
 * Allows dispatching signed recovery commands even when UI task switching is restricted.
 * Gated strictly behind BuildConfig.DEBUG.
 */
class AdminDispatchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val targetDeviceId = intent.getStringExtra("target_device_id") ?: return
        val commandType = intent.getStringExtra("command_type") ?: return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val sessionManager = AdminSessionManager(context)
                val adminKeystore = AdminKeystoreManager()
                val api = ApiClient.create(sessionManager)

                var trustedId = sessionManager.getTrustedDeviceId()
                if (trustedId.isNullOrBlank()) {
                    val adminPem = adminKeystore.getPublicKeyPem()
                    val regResp = api.registerTrustedDevice(
                        RegisterTrustedDeviceRequest(
                            device_name = "Admin Controller Phone",
                            public_key_pem = adminPem
                        )
                    )
                    trustedId = regResp.id
                    sessionManager.saveTrustedDeviceId(trustedId)
                }

                val cmdId = UUID.randomUUID().toString()
                val seq = sessionManager.getNextCommandSequence()
                val expiryEpoch = (System.currentTimeMillis() / 1000) + 300
                val paramsJson = "{}"
                val pairingId = sessionManager.getDevicePairingId(targetDeviceId) ?: ""

                val canonicalBytes = EcdsaSigner.createCanonicalCommandBytes(
                    pairingId = pairingId,
                    commandId = cmdId,
                    targetDeviceId = targetDeviceId,
                    commandType = commandType,
                    sequenceCounter = seq,
                    expiryEpoch = expiryEpoch,
                    parametersJson = paramsJson
                )

                val derSig = adminKeystore.signCommand(canonicalBytes)
                val sigB64 = Base64.getEncoder().encodeToString(derSig)

                val dispatchReq = CommandDispatchRequest(
                    command_id = cmdId,
                    pairing_id = pairingId,
                    trusted_device_id = trustedId,
                    target_device_id = targetDeviceId,
                    command_type = commandType,
                    sequence_counter = seq,
                    expiry_epoch = expiryEpoch,
                    parameters_json = paramsJson,
                    admin_signature = sigB64
                )

                val result = api.dispatchCommand(dispatchReq)
                Log.i("AdminDispatchReceiver", "DISPATCHED_COMMAND ID:${result.id} TYPE:${result.command_type} STATUS:${result.status}")
            } catch (e: Exception) {
                Log.e("AdminDispatchReceiver", "Error dispatching command via receiver", e)
            } finally {
                pending.finish()
            }
        }
    }
}
