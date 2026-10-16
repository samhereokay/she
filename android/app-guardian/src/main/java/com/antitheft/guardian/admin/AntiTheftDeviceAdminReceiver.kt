package com.antitheft.guardian.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

class AntiTheftDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin policy successfully enabled.")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device Admin policy disabled by user.")
    }

    companion object {
        private const val TAG = "AntiTheftDeviceAdmin"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, AntiTheftDeviceAdminReceiver::class.java)
        }

        fun isDeviceAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            return dpm?.isAdminActive(getComponentName(context)) ?: false
        }

        fun lockDevice(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return false
            val adminComponent = getComponentName(context)
            return if (dpm.isAdminActive(adminComponent)) {
                try {
                    dpm.lockNow()
                    Log.i(TAG, "Device successfully locked via DevicePolicyManager.lockNow()")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed locking device via DevicePolicyManager", e)
                    false
                }
            } else {
                Log.w(TAG, "Cannot lock device: Device Admin is not active.")
                false
            }
        }
    }
}
