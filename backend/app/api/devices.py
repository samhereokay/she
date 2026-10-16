import hashlib
from datetime import datetime, timezone
from typing import Any, List
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.db.session import get_db
from app.db.models import User, ProtectedDevice, LocationEvent, AuditEvent
from app.schemas.device import DevicePairRequest, DeviceResponse, DeviceListResponse
from app.schemas.location import LocationResponse
from app.api.deps import get_current_user

router = APIRouter(prefix="/api/v1/devices", tags=["Devices"])


def compute_device_status(last_location: LocationEvent) -> str:
    """
    Calculate dynamic device status based on TRUSTED SERVER CLOCK (server_receipt_time):
    - RECENT: server received fix within the last 5 minutes
    - DELAYED: server received fix between 5 minutes and 24 hours ago
    - UNAVAILABLE: no fix, or server receipt older than 24 hours
    """
    if not last_location:
        return "UNAVAILABLE"
    
    now = datetime.now(timezone.utc)
    receipt_time = last_location.server_receipt_time
    if receipt_time.tzinfo is None:
        receipt_time = receipt_time.replace(tzinfo=timezone.utc)
        
    diff_seconds = (now - receipt_time).total_seconds()
    
    if diff_seconds < 300:  # 5 minutes
        return "RECENT"
    elif diff_seconds < 86400:  # 24 hours
        return "DELAYED"
    else:
        return "UNAVAILABLE"


@router.post("/pair", response_model=DeviceResponse, status_code=status.HTTP_201_CREATED)
def pair_device(
    pair_in: DevicePairRequest,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
) -> Any:
    """Enroll and pair a new Guardian phone with the authenticated owner."""
    pairing_hash = hashlib.sha256(pair_in.pairing_secret.encode("utf-8")).hexdigest()
    
    device = ProtectedDevice(
        user_id=current_user.id,
        device_alias=pair_in.device_alias,
        public_key_pem=pair_in.public_key_pem,
        trusted_phone_number=pair_in.trusted_phone_number,
        pairing_secret_hash=pairing_hash,
        status="ACTIVE"
    )
    db.add(device)
    db.commit()
    db.refresh(device)
    
    # Audit trail
    audit = AuditEvent(
        user_id=current_user.id,
        protected_device_id=device.id,
        event_type="DEVICE_PAIRED",
        details=f"Paired alias: {device.device_alias}"
    )
    db.add(audit)
    db.commit()
    
    return DeviceResponse(
        id=device.id,
        user_id=device.user_id,
        device_alias=device.device_alias,
        public_key_pem=device.public_key_pem,
        trusted_phone_number=device.trusted_phone_number,
        status=device.status,
        created_at=device.created_at,
        last_location=None,
        calculated_status="UNAVAILABLE"
    )


@router.get("", response_model=DeviceListResponse)
def list_devices(
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
) -> Any:
    """List all protected devices owned by current user with live status indicators."""
    devices = db.query(ProtectedDevice).filter(ProtectedDevice.user_id == current_user.id).all()
    results = []
    
    for dev in devices:
        latest_loc = (
            db.query(LocationEvent)
            .filter(LocationEvent.protected_device_id == dev.id)
            .order_by(LocationEvent.sequence_number.desc())
            .first()
        )
        
        loc_resp = LocationResponse.model_validate(latest_loc) if latest_loc else None
        calc_status = compute_device_status(latest_loc)
        
        results.append(
            DeviceResponse(
                id=dev.id,
                user_id=dev.user_id,
                device_alias=dev.device_alias,
                public_key_pem=dev.public_key_pem,
                trusted_phone_number=dev.trusted_phone_number,
                status=dev.status,
                created_at=dev.created_at,
                last_location=loc_resp,
                calculated_status=calc_status
            )
        )
        
    return DeviceListResponse(devices=results)


@router.get("/{device_id}", response_model=DeviceResponse)
def get_device(
    device_id: str,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
) -> Any:
    """Get single protected device with strict ownership enforcement."""
    device = db.query(ProtectedDevice).filter(
        ProtectedDevice.id == device_id,
        ProtectedDevice.user_id == current_user.id
    ).first()
    
    if not device:
        # Cross-user authorization: return 404
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found")
        
    latest_loc = (
        db.query(LocationEvent)
        .filter(LocationEvent.protected_device_id == device.id)
        .order_by(LocationEvent.sequence_number.desc())
        .first()
    )
    
    loc_resp = LocationResponse.model_validate(latest_loc) if latest_loc else None
    calc_status = compute_device_status(latest_loc)
    
    return DeviceResponse(
        id=device.id,
        user_id=device.user_id,
        device_alias=device.device_alias,
        public_key_pem=device.public_key_pem,
        trusted_phone_number=device.trusted_phone_number,
        status=device.status,
        created_at=device.created_at,
        last_location=loc_resp,
        calculated_status=calc_status
    )


@router.get("/{device_id}/sync-status")
def get_device_sync_status(
    device_id: str,
    db: Session = Depends(get_db)
) -> Any:
    """
    Called by Guardian during pairing or after reinstall to resolve sequence number resets.
    Returns the highest acknowledged sequence number so client can resume without duplicate conflicts.
    """
    device = db.query(ProtectedDevice).filter(ProtectedDevice.id == device_id).first()
    if not device:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found")

    highest_seq = (
        db.query(LocationEvent.sequence_number)
        .filter(LocationEvent.protected_device_id == device.id)
        .order_by(LocationEvent.sequence_number.desc())
        .first()
    )

    last_ack = highest_seq[0] if highest_seq else 0

    return {
        "device_id": device.id,
        "status": device.status,
        "highest_acknowledged_sequence": last_ack,
        "next_expected_sequence": last_ack + 1
    }

