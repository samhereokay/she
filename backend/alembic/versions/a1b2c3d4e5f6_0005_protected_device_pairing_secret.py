"""Add pairing_secret column to protected_devices for server-originated HMAC signing.

Revision ID: a1b2c3d4e5f6
Revises: 8c2d3e4f5a6b
Create Date: 2026-10-19

Context:
  SEND_VERIFY_SMS commands are server-originated and authenticated to the Guardian device
  via HMAC-SHA256 keyed with the shared pairing secret established during the pairing QR flow.
  The server previously only stored pairing_secret_hash (SHA-256) which cannot be reversed.
  This migration adds pairing_secret (nullable) alongside the hash. Existing devices must
  be re-paired (which sets the column) before phone verification is available to them.
"""
from alembic import op
import sqlalchemy as sa

revision = 'a1b2c3d4e5f6'
down_revision = '8c2d3e4f5a6b'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        'protected_devices',
        sa.Column('pairing_secret', sa.String(length=256), nullable=True)
    )


def downgrade() -> None:
    op.drop_column('protected_devices', 'pairing_secret')
