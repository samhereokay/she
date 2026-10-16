from datetime import datetime
from typing import Optional, List
from pydantic import BaseModel, Field, ConfigDict


class VaultItemCreateRequest(BaseModel):
    """
    Store a new end-to-end encrypted recovery vault item.
    Plaintext must be encrypted on the client using AES-GCM before sending.
    """
    item_type: str = Field(..., min_length=1, max_length=64, description="RECOVERY_CODE, DEVICE_IDENTIFIER, INSURANCE_POLICY, PURCHASE_RECEIPT, CONTACTS_BACKUP")
    title: str = Field(..., min_length=1, max_length=128, description="User-friendly title (e.g. 'Pixel 8 IMEI & Purchase Receipt')")
    encrypted_blob: str = Field(..., min_length=1, description="Base64 encoded ciphertext")
    iv: str = Field(..., min_length=12, max_length=64, description="Base64 encoded initialization vector")
    tag: str = Field(..., min_length=16, max_length=64, description="Base64 encoded authentication tag")
    key_version: int = Field(default=1, ge=1, description="Version of the vault master key used for encryption")


class VaultItemUpdateRequest(BaseModel):
    """Update an existing encrypted vault item or rotate its key."""
    item_type: Optional[str] = Field(None, min_length=1, max_length=64)
    title: Optional[str] = Field(None, min_length=1, max_length=128)
    encrypted_blob: Optional[str] = Field(None, min_length=1)
    iv: Optional[str] = Field(None, min_length=12, max_length=64)
    tag: Optional[str] = Field(None, min_length=16, max_length=64)
    key_version: Optional[int] = Field(None, ge=1)


class VaultItemResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: str
    user_id: str
    item_type: str
    title: str
    encrypted_blob: str
    iv: str
    tag: str
    key_version: int
    created_at: datetime
    updated_at: datetime


class VaultItemListResponse(BaseModel):
    items: List[VaultItemResponse]
    total: int
