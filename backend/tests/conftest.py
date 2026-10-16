import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.main import app
from app.db.session import Base, get_db

SQLALCHEMY_DATABASE_URL = "sqlite:///:memory:"

engine = create_engine(
    SQLALCHEMY_DATABASE_URL,
    connect_args={"check_same_thread": False},
    poolclass=StaticPool,
)
TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


def override_get_db():
    db = TestingSessionLocal()
    try:
        yield db
    finally:
        db.close()


@pytest.fixture(autouse=True)
def reset_database():
    app.dependency_overrides[get_db] = override_get_db
    Base.metadata.create_all(bind=engine)
    yield
    Base.metadata.drop_all(bind=engine)
    app.dependency_overrides.clear()


@pytest.fixture
def client():
    return TestClient(app)


@pytest.fixture
def db_session():
    db = TestingSessionLocal()
    try:
        yield db
    finally:
        db.close()


def pair_device_helper(client, token, alias, pubkey, phone, secret, device_id=None):
    pt_resp = client.post("/auth/pairing-token", headers={"Authorization": f"Bearer {token}"}, json={})
    assert pt_resp.status_code == 200, pt_resp.text
    pt = pt_resp.json()["pairing_token"]
    pair_resp = client.post(
        "/api/v1/devices/pair",
        headers={"Authorization": f"Bearer {pt}"},
        json={
            "device_id": device_id,
            "device_alias": alias,
            "public_key_pem": pubkey,
            "trusted_phone_number": phone,
            "pairing_secret": secret
        }
    )
    assert pair_resp.status_code == 201, pair_resp.text
    data = pair_resp.json()
    code = data.get("confirmation_code")
    conf_resp = client.post(
        f"/api/v1/devices/{data['id']}/confirm-pairing",
        headers={"Authorization": f"Bearer {token}"},
        json={"confirmation_code": code}
    )
    assert conf_resp.status_code == 200, conf_resp.text
    return data["id"], conf_resp.json()["pairing_id"]


@pytest.fixture
def pair_device():
    return pair_device_helper

