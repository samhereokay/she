"""Add refresh_token_hash and is_2fa to user_sessions table.

Revision ID: c3d4e5f6a7b8
Revises: b2c3d4e5f6a7
Create Date: 2026-10-20

Context:
  Stores SHA-256 hashes of single-use rotating refresh tokens for session renewal
  over TLS, preventing token theft and supporting immediate session revocation.
"""
from alembic import op
import sqlalchemy as sa

revision = 'c3d4e5f6a7b8'
down_revision = 'b2c3d4e5f6a7'
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    insp = sa.inspect(bind)
    tables = insp.get_table_names()
    if 'user_sessions' in tables:
        columns = [c['name'] for c in insp.get_columns('user_sessions')]
        if 'refresh_token_hash' not in columns:
            op.add_column('user_sessions', sa.Column('refresh_token_hash', sa.String(length=64), nullable=True))
            op.create_index('ix_user_sessions_refresh_token_hash', 'user_sessions', ['refresh_token_hash'])
        if 'is_2fa' not in columns:
            op.add_column('user_sessions', sa.Column('is_2fa', sa.Boolean(), nullable=False, server_default=sa.text('false')))
        if 'consumed_refresh_hash' not in columns:
            op.add_column('user_sessions', sa.Column('consumed_refresh_hash', sa.String(length=64), nullable=True))
            op.create_index('ix_user_sessions_consumed_refresh_hash', 'user_sessions', ['consumed_refresh_hash'])
        if 'consumed_refresh_hashes' not in columns:
            op.add_column('user_sessions', sa.Column('consumed_refresh_hashes', sa.Text(), nullable=True, server_default='[]'))


def downgrade() -> None:
    op.drop_index('ix_user_sessions_consumed_refresh_hash', table_name='user_sessions')
    op.drop_column('user_sessions', 'consumed_refresh_hashes')
    op.drop_column('user_sessions', 'consumed_refresh_hash')
    op.drop_index('ix_user_sessions_refresh_token_hash', table_name='user_sessions')
    op.drop_column('user_sessions', 'refresh_token_hash')
    op.drop_column('user_sessions', 'is_2fa')
