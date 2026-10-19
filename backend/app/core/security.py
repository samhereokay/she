import time
import hmac
import hashlib
import uuid
import secrets
from datetime import datetime, timedelta, timezone
from typing import Optional, Union, Any

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError, VerificationError
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.exceptions import InvalidSignature

from app.core.config import settings
import logging

logger = logging.getLogger("uvicorn.error")

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
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def get_totp_encryption_key() -> bytes:
    """Derive or load deterministic 256-bit key for encrypting secrets at rest.
    Mandates TOTP_ENCRYPTION_KEY or SECRET_KEY from environment with no committed defaults."""
    totp_env = settings.TOTP_ENCRYPTION_KEY or os.getenv("TOTP_ENCRYPTION_KEY")
    if totp_env:
        return hashlib.sha256(totp_env.encode("utf-8")).digest()
    if not settings.SECRET_KEY:
        raise RuntimeError("CRITICAL SECURITY ERROR: No TOTP_ENCRYPTION_KEY or SECRET_KEY configured in environment.")
    return hashlib.sha256(f"totp-at-rest-secret-v1:{settings.SECRET_KEY}".encode("utf-8")).digest()


def get_pairing_secret_encryption_key() -> bytes:
    """Derive or load deterministic 256-bit key for encrypting pairing secrets at rest.
    Mandates PAIRING_SECRET_ENCRYPTION_KEY or SECRET_KEY from environment."""
    key_env = settings.PAIRING_SECRET_ENCRYPTION_KEY or os.getenv("PAIRING_SECRET_ENCRYPTION_KEY")
    if key_env:
        return hashlib.sha256(key_env.encode("utf-8")).digest()
    if not settings.SECRET_KEY:
        raise RuntimeError("CRITICAL SECURITY ERROR: No PAIRING_SECRET_ENCRYPTION_KEY or SECRET_KEY configured in environment.")
    return hashlib.sha256(f"pairing-secret-at-rest-v1:{settings.SECRET_KEY}".encode("utf-8")).digest()


def encrypt_pairing_secret(plain_secret: str) -> str:
    """Encrypt raw pairing secret using AES-256-GCM before saving to database."""
    if not plain_secret:
        return ""
    key = get_pairing_secret_encryption_key()
    iv = os.urandom(12)
    aesgcm = AESGCM(key)
    ciphertext = aesgcm.encrypt(iv, plain_secret.encode("utf-8"), None)
    return base64.b64encode(iv + ciphertext).decode("utf-8")


def decrypt_pairing_secret(encrypted_secret: str) -> str:
    """Decrypt pairing secret using AES-256-GCM with backward compatibility for legacy plaintext."""
    if not encrypted_secret:
        return ""
    try:
        combined = base64.b64decode(encrypted_secret)
        if len(combined) < 28:
            return encrypted_secret
        iv = combined[:12]
        ciphertext = combined[12:]
        aesgcm = AESGCM(get_pairing_secret_encryption_key())
        return aesgcm.decrypt(iv, ciphertext, None).decode("utf-8")
    except Exception:
        return encrypted_secret


def encrypt_totp_secret(plain_secret: str) -> str:
    """Encrypt TOTP secret using AES-256-GCM before saving to database."""
    if not plain_secret:
        return ""
    key = get_totp_encryption_key()
    iv = os.urandom(12)
    aesgcm = AESGCM(key)
    ciphertext = aesgcm.encrypt(iv, plain_secret.encode("utf-8"), None)
    return base64.b64encode(iv + ciphertext).decode("utf-8")


def decrypt_totp_secret(encrypted_secret: str) -> str:
    """Decrypt TOTP secret using AES-256-GCM with backward compatibility for legacy plaintext."""
    if not encrypted_secret:
        return ""
    try:
        combined = base64.b64decode(encrypted_secret)
        if len(combined) < 28:
            return encrypted_secret
        iv = combined[:12]
        ciphertext = combined[12:]
        aesgcm = AESGCM(get_totp_encryption_key())
        return aesgcm.decrypt(iv, ciphertext, None).decode("utf-8")
    except Exception:
        return encrypted_secret


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


def create_access_token(
    subject: Union[str, Any],
    is_2fa: bool = False,
    expires_delta: Optional[timedelta] = None,
    totp_verified_at: Optional[int] = None,
    jti: Optional[str] = None
) -> str:
    """
    Generate JWT Bearer token with 2FA status claim and unique session JTI.
    Phase 3 commands strictly require is_2fa=True.
    """
    now = datetime.now(timezone.utc)
    if expires_delta:
        expire = now + expires_delta
    else:
        expire = now + timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)
    
    session_jti = jti or secrets.token_hex(16)
    to_encode = {
        "exp": expire,
        "sub": str(subject),
        "is_2fa": is_2fa,
        "iat": now,
        "jti": session_jti
    }
    if is_2fa:
        to_encode["totp_verified_at"] = totp_verified_at if totp_verified_at is not None else int(time.time())
    encoded_jwt = jwt.encode(to_encode, settings.SECRET_KEY, algorithm=settings.ALGORITHM)
    return encoded_jwt


def verify_access_token(token: str) -> Optional[dict]:
    """Verify JWT token and extract payload dictionary."""
    clean_token = token.strip().strip('"').strip("'")
    try:
        payload = jwt.decode(clean_token, settings.SECRET_KEY, algorithms=[settings.ALGORITHM], leeway=60)
        if "sub" not in payload:
            return None
        return payload
    except (jwt.PyJWTError, Exception):
        return None


# In-memory single-use JTI cache for pairing tokens
_used_pairing_token_jtis: set[str] = set()


def create_pairing_token(
    user_id: str,
    trusted_device_id: Optional[str] = None,
    expires_minutes: int = 15
) -> str:
    """
    Generate short-lived, single-use token strictly restricted to device pairing.
    Cannot be used for command dispatch or API access.
    """
    expire = datetime.now(timezone.utc) + timedelta(minutes=expires_minutes)
    jti = str(uuid.uuid4())
    payload = {
        "sub": str(user_id),
        "trusted_device_id": str(trusted_device_id) if trusted_device_id else None,
        "scope": "device_pairing",
        "jti": jti,
        "exp": expire,
        "iat": datetime.now(timezone.utc)
    }
    return jwt.encode(payload, settings.SECRET_KEY, algorithm=settings.ALGORITHM)


def verify_pairing_token(token: str) -> Optional[dict]:
    """Verify pairing token and return payload if valid and unconsumed."""
    clean_token = token.strip().strip('"').strip("'")
    try:
        payload = jwt.decode(clean_token, settings.SECRET_KEY, algorithms=[settings.ALGORITHM], leeway=60)
        if payload.get("scope") != "device_pairing" or "sub" not in payload:
            logger.warning(f"verify_pairing_token: Invalid scope ({payload.get('scope')}) or missing sub in token")
            return None
        jti = payload.get("jti")
        if not jti:
            logger.warning("verify_pairing_token: Missing jti claim in token payload")
            return None
        if jti in _used_pairing_token_jtis:
            logger.warning(f"verify_pairing_token: JTI {jti} has already been consumed")
            return None
        return payload
    except jwt.ExpiredSignatureError as e:
        logger.warning(f"verify_pairing_token: Pairing token signature has expired: {e}")
        return None
    except jwt.ImmatureSignatureError as e:
        logger.warning(f"verify_pairing_token: Pairing token signature is immature (clock skew): {e}")
        return None
    except jwt.PyJWTError as e:
        logger.warning(f"verify_pairing_token: PyJWT error during decoding: {e}")
        return None
    except Exception as e:
        logger.warning(f"verify_pairing_token: Unexpected exception during decoding: {e}")
        return None


def claim_pairing_token_jti(jti: str) -> bool:
    """Atomically claim a pairing token JTI to prevent replay."""
    if jti in _used_pairing_token_jtis:
        return False
    _used_pairing_token_jtis.add(jti)
    return True


def create_canonical_command_bytes(
    pairing_id: str = "",
    command_id: str = "",
    target_device_id: str = "",
    command_type: str = "",
    sequence_counter: int = 0,
    expiry_epoch: int = 0,
    parameters_json: str = "{}"
) -> bytes:
    """
    Constructs deterministic canonical bytes for Owner Admin command signing.
    Enforces format: pairing_id|command_id|target_device_id|command_type|sequence_counter|expiry_epoch|<sha256(parameters)>
    """
    params_hash = hashlib.sha256(parameters_json.strip().encode("utf-8")).hexdigest()
    canonical_str = (
        f"{pairing_id.strip()}|"
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
