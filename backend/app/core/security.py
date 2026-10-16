import time
import hmac
import hashlib
from datetime import datetime, timedelta, timezone
from typing import Optional, Union, Any

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError, VerificationError
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.exceptions import InvalidSignature

from app.core.config import settings

# Memory-hard password hashing using Argon2id (OWASP recommended)
pwd_hasher = PasswordHasher(
    time_cost=2,
    memory_cost=19456,  # 19 MiB
    parallelism=1,
    hash_len=32,
    salt_len=16
)


def hash_password(password: str) -> str:
    """Hash password using Argon2id."""
    return pwd_hasher.hash(password)


def verify_password(plain_password: str, hashed_password: str) -> bool:
    """Verify password against Argon2id hash."""
    try:
        return pwd_hasher.verify(hashed_password, plain_password)
    except (VerifyMismatchError, VerificationError):
        return False


import base64
import os
import struct


def generate_totp_secret() -> str:
    """Generate 160-bit (20 bytes) base32-encoded TOTP secret."""
    return base64.b32encode(os.urandom(20)).decode('utf-8').replace('=', '')


def get_totp_uri(secret_b32: str, username: str, issuer: str = "AntiTheftRecovery") -> str:
    """Generate standard otpauth URI for authenticator apps."""
    return f"otpauth://totp/{issuer}:{username}?secret={secret_b32}&issuer={issuer}&algorithm=SHA1&digits=6&period=30"


def verify_totp_code(secret_b32: str, code: str, valid_windows: int = 1) -> bool:
    """
    Verify RFC 6238 TOTP code with time drift window.
    valid_windows=1 checks t-30s, t, t+30s.
    """
    if not secret_b32 or not code:
        return False
    clean_code = code.strip()
    if len(clean_code) != 6 or not clean_code.isdigit():
        return False

    # Pad secret if missing padding
    padded_secret = secret_b32.upper()
    missing_padding = len(padded_secret) % 8
    if missing_padding:
        padded_secret += "=" * (8 - missing_padding)

    try:
        key = base64.b32decode(padded_secret, casefold=True)
    except Exception:
        return False

    current_counter = int(time.time() // 30)
    for offset in range(-valid_windows, valid_windows + 1):
        counter = current_counter + offset
        msg = struct.pack(">Q", counter)
        h = hmac.new(key, msg, hashlib.sha1).digest()
        idx = h[-1] & 0x0F
        code_int = struct.unpack(">I", h[idx:idx + 4])[0] & 0x7FFFFFFF
        expected_code = f"{code_int % 1000000:06d}"
        if hmac.compare_digest(expected_code, clean_code):
            return True
    return False


def create_access_token(subject: Union[str, Any], is_2fa: bool = False, expires_delta: Optional[timedelta] = None) -> str:
    """
    Generate JWT Bearer token with 2FA status claim.
    Phase 3 commands strictly require is_2fa=True.
    """
    if expires_delta:
        expire = datetime.now(timezone.utc) + expires_delta
    else:
        expire = datetime.now(timezone.utc) + timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)
    
    to_encode = {
        "exp": expire,
        "sub": str(subject),
        "is_2fa": is_2fa,
        "iat": datetime.now(timezone.utc)
    }
    encoded_jwt = jwt.encode(to_encode, settings.SECRET_KEY, algorithm=settings.ALGORITHM)
    return encoded_jwt


def verify_access_token(token: str) -> Optional[dict]:
    """Verify JWT token and extract payload dictionary."""
    try:
        payload = jwt.decode(token, settings.SECRET_KEY, algorithms=[settings.ALGORITHM])
        if "sub" not in payload:
            return None
        return payload
    except (jwt.PyJWTError, Exception):
        return None


def create_canonical_command_bytes(
    command_id: str,
    target_device_id: str,
    command_type: str,
    sequence_counter: int,
    expiry_epoch: int,
    parameters_json: str = "{}"
) -> bytes:
    """
    Constructs deterministic canonical bytes for Owner Admin command signing.
    Enforces format: command_id|target_device_id|command_type|sequence_counter|expiry_epoch|<sha256(parameters)>
    """
    params_hash = hashlib.sha256(parameters_json.strip().encode("utf-8")).hexdigest()
    canonical_str = (
        f"{command_id.strip()}|"
        f"{target_device_id.strip()}|"
        f"{command_type.strip().upper()}|"
        f"{sequence_counter}|"
        f"{expiry_epoch}|"
        f"{params_hash}"
    )
    return canonical_str.encode("utf-8")


def compute_truncated_hmac(key_bytes: bytes, message_bytes: bytes, hex_length: int = 12) -> str:
    """
    Computes truncated HMAC-SHA256 for SMS channel.
    Default hex_length = 12 characters (48 bits / 6 bytes).
    """
    full_digest = hmac.new(key_bytes, message_bytes, hashlib.sha256).hexdigest()
    return full_digest[:hex_length].upper()


def verify_truncated_hmac(key_bytes: bytes, message_bytes: bytes, received_hex: str) -> bool:
    """Constant-time verification of truncated HMAC."""
    expected_hex = compute_truncated_hmac(key_bytes, message_bytes, len(received_hex))
    return hmac.compare_digest(expected_hex.upper(), received_hex.upper())


def verify_ecdsa_p256_signature(public_key_pem: str, data_bytes: bytes, der_signature_bytes: bytes) -> bool:
    """
    Verifies ECDSA P-256 (secp256r1) signature over SHA-256.
    Android Keystore 'SHA256withECDSA' outputs standard ASN.1 DER format.
    """
    try:
        public_key = serialization.load_pem_public_key(public_key_pem.encode("utf-8"))
        if not isinstance(public_key, ec.EllipticCurvePublicKey):
            return False
        
        # Verify ASN.1 DER signature against data_bytes
        public_key.verify(
            der_signature_bytes,
            data_bytes,
            ec.ECDSA(hashes.SHA256())
        )
        return True
    except (InvalidSignature, ValueError, Exception):
        return False
