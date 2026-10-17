"""0003_totp_recovery_codes

Revision ID: 7a1b2c3d4e5f
Revises: 5482d5039da3
Create Date: 2026-10-17 18:40:00.000000

"""
from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa

revision: str = '7a1b2c3d4e5f'
down_revision: Union[str, None] = '5482d5039da3'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column('users', sa.Column('totp_recovery_hashes', sa.Text(), nullable=True, server_default='[]'))


def downgrade() -> None:
    op.drop_column('users', 'totp_recovery_hashes')
