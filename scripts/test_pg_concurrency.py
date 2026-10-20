#!/usr/bin/env python3
"""
Standalone concurrency test for refresh token CAS rotation.
Runs against a real PostgreSQL instance via pgserver.
Does NOT use the SQLite-based conftest.py fixtures.

This test proves that:
1. Two concurrent refresh calls with the same token both succeed (grace window).
2. Replaying a 2-generation-old token fails with 401 + session revocation.
3. After revocation, even the latest valid token is rejected.

Run directly: python3 scripts/test_pg_concurrency.py
"""

import sys
import os
import concurrent.futures
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'backend'))
os.environ.setdefault('SECRET_KEY', '60d31945e0cc26369c9f55beaf0c068b365a155384b5292a74b01a78a1e3c1ff')
os.environ.setdefault('TOTP_ENCRYPTION_KEY', 'test-environment-totp-encryption-key-32bytes-for-testing')

import pgserver
import psycopg2
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker
from fastapi.testclient import TestClient


def setup_pg():
    """Start pgserver, create clean test DB, run migrations."""
    server = pgserver.get_server('/tmp/pg_conc_test_v2')
    pg_url = server.get_uri('conctest')

    # Create the database if needed
    admin_conn = psycopg2.connect(database='postgres',
                                  user='postgres',
                                  host='/tmp/pg_conc_test_v2')
    admin_conn.autocommit = True
    cur = admin_conn.cursor()
    cur.execute("SELECT 1 FROM pg_database WHERE datname='conctest'")
    if not cur.fetchone():
        cur.execute('CREATE DATABASE conctest')
        print("conctest created")
    else:
        # Drop and recreate for a clean slate
        # Terminate existing connections first
        cur.execute("""
            SELECT pg_terminate_backend(pid) FROM pg_stat_activity
            WHERE datname = 'conctest' AND pid <> pg_backend_pid()
        """)
        cur.execute('DROP DATABASE conctest')
        cur.execute('CREATE DATABASE conctest')
        print("conctest dropped and recreated (clean slate)")
    admin_conn.close()

    os.environ['DATABASE_URL'] = pg_url
    return pg_url, server


def run_alembic(pg_url):
    import subprocess
    env = {**os.environ, 'DATABASE_URL': pg_url}
    r = subprocess.run(
        [sys.executable, '-m', 'alembic', 'upgrade', 'head'],
        capture_output=True, text=True,
        cwd=os.path.join(os.path.dirname(__file__), '..', 'backend'),
        env=env
    )
    if r.returncode != 0:
        print("ALEMBIC STDERR:", r.stderr[-2000:])
        raise RuntimeError(f"alembic upgrade head failed (rc={r.returncode})")
    print("alembic upgrade head: OK")


def make_pg_test_client(pg_url):
    """Create a TestClient backed by the real PostgreSQL DB."""
    from sqlalchemy.pool import NullPool
    from app.main import app
    from app.db.session import Base, get_db

    # pgserver returns a socket-based URI like:
    #   postgresql://postgres:@/conctest?host=/tmp/pg_conc_test_v2
    # SQLAlchemy needs psycopg2 dialect specified explicitly.
    pg2_url = pg_url.replace('postgresql://', 'postgresql+psycopg2://', 1)

    engine = create_engine(pg2_url, poolclass=NullPool)
    TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

    def override_get_db():
        db = TestingSessionLocal()
        try:
            yield db
        finally:
            db.close()

    app.dependency_overrides[get_db] = override_get_db
    return TestClient(app), engine


def run_test(client):
    from app.core.config import settings
    settings.ALLOW_PUBLIC_REGISTRATION = True

    print("\n--- STEP 1: register + login ---")
    reg = client.post('/auth/register', json={
        'username': 'pg_race_user',
        'email': 'pg_race@example.com',
        'password': 'Password123!PgRace'
    })
    assert reg.status_code in (200, 201, 409), f"Register: {reg.status_code} {reg.text}"

    login = client.post('/auth/login/json', json={
        'username': 'pg_race_user',
        'password': 'Password123!PgRace'
    })
    assert login.status_code == 200, f"Login failed: {login.text}"
    ref_tok = login.json()['refresh_token']
    print(f"Got refresh_token: {ref_tok[:16]}...")

    print("\n--- STEP 2: concurrent refresh with same token (should both succeed via grace window) ---")
    results = []

    def do_refresh():
        return client.post('/auth/refresh', json={'refresh_token': ref_tok})

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        f1 = executor.submit(do_refresh)
        f2 = executor.submit(do_refresh)
        results = [f1.result(), f2.result()]

    status_codes = sorted([r.status_code for r in results])
    print(f"Concurrent refresh status codes: {status_codes}")
    print(f"  Response 1: {results[0].status_code} — {list(results[0].json().keys())}")
    print(f"  Response 2: {results[1].status_code} — {list(results[1].json().keys())}")

    assert status_codes == [200, 200], \
        f"Expected [200, 200], got {status_codes}. " \
        f"R1: {results[0].text[:200]}, R2: {results[1].text[:200]}"
    print("✓ Both concurrent requests succeeded (grace window working on PostgreSQL WITH row locking)")

    # Get the live token from whichever response has it
    new_tok = next(r.json().get('refresh_token') for r in results if r.json().get('refresh_token'))
    print(f"\n--- STEP 3: rotate two more generations ---")
    gen2 = client.post('/auth/refresh', json={'refresh_token': new_tok})
    assert gen2.status_code == 200, f"Gen2 refresh failed: {gen2.text}"
    gen3_tok = gen2.json()['refresh_token']
    gen3 = client.post('/auth/refresh', json={'refresh_token': gen3_tok})
    assert gen3.status_code == 200, f"Gen3 refresh failed: {gen3.text}"
    gen4_tok = gen3.json()['refresh_token']
    print(f"✓ Rotated to gen4: {gen4_tok[:16]}...")

    print("\n--- STEP 4: replay the original token (2+ generations old) — must be rejected ---")
    replay = client.post('/auth/refresh', json={'refresh_token': ref_tok})
    print(f"Replay status: {replay.status_code}, body: {replay.json()}")
    assert replay.status_code == 401, \
        f"Expected 401 for replayed historical token, got {replay.status_code}"
    assert 'reuse detected' in replay.json().get('detail', '').lower(), \
        f"Expected 'reuse detected' in detail, got: {replay.json()}"
    print("✓ Historical token replay rejected with 401")

    print("\n--- STEP 5: confirm session is revoked — even gen4 token must fail ---")
    after = client.post('/auth/refresh', json={'refresh_token': gen4_tok})
    print(f"After-revoke status: {after.status_code}")
    assert after.status_code == 401, \
        f"Expected 401 after session revocation, got {after.status_code}"
    print("✓ Latest token also rejected after revocation")

    print("\n=== ALL ASSERTIONS PASSED ===")


if __name__ == '__main__':
    print("=== PostgreSQL CAS Concurrency Test ===\n")

    pg_url, server = setup_pg()
    print(f"PostgreSQL URL: {pg_url}")

    run_alembic(pg_url)

    client, engine = make_pg_test_client(pg_url)

    try:
        run_test(client)
    finally:
        from app.main import app
        app.dependency_overrides.clear()

    print("\nDone.")
