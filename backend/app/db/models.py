import uuid
from datetime import datetime, timezone
from sqlalchemy import (
    Column,
    String,
    Integer,
    Float,
    BigInteger,
    DateTime,
    ForeignKey,
    Text,
    Boolean,
    UniqueConstraint
)
from sqlalchemy.orm import relationship
from app.db.session import Base


def generate_uuid() -> str:
    return str(uuid.uuid4())


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class User(Base):
    __tablename__ = "users"

    id = Column(String(36), primary_key=True, default=generate_uuid)
    username = Column(String(64), unique=True, index=True, nullable=False)
    email = Column(String(255), index=True, nullable=False)
    hashed_password = Column(String(255), nullable=False)
    totp_secret = Column(Text, nullable=True)
    totp_enabled = Column(Boolean, default=False, nullable=False)
    totp_recovery_hashes = Column(Text, nullable=True, default="[]")
    phone_number = Column(String(32), nullable=True)
    device_info = Column(Text, nullable=True)
    email_verified_at = Column(DateTime(timezone=True), nullable=True)
    fallback_phone_e164 = Column(String(32), nullable=True)
    phone_verified_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(DateTime(timezone=True), default=utc_now, nullable=False)

    trusted_devices = relationship("TrustedDevice", back_populates="user", cascade="all, delete-orphan")
    protected_devices = relationship("ProtectedDevice", back_populates="user", cascade="all, delete-orphan")
    commands = relationship("DeviceCommand", back_populates="user", cascade="all, delete-orphan")
    vault_items = relationship("VaultItem", back_populates="user", cascade="all, delete-orphan")
    verification_challenges = relationship("VerificationChallenge", back_populates="user", cascade="all, delete-orphan")
    sessions = relationship("UserSession", back_populates="user", cascade="all, delete-orphan")
    pairing_sessions = relationship("PairingSession", back_populates="user", cascade="all, delete-orphan")
    device_audit_logs = relationship("DeviceAuditLog", back_populates="user", cascade="all, delete-orphan")
    consumed_totp_codes = relationship("ConsumedTotpCode", back_populates="user", cascade="all, delete-orphan")


class VerificationChallenge(Base):
    """Temporary verification challenge codes for email OTP and phone SMS fallback verification."""
    __tablename__ = "verification_challenges"

    id = Column(String(36), primary_key=True, default=generate_uuid)
    user_id = Column(String(36), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    channel = Column(String(16), nullable=False)  # "email" | "sms"
    target = Column(String(255), nullable=False)
    code_hash = Column(String(64), nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False)
    attempts = Column(BigInteger, default=0, nullable=False)
    consumed_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(DateTime(timezone=True), default=utc_now, nullable=False)

    user = relationship("User", back_populates="verification_challenges")


class ConsumedTotpCode(Base):
    """Tracks consumed single-use TOTP codes per user to prevent replay attacks across multiple workers and restarts."""
    __tablename__ = "consumed_totp_codes"

    id = Column(String(36), primary_key=True, default=generate_uuid)
    user_id = Column(String(36), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    code_hash = Column(String(64), nullable=False, index=True)
    expires_at = Column(DateTime(timezone=True), nullable=False, index=True)
    consumed_at = Column(DateTime(timezone=True), default=utc_now, nullable=False)

    __table_args__ = (
        UniqueConstraint("user_id", "code_hash", name="uq_user_consumed_totp_code"),
    )

    user = relationship("User", back_populates="consumed_totp_codes")


class TrustedDevice(Base):
    """Admin / Owner control devices authorized to manage protected phones."""
    __tablename__ = "trusted_devices"

    id = Column(String(36), primary_key=True, default=generate_uuid)
    user_id = Column(String(36), ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    device_name = Column(String(128), nullable=False)
    public_key_pem = Column(Text, nullable=False)
    created_at = Column(DateTime(timezone=True), default=utc_now, nullable=False)
    revoked_at = Column(DateTime(timezone=True), nullable=True)
    is_active = Column(Boolean, default=True, nullable=False)

    user = relationship("User", back_populates="trusted_devices")


class ProtectedDevice(Base):
    """Phones being tracked and protected by Guardian."""
    __tablename__ = "protected_devices"

    id = Column(String(36), primary_key=True, default=generate_uuid)
    user_id = Column(String(36), ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    device_alias = Column(String(64), nullable=False)
    public_key_pem = Column(Text, nullable=False)
    trusted_phone_number = Column(String(32), nullable=False)
    pairing_secret_hash = Column(String(128), nullable=False)
    pairing_secret = Column(String(256), nullable=True)  # AES-256-GCM encrypted pairing secret at rest
    pairing_id = Column(String(36), default=generate_uuid, nullable=False)
    confirmation_code = Column(String(16), nullable=True)
    created_at = Column(DateTime(timezone=True), default=utc_now, nullable=False)
    status = Column(String(32), default="ACTIVE", nullable=False)  # ACTIVE, PENDING_CONFIRMATION, STOLEN, REVOKED

    # Device Identity & Hardware Specs
    key_fingerprint = Column(String(64), nullable=True, index=True)
    manufacturer = Column(String(64), nullable=True)
    brand = Column(String(64), nullable=True)
    model = Column(String(64), nullable=True)
    android_version = Column(String(32), nullable=True)
    sdk_level = Column(Integer, nullable=True)

    # Guardian Variant & Lifecycle
    variant = Column(String(32), default="lite", nullable=False)  # "lite" | "hardened"
    app_version = Column(String(32), nullable=True)
    install_date = Column(DateTime(timezone=True), nullable=True)
    is_device_owner = Column(Boolean, default=False, nullable=False)
    is_possible_reinstall = Column(Boolean, default=False, nullable=False)

    user = relationship("User", back_populates="protected_devices")
    location_events = relationship("LocationEvent", back_populates="protected_device", cascade="all, delete-orphan")
    status_record = relationship("DeviceStatus", uselist=False, back_populates="device", cascade="all, delete-orphan")
    audit_logs = relationship("DeviceAuditLog", back_populates="device", cascade="all, delete-orphan")


class LocationEvent(Base):
    """Signed GNSS/network location trace records."""
    __tablename__ = "location_events"

    id = Column(String(36), primary_key=True, default=generate_uuid)
    protected_device_id = Column(String(36), ForeignKey("protected_devices.id", ondelete="CASCADE"), nullable=False, index=True)
    latitude = Column(Float, nullable=False)
    longitude = Column(Float, nullable=False)
    accuracy = Column(Float, nullable=False)
    altitude = Column(Float, nullable=True)
    velocity = Column(Float, nullable=True)
    capture_time = Column(DateTime(timezone=True), nullable=False, index=True)
    server_receipt_time = Column(DateTime(timezone=True), default=utc_now, nullable=False)
    sequence_number = Column(BigInteger, nullable=False)
    signature = Column(Text, nullable=False)

    __table_args__ = (
        UniqueConstraint("protected_device_id", "sequence_number", name="uq_device_sequence"),
    )

    protected_device = relationship("ProtectedDevice", back_populates="location_events")


class AuditEvent(Base):
    """Tamper-evident audit log for device security events."""
    __tablename__ = "audit_events"

    id = Column(String(36), primary_key=True, default=generate_uuid)
    user_id = Column(String(36), ForeignKey("users.id", ondelete="SET NULL"), nullable=True)
    protected_device_id = Column(String(36), ForeignKey("protected_devices.id", ondelete="CASCADE"), nullable=True)
    event_type = Column(String(64), nullable=False)
    details = Column(Text, nullable=True)
    created_at = Column(DateTime(timezone=True), default=utc_now, nullable=False)


class DeviceCommand(Base):
    """
    Remote recovery commands signed by Owner Admin device and verified by Guardian device.
    Includes command_id, target device, command_type, sequence_counter, expiry, parameters, and Admin ECDSA signature.
    """
    __tablename__ = "device_commands"

    id = Column(String(36), primary_key=True, default=generate_uuid)
    user_id = Column(String(36), ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    trusted_device_id = Column(String(36), ForeignKey("trusted_devices.id", ondelete="CASCADE"), nullable=True)
    protected_device_id = Column(String(36), ForeignKey("protected_devices.id", ondelete="CASCADE"), nullable=False)
    command_type = Column(String(32), nullable=False)  # LOCATE, RING, LOCK, STOLEN_MODE
    sequence_counter = Column(BigInteger, nullable=False)
    expiry_epoch = Column(BigInteger, nullable=False)
    pairing_id = Column(String(36), nullable=True)
    parameters_json = Column(Text, default="{}", nullable=False)
    admin_signature = Column(Text, nullable=False)  # Base64 ASN.1 DER signature from Admin device key
    status = Column(String(32), default="PENDING", nullable=False)  # PENDING, DELIVERED, EXECUTED, EXPIRED, FAILED
    created_at = Column(DateTime(timezone=True), default=utc_now, nullable=False)
    executed_at = Column(DateTime(timezone=True), nullable=True)

    user = relationship("User", back_populates="commands")
    trusted_device = relationship("TrustedDevice")
    protected_device = relationship("ProtectedDevice")


class VaultItem(Base):
    """
    Client-side end-to-end encrypted recovery vault items.
    Stores recovery codes, IMEI/serial details, purchase receipts, and insurance documents.
    Server has ZERO knowledge of plaintext. Stored strictly as encrypted ciphertext blobs.
    """
    __tablename__ = "vault_items"

    id = Column(String(36), primary_key=True, default=generate_uuid)
    user_id = Column(String(36), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    item_type = Column(String(64), nullable=False)  # RECOVERY_CODE, DEVICE_IDENTIFIER, INSURANCE_POLICY, PURCHASE_RECEIPT, CONTACTS_BACKUP
    title = Column(String(128), nullable=False)
    encrypted_blob = Column(Text, nullable=False)  # Base64 ciphertext
    iv = Column(String(64), nullable=False)  # Base64 initialization vector (12 bytes for AES-GCM)
    tag = Column(String(64), nullable=False)  # Base64 authentication tag (16 bytes)
    key_version = Column(BigInteger, default=1, nullable=False)
    created_at = Column(DateTime(timezone=True), default=utc_now, nullable=False)
    updated_at = Column(DateTime(timezone=True), default=utc_now, onupdate=utc_now, nullable=False)

    user = relationship("User", back_populates="vault_items")


class BugReport(Base):
    """User feedback, issue logs, and vulnerability submissions."""
    __tablename__ = "bug_reports"

    id = Column(String(36), primary_key=True, default=generate_uuid)
    name = Column(String(128), nullable=False)
    email = Column(String(255), nullable=True)
    device_model = Column(String(128), nullable=True)
    android_version = Column(String(64), nullable=True)
    severity = Column(String(32), default="LOW", nullable=False)  # LOW, MEDIUM, HIGH, CRITICAL
    description = Column(Text, nullable=False)
    status = Column(String(32), default="NEW", nullable=False)  # NEW, TRIAGED, RESOLVED, DISMISSED
    created_at = Column(DateTime(timezone=True), default=utc_now, nullable=False)


class UserSession(Base):
    """Active user login sessions across devices and web consoles."""
    __tablename__ = "user_sessions"

    id = Column(String(36), primary_key=True, default=generate_uuid)
    user_id = Column(String(36), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    session_token_jti = Column(String(64), unique=True, index=True, nullable=False)
    device_info = Column(Text, nullable=True)
    ip_address = Column(String(64), nullable=True)
    user_agent = Column(String(256), nullable=True)
    is_registered_device = Column(Boolean, default=False, nullable=False)
    is_2fa = Column(Boolean, default=False, nullable=False)
    refresh_token_hash = Column(String(64), nullable=True, index=True)
    consumed_refresh_hash = Column(String(64), nullable=True, index=True)
    consumed_refresh_hashes = Column(Text, default="[]", nullable=True)
    created_at = Column(DateTime(timezone=True), default=utc_now, nullable=False)
    last_active_at = Column(DateTime(timezone=True), default=utc_now, nullable=False)
    revoked_at = Column(DateTime(timezone=True), nullable=True)

    user = relationship("User", back_populates="sessions")


class PairingSession(Base):
    """
    Temporary single-use pairing session created by Admin after TOTP verification.
    Expires in 5 minutes. Bound to the owner account.
    """
    __tablename__ = "pairing_sessions"

    id = Column(String(36), primary_key=True, default=generate_uuid)
    user_id = Column(String(36), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    session_token = Column(String(64), unique=True, index=True, nullable=False)
    short_code = Column(String(8), unique=True, index=True, nullable=False)  # 6-digit code for manual entry
    expires_at = Column(DateTime(timezone=True), nullable=False)
    consumed_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(DateTime(timezone=True), default=utc_now, nullable=False)

    user = relationship("User", back_populates="pairing_sessions")


class DeviceStatus(Base):
    """
    Live status and health telemetry reported by Guardian handset on sync and heartbeat.
    """
    __tablename__ = "device_status"

    id = Column(String(36), primary_key=True, default=generate_uuid)
    device_id = Column(String(36), ForeignKey("protected_devices.id", ondelete="CASCADE"), unique=True, nullable=False, index=True)
    battery_level = Column(Integer, nullable=True)
    is_charging = Column(Boolean, default=False, nullable=True)
    connectivity_type = Column(String(32), nullable=True)  # "wifi", "cellular", "none"
    sim_operator = Column(String(64), nullable=True)
    sim_country = Column(String(8), nullable=True)
    sim_changed = Column(Boolean, default=False, nullable=False)
    permission_health = Column(Text, default="{}", nullable=False)  # JSON: sms, background_location, camera, battery_optimization, restricted_settings
    capabilities = Column(Text, default="[]", nullable=False)  # JSON list: locate_sms, live_gps, siren, ring, etc.
    last_seen = Column(DateTime(timezone=True), default=utc_now, nullable=False)
    last_sync_path = Column(String(16), default="online", nullable=False)  # "online" | "sms"
    updated_at = Column(DateTime(timezone=True), default=utc_now, onupdate=utc_now, nullable=False)

    device = relationship("ProtectedDevice", back_populates="status_record")


class DeviceAuditLog(Base):
    """
    Tamper-evident audit log for Guardian device lifecycle:
    Records who (user/admin), what (action), when (timestamp), and which Admin device.
    """
    __tablename__ = "device_audit_logs"

    id = Column(String(36), primary_key=True, default=generate_uuid)
    user_id = Column(String(36), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    device_id = Column(String(36), ForeignKey("protected_devices.id", ondelete="CASCADE"), nullable=True, index=True)
    action = Column(String(64), nullable=False)  # PAIR, UNPAIR_GRACEFUL, REVOKE_FORCE, RENAME, SYNC
    actor_admin_id = Column(String(64), nullable=True)
    details = Column(Text, nullable=True)
    created_at = Column(DateTime(timezone=True), default=utc_now, nullable=False)

    user = relationship("User", back_populates="device_audit_logs")
    device = relationship("ProtectedDevice", back_populates="audit_logs")

