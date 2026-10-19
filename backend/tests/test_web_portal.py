import pytest
from fastapi.testclient import TestClient
from app.main import app


def test_landing_page_renders_with_pre_release_and_features():
    client = TestClient(app)
    response = client.get("/")
    assert response.status_code == 200
    assert "text/html" in response.headers["content-type"]
    html = response.text

    # Pre-release banner verification
    assert "Pre-Release / Hardware Verification in Progress" in html
    assert "Direct APK downloads are paused" in html

    # 9 architectural capabilities verification
    assert "1. Direct Boot Resumption" in html
    assert "2. Zero-Internet SMS Channel" in html
    assert "3. Zero-Knowledge Vault" in html
    assert "4. Device Owner Kiosk Lock" in html
    assert "5. Intruder Photo Capture" in html
    assert "6. 2FA &amp; Single-Use Recovery" in html or "6. 2FA & Single-Use Recovery" in html
    assert "7. Mutual Camera QR Pairing" in html
    assert "8. Indian ERSS 112 &amp; CEIR" in html or "8. Indian ERSS 112 & CEIR" in html
    assert "9. Zero-Telemetry Backend" in html

    # SMS security verification: AT1 PDU present, no false claims
    assert "AT1 Protocol Data Units" in html
    assert "48-bit truncated HMAC-SHA256" in html
    assert "CLEAR_DEVICE_OWNER" in html

    # Play Protect & OEM sideloading advisory verification
    assert "Blocked by Play Protect" in html
    assert "High Risk" in html
    assert "HyperOS" in html
    assert "adb install -r -g" in html

    # SMS Privacy & OTP Protection verification
    assert "Banking OTPs" in html
    assert "0 WebViews" in html
    assert "storage.getTrustedPhoneNumber()" in html


def test_debug_apk_downloads_gated_with_503():
    client = TestClient(app)

    # Guardian download
    resp_g = client.get("/download/guardian")
    assert resp_g.status_code == 503
    assert "Public binary downloads are disabled" in resp_g.json()["detail"]
    assert "assembleDirectRelease" in resp_g.json()["detail"]

    # Admin download
    resp_a = client.get("/download/admin")
    assert resp_a.status_code == 503
    assert "Public binary downloads are disabled" in resp_a.json()["detail"]
    assert "assembleRelease" in resp_a.json()["detail"]


def test_documentation_and_privacy_routes():
    client = TestClient(app)

    # User Guide
    resp_ug = client.get("/docs/user-guide")
    assert resp_ug.status_code == 200
    assert "User Guide" in resp_ug.text
    assert "sancharsaathi.gov.in" in resp_ug.text
    assert "Play Protect" in resp_ug.text
    assert "READ_SMS" in resp_ug.text

    # Setup Order
    resp_so = client.get("/docs/setup-order")
    assert resp_so.status_code == 200
    assert "Setup Order Runbook" in resp_so.text
    assert "dpm set-device-owner" in resp_so.text
    assert "Play Protect" in resp_so.text

    # Privacy Policy
    resp_p = client.get("/privacy")
    assert resp_p.status_code == 200
    assert "Data Privacy & Security Architecture" in resp_p.text
    assert "Zero Telemetry" in resp_p.text
    assert "30-day" in resp_p.text
    assert "Zero-Retention OTP Discard" in resp_p.text

    # Terms of Service
    resp_t = client.get("/terms")
    assert resp_t.status_code == 200
    assert "Terms of Service & Handset Defense Agreement" in resp_t.text
    assert "Permitted Anti-Theft" in resp_t.text
    assert "Zero Surveillance" in resp_t.text


def test_logo_asset_serves_png():
    client = TestClient(app)
    resp = client.get("/logo.png")
    assert resp.status_code == 200
    assert resp.headers["content-type"] == "image/png"


def test_releases_page_and_binary_downloads():
    client = TestClient(app)

    # Releases landing page
    resp_r = client.get("/releases")
    assert resp_r.status_code == 200
    assert "text/html" in resp_r.headers["content-type"]
    assert "Release Binaries (v0.3.0)" in resp_r.text
    assert "she-guardian.apk" in resp_r.text
    from app.api.web import get_file_sha256, GUARDIAN_APK_PATH, ADMIN_APK_PATH
    assert get_file_sha256(GUARDIAN_APK_PATH) in resp_r.text
    assert get_file_sha256(ADMIN_APK_PATH) in resp_r.text

    # Binary release downloads
    resp_g = client.get("/releases/download/she-guardian.apk")
    assert resp_g.status_code == 200
    assert "application/vnd.android.package-archive" in resp_g.headers["content-type"]
    assert len(resp_g.content) > 30_000_000

    resp_a = client.get("/releases/download/she-admin.apk")
    assert resp_a.status_code == 200
    assert "application/vnd.android.package-archive" in resp_a.headers["content-type"]
    assert len(resp_a.content) > 30_000_000


def test_2fa_and_recovery_guide_route():
    client = TestClient(app)
    resp = client.get("/docs/2fa-and-recovery")
    assert resp.status_code == 200
    assert "Two-Factor Authentication, Recovery Keys" in resp.text
    assert "otpauth://" in resp.text
    assert "Strict 1-Device-Per-User Restriction" in resp.text
    assert "Emergency Login from Someone Else" in resp.text
    assert "she.up.railway.app/emergency" in resp.text

    # Alias check
    resp_alias = client.get("/blog/setup-2fa-recovery")
    assert resp_alias.status_code == 200
    assert "Emergency Handset Tracking" in resp_alias.text


def test_emergency_console_route():
    client = TestClient(app)
    resp = client.get("/emergency")
    assert resp.status_code == 200
    assert "Emergency Web Console" in resp.text
    assert "Authenticate Handset Owner" in resp.text
    assert "Emergency Recovery Key" in resp.text
    assert "Sound Siren" in resp.text
    assert "mapFrame" in resp.text

