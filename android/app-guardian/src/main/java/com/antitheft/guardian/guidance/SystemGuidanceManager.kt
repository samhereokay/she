package com.antitheft.guardian.guidance

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Baseline System Guidance & Security Checklist for Guardian Lite.
 * Evaluates OS-level security features (Screen lock, Find My Device, SIM PIN, OEM power-menu protection)
 * without requiring Device Admin or elevated privileges.
 */
class SystemGuidanceManager {

    data class ChecklistItem(
        val id: String,
        val title: String,
        val description: String,
        val isEnabled: Boolean,
        val recommendation: String,
        val settingsIntentAction: String? = null
    )

    data class OemPowerMenuGuidance(
        val manufacturer: String,
        val settingName: String,
        val instructions: String,
        val settingsIntent: Intent
    )

    /**
     * Evaluates all baseline system security checklist items.
     */
    fun evaluateSystemChecklist(context: Context): List<ChecklistItem> {
        val list = mutableListOf<ChecklistItem>()

        // 1. Screen Lock & PIN Security
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isSecure = km?.isDeviceSecure ?: false
        list.add(
            ChecklistItem(
                id = "screen_lock",
                title = "Device Screen Lock (PIN/Password)",
                description = "Requires credential to access phone and unlocks Credential Encrypted (CE) storage.",
                isEnabled = isSecure,
                recommendation = if (isSecure) "Configured (Secure)" else "CRITICAL: Configure a 6+ digit PIN or passphrase.",
                settingsIntentAction = Settings.ACTION_SECURITY_SETTINGS
            )
        )

        // 2. Google Find My Device / Find Hub
        val hasFindMyDevice = isFindMyDeviceConfigured(context)
        list.add(
            ChecklistItem(
                id = "find_hub",
                title = "Google Find My Device / Find Hub",
                description = "Google's offline crowd-sourced network for locating powered-down or offline phones.",
                isEnabled = hasFindMyDevice,
                recommendation = if (hasFindMyDevice) "Available on device" else "Ensure Google account is signed in and Find My Device is enabled.",
                settingsIntentAction = "com.google.android.gms.settings.FIND_MY_DEVICE"
            )
        )

        // 3. SIM Card PIN Lock
        val simStatus = evaluateSimLock(context)
        list.add(
            ChecklistItem(
                id = "sim_pin",
                title = "SIM Card PIN Lock",
                description = "Prevents a thief from transferring your SIM to another phone to intercept 2FA SMS recovery codes.",
                isEnabled = simStatus.isPinConfigured,
                recommendation = simStatus.guidance,
                settingsIntentAction = "android.settings.SECURITY_SETTINGS"
            )
        )

        // 4. Power Menu Protection on Lock Screen
        val oem = getOemPowerMenuGuidance()
        list.add(
            ChecklistItem(
                id = "power_menu_lock",
                title = "Lock Screen Power-Off Protection (${oem.manufacturer})",
                description = "Requires PIN before allowing the phone to be turned off from the lock screen.",
                isEnabled = false, // Cannot be queried programmatically on non-rooted Android; guidance required
                recommendation = "${oem.settingName}: ${oem.instructions}",
                settingsIntentAction = Settings.ACTION_SECURITY_SETTINGS
            )
        )

        return list
    }

    private fun isFindMyDeviceConfigured(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            pm.getPackageInfo("com.google.android.gms", 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    data class SimStatusResult(val isPinConfigured: Boolean, val guidance: String)

    private fun evaluateSimLock(context: Context): SimStatusResult {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return SimStatusResult(false, "Telephony service unavailable")

        return when (tm.simState) {
            TelephonyManager.SIM_STATE_ABSENT -> SimStatusResult(false, "No physical SIM card detected (eSIM or absent).")
            TelephonyManager.SIM_STATE_PIN_REQUIRED, TelephonyManager.SIM_STATE_PUK_REQUIRED -> SimStatusResult(true, "SIM PIN lock is ACTIVE.")
            TelephonyManager.SIM_STATE_READY -> SimStatusResult(false, "SIM is active. Set up a SIM PIN in Security Settings -> More Security Settings -> SIM card lock.")
            else -> SimStatusResult(false, "Configure SIM PIN to block 2FA intercept.")
        }
    }

    /**
     * Identifies OEM specific settings path to require unlock for power off.
     */
    fun getOemPowerMenuGuidance(customManufacturer: String? = null): OemPowerMenuGuidance {
        val raw = customManufacturer ?: (try { Build.MANUFACTURER } catch (e: Exception) { null }) ?: "Generic"
        val manufacturer = raw.lowercase(Locale.ROOT)
        return when {
            manufacturer.contains("samsung") -> OemPowerMenuGuidance(
                manufacturer = "Samsung",
                settingName = "Lock Network and Security",
                instructions = "Go to Settings -> Lock screen -> Secure lock settings -> Enable 'Lock network and security'. This prevents switching off Wi-Fi/Mobile Data and powering off without unlocking.",
                settingsIntent = Intent(Settings.ACTION_SECURITY_SETTINGS)
            )
            manufacturer.contains("google") -> OemPowerMenuGuidance(
                manufacturer = "Google Pixel",
                settingName = "Lockdown Mode",
                instructions = "Go to Settings -> Display -> Lock screen -> Enable 'Show lockdown option'. In power menu, tap 'Lockdown' to disable biometrics and notifications.",
                settingsIntent = Intent(Settings.ACTION_SECURITY_SETTINGS)
            )
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> OemPowerMenuGuidance(
                manufacturer = "Xiaomi / HyperOS",
                settingName = "Secure Power-Off",
                instructions = "Go to Settings -> Passwords & security -> Privacy -> Disable 'Access power menu on secure lock screen'.",
                settingsIntent = Intent(Settings.ACTION_SECURITY_SETTINGS)
            )
            manufacturer.contains("oneplus") || manufacturer.contains("oppo") -> OemPowerMenuGuidance(
                manufacturer = "OnePlus / Oppo",
                settingName = "Require Password to Power Off",
                instructions = "Go to Settings -> Password & Security -> System security -> Enable 'Require password to power off'.",
                settingsIntent = Intent(Settings.ACTION_SECURITY_SETTINGS)
            )
            else -> OemPowerMenuGuidance(
                manufacturer = if (raw == "Generic") "Android" else raw.replaceFirstChar { it.uppercase() },
                settingName = "Lock Screen Security",
                instructions = "Go to Settings -> Security -> Ensure device credentials are required before power-down or toggling network settings.",
                settingsIntent = Intent(Settings.ACTION_SECURITY_SETTINGS)
            )
        }
    }
}
