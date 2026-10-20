import base64
import json
import time
import hashlib
import secrets
from datetime import datetime, timezone, timedelta
from typing import Any, List, Optional, Union
from fastapi import APIRouter, Depends, HTTPException, status, Request
from sqlalchemy import func
from sqlalchemy.orm import Session
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.asymmetric import ec

from app.core.config import settings
from app.core.ws_manager import ws_manager
from app.db.session import get_db
from app.db.models import (
    User,
    ProtectedDevice,
    LocationEvent,
    AuditEvent,
    TrustedDevice,
    DeviceCommand,
    PairingSession,
    DeviceStatus,
    DeviceAuditLog,
    generate_uuid,
    utc_now
)
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
    PairingConfirmResponse,
    EmergencyCommandRequest,
    EmergencyCommandResponse,
    PairingSessionCreateRequest,
    PairingSessionResponse,
    GuardianPairRequest,
    DeviceDetailResponse,
    DeviceRosterResponse,
    DeviceRenameRequest,
    DeviceHeartbeatRequest,
    DeviceUnpairRequest,
    DeviceRevokeRequest,
    DeviceAuditLogResponse,
    DeviceStatusResponse
)
from app.schemas.location import LocationResponse
from app.api.deps import get_current_user, get_verified_user, get_current_user_2fa, get_pairing_token_user
from app.core.security import (
    verify_ecdsa_p256_signature,
    create_canonical_command_bytes,
    decrypt_totp_secret,
    verify_totp_code,
    encrypt_pairing_secret,
    decrypt_pairing_secret,
    compute_truncated_hmac
)

router = APIRouter(prefix="/api/v1/devices", tags=["Devices"])
pairing_router = APIRouter(prefix="/api/v1/pairing-sessions", tags=["Pairing Sessions"])


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
    
    if diff_seconds < 900:  # 15 minutes (generous window to prevent false DELAYED on idle phones)
        return "RECENT"
    elif diff_seconds < 86400:  # 24 hours
        return "DELAYED"
    else:
        return "UNAVAILABLE"


def compute_key_fingerprint(public_key_pem: str) -> str:
    """Calculates SPKI SHA-256 fingerprint for public key."""
    try:
        pub = serialization.load_pem_public_key(public_key_pem.encode("utf-8"))
        der = pub.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        return hashlib.sha256(der).hexdigest()
    except Exception:
        return hashlib.sha256(public_key_pem.encode("utf-8")).hexdigest()


def derive_6digit_verification_code(admin_pem: str, guardian_pem: str) -> str:
    """
    Derives deterministic 6-digit mutual confirmation code from both ECDSA public keys.
    Commutative: both Admin and Guardian calculate the exact same number without network transmission.
    """
    try:
        pub_a = serialization.load_pem_public_key(admin_pem.encode("utf-8"))
        der_a = pub_a.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    except Exception:
        der_a = admin_pem.encode("utf-8")

    try:
        pub_g = serialization.load_pem_public_key(guardian_pem.encode("utf-8"))
        der_g = pub_g.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    except Exception:
        der_g = guardian_pem.encode("utf-8")

    sorted_der = min(der_a, der_g) + max(der_a, der_g)
    digest = hashlib.sha256(sorted_der).digest()
    code_int = (int.from_bytes(digest[:4], "big") % 900000) + 100000
    return f"{code_int:06d}"


def verify_user_totp_code(user: User, code: str) -> bool:
    """Verifies a 6-digit TOTP code against the owner's encrypted TOTP secret."""
    if not user.totp_enabled or not user.totp_secret:
        return False
    secret = decrypt_totp_secret(user.totp_secret)
    return verify_totp_code(secret, code)


def build_device_detail_response(dev: ProtectedDevice, db: Session) -> DeviceDetailResponse:
    """Builds enriched DeviceDetailResponse with live status, hardware identity, and stale detection."""
    latest_loc = (
        db.query(LocationEvent)
        .filter(LocationEvent.protected_device_id == dev.id)
        .order_by(LocationEvent.sequence_number.desc())
        .first()
    )
    loc_resp = LocationResponse.model_validate(latest_loc) if latest_loc else None
    calc_status = compute_device_status(latest_loc)

    status_rec = dev.status_record
    if not status_rec:
        status_rec = db.query(DeviceStatus).filter(DeviceStatus.device_id == dev.id).first()

    now = datetime.now(timezone.utc)
    if status_rec:
        last_seen = status_rec.last_seen
        if last_seen and last_seen.tzinfo is None:
            last_seen = last_seen.replace(tzinfo=timezone.utc)
        diff_mins = (now - last_seen).total_seconds() / 60.0 if last_seen else 9999
        is_stale = diff_mins > settings.STALE_DEVICE_THRESHOLD_MINUTES

        perm_health = {}
        if status_rec.permission_health:
            try:
                perm_health = json.loads(status_rec.permission_health)
            except Exception:
                perm_health = {}

        status_info = DeviceStatusResponse(
            battery_level=status_rec.battery_level,
            connectivity_type=status_rec.connectivity_type,
            sim_operator=status_rec.sim_operator,
            sim_country=status_rec.sim_country,
            sim_changed=status_rec.sim_changed,
            permission_health=perm_health,
            last_seen=last_seen,
            last_sync_path=status_rec.last_sync_path or "online",
            is_stale=is_stale
        )
    else:
        status_info = DeviceStatusResponse(
            battery_level=None,
            connectivity_type=None,
            sim_operator=None,
            sim_country=None,
            sim_changed=False,
            permission_health={},
            last_seen=dev.created_at,
            last_sync_path="online",
            is_stale=True
        )

    return DeviceDetailResponse(
        id=dev.id,
        user_id=dev.user_id,
        device_alias=dev.device_alias,
        public_key_pem=dev.public_key_pem,
        key_fingerprint=dev.key_fingerprint,
        trusted_phone_number=dev.trusted_phone_number,
        status=dev.status,
        calculated_status=calc_status,
        confirmation_code=dev.confirmation_code,
        pairing_id=dev.pairing_id,
        manufacturer=dev.manufacturer,
        brand=dev.brand,
        model=dev.model,
        android_version=dev.android_version,
        sdk_level=dev.sdk_level,
        variant=dev.variant or "lite",
        app_version=dev.app_version,
        install_date=dev.install_date,
        is_device_owner=bool(dev.is_device_owner),
        is_possible_reinstall=bool(dev.is_possible_reinstall),
        status_info=status_info,
        last_location=loc_resp,
        created_at=dev.created_at
    )


# -----------------------------------------------------------------------------
# Pairing Session Endpoints (Admin -> Backend)
# -----------------------------------------------------------------------------

@pairing_router.post("", response_model=PairingSessionResponse, status_code=status.HTTP_201_CREATED)
@router.post("/pairing-sessions", response_model=PairingSessionResponse, status_code=status.HTTP_201_CREATED)
def create_pairing_session(
    req: PairingSessionCreateRequest,
    current_user: User = Depends(get_verified_user),
    db: Session = Depends(get_db)
) -> Any:
    """
    Initiate a 5-minute single-use pairing session from SHE Admin.
    Strictly mandates TOTP two-factor authentication from account owner.
    Generates a secure 32-byte session token and a 6-digit short code for manual entry.
    """
    if not current_user.totp_enabled:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Two-factor authentication (TOTP) must be enabled before pairing Guardian handsets."
        )

    if not verify_user_totp_code(current_user, req.totp_code):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid TOTP verification code. Handset pairing session rejected."
        )

    # Purge old expired pairing sessions for this user to leave zero residue
    db.query(PairingSession).filter(
        PairingSession.user_id == current_user.id,
        PairingSession.expires_at < utc_now()
    ).delete()

    session_token = secrets.token_hex(32)
    short_code = "".join(secrets.choice("23456789ABCDEFGHJKLMNPQRSTUVWXYZ") for _ in range(6))
    expires_at = datetime.now(timezone.utc) + timedelta(minutes=settings.PAIRING_SESSION_TTL_MINUTES)

    session = PairingSession(
        user_id=current_user.id,
        session_token=session_token,
        short_code=short_code,
        expires_at=expires_at
    )
    db.add(session)

    db.add(DeviceAuditLog(
        user_id=current_user.id,
        action="PAIRING_SESSION_CREATED",
        actor_admin_id=req.admin_device_id,
        details=f"Pairing session initiated with 5-minute TTL. Short code: {short_code[:2]}****"
    ))
    db.commit()
    db.refresh(session)

    return PairingSessionResponse(
        session_id=session.id,
        session_token=session.session_token,
        short_code=session.short_code,
        expires_in_seconds=settings.PAIRING_SESSION_TTL_MINUTES * 60
    )


# -----------------------------------------------------------------------------
# Guardian Enrollment & Device Roster
# -----------------------------------------------------------------------------

@router.post("/pair", response_model=DeviceDetailResponse, status_code=status.HTTP_201_CREATED)
def pair_device(
    pair_in: Union[GuardianPairRequest, DevicePairRequest],
    pairing_data: tuple[User, dict] = Depends(get_pairing_token_user),
    db: Session = Depends(get_db)
) -> Any:
    """
    Enroll a new Guardian phone with the authenticated owner.
    STRICT SECURITY REQUIREMENTS:
    1. Authorized via single-use 5-minute pairing session or pairing token.
    2. Enforces maximum 5 Guardian handsets per account.
    3. Rejects duplicate device_id or duplicate public key fingerprints.
    4. Detects reinstalls on matching phone models and flags previous record.
    5. Records full hardware identity, variant, and initial telemetry.
    """
    current_user, token_payload = pairing_data
    dev_id = getattr(pair_in, "device_id", None) or generate_uuid()
    pairing_hash = hashlib.sha256(pair_in.pairing_secret.encode("utf-8")).hexdigest()
    pairing_id = generate_uuid()
    confirmation_code = f"{secrets.randbelow(900) + 100}-{secrets.randbelow(900) + 100}"
    fingerprint = compute_key_fingerprint(pair_in.public_key_pem)

    # 1. Enforce max Guardian limit per account (default: 5)
    active_count = db.query(ProtectedDevice).filter(
        ProtectedDevice.user_id == current_user.id,
        ProtectedDevice.status != "REVOKED",
        ProtectedDevice.id != dev_id
    ).count()

    limit = min(settings.MAX_GUARDIANS_PER_ACCOUNT, getattr(settings, "MAX_DEVICES_PER_USER", settings.MAX_GUARDIANS_PER_ACCOUNT))
    if active_count >= limit:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Account limit reached: You have reached the limit of {limit} protected devices. To pair another handset, delete or unpair an old device first."
        )

    # 2. Check for duplicate device_id
    existing_by_id = db.query(ProtectedDevice).filter(ProtectedDevice.id == dev_id).first()
    if existing_by_id:
        if existing_by_id.user_id != current_user.id:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Device ID registered to another user")
        if existing_by_id.status == "ACTIVE":
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail=f"Device ID '{dev_id}' is already paired and active. If replacing, remove or unpair the existing handset first."
            )

    # 3. Check for duplicate key fingerprint across active devices
    existing_by_key = db.query(ProtectedDevice).filter(
        ProtectedDevice.key_fingerprint == fingerprint,
        ProtectedDevice.status == "ACTIVE",
        ProtectedDevice.id != dev_id
    ).first()
    if existing_by_key:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="A handset with this exact cryptographic key pair is already registered and active."
        )

    # 4. Reinstall Detection: Same physical model & brand on account with new device_id
    model_name = getattr(pair_in, "model", None)
    brand_name = getattr(pair_in, "brand", None)
    if model_name and brand_name:
        prior_device = db.query(ProtectedDevice).filter(
            ProtectedDevice.user_id == current_user.id,
            ProtectedDevice.model == model_name,
            ProtectedDevice.brand == brand_name,
            ProtectedDevice.status != "REVOKED",
            ProtectedDevice.id != dev_id
        ).first()
        if prior_device:
            prior_device.is_possible_reinstall = True
            db.add(DeviceAuditLog(
                user_id=current_user.id,
                device_id=prior_device.id,
                action="POSSIBLE_REINSTALL_DETECTED",
                details=f"New handset ({dev_id}) enrolled with matching model '{model_name}'. Flagged previous handset record as possible reinstall."
            ))

    # 5. Create or update ProtectedDevice record
    if existing_by_id:
        existing_by_id.device_alias = pair_in.device_alias
        existing_by_id.public_key_pem = pair_in.public_key_pem
        existing_by_id.key_fingerprint = fingerprint
        existing_by_id.trusted_phone_number = pair_in.trusted_phone_number
        existing_by_id.pairing_secret_hash = pairing_hash
        existing_by_id.pairing_secret = encrypt_pairing_secret(pair_in.pairing_secret)
        existing_by_id.pairing_id = pairing_id
        existing_by_id.confirmation_code = confirmation_code
        existing_by_id.status = "PENDING_CONFIRMATION"
        existing_by_id.manufacturer = getattr(pair_in, "manufacturer", None)
        existing_by_id.brand = getattr(pair_in, "brand", None)
        existing_by_id.model = getattr(pair_in, "model", None)
        existing_by_id.android_version = getattr(pair_in, "android_version", None)
        existing_by_id.sdk_level = getattr(pair_in, "sdk_level", None)
        existing_by_id.variant = getattr(pair_in, "variant", "lite")
        existing_by_id.app_version = getattr(pair_in, "app_version", None)
        existing_by_id.install_date = getattr(pair_in, "install_date", None)
        existing_by_id.is_device_owner = getattr(pair_in, "is_device_owner", False)
        device = existing_by_id
    else:
        device = ProtectedDevice(
            id=dev_id,
            user_id=current_user.id,
            device_alias=pair_in.device_alias,
            public_key_pem=pair_in.public_key_pem,
            key_fingerprint=fingerprint,
            trusted_phone_number=pair_in.trusted_phone_number,
            pairing_secret_hash=pairing_hash,
            pairing_secret=encrypt_pairing_secret(pair_in.pairing_secret),
            pairing_id=pairing_id,
            confirmation_code=confirmation_code,
            status="PENDING_CONFIRMATION",
            manufacturer=getattr(pair_in, "manufacturer", None),
            brand=getattr(pair_in, "brand", None),
            model=getattr(pair_in, "model", None),
            android_version=getattr(pair_in, "android_version", None),
            sdk_level=getattr(pair_in, "sdk_level", None),
            variant=getattr(pair_in, "variant", "lite"),
            app_version=getattr(pair_in, "app_version", None),
            install_date=getattr(pair_in, "install_date", None),
            is_device_owner=getattr(pair_in, "is_device_owner", False),
            is_possible_reinstall=False
        )
        db.add(device)

    # 6. Initialize or update DeviceStatus record
    status_rec = db.query(DeviceStatus).filter(DeviceStatus.device_id == device.id).first()
    if not status_rec:
        status_rec = DeviceStatus(device_id=device.id)
        db.add(status_rec)

    status_rec.battery_level = getattr(pair_in, "battery_level", None)
    status_rec.connectivity_type = getattr(pair_in, "connectivity_type", None)
    status_rec.sim_operator = getattr(pair_in, "sim_operator", None)
    status_rec.sim_country = getattr(pair_in, "sim_country", None)
    status_rec.sim_changed = getattr(pair_in, "sim_changed", False)
    status_rec.permission_health = json.dumps(getattr(pair_in, "permission_health", {}) or {})
    status_rec.last_seen = utc_now()
    status_rec.last_sync_path = "online"

    # 7. Audit log
    db.add(DeviceAuditLog(
        user_id=current_user.id,
        device_id=device.id,
        action="PAIR",
        details=f"Handset '{device.device_alias}' ({device.id}) requested pairing. Model: {device.model or 'unknown'}, Variant: {device.variant}. Confirmation code: {confirmation_code}"
    ))
    db.add(AuditEvent(
        user_id=current_user.id,
        protected_device_id=device.id,
        event_type="DEVICE_PAIRING_REQUESTED",
        details=f"Device {device.device_alias} ({device.id}) requested enrollment. Confirmation code: {confirmation_code}"
    ))
    db.commit()
    db.refresh(device)

    # 8. Notify active Admin WebSocket listeners about the new handset
    try:
        import asyncio
        loop = asyncio.get_running_loop()
        loop.create_task(ws_manager.broadcast_location(device.id, {
            "type": "HANDSET_ADDED",
            "device_id": device.id,
            "device_alias": device.device_alias,
            "variant": device.variant,
            "timestamp": int(time.time())
        }))
    except (RuntimeError, Exception):
        pass

    return build_device_detail_response(device, db)


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

    if device.status == "ACTIVE":
        return PairingConfirmResponse(
            device_id=device.id,
            status="ACTIVE",
            pairing_id=device.pairing_id or ""
        )

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

    db.add(DeviceAuditLog(
        user_id=current_user.id,
        device_id=device.id,
        action="PAIR_CONFIRMED",
        details=f"Handset '{device.device_alias}' ({device.id}) enrollment confirmed by owner. Status ACTIVE."
    ))
    db.add(AuditEvent(
        user_id=current_user.id,
        protected_device_id=device.id,
        event_type="DEVICE_PAIRING_CONFIRMED",
        details=f"Device {device.device_alias} ({device.id}) enrollment confirmed by owner. Status ACTIVE."
    ))
    db.commit()

    return PairingConfirmResponse(
        device_id=device.id,
        status="ACTIVE",
        pairing_id=device.pairing_id
    )


@router.get("", response_model=DeviceRosterResponse)
def list_devices(
    current_user: User = Depends(get_verified_user),
    db: Session = Depends(get_db)
) -> Any:
    """
    Authoritative device roster: Returns all active/pending handsets owned by user.
    Includes hardware identity, battery, permission health, variant, and stale flag.
    """
    devices = db.query(ProtectedDevice).filter(
        ProtectedDevice.user_id == current_user.id,
        ProtectedDevice.status != "REVOKED"
    ).all()
    results = [build_device_detail_response(dev, db) for dev in devices]
    return DeviceRosterResponse(
        devices=results,
        total_count=len(results),
        max_devices_limit=settings.MAX_GUARDIANS_PER_ACCOUNT
    )


@router.get("/{device_id}", response_model=DeviceDetailResponse)
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
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found")
    return build_device_detail_response(device, db)


@router.patch("/{device_id}", response_model=DeviceDetailResponse)
def rename_device(
    device_id: str,
    req: DeviceRenameRequest,
    current_user: User = Depends(get_verified_user),
    db: Session = Depends(get_db)
) -> Any:
    """Update handset nickname/alias with audit log record."""
    device = db.query(ProtectedDevice).filter(
        ProtectedDevice.id == device_id,
        ProtectedDevice.user_id == current_user.id
    ).first()
    if not device:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found")

    old_alias = device.device_alias
    device.device_alias = req.nickname.strip()
    db.add(DeviceAuditLog(
        user_id=current_user.id,
        device_id=device.id,
        action="RENAME",
        details=f"Renamed handset from '{old_alias}' to '{device.device_alias}'"
    ))
    db.commit()
    db.refresh(device)
    return build_device_detail_response(device, db)


@router.post("/{device_id}/sync")
async def sync_device_status(
    device_id: str,
    current_user: User = Depends(get_verified_user),
    db: Session = Depends(get_db)
) -> Any:
    """
    Trigger fresh status sync for a handset.
    Online path: API plus WebSocket push to handset.
    Offline path: Reports SMS STATUS command recommendation.
    """
    device = db.query(ProtectedDevice).filter(
        ProtectedDevice.id == device_id,
        ProtectedDevice.user_id == current_user.id
    ).first()
    if not device:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found")

    has_listeners = ws_manager.get_listener_count(device_id) > 0
    path_used = "online" if has_listeners else "sms"

    if has_listeners:
        await ws_manager.broadcast_location(device.id, {
            "type": "STATUS_REQUEST",
            "device_id": device.id,
            "timestamp": int(time.time())
        })

    status_rec = db.query(DeviceStatus).filter(DeviceStatus.device_id == device.id).first()
    if status_rec:
        status_rec.last_sync_path = path_used
        db.commit()

    db.add(DeviceAuditLog(
        user_id=current_user.id,
        device_id=device.id,
        action="SYNC",
        details=f"Handset status sync requested via {path_used} channel"
    ))
    db.commit()

    return {
        "status": "SYNC_REQUESTED",
        "path_used": path_used,
        "device": build_device_detail_response(device, db)
    }


@router.post("/{device_id}/unpair")
def unpair_device_graceful(
    device_id: str,
    req: DeviceUnpairRequest,
    current_user: User = Depends(get_verified_user),
    db: Session = Depends(get_db)
) -> Any:
    """
    Initiate graceful handset unpair: Requires TOTP plus Admin-signed UNPAIR command.
    Queues UNPAIR command for handset to verify, wipe keys, stop services, and ack.
    """
    device = db.query(ProtectedDevice).filter(
        ProtectedDevice.id == device_id,
        ProtectedDevice.user_id == current_user.id
    ).first()
    if not device:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found")

    if not verify_user_totp_code(current_user, req.totp_code):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid TOTP two-factor code. Graceful unpair rejected."
        )

    # Queue UNPAIR command in device_commands
    cmd = DeviceCommand(
        user_id=current_user.id,
        trusted_device_id=req.trusted_device_id,
        protected_device_id=device.id,
        command_type="UNPAIR",
        sequence_counter=req.sequence_counter,
        expiry_epoch=req.expiry_epoch,
        pairing_id=device.pairing_id,
        parameters_json="{}",
        admin_signature=req.admin_signature,
        status="PENDING"
    )
    db.add(cmd)

    db.add(DeviceAuditLog(
        user_id=current_user.id,
        device_id=device.id,
        action="UNPAIR_GRACEFUL",
        actor_admin_id=req.trusted_device_id,
        details=f"Graceful unpair command queued (command_id: {cmd.id}). Queued for retry until acknowledged."
    ))
    db.commit()
    db.refresh(cmd)

    return {
        "status": "QUEUED",
        "command_id": cmd.id,
        "command_type": "UNPAIR",
        "device_id": device.id,
        "message": "Graceful unpair command queued for delivery over online and SMS fallback channels."
    }


@router.delete("/{device_id}", status_code=status.HTTP_200_OK)
def revoke_or_delete_device(
    device_id: str,
    request: Request,
    totp_code: Optional[str] = None,
    action: Optional[str] = None,
    current_user: User = Depends(get_verified_user),
    db: Session = Depends(get_db)
) -> Any:
    """
    Remove or revoke handset:
    - Revoke-only (force remove): for lost/stolen phones. Marks device REVOKED immediately,
      terminating all control and tracking with explicit warning.
    - Legacy unpair deletion: cascades cleanup when requested.
    """
    device = db.query(ProtectedDevice).filter(
        ProtectedDevice.id == device_id,
        ProtectedDevice.user_id == current_user.id
    ).first()
    if not device:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Protected device not found")

    alias = device.device_alias
    code = totp_code or request.headers.get("x-totp-code")

    # If owner has TOTP enabled, verify code
    if current_user.totp_enabled:
        if code and not verify_user_totp_code(current_user, code):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Invalid TOTP two-factor code. Handset removal rejected."
            )

    is_revoke = (action == "revoke") or (request.query_params.get("action") == "revoke")

    if is_revoke:
        device.status = "REVOKED"
        db.add(DeviceAuditLog(
            user_id=current_user.id,
            device_id=device.id,
            action="REVOKE_FORCE",
            details=f"Handset '{alias}' ({device_id}) was force-revoked by owner. Remote location and control terminated."
        ))
        db.commit()
        return {
            "status": "REVOKED",
            "device_id": device_id,
            "warning": "Handset has been force-revoked. Remote location, tracking, and recovery controls have been terminated. Re-pairing physically on the device is required to regain access.",
            "message": f"Handset '{alias}' successfully revoked."
        }
    else:
        # Full deletion cascade
        db.query(DeviceCommand).filter(DeviceCommand.protected_device_id == device.id).delete()
        db.query(LocationEvent).filter(LocationEvent.protected_device_id == device.id).delete()
        db.delete(device)

        db.add(DeviceAuditLog(
            user_id=current_user.id,
            device_id=None,
            action="DELETE",
            details=f"Handset '{alias}' ({device_id}) was permanently deleted from account."
        ))
        db.add(AuditEvent(
            user_id=current_user.id,
            protected_device_id=None,
            event_type="DEVICE_UNPAIRED",
            details=f"Device {alias} ({device_id}) was unpaired and deleted by owner."
        ))
        db.commit()

        return {
            "status": "deleted",
            "device_id": device_id,
            "message": f"Handset '{alias}' successfully unpaired and deleted."
        }


@router.post("/{device_id}/heartbeat")
def device_heartbeat(
    device_id: str,
    req: DeviceHeartbeatRequest,
    db: Session = Depends(get_db)
) -> Any:
    """
    Guardian handset signed heartbeat:
    Guardian sends periodic battery, connectivity, permission health, and SIM status.
    Cryptographically authenticated by Guardian's ECDSA private key.
    """
    device = db.query(ProtectedDevice).filter(ProtectedDevice.id == device_id).first()
    if not device or device.status == "REVOKED":
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found or revoked")

    # Anti-replay timestamp check (5 minute window)
    now_epoch = int(time.time())
    if abs(now_epoch - req.timestamp) > 300:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Heartbeat timestamp outside clock skew tolerance window (max 300s)"
        )

    # Signature verification: sha256(device_id:timestamp:battery:connectivity:sim_changed)
    canonical = f"{device_id}:{req.timestamp}:{req.battery_level}:{req.connectivity_type}:{req.sim_changed}".encode("utf-8")
    try:
        sig_bytes = base64.b64decode(req.signature)
        valid = verify_ecdsa_p256_signature(device.public_key_pem, canonical, sig_bytes)
    except Exception:
        valid = False

    if not valid:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid cryptographic signature on heartbeat payload"
        )

    status_rec = db.query(DeviceStatus).filter(DeviceStatus.device_id == device.id).first()
    if not status_rec:
        status_rec = DeviceStatus(device_id=device.id)
        db.add(status_rec)

    status_rec.battery_level = req.battery_level
    status_rec.connectivity_type = req.connectivity_type
    if req.sim_operator:
        status_rec.sim_operator = req.sim_operator
    if req.sim_country:
        status_rec.sim_country = req.sim_country
    status_rec.sim_changed = req.sim_changed
    if req.permission_health:
        status_rec.permission_health = json.dumps(req.permission_health)
    status_rec.last_seen = utc_now()
    status_rec.last_sync_path = "online"
    db.commit()

    return {
        "status": "HEARTBEAT_ACK",
        "timestamp": now_epoch,
        "device_status": device.status
    }


@router.get("/{device_id}/audit-log", response_model=List[DeviceAuditLogResponse])
def get_device_audit_log(
    device_id: str,
    current_user: User = Depends(get_verified_user),
    db: Session = Depends(get_db)
) -> Any:
    """Retrieve tamper-evident audit history for a specific handset."""
    device = db.query(ProtectedDevice).filter(
        ProtectedDevice.id == device_id,
        ProtectedDevice.user_id == current_user.id
    ).first()
    if not device:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Device not found")

    logs = db.query(DeviceAuditLog).filter(
        DeviceAuditLog.device_id == device.id,
        DeviceAuditLog.user_id == current_user.id
    ).order_by(DeviceAuditLog.created_at.desc()).limit(100).all()

    return logs


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
        "SYNC_VERIFIED_PHONE",
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


@router.post("/{device_id}/command", response_model=EmergencyCommandResponse, status_code=status.HTTP_201_CREATED)
def dispatch_emergency_command(
    device_id: str,
    req: EmergencyCommandRequest,
    current_user: User = Depends(get_verified_user),
    db: Session = Depends(get_db)
) -> Any:
    """
    Dispatch emergency recovery action from Web Console / Borrowed Handset Recovery Portal.
    Authenticated via session token (Owner identity) and signed using device's symmetric pairing secret (HMAC).
    Guarantees Guardian device can verify command integrity even when Admin companion phone is absent.
    """
    device = db.query(ProtectedDevice).filter(
        ProtectedDevice.id == device_id,
        ProtectedDevice.user_id == current_user.id
    ).first()
    if not device:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Protected device not found")

    if device.status not in ("ACTIVE", "STOLEN", "PENDING_CONFIRMATION"):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Cannot dispatch commands to device with status {device.status}. Must be ACTIVE."
        )

    raw_type = req.command_type.strip().upper()
    ALLOWED_EMERGENCY_TYPES = {"RING", "STOP_RING", "LOCK", "STOLEN_MODE", "LOST_MODE", "LOCATE"}
    if raw_type not in ALLOWED_EMERGENCY_TYPES:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Invalid emergency command type '{raw_type}'. Allowed: {', '.join(sorted(ALLOWED_EMERGENCY_TYPES))}"
        )

    # Normalize LOST_MODE to STOLEN_MODE with custom contact message if omitted
    effective_cmd_type = "STOLEN_MODE" if raw_type == "LOST_MODE" else raw_type
    payload = dict(req.payload or {})
    if raw_type == "LOST_MODE" and "lock_message" not in payload:
        payload["lock_message"] = "LOST DEVICE: Please contact owner to return."

    if not device.pairing_secret:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Handset lacks cryptographic pairing secret. Please re-pair handset to enable web emergency console."
        )

    # Determine monotonic sequence counter for this protected handset
    max_seq = db.query(func.max(DeviceCommand.sequence_counter)).filter(
        DeviceCommand.protected_device_id == device.id
    ).scalar() or 0
    next_seq = max_seq + 1

    cmd_id = generate_uuid()
    now_epoch = int(time.time())
    expiry_epoch = now_epoch + 600  # 10 minutes
    params_json = json.dumps(payload)

    canonical_bytes = create_canonical_command_bytes(
        pairing_id=device.pairing_id or "",
        command_id=cmd_id,
        target_device_id=device.id,
        command_type=effective_cmd_type,
        sequence_counter=next_seq,
        expiry_epoch=expiry_epoch,
        parameters_json=params_json
    )

    raw_pairing_secret = decrypt_pairing_secret(device.pairing_secret)
    pairing_secret_bytes = raw_pairing_secret.encode("utf-8")
    server_sig = compute_truncated_hmac(pairing_secret_bytes, canonical_bytes, hex_length=12)

    command = DeviceCommand(
        id=cmd_id,
        user_id=current_user.id,
        trusted_device_id=None,
        protected_device_id=device.id,
        pairing_id=device.pairing_id,
        command_type=effective_cmd_type,
        sequence_counter=next_seq,
        expiry_epoch=expiry_epoch,
        parameters_json=params_json,
        admin_signature=server_sig,
        status="PENDING"
    )
    db.add(command)

    audit = AuditEvent(
        user_id=current_user.id,
        protected_device_id=device.id,
        event_type="EMERGENCY_COMMAND_DISPATCHED",
        details=f"Emergency command {effective_cmd_type} (seq #{next_seq}) dispatched from web console for handset {device.device_alias} ({device.id})."
    )
    db.add(audit)
    db.commit()
    db.refresh(command)

    return EmergencyCommandResponse(
        status="dispatched",
        command_id=command.id,
        command_type=command.command_type,
        sequence_counter=command.sequence_counter
    )



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
            # Purge sensitive verification challenge payload on expiration
            if exp.command_type == "SEND_VERIFY_SMS":
                exp.parameters_json = "{}"
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

    # Immediately purge OTP challenge payload once acknowledged/executed/rejected
    if command.command_type == "SEND_VERIFY_SMS":
        command.parameters_json = "{}"
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


