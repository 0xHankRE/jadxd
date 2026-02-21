"""Pydantic models mirroring the jadxd JSON API."""

from __future__ import annotations

from pydantic import BaseModel, Field


class DecompileSettings(BaseModel):
    deobfuscation: bool = False
    inline_methods: bool = True
    show_inconsistent_code: bool = True


class Provenance(BaseModel):
    backend: str = "jadx"
    backend_version: str
    settings: DecompileSettings


# ── Load ─────────────────────────────────────────────────────────────────────


class LoadResult(BaseModel):
    session_id: str
    artifact_hash: str
    input_type: str
    class_count: int
    provenance: Provenance
    warnings: list[str] = Field(default_factory=list)


# ── Types ────────────────────────────────────────────────────────────────────


class TypeInfo(BaseModel):
    id: str
    kind: str
    name: str
    package: str = Field(alias="package")
    access_flags: list[str]


class TypeListResult(BaseModel):
    session_id: str
    types: list[TypeInfo]
    provenance: Provenance
    warnings: list[str] = Field(default_factory=list)


# ── Methods ──────────────────────────────────────────────────────────────────


class MethodSummary(BaseModel):
    id: str
    name: str
    access_flags: list[str]


class MethodListResult(BaseModel):
    session_id: str
    type_id: str
    methods: list[MethodSummary]
    provenance: Provenance
    warnings: list[str] = Field(default_factory=list)


class MethodDetail(BaseModel):
    id: str
    name: str
    access_flags: list[str]
    arguments: list[str]
    return_type: str
    is_constructor: bool = False
    is_class_init: bool = False
    throws: list[str] = Field(default_factory=list)
    generic_arguments: list[str] = Field(default_factory=list)
    generic_return_type: str | None = None


class MethodDetailResult(BaseModel):
    session_id: str
    type_id: str
    methods: list[MethodDetail]
    provenance: Provenance
    warnings: list[str] = Field(default_factory=list)


# ── Fields ───────────────────────────────────────────────────────────────────


class FieldInfo(BaseModel):
    id: str
    name: str
    type: str
    access_flags: list[str]


class FieldListResult(BaseModel):
    session_id: str
    type_id: str
    fields: list[FieldInfo]
    provenance: Provenance
    warnings: list[str] = Field(default_factory=list)


# ── Class hierarchy ──────────────────────────────────────────────────────────


class ClassHierarchyResult(BaseModel):
    session_id: str
    type_id: str
    super_class: str | None = None
    interfaces: list[str] = Field(default_factory=list)
    inner_classes: list[str] = Field(default_factory=list)
    access_flags: list[str] = Field(default_factory=list)
    generic_parameters: list[str] = Field(default_factory=list)
    generic_super_class: str | None = None
    generic_interfaces: list[str] = Field(default_factory=list)
    provenance: Provenance
    warnings: list[str] = Field(default_factory=list)


# ── Class decompile ──────────────────────────────────────────────────────────


class ClassDecompileResult(BaseModel):
    session_id: str
    type_id: str
    java: str | None = None
    provenance: Provenance
    warnings: list[str] = Field(default_factory=list)


# ── Decompiled method ────────────────────────────────────────────────────────


class DecompiledMethod(BaseModel):
    id: str
    kind: str = "decompiled_method"
    java: str | None = None
    smali: str | None = None
    locations: dict[str, int] = Field(default_factory=dict)
    provenance: Provenance
    warnings: list[str] = Field(default_factory=list)


# ── Xrefs ────────────────────────────────────────────────────────────────────


class XrefEntry(BaseModel):
    id: str
    kind: str
    name: str
    declaring_type: str


class XrefResult(BaseModel):
    id: str
    kind: str = "xrefs"
    direction: str
    refs: list[XrefEntry]
    provenance: Provenance
    warnings: list[str] = Field(default_factory=list)


# ── Override graph ───────────────────────────────────────────────────────────


class OverrideEntry(BaseModel):
    id: str
    name: str
    declaring_type: str


class OverrideResult(BaseModel):
    id: str
    overrides: list[OverrideEntry]
    provenance: Provenance
    warnings: list[str] = Field(default_factory=list)


# ── Unresolved refs ──────────────────────────────────────────────────────────


class UnresolvedRef(BaseModel):
    parent_class: str
    arg_types: list[str]
    return_type: str


class UnresolvedRefsResult(BaseModel):
    id: str
    refs: list[UnresolvedRef]
    provenance: Provenance
    warnings: list[str] = Field(default_factory=list)


# ── Strings ──────────────────────────────────────────────────────────────────


class StringLocation(BaseModel):
    type_id: str
    method_id: str | None = None


class StringMatch(BaseModel):
    value: str
    locations: list[StringLocation]


class StringSearchResult(BaseModel):
    session_id: str
    query: str
    is_regex: bool
    matches: list[StringMatch]
    total_count: int
    provenance: Provenance
    warnings: list[str] = Field(default_factory=list)


# ── Manifest ─────────────────────────────────────────────────────────────────


class ManifestResult(BaseModel):
    session_id: str
    kind: str = "manifest"
    text: str
    provenance: Provenance
    warnings: list[str] = Field(default_factory=list)


# ── Resources ────────────────────────────────────────────────────────────────


class ResourceEntry(BaseModel):
    name: str
    type: str
    size: int | None = None


class ResourceListResult(BaseModel):
    session_id: str
    resources: list[ResourceEntry]
    provenance: Provenance
    warnings: list[str] = Field(default_factory=list)


class ResourceContentResult(BaseModel):
    session_id: str
    name: str
    data_type: str
    text: str | None = None
    provenance: Provenance
    warnings: list[str] = Field(default_factory=list)


# ── Rename shim ──────────────────────────────────────────────────────────────


class RenameEntry(BaseModel):
    original_id: str
    entity_kind: str
    alias: str


class RenameResult(BaseModel):
    id: str
    alias: str
    status: str


class RenameListResult(BaseModel):
    session_id: str
    renames: list[RenameEntry]


# ── Error report ─────────────────────────────────────────────────────────────


class ErrorReportResult(BaseModel):
    session_id: str
    errors_count: int
    warnings_count: int
    provenance: Provenance


# ── Annotations ─────────────────────────────────────────────────────────────


class AnnotationValue(BaseModel):
    type: str
    value: str | None = None
    values: list[AnnotationValue] | None = None
    annotation: AnnotationInfo | None = None


class AnnotationInfo(BaseModel):
    annotation_class: str
    visibility: str
    values: dict[str, AnnotationValue] = Field(default_factory=dict)


AnnotationValue.model_rebuild()
AnnotationInfo.model_rebuild()


class AnnotationResult(BaseModel):
    id: str
    kind: str
    annotations: list[AnnotationInfo]
    parameter_annotations: list[list[AnnotationInfo]] | None = None
    provenance: Provenance
    warnings: list[str] = Field(default_factory=list)


# ── Dependencies ────────────────────────────────────────────────────────────


class DependencyResult(BaseModel):
    session_id: str
    type_id: str
    dependencies: list[str]
    total_deps_count: int
    provenance: Provenance
    warnings: list[str] = Field(default_factory=list)


# ── Packages ────────────────────────────────────────────────────────────────


class PackageInfo(BaseModel):
    full_name: str
    class_count: int
    sub_packages: list[str] = Field(default_factory=list)
    class_ids: list[str] = Field(default_factory=list)
    is_leaf: bool = False


class PackageListResult(BaseModel):
    session_id: str
    packages: list[PackageInfo]
    total_packages: int
    provenance: Provenance
    warnings: list[str] = Field(default_factory=list)
