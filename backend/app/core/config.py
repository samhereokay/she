import os
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        case_sensitive=True,
        env_file=(
            os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), ".env"),
            os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "backend", ".env")
        ),
        extra="allow"
    )

    PROJECT_NAME: str = "Android Anti-Theft & Recovery Backend"
    VERSION: str = "0.2.0-phase2"
    API_V1_STR: str = "/api/v1"
    
    # Environment & Security
    DEBUG: bool = os.getenv("DEBUG", "true").lower() == "true"
    ENFORCE_HTTPS: bool = os.getenv("ENFORCE_HTTPS", "false").lower() == "true"
    ALLOW_PUBLIC_REGISTRATION: bool = os.getenv("ALLOW_PUBLIC_REGISTRATION", "false").lower() == "true"
    REGISTRATION_TOKEN: str = os.getenv("REGISTRATION_TOKEN", "")
    
    # Environment-Mandated Secrets (NO committed fallback defaults)
    SECRET_KEY: str = os.getenv("SECRET_KEY", "")
    TOTP_ENCRYPTION_KEY: str = os.getenv("TOTP_ENCRYPTION_KEY", "")
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 30
    
    # Database
    BASE_DIR: str = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    DATABASE_URL: str = os.getenv("DATABASE_URL", f"sqlite:///{os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), 'antitheft_dev.db')}")
    
    # Location Privacy Limits
    LOCATION_RETENTION_DAYS: int = int(os.getenv("LOCATION_RETENTION_DAYS", "30"))


settings = Settings()
