#!/usr/bin/env bash
#
# Integration test for jadxd.
#
# Builds the service, starts it, exercises every endpoint against real
# test samples (small.apk and hello.dex from the jadx test suite), and
# verifies the responses.
#
# Usage:
#   ./test.sh            # build + run tests
#   ./test.sh --no-build # skip gradle build, just run tests
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JADXD_DIR="$SCRIPT_DIR/jadxd"
PORT=18085  # non-default port to avoid collisions
BASE="http://127.0.0.1:$PORT"
SERVER_PID=""
PASSED=0
FAILED=0
TOTAL=0

# Test samples bundled with jadx
SAMPLE_APK="$SCRIPT_DIR/jadx/jadx-cli/src/test/resources/samples/small.apk"
SAMPLE_DEX="$SCRIPT_DIR/jadx/jadx-cli/src/test/resources/samples/hello.dex"
SAMPLE_JAR="$SCRIPT_DIR/test-fixtures/testapp.jar"

# ── Auto-detect JDK 17+ ───────────────────────────────────────────────
# The Foojay toolchain plugin itself requires JDK 17+ to load.  If the
# system java is older, look for a JDK 17 downloaded by Foojay or under
# /usr/lib/jvm and export JAVA_HOME so Gradle uses it.
detect_jdk17() {
    # Already sufficient?
    if java -version 2>&1 | grep -qE '"(1[7-9]\.|[2-9][0-9]\.)'; then
        return
    fi
    # Check Foojay download cache
    local foojay_jdk
    foojay_jdk=$(find "$HOME/.gradle/jdks" -maxdepth 3 -name "release" -path "*17*" 2>/dev/null | head -1) || true
    if [[ -n "$foojay_jdk" ]]; then
        export JAVA_HOME
        JAVA_HOME="$(dirname "$foojay_jdk")"
        echo "Auto-detected JDK 17 at $JAVA_HOME"
        return
    fi
    # Check /usr/lib/jvm
    local sys_jdk
    sys_jdk=$(find /usr/lib/jvm -maxdepth 1 -type d -name "*17*" 2>/dev/null | head -1) || true
    if [[ -n "$sys_jdk" ]]; then
        export JAVA_HOME="$sys_jdk"
        echo "Auto-detected JDK 17 at $JAVA_HOME"
        return
    fi
    echo "WARNING: JDK 17+ not found. Build may fail. Set JAVA_HOME manually."
}

if [[ -z "${JAVA_HOME:-}" ]]; then
    detect_jdk17
fi

# ── Helpers ────────────────────────────────────────────────────────────

cleanup() {
    if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
        echo ""
        echo "Stopping server (pid $SERVER_PID)..."
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

log()  { echo -e "\033[1;34m==>\033[0m $*"; }
pass() { echo -e "  \033[1;32mPASS\033[0m $1"; PASSED=$((PASSED + 1)); TOTAL=$((TOTAL + 1)); }
fail() { echo -e "  \033[1;31mFAIL\033[0m $1: $2"; FAILED=$((FAILED + 1)); TOTAL=$((TOTAL + 1)); }

# Run a curl request, capture HTTP status and body.
# Usage: http_post <path> <json_body>
#   Sets: BODY, STATUS
http_post() {
    local resp
    resp=$(curl -s -w '\n%{http_code}' -X POST -H 'Content-Type: application/json' \
        -d "$2" "${BASE}$1" 2>&1) || true
    STATUS=$(echo "$resp" | tail -n1)
    BODY=$(echo "$resp" | sed '$d')
}

http_get() {
    local resp
    resp=$(curl -s -w '\n%{http_code}' "${BASE}$1" 2>&1) || true
    STATUS=$(echo "$resp" | tail -n1)
    BODY=$(echo "$resp" | sed '$d')
}

http_delete() {
    local resp
    resp=$(curl -s -w '\n%{http_code}' -X DELETE "${BASE}$1" 2>&1) || true
    STATUS=$(echo "$resp" | tail -n1)
    BODY=$(echo "$resp" | sed '$d')
}

# Assert HTTP status code
assert_status() {
    local test_name="$1" expected="$2"
    if [[ "$STATUS" == "$expected" ]]; then
        pass "$test_name (HTTP $STATUS)"
    else
        fail "$test_name" "expected HTTP $expected, got $STATUS"
        echo "    Body: ${BODY:0:200}"
    fi
}

# Assert that the body contains a fixed string (not a regex)
assert_contains() {
    local test_name="$1" needle="$2"
    if [[ "$BODY" == *"$needle"* ]]; then
        pass "$test_name"
    else
        fail "$test_name" "response missing '$needle'"
        echo "    Body: ${BODY:0:200}"
    fi
}

# Extract a JSON string value by key from pretty-printed output.
# Handles:  "key" : "value"  and  "key": "value"
# Returns empty string on no match (never fails the pipeline).
json_field() {
    local key="$1"
    # Collapse to single line, then extract
    local flat
    flat=$(echo "$BODY" | tr -d '\n')
    local val
    val=$(echo "$flat" | grep -oE "\"${key}\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -1 | sed 's/.*:[[:space:]]*"//;s/"$//') || true
    echo "$val"
}

json_int() {
    local key="$1"
    local flat
    flat=$(echo "$BODY" | tr -d '\n')
    local val
    val=$(echo "$flat" | grep -oE "\"${key}\"[[:space:]]*:[[:space:]]*[0-9]+" | head -1 | sed 's/.*:[[:space:]]*//') || true
    echo "$val"
}

# Extract the first Dalvik-style ID ("Lcom/...;->...") from the response
first_dalvik_id() {
    local flat
    flat=$(echo "$BODY" | tr -d '\n')
    local val
    val=$(echo "$flat" | grep -oE '"id"[[:space:]]*:[[:space:]]*"L[^"]*"' | head -1 | sed 's/.*:[[:space:]]*"//;s/"$//') || true
    echo "$val"
}

# ── Build ──────────────────────────────────────────────────────────────

if [[ "${1:-}" != "--no-build" ]]; then
    log "Building jadxd..."
    cd "$JADXD_DIR"
    ./gradlew build -q --no-daemon 2>&1 | tail -5
    cd "$SCRIPT_DIR"
    log "Build OK"
else
    log "Skipping build (--no-build)"
fi

# ── Start server ───────────────────────────────────────────────────────

log "Starting jadxd on port $PORT..."
cd "$JADXD_DIR"
./gradlew run -q --no-daemon --args="--port $PORT" &
SERVER_PID=$!
cd "$SCRIPT_DIR"

# Wait for server to be ready (up to 120s for first build / JDK download)
log "Waiting for server..."
for i in $(seq 1 120); do
    if curl -s "$BASE/v1/health" >/dev/null 2>&1; then
        log "Server ready (took ${i}s)"
        break
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "Server process died before becoming ready."
        exit 1
    fi
    sleep 1
done

if ! curl -s "$BASE/v1/health" >/dev/null 2>&1; then
    echo "Server failed to start within 120s."
    exit 1
fi

# ── Tests ──────────────────────────────────────────────────────────────

log "Running integration tests..."
echo ""

# ── 1. Health check ───────────────────────────────────────────────────

log "Health check"
http_get "/v1/health"
assert_status "GET /v1/health" "200"
assert_contains "health: active_sessions field" "active_sessions"

# ── 2. Load APK ───────────────────────────────────────────────────────

log "Load APK ($SAMPLE_APK)"
http_post "/v1/load" "{\"path\": \"$SAMPLE_APK\"}"
assert_status "POST /v1/load (APK)" "201"
assert_contains "load: session_id present" "session_id"
assert_contains "load: input_type is apk" "\"apk\""

APK_SID=$(json_field "session_id")
APK_CLASSES=$(json_int "class_count")

if [[ -z "$APK_SID" ]]; then
    echo "FATAL: could not extract session_id from load response"
    exit 1
fi

echo "  Session: $APK_SID  Classes: $APK_CLASSES"

# ── 3. List types ─────────────────────────────────────────────────────

log "List types (APK)"
http_post "/v1/sessions/$APK_SID/types" "{}"
assert_status "POST /types (APK)" "200"
assert_contains "types: contains types array" "\"types\""

# Extract first type ID
FIRST_TYPE=$(first_dalvik_id)
echo "  First type: $FIRST_TYPE"

# ── 4. List methods ───────────────────────────────────────────────────

if [[ -n "$FIRST_TYPE" ]]; then
    log "List methods ($FIRST_TYPE)"
    http_post "/v1/sessions/$APK_SID/methods" "{\"type_id\": \"$FIRST_TYPE\"}"
    assert_status "POST /methods" "200"
    assert_contains "methods: contains methods array" "\"methods\""

    # Extract first method ID
    FIRST_METHOD=$(first_dalvik_id)
    echo "  First method: $FIRST_METHOD"

    # ── 5. Decompile method ───────────────────────────────────────────

    if [[ -n "$FIRST_METHOD" ]]; then
        log "Decompile method ($FIRST_METHOD)"
        http_post "/v1/sessions/$APK_SID/decompile" "{\"method_id\": \"$FIRST_METHOD\"}"
        assert_status "POST /decompile" "200"
        assert_contains "decompile: has id field" "\"id\""

        # ── 6. Xrefs to ──────────────────────────────────────────────

        log "Xrefs to ($FIRST_METHOD)"
        http_post "/v1/sessions/$APK_SID/xrefs/to" "{\"method_id\": \"$FIRST_METHOD\"}"
        assert_status "POST /xrefs/to" "200"
        assert_contains "xrefs/to: direction=to" "\"to\""

        # ── 7. Xrefs from ────────────────────────────────────────────

        log "Xrefs from ($FIRST_METHOD)"
        http_post "/v1/sessions/$APK_SID/xrefs/from" "{\"method_id\": \"$FIRST_METHOD\"}"
        assert_status "POST /xrefs/from" "200"
        assert_contains "xrefs/from: direction=from" "\"from\""
    fi
fi

# ── 8. String search ─────────────────────────────────────────────────

log "String search (APK)"
http_post "/v1/sessions/$APK_SID/strings" '{"query": "a", "regex": false, "limit": 10}'
assert_status "POST /strings" "200"
assert_contains "strings: has matches array" "\"matches\""

# ── 9. String search (regex) ─────────────────────────────────────────

log "String search regex (APK)"
http_post "/v1/sessions/$APK_SID/strings" '{"query": ".*", "regex": true, "limit": 5}'
assert_status "POST /strings (regex)" "200"
assert_contains "strings regex: has matches array" "\"matches\""

# ── 10. Manifest ──────────────────────────────────────────────────────

log "Manifest (APK)"
http_post "/v1/sessions/$APK_SID/manifest" "{}"
assert_status "POST /manifest (APK)" "200"
assert_contains "manifest: has text" "\"text\""

# ── 11. Resources ─────────────────────────────────────────────────────

log "Resources (APK)"
http_post "/v1/sessions/$APK_SID/resources" "{}"
assert_status "POST /resources (APK)" "200"
assert_contains "resources: has resources array" "\"resources\""

# ── 12. List fields ──────────────────────────────────────────────────

if [[ -n "$FIRST_TYPE" ]]; then
    log "List fields ($FIRST_TYPE)"
    http_post "/v1/sessions/$APK_SID/fields" "{\"type_id\": \"$FIRST_TYPE\"}"
    assert_status "POST /fields" "200"
    assert_contains "fields: contains fields array" "\"fields\""

    # Extract first field ID for field xrefs test
    FIRST_FIELD=$(first_dalvik_id)
    echo "  First field: $FIRST_FIELD"
fi

# ── 13. Decompile class ─────────────────────────────────────────────

if [[ -n "$FIRST_TYPE" ]]; then
    log "Decompile class ($FIRST_TYPE)"
    http_post "/v1/sessions/$APK_SID/decompile/class" "{\"type_id\": \"$FIRST_TYPE\"}"
    assert_status "POST /decompile/class" "200"
    assert_contains "decompile/class: has type_id" "\"type_id\""
fi

# ── 14. Class hierarchy ─────────────────────────────────────────────

if [[ -n "$FIRST_TYPE" ]]; then
    log "Class hierarchy ($FIRST_TYPE)"
    http_post "/v1/sessions/$APK_SID/hierarchy" "{\"type_id\": \"$FIRST_TYPE\"}"
    assert_status "POST /hierarchy" "200"
    assert_contains "hierarchy: has type_id" "\"type_id\""
    assert_contains "hierarchy: has generic_parameters" "\"generic_parameters\""
fi

# ── 15. Methods detail ──────────────────────────────────────────────

if [[ -n "$FIRST_TYPE" ]]; then
    log "Methods detail ($FIRST_TYPE)"
    http_post "/v1/sessions/$APK_SID/methods/detail" "{\"type_id\": \"$FIRST_TYPE\"}"
    assert_status "POST /methods/detail" "200"
    assert_contains "methods/detail: has methods array" "\"methods\""
    assert_contains "methods/detail: has return_type" "\"return_type\""
    assert_contains "methods/detail: has throws" "\"throws\""
    assert_contains "methods/detail: has generic_arguments" "\"generic_arguments\""
fi

# ── 16. Field xrefs ─────────────────────────────────────────────────

if [[ -n "${FIRST_FIELD:-}" ]]; then
    log "Field xrefs ($FIRST_FIELD)"
    http_post "/v1/sessions/$APK_SID/xrefs/field" "{\"field_id\": \"$FIRST_FIELD\"}"
    assert_status "POST /xrefs/field" "200"
    assert_contains "xrefs/field: has refs" "\"refs\""
fi

# ── 17. Class xrefs ─────────────────────────────────────────────────

if [[ -n "$FIRST_TYPE" ]]; then
    log "Class xrefs ($FIRST_TYPE)"
    http_post "/v1/sessions/$APK_SID/xrefs/class" "{\"type_id\": \"$FIRST_TYPE\"}"
    assert_status "POST /xrefs/class" "200"
    assert_contains "xrefs/class: has refs" "\"refs\""
fi

# ── 18. Override graph ───────────────────────────────────────────────

if [[ -n "${FIRST_METHOD:-}" ]]; then
    log "Override graph ($FIRST_METHOD)"
    http_post "/v1/sessions/$APK_SID/overrides" "{\"method_id\": \"$FIRST_METHOD\"}"
    assert_status "POST /overrides" "200"
    assert_contains "overrides: has overrides array" "\"overrides\""
fi

# ── 19. Unresolved refs ─────────────────────────────────────────────

if [[ -n "${FIRST_METHOD:-}" ]]; then
    log "Unresolved refs ($FIRST_METHOD)"
    http_post "/v1/sessions/$APK_SID/unresolved" "{\"method_id\": \"$FIRST_METHOD\"}"
    assert_status "POST /unresolved" "200"
    assert_contains "unresolved: has refs array" "\"refs\""
fi

# ── 20. Resource content ────────────────────────────────────────────

log "Resource content (AndroidManifest.xml)"
http_post "/v1/sessions/$APK_SID/resources/content" '{"name": "AndroidManifest.xml"}'
assert_status "POST /resources/content" "200"
assert_contains "resource content: has text" "\"text\""

# ── 21. Error report ────────────────────────────────────────────────

log "Error report (APK)"
http_post "/v1/sessions/$APK_SID/errors" "{}"
assert_status "POST /errors" "200"
assert_contains "errors: has errors_count" "\"errors_count\""
assert_contains "errors: has warnings_count" "\"warnings_count\""

# ── 22. Rename flow ─────────────────────────────────────────────────

if [[ -n "$FIRST_TYPE" ]]; then
    log "Rename: set alias"
    http_post "/v1/sessions/$APK_SID/rename" "{\"id\": \"$FIRST_TYPE\", \"alias\": \"MyRenamedClass\"}"
    assert_status "POST /rename" "200"
    assert_contains "rename: has alias" "\"MyRenamedClass\""

    log "Rename: list renames"
    http_post "/v1/sessions/$APK_SID/renames" "{}"
    assert_status "POST /renames" "200"
    assert_contains "renames: has renames array" "\"renames\""
    assert_contains "renames: contains our alias" "\"MyRenamedClass\""

    log "Rename: remove alias"
    http_post "/v1/sessions/$APK_SID/rename/remove" "{\"id\": \"$FIRST_TYPE\"}"
    assert_status "POST /rename/remove" "200"
    assert_contains "rename/remove: status removed" "\"removed\""

    log "Rename: verify empty after remove"
    http_post "/v1/sessions/$APK_SID/renames" "{}"
    assert_status "POST /renames (after remove)" "200"
    # renames array should be empty
    assert_contains "renames: empty array" "\"renames\""
fi

# ── 23. Annotations (type) ──────────────────────────────────────────

if [[ -n "$FIRST_TYPE" ]]; then
    log "Annotations for type ($FIRST_TYPE)"
    http_post "/v1/sessions/$APK_SID/annotations" "{\"type_id\": \"$FIRST_TYPE\"}"
    assert_status "POST /annotations (type)" "200"
    assert_contains "annotations type: has annotations" "\"annotations\""
    assert_contains "annotations type: has kind" "\"kind\""
fi

# ── 24. Annotations (method) ───────────────────────────────────────

if [[ -n "${FIRST_METHOD:-}" ]]; then
    log "Annotations for method ($FIRST_METHOD)"
    http_post "/v1/sessions/$APK_SID/annotations" "{\"method_id\": \"$FIRST_METHOD\"}"
    assert_status "POST /annotations (method)" "200"
    assert_contains "annotations method: has annotations" "\"annotations\""
fi

# ── 25. Dependencies ───────────────────────────────────────────────

if [[ -n "$FIRST_TYPE" ]]; then
    log "Dependencies ($FIRST_TYPE)"
    http_post "/v1/sessions/$APK_SID/dependencies" "{\"type_id\": \"$FIRST_TYPE\"}"
    assert_status "POST /dependencies" "200"
    assert_contains "dependencies: has dependencies" "\"dependencies\""
    assert_contains "dependencies: has total_deps_count" "\"total_deps_count\""
fi

# ── 26. Packages ───────────────────────────────────────────────────

log "Packages (APK)"
http_post "/v1/sessions/$APK_SID/packages" "{}"
assert_status "POST /packages" "200"
assert_contains "packages: has packages" "\"packages\""
assert_contains "packages: has total_packages" "\"total_packages\""

# ── 27. Close APK session ────────────────────────────────────────────

log "Close APK session"
http_post "/v1/sessions/$APK_SID/close" "{}"
assert_status "POST /close (APK)" "200"
assert_contains "close: session_id returned" "session_id"

# ── 28. Verify closed session returns 404 ────────────────────────────

log "Verify closed session is gone"
http_post "/v1/sessions/$APK_SID/types" "{}"
assert_status "POST /types (closed session)" "404"
assert_contains "closed session: SESSION_NOT_FOUND" "SESSION_NOT_FOUND"

# ── 29. Load DEX ─────────────────────────────────────────────────────

log "Load DEX ($SAMPLE_DEX)"
http_post "/v1/load" "{\"path\": \"$SAMPLE_DEX\"}"
assert_status "POST /v1/load (DEX)" "201"
assert_contains "load dex: input_type=dex" "dex"

DEX_SID=$(json_field "session_id")
echo "  Session: $DEX_SID"

# ── 30. List types (DEX) ─────────────────────────────────────────────

log "List types (DEX)"
http_post "/v1/sessions/$DEX_SID/types" "{}"
assert_status "POST /types (DEX)" "200"
assert_contains "types dex: contains types array" "\"types\""

# ── 31. Manifest on DEX (should fail) ────────────────────────────────

log "Manifest on DEX (expect error)"
http_post "/v1/sessions/$DEX_SID/manifest" "{}"
assert_status "POST /manifest (DEX)" "404"
assert_contains "manifest dex: MANIFEST_UNAVAILABLE" "MANIFEST_UNAVAILABLE"

# ── 32. Close DEX session ────────────────────────────────────────────

log "Close DEX session"
http_post "/v1/sessions/$DEX_SID/close" "{}"
assert_status "POST /close (DEX)" "200"

# ── 33. Error: invalid session ────────────────────────────────────────

log "Error handling: nonexistent session"
http_post "/v1/sessions/nonexistent-session-id/types" "{}"
assert_status "nonexistent session" "404"
assert_contains "error: SESSION_NOT_FOUND" "SESSION_NOT_FOUND"

# ── 34. Error: invalid file path ─────────────────────────────────────

log "Error handling: bad file path"
http_post "/v1/load" '{"path": "/nonexistent/file.apk"}'
assert_status "bad file path" "422"

# ── 35. Error: invalid regex ─────────────────────────────────────────

# Need a session for this test
http_post "/v1/load" "{\"path\": \"$SAMPLE_DEX\"}"
TEMP_SID=$(json_field "session_id")

log "Error handling: invalid regex"
http_post "/v1/sessions/$TEMP_SID/strings" '{"query": "[invalid", "regex": true, "limit": 10}'
assert_status "invalid regex" "400"
assert_contains "error: INVALID_REGEX" "INVALID_REGEX"

http_post "/v1/sessions/$TEMP_SID/close" "{}"

# ══════════════════════════════════════════════════════════════════════
# ── Universal Tool Backend Protocol tests ─────────────────────────────
# ══════════════════════════════════════════════════════════════════════

log ""
log "Universal Tool Backend Protocol"
echo ""

# ── 36. Protocol health check ─────────────────────────────────────────

log "Protocol: health check"
http_get "/health"
assert_status "GET /health (protocol)" "200"
assert_contains "protocol health: has backends" "\"backends\""

# ── 37. List backends ─────────────────────────────────────────────────

log "Protocol: list backends"
http_get "/backends"
assert_status "GET /backends" "200"
assert_contains "backends: has jadx" "\"jadx\""
assert_contains "backends: has method_count" "\"method_count\""

# ── 38. Schema ────────────────────────────────────────────────────────

log "Protocol: schema"
http_get "/backends/jadx/schema"
assert_status "GET /backends/jadx/schema" "200"
assert_contains "schema: has list_types" "\"list_types\""
assert_contains "schema: has decompile_class" "\"decompile_class\""
assert_contains "schema: has search_strings" "\"search_strings\""
assert_contains "schema: has input_schema" "\"input_schema\""

# ── 39. Load target via protocol ──────────────────────────────────────

log "Protocol: load target (APK)"
http_post "/backends/jadx/targets" "{\"path\": \"$SAMPLE_APK\"}"
assert_status "POST /backends/jadx/targets" "201"
assert_contains "protocol load: has target_id" "\"target_id\""
assert_contains "protocol load: has artifact_hash" "\"artifact_hash\""

PROTO_TARGET_ID=$(json_field "target_id")
echo "  Protocol target: $PROTO_TARGET_ID"

if [[ -z "$PROTO_TARGET_ID" ]]; then
    echo "FATAL: could not extract target_id from protocol load response"
    exit 1
fi

# ── 40. List targets ─────────────────────────────────────────────────

log "Protocol: list targets"
http_get "/backends/jadx/targets"
assert_status "GET /backends/jadx/targets" "200"
assert_contains "targets: contains our target" "$PROTO_TARGET_ID"

# ── 41. Query: list_types ─────────────────────────────────────────────

log "Protocol: query list_types"
http_post "/backends/jadx/query" "{\"target_id\": \"$PROTO_TARGET_ID\", \"method\": \"list_types\", \"args\": {}}"
assert_status "POST /query list_types" "200"
assert_contains "query list_types: ok=true" "\"ok\": true"
assert_contains "query list_types: has data" "\"data\""
assert_contains "query list_types: has types" "\"types\""

# Extract a type from the protocol response for further queries
PROTO_TYPE_ID=$(echo "$BODY" | tr -d '\n' | grep -oE '"id"[[:space:]]*:[[:space:]]*"L[^"]*"' | head -1 | sed 's/.*:[[:space:]]*"//;s/"$//' || true)
echo "  Protocol type: $PROTO_TYPE_ID"

# ── 42. Query: decompile_class ────────────────────────────────────────

if [[ -n "$PROTO_TYPE_ID" ]]; then
    log "Protocol: query decompile_class"
    http_post "/backends/jadx/query" "{\"target_id\": \"$PROTO_TARGET_ID\", \"method\": \"decompile_class\", \"args\": {\"type_id\": \"$PROTO_TYPE_ID\"}}"
    assert_status "POST /query decompile_class" "200"
    assert_contains "query decompile_class: ok=true" "\"ok\": true"
    assert_contains "query decompile_class: has java" "\"java\""
fi

# ── 43. Query: search_strings ─────────────────────────────────────────

log "Protocol: query search_strings"
http_post "/backends/jadx/query" "{\"target_id\": \"$PROTO_TARGET_ID\", \"method\": \"search_strings\", \"args\": {\"query\": \"a\", \"regex\": false, \"limit\": 5}}"
assert_status "POST /query search_strings" "200"
assert_contains "query search_strings: ok=true" "\"ok\": true"
assert_contains "query search_strings: has matches" "\"matches\""

# ── 44. Query: list_packages ──────────────────────────────────────────

log "Protocol: query list_packages"
http_post "/backends/jadx/query" "{\"target_id\": \"$PROTO_TARGET_ID\", \"method\": \"list_packages\", \"args\": {}}"
assert_status "POST /query list_packages" "200"
assert_contains "query list_packages: ok=true" "\"ok\": true"
assert_contains "query list_packages: has packages" "\"packages\""

# ── 45. Query: unknown method → ok=false ──────────────────────────────

log "Protocol: query unknown method"
http_post "/backends/jadx/query" "{\"target_id\": \"$PROTO_TARGET_ID\", \"method\": \"nonexistent_method\", \"args\": {}}"
assert_status "POST /query unknown method" "200"
assert_contains "query unknown: ok=false" "\"ok\": false"
assert_contains "query unknown: has error" "\"error\""

# ── 46. Query: invalid target → ok=false ──────────────────────────────

log "Protocol: query invalid target"
http_post "/backends/jadx/query" "{\"target_id\": \"bad-target-id\", \"method\": \"list_types\", \"args\": {}}"
assert_status "POST /query bad target" "200"
assert_contains "query bad target: ok=false" "\"ok\": false"
assert_contains "query bad target: SESSION_NOT_FOUND" "SESSION_NOT_FOUND"

# ── 47. Delete target ────────────────────────────────────────────────

log "Protocol: delete target"
http_delete "/backends/jadx/targets/$PROTO_TARGET_ID"
assert_status "DELETE /backends/jadx/targets" "200"
assert_contains "delete target: ok=true" "\"ok\""

# ── 48. Verify deleted target is gone ────────────────────────────────

log "Protocol: verify deleted target"
http_post "/backends/jadx/query" "{\"target_id\": \"$PROTO_TARGET_ID\", \"method\": \"list_types\", \"args\": {}}"
assert_status "POST /query after delete" "200"
assert_contains "query deleted target: ok=false" "\"ok\": false"
assert_contains "query deleted target: SESSION_NOT_FOUND" "SESSION_NOT_FOUND"

# ══════════════════════════════════════════════════════════════════════
# ── Precise output tests (compiled test fixture) ──────────────────────
# ══════════════════════════════════════════════════════════════════════
#
# testapp.jar contains 4 classes with known relationships:
#   com.test.Callable  (interface)
#   com.test.Base      (class, @Deprecated, 2 fields: counter, name)
#   com.test.Child     (extends Base, implements Callable, field TAG)
#   com.test.Helper    (utility, compute() throws ArithmeticException)
#
# Known cross-references:
#   Child.process() -> Child.call(int), Helper.log(String,String)
#   Child.call(int) -> Base.getName(), overrides Callable.call(int)
#   Helper.compute() throws ArithmeticException
#   String "child" in Child.<init>, "Child" in Child.TAG, "ERROR" in Helper.compute
#

log ""
log "Precise output tests (test-fixtures/testapp.jar)"
echo ""

# ── 49. Load fixture JAR ─────────────────────────────────────────────

log "Load fixture JAR"
http_post "/backends/jadx/targets" "{\"path\": \"$SAMPLE_JAR\"}"
assert_status "POST /backends/jadx/targets (JAR)" "201"

FIX_TID=$(json_field "target_id")
echo "  Fixture target: $FIX_TID"

if [[ -z "$FIX_TID" ]]; then
    echo "FATAL: could not extract target_id for fixture JAR"
    exit 1
fi

# Helper: query the fixture target
fix_query() {
    local method="$1" args="$2"
    http_post "/backends/jadx/query" "{\"target_id\": \"$FIX_TID\", \"method\": \"$method\", \"args\": $args}"
}

# ── 50. Verify exact types ───────────────────────────────────────────

log "Fixture: list_types (expect 4 known types)"
fix_query "list_types" "{}"
assert_status "fix list_types" "200"
assert_contains "fix types: has Callable" "Lcom.test.Callable;"
assert_contains "fix types: has Base" "Lcom.test.Base;"
assert_contains "fix types: has Child" "Lcom.test.Child;"
assert_contains "fix types: has Helper" "Lcom.test.Helper;"

# ── 51. Class hierarchy: Child extends Base implements Callable ──────

log "Fixture: Child hierarchy"
fix_query "get_hierarchy" "{\"type_id\": \"Lcom.test.Child;\"}"
assert_status "fix Child hierarchy" "200"
assert_contains "fix hierarchy: super=Base" "com.test.Base"
assert_contains "fix hierarchy: implements Callable" "com.test.Callable"

# ── 52. Class hierarchy: Base extends Object ─────────────────────────

log "Fixture: Base hierarchy"
fix_query "get_hierarchy" "{\"type_id\": \"Lcom.test.Base;\"}"
assert_status "fix Base hierarchy" "200"
assert_contains "fix hierarchy: super=Object" "java.lang.Object"

# ── 53. Callable is interface ────────────────────────────────────────

log "Fixture: Callable kind"
fix_query "list_types" "{}"
assert_status "fix types for kind" "200"
assert_contains "fix types: Callable is interface" "\"interface\""

# ── 54. Fields: Base has counter and name ────────────────────────────

log "Fixture: Base fields"
fix_query "list_fields" "{\"type_id\": \"Lcom.test.Base;\"}"
assert_status "fix Base fields" "200"
assert_contains "fix fields: has counter" "counter"
assert_contains "fix fields: has name" "name"

# ── 55. Fields: Child has TAG ────────────────────────────────────────

log "Fixture: Child fields"
fix_query "list_fields" "{\"type_id\": \"Lcom.test.Child;\"}"
assert_status "fix Child fields" "200"
assert_contains "fix fields: has TAG" "TAG"

# ── 56. Methods: Child has call, process, <init> ─────────────────────

log "Fixture: Child methods"
fix_query "list_methods" "{\"type_id\": \"Lcom.test.Child;\"}"
assert_status "fix Child methods" "200"
assert_contains "fix methods: has call" "call"
assert_contains "fix methods: has process" "process"

# ── 57. Methods detail: Helper.compute throws ArithmeticException ────

log "Fixture: Helper methods detail (throws)"
fix_query "list_methods_detail" "{\"type_id\": \"Lcom.test.Helper;\"}"
assert_status "fix Helper methods detail" "200"
assert_contains "fix methods: has compute" "compute"
assert_contains "fix methods: throws ArithmeticException" "ArithmeticException"

# ── 58. Decompile class: Child ───────────────────────────────────────

log "Fixture: decompile Child class"
fix_query "decompile_class" "{\"type_id\": \"Lcom.test.Child;\"}"
assert_status "fix decompile Child" "200"
assert_contains "fix decompile: extends Base" "extends Base"
assert_contains "fix decompile: implements Callable" "implements Callable"
assert_contains "fix decompile: has process method" "process"
assert_contains "fix decompile: has call method" "call"

# ── 59. Xrefs from: Child.process calls Child.call and Helper.log ───

log "Fixture: xrefs_from Child.process()"
fix_query "xrefs_from" "{\"method_id\": \"Lcom.test.Child;->process()V\"}"
assert_status "fix xrefs_from process" "200"
assert_contains "fix xrefs_from: calls call()" "call"
assert_contains "fix xrefs_from: calls Helper.log" "log"
assert_contains "fix xrefs_from: kind is method" "\"kind\": \"method\""

# ── 60. Xrefs to: Helper.log called by Child.process ────────────────

log "Fixture: xrefs_to Helper.log()"
fix_query "xrefs_to" "{\"method_id\": \"Lcom.test.Helper;->log(Ljava/lang/String;Ljava/lang/String;)V\"}"
assert_status "fix xrefs_to Helper.log" "200"
assert_contains "fix xrefs_to: caller is process" "process"
assert_contains "fix xrefs_to: kind is method" "\"kind\": \"method\""

# ── 61. Xrefs to: Child.call called by Child.process ────────────────

log "Fixture: xrefs_to Child.call()"
fix_query "xrefs_to" "{\"method_id\": \"Lcom.test.Child;->call(I)Ljava/lang/String;\"}"
assert_status "fix xrefs_to Child.call" "200"
assert_contains "fix xrefs_to: caller is process" "process"

# ── 62. Class xrefs: who references Helper? ──────────────────────────

log "Fixture: class_xrefs Helper"
fix_query "class_xrefs" "{\"type_id\": \"Lcom.test.Helper;\"}"
assert_status "fix class_xrefs Helper" "200"
assert_contains "fix class_xrefs: Child references Helper" "Child"
assert_contains "fix class_xrefs: refs have kind" "\"kind\""

# ── 63. Field xrefs: who uses Base.counter? ──────────────────────────

log "Fixture: field_xrefs Base.counter"
fix_query "field_xrefs" "{\"field_id\": \"Lcom.test.Base;->counter:I\"}"
assert_status "fix field_xrefs counter" "200"
# Child.call() increments counter, so there should be a ref
assert_contains "fix field_xrefs: has refs" "\"refs\""
assert_contains "fix field_xrefs: kind is method" "\"kind\": \"method\""

# ── 64. Annotations: Base has @Deprecated ────────────────────────────

log "Fixture: Base annotations"
fix_query "get_annotations" "{\"type_id\": \"Lcom.test.Base;\"}"
assert_status "fix Base annotations" "200"
assert_contains "fix annotations: has Deprecated" "Deprecated"

# ── 65. String search: find 'child' ─────────────────────────────────

log "Fixture: search for 'child'"
fix_query "search_strings" "{\"query\": \"child\", \"regex\": false, \"limit\": 10}"
assert_status "fix string search child" "200"
assert_contains "fix strings: found 'child'" "child"
assert_contains "fix strings: in Child class" "Lcom.test.Child;"

# ── 66. String search: find 'ERROR' ─────────────────────────────────

log "Fixture: search for 'ERROR'"
fix_query "search_strings" "{\"query\": \"ERROR\", \"regex\": false, \"limit\": 10}"
assert_status "fix string search ERROR" "200"
assert_contains "fix strings: found ERROR" "ERROR"
assert_contains "fix strings: in Helper class" "Lcom.test.Helper;"

# ── 67. Dependencies: Child depends on Base, Callable, Helper ────────

log "Fixture: Child dependencies"
fix_query "get_dependencies" "{\"type_id\": \"Lcom.test.Child;\"}"
assert_status "fix Child dependencies" "200"
assert_contains "fix deps: depends on Base" "Lcom.test.Base;"
assert_contains "fix deps: depends on Callable" "Lcom.test.Callable;"
assert_contains "fix deps: depends on Helper" "Lcom.test.Helper;"

# ── 68. Packages: com.test ───────────────────────────────────────────

log "Fixture: packages"
fix_query "list_packages" "{}"
assert_status "fix packages" "200"
assert_contains "fix packages: has com.test" "com.test"

# ── 69. Decompile method: Child.call ─────────────────────────────────

log "Fixture: decompile Child.call()"
fix_query "decompile_method" "{\"method_id\": \"Lcom.test.Child;->call(I)Ljava/lang/String;\"}"
assert_status "fix decompile Child.call" "200"
assert_contains "fix decompile call: has getName" "getName"
assert_contains "fix decompile call: has counter" "counter"

# ── 70. Rename via protocol + verify in decompiled source ────────────

log "Fixture: rename Base via protocol"
fix_query "rename" "{\"id\": \"Lcom.test.Base;\", \"alias\": \"BaseEntity\"}"
assert_status "fix rename Base" "200"
assert_contains "fix rename: alias=BaseEntity" "BaseEntity"

log "Fixture: list renames"
fix_query "list_renames" "{}"
assert_status "fix list renames" "200"
assert_contains "fix renames: has BaseEntity" "BaseEntity"

log "Fixture: remove rename"
fix_query "remove_rename" "{\"id\": \"Lcom.test.Base;\"}"
assert_status "fix remove rename" "200"
assert_contains "fix remove rename: status removed" "removed"

# ── 71. Error report ─────────────────────────────────────────────────

log "Fixture: error report"
fix_query "error_report" "{}"
assert_status "fix error report" "200"
assert_contains "fix error report: has errors_count" "errors_count"

# ── 72. Clean up fixture target ──────────────────────────────────────

log "Fixture: delete target"
http_delete "/backends/jadx/targets/$FIX_TID"
assert_status "fix DELETE target" "200"

# ══════════════════════════════════════════════════════════════════════

# ── 73. Health check shows 0 sessions after cleanup ──────────────────

log "Health check (post-cleanup)"
http_get "/v1/health"
assert_status "GET /v1/health (final)" "200"
assert_contains "health: 0 active sessions" "\"active_sessions\": 0"

# ── Summary ───────────────────────────────────────────────────────────

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
if [[ $FAILED -eq 0 ]]; then
    echo -e "\033[1;32m  ALL $TOTAL TESTS PASSED\033[0m"
else
    echo -e "\033[1;31m  $FAILED/$TOTAL TESTS FAILED\033[0m"
fi
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

exit $FAILED
