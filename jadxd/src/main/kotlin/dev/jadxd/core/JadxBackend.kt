package dev.jadxd.core

import dev.jadxd.model.*
import jadx.api.JavaClass
import jadx.api.JavaField
import jadx.api.JavaMethod
import jadx.api.JavaNode
import jadx.api.JavaPackage
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.ResourceType
import jadx.api.plugins.input.data.annotations.EncodedType
import jadx.api.plugins.input.data.annotations.IAnnotation
import jadx.api.plugins.input.data.attributes.JadxAttrType
import jadx.api.plugins.input.data.attributes.types.AnnotationMethodParamsAttr
import jadx.api.plugins.input.data.attributes.types.AnnotationsAttr
import jadx.core.Jadx
import jadx.core.dex.info.AccessInfo
import jadx.core.xmlgen.ResContainer
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File

/**
 * Thin wrapper around [JadxDecompiler] that exposes stable, agent-friendly queries.
 *
 * Thread safety: all public methods synchronize on [jadx]. Concurrent queries to the
 * same session are serialized; different sessions are independent.
 */
class JadxBackend(
    val inputFile: File,
    val settings: DecompileSettings,
    val artifactHash: String,
    private val cache: Cache,
) : Closeable {

    private val log = LoggerFactory.getLogger(JadxBackend::class.java)
    private val jadx: JadxDecompiler
    val jadxVersion: String
    val cacheKey: String
    val inputType: String

    init {
        val args = JadxArgs().apply {
            setInputFiles(mutableListOf(inputFile))
            isDeobfuscationOn = settings.deobfuscation
            isInlineMethods = settings.inlineMethods
            isShowInconsistentCode = settings.showInconsistentCode
            threadsCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        }
        jadx = JadxDecompiler(args)
        jadx.load()

        jadxVersion = try {
            Jadx.getVersion()
        } catch (_: Exception) {
            "unknown"
        }
        cacheKey = Cache.cacheKey(artifactHash, jadxVersion, settings)
        inputType = guessInputType(inputFile)
        log.info("loaded {} ({} classes, jadx {})", inputFile.name, jadx.classes.size, jadxVersion)
    }

    fun provenance(): Provenance = Provenance(
        backendVersion = jadxVersion,
        settings = settings,
    )

    // ── Queries ─────────────────────────────────────────────────────────────

    fun classCount(): Int = synchronized(jadx) { jadx.classesWithInners.size }

    fun listTypes(): List<TypeInfo> = synchronized(jadx) {
        jadx.classesWithInners.map { cls -> typeInfoOf(cls) }
    }

    fun listMethods(typeId: String): List<MethodSummary> = synchronized(jadx) {
        val cls = resolveClass(typeId)
        cls.methods.map { m ->
            MethodSummary(
                id = methodDescriptor(m),
                name = m.name,
                accessFlags = accessFlagNames(m.accessFlags),
            )
        }
    }

    data class DecompileResult(
        val java: String?,
        val smali: String?,
        val warnings: List<String>,
    )

    fun decompileMethod(methodId: String): DecompileResult = synchronized(jadx) {
        val parsed = DalvikIds.parseMethodDescriptor(methodId)
        val cls = resolveClass(parsed.classDescriptor)
        val method = resolveMethod(cls, parsed.shortId, methodId)
        val warnings = mutableListOf<String>()

        // Java decompilation
        val java = decompileMethodJava(cls, method, warnings)

        // Smali extraction
        val smali = extractMethodSmali(cls, parsed.shortId, warnings)

        DecompileResult(java = java, smali = smali, warnings = warnings)
    }

    fun searchStrings(query: String, regex: Boolean, limit: Int): Pair<List<StringMatch>, List<String>> =
        synchronized(jadx) {
            val warnings = mutableListOf<String>()
            val matches = mutableListOf<StringMatch>()
            val pattern = if (regex) {
                try {
                    Regex(query)
                } catch (e: Exception) {
                    throw JadxdException("Invalid regex: ${e.message}", "INVALID_REGEX")
                }
            } else null

            for (cls in jadx.classesWithInners) {
                if (matches.size >= limit) break
                val code = try {
                    cls.code ?: continue
                } catch (e: Exception) {
                    warnings.add("failed to decompile ${classDescriptor(cls)}: ${e.message}")
                    continue
                }
                val classId = classDescriptor(cls)
                findStringLiterals(code, query, regex, pattern).forEach { literal ->
                    if (matches.size >= limit) return@forEach
                    matches.add(StringMatch(
                        value = literal,
                        locations = listOf(StringLocation(typeId = classId)),
                    ))
                }
            }
            Pair(matches, warnings)
        }

    fun xrefsTo(methodId: String): Pair<List<XrefEntry>, List<String>> = synchronized(jadx) {
        val parsed = DalvikIds.parseMethodDescriptor(methodId)
        val cls = resolveClass(parsed.classDescriptor)
        val method = resolveMethod(cls, parsed.shortId, methodId)
        val warnings = mutableListOf<String>()

        val refs = try {
            method.useIn.mapNotNull { node -> xrefEntryOf(node, warnings) }
        } catch (e: Exception) {
            warnings.add("xref analysis failed: ${e.message}")
            emptyList()
        }
        Pair(refs, warnings)
    }

    fun xrefsFrom(methodId: String): Pair<List<XrefEntry>, List<String>> = synchronized(jadx) {
        val parsed = DalvikIds.parseMethodDescriptor(methodId)
        val cls = resolveClass(parsed.classDescriptor)
        val method = resolveMethod(cls, parsed.shortId, methodId)
        val warnings = mutableListOf<String>()

        val refs = try {
            method.getUsed().mapNotNull { node -> xrefEntryOf(node, warnings) }
        } catch (e: Exception) {
            warnings.add("xref analysis failed: ${e.message}")
            emptyList()
        }
        Pair(refs, warnings)
    }

    fun getManifest(): String = synchronized(jadx) {
        val manifestRes = jadx.resources.firstOrNull { it.type == ResourceType.MANIFEST }
            ?: throw ManifestUnavailableException()
        try {
            val container = manifestRes.loadContent()
            container.text?.codeStr ?: container.toString()
        } catch (e: Exception) {
            throw JadxdException(
                "Failed to load manifest: ${e.message}",
                "MANIFEST_LOAD_FAILED",
            )
        }
    }

    fun listResources(): List<ResourceEntry> = synchronized(jadx) {
        jadx.resources.map { res ->
            ResourceEntry(
                name = res.originalName,
                type = res.type.name,
                size = try {
                    res.zipEntry?.uncompressedSize
                } catch (_: Exception) { null },
            )
        }
    }

    // ── New queries ──────────────────────────────────────────────────────────

    fun listFields(typeId: String): List<FieldInfo> = synchronized(jadx) {
        val cls = resolveClass(typeId)
        cls.fields.map { f ->
            FieldInfo(
                id = fieldDescriptor(f),
                name = f.name,
                type = f.type.toString(),
                accessFlags = accessFlagNames(f.accessFlags),
            )
        }
    }

    fun decompileClass(typeId: String): Pair<String?, List<String>> = synchronized(jadx) {
        val cls = resolveClass(typeId)
        val warnings = mutableListOf<String>()
        val rawName = cls.rawName

        val java = cache.getJava(cacheKey, rawName) ?: try {
            val code = cls.code
            if (code != null) {
                cache.putJava(cacheKey, rawName, code)
            } else {
                warnings.add("class produced no code")
            }
            code
        } catch (e: Exception) {
            warnings.add("decompilation failed: ${e.message}")
            null
        }
        Pair(java, warnings)
    }

    data class ClassHierarchyInfo(
        val superClass: String?,
        val interfaces: List<String>,
        val innerClasses: List<String>,
        val accessFlags: List<String>,
        val genericParameters: List<String>,
        val genericSuperClass: String?,
        val genericInterfaces: List<String>,
    )

    fun getClassHierarchy(typeId: String): ClassHierarchyInfo = synchronized(jadx) {
        val cls = resolveClass(typeId)
        val classNode = cls.classNode
        val genericParams = try {
            classNode.genericTypeParameters?.map { it.toString() } ?: emptyList()
        } catch (_: Exception) { emptyList() }
        val genericSuper = try {
            val st = classNode.superClass
            if (st != null && st.isGeneric) st.toString() else null
        } catch (_: Exception) { null }
        val genericIfaces = try {
            classNode.interfaces.filter { it.isGeneric }.map { it.toString() }
        } catch (_: Exception) { emptyList() }
        ClassHierarchyInfo(
            superClass = classNode.superClass?.toString(),
            interfaces = classNode.interfaces.map { it.toString() },
            innerClasses = cls.innerClasses.map { classDescriptor(it) },
            accessFlags = accessFlagNames(cls.accessInfo),
            genericParameters = genericParams,
            genericSuperClass = genericSuper,
            genericInterfaces = genericIfaces,
        )
    }

    fun listMethodDetails(typeId: String): List<MethodDetail> = synchronized(jadx) {
        val cls = resolveClass(typeId)
        cls.methods.map { m ->
            val throwsList = try {
                m.methodNode.throws.map { argType ->
                    val obj = argType.`object`
                    if (obj != null) "L${obj.replace('.', '/')};" else argType.toString()
                }
            } catch (_: Exception) { emptyList() }
            val genericArgs = try {
                m.methodNode.argTypes?.map { it.toString() } ?: m.arguments.map { it.toString() }
            } catch (_: Exception) { m.arguments.map { it.toString() } }
            val genericRet = try {
                val rt = m.methodNode.returnType
                if (rt != null && rt.isGeneric) rt.toString() else null
            } catch (_: Exception) { null }
            MethodDetail(
                id = methodDescriptor(m),
                name = m.name,
                accessFlags = accessFlagNames(m.accessFlags),
                arguments = m.arguments.map { it.toString() },
                returnType = m.returnType.toString(),
                isConstructor = m.isConstructor,
                isClassInit = m.isClassInit,
                throws = throwsList,
                genericArguments = genericArgs,
                genericReturnType = genericRet,
            )
        }
    }

    fun fieldXrefs(fieldId: String): Pair<List<XrefEntry>, List<String>> = synchronized(jadx) {
        val parsed = DalvikIds.parseFieldDescriptor(fieldId)
        val cls = resolveClass(parsed.classDescriptor)
        val field = resolveField(cls, parsed, fieldId)
        val warnings = mutableListOf<String>()

        val refs = try {
            field.useIn.mapNotNull { node -> xrefEntryOf(node, warnings) }
        } catch (e: Exception) {
            warnings.add("field xref analysis failed: ${e.message}")
            emptyList()
        }
        Pair(refs, warnings)
    }

    fun classXrefs(typeId: String): Pair<List<XrefEntry>, List<String>> = synchronized(jadx) {
        val cls = resolveClass(typeId)
        val warnings = mutableListOf<String>()

        val refs = try {
            cls.useIn.mapNotNull { node -> xrefEntryOf(node, warnings) }
        } catch (e: Exception) {
            warnings.add("class xref analysis failed: ${e.message}")
            emptyList()
        }
        Pair(refs, warnings)
    }

    fun overrideGraph(methodId: String): Pair<List<OverrideEntry>, List<String>> = synchronized(jadx) {
        val parsed = DalvikIds.parseMethodDescriptor(methodId)
        val cls = resolveClass(parsed.classDescriptor)
        val method = resolveMethod(cls, parsed.shortId, methodId)
        val warnings = mutableListOf<String>()

        val entries = try {
            method.overrideRelatedMethods.map { m ->
                OverrideEntry(
                    id = methodDescriptor(m),
                    name = m.name,
                    declaringType = classDescriptor(m.declaringClass),
                )
            }
        } catch (e: Exception) {
            warnings.add("override analysis failed: ${e.message}")
            emptyList()
        }
        Pair(entries, warnings)
    }

    fun unresolvedRefs(methodId: String): Pair<List<UnresolvedRef>, List<String>> = synchronized(jadx) {
        val parsed = DalvikIds.parseMethodDescriptor(methodId)
        val cls = resolveClass(parsed.classDescriptor)
        val method = resolveMethod(cls, parsed.shortId, methodId)
        val warnings = mutableListOf<String>()

        val refs = try {
            method.unresolvedUsed.map { ref ->
                ref.load()
                UnresolvedRef(
                    parentClass = ref.parentClassType ?: "?",
                    argTypes = ref.argTypes ?: emptyList(),
                    returnType = ref.returnType ?: "?",
                )
            }
        } catch (e: Exception) {
            warnings.add("unresolved ref analysis failed: ${e.message}")
            emptyList()
        }
        Pair(refs, warnings)
    }

    data class ResourceContent(
        val dataType: String,
        val text: String?,
        val warnings: List<String>,
    )

    fun getResourceContent(name: String): ResourceContent = synchronized(jadx) {
        val res = jadx.resources.firstOrNull { it.originalName == name }
            ?: throw ResourceNotFoundException(name)
        val warnings = mutableListOf<String>()

        try {
            val container = res.loadContent()
            when (container.dataType) {
                ResContainer.DataType.TEXT -> ResourceContent(
                    dataType = "text",
                    text = container.text?.codeStr,
                    warnings = warnings,
                )
                ResContainer.DataType.RES_TABLE -> {
                    // Return root text + note about sub-files
                    val rootText = try { container.text?.codeStr } catch (_: Exception) { null }
                    val subNames = container.subFiles.map { it.name }
                    if (subNames.isNotEmpty()) {
                        warnings.add("resource table has ${subNames.size} sub-files: ${subNames.take(5)}")
                    }
                    ResourceContent(dataType = "res_table", text = rootText, warnings = warnings)
                }
                ResContainer.DataType.DECODED_DATA -> {
                    val bytes = container.decodedData
                    val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
                    ResourceContent(dataType = "binary", text = encoded, warnings = warnings)
                }
                ResContainer.DataType.RES_LINK -> {
                    warnings.add("resource is a link to another resource file")
                    ResourceContent(dataType = "res_link", text = null, warnings = warnings)
                }
                else -> ResourceContent(dataType = "unknown", text = null, warnings = warnings)
            }
        } catch (e: ResourceNotFoundException) {
            throw e
        } catch (e: Exception) {
            throw ResourceLoadException(name, e)
        }
    }

    // ── Annotations ────────────────────────────────────────────────────────

    data class AnnotationsInfo(
        val annotations: List<AnnotationInfo>,
        val parameterAnnotations: List<List<AnnotationInfo>>?,
        val warnings: List<String>,
    )

    fun getAnnotations(typeId: String?, methodId: String?, fieldId: String?): AnnotationsInfo = synchronized(jadx) {
        val warnings = mutableListOf<String>()
        when {
            typeId != null -> {
                val cls = resolveClass(typeId)
                val annAttr = cls.classNode.get(JadxAttrType.ANNOTATION_LIST) as? AnnotationsAttr
                AnnotationsInfo(extractAnnotations(annAttr), null, warnings)
            }
            methodId != null -> {
                val parsed = DalvikIds.parseMethodDescriptor(methodId)
                val cls = resolveClass(parsed.classDescriptor)
                val method = resolveMethod(cls, parsed.shortId, methodId)
                val annAttr = method.methodNode.get(JadxAttrType.ANNOTATION_LIST) as? AnnotationsAttr
                val paramAttr = method.methodNode.get(JadxAttrType.ANNOTATION_MTH_PARAMETERS) as? AnnotationMethodParamsAttr
                val paramAnns = paramAttr?.paramList?.map { extractAnnotations(it) }
                AnnotationsInfo(extractAnnotations(annAttr), paramAnns, warnings)
            }
            fieldId != null -> {
                val parsed = DalvikIds.parseFieldDescriptor(fieldId)
                val cls = resolveClass(parsed.classDescriptor)
                val field = resolveField(cls, parsed, fieldId)
                val annAttr = field.fieldNode.get(JadxAttrType.ANNOTATION_LIST) as? AnnotationsAttr
                AnnotationsInfo(extractAnnotations(annAttr), null, warnings)
            }
            else -> throw JadxdException(
                "exactly one of type_id, method_id, or field_id required", "BAD_REQUEST",
            )
        }
    }

    // ── Dependencies ────────────────────────────────────────────────────────

    fun getDependencies(typeId: String): Triple<List<String>, Int, List<String>> = synchronized(jadx) {
        val cls = resolveClass(typeId)
        val warnings = mutableListOf<String>()
        val deps = try {
            cls.dependencies.map { classDescriptor(it) }
        } catch (e: Exception) {
            warnings.add("dependency analysis failed: ${e.message}")
            emptyList()
        }
        val total = try { cls.totalDepsCount } catch (_: Exception) { deps.size }
        Triple(deps, total, warnings)
    }

    // ── Packages ────────────────────────────────────────────────────────────

    fun listPackages(): Pair<List<PackageInfo>, List<String>> = synchronized(jadx) {
        val warnings = mutableListOf<String>()
        val result = mutableListOf<PackageInfo>()
        fun collect(pkgs: List<JavaPackage>) {
            for (pkg in pkgs) {
                result.add(PackageInfo(
                    fullName = pkg.fullName,
                    classCount = pkg.classes.size,
                    subPackages = pkg.subPackages.map { it.fullName },
                    classIds = pkg.classes.map { classDescriptor(it) },
                    isLeaf = pkg.isLeaf,
                ))
                collect(pkg.subPackages)
            }
        }
        try { collect(jadx.packages) }
        catch (e: Exception) { warnings.add("package enumeration failed: ${e.message}") }
        Pair(result, warnings)
    }

    fun errorReport(): Pair<Int, Int> = synchronized(jadx) {
        Pair(jadx.errorsCount, jadx.warnsCount)
    }

    override fun close() {
        try {
            jadx.close()
        } catch (e: Exception) {
            log.warn("error closing jadx: {}", e.message)
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun resolveClass(typeId: String): JavaClass {
        val parsed = DalvikIds.parseTypeDescriptor(typeId)
        val rawName = parsed.rawName // "com/example/Foo$Bar"
        return jadx.classesWithInners.find { it.rawName == rawName }
            ?: throw TypeNotFoundException(typeId)
    }

    private fun resolveMethod(cls: JavaClass, shortId: String, fullId: String): JavaMethod {
        return cls.methods.find { m ->
            m.methodNode.methodInfo.shortId == shortId
        } ?: throw MethodNotFoundException(fullId)
    }

    private fun resolveField(cls: JavaClass, parsed: DalvikIds.ParsedField, fullId: String): JavaField {
        val shortId = "${parsed.name}:${parsed.typeDescriptor}"
        return cls.fields.find { f ->
            f.fieldNode.fieldInfo.shortId == shortId
        } ?: throw FieldNotFoundException(fullId)
    }

    private fun decompileMethodJava(
        cls: JavaClass, method: JavaMethod, warnings: MutableList<String>,
    ): String? {
        val rawName = cls.rawName
        // Check cache first
        cache.getJava(cacheKey, rawName)?.let { cachedClass ->
            return extractMethodFromClassCode(cachedClass, method, warnings)
        }
        return try {
            val classCode = cls.code ?: run {
                warnings.add("class produced no code")
                return null
            }
            cache.putJava(cacheKey, rawName, classCode)
            extractMethodFromClassCode(classCode, method, warnings)
        } catch (e: Exception) {
            warnings.add("decompilation failed: ${e.message}")
            null
        }
    }

    private fun extractMethodFromClassCode(
        classCode: String, method: JavaMethod, warnings: MutableList<String>,
    ): String {
        // Prefer JavaMethod.getCodeStr() which returns just the method code
        try {
            val methodCode = method.codeStr
            if (!methodCode.isNullOrBlank()) return methodCode
        } catch (_: Exception) { /* fall through */ }

        // Fallback: try defPos-based extraction
        val defPos = method.defPos
        if (defPos > 0 && defPos < classCode.length) {
            try {
                return extractByBraceMatching(classCode, defPos)
            } catch (_: Exception) { /* fall through */ }
        }

        warnings.add("could not extract method; returning full class source")
        return classCode
    }

    private fun extractByBraceMatching(code: String, startPos: Int): String {
        // Scan backwards from startPos to find the beginning of annotations/modifiers
        var lineStart = code.lastIndexOf('\n', startPos - 1) + 1

        // Find the opening brace
        val braceIdx = code.indexOf('{', startPos)
        if (braceIdx < 0) {
            // Abstract / native method — find the semicolon
            val semi = code.indexOf(';', startPos)
            return if (semi >= 0) code.substring(lineStart, semi + 1).trim()
            else code.substring(lineStart).trim()
        }

        var depth = 1
        var pos = braceIdx + 1
        while (pos < code.length && depth > 0) {
            when (code[pos]) {
                '{' -> depth++
                '}' -> depth--
                '"' -> pos = skipStringLiteral(code, pos)
                '\'' -> pos = skipCharLiteral(code, pos)
            }
            pos++
        }
        return code.substring(lineStart, pos).trim()
    }

    private fun skipStringLiteral(code: String, openQuoteIdx: Int): Int {
        var i = openQuoteIdx + 1
        while (i < code.length) {
            when (code[i]) {
                '\\' -> i++ // skip escaped char
                '"' -> return i
            }
            i++
        }
        return i
    }

    private fun skipCharLiteral(code: String, openQuoteIdx: Int): Int {
        var i = openQuoteIdx + 1
        while (i < code.length) {
            when (code[i]) {
                '\\' -> i++
                '\'' -> return i
            }
            i++
        }
        return i
    }

    private fun extractMethodSmali(
        cls: JavaClass, shortId: String, warnings: MutableList<String>,
    ): String? {
        val rawName = cls.rawName
        // Check cache
        val cachedSmali = cache.getSmali(cacheKey, rawName)
        val classSmali = if (cachedSmali != null) {
            cachedSmali
        } else {
            try {
                val s = cls.smali ?: run {
                    warnings.add("smali unavailable for class")
                    return null
                }
                cache.putSmali(cacheKey, rawName, s)
                s
            } catch (e: Exception) {
                warnings.add("smali generation failed: ${e.message}")
                return null
            }
        }
        return DalvikIds.extractMethodSmali(classSmali, shortId)
            ?: run {
                warnings.add("could not locate method in smali; returning full class smali")
                classSmali
            }
    }

    private fun findStringLiterals(
        code: String, query: String, isRegex: Boolean, pattern: Regex?,
    ): List<String> {
        // Simple approach: find occurrences in decompiled source
        val results = mutableListOf<String>()
        if (isRegex && pattern != null) {
            pattern.findAll(code).forEach { m -> results.add(m.value) }
        } else {
            var idx = code.indexOf(query)
            while (idx >= 0) {
                // Try to extract the surrounding string literal
                val literalStart = code.lastIndexOf('"', idx)
                val literalEnd = code.indexOf('"', idx + query.length)
                val value = if (literalStart >= 0 && literalEnd > literalStart) {
                    code.substring(literalStart + 1, literalEnd)
                } else {
                    query
                }
                results.add(value)
                idx = code.indexOf(query, idx + 1)
            }
        }
        return results.distinct()
    }

    // ── Descriptor helpers ──────────────────────────────────────────────────

    companion object {
        // ── Annotation helpers ──────────────────────────────────────────

        fun encodedValueToModel(ev: jadx.api.plugins.input.data.annotations.EncodedValue): AnnotationValue {
            val typeName = ev.type.name
            return when (ev.type) {
                EncodedType.ENCODED_ARRAY -> {
                    @Suppress("UNCHECKED_CAST")
                    val items = (ev.value as? List<jadx.api.plugins.input.data.annotations.EncodedValue>)
                        ?.map { encodedValueToModel(it) } ?: emptyList()
                    AnnotationValue(type = typeName, values = items)
                }
                EncodedType.ENCODED_ANNOTATION -> {
                    val nested = ev.value as? IAnnotation
                    AnnotationValue(type = typeName, annotation = nested?.let { annotationToModel(it) })
                }
                EncodedType.ENCODED_NULL -> AnnotationValue(type = typeName, value = "null")
                else -> AnnotationValue(type = typeName, value = ev.value?.toString())
            }
        }

        fun annotationToModel(ann: IAnnotation): AnnotationInfo = AnnotationInfo(
            annotationClass = ann.annotationClass,
            visibility = ann.visibility.name,
            values = ann.values.mapValues { (_, ev) -> encodedValueToModel(ev) },
        )

        fun extractAnnotations(attr: AnnotationsAttr?): List<AnnotationInfo> =
            attr?.all?.map { annotationToModel(it) } ?: emptyList()

        // ── Descriptor helpers ──────────────────────────────────────────

        fun classDescriptor(cls: JavaClass): String = "L${cls.rawName};"

        fun methodDescriptor(m: JavaMethod): String {
            val classDesc = "L${m.declaringClass.rawName};"
            val shortId = m.methodNode.methodInfo.shortId
            return "$classDesc->$shortId"
        }

        fun fieldDescriptor(f: JavaField): String {
            val classDesc = "L${f.declaringClass.rawName};"
            val shortId = f.fieldNode.fieldInfo.shortId
            return "$classDesc->$shortId"
        }

        fun accessFlagNames(info: AccessInfo): List<String> = buildList {
            if (info.isPublic) add("public")
            if (info.isProtected) add("protected")
            if (info.isPrivate) add("private")
            if (info.isStatic) add("static")
            if (info.isFinal) add("final")
            if (info.isAbstract) add("abstract")
            if (info.isInterface) add("interface")
            if (info.isEnum) add("enum")
            if (info.isAnnotation) add("annotation")
            if (info.isNative) add("native")
            if (info.isSynchronized) add("synchronized")
            if (info.isSynthetic) add("synthetic")
            if (info.isBridge) add("bridge")
            if (info.isVarArgs) add("varargs")
            if (info.isTransient) add("transient")
            if (info.isVolatile) add("volatile")
        }

        fun typeInfoOf(cls: JavaClass): TypeInfo {
            val accessInfo = cls.accessInfo
            val kind = when {
                accessInfo.isAnnotation -> "annotation"
                accessInfo.isInterface -> "interface"
                accessInfo.isEnum -> "enum"
                else -> "class"
            }
            return TypeInfo(
                id = classDescriptor(cls),
                kind = kind,
                name = cls.name,
                packageName = cls.`package`,
                accessFlags = accessFlagNames(accessInfo),
            )
        }

        fun xrefEntryOf(node: JavaNode, warnings: MutableList<String>): XrefEntry? {
            return try {
                when (node) {
                    is JavaMethod -> XrefEntry(
                        id = methodDescriptor(node),
                        kind = "method",
                        name = node.name,
                        declaringType = classDescriptor(node.declaringClass),
                    )
                    is JavaClass -> XrefEntry(
                        id = classDescriptor(node),
                        kind = "class",
                        name = node.name,
                        declaringType = classDescriptor(node),
                    )
                    is JavaField -> XrefEntry(
                        id = fieldDescriptor(node),
                        kind = "field",
                        name = node.name,
                        declaringType = classDescriptor(node.declaringClass),
                    )
                    else -> {
                        warnings.add("unknown xref node type: ${node.javaClass.simpleName}")
                        null
                    }
                }
            } catch (e: Exception) {
                warnings.add("xref conversion failed: ${e.message}")
                null
            }
        }

        private fun guessInputType(file: File): String = when (file.extension.lowercase()) {
            "apk" -> "apk"
            "dex" -> "dex"
            "jar" -> "jar"
            "class" -> "class"
            "aar" -> "aar"
            else -> "unknown"
        }
    }
}
