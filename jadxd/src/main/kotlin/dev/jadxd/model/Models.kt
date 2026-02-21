package dev.jadxd.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// ── Settings ────────────────────────────────────────────────────────────────

@Serializable
data class DecompileSettings(
    val deobfuscation: Boolean = false,
    @SerialName("inline_methods") val inlineMethods: Boolean = true,
    @SerialName("show_inconsistent_code") val showInconsistentCode: Boolean = true,
)

// ── Provenance (attached to every response) ─────────────────────────────────

@Serializable
data class Provenance(
    val backend: String = "jadx",
    @SerialName("backend_version") val backendVersion: String,
    val settings: DecompileSettings,
)

// ── Error envelope ──────────────────────────────────────────────────────────

@Serializable
data class ErrorDetail(
    @SerialName("error_code") val errorCode: String,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

@Serializable
data class ErrorResponse(val error: ErrorDetail)

// ── Exceptions → mapped to ErrorResponse by StatusPages ─────────────────────

open class JadxdException(
    override val message: String,
    val errorCode: String,
    val details: Map<String, String> = emptyMap(),
) : RuntimeException(message)

class SessionNotFoundException(sessionId: String) :
    JadxdException("Session not found: $sessionId", "SESSION_NOT_FOUND", mapOf("session_id" to sessionId))

class TypeNotFoundException(typeId: String) :
    JadxdException("Type not found: $typeId", "TYPE_NOT_FOUND", mapOf("type_id" to typeId))

class MethodNotFoundException(methodId: String) :
    JadxdException("Method not found: $methodId", "METHOD_NOT_FOUND", mapOf("method_id" to methodId))

class FieldNotFoundException(fieldId: String) :
    JadxdException("Field not found: $fieldId", "FIELD_NOT_FOUND", mapOf("field_id" to fieldId))

class InvalidDescriptorException(descriptor: String, reason: String = "malformed") :
    JadxdException("Invalid descriptor ($reason): $descriptor", "INVALID_DESCRIPTOR", mapOf("descriptor" to descriptor))

class LoadFailedException(path: String, cause: Throwable) :
    JadxdException("Failed to load artifact: $path – ${cause.message}", "LOAD_FAILED", mapOf("path" to path))

class ManifestUnavailableException :
    JadxdException("No AndroidManifest.xml available (input may not be an APK)", "MANIFEST_UNAVAILABLE")

class ResourceNotFoundException(name: String) :
    JadxdException("Resource not found: $name", "RESOURCE_NOT_FOUND", mapOf("name" to name))

class ResourceLoadException(name: String, cause: Throwable) :
    JadxdException("Failed to load resource: $name – ${cause.message}", "RESOURCE_LOAD_FAILED", mapOf("name" to name))

// ── Request bodies ──────────────────────────────────────────────────────────

@Serializable
data class LoadRequest(
    val path: String,
    val settings: DecompileSettings = DecompileSettings(),
)

@Serializable
data class TypeIdRequest(@SerialName("type_id") val typeId: String)

@Serializable
data class MethodIdRequest(@SerialName("method_id") val methodId: String)

@Serializable
data class FieldIdRequest(@SerialName("field_id") val fieldId: String)

@Serializable
data class StringSearchRequest(
    val query: String,
    val regex: Boolean = false,
    val limit: Int = 200,
)

@Serializable
data class ResourceContentRequest(val name: String)

@Serializable
data class RenameRequest(val id: String, val alias: String)

@Serializable
data class RemoveRenameRequest(val id: String)

// ── Response bodies ─────────────────────────────────────────────────────────

@Serializable
data class LoadResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("artifact_hash") val artifactHash: String,
    @SerialName("input_type") val inputType: String,
    @SerialName("class_count") val classCount: Int,
    val provenance: Provenance,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class TypeInfo(
    val id: String,
    val kind: String,
    val name: String,
    @SerialName("package") val packageName: String,
    @SerialName("access_flags") val accessFlags: List<String>,
)

@Serializable
data class TypeListResponse(
    @SerialName("session_id") val sessionId: String,
    val types: List<TypeInfo>,
    val provenance: Provenance,
    val warnings: List<String> = emptyList(),
)

// ── Method models ───────────────────────────────────────────────────────────

@Serializable
data class MethodSummary(
    val id: String,
    val name: String,
    @SerialName("access_flags") val accessFlags: List<String>,
)

@Serializable
data class MethodListResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("type_id") val typeId: String,
    val methods: List<MethodSummary>,
    val provenance: Provenance,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class MethodDetail(
    val id: String,
    val name: String,
    @SerialName("access_flags") val accessFlags: List<String>,
    val arguments: List<String>,
    @SerialName("return_type") val returnType: String,
    @SerialName("is_constructor") val isConstructor: Boolean = false,
    @SerialName("is_class_init") val isClassInit: Boolean = false,
    @SerialName("throws") val throws: List<String> = emptyList(),
    @SerialName("generic_arguments") val genericArguments: List<String> = emptyList(),
    @SerialName("generic_return_type") val genericReturnType: String? = null,
)

@Serializable
data class MethodDetailResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("type_id") val typeId: String,
    val methods: List<MethodDetail>,
    val provenance: Provenance,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class DecompiledMethodResponse(
    val id: String,
    val kind: String = "decompiled_method",
    val java: String? = null,
    val smali: String? = null,
    val locations: Map<String, Int> = emptyMap(),
    val provenance: Provenance,
    val warnings: List<String> = emptyList(),
)

// ── Field models ────────────────────────────────────────────────────────────

@Serializable
data class FieldInfo(
    val id: String,
    val name: String,
    val type: String,
    @SerialName("access_flags") val accessFlags: List<String>,
)

@Serializable
data class FieldListResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("type_id") val typeId: String,
    val fields: List<FieldInfo>,
    val provenance: Provenance,
    val warnings: List<String> = emptyList(),
)

// ── Class hierarchy ─────────────────────────────────────────────────────────

@Serializable
data class ClassHierarchyResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("type_id") val typeId: String,
    @SerialName("super_class") val superClass: String? = null,
    val interfaces: List<String> = emptyList(),
    @SerialName("inner_classes") val innerClasses: List<String> = emptyList(),
    @SerialName("access_flags") val accessFlags: List<String> = emptyList(),
    @SerialName("generic_parameters") val genericParameters: List<String> = emptyList(),
    @SerialName("generic_super_class") val genericSuperClass: String? = null,
    @SerialName("generic_interfaces") val genericInterfaces: List<String> = emptyList(),
    val provenance: Provenance,
    val warnings: List<String> = emptyList(),
)

// ── Class decompile ─────────────────────────────────────────────────────────

@Serializable
data class ClassDecompileResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("type_id") val typeId: String,
    val java: String? = null,
    val provenance: Provenance,
    val warnings: List<String> = emptyList(),
)

// ── Xrefs ───────────────────────────────────────────────────────────────────

@Serializable
data class XrefEntry(
    val id: String,
    val kind: String,
    val name: String,
    @SerialName("declaring_type") val declaringType: String,
)

@Serializable
data class XrefResponse(
    val id: String,
    val kind: String = "xrefs",
    val direction: String,
    val refs: List<XrefEntry>,
    val provenance: Provenance,
    val warnings: List<String> = emptyList(),
)

// ── Override graph ──────────────────────────────────────────────────────────

@Serializable
data class OverrideEntry(
    val id: String,
    val name: String,
    @SerialName("declaring_type") val declaringType: String,
)

@Serializable
data class OverrideResponse(
    val id: String,
    val overrides: List<OverrideEntry>,
    val provenance: Provenance,
    val warnings: List<String> = emptyList(),
)

// ── Unresolved references ───────────────────────────────────────────────────

@Serializable
data class UnresolvedRef(
    @SerialName("parent_class") val parentClass: String,
    @SerialName("arg_types") val argTypes: List<String>,
    @SerialName("return_type") val returnType: String,
)

@Serializable
data class UnresolvedRefsResponse(
    val id: String,
    val refs: List<UnresolvedRef>,
    val provenance: Provenance,
    val warnings: List<String> = emptyList(),
)

// ── String search ───────────────────────────────────────────────────────────

@Serializable
data class StringMatch(
    val value: String,
    val locations: List<StringLocation>,
)

@Serializable
data class StringLocation(
    @SerialName("type_id") val typeId: String,
    @SerialName("method_id") val methodId: String? = null,
)

@Serializable
data class StringSearchResponse(
    @SerialName("session_id") val sessionId: String,
    val query: String,
    @SerialName("is_regex") val isRegex: Boolean,
    val matches: List<StringMatch>,
    @SerialName("total_count") val totalCount: Int,
    val provenance: Provenance,
    val warnings: List<String> = emptyList(),
)

// ── Manifest / Resources ────────────────────────────────────────────────────

@Serializable
data class ManifestResponse(
    @SerialName("session_id") val sessionId: String,
    val kind: String = "manifest",
    val text: String,
    val provenance: Provenance,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class ResourceEntry(
    val name: String,
    val type: String,
    val size: Long? = null,
)

@Serializable
data class ResourceListResponse(
    @SerialName("session_id") val sessionId: String,
    val resources: List<ResourceEntry>,
    val provenance: Provenance,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class ResourceContentResponse(
    @SerialName("session_id") val sessionId: String,
    val name: String,
    @SerialName("data_type") val dataType: String,
    val text: String? = null,
    val provenance: Provenance,
    val warnings: List<String> = emptyList(),
)

// ── Rename shim ─────────────────────────────────────────────────────────────

@Serializable
data class RenameEntry(
    @SerialName("original_id") val originalId: String,
    @SerialName("entity_kind") val entityKind: String,
    val alias: String,
)

@Serializable
data class RenameResponse(
    val id: String,
    val alias: String,
    val status: String,
)

@Serializable
data class RenameListResponse(
    @SerialName("session_id") val sessionId: String,
    val renames: List<RenameEntry>,
)

// ── Error report ────────────────────────────────────────────────────────────

@Serializable
data class ErrorReportResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("errors_count") val errorsCount: Int,
    @SerialName("warnings_count") val warningsCount: Int,
    val provenance: Provenance,
)

// ── Annotations ────────────────────────────────────────────────────────────

@Serializable
data class AnnotationValue(
    val type: String,
    val value: String? = null,
    val values: List<AnnotationValue>? = null,
    val annotation: AnnotationInfo? = null,
)

@Serializable
data class AnnotationInfo(
    @SerialName("annotation_class") val annotationClass: String,
    val visibility: String,
    val values: Map<String, AnnotationValue> = emptyMap(),
)

@Serializable
data class AnnotationRequest(
    @SerialName("type_id") val typeId: String? = null,
    @SerialName("method_id") val methodId: String? = null,
    @SerialName("field_id") val fieldId: String? = null,
)

@Serializable
data class AnnotationResponse(
    val id: String,
    val kind: String,
    val annotations: List<AnnotationInfo>,
    @SerialName("parameter_annotations") val parameterAnnotations: List<List<AnnotationInfo>>? = null,
    val provenance: Provenance,
    val warnings: List<String> = emptyList(),
)

// ── Dependencies ───────────────────────────────────────────────────────────

@Serializable
data class DependencyResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("type_id") val typeId: String,
    val dependencies: List<String>,
    @SerialName("total_deps_count") val totalDepsCount: Int,
    val provenance: Provenance,
    val warnings: List<String> = emptyList(),
)

// ── Packages ───────────────────────────────────────────────────────────────

@Serializable
data class PackageInfo(
    @SerialName("full_name") val fullName: String,
    @SerialName("class_count") val classCount: Int,
    @SerialName("sub_packages") val subPackages: List<String> = emptyList(),
    @SerialName("class_ids") val classIds: List<String> = emptyList(),
    @SerialName("is_leaf") val isLeaf: Boolean = false,
)

@Serializable
data class PackageListResponse(
    @SerialName("session_id") val sessionId: String,
    val packages: List<PackageInfo>,
    @SerialName("total_packages") val totalPackages: Int,
    val provenance: Provenance,
    val warnings: List<String> = emptyList(),
)

// ── Simple utility responses ────────────────────────────────────────────────

@Serializable
data class HealthResponse(
    val status: String = "ok",
    @SerialName("active_sessions") val activeSessions: Int,
)

@Serializable
data class SessionClosedResponse(
    val status: String = "closed",
    @SerialName("session_id") val sessionId: String,
)

// ── Universal Tool Backend Protocol ────────────────────────────────────────

@Serializable
data class ResultEnvelope(
    val ok: Boolean,
    val query: String,
    val args: JsonObject = JsonObject(emptyMap()),
    val data: JsonElement? = null,
    val truncated: Boolean = false,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
)

@Serializable
data class ProtocolQueryRequest(
    @SerialName("target_id") val targetId: String,
    val method: String,
    val args: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class ProtocolLoadRequest(
    val path: String,
    val options: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class BackendInfo(
    val id: String,
    val name: String,
    val version: String,
    @SerialName("target_count") val targetCount: Int,
    @SerialName("method_count") val methodCount: Int,
)

@Serializable
data class TargetInfo(
    @SerialName("target_id") val targetId: String,
    val path: String,
    @SerialName("input_type") val inputType: String,
    @SerialName("class_count") val classCount: Int,
    @SerialName("artifact_hash") val artifactHash: String,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class MethodSchema(
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject,
    @SerialName("is_write") val isWrite: Boolean = false,
)
