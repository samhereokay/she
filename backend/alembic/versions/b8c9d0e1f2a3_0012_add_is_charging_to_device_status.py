"""0012 add is_charging to device_status

Revision ID: b8c9d0e1f2a3
Revises: a7b8c9d0e1f2
Create Date: 2026-10-23 12:45:00.000000

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = 'b8c9d0e1f2a3'
down_revision: Union[str, Sequence[str], None] = 'a7b8c9d0e1f2'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Add is_charging column to device_status."""
    with op.batch_alter_table('device_status', schema=None) as batch_op:
        batch_op.add_column(sa.Column('is_charging', sa.Boolean(), nullable=True, server_default=sa.text('false')))


def downgrade() -> None:
    """Remove is_charging column from device_status."""
    with op.batch_alter_table('device_status', schema=None) as batch_op:
        batch_op.drop_column('is_charging')
