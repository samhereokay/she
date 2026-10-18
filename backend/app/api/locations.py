import base64
import logging
from datetime import datetime, timezone
from typing import Any, List
from fastapi import APIRouter, Depends, HTTPException, status, WebSocket, WebSocketDisconnect
from sqlalchemy.orm import Session
from sqlalchemy.exc import IntegrityError

from app.db.session import get_db, SessionLocal
from app.db.models import User, ProtectedDevice, LocationEvent, AuditEvent
from app.schemas.location import (
    LocationBatchIngestRequest,
    LocationBatchIngestResponse,
    LocationResponse
)
from app.core.security import verify_ecdsa_p256_signature, verify_access_token
from app.api.deps import get_current_user
from app.core.ws_manager import ws_manager

logger = logging.getLogger("uvicorn.error")

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
async def ingest_location_batch(
    batch_in: LocationBatchIngestRequest,
    db: Session = Depends(get_db)
) -> Any:

    """
    Guardian sync endpoint: ingests batched location fixes.
    - Authenticates via device ECDSA P-256 signature against registered public key.
    - Idempotency & Conflict Handling:
      - Identical sequence + identical payload -> Acknowledged (safe idempotent retry).
      - Identical sequence + different payload -> REJECTED & AUDITED (replay / tampering conflict).
    - Out-of-order event handling:
      - Events are stored and indexed by (protected_device_id, sequence_number).
      - Non-contiguous sequence numbers are permitted to support out-of-order delivery during network reconnects.
    - Records separate capture_time (untrusted client clock) vs server_receipt_time (trusted server clock).
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
    
    for event in batch_in.events:
        # Check if sequence already exists
        existing = db.query(LocationEvent).filter(
            LocationEvent.protected_device_id == device.id,
            LocationEvent.sequence_number == event.sequence_number
        ).first()
        
        if existing:
            # Check if payload and signature are identical (safe retry)
            is_same_payload = (
                abs(existing.latitude - event.latitude) < 0.000001 and
                abs(existing.longitude - event.longitude) < 0.000001 and
                abs(existing.accuracy - event.accuracy) < 0.1 and
                existing.signature == event.signature
            )
            
            if is_same_payload:
                acknowledged_seqs.append(event.sequence_number)
            else:
                # Same sequence with different payload: REJECT and log tampering attempt
                rejected_seqs.append(event.sequence_number)
                audit = AuditEvent(
                    user_id=device.user_id,
                    protected_device_id=device.id,
                    event_type="REPLAY_CONFLICT_DETECTED",
                    details=f"Sequence {event.sequence_number} replayed with altered payload! Existing lat/lng: {existing.latitude},{existing.longitude}; Received: {event.latitude},{event.longitude}"
                )
                db.add(audit)
                db.commit()
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
            audit = AuditEvent(
                user_id=device.user_id,
                protected_device_id=device.id,
                event_type="INVALID_SIGNATURE",
                details=f"Signature check failed on sequence {event.sequence_number}"
            )
            db.add(audit)
            db.commit()
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
            
            # Broadcast over WebSocket in real time
            await ws_manager.broadcast_location(device.id, {
                "type": "LOCATION_UPDATE",
                "device_id": device.id,
                "latitude": loc_record.latitude,
                "longitude": loc_record.longitude,
                "accuracy": loc_record.accuracy,
                "altitude": loc_record.altitude,
                "velocity": loc_record.velocity,
                "capture_time": loc_record.capture_time.isoformat() if loc_record.capture_time else None,
                "server_receipt_time": loc_record.server_receipt_time.isoformat() if loc_record.server_receipt_time else None,
                "sequence_number": loc_record.sequence_number
            })
        except IntegrityError:
            db.rollback()
            rejected_seqs.append(event.sequence_number)
            
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
        # Cross-user authorization check: returns 404 to avoid leaking device existence
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found")
        
    locations = (
        db.query(LocationEvent)
        .filter(LocationEvent.protected_device_id == device.id)
        .order_by(LocationEvent.sequence_number.desc())
        .limit(limit)
        .all()
    )
    return locations


@router.websocket("/ws/devices/{device_id}")
async def websocket_device_location_stream(
    websocket: WebSocket,
    device_id: str,
    token: str = None,
    db: Session = Depends(get_db)
):
    """
    Real-time authenticated WebSocket stream for device location tracking.
    - Validates JWT bearer token from query param (?token=<jwt>).
    - Verifies device exists and belongs to current authenticated owner.
    - On connect: sends INITIAL_STATE with last known location + recent historical trail.
    - Streams live LOCATION_UPDATE events as they are ingested from physical handsets.
    """
    if not token:
        token = websocket.query_params.get("token")

    if not token:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="Authentication token required")
        return

    payload = verify_access_token(token)
    if not payload or "sub" not in payload:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="Invalid authentication token")
        return

    user = db.query(User).filter(User.id == payload["sub"]).first()
    if not user:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="User not found")
        return

    device = db.query(ProtectedDevice).filter(
        ProtectedDevice.id == device_id,
        ProtectedDevice.user_id == user.id
    ).first()

    if not device:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION, reason="Device not found or unauthorized")
        return

    # Register and accept WebSocket connection
    await ws_manager.connect(device_id, websocket)

    # Retrieve up to 50 recent points for breadcrumb trail
    recent_locs = (
        db.query(LocationEvent)
        .filter(LocationEvent.protected_device_id == device.id)
        .order_by(LocationEvent.sequence_number.desc())
        .limit(50)
        .all()
    )
    recent_locs.reverse()

    trail_data = [
        {
            "latitude": loc.latitude,
            "longitude": loc.longitude,
            "accuracy": loc.accuracy,
            "altitude": loc.altitude,
            "velocity": loc.velocity,
            "capture_time": loc.capture_time.isoformat() if loc.capture_time else None,
            "sequence_number": loc.sequence_number
        }
        for loc in recent_locs
    ]

    latest = trail_data[-1] if trail_data else None

    # Send initial state payload
    await websocket.send_json({
        "type": "INITIAL_STATE",
        "device_id": device.id,
        "device_alias": device.device_alias,
        "status": device.status,
        "latest_location": latest,
        "trail": trail_data
    })

    try:
        while True:
            data = await websocket.receive_text()
            if data == "ping":
                await websocket.send_text("pong")
    except WebSocketDisconnect:
        ws_manager.disconnect(device_id, websocket)
    except Exception as e:
        logger.warning(f"WebSocket session closed for device {device_id}: {e}")
        ws_manager.disconnect(device_id, websocket)

