package dev.jadxd.core

import dev.jadxd.model.RenameEntry
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.createDirectories

/**
 * SQLite-backed rename store. One `.db` file per session.
 *
 * Stores user-defined aliases for types, methods, and fields without
 * modifying the original decompiled output. The agent can overlay
 * these renames in its own representation.
 */
class RenameStore(dbPath: Path) : Closeable {

    private val log = LoggerFactory.getLogger(RenameStore::class.java)
    private val conn: Connection

    init {
        dbPath.parent.createDirectories()
        conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { stmt ->
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS renames (
                    original_id TEXT NOT NULL PRIMARY KEY,
                    entity_kind TEXT NOT NULL,
                    alias TEXT NOT NULL
                )
            """.trimIndent())
        }
        log.debug("rename store opened at {}", dbPath)
    }

    fun rename(originalId: String, kind: String, alias: String) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO renames (original_id, entity_kind, alias) VALUES (?, ?, ?)"
        ).use { ps ->
            ps.setString(1, originalId)
            ps.setString(2, kind)
            ps.setString(3, alias)
            ps.executeUpdate()
        }
    }

    fun getAlias(originalId: String): String? {
        return conn.prepareStatement(
            "SELECT alias FROM renames WHERE original_id = ?"
        ).use { ps ->
            ps.setString(1, originalId)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getString("alias") else null
            }
        }
    }

    fun removeAlias(originalId: String): Boolean {
        return conn.prepareStatement(
            "DELETE FROM renames WHERE original_id = ?"
        ).use { ps ->
            ps.setString(1, originalId)
            ps.executeUpdate() > 0
        }
    }

    fun listAll(): List<RenameEntry> {
        return conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT original_id, entity_kind, alias FROM renames ORDER BY original_id").use { rs ->
                buildList {
                    while (rs.next()) {
                        add(RenameEntry(
                            originalId = rs.getString("original_id"),
                            entityKind = rs.getString("entity_kind"),
                            alias = rs.getString("alias"),
                        ))
                    }
                }
            }
        }
    }

    override fun close() {
        try {
            conn.close()
        } catch (e: Exception) {
            log.warn("error closing rename store: {}", e.message)
        }
    }
}
