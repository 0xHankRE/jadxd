"""Typed Python client for the jadxd service."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import httpx

from pyjadxd.errors import (
    JadxdConnectionError,
    JadxdError,
    JadxdNotFoundError,
    JadxdSessionError,
)
from pyjadxd.models import (
    AnnotationResult,
    ClassDecompileResult,
    ClassHierarchyResult,
    DecompileSettings,
    DecompiledMethod,
    DependencyResult,
    ErrorReportResult,
    FieldListResult,
    LoadResult,
    ManifestResult,
    MethodDetailResult,
    MethodListResult,
    OverrideResult,
    PackageListResult,
    RenameListResult,
    RenameResult,
    ResourceContentResult,
    ResourceListResult,
    StringSearchResult,
    TypeListResult,
    UnresolvedRefsResult,
    XrefResult,
)

_NOT_FOUND_CODES = {
    "SESSION_NOT_FOUND",
    "TYPE_NOT_FOUND",
    "METHOD_NOT_FOUND",
    "FIELD_NOT_FOUND",
    "MANIFEST_UNAVAILABLE",
    "RESOURCE_NOT_FOUND",
}
_SESSION_CODES = {"SESSION_NOT_FOUND"}


class JadxdClient:
    """Synchronous client for the jadxd decompiler service.

    Usage::

        client = JadxdClient()  # defaults to http://127.0.0.1:8085
        result = client.load("/path/to/app.apk")
        types = client.list_types(result.session_id)
    """

    def __init__(self, base_url: str = "http://127.0.0.1:8085", timeout: float = 300.0):
        self._base = base_url.rstrip("/")
        self._http = httpx.Client(base_url=self._base, timeout=timeout)

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> JadxdClient:
        return self

    def __exit__(self, *exc: Any) -> None:
        self.close()

    # ── API methods ──────────────────────────────────────────────────────

    def health(self) -> dict[str, Any]:
        return self._get("/v1/health")

    def load(
        self,
        path: str | Path,
        settings: DecompileSettings | None = None,
    ) -> LoadResult:
        body: dict[str, Any] = {"path": str(path)}
        if settings is not None:
            body["settings"] = settings.model_dump()
        data = self._post("/v1/load", body)
        return LoadResult.model_validate(data)

    def list_types(self, session_id: str) -> TypeListResult:
        data = self._post(f"/v1/sessions/{session_id}/types", {})
        return TypeListResult.model_validate(data)

    def list_methods(self, session_id: str, type_id: str) -> MethodListResult:
        data = self._post(f"/v1/sessions/{session_id}/methods", {"type_id": type_id})
        return MethodListResult.model_validate(data)

    def list_methods_detail(self, session_id: str, type_id: str) -> MethodDetailResult:
        data = self._post(f"/v1/sessions/{session_id}/methods/detail", {"type_id": type_id})
        return MethodDetailResult.model_validate(data)

    def list_fields(self, session_id: str, type_id: str) -> FieldListResult:
        data = self._post(f"/v1/sessions/{session_id}/fields", {"type_id": type_id})
        return FieldListResult.model_validate(data)

    def decompile_class(self, session_id: str, type_id: str) -> ClassDecompileResult:
        data = self._post(f"/v1/sessions/{session_id}/decompile/class", {"type_id": type_id})
        return ClassDecompileResult.model_validate(data)

    def get_hierarchy(self, session_id: str, type_id: str) -> ClassHierarchyResult:
        data = self._post(f"/v1/sessions/{session_id}/hierarchy", {"type_id": type_id})
        return ClassHierarchyResult.model_validate(data)

    def decompile_method(self, session_id: str, method_id: str) -> DecompiledMethod:
        data = self._post(f"/v1/sessions/{session_id}/decompile", {"method_id": method_id})
        return DecompiledMethod.model_validate(data)

    def xrefs_to(self, session_id: str, method_id: str) -> XrefResult:
        data = self._post(f"/v1/sessions/{session_id}/xrefs/to", {"method_id": method_id})
        return XrefResult.model_validate(data)

    def xrefs_from(self, session_id: str, method_id: str) -> XrefResult:
        data = self._post(f"/v1/sessions/{session_id}/xrefs/from", {"method_id": method_id})
        return XrefResult.model_validate(data)

    def field_xrefs(self, session_id: str, field_id: str) -> XrefResult:
        data = self._post(f"/v1/sessions/{session_id}/xrefs/field", {"field_id": field_id})
        return XrefResult.model_validate(data)

    def class_xrefs(self, session_id: str, type_id: str) -> XrefResult:
        data = self._post(f"/v1/sessions/{session_id}/xrefs/class", {"type_id": type_id})
        return XrefResult.model_validate(data)

    def overrides(self, session_id: str, method_id: str) -> OverrideResult:
        data = self._post(f"/v1/sessions/{session_id}/overrides", {"method_id": method_id})
        return OverrideResult.model_validate(data)

    def unresolved_refs(self, session_id: str, method_id: str) -> UnresolvedRefsResult:
        data = self._post(f"/v1/sessions/{session_id}/unresolved", {"method_id": method_id})
        return UnresolvedRefsResult.model_validate(data)

    def search_strings(
        self,
        session_id: str,
        query: str,
        regex: bool = False,
        limit: int = 200,
    ) -> StringSearchResult:
        data = self._post(
            f"/v1/sessions/{session_id}/strings",
            {"query": query, "regex": regex, "limit": limit},
        )
        return StringSearchResult.model_validate(data)

    def get_manifest(self, session_id: str) -> ManifestResult:
        data = self._post(f"/v1/sessions/{session_id}/manifest", {})
        return ManifestResult.model_validate(data)

    def list_resources(self, session_id: str) -> ResourceListResult:
        data = self._post(f"/v1/sessions/{session_id}/resources", {})
        return ResourceListResult.model_validate(data)

    def get_resource_content(self, session_id: str, name: str) -> ResourceContentResult:
        data = self._post(f"/v1/sessions/{session_id}/resources/content", {"name": name})
        return ResourceContentResult.model_validate(data)

    def rename(self, session_id: str, id: str, alias: str) -> RenameResult:
        data = self._post(f"/v1/sessions/{session_id}/rename", {"id": id, "alias": alias})
        return RenameResult.model_validate(data)

    def remove_rename(self, session_id: str, id: str) -> RenameResult:
        data = self._post(f"/v1/sessions/{session_id}/rename/remove", {"id": id})
        return RenameResult.model_validate(data)

    def list_renames(self, session_id: str) -> RenameListResult:
        data = self._post(f"/v1/sessions/{session_id}/renames", {})
        return RenameListResult.model_validate(data)

    def error_report(self, session_id: str) -> ErrorReportResult:
        data = self._post(f"/v1/sessions/{session_id}/errors", {})
        return ErrorReportResult.model_validate(data)

    def get_annotations(
        self,
        session_id: str,
        *,
        type_id: str | None = None,
        method_id: str | None = None,
        field_id: str | None = None,
    ) -> AnnotationResult:
        body: dict[str, Any] = {}
        if type_id is not None:
            body["type_id"] = type_id
        if method_id is not None:
            body["method_id"] = method_id
        if field_id is not None:
            body["field_id"] = field_id
        data = self._post(f"/v1/sessions/{session_id}/annotations", body)
        return AnnotationResult.model_validate(data)

    def get_dependencies(self, session_id: str, type_id: str) -> DependencyResult:
        data = self._post(f"/v1/sessions/{session_id}/dependencies", {"type_id": type_id})
        return DependencyResult.model_validate(data)

    def list_packages(self, session_id: str) -> PackageListResult:
        data = self._post(f"/v1/sessions/{session_id}/packages", {})
        return PackageListResult.model_validate(data)

    def close_session(self, session_id: str) -> dict[str, Any]:
        return self._post(f"/v1/sessions/{session_id}/close", {})

    # ── Transport ────────────────────────────────────────────────────────

    def _get(self, path: str) -> dict[str, Any]:
        try:
            resp = self._http.get(path)
        except httpx.ConnectError as e:
            raise JadxdConnectionError(f"cannot reach jadxd at {self._base}: {e}") from e
        return self._handle(resp)

    def _post(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        try:
            resp = self._http.post(path, json=body)
        except httpx.ConnectError as e:
            raise JadxdConnectionError(f"cannot reach jadxd at {self._base}: {e}") from e
        return self._handle(resp)

    @staticmethod
    def _handle(resp: httpx.Response) -> dict[str, Any]:
        data = resp.json()
        if resp.is_success:
            return data
        # Structured error
        err = data.get("error", {})
        code = err.get("error_code", "UNKNOWN")
        msg = err.get("message", resp.text)
        details = err.get("details", {})
        if code in _SESSION_CODES:
            raise JadxdSessionError(code, msg, details)
        if code in _NOT_FOUND_CODES:
            raise JadxdNotFoundError(code, msg, details)
        raise JadxdError(code, msg, details)
