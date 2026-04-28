#!/bin/bash
set -euo pipefail

# register-edc-asset.sh
#
# Registers pilots-process-service as an HttpData Asset in both participant EDC
# connectors (two-participant docker-compose.yml in ../pilots-dataspace).
#
# For each participant the script creates:
#   1. Asset          — HttpData pointing at that participant's process service
#   2. Policy         — permissive open policy (no constraints)
#   3. ContractDef    — binds the asset to the open policy
#
# Prerequisites:
#   - Both connectors are running and healthy (docker compose up -d)
#   - jq is installed (used only for the verification hint at the end)
#
# Override any variable via the environment, e.g.:
#   P2_PROCESS_SERVICE_URL=http://192.168.1.10:8081/serviceInstances ./register-edc-asset.sh

# ── Configuration ─────────────────────────────────────────────────────────────
#
# Management API base URLs
# Source: docker-compose.yml port mappings
#   participant-1-controlplane: 19193:19193
#   participant-2-controlplane: 29193:29193
P1_MGMT="${P1_MGMT:-http://localhost:19193/management}"
P2_MGMT="${P2_MGMT:-http://localhost:29193/management}"

# API key
# Source: edc.api.auth.key=password in both controlplane-participant-*.properties
#         EDC_API_KEY=password in deployment/connector/.env
API_KEY="${EDC_API_KEY:-password}"

# Process service URLs as seen FROM INSIDE Docker containers (the EDC data-plane
# resolves these when proxying a transfer request).
#
# Default: container hostnames on the shared dataspace Docker network.
# Both instances listen on port 8080 inside their containers; docker-compose.yml
# maps p2 to host-port 8081, but the internal port stays 8080.
#
# If running the process service directly on the host instead of Docker, use:
#   P1_PROCESS_SERVICE_URL=http://172.17.0.1:8080/serviceInstances   (Linux)
#   P1_PROCESS_SERVICE_URL=http://host.docker.internal:8080/...       (macOS/Win)
P1_PROCESS_SERVICE_URL="${P1_PROCESS_SERVICE_URL:-http://p1-process-service:8080/serviceInstances}"
P2_PROCESS_SERVICE_URL="${P2_PROCESS_SERVICE_URL:-http://p2-process-service:8080/serviceInstances}"

# ── Stable IDs ────────────────────────────────────────────────────────────────
# Each connector has its own database, so IDs are scoped per-connector.
# Using participant suffix on the asset ID keeps catalogs unambiguous when
# a consumer browses both providers.
P1_ASSET_ID="process-service-asset-p1"
P2_ASSET_ID="process-service-asset-p2"

# Policy and contract-def IDs are the same name in each connector's own DB.
POLICY_ID="process-service-open-policy"
CONTRACT_DEF_ID="process-service-contract-def"

# ── Helper ────────────────────────────────────────────────────────────────────
# post_json LABEL ENDPOINT BODY
# 200/201/204 = success; 409 = already exists (idempotent re-run); anything
# else = fatal.
post_json() {
  local label="$1"
  local url="$2"
  local body="$3"

  local result http_code response_body
  result=$(curl -s -w "\n%{http_code}" -X POST "$url" \
    -H "Content-Type: application/json" \
    -H "X-Api-Key: ${API_KEY}" \
    -d "$body")
  http_code=$(echo "$result" | tail -1)
  response_body=$(echo "$result" | sed '$d')

  case "$http_code" in
    200|201|204)
      echo "  [OK $http_code]   $label"
      ;;
    409)
      echo "  [SKIP 409] $label (already exists)"
      ;;
    *)
      echo "  [FAIL $http_code] $label"
      echo "             $response_body"
      return 1
      ;;
  esac
}

# ── Per-participant registration ──────────────────────────────────────────────
register_participant() {
  local participant_label="$1"
  local mgmt="$2"
  local process_service_url="$3"
  local asset_id="$4"

  echo ""
  echo "=== $participant_label ==="
  echo "    Management API : $mgmt"
  echo "    Process service: $process_service_url"
  echo ""

  # 1. Asset — HttpData with full proxy flags so EDC data-plane forwards the
  #    complete HTTP method, path, query string, and body to the process service.
  post_json "Asset '$asset_id'" "${mgmt}/v3/assets" \
    "{
      \"@context\": { \"@vocab\": \"https://w3id.org/edc/v0.0.1/ns/\" },
      \"@id\": \"${asset_id}\",
      \"properties\": {
        \"name\": \"Pilots Process Service\",
        \"description\": \"Flowable BPMN-backed Service Instances API (${participant_label})\",
        \"contenttype\": \"application/json\"
      },
      \"dataAddress\": {
        \"type\": \"HttpData\",
        \"baseUrl\": \"${process_service_url}\",
        \"proxyPath\": \"true\",
        \"proxyMethod\": \"true\",
        \"proxyQueryParams\": \"true\",
        \"proxyBody\": \"true\"
      }
    }"

  # 2. Policy — permissive ODRL Set with no permissions, prohibitions, or
  #    obligations; any counterparty holding a valid membership credential can
  #    negotiate a contract.
  post_json "Policy '$POLICY_ID'" "${mgmt}/v3/policydefinitions" \
    "{
      \"@context\": {
        \"@vocab\": \"https://w3id.org/edc/v0.0.1/ns/\",
        \"odrl\": \"http://www.w3.org/ns/odrl/2/\"
      },
      \"@id\": \"${POLICY_ID}\",
      \"policy\": {
        \"@context\": \"http://www.w3.org/ns/odrl.jsonld\",
        \"@type\": \"Set\",
        \"permission\": [],
        \"prohibition\": [],
        \"obligation\": []
      }
    }"

  # 3. Contract Definition — binds this asset to the open policy for both access
  #    control and contract terms. The assetsSelector pins it to the exact asset.
  post_json "ContractDefinition '$CONTRACT_DEF_ID'" "${mgmt}/v3/contractdefinitions" \
    "{
      \"@context\": { \"@vocab\": \"https://w3id.org/edc/v0.0.1/ns/\" },
      \"@id\": \"${CONTRACT_DEF_ID}\",
      \"accessPolicyId\": \"${POLICY_ID}\",
      \"contractPolicyId\": \"${POLICY_ID}\",
      \"assetsSelector\": [
        {
          \"@type\": \"Criterion\",
          \"operandLeft\": \"https://w3id.org/edc/v0.0.1/ns/id\",
          \"operator\": \"=\",
          \"operandRight\": \"${asset_id}\"
        }
      ]
    }"
}

# ── Main ──────────────────────────────────────────────────────────────────────
echo "pilots-process-service EDC asset registration"
echo ""
echo "P1 management : $P1_MGMT"
echo "P2 management : $P2_MGMT"
echo "P1 process svc: $P1_PROCESS_SERVICE_URL"
echo "P2 process svc: $P2_PROCESS_SERVICE_URL"

register_participant "Participant 1" "$P1_MGMT" "$P1_PROCESS_SERVICE_URL" "$P1_ASSET_ID"
register_participant "Participant 2" "$P2_MGMT" "$P2_PROCESS_SERVICE_URL" "$P2_ASSET_ID"

echo ""
echo "=== Registration complete ==="
echo ""
echo "Verify assets are visible in each connector's catalog:"
echo ""
echo "  # Participant 1 asset list"
echo "  curl -s -X POST ${P1_MGMT}/v3/assets/request \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -H 'X-Api-Key: ${API_KEY}' \\"
echo "    -d '{\"@context\":{\"@vocab\":\"https://w3id.org/edc/v0.0.1/ns/\"}}' | jq ."
echo ""
echo "  # Participant 2 asset list"
echo "  curl -s -X POST ${P2_MGMT}/v3/assets/request \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -H 'X-Api-Key: ${API_KEY}' \\"
echo "    -d '{\"@context\":{\"@vocab\":\"https://w3id.org/edc/v0.0.1/ns/\"}}' | jq ."
