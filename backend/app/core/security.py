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


def create_access_token(subject: Union[str, Any], expires_delta: Optional[timedelta] = None) -> str:
    """
    Generate temporary Phase 2 JWT Bearer token.
    NOTE: Temporary baseline for Phase 2 MVP. Phase 3 mandates 2FA + trusted-device approval.
    """
    if expires_delta:
        expire = datetime.now(timezone.utc) + expires_delta
    else:
        expire = datetime.now(timezone.utc) + timedelta(minutes=settings.ACCESS_TOKEN_EXPIRE_MINUTES)
    
    to_encode = {
        "exp": expire,
        "sub": str(subject),
        "iat": datetime.now(timezone.utc)
    }
    encoded_jwt = jwt.encode(to_encode, settings.SECRET_KEY, algorithm=settings.ALGORITHM)
    return encoded_jwt


def verify_access_token(token: str) -> Optional[str]:
    """Verify JWT token and extract subject (user ID)."""
    try:
        payload = jwt.decode(token, settings.SECRET_KEY, algorithms=[settings.ALGORITHM])
        user_id: str = payload.get("sub")
        if user_id is None:
            return None
        return user_id
    except (jwt.PyJWTError, Exception):
        return None


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
