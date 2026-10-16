package com.antitheft.deviceowner

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import android.util.Log

/**
 * High-level manager for Protection Tier 3 (Device Owner / Enterprise DPC).
 * Controls theft lockdown restrictions, safe-boot suppression, and kiosk lock-task mode.
 */
class DeviceOwnerManager {

    fun getAdminComponent(context: Context): ComponentName {
        return AntiTheftDeviceAdminReceiver.getComponentName(context)
    }

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun isProfileOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        return dpm.isProfileOwnerApp(context.packageName)
    }

    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        return dpm.isAdminActive(getAdminComponent(context))
    }

    /**
     * Applies core anti-theft user restrictions to prevent thief from disabling app or resetting device.
     * Requires Device Owner privileges.
     */
    fun applyTheftRestrictions(context: Context): Map<String, Boolean> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return emptyMap()
        val admin = getAdminComponent(context)
        val results = mutableMapOf<String, Boolean>()

        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Cannot apply theft restrictions: App is not provisioned as Device Owner.")
            return emptyMap()
        }

        val restrictions = listOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            UserManager.DISALLOW_AIRPLANE_MODE
        )

        for (res in restrictions) {
            try {
                dpm.addUserRestriction(admin, res)
                results[res] = true
                Log.i(TAG, "ENFORCED_USER_RESTRICTION: $res")
            } catch (e: Exception) {
                results[res] = false
                Log.e(TAG, "Failed applying restriction: $res", e)
            }
        }

        return results
    }

    /**
     * Clears all anti-theft user restrictions upon verified owner de-escalation.
     */
    fun clearTheftRestrictions(context: Context): Map<String, Boolean> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return emptyMap()
        val admin = getAdminComponent(context)
        val results = mutableMapOf<String, Boolean>()

        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            return emptyMap()
        }

        val restrictions = listOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            UserManager.DISALLOW_AIRPLANE_MODE
        )

        for (res in restrictions) {
            try {
                dpm.clearUserRestriction(admin, res)
                results[res] = true
                Log.i(TAG, "CLEARED_USER_RESTRICTION: $res")
            } catch (e: Exception) {
                results[res] = false
                Log.e(TAG, "Failed clearing restriction: $res", e)
            }
        }

        return results
    }

    /**
     * Inspects which restrictions are currently active on the device.
     */
    fun getActiveRestrictions(context: Context): Map<String, Boolean> {
        val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            ?: return emptyMap()
        
        val keys = listOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            UserManager.DISALLOW_AIRPLANE_MODE
        )

        return keys.associateWith { key ->
            userManager.hasUserRestriction(key)
        }
    }

    /**
     * Sets recovery contact message displayed prominently on the keyguard lock screen.
     */
    fun setDeviceOwnerLockScreenInfo(context: Context, info: CharSequence?): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        val admin = getAdminComponent(context)
        return try {
            dpm.setDeviceOwnerLockScreenInfo(admin, info)
            Log.i(TAG, "LOCK_SCREEN_INFO_SET: $info")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed setting lock screen info", e)
            false
        }
    }

    /**
     * Retrieves the current device owner lock screen info string.
     */
    fun getDeviceOwnerLockScreenInfo(context: Context): CharSequence? {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return null
        return try {
            dpm.getDeviceOwnerLockScreenInfo()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Authenticated Deprovisioning: Safely clears Device Owner status.
     * Reverts phone to standard consumer mode upon verified owner de-escalation.
     */
    fun clearDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        return try {
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                clearTheftRestrictions(context)
                setDeviceOwnerLockScreenInfo(context, null)
                dpm.clearDeviceOwnerApp(context.packageName)
                Log.i(TAG, "DEVICE_OWNER_DEPROVISIONED: Cleared Device Owner privileges.")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed deprovisioning Device Owner", e)
            false
        }
    }

    /**
     * Enters Lock-Task (Kiosk) Mode in stolen state to suppress navigation and power menu.
     */
    fun enterLockTaskStolenMode(activity: Activity): Boolean {
        val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        val admin = getAdminComponent(activity)

        if (!dpm.isDeviceOwnerApp(activity.packageName)) {
            Log.w(TAG, "Lock-Task mode unavailable: Device Owner required.")
            return false
        }

        try {
            // Allow this package and system dialer to run in lock-task mode for emergency return
            val allowedPackages = (arrayOf(
                activity.packageName,
                "com.android.dialer",
                "com.google.android.dialer",
                "com.android.phone"
            )).distinct().toTypedArray()
            dpm.setLockTaskPackages(admin, allowedPackages)

            // Enable Keyguard feature so lock-screen recovery message displays upon lockNow()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD)
            }

            activity.startLockTask()
            Log.i(TAG, "ACTIVATED_LOCK_TASK_MODE: Kiosk stolen recovery mode active with dialer whitelisted.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed entering lock-task mode", e)
            return false
        }
    }

    /**
     * Pre-configures Lock-Task packages so the activity can enter kiosk immediately.
     */
    fun setLockTaskPackages(context: Context, packages: Array<String>): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        val admin = getAdminComponent(context)
        return try {
            val allowedPackages = (packages + arrayOf(
                "com.android.dialer",
                "com.google.android.dialer",
                "com.android.phone"
            )).distinct().toTypedArray()
            dpm.setLockTaskPackages(admin, allowedPackages)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed setting lock task packages", e)
            false
        }
    }

    /**
     * Exits Lock-Task Mode when recovery code or owner authentication is confirmed.
     */
    fun exitLockTaskMode(activity: Activity) {
        try {
            activity.stopLockTask()
            Log.i(TAG, "EXITED_LOCK_TASK_MODE")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping lock-task mode", e)
        }
    }

    /**
     * Immediately locks device keyguard.
     */
    fun lockNow(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        val admin = getAdminComponent(context)
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
            Log.i(TAG, "DEVICE_LOCKED_VIA_DPM: lockNow() executed.")
            return true
        }
        return false
    }

    /**
     * Generates the command required to provision this package as Device Owner via ADB.
     */
    fun getAdbProvisioningCommand(context: Context): String {
        return "adb shell dpm set-device-owner ${context.packageName}/${AntiTheftDeviceAdminReceiver::class.java.name}"
    }

    companion object {
        private const val TAG = "DeviceOwnerManager"
    }
}
