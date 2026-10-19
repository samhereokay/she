import base64
import json
import time
import hashlib
import secrets
from datetime import datetime, timezone
from typing import Any, List, Optional
from fastapi import APIRouter, Depends, HTTPException, status, Request
from sqlalchemy.orm import Session

from app.core.config import settings
from app.db.session import get_db
from app.db.models import User, ProtectedDevice, LocationEvent, AuditEvent, TrustedDevice, DeviceCommand, generate_uuid
from app.schemas.device import (
    DevicePairRequest,
    DeviceResponse,
    DeviceListResponse,
    TrustedDeviceRegisterRequest,
    TrustedDeviceResponse,
    CommandDispatchRequest,
    CommandAckRequest,
    CommandResponse,
    PairingConfirmRequest,
    PairingConfirmResponse
)
from app.schemas.location import LocationResponse
from app.api.deps import get_current_user, get_verified_user, get_current_user_2fa, get_pairing_token_user
from app.core.security import (
    verify_ecdsa_p256_signature,
    create_canonical_command_bytes,
    decrypt_totp_secret,
    verify_totp_code
)

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
    pairing_data: tuple[User, dict] = Depends(get_pairing_token_user),
    db: Session = Depends(get_db)
) -> Any:
    """
    Enroll a new Guardian phone with the authenticated owner.
    STRICT SECURITY REQUIREMENT:
    Must be authorized with a single-use, 5-minute register-only pairing token from the QR code.
    General session tokens are strictly rejected.
    Device is placed in PENDING_CONFIRMATION until the owner confirms the mutual confirmation code.
    """
    current_user, token_payload = pairing_data
    pairing_hash = hashlib.sha256(pair_in.pairing_secret.encode("utf-8")).hexdigest()
    pairing_id = generate_uuid()
    confirmation_code = f"{secrets.randbelow(900) + 100}-{secrets.randbelow(900) + 100}"
    
    # Restrict one device per user account
    user_other_device = db.query(ProtectedDevice).filter(
        ProtectedDevice.user_id == current_user.id,
        ProtectedDevice.id != pair_in.device_id
    ).first()
    if user_other_device:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Account limit reached: Each user account is strictly restricted to 1 protected device. To pair a new handset, unpair your current device first."
        )

    if pair_in.device_id:
        existing = db.query(ProtectedDevice).filter(ProtectedDevice.id == pair_in.device_id).first()
        if existing:
            if existing.user_id != current_user.id:
                raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Device ID registered to another user")
            existing.device_alias = pair_in.device_alias
            existing.public_key_pem = pair_in.public_key_pem
            existing.trusted_phone_number = pair_in.trusted_phone_number
            existing.pairing_secret_hash = pairing_hash
            existing.pairing_secret = pair_in.pairing_secret
            existing.pairing_id = pairing_id
            existing.confirmation_code = confirmation_code
            existing.status = "PENDING_CONFIRMATION"
            db.commit()
            db.refresh(existing)
            device = existing
        else:
            device = ProtectedDevice(
                id=pair_in.device_id,
                user_id=current_user.id,
                device_alias=pair_in.device_alias,
                public_key_pem=pair_in.public_key_pem,
                trusted_phone_number=pair_in.trusted_phone_number,
                pairing_secret_hash=pairing_hash,
                pairing_secret=pair_in.pairing_secret,
                pairing_id=pairing_id,
                confirmation_code=confirmation_code,
                status="PENDING_CONFIRMATION"
            )
            db.add(device)
            db.commit()
            db.refresh(device)
    else:
        device = ProtectedDevice(
            user_id=current_user.id,
            device_alias=pair_in.device_alias,
            public_key_pem=pair_in.public_key_pem,
            trusted_phone_number=pair_in.trusted_phone_number,
            pairing_secret_hash=pairing_hash,
            pairing_secret=pair_in.pairing_secret,
            pairing_id=pairing_id,
            confirmation_code=confirmation_code,
            status="PENDING_CONFIRMATION"
        )
        db.add(device)
        db.commit()
        db.refresh(device)
    
    # Audit trail
    audit = AuditEvent(
        user_id=current_user.id,
        protected_device_id=device.id,
        event_type="DEVICE_PAIRING_REQUESTED",
        details=f"Device {device.device_alias} ({device.id}) requested enrollment. Confirmation code: {confirmation_code}"
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
        pairing_id=device.pairing_id,
        confirmation_code=device.confirmation_code,
        created_at=device.created_at,
        last_location=None,
        calculated_status="UNAVAILABLE"
    )


@router.get("/pending-confirmations", response_model=List[DeviceResponse])
def list_pending_confirmations(
    current_user: User = Depends(get_verified_user),
    db: Session = Depends(get_db)
) -> Any:
    """List devices currently awaiting owner mutual confirmation."""
    devices = db.query(ProtectedDevice).filter(
        ProtectedDevice.user_id == current_user.id,
        ProtectedDevice.status == "PENDING_CONFIRMATION"
    ).all()
    results = []
    for d in devices:
        results.append(DeviceResponse(
            id=d.id,
            user_id=d.user_id,
            device_alias=d.device_alias,
            public_key_pem=d.public_key_pem,
            trusted_phone_number=d.trusted_phone_number,
            status=d.status,
            pairing_id=d.pairing_id,
            confirmation_code=d.confirmation_code,
            created_at=d.created_at,
            last_location=None,
            calculated_status="UNAVAILABLE"
        ))
    return results


@router.post("/{device_id}/confirm-pairing", response_model=PairingConfirmResponse)
def confirm_device_pairing(
    device_id: str,
    req: PairingConfirmRequest,
    current_user: User = Depends(get_current_user_2fa),
    db: Session = Depends(get_db)
) -> Any:
    """
    Confirm device enrollment by matching the 6-digit confirmation code shown on Guardian screen.
    Requires 2FA owner authentication. Transitions device from PENDING_CONFIRMATION to ACTIVE.
    """
    device = db.query(ProtectedDevice).filter(
        ProtectedDevice.id == device_id,
        ProtectedDevice.user_id == current_user.id
    ).first()
    if not device:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found")

    if device.status != "PENDING_CONFIRMATION":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Device is not awaiting confirmation (status: {device.status})"
        )

    clean_req = req.confirmation_code.replace("-", "").strip()
    clean_dev = (device.confirmation_code or "").replace("-", "").strip()
    if clean_req != clean_dev:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Confirmation code does not match. Check Guardian screen."
        )

    device.status = "ACTIVE"
    device.confirmation_code = None

    audit = AuditEvent(
        user_id=current_user.id,
        protected_device_id=device.id,
        event_type="DEVICE_PAIRING_CONFIRMED",
        details=f"Device {device.device_alias} ({device.id}) enrollment confirmed by owner. Status ACTIVE."
    )
    db.add(audit)
    db.commit()

    return PairingConfirmResponse(
        device_id=device.id,
        status="ACTIVE",
        pairing_id=device.pairing_id
    )


@router.get("", response_model=DeviceListResponse)
def list_devices(
    current_user: User = Depends(get_verified_user),
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
    current_user: User = Depends(get_verified_user),
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


@router.post("/trusted", response_model=TrustedDeviceResponse, status_code=status.HTTP_201_CREATED)
def register_trusted_device(
    req: TrustedDeviceRegisterRequest,
    current_user: User = Depends(get_current_user_2fa),
    db: Session = Depends(get_db)
) -> Any:
    """Register an Owner Admin companion device and its ECDSA P-256 public key."""
    trusted = TrustedDevice(
        user_id=current_user.id,
        device_name=req.device_name,
        public_key_pem=req.public_key_pem
    )
    db.add(trusted)
    db.commit()
    db.refresh(trusted)
    return trusted


@router.get("/trusted", response_model=List[TrustedDeviceResponse])
def list_trusted_devices(
    current_user: User = Depends(get_verified_user),
    db: Session = Depends(get_db)
) -> Any:
    """List approved Admin companion devices for the authenticated owner."""
    return db.query(TrustedDevice).filter(
        TrustedDevice.user_id == current_user.id,
        TrustedDevice.revoked_at.is_(None)
    ).all()


@router.post("/commands/dispatch", response_model=CommandResponse, status_code=status.HTTP_201_CREATED)
def dispatch_command(
    req: CommandDispatchRequest,
    current_user: User = Depends(get_current_user_2fa),
    db: Session = Depends(get_db)
) -> Any:
    """
    Dispatch an emergency recovery command (LOCATE, RING, LOCK, STOLEN_MODE).
    STRICT SECURITY ENFORCEMENT:
    1. Caller must have authenticated with 2FA / TOTP (is_2fa=True).
    2. Trusted Admin device must belong to current user and be active.
    3. Target Guardian device must belong to current user.
    4. Command must be cryptographically signed by the Admin device private key (P-256 ASN.1 DER).
    5. Command must not be expired (expiry_epoch > now).
    6. Sequence counter must be monotonically increasing.
    """
    now_epoch = int(time.time())
    if req.expiry_epoch <= now_epoch:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Command expired. expiry_epoch ({req.expiry_epoch}) <= now ({now_epoch})"
        )

    trusted = db.query(TrustedDevice).filter(
        TrustedDevice.id == req.trusted_device_id,
        TrustedDevice.user_id == current_user.id
    ).first()
    if not trusted or not trusted.is_active or trusted.revoked_at is not None:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Trusted Admin device not recognized or has been revoked."
        )

    target = db.query(ProtectedDevice).filter(
        ProtectedDevice.id == req.target_device_id
    ).first()
    if not target:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Target protected device not found."
        )
    if target.user_id != current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Not authorized to dispatch recovery commands to another user's device."
        )
    if target.status != "ACTIVE":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Cannot dispatch commands to device with status {target.status}. Must be ACTIVE."
        )

    # Check monotonic counter per trusted device
    max_cmd_seq = (
        db.query(DeviceCommand.sequence_counter)
        .filter(DeviceCommand.trusted_device_id == trusted.id)
        .order_by(DeviceCommand.sequence_counter.desc())
        .first()
    )
    last_seq = max_cmd_seq[0] if max_cmd_seq else 0
    if req.sequence_counter <= last_seq:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Command counter replayed or non-monotonic: {req.sequence_counter} <= last_seen ({last_seq})"
        )
    ALLOWED_COMMAND_TYPES = {
        "LOCATE",
        "RING",
        "STOP_RING",
        "LOCK",
        "STOLEN_MODE",
        "EXIT_KIOSK",
        "RECOVERED",
        "UNPAIR",
        "CLEAR_DEVICE_OWNER",
        "SEND_VERIFY_SMS",
    }
    if req.command_type not in ALLOWED_COMMAND_TYPES:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Invalid command_type '{req.command_type}'. Allowed command types: {sorted(list(ALLOWED_COMMAND_TYPES))}"
        )

    stored_parameters_json = req.parameters_json
    if req.command_type == "CLEAR_DEVICE_OWNER":
        totp_fresh = False
        try:
            params = json.loads(req.parameters_json or "{}")
        except Exception:
            params = {}
        
        fresh_code = params.pop("totp_code", None)
        if fresh_code is not None:
            # Re-serialize parameters without totp_code so it is never stored in DB or transmitted to device
            stored_parameters_json = json.dumps(params)
            if current_user.totp_enabled and current_user.totp_secret:
                plain_secret = decrypt_totp_secret(current_user.totp_secret)
                if verify_totp_code(plain_secret, str(fresh_code).strip()):
                    totp_fresh = True
                else:
                    raise HTTPException(
                        status_code=status.HTTP_403_FORBIDDEN,
                        detail="Invalid TOTP code provided for CLEAR_DEVICE_OWNER."
                    )
            else:
                raise HTTPException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    detail="TOTP is not configured or enabled for this user."
                )

        if not totp_fresh:
            verified_epoch = getattr(current_user, "_totp_verified_at", None)
            if isinstance(verified_epoch, (int, float)):
                age_s = now_epoch - int(verified_epoch)
                if 0 <= age_s <= 60:
                    totp_fresh = True
                else:
                    raise HTTPException(
                        status_code=status.HTTP_403_FORBIDDEN,
                        detail=f"CLEAR_DEVICE_OWNER requires fresh TOTP authentication verified within the last 60 seconds (verified {age_s}s ago). Re-authenticate with TOTP."
                    )
            else:
                raise HTTPException(
                    status_code=status.HTTP_403_FORBIDDEN,
                    detail="CLEAR_DEVICE_OWNER requires fresh TOTP authentication verified within the last 60 seconds. Re-authenticate with TOTP."
                )

    # Reconstruct canonical command bytes with pairing_id
    cmd_id = req.command_id or generate_uuid()
    canonical_bytes = create_canonical_command_bytes(
        pairing_id=target.pairing_id or "",
        command_id=req.command_id or "",
        target_device_id=req.target_device_id,
        command_type=req.command_type,
        sequence_counter=req.sequence_counter,
        expiry_epoch=req.expiry_epoch,
        parameters_json=req.parameters_json
    )

    try:
        der_sig = base64.b64decode(req.admin_signature)
    except Exception:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid base64 encoding for admin_signature"
        )

    is_valid = verify_ecdsa_p256_signature(
        public_key_pem=trusted.public_key_pem,
        data_bytes=canonical_bytes,
        der_signature_bytes=der_sig
    )
    if not is_valid and stored_parameters_json != req.parameters_json:
        # Also check signature against cleaned parameters (if client signed canonical bytes without totp_code)
        clean_bytes = create_canonical_command_bytes(
            pairing_id=target.pairing_id or "",
            command_id=req.command_id or "",
            target_device_id=req.target_device_id,
            command_type=req.command_type,
            sequence_counter=req.sequence_counter,
            expiry_epoch=req.expiry_epoch,
            parameters_json=stored_parameters_json
        )
        is_valid = verify_ecdsa_p256_signature(
            public_key_pem=trusted.public_key_pem,
            data_bytes=clean_bytes,
            der_signature_bytes=der_sig
        )

    if not is_valid:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Cryptographic verification failed for Admin device signature. Command rejected."
        )

    command = DeviceCommand(
        id=cmd_id,
        user_id=current_user.id,
        trusted_device_id=trusted.id,
        protected_device_id=target.id,
        pairing_id=target.pairing_id,
        command_type=req.command_type,
        sequence_counter=req.sequence_counter,
        expiry_epoch=req.expiry_epoch,
        parameters_json=stored_parameters_json,
        admin_signature=req.admin_signature,
        status="PENDING"
    )
    db.add(command)

    audit = AuditEvent(
        user_id=current_user.id,
        protected_device_id=target.id,
        event_type="COMMAND_DISPATCHED",
        details=f"Command {req.command_type} (seq #{req.sequence_counter}) dispatched to device {target.device_alias} ({target.id}) by Admin device {trusted.device_name}."
    )
    db.add(audit)
    db.commit()
    db.refresh(command)
    return command


@router.get("/{device_id}/commands/pending", response_model=List[CommandResponse])
def get_pending_commands(
    device_id: str,
    request: Request,
    db: Session = Depends(get_db)
) -> Any:
    """
    Endpoint for Guardian device foreground service to poll for pending recovery commands.
    Verifies signed device authentication headers (X-Device-Signature + X-Device-Timestamp).
    """
    device = db.query(ProtectedDevice).filter(ProtectedDevice.id == device_id).first()
    if not device:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Protected device not found")

    sig_header = request.headers.get("X-Device-Signature")
    ts_header = request.headers.get("X-Device-Timestamp")
    if sig_header and ts_header:
        try:
            ts = int(ts_header)
            now = int(time.time())
            if abs(now - ts) > 300:
                raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Poll request timestamp expired")
            poll_bytes = f"POLL_COMMANDS|{device_id}|{ts}".encode("utf-8")
            der_sig = base64.b64decode(sig_header)
            if not verify_ecdsa_p256_signature(device.public_key_pem, poll_bytes, der_sig):
                raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid device signature for command poll")
        except ValueError:
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid timestamp header")
    elif not settings.DEBUG:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Signed device authentication required")

    now_epoch = int(time.time())

    # Auto-expire overdue commands
    expired_cmds = db.query(DeviceCommand).filter(
        DeviceCommand.protected_device_id == device_id,
        DeviceCommand.status == "PENDING",
        DeviceCommand.expiry_epoch <= now_epoch
    ).all()
    if expired_cmds:
        for exp in expired_cmds:
            exp.status = "EXPIRED"
        db.commit()

    commands = db.query(DeviceCommand).filter(
        DeviceCommand.protected_device_id == device_id,
        DeviceCommand.status == "PENDING",
        DeviceCommand.expiry_epoch > now_epoch
    ).order_by(DeviceCommand.sequence_counter.asc()).all()
    return commands


@router.post("/{device_id}/commands/{command_id}/ack", status_code=status.HTTP_200_OK)
def acknowledge_command(
    device_id: str,
    command_id: str,
    ack_in: Optional[CommandAckRequest] = None,
    db: Session = Depends(get_db)
) -> Any:
    """Acknowledge execution or rejection of a recovery command by the Guardian phone."""
    command = db.query(DeviceCommand).filter(
        DeviceCommand.id == command_id,
        DeviceCommand.protected_device_id == device_id
    ).first()
    if not command:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Command not found")

    new_status = ack_in.status if ack_in else "EXECUTED"
    command.status = new_status
    command.executed_at = datetime.now(timezone.utc)
    db.commit()

    if new_status == "REJECTED":
        reason = ack_in.reason if ack_in else "Unknown"
        audit = AuditEvent(
            user_id=command.user_id,
            protected_device_id=device_id,
            event_type="COMMAND_REJECTED_BY_DEVICE",
            details=f"Command {command_id} ({command.command_type}) rejected by Guardian: {reason}"
        )
        db.add(audit)
        db.commit()

    return {"status": "success", "command_id": command_id, "command_status": command.status}


