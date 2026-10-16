from datetime import datetime
from typing import Optional, List
from pydantic import BaseModel, Field, ConfigDict
from app.schemas.location import LocationResponse


class DevicePairRequest(BaseModel):
    """Enroll a new Guardian device using pairing parameters."""
    device_alias: str = Field(..., min_length=1, max_length=64)
    public_key_pem: str = Field(..., description="Guardian ECDSA P-256 public key in PEM format")
    trusted_phone_number: str = Field(..., min_length=5, max_length=32)
    pairing_secret: str = Field(..., min_length=16, description="Shared symmetric secret from QR exchange")


class DeviceResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    user_id: str
    device_alias: str
    public_key_pem: str
    trusted_phone_number: str
    status: str
    created_at: datetime
    last_location: Optional[LocationResponse] = None
    calculated_status: str = "UNAVAILABLE"  # LIVE, DELAYED, UNAVAILABLE


class DeviceListResponse(BaseModel):
    devices: List[DeviceResponse]


class QRPairingPayload(BaseModel):
    """Payload serialized into QR code for Guardian phone to scan."""
    server_url: str
    admin_user_id: str
    pairing_token: str
    pairing_secret_hex: str
    trusted_phone_number: str
