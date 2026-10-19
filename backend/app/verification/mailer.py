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


def send_verification_email(to_email: str, code: str) -> None:
    """
    Deliver a 6-digit verification code to the target email.
    
    Supports:
    - STARTTLS when credentials are provided
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
    <div class="logo">🛡️ SHE Anti-Theft & Recovery</div>
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

    try:
        with smtplib.SMTP(host=settings.SMTP_HOST, port=settings.SMTP_PORT, timeout=10.0) as server:
            # If server supports EHLO/STARTTLS and user credentials provided, upgrade to TLS
            server.ehlo()
            if settings.SMTP_USER and settings.SMTP_PASS:
                if server.has_extn("STARTTLS"):
                    server.starttls()
                    server.ehlo()
                server.login(settings.SMTP_USER, settings.SMTP_PASS)

            server.send_message(msg)
            logger.info(f"Verification email dispatched successfully to {clean_target}")
    except Exception as e:
        # Crucial security rule: NEVER log the verification code or SMTP password
        logger.error(f"Failed to dispatch verification email to {clean_target}: {type(e).__name__}")
        raise
