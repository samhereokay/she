#!/usr/bin/env python3
"""
Manifest & Security Configuration Audit Script
==============================================
Validates Android manifests, build configurations, and network security configs
against architectural and security policies for Step 1B (Guardian Lite):

Rules enforced:
1. Guardian Manifest:
   - NO android.permission.BIND_DEVICE_ADMIN
   - NO DeviceAdminReceiver component
   - NO android:priority on SMS receiver (specifically no priority="999" or >= 100)
   - android:usesCleartextTraffic is NOT set to true
2. Guardian Gradle Config:
   - applicationId is "com.antitheft.guardian.lite"
   - NO project(":device-owner") dependency
3. Guardian Resources:
   - app_name is "SHE Guardian Lite" (contains "Lite", no legacy "Beta")
4. Admin Manifest:
   - android.permission.SEND_SMS present (for runtime fallback)
   - android.permission.RECEIVE_SMS present (for SMS telemetry parsing)
   - NO android.permission.BIND_DEVICE_ADMIN
   - NO DeviceAdminReceiver component
   - android:usesCleartextTraffic is NOT set to true
5. Release Network Security Configs:
   - No <debug-overrides> in release configs
   - Must only trust system CAs in release configs (<certificates src="system" />)
   - Must NOT trust user CAs (<certificates src="user" />)
"""

import sys
import os
import argparse
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

GUARDIAN_MANIFEST = REPO_ROOT / "android/app-guardian/src/main/AndroidManifest.xml"
GUARDIAN_GRADLE = REPO_ROOT / "android/app-guardian/build.gradle.kts"
GUARDIAN_STRINGS = REPO_ROOT / "android/app-guardian/src/main/res/values/strings.xml"
GUARDIAN_NET_CONFIG = REPO_ROOT / "android/app-guardian/src/main/res/xml/network_security_config.xml"

ADMIN_MANIFEST = REPO_ROOT / "android/app-admin/src/main/AndroidManifest.xml"
ADMIN_GRADLE = REPO_ROOT / "android/app-admin/build.gradle.kts"
ADMIN_NET_CONFIG = REPO_ROOT / "android/app-admin/src/main/res/xml/network_security_config.xml"

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


class AuditFailure(Exception):
    pass


def audit_guardian_manifest(xml_content: str) -> list[str]:
    violations = []
    root = ET.fromstring(xml_content)

    # 1. Check permissions for BIND_DEVICE_ADMIN
    for elem in root.findall(".//receiver"):
        perm = elem.attrib.get(f"{ANDROID_NS}permission", "")
        name = elem.attrib.get(f"{ANDROID_NS}name", "")
        if "BIND_DEVICE_ADMIN" in perm:
            violations.append(f"Guardian receiver '{name}' requests prohibited BIND_DEVICE_ADMIN permission.")
        if "DeviceAdmin" in name or "AntiTheftDeviceAdminReceiver" in name:
            violations.append(f"Guardian declares prohibited DeviceAdminReceiver: '{name}'.")

    # 2. Check SMS receiver priority
    for receiver in root.findall(".//receiver"):
        name = receiver.attrib.get(f"{ANDROID_NS}name", "")
        for intent_filter in receiver.findall("intent-filter"):
            prio = intent_filter.attrib.get(f"{ANDROID_NS}priority")
            if prio is not None:
                try:
                    prio_val = int(prio)
                    if prio_val >= 100:
                        violations.append(
                            f"Guardian receiver '{name}' specifies prohibited android:priority='{prio}'."
                        )
                except ValueError:
                    violations.append(f"Guardian receiver '{name}' has invalid priority: '{prio}'.")

    # 3. Check cleartext traffic
    application = root.find("application")
    if application is not None:
        cleartext = application.attrib.get(f"{ANDROID_NS}usesCleartextTraffic")
        if cleartext and cleartext.lower() == "true":
            violations.append("Guardian manifest enables android:usesCleartextTraffic='true'.")

    return violations


def audit_guardian_gradle(gradle_content: str) -> list[str]:
    violations = []
    if 'applicationId = "com.antitheft.guardian.lite"' not in gradle_content:
        violations.append('Guardian build.gradle.kts must declare applicationId = "com.antitheft.guardian.lite"')
    if 'project(":device-owner")' in gradle_content:
        violations.append('Guardian build.gradle.kts must not depend on project(":device-owner") in Lite variant')
    return violations


def audit_guardian_strings(strings_content: str) -> list[str]:
    violations = []
    root = ET.fromstring(strings_content)
    for string_elem in root.findall("string"):
        if string_elem.attrib.get("name") == "app_name":
            text = string_elem.text or ""
            if "Lite" not in text:
                violations.append(f"Guardian app_name '{text}' does not contain 'Lite'.")
            if "Beta" in text:
                violations.append(f"Guardian app_name '{text}' contains legacy 'Beta' branding.")
    return violations


def audit_admin_manifest(xml_content: str) -> list[str]:
    violations = []
    root = ET.fromstring(xml_content)

    permissions = set()
    for elem in root.findall("uses-permission"):
        name = elem.attrib.get(f"{ANDROID_NS}name", "")
        permissions.add(name)

    if "android.permission.SEND_SMS" not in permissions:
        violations.append("Admin manifest missing required 'android.permission.SEND_SMS'.")
    if "android.permission.RECEIVE_SMS" not in permissions:
        violations.append("Admin manifest missing required 'android.permission.RECEIVE_SMS'.")

    for elem in root.findall(".//receiver"):
        perm = elem.attrib.get(f"{ANDROID_NS}permission", "")
        name = elem.attrib.get(f"{ANDROID_NS}name", "")
        if "BIND_DEVICE_ADMIN" in perm:
            violations.append(f"Admin receiver '{name}' requests prohibited BIND_DEVICE_ADMIN permission.")
        if "DeviceAdmin" in name:
            violations.append(f"Admin declares prohibited DeviceAdminReceiver: '{name}'.")

    application = root.find("application")
    if application is not None:
        cleartext = application.attrib.get(f"{ANDROID_NS}usesCleartextTraffic")
        if cleartext and cleartext.lower() == "true":
            violations.append("Admin manifest enables android:usesCleartextTraffic='true'.")

    return violations


def audit_network_security_config(xml_content: str, label: str) -> list[str]:
    violations = []
    root = ET.fromstring(xml_content)

    if root.find("debug-overrides") is not None:
        violations.append(f"{label} contains <debug-overrides> in main/release configuration.")

    for cert in root.findall(".//certificates"):
        src = cert.attrib.get("src", "")
        if src == "user":
            violations.append(f"{label} trusts user CAs (<certificates src=\"user\" />) in release config.")

    return violations


def run_full_audit(verbose: bool = True) -> tuple[int, list[str]]:
    all_violations = []

    # Guardian Manifest
    if verbose:
        print("--> Auditing Guardian AndroidManifest.xml...")
    v = audit_guardian_manifest(GUARDIAN_MANIFEST.read_text())
    all_violations.extend(v)

    # Guardian Gradle
    if verbose:
        print("--> Auditing Guardian build.gradle.kts...")
    v = audit_guardian_gradle(GUARDIAN_GRADLE.read_text())
    all_violations.extend(v)

    # Guardian Strings
    if verbose:
        print("--> Auditing Guardian strings.xml...")
    v = audit_guardian_strings(GUARDIAN_STRINGS.read_text())
    all_violations.extend(v)

    # Admin Manifest
    if verbose:
        print("--> Auditing Admin AndroidManifest.xml...")
    v = audit_admin_manifest(ADMIN_MANIFEST.read_text())
    all_violations.extend(v)

    # Release Network Security Configs
    if verbose:
        print("--> Auditing Guardian release network_security_config.xml...")
    v = audit_network_security_config(GUARDIAN_NET_CONFIG.read_text(), "Guardian release network config")
    all_violations.extend(v)

    if verbose:
        print("--> Auditing Admin release network_security_config.xml...")
    v = audit_network_security_config(ADMIN_NET_CONFIG.read_text(), "Admin release network config")
    all_violations.extend(v)

    return len(all_violations), all_violations


def test_failure_demonstration():
    """
    Demonstrates that the audit script catches intentional manifest/config violations.
    """
    print("\n=======================================================")
    print("DEMONSTRATION: Verifying audit script detects violations")
    print("=======================================================")

    # Test 1: BIND_DEVICE_ADMIN violation
    bad_manifest_admin = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
        <application>
            <receiver android:name=".BadAdminReceiver" android:permission="android.permission.BIND_DEVICE_ADMIN">
                <intent-filter>
                    <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
                </intent-filter>
            </receiver>
        </application>
    </manifest>"""
    violations = audit_guardian_manifest(bad_manifest_admin)
    assert any("BIND_DEVICE_ADMIN" in v for v in violations), f"Failed to catch BIND_DEVICE_ADMIN: {violations}"
    print("[PASS] Caught intentional BIND_DEVICE_ADMIN violation:")
    for v in violations:
        print(f"       -> {v}")

    # Test 2: Priority 999 violation
    bad_manifest_priority = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
        <application>
            <receiver android:name=".SmsReceiver">
                <intent-filter android:priority="999">
                    <action android:name="android.provider.Telephony.SMS_RECEIVED" />
                </intent-filter>
            </receiver>
        </application>
    </manifest>"""
    violations = audit_guardian_manifest(bad_manifest_priority)
    assert any("priority='999'" in v for v in violations), f"Failed to catch priority 999: {violations}"
    print("[PASS] Caught intentional priority='999' violation:")
    for v in violations:
        print(f"       -> {v}")

    # Test 3: Cleartext traffic violation
    bad_manifest_cleartext = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
        <application android:usesCleartextTraffic="true" />
    </manifest>"""
    violations = audit_guardian_manifest(bad_manifest_cleartext)
    assert any("usesCleartextTraffic" in v for v in violations), f"Failed to catch cleartext traffic: {violations}"
    print("[PASS] Caught intentional usesCleartextTraffic='true' violation:")
    for v in violations:
        print(f"       -> {v}")

    # Test 4: Missing SMS permissions in Admin
    bad_admin_manifest = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
        <application />
    </manifest>"""
    violations = audit_admin_manifest(bad_admin_manifest)
    assert any("SEND_SMS" in v for v in violations), f"Failed to catch missing SEND_SMS: {violations}"
    print("[PASS] Caught intentional missing SEND_SMS in Admin manifest:")
    for v in violations:
        print(f"       -> {v}")

    # Test 5: User CA trust in release network security config
    bad_net_config = """<network-security-config>
        <base-config>
            <trust-anchors>
                <certificates src="system" />
                <certificates src="user" />
            </trust-anchors>
        </base-config>
    </network-security-config>"""
    violations = audit_network_security_config(bad_net_config, "Test Config")
    assert any("trusts user CAs" in v for v in violations), f"Failed to catch user CA trust: {violations}"
    print("[PASS] Caught intentional user CA trust violation in release config:")
    for v in violations:
        print(f"       -> {v}")

    print("\nDemonstration complete: All 5 violation tests successfully detected.\n")


def main():
    parser = argparse.ArgumentParser(description="Audit Android Manifests and Security Configurations")
    parser.add_argument("--demonstrate-failure", action="store_true", help="Demonstrate detection of intentional violations")
    args = parser.parse_args()

    if args.demonstrate_failure:
        test_failure_demonstration()

    count, violations = run_full_audit(verbose=True)
    if count == 0:
        print("\n[SUCCESS] Manifest & Security Audit PASSED! All 1B rules satisfied.")
        sys.exit(0)
    else:
        print(f"\n[FAILURE] Manifest & Security Audit FAILED with {count} violations:")
        for v in violations:
            print(f"  - {v}")
        sys.exit(1)


if __name__ == "__main__":
    main()
