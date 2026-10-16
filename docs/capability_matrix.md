# Android Anti-Theft & Recovery System: Capability Matrix

This document tracks verified capabilities, explicit simulation test commands, and pending real-hardware tests. Every capability claim is anchored in official Android documentation and platform security guarantees.

---

## 1. Protection Tiers Overview

| Tier | Layer | Deployment Model | OS Permissions / Privileges | Verification & Test Status | Primary Function |
|---|---|---|---|---|---|
| **Tier 1** | **System Layer (Guidance)** | Native OS / Google Play Services | None (Audit & Checklist UI) | **Untested on Hardware** (UI scaffolded in Emulator) | Guides owner to activate OS-level locks (Theft Detection Lock, Offline Device Lock, Remote Lock, Find Hub, SIM PIN, power menu lock). |
| **Tier 2** | **Normal App (Guardian)** | Sideloaded APK (Direct APK) | Standard Runtime Permissions (`ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `SEND_SMS`, `RECEIVE_SMS`, `POST_NOTIFICATIONS`) | **Simulated in Emulator (API 34)**; Cellular/GPS/Battery pending real hardware | Autonomous GNSS capture, SQLCipher-encrypted Room storage in Direct Boot DE context, durable sync queue, 48-bit HMAC SMS fallback, SIM change & immediate low-battery beacons. |
| **Tier 3** | **Device Owner (Guardian DO)** | Dedicated Provisioning (ADB or QR out-of-box) | Device Administrator (`android.app.admin.DevicePolicyManager`) with Device Owner status | **Simulated in Emulator (API 34)**; OEM setting lockdowns pending real hardware | Restricts factory reset, safe boot, airplane mode, USB debugging, app uninstall; enforces kiosk Lock-Task mode on stolen trigger. **Requires zero-account unprovisioned phone**. |

---

## 2. Detailed Capability Matrix

### Tier 1: System Guidance & Verification Checklist

| Capability | OS Requirement | Availability / Limitations | Verification Status & Test Executed | Official Android Documentation Link |
|---|---|---|---|---|
| **Google Find Hub (Find My Device Network)** | Android 9+ (API 28+) via Play Services | **Guidance only.** Third-party apps have **zero API access** to the crowd-sourced Bluetooth tracker mesh. App audits whether Google Find My Device is enabled. | **Untested on Hardware** (Google Play Services dependent; requires physical Google-provisioned device) | [Find My Device Help](https://support.google.com/android/answer/6160491) |
| **Theft Detection Lock** | Android 15+ (API 35+) / Play Services rollout | Uses device sensors & AI to detect snatch-and-run movement and immediately lock screen. Native OS feature; no 3rd-party callback. | **Untested on Hardware** (Requires physical Android 15+ device with GMS rollout) | [Android 15 Theft Protection Features](https://developer.android.com/about/versions/15) |
| **Offline Device Lock** | Android 15+ (API 35+) | Automatically locks screen shortly after device disconnects from all networks. Native OS feature. | **Untested on Hardware** (Requires Android 15 hardware) | [Android 15 Overview](https://developer.android.com/about/versions/15) |
| **Remote Lock (Web)** | Android 10+ (API 29+) via Play Services | Allows owner to lock phone using verified phone number on `android.com/lock`. | **Untested on Hardware** (Play Services dependent) | [Google Remote Lock Support](https://support.google.com/android/answer/6160491) |
| **SIM Lock (PIN)** | Android 1.0+ (All versions) | Protects SIM card from being swapped into another phone to intercept OTPs. Hardware SIM security. | **Untested on Hardware** (Requires physical UICC/eSIM hardware) | [TelephonyManager API](https://developer.android.com/reference/android/telephony/TelephonyManager) |
| **Require Unlock for Power Menu** | OEM-specific (Samsung One UI, Pixel Android 14+, Xiaomi HyperOS) | Prevents turning off or restarting the phone from the lock screen without entering PIN/biometrics. Not standardized in AOSP. | **Untested on Hardware** (Varies across OEM builds; needs Samsung/Pixel/Xiaomi hardware test) | [AOSP Power Management](https://source.android.com/docs/core/power) |

---

### Tier 2: Normal App (Guardian Core)

| Capability | Minimum SDK | Target SDK | Supported | Verification Status & Specific Test Executed | Constraints & Edge Cases | Official Android Documentation Link |
|---|---|---|---|---|---|---|
| **Autonomous GNSS Capture** | API 26 (8.0) | API 34 (14.0) | **Yes** | **Simulated in Emulator**: Injected simulated coordinates via `adb emu geo fix <lng> <lat>`. Real satellite TTFF & indoor attenuation pending hardware. | GNSS chip works without cellular or Wi-Fi data. Needs `ACCESS_FINE_LOCATION`. On API 34+, background tracking requires `foregroundServiceType="location"`. | [Location Strategies Guide](https://developer.android.com/develop/sensors-and-location/location/strategies) |
| **Local SQLCipher Encrypted Room DB** | API 26 (8.0) | API 34 (14.0) | **Yes** | **Simulated in Emulator**: Automated unit tests verifying schema creation, AES-256 page encryption, and Keystore master key wrapping on API 34 AVD. | SQLCipher for Android (`net.zetetic:sqlcipher-android:4.5.5`). Direct Boot DE storage trade-off applies. | [Room Persistence Library](https://developer.android.com/training/data-storage/room) |
| **Durable Sync Protocol** | API 26 (8.0) | API 34 (14.0) | **Yes** | **Simulated in Emulator**: Tested via `adb shell cmd jobscheduler run` and toggling `adb shell svc data disable`. | Idempotent event UUIDs, monotonic sequence numbers, server ACK verification, separate client capture timestamp vs server receipt timestamp. | [WorkManager Persistent Work](https://developer.android.com/topic/libraries/architecture/workmanager) |
| **SMS Location Fallback** | API 26 (8.0) | API 34 (14.0) | **Yes (Direct APK)** | **Simulated in Emulator**: Inbound & outbound SMS tested via `adb emu sms send <phone> <msg>`. Real carrier SMS routing & filtering pending hardware. | Requires `SEND_SMS`. Sideload required. Single-part 160-char PDU (~58 chars), 48-bit truncated HMAC, token-bucket rate-limited. | [SmsManager API Reference](https://developer.android.com/reference/android/telephony/SmsManager) |
| **SMS Inbound Command Parsing** | API 26 (8.0) | API 34 (14.0) | **Yes (Direct APK)** | **Simulated in Emulator**: Sent simulated SMS with 48-bit HMAC via `adb emu sms send`. Verified HMAC check & counter rejection. Real carrier pending. | Requires `RECEIVE_SMS`. Incoming SMS cannot be aborted on Android 4.4+ unless default SMS app. Appears in native SMS inbox. | [SMS_RECEIVED_ACTION Reference](https://developer.android.com/reference/android/provider/Telephony.Sms.Intents#SMS_RECEIVED_ACTION) |
| **Immediate Low-Battery Beacon** | API 26 (8.0) | API 34 (14.0) | **Yes** | **Simulated in Emulator**: Simulated low battery via `adb shell dumpsys battery unplug && adb shell dumpsys battery set level 3`. Real sudden voltage collapse pending hardware. | Dispatches last-known location immediately, then attempts fresh fix within `goAsync()`. `ACTION_BATTERY_LOW` is manifest-exempt. | [Broadcast Exceptions](https://developer.android.com/guide/components/broadcast-exceptions) & [Battery Monitoring](https://developer.android.com/training/monitoring-device-state/battery-monitoring) |
| **SIM Change Detection** | API 26 (8.0) | API 34 (14.0) | **Yes** | **Simulated in Emulator**: Broadcast simulated via `adb shell am broadcast -a android.intent.action.SIM_STATE_CHANGED`. Physical SIM swap pending hardware. | Listens for `TelephonyManager.ACTION_SIM_CARD_STATE_CHANGED` / `SubscriptionManager`. Triggers emergency beacon to trusted number. | [SubscriptionManager Reference](https://developer.android.com/reference/android/telephony/SubscriptionManager) |
| **Boot Resume (Direct Boot)** | API 26 (8.0) | API 34 (14.0) | **Yes** | **Simulated in Emulator**: Tested via `adb shell am broadcast -a android.intent.action.LOCKED_BOOT_COMPLETED` on Direct Boot locked AVD. | `android:directBootAware="true"`. Runs before First Unlock. Uses `DeviceProtectedStorageContext` for keys and sync queue. | [Direct Boot Developer Guide](https://developer.android.com/training/articles/direct-boot) |
| **Intruder Photo on Failed Unlock** | API 29 (10.0) | API 34 (14.0) | **Experimental** | **Untested on Hardware (Known Failure on standard API 34)** | `DeviceAdminReceiver.onPasswordFailed()` triggers. Background camera blocked on Android 10+ without visible window. | [Camera Background Restrictions](https://developer.android.com/about/versions/10/privacy/changes#camera-mic-background) |
| **Block Power-Off / Airplane Mode** | — | — | **No** | **Architectural Constraint** | Normal applications cannot intercept or block system shutdown, restart, or airplane mode. | [Android Security Architecture](https://source.android.com/docs/security) |
| **Lock Screen Custom Message** | — | — | **No** | **Architectural Constraint** | Normal apps cannot set the lock screen owner message; only Device Owner or system settings can. | [DevicePolicyManager.setDeviceOwnerLockScreenInfo](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setDeviceOwnerLockScreenInfo(android.content.ComponentName,%20java.lang.CharSequence)) |

---

### Tier 3: Device Owner (Guardian DO Module)

| Capability | Policy / API Method | Supported | Verification Status & Specific Test Executed | Real-World Hardware Behavior & Limitations | Official Android Documentation Link |
|---|---|---|---|---|---|
| **Disallow Factory Reset** | `UserManager.DISALLOW_FACTORY_RESET` | **Yes** | **Simulated in Emulator**: Provisioned DO via `adb shell dpm set-device-owner ...` on account-free AVD; verified reset button disabled in Settings. | Disables "Factory data reset" in Settings. Requires zero-account unprovisioned phone (factory reset required on personal phone). | [UserManager.DISALLOW_FACTORY_RESET](https://developer.android.com/reference/android/os/UserManager#DISALLOW_FACTORY_RESET) |
| **Disallow Safe Boot** | `UserManager.DISALLOW_SAFE_BOOT` | **Yes** | **Simulated in Emulator**: Verified policy application via `adb shell dumpsys device_policy`. Physical hardware reboot combo pending hardware. | Prevents booting into Safe Mode where third-party apps and background services are disabled. | [UserManager.DISALLOW_SAFE_BOOT](https://developer.android.com/reference/android/os/UserManager#DISALLOW_SAFE_BOOT) |
| **Disallow Airplane Mode** | `UserManager.DISALLOW_AIRPLANE_MODE` | **Yes** | **Simulated in Emulator**: Verified restriction active in AVD Settings. OEM Quick Settings variations pending hardware. | Disables airplane mode toggle in Quick Settings and Settings. | [UserManager.DISALLOW_AIRPLANE_MODE](https://developer.android.com/reference/android/os/UserManager#DISALLOW_AIRPLANE_MODE) |
| **Prevent App Uninstall** | `DevicePolicyManager.setUninstallBlocked` | **Yes** | **Simulated in Emulator**: Executed `adb shell pm uninstall com.antitheft.guardian`, verified `DELETE_FAILED_OWNER_BLOCKED`. | Grays out and prevents uninstallation of Guardian packages. | [setUninstallBlocked Reference](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setUninstallBlocked(android.content.ComponentName,%20java.lang.CharSequence)) |
| **Lock-Task (Kiosk) Recovery Mode** | `DevicePolicyManager.setLockTaskPackages` + `startLockTask()` | **Yes** | **Simulated in Emulator**: Invoked `startLockTask()`, verified home/recents disabled in AVD. Hardware navigation gesture bypass pending hardware. | Pins recovery screen, hides navigation buttons, hides status bar and power dialog options. | [Lock Task Mode Guide](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode) |
| **Set Lock Screen Recovery Info** | `DevicePolicyManager.setDeviceOwnerLockScreenInfo` | **Yes** | **Simulated in Emulator**: Verified text applied on AVD lock screen. OEM custom lockscreens pending hardware. | Displays owner contact phone and return reward message directly on native lock screen. | [setDeviceOwnerLockScreenInfo Reference](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setDeviceOwnerLockScreenInfo(android.content.ComponentName,%20java.lang.CharSequence)) |
| **Prevent Hardware Force-Poweroff** | — | **Impossible (Hardware)** | **Hardware Constraint (All Devices)** | **Hardware ground truth**: Holding Power + Volume Down triggers the PMIC at the silicon level. The SoC cuts power unconditionally. Cannot be stopped by software or Device Owner. | [AOSP Hardware Power Management](https://source.android.com/docs/core/power) |

---

## 3. Cryptographic & Security Mechanisms

| Component | Standard / Algorithm | Storage & Security Provider | Verification Status & Specific Test Executed | Official Android Documentation Link |
|---|---|---|---|---|
| **Pairing & Mutual Auth** | ECDSA (P-256 / secp256r1) + QR Exchange | `AndroidKeyStore` (Hardware TEE / StrongBox backed) | **Simulated in Emulator**: Verified keypair generation and signature verification on API 34 AVD Keystore. | [Android Keystore System](https://developer.android.com/training/articles/keystore) |
| **SMS Message Integrity** | HMAC-SHA256 (truncated to 48 bits / 12 hex chars) | Shared symmetric secret in Keystore / Device Protected Storage | **Simulated in Emulator**: Automated unit tests for 12 hex chars generation and authentication against mutated payloads. | [KeyGenParameterSpec Reference](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec) |
| **Replay Protection** | 64-bit strictly monotonic sequence counters + timestamp check | Persisted in SQLCipher Room DB / Keystore | **Simulated in Emulator**: Automated unit tests confirming duplicate and lower counter rejections. | [Secure Data Storage Guide](https://developer.android.com/topic/security/data) |
| **Recovery Vault Encryption** | AES-256-GCM + PBKDF2 with biometric-bound auth key | Client-side only. Zero-knowledge backend. BiometricPrompt required on Admin. | **Untested on Hardware**: `BiometricPrompt` simulated via `adb emu finger touch 1`. Real physical capacitive/optical biometric sensor pending hardware. | [BiometricPrompt Guide](https://developer.android.com/training/sign-in/biometric-auth) |
