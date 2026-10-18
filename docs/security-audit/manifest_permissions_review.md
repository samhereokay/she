# SHE Android Manifest & Permission Security Review

**Date**: 2026-10-18  
**Scope**: 
- `android/app-guardian` (`directRelease`, `playRelease`)
- `android/app-admin` (`release`)
- `android/device-owner` (Device Policy Controller)
- Merged Manifest artifacts in `build/intermediates/merged_manifests/`

---

## 1. Executive Summary

This review analyzes every permission and exported component declared across the merged Android manifests of the SHE suite (**SHE Guardian** and **SHE Admin**). 

The application architecture enforces strict separation of responsibilities:
- **SHE Guardian**: Sits on the target protected device, requiring high-privilege hardware capabilities (Background Location, SMS Telephony fallback, Device Admin / Device Owner restrictions, Direct Boot resumption) to maintain anti-theft tracking even when offline or before the initial user screen unlock.
- **SHE Admin**: Runs on the owner's personal device, requiring minimal privileges (Internet for backend communication, Biometrics for local vault access, Camera for scanning pairing QR codes).

---

## 2. Merged Permission Analysis: SHE Guardian (`com.antitheft.guardian`)

### Declared Permissions Table

| Permission | Protection Level | Purpose / Architectural Justification | Over-Privilege Assessment & Remediation Notes |
| :--- | :--- | :--- | :--- |
| `ACCESS_FINE_LOCATION` | Dangerous (Runtime) | High-accuracy GPS tracking during emergency/stolen tracking mode. | **Justified**: Required for precise recovery coordinates submitted to backend and emergency SMS beacons. |
| `ACCESS_COARSE_LOCATION` | Dangerous (Runtime) | Approximate cellular/Wi-Fi location fallback when GPS fix is slow or indoors. | **Justified**: Standard pairing with FINE_LOCATION. |
| `ACCESS_BACKGROUND_LOCATION` | Dangerous (Runtime) | Allows location updates when screen is locked, app is backgrounded, or kiosk lock is active. | **Justified**: Anti-theft tracking is ineffective if restricted to foreground UI. Requires explicit OS permission grant. |
| `FOREGROUND_SERVICE` | Normal | Allows execution of persistent tracking foreground service (`TrackingForegroundService`). | **Justified**: Android 9+ requirement for non-dismissible background services. |
| `FOREGROUND_SERVICE_LOCATION` | Normal | Specifies the foreground service type as `location` under Android 14 (API 34) platform policy. | **Justified**: Mandatory on Android 14+ when running location services from background. |
| `RECEIVE_BOOT_COMPLETED` | Normal | Wakes up tracking daemon immediately after device reboot (`BOOT_COMPLETED`). | **Justified**: Core anti-theft requirement. Paired with `LOCKED_BOOT_COMPLETED` for Direct Boot support. |
| `READ_PHONE_STATE` | Dangerous (Runtime) | Detects SIM removal, SIM swap, or carrier change events (`SIM_STATE_CHANGED`). | **Justified**: Required to alert owner if a thief swaps out the owner's SIM card. |
| `POST_NOTIFICATIONS` | Dangerous (Runtime) | Displays ongoing foreground service notification and security status on Android 13+ (API 33). | **Justified**: Mandatory under Android 13+ platform requirements. |
| `SEND_SMS` | Dangerous (Runtime) | Sends out-of-band emergency SMS beacons containing live GPS coordinates when cellular data/Wi-Fi is killed. | **Justified for Direct Flavor**: Essential offline fallback when internet is disabled. **Over-privileged for Google Play**: Requires Play Store SMS Exemption Form or flavor stripping. |
| `RECEIVE_SMS` | Dangerous (Runtime) | Intercepts remote inbound SMS commands (`#LOC`, `#ALARM`, `#WIPE`) from trusted admin number. | **Justified for Direct Flavor**: Handled with priority 999 and permission `BROADCAST_SMS`. Same Play Store compliance restriction as `SEND_SMS`. |
| `CALL_PHONE` | Dangerous (Runtime) | Initiates an immediate phone call to the owner's emergency contact from the lockscreen/kiosk UI. | **Potential Over-Privilege**: `CALL_PHONE` executes direct dialing without user confirmation dialog. `Intent.ACTION_DIAL` (which requires **NO permission**) already serves as the fallback in `GuardianMainActivity.kt:102`. Recommendation: Consider dropping `CALL_PHONE` in favor of standard `ACTION_DIAL` to reduce dangerous permission footprint. |
| `INTERNET` | Normal | Transmits encrypted location updates, logs, and ECDSA telemetry to backend REST API. | **Justified**: Standard network communication. |
| `ACCESS_NETWORK_STATE` | Normal | Monitors network connectivity to toggle between HTTP sync and SMS fallback. | **Justified**: Standard network check. |
| `CAMERA` | Dangerous (Runtime) | Scans pairing QR codes displayed by the SHE Admin app during initial setup. | **Justified**: Used strictly by ZXing barcode scanner activity. `android.hardware.camera` is marked `required="false"` to prevent filtering non-camera hardware. |
| `WAKE_LOCK` | Normal | Ensures CPU remains awake briefly during critical location acquisition and SMS dispatch. | **Justified**: Prevents aggressive OS doze mode from terminating beacon transmissions. |
| `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Signature | Android 14 internal permission for dynamic runtime receivers. | **Justified**: Automatically injected by Android build tools / AndroidX to prevent external broadcast hijacking. |

---

## 3. Merged Permission Analysis: SHE Admin (`com.antitheft.admin`)

### Declared Permissions Table

| Permission | Protection Level | Purpose / Architectural Justification | Over-Privilege Assessment & Remediation Notes |
| :--- | :--- | :--- | :--- |
| `INTERNET` | Normal | Communicates with the SHE Backend API to issue commands, query device status, and stream telemetry. | **Justified**: Core admin functionality. |
| `ACCESS_NETWORK_STATE` | Normal | Verifies network availability before making API calls. | **Justified**: Standard diagnostic check. |
| `USE_BIOMETRIC` | Normal | Authenticates the phone owner before unlocking the encrypted Admin Vault. | **Justified**: Local credential security. |
| `USE_FINGERPRINT` | Normal (Deprecated) | Backward compatibility fallback for biometrics on Android API 26-27 devices. | **Justified**: Standard AndroidX Biometric library dependency. |
| `CAMERA` | Dangerous (Runtime) | Scans Guardian pairing QR code during initial device association. | **Justified**: Handled by ZXing scanner; hardware camera marked `required="false"`. |
| `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Signature | Platform-generated permission for Android 14 receiver isolation. | **Justified**: Internal security mechanism. |

---

## 4. Exported Components Security Audit

### 4.1 SHE Guardian

1. **`com.antitheft.guardian.ui.GuardianMainActivity`**
   - **Exported**: `true`
   - **Intent Filter**: `android.intent.action.MAIN`, `android.intent.category.LAUNCHER`
   - **Security Evaluation**: Legitimate entry point for app launcher. Does not expose privileged internal parameters via intent extras.
2. **`com.antitheft.guardian.directboot.BootReceiver`**
   - **Exported**: `true`
   - **Intent Filter**: `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`
   - **Security Evaluation**: Protected by Android OS system boundaries. Third-party applications cannot forge or broadcast `BOOT_COMPLETED` intents.
3. **`com.antitheft.guardian.location.LowBatteryReceiver`**
   - **Exported**: `true`
   - **Intent Filter**: `BATTERY_LOW`
   - **Security Evaluation**: System broadcast protected by Android OS.
4. **`com.antitheft.guardian.sms.SmsCommandReceiver`**
   - **Exported**: `true`
   - **Intent Filter**: `android.provider.Telephony.SMS_RECEIVED` (priority `999`)
   - **Permission Gate**: Protected by `android:permission="android.permission.BROADCAST_SMS"`
   - **Security Evaluation**: Strict OS protection. Only the telephony stack holding `BROADCAST_SMS` signature permission can send broadcasts to this receiver. Non-system apps attempting to spoof SMS broadcasts are rejected by Android PackageManager.
5. **`com.antitheft.guardian.sms.SimStateReceiver`**
   - **Exported**: `true`
   - **Intent Filter**: `android.intent.action.SIM_STATE_CHANGED`
   - **Security Evaluation**: Protected system broadcast.
6. **`com.antitheft.deviceowner.AntiTheftDeviceAdminReceiver`**
   - **Exported**: `true`
   - **Permission Gate**: Protected by `android:permission="android.permission.BIND_DEVICE_ADMIN"`
   - **Security Evaluation**: Strict OS protection. Only the system DevicePolicyManagerService holding `BIND_DEVICE_ADMIN` can interact with this receiver.
7. **`com.antitheft.guardian.location.TrackingForegroundService`**
   - **Exported**: `false`
   - **Security Evaluation**: Clean. Cannot be launched or bound by external malicious apps.
8. **`androidx.startup.InitializationProvider`**
   - **Exported**: `false`
   - **Security Evaluation**: Clean. ContentProvider is unexported.

### 4.2 SHE Admin

1. **`com.antitheft.admin.auth.AdminLoginActivity`**
   - **Exported**: `true`
   - **Intent Filter**: `android.intent.action.MAIN`, `android.intent.category.LAUNCHER`
   - **Security Evaluation**: Legitimate launcher entry point.
2. **`com.antitheft.admin.dashboard.AdminDashboardActivity`**
   - **Exported**: `false`
   - **Security Evaluation**: Secure. Cannot be directly launched by external third-party applications to bypass the login gate.
3. **`com.antitheft.admin.pairing.PairDeviceActivity`**
   - **Exported**: `false`
   - **Security Evaluation**: Secure.
4. **`com.antitheft.admin.vault.AdminVaultActivity`**
   - **Exported**: `false`
   - **Security Evaluation**: Secure. Unexported; requires explicit intent from authenticated dashboard or biometric prompt.

---

## 5. Security Conclusions & Unaddressed Items for Review

1. **`CALL_PHONE` Assessment**: `GuardianMainActivity` uses `CALL_PHONE` to trigger direct calling of emergency contact. Since it already gracefully falls back to `Intent.ACTION_DIAL` (which requires zero permissions), removing `android.permission.CALL_PHONE` would reduce user friction and app permission footprint.
2. **Play Store Telephony Exemption**: Sideloaded `directRelease` flavor correctly retains SMS/Telephony fallback. For any future Google Play release (`playRelease`), SMS permissions must either be gated by Google Play's SMS/Call Log policy declaration form or separated into a direct-download APK only.
3. **Component Exposure**: Zero unauthorized exported services or unauthenticated exported activities exist. All exported receivers are gated either by system broadcast protections or explicit signature permissions (`BIND_DEVICE_ADMIN`, `BROADCAST_SMS`).
