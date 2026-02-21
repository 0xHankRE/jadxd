package dev.jadxd.core

import dev.jadxd.model.DecompileSettings
import dev.jadxd.model.SessionNotFoundException
import dev.jadxd.model.LoadFailedException
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Session(
    val id: String,
    val backend: JadxBackend,
    val renameStore: RenameStore,
    val createdAt: Long = System.currentTimeMillis(),
    @Volatile var lastAccessedAt: Long = System.currentTimeMillis(),
)

class SessionManager(private val cacheDir: Path) {

    private val log = LoggerFactory.getLogger(SessionManager::class.java)
    private val sessions = ConcurrentHashMap<String, Session>()
    private val cache = Cache(cacheDir)

    fun load(path: String, settings: DecompileSettings): Session {
        val file = File(path)
        if (!file.exists()) throw LoadFailedException(path, IllegalArgumentException("file not found"))
        if (!file.canRead()) throw LoadFailedException(path, IllegalArgumentException("file not readable"))

        val hash = Cache.hashFile(file)
        val backend = try {
            JadxBackend(file, settings, hash, cache)
        } catch (e: Exception) {
            throw LoadFailedException(path, e)
        }

        val sessionId = UUID.randomUUID().toString()
        val renameDbPath = cacheDir.resolve("renames").resolve("$sessionId.db")
        val renameStore = RenameStore(renameDbPath)

        val session = Session(id = sessionId, backend = backend, renameStore = renameStore)
        sessions[sessionId] = session
        log.info("session {} created for {} (hash={})", sessionId, file.name, hash.take(12))
        return session
    }

    fun get(sessionId: String): Session {
        val session = sessions[sessionId] ?: throw SessionNotFoundException(sessionId)
        session.lastAccessedAt = System.currentTimeMillis()
        return session
    }

    fun close(sessionId: String) {
        sessions.remove(sessionId)?.let { session ->
            session.renameStore.close()
            session.backend.close()
            log.info("session {} closed", sessionId)
        }
    }

    fun closeAll() {
        sessions.keys.toList().forEach { close(it) }
    }

    fun activeSessions(): Int = sessions.size

    fun listSessions(): List<Session> = sessions.values.toList()
}
