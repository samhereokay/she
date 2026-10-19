from datetime import datetime
from typing import Optional
from pydantic import BaseModel, Field, ConfigDict


class UserCreate(BaseModel):
    username: str = Field(..., min_length=3, max_length=64)
    email: str = Field(..., min_length=5, max_length=255, pattern=r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
    password: str = Field(..., min_length=8)
    registration_token: Optional[str] = Field(None, description="Optional verification/invite token required when registration verification is enabled")
    phone_number: Optional[str] = Field(None, description="Owner phone number for emergency contact and SMS beacons")
    device_info: Optional[str] = Field(None, description="Client hardware model and OS details")


class UserLogin(BaseModel):
    username: str
    password: str


class UserResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    username: str
    email: str
    phone_number: Optional[str] = None
    device_info: Optional[str] = None
    email_verified_at: Optional[datetime] = None
    fallback_phone_e164: Optional[str] = None
    phone_verified_at: Optional[datetime] = None
    created_at: datetime


class EmailSendRequest(BaseModel):
    email: Optional[str] = Field(None, description="Optional target email address, defaults to account email")


class EmailConfirmRequest(BaseModel):
    code: str = Field(..., min_length=6, max_length=6, description="6-digit verification code")


class EmailVerifyResponse(BaseModel):
    status: str
    email_verified_at: datetime


class Token(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in_minutes: int
    is_2fa: bool = False
    totp_required: bool = False
    recovery_codes: Optional[list[str]] = None


class TokenData(BaseModel):
    user_id: Optional[str] = None
    is_2fa: bool = False


class TotpSetupResponse(BaseModel):
    secret: str
    otpauth_uri: str


class TotpVerifyRequest(BaseModel):
    code: str = Field(..., min_length=6, max_length=9)


class TotpStatusResponse(BaseModel):
    totp_enabled: bool

