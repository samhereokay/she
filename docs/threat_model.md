# Threat Model: Android Anti-Theft & Recovery System

This document establishes the formal threat model for the anti-theft and recovery system, identifying protected assets, adversary capabilities, attack vectors, cryptographic mitigations, physical non-goals, the Direct Boot storage exposure trade-off, and the inbound-SMS visibility exposure.

---

## 1. Protected Assets & Storage Tiers

| Asset ID | Asset Name | Sensitivity Level | Description | Storage Tier & Encryption |
|---|---|---|---|---|
| **A-1** | **Ephemeral Location Points** | Critical (Privacy / Safety) | GNSS coordinates, timestamp, accuracy, altitude, velocity. | **Device Protected (DE) Storage**; SQLCipher Room DB (AES-256). Pruned upon sync. |
| **A-2** | **SMS Pairing Secret & Counter** | Critical (Integrity / Control) | Shared 256-bit symmetric key $K_{\text{sms}}$ and monotonic sequence counter. | **Device Protected (DE) Storage**; Keystore-backed / SQLCipher Room DB. |
| **A-3** | **Device Identity Keys** | Critical (Authenticity) | Device ECDSA (P-256 / secp256r1) private key. | `AndroidKeyStore` backed by hardware TEE / StrongBox. Never leaves device. |
| **A-4** | **Recovery Vault Data** | High (Confidentiality) | Device serials, IMEI, account recovery codes, insurance policy PDFs, receipts. | **Credential Protected (CE) Storage only**. Client-side AES-256-GCM. BiometricPrompt required. |
| **A-5** | **System Audit Logs & Events** | Moderate (Integrity) | Device state changes, pairing events, command receipts, SIM change alerts. | SQLCipher Room DB, tamper-evident hash chained. |

---

## 2. Adversary Profiles

### Adversary 1: Opportunistic Street Snatcher
- **Profile**: Steals phone in public. Tries quick resale or pawning.
- **Capabilities**: Enables Airplane mode, removes SIM, attempts common PINs (1234, 0000), holds power button to turn off device, tries factory reset via Settings.

### Adversary 2: Device Fence / Forensic Hardware Technician
- **Profile**: Experienced at stripping phones for parts, bypassing software locks, or flashing firmware.
- **Capabilities**: Uses RF-shielded Faraday bags, attempts recovery mode / fastboot flashing, boots into safe mode, attempts ADB shell inspection, chip-off memory dumps, or JTAG bus probing.

### Adversary 3: Network Attacker / Eavesdropper
- **Profile**: Malicious actor on public Wi-Fi or rogue cell tower (IMSI catcher).
- **Capabilities**: Packet sniffing, traffic replay, TLS interception attempts.

### Adversary 4: SMS / Telephony Attacker
- **Profile**: Remote actor knowing the victim's phone number or trusted contact number.
- **Capabilities**: Online SMS spoofing gateways, replaying previously intercepted SMS payloads.

### Adversary 5: Compromised Backend Server / Rogue Database Admin
- **Profile**: Cloud server compromised or database dump leaked.
- **Capabilities**: Read access to all PostgreSQL tables, ability to send forged FCM messages.

### Adversary 6: Compromised Admin Phone
- **Profile**: Owner's admin phone is lost or accessed while unlocked by a third party.
- **Capabilities**: Attempts to view recovery vault or issue destructive commands.

---

## 3. Storage Exposure & Forensic Trade-offs

### 3.1 Room DB Encryption Engine: SQLCipher
Standard Android Room DB stores SQLite databases in plaintext on the filesystem. To enforce at-rest security:
- **Engine**: SQLCipher for Android (`net.zetetic:sqlcipher-android:4.5.5`).
- **Algorithm**: AES-256 in CBC mode with HMAC-SHA512 per-page integrity verification.
- **Key Generation & Storage**: A random 256-bit passphrase is generated via `SecureRandom` on first launch and protected by `AndroidKeyStore` using AES-256-GCM.
- **Dependency Pinning**: Pinned to `4.5.5` to guarantee ABI compatibility, deterministic native OpenSSL compilation, and avoidance of breaking SQLite schema shifts.

### 3.2 The Direct Boot Data-Exposure Trade-off

Modern Android uses File-Based Encryption (FBE) with two distinct storage contexts:
1. **Credential Protected (CE) Storage**: Keys are derived from the user's lock-screen PIN/password. CE is completely encrypted and unreadable until the user unlocks the phone after a reboot ("First Unlock").
2. **Device Protected (DE) Storage**: Keys are tied to the device hardware SoC. DE is automatically decrypted by the kernel as soon as the OS boots up, *before* any PIN is entered.

> [!CAUTION]
> **Direct Boot Forensic Vulnerability**:
> Because Device Protected (DE) storage is decrypted by the kernel at boot before first user unlock, a forensic attacker with physical chip-off, JTAG bus probing, or an active kernel memory exploit on a booted phone before PIN entry could read the SMS HMAC key and recent coordinates from Device Protected Storage.
> **Mitigation Policy**:
> - **Permitted in DE Storage**: Only operational recovery assets (ephemeral coordinates, pending SMS queue, monotonic replay counter, 48-bit SMS HMAC secret $K_{\text{sms}}$, and device public key).
> - **Strictly Prohibited in DE Storage**: Recovery Vault data (IMEI, recovery codes, receipts, insurance documents), user contacts, personal identity, and user credentials. All vault material MUST reside exclusively in CE storage and require additional client-side AES-256-GCM decryption with biometric authorization.

### 3.3 Inbound SMS Command Visibility Exposure

On Android 4.4+ (KitKat), only the system-designated Default SMS App can abort broadcasts (`abortBroadcast()`). For non-default apps:
- **Exposure**: An incoming command SMS sent from the owner admin to the Guardian phone cannot be hidden from the Android SMS provider. It will appear in the thief's default SMS inbox.
- **Information Leaked**:
  - The thief can see that remote recovery commands are being sent.
  - The thief can see the trusted phone number that dispatched the command.
  - *Note*: The thief cannot extract the shared secret from the message (it contains only a one-way 48-bit HMAC), but they learn that recovery is underway.
- **Mitigations**:
  1. *Cryptic Syntax*: Payloads use cryptic, non-obvious prefixes (`ST:...`, `CMD:...`) resembling carrier provisioning strings rather than plain English ("STOP THIEF").
  2. *Lock Screen Privacy Guidance (Tier 1)*: Guidance instructs owner to set "Don't show notifications at all" or "Hide sensitive notification content" on the lock screen, preventing the incoming SMS popup from being seen while locked.
  3. *Kiosk Lock-Task Mode (Tier 3)*: If provisioned as Device Owner, entering stolen state locks the device into a kiosk recovery screen and hides the notification shade entirely.

---

## 4. Attack Vectors & Mitigations Matrix

| Attack Vector | Threat Scenario | Mitigation Strategy | Verification Status |
|---|---|---|---|
| **AV-01: Network Cut-off** | Thief turns off Wi-Fi & Mobile Data. | Autonomous GNSS fix + 48-bit HMAC SMS fallback + Room sync queue. | **Simulated in Emulator (API 34)** |
| **AV-02: SIM Card Ejection** | Thief removes SIM or inserts new SIM. | `ACTION_SIM_CARD_STATE_CHANGED` triggers immediate beacon to trusted number. | **Simulated in Emulator (Simulated SIM)** |
| **AV-03: Low Battery Cut-off** | Phone dies before owner can locate. | `ACTION_BATTERY_LOW` triggers immediate transmission of last-known fix, then attempts fresh fix within `goAsync()`. | **Simulated in Emulator (API 34)** |
| **AV-04: SMS Replay Attack** | Attacker replays intercepted locate SMS. | Monotonic sequence counters ($C_{k+1} > C_k$) + 5-minute timestamp TTL window. | **Simulated in Emulator (API 34)** |
| **AV-05: SMS Command Spoofing** | Attacker uses SMS web gateway to send fake commands. | 48-bit truncated HMAC-SHA256 verified against shared pairing secret $K_{\text{sms}}$. Forgery is impractical given carrier latency and token-bucket rate limits. Invalid SMS dropped silently. | **Simulated in Emulator (API 34)** |
| **AV-06: Network MITM** | Attacker sniffs traffic on rogue Wi-Fi. | TLS 1.3 with Certificate Pinning on mobile client. | **Simulated in Emulator (API 34)** |
| **AV-07: Compromised Backend Server** | Server database leaked or admin goes rogue. | Zero-knowledge vault (client-side AES-256-GCM). Location events authenticated by ECDSA signature. | **Simulated in Emulator (API 34)** |
| **AV-08: Admin Phone Compromised** | Unauthorized party unlocks owner's admin phone. | `BiometricPrompt` (Fingerprint/PIN) mandatory before opening vault. Phase 3 mandates passkey/TOTP + trusted device approval. | **Untested on Hardware** (Biometric sensor) |
| **AV-09: Safe Mode / Settings Wipe** | Thief boots to Safe Mode or resets via Settings. | Device Owner policy (`DISALLOW_SAFE_BOOT`, `DISALLOW_FACTORY_RESET`). Requires no-accounts wiped phone. | **Simulated in Emulator (API 34)** |
| **AV-10: Cold Boot (Before First Unlock)** | Phone rebooted; credential storage locked. | Direct Boot awareness (`directBootAware="true"`) using `DeviceProtectedStorageContext`. | **Simulated in Emulator (API 34)** |

---

## 5. Explicit Non-Goals & Physical Boundaries

1. **Hardware PMIC Force-Poweroff**:
   - Holding `Power + Vol Down` triggers silicon-level PMIC cutoff. Software cannot intercept or block this. Addressed via early beacons and Direct Boot auto-resume.
2. **Faraday Cage Shielding**:
   - RF-attenuated bags block all radio signals while phone is inside. Guardian queues events in SQLCipher Room DB and empties queue upon signal restoration.
3. **Unlocked Bootloader Custom Flashing**:
   - If bootloader is unlocked (`fastboot oem unlock`), physical flashing cannot be prevented. Tier 1 instructs keeping OEM bootloader locked.
4. **Google Find My Device Network Third-Party Access**:
   - Google does not offer 3rd-party APIs to query the Bluetooth mesh. Tier 1 guides owner to activate native Google Find Hub.
