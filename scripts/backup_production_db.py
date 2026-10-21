import os
import sys
import json
import psycopg2
from psycopg2.extras import RealDictCursor
from datetime import datetime

DB_URL = "postgresql://postgres.dmyuumblqnncgpbijxhf:Saikaa$$!!77@aws-0-ap-northeast-2.pooler.supabase.com:6543/postgres"

print("Connecting to production PostgreSQL database...")
try:
    conn = psycopg2.connect(DB_URL)
    cur = conn.cursor(cursor_factory=RealDictCursor)
except Exception as e:
    print(f"Failed to connect: {e}")
    sys.exit(1)

# List all tables in public schema
cur.execute("""
    SELECT table_name 
    FROM information_schema.tables 
    WHERE table_schema = 'public' AND table_type = 'BASE TABLE';
""")
tables = [row["table_name"] for row in cur.fetchall()]
print(f"Discovered {len(tables)} tables: {', '.join(tables)}")

backup_data = {
    "metadata": {
        "timestamp": datetime.utcnow().isoformat(),
        "tables_count": len(tables)
    },
    "tables": {}
}

total_rows = 0
for table in sorted(tables):
    cur.execute(f'SELECT * FROM "{table}"')
    rows = cur.fetchall()
    # Convert dates/objects to strings
    serialized_rows = []
    for r in rows:
        row_dict = {}
        for k, v in dict(r).items():
            if isinstance(v, (datetime, bytes)):
                row_dict[k] = str(v)
            else:
                row_dict[k] = v
        serialized_rows.append(row_dict)
    backup_data["tables"][table] = serialized_rows
    total_rows += len(serialized_rows)
    print(f"  - {table}: {len(serialized_rows)} rows backed up")

filename = f"/home/sam/Documents/she/backups/she-backup-{datetime.utcnow().strftime('%Y-%m-%d_%H%M%S')}.json"
with open(filename, "w") as f:
    json.dump(backup_data, f, indent=2)

print(f"\nSUCCESS: Backed up {total_rows} total rows across {len(tables)} tables to:")
print(filename)
cur.close()
conn.close()
