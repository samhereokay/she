package com.antitheft.guardian.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import com.antitheft.guardian.db.DirectBootStorageHelper
import com.antitheft.guardian.location.LocationCaptureManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Monitors SIM card state changes (ejection, swap) and triggers emergency beacon.
 */
class SimStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != "android.intent.action.SIM_STATE_CHANGED") return

        val stateExtra = intent.getStringExtra("ss")
        Log.i(TAG, "SIM state changed: $stateExtra")

        if (stateExtra == "ABSENT" || stateExtra == "LOADED") {
            val storageHelper = DirectBootStorageHelper(context)
            val trustedPhone = storageHelper.getTrustedPhoneNumber()
            if (trustedPhone.isBlank()) return

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val captureManager = LocationCaptureManager(context)
                    captureManager.captureCurrentLocation()
                    
                    // Immediate SMS notification
                    val smsManager = context.getSystemService(SmsManager::class.java)
                        ?: SmsManager.getDefault()
                    val alertMsg = "ALERT: SIM change detected on Guardian device ${storageHelper.getDeviceAlias()}"
                    smsManager.sendTextMessage(trustedPhone, null, alertMsg, null, null)
                    Log.w(TAG, "SIM change alert dispatched to trusted contact.")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed processing SIM state change", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        private const val TAG = "SimStateReceiver"
    }
}
