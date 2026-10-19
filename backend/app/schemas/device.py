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


