# What SHE Does and Does Not Do (Honest Capability & Threat Model)

This document provides a realistic, unvarnished disclosure of the capabilities, operational limits, and threat model of the **SHE (Sentinel for Handheld Equipment)** anti-theft system for Android.

SHE adopts a **defense-in-depth recovery** philosophy: it significantly raises the cost and complexity for a thief or unauthorized possessor to retain, misuse, or reset a stolen phone. It does **not** claim to make an Android handset invincible or 100% theft-proof.

---

## 1. What SHE Does (Capabilities)

### A. Mutual Cryptographic Pairing & Authentication
* **Hardware-Backed Key Pairs**: Generates asymmetric ECDSA key pairs inside Android Keystore on both Guardian (protected handset) and Admin (controller handset).
* **Zero-Shared-Secrets Server Architecture**: The backend acts as an authenticated zero-knowledge relay and audit pipeline. Private keys never leave the respective physical handsets.
* **Per-Action TOTP & Admin Signatures**: High-impact commands (`LOCATE`, `RING`, `SIREN`, `UNPAIR`, `REVOKE`) require dynamic TOTP authorization and ECDSA signatures.

### B. Lite Handset Behavior (Non-Device Owner / Sideload Baseline)
* **Foreground Persistence**: Persistent foreground service with a visible ongoing system notification (`FOREGROUND_SERVICE_LOCATION` + `WAKE_LOCK`), complying with modern Android background execution constraints.
* **Dual-Channel Beaconing**:
  * **Online (HTTPS/WSS)**: Real-time telemetry, location GPS reporting, battery percentage, charging state, and network connectivity over pinned TLS (`wss://` and `https://`).
  * **Offline (SMS Fallback)**: Automatically activates if mobile data or Wi-Fi is severed. Guardian formats encrypted/signed compact SMS beacons strictly routed to the single registered Admin companion phone number.
* **Direct Boot Resilience**: Retains critical offline identity, public keys, and emergency pairing state in Android Direct Boot device-protected storage (`createDeviceProtectedStorageContext()`), allowing recovery operations prior to the first user unlock after a reboot.
* **Low-Battery Alert**: When battery drops below 10%, Guardian dispatches an immediate high-priority beacon before the battery is fully depleted.
* **Clock Skew Auditing**: Backend rejects or flags heartbeats with clock skews exceeding tolerance windows (60 seconds online / 300 seconds offline SMS) to prevent replay of captured telemetry.

---

## 2. What SHE Does NOT Do (Explicit Limitations)

1. **Does NOT "Prevent Theft"**:
   No software application can stop an opportunist or mugger from physically taking a handset from your hands, bag, or vehicle. SHE is an **anti-theft response and recovery system**, designed to minimize data loss and assist in recovery after theft occurs.

2. **Does NOT Guarantee 100% Protection or Recovery**:
   Physical custody provides an attacker with avenues that software cannot completely block:
   * **Faraday Bags / RF Shielding**: Placing the stolen handset inside an RF-shielded bag, metal container, or microwave completely attenuates all cellular, GPS, and Wi-Fi signals. No telemetry or SMS can transmit until removed.
   * **Hardware Disassembly / Battery Pull / Powered Off**: If the device is kept powered off or the battery is removed/discharged, tracking halts until the phone is powered on.
   * **Bootloader Fastboot / EDL Flashing**: A skilled attacker with physical USB access and OEM unlocking enabled can wipe partitions via Fastboot or Emergency Download (EDL) mode.
   * **Physical SIM Ejection without eSIM**: If the user uses a physical SIM card and the thief ejects it without connecting to Wi-Fi, SMS fallback cannot transmit unless an active SIM is inserted. (We recommend pairing SHE with an eSIM and PIN-locked SIM slot).

3. **Does NOT Operate as a Covert Stalkerware**:
   SHE is not designed for covert surveillance or secret tracking of individuals:
   * Guardian displays a permanent, visible Android notification indicating that active protection and location tracking are running.
   * SHE will never hide its icon or suppress system notifications.

4. **Lite Mode Limitations (Without Device Owner / Knox)**:
   In standard Lite mode (Part 1):
   * **Does NOT block factory reset**: Lite mode has no Device Administrator or Device Owner privileges. An attacker or possessor can factory reset the phone from Android Settings (if unlocked) or from the hardware Recovery / Bootloader menu.
   * **Does NOT prevent uninstallation**: Any user who unlocks the screen can uninstall the Guardian application via standard Android Settings or launcher.
   * Android OS-level battery saver restrictions (if not manually whitelisted by the user during onboarding) may delay background jobs when stationary.

---

## 3. Best Practices to Maximize Recovery Likelihood

To maximize SHE's effectiveness:
1. **Enable eSIM**: Prevent thieves from disabling SMS fallback by simply ejecting a plastic SIM card.
2. **Lock Network & Security from Lock Screen**: Ensure your phone requires biometric/PIN authentication to pull down Quick Settings or disable Mobile Data / Wi-Fi.
3. **Set a Strong Lock Screen PIN/Password**: Never use trivial PINs (e.g. 1234, 0000).
4. **Complete Onboarding Whitelisting**: Ensure battery optimization is disabled for Guardian, Autostart is allowed (on Xiaomi, OnePlus, Samsung), and location permissions are granted as "Allow all the time".
