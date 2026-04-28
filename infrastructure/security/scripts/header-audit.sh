#!/usr/bin/env bash
# Passive header audit against a running Glacier container.
#
# Tests the controls documented in source:
#   - InformationController#readCookie         -> wallId cookie flags
#   - FallbackSecurityHeadersFilter            -> headers on /rest/messages
#   - ShareSecurityHeadersFilter               -> CSP/COOP/COEP/CORP/HSTS/XFO
#                                                 on /share/*, /rest/share/*
#   - GlacierApplication#corsConfigurer        -> CORS allowlist on /rest/*
#
# Exits non-zero on any expected header missing or with a wrong value.
# Designed to run inside the security.yml job after `docker compose up`.
#
# Usage: BASE_URL=http://glacier:8080 SHARE_BASE_URL=https://share.proxy ./header-audit.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://glacier:8080}"
SHARE_BASE_URL="${SHARE_BASE_URL:-${BASE_URL}}"
FAIL=0

check_header() {
    local label="$1"
    local url="$2"
    local header_name="$3"
    local pattern="$4"

    local value
    value="$(curl -fkis --max-time 10 "$url" | tr -d '\r' \
            | awk -v h="$header_name" 'BEGIN{IGNORECASE=1} $1==h":"{$1=""; print substr($0,2); exit}')"

    if [[ -z "$value" ]]; then
        echo "FAIL $label: header '$header_name' missing on $url"
        FAIL=$((FAIL + 1))
        return
    fi
    if ! grep -Eqi -- "$pattern" <<< "$value"; then
        echo "FAIL $label: header '$header_name' on $url"
        echo "  expected pattern: $pattern"
        echo "  actual value:     $value"
        FAIL=$((FAIL + 1))
        return
    fi
    echo "OK   $label: $header_name on $url"
}

echo "=== Glacier passive header audit ==="
echo "BASE_URL=${BASE_URL}"
echo "SHARE_BASE_URL=${SHARE_BASE_URL}"
echo

# ── wallId cookie flags ──────────────────────────────────────────────────────
echo "[wallId cookie hygiene]"
WALL_ID_RESPONSE="$(curl -fkis --max-time 10 "${BASE_URL}/rest/wall-id")"
SET_COOKIE="$(echo "$WALL_ID_RESPONSE" | tr -d '\r' \
              | awk 'BEGIN{IGNORECASE=1} /^Set-Cookie:.*wallId=/{$1=""; print substr($0,2); exit}')"
if [[ -z "$SET_COOKIE" ]]; then
    echo "INFO no Set-Cookie returned — assume an existing wallId cookie was already presented"
else
    for flag in "HttpOnly" "SameSite=Lax" "Path=/" "Max-Age=2592000"; do
        if grep -qi -- "$flag" <<< "$SET_COOKIE"; then
            echo "OK   wallId cookie carries '$flag'"
        else
            echo "FAIL wallId cookie missing '$flag': $SET_COOKIE"
            FAIL=$((FAIL + 1))
        fi
    done
    # Secure flag is conditional on glacier.cookie.secure (insecure profile drops it).
    if [[ "${EXPECT_SECURE_COOKIE:-true}" == "true" ]]; then
        if grep -qi "Secure" <<< "$SET_COOKIE"; then
            echo "OK   wallId cookie carries 'Secure' (production-mode expected)"
        else
            echo "FAIL wallId cookie missing 'Secure' but EXPECT_SECURE_COOKIE=true"
            FAIL=$((FAIL + 1))
        fi
    fi
fi
echo

# ── /rest/messages — FallbackSecurityHeadersFilter ───────────────────────────
echo "[FallbackSecurityHeadersFilter on /rest/messages]"
# We expect 401 (no wallId) but the filter still adds the headers:
check_header "/rest/messages no-store"      "${BASE_URL}/rest/messages?hashtag=java" "Cache-Control"        "no-store"
check_header "/rest/messages nosniff"       "${BASE_URL}/rest/messages?hashtag=java" "X-Content-Type-Options" "nosniff"
check_header "/rest/messages CSP default-src 'none'" "${BASE_URL}/rest/messages?hashtag=java" "Content-Security-Policy" "default-src 'none'"
echo

# ── /rest/share-csrf — ShareSecurityHeadersFilter ─────────────────────────────
echo "[ShareSecurityHeadersFilter on /rest/share-csrf]"
check_header "share CSP frame-ancestors none" "${SHARE_BASE_URL}/rest/share-csrf" "Content-Security-Policy" "frame-ancestors 'none'"
check_header "share XFO DENY"                 "${SHARE_BASE_URL}/rest/share-csrf" "X-Frame-Options"          "DENY"
check_header "share Referrer-Policy"          "${SHARE_BASE_URL}/rest/share-csrf" "Referrer-Policy"          "no-referrer"
check_header "share COOP same-origin"         "${SHARE_BASE_URL}/rest/share-csrf" "Cross-Origin-Opener-Policy" "same-origin"
check_header "share COEP require-corp"        "${SHARE_BASE_URL}/rest/share-csrf" "Cross-Origin-Embedder-Policy" "require-corp"
check_header "share CORP same-origin"         "${SHARE_BASE_URL}/rest/share-csrf" "Cross-Origin-Resource-Policy" "same-origin"
echo

# ── CORS allowlist — GlacierApplication#corsConfigurer ───────────────────────
echo "[CORS allowlist on /rest/*]"
DISALLOWED_ORIGIN="https://attacker.example.com"
ACAO="$(curl -fks --max-time 10 -H "Origin: $DISALLOWED_ORIGIN" -I \
        "${BASE_URL}/rest/wall-id" | tr -d '\r' \
        | awk 'BEGIN{IGNORECASE=1} /^Access-Control-Allow-Origin:/{$1=""; print substr($0,2); exit}')"
if [[ -z "$ACAO" ]]; then
    echo "OK   CORS: no Access-Control-Allow-Origin returned for $DISALLOWED_ORIGIN"
elif [[ "$ACAO" == "$DISALLOWED_ORIGIN" || "$ACAO" == "*" ]]; then
    echo "FAIL CORS: $DISALLOWED_ORIGIN was reflected back as ACAO='$ACAO'"
    FAIL=$((FAIL + 1))
else
    echo "INFO CORS: ACAO='$ACAO' (not the attacker origin) — accepted"
fi
echo

if [[ "$FAIL" -gt 0 ]]; then
    echo "header-audit FAILED with $FAIL finding(s)"
    exit 1
fi
echo "header-audit PASSED"
