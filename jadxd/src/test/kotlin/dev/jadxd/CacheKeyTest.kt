package dev.jadxd

import dev.jadxd.core.Cache
import dev.jadxd.model.DecompileSettings
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CacheKeyTest {

    // ── File hashing ────────────────────────────────────────────────────────

    @Test
    fun `hashFile produces consistent sha256`(@TempDir tmp: Path) {
        val file = tmp.resolve("test.bin").toFile()
        file.writeBytes(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))

        val hash1 = Cache.hashFile(file)
        val hash2 = Cache.hashFile(file)
        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length) // SHA-256 hex = 64 chars
    }

    @Test
    fun `hashFile differs for different content`(@TempDir tmp: Path) {
        val f1 = tmp.resolve("a.bin").toFile().also { it.writeText("hello") }
        val f2 = tmp.resolve("b.bin").toFile().also { it.writeText("world") }
        assertNotEquals(Cache.hashFile(f1), Cache.hashFile(f2))
    }

    // ── Cache key generation ────────────────────────────────────────────────

    @Test
    fun `cacheKey is deterministic`() {
        val settings = DecompileSettings()
        val k1 = Cache.cacheKey("abc123", "1.5.4", settings)
        val k2 = Cache.cacheKey("abc123", "1.5.4", settings)
        assertEquals(k1, k2)
    }

    @Test
    fun `cacheKey changes with different settings`() {
        val s1 = DecompileSettings(deobfuscation = false)
        val s2 = DecompileSettings(deobfuscation = true)
        val k1 = Cache.cacheKey("abc123", "1.5.4", s1)
        val k2 = Cache.cacheKey("abc123", "1.5.4", s2)
        assertNotEquals(k1, k2)
    }

    @Test
    fun `cacheKey changes with different version`() {
        val settings = DecompileSettings()
        val k1 = Cache.cacheKey("abc123", "1.5.3", settings)
        val k2 = Cache.cacheKey("abc123", "1.5.4", settings)
        assertNotEquals(k1, k2)
    }

    @Test
    fun `cacheKey changes with different hash`() {
        val settings = DecompileSettings()
        val k1 = Cache.cacheKey("abc", "1.5.4", settings)
        val k2 = Cache.cacheKey("def", "1.5.4", settings)
        assertNotEquals(k1, k2)
    }

    // ── Disk cache read/write ───────────────────────────────────────────────

    @Test
    fun `put and get java code`(@TempDir tmp: Path) {
        val cache = Cache(tmp)
        val key = "testkey"
        val className = "com/example/Foo"

        assertNull(cache.getJava(key, className))

        cache.putJava(key, className, "public class Foo {}")
        assertEquals("public class Foo {}", cache.getJava(key, className))
    }

    @Test
    fun `put and get smali code`(@TempDir tmp: Path) {
        val cache = Cache(tmp)
        val key = "testkey"
        val className = "com/example/Foo"

        assertNull(cache.getSmali(key, className))

        cache.putSmali(key, className, ".class public Lcom/example/Foo;")
        assertEquals(".class public Lcom/example/Foo;", cache.getSmali(key, className))
    }

    @Test
    fun `cache handles nested class names`(@TempDir tmp: Path) {
        val cache = Cache(tmp)
        cache.putJava("k", "com/example/Outer\$Inner", "class Inner {}")
        assertEquals("class Inner {}", cache.getJava("k", "com/example/Outer\$Inner"))
    }
}
