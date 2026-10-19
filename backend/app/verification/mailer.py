"""Email delivery service for zero-trust verification codes.

Uses Python smtplib with STARTTLS support and clean plain text / HTML formatting.
Never logs OTP codes, passwords, or SMTP authentication credentials.
"""

import logging
import smtplib
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText

from app.core.config import settings

logger = logging.getLogger("uvicorn.error")


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


def send_verification_email(to_email: str, code: str) -> None:
    """
    Deliver a 6-digit verification code to the target email.
    
    Supports:
    - STARTTLS (587) and direct SSL (465) with automatic failover
    - Plain SMTP when unauthenticated (e.g. local Mailpit test instance)
    - Zero credential / code leakage in application logs
    """
    clean_target = to_email.strip().lower()
    subject = f"SHE Anti-Theft Verification Code: {code}"

    text_body = f"""SHE Anti-Theft Verification

Your one-time email verification code is: {code}

This code is valid for 10 minutes.
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
    <p>Please enter the following 6-digit code to verify your account email address:</p>
    <div class="code-box">
      <span class="code">{code}</span>
    </div>
    <p class="footer">
      This code will expire in <strong>10 minutes</strong>.<br>
      If you did not request this code, you can safely ignore this email.
    </p>
  </div>
</body>
</html>
"""

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
    logger.error(f"Failed to dispatch verification email to {clean_target} across all available SMTP transports.")
    raise
