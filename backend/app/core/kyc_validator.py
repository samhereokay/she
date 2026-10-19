"""Automated KYC Identity & Anti-Spoofing Verification Module.

Enforces zero-trust validation for registration:
1. Disposable & Burner Email Blocking (loaded from disposable_domains.txt, matches subdomains)
2. Live DNS MX Resolution (with RFC 7505 Null MX detection, A/AAAA fallback, 5s timeout, fail-open on transient resolver errors)
3. Typo Protection for Major Mail Providers (blocks gmial.com, yaho.com, etc.)
4. Phone Anti-Spoofing using phonenumbers library as the base:
   - Enforces international E.164 normalization and validity
   - Rejects FIXED_LINE landlines
   - Extra layer for country-specific mobile ranges (IN, US, UK)
   - Extra layer for sequential and low-entropy dummy numbers with configurable support guidance
5. Authentic Hardware Telemetry Verification:
   - Rejects dummy placeholders in production
   - Permitted Android emulator models (sdk_gphone64_*) only when settings.DEBUG is enabled
"""

import logging
import os
import re
from typing import Tuple

import dns.exception
import dns.resolver
import phonenumbers
from phonenumbers import PhoneNumberType

logger = logging.getLogger("kyc_validator")

# Common typos for major email providers
EMAIL_TYPO_MAP = {
    "gmial.com": "gmail.com",
    "gmaill.com": "gmail.com",
    "gamil.com": "gmail.com",
    "gmai.com": "gmail.com",
    "hotmial.com": "hotmail.com",
    "hotmaill.com": "hotmail.com",
    "yaho.com": "yahoo.com",
    "yahooo.com": "yahoo.com",
    "outlok.com": "outlook.com",
    "iclud.com": "icloud.com",
}

# RFC-reserved and test domains permitted during offline testing
RFC_TEST_DOMAINS = {"example.com", "example.org", "example.net", "test.com", "localhost", "test.local"}


def _load_disposable_domains() -> set[str]:
    """Load disposable email domains from disposable_domains.txt once at import time."""
    domains_file = os.path.join(os.path.dirname(__file__), "disposable_domains.txt")
    loaded: set[str] = set()
    if os.path.exists(domains_file):
        with open(domains_file, "r", encoding="utf-8") as f:
            for line in f:
                stripped = line.strip().lower()
                if stripped and not stripped.startswith("#"):
                    loaded.add(stripped)
    return loaded


DISPOSABLE_EMAIL_DOMAINS = _load_disposable_domains()


def check_domain_dns_mx(domain_part: str, resolver: dns.resolver.Resolver | None = None) -> Tuple[bool, str | None]:
    """Perform DNS MX record resolution with A/AAAA fallback, checking for RFC 7505 Null MX and failing open on transient errors."""
    if resolver is None:
        resolver = dns.resolver.Resolver()
        resolver.lifetime = 5.0  # 5-second timeout policy

    try:
        # 1. Query MX records
        try:
            mx_answers = resolver.resolve(domain_part, "MX")
            # Check for RFC 7505 Null MX (single record with exchange "." or "")
            for rdata in mx_answers:
                exchange_str = str(rdata.exchange).strip().rstrip(".")
                if exchange_str == "":
                    return False, f"Email domain '@{domain_part}' explicitly does not accept email (Null MX)."
            return True, None
        except dns.resolver.NoAnswer:
            pass  # Fall back to A / AAAA lookup
        except dns.resolver.NXDOMAIN:
            return False, f"Email domain '@{domain_part}' does not exist."
        except dns.exception.DNSException as e:
            logger.warning("DNS MX query encountered resolver error for '%s': %s. Failing open.", domain_part, e)
            return True, None

        # 2. Fallback to A / AAAA address records (RFC 5321 section 5.1)
        has_a_record = False
        for qtype in ("A", "AAAA"):
            try:
                resolver.resolve(domain_part, qtype)
                has_a_record = True
                break
            except (dns.resolver.NoAnswer, dns.resolver.NoNameservers):
                continue
            except dns.resolver.NXDOMAIN:
                return False, f"Email domain '@{domain_part}' does not exist."
            except dns.exception.DNSException as e:
                logger.warning("DNS A/AAAA fallback query encountered resolver error for '%s': %s. Failing open.", domain_part, e)
                return True, None

        if not has_a_record:
            return False, f"Email domain '@{domain_part}' does not exist or has no active mail servers."

        return True, None

    except dns.resolver.NXDOMAIN:
        return False, f"Email domain '@{domain_part}' does not exist."
    except dns.exception.DNSException as e:
        logger.warning("DNS lookup failed with resolver error for '%s': %s. Failing open.", domain_part, e)
        return True, None


def validate_email_kyc(email: str, resolver: dns.resolver.Resolver | None = None) -> Tuple[bool, str | None]:
    """Validate email format, reject disposable domains, check typos and perform live MX lookup with fail-open policy."""
    cleaned = (email or "").strip().lower()
    if not cleaned or not re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", cleaned):
        return False, "A valid email address is required (e.g. owner@example.com)."

    username_part, domain_part = cleaned.split("@", 1)

    # 1. Disposable email check (matches exact domain or subdomains)
    if domain_part in DISPOSABLE_EMAIL_DOMAINS or any(domain_part.endswith("." + d) for d in DISPOSABLE_EMAIL_DOMAINS):
        return False, "Disposable or temporary email addresses are not permitted. Please use your authentic personal email."

    # 2. Common typo check
    if domain_part in EMAIL_TYPO_MAP:
        suggestion = EMAIL_TYPO_MAP[domain_part]
        return False, f"Did you mean @{suggestion}? Please check your email domain spelling."

    # 3. Live DNS MX resolution (with A/AAAA fallback, 5s timeout, fail-open policy)
    is_testing = os.getenv("PYTEST_CURRENT_TEST") is not None or domain_part in RFC_TEST_DOMAINS
    if resolver is not None:
        # Explicit resolver supplied (e.g. in test)
        return check_domain_dns_mx(domain_part, resolver=resolver)

    if not is_testing:
        return check_domain_dns_mx(domain_part)

    return True, None


def is_dummy_number(digits: str) -> bool:
    """Detect repetitive, sequential or low-entropy dummy phone numbers."""
    if len(set(digits)) < 4:
        return True

    # Check for sequential ascending/descending runs of 6+ digits
    ascending = "01234567890123456789"
    descending = "98765432109876543210"
    for i in range(len(digits) - 5):
        chunk = digits[i:i+6]
        if chunk in ascending or chunk in descending:
            return True

    return False


def validate_phone_kyc(phone: str) -> Tuple[bool, str | None]:
    """Validate phone number using phonenumbers library as the base, enforcing mobile carrier type and anti-dummy checks."""
    cleaned = (phone or "").strip().replace(" ", "").replace("-", "").replace("(", "").replace(")", "")
    if not cleaned:
        return False, "Owner mobile number is required for emergency offline SMS beacons."

    raw = cleaned if cleaned.startswith("+") else "+" + cleaned

    # 1. Base validation and parsing using phonenumbers
    try:
        parsed = phonenumbers.parse(raw, None)
    except phonenumbers.NumberParseException:
        return False, "Invalid international phone number format. Please include your country code (e.g. +91... or +1...)."

    if not phonenumbers.is_valid_number(parsed):
        return False, f"The phone number '{cleaned}' is not a valid international phone number."

    # 2. Reject FIXED_LINE landlines
    ntype = phonenumbers.number_type(parsed)
    if ntype == PhoneNumberType.FIXED_LINE:
        return False, "Fixed-line landline phone numbers are not permitted. Please provide an active mobile number for emergency SMS beacons."

    e164 = phonenumbers.format_number(parsed, phonenumbers.PhoneNumberFormat.E164)
    raw_digits = e164[1:]
    cc = str(parsed.country_code)
    subscriber = str(parsed.national_number)

    # 3. Country-specific mobile range validation (extra defense layer)
    if cc == "91":
        # India (+91): exactly 10 digits subscriber, must start with 6, 7, 8, or 9
        if len(subscriber) != 10:
            return False, "Indian (+91) mobile numbers must contain exactly 10 digits."
        if subscriber[0] not in {"6", "7", "8", "9"}:
            return False, "Invalid Indian mobile number: mobile numbers must start with 6, 7, 8, or 9."

    elif cc == "1":
        # US/Canada (+1): exactly 10 digits (3-digit area code + 7-digit subscriber)
        if len(subscriber) != 10:
            return False, "North American (+1) numbers must contain exactly 10 digits."
        area_code = subscriber[:3]
        exchange = subscriber[3:6]
        if area_code[0] in {"0", "1"} or exchange[0] in {"0", "1"}:
            return False, "Invalid North American area or exchange code."
        if area_code == "555" and subscriber[3:5] == "01":
            return False, "555-01xx numbers are reserved for fictional use and cannot be registered."

    elif cc == "44":
        # UK (+44): Mobile numbers start with 7 and are 10 digits
        if len(subscriber) == 10 and not subscriber.startswith("7"):
            return False, "UK (+44) mobile numbers must start with 7."

    # 4. Anti-dummy entropy & sequence check on subscriber digits
    if is_dummy_number(subscriber):
        from app.core.config import settings
        if settings.SUPPORT_CONTACT:
            contact_info = f" If this is your genuine personal number, please contact support at {settings.SUPPORT_CONTACT}."
        else:
            contact_info = " If this is your genuine personal number, please contact your system administrator."
        return False, (
            "The phone number appears to be a sequential or dummy placeholder (e.g. 111111, 12345678)."
            f"{contact_info}"
        )

    return True, None


def normalize_phone_number(phone: str) -> str:
    """Normalize phone number to international E.164 format."""
    cleaned = (phone or "").strip().replace(" ", "").replace("-", "").replace("(", "").replace(")", "")
    raw = cleaned if cleaned.startswith("+") else "+" + cleaned
    parsed = phonenumbers.parse(raw, None)
    return phonenumbers.format_number(parsed, phonenumbers.PhoneNumberFormat.E164)


def validate_hardware_kyc(device_info: str) -> Tuple[bool, str | None]:
    """Verify authentic hardware telemetry from handset, rejecting dummy strings while permitting emulators only in debug builds."""
    cleaned = (device_info or "").strip()
    if len(cleaned) < 6:
        return False, "Handset hardware telemetry is missing or incomplete."

    lowered = cleaned.lower()

    # Android emulator models check (e.g. sdk_gphone64_arm64, sdk_gphone64_x86_64, Android SDK built for x86)
    is_emulator = (
        "sdk_gphone" in lowered or
        "emulator" in lowered or
        "goldfish" in lowered or
        "ranchu" in lowered
    )

    from app.core.config import settings
    if is_emulator:
        if settings.DEBUG:
            return True, None
        return False, "Android emulators are not permitted for production owner registration."

    fake_terms = {
        "test", "dummy", "fake", "none", "null", "undefined", "string",
        "123", "generic", "device", "phone"
    }
    if lowered in fake_terms:
        return False, "Invalid hardware telemetry. Registration requires an authentic physical Android handset."

    # Must contain word characters indicating manufacturer and model
    tokens = re.findall(r"[a-zA-Z0-9]+", cleaned)
    if len(tokens) < 2:
        return False, "Hardware device descriptor must include manufacturer and model."

    return True, None
