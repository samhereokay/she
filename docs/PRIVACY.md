# Privacy & Telemetry Architecture

This document details how **SHE (Sentinel for Handheld Equipment)** handles inbound SMS, outbound offline fallback, location telemetry, and credentials across Guardian (monitored handset) and Admin (controller).

---

## 1. Zero-Retention Inbound SMS Filter

Under the Android telephony architecture, Guardian receives all incoming SMS broadcasts via the system `RECEIVE_SMS` broadcast receiver and discards non-command messages immediately. To protect user privacy against unauthorized interception of personal messages, banking OTPs, or private communications, SHE implements a **strict zero-retention privacy filter**:

* **Pre-Filter by Header**:
  In [`SmsCommandReceiver.kt`](file:///home/sam/Documents/she/android/app-guardian/src/main/java/com/antitheft/guardian/sms/SmsCommandReceiver.kt#L38-L42), inbound messages are evaluated in memory:
  ```kotlin
  if (!body.trim().startsWith("AT1:CMD:")) {
      continue
  }
  ```
  Any SMS that does not strictly begin with the `AT1:CMD:` protocol prefix is immediately discarded from memory without parsing. Guardian receives all incoming SMS broadcasts and discards non-command messages immediately in-memory without processing, saving, or transmitting them.
* **No Content Logging**:
  Inbound SMS body content is **never logged**, stored in databases, or forwarded to backend servers. Log output records only metadata for valid commands (e.g., `Authenticated command received: LOCATE (Seq: 5)`).
* **Non-Interfering Delivery**:
  SHE does not abort broadcast delivery (`abortBroadcast()`). Incoming SMS messages continue to route uninterrupted to the device's default SMS messenger (e.g. Google Messages, Samsung Messages).

---

## 2. Scoped Outbound SMS Dispatch

* **Strict Whitelist Routing**:
  Guardian cannot send SMS messages to arbitrary or premium numbers. Outgoing SMS fallback is hardcoded to route strictly to `storage.getTrustedPhoneNumber()`—the owner's Admin Companion number verified during mutual QR camera enrollment.
* **Drop on Missing Trusted Phone**:
  If no verified trusted phone number is registered, outgoing SMS beacons are dropped immediately.
* **Rigid Structured Templates**:
  The only SMS messages constructed by Guardian are compact, authenticated `AT1:LOC:` format strings containing GPS coordinates, fix timestamp, monotonic sequence counter, and HMAC-SHA256 signature.

---

## 3. Location Telemetry & Server Privacy

* **Zero-Knowledge Backend Storage**:
  Location updates are encrypted and signed on the device with hardware Keystore keys before ingestion.
* **Data Minimization**:
  Location history is retained only for the active retention window (`LOCATION_RETENTION_DAYS`, defaulting to 30 days) and purged automatically.
* **No Covert Surveillance**:
  Guardian runs an ongoing Android Foreground Service with a permanent system notification (`FOREGROUND_SERVICE_LOCATION`), ensuring the handset user is always aware of active protection.
