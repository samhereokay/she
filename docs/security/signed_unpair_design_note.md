# Signed UNPAIR Lifecycle & Handset Unpairing Architecture

## 1. Overview & Threat Model

In SHE, an attacker who acquires physical possession of a protected Guardian handset must **not** be able to disconnect or unpair the device by interacting with its user interface or issuing unauthenticated commands. Unpairing disarms tracking and recovery protections. Therefore:

1. **No local UI unpair:** The Guardian Android client contains no button, intent, or interface to unpair itself or remove the device owner credentials locally.
2. **Signed UNPAIR command required:** The Guardian will only wipe its pairing state upon receiving an authentic `UNPAIR` command cryptographically signed by the registered Admin's ECDSA P-256 private key (stored in the Admin device hardware Keystore).
3. **Owner authentication required:** Triggering the server to queue an `UNPAIR` command or revoking a device requires the owner to supply a valid 2FA TOTP code.

---

## 2. Command Pipeline: Nonce, Timestamp, and Anti-Replay

Every remote command dispatched to the Guardian—including `UNPAIR`—enforces strict anti-replay and expiration checks:

```
Admin Keystore (ECDSA P-256)
       │
       ▼ Canonical bytes:
       │  command_id + target_device_id + command_type + sequence_counter + expiry_epoch + parameters_json
       │
       ▼ Sign with Admin Private Key
       │
       ▼ Dispatch to Backend (authenticated via JWT + fresh TOTP)
       │
       ▼ Queued in PostgreSQL (status="PENDING")
       │
       ├─► Delivered via WebSocket / HTTPS polling
       └─► Fallback delivered via AT1 SMS payload if offline
```

### Handset Verification Checks (`CommandProcessor.kt`):
1. **Sequence Monotonicity (Anti-Replay):** The incoming `sequence_counter` must be strictly greater than the last successfully acknowledged sequence (`last_seq < seq`). Replayed commands are rejected and dropped.
2. **Expiration Epoch (Freshness Window):** The `expiry_epoch` timestamp must be strictly in the future relative to the device's clock (`now < expiry_epoch`). Expired commands are discarded.
3. **Cryptographic Signature Verification:** Canonical bytes are reconstructed on the handset and verified against the registered Admin's public key (`currentAdminKeyPem`). Any signature mismatch immediately aborts command processing.

---

## 3. Delivery Channels & Queued Retries

To guarantee reliable delivery even when a stolen phone has network connectivity cut:

- **Online Channel (Primary):** The command is queued in the `device_commands` table. It is immediately delivered via persistent WebSocket stream or picked up on the next periodic command poll (`TrackingForegroundService`).
- **Offline / SMS Channel (Fallback):** If the device does not acknowledge within the timeout window, the command is serialized into an AT1 binary SMS payload with HMAC-SHA256 authentication and dispatched directly to the handset.
- **Queued Retries:** The command status remains `PENDING` until the Guardian sends back a signed acknowledgment (`ack`) containing `status="EXECUTED"`. The server retries delivery across polling cycles until verified execution.

---

## 4. Execution & Secure State Wipe

Upon successful verification of the `UNPAIR` command on the handset:
1. Active foreground tracking services and location updates are halted.
2. Device owner / device admin policies are released.
3. Registered Admin public keys, pairing identifiers, and shared secrets stored in encrypted Direct Boot storage (`EncryptedSharedPreferences`) are wiped.
4. The Guardian handset resets its state to unconfigured/unpaired, allowing it to safely undergo new pairing.
