# Anti-Theft & Handset Recovery Ecosystem

An open, high-assurance Android device protection and recovery suite built for anti-theft telemetry, out-of-band SMS control, Device Owner kiosk lockdown, and zero-knowledge encrypted vault storage.

---

## Architecture Overview

```
                                +-----------------------------+
                                |      FastAPI Backend        |
                                |  (PostgreSQL/SQLite, 2FA)   |
                                +--------------+--------------+
                                               |
                       HTTPS API / Reverse USB | Port 8000
                                               |
                 +-----------------------------+-----------------------------+
                 |                                                           |
                 v                                                           v
+----------------------------------+                       +----------------------------------+
|          app-guardian            |                       |            app-admin             |
|   (Target Protected Handset)     |                       |    (Owner Controller Handset)    |
|----------------------------------|                       |----------------------------------|
| • Direct Boot pre-unlock service |      Carrier SMS      | • Hardware Keystore signing key  |
| • Offline GPS telemetry queue    | <-------------------> | • Dual-passphrase AES-256 Vault  |
| • Signed PDU SMS command engine  |    (Out-of-Band)      | • 2FA TOTP authentication        |
| • Device Owner LockTask kiosk    |                       | • Remote command dispatching     |
+----------------------------------+                       +----------------------------------+
```

---

## Quick Start Runbook

To run the system locally on your PC and physical handset, refer to the step-by-step runbook:
👉 **[docs/HOW_TO_RUN.md](docs/HOW_TO_RUN.md)**

### Rapid Summary:
1. **Start Backend**:
   ```bash
   python3 -m uvicorn app.main:app --app-dir backend --host 0.0.0.0 --port 8000
   ```
2. **Initialize Owner**:
   ```bash
   PYTHONPATH=backend python3 -m app.cli.admin create-owner --username admin_owner --email admin@example.com --password "<CHOOSE_A_STRONG_PASSWORD>"
   ```
3. **Connect Phone via USB & Route Network**:
   ```bash
   adb reverse tcp:8000 tcp:8000
   ```
4. **Install Apps**:
   ```bash
   adb install -r android/app-guardian/build/outputs/apk/direct/debug/app-guardian-direct-debug.apk
   adb install -r android/app-admin/build/outputs/apk/debug/app-admin-debug.apk
   ```

---

## Test Suites & Verification

The ecosystem includes **109 unique automated tests** across all components:
- **Backend Tests (37 passed)**: `PYTHONPATH=backend pytest backend/tests`
- **Shared Core (33 passed)**: `./.tools/gradle-8.7/bin/gradle -p android :common-core:test`
- **Guardian Handset (22 passed)**: `./.tools/gradle-8.7/bin/gradle -p android :app-guardian:testDirectDebugUnitTest`
- **Admin Controller (8 passed)**: `./.tools/gradle-8.7/bin/gradle -p android :app-admin:testDebugUnitTest`
- **Device Owner Module (9 passed)**: `./.tools/gradle-8.7/bin/gradle -p android :device-owner:test`

Full audit history, remediation details, and state proofs:
👉 **[docs/phases_3_to_5_remediation_and_audit_report.md](docs/phases_3_to_5_remediation_and_audit_report.md)**

---

## Project Structure

```
.
├── android/
│   ├── app-admin/        # Owner controller application (Vault, pairing, remote commands)
│   ├── app-guardian/     # Protected device agent (GPS tracking, SMS receiver, Kiosk lock)
│   ├── common-core/      # Shared Kotlin crypto (P-256 ECDSA, AES-GCM, PBKDF2, SMS PDU)
│   └── device-owner/     # Dedicated Device Administration & LockTask management
├── backend/
│   ├── app/              # FastAPI application (Authentication, Devices, Commands, Vault)
│   ├── alembic/          # Database schema migrations
│   └── tests/            # Pytest test suite (Phase 3 security, replay, vault crypto)
├── docs/
│   ├── HOW_TO_RUN.md     # Hardware test protocol and operations runbook
│   ├── evidence/         # On-device screenshot artifacts from audit runs
│   └── ...               # Security architecture, threat models, and capability matrices
└── antitheft_complete_emulator_phase.bundle # Offline Git bundle backup
```