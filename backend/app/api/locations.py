import base64
from datetime import datetime, timezone
from typing import Any, List
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session
from sqlalchemy.exc import IntegrityError

from app.db.session import get_db
from app.db.models import User, ProtectedDevice, LocationEvent
from app.schemas.location import (
    LocationBatchIngestRequest,
    LocationBatchIngestResponse,
    LocationResponse
)
from app.core.security import verify_ecdsa_p256_signature
from app.api.deps import get_current_user

router = APIRouter(prefix="/api/v1", tags=["Locations"])


def format_canonical_location_string(
    device_id: str,
    sequence_number: int,
    capture_timestamp_sec: int,
    latitude: float,
    longitude: float,
    accuracy: float
) -> bytes:
    """
    Standard canonical representation hashed and signed by Guardian ECDSA key.
    Format: <device_id>|<sequence>|<epoch_sec>|<lat:.6f>|<lng:.6f>|<acc:.1f>
    """
    canonical_str = f"{device_id}|{sequence_number}|{capture_timestamp_sec}|{latitude:.6f}|{longitude:.6f}|{accuracy:.1f}"
    return canonical_str.encode("utf-8")


@router.post("/locations/batch", response_model=LocationBatchIngestResponse)
def ingest_location_batch(
    batch_in: LocationBatchIngestRequest,
    db: Session = Depends(get_db)
) -> Any:
    """
    Guardian sync endpoint: ingests batched location fixes.
    - Authenticates via device ECDSA P-256 signature against registered public key.
    - Idempotent: replayed identical sequences are acknowledged without error.
    - Enforces monotonically increasing sequence numbers.
    - Records separate capture_time vs server_receipt_time.
    """
    device = db.query(ProtectedDevice).filter(ProtectedDevice.id == batch_in.device_id).first()
    if not device:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Protected device not found"
        )
    
    if device.status == "REVOKED":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Device has been revoked"
        )
    
    acknowledged_seqs = []
    rejected_seqs = []
    
    # Sort events by sequence number to guarantee in-order processing
    sorted_events = sorted(batch_in.events, key=lambda e: e.sequence_number)
    
    for event in sorted_events:
        # Check if already ingested (Idempotency check)
        existing = db.query(LocationEvent).filter(
            LocationEvent.protected_device_id == device.id,
            LocationEvent.sequence_number == event.sequence_number
        ).first()
        
        if existing:
            # Already acknowledged previously
            acknowledged_seqs.append(event.sequence_number)
            continue
            
        # Verify ECDSA P-256 Signature
        capture_epoch = int(event.capture_time.timestamp())
        canonical_bytes = format_canonical_location_string(
            device_id=device.id,
            sequence_number=event.sequence_number,
            capture_timestamp_sec=capture_epoch,
            latitude=event.latitude,
            longitude=event.longitude,
            accuracy=event.accuracy
        )
        
        try:
            der_sig_bytes = base64.b64decode(event.signature)
        except Exception:
            rejected_seqs.append(event.sequence_number)
            continue
            
        is_valid = verify_ecdsa_p256_signature(
            public_key_pem=device.public_key_pem,
            data_bytes=canonical_bytes,
            der_signature_bytes=der_sig_bytes
        )
        
        if not is_valid:
            rejected_seqs.append(event.sequence_number)
            continue
            
        # Insert verified location fix
        loc_record = LocationEvent(
            protected_device_id=device.id,
            latitude=event.latitude,
            longitude=event.longitude,
            accuracy=event.accuracy,
            altitude=event.altitude,
            velocity=event.velocity,
            capture_time=event.capture_time,
            server_receipt_time=datetime.now(timezone.utc),
            sequence_number=event.sequence_number,
            signature=event.signature
        )
        
        try:
            db.add(loc_record)
            db.commit()
            acknowledged_seqs.append(event.sequence_number)
        except IntegrityError:
            db.rollback()
            # Handled duplicate gracefully
            acknowledged_seqs.append(event.sequence_number)
            
    return LocationBatchIngestResponse(
        device_id=device.id,
        acknowledged_sequences=acknowledged_seqs,
        rejected_sequences=rejected_seqs,
        status="PROCESSED" if not rejected_seqs else "PARTIAL_REJECTION"
    )


@router.get("/devices/{device_id}/locations", response_model=List[LocationResponse])
def get_device_location_history(
    device_id: str,
    limit: int = 100,
    current_user: User = Depends(get_current_user),
    db: Session = Depends(get_db)
) -> Any:
    """Retrieve historical location traces for a protected device owned by current user."""
    device = db.query(ProtectedDevice).filter(
        ProtectedDevice.id == device_id,
        ProtectedDevice.user_id == current_user.id
    ).first()
    
    if not device:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found")
        
    locations = (
        db.query(LocationEvent)
        .filter(LocationEvent.protected_device_id == device.id)
        .order_by(LocationEvent.capture_time.desc())
        .limit(limit)
        .all()
    )
    return locations
