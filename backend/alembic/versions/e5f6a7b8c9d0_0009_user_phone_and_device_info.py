"""0009 add phone_number and device_info to users

Revision ID: e5f6a7b8c9d0
Revises: d4e5f6a7b8c9
Create Date: 2026-10-21
"""
from alembic import op
import sqlalchemy as sa

revision = 'e5f6a7b8c9d0'
down_revision = 'd4e5f6a7b8c9'
branch_labels = None
depends_on = None


def upgrade() -> None:
    bind = op.get_bind()
    insp = sa.inspect(bind)
    tables = insp.get_table_names()
    if 'users' in tables:
        columns = [c['name'] for c in insp.get_columns('users')]
        if 'phone_number' not in columns:
            op.add_column('users', sa.Column('phone_number', sa.String(length=32), nullable=True))
        if 'device_info' not in columns:
            op.add_column('users', sa.Column('device_info', sa.Text(), nullable=True))


def downgrade() -> None:
    op.drop_column('users', 'device_info')
    op.drop_column('users', 'phone_number')
