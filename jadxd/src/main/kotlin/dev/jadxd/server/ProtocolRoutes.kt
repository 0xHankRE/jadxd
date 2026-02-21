package dev.jadxd.server

import dev.jadxd.core.SessionManager
import dev.jadxd.model.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val protocolLog = LoggerFactory.getLogger("dev.jadxd.protocol")

/** Detect JADX version once without loading an artifact. */
private fun detectJadxVersion(): String = try {
    jadx.core.Jadx.getVersion()
} catch (_: Exception) {
    "unknown"
}

fun Application.configureProtocolRoutes(sessionManager: SessionManager) {
    val jadxVersion = detectJadxVersion()

    routing {

        // ── Protocol health (distinct from /v1/health) ─────────────────

        get("/health") {
            call.respond(buildJsonObject {
                put("status", "ok")
                putJsonArray("backends") { add("jadx") }
            })
        }

        // ── Backend listing ────────────────────────────────────────────

        get("/backends") {
            call.respond(listOf(BackendInfo(
                id = "jadx",
                name = "JADX Decompiler",
                version = jadxVersion,
                targetCount = sessionManager.activeSessions(),
                methodCount = JADX_SCHEMA.size,
            )))
        }

        // ── JADX backend routes ────────────────────────────────────────

        route("/backends/jadx") {

            // Schema: list all available methods
            get("/schema") {
                call.respond(JADX_SCHEMA)
            }

            // List loaded targets (sessions)
            get("/targets") {
                val targets = sessionManager.listSessions().map { session ->
                    TargetInfo(
                        targetId = session.id,
                        path = session.backend.inputFile.absolutePath,
                        inputType = session.backend.inputType,
                        classCount = session.backend.classCount(),
                        artifactHash = session.backend.artifactHash,
                        createdAt = session.createdAt,
                    )
                }
                call.respond(targets)
            }

            // Load a new target
            post("/targets") {
                val req = call.receive<ProtocolLoadRequest>()
                val settings = DecompileSettings()  // use defaults; options could override later
                val session = sessionManager.load(req.path, settings)
                val backend = session.backend

                call.respond(HttpStatusCode.Created, buildJsonObject {
                    put("target_id", session.id)
                    putJsonObject("metadata") {
                        put("artifact_hash", backend.artifactHash)
                        put("input_type", backend.inputType)
                        put("class_count", backend.classCount())
                    }
                })
            }

            // Unload a target
            delete("/targets/{target_id}") {
                val targetId = call.parameters["target_id"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, buildJsonObject {
                        put("error", "missing target_id")
                    })
                try {
                    sessionManager.close(targetId)
                    call.respond(buildJsonObject { put("ok", true) })
                } catch (e: JadxdException) {
                    call.respond(HttpStatusCode.NotFound, buildJsonObject {
                        put("error", e.message)
                    })
                }
            }

            // Execute a query
            post("/query") {
                val req = call.receive<ProtocolQueryRequest>()

                val session = try {
                    sessionManager.get(req.targetId)
                } catch (e: JadxdException) {
                    call.respond(HttpStatusCode.OK, ResultEnvelope(
                        ok = false,
                        query = req.method,
                        args = req.args,
                        error = "${e.errorCode}: ${e.message}",
                    ))
                    return@post
                }

                val result = dispatchQuery(session, req.method, req.args)
                call.respond(HttpStatusCode.OK, result)
            }
        }
    }
}
