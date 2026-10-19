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

    PROJECT_NAME: str = "SHE - Sentinel for Handheld Equipment"
    VERSION: str = "0.3.0"
    API_V1_STR: str = "/api/v1"
    
    # Environment & Security
    DEBUG: bool = os.getenv("DEBUG", "true").lower() == "true"
    ENFORCE_HTTPS: bool = os.getenv("ENFORCE_HTTPS", "false").lower() == "true"
    ALLOW_PUBLIC_REGISTRATION: bool = os.getenv("ALLOW_PUBLIC_REGISTRATION", "false").lower() == "true"
    REGISTRATION_TOKEN: str = os.getenv("REGISTRATION_TOKEN", "")
    ADMIN_SETUP_TOKEN: str = os.getenv("ADMIN_SETUP_TOKEN", "")
    CORS_ORIGINS: list[str] = [o.strip() for o in os.getenv("CORS_ORIGINS", "http://localhost:8000,http://127.0.0.1:8000,https://samhereokay.github.io").split(",") if o.strip()]
    
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

    # Recovery Vault Storage Quotas (Per User)
    MAX_VAULT_ITEMS_PER_USER: int = int(os.getenv("MAX_VAULT_ITEMS_PER_USER", "100"))
    MAX_VAULT_ITEM_BYTES: int = int(os.getenv("MAX_VAULT_ITEM_BYTES", "262144"))  # 256 KB max per item
    MAX_VAULT_STORAGE_BYTES_PER_USER: int = int(os.getenv("MAX_VAULT_STORAGE_BYTES_PER_USER", "5242880"))  # 5 MB max per user


settings = Settings()
