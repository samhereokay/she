# Direct Boot, Device-Protected Storage, and SMS Emergency Pipeline

## Architectural Overview

When an Android phone boots or is restarted by an attacker, it sits in **Direct Boot** mode before the owner enters their lock-screen PIN or password. Under modern Android encryption (File-Based Encryption, FBE):
1. **Credential Encrypted (CE) Storage:** Unlocked **only after** the user enters their lock-screen credentials for the first time after boot ("First Unlock").
2. **Device Protected (DE) Storage:** Available **immediately on power-on** (`ACTION_LOCKED_BOOT_COMPLETED`), before the user enters their PIN.

---

## Why Anti-Theft Keys are Stored in Device Protected Storage

In an authentic anti-theft scenario, a stolen device is frequently rebooted or power-cycled by a thief attempting to escape cellular tracking or network connectivity:
- If anti-theft keys and SMS receivers were stored exclusively in Credential Encrypted (CE) storage, the application would remain completely inactive until the thief enters the victim's PIN.
- The phone would be deaf to incoming emergency recovery commands (`LOCATE`, `RING`, `STOLEN_MODE`) and unable to report coordinates.

To solve this, **SHE Guardian Lite** utilizes Android's native Direct Boot architecture:
- `BootReceiver` and `SmsCommandReceiver` declare `android:directBootAware="true"`.
- `DirectBootStorageHelper` accesses `context.createDeviceProtectedStorageContext()`.
- The SQLCipher database and shared HMAC secret (`pairing_secret`) are maintained in DE storage, allowing offline SMS commands to execute immediately at the lock screen following a reboot.

---

## Threat Model and Security Trade-Offs

Storing cryptographic material in DE storage introduces a documented trade-off:

| Threat Vector | Mitigation in SHE | Remaining Limit |
|---|---|---|
| **Replay Attacks** | Inbound SMS commands enforce strict monotonic sequence numbers and 5-minute expiry windows. Duplicate or stale sequence numbers are silently dropped. | Clock tampering on device (detected by backend clock skew check). |
| **Forged Commands** | Inbound commands require a 48-bit HMAC-SHA256 calculated using the 256-bit shared pairing secret. Forged SMS messages fail HMAC verification and are dropped without acknowledgment. | Brute force prevented by HMAC entropy (2^48 search space per message, with 30s rate limiting). |
| **Physical Chip-Off / JTAG** | Hardware Keystore keys are backed by StrongBox / TEE. | On devices with unlocked bootloaders or hardware debug ports, physical memory extraction of DE storage is theoretically possible in advanced forensic labs. |
| **Non-SHE SMS Sniffing** | Zero-retention privacy filter: messages not starting with `AT1:CMD:` are discarded instantly; personal and banking texts are never stored or inspected. | Standard Android carrier-level SMS inspection remains possible by telecom operators. |

---

## Acoustic Siren & Silent/DND Limitations

The acoustic siren (`AlarmSoundManager`) routes audio to `AudioManager.STREAM_ALARM` with `AudioAttributes.USAGE_ALARM` and forces stream volume to maximum:
- **Standard Silent / Vibrate Mode:** Bypassed successfully on standard Android.
- **Do Not Disturb (DND):** Standard DND rules permit alarm streams by default.
- **Hardware Sliders:** Physical alert sliders (e.g. on OnePlus devices) or custom OEM "Total Silence" DND modes on heavily skinned ROMs (HyperOS, ColorOS) can silence hardware speakers at the audio server level. Owners are advised to test alarm behavior on their specific handset model.
