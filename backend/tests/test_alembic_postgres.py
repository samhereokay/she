import tempfile
import pytest
from alembic.config import Config
from alembic import command
from sqlalchemy import create_engine, inspect
import pgserver


def test_alembic_migrations_on_real_postgresql():
    """Verifies Alembic migrations run end-to-end on a real user-space PostgreSQL instance.

    Tests upgrade head, verifies table schemas and foreign keys, downgrades to base,
    and re-upgrades to head.
    """
    tmp_dir = tempfile.mkdtemp()
    srv = pgserver.get_server(tmp_dir)
    raw_uri = srv.get_uri()
    pg_uri = raw_uri.replace("postgresql://", "postgresql+psycopg2://")

    alembic_cfg = Config("backend/alembic.ini")
    alembic_cfg.set_main_option("sqlalchemy.url", pg_uri)
    alembic_cfg.set_section_option("alembic", "prepend_sys_path", "backend")

    # 1. Run migrations upgrade to head
    command.upgrade(alembic_cfg, "head")

    # 2. Inspect created tables
    engine = create_engine(pg_uri)
    inspector = inspect(engine)
    tables = set(inspector.get_table_names())

    expected_tables = {
        "alembic_version",
        "users",
        "protected_devices",
        "trusted_devices",
        "audit_events",
        "device_commands",
        "location_events",
        "vault_items",
    }
    assert expected_tables.issubset(tables), f"Missing tables in PostgreSQL: {expected_tables - tables}"

    # 3. Verify vault_items columns and constraints
    vault_cols = {col["name"] for col in inspector.get_columns("vault_items")}
    assert {"id", "user_id", "item_type", "title", "encrypted_blob", "iv", "tag", "key_version"}.issubset(vault_cols)

    # 4. Verify round-trip downgrade to base
    command.downgrade(alembic_cfg, "base")
    post_downgrade_tables = set(inspect(engine).get_table_names())
    assert "users" not in post_downgrade_tables
    assert "vault_items" not in post_downgrade_tables

    # 5. Re-upgrade to head
    command.upgrade(alembic_cfg, "head")
    re_upgraded_tables = set(inspect(engine).get_table_names())
    assert expected_tables.issubset(re_upgraded_tables)
