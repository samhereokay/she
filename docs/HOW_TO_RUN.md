# Operations & Hardware Testing Guide: How to Run Anti-Theft & Recovery Ecosystem

This comprehensive runbook details how to configure, execute, and verify the Anti-Theft & Handset Recovery Ecosystem across local development, physical test handsets, and production environments.

---

## 1. Environment Prerequisites

The repository includes embedded tooling in the `.tools/` directory.

### Toolchain Paths
```bash
# Set up environment variables
export REPO_ROOT="/home/sam/Documents/she"
export JAVA_HOME="$REPO_ROOT/.tools/jdk17"
export ANDROID_HOME="$REPO_ROOT/.tools/android-sdk"
export PATH="$REPO_ROOT/.tools/gradle-8.7/bin:$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$HOME/.local/bin:$PATH"
```

Verify installed versions:
- `adb version` $\rightarrow$ Android Debug Bridge version 1.0.41 (v37.0.1)
- `python3 --version` $\rightarrow$ Python 3.12+
- `java -version` $\rightarrow$ OpenJDK 17.0.2

---

## 2. Running the Backend Server

The backend orchestrates signed device pairing, recovery command dispatching, encrypted vault storage, and location telemetry sync.

### 2.1 Starting Local Development Server
From the repository root:
```bash
cd /home/sam/Documents/she
python3 -m uvicorn app.main:app --app-dir backend --host 0.0.0.0 --port 8000 --reload
```
The server will bind to `0.0.0.0:8000` using SQLite (`backend/antitheft.db`).

### 2.2 Bootstrapping the Single-Owner Account
Registration closes after the first account. Use the CLI tool to initialize the primary owner:
```bash
PYTHONPATH=backend python3 -m app.cli.admin create-owner \
  --username admin_owner \
  --email admin@example.com \
  --password YourSecurePassword123!
```

### 2.3 Server CLI Administrative Commands
```bash
# Check server status, owner provisioning, and registered devices:
PYTHONPATH=backend python3 -m app.cli.admin status

# Reset owner account if locked out (requires terminal confirmation):
PYTHONPATH=backend python3 -m app.cli.admin reset-owner --force
```

---

## 3. Connecting the Physical Phone to the PC

### 3.1 Phone Setup (Developer Mode & USB Debugging)
1. On your test phone, open **Settings** $\rightarrow$ **About phone**.
2. Tap **Build number** 7 times until you see *"You are now a developer!"*.
3. Go to **Settings** $\rightarrow$ **System** $\rightarrow$ **Developer options**.
4. Enable **USB debugging**.
5. Connect phone to PC with a USB cable. On the phone screen, select **"Always allow from this computer"** $\rightarrow$ **Allow**.
6. Verify connection from PC terminal:
   ```bash
   adb devices
   ```
   *(Should report your phone's serial number with status `device`)*

### 3.2 Network Routing Modes
Because Android devices cannot reach `localhost` or `127.0.0.1` on your PC directly:

* **Mode A: USB Port Forwarding (Recommended for Initial Tests)**
  ```bash
  adb reverse tcp:8000 tcp:8000
  ```
  *(Allows the phone to reach your PC server via `http://127.0.0.1:8000` through the USB cable)*

* **Mode B: Local WiFi LAN**
  Find your PC's local IP (e.g., `ip addr show` $\rightarrow$ `192.168.1.50`). Connect phone to the same WiFi and use `http://192.168.1.50:8000`.

* **Mode C: Cloudflare Quick Tunnel (Required for Untethered / Outdoor / Release Testing)**
  Release APKs block cleartext HTTP. For outdoor GPS or SIM testing off the cable:
  ```bash
  cloudflared tunnel --url http://localhost:8000
  ```
  Use the generated HTTPS URL (e.g. `https://random-words.trycloudflare.com`).

---

## 4. Building and Installing the Android APKs

### 4.1 Building from Source
To compile fresh debug APKs:
```bash
cd /home/sam/Documents/she
export JAVA_HOME=/home/sam/Documents/she/.tools/jdk17
export ANDROID_HOME=/home/sam/Documents/she/.tools/android-sdk
export PATH=$JAVA_HOME/bin:$PATH

./.tools/gradle-8.7/bin/gradle -p android :app-guardian:assembleDirectDebug :app-admin:assembleDebug
```

### 4.2 Installing via ADB
```bash
# Install Guardian (on the protected handset):
adb install -r android/app-guardian/build/outputs/apk/direct/debug/app-guardian-direct-debug.apk

# Install Admin (on controller handset or side-by-side on test phone):
adb install -r android/app-admin/build/outputs/apk/debug/app-admin-debug.apk
```

### 4.3 Handling Android 13+ "Restricted Settings"
On Android 13, 14, and 15, accessibility and SMS permissions are locked for sideloaded apps:
1. Open phone **Settings** $\rightarrow$ **Apps** $\rightarrow$ **Guardian**.
2. Tap the **3 vertical dots** in the top right corner.
3. Tap **Allow restricted settings** and authenticate with your lockscreen PIN.
4. Grant standard permissions automatically via ADB:
   ```bash
   for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS READ_PHONE_STATE SEND_SMS RECEIVE_SMS CALL_PHONE CAMERA; do
     adb shell pm grant com.antitheft.guardian android.permission.$p 2>/dev/null
   done
   ```

---

## 5. Physical Hardware 8-Step Test Protocol

Treat physical tests as learning iterations. Follow this exact sequence:

### Step 1: Real Camera QR Pairing
1. Open the **Admin** app on your phone.
2. Sign in with server URL `http://127.0.0.1:8000`, username `admin_owner`, and your password.
3. Enroll 2FA TOTP into your authenticator app (Google Authenticator, Aegis, Bitwarden).
4. Tap **PAIR NEW DEVICE** to surface the pairing QR code.
5. On the test handset, open **Guardian** $\rightarrow$ tap **PAIR WITH ADMIN PHONE (QR / CODE)**.
6. Scan the QR code using the camera. Confirm the mutual 6-digit verification code matches on both displays and submit.
7. Verify Guardian switches to **PROTECTED (ACTIVE)**.

### Step 2: Real GPS & Telemetry Sync
1. On Guardian, tap **CAPTURE LOCATION NOW**.
2. Verify GPS coordinates, accuracy, and altitude appear on the card.
3. Turn off Wi-Fi/mobile data; tap **CAPTURE LOCATION NOW** again. Verify counter increases under **Offline Queue**.
4. Restore internet and tap **SYNC NOW**; verify queue empties and coordinates appear on the Admin dashboard map/history.

### Step 3: Bi-Directional SMS Testing
*Requires 2 SIM cards (or one SIM plus a secondary phone).*
1. Send an SMS containing the authenticated command to the Guardian phone:
   `ST:<pairing-secret-prefix> LOCATE <seq> <timestamp> <hmac>`
2. Verify Guardian parses the PDU, verifies the HMAC, and replies via SMS with its GPS location to the configured trusted number.
3. Send a forged SMS or replayed sequence counter; verify Guardian drops the message without reply and logs the rejection.

### Step 4: Admin Online Remote Commands
From the Admin app dashboard:
1. **LOCATE**: Dispatch online locate; verify Guardian receives command and updates location fix.
2. **RING**: Dispatch loud siren command. Verify high-decibel audio overrides Do Not Disturb / silent mode. Dispatch **STOP_RING** to silence.
3. **LOCK**: Dispatch lock command. Verify screen keyguard immediately engages.

### Step 5: Zero-Knowledge Vault Lifecycle
1. In Admin, tap **VAULT** $\rightarrow$ enter device lock PIN when prompted.
2. Tap **+ ADD SECRET**.
3. Enter title (`Bank-Recovery-Code`), secret (`98234-AX109`), and type master passphrase in both fields.
4. Tap **ENCRYPT & STORE**. Verify round-trip self-decryption succeeds and card appears.
5. Wipe app data to simulate lost phone:
   ```bash
   adb shell pm clear com.antitheft.admin
   ```
6. Re-open Admin, log in with password + TOTP, open Vault. Verify card shows:
   `[This phone has no local key. Passphrase restore required. Tap 'Restore / Import']`
7. Tap **RESTORE / IMPORT**, enter your passphrase. Verify secret decrypts on screen without server intervention.

### Step 6: Direct Boot (Pre-Unlock) & 1-Hour Doze Idle
1. Set a screen lock PIN on the Guardian phone.
2. Reboot the phone (`adb reboot`). **Do NOT enter the lockscreen PIN after boot.**
3. Verify Guardian's Direct Boot service initializes before first unlock and can capture location or receive SMS commands.
4. Unlock the phone, leave it unplugged on a flat surface for 1 hour to enter Android Doze mode. Send a remote recovery command and record latency.

### Step 7: Device Owner & Stolen Mode (Kiosk)
*Requires freshly factory-reset phone with NO accounts configured.*
1. Set Guardian as Device Owner via ADB:
   ```bash
   adb shell dpm set-device-owner com.antitheft.guardian/.directboot.DeviceAdminReceiver
   ```
2. Verify Device Owner status is active on Guardian UI.
3. In Admin app, dispatch **STOLEN_MODE**.
4. Verify LockTask kiosk engages: status bar, notifications, home key, and recent apps are disabled. Only the emergency dialer is accessible.
5. Dispatch **EXIT_KIOSK** from Admin to return to normal operation.
6. Verify `CLEAR_DEVICE_OWNER` command behavior: verify SMS attempt is rejected, and online attempt requires fresh TOTP verified $\le 60$s.

### Step 8: Hardware Recovery Mode Wipe Verification
1. Power off the phone.
2. Press hardware recovery keys (e.g., Power + Volume Up).
3. Select **Wipe data / factory reset**.
4. Reboot phone and confirm Android Factory Reset Protection (FRP) prompts for your Google account credentials.

---

## 6. Realistic Physical Capabilities vs Hard Limits

| Protection Feature | Real Capability | Hard Hardware Boundary |
| :--- | :--- | :--- |
| **Location Tracking** | Captures GPS without internet; queues offline; syncs when online. | Cannot track if handset is completely powered off or battery is drained. |
| **Carrier SMS** | Sends coordinates over cellular SMS when mobile data is unavailable. | Requires active SIM card and cellular network tower connection. |
| **Kiosk Stolen Mode** | Locks screen, disables USB debugging, status bar, and user factory reset. | Cannot prevent physical battery pull or hardware recovery bootloader menu. |
| **Tamper Alerts** | Detects SIM replacement and alerts trusted number immediately. | If SIM is removed before replacement SIM is inserted, alert queues until SIM inserted. |
| **Vault Encryption** | AES-256-GCM zero-knowledge; server cannot read stored secrets. | Forgotten master passphrase cannot be recovered by server or administrator. |

---

## 7. Troubleshooting & Diagnostics

### Real-Time Log Inspection
Monitor all Guardian and Admin activity live:
```bash
adb logcat -v time | grep -E "Guardian|Admin|Vault|SmsCommand"
```

### Save Logcat After a Failure
```bash
adb logcat -d > ~/Documents/she/phone_failure.log
```

### Resetting Device Owner During Testing
If you need to unprovision Device Owner during development:
```bash
# Must be executed from signed Admin command with fresh TOTP, or via wipe:
adb shell dpm remove-active-admin com.antitheft.guardian/.directboot.DeviceAdminReceiver
```
*(If blocked by policy, factory reset the test device).*
