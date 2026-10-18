# Anti-Theft & Handset Recovery Ecosystem: Release & Distribution Runbook

This guide details the procedure for producing, signing, verifying, and distributing release-ready APKs to physical handsets without exposing unauthenticated attack surfaces.

---

## 1. Release Keystore & Key Management

Android requires all applications to be digitally signed with a cryptographic private key before installation on physical hardware.

### 1.1 Generating Your Private Release Keystore
> [!CAUTION]
> **Generate this keystore on your own machine. Never share or commit the keystore file or password.**
> If your release keystore is lost, existing installations cannot be updated in-place without uninstalling (which wipes on-device pairing keys).

Generate a 4096-bit RSA release signing key:
```bash
keytool -genkey -v \
  -keystore /path/to/secure/release.keystore \
  -alias antitheft_release \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

### 1.2 Environment Variable Configuration
The Gradle build configuration reads signing credentials lazily from environment variables. Do **not** hardcode credentials into Gradle build files.

Before building, export these variables in your shell:
```bash
export RELEASE_KEYSTORE_PATH="/path/to/secure/release.keystore"
export RELEASE_KEYSTORE_PASSWORD="<YOUR_STRONG_PASSWORD>"
export RELEASE_KEY_ALIAS="antitheft_release"
```

---

## 2. Compiling Signed Release APKs

From the repository root:
```bash
cd /home/sam/Documents/she/android

# Set up JDK 17 toolchain
export JAVA_HOME=/home/sam/Documents/she/.tools/jdk17
export PATH=$JAVA_HOME/bin:$PATH

# Compile Direct Release Guardian and Admin APKs
/home/sam/Documents/she/.tools/gradle-8.7/bin/gradle \
  :app-guardian:assembleDirectRelease \
  :app-admin:assembleRelease
```

### Generated Artifact Locations
* **Guardian (Protected Phone)**: 
  `android/app-guardian/build/outputs/apk/direct/release/app-guardian-direct-release.apk`
* **Admin (Controller Phone)**: 
  `android/app-admin/build/outputs/apk/release/app-admin-release.apk`

---

## 3. Pre-Distribution Verification Checklist

Before transferring any APK to a real phone, verify that the artifact complies with security standards:

```
┌────────────────────────────────────────────────────────────────────────┐
│                   PRE-DISTRIBUTION SECURITY AUDIT                      │
├────────────────────────────────────────────────────────────────────────┤
│ [ ] Check 1: Cryptographic Signature Valid (apksigner)                 │
│ [ ] Check 2: Debuggable Flag Is False (aapt dump badging)              │
│ [ ] Check 3: Cleartext HTTP Is Prohibited (Network Security Config)    │
│ [ ] Check 4: Version Code Monotonically Incremented                    │
└────────────────────────────────────────────────────────────────────────┘
```

### Check 1: Cryptographic Signature Verification
Verify that the APK is signed with v2/v3 signing schemes:
```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --verbose \
  android/app-guardian/build/outputs/apk/direct/release/app-guardian-direct-release.apk
```
*Expected Output*:
```text
Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
```

### Check 2: Confirm Non-Debuggable
Ensure debug mode is disabled so unauthorized debuggers cannot attach to process memory:
```bash
$ANDROID_HOME/build-tools/34.0.0/aapt dump badging \
  android/app-guardian/build/outputs/apk/direct/release/app-guardian-direct-release.apk | grep -i "debuggable"
```
*Expected Output*: **Zero output** (empty). If `application-debuggable` appears, the build is invalid.

### Check 3: Confirm Cleartext HTTP Is Prohibited
In release builds, Android's `NetworkSecurityConfig` enforces HTTPS exclusively. Verify that no cleartext exceptions leaked from debug testing:
```bash
grep -rn "cleartextTrafficPermitted" android/app-guardian/src/main/res/
```
In release mode, the backend URL must be an encrypted HTTPS domain (`https://...`) backed by a valid, publicly trusted Certificate Authority (e.g., Let's Encrypt, Cloudflare). Self-signed certificates and cleartext `http://` URLs will be blocked by Android.

---

## 4. Distributing to Real Handsets (Without Public Web Servers)

> [!WARNING]
> **Never serve APKs over unencrypted public HTTP (`http://...`).**
> Any attacker on the same Wi-Fi can execute a Man-in-the-Middle (MITM) attack, modify the APK binary, and inject malicious surveillance code. Always distribute APKs via authenticated, direct channels.

### Method A: Direct ADB Installation over Authenticated USB (Recommended)
This is the safest distribution method for your own devices:
```bash
# Install Guardian to target phone
adb -s <DEVICE_SERIAL> install -r \
  android/app-guardian/build/outputs/apk/direct/release/app-guardian-direct-release.apk

# Install Admin to controller phone
adb -s <ADMIN_SERIAL> install -r \
  android/app-admin/build/outputs/apk/release/app-admin-release.apk
```

### Method B: Direct Secure File Transfer (Sideloading)
If installing onto a remote or untethered phone, transfer the file directly (via encrypted cloud storage, Signal, or USB flash drive):

1. **Enable Sideloading (Unknown Apps)**:
   * When opening the APK on the phone, Android displays: *"For your security, your phone is not allowed to install unknown apps from this source"*.
   * Tap **Settings** $\rightarrow$ Toggle **"Allow from this source"** ON $\rightarrow$ Tap **Install**.

2. **Android 13+ "Restricted Settings" Bypass**:
   Android 13+ automatically blocks sideloaded apps from enabling Device Administration and Accessibility services (*"Restricted setting: For your security, this setting is currently unavailable"*).
   To safely enable permissions on the user's phone:
   * Go to **Settings** $\rightarrow$ **Apps** $\rightarrow$ **All apps** $\rightarrow$ **Guardian**.
   * In the top-right corner, tap the **Three Dots (⋮)** menu.
   * Tap **"Allow restricted settings"**.
   * Authenticate with your phone's PIN or fingerprint.
   * Return to the Guardian app and activate Device Administrator.

---

## 5. Version Bumping & Safe In-Place Updates

> [!CRITICAL]
> **Never uninstall the Guardian app to perform an update.**
> Uninstalling Guardian will erase:
> 1. The hardware Keystore AES keys.
> 2. The encrypted Room SQLCipher database.
> 3. The Direct Boot pairing secret and sequence counters.
> Doing so breaks the cryptographic link with the Admin companion app permanently.

### How In-Place Updates Work
To update the app without losing data or pairing:
1. In `android/app-guardian/build.gradle.kts` and `android/app-admin/build.gradle.kts`:
   * Increment `versionCode` by 1 (e.g., `1` $\rightarrow$ `2`).
   * Update `versionName` (e.g., `"0.2.0"` $\rightarrow$ `"0.2.1"`).
2. Sign the new APK using the **exact same release keystore** used for the previous version.
3. Install the update directly over the existing app:
   ```bash
   adb install -r android/app-guardian/build/outputs/apk/direct/release/app-guardian-direct-release.apk
   ```
   Android verifies that the signing certificate matches the installed package, keeps all app data, Keystore entries, and database tables intact, and seamlessly applies the update.

---

## 6. Google Play Store Status

Google Play Store distribution is currently **out of scope** for this ecosystem.

* **Reason**: Google Play enforces strict policy limitations on `SEND_SMS`, `RECEIVE_SMS`, and consumer-facing `DeviceAdminReceiver` capabilities. Legitimate anti-theft apps requiring autonomous SMS dispatch when offline must operate as Enterprise Work Profile apps or via direct distribution.
* **Current Distribution Model**: Direct Distribution (`directRelease`) is the officially supported deployment model.
