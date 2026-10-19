#!/usr/bin/env bash
# ==============================================================================
# SHE Anti-Theft & Recovery System
# Unified Automated Self-Hosted Runner & Setup Script
#
# Supported Platforms:
#   - Linux: Ubuntu, Debian, Arch Linux, Fedora, CentOS/RHEL, Alpine
#   - Android: Termux (aarch64 / armv7l / x86_64)
#
# Usage:
#   ./runner.sh setup            # Auto-detect OS & install all prerequisites + venv
#   ./runner.sh start [--daemon] # Start the self-hosted backend service
#   ./runner.sh stop             # Stop background backend service
#   ./runner.sh status           # Check running backend health and status
#   ./runner.sh test             # Execute full test suite
#   ./runner.sh install-guardian # Install latest signed Guardian APK (Termux/ADB)
# ==============================================================================

set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="${SCRIPT_DIR}/.runner.pid"
LOG_FILE="${SCRIPT_DIR}/runner.log"
VENV_DIR="${SCRIPT_DIR}/.venv"
PORT="${PORT:-8000}"
HOST="${HOST:-0.0.0.0}"

# ANSI Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

banner() {
    echo -e "${CYAN}${BOLD}"
    cat << "EOF"
  ____  _   _ _____     _             _   _     _____ _            _   
 / ___|| | | | ____|   / \   _ __  | |_(_)   |_   _| |__   ___| |_| |_ 
 \___ \| |_| |  _|    / _ \ | '_ \ | __| |_____| | | '_ \ / _ \ __| __|
  ___) |  _  | |___  / ___ \| | | || |_| |_____| | | | | |  __/ |_| |_ 
 |____/|_| |_|_____|/_/   \_\_| |_| \__|_|     |_| |_| |_|\___|\__|\__|
EOF
    echo -e "  Unified Automated Runner (Linux & Termux Edition)${NC}\n"
}

# -----------------------------------------------------------------------------
# Platform & Environment Detection
# -----------------------------------------------------------------------------
IS_TERMUX=false
if [ -n "${TERMUX_VERSION:-}" ] || [[ "${PREFIX:-}" == *"com.termux"* ]]; then
    IS_TERMUX=true
fi

detect_pkg_mgr() {
    if $IS_TERMUX; then
        echo "termux"
    elif command -v apt-get >/dev/null 2>&1; then
        echo "apt"
    elif command -v dnf >/dev/null 2>&1; then
        echo "dnf"
    elif command -v pacman >/dev/null 2>&1; then
        echo "pacman"
    elif command -v apk >/dev/null 2>&1; then
        echo "apk"
    else
        echo "unknown"
    fi
}

# -----------------------------------------------------------------------------
# Setup Command
# -----------------------------------------------------------------------------
cmd_setup() {
    banner
    log_info "Detecting system architecture and package manager..."
    PKG_MGR=$(detect_pkg_mgr)
    ARCH=$(uname -m)

    log_info "Platform: $(if $IS_TERMUX; then echo 'Android Termux'; else echo 'Standard Linux'; fi) (${ARCH})"
    log_info "Package Manager: ${PKG_MGR}"

    echo ""
    log_info "Step 1/5: Installing system packages..."
    case "${PKG_MGR}" in
        termux)
            pkg update -y
            pkg install -y python git curl clang make libffi openssl postgresql-client ndk-sysroot
            ;;
        apt)
            if [ "$EUID" -ne 0 ] && command -v sudo >/dev/null 2>&1; then
                SUDO="sudo"
            else
                SUDO=""
            fi
            $SUDO apt-get update -y
            $SUDO apt-get install -y --no-install-recommends \
                python3 python3-pip python3-venv git curl build-essential libpq-dev libffi-dev libssl-dev
            ;;
        dnf)
            sudo dnf install -y python3 python3-pip python3-virtualenv git curl gcc gcc-c++ libpq-devel libffi-devel openssl-devel
            ;;
        pacman)
            sudo pacman -Sy --noconfirm python python-pip git curl base-devel postgresql-libs libffi openssl
            ;;
        apk)
            apk update
            apk add python3 py3-pip py3-virtualenv git curl build-base postgresql-dev libffi-dev openssl-dev
            ;;
        *)
            log_warn "Unknown package manager. Please ensure python3 (>=3.11), git, and build tools are installed."
            ;;
    esac
    log_success "System packages configured."

    echo ""
    log_info "Step 2/5: Configuring Python virtual environment..."
    if [ ! -d "${VENV_DIR}" ]; then
        if $IS_TERMUX; then
            python3 -m venv "${VENV_DIR}" || virtualenv "${VENV_DIR}"
        else
            python3 -m venv "${VENV_DIR}"
        fi
        log_success "Created virtual environment at ${VENV_DIR}"
    else
        log_info "Virtual environment already exists at ${VENV_DIR}"
    fi

    # Activate virtual environment
    # shellcheck source=/dev/null
    source "${VENV_DIR}/bin/activate"

    echo ""
    log_info "Step 3/5: Installing Python backend dependencies..."
    pip install --upgrade pip wheel setuptools

    if [ -f "${SCRIPT_DIR}/backend/requirements.txt" ]; then
        pip install -r "${SCRIPT_DIR}/backend/requirements.txt"
        log_success "Dependencies successfully installed."
    else
        log_error "backend/requirements.txt not found!"
        exit 1
    fi

    echo ""
    log_info "Step 4/5: Generating cryptographic security environment (.env)..."
    ENV_FILE="${SCRIPT_DIR}/backend/.env"
    if [ ! -f "${ENV_FILE}" ]; then
        SECRET_KEY=$(python3 -c "import secrets; print(secrets.token_hex(32))")
        TOTP_KEY=$(python3 -c "import secrets; print(secrets.token_hex(32))")
        PAIRING_KEY=$(python3 -c "import secrets; print(secrets.token_hex(32))")
        VERIFY_SECRET=$(python3 -c "import secrets; print(secrets.token_hex(32))")

        cat > "${ENV_FILE}" << EOF
# SHE Anti-Theft Production / Self-Hosted Configuration
PROJECT_NAME="SHE Anti-Theft"
DEBUG=false
ENFORCE_HTTPS=false
ALLOW_PUBLIC_REGISTRATION=true

# Cryptographic Keys (Auto-Generated)
SECRET_KEY="${SECRET_KEY}"
TOTP_ENCRYPTION_KEY="${TOTP_KEY}"
PAIRING_SECRET_ENCRYPTION_KEY="${PAIRING_KEY}"
VERIFY_SECRET="${VERIFY_SECRET}"

# Outbound Mail (SMTP / Gmail)
SMTP_HOST=localhost
SMTP_PORT=1025
SMTP_USER=
SMTP_PASS=
SMTP_FROM=no-reply@she.local

# Database Configuration (Defaults to local SQLite)
DATABASE_URL="sqlite:///${SCRIPT_DIR}/backend/antitheft_dev.db"
EOF
        log_success "Generated new cryptographic secrets in backend/.env"
    else
        log_info "Found existing backend/.env"
    fi

    echo ""
    log_info "Step 5/5: Preparing database schema & migrations..."
    export PYTHONPATH="${SCRIPT_DIR}/backend"
    if [ -f "${SCRIPT_DIR}/backend/alembic.ini" ]; then
        python3 -m alembic -c "${SCRIPT_DIR}/backend/alembic.ini" upgrade head || true
    fi
    log_success "Database initialization complete."

    echo ""
    echo -e "${GREEN}${BOLD}========================================================================${NC}"
    echo -e "${GREEN}${BOLD}  SHE Anti-Theft Runner Setup Completed Successfully!                   ${NC}"
    echo -e "${GREEN}${BOLD}========================================================================${NC}"
    echo -e "You can now control the backend using:"
    echo -e "  ${CYAN}./runner.sh start${NC}           - Start server in foreground"
    echo -e "  ${CYAN}./runner.sh start --daemon${NC}  - Start server in background"
    echo -e "  ${CYAN}./runner.sh status${NC}          - Check server health"
    echo -e "  ${CYAN}./runner.sh test${NC}            - Run test suite"
    echo -e "  ${CYAN}./runner.sh install-guardian${NC}- Install Guardian app on Android"
    echo ""
}

# -----------------------------------------------------------------------------
# Start Command
# -----------------------------------------------------------------------------
cmd_start() {
    DAEMON=false
    if [ "${1:-}" == "--daemon" ] || [ "${1:-}" == "-d" ]; then
        DAEMON=true
    fi

    if [ -f "${PID_FILE}" ]; then
        OLD_PID=$(cat "${PID_FILE}" 2>/dev/null || true)
        if [ -n "${OLD_PID}" ] && kill -0 "${OLD_PID}" 2>/dev/null; then
            log_warn "SHE Runner is already running with PID ${OLD_PID}."
            exit 0
        else
            rm -f "${PID_FILE}"
        fi
    fi

    if [ ! -d "${VENV_DIR}" ]; then
        log_warn "Virtual environment not detected. Running automated setup first..."
        cmd_setup
    fi

    # shellcheck source=/dev/null
    source "${VENV_DIR}/bin/activate"
    export PYTHONPATH="${SCRIPT_DIR}/backend:${PYTHONPATH:-}"

    if $DAEMON; then
        log_info "Starting SHE Anti-Theft backend daemon on ${HOST}:${PORT}..."
        nohup python3 -m uvicorn app.main:app --host "${HOST}" --port "${PORT}" > "${LOG_FILE}" 2>&1 &
        SERVER_PID=$!
        echo "${SERVER_PID}" > "${PID_FILE}"
        sleep 2

        if kill -0 "${SERVER_PID}" 2>/dev/null; then
            log_success "Backend running in background (PID: ${SERVER_PID})."
            log_info "Logs: ${LOG_FILE}"
            log_info "Health: http://127.0.0.1:${PORT}/health"
        else
            log_error "Server failed to start. Last log lines:"
            tail -n 20 "${LOG_FILE}"
            exit 1
        fi
    else
        log_info "Starting SHE Anti-Theft backend in foreground on http://${HOST}:${PORT}..."
        exec python3 -m uvicorn app.main:app --host "${HOST}" --port "${PORT}"
    fi
}

# -----------------------------------------------------------------------------
# Stop Command
# -----------------------------------------------------------------------------
cmd_stop() {
    if [ -f "${PID_FILE}" ]; then
        PID=$(cat "${PID_FILE}")
        if kill -0 "${PID}" 2>/dev/null; then
            log_info "Stopping SHE Runner (PID: ${PID})..."
            kill "${PID}" || true
            sleep 1
            if kill -0 "${PID}" 2>/dev/null; then
                kill -9 "${PID}" || true
            fi
            log_success "Stopped."
        else
            log_info "Process ${PID} is not running."
        fi
        rm -f "${PID_FILE}"
    else
        log_warn "No running daemon PID file found."
    fi
}

# -----------------------------------------------------------------------------
# Status Command
# -----------------------------------------------------------------------------
cmd_status() {
    if [ -f "${PID_FILE}" ]; then
        PID=$(cat "${PID_FILE}")
        if kill -0 "${PID}" 2>/dev/null; then
            echo -e "${GREEN}● SHE Backend is RUNNING (PID: ${PID})${NC}"
        else
            echo -e "${RED}● SHE Backend is STOPPED (stale PID ${PID})${NC}"
        fi
    else
        echo -e "${YELLOW}● SHE Backend is STOPPED (No PID file)${NC}"
    fi

    # Test HTTP healthcheck
    if command -v curl >/dev/null 2>&1; then
        echo -n "Health Check (http://127.0.0.1:${PORT}/health): "
        HEALTH=$(curl -s -m 3 "http://127.0.0.1:${PORT}/health" 2>/dev/null || true)
        if [ -n "${HEALTH}" ]; then
            echo -e "${GREEN}OK${NC} -> ${HEALTH}"
        else
            echo -e "${RED}UNAVAILABLE${NC}"
        fi
    fi
}

# -----------------------------------------------------------------------------
# Test Command
# -----------------------------------------------------------------------------
cmd_test() {
    if [ ! -d "${VENV_DIR}" ]; then
        cmd_setup
    fi
    # shellcheck source=/dev/null
    source "${VENV_DIR}/bin/activate"
    export PYTHONPATH="${SCRIPT_DIR}/backend:${PYTHONPATH:-}"

    log_info "Running backend test suite with pytest..."
    pytest "${SCRIPT_DIR}/backend/tests/"
}

# -----------------------------------------------------------------------------
# Install Guardian APK on Android (Termux or ADB)
# -----------------------------------------------------------------------------
cmd_install_guardian() {
    log_info "Locating latest signed Guardian APK..."
    APK_CANDIDATE=""
    for p in \
        "${SCRIPT_DIR}/android/app-guardian/build/outputs/apk/direct/release/app-guardian-direct-release.apk" \
        "${SCRIPT_DIR}/backend/static/she-guardian.apk" \
        "${SCRIPT_DIR}/android/app-guardian/build/outputs/apk/direct/debug/app-guardian-direct-debug.apk"; do
        if [ -f "$p" ]; then
            APK_CANDIDATE="$p"
            break
        fi
    done

    if [ -z "$APK_CANDIDATE" ]; then
        log_error "No Guardian APK found. Build with ./runner.sh build-apk or check backend/static/she-guardian.apk."
        exit 1
    fi

    log_info "Selected APK: ${APK_CANDIDATE} ($(du -h "${APK_CANDIDATE}" | cut -f1))"

    if $IS_TERMUX; then
        log_info "Termux environment detected."
        # If root available
        if command -v su >/dev/null 2>&1 && su -c id >/dev/null 2>&1; then
            log_info "Root privileges available. Performing silent package install..."
            su -c "pm install -r -d -g ${APK_CANDIDATE}"
            log_success "SHE Guardian installed via root package manager!"
        else
            TARGET_SDCARD="/sdcard/Download/she-guardian.apk"
            log_info "Copying APK to ${TARGET_SDCARD}..."
            cp "${APK_CANDIDATE}" "${TARGET_SDCARD}"
            if command -v termux-open >/dev/null 2>&1; then
                log_info "Launching Android Package Installer via termux-open..."
                termux-open "${TARGET_SDCARD}"
            else
                log_success "APK copied to Downloads. Open your Android File Manager and tap 'she-guardian.apk' to install."
            fi
        fi
    else
        log_info "Standard Linux detected. Checking for connected ADB devices..."
        ADB_BIN=""
        if [ -x "${SCRIPT_DIR}/.tools/android-sdk/platform-tools/adb" ]; then
            ADB_BIN="${SCRIPT_DIR}/.tools/android-sdk/platform-tools/adb"
        elif command -v adb >/dev/null 2>&1; then
            ADB_BIN="adb"
        fi

        if [ -n "$ADB_BIN" ]; then
            DEVICES=$($ADB_BIN devices | grep -w "device" | awk '{print $1}')
            if [ -n "$DEVICES" ]; then
                for dev in $DEVICES; do
                    log_info "Installing onto device ${dev}..."
                    $ADB_BIN -s "$dev" install -r -d -g "${APK_CANDIDATE}"
                    log_success "Installed successfully on ${dev}!"
                done
            else
                log_warn "No ADB devices connected. Connect phone with USB Debugging enabled, or install via Termux."
            fi
        else
            log_warn "ADB not found. Copy '${APK_CANDIDATE}' to your phone to install."
        fi
    fi
}

# -----------------------------------------------------------------------------
# Main CLI Dispatcher
# -----------------------------------------------------------------------------
ACTION="${1:-help}"
shift || true

case "${ACTION}" in
    setup|install)
        cmd_setup
        ;;
    start|run)
        cmd_start "$@"
        ;;
    stop)
        cmd_stop
        ;;
    restart)
        cmd_stop
        sleep 1
        cmd_start --daemon
        ;;
    status)
        cmd_status
        ;;
    test)
        cmd_test
        ;;
    install-guardian)
        cmd_install_guardian
        ;;
    help|--help|-h)
        banner
        echo "Usage: ./runner.sh <command> [options]"
        echo ""
        echo "Commands:"
        echo "  setup                 Install system dependencies, python venv, and initialize DB"
        echo "  start [--daemon|-d]   Start the SHE Anti-Theft backend (foreground or background)"
        echo "  stop                  Stop running background backend daemon"
        echo "  restart               Restart the backend daemon"
        echo "  status                Show running status and health check"
        echo "  test                  Run backend test suite"
        echo "  install-guardian      Install signed Guardian APK onto Android phone (Termux or ADB)"
        echo "  help                  Display this help message"
        echo ""
        ;;
    *)
        log_error "Unknown command: ${ACTION}"
        echo "Run './runner.sh help' for usage instructions."
        exit 1
        ;;
esac
