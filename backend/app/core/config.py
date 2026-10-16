import os
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(case_sensitive=True)

    PROJECT_NAME: str = "Android Anti-Theft & Recovery Backend"
    VERSION: str = "0.2.0-phase2"
    API_V1_STR: str = "/api/v1"
    
    # Environment & Security
    DEBUG: bool = os.getenv("DEBUG", "true").lower() == "true"
    ENFORCE_HTTPS: bool = os.getenv("ENFORCE_HTTPS", "false").lower() == "true"
    ALLOW_PUBLIC_REGISTRATION: bool = os.getenv("ALLOW_PUBLIC_REGISTRATION", "true").lower() == "true"
    REGISTRATION_TOKEN: str = os.getenv("REGISTRATION_TOKEN", "")
    
    # Temporary Phase 2 JWT Secret (2FA mandated in Phase 3)
    SECRET_KEY: str = os.getenv("SECRET_KEY", "temporary-phase2-dev-secret-key-replace-in-production-64char-minimum-length-hash")
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 30
    
    # Database
    BASE_DIR: str = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    DATABASE_URL: str = os.getenv("DATABASE_URL", f"sqlite:///{os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), 'antitheft_dev.db')}")
    
    # Location Privacy Limits
    LOCATION_RETENTION_DAYS: int = int(os.getenv("LOCATION_RETENTION_DAYS", "30"))


settings = Settings()
