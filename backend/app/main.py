from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.core.config import settings
from app.db.session import engine, Base
from app.api.auth import router as auth_router
from app.api.devices import router as devices_router
from app.api.locations import router as locations_router
from app.api.vault import router as vault_router
from app.api.web import router as web_router
from app.api.verify import router as verify_router

# Auto-create tables for development / SQLite testing
Base.metadata.create_all(bind=engine)

DISALLOWED_VERIFY_SECRETS = {
    "",
    "default-verification-hmac-secret-32b",
    "development-challenge-hmac-sha256-secret-minimum-32bytes",
    "change-me",
}

def validate_production_security_config(cfg=None):
    """Enforces non-empty, non-example VERIFY_SECRET when DEBUG is False."""
    conf = cfg or settings
    if not conf.DEBUG:
        sec = (conf.VERIFY_SECRET or "").strip()
        if not sec or sec in DISALLOWED_VERIFY_SECRETS:
            raise RuntimeError(
                "CRITICAL SECURITY CONFIGURATION ERROR: VERIFY_SECRET must be set to a secure, unique, non-default value when DEBUG=false."
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
from fastapi import Request
from fastapi.responses import JSONResponse

logger = logging.getLogger("uvicorn.error")

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
    """Inject OWASP ASVS recommended security headers on all HTTP responses."""
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
    if settings.ENFORCE_HTTPS:
        response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains; preload"
    return response


@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    """
    Fail-safe global exception handler:
    Logs full traceback internally while ensuring zero stack traces or internal
    exception details are ever leaked to the client in production responses.
    """
    logger.error(f"Unhandled server error on {request.method} {request.url.path}: {exc}", exc_info=True)
    return JSONResponse(
        status_code=500,
        content={"detail": "Internal server error"}
    )

# Register API Routers
app.include_router(auth_router)
app.include_router(verify_router)
app.include_router(devices_router)
app.include_router(locations_router)
app.include_router(vault_router)
app.include_router(web_router)


@app.get("/health", tags=["Health"])
def health_check():
    """Healthcheck endpoint for Docker Compose and monitoring."""
    return {
        "status": "HEALTHY",
        "version": settings.VERSION,
        "phase": "Phase 2 (Vertical Slice)"
    }
