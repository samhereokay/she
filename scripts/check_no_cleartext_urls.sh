#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# SHE Anti-Theft: Cleartext URL & Plain HTTP Leakage Guard
# Enforces that no cleartext http:// or ws:// URLs exist in release source files.
# ==============================================================================

echo "=== Running Cleartext URL and Plain HTTP Audit ==="

# Check Android release code for cleartext http:// (excluding XML namespaces and test files)
LEAKS=$(grep -rn \
    --exclude-dir=build \
    --exclude-dir=.gradle \
    --exclude-dir=test \
    --exclude-dir=androidTest \
    "http://" \
    android/ | grep -v 'schemas\.android\.com' || true)

# Filter out legitimate string replacements (e.g. .replace("http://", "https://"))
FILTERED_LEAKS=$(echo "$LEAKS" | grep -v 'replace.*"http://"' || true)

if [ -n "$FILTERED_LEAKS" ]; then
    echo "ERROR: Plain http:// URLs found in release sources:"
    echo "$FILTERED_LEAKS"
    exit 1
fi

# Check for ws:// in release sources (WebSockets must use wss:// only)
WS_LEAKS=$(grep -rn \
    --exclude-dir=build \
    --exclude-dir=.gradle \
    --exclude-dir=test \
    --exclude-dir=androidTest \
    'ws://' \
    android/ || true)

FILTERED_WS_LEAKS=$(echo "$WS_LEAKS" | grep -v 'replace.*"ws://"' || true)

if [ -n "$FILTERED_WS_LEAKS" ]; then
    echo "ERROR: Plain ws:// URLs found in release sources (must use wss://):"
    echo "$FILTERED_WS_LEAKS"
    exit 1
fi

# Check Android Manifests for cleartext traffic permission
CLEARTEXT_MANIFEST=$(grep -rn 'android:usesCleartextTraffic="true"' android/ || true)
if [ -n "$CLEARTEXT_MANIFEST" ]; then
    echo "ERROR: android:usesCleartextTraffic=\"true\" detected in Manifests:"
    echo "$CLEARTEXT_MANIFEST"
    exit 1
fi

echo "PASS: Zero cleartext http:// or ws:// URLs detected in release sources."
exit 0
