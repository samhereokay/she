from datetime import datetime
from typing import Optional, List
from pydantic import BaseModel, Field, ConfigDict
from app.schemas.location import LocationResponse


class DevicePairRequest(BaseModel):
    """Enroll a new Guardian device using pairing parameters."""
    device_id: Optional[str] = None
    device_alias: str = Field(..., min_length=1, max_length=64)
    public_key_pem: str = Field(..., description="Guardian ECDSA P-256 public key in PEM format")
    trusted_phone_number: str = Field(..., min_length=5, max_length=32)
    pairing_secret: str = Field(..., min_length=16, description="Shared symmetric secret from QR exchange")


class PairingTokenCreateRequest(BaseModel):
    trusted_device_id: Optional[str] = None


class PairingTokenResponse(BaseModel):
    pairing_token: str
    expires_in_seconds: int = 900


class PairingConfirmRequest(BaseModel):
    confirmation_code: str = Field(..., min_length=3, max_length=16)


class PairingConfirmResponse(BaseModel):
    device_id: str
    status: str
    pairing_id: str


class DeviceResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    user_id: str
    device_alias: str
    public_key_pem: str
    trusted_phone_number: str
    status: str
    pairing_id: Optional[str] = None
    confirmation_code: Optional[str] = None
    created_at: datetime
    last_location: Optional[LocationResponse] = None
    calculated_status: str = "UNAVAILABLE"  # LIVE, DELAYED, UNAVAILABLE


class DeviceListResponse(BaseModel):
    devices: List[DeviceResponse]


class QRPairingPayload(BaseModel):
    """Payload serialized into QR code for Guardian phone to scan."""
    server_url: str
    admin_public_key_pem: str
    pairing_token: str
    pairing_secret_hex: str
    trusted_phone_number: str


class TrustedDeviceRegisterRequest(BaseModel):
    device_name: str = Field(..., min_length=1, max_length=128)
    public_key_pem: str = Field(..., description="Admin device ECDSA P-256 public key PEM")


class TrustedDeviceResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    device_name: str
    public_key_pem: str
    created_at: datetime
    revoked_at: Optional[datetime] = None
    is_active: bool = True


class CommandDispatchRequest(BaseModel):
    command_id: Optional[str] = None
    pairing_id: Optional[str] = None
    trusted_device_id: str
    target_device_id: str
    command_type: str = Field(..., pattern=r"^(LOCATE|RING|STOP_RING|LOCK|STOLEN_MODE|EXIT_KIOSK|RECOVERED|UNPAIR|CLEAR_DEVICE_OWNER)$")
    sequence_counter: int = Field(..., ge=1)
    expiry_epoch: int = Field(..., description="Unix epoch timestamp when command expires")
    parameters_json: str = Field(default="{}", description="JSON string of command parameters")
    admin_signature: str = Field(..., description="Base64 ASN.1 DER signature from Admin device ECDSA key")


class CommandAckRequest(BaseModel):
    status: str = Field("EXECUTED", pattern=r"^(EXECUTED|REJECTED)$")
    reason: Optional[str] = "OK"
    signature: Optional[str] = None
    timestamp: Optional[int] = None


class CommandResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    pairing_id: Optional[str] = None
    trusted_device_id: Optional[str] = None
    protected_device_id: str
    command_type: str
    sequence_counter: int
    expiry_epoch: int
    parameters_json: str
    admin_signature: str
    status: str
    created_at: datetime
    executed_at: Optional[datetime] = None


class EmergencyCommandRequest(BaseModel):
    command_type: str = Field(..., description="Action to trigger: RING, STOP_RING, LOCK, STOLEN_MODE, LOST_MODE, LOCATE")
    payload: Optional[dict] = Field(default_factory=dict, description="Optional extra arguments (e.g. lock_message)")


class EmergencyCommandResponse(BaseModel):
    status: str
    command_id: str
    command_type: str
    sequence_counter: int


class PairingSessionCreateRequest(BaseModel):
    totp_code: str = Field(..., min_length=6, max_length=6, description="TOTP code from Owner Authenticator app")
    admin_device_id: Optional[str] = None


class PairingSessionResponse(BaseModel):
    session_id: str
    session_token: str
    short_code: str
    expires_in_seconds: int = 300


class GuardianPairRequest(BaseModel):
    """Enroll a new Guardian handset with full hardware identity & telemetry."""
    device_id: str = Field(..., min_length=16, max_length=64, description="App-generated UUID from device-protected storage")
    device_alias: str = Field(..., min_length=1, max_length=64)
    public_key_pem: str = Field(..., description="Guardian ECDSA P-256 public key in PEM format")
    trusted_phone_number: str = Field(..., min_length=5, max_length=32)
    pairing_secret: str = Field(..., min_length=16, description="Shared symmetric secret from ECDH / QR exchange")
    pairing_session_token: Optional[str] = None
    short_code: Optional[str] = None

    # Hardware specs & identity
    manufacturer: Optional[str] = None
    brand: Optional[str] = None
    model: Optional[str] = None
    android_version: Optional[str] = None
    sdk_level: Optional[int] = None

    # Guardian variant & lifecycle
    variant: str = Field("lite", pattern=r"^(lite|hardened)$")
    app_version: Optional[str] = None
    install_date: Optional[datetime] = None
    is_device_owner: bool = False

    # Status / Telemetry at enrollment
    battery_level: Optional[int] = Field(None, ge=0, le=100)
    connectivity_type: Optional[str] = None
    sim_operator: Optional[str] = None
    sim_country: Optional[str] = None
    sim_changed: bool = False
    permission_health: Optional[dict] = Field(default_factory=dict)


class DeviceRenameRequest(BaseModel):
    nickname: str = Field(..., min_length=1, max_length=64)


class DeviceHeartbeatRequest(BaseModel):
    battery_level: Optional[int] = Field(None, ge=0, le=100)
    connectivity_type: Optional[str] = None
    sim_operator: Optional[str] = None
    sim_country: Optional[str] = None
    sim_changed: bool = False
    permission_health: Optional[dict] = Field(default_factory=dict)
    timestamp: int = Field(..., description="Unix epoch timestamp in seconds")
    signature: str = Field(..., description="ECDSA signature by Guardian private key over canonical heartbeat payload")


class DeviceUnpairRequest(BaseModel):
    """Graceful unpair request dispatched from Admin."""
    totp_code: str = Field(..., min_length=6, max_length=6)
    trusted_device_id: Optional[str] = None
    admin_signature: str = Field(..., description="Admin ECDSA signature authorizing UNPAIR")
    sequence_counter: int = Field(..., ge=1)
    expiry_epoch: int = Field(..., description="Unix timestamp for command expiry")


class DeviceRevokeRequest(BaseModel):
    """Force remove / revoke device when phone is lost or stolen."""
    totp_code: str = Field(..., min_length=6, max_length=6)
    reason: Optional[str] = Field("LOST_OR_STOLEN", max_length=128)
    actor_admin_id: Optional[str] = None


class DeviceAuditLogResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    device_id: Optional[str] = None
    action: str
    actor_admin_id: Optional[str] = None
    details: Optional[str] = None
    created_at: datetime


class DeviceStatusResponse(BaseModel):
    battery_level: Optional[int] = None
    connectivity_type: Optional[str] = None
    sim_operator: Optional[str] = None
    sim_country: Optional[str] = None
    sim_changed: bool = False
    permission_health: dict = Field(default_factory=dict)
    last_seen: Optional[datetime] = None
    last_sync_path: str = "online"
    is_stale: bool = False


class DeviceDetailResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    user_id: str
    device_alias: str
    public_key_pem: str
    key_fingerprint: Optional[str] = None
    trusted_phone_number: str
    status: str  # ACTIVE, PENDING_CONFIRMATION, STOLEN, REVOKED
    calculated_status: str = "UNAVAILABLE"
    confirmation_code: Optional[str] = None
    pairing_id: Optional[str] = None

    # Hardware & identity
    manufacturer: Optional[str] = None
    brand: Optional[str] = None
    model: Optional[str] = None
    android_version: Optional[str] = None
    sdk_level: Optional[int] = None

    # Guardian variant & lifecycle
    variant: str = "lite"
    app_version: Optional[str] = None
    install_date: Optional[datetime] = None
    is_device_owner: bool = False
    is_possible_reinstall: bool = False

    # Status & Health
    status_info: Optional[DeviceStatusResponse] = None
    last_location: Optional[LocationResponse] = None
    created_at: datetime


class DeviceRosterResponse(BaseModel):
    devices: List[DeviceDetailResponse]
    total_count: int
    max_devices_limit: int = 5



