from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.core.config import settings
from app.db.session import engine, Base
from app.api.auth import router as auth_router
from app.api.devices import router as devices_router, pairing_router
from app.api.locations import router as locations_router
from app.api.vault import router as vault_router
from app.api.web import router as web_router
from app.api.verify import router as verify_router

# Auto-create tables for development / SQLite testing
if settings.DATABASE_URL.startswith("sqlite"):
    Base.metadata.create_all(bind=engine)


DISALLOWED_VERIFY_SECRETS = {
    "",
    "default-verification-hmac-secret-32b",
    "development-challenge-hmac-sha256-secret-minimum-32bytes",
    "change-me",
}

DISALLOWED_ENCRYPTION_KEYS = {
    "",
    "change-me",
    "default",
    "example",
}

def validate_production_security_config(cfg=None):
    """Enforces non-empty, non-example VERIFY_SECRET and PAIRING_SECRET_ENCRYPTION_KEY when DEBUG is False."""
    conf = cfg or settings
    if not conf.DEBUG:
        sec = (conf.VERIFY_SECRET or "").strip()
        if not sec or sec in DISALLOWED_VERIFY_SECRETS:
            raise RuntimeError(
                "CRITICAL SECURITY CONFIGURATION ERROR: VERIFY_SECRET must be set to a secure, unique, non-default value when DEBUG=false."
            )
        pairing_key = (getattr(conf, "PAIRING_SECRET_ENCRYPTION_KEY", None) or "").strip()
        if not pairing_key or pairing_key in DISALLOWED_ENCRYPTION_KEYS:
            raise RuntimeError(
                "CRITICAL SECURITY CONFIGURATION ERROR: PAIRING_SECRET_ENCRYPTION_KEY must be configured in environment when DEBUG=false."
            )

validate_production_security_config()

app = FastAPI(
    title=settings.PROJECT_NAME,
    version=settings.VERSION,
    description="Backend service for Android Anti-Theft & Recovery System",
    docs_url="/docs" if settings.DEBUG else None,
    redoc_url="/redoc" if settings.DEBUG else None,
    openapi_url="/openapi.json" if settings.DEBUG else None,
)

import logging
import re
from fastapi import Request
from fastapi.responses import JSONResponse, RedirectResponse

logger = logging.getLogger("uvicorn.error")


class SensitiveDataFilter(logging.Filter):
    """Redacts tokens, OTPs, recovery keys, and coordinates from all server log records."""
    SENSITIVE_PATTERNS = [
        (re.compile(r'(token=)[^&\s]+', re.IGNORECASE), r'\1[REDACTED]'),
        (re.compile(r'(otp=)[^&\s]+', re.IGNORECASE), r'\1[REDACTED]'),
        (re.compile(r'(code=)[^&\s]+', re.IGNORECASE), r'\1[REDACTED]'),
        (re.compile(r'(secret=)[^&\s]+', re.IGNORECASE), r'\1[REDACTED]'),
        (re.compile(r'(password=)[^&\s]+', re.IGNORECASE), r'\1[REDACTED]'),
        (re.compile(r'(lat=)[^&\s]+', re.IGNORECASE), r'\1[REDACTED]'),
        (re.compile(r'(lng=)[^&\s]+', re.IGNORECASE), r'\1[REDACTED]'),
        (re.compile(r'(latitude=)[^&\s]+', re.IGNORECASE), r'\1[REDACTED]'),
        (re.compile(r'(longitude=)[^&\s]+', re.IGNORECASE), r'\1[REDACTED]'),
        (re.compile(r'(phone=)[^&\s]+', re.IGNORECASE), r'\1[REDACTED]'),
        (re.compile(r'(mobile=)[^&\s]+', re.IGNORECASE), r'\1[REDACTED]'),
        (re.compile(r'(email=)[^&\s]+', re.IGNORECASE), r'\1[REDACTED]'),
        (re.compile(r'Bearer\s+[a-zA-Z0-9_\-\.]+', re.IGNORECASE), r'Bearer [REDACTED]'),
    ]

    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.msg, str):
            for pattern, replacement in self.SENSITIVE_PATTERNS:
                record.msg = pattern.sub(replacement, record.msg)
        if record.args:
            cleaned_args = []
            for arg in record.args:
                if isinstance(arg, str):
                    for pattern, replacement in self.SENSITIVE_PATTERNS:
                        arg = pattern.sub(replacement, arg)
                cleaned_args.append(arg)
            record.args = tuple(cleaned_args)
        return True


# Install sensitive data filter on uvicorn and app loggers
for log_name in ("uvicorn", "uvicorn.access", "uvicorn.error"):
    l = logging.getLogger(log_name)
    l.addFilter(SensitiveDataFilter())

# CORS Middleware with restricted origins (prevent wildcard credentials vulnerability)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["GET", "POST", "PUT", "DELETE", "OPTIONS"],
    allow_headers=["*"],
)


@app.middleware("http")
async def add_security_headers(request: Request, call_next):
    """Inject OWASP ASVS and TLS migration recommended security headers on all HTTP responses."""
    raw_proto = request.headers.get("x-forwarded-proto")
    if raw_proto:
        # Take the leftmost hop (client-facing protocol) in case of multiple proxies
        proto = raw_proto.split(",")[0].strip().lower()
    else:
        proto = request.url.scheme.lower()

    # 308 Permanent Redirect for plain HTTP when HTTPS enforcement is enabled.
    # If proto or url scheme is already https, never redirect (prevents infinite loop behind TLS-terminating proxies)
    if settings.ENFORCE_HTTPS and proto == "http" and request.url.scheme.lower() != "https" and not request.url.path.startswith("/health"):
        # Reconstruct HTTPS URL preserving host, port, path, and query params
        url = request.url.replace(scheme="https")
        return RedirectResponse(url=str(url), status_code=308)

    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["X-XSS-Protection"] = "0"
    response.headers["Referrer-Policy"] = "strict-origin-when-cross-origin"
    response.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"
    response.headers["Content-Security-Policy"] = (
        "default-src 'self'; "
        "script-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
        "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
        "font-src 'self' https://fonts.gstatic.com data:; "
        "img-src 'self' data: https://*.tile.openstreetmap.org https://*.basemaps.cartocdn.com; "
        "connect-src 'self' wss: https:;"
    )

    # Cache-Control: no-store on authenticated or sensitive API routes
    auth_header = request.headers.get("Authorization")
    is_sensitive_path = (
        request.url.path.startswith("/auth/") or
        request.url.path.startswith("/api/v1/devices") or
        request.url.path.startswith("/api/v1/vault") or
        request.url.path.startswith("/api/v1/locations") or
        request.url.path.startswith("/api/v1/verify")
    )
    if auth_header or is_sensitive_path:
        response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate, private"
        response.headers["Pragma"] = "no-cache"

    # Enforce HSTS on all responses (no preload: we don't own the railway.app domain,
    # and preload is hard to undo once submitted to browser lists)
    response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"

    return response


@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    """
    Fail-safe global exception handler:
    Logs full traceback internally and surfaces exception summary in response for rapid triage.
    """
    logger.error(f"Unhandled server error on {request.method} {request.url.path}: {exc}", exc_info=True)
    return JSONResponse(
        status_code=500,
        content={"detail": f"Internal server error: {type(exc).__name__}: {str(exc)}"}
    )

# Register API Routers
app.include_router(auth_router)
app.include_router(verify_router)
app.include_router(devices_router)
app.include_router(pairing_router)
app.include_router(locations_router)
app.include_router(vault_router)
app.include_router(web_router)


@app.get("/health", tags=["Health"])
async def health_check():
    """Healthcheck endpoint for Docker Compose and monitoring."""
    return {
        "status": "HEALTHY",
        "version": settings.VERSION,
        "phase": "Phase 2 (Vertical Slice)"
    }
