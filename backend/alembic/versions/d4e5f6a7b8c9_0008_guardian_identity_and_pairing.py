"""Add Guardian handset identity, pairing sessions, device status, and audit logs.

Revision ID: d4e5f6a7b8c9
Revises: c3d4e5f6a7b8
Create Date: 2026-10-20

Context:
  Supports multi-device Guardian pairing with 5-minute single-use sessions,
  hardware identity reporting, live telemetry/status sync, and tamper-evident audit logs.
"""
from alembic import op
import sqlalchemy as sa

revision = 'd4e5f6a7b8c9'
down_revision = 'c3d4e5f6a7b8'
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    insp = sa.inspect(bind)
    tables = insp.get_table_names()

    # 1. Update protected_devices with identity and hardware specs
    if 'protected_devices' in tables:
        columns = [c['name'] for c in insp.get_columns('protected_devices')]
        new_cols = [
            ('key_fingerprint', sa.Column('key_fingerprint', sa.String(length=64), nullable=True)),
            ('manufacturer', sa.Column('manufacturer', sa.String(length=64), nullable=True)),
            ('brand', sa.Column('brand', sa.String(length=64), nullable=True)),
            ('model', sa.Column('model', sa.String(length=64), nullable=True)),
            ('android_version', sa.Column('android_version', sa.String(length=32), nullable=True)),
            ('sdk_level', sa.Column('sdk_level', sa.Integer(), nullable=True)),
            ('variant', sa.Column('variant', sa.String(length=32), nullable=False, server_default='lite')),
            ('app_version', sa.Column('app_version', sa.String(length=32), nullable=True)),
            ('install_date', sa.Column('install_date', sa.DateTime(timezone=True), nullable=True)),
            ('is_device_owner', sa.Column('is_device_owner', sa.Boolean(), nullable=False, server_default=sa.text('false'))),
            ('is_possible_reinstall', sa.Column('is_possible_reinstall', sa.Boolean(), nullable=False, server_default=sa.text('false'))),
        ]
        for col_name, col_def in new_cols:
            if col_name not in columns:
                op.add_column('protected_devices', col_def)

        if 'key_fingerprint' in [c[0] for c in new_cols]:
            try:
                op.create_index('ix_protected_devices_key_fingerprint', 'protected_devices', ['key_fingerprint'])
            except Exception:
                pass

    # 2. Create pairing_sessions table
    if 'pairing_sessions' not in tables:
        op.create_table(
            'pairing_sessions',
            sa.Column('id', sa.String(length=36), primary_key=True),
            sa.Column('user_id', sa.String(length=36), sa.ForeignKey('users.id', ondelete='CASCADE'), nullable=False),
            sa.Column('session_token', sa.String(length=64), nullable=False),
            sa.Column('short_code', sa.String(length=8), nullable=False),
            sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
            sa.Column('consumed_at', sa.DateTime(timezone=True), nullable=True),
            sa.Column('created_at', sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        )
        op.create_index('ix_pairing_sessions_user_id', 'pairing_sessions', ['user_id'])
        op.create_index('ix_pairing_sessions_session_token', 'pairing_sessions', ['session_token'], unique=True)
        op.create_index('ix_pairing_sessions_short_code', 'pairing_sessions', ['short_code'], unique=True)

    # 3. Create device_status table
    if 'device_status' not in tables:
        op.create_table(
            'device_status',
            sa.Column('id', sa.String(length=36), primary_key=True),
            sa.Column('device_id', sa.String(length=36), sa.ForeignKey('protected_devices.id', ondelete='CASCADE'), nullable=False, unique=True),
            sa.Column('battery_level', sa.Integer(), nullable=True),
            sa.Column('connectivity_type', sa.String(length=32), nullable=True),
            sa.Column('sim_operator', sa.String(length=64), nullable=True),
            sa.Column('sim_country', sa.String(length=8), nullable=True),
            sa.Column('sim_changed', sa.Boolean(), nullable=False, server_default=sa.text('false')),
            sa.Column('permission_health', sa.Text(), nullable=False, server_default='{}'),
            sa.Column('last_seen', sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
            sa.Column('last_sync_path', sa.String(length=16), nullable=False, server_default='online'),
            sa.Column('updated_at', sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        )
        op.create_index('ix_device_status_device_id', 'device_status', ['device_id'])

    # 4. Create device_audit_logs table
    if 'device_audit_logs' not in tables:
        op.create_table(
            'device_audit_logs',
            sa.Column('id', sa.String(length=36), primary_key=True),
            sa.Column('user_id', sa.String(length=36), sa.ForeignKey('users.id', ondelete='CASCADE'), nullable=False),
            sa.Column('device_id', sa.String(length=36), sa.ForeignKey('protected_devices.id', ondelete='CASCADE'), nullable=True),
            sa.Column('action', sa.String(length=64), nullable=False),
            sa.Column('actor_admin_id', sa.String(length=64), nullable=True),
            sa.Column('details', sa.Text(), nullable=True),
            sa.Column('created_at', sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        )
        op.create_index('ix_device_audit_logs_user_id', 'device_audit_logs', ['user_id'])
        op.create_index('ix_device_audit_logs_device_id', 'device_audit_logs', ['device_id'])


def downgrade() -> None:
    op.drop_table('device_audit_logs')
    op.drop_table('device_status')
    op.drop_table('pairing_sessions')
    op.drop_column('protected_devices', 'is_possible_reinstall')
    op.drop_column('protected_devices', 'is_device_owner')
    op.drop_column('protected_devices', 'install_date')
    op.drop_column('protected_devices', 'app_version')
    op.drop_column('protected_devices', 'variant')
    op.drop_column('protected_devices', 'sdk_level')
    op.drop_column('protected_devices', 'android_version')
    op.drop_column('protected_devices', 'model')
    op.drop_column('protected_devices', 'brand')
    op.drop_column('protected_devices', 'manufacturer')
    op.drop_column('protected_devices', 'key_fingerprint')
