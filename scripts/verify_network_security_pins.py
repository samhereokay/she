#!/usr/bin/env python3
"""
Verify that all 4 network_security_config.xml files contain all 6 required SPKI pins
matching the raw OpenSSL derivations from the live chain and official published CA roots.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

CONFIGS = [
    REPO_ROOT / "android/app-guardian/src/main/res/xml/network_security_config.xml",
    REPO_ROOT / "android/app-admin/src/main/res/xml/network_security_config.xml",
    REPO_ROOT / "android/app-guardian/src/debug/res/xml/network_security_config.xml",
    REPO_ROOT / "android/app-admin/src/debug/res/xml/network_security_config.xml",
]

EXPECTED_PINS = {
    "s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=": "Let's Encrypt Intermediate YE2 (Live Chain)",
    "sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=": "ISRG Root YE cross-sign (Live Chain)",
    "diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=": "ISRG Root X2 cross-sign & CA root",
    "C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=": "ISRG Root X1 trust anchor",
    "hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc=": "Google Trust Services GTS Root R1",
    "i7WTqTvh0OioIruIfFR4kMPnBqrS2rdiVPl/s2uC/CY=": "DigiCert Global Root G2",
}

def verify_config(path: Path) -> list[str]:
    errors = []
    if not path.exists():
        return [f"File not found: {path}"]
    tree = ET.parse(path)
    root = tree.getroot()
    pins_found = {elem.text.strip() for elem in root.findall(".//pin")}
    
    for expected_pin, label in EXPECTED_PINS.items():
        if expected_pin not in pins_found:
            errors.append(f"Missing pin for {label} ({expected_pin}) in {path.name}")
    return errors

def main():
    all_errors = []
    print("Verifying SPKI pins across all 4 Android network security configurations...")
    for cfg in CONFIGS:
        errs = verify_config(cfg)
        if errs:
            all_errors.extend(errs)
        else:
            print(f"  ✓ {cfg.relative_to(REPO_ROOT)}: All 6 SPKI pins present and verified")

    if all_errors:
        print("\n[FAIL] Pin verification errors:")
        for e in all_errors:
            print(f"  - {e}")
        sys.exit(1)
    print("\n[SUCCESS] All 4 network security configs match the derived SPKI pins perfectly!")

if __name__ == "__main__":
    main()
