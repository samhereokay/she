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
        password = args.password

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

        if not args.force:
            confirm = input(f"WARNING: This will permanently delete {user_count} user(s) and ALL paired devices. Proceed? [y/N]: ")
            if confirm.lower() != 'y':
                print("[ABORTED] Reset cancelled by user.")
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

        print("[SUCCESS] All owner accounts, paired devices, and sessions have been permanently cleared.")
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
    create_parser.add_argument("--password", "-p", required=True, help="Owner password (min 8 chars)")
    create_parser.set_defaults(func=handle_create_owner)

    # reset-owner
    reset_parser = subparsers.add_parser("reset-owner", help="Reset owner account and re-open bootstrap")
    reset_parser.add_argument("--force", "-f", action="store_true", help="Bypass interactive confirmation")
    reset_parser.set_defaults(func=handle_reset_owner)

    # status
    status_parser = subparsers.add_parser("status", help="Show owner and registration lockdown status")
    status_parser.set_defaults(func=handle_status)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
