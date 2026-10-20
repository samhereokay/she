# SHE Anti-Theft: Certificate Pinning & Key Rotation Runbook

## 1. Overview & Threat Model

To protect mobile apps (SHE Guardian and SHE Admin) against rogue Certificate Authorities (CAs),
state-level interception, and malicious Wi-Fi MITM proxies, release builds enforce HTTP Public Key
Pinning via Android's `network_security_config.xml`.

Cleartext HTTP traffic is unconditionally forbidden (`cleartextTrafficPermitted="false"`). Release
builds reject user-installed CA certificates and enforce Subject Public Key Info (SPKI) SHA-256
hashes against all connections to `she.up.railway.app`.

**What pinning protects against:** a third-party CA issuing a fraudulent certificate for
`she.up.railway.app`, or a user-installed MITM proxy certificate being used to intercept traffic.

**What pinning does not protect against:** compromise of a pinned CA's private key itself, or
Railway switching their backend to a different sub-CA that happens to chain to a pinned root
(though the leaf-CA pin catches that case until it rotates).

---

## 2. Pinned Key Hierarchy

Six SPKI SHA-256 hashes are pinned across all four `network_security_config.xml` files. Pinning
is applied at `<domain includeSubdomains="true">she.up.railway.app</domain>` with
`expiration="2027-12-31"`.

| # | Name | Role | SPKI SHA-256 |
|---|------|------|-------------|
| 1 | Let's Encrypt YE2 | Intermediate CA (current issuer) | `s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=` |
| 2 | ISRG Root YE | Cross-signed intermediate | `sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=` |
| 3 | ISRG Root X2 | Let's Encrypt root (ECDSA) | `diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=` |
| 4 | ISRG Root X1 | Let's Encrypt root (RSA) | `C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=` |
| 5 | Google Trust Services GTS Root R1 | Railway alternate CA root | `hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc=` |
| 6 | DigiCert Global Root G2 | Railway alternate CA root | `i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY=` |

> **Why GTS Root R1 and DigiCert G2 are included — and the trade-off**
>
> Railway (the production host) has, in the past, changed which CA it uses for TLS termination.
> SHE is sideloaded (no Play Store auto-update), so if Railway switches from Let's Encrypt to
> a GTS- or DigiCert-issued certificate before users update the app, every release APK would
> immediately stop connecting and the device would be unrecoverable remotely — a permanent brick
> for stolen-device scenarios.
>
> To prevent that, GTS Root R1 and DigiCert G2 are included as backup root pins. If Railway
> ever presents a certificate chaining to one of those roots, the app still connects.
>
> **Trade-off:** pinning two additional CA families means a misissued certificate from Google
> Trust Services or DigiCert for `she.up.railway.app` would not be caught by the pin check alone.
> This is the same position as any app that can't guarantee which CA its cloud host will use. The
> mitigation is: (a) the intermediate CA pin (YE2) is the first line of defence for the current
> configuration; (b) CT log monitoring can still detect misissued certificates; (c) the expiry
> date forces a review if the app is not updated before 2027-12-31.
>
> **Why `expiration="2027-12-31"` and not forever:** the expiry is a conscious safety net. If the
> pins become stale (e.g. Let's Encrypt retires ISRG X1 and Railway moves to a CA outside the
> pinned set) and the app is never updated, pin enforcement would block all connections permanently
> on a sideloaded APK with no auto-update path. The expiry allows the app to fall back to system CA
> validation rather than bricking itself. This fails open — it does not destroy the anti-theft
> function — and a future app update can set a new expiry with the correct pin set.

---

## 3. Derivation Commands & Verified Output

All pins were derived from official CA certificate sources and verified against the live chain
and official PEM releases:

### 1. Let's Encrypt YE2 (current issuer intermediate)
```bash
# Extracted from live chain on she.up.railway.app (cert #1)
openssl s_client -connect she.up.railway.app:443 -showcerts </dev/null 2>/dev/null \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary | openssl enc -base64
# → s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=
```

### 2. ISRG Root YE (cross-signed intermediate)
```bash
# Extracted from live chain (cert #2)
# Subject: C=US, O=ISRG, CN=Root YE
# → sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=
```

### 3. ISRG Root X2 (cross-signed by Root X1)
```bash
# Extracted from live chain (cert #3) and https://letsencrypt.org/certs/isrg-root-x2.pem
# Subject: C=US, O=Internet Security Research Group, CN=ISRG Root X2
# → diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=
```

### 4. ISRG Root X1 (self-signed trust anchor)
```bash
# Source: https://letsencrypt.org/certs/isrgrootx1.pem
curl -s https://letsencrypt.org/certs/isrgrootx1.pem \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary | openssl enc -base64
# → C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=
```

### 5. GTS Root R1 (Google Trust Services alternate root)
```bash
# Source: https://pki.goog/roots.pem (GTS Root R1 block)
# Subject: CN=GTS Root R1, O=Google Trust Services LLC, C=US
# → hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc=
```

### 6. DigiCert Global Root G2 (DigiCert alternate root)
```bash
# Source: https://cacerts.digicert.com/DigiCertGlobalRootG2.crt.pem
curl -s https://cacerts.digicert.com/DigiCertGlobalRootG2.crt.pem \
  | openssl x509 -pubkey -noout \
  | openssl pkey -pubin -outform der \
  | openssl dgst -sha256 -binary | openssl enc -base64
# → i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY=
```

---

## 4. Emergency Rotation Procedure

When rotating TLS certificates or renewing with a new private key pair:

1. **Pre-generate the next key pair** at least 60 days before the existing certificate expires.
2. **Calculate the future SPKI pin** from the new CSR/public key using the `openssl` pipeline above.
3. **Add the new pin as a backup** `<pin>` tag in all four `network_security_config.xml` files (both apps, both `main` and `debug` variants).
4. **Release updated APKs** so the installed base trusts the upcoming key before it goes live.
5. **Swap the server certificate** on Caddy / Railway. Zero downtime occurs because the app already trusts both the old and new pins.
6. **Post-migration cleanup**: after 90 days remove the deprecated leaf pin in the next regular app update.

---

## 5. Development vs. Production Configuration

| Variant | File | Trust | Pins enforced |
|---------|------|-------|---------------|
| Release | `src/main/res/xml/network_security_config.xml` | System CAs only | Yes — expiry 2027-12-31 |
| Debug | `src/debug/res/xml/network_security_config.xml` | System + user CAs (`<debug-overrides>`) | Yes — same pin set |

The debug variant trusts user-installed CAs only through `<debug-overrides>`, which applies
exclusively to `debuggable="true"` builds. Release APKs (`debuggable="false"`) ignore this block
entirely; user certificates are silently rejected.

Note: the debug build still enforces the same pins. This means mitmproxy will fail with a
**pin verification error** on a debug build unless a throwaway `network_security_config.xml` that
removes the pins is used — and that throwaway file must never be committed.
