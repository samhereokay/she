from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.core.config import settings
from app.db.session import engine, Base
from app.api.auth import router as auth_router
from app.api.devices import router as devices_router
from app.api.locations import router as locations_router
from app.api.vault import router as vault_router

# Auto-create tables for development / SQLite testing
Base.metadata.create_all(bind=engine)

app = FastAPI(
    title=settings.PROJECT_NAME,
    version=settings.VERSION,
    description="Backend service for Android Anti-Theft & Recovery System"
)

# CORS Middleware for local development and admin UI
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Register API Routers
app.include_router(auth_router)
app.include_router(devices_router)
app.include_router(locations_router)
app.include_router(vault_router)


@app.get("/health", tags=["Health"])
def health_check():
    """Healthcheck endpoint for Docker Compose and monitoring."""
    return {
        "status": "HEALTHY",
        "version": settings.VERSION,
        "phase": "Phase 2 (Vertical Slice)"
    }
