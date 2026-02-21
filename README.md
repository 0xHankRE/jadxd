# jadxd -- JADX Decompiler Shim Layer

> **Disclaimer:** This project was generated with the assistance of a large language model (LLM).
> The code, documentation, and tests were produced through AI-assisted development and may contain
> errors, suboptimal patterns, or inaccuracies. **Use at your own discretion.** You are encouraged
> to review, test, and validate all code before using it in any production or security-critical
> context. No warranty is provided, express or implied.

A backend-agnostic shim that exposes stable, agent-friendly queries for Android reverse
engineering. Uses [JADX](https://github.com/skylot/jadx) as a library behind a simple
HTTP/JSON API, with a companion Python client library.

## Architecture

```
┌─────────────┐       HTTP/JSON        ┌────────────┐
│  RE Agent   │ <───────────────────── │   jadxd    │
│  (Python)   │    /v1/... + UTBP      │  (Kotlin)  │
└──────┬──────┘                        └──────┬─────┘
       │                                      │
  pyjadxd client                     JadxBackend <- JadxDecompiler
  or UTBP generic                    MethodRegistry (24 methods)
                                     Cache (disk)
```

**Key design decisions:**
- **Canonical Dalvik descriptors** as stable identifiers across backends
- **Session model** -- load once, query many times
- **Caching** -- per artifact hash + jadx version + settings
- **Backend-agnostic models** -- swap Jadx for CFR/fernflower/Ghidra later
- **Dual output** -- both Java decompilation and smali disassembly per method

## Prerequisites

- **JDK 17+** -- required to build and run the Kotlin service. The build is configured with
  [Foojay Toolchain Resolver](https://github.com/gradle/foojay-toolchain-convention-plugin),
  which can auto-download a JDK 17 if one isn't available on your system. However, Gradle itself
  must be launched with a JDK (not a JRE). If you have multiple Java versions, set `JAVA_HOME`
  to point at a JDK 17+ installation.
- **Python 3.10+** -- for the `pyjadxd` client library.
- **JADX source tree** -- the `jadx/` directory (sibling to `jadxd/`) must contain the JADX
  source. This repo uses a [Gradle composite build](https://docs.gradle.org/current/userguide/composite_builds.html)
  to compile JADX from source, which gives access to APIs not yet released to Maven Central.

## Quick Start

### 1. Build the service

```bash
cd jadxd
./gradlew build
```

This compiles the Kotlin service and the local JADX dependency, and runs the unit test suite
(25 tests covering Dalvik descriptor parsing and cache key generation).

### 2. Run the integration tests

```bash
# From the repo root:
./test.sh            # build + run all integration tests
./test.sh --no-build # skip the gradle build, just run tests
```

The script starts jadxd on a temporary port, exercises every API endpoint against real
samples (`small.apk` and `hello.dex` from the jadx test suite), and shuts down afterward.
It covers:

- Health check
- Load APK and DEX files
- List types, list methods (summary and detailed), list fields
- Decompile method, decompile full class
- Class hierarchy (superclass, interfaces, inner classes, generics)
- Java annotations (type, method, field, with parameter annotations)
- Type dependency graph, package tree
- Cross-references (method to/from, field xrefs, class xrefs)
- Override graph, unresolved references
- String search (literal and regex)
- Manifest retrieval (and expected failure on non-APK input)
- Resource listing and content retrieval
- Rename shim (set, list, remove aliases)
- Error report (decompilation error/warning counts)
- Session close and post-close 404 verification
- Error paths: nonexistent session, bad file path, invalid regex
- **UTBP protocol**: health, backends, schema, target CRUD, query dispatch,
  unknown method error, invalid target error, target cleanup

The script requires only `curl` and `bash` -- no Python dependencies needed.

### 3. Run the service

```bash
./gradlew run
# Or with options:
./gradlew run --args="--port 8085 --cache-dir /tmp/jadxd-cache"
```

The service starts on `http://127.0.0.1:8085` by default.

**CLI flags:**
| Flag | Default | Description |
|------|---------|-------------|
| `--host` | `127.0.0.1` | Bind address |
| `--port` | `8085` | Listen port |
| `--cache-dir` | `~/.cache/jadxd` | Disk cache directory |

### 4. Install the Python client

```bash
cd pyjadxd
pip install -e .
```

Dependencies: `httpx>=0.25`, `pydantic>=2.0`.

### 5. Use it

```python
from pyjadxd import JadxdClient

client = JadxdClient()
load = client.load("/path/to/app.apk")
sid = load.session_id

# List all types
types = client.list_types(sid)
for t in types.types[:5]:
    print(t.id, t.kind)

# Decompile a method (Java + smali)
dec = client.decompile_method(sid, "Lcom/example/MainActivity;->onCreate(Landroid/os/Bundle;)V")
print(dec.java)
print(dec.smali)

# Decompile a full class
cls = client.decompile_class(sid, "Lcom/example/MainActivity;")
print(cls.java)

# Class hierarchy
hier = client.get_hierarchy(sid, "Lcom/example/MainActivity;")
print(hier.super_class, hier.interfaces)

# Fields and detailed methods
fields = client.list_fields(sid, "Lcom/example/MainActivity;")
methods = client.list_methods_detail(sid, "Lcom/example/MainActivity;")

# Cross-references (methods, fields, classes)
callers = client.xrefs_to(sid, method_id)
callees = client.xrefs_from(sid, method_id)
field_refs = client.field_xrefs(sid, field_id)
class_refs = client.class_xrefs(sid, type_id)

# Override graph and unresolved refs
overrides = client.overrides(sid, method_id)
unresolved = client.unresolved_refs(sid, method_id)

# String search (literal or regex)
strings = client.search_strings(sid, query="password", regex=False, limit=50)

# Android manifest and resources
manifest = client.get_manifest(sid)
resources = client.list_resources(sid)
content = client.get_resource_content(sid, "AndroidManifest.xml")

# Annotations on a type, method, or field
anns = client.get_annotations(sid, type_id="Lcom/example/MainActivity;")
anns = client.get_annotations(sid, method_id="Lcom/example/Foo;->bar(I)V")

# Type dependency graph
deps = client.get_dependencies(sid, "Lcom/example/MainActivity;")

# Package tree
packages = client.list_packages(sid)

# Rename shim (metadata overlay, doesn't modify decompiled output)
client.rename(sid, "Lcom/example/a;", "DecryptionHelper")
renames = client.list_renames(sid)
client.remove_rename(sid, "Lcom/example/a;")

# Clean up
client.close_session(sid)
```

## API Reference

All session-scoped endpoints are `POST` with JSON bodies. Responses include `provenance`
(backend version and settings used) and `warnings` (non-fatal issues encountered).

### Identifiers (Dalvik Descriptors)

All types, methods, and fields are identified by canonical Dalvik descriptors. These are
stable across sessions and backends.

| Kind   | Format                                      | Example                                          |
|--------|---------------------------------------------|--------------------------------------------------|
| Type   | `Lpackage/ClassName;`                       | `Lcom/example/MainActivity;`                     |
| Method | `Lpackage/Class;->name(ArgTypes)RetType`    | `Lcom/example/Foo;->bar(Ljava/lang/String;I)V`  |
| Field  | `Lpackage/Class;->name:Type`               | `Lcom/example/Foo;->count:I`                     |

**Primitive type codes:** `V` void, `Z` boolean, `B` byte, `S` short, `I` int,
`J` long, `F` float, `D` double. Arrays use `[` prefix (e.g. `[B` = byte array,
`[[Ljava/lang/String;` = String[][]).

### Endpoints

#### `POST /v1/load`
Load an APK, DEX, or JAR file and create a session.

```json
// Request
{"path": "/data/app.apk", "settings": {"deobfuscation": false}}

// Response (201)
{
  "session_id": "a1b2c3d4-...",
  "artifact_hash": "sha256...",
  "input_type": "apk",
  "class_count": 1423,
  "provenance": {"backend_version": "1.5.4", "settings": {...}}
}
```

The `settings` object is optional. Available settings:
- `deobfuscation` (bool, default `false`) -- enable JADX deobfuscation
- `inline_methods` (bool, default `true`) -- inline simple methods
- `show_inconsistent_code` (bool, default `true`) -- show code even when decompilation is uncertain

#### `POST /v1/sessions/{id}/types`
List all types (classes, interfaces, enums, annotations).

```json
// Request: {} (empty body)
// Response
{
  "session_id": "a1b2c3d4-...",
  "types": [
    {
      "id": "Lcom/example/Foo;",
      "kind": "class",
      "name": "Foo",
      "package_name": "com.example",
      "access_flags": ["public"]
    }
  ],
  "provenance": {...}
}
```

Kind values: `class`, `interface`, `enum`, `annotation`.

#### `POST /v1/sessions/{id}/methods`
List methods of a type.

```json
// Request
{"type_id": "Lcom/example/Foo;"}

// Response
{
  "session_id": "a1b2c3d4-...",
  "type_id": "Lcom/example/Foo;",
  "methods": [
    {"id": "Lcom/example/Foo;->bar(I)V", "name": "bar", "access_flags": ["public"]}
  ],
  "provenance": {...}
}
```

#### `POST /v1/sessions/{id}/decompile`
Decompile a method (returns both Java source and smali disassembly).

```json
// Request
{"method_id": "Lcom/example/Foo;->bar(I)V"}

// Response
{
  "id": "Lcom/example/Foo;->bar(I)V",
  "java": "public void bar(int i) {\n    ...\n}",
  "smali": ".method public bar(I)V\n    ...\n.end method",
  "provenance": {...},
  "warnings": []
}
```

The decompiler tries multiple extraction strategies:
1. `JavaMethod.getCodeStr()` -- method-level decompiled code
2. `defPos`-based brace matching -- extracts method from full class source
3. Full class source as fallback (with a warning)

#### `POST /v1/sessions/{id}/xrefs/to`
Find callers of a method (who calls this method?).

```json
// Request
{"method_id": "Lcom/example/Foo;->bar(I)V"}

// Response
{
  "id": "Lcom/example/Foo;->bar(I)V",
  "direction": "to",
  "refs": [
    {"id": "Lcom/example/Baz;->init()V", "kind": "method", "name": "init", "declaring_type": "Lcom/example/Baz;"}
  ],
  "provenance": {...},
  "warnings": []
}
```

#### `POST /v1/sessions/{id}/xrefs/from`
Find callees of a method (what does this method call?). Same response shape with `"direction": "from"`.

#### `POST /v1/sessions/{id}/xrefs/field`
Find references to a field (who reads/writes it?).

```json
// Request
{"field_id": "Lcom/example/Foo;->count:I"}

// Response
{
  "id": "Lcom/example/Foo;->count:I",
  "direction": "to",
  "refs": [
    {"id": "Lcom/example/Bar;->update()V", "kind": "method", "name": "update", "declaring_type": "Lcom/example/Bar;"}
  ],
  "provenance": {...}
}
```

#### `POST /v1/sessions/{id}/xrefs/class`
Find references to a class (who uses this type?). Same response shape as method xrefs.

```json
// Request
{"type_id": "Lcom/example/Foo;"}
```

#### `POST /v1/sessions/{id}/fields`
List fields of a type with type information.

```json
// Request
{"type_id": "Lcom/example/Foo;"}

// Response
{
  "session_id": "a1b2c3d4-...",
  "type_id": "Lcom/example/Foo;",
  "fields": [
    {"id": "Lcom/example/Foo;->count:I", "name": "count", "type": "int", "access_flags": ["private"]}
  ],
  "provenance": {...}
}
```

#### `POST /v1/sessions/{id}/methods/detail`
List methods with full signature information (arguments, return type, constructor flags, declared
exceptions, and generic type signatures).

```json
// Request
{"type_id": "Lcom/example/Foo;"}

// Response
{
  "session_id": "a1b2c3d4-...",
  "type_id": "Lcom/example/Foo;",
  "methods": [
    {
      "id": "Lcom/example/Foo;->bar(I)V",
      "name": "bar",
      "access_flags": ["public"],
      "arguments": ["int"],
      "return_type": "void",
      "is_constructor": false,
      "is_class_init": false,
      "throws": ["Ljava/io/IOException;"],
      "generic_arguments": ["int"],
      "generic_return_type": null
    }
  ],
  "provenance": {...}
}
```

#### `POST /v1/sessions/{id}/decompile/class`
Decompile an entire class (full Java source).

```json
// Request
{"type_id": "Lcom/example/Foo;"}

// Response
{
  "session_id": "a1b2c3d4-...",
  "type_id": "Lcom/example/Foo;",
  "java": "package com.example;\n\npublic class Foo {\n    ...\n}",
  "provenance": {...}
}
```

#### `POST /v1/sessions/{id}/hierarchy`
Get the class hierarchy: superclass, implemented interfaces, inner classes, access flags,
and generic type information (type parameters, generic superclass, generic interfaces).

```json
// Request
{"type_id": "Lcom/example/Foo;"}

// Response
{
  "session_id": "a1b2c3d4-...",
  "type_id": "Lcom/example/Foo;",
  "super_class": "Ljava/lang/Object;",
  "interfaces": ["Ljava/io/Serializable;"],
  "inner_classes": ["Lcom/example/Foo$Builder;"],
  "access_flags": ["public"],
  "generic_parameters": ["T"],
  "generic_super_class": null,
  "generic_interfaces": ["Comparable<Foo<T>>"],
  "provenance": {...}
}
```

#### `POST /v1/sessions/{id}/overrides`
Get the override/implementation chain for a method.

```json
// Request
{"method_id": "Lcom/example/Foo;->toString()Ljava/lang/String;"}

// Response
{
  "id": "Lcom/example/Foo;->toString()Ljava/lang/String;",
  "overrides": [
    {"id": "Ljava/lang/Object;->toString()Ljava/lang/String;", "name": "toString", "declaring_type": "Ljava/lang/Object;"}
  ],
  "provenance": {...}
}
```

#### `POST /v1/sessions/{id}/unresolved`
Get unresolved external references from a method (calls to libraries/framework not in the loaded artifact).

```json
// Request
{"method_id": "Lcom/example/Foo;->bar(I)V"}

// Response
{
  "id": "Lcom/example/Foo;->bar(I)V",
  "refs": [
    {"parent_class": "android/util/Log", "arg_types": ["java/lang/String", "java/lang/String"], "return_type": "int"}
  ],
  "provenance": {...}
}
```

#### `POST /v1/sessions/{id}/strings`
Search for strings in decompiled source. Supports both literal and regex matching.

```json
// Request
{"query": "password", "regex": false, "limit": 50}

// Response
{
  "session_id": "a1b2c3d4-...",
  "query": "password",
  "is_regex": false,
  "matches": [
    {"value": "password_hash", "locations": [{"type_id": "Lcom/example/Auth;"}]}
  ],
  "total_count": 3,
  "provenance": {...},
  "warnings": []
}
```

#### `POST /v1/sessions/{id}/manifest`
Get the decoded AndroidManifest.xml (APK only). Returns a `MANIFEST_UNAVAILABLE` error for
non-APK inputs.

```json
// Response
{
  "session_id": "a1b2c3d4-...",
  "text": "<?xml version=\"1.0\" ...?>...",
  "provenance": {...}
}
```

#### `POST /v1/sessions/{id}/resources`
List resource files in the artifact.

```json
// Response
{
  "session_id": "a1b2c3d4-...",
  "resources": [
    {"name": "res/layout/activity_main.xml", "type": "XML", "size": 1234}
  ],
  "provenance": {...}
}
```

#### `POST /v1/sessions/{id}/resources/content`
Fetch the content of a specific resource file.

```json
// Request
{"name": "AndroidManifest.xml"}

// Response
{
  "session_id": "a1b2c3d4-...",
  "name": "AndroidManifest.xml",
  "data_type": "TEXT",
  "text": "<?xml version=\"1.0\" ...?>...",
  "provenance": {...}
}
```

`data_type` is one of: `TEXT`, `DECODED_DATA` (base64), `RES_TABLE`.

#### `POST /v1/sessions/{id}/rename`
Set a rename alias for a type, method, or field. Aliases are session-scoped metadata overlays
and do not modify decompiled output.

```json
// Request
{"id": "Lcom/example/a;", "alias": "DecryptionHelper"}

// Response
{"id": "Lcom/example/a;", "alias": "DecryptionHelper", "status": "renamed"}
```

#### `POST /v1/sessions/{id}/rename/remove`
Remove a rename alias.

```json
// Request
{"id": "Lcom/example/a;"}

// Response
{"id": "Lcom/example/a;", "alias": "", "status": "removed"}
```

#### `POST /v1/sessions/{id}/renames`
List all active rename aliases for the session.

```json
// Request: {} (empty body)
// Response
{
  "session_id": "a1b2c3d4-...",
  "renames": [
    {"original_id": "Lcom/example/a;", "entity_kind": "type", "alias": "DecryptionHelper"}
  ]
}
```

#### `POST /v1/sessions/{id}/errors`
Get decompilation error/warning counts.

```json
// Request: {} (empty body)
// Response
{
  "session_id": "a1b2c3d4-...",
  "errors_count": 2,
  "warnings_count": 15,
  "provenance": {...}
}
```

#### `POST /v1/sessions/{id}/annotations`
Get Java annotations on a type, method, or field. Supply exactly one of `type_id`, `method_id`,
or `field_id`. For methods, also returns per-parameter annotations.

```json
// Request
{"type_id": "Lcom/example/Foo;"}
// or: {"method_id": "Lcom/example/Foo;->bar(I)V"}
// or: {"field_id": "Lcom/example/Foo;->count:I"}

// Response
{
  "id": "Lcom/example/Foo;",
  "kind": "type",
  "annotations": [
    {
      "annotation_class": "Ljava/lang/Deprecated;",
      "visibility": "RUNTIME",
      "values": {}
    }
  ],
  "parameter_annotations": null,
  "provenance": {...}
}
```

Visibility values: `BUILD`, `RUNTIME`, `SYSTEM`. Annotation values can be scalars, arrays
(nested `values`), or nested annotations (`annotation` field).

#### `POST /v1/sessions/{id}/dependencies`
Get the type dependency graph — what types does this class depend on?

```json
// Request
{"type_id": "Lcom/example/Foo;"}

// Response
{
  "session_id": "a1b2c3d4-...",
  "type_id": "Lcom/example/Foo;",
  "dependencies": ["Ljava/lang/Object;", "Ljava/io/Serializable;", "Lcom/example/Bar;"],
  "total_deps_count": 3,
  "provenance": {...}
}
```

#### `POST /v1/sessions/{id}/packages`
Get the package tree with class IDs per package.

```json
// Request: {} (empty body)

// Response
{
  "session_id": "a1b2c3d4-...",
  "packages": [
    {
      "full_name": "com.example",
      "class_count": 5,
      "sub_packages": ["com.example.util"],
      "class_ids": ["Lcom/example/Foo;", "Lcom/example/Bar;"],
      "is_leaf": false
    }
  ],
  "total_packages": 3,
  "provenance": {...}
}
```

#### `POST /v1/sessions/{id}/close`
Close a session and free resources (including rename database).

#### `GET /v1/health`
Health check. Returns `{"active_sessions": N}`.

### Error Responses

All errors follow a consistent structure:

```json
{
  "error": {
    "error_code": "METHOD_NOT_FOUND",
    "message": "Method not found: Lcom/example/Foo;->missing()V",
    "details": {"method_id": "Lcom/example/Foo;->missing()V"}
  }
}
```

| Error Code | HTTP Status | Description |
|------------|-------------|-------------|
| `SESSION_NOT_FOUND` | 404 | Session ID does not exist or was closed |
| `TYPE_NOT_FOUND` | 404 | Type descriptor not found in loaded artifact |
| `METHOD_NOT_FOUND` | 404 | Method descriptor not found in the specified type |
| `FIELD_NOT_FOUND` | 404 | Field descriptor not found in the specified type |
| `MANIFEST_UNAVAILABLE` | 404 | Input is not an APK or has no manifest |
| `RESOURCE_NOT_FOUND` | 404 | Named resource not found in the artifact |
| `INVALID_DESCRIPTOR` | 400 | Malformed Dalvik descriptor |
| `INVALID_REGEX` | 400 | Invalid regex pattern in string search |
| `LOAD_FAILED` | 422 | JADX could not load the input file |
| `RESOURCE_LOAD_FAILED` | 422 | Failed to load/decode resource content |
| `INTERNAL_ERROR` | 500 | Unexpected server error |

## Universal Tool Backend Protocol (UTBP)

In addition to the `/v1/` REST API, jadxd implements the **Universal Tool Backend Protocol** --
a generic protocol that allows LLM agents to discover and call backend methods at runtime without
hardcoded API knowledge.

### How it works

1. Agent discovers available methods via `GET /backends/jadx/schema`
2. Agent loads a target via `POST /backends/jadx/targets`
3. Agent calls any method via `POST /backends/jadx/query` with `{target_id, method, args}`
4. Every response is a `ResultEnvelope` -- agents don't need per-method response parsing

### Protocol Endpoints

#### `GET /health`
Protocol-level health check (distinct from `/v1/health`).

```json
{"status": "ok", "backends": ["jadx"]}
```

#### `GET /backends`
List registered backends.

```json
[{
  "id": "jadx",
  "name": "JADX Decompiler",
  "version": "1.5.4",
  "target_count": 2,
  "method_count": 24
}]
```

#### `GET /backends/jadx/schema`
Get the method schema -- all available query methods with their JSON Schema inputs.

```json
{
  "list_types": {
    "description": "List all types in the artifact",
    "input_schema": {"type": "object", "properties": {}},
    "is_write": false
  },
  "decompile_class": {
    "description": "Decompile an entire class to Java source",
    "input_schema": {
      "type": "object",
      "properties": {"type_id": {"type": "string", "description": "Dalvik type descriptor"}},
      "required": ["type_id"]
    },
    "is_write": false
  }
}
```

#### `GET /backends/jadx/targets`
List loaded targets (sessions).

```json
[{
  "target_id": "a1b2c3d4-...",
  "path": "/data/app.apk",
  "input_type": "apk",
  "class_count": 1423,
  "artifact_hash": "sha256...",
  "created_at": 1708444800000
}]
```

#### `POST /backends/jadx/targets`
Load a target.

```json
// Request
{"path": "/data/app.apk"}

// Response (201)
{"target_id": "a1b2c3d4-...", "metadata": {"artifact_hash": "...", "input_type": "apk", "class_count": 1423}}
```

#### `DELETE /backends/jadx/targets/{target_id}`
Unload a target (closes the session).

#### `POST /backends/jadx/query`
Execute a query method. This is the main entry point for all analysis operations.

```json
// Request
{
  "target_id": "a1b2c3d4-...",
  "method": "decompile_class",
  "args": {"type_id": "Lcom/example/Foo;"}
}

// Response (ResultEnvelope)
{
  "ok": true,
  "query": "decompile_class",
  "args": {"type_id": "Lcom/example/Foo;"},
  "data": {
    "session_id": "a1b2c3d4-...",
    "type_id": "Lcom/example/Foo;",
    "java": "package com.example;\n\npublic class Foo { ... }",
    "provenance": {...}
  },
  "truncated": false,
  "warnings": []
}
```

On error:

```json
{
  "ok": false,
  "query": "decompile_class",
  "args": {"type_id": "Lcom/example/Missing;"},
  "error": "TYPE_NOT_FOUND: Type not found: Lcom/example/Missing;"
}
```

### Available Methods (24)

| Method | Args | Description |
|--------|------|-------------|
| `list_types` | (none) | List all types in the artifact |
| `list_methods` | `type_id` | List methods (summary) |
| `list_methods_detail` | `type_id` | List methods with full signatures |
| `list_fields` | `type_id` | List fields with type info |
| `decompile_method` | `method_id` | Decompile a method (Java + smali) |
| `decompile_class` | `type_id` | Decompile a full class |
| `get_hierarchy` | `type_id` | Class hierarchy + generics |
| `xrefs_to` | `method_id` | Find callers of a method |
| `xrefs_from` | `method_id` | Find callees of a method |
| `field_xrefs` | `field_id` | Find references to a field |
| `class_xrefs` | `type_id` | Find references to a class |
| `override_graph` | `method_id` | Override/implementation chain |
| `unresolved_refs` | `method_id` | External references from a method |
| `search_strings` | `query, regex?, limit?` | Search strings in source |
| `get_manifest` | (none) | AndroidManifest.xml (APK only) |
| `list_resources` | (none) | List resource files |
| `get_resource_content` | `name` | Fetch resource content |
| `get_annotations` | `type_id/method_id/field_id` | Java annotations |
| `get_dependencies` | `type_id` | Type dependency graph |
| `list_packages` | (none) | Package tree with class IDs |
| `error_report` | (none) | Decompilation error counts |
| `rename` | `id, alias` | Set a rename alias (write) |
| `remove_rename` | `id` | Remove a rename alias (write) |
| `list_renames` | (none) | List all rename aliases |

### Adding New Methods

To add a new query method, only two changes are needed:

1. Add the backend method to `JadxBackend.kt`
2. Add one schema entry + one dispatch case in `MethodRegistry.kt`

No new routes, no Python model updates, no client method additions -- the UTBP framework
discovers new methods automatically via the schema.

## Project Structure

```
jadxd/                          Kotlin service
├── build.gradle.kts            Gradle build (Ktor 2.3.7, Kotlin 1.9.22, sqlite-jdbc)
├── settings.gradle.kts         Composite build config (links ../jadx)
├── src/main/kotlin/dev/jadxd/
│   ├── Main.kt                 CLI arg parsing + Ktor server startup
│   ├── model/Models.kt         Request/response types + exception hierarchy
│   ├── core/
│   │   ├── DalvikIds.kt        Dalvik descriptor parsing/formatting/validation
│   │   ├── Cache.kt            SHA-256 keyed disk cache
│   │   ├── JadxBackend.kt      Jadx library wrapper (thread-safe, 25+ query methods)
│   │   ├── RenameStore.kt      SQLite-backed rename alias store (per session)
│   │   └── SessionManager.kt   Session lifecycle (create/get/close + rename DB)
│   └── server/
│       ├── Routes.kt           Ktor HTTP route definitions (25 REST endpoints)
│       ├── MethodRegistry.kt   UTBP method schema (24 methods) + query dispatcher
│       └── ProtocolRoutes.kt   UTBP HTTP route handlers (7 protocol endpoints)
├── src/main/resources/
│   └── logback.xml             Logging config
└── src/test/kotlin/dev/jadxd/
    ├── DalvikIdsTest.kt        Descriptor parsing tests (16 tests)
    └── CacheKeyTest.kt         Cache key + disk cache tests (9 tests)

pyjadxd/                        Python client library
├── pyproject.toml              Package config (httpx, pydantic)
├── pyjadxd/
│   ├── __init__.py             Public API exports
│   ├── client.py               JadxdClient with typed methods (25 API methods)
│   ├── models.py               Pydantic v2 response models
│   └── errors.py               Structured error types
└── examples/demo.py            End-to-end usage demo

test.sh                         Integration test suite (bash + curl)
jadx/                           JADX source tree (composite build dependency)
```

## Caching

Cached data lives in `~/.cache/jadxd/<cache_key>/` (or `--cache-dir`).

**Cache key** = SHA-256 of `(artifact_file_hash | jadx_version | settings_json)`.

Within a cache key directory:
- `java/<raw_class_name>.java` -- decompiled Java per class
- `smali/<raw_class_name>.smali` -- smali output per class

The same APK with the same settings will reuse cached decompilations across sessions.
Changing any setting, upgrading JADX, or modifying the input file invalidates the cache
automatically.

## Thread Safety

All `JadxBackend` queries synchronize on the underlying `JadxDecompiler` instance. Concurrent
requests to the same session are serialized; requests to different sessions run independently.
The `SessionManager` uses a `ConcurrentHashMap` for thread-safe session lookup.

## Limitations & TODO Roadmap

### Current limitations
- String search decompiles all classes on first run (slow for large APKs)
- No pagination on `list_types` (fine for most APKs, may be slow for 50k+ classes)
- Xrefs require triggering decompilation of referencing/referenced classes
- Single-threaded query execution per session (synchronized on the decompiler)
- No session timeout or auto-cleanup (sessions persist until explicitly closed or server restart)
- Renames are session-scoped metadata overlays; they don't affect decompiled output

### Planned
- [ ] AST / CFG export endpoint
- [ ] Pagination / streaming for large result sets
- [ ] Session timeout / auto-cleanup
- [ ] Unix domain socket transport
- [ ] OpenAPI spec generation
- [ ] Alternative backends (CFR, fernflower, Ghidra bridge)
- [ ] Full resources.arsc parsing
- [ ] Smali-only backend (no decompilation, just disassembly)

## License

This project wraps [JADX](https://github.com/skylot/jadx), which is licensed under the
Apache License 2.0.
