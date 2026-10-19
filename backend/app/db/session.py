from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker, declarative_base
from app.core.config import settings

db_url = settings.DATABASE_URL
if db_url.startswith("postgres://"):
    db_url = db_url.replace("postgres://", "postgresql+psycopg2://", 1)
elif db_url.startswith("postgresql://") and not db_url.startswith("postgresql+"):
    db_url = db_url.replace("postgresql://", "postgresql+psycopg2://", 1)

# Supabase connection pooler: auto-route session mode (:5432) to transaction mode (:6543)
# to avoid EMAXCONNSESSION max 15 clients limit exhaustion
if "pooler.supabase.com:5432" in db_url:
    db_url = db_url.replace("pooler.supabase.com:5432", "pooler.supabase.com:6543")

# Connection parameters & pooling strategy
connect_args = {}
engine_kwargs = {"pool_pre_ping": True}

if db_url.startswith("sqlite"):
    connect_args = {"check_same_thread": False}
else:
    connect_args = {"connect_timeout": 5}
    engine_kwargs.update({
        "pool_recycle": 120,
        "pool_timeout": 5,
        "pool_size": 10,
        "max_overflow": 10
    })

engine = create_engine(
    db_url,
    connect_args=connect_args,
    **engine_kwargs
)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()


def get_db():
    """Dependency yielding database session."""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
