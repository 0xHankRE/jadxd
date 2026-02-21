package dev.jadxd.server

import dev.jadxd.core.SessionManager
import dev.jadxd.model.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val routeLog = LoggerFactory.getLogger("dev.jadxd.server")

fun Application.configureRoutes(sessionManager: SessionManager) {

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        })
    }

    install(StatusPages) {
        exception<JadxdException> { call, cause ->
            val status = when (cause.errorCode) {
                "SESSION_NOT_FOUND" -> HttpStatusCode.NotFound
                "TYPE_NOT_FOUND" -> HttpStatusCode.NotFound
                "METHOD_NOT_FOUND" -> HttpStatusCode.NotFound
                "FIELD_NOT_FOUND" -> HttpStatusCode.NotFound
                "MANIFEST_UNAVAILABLE" -> HttpStatusCode.NotFound
                "RESOURCE_NOT_FOUND" -> HttpStatusCode.NotFound
                "INVALID_DESCRIPTOR" -> HttpStatusCode.BadRequest
                "INVALID_REGEX" -> HttpStatusCode.BadRequest
                "LOAD_FAILED" -> HttpStatusCode.UnprocessableEntity
                "RESOURCE_LOAD_FAILED" -> HttpStatusCode.UnprocessableEntity
                else -> HttpStatusCode.InternalServerError
            }
            routeLog.warn("{}: {}", cause.errorCode, cause.message)
            call.respond(status, ErrorResponse(
                ErrorDetail(
                    errorCode = cause.errorCode,
                    message = cause.message,
                    details = cause.details,
                )
            ))
        }
        exception<Throwable> { call, cause ->
            routeLog.error("unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(
                ErrorDetail(
                    errorCode = "INTERNAL_ERROR",
                    message = cause.message ?: "unknown error",
                )
            ))
        }
    }

    routing {
        route("/v1") {

            // Health check
            get("/health") {
                call.respond(HealthResponse(activeSessions = sessionManager.activeSessions()))
            }

            // ── Load artifact ───────────────────────────────────────────

            post("/load") {
                val req = call.receive<LoadRequest>()
                val session = sessionManager.load(req.path, req.settings)
                val backend = session.backend

                call.respond(HttpStatusCode.Created, LoadResponse(
                    sessionId = session.id,
                    artifactHash = backend.artifactHash,
                    inputType = backend.inputType,
                    classCount = backend.classCount(),
                    provenance = backend.provenance(),
                ))
            }

            // ── Session-scoped routes ───────────────────────────────────

            route("/sessions/{session_id}") {

                // List types
                post("/types") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    call.respond(TypeListResponse(
                        sessionId = session.id,
                        types = backend.listTypes(),
                        provenance = backend.provenance(),
                    ))
                }

                // List methods for a type
                post("/methods") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    val req = call.receive<TypeIdRequest>()
                    call.respond(MethodListResponse(
                        sessionId = session.id,
                        typeId = req.typeId,
                        methods = backend.listMethods(req.typeId),
                        provenance = backend.provenance(),
                    ))
                }

                // Decompile method
                post("/decompile") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    val req = call.receive<MethodIdRequest>()
                    val result = backend.decompileMethod(req.methodId)
                    call.respond(DecompiledMethodResponse(
                        id = req.methodId,
                        java = result.java,
                        smali = result.smali,
                        provenance = backend.provenance(),
                        warnings = result.warnings,
                    ))
                }

                // Xrefs to (callers)
                post("/xrefs/to") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    val req = call.receive<MethodIdRequest>()
                    val (refs, warnings) = backend.xrefsTo(req.methodId)
                    call.respond(XrefResponse(
                        id = req.methodId,
                        direction = "to",
                        refs = refs,
                        provenance = backend.provenance(),
                        warnings = warnings,
                    ))
                }

                // Xrefs from (callees)
                post("/xrefs/from") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    val req = call.receive<MethodIdRequest>()
                    val (refs, warnings) = backend.xrefsFrom(req.methodId)
                    call.respond(XrefResponse(
                        id = req.methodId,
                        direction = "from",
                        refs = refs,
                        provenance = backend.provenance(),
                        warnings = warnings,
                    ))
                }

                // String search
                post("/strings") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    val req = call.receive<StringSearchRequest>()
                    val (matches, warnings) = backend.searchStrings(
                        req.query, req.regex, req.limit,
                    )
                    call.respond(StringSearchResponse(
                        sessionId = session.id,
                        query = req.query,
                        isRegex = req.regex,
                        matches = matches,
                        totalCount = matches.size,
                        provenance = backend.provenance(),
                        warnings = warnings,
                    ))
                }

                // Manifest
                post("/manifest") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    call.respond(ManifestResponse(
                        sessionId = session.id,
                        text = backend.getManifest(),
                        provenance = backend.provenance(),
                    ))
                }

                // Resources
                post("/resources") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    call.respond(ResourceListResponse(
                        sessionId = session.id,
                        resources = backend.listResources(),
                        provenance = backend.provenance(),
                    ))
                }

                // Resource content
                post("/resources/content") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    val req = call.receive<ResourceContentRequest>()
                    val result = backend.getResourceContent(req.name)
                    call.respond(ResourceContentResponse(
                        sessionId = session.id,
                        name = req.name,
                        dataType = result.dataType,
                        text = result.text,
                        provenance = backend.provenance(),
                        warnings = result.warnings,
                    ))
                }

                // List fields
                post("/fields") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    val req = call.receive<TypeIdRequest>()
                    call.respond(FieldListResponse(
                        sessionId = session.id,
                        typeId = req.typeId,
                        fields = backend.listFields(req.typeId),
                        provenance = backend.provenance(),
                    ))
                }

                // Decompile full class
                post("/decompile/class") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    val req = call.receive<TypeIdRequest>()
                    val (java, warnings) = backend.decompileClass(req.typeId)
                    call.respond(ClassDecompileResponse(
                        sessionId = session.id,
                        typeId = req.typeId,
                        java = java,
                        provenance = backend.provenance(),
                        warnings = warnings,
                    ))
                }

                // Class hierarchy
                post("/hierarchy") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    val req = call.receive<TypeIdRequest>()
                    val info = backend.getClassHierarchy(req.typeId)
                    call.respond(ClassHierarchyResponse(
                        sessionId = session.id,
                        typeId = req.typeId,
                        superClass = info.superClass,
                        interfaces = info.interfaces,
                        innerClasses = info.innerClasses,
                        accessFlags = info.accessFlags,
                        genericParameters = info.genericParameters,
                        genericSuperClass = info.genericSuperClass,
                        genericInterfaces = info.genericInterfaces,
                        provenance = backend.provenance(),
                    ))
                }

                // Enriched method details (with types)
                post("/methods/detail") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    val req = call.receive<TypeIdRequest>()
                    call.respond(MethodDetailResponse(
                        sessionId = session.id,
                        typeId = req.typeId,
                        methods = backend.listMethodDetails(req.typeId),
                        provenance = backend.provenance(),
                    ))
                }

                // Field xrefs
                post("/xrefs/field") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    val req = call.receive<FieldIdRequest>()
                    val (refs, warnings) = backend.fieldXrefs(req.fieldId)
                    call.respond(XrefResponse(
                        id = req.fieldId,
                        direction = "to",
                        refs = refs,
                        provenance = backend.provenance(),
                        warnings = warnings,
                    ))
                }

                // Class xrefs
                post("/xrefs/class") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    val req = call.receive<TypeIdRequest>()
                    val (refs, warnings) = backend.classXrefs(req.typeId)
                    call.respond(XrefResponse(
                        id = req.typeId,
                        direction = "to",
                        refs = refs,
                        provenance = backend.provenance(),
                        warnings = warnings,
                    ))
                }

                // Override graph
                post("/overrides") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    val req = call.receive<MethodIdRequest>()
                    val (overrides, warnings) = backend.overrideGraph(req.methodId)
                    call.respond(OverrideResponse(
                        id = req.methodId,
                        overrides = overrides,
                        provenance = backend.provenance(),
                        warnings = warnings,
                    ))
                }

                // Unresolved external refs
                post("/unresolved") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    val req = call.receive<MethodIdRequest>()
                    val (refs, warnings) = backend.unresolvedRefs(req.methodId)
                    call.respond(UnresolvedRefsResponse(
                        id = req.methodId,
                        refs = refs,
                        provenance = backend.provenance(),
                        warnings = warnings,
                    ))
                }

                // Error report
                post("/errors") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    val (errors, warns) = backend.errorReport()
                    call.respond(ErrorReportResponse(
                        sessionId = session.id,
                        errorsCount = errors,
                        warningsCount = warns,
                        provenance = backend.provenance(),
                    ))
                }

                // ── Annotations ────────────────────────────────────────

                post("/annotations") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    val req = call.receive<AnnotationRequest>()
                    val result = backend.getAnnotations(req.typeId, req.methodId, req.fieldId)
                    val id = req.typeId ?: req.methodId ?: req.fieldId ?: ""
                    val kind = when {
                        req.typeId != null -> "type"
                        req.methodId != null -> "method"
                        else -> "field"
                    }
                    call.respond(AnnotationResponse(
                        id = id,
                        kind = kind,
                        annotations = result.annotations,
                        parameterAnnotations = result.parameterAnnotations,
                        provenance = backend.provenance(),
                        warnings = result.warnings,
                    ))
                }

                // Dependencies
                post("/dependencies") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    val req = call.receive<TypeIdRequest>()
                    val (deps, total, warnings) = backend.getDependencies(req.typeId)
                    call.respond(DependencyResponse(
                        sessionId = session.id,
                        typeId = req.typeId,
                        dependencies = deps,
                        totalDepsCount = total,
                        provenance = backend.provenance(),
                        warnings = warnings,
                    ))
                }

                // Package tree
                post("/packages") {
                    val session = sessionManager.get(sessionId())
                    val backend = session.backend
                    val (packages, warnings) = backend.listPackages()
                    call.respond(PackageListResponse(
                        sessionId = session.id,
                        packages = packages,
                        totalPackages = packages.size,
                        provenance = backend.provenance(),
                        warnings = warnings,
                    ))
                }

                // ── Rename shim ──────────────────────────────────────────

                // Set rename
                post("/rename") {
                    val session = sessionManager.get(sessionId())
                    val req = call.receive<RenameRequest>()
                    val kind = guessEntityKind(req.id)
                    session.renameStore.rename(req.id, kind, req.alias)
                    call.respond(RenameResponse(
                        id = req.id,
                        alias = req.alias,
                        status = "renamed",
                    ))
                }

                // Remove rename
                post("/rename/remove") {
                    val session = sessionManager.get(sessionId())
                    val req = call.receive<RemoveRenameRequest>()
                    val removed = session.renameStore.removeAlias(req.id)
                    call.respond(RenameResponse(
                        id = req.id,
                        alias = "",
                        status = if (removed) "removed" else "not_found",
                    ))
                }

                // List all renames
                post("/renames") {
                    val session = sessionManager.get(sessionId())
                    call.respond(RenameListResponse(
                        sessionId = session.id,
                        renames = session.renameStore.listAll(),
                    ))
                }

                // Close session
                post("/close") {
                    val sid = sessionId()
                    sessionManager.close(sid)
                    call.respond(SessionClosedResponse(sessionId = sid))
                }
            }
        }
    }
}

private fun PipelineContext<Unit, ApplicationCall>.sessionId(): String =
    call.parameters["session_id"] ?: throw JadxdException(
        "missing session_id path parameter", "BAD_REQUEST",
    )

/** Infer entity kind from a Dalvik descriptor for the rename store. */
private fun guessEntityKind(id: String): String = when {
    id.contains("->") && id.contains("(") -> "method"
    id.contains("->") && id.contains(":") -> "field"
    id.startsWith("L") && id.endsWith(";") -> "type"
    else -> "unknown"
}
