import os
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(case_sensitive=True)

    PROJECT_NAME: str = "Android Anti-Theft & Recovery Backend"
    VERSION: str = "0.2.0-phase2"
    API_V1_STR: str = "/api/v1"
    
    # Security & Temporary Phase 2 JWT Secret
    # NOTE: Password + JWT sign-in is strictly temporary for Phase 2 MVP.
    # Phase 3 mandates WebAuthn/Passkey or TOTP + trusted-device approval.
    SECRET_KEY: str = os.getenv("SECRET_KEY", "temporary-phase2-dev-secret-key-replace-in-production-64char-minimum-length-hash")
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 30
    
    # Database
    DATABASE_URL: str = os.getenv("DATABASE_URL", "sqlite:///./antitheft_dev.db")
    
    # Location Privacy Limits
    LOCATION_RETENTION_DAYS: int = int(os.getenv("LOCATION_RETENTION_DAYS", "30"))


settings = Settings()
