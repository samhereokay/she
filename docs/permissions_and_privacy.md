# Permissions, Consent & Privacy Architecture

This document details the permissions model, owner consent framework, and privacy guarantees for the Android Anti-Theft & Recovery System.

---

## 1. Principles of Consent & Visibility

The system is strictly designed around **owner-authorized recovery and asset protection**:
1. **No Covert Surveillance**: The app is never hidden from the launcher, application list, or system notifications. It does not masquerade as a system process or disguise its package name.
2. **Explicit Owner Pairing**: The Guardian phone pairs with the Owner Admin phone via an interactive QR code exchange involving mutual cryptographic key registration.
3. **Revocability**: The owner or authorized device holder can revoke pairing, export stored data, or clear all application state directly through a dedicated in-app Privacy & Security screen.
4. **Zero Third-Party Tracking**: No analytics SDKs, ad networks, cloud telemetry, or third-party trackers are integrated into the codebase.

---

## 2. Guardian Permissions Specification

| Permission | Android API Level | Protection Level | Purpose & Justification | Visibility to User |
|---|---|---|---|---|
| `ACCESS_FINE_LOCATION` | API 1+ | Dangerous (Runtime) | Acquire high-accuracy GNSS coordinates during tracking and recovery states. | Prompted during onboarding with detailed rationale. |
| `ACCESS_COARSE_LOCATION` | API 1+ | Dangerous (Runtime) | Approximate location fallback when GNSS satellite reception is obstructed (e.g. indoors). | Bundled with location permission grant. |
| `ACCESS_BACKGROUND_LOCATION` | API 29+ (Android 10) | Dangerous (Runtime) | Enables periodic location updates when phone screen is locked or app is in background. | Separate OS permission screen prompted after foreground location is granted. |
| `FOREGROUND_SERVICE` | API 28+ (Android 9) | Normal | Keeps tracking active during active recovery or stolen mode. | Persistent system notification visible in notification shade. |
| `FOREGROUND_SERVICE_LOCATION` | API 34+ (Android 14) | Normal | Declares location foreground service type mandated by Android 14. | Required manifest declaration. |
| `SEND_SMS` | API 1+ | Dangerous (Runtime) | Sends compact emergency location reports to pre-registered trusted number when mobile data is off. | Prompted during onboarding; direct APK flavor only. |
| `RECEIVE_SMS` | API 1+ | Dangerous (Runtime) | Listens for authenticated, HMAC-signed remote recovery commands from trusted contact. | Prompted during onboarding; direct APK flavor only. |
| `READ_PHONE_STATE` | API 1+ | Dangerous (Runtime) | Detects SIM card ejection and SIM swap events to trigger emergency beacon. | Prompted during onboarding. |
| `RECEIVE_BOOT_COMPLETED` | API 1+ | Normal | Resumes location listener and sync queue immediately upon device reboot. | Auto-granted at install time. |
| `POST_NOTIFICATIONS` | API 33+ (Android 13) | Dangerous (Runtime) | Displays mandatory foreground service alerts and recovery status updates. | Prompted on Android 13+. |
| `BIND_DEVICE_ADMIN` | API 8+ | System (Special Access) | Used by optional Tier 3 Device Owner module to enforce theft countermeasures. | Explicit device admin / provisioning activation. |

---

## 3. Data Retention & Privacy Boundaries

### Local Device Storage (Guardian)
- **Room Database**: Location traces and event logs are stored in an encrypted local database.
- **Auto-Pruning**: Traced location points are automatically pruned from local storage after 14 days or immediately upon confirmed synchronization with the owner backend.
- **Direct Boot Isolation**: Pairing keys and emergency queues in Device Protected (DE) storage contain zero personal contacts, credentials, or photos.

### Backend Data Handling
- **Location Expiration**: Real-time location traces on the backend are configured with an automated TTL (default: 30 days retention limit, customizable by owner).
- **Zero-Knowledge Recovery Vault**: Vault objects (IMEI, recovery passwords, insurance files) are encrypted on the client device using AES-256-GCM. The backend only stores encrypted ciphertext blobs (`vault_objects` table) and has no access to the decryption keys.
- **Account Deletion**: Invoking account deletion issues a cryptographic purge of all associated device rows, location traces, commands, and vault payloads from the database.
