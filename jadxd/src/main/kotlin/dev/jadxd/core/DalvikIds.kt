package dev.jadxd.core

import dev.jadxd.model.InvalidDescriptorException

/**
 * Utilities for working with canonical Dalvik descriptors.
 *
 *   Type   : Lcom/pkg/ClassName;
 *   Method : Lcom/pkg/ClassName;->methodName(Args)Ret
 *   Field  : Lcom/pkg/ClassName;->fieldName:Type
 */
object DalvikIds {

    // ── Parsing ─────────────────────────────────────────────────────────────

    data class ParsedType(val descriptor: String, val rawName: String)

    data class ParsedMethod(
        val classDescriptor: String,
        val name: String,
        val shortId: String,  // "name(args)ret"
    )

    data class ParsedField(
        val classDescriptor: String,
        val name: String,
        val typeDescriptor: String,
    )

    fun parseTypeDescriptor(desc: String): ParsedType {
        if (!desc.startsWith("L") || !desc.endsWith(";"))
            throw InvalidDescriptorException(desc, "type must match Lcom/pkg/Class;")
        val rawName = desc.substring(1, desc.length - 1)
        return ParsedType(desc, rawName)
    }

    fun parseMethodDescriptor(desc: String): ParsedMethod {
        val arrow = desc.indexOf("->")
        if (arrow < 0) throw InvalidDescriptorException(desc, "missing -> separator")
        val classDesc = desc.substring(0, arrow)
        parseTypeDescriptor(classDesc) // validate
        val shortId = desc.substring(arrow + 2)
        val parenOpen = shortId.indexOf('(')
        if (parenOpen < 0) throw InvalidDescriptorException(desc, "missing ( in method descriptor")
        val name = shortId.substring(0, parenOpen)
        return ParsedMethod(classDesc, name, shortId)
    }

    fun parseFieldDescriptor(desc: String): ParsedField {
        val arrow = desc.indexOf("->")
        if (arrow < 0) throw InvalidDescriptorException(desc, "missing -> separator")
        val classDesc = desc.substring(0, arrow)
        parseTypeDescriptor(classDesc) // validate
        val rest = desc.substring(arrow + 2)
        val colon = rest.indexOf(':')
        if (colon < 0) throw InvalidDescriptorException(desc, "missing : in field descriptor")
        return ParsedField(classDesc, rest.substring(0, colon), rest.substring(colon + 1))
    }

    // ── Construction ────────────────────────────────────────────────────────

    /** "com/example/Foo" → "Lcom/example/Foo;" */
    fun rawNameToDescriptor(rawName: String): String = "L$rawName;"

    /** "Lcom/example/Foo;" → "com/example/Foo" */
    fun descriptorToRawName(desc: String): String = desc.removePrefix("L").removeSuffix(";")

    /** "com.example.Foo" → "Lcom/example/Foo;" (heuristic, inner-class ambiguous) */
    fun dottedNameToDescriptor(dottedName: String): String =
        "L${dottedName.replace('.', '/')};"

    /** "Lcom/example/Foo;" → "com.example.Foo" */
    fun descriptorToDottedName(desc: String): String =
        desc.removePrefix("L").removeSuffix(";").replace('/', '.')

    // ── Smali matching ──────────────────────────────────────────────────────

    /**
     * Extract a single method's smali from a full class smali text.
     * Matches the `.method` directive whose trailing token equals [shortId].
     */
    fun extractMethodSmali(classSmali: String, shortId: String): String? {
        val lines = classSmali.lineSequence()
        val buf = StringBuilder()
        var inside = false

        for (line in lines) {
            val trimmed = line.trimStart()
            if (!inside) {
                if (trimmed.startsWith(".method ") && trimmed.endsWith(shortId)) {
                    inside = true
                    buf.appendLine(line)
                }
            } else {
                buf.appendLine(line)
                if (trimmed == ".end method") return buf.toString().trimEnd()
            }
        }
        return null
    }
}
