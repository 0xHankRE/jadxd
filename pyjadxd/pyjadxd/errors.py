"""Error types for the jadxd client."""

from __future__ import annotations


class JadxdError(Exception):
    """Base error from the jadxd service."""

    def __init__(self, error_code: str, message: str, details: dict[str, str] | None = None):
        self.error_code = error_code
        self.message = message
        self.details = details or {}
        super().__init__(f"[{error_code}] {message}")


class JadxdNotFoundError(JadxdError):
    """Raised when a type, method, or resource is not found."""


class JadxdSessionError(JadxdError):
    """Raised when a session is not found or has expired."""


class JadxdConnectionError(JadxdError):
    """Raised when the service is unreachable."""

    def __init__(self, message: str):
        super().__init__("CONNECTION_ERROR", message)
