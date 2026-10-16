# Corrected Master Build Prompt: Android Anti-Theft & Recovery System

Paste everything under Prompt into your coding agent (Antigravity, Claude Code). Answer the four questions in the last section first.

## Prompt

### 1. Role and goal

Act as a senior Android security engineer and Kotlin developer. Build a consent-based anti-theft and recovery system that helps the owner of a phone locate, secure and recover it, including when mobile data is off. Everything is owner-installed, visible and revocable. No hidden or covert behaviour.

### 2. Ground truth: what is and is not possible

Treat these as design constraints. Verify against current Android documentation, and report anything that has changed.

- GPS/GNSS works without internet, but sending a position needs a channel. Delivery order: internet to backend, then SMS to a pre-registered trusted number, then local queue, plus a low-battery last-known-location report.
- A normal app cannot stop power-off, airplane mode, force-stop, uninstall or factory reset. Only a device-owner app, provisioned on the owner's own phone, can apply supported user restrictions and lock-task (kiosk) mode, and even then a hardware force-reboot cannot be blocked.
- Factory Reset Protection comes from the Google account and the OS, not from our app. Google Find Hub's offline network is not open to third-party apps, so we guide the owner to enable it rather than rebuild it.
- On a personal phone, a lock-screen recovery message is not available to a third-party app. Device-owner mode, or Find Hub's remote secure, can do it.
- Never auto-dial calls. Use SMS to emergency contacts and a visible tap-to-call recovery screen.
- Android varies by version and manufacturer. Define a minimum SDK, detect capabilities at runtime, and show unsupported ones as unavailable.

### 3. Three protection tiers (separate code paths)

1. System layer (guidance only): a checklist screen that helps the owner switch on Find Hub, the OS theft-protection features (theft detection lock, offline device lock, remote lock, where the OS offers them), a strong PIN, a SIM PIN, and the manufacturer's setting that requires unlock for the power menu. Verify which of these exist on the test phone.
2. Normal app (default): everything in section 4.
3. Device owner (optional, separate module, adb or QR provisioning on the owner's own phone): factory-reset, airplane-mode, safe-boot, add-user and uninstall restrictions, plus lock-task mode in stolen state to hide the power menu. Test each restriction on real hardware and record the result per model.

### 4. Guardian (protected phone)

- Pairing with Owner Admin by visible QR code; per-device keypair in Android Keystore.
- Location capture with accuracy, capture time, source and device ID, stored in Room; works with no backend.
- Sync queue: idempotent event IDs, sequence numbers, out-of-order handling; capture time shown separately from server-receipt time.
- SMS fallback to a pre-registered trusted number: compact report (alias, coordinates, timestamp, report ID, counter, truncated HMAC from the pairing secret), rate limited, with states pending, sent, failed, unconfirmed.
- Inbound SMS command from the trusted number (for example locate) only if it carries a valid HMAC and counter; otherwise ignore it.
- SIM-change and low-battery events: send a report to the trusted number and queue it for the backend.
- Boot resume and OEM battery-optimization guidance, with a status screen showing what is actually running.
- Stolen mode: frequent reports within battery limits, a visible recovery screen with the owner's contact, evidence queued for sync.
- Intruder photo after failed unlock: research first (device-admin failed-password callback, camera and background restrictions). Mark it experimental; ship only if it works on the test device.
- Privacy screen: permissions, data export, account removal.

### 5. Owner Admin (owner's phone)

- Strong sign-in (passkey) plus trusted-device pairing; revoke lost admin phones.
- Device list and map with last-known location, timestamp and accuracy; label each as live, delayed or unavailable.
- Allowlisted commands only: locate, ring, mark stolen, lock (supported mechanism), sync vault, revoke device, report status. Every command has an ID, target, expiry, signature and replay protection; show queued, delivered and executed as separate states.
- Alerts, audit history, and owner notification for new devices and recovery-setting changes.
- Encrypted recovery vault (section 7). Optional contacts backup, encrypted on the client, with restore.

### 6. Backend

FastAPI and PostgreSQL in Docker Compose. Only the tables the MVP needs: users, trusted devices, protected devices, capabilities, location events, commands, security events, vault objects, audit events. Ownership checks on every device endpoint, TLS, payload validation, rate limits, short-lived tokens, a location retention limit, account deletion. FCM is a delivery hint only; commands live in a durable queue. Device-reported security state is untrusted.

### 7. Recovery vault

Store recovery codes, IMEI and serial details, purchase receipts and insurance documents the owner selects. Do not build a general password manager; advise using one and store only its recovery material. Encrypt on the client with a vault key separate from login credentials, protected by Keystore. Document key loss, rotation, export and deletion. No plaintext secrets in logs. Call it end-to-end encrypted only after it is implemented and tested. The admin phone can also be stolen, so require biometric or PIN to open the vault.

### 8. Distribution decision (Phase 1)

SMS permissions are restricted on Google Play, and background location needs a core-function justification. Decide and document: a direct APK for personal and friends-and-family use with SMS enabled, or a Play build without SMS features. Use build flavors if both are wanted. Do not work around store policy.

### 9. Phases with stop gates

1. Inspect and research. Inspect the repo, SDKs and attached device (adb: model, Android version). Write the capability matrix, threat model, and SMS and device-owner feasibility. Stop and report.
2. Vertical slice: pair, capture, store, sync, show last-known location. Test offline and reconnect. Stop and report.
3. Recovery: commands, stolen mode, ring and lock, SMS fallback, SIM and low-battery events. Stop and report.
4. Vault and hardening: vault, revocation, logging review, threat-model tests, optional contacts backup.
5. Device-owner module and hardware tests, per model.
6. Release readiness: builds, tests, dependency checks, docs, risk list.

### 10. Rules

- Inspect before editing; keep existing work; no duplicate projects.
- Do not invent APIs, permissions or versions; cite the documentation page for every capability claim.
- No mocks unless labelled; no test reported as passed unless executed; keep automated results separate from hardware-pending tests.
- No hidden installs, covert audio or camera, credential harvesting, accessibility-service abuse, arbitrary remote shell, wipe commands or security bypasses.
- No AI, analytics, ads, satellite or crowd-network work in the MVP.
- Secrets stay out of code, logs and Git. Pin dependencies and say why.
- Ask before paid services, deployment or destructive changes.

### 11. Deliverables

Source for both apps and the backend, migrations, env template, Docker Compose, a capability matrix with measured results per tested device, threat model, permissions and privacy doc, build instructions, test report, and a plain list of what works, what does not and what is next.

## Answer before starting

1. Which phone and Android version is the test device?
2. Sideload only, or Google Play too?
3. May the agent provision device-owner mode on that test phone? It needs a phone with no accounts, so a spare or freshly reset one.
4. Where will the backend run during testing: your PC or a cloud host?
