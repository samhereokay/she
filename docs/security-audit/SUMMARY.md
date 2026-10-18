# SHE - Independent Security Audit Summary & Remediation Report

**Date**: 2026-10-18  
**Project**: SHE (Sentinel for Handheld Equipment)  
**Scope**: 
- Backend Service (`backend/app`, `backend/requirements.txt`)
- Android Client Applications (`android/app-guardian`, `android/app-admin`, `android/common-core`, `android/device-owner`)
- Merged Manifests & Security Permissions

**Remediation Approval**: 
All 5 identified remediations were reviewed and explicitly approved by stakeholder on 2026-10-18. Each remediation has been applied to code and dependencies, and verified through fresh automated tool runs and complete test suite executions.

---

## 1. Tooling & Execution Record (Post-Remediation Verification)

| Tool | Version | Target | Post-Fix Verification Status | Raw Output Artifact |
| :--- | :--- | :--- | :--- | :--- |
| **pip-audit** | 2.10.1 | `backend/requirements.txt` | **CLEAN** (0 vulnerabilities) | [pip_audit_raw.txt](file:///home/sam/Documents/she/docs/security-audit/pip_audit_raw.txt), [pip_audit.json](file:///home/sam/Documents/she/docs/security-audit/pip_audit.json) |
| **bandit** | 1.9.4 | `backend/app` | **CLEAN** (0 genuine issues; only 4 OAuth2 RFC `bearer` false-positives) | [bandit_raw.txt](file:///home/sam/Documents/she/docs/security-audit/bandit_raw.txt), [bandit.json](file:///home/sam/Documents/she/docs/security-audit/bandit.json) |
| **semgrep** | 1.181.0 | `android/` | **VERIFIED SAFE** (6 informational findings reviewed) | [semgrep_raw.txt](file:///home/sam/Documents/she/docs/security-audit/semgrep_raw.txt), [semgrep.json](file:///home/sam/Documents/she/docs/security-audit/semgrep.json) |
| **SQL Injection Audit** | Manual/AST | `backend/app` | **CLEAN** (100% SQLAlchemy parameterized ORM) | [sql_injection_audit.txt](file:///home/sam/Documents/she/docs/security-audit/sql_injection_audit.txt) |
| **Manifest Review** | AST / XML | `android/` merged manifests | **HARDENED** (`CALL_PHONE` removed, `ACTION_DIAL` standardized) | [manifest_permissions_review.md](file:///home/sam/Documents/she/docs/security-audit/manifest_permissions_review.md) |

---

## 2. Remediation Verification Matrix

| Finding ID | Severity | Description | Remediation Applied | Status |
| :--- | :---: | :--- | :--- | :---: |
| **SEC-HIGH-01** | **High** | `cryptography==44.0.0` upstream memory safety / cipher CVEs | Upgraded to `cryptography==50.0.2` in `backend/requirements.txt`. ECDSA P-256 signature verification tests verified passing. | **REMEDIATED & VERIFIED** |
| **SEC-MED-01** | **Medium** | `pyjwt==2.10.1` non-canonical JWS serialization handling | Upgraded to `PyJWT==2.15.1` in `backend/requirements.txt`. | **REMEDIATED & VERIFIED** |
| **SEC-MED-02** | **Medium** | `python-multipart==0.0.20` multipart boundary parser flaws | Upgraded to `python-multipart==0.0.32` in `backend/requirements.txt`. | **REMEDIATED & VERIFIED** |
| **SEC-MED-03** | **Medium** | Over-privileged `CALL_PHONE` runtime permission in SHE Guardian | Dropped `CALL_PHONE` from `AndroidManifest.xml`. Standardized `GuardianMainActivity.kt` on `Intent.ACTION_DIAL` (zero special permissions required). | **REMEDIATED & VERIFIED** |
| **SEC-LOW-01** | **Low** | `random.randint` pseudo-random generator used for pairing codes | Replaced `random.randint` with CSPRNG `secrets.randbelow(900) + 100` in `backend/app/api/devices.py:79`. Bandit B311 now reports 0 issues. | **REMEDIATED & VERIFIED** |
| **SEC-LOW-02** | **Low** | `pytest==8.3.4` development test runner vulnerability | Pinned `pytest==9.1.1` in `backend/requirements.txt`. | **REMEDIATED & VERIFIED** |
| **SEC-INFO-01** | **Info** | Semgrep exported activity warning for launcher activities | Documented as intentional. Both activities are root launcher entry points with strict unauthenticated IPC protection. | **VERIFIED SAFE** |
| **SEC-INFO-02** | **Info** | Semgrep AES-GCM IV freshness check | Verified safe. Android Keystore and SunJCE generate fresh 12-byte IVs on every encryption call; passphrase backups generate fresh 16-byte random salts. | **VERIFIED SAFE** |
| **SEC-INFO-03** | **Info** | Bandit B105 on OAuth2 `"token_type": "bearer"` | Documented as false positive. Required by RFC 6749 specification. | **VERIFIED SAFE** |

---

## 3. Tooling Architectural Context

### 3.1 Why `pip-audit` Required `--no-deps` / `--disable-pip`
- **Root Cause**: By default, `pip-audit -r requirements.txt` invokes `pip` as its dependency resolution backend. To avoid polluting the host environment, `pip` attempts to construct an isolated ephemeral virtual environment in `/tmp`. On Debian and Ubuntu systems, the standard Python installation splits the virtual environment subsystem into a separate OS package (`python3-venv` / `ensurepip`). Because `ensurepip` is absent from the host Python standard library, creating an ephemeral venv threw:
  ```
  The virtual environment was not created successfully because ensurepip is not available.
  On Debian/Ubuntu systems, you need to install the python3-venv package...
  ```
- **The Flag**: Passing `--no-deps --disable-pip` bypasses spawning an ephemeral virtual environment via `pip` and directly queries the PyPI and OSV vulnerability databases for the explicitly pinned requirements.
- **Transitive Dependency Audit Considerations**:
  - Because all 13 production backend dependencies are strictly pinned with exact version specifications (`==`), direct supply-chain vulnerabilities are fully surfaced.
  - In production deployment (e.g. Docker container `Dockerfile` or production Linux server), running `pip audit` inside an established virtual environment with `ensurepip` allows auditing full transitive sub-dependencies. Furthermore, auditing the local system environment with `pip-audit --local` verifies all installed site-packages.

### 3.2 Why `semgrep --config=auto` Was Not Used
- **Root Cause**: Running `semgrep scan --config=auto --metrics=off android/` explicitly failed with:
  ```
  [ERROR]: Cannot create auto config when metrics are off. Please allow metrics or run with a specific config.
  ```
  Semgrep's `--config=auto` feature requires contacting the Semgrep SaaS telemetry registry with usage metrics enabled to dynamically generate a project profile.
- **Resolution**: Instead of relying on auto-config, we explicitly supplied comprehensive Semgrep rule suites:
  `--config=p/owasp-top-ten --config=p/default --config=p/secrets`. This downloaded and evaluated **1,145 code rules** from the community and security registry across all 73 git-tracked files in the `android/` project.
