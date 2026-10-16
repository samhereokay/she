import uuid
from datetime import datetime, timezone
from sqlalchemy import (
    Column,
    String,
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
    email = Column(String(255), unique=True, index=True, nullable=False)
    hashed_password = Column(String(255), nullable=False)
    totp_secret = Column(String(64), nullable=True)
    totp_enabled = Column(Boolean, default=False, nullable=False)
    created_at = Column(DateTime(timezone=True), default=utc_now, nullable=False)

    trusted_devices = relationship("TrustedDevice", back_populates="user", cascade="all, delete-orphan")
    protected_devices = relationship("ProtectedDevice", back_populates="user", cascade="all, delete-orphan")
    commands = relationship("DeviceCommand", back_populates="user", cascade="all, delete-orphan")


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
    pairing_id = Column(String(36), default=generate_uuid, nullable=False)
    confirmation_code = Column(String(16), nullable=True)
    created_at = Column(DateTime(timezone=True), default=utc_now, nullable=False)
    status = Column(String(32), default="ACTIVE", nullable=False)  # ACTIVE, PENDING_CONFIRMATION, STOLEN, REVOKED

    user = relationship("User", back_populates="protected_devices")
    location_events = relationship("LocationEvent", back_populates="protected_device", cascade="all, delete-orphan")


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
    trusted_device_id = Column(String(36), ForeignKey("trusted_devices.id", ondelete="CASCADE"), nullable=False)
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

