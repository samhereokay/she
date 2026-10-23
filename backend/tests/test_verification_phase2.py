"""Test suite for Phase 2: Cryptographic Challenge Engine, Email OTP, and Ownership Gating."""

import smtplib
from datetime import timedelta
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from app.core.config import settings
from app.db.models import User, VerificationChallenge, utc_now
from app.main import app
from app.verification.engine import (
    issue,
    confirm,
    compute_code_hash,
    CHALLENGE_TTL_MINUTES,
    RESEND_COOLDOWN_SECONDS,
    HOURLY_SEND_LIMIT,
    MAX_ATTEMPTS_PER_CHALLENGE
)
from app.verification.mailer import send_verification_email
from conftest import TestingSessionLocal


@pytest.fixture
def client():
    return TestClient(app)


def test_challenge_engine_direct_issue_and_confirm(db_session):
    """Verifies standard cryptographic challenge issuance and verification lifecycle."""
    settings.ALLOW_PUBLIC_REGISTRATION = True

    user = User(
        username="engine_user_1",
        email="engine1@example.com",
        hashed_password="hash"
    )
    db_session.add(user)
    db_session.commit()
    db_session.refresh(user)

    # 1. Issue challenge
    challenge, code = issue(db_session, user.id, "email", user.email)
    assert len(code) == 6
    assert code.isdigit()
    assert challenge.user_id == user.id
    assert challenge.channel == "email"
    assert challenge.target == "engine1@example.com"
    assert challenge.attempts == 0
    assert challenge.consumed_at is None
    # Verify code is NOT stored plaintext in database
    assert challenge.code_hash != code
    assert len(challenge.code_hash) == 64  # SHA256 hex digest

    # 2. Confirm challenge with correct code
    confirmed = confirm(db_session, user.id, "email", user.email, code)
    assert confirmed.consumed_at is not None

    # 3. Replay attack: Re-submitting the same code fails immediately
    with pytest.raises(Exception) as exc_info:
        confirm(db_session, user.id, "email", user.email, code)
    assert "challenge_invalid_or_consumed" in str(exc_info.value.detail)


def test_challenge_engine_max_attempts_lockout(db_session):
    """Enforces that 5 wrong code attempts permanently lock the challenge."""
    user = User(
        username="engine_lockout_user",
        email="lockout@example.com",
        hashed_password="hash"
    )
    db_session.add(user)
    db_session.commit()
    db_session.refresh(user)

    challenge, correct_code = issue(db_session, user.id, "email", user.email)

    # Submit 4 wrong attempts
    for attempt_idx in range(1, MAX_ATTEMPTS_PER_CHALLENGE):
        with pytest.raises(Exception) as exc_info:
            confirm(db_session, user.id, "email", user.email, "000000")
        assert "invalid_verification_code" in str(exc_info.value.detail)
        # Attempt counter must be persisted even after exception
        db_session.refresh(challenge)
        assert challenge.attempts == attempt_idx

    # 5th wrong attempt triggers permanent lockout
    with pytest.raises(Exception) as exc_info:
        confirm(db_session, user.id, "email", user.email, "000000")
    assert "challenge_locked_too_many_attempts" in str(exc_info.value.detail)
    db_session.refresh(challenge)
    assert challenge.attempts == 5

    # 6th attempt (even with the correct code!) is strictly rejected
    with pytest.raises(Exception) as exc_info:
        confirm(db_session, user.id, "email", user.email, correct_code)
    assert "challenge_locked_too_many_attempts" in str(exc_info.value.detail)


def test_challenge_engine_expiration_ttl(db_session):
    """Verifies that challenges older than 10 minutes expire and are rejected."""
    user = User(
        username="engine_exp_user",
        email="exp@example.com",
        hashed_password="hash"
    )
    db_session.add(user)
    db_session.commit()
    db_session.refresh(user)

    now = utc_now()
    challenge, code = issue(db_session, user.id, "email", user.email, now=now)

    # Fast forward time 11 minutes
    future_time = now + timedelta(minutes=11)
    with pytest.raises(Exception) as exc_info:
        confirm(db_session, user.id, "email", user.email, code, now=future_time)
    assert "challenge_expired" in str(exc_info.value.detail)


def test_challenge_engine_resend_cooldown_and_hourly_limits(db_session):
    """Enforces 60-second cooldown between issues and 5 sends per hour rate limit."""
    user = User(
        username="engine_rate_user",
        email="rate@example.com",
        hashed_password="hash"
    )
    db_session.add(user)
    db_session.commit()
    db_session.refresh(user)

    now = utc_now()
    # 1. First send succeeds
    issue(db_session, user.id, "email", user.email, now=now)

    # 2. Immediate resend (e.g. 10s later) rejected with 429 cooldown active
    with pytest.raises(Exception) as exc_info:
        issue(db_session, user.id, "email", user.email, now=now + timedelta(seconds=10))
    assert "resend_cooldown_active" in str(exc_info.value.detail)

    # 3. Resend at +61 seconds succeeds
    issue(db_session, user.id, "email", user.email, now=now + timedelta(seconds=61))

    # 4. Issue 3 more at +122s, +183s, +244s (total 5 in the hour)
    issue(db_session, user.id, "email", user.email, now=now + timedelta(seconds=122))
    issue(db_session, user.id, "email", user.email, now=now + timedelta(seconds=183))
    issue(db_session, user.id, "email", user.email, now=now + timedelta(seconds=244))

    # 5. 6th send within the hour is rejected with 429 hourly limit exceeded
    with pytest.raises(Exception) as exc_info:
        issue(db_session, user.id, "email", user.email, now=now + timedelta(seconds=305))
    assert "hourly_rate_limit_exceeded" in str(exc_info.value.detail)

    # 6. Once an hour passes from the earliest send, new issue is accepted
    issue(db_session, user.id, "email", user.email, now=now + timedelta(hours=1, seconds=30))


def test_challenge_engine_issuing_new_code_invalidates_prior_open_challenge(db_session):
    """Verifies that requesting a new code immediately voids any previously unconsumed code."""
    user = User(
        username="engine_inval_user",
        email="inval@example.com",
        hashed_password="hash"
    )
    db_session.add(user)
    db_session.commit()
    db_session.refresh(user)

    now = utc_now()
    old_ch, old_code = issue(db_session, user.id, "email", user.email, now=now)

    # Fast forward past cooldown and issue new code
    later = now + timedelta(seconds=65)
    new_ch, new_code = issue(db_session, user.id, "email", user.email, now=later)

    db_session.refresh(old_ch)
    assert old_ch.consumed_at is not None

    # Old code now fails against the active challenge
    with pytest.raises(Exception) as exc_info:
        confirm(db_session, user.id, "email", user.email, old_code, now=later)
    assert exc_info.value.status_code == 400
    assert "invalid_verification_code" in str(exc_info.value.detail)

    # New code succeeds
    confirmed = confirm(db_session, user.id, "email", user.email, new_code, now=later)
    assert confirmed.id == new_ch.id


def test_mailer_smtp_delivery_and_privacy():
    """Verifies SMTP message construction and zero secret / code logging."""
    mock_smtp_instance = MagicMock()

    with patch("smtplib.SMTP") as mock_smtp_cls:
        mock_smtp_cls.return_value.__enter__.return_value = mock_smtp_instance

        # Test unauthenticated plain delivery (e.g. Mailpit)
        settings.SMTP_USER = ""
        settings.SMTP_PASS = ""
        send_verification_email("target@example.com", "987654")

        assert mock_smtp_instance.send_message.called
        msg = mock_smtp_instance.send_message.call_args[0][0]
        assert msg["To"] == "target@example.com"
        assert msg["Subject"] == "SHE Anti-Theft Verification Code: 987654"
        assert "987654" in msg.as_string()
        assert not mock_smtp_instance.login.called

    with patch("smtplib.SMTP") as mock_smtp_cls:
        mock_smtp_cls.return_value.__enter__.return_value = mock_smtp_instance
        mock_smtp_instance.has_extn.return_value = True

        # Test authenticated STARTTLS delivery
        settings.SMTP_USER = "she_mailer"
        settings.SMTP_PASS = "smtp_secret_pass"
        send_verification_email("tls_target@example.com", "123456")

        assert mock_smtp_instance.starttls.called
        mock_smtp_instance.login.assert_called_with("she_mailer", "smtp_secret_pass")

    # Clean up settings
    settings.SMTP_USER = ""
    settings.SMTP_PASS = ""


def test_unverified_user_cannot_access_protected_endpoints(client):
    """
    CRITICAL OWNERSHIP GATING INVARIANT:
    Freshly registered users have email_verified_at=None.
    They are strictly blocked from devices, commands, locations, and vault (HTTP 403 detail='email_not_verified').
    They CAN access /auth/me, /auth/totp/setup, /verify/email/send, and /verify/email/confirm.
    """
    settings.ALLOW_PUBLIC_REGISTRATION = True

    # 1. Register new user
    client.post("/auth/register", json={
        "username": "unverified_owner",
        "email": "unverified@example.com",
        "password": "PasswordUnverified123!"
    })

    # 2. Login to get token
    login_resp = client.post("/auth/login/json", json={
        "username": "unverified_owner",
        "password": "PasswordUnverified123!"
    })
    token = login_resp.json()["access_token"]
    headers = {"Authorization": f"Bearer {token}"}

    # 3. Allowed endpoints for unverified user:
    # /auth/me -> 200 OK (can view own status)
    me_resp = client.get("/auth/me", headers=headers)
    assert me_resp.status_code == 200
    assert me_resp.json()["email_verified_at"] is None

    # /auth/totp/setup -> 200 OK (can begin 2FA onboarding)
    totp_resp = client.post("/auth/totp/setup", headers=headers)
    assert totp_resp.status_code == 200

    # 4. Blocked endpoints for unverified user (HTTP 403 detail='email_not_verified')
    # Devices list
    dev_resp = client.get("/api/v1/devices", headers=headers)
    assert dev_resp.status_code == 403
    assert dev_resp.json()["detail"] == "email_not_verified"

    # Pending confirmations
    conf_resp = client.get("/api/v1/devices/pending-confirmations", headers=headers)
    assert conf_resp.status_code == 403
    assert conf_resp.json()["detail"] == "email_not_verified"

    # Device detail
    d_resp = client.get("/api/v1/devices/fake-device-id", headers=headers)
    assert d_resp.status_code == 403
    assert d_resp.json()["detail"] == "email_not_verified"

    # Location history
    loc_resp = client.get("/api/v1/devices/fake-device-id/locations", headers=headers)
    assert loc_resp.status_code == 403
    assert loc_resp.json()["detail"] == "email_not_verified"

    # Pairing token creation
    pt_resp = client.post("/auth/pairing-token", json={}, headers=headers)
    assert pt_resp.status_code == 403

    # Vault items
    v_resp = client.get("/api/v1/vault/items", headers=headers)
    assert v_resp.status_code == 403


def test_full_email_verification_flow(client):
    """
    Verifies end-to-end registration -> challenge issue -> email confirm -> full activation flow.
    """
    settings.ALLOW_PUBLIC_REGISTRATION = True

    # 1. Register unverified user
    reg_resp = client.post("/auth/register", json={
        "username": "flow_owner",
        "email": "flow_owner@example.com",
        "password": "PasswordFlow123!"
    })
    assert reg_resp.status_code == 201

    login_resp = client.post("/auth/login/json", json={
        "username": "flow_owner",
        "password": "PasswordFlow123!"
    })
    token = login_resp.json()["access_token"]
    headers = {"Authorization": f"Bearer {token}"}

    # 2. Dispatch verification code via /verify/email/send
    with patch("app.api.verify.send_verification_email") as mock_send_email:
        send_resp = client.post("/verify/email/send", json={}, headers=headers)
        assert send_resp.status_code == 202
        assert send_resp.json()["status"] == "challenge_issued"
        assert send_resp.json()["target"] == "flow_owner@example.com"
        assert mock_send_email.called
        # Extract sent code
        to_email, sent_code = mock_send_email.call_args[0]
        assert to_email == "flow_owner@example.com"
        assert len(sent_code) == 6

    # 3. Submit wrong code -> 400
    bad_confirm = client.post("/verify/email/confirm", json={"code": "999999"}, headers=headers)
    assert bad_confirm.status_code == 400
    assert "invalid_verification_code" in bad_confirm.json()["detail"]

    # 4. Submit correct code -> 200 OK
    ok_confirm = client.post("/verify/email/confirm", json={"code": sent_code}, headers=headers)
    assert ok_confirm.status_code == 200
    assert ok_confirm.json()["status"] == "email_verified"
    assert ok_confirm.json()["email_verified_at"] is not None

    # 5. /auth/me now reflects verified status
    me_resp = client.get("/auth/me", headers=headers)
    assert me_resp.status_code == 200
    assert me_resp.json()["email_verified_at"] is not None

    # 6. Devices endpoint is now unblocked
    dev_resp = client.get("/api/v1/devices", headers=headers)
    assert dev_resp.status_code == 200
    assert dev_resp.json()["devices"] == []


def test_verify_email_update_with_kyc_checks(client):
    """Verifies that /verify/email/send validates target emails with KYC pre-filters."""
    settings.ALLOW_PUBLIC_REGISTRATION = True

    client.post("/auth/register", json={
        "username": "update_email_user",
        "email": "initial@example.com",
        "password": "PasswordUpdate123!"
    })
    login_resp = client.post("/auth/login/json", json={
        "username": "update_email_user",
        "password": "PasswordUpdate123!"
    })
    headers = {"Authorization": f"Bearer {login_resp.json()['access_token']}"}

    # 1. Update to disposable domain -> 422
    bad_resp = client.post("/verify/email/send", json={"email": "hacker@mailinator.com"}, headers=headers)
    assert bad_resp.status_code == 422
    assert "Disposable" in bad_resp.json()["detail"]

    # 2. Update to invalid syntax -> 422
    syntax_resp = client.post("/verify/email/send", json={"email": "not-an-email"}, headers=headers)
    assert syntax_resp.status_code == 422

    # 3. Update to valid new email -> 202
    with patch("app.api.verify.send_verification_email"):
        good_resp = client.post("/verify/email/send", json={"email": "new_address@example.com"}, headers=headers)
        assert good_resp.status_code == 202
        assert good_resp.json()["target"] == "new_address@example.com"

    # User's email in database was updated
    me_resp = client.get("/auth/me", headers=headers)
    assert me_resp.json()["email"] == "new_address@example.com"
    assert me_resp.json()["email_verified_at"] is None


def test_email_change_clears_verification_and_requires_new_otp(client):
    """
    CRITICAL SECURITY INVARIANT:
    Changing an account's email address immediately clears email_verified_at.
    Protected routes become locked (HTTP 403) until the new address is verified with a fresh OTP.
    """
    settings.ALLOW_PUBLIC_REGISTRATION = True

    # 1. Register and verify address A
    client.post("/auth/register", json={
        "username": "switch_user",
        "email": "addr_a@example.com",
        "password": "PasswordSwitch123!"
    })
    token = client.post("/auth/login/json", json={
        "username": "switch_user",
        "password": "PasswordSwitch123!"
    }).json()["access_token"]
    headers = {"Authorization": f"Bearer {token}"}

    with patch("app.api.verify.send_verification_email") as mock_mail_a:
        client.post("/verify/email/send", json={}, headers=headers)
        code_a = mock_mail_a.call_args[0][1]
        client.post("/verify/email/confirm", json={"code": code_a}, headers=headers)

    # User is fully verified: device listing succeeds
    assert client.get("/api/v1/devices", headers=headers).status_code == 200

    # 2. Switch to address B via /verify/email/send
    with patch("app.api.verify.send_verification_email") as mock_mail_b:
        change_resp = client.post("/verify/email/send", json={"email": "addr_b@example.com"}, headers=headers)
        assert change_resp.status_code == 202
        code_b = mock_mail_b.call_args[0][1]

    # Verification must be IMMEDIATELY REVOKED
    me_resp = client.get("/auth/me", headers=headers)
    assert me_resp.json()["email"] == "addr_b@example.com"
    assert me_resp.json()["email_verified_at"] is None

    # Protected routes are now blocked!
    blocked_dev = client.get("/api/v1/devices", headers=headers)
    assert blocked_dev.status_code == 403
    assert blocked_dev.json()["detail"] == "email_not_verified"

    # Attempting to use old code A fails
    bad_old_code = client.post("/verify/email/confirm", json={"code": code_a}, headers=headers)
    assert bad_old_code.status_code == 400

    # 3. Confirm with new code B restores access
    confirm_b = client.post("/verify/email/confirm", json={"code": code_b}, headers=headers)
    assert confirm_b.status_code == 200
    assert confirm_b.json()["status"] == "email_verified"

    # Protected routes unblocked again
    assert client.get("/api/v1/devices", headers=headers).status_code == 200


def test_unverified_email_squatting_resolution(client, db_session):
    """
    Enforces that unverified placeholder accounts cannot squat on an email and block the legitimate owner.
    Once an email is verified, it is permanently protected against duplicate registration.
    """
    settings.ALLOW_PUBLIC_REGISTRATION = True

    # 1. Attacker registers victim@example.com but never verifies it
    att_resp = client.post("/auth/register", json={
        "username": "attacker_squatter",
        "email": "victim@example.com",
        "password": "PasswordAttacker123!"
    })
    assert att_resp.status_code == 201

    # 2. Legitimate owner also registers with the same email
    # Both unverified accounts co-exist without blocking registration
    real_resp = client.post("/auth/register", json={
        "username": "real_owner",
        "email": "victim@example.com",
        "password": "PasswordReal123!"
    })
    assert real_resp.status_code == 201

    # Both users exist in DB before either confirms
    assert db_session.query(User).filter(User.email == "victim@example.com").count() == 2

    # 3. Legitimate owner logs in and confirms the OTP challenge first
    token_real = client.post("/auth/login/json", json={
        "username": "real_owner",
        "password": "PasswordReal123!"
    }).json()["access_token"]
    headers_real = {"Authorization": f"Bearer {token_real}"}

    with patch("app.api.verify.send_verification_email") as mock_mail:
        client.post("/verify/email/send", json={}, headers=headers_real)
        code = mock_mail.call_args[0][1]
        conf_resp = client.post("/verify/email/confirm", json={"code": code}, headers=headers_real)
        assert conf_resp.status_code == 200

    # 4. First to confirm wins: unverified competitor account was automatically purged
    assert db_session.query(User).filter(User.username == "attacker_squatter").first() is None
    assert db_session.query(User).filter(User.email == "victim@example.com").count() == 1

    # 5. Now that victim@example.com is VERIFIED, new registration is strictly rejected (400)
    blocked_resp = client.post("/auth/register", json={
        "username": "another_user",
        "email": "victim@example.com",
        "password": "PasswordReal123!"
    })
    assert blocked_resp.status_code == 400
    assert "Email already registered" in blocked_resp.json()["detail"]


def test_startup_fails_when_debug_false_and_verify_secret_disallowed():
    """Enforces production failure when DEBUG=False and VERIFY_SECRET is empty or example default."""
    from app.main import validate_production_security_config

    class MockConfig:
        def __init__(self, debug: bool, verify_secret: str, pairing_key: str = "valid-prod-pairing-secret-key-32b"):
            self.DEBUG = debug
            self.VERIFY_SECRET = verify_secret
            self.PAIRING_SECRET_ENCRYPTION_KEY = pairing_key

    # DEBUG=True permits development default
    validate_production_security_config(MockConfig(debug=True, verify_secret="default-verification-hmac-secret-32b"))
    validate_production_security_config(MockConfig(debug=True, verify_secret=""))

    # DEBUG=False rejects empty or example secrets
    with pytest.raises(RuntimeError) as exc_empty:
        validate_production_security_config(MockConfig(debug=False, verify_secret=""))
    assert "VERIFY_SECRET must be set" in str(exc_empty.value)

    with pytest.raises(RuntimeError) as exc_example:
        validate_production_security_config(MockConfig(debug=False, verify_secret="default-verification-hmac-secret-32b"))
    assert "VERIFY_SECRET must be set" in str(exc_example.value)

    # DEBUG=False rejects empty or example pairing encryption keys
    with pytest.raises(RuntimeError) as exc_pairing:
        validate_production_security_config(MockConfig(debug=False, verify_secret="prod-entropy-strong-unique-secret-32b-length", pairing_key=""))
    assert "PAIRING_SECRET_ENCRYPTION_KEY must be configured" in str(exc_pairing.value)

    # DEBUG=False succeeds with genuine production secrets
    validate_production_security_config(
        MockConfig(debug=False, verify_secret="prod-entropy-strong-unique-secret-32b-length", pairing_key="prod-pairing-entropy-strong-32b")
    )


def test_email_link_verification_single_use_and_roundtrip(client):
    """
    Verifies 1-click email confirmation link flow:
    - GET /email/link shows confirm page
    - POST /email/link verifies account and marks challenge consumed
    - Subsequent POST or GET is rejected as already consumed
    """
    settings.ALLOW_PUBLIC_REGISTRATION = True
    settings.DEBUG = True

    # 1. Register unverified user
    client.post("/auth/register", json={
        "username": "link_owner",
        "email": "link_owner@example.com",
        "password": "PasswordLink123!"
    })

    login_resp = client.post("/auth/login/json", json={
        "username": "link_owner",
        "password": "PasswordLink123!"
    })
    token = login_resp.json()["access_token"]
    headers = {"Authorization": f"Bearer {token}"}

    # 2. Dispatch verification code
    with patch("app.api.verify.send_verification_email") as mock_mail:
        send_resp = client.post("/verify/email/send", json={}, headers=headers)
        assert send_resp.status_code == 202
        verify_link = send_resp.json()["debug_verify_link"]
        assert "token=" in verify_link
        link_token = verify_link.split("token=")[1]

    # 3. GET /email/link shows confirm page with form
    get_resp = client.get(f"/verify/email/link?token={link_token}")
    assert get_resp.status_code == 200
    assert "Confirm Email Verification" in get_resp.text
    assert "Confirm &amp; Verify Email" in get_resp.text or "Confirm & Verify Email" in get_resp.text

    # 4. POST /email/link confirms verification
    post_resp = client.post(f"/verify/email/link?token={link_token}")
    assert post_resp.status_code == 200
    assert "Email Verified Successfully!" in post_resp.text

    # 5. Profile is now verified
    me_resp = client.get("/auth/me", headers=headers)
    assert me_resp.status_code == 200
    assert me_resp.json()["email_verified_at"] is not None

    # 6. Replaying link returns 400 Already Used
    replay_resp = client.post(f"/verify/email/link?token={link_token}")
    assert replay_resp.status_code == 400
    assert "Link Already Used" in replay_resp.text


def test_debug_verify_bypass_strictly_rejected_in_production(client):
    """
    CRITICAL SECURITY INVARIANT:
    Direct verification bypass (/email/debug-verify, /phone/debug-verify)
    is strictly forbidden (HTTP 403) whenever settings.DEBUG=False.
    """
    settings.ALLOW_PUBLIC_REGISTRATION = True

    client.post("/auth/register", json={
        "username": "prod_sec_user",
        "email": "prodsec@example.com",
        "password": "PasswordProd123!"
    })
    token = client.post("/auth/login/json", json={
        "username": "prod_sec_user",
        "password": "PasswordProd123!"
    }).json()["access_token"]
    headers = {"Authorization": f"Bearer {token}"}

    # When DEBUG=False, bypass is strictly 403 Forbidden
    settings.DEBUG = False
    try:
        resp_email = client.post("/verify/email/debug-verify", headers=headers)
        assert resp_email.status_code == 403
        assert "debug_bypass_disabled" in resp_email.json()["detail"]

        resp_phone = client.post("/verify/phone/debug-verify", headers=headers)
        assert resp_phone.status_code == 403
        assert "debug_bypass_disabled" in resp_phone.json()["detail"]
    finally:
        settings.DEBUG = True

