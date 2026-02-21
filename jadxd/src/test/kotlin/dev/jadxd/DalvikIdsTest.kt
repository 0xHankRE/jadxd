package dev.jadxd

import dev.jadxd.core.DalvikIds
import dev.jadxd.model.InvalidDescriptorException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DalvikIdsTest {

    // ── Type descriptors ────────────────────────────────────────────────────

    @Test
    fun `parse valid type descriptor`() {
        val parsed = DalvikIds.parseTypeDescriptor("Lcom/example/Foo;")
        assertEquals("Lcom/example/Foo;", parsed.descriptor)
        assertEquals("com/example/Foo", parsed.rawName)
    }

    @Test
    fun `parse inner class type descriptor`() {
        val parsed = DalvikIds.parseTypeDescriptor("Lcom/example/Outer\$Inner;")
        assertEquals("com/example/Outer\$Inner", parsed.rawName)
    }

    @Test
    fun `reject type descriptor without L prefix`() {
        assertThrows<InvalidDescriptorException> {
            DalvikIds.parseTypeDescriptor("com/example/Foo;")
        }
    }

    @Test
    fun `reject type descriptor without semicolon`() {
        assertThrows<InvalidDescriptorException> {
            DalvikIds.parseTypeDescriptor("Lcom/example/Foo")
        }
    }

    // ── Method descriptors ──────────────────────────────────────────────────

    @Test
    fun `parse simple method descriptor`() {
        val desc = "Lcom/example/Foo;->bar(I)V"
        val parsed = DalvikIds.parseMethodDescriptor(desc)
        assertEquals("Lcom/example/Foo;", parsed.classDescriptor)
        assertEquals("bar", parsed.name)
        assertEquals("bar(I)V", parsed.shortId)
    }

    @Test
    fun `parse method with object args`() {
        val desc = "Lcom/example/Foo;->baz(Ljava/lang/String;IZ)Ljava/util/List;"
        val parsed = DalvikIds.parseMethodDescriptor(desc)
        assertEquals("Lcom/example/Foo;", parsed.classDescriptor)
        assertEquals("baz", parsed.name)
        assertEquals("baz(Ljava/lang/String;IZ)Ljava/util/List;", parsed.shortId)
    }

    @Test
    fun `parse constructor descriptor`() {
        val desc = "Lcom/example/Foo;-><init>(Landroid/content/Context;)V"
        val parsed = DalvikIds.parseMethodDescriptor(desc)
        assertEquals("<init>", parsed.name)
        assertEquals("<init>(Landroid/content/Context;)V", parsed.shortId)
    }

    @Test
    fun `parse class init descriptor`() {
        val desc = "Lcom/example/Foo;-><clinit>()V"
        val parsed = DalvikIds.parseMethodDescriptor(desc)
        assertEquals("<clinit>", parsed.name)
    }

    @Test
    fun `reject method descriptor without arrow`() {
        assertThrows<InvalidDescriptorException> {
            DalvikIds.parseMethodDescriptor("Lcom/example/Foo;bar(I)V")
        }
    }

    // ── Field descriptors ───────────────────────────────────────────────────

    @Test
    fun `parse field descriptor`() {
        val desc = "Lcom/example/Foo;->count:I"
        val parsed = DalvikIds.parseFieldDescriptor(desc)
        assertEquals("Lcom/example/Foo;", parsed.classDescriptor)
        assertEquals("count", parsed.name)
        assertEquals("I", parsed.typeDescriptor)
    }

    @Test
    fun `parse field with object type`() {
        val desc = "Lcom/example/Foo;->name:Ljava/lang/String;"
        val parsed = DalvikIds.parseFieldDescriptor(desc)
        assertEquals("name", parsed.name)
        assertEquals("Ljava/lang/String;", parsed.typeDescriptor)
    }

    // ── Conversion helpers ──────────────────────────────────────────────────

    @Test
    fun `rawNameToDescriptor round trip`() {
        val raw = "com/example/Foo"
        val desc = DalvikIds.rawNameToDescriptor(raw)
        assertEquals("Lcom/example/Foo;", desc)
        assertEquals(raw, DalvikIds.descriptorToRawName(desc))
    }

    @Test
    fun `dottedNameToDescriptor`() {
        assertEquals("Lcom/example/Foo;", DalvikIds.dottedNameToDescriptor("com.example.Foo"))
    }

    @Test
    fun `descriptorToDottedName`() {
        assertEquals("com.example.Foo", DalvikIds.descriptorToDottedName("Lcom/example/Foo;"))
    }

    // ── Smali extraction ────────────────────────────────────────────────────

    @Test
    fun `extractMethodSmali finds correct method`() {
        val classSmali = """
            .class public Lcom/example/Foo;
            .super Ljava/lang/Object;

            .method public constructor <init>()V
                .registers 1
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                return-void
            .end method

            .method public doStuff(I)V
                .registers 3
                add-int v0, p1, p1
                return-void
            .end method

            .method private helper()I
                .registers 2
                const/4 v0, 0x0
                return v0
            .end method
        """.trimIndent()

        val result = DalvikIds.extractMethodSmali(classSmali, "doStuff(I)V")
        assertNotNull(result)
        assert(result.contains(".method public doStuff(I)V"))
        assert(result.contains("add-int"))
        assert(result.contains(".end method"))
        assert(!result.contains("<init>"))
        assert(!result.contains("helper"))
    }

    @Test
    fun `extractMethodSmali returns null for missing method`() {
        val classSmali = ".class public Lcom/example/Foo;\n.super Ljava/lang/Object;\n"
        assertNull(DalvikIds.extractMethodSmali(classSmali, "missing()V"))
    }
}
