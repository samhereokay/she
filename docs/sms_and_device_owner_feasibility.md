# SMS and Device-Owner Feasibility Analysis

This report provides the technical feasibility assessment for SMS fallback communication, Tier 3 Device Owner provisioning, broadcast delivery on Android 14/15, and lists the unresolved empirical questions.

---

## 1. SMS Communication Feasibility

### 1.1 Google Play Policy vs. Direct APK Distribution

Google Play strictly restricts access to `android.permission.SEND_SMS` and `android.permission.RECEIVE_SMS` under the **SMS and Call Log Permissions Policy**:
- **Core Functionality Constraint**: Google Play only grants SMS permissions to applications whose primary purpose is serving as the Default SMS Handler.
- **Anti-theft / Personal Safety Non-Exemption**: Anti-theft, device recovery, or remote administration apps are explicitly **not** granted an exception.
- **Distribution Mandate**: The app must be distributed as a **Direct APK (sideload)** for personal and trusted use. A separate Play Store build flavor will exclude SMS features entirely.

### 1.2 Protocol Design: Compact 7-Bit ASCII PDU & 48-Bit Truncated HMAC

- **Encoding Budget**: The GSM 7-bit default alphabet allows **160 characters** in a single unfragmented SMS PDU. Using any Unicode character drops the capacity to 70 characters (UCS-2), causing multi-part fragmentation and delivery failures.
- **Payload Format**:
  ```text
  ST:<DEVICE_ALIAS>|<LAT>,<LNG>|<ACCURACY_METERS>|<UTC_EPOCH_SEC>|<SEQ_COUNTER>|<HMAC_HEX>
  ```
  *Example*:
  ```text
  ST:G1|37.77492,-122.41941|14|1729036800|104|D9A2F80B31C5
  ```
- **HMAC Specification (48-bit / 12 Hex Characters)**:
  - Length: Exactly **48 bits** (6 bytes, encoded as **12 hexadecimal characters**).
  - Practical Security: In an SMS channel, every character consumed directly impacts the 160-character budget. A 48-bit truncated HMAC-SHA256 provides a search space of $2^{48} \approx 2.81 \times 10^{14}$ possibilities.
  - Brute-force Resistance: An attacker gets zero oracle feedback and only one blind guess per SMS sent. Because carrier delivery latency is ~1-2 seconds per message and Guardian's client token-bucket rate limiter throttles incoming requests, guessing the 48-bit truncated HMAC is **impractical given rate limits and carrier latency**, while saving 4 valuable characters compared to 64-bit hex.

### 1.3 Inbound SMS Command Processing & Android Broadcast Limits

- **Format**:
  ```text
  CMD:<CMD_TYPE>|<COUNTER>|<EXPIRY_EPOCH_SEC>|<HMAC_HEX>
  ```
  *Example*: `CMD:LOCATE|105|1729037100|84E29A10F0B1`
- **Replay Protection**:
  1. Monotonic sequence counter: $\text{COUNTER} > \text{COUNTER}_{\text{last}}$.
  2. Timestamp window: $\text{UTC\_NOW} \le \text{EXPIRY\_EPOCH\_SEC}$.
  3. Valid 48-bit HMAC: Computed using the symmetric secret $K_{\text{sms}}$ established during QR pairing.
- **Inbox Visibility & Exposure Risk**:
  - Since Android 4.4 (KitKat), only the system Default SMS App can call `abortBroadcast()`.
  - Non-default apps receiving `Telephony.Sms.Intents.SMS_RECEIVED` cannot suppress the incoming SMS. It **will appear in the device's native SMS messaging inbox**.
  - *Risk*: A thief can see that remote tracking commands are occurring and can identify the trusted contact's phone number.
  - *Mitigations*: PDU uses cryptic telemetry syntax (`CMD:LOCATE|...`) rather than obvious messages; Tier 1 checklist instructs enabling lock-screen notification privacy ("Hide sensitive notification content").

### 1.4 Battery-Low Broadcast (`ACTION_BATTERY_LOW`) Delivery on Modern Android

#### Official Android Documentation Quotation
From the official Android Developers Guide on [Implicit Broadcast Exceptions](https://developer.android.com/guide/components/broadcast-exceptions):
> **`ACTION_BATTERY_LOW`**  
> *"Sent when the device's battery may have dropped to a critical level."*  
> *Constant Value: `android.intent.action.BATTERY_LOW`*  
> *Declared in: `AndroidManifest.xml`*

This confirms that `Intent.ACTION_BATTERY_LOW` is officially whitelisted by Google as an exception to Android 8.0+ implicit broadcast restrictions and can be declared in `AndroidManifest.xml`. However, real hardware OEM battery managers (e.g. Huawei, Xiaomi) frequently alter battery broadcast thresholds, making **physical hardware verification mandatory**.

#### Two-Stage Low-Battery Beacon Design (No Waiting for Fresh Fix)
Because acquiring a fresh GNSS fix cold can take 30 to 60+ seconds, and a low-battery event signals imminent hardware voltage cut-off:
1. **Stage 1 (Immediate Dispatch - Zero Latency)**:
   - Inside `BroadcastReceiver.onReceive()`, immediately invoke `fusedLocationClient.lastLocation` (returns instantly from memory cache).
   - Format and dispatch the SMS beacon immediately, containing the coordinates and their historical capture timestamp (explicit age).
2. **Stage 2 (Opportunistic Fresh Fix via `goAsync()`)**:
   - Call `val pendingResult = goAsync()`.
   - Start `getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationToken)` with a strict **5-second timeout**.
   - If a fresh satellite fix arrives within 5 seconds, dispatch a second, updated SMS beacon.
   - Enqueue an expedited `WorkManager` sync request for server delivery.
   - Call `pendingResult.finish()`.

---

## 2. Device Owner (Tier 3) Feasibility & Reality Check

### 2.1 The Zero-Accounts Requirement on Real Phones

> [!WARNING]
> **Adoption Barrier for Existing Phones**:
> Android Enterprise architecture strictly dictates that Device Owner (`DevicePolicyManager`) can **only be set on an unmanaged device with ZERO accounts** (no Google accounts, no email accounts, no messaging accounts).
> On a friend's or owner's existing, actively-used personal phone, provisioning Device Owner mode **mandates a complete Factory Reset**.

- **Practical Impact**:
  - **Tier 3 (Device Owner)** is feasible for dedicated, wiped test devices or enterprise-managed deployments.
  - **Tier 2 (Normal App)** is the mandatory operational path for existing personal phones that cannot undergo a wipe. Tier 2 delivers the full location capture, encrypted queue, and SMS fallback without requiring Device Owner status.

### 2.2 Provisioning Methods
1. **ADB Command (Wiped Device / Emulator)**:
   ```bash
   adb shell dpm set-device-owner com.antitheft.guardian/.receiver.GuardianDeviceAdminReceiver
   ```
2. **QR Code Provisioning (Out of Box Setup Wizard)**:
   - 6 taps on initial "Welcome" screen, scan provisioning QR code.

### 2.3 Hardware Force-Poweroff Reality
- Holding `Power + Vol Down` initiates hardware PMIC cutoff at the silicon level.
- No app, root script, or Device Owner policy can intercept or prevent this physical action.

---

## 3. Open Unresolved Questions (Pending Real Hardware)

The following empirical questions remain open and can only be definitively validated on physical Android hardware across different OEM vendors:

1. **Pre-Mortem Battery-Low Delivery on Degraded Physical Batteries**:
   - *Question*: On aging lithium-ion batteries with high internal resistance, does the OS issue `ACTION_BATTERY_LOW` with sufficient voltage headroom before the PMIC cuts power abruptly during high-power GNSS and cellular modem transmit spikes?
   - *Resolution Path*: Empirical battery drain tests on an older physical test device.

2. **OEM-Specific Power Menu Lock Variations**:
   - *Question*: AOSP does not provide an API to require biometric/PIN unlock before opening the power menu. Samsung One UI provides "Lock network and security", Pixel has "Require unlock for power menu", while Xiaomi/HyperOS and Transsion (Infinix/Tecno) handle this differently or omit it. Which specific OEM models reliably support power-menu lock screen suppression?
   - *Resolution Path*: Hardware validation checklist across top OEM builds.

3. **Carrier SMS Routing & Shortcode/Spam Filtering**:
   - *Question*: Do aggressive carrier spam filters or MVNO SMS gateways throttle, delay, or flag high-frequency piped ASCII payloads (`ST:...`) between physical SIMs?
   - *Resolution Path*: Live SIM-to-SIM transmission tests across major mobile carriers (e.g., T-Mobile, Airtel, Vodafone, Jio).

4. **Aggressive OEM Background Process Termination**:
   - *Question*: Third-party Android OEM vendors (Xiaomi, Huawei, Oppo, Vivo) employ proprietary battery managers that kill background services and ignore `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. How do their proprietary task killers impact WorkManager and Direct Boot receivers?
   - *Resolution Path*: Hardware testing using OEM-specific autostart permission prompts and manufacturer settings guidance.

5. **Direct Boot Storage Exposure vs. Theft Threat Model**:
   - *Question*: Data in Device Protected (DE) storage is decrypted by the kernel at boot before first user unlock. If a sophisticated thief gains hardware JTAG or exploit access to a booted phone before PIN entry, what is the maximum acceptable metadata footprint in DE storage?
   - *Resolution Path*: Strict policy to store only volatile coordinates, monotonic sequence counter, and SMS HMAC key in DE. All recovery vault data and personal identification must remain strictly in Credential Protected (CE) storage.
