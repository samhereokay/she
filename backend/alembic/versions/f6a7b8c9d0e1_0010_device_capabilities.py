"""0010 add capabilities to device_status

Revision ID: f6a7b8c9d0e1
Revises: e5f6a7b8c9d0
Create Date: 2026-10-21
"""
from alembic import op
import sqlalchemy as sa

revision = 'f6a7b8c9d0e1'
down_revision = 'e5f6a7b8c9d0'
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    insp = sa.inspect(bind)
    tables = insp.get_table_names()
    if 'device_status' in tables:
        columns = [c['name'] for c in insp.get_columns('device_status')]
        if 'capabilities' not in columns:
            op.add_column('device_status', sa.Column('capabilities', sa.Text(), nullable=False, server_default='[]'))


def downgrade() -> None:
    op.drop_column('device_status', 'capabilities')
