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
    device_info: Optional[str] = Field(None, description="Client hardware model and OS details")


class UserSessionResponse(BaseModel):
    id: str
    device_info: Optional[str] = None
    ip_address: Optional[str] = None
    user_agent: Optional[str] = None
    is_registered_device: bool
    is_current: bool = False
    created_at: datetime
    last_active_at: datetime
    is_revoked: bool


class SessionRevokeResponse(BaseModel):
    status: str
    revoked_count: int
    message: str


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


class PhoneStartRequest(BaseModel):
    phone_number: str = Field(..., min_length=7, max_length=32, description="International mobile number in E.164 format")


class PhoneConfirmRequest(BaseModel):
    code: str = Field(..., min_length=6, max_length=6, description="6-digit SMS verification code")


class PhoneVerifyResponse(BaseModel):
    status: str
    fallback_phone_e164: str
    phone_verified_at: datetime


class Token(BaseModel):
    access_token: str
    token_type: str = "bearer"
    expires_in_minutes: int
    is_2fa: bool = False
    totp_required: bool = False
    refresh_token: Optional[str] = None
    recovery_codes: Optional[list[str]] = None


class RefreshTokenRequest(BaseModel):
    refresh_token: str = Field(..., min_length=16, description="Single-use rotating refresh token")


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


class ForgotPasswordRequest(BaseModel):
    identifier: str = Field(..., min_length=3, max_length=255, description="Registered email or username")


class ForgotPasswordResponse(BaseModel):
    status: str
    message: str
    email_hint: Optional[str] = None


class ResetPasswordRequest(BaseModel):
    identifier: str = Field(..., min_length=3, max_length=255, description="Registered email or username")
    code: str = Field(..., min_length=6, max_length=6, description="6-digit reset code received via email")
    new_password: str = Field(..., min_length=8, max_length=128, description="New secure password")


class ResetPasswordResponse(BaseModel):
    status: str
    message: str


