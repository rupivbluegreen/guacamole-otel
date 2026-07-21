#!/usr/bin/env bash
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Phase 2 integration test: brings up guacamole + guacd + postgres + otelcol,
# drives one SSH session, and asserts (a) the session span arrived on close,
# (b) the session.connected log arrived at connect, (c) a guacd log line and the
# session share guacamole.connection.id (Gap 2). Then kills the collector and
# asserts login + session are unaffected (SPEC §15.4).
#
# Prereq: build the extension jar first —
#   docker run --rm -v "$PWD/../extension":/w -w /w maven:3.9-eclipse-temurin-17 mvn -q -B package
# Requires: docker, docker compose, curl. Uses ghcr.io/vi/websocat for the tunnel.
set -euo pipefail
cd "$(dirname "$0")"

BASE_URL="http://localhost:18080/guacamole"
WS_BASE="ws://localhost:18080/guacamole/websocket-tunnel"
F=out/telemetry.json

fail() { echo "ITEST FAIL: $*" >&2; exit 1; }

drive_session() {
  local token url
  token=$(curl -s --max-time 10 -X POST "$BASE_URL/api/tokens" \
      -d 'username=guacadmin&password=guacadmin' \
      | sed -n 's/.*"authToken":"\([^"]*\)".*/\1/p')
  [ -n "$token" ] || return 1
  url="$WS_BASE?token=$token&GUAC_DATA_SOURCE=postgresql&GUAC_ID=1&GUAC_TYPE=c&GUAC_WIDTH=1024&GUAC_HEIGHT=768&GUAC_DPI=96&GUAC_TIMEZONE=UTC&GUAC_AUDIO=audio%2FL16&GUAC_IMAGE=image%2Fpng"
  timeout 8 docker run --rm --network host ghcr.io/vi/websocat:latest \
      -H='Sec-WebSocket-Protocol: guacamole' --protocol guacamole "$url" >/dev/null 2>&1 || true
  return 0
}

echo "== bringing up the stack =="
mkdir -p out && rm -f "$F"
docker compose up -d
for i in $(seq 1 40); do
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 4 "$BASE_URL/" 2>/dev/null || true)
  [ "$code" = "200" ] && break
  sleep 3
done
[ "$code" = "200" ] || fail "guacamole did not become ready"

echo "== driving a session =="
drive_session || fail "could not authenticate"
sleep 8

echo "== correlation assertions =="
[ -s "$F" ] || fail "no telemetry exported"
cid=$(grep -oE '\$[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' "$F" | sort -u | head -1)
[ -n "$cid" ] || fail "no guacd connection id in telemetry"
grep -q '"guacamole.session"' "$F"       || fail "session span not present (a)"
grep -q 'guacamole.session.connected' "$F" || fail "session.connected log not present (b)"
grep -q -F "Connection ID is \"$cid\"" "$F" || fail "guacd log line for $cid not ingested (c)"
occ=$(grep -oF "$cid" "$F" | wc -l)
[ "$occ" -ge 3 ] || fail "connection id shared across too few records ($occ)"
echo "  PASS: L2 telemetry and L3 guacd log share guacamole.connection.id=$cid ($occ records)"

echo "== kill test: collector down, login + session must still work (§15.4) =="
docker compose stop otelcol >/dev/null 2>&1
drive_session || fail "login/session failed while collector was down"
sleep 2
docker compose logs guacd --since 20s 2>&1 | grep -q 'SSH connection successful' \
    || fail "guacd did not establish the session while collector down"
[ "$(docker compose ps guacamole --format '{{.Status}}' | grep -c Up)" -ge 1 ] \
    || fail "guacamole not healthy after collector kill"
docker compose start otelcol >/dev/null 2>&1
echo "  PASS: login + session unaffected with collector down"

echo "ITEST PASS"
