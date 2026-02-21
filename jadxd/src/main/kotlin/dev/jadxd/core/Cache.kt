package dev.jadxd.core

import dev.jadxd.model.DecompileSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Disk-backed cache keyed on (artifact_hash, jadx_version, settings).
 *
 * Layout:
 *   <root>/<cache_key>/java/<raw_class_name>.java
 *   <root>/<cache_key>/smali/<raw_class_name>.smali
 */
class Cache(private val rootDir: Path) {

    private val log = LoggerFactory.getLogger(Cache::class.java)

    // ── Key computation ─────────────────────────────────────────────────────

    companion object {
        private val json = Json { prettyPrint = false }

        fun hashFile(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun cacheKey(artifactHash: String, jadxVersion: String, settings: DecompileSettings): String {
            val payload = "$artifactHash|$jadxVersion|${json.encodeToString(settings)}"
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }

    // ── Per-class cache operations ──────────────────────────────────────────

    private fun classJavaPath(key: String, rawClassName: String): Path =
        rootDir.resolve(key).resolve("java").resolve("$rawClassName.java")

    private fun classSmaliPath(key: String, rawClassName: String): Path =
        rootDir.resolve(key).resolve("smali").resolve("$rawClassName.smali")

    fun getJava(key: String, rawClassName: String): String? {
        val p = classJavaPath(key, rawClassName)
        return if (p.exists()) p.readText() else null
    }

    fun putJava(key: String, rawClassName: String, code: String) {
        val p = classJavaPath(key, rawClassName)
        p.parent.createDirectories()
        p.writeText(code)
        log.debug("cached java: {}", rawClassName)
    }

    fun getSmali(key: String, rawClassName: String): String? {
        val p = classSmaliPath(key, rawClassName)
        return if (p.exists()) p.readText() else null
    }

    fun putSmali(key: String, rawClassName: String, smali: String) {
        val p = classSmaliPath(key, rawClassName)
        p.parent.createDirectories()
        p.writeText(smali)
        log.debug("cached smali: {}", rawClassName)
    }
}
