package dev.jadxd.server

import dev.jadxd.core.Session
import dev.jadxd.model.*
import kotlinx.serialization.json.*

private val json = Json {
    prettyPrint = false
    encodeDefaults = true
    ignoreUnknownKeys = true
}

// ── Helper to build JSON Schema objects concisely ──────────────────────────

private fun schema(block: MutableMap<String, JsonElement>.() -> Unit): JsonObject {
    val map = mutableMapOf<String, JsonElement>()
    map["type"] = JsonPrimitive("object")
    map.block()
    return JsonObject(map)
}

private fun emptySchema(): JsonObject = schema {
    put("properties", JsonObject(emptyMap()))
}

private fun stringProp(description: String): JsonObject = JsonObject(mapOf(
    "type" to JsonPrimitive("string"),
    "description" to JsonPrimitive(description),
))

private fun intProp(description: String, default: Int? = null): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
    if (default != null) put("default", default)
}

private fun boolProp(description: String, default: Boolean? = null): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
    if (default != null) put("default", default)
}

// ── Schema definitions for all 24 methods ──────────────────────────────────

val JADX_SCHEMA: Map<String, MethodSchema> = mapOf(

    // ── Type-level ─────────────────────────────────────────────────────

    "list_types" to MethodSchema(
        description = "List all types (classes, interfaces, enums, annotations) in the artifact",
        inputSchema = emptySchema(),
    ),

    "list_methods" to MethodSchema(
        description = "List methods of a type (summary: id, name, access flags)",
        inputSchema = schema {
            put("properties", JsonObject(mapOf("type_id" to stringProp("Dalvik type descriptor"))))
            put("required", JsonArray(listOf(JsonPrimitive("type_id"))))
        },
    ),

    "list_methods_detail" to MethodSchema(
        description = "List methods with full signature info (arguments, return type, throws, generics)",
        inputSchema = schema {
            put("properties", JsonObject(mapOf("type_id" to stringProp("Dalvik type descriptor"))))
            put("required", JsonArray(listOf(JsonPrimitive("type_id"))))
        },
    ),

    "list_fields" to MethodSchema(
        description = "List fields of a type with type information",
        inputSchema = schema {
            put("properties", JsonObject(mapOf("type_id" to stringProp("Dalvik type descriptor"))))
            put("required", JsonArray(listOf(JsonPrimitive("type_id"))))
        },
    ),

    // ── Decompilation ──────────────────────────────────────────────────

    "decompile_method" to MethodSchema(
        description = "Decompile a method (returns both Java source and smali disassembly)",
        inputSchema = schema {
            put("properties", JsonObject(mapOf("method_id" to stringProp("Dalvik method descriptor"))))
            put("required", JsonArray(listOf(JsonPrimitive("method_id"))))
        },
    ),

    "decompile_class" to MethodSchema(
        description = "Decompile an entire class to Java source",
        inputSchema = schema {
            put("properties", JsonObject(mapOf("type_id" to stringProp("Dalvik type descriptor"))))
            put("required", JsonArray(listOf(JsonPrimitive("type_id"))))
        },
    ),

    // ── Class hierarchy ────────────────────────────────────────────────

    "get_hierarchy" to MethodSchema(
        description = "Get class hierarchy: superclass, interfaces, inner classes, generics, access flags",
        inputSchema = schema {
            put("properties", JsonObject(mapOf("type_id" to stringProp("Dalvik type descriptor"))))
            put("required", JsonArray(listOf(JsonPrimitive("type_id"))))
        },
    ),

    // ── Cross-references ───────────────────────────────────────────────

    "xrefs_to" to MethodSchema(
        description = "Find callers of a method (who calls this method?)",
        inputSchema = schema {
            put("properties", JsonObject(mapOf("method_id" to stringProp("Dalvik method descriptor"))))
            put("required", JsonArray(listOf(JsonPrimitive("method_id"))))
        },
    ),

    "xrefs_from" to MethodSchema(
        description = "Find callees of a method (what does this method call?)",
        inputSchema = schema {
            put("properties", JsonObject(mapOf("method_id" to stringProp("Dalvik method descriptor"))))
            put("required", JsonArray(listOf(JsonPrimitive("method_id"))))
        },
    ),

    "field_xrefs" to MethodSchema(
        description = "Find references to a field (who reads/writes it?)",
        inputSchema = schema {
            put("properties", JsonObject(mapOf("field_id" to stringProp("Dalvik field descriptor"))))
            put("required", JsonArray(listOf(JsonPrimitive("field_id"))))
        },
    ),

    "class_xrefs" to MethodSchema(
        description = "Find references to a class (who uses this type?)",
        inputSchema = schema {
            put("properties", JsonObject(mapOf("type_id" to stringProp("Dalvik type descriptor"))))
            put("required", JsonArray(listOf(JsonPrimitive("type_id"))))
        },
    ),

    "override_graph" to MethodSchema(
        description = "Get the override/implementation chain for a method",
        inputSchema = schema {
            put("properties", JsonObject(mapOf("method_id" to stringProp("Dalvik method descriptor"))))
            put("required", JsonArray(listOf(JsonPrimitive("method_id"))))
        },
    ),

    "unresolved_refs" to MethodSchema(
        description = "Get unresolved external references from a method (calls to libraries/framework)",
        inputSchema = schema {
            put("properties", JsonObject(mapOf("method_id" to stringProp("Dalvik method descriptor"))))
            put("required", JsonArray(listOf(JsonPrimitive("method_id"))))
        },
    ),

    // ── String search ──────────────────────────────────────────────────

    "search_strings" to MethodSchema(
        description = "Search for strings in decompiled source (literal or regex)",
        inputSchema = schema {
            put("properties", JsonObject(mapOf(
                "query" to stringProp("Search query string"),
                "regex" to boolProp("Whether query is a regex pattern", default = false),
                "limit" to intProp("Maximum number of matches to return", default = 200),
            )))
            put("required", JsonArray(listOf(JsonPrimitive("query"))))
        },
    ),

    // ── Manifest & resources ───────────────────────────────────────────

    "get_manifest" to MethodSchema(
        description = "Get the decoded AndroidManifest.xml (APK only)",
        inputSchema = emptySchema(),
    ),

    "list_resources" to MethodSchema(
        description = "List resource files in the artifact",
        inputSchema = emptySchema(),
    ),

    "get_resource_content" to MethodSchema(
        description = "Fetch the content of a specific resource file",
        inputSchema = schema {
            put("properties", JsonObject(mapOf("name" to stringProp("Resource file name"))))
            put("required", JsonArray(listOf(JsonPrimitive("name"))))
        },
    ),

    // ── Annotations ────────────────────────────────────────────────────

    "get_annotations" to MethodSchema(
        description = "Get Java annotations on a type, method, or field (supply exactly one ID)",
        inputSchema = schema {
            put("properties", JsonObject(mapOf(
                "type_id" to stringProp("Dalvik type descriptor"),
                "method_id" to stringProp("Dalvik method descriptor"),
                "field_id" to stringProp("Dalvik field descriptor"),
            )))
        },
    ),

    // ── Dependencies & packages ────────────────────────────────────────

    "get_dependencies" to MethodSchema(
        description = "Get the type dependency graph (what types does this class depend on?)",
        inputSchema = schema {
            put("properties", JsonObject(mapOf("type_id" to stringProp("Dalvik type descriptor"))))
            put("required", JsonArray(listOf(JsonPrimitive("type_id"))))
        },
    ),

    "list_packages" to MethodSchema(
        description = "Get the package tree with class IDs per package",
        inputSchema = emptySchema(),
    ),

    // ── Error report ───────────────────────────────────────────────────

    "error_report" to MethodSchema(
        description = "Get decompilation error/warning counts",
        inputSchema = emptySchema(),
    ),

    // ── Rename operations (write) ──────────────────────────────────────

    "rename" to MethodSchema(
        description = "Set a rename alias for a type, method, or field",
        inputSchema = schema {
            put("properties", JsonObject(mapOf(
                "id" to stringProp("Dalvik descriptor of the entity to rename"),
                "alias" to stringProp("New display name"),
            )))
            put("required", JsonArray(listOf(JsonPrimitive("id"), JsonPrimitive("alias"))))
        },
        isWrite = true,
    ),

    "remove_rename" to MethodSchema(
        description = "Remove a rename alias",
        inputSchema = schema {
            put("properties", JsonObject(mapOf("id" to stringProp("Dalvik descriptor"))))
            put("required", JsonArray(listOf(JsonPrimitive("id"))))
        },
        isWrite = true,
    ),

    "list_renames" to MethodSchema(
        description = "List all active rename aliases for the session",
        inputSchema = emptySchema(),
    ),
)

// ── Arg extraction helpers ─────────────────────────────────────────────────

private fun JsonObject.str(key: String): String =
    this[key]?.jsonPrimitive?.content
        ?: throw JadxdException("missing required arg: $key", "BAD_REQUEST")

private fun JsonObject.strOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.bool(key: String, default: Boolean): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: default

private fun JsonObject.int(key: String, default: Int): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: default

// ── Dispatch: method name + args → ResultEnvelope ──────────────────────────

fun dispatchQuery(session: Session, method: String, args: JsonObject): ResultEnvelope {
    val backend = session.backend

    return try {
        when (method) {
            "list_types" -> {
                val data = TypeListResponse(
                    sessionId = session.id,
                    types = backend.listTypes(),
                    provenance = backend.provenance(),
                )
                envelope(method, args, json.encodeToJsonElement(TypeListResponse.serializer(), data))
            }

            "list_methods" -> {
                val typeId = args.str("type_id")
                val data = MethodListResponse(
                    sessionId = session.id,
                    typeId = typeId,
                    methods = backend.listMethods(typeId),
                    provenance = backend.provenance(),
                )
                envelope(method, args, json.encodeToJsonElement(MethodListResponse.serializer(), data))
            }

            "list_methods_detail" -> {
                val typeId = args.str("type_id")
                val data = MethodDetailResponse(
                    sessionId = session.id,
                    typeId = typeId,
                    methods = backend.listMethodDetails(typeId),
                    provenance = backend.provenance(),
                )
                envelope(method, args, json.encodeToJsonElement(MethodDetailResponse.serializer(), data))
            }

            "list_fields" -> {
                val typeId = args.str("type_id")
                val data = FieldListResponse(
                    sessionId = session.id,
                    typeId = typeId,
                    fields = backend.listFields(typeId),
                    provenance = backend.provenance(),
                )
                envelope(method, args, json.encodeToJsonElement(FieldListResponse.serializer(), data))
            }

            "decompile_method" -> {
                val methodId = args.str("method_id")
                val result = backend.decompileMethod(methodId)
                val data = DecompiledMethodResponse(
                    id = methodId,
                    java = result.java,
                    smali = result.smali,
                    provenance = backend.provenance(),
                    warnings = result.warnings,
                )
                envelope(method, args, json.encodeToJsonElement(DecompiledMethodResponse.serializer(), data), data.warnings)
            }

            "decompile_class" -> {
                val typeId = args.str("type_id")
                val (java, warnings) = backend.decompileClass(typeId)
                val data = ClassDecompileResponse(
                    sessionId = session.id,
                    typeId = typeId,
                    java = java,
                    provenance = backend.provenance(),
                    warnings = warnings,
                )
                envelope(method, args, json.encodeToJsonElement(ClassDecompileResponse.serializer(), data), warnings)
            }

            "get_hierarchy" -> {
                val typeId = args.str("type_id")
                val info = backend.getClassHierarchy(typeId)
                val data = ClassHierarchyResponse(
                    sessionId = session.id,
                    typeId = typeId,
                    superClass = info.superClass,
                    interfaces = info.interfaces,
                    innerClasses = info.innerClasses,
                    accessFlags = info.accessFlags,
                    genericParameters = info.genericParameters,
                    genericSuperClass = info.genericSuperClass,
                    genericInterfaces = info.genericInterfaces,
                    provenance = backend.provenance(),
                )
                envelope(method, args, json.encodeToJsonElement(ClassHierarchyResponse.serializer(), data))
            }

            "xrefs_to" -> {
                val methodId = args.str("method_id")
                val (refs, warnings) = backend.xrefsTo(methodId)
                val data = XrefResponse(
                    id = methodId, direction = "to", refs = refs,
                    provenance = backend.provenance(), warnings = warnings,
                )
                envelope(method, args, json.encodeToJsonElement(XrefResponse.serializer(), data), warnings)
            }

            "xrefs_from" -> {
                val methodId = args.str("method_id")
                val (refs, warnings) = backend.xrefsFrom(methodId)
                val data = XrefResponse(
                    id = methodId, direction = "from", refs = refs,
                    provenance = backend.provenance(), warnings = warnings,
                )
                envelope(method, args, json.encodeToJsonElement(XrefResponse.serializer(), data), warnings)
            }

            "field_xrefs" -> {
                val fieldId = args.str("field_id")
                val (refs, warnings) = backend.fieldXrefs(fieldId)
                val data = XrefResponse(
                    id = fieldId, direction = "to", refs = refs,
                    provenance = backend.provenance(), warnings = warnings,
                )
                envelope(method, args, json.encodeToJsonElement(XrefResponse.serializer(), data), warnings)
            }

            "class_xrefs" -> {
                val typeId = args.str("type_id")
                val (refs, warnings) = backend.classXrefs(typeId)
                val data = XrefResponse(
                    id = typeId, direction = "to", refs = refs,
                    provenance = backend.provenance(), warnings = warnings,
                )
                envelope(method, args, json.encodeToJsonElement(XrefResponse.serializer(), data), warnings)
            }

            "override_graph" -> {
                val methodId = args.str("method_id")
                val (overrides, warnings) = backend.overrideGraph(methodId)
                val data = OverrideResponse(
                    id = methodId, overrides = overrides,
                    provenance = backend.provenance(), warnings = warnings,
                )
                envelope(method, args, json.encodeToJsonElement(OverrideResponse.serializer(), data), warnings)
            }

            "unresolved_refs" -> {
                val methodId = args.str("method_id")
                val (refs, warnings) = backend.unresolvedRefs(methodId)
                val data = UnresolvedRefsResponse(
                    id = methodId, refs = refs,
                    provenance = backend.provenance(), warnings = warnings,
                )
                envelope(method, args, json.encodeToJsonElement(UnresolvedRefsResponse.serializer(), data), warnings)
            }

            "search_strings" -> {
                val query = args.str("query")
                val regex = args.bool("regex", false)
                val limit = args.int("limit", 200)
                val (matches, warnings) = backend.searchStrings(query, regex, limit)
                val data = StringSearchResponse(
                    sessionId = session.id, query = query, isRegex = regex,
                    matches = matches, totalCount = matches.size,
                    provenance = backend.provenance(), warnings = warnings,
                )
                envelope(method, args, json.encodeToJsonElement(StringSearchResponse.serializer(), data), warnings)
            }

            "get_manifest" -> {
                val text = backend.getManifest()
                val data = ManifestResponse(
                    sessionId = session.id, text = text,
                    provenance = backend.provenance(),
                )
                envelope(method, args, json.encodeToJsonElement(ManifestResponse.serializer(), data))
            }

            "list_resources" -> {
                val data = ResourceListResponse(
                    sessionId = session.id,
                    resources = backend.listResources(),
                    provenance = backend.provenance(),
                )
                envelope(method, args, json.encodeToJsonElement(ResourceListResponse.serializer(), data))
            }

            "get_resource_content" -> {
                val name = args.str("name")
                val result = backend.getResourceContent(name)
                val data = ResourceContentResponse(
                    sessionId = session.id, name = name,
                    dataType = result.dataType, text = result.text,
                    provenance = backend.provenance(), warnings = result.warnings,
                )
                envelope(method, args, json.encodeToJsonElement(ResourceContentResponse.serializer(), data), result.warnings)
            }

            "get_annotations" -> {
                val typeId = args.strOrNull("type_id")
                val methodId = args.strOrNull("method_id")
                val fieldId = args.strOrNull("field_id")
                val result = backend.getAnnotations(typeId, methodId, fieldId)
                val id = typeId ?: methodId ?: fieldId ?: ""
                val kind = when {
                    typeId != null -> "type"
                    methodId != null -> "method"
                    else -> "field"
                }
                val data = AnnotationResponse(
                    id = id, kind = kind,
                    annotations = result.annotations,
                    parameterAnnotations = result.parameterAnnotations,
                    provenance = backend.provenance(), warnings = result.warnings,
                )
                envelope(method, args, json.encodeToJsonElement(AnnotationResponse.serializer(), data), result.warnings)
            }

            "get_dependencies" -> {
                val typeId = args.str("type_id")
                val (deps, total, warnings) = backend.getDependencies(typeId)
                val data = DependencyResponse(
                    sessionId = session.id, typeId = typeId,
                    dependencies = deps, totalDepsCount = total,
                    provenance = backend.provenance(), warnings = warnings,
                )
                envelope(method, args, json.encodeToJsonElement(DependencyResponse.serializer(), data), warnings)
            }

            "list_packages" -> {
                val (packages, warnings) = backend.listPackages()
                val data = PackageListResponse(
                    sessionId = session.id, packages = packages,
                    totalPackages = packages.size,
                    provenance = backend.provenance(), warnings = warnings,
                )
                envelope(method, args, json.encodeToJsonElement(PackageListResponse.serializer(), data), warnings)
            }

            "error_report" -> {
                val (errors, warns) = backend.errorReport()
                val data = ErrorReportResponse(
                    sessionId = session.id, errorsCount = errors, warningsCount = warns,
                    provenance = backend.provenance(),
                )
                envelope(method, args, json.encodeToJsonElement(ErrorReportResponse.serializer(), data))
            }

            // ── Rename operations ──────────────────────────────────────

            "rename" -> {
                val id = args.str("id")
                val alias = args.str("alias")
                val kind = guessEntityKind(id)
                session.renameStore.rename(id, kind, alias)
                val data = RenameResponse(id = id, alias = alias, status = "renamed")
                envelope(method, args, json.encodeToJsonElement(RenameResponse.serializer(), data))
            }

            "remove_rename" -> {
                val id = args.str("id")
                val removed = session.renameStore.removeAlias(id)
                val data = RenameResponse(id = id, alias = "", status = if (removed) "removed" else "not_found")
                envelope(method, args, json.encodeToJsonElement(RenameResponse.serializer(), data))
            }

            "list_renames" -> {
                val data = RenameListResponse(
                    sessionId = session.id,
                    renames = session.renameStore.listAll(),
                )
                envelope(method, args, json.encodeToJsonElement(RenameListResponse.serializer(), data))
            }

            else -> ResultEnvelope(
                ok = false,
                query = method,
                args = args,
                error = "Unknown method: $method. Available: ${JADX_SCHEMA.keys.sorted()}",
            )
        }
    } catch (e: JadxdException) {
        ResultEnvelope(
            ok = false,
            query = method,
            args = args,
            error = "${e.errorCode}: ${e.message}",
        )
    }
}

private fun envelope(
    query: String,
    args: JsonObject,
    data: JsonElement,
    warnings: List<String> = emptyList(),
) = ResultEnvelope(ok = true, query = query, args = args, data = data, warnings = warnings)

/** Infer entity kind from a Dalvik descriptor for the rename store. */
private fun guessEntityKind(id: String): String = when {
    id.contains("->") && id.contains("(") -> "method"
    id.contains("->") && id.contains(":") -> "field"
    id.startsWith("L") && id.endsWith(";") -> "type"
    else -> "unknown"
}
