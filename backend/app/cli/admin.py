#!/usr/bin/env python3
"""
Command-Line Management Tool for Anti-Theft Administrator / Owner Accounts.
Supports:
1. create-owner: Create initial single-owner account directly via CLI.
2. reset-owner: Reset owner account from server terminal when registration is locked.
3. status: Check lockdown status, owner provisioning, and registered devices.
"""

import argparse
import sys
from app.db.session import SessionLocal
from app.db.models import User, TrustedDevice, ProtectedDevice, AuditEvent, DeviceCommand, LocationEvent, VaultItem
from app.core.security import hash_password
from app.core.config import settings


def get_db(args=None):
    if args and getattr(args, "db", None) is not None:
        return args.db, False
    return SessionLocal(), True


def handle_create_owner(args):
    db, should_close = get_db(args)
    try:
        existing_count = db.query(User).count()
        if existing_count > 0:
            print(f"[ERROR] Owner account already initialized ({existing_count} user(s) exist).", file=sys.stderr)
            print("Public registration and bootstrap are permanently locked down.", file=sys.stderr)
            print("To reset the owner, run: python -m app.cli.admin reset-owner --force", file=sys.stderr)
            sys.exit(1)

        username = args.username.strip()
        email = args.email.strip()
        password = getattr(args, "password", None)

        if not password:
            import getpass
            password = getpass.getpass("Enter owner password (min 8 chars): ")
            confirm = getpass.getpass("Confirm owner password: ")
            if password != confirm:
                print("[ERROR] Passwords do not match.", file=sys.stderr)
                sys.exit(1)

        if len(password) < 8:
            print("[ERROR] Password must be at least 8 characters long.", file=sys.stderr)
            sys.exit(1)

        hashed = hash_password(password)
        new_user = User(
            username=username,
            email=email,
            hashed_password=hashed,
            totp_enabled=False
        )
        db.add(new_user)
        db.commit()
        db.refresh(new_user)

        print(f"[SUCCESS] Owner account '{username}' (<{email}>) successfully created (ID: {new_user.id}).")
        print("Public registration is now permanently locked down.")
        print("Next step: Log into the Admin Companion App and enroll Two-Factor Authentication (TOTP).")
    finally:
        if should_close:
            db.close()


def handle_reset_owner(args):
    db, should_close = get_db(args)
    try:
        user_count = db.query(User).count()
        if user_count == 0:
            print("[INFO] No owner accounts exist in the database. Nothing to reset.")
            return

        vault_count = db.query(VaultItem).count()
        device_count = db.query(ProtectedDevice).count()

        print("=" * 68)
        print("!!! CRITICAL DATA LOSS WARNING: OWNER RESET REQUESTED !!!")
        print("Resetting the platform owner will permanently DESTROY:")
        print(f"  - {user_count} owner user account(s)")
        print(f"  - {device_count} paired protected handset(s)")
        print(f"  - {vault_count} encrypted recovery vault secret(s) [IRREVERSIBLE]")
        print("All server-side encrypted vault envelopes and pairing keys will be ERASED.")
        print("=" * 68)

        if not args.force:
            confirm = input("Type 'RESET' to confirm permanent destruction of all vault and owner data: ")
            if confirm.strip() != "RESET":
                print("[ABORTED] Reset cancelled by user. No data was deleted.")
                sys.exit(0)

        # Purge dependent records first
        db.query(LocationEvent).delete()
        db.query(DeviceCommand).delete()
        db.query(VaultItem).delete()
        db.query(AuditEvent).delete()
        db.query(TrustedDevice).delete()
        db.query(ProtectedDevice).delete()
        db.query(User).delete()
        db.commit()

        print(f"[SUCCESS] Cleared {user_count} user(s), {device_count} device(s), and {vault_count} vault secret(s).")
        print("Initial owner bootstrap is now re-opened.")
    finally:
        if should_close:
            db.close()


def handle_status(args):
    db, should_close = get_db(args)
    try:
        users = db.query(User).all()
        print("=== AntiTheft Platform Security & Owner Status ===")
        print(f"ALLOW_PUBLIC_REGISTRATION (config): {settings.ALLOW_PUBLIC_REGISTRATION}")
        print(f"ADMIN_SETUP_TOKEN configured: {'Yes' if bool(settings.ADMIN_SETUP_TOKEN) else 'No'}")
        print(f"Total Users: {len(users)}")
        if users:
            print(f"Registration State: PERMANENTLY LOCKED DOWN (Single-Owner Invariant Enforced)")
            for u in users:
                dev_count = db.query(ProtectedDevice).filter(ProtectedDevice.user_id == u.id).count()
                trusted_count = db.query(TrustedDevice).filter(TrustedDevice.user_id == u.id).count()
                print(f" - Owner: {u.username} <{u.email}> (2FA Enrolled: {u.totp_enabled}, Paired Devices: {dev_count}, Trusted Admin Devices: {trusted_count})")
        else:
            print(f"Registration State: OPEN FOR INITIAL BOOTSTRAP (No owner provisioned yet)")
    finally:
        if should_close:
            db.close()


def handle_setup_2fa(args):
    db, should_close = get_db(args)
    try:
        username = args.username.strip()
        user = db.query(User).filter(User.username == username).first()
        if not user:
            print(f"[ERROR] User '{username}' not found.", file=sys.stderr)
            sys.exit(1)

        from app.core.security import generate_totp_secret, encrypt_totp_secret, get_totp_uri, verify_totp_code
        from app.api.auth import generate_recovery_codes
        import json

        secret = generate_totp_secret()
        uri = get_totp_uri(secret, user.username)

        print("=" * 64)
        print("          TWO-FACTOR AUTHENTICATION (TOTP) ENROLLMENT")
        print("=" * 64)
        print(f"Account: {user.username} ({user.email})")
        print("\n1. In your Authenticator App (Google Authenticator, Aegis, Bitwarden):")
        print("   Tap '+' -> 'Enter a setup key' (or 'Scan QR')")
        print(f"   Account Name: SHE ({user.username})")
        print(f"   Secret Key  : {secret}")
        print("   Key Type    : Time-based (TOTP)\n")
        print(f"Authenticator URI: {uri}\n")

        code = input("2. Enter the current 6-digit code shown in Google Authenticator to verify: ").strip()
        if not verify_totp_code(secret, code):
            print("[ERROR] Invalid TOTP code. 2FA enrollment cancelled.", file=sys.stderr)
            sys.exit(1)

        plain_codes, recovery_hashes = generate_recovery_codes()
        user.totp_secret = encrypt_totp_secret(secret)
        user.totp_enabled = True
        user.totp_recovery_hashes = json.dumps(recovery_hashes)
        db.commit()

        print("\n[SUCCESS] Two-Factor Authentication (2FA) successfully ACTIVATED!")
        print("=" * 64)
        print("CRITICAL: SAVE THESE 8 SINGLE-USE EMERGENCY RECOVERY CODES:")
        print("(Write these down on paper. If your phone is stolen, use one to bypass TOTP)")
        print("-" * 64)
        for i, rc in enumerate(plain_codes, 1):
            print(f"  Code {i}: {rc}")
        print("=" * 64)
    finally:
        if should_close:
            db.close()


def main():
    parser = argparse.ArgumentParser(
        prog="python -m app.cli.admin",
        description="AntiTheft Owner Account Management CLI"
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    # create-owner
    create_parser = subparsers.add_parser("create-owner", help="Create initial owner account")
    create_parser.add_argument("--username", "-u", required=True, help="Owner username")
    create_parser.add_argument("--email", "-e", required=True, help="Owner email address")
    create_parser.add_argument("--password", "-p", required=False, default=None, help="Owner password (if omitted, will be prompted securely)")
    create_parser.set_defaults(func=handle_create_owner)

    # reset-owner
    reset_parser = subparsers.add_parser("reset-owner", help="Reset owner account and re-open bootstrap")
    reset_parser.add_argument("--force", "-f", action="store_true", help="Bypass interactive confirmation")
    reset_parser.set_defaults(func=handle_reset_owner)

    # setup-2fa
    setup_2fa_parser = subparsers.add_parser("setup-2fa", help="Enroll Two-Factor Authentication (TOTP) and generate recovery codes")
    setup_2fa_parser.add_argument("--username", "-u", required=True, help="Owner username to enroll")
    setup_2fa_parser.set_defaults(func=handle_setup_2fa)

    # status
    status_parser = subparsers.add_parser("status", help="Show owner and registration lockdown status")
    status_parser.set_defaults(func=handle_status)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()

