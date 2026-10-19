"""0004_verification_challenges

Revision ID: 8c2d3e4f5a6b
Revises: 7a1b2c3d4e5f
Create Date: 2026-10-19 14:15:00.000000

"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa

revision: str = '8c2d3e4f5a6b'
down_revision: Union[str, None] = '7a1b2c3d4e5f'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # 1. Add email and phone verification columns to users
    op.add_column('users', sa.Column('email_verified_at', sa.DateTime(timezone=True), nullable=True))
    op.add_column('users', sa.Column('fallback_phone_e164', sa.String(length=32), nullable=True))
    op.add_column('users', sa.Column('phone_verified_at', sa.DateTime(timezone=True), nullable=True))

    # 2. Create verification_challenges table
    op.create_table(
        'verification_challenges',
        sa.Column('id', sa.String(length=36), primary_key=True),
        sa.Column('user_id', sa.String(length=36), sa.ForeignKey('users.id', ondelete='CASCADE'), nullable=False),
        sa.Column('channel', sa.String(length=16), nullable=False),
        sa.Column('target', sa.String(length=255), nullable=False),
        sa.Column('code_hash', sa.String(length=64), nullable=False),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=False),
        sa.Column('attempts', sa.BigInteger(), nullable=False, server_default='0'),
        sa.Column('consumed_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), nullable=False)
    )
    op.create_index('ix_verification_challenges_user_id', 'verification_challenges', ['user_id'])


def downgrade() -> None:
    op.drop_index('ix_verification_challenges_user_id', table_name='verification_challenges')
    op.drop_table('verification_challenges')
    op.drop_column('users', 'phone_verified_at')
    op.drop_column('users', 'fallback_phone_e164')
    op.drop_column('users', 'email_verified_at')
