#!/bin/bash
set -euo pipefail

# start-local.sh — bring up the full two-participant local environment:
#
#   pilots-dataspace  (EDC connectors, identity hubs, vaults, Postgres)
#   p1-process-service + p2-process-service  (Flowable BPMN process API)
#   p1-frontend + p2-frontend  (React UI served via nginx)
#
# Usage:
#   ./start-local.sh          # normal start (preserves existing data)
#   ./start-local.sh --clean  # wipe dataspace volumes before starting

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATASPACE_DIR="$SCRIPT_DIR/../pilots-dataspace"

CLEAN=false
for arg in "$@"; do
  case "$arg" in
    --clean) CLEAN=true ;;
    -h|--help)
      echo "Usage: ./start-local.sh [--clean]"
      echo ""
      echo "  --clean   Wipe dataspace volumes (database, vault) before starting"
      exit 0
      ;;
    *)
      echo "Unknown option: $arg"
      echo "Usage: ./start-local.sh [--clean]"
      exit 1
      ;;
  esac
done

# ── Prerequisites ─────────────────────────────────────────────────────────────

echo "=== Checking prerequisites ==="
MISSING=()
for cmd in docker curl jq; do
  command -v "$cmd" &>/dev/null || MISSING+=("$cmd")
done
docker compose version &>/dev/null 2>&1 || MISSING+=("docker compose (v2 plugin)")

if [ ${#MISSING[@]} -gt 0 ]; then
  echo "ERROR: Missing:"
  for m in "${MISSING[@]}"; do echo "  - $m"; done
  exit 1
fi
echo "  OK"
echo ""

# ── Step 1: Start pilots-dataspace ────────────────────────────────────────────

echo "=== Step 1: Start pilots-dataspace ==="
cd "$DATASPACE_DIR"
if [ "$CLEAN" = true ]; then
  ./start.sh --clean
else
  ./start.sh
fi
echo ""

# ── Step 2: Build process service JAR ─────────────────────────────────────────

echo "=== Step 2: Build process service JAR ==="
cd "$SCRIPT_DIR"
./gradlew bootJar -x test -q
echo "  JAR built."
echo ""

# ── Step 3: Start process services + frontends ────────────────────────────────

echo "=== Step 3: Start process services and frontends ==="
cd "$SCRIPT_DIR"
docker compose up -d --build
echo ""

# ── Step 4: Wait for process services ─────────────────────────────────────────

echo "=== Step 4: Wait for process services to be ready ==="
for entry in "P1:http://localhost:8080" "P2:http://localhost:8081"; do
  label="${entry%%:*}"
  url="${entry#*:}"
  echo -n "  Waiting for $label process service ($url)..."
  ELAPSED=0
  until curl -sf "$url/serviceInstances" > /dev/null 2>&1; do
    sleep 3
    ELAPSED=$((ELAPSED + 3))
    if [ "$ELAPSED" -ge 90 ]; then
      echo " TIMEOUT"
      echo "ERROR: $label process service did not become ready within 90s."
      exit 1
    fi
    echo -n "."
  done
  echo " ready"
done
echo ""

# ── Step 5: Register process services as EDC assets ──────────────────────────

echo "=== Step 5: Register EDC assets ==="
cd "$SCRIPT_DIR"
./register-edc-asset.sh
echo ""

# ── Done ──────────────────────────────────────────────────────────────────────

echo "========================================"
echo "  Full local environment is ready!"
echo "========================================"
echo ""
echo "Process services (direct):"
echo "  P1 Certiweight: http://localhost:8080/serviceInstances"
echo "  P2 Shipper:     http://localhost:8081/serviceInstances"
echo ""
echo "Frontends:"
echo "  P1 Certiweight: http://localhost:3002"
echo "  P2 Shipper:     http://localhost:3003"
echo ""
echo "EDC dashboards:"
echo "  P1: http://localhost:3000"
echo "  P2: http://localhost:3001"
echo ""
echo "EDC management APIs:"
echo "  P1: http://localhost:19193/management  (X-Api-Key: password)"
echo "  P2: http://localhost:29193/management  (X-Api-Key: password)"
echo ""
echo "To stop everything:"
echo "  docker compose down                           # stop process services + frontends"
echo "  cd ../pilots-dataspace && docker compose down # stop dataspace"
echo ""
