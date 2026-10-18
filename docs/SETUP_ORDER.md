# Anti-Theft & Handset Recovery Ecosystem: Setup Order Runbook

> [!IMPORTANT]
> **Order strictly matters.** Android security frameworks enforce rigid dependency chains:
> * Provisioning **Device Owner** fails if *any* user account is already configured.
> * Arming **Hardware Factory Reset Protection (FRP)** requires linking a Google account *after* Device Owner is set.
> * Initializing **Android Keystore hardware-bound credentials** requires a device PIN/Biometric lock to exist *before* vault secrets are stored.
> Follow this numbered sequence exactly from Step 1 to Step 10.

---

```
┌────────────────────────────────────────────────────────────────────────┐
│                        MANDATORY SETUP SEQUENCE                        │
├────────────────────────────────────────────────────────────────────────┤
│ 1. Factory Reset Test Phone  ──► 2. Provision Device Owner (ADB/QR)    │
│                                           │                            │
│ 4. Set Screen Lock / PIN     ◄── 3. Add Google Account (Engages FRP)   │
│         │                                                              │
│ 5. Install Guardian App      ──► 6. Install & Log into Admin App       │
│                                           │                            │
│ 8. Configure Emergency SMS   ◄── 7. Mutual Camera QR Pairing           │
│         │                                                              │
│ 9. Arm Encrypted Vault       ──► 10. Execute Safe "Test Mode" Drill    │
└────────────────────────────────────────────────────────────────────────┘
```

---

## Step 1: Factory Reset the Phone to Protect (Zero Accounts)
1. Perform a complete factory reset on the handset you intend to protect.
2. Complete the initial Android Setup Wizard (select Language, connect to Wi-Fi).
3. **CRITICAL REQUIREMENT**: **DO NOT ADD A GOOGLE ACCOUNT OR ANY OTHER ACCOUNT.**
   * When the wizard prompts *"Sign in with your Google Account"*, tap **Skip**.
   * If any account exists, Android's security framework will reject Device Owner provisioning with: `java.lang.IllegalStateException: Not allowed to set the device owner because there are already some accounts on the device`.

---

## Step 2: Provision Device Owner Mode via ADB
Device Owner status allows the Guardian app to enforce physical volume-down button lockdown (`DISALLOW_ADJUST_VOLUME`), suppress factory resets from settings (`DISALLOW_FACTORY_RESET`), and activate the anti-theft kiosk screen.

1. Enable Developer Options & USB Debugging:
   * Go to **Settings** $\rightarrow$ **About phone** $\rightarrow$ Tap **Build number** 7 times.
   * Go to **Settings** $\rightarrow$ **System** $\rightarrow$ **Developer options** $\rightarrow$ Turn on **USB debugging**.
2. Connect the phone to your PC with a USB cable and authorize the computer.
3. Install the Guardian APK first (using `-g` to pre-grant runtime permissions and prevent OEM prompt conflicts):
   ```bash
   adb install -r -g android/app-guardian/build/outputs/apk/direct/debug/app-guardian-direct-debug.apk
   ```

   > [!NOTE]
   > **Handling Play Protect / OEM "High Risk" Warnings**: If installing manually without ADB (or if an OEM package manager prompts):
   > * **Google Play Protect**: Tap **"More details"** $\rightarrow$ **"Install anyway"**.
   > * **Xiaomi / MIUI / HyperOS**: Wait 10 seconds on the warning screen $\rightarrow$ Check *"I am aware of risks"* $\rightarrow$ Tap **"OK"**.
   > * **Samsung One UI**: Tap **"More options"** $\rightarrow$ **"Install anyway"**.

4. Set the Guardian receiver as the Device Owner:
   ```bash
   adb shell dpm set-device-owner com.antitheft.guardian/com.antitheft.deviceowner.AntiTheftDeviceAdminReceiver
   ```
5. **Verify Output**: The terminal must return:
   ```text
   Success: Device owner set to package ComponentInfo{com.antitheft.guardian/com.antitheft.deviceowner.AntiTheftDeviceAdminReceiver}
   ```

---

## Step 3: Link Your Google Account (Arms Hardware FRP)
Now that Device Owner is established:
1. Open **Settings** $\rightarrow$ **Passwords & accounts** (or **Accounts**).
2. Tap **Add account** $\rightarrow$ **Google**.
3. Sign in with your personal Google credentials.
4. **Why this order works**: 
   * Device Owner is already locked into the OS root.
   * Linking your Google account *afterwards* arms **Factory Reset Protection (FRP)**. If a thief forces the phone into recovery mode and attempts a hardware wipe, Android blocks initialization until the original Google password is entered.

---

## Step 4: Configure Device Screen Lock (PIN / Fingerprint)
1. Go to **Settings** $\rightarrow$ **Security** $\rightarrow$ **Screen lock**.
2. Set a strong PIN, Pattern, or Password (minimum 6 digits recommended).
3. Enroll your fingerprint.
4. **Why this matters**:
   * Android Keystore requires a secure lockscreen to generate hardware-backed master encryption keys.
   * Enables the **Failed Unlock Intruder Photo** trigger (`ACTION_SECURITY_PASSWORD_FAILED`), which trips when someone enters 3 wrong PIN attempts.

---

## Step 5: Grant Runtime Permissions & Disable Battery Optimization
1. In terminal, grant all critical runtime permissions to Guardian:
   ```bash
   for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION POST_NOTIFICATIONS READ_PHONE_STATE SEND_SMS RECEIVE_SMS CALL_PHONE CAMERA; do
     adb shell pm grant com.antitheft.guardian android.permission.$p 2>/dev/null
   done
   ```
2. Disable OEM Battery Killers:
   * Go to **Settings** $\rightarrow$ **Apps** $\rightarrow$ **Guardian** $\rightarrow$ **App battery usage** $\rightarrow$ Select **Unrestricted**.
   * (On Xiaomi/Vivo/OnePlus devices, also enable **Autostart** in system settings).

---

## Step 6: Install & Initialize the Admin Companion App
1. On your controller phone (or PC testing environment), install `app-admin-debug.apk`:
   ```bash
   adb install -r android/app-admin/build/outputs/apk/debug/app-admin-debug.apk
   ```
2. Forward the communication port over USB (if testing locally):
   ```bash
   adb reverse tcp:8000 tcp:8000
   ```
3. Open the Admin app, set Server URL to `http://127.0.0.1:8000`, enter your chosen owner credentials, and tap **REGISTER**.
4. Set up Two-Factor Authentication (TOTP) and store your one-time recovery codes safely offline.

---

## Step 7: Perform Camera QR Pairing
1. In the **Admin App**, tap **`PAIR NEW DEVICE`**. This renders an on-screen QR code containing a single-use pairing token and ECDSA key.
2. In the **Guardian App**, tap **`PAIR WITH ADMIN (QR / CODE)`**. Point the camera at the Admin phone screen.
3. Verify the **Mutual Confirmation Code** displayed on both screens (e.g. `512-890`).
4. Both phones store the shared pairing secret in hardware-backed storage (`KeystoreAesGcmStorage`).

---

## Step 8: Configure Trusted Emergency Contacts
1. In the Guardian app, enter your primary **Emergency Contact Number** (the phone that should receive automated emergency SMS coordinates if the phone is stolen).
2. Tap **Save Settings**.
3. Verify that the number normalizes properly (e.g., `+919876543210` for India).

---

## Step 9: Arm the Encrypted Recovery Vault
1. Open the Admin Companion App and navigate to **`RECOVERY VAULT`**.
2. Authenticate with your fingerprint or device PIN.
3. Tap **`+ Add Secret`**:
   * **Item 1 (`DEVICE_IDENTIFIER`)**: Enter Title `"Handset IMEI & Serial"` and paste your phone's IMEI 1, IMEI 2, and Hardware Serial.
   * **Item 2 (`RECOVERY_CODE`)**: Store Google and cloud backup recovery codes.
4. Enter a strong **Master Passphrase** (at least 16 characters).
5. **Write down your master passphrase on physical paper** and place it in your home safe.

---

## Step 10: Run the "Test Mode" Drill
Before relying on the ecosystem, verify end-to-end functionality using the safe drill:
1. Open the Admin App $\rightarrow$ Tap your paired device $\rightarrow$ Select **`TEST_MODE (Drill)`**.
2. **What should happen on the Guardian phone**:
   * The phone immediately plays a 10-second audible siren + Android Text-to-Speech voice warning (*"Anti-Theft Test Mode activated..."*) at maximum volume.
   * After 10 seconds, the siren automatically silences.
   * The latest GPS coordinate updates on the Admin map.
   * **Zero emergency SMS messages are sent to your family contacts.**
3. If the test succeeds, your handset is fully protected and ready for field use!

---

## How to Safely De-provision (When Changing Phones)

If you ever retire or sell the protected phone, de-provision it cleanly using this sequence:
1. In the Admin Companion App, tap the device and select **`CLEAR_DEVICE_OWNER`**.
2. Enter your 6-digit TOTP authenticator code to authorize the de-provisioning.
3. Once the command arrives on the Guardian phone, it clears all user restrictions, unbinds the Device Owner status, and allows standard uninstallation.
4. *(Fallback)*: If the Admin phone is completely destroyed, perform a recovery-mode hardware wipe using your phone model's hardware key combination (e.g., Power + Volume Down). You will be prompted for your Google account credentials upon reboot to pass Factory Reset Protection.
