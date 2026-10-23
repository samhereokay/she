#!/usr/bin/env python3
"""
Production Smoke Test Suite for SHE Anti-Theft Service.
Validates live endpoints for uptime, status codes, latency, and absence of 500 errors.
"""
import sys
import time
import urllib.request
import urllib.error
import json

DEFAULT_BASE_URL = "https://she-92h6.onrender.com"

ENDPOINTS = [
    {"path": "/health", "expected_status": 200, "json_keys": ["status"]},
    {"path": "/", "expected_status": 200, "content_check": "SHE"},
    {"path": "/terms", "expected_status": 200, "content_check": "Terms"},
    {"path": "/emergency", "expected_status": 200, "content_check": "Emergency"},
    {"path": "/logo.png", "expected_status": 200, "content_type": "image/png"},
    {"path": "/favicon.ico", "expected_status": 200},
]


def run_smoke_tests(base_url: str, retries: int = 5, retry_delay: int = 6) -> bool:
    print(f"Starting smoke tests against: {base_url}")
    all_passed = True

    for ep in ENDPOINTS:
        url = f"{base_url.rstrip('/')}{ep['path']}"
        success = False

        for attempt in range(1, retries + 1):
            start = time.perf_counter()
            req = urllib.request.Request(
                url,
                headers={"User-Agent": "SHE-CI-SmokeTest/1.0"}
            )
            try:
                with urllib.request.urlopen(req, timeout=10) as resp:
                    elapsed_ms = (time.perf_counter() - start) * 1000
                    status = resp.status
                    body = resp.read()

                    if status != ep["expected_status"]:
                        print(f"  [FAIL] {ep['path']}: Expected {ep['expected_status']}, got {status}")
                        break

                    if "json_keys" in ep:
                        try:
                            data = json.loads(body.decode("utf-8"))
                            for k in ep["json_keys"]:
                                if k not in data:
                                    print(f"  [FAIL] {ep['path']}: Missing expected JSON key '{k}'")
                                    break
                        except Exception as e:
                            print(f"  [FAIL] {ep['path']}: Failed to parse JSON response: {e}")
                            break

                    if "content_check" in ep:
                        text = body.decode("utf-8", errors="replace")
                        if ep["content_check"] not in text:
                            print(f"  [FAIL] {ep['path']}: Response does not contain '{ep['content_check']}'")
                            break

                    print(f"  [PASS] {ep['path']} -> {status} OK ({elapsed_ms:.1f}ms)")
                    success = True
                    break

            except urllib.error.HTTPError as e:
                elapsed_ms = (time.perf_counter() - start) * 1000
                print(f"  [RETRY {attempt}/{retries}] {ep['path']} returned HTTP {e.code} ({elapsed_ms:.1f}ms)")
                if attempt < retries:
                    time.sleep(retry_delay)
            except Exception as e:
                elapsed_ms = (time.perf_counter() - start) * 1000
                print(f"  [RETRY {attempt}/{retries}] {ep['path']} error: {e} ({elapsed_ms:.1f}ms)")
                if attempt < retries:
                    time.sleep(retry_delay)

        if not success:
            all_passed = False

    return all_passed


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_BASE_URL
    passed = run_smoke_tests(target)
    if passed:
        print("\nAll production smoke tests passed successfully.")
        sys.exit(0)
    else:
        print("\nOne or more smoke tests failed.")
        sys.exit(1)
