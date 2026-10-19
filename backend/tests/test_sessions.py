import pytest
from app.db.models import User, UserSession
from app.core.config import settings


def test_login_creates_session_and_populates_jti(client, db_session):
    """Verifies that logging in generates a UserSession and attaches JTI to JWT token."""
    settings.ALLOW_PUBLIC_REGISTRATION = True

    # 1. Register with device_info
    reg_resp = client.post("/auth/register", json={
        "username": "session_user_1",
        "email": "session1@example.com",
        "password": "Password123!@#",
        "device_info": "Motorola moto g32 (Android 14) [ID: a1b2c3d4]"
    })
    assert reg_resp.status_code == 201

    # 2. Login from registered phone
    login_resp = client.post("/auth/login/json", json={
        "username": "session_user_1",
        "password": "Password123!@#",
        "device_info": "Motorola moto g32 (Android 14) [ID: a1b2c3d4]"
    })
    assert login_resp.status_code == 200
    token = login_resp.json()["access_token"]
    assert token

    # Check session created in DB
    user = db_session.query(User).filter(User.username == "session_user_1").first()
    sessions = db_session.query(UserSession).filter(UserSession.user_id == user.id).all()
    assert len(sessions) == 1
    phone_session = sessions[0]
    assert phone_session.is_registered_device is True
    assert phone_session.revoked_at is None
    assert "moto g32" in phone_session.device_info


def test_remote_session_detection_and_listing(client, db_session):
    """Verifies that remote web login has is_registered_device=False and sessions list shows both."""
    settings.ALLOW_PUBLIC_REGISTRATION = True

    # 1. Register
    client.post("/auth/register", json={
        "username": "session_user_2",
        "email": "session2@example.com",
        "password": "Password123!@#",
        "device_info": "Google Pixel 8 (Android 14) [ID: pix8_dev]"
    })

    # 2. Login from registered device
    phone_token = client.post("/auth/login/json", json={
        "username": "session_user_2",
        "password": "Password123!@#",
        "device_info": "Google Pixel 8 (Android 14) [ID: pix8_dev]"
    }).json()["access_token"]

    # 3. Login from remote web emergency console
    web_token = client.post("/auth/login/json", json={
        "username": "session_user_2",
        "password": "Password123!@#",
        "device_info": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0"
    }).json()["access_token"]

    # 4. List sessions using phone_token
    list_resp = client.get("/auth/sessions", headers={"Authorization": f"Bearer {phone_token}"})
    assert list_resp.status_code == 200
    sess_list = list_resp.json()
    assert len(sess_list) == 2

    # Verify attributes
    registered_sess = next(s for s in sess_list if s["is_registered_device"] is True)
    remote_sess = next(s for s in sess_list if s["is_registered_device"] is False)

    assert "Pixel 8" in registered_sess["device_info"]
    assert registered_sess["is_current"] is True  # Caller was phone_token

    assert "Chrome" in remote_sess["device_info"]
    assert remote_sess["is_current"] is False


def test_revoke_other_sessions_preserves_registered_device(client):
    """
    Core user requirement:
    Terminates all active login sessions (e.g. web emergency console, borrowed devices)
    EXCEPT the primary registered device.
    """
    settings.ALLOW_PUBLIC_REGISTRATION = True

    # 1. Register with primary phone
    client.post("/auth/register", json={
        "username": "session_user_3",
        "email": "session3@example.com",
        "password": "Password123!@#",
        "device_info": "Samsung Galaxy S24 [ID: s24_primary]"
    })

    # Primary phone login
    phone_token = client.post("/auth/login/json", json={
        "username": "session_user_3",
        "password": "Password123!@#",
        "device_info": "Samsung Galaxy S24 [ID: s24_primary]"
    }).json()["access_token"]

    # Remote Web login 1
    web_token_1 = client.post("/auth/login/json", json={
        "username": "session_user_3",
        "password": "Password123!@#",
        "device_info": "Chrome on Linux (Emergency Console)"
    }).json()["access_token"]

    # Remote Web login 2 (Laptop)
    web_token_2 = client.post("/auth/login/json", json={
        "username": "session_user_3",
        "password": "Password123!@#",
        "device_info": "Safari on macOS"
    }).json()["access_token"]

    # Confirm both web tokens work before revocation
    assert client.get("/auth/sessions", headers={"Authorization": f"Bearer {web_token_1}"}).status_code == 200
    assert client.get("/auth/sessions", headers={"Authorization": f"Bearer {web_token_2}"}).status_code == 200
    assert client.get("/auth/sessions", headers={"Authorization": f"Bearer {phone_token}"}).status_code == 200

    # 2. Invoke revoke-others from primary phone
    revoke_resp = client.post("/auth/sessions/revoke-others", headers={"Authorization": f"Bearer {phone_token}"})
    assert revoke_resp.status_code == 200
    assert revoke_resp.json()["revoked_count"] == 2

    # 3. Web tokens are now revoked and rejected with 401
    resp_web_1 = client.get("/auth/sessions", headers={"Authorization": f"Bearer {web_token_1}"})
    assert resp_web_1.status_code == 401
    assert "session_revoked" in resp_web_1.json()["detail"]

    resp_web_2 = client.get("/auth/sessions", headers={"Authorization": f"Bearer {web_token_2}"})
    assert resp_web_2.status_code == 401
    assert "session_revoked" in resp_web_2.json()["detail"]

    # 4. Primary registered phone is PRESERVED and still works 100%
    resp_phone = client.get("/auth/sessions", headers={"Authorization": f"Bearer {phone_token}"})
    assert resp_phone.status_code == 200


def test_protect_registered_device_from_accidental_individual_revocation(client):
    """Attempting to revoke the registered device session without force=True is rejected."""
    settings.ALLOW_PUBLIC_REGISTRATION = True

    client.post("/auth/register", json={
        "username": "session_user_4",
        "email": "session4@example.com",
        "password": "Password123!@#",
        "device_info": "OnePlus 12 [ID: op12_reg]"
    })

    phone_token = client.post("/auth/login/json", json={
        "username": "session_user_4",
        "password": "Password123!@#",
        "device_info": "OnePlus 12 [ID: op12_reg]"
    }).json()["access_token"]

    sessions = client.get("/auth/sessions", headers={"Authorization": f"Bearer {phone_token}"}).json()
    reg_id = sessions[0]["id"]

    # Revoke without force -> 400
    bad_revoke = client.post(f"/auth/sessions/{reg_id}/revoke", headers={"Authorization": f"Bearer {phone_token}"})
    assert bad_revoke.status_code == 400
    assert "primary registered device" in bad_revoke.json()["detail"]

    # Revoke with force=True -> 200
    good_revoke = client.post(f"/auth/sessions/{reg_id}/revoke?force=true", headers={"Authorization": f"Bearer {phone_token}"})
    assert good_revoke.status_code == 200
