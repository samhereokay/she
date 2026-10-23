import pytest
from unittest.mock import patch
from tests.conftest import client


@pytest.fixture
def registered_user(client):
    # Register an owner user for testing
    reg_resp = client.post("/auth/register", json={
        "username": "forgot_user",
        "email": "forgot_user@example.com",
        "password": "OldPassword123!"
    })
    assert reg_resp.status_code == 201, reg_resp.text
    return reg_resp.json()


def test_forgot_password_unknown_user_returns_generic_success(client):
    """Anti-enumeration: Requesting reset for non-existent user returns generic success."""
    resp = client.post("/auth/forgot-password", json={"identifier": "nonexistent_user@example.com"})
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert "If an account exists" in data["message"]


@patch("app.api.auth.send_password_reset_email")
def test_forgot_password_by_email_success(mock_email, client, registered_user):
    """Requesting reset with valid email issues code and sends email."""
    resp = client.post("/auth/forgot-password", json={"identifier": "forgot_user@example.com"})
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert "6-digit" in data["message"]
    assert "f***r@example.com" == data["email_hint"]
    assert mock_email.called


@patch("app.api.auth.send_password_reset_email")
def test_forgot_password_by_username_success(mock_email, client, registered_user):
    """Requesting reset with valid username issues code and sends email."""
    resp = client.post("/auth/forgot-password", json={"identifier": "forgot_user"})
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert "f***r@example.com" == data["email_hint"]
    assert mock_email.called


@patch("app.api.auth.send_password_reset_email")
def test_reset_password_full_flow(mock_email, client, registered_user):
    """Verify reset code, change password, verify old password fails and new password succeeds."""
    # 1. Request reset
    req_resp = client.post("/auth/forgot-password", json={"identifier": "forgot_user"})
    assert req_resp.status_code == 200
    assert mock_email.called

    # Extract the code passed to mock_email
    call_args = mock_email.call_args
    code = call_args.kwargs.get("code") or call_args[1].get("code") if call_args[1] else call_args[0][1]

    # 2. Reset with wrong code fails
    fail_resp = client.post("/auth/reset-password", json={
        "identifier": "forgot_user",
        "code": "000000",
        "new_password": "BrandNewPassword123!"
    })
    assert fail_resp.status_code == 400

    # 3. Reset with short password fails Pydantic schema validation (min_length=8)
    short_resp = client.post("/auth/reset-password", json={
        "identifier": "forgot_user",
        "code": code,
        "new_password": "short"
    })
    assert short_resp.status_code == 422

    # 4. Reset with valid code succeeds
    success_resp = client.post("/auth/reset-password", json={
        "identifier": "forgot_user",
        "code": code,
        "new_password": "BrandNewPassword123!"
    })
    assert success_resp.status_code == 200
    assert "successfully reset" in success_resp.json()["message"]

    # 5. Old password fails
    old_login = client.post("/auth/login/json", json={
        "username": "forgot_user",
        "password": "OldPassword123!"
    })
    assert old_login.status_code == 401

    # 6. New password succeeds
    new_login = client.post("/auth/login/json", json={
        "username": "forgot_user",
        "password": "BrandNewPassword123!"
    })
    assert new_login.status_code == 200
    assert "access_token" in new_login.json()
