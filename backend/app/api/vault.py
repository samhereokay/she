import base64
from typing import Any, Optional
from fastapi import APIRouter, Depends, HTTPException, status, Query
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.db.models import User, VaultItem, AuditEvent, utc_now
from app.schemas.vault import (
    VaultItemCreateRequest,
    VaultItemUpdateRequest,
    VaultItemResponse,
    VaultItemListResponse
)
from app.api.deps import get_current_user_2fa
from app.core.config import settings

router = APIRouter(prefix="/api/v1/vault", tags=["Recovery Vault"])


def _validate_base64_payload(data_b64: str, min_bytes: int = 1, exact_bytes: Optional[int] = None) -> bytes:
    """Helper to validate that a string is valid Base64 and satisfies length constraints."""
    try:
        raw = base64.b64decode(data_b64)
        if len(raw) < min_bytes:
            raise ValueError(f"Decoded bytes length {len(raw)} < minimum {min_bytes}")
        if exact_bytes and len(raw) != exact_bytes:
            raise ValueError(f"Decoded bytes length {len(raw)} != required {exact_bytes}")
        return raw
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Invalid cryptographic encoding: {str(e)}"
        )


@router.post("/items", response_model=VaultItemResponse, status_code=status.HTTP_201_CREATED)
def create_vault_item(
    req: VaultItemCreateRequest,
    current_user: User = Depends(get_current_user_2fa),
    db: Session = Depends(get_db)
) -> Any:
    """
    Store a new client-side encrypted recovery vault item.
    STRICT ZERO-KNOWLEDGE POLICY:
    - Caller must be authenticated with 2FA / TOTP.
    - Server stores only encrypted ciphertext; plaintext is NEVER sent or stored.
    - IV must be valid 12-byte Base64 (standard for AES-256-GCM).
    - Tag must be valid 16-byte Base64 (standard for GCM authentication tag).
    """
    _validate_base64_payload(req.iv, exact_bytes=12)
    _validate_base64_payload(req.tag, exact_bytes=16)
    raw_blob = _validate_base64_payload(req.encrypted_blob, min_bytes=1)

    # 1. Enforce per-item size limit
    if len(raw_blob) > settings.MAX_VAULT_ITEM_BYTES:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=f"Vault item payload exceeds maximum size limit of {settings.MAX_VAULT_ITEM_BYTES // 1024} KB."
        )

    # 2. Enforce per-user item count limit
    current_count = db.query(VaultItem).filter(VaultItem.user_id == current_user.id).count()
    if current_count >= settings.MAX_VAULT_ITEMS_PER_USER:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Vault storage limit reached. Maximum allowed is {settings.MAX_VAULT_ITEMS_PER_USER} items per user account."
        )

    # 3. Enforce per-user cumulative storage limit
    existing_items = db.query(VaultItem.encrypted_blob).filter(VaultItem.user_id == current_user.id).all()
    current_used_bytes = sum(len(i[0]) for i in existing_items)
    if current_used_bytes + len(req.encrypted_blob) > settings.MAX_VAULT_STORAGE_BYTES_PER_USER:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=f"Vault cumulative storage limit exceeded ({settings.MAX_VAULT_STORAGE_BYTES_PER_USER // (1024 * 1024)} MB limit per user)."
        )

    item = VaultItem(
        user_id=current_user.id,
        item_type=req.item_type.strip().upper(),
        title=req.title.strip(),
        encrypted_blob=req.encrypted_blob.strip(),
        iv=req.iv.strip(),
        tag=req.tag.strip(),
        key_version=req.key_version
    )
    db.add(item)

    audit = AuditEvent(
        user_id=current_user.id,
        event_type="VAULT_ITEM_CREATED",
        details=f"Created encrypted vault item '{req.title}' of type {req.item_type} (key v{req.key_version})."
    )
    db.add(audit)
    db.commit()
    db.refresh(item)
    return item


@router.get("/items", response_model=VaultItemListResponse)
def list_vault_items(
    item_type: Optional[str] = Query(None, description="Filter by item_type"),
    current_user: User = Depends(get_current_user_2fa),
    db: Session = Depends(get_db)
) -> Any:
    """
    List all encrypted vault items owned by the authenticated user.
    Cross-user isolation: strictly filtered by current_user.id.
    """
    query = db.query(VaultItem).filter(VaultItem.user_id == current_user.id)
    if item_type:
        query = query.filter(VaultItem.item_type == item_type.strip().upper())
    
    items = query.order_by(VaultItem.created_at.desc()).all()
    used_bytes = sum(len(item.encrypted_blob) for item in items)
    return VaultItemListResponse(
        items=items,
        total=len(items),
        max_items=settings.MAX_VAULT_ITEMS_PER_USER,
        used_storage_bytes=used_bytes,
        max_storage_bytes=settings.MAX_VAULT_STORAGE_BYTES_PER_USER
    )


@router.get("/items/{item_id}", response_model=VaultItemResponse)
def get_vault_item(
    item_id: str,
    current_user: User = Depends(get_current_user_2fa),
    db: Session = Depends(get_db)
) -> Any:
    """Get a single encrypted vault item with strict ownership enforcement."""
    item = db.query(VaultItem).filter(
        VaultItem.id == item_id,
        VaultItem.user_id == current_user.id
    ).first()
    if not item:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Vault item not found for this account."
        )
    return item


@router.put("/items/{item_id}", response_model=VaultItemResponse)
def update_vault_item(
    item_id: str,
    req: VaultItemUpdateRequest,
    current_user: User = Depends(get_current_user_2fa),
    db: Session = Depends(get_db)
) -> Any:
    """Update an existing vault item or re-encrypt with a rotated key."""
    item = db.query(VaultItem).filter(
        VaultItem.id == item_id,
        VaultItem.user_id == current_user.id
    ).first()
    if not item:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Vault item not found for this account."
        )

    if req.iv is not None:
        _validate_base64_payload(req.iv, exact_bytes=12)
        item.iv = req.iv.strip()
    if req.tag is not None:
        _validate_base64_payload(req.tag, exact_bytes=16)
        item.tag = req.tag.strip()
    if req.encrypted_blob is not None:
        raw_blob = _validate_base64_payload(req.encrypted_blob, min_bytes=1)
        if len(raw_blob) > settings.MAX_VAULT_ITEM_BYTES:
            raise HTTPException(
                status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                detail=f"Vault item payload exceeds maximum size limit of {settings.MAX_VAULT_ITEM_BYTES // 1024} KB."
            )
        existing_items = db.query(VaultItem.id, VaultItem.encrypted_blob).filter(VaultItem.user_id == current_user.id).all()
        current_used_bytes = sum(len(b) for (iid, b) in existing_items if iid != item.id)
        if current_used_bytes + len(req.encrypted_blob) > settings.MAX_VAULT_STORAGE_BYTES_PER_USER:
            raise HTTPException(
                status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                detail=f"Vault cumulative storage limit exceeded ({settings.MAX_VAULT_STORAGE_BYTES_PER_USER // (1024 * 1024)} MB limit per user)."
            )
        item.encrypted_blob = req.encrypted_blob.strip()

    if req.title is not None:
        item.title = req.title.strip()
    if req.item_type is not None:
        item.item_type = req.item_type.strip().upper()
    if req.key_version is not None:
        item.key_version = req.key_version

    item.updated_at = utc_now()

    audit = AuditEvent(
        user_id=current_user.id,
        event_type="VAULT_ITEM_UPDATED",
        details=f"Updated encrypted vault item '{item.title}' (key v{item.key_version})."
    )
    db.add(audit)
    db.commit()
    db.refresh(item)
    return item


@router.delete("/items/{item_id}", status_code=status.HTTP_200_OK)
def delete_vault_item(
    item_id: str,
    current_user: User = Depends(get_current_user_2fa),
    db: Session = Depends(get_db)
) -> Any:
    """Delete a vault item permanently from remote storage."""
    item = db.query(VaultItem).filter(
        VaultItem.id == item_id,
        VaultItem.user_id == current_user.id
    ).first()
    if not item:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Vault item not found for this account."
        )

    db.delete(item)
    audit = AuditEvent(
        user_id=current_user.id,
        event_type="VAULT_ITEM_DELETED",
        details=f"Deleted encrypted vault item '{item.title}' ({item.id})."
    )
    db.add(audit)
    db.commit()
    return {"status": "DELETED", "id": item_id}


@router.delete("/purge", status_code=status.HTTP_200_OK)
def purge_entire_vault(
    current_user: User = Depends(get_current_user_2fa),
    db: Session = Depends(get_db)
) -> Any:
    """Emergency cryptographic shredding: purge all vault items for the user."""
    count = db.query(VaultItem).filter(VaultItem.user_id == current_user.id).delete()
    audit = AuditEvent(
        user_id=current_user.id,
        event_type="VAULT_PURGED",
        details=f"Owner purged entire recovery vault. {count} items permanently deleted."
    )
    db.add(audit)
    db.commit()
    return {"status": "PURGED", "deleted_count": count}
