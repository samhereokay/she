# Distribution Decision: Direct APK vs. Google Play Store

This document records the formal distribution strategy, regulatory analysis, and build-flavor architecture for the Android Anti-Theft & Recovery System.

---

## 1. Regulatory Context & Store Policy Constraints

### 1.1 Google Play SMS and Call Log Permissions Policy
In March 2019, Google Play enacted strict platform restrictions regarding `SEND_SMS` and `RECEIVE_SMS`. Under current Google Play policies:
- **Default Handler Rule**: An app requesting SMS permissions must be actively configured and selected as the user's **Default SMS app**.
- **Exception Categories**: Google maintains a restricted list of permitted exceptions (e.g., enterprise device management, backup and restore).
- **Anti-Theft Non-Eligibility**: Consumer anti-theft, tracker, and recovery applications are **explicitly denied SMS permission exceptions**. Attempting to publish an anti-theft app with SMS permissions results in automated policy rejection or store suspension.

### 1.2 Background Location Policy
Google Play requires a dedicated Declaration Form and video demonstration proving that background location (`ACCESS_BACKGROUND_LOCATION`) is core to the application's functionality. While anti-theft apps can qualify for background location exceptions, the review process is lengthy and subject to arbitrary rejection.

---

## 2. Decision: Direct APK Distribution (Primary)

To achieve the core prompt requirement—**reliable device recovery even when mobile data is off**—the primary deployment model is **Direct APK (Sideloading)**.

### Advantages:
1. **Unrestricted SMS Fallback**: Guardian retains full access to `SEND_SMS` and `RECEIVE_SMS`, enabling the 48-bit HMAC compact ASCII emergency beacons over cellular telephony when mobile data and Wi-Fi are disconnected.
2. **Immediate Deployment**: No reliance on third-party store approvals, policy shifts, or unexpected package removals.
3. **No Compromise on Security**: The application still signs all code with standard Android APK Signature Scheme v2/v3 and maintains strict Android sandbox isolation.

---

## 3. Dual Build-Flavor Architecture

To prevent code duplication if a public Google Play Store release is desired in the future, the project adopts a dual Gradle build flavor architecture:

```kotlin
android {
    flavorDimensions += "distribution"
    productFlavors {
        create("direct") {
            dimension = "distribution"
            versionNameSuffix = "-direct"
            // Includes SEND_SMS and RECEIVE_SMS in AndroidManifest.xml
        }
        create("play") {
            dimension = "distribution"
            versionNameSuffix = "-play"
            // Strips SMS permissions via manifest merge; disables SMS fallback UI
        }
    }
}
```

### Flavor Feature Matrix:

| Feature | `direct` Flavor (Primary) | `play` Flavor (Store Compliant) |
|---|---|---|
| **Autonomous GNSS Capture** | Supported | Supported |
| **SQLCipher Room Queue** | Supported | Supported |
| **Direct Boot Support** | Supported | Supported |
| **HTTPS Backend Sync** | Supported | Supported |
| **SMS Location Fallback** | **Supported** (`SEND_SMS`) | **Disabled** (Omitted) |
| **SMS Inbound Commands** | **Supported** (`RECEIVE_SMS`) | **Disabled** (Omitted) |
| **Google Play Eligibility** | Non-compliant (By design) | **100% Compliant** |

---

## 4. Sideload Installation & Google Play Protect Guidance

When installing the `direct` APK flavor:
1. **Unknown Sources**: The user enables "Install unknown apps" specifically for their file manager or browser.
2. **Google Play Protect**: Because the APK is self-signed and requests SMS permissions, Google Play Protect may display a warning dialog ("Unrecognized app developer").
3. **Owner Action**: The owner taps *More details* -> *Install anyway*. System guidance screens in Owner Admin guide users through this one-time step.
