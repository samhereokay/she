# Anti-Theft & Handset Recovery Ecosystem: User Guide

Welcome to the **Anti-Theft & Handset Recovery Ecosystem**. This guide is written for you as the phone owner. It explains how the system works in plain language, how to manage your devices day-to-day, what to do if your phone is ever stolen, and how to recover your vital information.

---

## 1. How the System Works: The Two Apps

The ecosystem consists of two distinct Android applications that communicate securely:

```
┌──────────────────────────────────────┐       Cryptographic Sync       ┌──────────────────────────────────────┐
│             ADMIN APP                │  ◄───────────────────────────► │             GUARDIAN APP             │
│   (Installed on Controller Phone)    │   Signed Commands / Telemetry  │     (Installed on Protected Phone)   │
├──────────────────────────────────────┤                                ├──────────────────────────────────────┤
│ • Interactive Tracking Dashboard     │                                │ • Silent Background Location Tracking│
│ • Secure Command Dispatcher          │                                │ • 100% Volume Theft Siren + Voice    │
│ • Encrypted Recovery Vault           │                                │ • Anti-Theft Kiosk Lock Screen       │
│ • Hardware Keystore Private Keys     │                                │ • Emergency SMS Location Dispatch    │
└──────────────────────────────────────┘                                └──────────────────────────────────────┘
```

1. **Guardian App (`app-guardian`)**: 
   Installed on the phone you want to protect. It runs silently in the background, continuously records location coordinates, listens for signed emergency recovery commands, and defends itself against tampering.
2. **Admin Companion App (`app-admin`)**: 
   Installed on your secondary/controller phone (or family member's phone). It gives you the dashboard to track the Guardian phone, dispatch remote alarms, lock the screen, and store your encrypted recovery documents.

---

## 2. Setting Up Your Handset

### Step 1: Install and Open the Admin App
1. Install the **Admin Companion App** on your controller phone.
2. Sign in with your owner account credentials.
3. If prompted, enroll in Two-Factor Authentication (TOTP) using your preferred authenticator app (Google Authenticator, Aegis, or Bitwarden).
4. Save your **Emergency Recovery Codes** in a safe place off your phone (e.g., written on paper or in a physical home safe).

### Step 2: Install and Open the Guardian App
1. Install the **Guardian App** on the phone you want to protect.

> [!WARNING]
> **Google Play Protect & OEM "High Risk" Warnings**:
> When sideloading pre-release builds, **Google Play Protect** or OEM security managers (Xiaomi HyperOS/MIUI, Samsung Knox, ColorOS, OriginOS) may display a full-screen warning: *"Blocked by Play Protect"* or *"High Risk App Detected: This app can access your personal data and track your location."*
>
> **Why this happens**: SHE requires elevated permissions (`BIND_DEVICE_ADMIN`, `ACCESS_BACKGROUND_LOCATION`, `SEND_SMS`, `RECEIVE_BOOT_COMPLETED`) to combat thieves when offline, and pre-release binaries are signed with a development key rather than an established Google Play account. SHE contains zero trackers, zero ads, and zero cloud data harvesting.
>
> **How to bypass and proceed**:
> * **Google Play Protect (Stock/Pixel/Motorola)**: Tap **"More details"** (down arrow) $\rightarrow$ Tap **"Install anyway"**. If completely blocked, open Google Play Store $\rightarrow$ Profile $\rightarrow$ Play Protect $\rightarrow$ Settings gear $\rightarrow$ temporarily toggle off *"Scan apps with Play Protect"*.
> * **Xiaomi / Redmi / POCO (HyperOS / MIUI)**: Wait for the 10-second security countdown $\rightarrow$ Check *"I am aware of the possible risks"* $\rightarrow$ Tap **"OK"** or **"Install"**.
> * **Samsung Galaxy (One UI)**: Tap **"More options"** $\rightarrow$ Tap **"Install anyway"**.
> * **Oppo / Realme / Vivo**: Tap **"Continue installing"** $\rightarrow$ Enter lockscreen PIN/fingerprint when prompted.
> * **Via Computer (ADB)**: Run `adb install -r -g app-guardian-direct-debug.apk` (the `-g` flag auto-grants all permissions and bypasses interactive warnings).

2. Grant the requested Android permissions:
   * **Location (Allow all the time)**: Needed for continuous theft tracking.
   * **SMS & Phone (Optional)**: Needed to receive offline emergency commands and dispatch emergency location alerts when no internet is available.
   * **Notifications**: Required to keep the tracking service active in the background.

> [!NOTE]
> **SMS Privacy & Zero-Retention OTP Protection**:
> * **No Inbox Reading**: SHE does not request `READ_SMS` and cannot access existing messages, inbox history, or banking records.
> * **Zero-Retention OTP Discard**: Any incoming SMS that does not start with `AT1:CMD:` (such as bank OTPs, 2FA codes, or personal chats) is immediately dropped from memory in 0 microseconds without being logged or parsed.
> * **Strict Recipient Lockdown**: Outgoing SMS can only ever be sent to your registered Admin Companion phone number.
> * **Zero Link Opening**: Guardian contains 0 WebViews and 0 URL launching capabilities; it is physically impossible for the app to open malicious links.
> * **100% Optional**: If you prefer not to grant SMS permissions, simply deny them. Guardian will continue operating 100% normally over Wi-Fi and mobile data.

### Step 3: Pair the Devices via Camera QR Scan
1. In the **Admin App**, tap **`PAIR NEW DEVICE`**. A high-density QR code will appear on screen.
2. In the **Guardian App**, tap **`PAIR WITH ADMIN (QR / CODE)`**. Point the camera at the Admin phone's screen.
3. Both phones will display a **Mutual Confirmation Code** (e.g., `482-910`). 
4. Confirm that the codes match on both screens. Once confirmed, the phones negotiate symmetric keys and the device is fully protected.

### Step 4: Configure Trusted Emergency Contacts
1. In the **Guardian App**, set your **Trusted Emergency Contact Number** (e.g., your secondary number or a family member's number).
2. This number is used to receive automated emergency SMS coordinates if your phone is ever stolen and taken offline.

---

## 3. Day-to-Day Operation

### Checking Live Location
* Open the **Admin App** dashboard.
* Tap your protected phone's alias to view its latest location fix, battery level, accuracy radius, and last-seen timestamp.
* **Search on Map**: Tap **`Search on Map`** to automatically open Google Maps (or your preferred map app) with a pin placed on the phone's coordinates.
* **Telemetry Guide**: Tap **`Info`** to review how GPS satellite acquisition, Direct Boot resumption, and accuracy margins work under the hood.

### Testing Alarms Safely: "Test Mode" vs. "Real Stolen Mode"
When showing the app to friends or verifying that speakers work, **never** activate real stolen mode:
* **`TEST_MODE (Drill)`**: Plays a safe, non-destructive 10-second siren and voice alert. It automatically shuts off after 10 seconds, does not lock your device, and does **not** send emergency SMS messages to your contacts.
* **`STOLEN_MODE (Real OG)`**: Full combat lockdown. Loops siren and voice alert continuously at maximum volume, dispatches emergency SMS messages with Google Maps links, and takes over the screen.

### Using the Encrypted Recovery Vault
The **Recovery Vault** is your digital safety deposit box. It uses military-grade AES-256-GCM encryption on your device before sending anything to the server. Even the server cannot read your data.

1. In the Admin app, tap **`RECOVERY VAULT`**.
2. Unlock with your fingerprint or device PIN.
3. Tap **`+ Add Secret`** to store:
   * **Device Hardware Identifiers**: IMEI 1, IMEI 2, and Serial Number (vital for police reports and carrier blacklisting).
   * **Account Recovery Codes**: Google, banking, or cloud backup 2FA codes.
   * **Vital Emergency Contacts**: Phone numbers and email addresses of close family.
   * **Insurance & Purchase Receipts**: Proof of ownership document details.
4. Set a strong **Master Passphrase** (minimum 16 characters). Write this passphrase on a piece of paper and keep it in your physical records.

---

## 4. What to Do If Your Phone Is Stolen

If your phone is lost or stolen, act immediately using this 4-step workflow:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        THEFT RESPONSE WORKFLOW                         │
├────────────────────────────────────────────────────────────────────────┤
│ Step 1: Engage Stolen Mode from Admin Companion (or via SMS)           │
│    │                                                                   │
│    ▼                                                                   │
│ Step 2: What Happens on the Thief's Phone                              │
│         • Max-volume siren sounds with looping voice warning           │
│         • Hardware volume keys are locked                              │
│         • Screen locks showing your Emergency Return Card             │
│         • Emergency SMS with live Google Maps link sent to contacts    │
│    │                                                                   │
│    ▼                                                                   │
│ Step 3: Call Police (112 India) with Coordinates & IMEI               │
│    │                                                                   │
│    ▼                                                                   │
│ Step 4: Access Vault on Replacement Device to Restore Accounts         │
└────────────────────────────────────────────────────────────────────────┘
```

### Step 1: Activate Stolen Mode
* **If the phone has internet**: Open your Admin Companion App, tap the device, select **`STOLEN_MODE (Real OG)`**, and confirm.
* **If the phone is offline / mobile data is turned off**: Send an authenticated SMS command from your registered trusted number to the Guardian phone.

### Step 2: What the Thief Experiences
Once Stolen Mode engages:
1. **Loud Voice Alarm**: The phone immediately starts blaring a siren at 100% volume, accompanied by Android Text-To-Speech loudly repeating:
   > *"Warning. This device is reported stolen. Location is being tracked and reported. Return to owner immediately."*
2. **Volume Lock**: Physical volume-down buttons cannot silence the alarm.
3. **Lock Screen Takeover**: The screen displays your **Emergency Return Card** with your trusted phone number and a direct button to dial Emergency Services (112).
4. **Automated Emergency SMS**: The phone sends an SMS to your trusted emergency contacts:
   > `[AntiTheft ALERT] Handset 'MyPhone' marked STOLEN. GPS: https://maps.google.com/?q=28.6139,77.2090 (±8m). Return to owner immediately.`

### Step 3: Reporting & Official IMEI Blacklisting in India

Filing a police report and blacklisting the stolen device are two **separate, sequential actions**:

```
┌────────────────────────────────────────────────────────┐
│  ACTION A: File Police Complaint / FIR First           │
│  • Dial 112 (Real-time emergency dispatch) or          │
│    visit local police station / State Police e-FIR     │
│  • Hand over live GPS coordinates & IMEI numbers       │
│  • Obtain formal FIR / Complaint Acknowledgment Copy   │
└──────────────────────────┬─────────────────────────────┘
                           │ Mandatory FIR Copy
                           ▼
┌────────────────────────────────────────────────────────┐
│  ACTION B: Submit CEIR Blacklist Request               │
│  • Visit official portal: sancharsaathi.gov.in         │
│  • Submit Block Request with FIR & ID documents        │
│  • Handset blocked across all Indian telecom networks  │
└────────────────────────────────────────────────────────┘
```

#### Action A: File the Police Complaint / FIR First
1. **For Immediate Emergencies (Theft in Progress)**: Dial **112** (India National Emergency Response Support System - ERSS) to request immediate on-duty officer assistance.
2. **For Lost / Snapped Devices**: Visit the jurisdictional police station or your State Police online citizen portal (e-FIR for lost property).
3. **What to Provide to the Police**:
   * **Live Location Coordinates**: Show the latest GPS fix and Google Maps link from your Admin Dashboard or emergency SMS.
   * **Device Identity**: Provide your phone's **IMEI 1 and IMEI 2** numbers (retrieve them instantly from your Admin app's encrypted **Recovery Vault**).
4. **Essential Output**: You **must** obtain a stamped copy of the **Police Complaint / FIR**, or an official digital complaint reference acknowledgment. This document is strictly required for the next step.

#### Action B: Submit a Block Request on CEIR / Sanchar Saathi
The **Central Equipment Identity Register (CEIR)**, operated by the Department of Telecommunications (DoT) via **Sanchar Saathi**, blocks stolen devices from operating on any Indian carrier network (Jio, Airtel, Vi, BSNL).

> [!NOTE]
> **What CEIR Does vs. What This App Does**:
> * **This App**: Captures live GPS coordinates, triggers audio sirens, takes intruder snapshots, and sends real-time emergency SMS.
> * **CEIR**: Carrier-level blacklisting. It prevents the handset from connecting to cellular towers even if the thief replaces the SIM card. CEIR does **not** provide you with real-time GPS tracking.

1. Navigate to the official portal: **[https://www.sancharsaathi.gov.in](https://www.sancharsaathi.gov.in)** (or directly at **[https://ceir.sancharsaathi.gov.in](https://ceir.sancharsaathi.gov.in)**).
2. Select **"Block Stolen / Lost Mobile"** (`Request for blocking lost/stolen mobile`).
3. Fill out the citizen form with the **Required Documents Checklist**:
   * **Device Information**: Mobile Number 1 & 2 (associated with the device), **IMEI 1 and IMEI 2** (both are mandatory for dual-SIM phones), Device Brand, Model, and Mobile Purchase Invoice/Bill.
   * **Lost Information**: Lost date, state, district, police station, and the **Police Complaint / FIR Number** + an uploaded digital copy (PDF/JPEG) of the complaint.
   * **Mobile Owner Personal Information**: Owner Name, Address, Identity Proof upload (Aadhaar Card, PAN, Voter ID, Driving License, or Passport).
   * **Alternate Mobile Number**: A working secondary mobile number belonging to you or a relative to receive the verification OTP and request tracking ID.
4. Submit the request. Once processed, the device's IMEIs are added to the national central blacklist. If anyone inserts a new SIM card into the phone, the carrier automatically flags the handset to law enforcement.

### Step 4: De-escalation (When You Get Your Phone Back)
Once the phone is recovered:
1. In the Admin app, tap the device and select **`EXIT_KIOSK`** (or **`RECOVERED`**).
2. The alarm will instantly silence, restrictions will be cleared, and normal phone operation will resume.

---

## 5. Troubleshooting & Edge Cases

* **What if I lose my Admin Controller Phone?**
  Use your **One-Time Recovery Codes** to log into the backend from any secondary phone or authorized device.
* **What if I forget my Vault Master Passphrase?**
  Because the Vault is zero-knowledge and end-to-end encrypted, **no one can recover your passphrase if you lose it**. Always keep a written paper copy of your 16-character master passphrase in a secure physical location.
* **How do I safely exit Kiosk Mode without the Admin phone?**
  If the Admin phone is completely unavailable, use your registered secondary phone to send the signed de-escalation SMS command to the handset.

---

## 6. Honest Technical Boundaries: What No App Can Prevent

Security software must set honest expectations. No mobile application in the world can bypass physical physics:

1. **Hardware Force-Off / Battery Pull**: 
   A thief who holds down the physical hardware power button for 10–15 seconds will trigger a motherboard-level hard reset, cutting power to the processor. Once powered off, software stops running.
2. **Faraday Bags / Signal Blockers**: 
   If a thief places the phone into a metal-lined radio-frequency shielding pouch (Faraday bag), cellular and GPS radio signals cannot penetrate. Guardian continues logging GPS internally and will burst-sync coordinates the moment signal returns.
3. **Specialized Hardware Chip-Off Tools**: 
   Professional theft syndicates with hardware EEPROM programmers can overwrite flash memory chipsets directly. Hardware-backed Factory Reset Protection (FRP) and Device Owner restrictions make this economically non-viable for opportunistic thieves, but cannot stop physical destruction.
