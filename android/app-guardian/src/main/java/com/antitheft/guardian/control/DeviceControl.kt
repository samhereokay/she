package com.antitheft.guardian.control

import android.content.Context

/**
 * Clean hardware abstraction layer for device control policies.
 * In Guardian Lite, all device administration and lock privileges are unsupported,
 * keeping the app fully decoupled from Device Admin and Device Owner APIs.
 *
 * In Part 2 (Hardened flavor), HardenedDeviceControl will implement this interface
 * without requiring any modifications to the calling code.
 */
interface DeviceControl {
    fun lockDevice(context: Context): Boolean
    fun isDeviceOwner(context: Context): Boolean
    fun isDeviceAdminActive(context: Context): Boolean
    fun applyTheftRestrictions(context: Context): Boolean
    fun clearTheftRestrictions(context: Context): Boolean
    fun enterLockTaskStolenMode(context: Context): Boolean
    fun exitLockTaskMode(context: Context): Boolean
    fun setLockScreenInfo(context: Context, info: String?): Boolean
    fun clearDeviceOwner(context: Context): Boolean
    fun getActiveRestrictions(context: Context): Map<String, Boolean>
}

/**
 * Lite implementation: unsupported no-ops returning false/empty.
 * Guardian Lite operates with standard user permissions only.
 */
class LiteDeviceControl : DeviceControl {
    override fun lockDevice(context: Context): Boolean = false
    override fun isDeviceOwner(context: Context): Boolean = false
    override fun isDeviceAdminActive(context: Context): Boolean = false
    override fun applyTheftRestrictions(context: Context): Boolean = false
    override fun clearTheftRestrictions(context: Context): Boolean = false
    override fun enterLockTaskStolenMode(context: Context): Boolean = false
    override fun exitLockTaskMode(context: Context): Boolean = false
    override fun setLockScreenInfo(context: Context, info: String?): Boolean = false
    override fun clearDeviceOwner(context: Context): Boolean = false
    override fun getActiveRestrictions(context: Context): Map<String, Boolean> = emptyMap()
}
