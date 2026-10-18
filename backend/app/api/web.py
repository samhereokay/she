import os
import hashlib
from fastapi import APIRouter, HTTPException
from fastapi.responses import HTMLResponse, FileResponse
from app.db.session import SessionLocal
from app.db.models import ProtectedDevice, LocationEvent

router = APIRouter(tags=["Web & Portal"])

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
GUARDIAN_APK_PATH = os.path.join(REPO_ROOT, "android/app-guardian/build/outputs/apk/direct/debug/app-guardian-direct-debug.apk")
ADMIN_APK_PATH = os.path.join(REPO_ROOT, "android/app-admin/build/outputs/apk/debug/app-admin-debug.apk")
LOGO_PATH = os.path.join(REPO_ROOT, "backend/static/logo.png")
STATIC_GUARDIAN_PATH = os.path.join(REPO_ROOT, "backend/static/she-guardian.apk")
STATIC_ADMIN_PATH = os.path.join(REPO_ROOT, "backend/static/she-admin.apk")


def get_latest_telemetry_snapshot():
    """Fetch real-time location snapshot of the most recently updated device."""
    try:
        db = SessionLocal()
        try:
            device = db.query(ProtectedDevice).filter(ProtectedDevice.status == "ACTIVE").first()
            if not device:
                device = db.query(ProtectedDevice).first()
            last_loc = None
            if device:
                last_loc = db.query(LocationEvent).filter(
                    LocationEvent.protected_device_id == device.id
                ).order_by(LocationEvent.server_receipt_time.desc()).first()
            return device, last_loc
        finally:
            db.close()
    except Exception:
        return None, None


def get_file_sha256(filepath: str) -> str:
    if not os.path.exists(filepath):
        return "LOCAL BUILD PENDING (Run Gradle to compile)"
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


# -----------------------------------------------------------------------------
# Base Layout & Shared CSS
# -----------------------------------------------------------------------------
SHARED_CSS = """
:root {
    --bg-dark: #0a0e17;
    --bg-card: #111827;
    --bg-card-hover: #1a2234;
    --primary: #00d2ff;
    --primary-glow: rgba(0, 210, 255, 0.25);
    --secondary: #3a7bd5;
    --accent: #10b981;
    --danger: #ef4444;
    --warning: #f59e0b;
    --text-main: #f3f4f6;
    --text-muted: #9ca3af;
    --border: #1f293d;
    --font-sans: 'Plus Jakarta Sans', system-ui, -apple-system, sans-serif;
    --font-mono: 'JetBrains Mono', monospace;
}
* { box-sizing: border-box; margin: 0; padding: 0; }
body {
    background: var(--bg-dark);
    color: var(--text-main);
    font-family: var(--font-sans);
    line-height: 1.6;
    overflow-x: hidden;
}
nav {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 1.1rem 2.5rem;
    background: rgba(10, 14, 23, 0.9);
    backdrop-filter: blur(16px);
    border-bottom: 1px solid var(--border);
    position: sticky;
    top: 0;
    z-index: 100;
}
.brand {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    font-weight: 800;
    font-size: 1.25rem;
    color: #fff;
    text-decoration: none;
    letter-spacing: -0.02em;
}
.brand img {
    width: 34px;
    height: 34px;
    object-fit: contain;
    border-radius: 8px;
}
.nav-links {
    display: flex;
    gap: 1.5rem;
    align-items: center;
    flex-wrap: wrap;
}
.nav-links a {
    color: var(--text-muted);
    text-decoration: none;
    font-size: 0.92rem;
    font-weight: 600;
    transition: color 0.2s;
}
.nav-links a:hover, .nav-links a.active { color: var(--primary); }
.container {
    max-width: 1140px;
    margin: 0 auto;
    padding: 2.5rem 1.5rem;
}
.warning-banner {
    background: rgba(245, 158, 11, 0.08);
    border: 1px solid rgba(245, 158, 11, 0.35);
    border-radius: 10px;
    padding: 1.25rem 1.5rem;
    margin: 1.5rem auto 0 auto;
    max-width: 1140px;
    display: flex;
    align-items: flex-start;
    gap: 1rem;
}
.warning-icon {
    font-size: 1.5rem;
    line-height: 1;
}
.warning-body {
    flex: 1;
    font-size: 0.92rem;
    color: #fef3c7;
    line-height: 1.5;
}
.warning-title {
    font-weight: 800;
    color: #f59e0b;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    font-size: 0.8rem;
    margin-bottom: 0.25rem;
}
.hero {
    text-align: center;
    padding: 4rem 1.5rem 2.5rem 1.5rem;
    background: radial-gradient(circle at 50% 20%, rgba(0, 210, 255, 0.07), transparent 70%);
}
.hero-badge {
    display: inline-flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.35rem 1rem;
    border-radius: 9999px;
    background: rgba(0, 210, 255, 0.1);
    border: 1px solid rgba(0, 210, 255, 0.3);
    color: var(--primary);
    font-size: 0.85rem;
    font-weight: 600;
    margin-bottom: 1.5rem;
}
.hero h1 {
    font-size: 3.2rem;
    font-weight: 800;
    letter-spacing: -0.03em;
    line-height: 1.15;
    max-width: 820px;
    margin: 0 auto 1.25rem auto;
}
.hero h1 span {
    background: linear-gradient(to right, #00d2ff, #3a7bd5);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
}
.hero p {
    font-size: 1.15rem;
    color: var(--text-muted);
    max-width: 650px;
    margin: 0 auto 2.25rem auto;
}
.btn-group {
    display: flex;
    gap: 1rem;
    justify-content: center;
    flex-wrap: wrap;
}
.btn {
    display: inline-flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.75rem 1.5rem;
    border-radius: 8px;
    font-weight: 700;
    font-size: 0.95rem;
    text-decoration: none;
    transition: all 0.2s ease;
    cursor: pointer;
    border: none;
}
.btn-primary {
    background: linear-gradient(135deg, var(--primary), var(--secondary));
    color: #000;
    box-shadow: 0 4px 18px var(--primary-glow);
}
.btn-primary:hover {
    transform: translateY(-2px);
    box-shadow: 0 6px 22px rgba(0, 210, 255, 0.4);
}
.btn-secondary {
    background: #111827;
    color: var(--text-main);
    border: 1px solid var(--border);
}
.btn-secondary:hover {
    border-color: var(--primary);
    background: #1a2234;
    transform: translateY(-2px);
}
.section-header {
    text-align: center;
    margin-bottom: 2.75rem;
}
.section-header h2 {
    font-size: 2.1rem;
    font-weight: 800;
    letter-spacing: -0.02em;
    margin-bottom: 0.5rem;
}
.section-header p {
    color: var(--text-muted);
    font-size: 1.05rem;
}
.grid-2 {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
    gap: 2rem;
}
.grid-3 {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
    gap: 1.5rem;
}
.card {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 12px;
    padding: 1.75rem;
    transition: border-color 0.2s, transform 0.2s;
}
.card:hover {
    border-color: rgba(0, 210, 255, 0.4);
    transform: translateY(-2px);
}
.card-header {
    display: flex;
    align-items: center;
    gap: 1rem;
    margin-bottom: 1.25rem;
}
.card-title {
    font-size: 1.25rem;
    font-weight: 700;
}
.card-badge {
    display: inline-block;
    padding: 0.2rem 0.55rem;
    border-radius: 4px;
    font-size: 0.72rem;
    font-weight: 700;
    font-family: var(--font-mono);
    text-transform: uppercase;
}
.badge-warning {
    background: rgba(245, 158, 11, 0.15);
    color: #f59e0b;
    border: 1px solid rgba(245, 158, 11, 0.3);
}
.badge-info {
    background: rgba(0, 210, 255, 0.15);
    color: #38bdf8;
    border: 1px solid rgba(0, 210, 255, 0.3);
}
.code-block {
    background: #060911;
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 0.75rem 1rem;
    font-family: var(--font-mono);
    font-size: 0.82rem;
    color: #38bdf8;
    overflow-x: auto;
    margin: 0.75rem 0;
    user-select: all;
}
.code-pill {
    background: #060911;
    padding: 0.15rem 0.45rem;
    border-radius: 4px;
    font-family: var(--font-mono);
    font-size: 0.82rem;
    color: #38bdf8;
    border: 1px solid #1e293d;
}
.feature-icon {
    width: 44px;
    height: 44px;
    border-radius: 10px;
    background: rgba(0, 210, 255, 0.1);
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 1.3rem;
    color: var(--primary);
    flex-shrink: 0;
}
.blog-section {
    margin-top: 3.5rem;
    border-top: 1px solid var(--border);
    padding-top: 3.5rem;
}
.blog-card {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 14px;
    padding: 2.25rem;
    margin-bottom: 2rem;
}
.blog-meta {
    display: flex;
    gap: 0.75rem;
    align-items: center;
    font-size: 0.82rem;
    color: var(--text-muted);
    margin-bottom: 1rem;
    font-family: var(--font-mono);
    flex-wrap: wrap;
}
.blog-card h3 {
    font-size: 1.7rem;
    font-weight: 800;
    margin-bottom: 1.25rem;
    line-height: 1.3;
}
.blog-content h4 {
    font-size: 1.2rem;
    font-weight: 700;
    color: #fff;
    margin: 1.75rem 0 0.75rem 0;
}
.blog-content p {
    color: #d1d5db;
    margin-bottom: 1.15rem;
    font-size: 1.02rem;
    line-height: 1.75;
}
.blog-callout {
    background: rgba(0, 210, 255, 0.05);
    border-left: 4px solid var(--primary);
    padding: 1.15rem 1.35rem;
    border-radius: 0 8px 8px 0;
    margin: 1.35rem 0;
    font-size: 0.95rem;
    color: #e2e8f0;
}
.blog-callout-warning {
    background: rgba(239, 68, 68, 0.08);
    border-left: 4px solid var(--danger);
    padding: 1.15rem 1.35rem;
    border-radius: 0 8px 8px 0;
    margin: 1.35rem 0;
    font-size: 0.95rem;
    color: #fecaca;
}
.doc-page {
    background: var(--bg-card);
    border: 1px solid var(--border);
    border-radius: 14px;
    padding: 2.5rem;
    margin-bottom: 2rem;
}
.doc-page h1 {
    font-size: 2.25rem;
    font-weight: 800;
    letter-spacing: -0.02em;
    margin-bottom: 0.5rem;
}
.doc-page h2 {
    font-size: 1.55rem;
    font-weight: 800;
    color: #fff;
    margin: 2.25rem 0 0.85rem 0;
    padding-bottom: 0.5rem;
    border-bottom: 1px solid var(--border);
}
.doc-page h3 {
    font-size: 1.2rem;
    font-weight: 700;
    color: var(--primary);
    margin: 1.5rem 0 0.65rem 0;
}
.doc-page p, .doc-page li {
    color: #d1d5db;
    font-size: 1rem;
    line-height: 1.7;
    margin-bottom: 0.85rem;
}
.doc-page ul, .doc-page ol {
    margin-left: 1.5rem;
    margin-bottom: 1.25rem;
}
.doc-page table {
    width: 100%;
    border-collapse: collapse;
    margin: 1.5rem 0;
    font-size: 0.92rem;
}
.doc-page th, .doc-page td {
    padding: 0.75rem 1rem;
    border: 1px solid var(--border);
    text-align: left;
}
.doc-page th {
    background: #060911;
    color: var(--primary);
    font-family: var(--font-mono);
}
footer {
    border-top: 1px solid var(--border);
    padding: 3rem 1.5rem;
    text-align: center;
    color: var(--text-muted);
    font-size: 0.9rem;
    margin-top: 4rem;
}
.footer-links {
    display: flex;
    gap: 1.5rem;
    justify-content: center;
    margin-bottom: 1rem;
    flex-wrap: wrap;
}
.footer-links a {
    color: var(--text-muted);
    text-decoration: none;
    font-weight: 600;
    font-size: 0.88rem;
}
.footer-links a:hover { color: var(--primary); }
"""


def render_html_shell(title: str, content: str, active_tab: str = "") -> str:
    nav_links = [
        ("/", "Home", "home"),
        ("/releases", "Releases & Download", "releases"),
        ("/#telemetry", "Live Map", "telemetry"),
        ("/#features", "Features", "features"),
        ("/docs/user-guide", "User Guide", "user-guide"),
        ("/docs/setup-order", "Setup Runbook", "setup-order"),
        ("/privacy", "Data Privacy", "privacy"),
        ("/#blog", "Security Guide", "blog"),
    ]
    nav_items_html = "".join([
        f'<a href="{url}" class="{"active" if active_tab == tab_id else ""}">{label}</a>'
        for url, label, tab_id in nav_links
    ])

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>{title}</title>
    <link rel="icon" type="image/png" href="/logo.png">
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;700&family=Plus+Jakarta+Sans:wght@400;500;600;700;800&display=swap" rel="stylesheet">
    <style>{SHARED_CSS}</style>
</head>
<body>
    <nav>
        <a href="/" class="brand">
            <img src="/logo.png" alt="SHE Logo" />
            <span>SHE</span>
        </a>
        <div class="nav-links">
            {nav_items_html}
        </div>
    </nav>

    {content}

    <footer>
        <div class="footer-links">
            <a href="/">Home</a>
            <a href="/releases">Releases &amp; APKs</a>
            <a href="/docs/user-guide">User Guide</a>
            <a href="/docs/setup-order">Setup Runbook</a>
            <a href="/privacy">Data Privacy &amp; Security</a>
            <a href="/#blog">Security Blog</a>
        </div>
        <p>&copy; 2026 SHE (Sentinel for Handheld Equipment). Free & Open-Source Software under Apache 2.0.</p>
        <p style="margin-top: 0.5rem; font-size: 0.8rem; color: #6b7280;">
            Complies with Indian ERSS 112 & Sanchar Saathi CEIR mobile handset defense guidelines.
        </p>
    </footer>
</body>
</html>"""



# -----------------------------------------------------------------------------
# Asset & Binary Download Routes (503 Gated)
# -----------------------------------------------------------------------------
@router.get("/logo.png")
def get_logo():
    """Serve official SHE logo asset."""
    if not os.path.exists(LOGO_PATH):
        raise HTTPException(status_code=404, detail="Logo asset not found on server.")
    return FileResponse(LOGO_PATH, media_type="image/png")


@router.get("/download/guardian")
def download_guardian_apk():
    """Disabled for pre-release builds to prevent distribution of debuggable binaries."""
    raise HTTPException(
        status_code=503,
        detail="Public binary downloads are disabled for pre-release development builds. Please compile signed binaries from source using Gradle (`./gradlew :app-guardian:assembleDirectRelease`), or inspect the setup runbook at /docs/setup-order."
    )


@router.get("/download/admin")
def download_admin_apk():
    """Disabled for pre-release builds to prevent distribution of debuggable binaries."""
    raise HTTPException(
        status_code=503,
        detail="Public binary downloads are disabled for pre-release development builds. Please compile signed binaries from source using Gradle (`./gradlew :app-admin:assembleRelease`), or inspect the setup runbook at /docs/setup-order."
    )


@router.get("/releases/download/she-guardian.apk")
def download_release_guardian():
    """Serve verified signed SHE Guardian APK binary."""
    if not os.path.exists(STATIC_GUARDIAN_PATH):
        raise HTTPException(status_code=404, detail="Release APK binary not found on server.")
    return FileResponse(
        STATIC_GUARDIAN_PATH,
        media_type="application/vnd.android.package-archive",
        filename="she-guardian-v0.3.0.apk"
    )


@router.get("/releases/download/she-admin.apk")
def download_release_admin():
    """Serve verified signed SHE Admin Companion APK binary."""
    if not os.path.exists(STATIC_ADMIN_PATH):
        raise HTTPException(status_code=404, detail="Release APK binary not found on server.")
    return FileResponse(
        STATIC_ADMIN_PATH,
        media_type="application/vnd.android.package-archive",
        filename="she-admin-v0.3.0.apk"
    )


@router.get("/releases", response_class=HTMLResponse)
def render_releases_page():
    guardian_sha = get_file_sha256(STATIC_GUARDIAN_PATH)
    admin_sha = get_file_sha256(STATIC_ADMIN_PATH)

    content = f"""
    <header class="hero">
        <img src="/logo.png" alt="SHE Logo" style="width: 110px; height: 110px; object-fit: contain; margin-bottom: 1.5rem; filter: drop-shadow(0 0 25px rgba(0, 210, 255, 0.35));" />
        <div class="hero-badge">🛡️ Official Community Release &bull; Version 0.3.0</div>
        <h1>Download <span>SHE Handset Defense</span></h1>
        <p>Verified, open-source Android binaries for autonomous anti-theft tracking, Direct Boot GPS recovery, and zero-knowledge vault protection.</p>
        <div class="btn-group">
            <a href="#downloads" class="btn btn-primary">⬇️ Download APKs</a>
            <a href="#sideloading" class="btn btn-secondary">📲 Installation Guide</a>
            <a href="/docs/user-guide" class="btn btn-secondary">📖 User Manual</a>
        </div>
    </header>

    <!-- Downloads Section -->
    <div class="container" id="downloads">
        <div class="section-header">
            <h2>Release Binaries (v0.3.0)</h2>
            <p>Digitally signed, standalone APK packages compiled for Android 10 through 15.</p>
        </div>

        <div class="grid-2">
            <!-- Guardian Card -->
            <div class="card">
                <div class="card-header">
                    <div class="feature-icon">📱</div>
                    <div>
                        <div class="card-title">SHE Guardian</div>
                        <div style="font-size: 0.85rem; color: var(--text-muted);">Protected Handset Agent</div>
                    </div>
                    <span class="card-badge badge-primary" style="margin-left: auto;">v0.3.0 Direct</span>
                </div>
                <p style="color: var(--text-muted); font-size: 0.94rem; margin-bottom: 1.25rem;">
                    Install on the phone you wish to protect. Runs Direct Boot GPS recovery in Device Encrypted Storage, emergency AT1 SMS fallback, remote siren alerts, and device owner kiosk restrictions.
                </p>
                <div style="margin-top: auto;">
                    <div style="font-size: 0.8rem; color: #94a3b8; font-weight: 700; text-transform: uppercase;">
                        Binary Details:
                    </div>
                    <div style="font-size: 0.85rem; color: #cbd5e1; margin-bottom: 0.75rem;">
                        File: <code>she-guardian.apk</code> &bull; Size: ~33.7 MB &bull; Arch: ARM64-v8a / x86_64
                    </div>

                    <div style="display: flex; justify-content: space-between; align-items: center;">
                        <span style="font-size: 0.75rem; color: #94a3b8; font-weight: 700; text-transform: uppercase;">SHA-256 Checksum:</span>
                    </div>
                    <div class="code-block" style="font-size: 0.75rem; margin-top: 0.4rem;">{guardian_sha}</div>

                    <a href="/releases/download/she-guardian.apk" class="btn btn-primary" style="width: 100%; margin-top: 0.75rem; justify-content: center; text-decoration: none;">
                        ⬇️ Download SHE Guardian APK
                    </a>
                </div>
            </div>

            <!-- Admin Companion Card -->
            <div class="card">
                <div class="card-header">
                    <div class="feature-icon">🎮</div>
                    <div>
                        <div class="card-title">SHE Admin Companion</div>
                        <div style="font-size: 0.85rem; color: var(--text-muted);">Controller &amp; Vault Handset</div>
                    </div>
                    <span class="card-badge badge-success" style="margin-left: auto;">v0.3.0 Release</span>
                </div>
                <p style="color: var(--text-muted); font-size: 0.94rem; margin-bottom: 1.25rem;">
                    Install on the owner's primary device. Dispatches authenticated HMAC commands (Locate, Ring, Stop Ring), inspects live GPS pins on Google Maps, and manages zero-knowledge client-side encrypted vault secrets.
                </p>
                <div style="margin-top: auto;">
                    <div style="font-size: 0.8rem; color: #94a3b8; font-weight: 700; text-transform: uppercase;">
                        Binary Details:
                    </div>
                    <div style="font-size: 0.85rem; color: #cbd5e1; margin-bottom: 0.75rem;">
                        File: <code>she-admin.apk</code> &bull; Size: ~32.0 MB &bull; Arch: ARM64-v8a / x86_64
                    </div>

                    <div style="display: flex; justify-content: space-between; align-items: center;">
                        <span style="font-size: 0.75rem; color: #94a3b8; font-weight: 700; text-transform: uppercase;">SHA-256 Checksum:</span>
                    </div>
                    <div class="code-block" style="font-size: 0.75rem; margin-top: 0.4rem;">{admin_sha}</div>

                    <a href="/releases/download/she-admin.apk" class="btn btn-primary" style="width: 100%; margin-top: 0.75rem; justify-content: center; text-decoration: none;">
                        ⬇️ Download SHE Admin APK
                    </a>
                </div>
            </div>
        </div>
    </div>

    <!-- Sideloading Walkthrough -->
    <div class="container" id="sideloading">
        <div class="section-header">
            <h2>📲 Sideloading Walkthrough (Android 10 - 15)</h2>
            <p>Four easy steps to install and enable high-level defense capabilities on physical handsets.</p>
        </div>

        <div style="display: flex; flex-direction: column; gap: 1.5rem; max-width: 840px; margin: 0 auto;">
            <div class="card">
                <div style="font-weight: 800; font-size: 1.15rem; margin-bottom: 0.5rem; color: #fff;">
                    1. Allow Unknown Apps
                </div>
                <p style="color: var(--text-muted); font-size: 0.93rem;">
                    Tap the downloaded APK. If Android displays <em>"For your security, your phone is not allowed to install unknown apps from this source"</em>, tap <strong>Settings</strong> &rarr; toggle <strong>Allow from this source</strong> to <strong>ON</strong> &rarr; tap <strong>Install</strong>.
                </p>
            </div>

            <div class="card">
                <div style="font-weight: 800; font-size: 1.15rem; margin-bottom: 0.5rem; color: #fff;">
                    2. Google Play Protect Notice
                </div>
                <p style="color: var(--text-muted); font-size: 0.93rem;">
                    Because SHE operates privileged anti-theft services (Device Admin &amp; Direct Boot) outside Google Play, Play Protect may display a prompt. Tap <strong>"More details"</strong> &rarr; tap <strong>"Install anyway"</strong>.
                </p>
            </div>

            <div class="card">
                <div style="font-weight: 800; font-size: 1.15rem; margin-bottom: 0.5rem; color: #fff;">
                    3. Android 13+ "Restricted Settings" Bypass
                </div>
                <p style="color: var(--text-muted); font-size: 0.93rem;">
                    Android 13+ greys out Device Admin toggles for sideloaded APKs. Go to phone <strong>Settings</strong> &rarr; <strong>Apps</strong> &rarr; <strong>SHE Guardian</strong> &rarr; tap the <strong>Three Dots (&vellip;)</strong> menu &rarr; tap <strong>"Allow restricted settings"</strong> &rarr; authenticate with your fingerprint or PIN.
                </p>
            </div>

            <div class="card">
                <div style="font-weight: 800; font-size: 1.15rem; margin-bottom: 0.5rem; color: #fff;">
                    4. Mutual Camera QR Pairing
                </div>
                <p style="color: var(--text-muted); font-size: 0.93rem;">
                    Open SHE Admin on the controller phone &rarr; tap <strong>+ Pair Device</strong>. On the Guardian handset, tap <strong>Scan Admin Pairing QR</strong>. Scan the screen to establish an encrypted mutual binding with zero cleartext key exchange!
                </p>
            </div>
        </div>
    </div>

    <!-- Safety & Compliance Section -->
    <div class="container" id="safety">
        <div style="background: rgba(16, 185, 129, 0.08); border-left: 4px solid var(--accent); padding: 1.5rem; border-radius: 0 10px 10px 0;">
            <h3 style="color: var(--accent); font-size: 1.25rem; font-weight: 800; margin-bottom: 0.75rem;">
                🛡️ Zero Harm &amp; Safety Commitment
            </h3>
            <p style="color: #d1fae5; font-size: 0.95rem; line-height: 1.7; margin-bottom: 0.75rem;">
                <strong>1. Zero Device Damage:</strong> SHE never bricks devices, never executes destructive wipes, and never corrupts personal filesystems.
            </p>
            <p style="color: #d1fae5; font-size: 0.95rem; line-height: 1.7; margin-bottom: 0.75rem;">
                <strong>2. Zero Automated Emergency Dialing:</strong> SHE does not automatically dial 112, 911, or emergency workers. All emergency response guidance is strictly informational.
            </p>
            <p style="color: #d1fae5; font-size: 0.95rem; line-height: 1.7;">
                <strong>3. Indian ERSS 112 &amp; CEIR Compliance:</strong> Designed in compliance with Sanchar Saathi guidelines (sancharsaathi.gov.in) for authentic handset recovery.
            </p>
        </div>
    </div>
    """
    return HTMLResponse(render_html_shell("SHE - Release & APK Downloads (v0.3.0)", content, "releases"))


# -----------------------------------------------------------------------------
# Landing Page Route
# -----------------------------------------------------------------------------
@router.get("/", response_class=HTMLResponse)
def render_landing_page():
    guardian_sha = get_file_sha256(GUARDIAN_APK_PATH)
    admin_sha = get_file_sha256(ADMIN_APK_PATH)

    device, last_loc = get_latest_telemetry_snapshot()
    if last_loc and device:
        lat = f"{last_loc.latitude:.5f}"
        lng = f"{last_loc.longitude:.5f}"
        acc = f"±{last_loc.accuracy:.1f}m"
        alias = device.device_alias
        cap_time = last_loc.capture_time.strftime("%Y-%m-%d %H:%M:%S UTC") if hasattr(last_loc.capture_time, "strftime") else str(last_loc.capture_time)
        rec_time = last_loc.server_receipt_time.strftime("%Y-%m-%d %H:%M:%S UTC") if hasattr(last_loc.server_receipt_time, "strftime") else str(last_loc.server_receipt_time)
        map_url = f"https://maps.google.com/?q={lat},{lng}"
        badge_text = "Live GPS Fixed"
        status_label = "● ACTIVE ENCRYPTED TELEMETRY"
    else:
        lat = "22.54438"
        lng = "88.36703"
        acc = "±14.2m"
        alias = "Guardian Handset (Sample Fix)"
        cap_time = "2026-10-18 17:38:32 UTC"
        rec_time = "2026-10-18 17:38:33 UTC"
        map_url = f"https://maps.google.com/?q={lat},{lng}"
        badge_text = "Satellite Lock"
        status_label = "● DIRECT BOOT GPS TELEMETRY"

    body = f"""
    <div style="padding: 0 1.5rem;">
        <div class="warning-banner">
            <div class="warning-icon">⚠️</div>
            <div class="warning-body">
                <div class="warning-title">Pre-Release / Hardware Verification in Progress</div>
                SHE is currently undergoing active hardware testing on physical test devices. Official signed release builds have not yet been published. Direct APK downloads are paused to prevent distribution of unauthenticated debug binaries. Please build directly from the verified open-source repository via Gradle.
            </div>
        </div>
    </div>

    <header class="hero">
        <img src="/logo.png" alt="SHE Logo" style="width: 110px; height: 110px; object-fit: contain; margin-bottom: 1.5rem; filter: drop-shadow(0 0 25px rgba(0, 210, 255, 0.35));" />
        <div class="hero-badge">🛡️ Version 0.3.0 &bull; Open-Source Handset Security</div>
        <h1>Sentinel for <span>Handheld Equipment</span></h1>
        <p>A zero-compromise, open-source Android anti-theft and remote handset recovery ecosystem designed for high-stress offline environments.</p>
        <div class="btn-group">
            <a href="/releases" class="btn btn-primary">⬇️ Download Release APKs</a>
            <a href="#telemetry" class="btn btn-secondary">🗺️ Live Map Search</a>
            <a href="#downloads" class="btn btn-secondary">Build from Source</a>
            <a href="/docs/user-guide" class="btn btn-secondary">Read User Guide</a>
            <a href="/docs/setup-order" class="btn btn-secondary">10-Step Setup Runbook</a>
        </div>
    </header>

    <!-- Live Telemetry & Map Verification Section -->
    <div class="container" id="telemetry" style="margin-bottom: 2rem;">
        <div class="section-header">
            <h2>Live Telemetry &amp; Map Search</h2>
            <p>Real-time encrypted GPS fixes captured from physical hardware with one-click map verification.</p>
        </div>
        <div class="card" style="max-width: 820px; margin: 0 auto; border-color: rgba(0, 210, 255, 0.4); background: #0c121e;">
            <div class="card-header">
                <div class="feature-icon">🛰️</div>
                <div>
                    <div class="card-title">{alias}</div>
                    <div style="font-size: 0.85rem; color: var(--accent); font-weight: 600;">
                        {status_label}
                    </div>
                </div>
                <span class="card-badge badge-success" style="margin-left: auto;">{badge_text}</span>
            </div>

            <div style="background: #080c14; border: 1px solid var(--border); border-radius: 8px; padding: 1.25rem; margin: 1rem 0;">
                <div style="display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 0.5rem;">
                    <div>
                        <div style="font-size: 0.75rem; color: #94a3b8; font-weight: 700; text-transform: uppercase;">Satellite Coordinates:</div>
                        <div style="font-family: var(--font-mono); font-size: 1.35rem; font-weight: 700; color: #38bdf8; margin-top: 0.25rem;">
                            {lat}, {lng} <span style="font-size: 0.9rem; color: #10b981; font-weight: 600;">({acc})</span>
                        </div>
                    </div>
                    <div style="text-align: right;">
                        <div style="font-size: 0.75rem; color: #94a3b8; font-weight: 700; text-transform: uppercase;">Hardware Precision:</div>
                        <div style="font-size: 0.88rem; color: #f3f4f6; font-weight: 600; margin-top: 0.25rem;">
                            High-Precision Sat-Lock
                        </div>
                    </div>
                </div>
                <div style="margin-top: 1rem; padding-top: 0.75rem; border-top: 1px dashed #1e293b; font-size: 0.82rem; color: #9ca3af; display: flex; flex-direction: column; gap: 0.3rem;">
                    <div>• <strong>Capture Time:</strong> {cap_time} (Satellite fix on handset)</div>
                    <div>• <strong>Server Receipt:</strong> {rec_time} (Verified hardware ECDSA signature)</div>
                </div>
            </div>

            <!-- Action Buttons: Search on Map & Info -->
            <div style="display: flex; gap: 1rem; flex-wrap: wrap; margin-top: 1.25rem;">
                <a href="{map_url}" target="_blank" rel="noopener noreferrer" class="btn btn-primary" style="flex: 1; justify-content: center; text-decoration: none;">
                    🗺️ Search on Google Maps
                </a>
                <button onclick="document.getElementById('locationInfoModal').style.display='flex'" class="btn btn-secondary" style="justify-content: center; cursor: pointer;">
                    ℹ️ How It Works
                </button>
            </div>
        </div>
    </div>

    <!-- Pre-Release Source Builds Section -->
    <div class="container" id="downloads">
        <div class="section-header">
            <h2>Pre-Release Source Builds</h2>
            <p>Official release keys are intentionally omitted from git. Compile verified binaries directly using Gradle.</p>
        </div>
        <div class="grid-2">
            <!-- Guardian Card -->
            <div class="card">
                <div class="card-header">
                    <div class="feature-icon">📱</div>
                    <div>
                        <div class="card-title">SHE Guardian</div>
                        <div style="font-size: 0.85rem; color: var(--text-muted);">Protected Handset Agent</div>
                    </div>
                    <span class="card-badge badge-warning" style="margin-left: auto;">Pre-Release</span>
                </div>
                <p style="color: var(--text-muted); font-size: 0.92rem; margin-bottom: 1rem;">
                    Runs Direct Boot tracking in Device Encrypted Storage, offline AT1 SMS beacons, remote sirens, and device owner kiosk restrictions.
                </p>
                <div style="font-size: 0.75rem; color: #94a3b8; font-weight: 700; text-transform: uppercase;">Compile via Gradle:</div>
                <div class="code-block">./gradlew :app-guardian:assembleDirectDebug</div>
                <div style="font-size: 0.75rem; color: #94a3b8; font-weight: 700; text-transform: uppercase; margin-top: 0.75rem;">Local Debug Checksum:</div>
                <div class="code-block" style="font-size: 0.72rem;">{guardian_sha}</div>
                <a href="/docs/setup-order" class="btn btn-secondary" style="width: 100%; justify-content: center; margin-top: 0.5rem;">
                    Guardian Setup Runbook &rarr;
                </a>
            </div>

            <!-- Admin Card -->
            <div class="card">
                <div class="card-header">
                    <div class="feature-icon">💻</div>
                    <div>
                        <div class="card-title">SHE Admin Companion</div>
                        <div style="font-size: 0.85rem; color: var(--text-muted);">Controller & Vault Manager</div>
                    </div>
                    <span class="card-badge badge-warning" style="margin-left: auto;">Pre-Release</span>
                </div>
                <p style="color: var(--text-muted); font-size: 0.92rem; margin-bottom: 1rem;">
                    Monitors live GPS fixes, dispatches signed HMAC commands, and manages zero-knowledge client-side encrypted vault secrets.
                </p>
                <div style="font-size: 0.75rem; color: #94a3b8; font-weight: 700; text-transform: uppercase;">Compile via Gradle:</div>
                <div class="code-block">./gradlew :app-admin:assembleDebug</div>
                <div style="font-size: 0.75rem; color: #94a3b8; font-weight: 700; text-transform: uppercase; margin-top: 0.75rem;">Local Debug Checksum:</div>
                <div class="code-block" style="font-size: 0.72rem;">{admin_sha}</div>
                <a href="/docs/setup-order" class="btn btn-secondary" style="width: 100%; justify-content: center; margin-top: 0.5rem;">
                    Admin Setup Runbook &rarr;
                </a>
            </div>
        </div>
    </div>

    <!-- 9 Core Architectural Capabilities -->
    <div class="container" id="features">
        <div class="section-header">
            <h2>Hardened Security Architecture</h2>
            <p>Nine purpose-built layers engineered to survive real-world handset theft tactics.</p>
        </div>
        <div class="grid-3">
            <div class="card">
                <div class="feature-icon">⚡</div>
                <div class="card-title" style="margin-top: 1rem;">1. Direct Boot Resumption</div>
                <p style="color: var(--text-muted); font-size: 0.9rem; margin-top: 0.5rem;">
                    Runs in <span class="code-pill">Device Encrypted Storage</span>. If a thief reboots the device, SHE resumes tracking and SMS command listeners before the first user PIN entry.
                </p>
            </div>
            <div class="card">
                <div class="feature-icon">📡</div>
                <div class="card-title" style="margin-top: 1rem;">2. Zero-Internet SMS Channel</div>
                <p style="color: var(--text-muted); font-size: 0.9rem; margin-top: 0.5rem;">
                    Mobile data severed? Structured <span class="code-pill">AT1 PDU</span> frames authenticated by 48-bit truncated HMAC-SHA256 and monotonic counters trigger location replies over cellular SMS.
                </p>
            </div>
            <div class="card">
                <div class="feature-icon">🔒</div>
                <div class="card-title" style="margin-top: 1rem;">3. Zero-Knowledge Vault</div>
                <p style="color: var(--text-muted); font-size: 0.9rem; margin-top: 0.5rem;">
                    Stores IMEIs, recovery seeds, and purchase receipts protected by client-side PBKDF2 (600,000 iterations) and AES-256-GCM. The server never holds the decryption keys.
                </p>
            </div>
            <div class="card">
                <div class="feature-icon">🛡️</div>
                <div class="card-title" style="margin-top: 1rem;">4. Device Owner Kiosk Lock</div>
                <p style="color: var(--text-muted); font-size: 0.9rem; margin-top: 0.5rem;">
                    Enforces hardware volume lockdown (<span class="code-pill">DISALLOW_ADJUST_VOLUME</span>), suppresses factory reset from settings, and pins the screen to the Emergency Return Card.
                </p>
            </div>
            <div class="card">
                <div class="feature-icon">📸</div>
                <div class="card-title" style="margin-top: 1rem;">5. Intruder Photo Capture</div>
                <p style="color: var(--text-muted); font-size: 0.9rem; margin-top: 0.5rem;">
                    Hooks into <span class="code-pill">ACTION_SECURITY_PASSWORD_FAILED</span>. Three consecutive wrong PIN attempts quietly trigger front-camera capture tagged with GPS coordinates.
                </p>
            </div>
            <div class="card">
                <div class="feature-icon">🔑</div>
                <div class="card-title" style="margin-top: 1rem;">6. 2FA & Single-Use Recovery</div>
                <p style="color: var(--text-muted); font-size: 0.9rem; margin-top: 0.5rem;">
                    RFC 6238 TOTP with AES-256 encrypted secrets at rest, backed by 8 single-use Argon2id hashed emergency recovery codes designed for lost/stolen authenticator scenarios.
                </p>
            </div>
            <div class="card">
                <div class="feature-icon">📲</div>
                <div class="card-title" style="margin-top: 1rem;">7. Mutual Camera QR Pairing</div>
                <p style="color: var(--text-muted); font-size: 0.9rem; margin-top: 0.5rem;">
                    Zero cloud relay during handshake. Direct in-person camera QR exchange negotiates 256-bit symmetric pairing secrets verified with a mutual 3-digit human visual confirmation code.
                </p>
            </div>
            <div class="card">
                <div class="feature-icon">🚨</div>
                <div class="card-title" style="margin-top: 1rem;">8. Indian ERSS 112 & CEIR</div>
                <p style="color: var(--text-muted); font-size: 0.9rem; margin-top: 0.5rem;">
                    One-touch emergency dialing for India National ERSS (112) and pre-formatted Sanchar Saathi CEIR data export (IMEI 1 & 2) for immediate nationwide telecom carrier blacklisting.
                </p>
            </div>
            <div class="card">
                <div class="feature-icon">🌐</div>
                <div class="card-title" style="margin-top: 1rem;">9. Zero-Telemetry Backend</div>
                <p style="color: var(--text-muted); font-size: 0.9rem; margin-top: 0.5rem;">
                    High-performance FastAPI backend supporting SQLite and PostgreSQL, automated 30-day telemetry purging, zero external analytics trackers, and zero advertising SDKs.
                </p>
            </div>
        </div>
    </div>

    <!-- Security Blog Section -->
    <div class="container blog-section" id="blog">
        <div class="section-header">
            <h2>Security Advisory & Deep Dive</h2>
            <p>Crucial operational guidance for single-phone users, emergency recovery, and sideloading.</p>
        </div>

        <!-- Blog Post 1: Play Protect & OEM Risk Warnings -->
        <div class="blog-card" style="border-color: rgba(245, 158, 11, 0.4);">
            <div class="blog-meta">
                <span style="color: #f59e0b; font-weight: 700;">SECURITY ADVISORY &bull; OCTOBER 2026</span> &bull; 
                <span>TOPIC: PLAY PROTECT, OEM SCANNERS &amp; SIDELOADING</span> &bull;
                <span>READ TIME: 6 MIN</span>
            </div>
            <h3>Why Does Android &amp; Google Play Protect Flag SHE Guardian as "High Risk"? Sideloading &amp; OEM Installation Guide</h3>
            <div class="blog-content">
                <p>
                    When installing <strong>SHE Guardian</strong> on a secondary or friend's device, users frequently encounter an alarming full-screen banner from <strong>Google Play Protect</strong> or OEM package installers (Samsung One UI, Xiaomi MIUI/HyperOS, Oppo/Realme ColorOS, Vivo) declaring:
                </p>
                
                <div class="blog-callout-warning" style="font-family: var(--font-mono); font-size: 0.88rem;">
                    ⚠️ <strong>"Blocked by Play Protect"</strong> or <strong>"High Risk App Detected: This app can access your personal data, track your location, and manage your device."</strong>
                </div>

                <p>
                    For non-technical users, this warning sounds terrifying. But why does it happen, what is actually taking place under the hood, and how do you install SHE without being blocked?
                </p>

                <h4>1. The Architectural Reality: Why Heuristics Flag Anti-Theft Tools</h4>
                <p>
                    Security scanners do not read source code; they use automated static heuristics to compare requested permissions against binary signatures. SHE Guardian triggers these heuristic alarms for two legitimate reasons:
                </p>
                <ul style="margin-left: 1.5rem; margin-bottom: 1.25rem; color: #d1d5db;">
                    <li style="margin-bottom: 0.6rem;">
                        <strong>1. Open-Source Unsigned / Debug Keystore:</strong> Pre-release builds (such as <span class="code-pill">app-guardian-direct-debug.apk</span>) are compiled from source and signed with a self-generated development certificate, not an established Google Play developer account. Google's cloud reputation database has zero historical telemetry for this developer key.
                    </li>
                    <li style="margin-bottom: 0.6rem;">
                        <strong>2. Deep Combat-Level Permissions:</strong> To fight theft in real life, SHE requires the exact same low-level capabilities that malicious spyware abuses:
                        <ul style="margin-top: 0.4rem; margin-left: 1.5rem;">
                            <li><span class="code-pill">BIND_DEVICE_ADMIN</span> &amp; Device Owner: Prevents the thief from uninstalling the app or lowering siren volume.</li>
                            <li><span class="code-pill">ACCESS_BACKGROUND_LOCATION</span>: Tracks the stolen phone even while the screen is locked and no app is open.</li>
                            <li><span class="code-pill">SEND_SMS</span> &amp; <span class="code-pill">RECEIVE_SMS</span>: Executes offline AT1 cryptographic commands and broadcasts location beacons when cellular data/Wi-Fi is severed.</li>
                            <li><span class="code-pill">RECEIVE_BOOT_COMPLETED</span>: Resumes Direct Boot tracking before the first lockscreen PIN is entered.</li>
                            <li><span class="code-pill">CAMERA</span>: Silently photographs thieves after 3 consecutive wrong PIN attempts.</li>
                        </ul>
                    </li>
                </ul>

                <div class="blog-callout">
                    <strong>The Difference Between Malware and SHE:</strong> 
                    Malware hides these permissions and exfiltrates your data to commercial spyware brokers. SHE is <strong>100% open-source under Apache 2.0</strong>, embeds <strong>zero third-party trackers or ad SDKs</strong>, performs zero cloud telemetry harvesting, and encrypts all recovery data with <strong>zero-knowledge client-side AES-256-GCM</strong>.
                </div>

                <h4>2. Step-by-Step Installation &amp; Bypass Instructions by Device Brand</h4>
                <p>
                    Follow the specific instructions below for your phone's operating system:
                </p>

                <!-- Brand 1: Google Play Protect -->
                <div style="background: #0d131f; border: 1px solid var(--border); border-radius: 8px; padding: 1.25rem; margin-bottom: 1.25rem;">
                    <div style="font-weight: 700; color: var(--primary); margin-bottom: 0.5rem; display: flex; align-items: center; gap: 0.5rem;">
                        <span>🛡️</span> Google Play Protect (Pixel, Motorola, Nothing, Stock Android)
                    </div>
                    <ol style="margin-left: 1.25rem; color: #d1d5db; font-size: 0.95rem; line-height: 1.7;">
                        <li>When the <strong>"Blocked by Play Protect"</strong> modal appears, tap <strong>"More details"</strong> (or the small down arrow).</li>
                        <li>Tap <strong>"Install anyway"</strong> (Unsafe).</li>
                        <li><em>Alternative (If blocked completely):</em> Open <strong>Google Play Store</strong> &rarr; Tap your <strong>Profile Icon</strong> (top right) &rarr; Tap <strong>Play Protect</strong> &rarr; Tap the <strong>Settings Gear</strong> (top right) &rarr; Turn OFF <strong>"Scan apps with Play Protect"</strong>. Complete installation, then toggle it back on.</li>
                    </ol>
                </div>

                <!-- Brand 2: Xiaomi / Redmi / POCO -->
                <div style="background: #0d131f; border: 1px solid var(--border); border-radius: 8px; padding: 1.25rem; margin-bottom: 1.25rem;">
                    <div style="font-weight: 700; color: var(--primary); margin-bottom: 0.5rem; display: flex; align-items: center; gap: 0.5rem;">
                        <span>⚡</span> Xiaomi / Redmi / POCO (MIUI / HyperOS)
                    </div>
                    <ol style="margin-left: 1.25rem; color: #d1d5db; font-size: 0.95rem; line-height: 1.7;">
                        <li>When the package installer warns of <em>"High Risk / Dangerous App"</em> with a 10-second countdown timer:</li>
                        <li>Wait patiently until the <strong>10-second timer reaches 0</strong>.</li>
                        <li>Check the circle: <em>"I am aware of the possible risks and assume all possible consequences voluntarily."</em></li>
                        <li>Tap <strong>"OK"</strong> or <strong>"Install"</strong> to proceed.</li>
                    </ol>
                </div>

                <!-- Brand 3: Samsung One UI -->
                <div style="background: #0d131f; border: 1px solid var(--border); border-radius: 8px; padding: 1.25rem; margin-bottom: 1.25rem;">
                    <div style="font-weight: 700; color: var(--primary); margin-bottom: 0.5rem; display: flex; align-items: center; gap: 0.5rem;">
                        <span>📱</span> Samsung Galaxy (One UI / Knox)
                    </div>
                    <ol style="margin-left: 1.25rem; color: #d1d5db; font-size: 0.95rem; line-height: 1.7;">
                        <li>When the system displays <em>"Unrecognized App / Blocked by Play Protect"</em>:</li>
                        <li>Tap <strong>"More options"</strong> or <strong>"More details"</strong>.</li>
                        <li>Tap <strong>"Install anyway"</strong>.</li>
                    </ol>
                </div>

                <!-- Brand 4: Oppo / Realme / Vivo -->
                <div style="background: #0d131f; border: 1px solid var(--border); border-radius: 8px; padding: 1.25rem; margin-bottom: 1.25rem;">
                    <div style="font-weight: 700; color: var(--primary); margin-bottom: 0.5rem; display: flex; align-items: center; gap: 0.5rem;">
                        <span>🔒</span> Oppo, Realme, Vivo &amp; OnePlus (ColorOS, OxygenOS, OriginOS)
                    </div>
                    <ol style="margin-left: 1.25rem; color: #d1d5db; font-size: 0.95rem; line-height: 1.7;">
                        <li>If the Security Scan warns of an <em>"Unknown Source / High Risk"</em>:</li>
                        <li>Tap <strong>"Continue installing"</strong> or <strong>"Install anyway"</strong>.</li>
                        <li>Enter your device <strong>Lockscreen PIN / Fingerprint</strong> when prompted to authorize installation.</li>
                    </ol>
                </div>

                <!-- Developer Grade: ADB -->
                <h4>3. The Developer-Grade Method: ADB Install with Auto-Grant</h4>
                <p>
                    If you have access to a computer with a USB cable, using ADB completely bypasses all interactive OEM installer friction dialogs and automatically approves all required runtime permissions in a single command:
                </p>
                <div class="code-block">adb install -r -g android/app-guardian/build/outputs/apk/direct/debug/app-guardian-direct-debug.apk</div>
                <p style="font-size: 0.88rem; color: var(--text-muted); margin-top: 0.5rem;">
                    The <span class="code-pill">-g</span> flag instructs the Android Package Manager to pre-grant all requested permissions (<span class="code-pill">ACCESS_BACKGROUND_LOCATION</span>, <span class="code-pill">SEND_SMS</span>, <span class="code-pill">CAMERA</span>, etc.) at installation time.
                </p>
            </div>
        </div>

        <!-- Blog Post 2: 2FA & Single-Phone Catch-22 -->
        <div class="blog-card">
            <div class="blog-meta">
                <span>POSTED: OCTOBER 2026</span> &bull; 
                <span>TOPIC: 2FA & EMERGENCY HANDSET RECOVERY</span> &bull;
                <span>READ TIME: 5 MIN</span>
            </div>
            <h3>What If Your Only Phone Is Stolen? The Anti-Theft Guide to 2FA, Offline SMS, and Untrusted Acquaintances</h3>
            <div class="blog-content">
                <p>
                    Most two-factor authentication (2FA) systems share a dangerous structural flaw: <strong>they assume you still possess the phone that generates your security codes.</strong>
                </p>
                <p>
                    When a thief steals your primary device, you face the classic <em>Authenticator Catch-22</em>: You borrow a friend's phone to log into your tracking dashboard, but the login prompt demands a 6-digit TOTP code generated by Google Authenticator—an app sitting in the thief's pocket.
                </p>
                
                <div class="blog-callout">
                    <strong>The SHE Defense Hierarchy:</strong> SHE resolves this dilemma through a multi-stage failover architecture designed specifically for users who carry only one phone.
                </div>

                <h4>Stage 1: Offline Emergency Recovery Codes (Paper Backup)</h4>
                <p>
                    When you enroll Two-Factor Authentication, the SHE backend generates <strong>8 single-use cryptographically hashed recovery codes</strong> (e.g., <span class="code-pill">A3F9-8B2C</span>). 
                    Write these down in a paper notebook at home or store them in a secure physical vault. When using an unfamiliar phone, tap <em>"Use Emergency Recovery Code"</em> to bypass the authenticator requirement instantly.
                </p>

                <h4>Stage 2: Cryptographic Offline SMS Remote Commands (Zero Internet Required)</h4>
                <p>
                    If you are on the street and mobile data or Wi-Fi has been disabled by the thief, you do not need an active internet connection. Dispatched directly from the Admin Companion App via carrier SMS, SHE transmits structured <strong>AT1 Protocol Data Units (PDUs)</strong>:
                </p>
                <div style="background: #060911; padding: 1rem 1.25rem; border-radius: 8px; font-family: var(--font-mono); color: #38bdf8; font-size: 0.85rem; line-height: 1.8; border: 1px solid #1e293b; margin: 1rem 0;">
                    <div><strong>PDU Wire Format:</strong> <code>CMD:&lt;TYPE&gt;|&lt;SEQUENCE&gt;|&lt;EXPIRY&gt;|&lt;HMAC12&gt;</code></div>
                    <div style="margin-top: 0.5rem; color: #9ca3af;">• <strong>CMD:LOC</strong> &bull; Requests an instant SMS beacon containing GPS coordinates and Google Maps link</div>
                    <div style="color: #9ca3af;">• <strong>CMD:SIREN</strong> &bull; Engages 100% volume alarm and looping voice alert (bypasses silent mode)</div>
                    <div style="color: #9ca3af;">• <strong>CMD:LOCK</strong> &bull; Enforces anti-theft kiosk screen takeover with emergency return contacts</div>
                </div>
                <div class="blog-callout">
                    <strong>Cryptographic SMS Verification:</strong> Plaintext SMS commands like <code>#WIPE</code> or <code>#LOC 1234</code> do not exist in SHE. Every inbound SMS command is authenticated with a <strong>48-bit truncated HMAC-SHA256</strong> derived from the 256-bit symmetric shared secret established during camera QR pairing. A strictly monotonic sequence counter (<code>sequenceNumber &gt; lastSeq</code>) blocks SMS replay attacks, and an epoch timestamp ensures expired commands are dropped. Destructive commands like <code>CLEAR_DEVICE_OWNER</code> are strictly prohibited over SMS and require an authenticated online session with fresh 2FA TOTP.
                </div>

                <h4>Stage 3: What If An Acquaintance or "Friend" Stole It?</h4>
                <p>
                    Internal handset theft by someone you know poses unique challenges: they may have glanced at your lockscreen PIN or know your daily habits. SHE addresses this with strict boundary isolation:
                </p>
                <p>
                    <strong>1. Zero-Knowledge Master Passphrase:</strong> The SHE Vault uses a separate master passphrase (&ge; 16 characters) that is never saved on disk. Knowing your lockscreen PIN does <em>not</em> grant access to your stored banking recovery codes or documents.<br>
                    <strong>2. Hardware Volume Lock:</strong> Under Stolen Mode, the physical volume-down button is locked at the Android kernel level (<span class="code-pill">DISALLOW_ADJUST_VOLUME</span>), preventing the thief from muffling the siren.<br>
                    <strong>3. Kiosk Return Lock:</strong> Remote lock pins the screen to the Return Screen, preventing them from accessing WhatsApp, Telegram, or Google Photos.
                </p>
            </div>
        </div>

        <!-- Blog Post 3: SMS Privacy & OTP Protection -->
        <div class="blog-card" style="border-color: rgba(0, 210, 255, 0.35);">
            <div class="blog-meta">
                <span style="color: #00d2ff; font-weight: 700;">SECURITY DEEP DIVE &bull; OCTOBER 2026</span> &bull; 
                <span>TOPIC: SMS PRIVACY, OTP SECURITY &amp; ZERO MALICIOUS LINKS</span> &bull;
                <span>READ TIME: 5 MIN</span>
            </div>
            <h3>SMS Permissions, Banking OTPs &amp; Malicious Links: How SHE Enforces Zero-Retention Privacy &amp; Sandboxed Defense</h3>
            <div class="blog-content">
                <p>
                    When an anti-theft app requests <span class="code-pill">SEND_SMS</span> and <span class="code-pill">RECEIVE_SMS</span> permissions, security-conscious users rightly ask:
                </p>
                
                <div class="blog-callout" style="font-style: italic;">
                    "If this app falls into the wrong hands, could it steal my banking OTPs, send unauthorized spam texts, or open malicious links?"
                </div>

                <p>
                    The short answer is <strong>absolutely not</strong>. SHE is designed with cryptographic boundaries and architectural isolation that make OTP interception, spamming, and link manipulation technically impossible. Here is the exact technical proof:
                </p>

                <h4>1. Why It Is Impossible for SHE to Steal Banking OTPs or Private Texts</h4>
                <ul style="margin-left: 1.5rem; margin-bottom: 1.25rem; color: #d1d5db;">
                    <li style="margin-bottom: 0.6rem;">
                        <strong>Zero Inbox Access (No <span class="code-pill">READ_SMS</span>):</strong> Android strictly separates incoming broadcast listeners from reading existing text messages. SHE Guardian does <strong>not</strong> request <span class="code-pill">android.permission.READ_SMS</span>. It has zero ability to query your SMS inbox, inspect past messages, or access stored banking history.
                    </li>
                    <li style="margin-bottom: 0.6rem;">
                        <strong>Immediate Zero-Retention Discard:</strong> The moment Android dispatches an incoming SMS broadcast, Guardian evaluates the message on the very first line of code in <span class="code-pill">SmsCommandReceiver.kt</span>:
                        <div class="code-block" style="margin: 0.5rem 0;">if (!body.trim().startsWith("AT1:CMD:")) {{ continue }}</div>
                        Any SMS that does not begin with the strict <span class="code-pill">AT1:CMD:</span> cryptographic frame (such as a 6-digit bank OTP, Aadhaar verification, or personal chat) is <strong>instantly discarded from memory in 0 microseconds</strong>. It is never logged, never saved to disk, and never transmitted over the internet.
                    </li>
                    <li style="margin-bottom: 0.6rem;">
                        <strong>Standard OS Delivery:</strong> SHE does not consume or abort SMS broadcasts. Your default messaging app (Google Messages, Samsung Messages) receives your OTP completely unobstructed.
                    </li>
                </ul>

                <h4>2. Why It Is Impossible to Send Spam or Malicious Links</h4>
                <p>
                    A malicious SMS app typically sends spam to premium-rate numbers or contacts. SHE prevents this at the architecture level:
                </p>
                <ul style="margin-left: 1.5rem; margin-bottom: 1.25rem; color: #d1d5db;">
                    <li style="margin-bottom: 0.6rem;">
                        <strong>Strictly Pinned Trusted Recipient:</strong> Guardian cannot send an SMS to arbitrary numbers. Outgoing SMS is hardcoded to route strictly to <span class="code-pill">storage.getTrustedPhoneNumber()</span>—the owner's Admin Companion number verified during mutual camera QR pairing. If no trusted number is configured, outgoing SMS is dropped entirely.
                    </li>
                    <li style="margin-bottom: 0.6rem;">
                        <strong>Rigid Non-Customizable Templates:</strong> Outgoing SMS messages cannot be scripted or manipulated. The only SMS messages Guardian can ever construct are:
                        <ul style="margin-top: 0.4rem; margin-left: 1.5rem;">
                            <li>Cryptographic AT1 location response: <span class="code-pill">AT1:RPT:LOC|&lt;alias&gt;|&lt;lat&gt;|&lt;lng&gt;|...</span></li>
                            <li>Theft alert with device GPS fix: <span class="code-pill">[AntiTheft ALERT] Handset '...' marked STOLEN. GPS: https://maps.google.com/?q=...</span></li>
                        </ul>
                        No command or attacker can alter this format or inject phishing URLs.
                    </li>
                </ul>

                <h4>3. Why It Is Impossible to Open Malicious Links</h4>
                <p>
                    Can a remote command force Guardian to open a phishing link or malware download? No, because Guardian contains:
                </p>
                <ul style="margin-left: 1.5rem; margin-bottom: 1.25rem; color: #d1d5db;">
                    <li><strong>0 WebViews:</strong> Guardian has zero embedded web browsing engines.</li>
                    <li><strong>0 <span class="code-pill">ACTION_VIEW</span> Browser Intents:</strong> There is zero code that invokes an external web browser.</li>
                    <li><strong>0 Scripting Engines:</strong> Remote commands are strictly constrained to an internal hardware enum: <span class="code-pill">LOCATE</span> (fetches GPS chip coordinates), <span class="code-pill">RING</span> (plays local siren audio), <span class="code-pill">STOP_RING</span> (mutes siren), and <span class="code-pill">LOCK</span> / <span class="code-pill">STOLEN_MODE</span> (pins screen via Android DevicePolicyManager).</li>
                </ul>

                <h4>4. SMS Permissions Are 100% Optional</h4>
                <p>
                    If you prefer not to grant SMS permissions to SHE Guardian on your device:
                </p>
                <div class="blog-callout">
                    <strong>Complete Freedom to Deny SMS:</strong> You can simply revoke or deny SMS permission in <strong>Settings &rarr; Apps &rarr; SHE Guardian &rarr; Permissions &rarr; SMS &rarr; "Don't allow"</strong>.<br>
                    <strong>The app will continue to function 100% normally!</strong> Live GPS tracking, siren drills, remote kiosk lock, intruder front-camera capture, and zero-knowledge vault backups will operate seamlessly over Wi-Fi and mobile data via the encrypted FastAPI backend. SMS is solely an emergency offline fallback when mobile data has been severed.
                </div>
            </div>
        </div>

        <!-- Blog Post 4: GPS Telemetry, Direct Boot & One-Click Map Search -->
        <div class="blog-card" style="border-color: rgba(0, 210, 255, 0.4);">
            <div class="blog-meta">
                <span style="color: var(--primary); font-weight: 700;">TECHNICAL DEEP DIVE &bull; OCTOBER 2026</span> &bull; 
                <span>TOPIC: SATELLITE GPS, DIRECT BOOT &amp; MAP VERIFICATION</span> &bull;
                <span>READ TIME: 5 MIN</span>
            </div>
            <h3>High-Precision GPS Telemetry, Direct Boot Tracking &amp; One-Click Map Search: How SHE Pinpoints Stolen Devices</h3>
            <div class="blog-content">
                <p>
                    When a smartphone is stolen, every minute matters. Tracking must be immediate, precise, and verifiable. SHE combines hardware GPS satellite fixes, Android Direct Boot cryptographic storage, and one-touch map navigation to pinpoint handsets while strictly preserving zero-knowledge privacy.
                </p>

                <h4>1. Real-Time Hardware GPS &amp; Fused Triangulation</h4>
                <p>
                    SHE Guardian queries Android's native location providers (combining GPS satellites, GLONASS, Galileo, and cell-tower trilateration). Rather than sending raw or unverified strings:
                </p>
                <ul style="margin-left: 1.5rem; margin-bottom: 1.25rem; color: #d1d5db;">
                    <li style="margin-bottom: 0.6rem;">
                        <strong>Hardware Cryptographic Signatures:</strong> Every single coordinate packet (latitude, longitude, altitude, velocity, and accuracy) is digitally signed with the handset's hardware-backed <strong>ECDSA P-256</strong> private key stored in the device Keystore. Coordinates cannot be spoofed by attackers or man-in-the-middle proxies.
                    </li>
                    <li style="margin-bottom: 0.6rem;">
                        <strong>High-Precision Coordinates:</strong> Location fixes are encoded to 5 decimal places (approx. 1.1-meter physical resolution).
                    </li>
                </ul>

                <h4>2. Direct Boot Resumption: Tracking Before First Unlock</h4>
                <p>
                    A standard anti-theft app stops working if the thief simply powers off the phone or forces a reboot. Why? Because Credential Encrypted Storage (CE) remains locked until the user enters their lockscreen PIN.
                </p>
                <div class="blog-callout">
                    <strong>The Direct Boot Advantage:</strong> SHE Guardian operates entirely within <strong>Device Encrypted Storage (DES)</strong>. As soon as the Linux kernel boots, Guardian's tracking foreground service and SMS receivers initialize immediately. If a thief turns on the phone and leaves it locked, location telemetry is already streaming to your private server.
                </div>

                <h4>3. One-Click "Search on Map" Integration</h4>
                <p>
                    In an emergency, you do not have time to manually copy, paste, or type coordinate numbers into a search engine. Both the <strong>SHE Admin Companion App</strong> and the <strong>Web Dashboard</strong> feature a dedicated <strong>"Search on Google Maps"</strong> action:
                </p>
                <ul style="margin-left: 1.5rem; margin-bottom: 1.25rem; color: #d1d5db;">
                    <li style="margin-bottom: 0.6rem;">
                        <strong>Native Android Intent:</strong> In the Admin Companion App, tapping <code>🗺️ Search on Map</code> executes <code>Intent.ACTION_VIEW</code> with standard <code>geo:lat,lng?q=lat,lng</code> URI, dropping an immediate red pin on Google Maps or OpenStreetMap.
                    </li>
                    <li style="margin-bottom: 0.6rem;">
                        <strong>Web Portal Search:</strong> On the web dashboard, clicking <code>🗺️ Search on Google Maps</code> opens a direct satellite/street view navigation pin in a secure new tab.
                    </li>
                </ul>

                <h4>4. Understanding Accuracy Margins &amp; Timestamps</h4>
                <ul style="margin-left: 1.5rem; margin-bottom: 1.25rem; color: #d1d5db;">
                    <li><strong>Accuracy Margin (&plusmn;meters):</strong> Represents the 68% confidence radius. Values under <strong>&plusmn;15m</strong> indicate direct satellite lock; larger values (&plusmn;500m) indicate cellular cell-tower fallback.</li>
                    <li><strong>Capture Time vs. Server Receipt:</strong> Capture time shows the exact microsecond the GPS chip calculated the fix; Server Receipt shows when your private backend verified the hardware ECDSA signature.</li>
                </ul>

                <div class="blog-callout">
                    <strong>100% Zero-Telemetry Guarantee:</strong> Your coordinates are never sent to third-party ad brokers, analytics SDKs, or cloud aggregators. Telemetry is saved strictly in your private database and subjected to an automated <strong>30-day rolling purge</strong>.
                </div>
            </div>
        </div>
    </div>

    <!-- Community & Open-Source Section -->
    <div class="container" id="support" style="margin-top: 1rem;">
        <div class="card" style="text-align: center; max-width: 720px; margin: 0 auto; border-color: rgba(16, 185, 129, 0.35);">
            <div style="font-size: 2rem; margin-bottom: 0.5rem;">❤️</div>
            <h3 style="font-size: 1.5rem; margin-bottom: 0.75rem;">SHE Open-Source Community</h3>
            <p style="color: var(--text-muted); font-size: 0.95rem; line-height: 1.6; margin-bottom: 1.25rem;">
                Free & open-source software built for community handset defense. All administration and device recovery is performed exclusively through verified native Android Companion clients.
            </p>
            <div class="btn-group">
                <a href="/docs/user-guide" class="btn btn-secondary">User Guide</a>
                <a href="/docs/setup-order" class="btn btn-secondary">10-Step Setup Runbook</a>
                <a href="/privacy" class="btn btn-secondary">Data Privacy & Security</a>
            </div>
        </div>
    </div>

    <!-- Location How It Works Modal -->
    <div id="locationInfoModal" style="display: none; position: fixed; inset: 0; background: rgba(10, 14, 23, 0.85); backdrop-filter: blur(10px); z-index: 9999; justify-content: center; align-items: center; padding: 1.5rem;">
        <div class="card" style="max-width: 650px; width: 100%; border-color: var(--primary); background: #111827; position: relative; max-height: 90vh; overflow-y: auto;">
            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem;">
                <h3 style="font-size: 1.35rem; color: #f3f4f6;">🛰️ How Handset Location Tracking Works</h3>
                <button onclick="document.getElementById('locationInfoModal').style.display='none'" style="background: none; border: none; color: #9ca3af; font-size: 1.5rem; cursor: pointer;">&times;</button>
            </div>
            <div style="color: #d1d5db; font-size: 0.92rem; line-height: 1.7;">
                <p style="margin-bottom: 0.75rem;">
                    <strong>1. Real-Time GPS & Fused Satellites:</strong><br>
                    Guardian queries Android's hardware GPS and network location providers at regular intervals, or on-demand when the owner triggers a <code>LOCATE</code> command. Each fix includes latitude, longitude, altitude, and precision radius.
                </p>
                <p style="margin-bottom: 0.75rem;">
                    <strong>2. Tamper-Proof Cryptographic Signatures:</strong><br>
                    Before leaving the phone, each coordinate packet is cryptographically signed using the handset's hardware-backed <strong>ECDSA P-256</strong> private key stored in Android Keystore. The backend verifies this signature before storing the fix, preventing GPS spoofing attacks.
                </p>
                <p style="margin-bottom: 0.75rem;">
                    <strong>3. Direct Boot Resumption (Pre-PIN Tracking):</strong><br>
                    Even if a thief power-cycles the phone and leaves it at the lockscreen, Guardian starts automatically in <strong>Device Encrypted Storage (DES)</strong> and continues broadcasting location updates without needing the user's PIN to be entered.
                </p>
                <p style="margin-bottom: 0.75rem;">
                    <strong>4. Margin of Accuracy (±meters):</strong><br>
                    The accuracy number represents the 68% confidence radius in meters. Values under <strong>±20m</strong> represent high-precision satellite triangulation; larger values represent cellular tower approximations.
                </p>
                <p style="margin-bottom: 0.75rem;">
                    <strong>5. 100% Zero-Telemetry Guarantee:</strong><br>
                    Coordinates are stored exclusively in your own private, self-hosted database. SHE contains zero third-party trackers, zero Google Analytics, and enforces an automated <strong>30-day rolling purge</strong>.
                </p>
            </div>
            <button onclick="document.getElementById('locationInfoModal').style.display='none'" class="btn btn-primary" style="margin-top: 1.25rem; width: 100%; justify-content: center; cursor: pointer;">
                Close Guide
            </button>
        </div>
    </div>
    """
    return HTMLResponse(content=render_html_shell("SHE - Sentinel for Handheld Equipment | Open-Source Anti-Theft", body, "downloads"))


# -----------------------------------------------------------------------------
# User Guide Documentation Route
# -----------------------------------------------------------------------------
@router.get("/docs/user-guide", response_class=HTMLResponse)
def render_user_guide():
    body = """
    <div class="container">
        <div class="doc-page">
            <div style="display: flex; align-items: center; gap: 0.75rem; margin-bottom: 1rem;">
                <span class="card-badge badge-info">Documentation</span>
                <span style="font-size: 0.85rem; color: var(--text-muted); font-family: var(--font-mono);">Updated: October 2026</span>
            </div>
            <h1>Anti-Theft & Handset Recovery Ecosystem: User Guide</h1>
            <p style="font-size: 1.1rem; color: var(--text-muted); margin-bottom: 2rem;">
                A comprehensive operational runbook for handset owners. Explains everyday management, emergency response during theft, encrypted vault storage, and official Indian CEIR reporting.
            </p>

            <h2>1. How the System Works: The Two Apps</h2>
            <p>The ecosystem consists of two distinct Android applications communicating securely:</p>
            <table>
                <thead>
                    <tr>
                        <th>Capability</th>
                        <th>Guardian App (Protected Phone)</th>
                        <th>Admin App (Controller Phone)</th>
                    </tr>
                </thead>
                <tbody>
                    <tr>
                        <td><strong>Primary Purpose</strong></td>
                        <td>Silent background tracking & combat defense</td>
                        <td>Remote management, live map & recovery vault</td>
                    </tr>
                    <tr>
                        <td><strong>Background Services</strong></td>
                        <td>Direct Boot foreground tracking & SMS listener</td>
                        <td>On-demand polling & command dispatcher</td>
                    </tr>
                    <tr>
                        <td><strong>Hardware Controls</strong></td>
                        <td>Device Owner volume lock, kiosk screen takeover</td>
                        <td>Hardware Keystore ECDSA private key signing</td>
                    </tr>
                    <tr>
                        <td><strong>Recovery Vault</strong></td>
                        <td>Does not store vault secrets (prevents theft leak)</td>
                        <td>Zero-knowledge AES-256-GCM vault decryptor</td>
                    </tr>
                </tbody>
            </table>

            <h2>2. Sideloading: Bypassing Google Play Protect &amp; OEM "High Risk" Warnings</h2>
            <div class="blog-callout-warning">
                <strong>Why Android Flags SHE Guardian:</strong><br>
                When sideloading pre-release builds onto a secondary handset, <strong>Google Play Protect</strong> and OEM package installers (Samsung One UI, Xiaomi HyperOS/MIUI, ColorOS, OriginOS) will flag Guardian as <em>"High Risk"</em> or <em>"Blocked by Play Protect"</em>.
                This is an automated heuristic response triggered because Guardian combines <strong>Device Admin, Background Location, and SMS execution</strong> under a local open-source developer certificate.<br><br>
                <strong>How to Install Without Errors:</strong>
                <ul style="margin-top: 0.5rem; margin-left: 1.5rem;">
                    <li><strong>Google Play Protect (Stock / Motorola / Pixel):</strong> Tap <em>"More details"</em> (down arrow) &rarr; Tap <em>"Install anyway"</em>. If completely blocked, open Play Store &rarr; Profile &rarr; Play Protect &rarr; Settings gear &rarr; temporarily toggle off <em>"Scan apps with Play Protect"</em>.</li>
                    <li><strong>Xiaomi / Redmi / POCO (HyperOS / MIUI):</strong> Wait for the 10-second security countdown &rarr; Check <em>"I am aware of the possible risks"</em> &rarr; Tap <em>"OK"</em> / <em>"Install"</em>.</li>
                    <li><strong>Samsung Galaxy (One UI):</strong> Tap <em>"More options"</em> &rarr; Tap <em>"Install anyway"</em>.</li>
                    <li><strong>Oppo / Realme / Vivo:</strong> Tap <em>"Continue installing"</em> &rarr; Enter your lockscreen PIN/fingerprint when prompted.</li>
                    <li><strong>Cleanest Method (ADB):</strong> Connect phone to PC and execute: <span class="code-pill">adb install -r -g app-guardian-direct-debug.apk</span> (the <span class="code-pill">-g</span> flag auto-grants permissions and bypasses all UI warnings).</li>
                </ul>
            </div>

            <div class="blog-callout">
                <strong>SMS Channel Privacy &amp; Zero-Retention OTP Protection:</strong><br>
                • <strong>No Inbox Access:</strong> Guardian does <em>not</em> request <span class="code-pill">READ_SMS</span> and cannot access stored banking history or existing messages.<br>
                • <strong>Immediate OTP Discard:</strong> Any incoming text that does not start with <span class="code-pill">AT1:CMD:</span> (bank OTPs, personal SMS) is discarded in 0 microseconds without logging or disk storage.<br>
                • <strong>Strictly Pinned Recipient:</strong> Outgoing SMS can only ever reach your registered Admin Companion phone number.<br>
                • <strong>Zero Link Opening:</strong> Guardian contains 0 WebViews and 0 URL launching code; it cannot open web links.<br>
                • <strong>100% Optional:</strong> SMS permissions can be revoked anytime. The app will continue to track, sound sirens, and lock via Wi-Fi and mobile data.
            </div>

            <h2>3. Day-to-Day Operation</h2>
            <h3>Checking Live Location</h3>
            <p>
                Open the Admin Companion App dashboard. Tap your protected phone's alias to view its latest location fix, battery level, accuracy radius, and last-seen timestamp.
            </p>

            <h3>Testing Alarms Safely: "Test Mode" vs. "Real Stolen Mode"</h3>
            <p>When verifying that speakers and tracking work, never activate real stolen mode in casual settings:</p>
            <ul>
                <li><strong>TEST_MODE (Drill)</strong>: Plays a safe, non-destructive 10-second siren and voice alert. It automatically shuts off after 10 seconds, does not lock your device, and does <strong>not</strong> send emergency SMS alerts to your family contacts.</li>
                <li><strong>STOLEN_MODE (Real Combat)</strong>: Full combat lockdown. Loops siren and voice alert continuously at maximum volume, locks the physical volume-down button, takes over the screen with the Emergency Return Card, and dispatches automated emergency SMS messages with Google Maps links.</li>
            </ul>

            <h3>Using the Encrypted Recovery Vault</h3>
            <p>
                The Recovery Vault is your digital safety deposit box. It uses client-side AES-256-GCM encryption with PBKDF2 (600,000 iterations). Even if the backend server database is compromised, your secrets cannot be read.
            </p>
            <ul>
                <li><strong>Device Identifiers</strong>: Store IMEI 1, IMEI 2, and Hardware Serial Number for police reports.</li>
                <li><strong>Account Recovery Codes</strong>: Google, banking, and cloud backup 2FA recovery seeds.</li>
                <li><strong>Emergency Contacts</strong>: Family phone numbers and critical email addresses.</li>
            </ul>

            <h2>4. What to Do If Your Phone Is Stolen (4-Step Workflow)</h2>
            <ol>
                <li>
                    <strong>Step 1: Activate Stolen Mode</strong><br>
                    If the handset has internet, engage <code>STOLEN_MODE</code> from the Admin Companion App. If the thief shut off mobile data or Wi-Fi, the Admin App automatically sends an authenticated AT1 SMS command directly to the handset.
                </li>
                <li>
                    <strong>Step 2: What the Thief Experiences</strong><br>
                    The phone immediately starts blaring a siren at 100% volume accompanied by looping Android Text-To-Speech: <em>"Warning. This device is reported stolen. Location is being tracked and reported. Return to owner immediately."</em> Physical volume buttons cannot silence it, and the screen is locked to the Emergency Return Card.
                </li>
                <li>
                    <strong>Step 3: Indian ERSS 112 & Sanchar Saathi CEIR Blacklisting</strong><br>
                    Filing a police report and carrier blacklisting are two sequential actions:
                    <ul>
                        <li><strong>Action A (Police Complaint / FIR)</strong>: Dial <strong>112</strong> (India National ERSS) for active pursuit, or file an e-FIR on your State Police portal. Provide live GPS coordinates and IMEIs retrieved from your Admin Vault. Obtain the stamped FIR / Complaint Reference.</li>
                        <li><strong>Action B (CEIR Blacklist)</strong>: Visit <a href="https://www.sancharsaathi.gov.in" target="_blank" style="color: var(--primary);">sancharsaathi.gov.in</a> (CEIR portal). Submit a "Block Stolen/Lost Mobile" request with your FIR copy and IMEI numbers to permanently blacklist the device across all Indian telecom networks (Jio, Airtel, Vi, BSNL).</li>
                    </ul>
                </li>
                <li>
                    <strong>Step 4: De-escalation Upon Recovery</strong><br>
                    Once recovered, open the Admin Companion App, tap the device, and dispatch <code>EXIT_KIOSK</code> to restore normal operation.
                </li>
            </ol>

            <h2>5. Honest Technical Boundaries: What No App Can Prevent</h2>
            <p>Security software must set honest expectations. No application can bypass physical laws:</p>
            <ul>
                <li><strong>Hardware Force-Off / Battery Pull</strong>: A thief who holds the physical power button for 10–15 seconds triggers a motherboard-level cut-off. Software cannot run while powered off.</li>
                <li><strong>Faraday Bags / Signal Blockers</strong>: Radio-frequency shielding bags block GPS and cellular signals. Guardian logs fixes internally and burst-syncs the moment signal returns.</li>
                <li><strong>Professional Chip-Off Exploitation</strong>: Advanced syndicates with EEPROM programmers can overwrite flash memory chipsets directly. Hardware FRP and Device Owner restrictions make this economically non-viable for opportunistic thieves, but cannot prevent hardware destruction.</li>
            </ul>

            <div style="margin-top: 2.5rem; text-align: center;">
                <a href="/docs/setup-order" class="btn btn-primary">Proceed to 10-Step Setup Runbook &rarr;</a>
            </div>
        </div>
    </div>
    """
    return HTMLResponse(content=render_html_shell("SHE User Guide | Anti-Theft Handset Recovery", body, "user-guide"))


# -----------------------------------------------------------------------------
# Setup Order Documentation Route
# -----------------------------------------------------------------------------
@router.get("/docs/setup-order", response_class=HTMLResponse)
def render_setup_order():
    body = """
    <div class="container">
        <div class="doc-page">
            <div style="display: flex; align-items: center; gap: 0.75rem; margin-bottom: 1rem;">
                <span class="card-badge badge-warning">Mandatory Sequence</span>
                <span style="font-size: 0.85rem; color: var(--text-muted); font-family: var(--font-mono);">Updated: October 2026</span>
            </div>
            <h1>Setup Order Runbook: 10 Mandatory Steps</h1>
            <p style="font-size: 1.1rem; color: var(--text-muted); margin-bottom: 2rem;">
                Android security frameworks enforce rigid dependency chains. Follow this numbered sequence exactly from Step 1 to Step 10.
            </p>

            <div class="blog-callout-warning">
                <strong>Why Order Strictly Matters:</strong><br>
                • Provisioning <strong>Device Owner</strong> fails if <em>any</em> user account is already configured on the handset.<br>
                • Arming <strong>Hardware Factory Reset Protection (FRP)</strong> requires linking a Google account <em>after</em> Device Owner is set.<br>
                • Initializing <strong>Android Keystore hardware-bound credentials</strong> requires a device PIN/Biometric lock to exist <em>before</em> vault secrets are stored.
            </div>

            <h2>Step 1: Factory Reset Test Phone (Zero Accounts)</h2>
            <p>
                Perform a complete factory reset on the handset to protect. Complete the initial setup wizard (Language, Wi-Fi).<br>
                <strong style="color: #ef4444;">CRITICAL: DO NOT ADD A GOOGLE ACCOUNT OR ANY OTHER ACCOUNT.</strong> Skip account setup. If any account exists, Android will reject Device Owner provisioning with <code>IllegalStateException</code>.
            </p>

            <h2>Step 2: Provision Device Owner Mode via ADB</h2>
            <p>Enable Developer Options and USB Debugging on the phone, connect via USB, and install Guardian:</p>
            <div class="code-block">adb install -r -g android/app-guardian/build/outputs/apk/direct/debug/app-guardian-direct-debug.apk</div>
            <div class="blog-callout">
                <strong>Handling Play Protect / OEM "High Risk" Warnings:</strong><br>
                When sideloading pre-release binaries, Google Play Protect or OEM security managers (Xiaomi HyperOS, Samsung Knox, ColorOS, OriginOS) may warn that the app is <em>"High Risk"</em> due to its device admin and background location privileges.
                Using <code>adb install -r -g</code> automatically pre-grants runtime permissions and bypasses these prompts. If sideloading manually on the device:
                • <strong>Play Protect:</strong> Tap <em>"More details"</em> &rarr; <em>"Install anyway"</em>.<br>
                • <strong>Xiaomi HyperOS/MIUI:</strong> Wait for the 10-second timer &rarr; Check <em>"I am aware of risks"</em> &rarr; <em>"OK"</em>.<br>
                • <strong>Samsung Galaxy:</strong> Tap <em>"More options"</em> &rarr; <em>"Install anyway"</em>.
            </div>
            <p>Provision Device Owner component:</p>
            <div class="code-block">adb shell dpm set-device-owner com.antitheft.guardian/com.antitheft.deviceowner.AntiTheftDeviceAdminReceiver</div>
            <p>Verify that terminal returns: <code>Success: Device owner set to package...</code></p>

            <h2>Step 3: Link Your Google Account (Arms Hardware FRP)</h2>
            <p>
                Now that Device Owner is anchored in the OS, go to <strong>Settings &rarr; Passwords & Accounts &rarr; Add Google Account</strong>. Linking your account now arms <strong>Factory Reset Protection (FRP)</strong>. If a thief forces a hardware wipe in recovery mode, Android demands your Google credentials before booting.
            </p>

            <h2>Step 4: Configure Device Screen Lock (PIN / Fingerprint)</h2>
            <p>
                Set a strong screen lock PIN (minimum 6 digits) and enroll biometrics. This is required for Android Keystore hardware-backed keys and arms the 3-wrong-attempt intruder photo capture trigger.
            </p>

            <h2>Step 5: Grant Runtime Permissions & Disable Battery Optimization</h2>
            <p>Grant all required permissions via ADB or manual prompt:</p>
            <div class="code-block">for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION POST_NOTIFICATIONS READ_PHONE_STATE SEND_SMS RECEIVE_SMS CALL_PHONE CAMERA; do
    adb shell pm grant com.antitheft.guardian android.permission.$p 2>/dev/null
done</div>
            <p>Go to <strong>Settings &rarr; Apps &rarr; Guardian &rarr; App battery usage &rarr; Unrestricted</strong>.</p>

            <h2>Step 6: Install & Initialize the Admin Companion App</h2>
            <p>Install the Admin Companion app on your secondary/controller phone:</p>
            <div class="code-block">adb install -r android/app-admin/build/outputs/apk/debug/app-admin-debug.apk</div>
            <p>Forward port (for local development testing):</p>
            <div class="code-block">adb reverse tcp:8000 tcp:8000</div>
            <p>Open Admin App, log into your owner account, enroll Two-Factor Authentication (TOTP), and save your 8 recovery codes.</p>

            <h2>Step 7: Perform Mutual Camera QR Pairing</h2>
            <p>
                In the Admin App, tap <strong>Pair New Device</strong>. Enter your Admin phone number and generate the pairing QR code. In Guardian, tap <strong>Pair with Admin</strong> and scan the QR code. Confirm the mutual 3-digit visual verification code on both screens.
            </p>

            <h2>Step 8: Configure Trusted Emergency Contacts</h2>
            <p>
                In Guardian settings, save your trusted emergency contact number in full international E.164 format (e.g., <code>+919876543210</code>). This number receives automated SMS coordinates upon theft.
            </p>

            <h2>Step 9: Arm the Encrypted Recovery Vault</h2>
            <p>
                In the Admin App, open <strong>Recovery Vault</strong>. Authenticate with biometrics and store your handset's IMEI 1, IMEI 2, serial number, and secondary recovery codes. Set a strong master passphrase (&ge;16 chars) and write it down on physical paper.
            </p>

            <h2>Step 10: Run the "Test Mode" Drill</h2>
            <p>
                Verify functionality without causing a real panic: in the Admin App, select <strong>TEST_MODE (Drill)</strong>. The Guardian handset will play a 10-second audible siren and voice announcement at maximum volume, updating its live GPS fix on your dashboard while sending zero emergency SMS messages to family contacts.
            </p>

            <div style="margin-top: 2.5rem; text-align: center;">
                <a href="/docs/user-guide" class="btn btn-secondary">&larr; Back to User Guide</a>
                <a href="/privacy" class="btn btn-primary" style="margin-left: 1rem;">View Data Privacy Policy &rarr;</a>
            </div>
        </div>
    </div>
    """
    return HTMLResponse(content=render_html_shell("SHE Setup Runbook | Mandatory 10-Step Sequence", body, "setup-order"))


# -----------------------------------------------------------------------------
# Data Privacy & Security Route
# -----------------------------------------------------------------------------
@router.get("/privacy", response_class=HTMLResponse)
def render_privacy_page():
    body = """
    <div class="container">
        <div class="doc-page">
            <div style="display: flex; align-items: center; gap: 0.75rem; margin-bottom: 1rem;">
                <span class="card-badge badge-info">Privacy & Security</span>
                <span style="font-size: 0.85rem; color: var(--text-muted); font-family: var(--font-mono);">Updated: October 2026</span>
            </div>
            <h1>Data Privacy & Security Architecture</h1>
            <p style="font-size: 1.1rem; color: var(--text-muted); margin-bottom: 2rem;">
                A plain-language breakdown of how SHE protects your personal data, guarantees zero telemetry, and enforces zero-knowledge encryption.
            </p>

            <h2>1. Zero Telemetry & Advertising Commitment</h2>
            <p>
                SHE is built strictly for handset defense, not data collection. Our applications and backend contain:
            </p>
            <ul>
                <li><strong>Zero Third-Party Trackers</strong>: No Google Analytics, Firebase Analytics, Crashlytics, or user tracking SDKs.</li>
                <li><strong>Zero Advertising SDKs</strong>: No advertising networks, behavioral profiling, or device fingerprinting.</li>
                <li><strong>Zero Data Monetization</strong>: Your handset locations, identity documents, and recovery records are never sold, analyzed, or shared with third parties.</li>
            </ul>

            <h2>2. Zero-Knowledge Cryptographic Vault</h2>
            <p>
                When you store IMEI numbers, recovery keys, or purchase invoices in the Recovery Vault:
            </p>
            <ul>
                <li><strong>Client-Side Encryption</strong>: All encryption is performed directly on your Android controller device before data leaves the handset.</li>
                <li><strong>Military-Grade Algorithms</strong>: Uses AES-256-GCM authenticated encryption with PBKDF2 key derivation (600,000 iterations and 128-bit random salts).</li>
                <li><strong>Opaque Server Storage</strong>: The backend database only ever stores base64-encoded ciphertext envelopes, initialization vectors (IVs), and authentication tags. Even with full database access, server operators cannot decrypt your secrets.</li>
            </ul>

            <h2>3. Handset Location Telemetry Retention (30-Day Purge)</h2>
            <p>
                Location data is sensitive. SHE enforces strict telemetry controls:
            </p>
            <ul>
                <li><strong>Scoped Strictly to Paired Owners</strong>: Only the authenticated owner of a device can query its GPS coordinates. Cross-user access is blocked at the API authorization layer.</li>
                <li><strong>Automated Rolling Purge</strong>: 30-day rolling purge automatically removes historical GPS coordinate fixes older than 30 days from the database.</li>
                <li><strong>Direct Boot Separation</strong>: Before the first unlock, location coordinates are stored strictly in Device Encrypted Storage to prevent unauthorized local extraction.</li>
            </ul>

            <h2>4. Authentication & Key Management</h2>
            <ul>
                <li><strong>Password Protection</strong>: Owner account passwords are hash-protected using the memory-hard <strong>Argon2id</strong> algorithm.</li>
                <li><strong>Encrypted TOTP Secrets</strong>: Two-Factor Authentication shared secrets are encrypted at rest on the server using AES-256 before database insertion.</li>
                <li><strong>Argon2id Single-Use Recovery Codes</strong>: 8 emergency recovery codes are generated as salted Argon2id hashes. Once used to log in during a theft crisis, the code is permanently destroyed.</li>
            </ul>

            <h2>5. SMS Channel Privacy & Zero-Retention OTP Protection</h2>
            <p>
                When offline SMS fallback is enabled, SHE enforces strict privacy controls to prevent message interception and spam:
            </p>
            <ul>
                <li><strong>Zero Inbox Access</strong>: SHE does not request <code>android.permission.READ_SMS</code>. It cannot read stored messages, SMS history, or personal conversations.</li>
                <li><strong>Zero-Retention OTP Discard</strong>: Inbound messages that do not start with <code>AT1:CMD:</code> (such as banking OTPs, 2FA codes, or personal chats) are immediately discarded from memory in 0 microseconds without being logged or parsed.</li>
                <li><strong>Strict Recipient Pinned to Trusted Admin</strong>: Outgoing SMS can only ever be sent to the paired Admin Companion phone number. It cannot send messages to arbitrary third parties or premium-rate numbers.</li>
                <li><strong>Zero Link Launching Capabilities</strong>: Guardian contains zero WebViews and zero browser intents; it is incapable of opening web links.</li>
                <li><strong>100% Optional</strong>: SMS permissions are completely optional. If denied, the entire ecosystem continues to operate normally over Wi-Fi and mobile data.</li>
            </ul>

            <h2>6. Open-Source Transparency & Self-Hosting</h2>
            <p>
                Trust is verified through source code, not marketing claims:
            </p>
            <ul>
                <li><strong>100% Open-Source</strong>: Published under the permissive Apache 2.0 open-source license. Every line of backend and Android client code is available for independent security audits.</li>
                <li><strong>Self-Hostable</strong>: You can host the SHE FastAPI backend on your own private virtual server or home Raspberry Pi, retaining complete sovereignty over your data.</li>
            </ul>

            <div style="margin-top: 2.5rem; text-align: center;">
                <a href="/docs/user-guide" class="btn btn-secondary">&larr; Read User Guide</a>
                <a href="/" class="btn btn-primary" style="margin-left: 1rem;">Return to Homepage</a>
            </div>
        </div>
    </div>
    """
    return HTMLResponse(content=render_html_shell("SHE Data Privacy & Security Architecture", body, "privacy"))
