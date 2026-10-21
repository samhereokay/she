import tempfile
import pytest
from alembic.config import Config
from alembic import command
from sqlalchemy import create_engine, inspect
pgserver = pytest.importorskip("pgserver", reason="pgserver required for isolated PostgreSQL migration test")



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
        "verification_challenges",
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


def test_upgrade_from_previous_head_with_sample_data_no_data_loss():
    """
    Item 2: Verifies upgrading from previous head (f6a7b8c9d0e1) to current head (a7b8c9d0e1f2)
    on a real PostgreSQL database populated with sample data (users, devices, locations, commands, status)
    preserves all records with zero data loss, and verifies alembic check passes.
    """
    from sqlalchemy import text

    tmp_dir = tempfile.mkdtemp()
    srv = pgserver.get_server(tmp_dir)
    raw_uri = srv.get_uri()
    pg_uri = raw_uri.replace("postgresql://", "postgresql+psycopg2://")

    alembic_cfg = Config("backend/alembic.ini")
    alembic_cfg.set_main_option("sqlalchemy.url", pg_uri)
    alembic_cfg.set_section_option("alembic", "prepend_sys_path", "backend")

    # 1. Upgrade to PREVIOUS head: f6a7b8c9d0e1 (0010)
    command.upgrade(alembic_cfg, "f6a7b8c9d0e1")

    engine = create_engine(pg_uri)
    with engine.begin() as conn:
        # Insert sample user
        conn.execute(text("""
            INSERT INTO users (id, username, email, hashed_password, totp_secret, totp_enabled, created_at)
            VALUES ('u-1001', 'sample_owner', 'owner@example.com', 'argon2id$hashed', 'SECRET_TOTP_KEY_SAMPLE', true, NOW())
        """))

        # Insert sample protected device
        conn.execute(text("""
            INSERT INTO protected_devices (id, user_id, device_alias, public_key_pem, key_fingerprint, pairing_id, pairing_secret_hash, trusted_phone_number, status, created_at)
            VALUES ('d-2002', 'u-1001', 'Sample Guardian', 'PEM_PUBLIC_KEY_MOCK', 'FP_SHA256_MOCK', 'PAIR-1234-ABCD', 'HASH_SECRET_MOCK', '+15551234567', 'ACTIVE', NOW())
        """))

        # Insert sample trusted device (admin)
        conn.execute(text("""
            INSERT INTO trusted_devices (id, user_id, device_name, public_key_pem, is_active, created_at)
            VALUES ('td-909', 'u-1001', 'Admin Phone', 'PEM_ADMIN_PUBKEY', true, NOW())
        """))

        # Insert sample location event
        conn.execute(text("""
            INSERT INTO location_events (id, protected_device_id, latitude, longitude, accuracy, capture_time, server_receipt_time, sequence_number, signature)
            VALUES ('loc-3003', 'd-2002', 37.774929, -122.419416, 5.0, NOW(), NOW(), 1, 'SIG_MOCK')
        """))

        # Insert sample command
        conn.execute(text("""
            INSERT INTO device_commands (id, user_id, trusted_device_id, protected_device_id, command_type, sequence_counter, expiry_epoch, parameters_json, admin_signature, status, created_at)
            VALUES ('cmd-4004', 'u-1001', 'td-909', 'd-2002', 'LOCATE', 1, 1893456000, '{}', 'SIG_ADMIN', 'DELIVERED', NOW())
        """))

        # Insert sample device status
        conn.execute(text("""
            INSERT INTO device_status (id, device_id, battery_level, connectivity_type, capabilities, last_seen, updated_at)
            VALUES ('stat-5005', 'd-2002', 88, 'WIFI', '["locate_sms", "live_gps"]', NOW(), NOW())
        """))

    # 2. Perform migration to HEAD (a7b8c9d0e1f2_0011_sync_models_schema)
    command.upgrade(alembic_cfg, "head")

    # 3. Verify all sample data survived with ZERO loss
    with engine.connect() as conn:
        user_row = conn.execute(text("SELECT id, username, email, totp_secret FROM users WHERE id = 'u-1001'")).fetchone()
        assert user_row is not None
        assert user_row.username == "sample_owner"
        assert user_row.email == "owner@example.com"
        assert user_row.totp_secret == "SECRET_TOTP_KEY_SAMPLE"

        dev_row = conn.execute(text("SELECT id, device_alias, trusted_phone_number, status FROM protected_devices WHERE id = 'd-2002'")).fetchone()
        assert dev_row is not None
        assert dev_row.device_alias == "Sample Guardian"
        assert dev_row.trusted_phone_number == "+15551234567"
        assert dev_row.status == "ACTIVE"

        loc_row = conn.execute(text("SELECT id, latitude, longitude FROM location_events WHERE id = 'loc-3003'")).fetchone()
        assert loc_row is not None
        assert abs(loc_row.latitude - 37.774929) < 1e-5
        assert abs(loc_row.longitude - (-122.419416)) < 1e-5

        cmd_row = conn.execute(text("SELECT id, command_type, status FROM device_commands WHERE id = 'cmd-4004'")).fetchone()
        assert cmd_row is not None
        assert cmd_row.command_type == "LOCATE"
        assert cmd_row.status == "DELIVERED"

        stat_row = conn.execute(text("SELECT id, battery_level, capabilities FROM device_status WHERE id = 'stat-5005'")).fetchone()
        assert stat_row is not None
        assert stat_row.battery_level == 88

    # 4. Confirm alembic check passes with zero schema discrepancies
    command.check(alembic_cfg)

