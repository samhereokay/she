import time
from typing import Dict, Tuple
from fastapi import Request, HTTPException, status

class SimpleRateLimiter:
    """
    In-memory sliding window rate limiter.
    Keyed by client IP or client ID.

    KNOWN LIMITATION:
    This rate limiter maintains state purely in-process memory. It resets
    upon server restart or process reload and does not synchronize across
    multiple uvicorn worker processes or cluster nodes. For production
    multi-worker deployments, replace or back with a distributed Redis store.
    """
    def __init__(self, requests_per_minute: int = 60):
        self.requests_per_minute = requests_per_minute
        # client_key -> list of timestamp floats
        self.history: Dict[str, list] = {}

    def check_rate_limit(self, request: Request, key_override: str = None):
        client_key = key_override or request.client.host if request.client else "unknown"
        now = time.time()
        window_start = now - 60.0

        if client_key not in self.history:
            self.history[client_key] = []

        # Filter out timestamps older than 60 seconds
        self.history[client_key] = [t for t in self.history[client_key] if t > window_start]

        if len(self.history[client_key]) >= self.requests_per_minute:
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail="Rate limit exceeded. Please retry in 60 seconds."
            )

        self.history[client_key].append(now)

# Limiters for different sensitivity levels
auth_limiter = SimpleRateLimiter(requests_per_minute=20)
totp_limiter = SimpleRateLimiter(requests_per_minute=5)
batch_ingest_limiter = SimpleRateLimiter(requests_per_minute=120)
