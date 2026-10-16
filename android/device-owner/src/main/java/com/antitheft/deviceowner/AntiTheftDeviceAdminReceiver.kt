package com.antitheft.deviceowner

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.util.Log

/**
 * Device Administrator and Device Policy Controller (DPC) Receiver for Anti-Theft.
 * Handles lifecycle callbacks, failed unlock attempts, and kiosk mode events.
 */
class AntiTheftDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "DEVICE_ADMIN_ENABLED: Admin receiver is now active.")
        listener?.onAdminStatusChanged(isActive = true)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.w(TAG, "DEVICE_ADMIN_DISABLE_REQUESTED: User or process requested disabling device admin.")
        return "Warning: Disabling Anti-Theft Device Protection will remove theft lockdown, factory reset protection, and safe-boot restrictions."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "DEVICE_ADMIN_DISABLED: Admin receiver was disabled.")
        listener?.onAdminStatusChanged(isActive = false)
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordFailed(context, intent, user)
        val dpm = getManager(context)
        val attempts = dpm.getCurrentFailedPasswordAttempts()
        Log.w(TAG, "PASSWORD_ATTEMPT_FAILED: Total consecutive failed attempts: $attempts (User: $user)")
        
        listener?.onFailedUnlockAttempt(attempts)
        
        // Broadcast local intent for Guardian service to record security event
        val failIntent = Intent(ACTION_SECURITY_PASSWORD_FAILED).apply {
            putExtra(EXTRA_FAILED_ATTEMPTS, attempts)
            setPackage(context.packageName)
        }
        context.sendBroadcast(failIntent)
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordSucceeded(context, intent, user)
        Log.i(TAG, "PASSWORD_ATTEMPT_SUCCEEDED: Keyguard successfully unlocked. Resetting failure counter.")
        listener?.onSuccessfulUnlock()
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Log.i(TAG, "LOCK_TASK_ENTERING: Kiosk mode active for package: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        Log.i(TAG, "LOCK_TASK_EXITING: Kiosk mode exited.")
    }

    interface AdminEventListener {
        fun onAdminStatusChanged(isActive: Boolean) {}
        fun onFailedUnlockAttempt(failedAttempts: Int) {}
        fun onSuccessfulUnlock() {}
    }

    companion object {
        private const val TAG = "AntiTheftAdminReceiver"
        const val ACTION_SECURITY_PASSWORD_FAILED = "com.antitheft.deviceowner.ACTION_PASSWORD_FAILED"
        const val EXTRA_FAILED_ATTEMPTS = "failed_attempts"

        var listener: AdminEventListener? = null

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, AntiTheftDeviceAdminReceiver::class.java)
        }
    }
}
