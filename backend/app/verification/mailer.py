"""Email delivery service for zero-trust verification codes.

Supports:
- HTTPS Email APIs: Resend, Brevo, SendGrid (configured via Render environment variables)
- Traditional SMTP with STARTTLS and SSL failover (e.g. Mailpit for local testing)
- Direct 1-click verification link in email body
- Never logs OTP codes, passwords, or authentication credentials.
"""

import json
import logging
import smtplib
import urllib.request
import urllib.error
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText
from typing import Optional

from app.core.config import settings

logger = logging.getLogger("uvicorn.error")


def _dispatch_https_api(clean_target: str, subject: str, text_body: str, html_body: str) -> bool:
    """
    Attempt email dispatch via HTTPS APIs (Resend, Brevo, SendGrid) without relying on SMTP.
    Returns True if successfully dispatched via an HTTPS API, False otherwise.
    """
    # 1. Resend API
    if settings.RESEND_API_KEY:
        try:
            url = "https://api.resend.com/emails"
            from_addr = settings.SMTP_FROM if ("@" in settings.SMTP_FROM and not settings.SMTP_FROM.endswith(".local")) else "onboarding@resend.dev"
            payload = json.dumps({
                "from": from_addr,
                "to": [clean_target],
                "subject": subject,
                "html": html_body,
                "text": text_body
            }).encode("utf-8")
            req = urllib.request.Request(
                url,
                data=payload,
                headers={
                    "Authorization": f"Bearer {settings.RESEND_API_KEY}",
                    "Content-Type": "application/json",
                    "User-Agent": "SHE-AntiTheft/0.4.1"
                },
                method="POST"
            )
            with urllib.request.urlopen(req, timeout=8.0) as resp:
                if 200 <= resp.status < 300:
                    logger.info(f"Verification email dispatched successfully via Resend API to {clean_target}")
                    return True
        except Exception as resend_err:
            logger.warning(f"Resend API dispatch failed ({type(resend_err).__name__}). Falling back...")

    # 2. Brevo API (formerly Sendinblue)
    if settings.BREVO_API_KEY:
        try:
            url = "https://api.brevo.com/v3/smtp/email"
            from_addr = settings.SMTP_FROM if ("@" in settings.SMTP_FROM and not settings.SMTP_FROM.endswith(".local")) else "verify@she.app"
            payload = json.dumps({
                "sender": {"email": from_addr, "name": "SHE Anti-Theft"},
                "to": [{"email": clean_target}],
                "subject": subject,
                "htmlContent": html_body,
                "textContent": text_body
            }).encode("utf-8")
            req = urllib.request.Request(
                url,
                data=payload,
                headers={
                    "api-key": settings.BREVO_API_KEY,
                    "Content-Type": "application/json",
                    "User-Agent": "SHE-AntiTheft/0.4.1"
                },
                method="POST"
            )
            with urllib.request.urlopen(req, timeout=8.0) as resp:
                if 200 <= resp.status < 300:
                    logger.info(f"Verification email dispatched successfully via Brevo API to {clean_target}")
                    return True
        except Exception as brevo_err:
            logger.warning(f"Brevo API dispatch failed ({type(brevo_err).__name__}). Falling back...")

    # 3. SendGrid API
    if settings.SENDGRID_API_KEY:
        try:
            url = "https://api.sendgrid.com/v3/mail/send"
            from_addr = settings.SMTP_FROM if ("@" in settings.SMTP_FROM and not settings.SMTP_FROM.endswith(".local")) else "no-reply@she.app"
            payload = json.dumps({
                "personalizations": [{"to": [{"email": clean_target}]}],
                "from": {"email": from_addr, "name": "SHE Anti-Theft"},
                "subject": subject,
                "content": [
                    {"type": "text/plain", "value": text_body},
                    {"type": "text/html", "value": html_body}
                ]
            }).encode("utf-8")
            req = urllib.request.Request(
                url,
                data=payload,
                headers={
                    "Authorization": f"Bearer {settings.SENDGRID_API_KEY}",
                    "Content-Type": "application/json",
                    "User-Agent": "SHE-AntiTheft/0.4.1"
                },
                method="POST"
            )
            with urllib.request.urlopen(req, timeout=8.0) as resp:
                if 200 <= resp.status < 300:
                    logger.info(f"Verification email dispatched successfully via SendGrid API to {clean_target}")
                    return True
        except Exception as sg_err:
            logger.warning(f"SendGrid API dispatch failed ({type(sg_err).__name__}). Falling back...")

    return False


def _dispatch_smtp(msg: MIMEMultipart, clean_target: str, host: str, port: int, timeout: float = 6.0) -> None:
    """Attempt email dispatch using the appropriate protocol for the given port."""
    if port == 465:
        # Port 465 requires direct SSL handshake (SMTPS)
        with smtplib.SMTP_SSL(host=host, port=port, timeout=timeout) as server:
            server.ehlo()
            if settings.SMTP_USER and settings.SMTP_PASS:
                server.login(settings.SMTP_USER, settings.SMTP_PASS)
            server.send_message(msg)
    else:
        # Port 587 (or 25/1025) uses plain connect with opportunistic STARTTLS
        with smtplib.SMTP(host=host, port=port, timeout=timeout) as server:
            server.ehlo()
            if settings.SMTP_USER and settings.SMTP_PASS:
                if server.has_extn("STARTTLS"):
                    server.starttls()
                    server.ehlo()
                server.login(settings.SMTP_USER, settings.SMTP_PASS)
            server.send_message(msg)


def send_verification_email(to_email: str, code: str, verify_url: Optional[str] = None) -> None:
    """
    Deliver a 6-digit verification code and optional 1-click verification link to the target email.
    
    Priority:
    1. HTTPS API delivery (Resend / Brevo / SendGrid) if configured via environment
    2. SMTP delivery (port 587 with STARTTLS or port 465 direct SSL)
    3. Plain SMTP for local development (Mailpit)
    """
    clean_target = to_email.strip().lower()
    subject = f"SHE Anti-Theft Verification Code: {code}"

    link_text_section = f"\nAlternatively, verify instantly by clicking this link:\n{verify_url}\n" if verify_url else ""
    link_html_section = f"""
    <div style="text-align: center; margin: 20px 0;">
      <a href="{verify_url}" style="display: inline-block; background-color: #0284c7; color: #ffffff; text-decoration: none; padding: 12px 24px; border-radius: 6px; font-weight: 600; font-size: 15px;">Verify Email Address</a>
    </div>
    <p style="font-size: 12px; color: #64748b; word-break: break-all;">Or copy and paste this link in your browser:<br>{verify_url}</p>
    """ if verify_url else ""

    text_body = f"""SHE Anti-Theft Verification

Your one-time email verification code is: {code}
{link_text_section}
This code and link are valid for 15 minutes.
If you did not request this code, no further action is required.
"""

    html_body = f"""<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <style>
    body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #0f172a; color: #f8fafc; padding: 24px; }}
    .container {{ max-width: 500px; margin: 0 auto; background-color: #1e293b; border-radius: 12px; padding: 32px; border: 1px solid #334155; }}
    .logo {{ font-size: 20px; font-weight: 700; color: #38bdf8; margin-bottom: 24px; }}
    .code-box {{ background-color: #0f172a; border: 1px solid #0284c7; border-radius: 8px; padding: 18px; text-align: center; margin: 24px 0; }}
    .code {{ font-family: 'Courier New', Courier, monospace; font-size: 32px; font-weight: 800; letter-spacing: 6px; color: #38bdf8; }}
    .footer {{ font-size: 13px; color: #94a3b8; margin-top: 24px; line-height: 1.5; }}
  </style>
</head>
<body>
  <div class="container">
    <div class="logo">SHE Anti-Theft & Recovery</div>
    <p>Please enter the following 6-digit code in the SHE Admin app, or click the verification button below:</p>
    <div class="code-box">
      <span class="code">{code}</span>
    </div>
    {link_html_section}
    <p class="footer">
      This code will expire in <strong>15 minutes</strong>.<br>
      If you did not request this code, you can safely ignore this email.
    </p>
  </div>
</body>
</html>
"""

    # Priority 1: Try HTTPS API (Resend / Brevo / SendGrid)
    if _dispatch_https_api(clean_target, subject, text_body, html_body):
        return

    # Priority 2: Fall back to SMTP
    msg = MIMEMultipart("alternative")
    msg["Subject"] = subject
    msg["From"] = settings.SMTP_FROM
    msg["To"] = clean_target

    msg.attach(MIMEText(text_body, "plain", "utf-8"))
    msg.attach(MIMEText(html_body, "html", "utf-8"))

    host = settings.SMTP_HOST
    primary_port = settings.SMTP_PORT

    # Attempt 1: Configured primary port
    try:
        _dispatch_smtp(msg, clean_target, host, primary_port, timeout=6.0)
        logger.info(f"Verification email dispatched successfully to {clean_target} via {host}:{primary_port}")
        return
    except Exception as primary_err:
        logger.warning(
            f"Primary SMTP dispatch via {host}:{primary_port} failed ({type(primary_err).__name__}). Trying automatic failover..."
        )

    # Attempt 2: Automatic failover between port 587 (STARTTLS) and port 465 (SSL)
    fallback_port = 465 if primary_port != 465 else 587
    if host in ("smtp.gmail.com", "smtp.sendgrid.net", "smtp.mailgun.org", "smtp-relay.brevo.com") or primary_port in (587, 465):
        try:
            _dispatch_smtp(msg, clean_target, host, fallback_port, timeout=6.0)
            logger.info(f"Verification email dispatched successfully via fallback {host}:{fallback_port} to {clean_target}")
            return
        except Exception as fallback_err:
            logger.error(
                f"Fallback SMTP dispatch via {host}:{fallback_port} also failed ({type(fallback_err).__name__})."
            )

    # If all attempts failed, log clean diagnostic without leaking credentials or OTP
    logger.error(f"Failed to dispatch verification email to {clean_target} across all available transports.")
    raise
