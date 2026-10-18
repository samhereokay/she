#!/usr/bin/env bash
# =============================================================================
# SHE Anti-Theft: Release APK Compiler
# =============================================================================
# Compiles production-optimized Release APKs for Guardian and Admin Companion.
# =============================================================================
set -e

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ANDROID_DIR="$REPO_ROOT/android"
TOOLS_DIR="$REPO_ROOT/.tools"

export JAVA_HOME="$TOOLS_DIR/jdk17"
export PATH="$JAVA_HOME/bin:$PATH"
GRADLE_BIN="$TOOLS_DIR/gradle-8.7/bin/gradle"

if [ ! -d "$JAVA_HOME" ]; then
    echo "❌ JDK 17 not found at $JAVA_HOME"
    exit 1
fi

if [ ! -f "$GRADLE_BIN" ]; then
    echo "❌ Gradle binary not found at $GRADLE_BIN"
    exit 1
fi

echo "================================================================="
echo "🔨 Compiling SHE Direct Debug & Release APKs"
echo "================================================================="
echo "Java Version: $(java -version 2>&1 | head -n 1)"
echo "Gradle: $($GRADLE_BIN --version | grep 'Gradle ')"
echo "================================================================="

cd "$ANDROID_DIR"
$GRADLE_BIN assembleDebug

echo "================================================================="
echo "✓ Build Complete!"
echo "Artifacts:"
echo "  * Admin:    $ANDROID_DIR/app-admin/build/outputs/apk/debug/app-admin-debug.apk"
echo "  * Guardian: $ANDROID_DIR/app-guardian/build/outputs/apk/direct/debug/app-guardian-direct-debug.apk"
echo "================================================================="
