#!/bin/bash
# vgm-e2e-dataspace.sh
#
# Demo script — drives the Certiweight (P1 / provider) side of the VGM flow.
# The Shipper (P2 / consumer) side is operated via the frontend at localhost:3003.
#
# Usage:
#   ./vgm-e2e-dataspace.sh [SHIP_ID]
#
#   SHIP_ID  — optional UUID of an existing shipperProcess instance on P2.
#              If omitted the script queries P2's process service and picks
#              the most recently created active instance automatically.
#
# Demo setup (run once before starting this script):
#   1. Open http://localhost:3003  (Shipper frontend)
#   2. Create a new VGM request for a container
#   3. Run this script — it drives Certiweight's side of the workflow
#      and coordinates with the Shipper through the EDC dataspace
#
# Sections:
#   0. Prerequisites + find Shipper's VGM request
#   1. Secure channel setup (EDC contract negotiation)
#   2. Channel verification
#   3. VGM business flow
#   4. Final state verification
#
# Prerequisites:
#   pilots-dataspace stack running       : cd ../pilots-dataspace && docker compose up -d
#   Process services running & current   : docker compose up -d --build
#   Assets registered                    : ./register-edc-asset.sh
#   Shipper frontend                     : http://localhost:3003
#   Certiweight frontend                 : http://localhost:3002
#   jq + python3 available on PATH
#
# All cross-party API calls go through the EDC data-plane proxy using
# short-lived EDR bearer tokens — the dataspace enforces the contract policy.

set -euo pipefail

# Optional ship instance ID from command line or environment
SHIP_ID="${1:-${SHIP_ID:-}}"

# ── Colours ───────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[1;33m'
RED='\033[0;31m'; BOLD='\033[1m'; RESET='\033[0m'
ok()   { echo -e "${GREEN}  ✓ $*${RESET}" >&2; }
info() { echo -e "${CYAN}  → $*${RESET}" >&2; }
warn() { echo -e "${YELLOW}  ⚠ $*${RESET}" >&2; }
fail() { echo -e "${RED}  ✗ $*${RESET}" >&2; exit 1; }
hdr()  { echo -e "\n${BOLD}══════════════════════════════════════════════${RESET}\n${BOLD}  $*${RESET}\n${BOLD}══════════════════════════════════════════════${RESET}" >&2; }
step() { echo -e "\n${CYAN}${BOLD}▶  $*${RESET}" >&2; }

# next DESCRIPTION [CHANNEL_NOTE]
# Shows what will happen next and waits for Enter.
next() {
  local desc="$1"
  local channel="${2:-}"
  echo -e "" >&2
  echo -e "  ${BOLD}┌─────────────────────────────────────────────────────────────┐${RESET}" >&2
  echo -e "  ${BOLD}│  NEXT: ${RESET}${desc}" >&2
  [ -n "$channel" ] && echo -e "  ${BOLD}│        ${CYAN}${channel}${RESET}" >&2
  echo -e "  ${BOLD}└─────────────────────────────────────────────────────────────┘${RESET}" >&2
  read -rp "  Press Enter to continue... " _ >&2
  echo "" >&2
}

# party P1|P2 ACTION  — prints a clear party-action line
party() {
  local who="$1"; shift
  local label
  case "$who" in
    P1) label="${BOLD}[CERTIWEIGHT]${RESET}" ;;
    P2) label="${BOLD}[SHIPPER]${RESET}" ;;
    *)  label="${BOLD}[$who]${RESET}" ;;
  esac
  echo -e "  ${label}  $*" >&2
}

# notification SYSTEM EVENT DETAIL  — shows an ERP/TMS notification event
notification() {
  local system="$1" event="$2" detail="$3"
  echo -e "  ${YELLOW}  ⚙  ${BOLD}${system}${RESET}${YELLOW} received: ${BOLD}${event}${RESET}" >&2
  [ -n "$detail" ] && echo -e "       ${detail}" >&2
}

# flow_out LABEL JSON — show outbound payload
flow_out() {
  echo -e "${YELLOW}  ↑ $1${RESET}" >&2
  echo "$2" | python3 -c "
import sys, json
try: print(json.dumps(json.loads(sys.stdin.read()), indent=6))
except: pass" | sed 's/^/      /' >&2
}

# flow_in LABEL JSON — show inbound response (id, serviceDefinition, state, version)
flow_in() {
  echo -e "${GREEN}  ↓ $1${RESET}" >&2
  echo "$2" | python3 -c "
import sys, json
try:
    d = json.loads(sys.stdin.read())
    out = {k: d[k] for k in ('id', 'serviceDefinition', 'state', 'version') if k in d}
    print(json.dumps(out, indent=6))
except: pass" | sed 's/^/      /' >&2
}

# ── Configuration ──────────────────────────────────────────────────────────────
P1_MGMT="${P1_MGMT:-http://localhost:19193/management}"
P2_MGMT="${P2_MGMT:-http://localhost:29193/management}"
API_KEY="${EDC_API_KEY:-password}"

P1_ID="did:web:participant-1-identityhub%3A7093"
P2_ID="did:web:participant-2-identityhub%3A7083"

# DSP protocol endpoints — Docker hostnames (control planes call each other internally)
P1_PROTOCOL="http://participant-1-controlplane:19194/protocol"
P2_PROTOCOL="http://participant-2-controlplane:29194/protocol"

P1_ASSET="process-service-asset-p1"
P2_ASSET="process-service-asset-p2"

P1_PS="${P1_PS:-http://localhost:8080}"
P2_PS="${P2_PS:-http://localhost:8081}"

# EDC data-plane public endpoints — all cross-party calls go through here
P1_DP="${P1_DP:-http://localhost:38185/public}"
P2_DP="${P2_DP:-http://localhost:48185/public}"

# Internal ERP/TMS stub target (http-receiver container — replace with real URLs in production)
ERP_URL="${ERP_URL:-http://http-receiver:4000/erp}"
TMS_URL="${TMS_URL:-http://http-receiver:4000/tms}"

# ── Helpers ────────────────────────────────────────────────────────────────────
mgmt_get()  { curl -s -H "X-Api-Key: ${API_KEY}" "$1"; }
mgmt_post() { curl -s -X POST -H "Content-Type: application/json" -H "X-Api-Key: ${API_KEY}" -d "$2" "$1"; }

poll_state() {
  local url="$1" field="$2" expected="$3" max="${4:-20}"
  for i in $(seq 1 "$max"); do
    local state
    state=$(mgmt_get "$url" | python3 -c "import sys,json; print(json.load(sys.stdin).get('${field}',''))" 2>/dev/null || echo "")
    [ "$state" = "$expected" ] && return 0
    info "  waiting ($i/$max): $state"
    sleep 2
  done
  fail "Timed out waiting for ${expected} at ${url}"
}

# negotiate_edr LABEL CONSUMER_MGMT PROVIDER_PROTOCOL PROVIDER_ID ASSET_ID
# Prints: <transferProcessId>
negotiate_edr() {
  local label="$1" cmgmt="$2" proto="$3" pid="$4" asset="$5"

  info "Fetching ${label} catalog …"
  local catalog
  catalog=$(mgmt_post "${cmgmt}/v3/catalog/request" \
    "{\"@context\":{\"@vocab\":\"https://w3id.org/edc/v0.0.1/ns/\"},
      \"@type\":\"CatalogRequest\",
      \"counterPartyAddress\":\"${proto}\",
      \"counterPartyId\":\"${pid}\",
      \"protocol\":\"dataspace-protocol-http\",
      \"querySpec\":{\"@type\":\"QuerySpec\"}}")

  local offer_id
  offer_id=$(echo "$catalog" | python3 -c "
import sys, json
d = json.load(sys.stdin)
if isinstance(d, list): d = d[0]
ds = d.get('dcat:dataset', [])
if isinstance(ds, dict): ds = [ds]
for item in ds:
    if item.get('@id') == '${asset}':
        policy = item.get('odrl:hasPolicy', {})
        if isinstance(policy, list): policy = policy[0]
        print(policy.get('@id', ''))
        break
")
  [ -z "$offer_id" ] && fail "Could not find offer for ${asset} in ${label} catalog"
  ok "Found data offer for ${asset}"

  info "Negotiating contract …"
  local neg_id
  neg_id=$(mgmt_post "${cmgmt}/v3/contractnegotiations" \
    "{\"@context\":{\"@vocab\":\"https://w3id.org/edc/v0.0.1/ns/\"},
      \"@type\":\"ContractRequest\",
      \"counterPartyAddress\":\"${proto}\",
      \"counterPartyId\":\"${pid}\",
      \"protocol\":\"dataspace-protocol-http\",
      \"policy\":{
        \"@context\":\"http://www.w3.org/ns/odrl.jsonld\",
        \"@type\":\"Offer\",
        \"@id\":\"${offer_id}\",
        \"assigner\":\"${pid}\",
        \"target\":\"${asset}\"
      }}" | python3 -c "import sys,json; print(json.load(sys.stdin).get('@id'))")
  [ -z "$neg_id" ] && fail "Contract negotiation failed for ${label}"
  poll_state "${cmgmt}/v3/contractnegotiations/${neg_id}" "state" "FINALIZED"
  local contract_id
  contract_id=$(mgmt_get "${cmgmt}/v3/contractnegotiations/${neg_id}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin).get('contractAgreementId'))")
  ok "Contract finalized"

  info "Starting data transfer (requesting access token) …"
  local tp_id
  tp_id=$(mgmt_post "${cmgmt}/v3/transferprocesses" \
    "{\"@context\":{\"@vocab\":\"https://w3id.org/edc/v0.0.1/ns/\"},
      \"@type\":\"TransferRequest\",
      \"counterPartyAddress\":\"${proto}\",
      \"counterPartyId\":\"${pid}\",
      \"contractId\":\"${contract_id}\",
      \"assetId\":\"${asset}\",
      \"protocol\":\"dataspace-protocol-http\",
      \"transferType\":\"HttpData-PULL\"}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin).get('@id'))")
  [ -z "$tp_id" ] && fail "Transfer request failed for ${label}"
  poll_state "${cmgmt}/v3/transferprocesses/${tp_id}" "state" "STARTED"
  ok "Access token issued — channel is open"

  echo "${tp_id}"
}

get_edr_token() {
  mgmt_get "${1}/v3/edrs/${2}/dataaddress" \
    | python3 -c "import sys,json; print(json.load(sys.stdin).get('authorization'))"
}

ps_post() { curl -s -X POST -H "Content-Type: application/json" -d "$2" "${1}/serviceInstances"; }

ps_patch() {
  local base="$1" id="$2" ver="$3" state="$4" params="$5"
  local body="{\"state\":\"${state}\",\"parameters\":${params}}"
  flow_out "PATCH ${base}/serviceInstances/${id}" "$body"
  curl -s -X PATCH -H "Content-Type: application/json" \
    -H "If-Match: \"${ver}\"" \
    -d "$body" \
    "${base}/serviceInstances/${id}"
}

edc_list_instances() { curl -s -H "Authorization: Bearer $2" "${1}"; }

# edc_patch DP_URL TOKEN ID VERSION STATE PARAMS_JSON
edc_patch() {
  local dp_url="$1" token="$2" id="$3" ver="$4" state="$5" params="$6"
  local body="{\"state\":\"${state}\",\"parameters\":${params}}"

  flow_out "PATCH ${dp_url}/${id}  [via EDC data-plane — bearer token authorised]" "$body"
  local resp http_code
  resp=$(curl -s -w "\n%{http_code}" -X PATCH \
    -H "Authorization: Bearer ${token}" \
    -H "Content-Type: application/json" \
    -H "If-Match: \"${ver}\"" \
    -d "$body" \
    "${dp_url}/${id}")
  http_code=$(echo "$resp" | tail -1)
  local body_only
  body_only=$(echo "$resp" | sed '$d')

  if [[ "$http_code" =~ ^2 ]]; then
    ok "Update delivered through EDC (HTTP ${http_code})"
    flow_in "Process service response" "$body_only"
    echo "$body_only"
    return
  fi

  fail "EDC proxy returned HTTP ${http_code}: ${body_only}"
}

# ─────────────────────────────────────────────────────────────────────────────
# Opening banner
# ─────────────────────────────────────────────────────────────────────────────
echo -e "" >&2
echo -e "${BOLD}╔══════════════════════════════════════════════════════════════╗${RESET}" >&2
echo -e "${BOLD}║          VGM Container Weighing — Live Demo                  ║${RESET}" >&2
echo -e "${BOLD}╠══════════════════════════════════════════════════════════════╣${RESET}" >&2
echo -e "${BOLD}║${RESET}  This script acts as ${BOLD}Certiweight's ERP backend${RESET}.             ${BOLD}║${RESET}" >&2
echo -e "${BOLD}║${RESET}  The Shipper uses the frontend at ${CYAN}http://localhost:3003${RESET}.  ${BOLD}║${RESET}" >&2
echo -e "${BOLD}║${RESET}                                                              ${BOLD}║${RESET}" >&2
echo -e "${BOLD}║${RESET}  Flow:                                                        ${BOLD}║${RESET}" >&2
echo -e "${BOLD}║${RESET}    Shipper requests VGM  →  Certiweight weighs container      ${BOLD}║${RESET}" >&2
echo -e "${BOLD}║${RESET}    →  Shipper confirms purchase  →  certificate issued        ${BOLD}║${RESET}" >&2
echo -e "${BOLD}║${RESET}                                                              ${BOLD}║${RESET}" >&2
echo -e "${BOLD}║${RESET}  Cross-party calls go through the ${BOLD}Eclipse Dataspace (EDC)${RESET}.  ${BOLD}║${RESET}" >&2
echo -e "${BOLD}╚══════════════════════════════════════════════════════════════╝${RESET}" >&2
echo -e "" >&2

# ─────────────────────────────────────────────────────────────────────────────
hdr "Section 0 — Setup check"
# ─────────────────────────────────────────────────────────────────────────────

for svc in "${P1_MGMT}/v3/assets/request" "${P2_MGMT}/v3/assets/request"; do
  curl -sf -X POST -H "Content-Type: application/json" -H "X-Api-Key: ${API_KEY}" \
    -d '{"@context":{"@vocab":"https://w3id.org/edc/v0.0.1/ns/"},"@type":"QuerySpec"}' \
    "$svc" > /dev/null || fail "EDC connector not reachable: $svc"
done
ok "Both EDC connectors reachable"

curl -sf "${P1_PS}/serviceInstances" > /dev/null || fail "Certiweight process service not reachable (${P1_PS})"
curl -sf "${P2_PS}/serviceInstances" > /dev/null || fail "Shipper process service not reachable (${P2_PS})"
ok "Both process services reachable"

# ── Resolve Shipper instance ───────────────────────────────────────────────────
if [ -z "$SHIP_ID" ]; then
  info "Looking for an active Shipper VGM request on P2 …"
  SHIP_CANDIDATES=$(curl -s "${P2_PS}/serviceInstances?serviceDefinition=shipperProcess")
  SHIP_TOTAL=$(echo "$SHIP_CANDIDATES" | python3 -c "
import sys, json
items = json.load(sys.stdin).get('items', [])
active = [i for i in items if i.get('state') not in ('vgm_purchased', 'COMPLETED', 'CANCELLED')]
print(len(active))" 2>/dev/null || echo "0")
  [ "$SHIP_TOTAL" -eq 0 ] && fail "No active Shipper VGM request found. Open http://localhost:3003 and create one first."
  [ "$SHIP_TOTAL" -gt 1 ] && warn "Multiple active requests found — picking the most recently created."
  SHIP_ID=$(echo "$SHIP_CANDIDATES" | python3 -c "
import sys, json
items = json.load(sys.stdin).get('items', [])
active = [i for i in items if i.get('state') not in ('vgm_purchased', 'COMPLETED', 'CANCELLED')]
active.sort(key=lambda i: i.get('createdAt',''), reverse=True)
print(active[0]['id'])")
  ok "Found Shipper VGM request: ${SHIP_ID}"
else
  ok "Using supplied Shipper VGM request: ${SHIP_ID}"
fi

# Extract container/booking parameters from the Shipper instance
SHIP_PARAMS=$(curl -s "${P2_PS}/serviceInstances/${SHIP_ID}")
CONTAINER_NR=$(echo "$SHIP_PARAMS" | python3 -c "import sys,json; print(json.load(sys.stdin).get('parameters',{}).get('containernr','TCKU1234567'))")
BOOKING_NR=$(echo "$SHIP_PARAMS"  | python3 -c "import sys,json; print(json.load(sys.stdin).get('parameters',{}).get('bookingnr','BKG-2026-001'))")
TRANSPORT_CO="${TRANSPORT_CO:-Van Moer Transport}"
ok "Container: ${CONTAINER_NR}   Booking: ${BOOKING_NR}   Transport: ${TRANSPORT_CO}"

next "Set up secure channels between Certiweight and Shipper via the EDC dataspace" \
     "EDC contract negotiation — both parties get policy-governed access tokens"

# ─────────────────────────────────────────────────────────────────────────────
hdr "Section 1 — Secure channel setup (EDC)"
# ─────────────────────────────────────────────────────────────────────────────
# Before any cross-party data can flow, each side negotiates a contract with
# the other's EDC connector and receives a short-lived bearer token (EDR).
# The token is bound to the agreed policy — the data-plane enforces it on every call.

step "Certiweight → Shipper channel"
party P1 "Negotiating access to Shipper's process service …"
P2_TP=$(negotiate_edr "P2" "${P1_MGMT}" "${P2_PROTOCOL}" "${P2_ID}" "${P2_ASSET}")
P2_TOKEN=$(get_edr_token "${P1_MGMT}" "${P2_TP}")
ok "Certiweight can now send updates to Shipper through the dataspace"

step "Shipper → Certiweight channel"
party P2 "Negotiating access to Certiweight's process service …"
P1_TP=$(negotiate_edr "P1" "${P2_MGMT}" "${P1_PROTOCOL}" "${P1_ID}" "${P1_ASSET}")
P1_TOKEN=$(get_edr_token "${P2_MGMT}" "${P1_TP}")
ok "Shipper can now send updates to Certiweight through the dataspace"

# ─────────────────────────────────────────────────────────────────────────────
hdr "Section 2 — Channel verification"
# ─────────────────────────────────────────────────────────────────────────────

info "Certiweight reads Shipper's instances through the dataspace …"
P2_LIST=$(edc_list_instances "${P2_DP}" "${P2_TOKEN}")
P2_LIST_COUNT=$(echo "$P2_LIST" | python3 -c "import sys,json; print(json.load(sys.stdin).get('count',0))" 2>/dev/null || echo "0")
ok "Certiweight can see Shipper's process service (${P2_LIST_COUNT} instance(s))"

info "Shipper reads Certiweight's instances through the dataspace …"
P1_LIST=$(edc_list_instances "${P1_DP}" "${P1_TOKEN}")
P1_LIST_COUNT=$(echo "$P1_LIST" | python3 -c "import sys,json; print(json.load(sys.stdin).get('count',0))" 2>/dev/null || echo "0")
ok "Shipper can see Certiweight's process service (${P1_LIST_COUNT} instance(s))"

next "Start the VGM business flow" \
     "Certiweight receives the Shipper's VGM request and creates a weighing order"

# ─────────────────────────────────────────────────────────────────────────────
hdr "Section 3 — VGM business flow"
# ─────────────────────────────────────────────────────────────────────────────

# ── Step 1: Shipper's VGM request (already created by the Shipper via frontend) ──
step "Step 1 of 5 — Shipper's VGM request  [created in the frontend]"
party P2 "Has submitted a VGM request for container ${CONTAINER_NR}"
flow_in "Shipper's process instance (P2 — ${P2_PS})" "$SHIP_PARAMS"
ok "Container: ${CONTAINER_NR}   Booking: ${BOOKING_NR}   State: STARTED"
ok "Shipper process is waiting for Certiweight to confirm the order"

next "Certiweight receives the order — its ERP is notified and a weighing job is created" \
     "Direct call (Certiweight's own process service)"

# ── Step 2: Certiweight creates process instance ───────────────────────────────
step "Step 2 of 5 — Certiweight creates a weighing job  [CERTIWEIGHT SIDE]"
party P1 "Creating certiweightVGMProcess instance for container ${CONTAINER_NR} …"
CERT_BODY="{\"serviceDefinition\":\"certiweightVGMProcess\",
    \"stakeholders\":[
      {\"role\":\"provider\",\"party\":\"${P1_ID}\"},
      {\"role\":\"customer\",\"party\":\"${P2_ID}\"}
    ],
    \"parameters\":{
      \"internalApiUrl\":\"${ERP_URL}/order-created\",
      \"payloadData\":\"{\\\"containerNr\\\":\\\"${CONTAINER_NR}\\\",\\\"bookingNr\\\":\\\"${BOOKING_NR}\\\"}\",
      \"containernr\":\"${CONTAINER_NR}\",
      \"bookingnr\":\"${BOOKING_NR}\",
      \"shipperInstanceId\":\"${SHIP_ID}\"
    }}"
flow_out "POST ${P1_PS}/serviceInstances" "$CERT_BODY"
CERT_RESP=$(ps_post "${P1_PS}" "$CERT_BODY")
flow_in "Certiweight's process instance (P1 — ${P1_PS})" "$CERT_RESP"
CERT_ID=$(echo "$CERT_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
ok "Certiweight weighing job created: ${CERT_ID}"
notification "Certiweight ERP" "Order Created" "Container ${CONTAINER_NR} / Booking ${BOOKING_NR}"
ok "Certiweight is now waiting for the truck to arrive at the facility"

next "Certiweight notifies the Shipper that the order has been confirmed" \
     "EDC data-plane — Certiweight → Shipper (policy-governed, bearer token)"

# ── Step 2b: Certiweight ERP → Shipper (advance waitOrderCreated via EDC) ──────
step "Step 2b of 5 — Order confirmed: Certiweight notifies Shipper  [EDC]"
party P1 "Sending ORDER_CONFIRMED to Shipper through the dataspace …"
RESP=$(edc_patch "${P2_DP}" "${P2_TOKEN}" \
  "${SHIP_ID}" "1" "ORDER_CONFIRMED" \
  "{\"certiweightInstanceId\":\"${CERT_ID}\",\"containerNr\":\"${CONTAINER_NR}\"}")
ok "Shipper received: ORDER_CONFIRMED (version $(echo $RESP | python3 -c "import sys,json; print(json.load(sys.stdin)['version'])"))"
party P2 "Shipper is now waiting for the weight measurement"

next "The truck arrives at Certiweight's facility — container is weighed" \
     "Direct call (Certiweight's own process service)"

# ── Step 3: Trucker arrives – Certiweight self-PATCH ──────────────────────────
step "Step 3 of 5 — Truck arrives, container weighed  [CERTIWEIGHT SIDE]"
party P1 "Truck TRK-42 has arrived — recording weight measurement …"
RESP=$(ps_patch "${P1_PS}" "${CERT_ID}" "1" "TRUCKER_ANNOUNCED" \
  "{\"internalApiUrl\":\"${ERP_URL}/measurement-created\",
    \"payloadData\":\"{\\\"containerNr\\\":\\\"${CONTAINER_NR}\\\",\\\"grossMass\\\":24500,\\\"unit\\\":\\\"kg\\\"}\",
    \"truckId\":\"TRK-42\",
    \"transportbedrijf\":\"${TRANSPORT_CO}\",
    \"grossMass\":24500}")
flow_in "Certiweight's process instance" "$RESP"
ok "Certiweight state: TRUCKER_ANNOUNCED (version $(echo $RESP | python3 -c "import sys,json; print(json.load(sys.stdin)['version'])"))"
notification "Certiweight ERP" "Measurement Created" "Container ${CONTAINER_NR} — Gross mass: 24 500 kg"
ok "Certiweight is now waiting for the Shipper to confirm the VGM purchase"

next "Certiweight notifies the Shipper that the trucker has arrived" \
     "EDC data-plane — Certiweight → Shipper (policy-governed, bearer token)"

# ── Step 3b: Certiweight ERP → Shipper (advance waitTruckerAnnounced via EDC) ───
step "Step 3b of 5 — Trucker announced: Certiweight notifies Shipper  [EDC]"
party P1 "Sending TRUCKER_ANNOUNCED to Shipper through the dataspace …"
RESP=$(edc_patch "${P2_DP}" "${P2_TOKEN}" \
  "${SHIP_ID}" "2" "TRUCKER_ANNOUNCED" \
  "{\"transportbedrijf\":\"${TRANSPORT_CO}\"}")
ok "Shipper received: TRUCKER_ANNOUNCED (version $(echo $RESP | python3 -c "import sys,json; print(json.load(sys.stdin)['version'])"))"
party P2 "Shipper sees the trucker has arrived — waiting for weight measurement"

next "Certiweight sends the weight measurement to the Shipper for VGM purchase confirmation" \
     "EDC data-plane — Certiweight → Shipper (policy-governed, bearer token)"

# ── Step 3c: Certiweight ERP → Shipper (advance waitMeasurementCreated via EDC) ─
step "Step 3c of 5 — Measurement shared: Certiweight notifies Shipper  [EDC]"
party P1 "Sending MEASUREMENT_RECEIVED (24 500 kg) to Shipper through the dataspace …"
RESP=$(edc_patch "${P2_DP}" "${P2_TOKEN}" \
  "${SHIP_ID}" "3" "MEASUREMENT_RECEIVED" \
  "{\"internalApiUrl\":\"${TMS_URL}/purchase-vgm\",
    \"payloadData\":\"{\\\"containerNr\\\":\\\"${CONTAINER_NR}\\\",\\\"grossMass\\\":24500}\"}")
ok "Shipper received: MEASUREMENT_RECEIVED (version $(echo $RESP | python3 -c "import sys,json; print(json.load(sys.stdin)['version'])"))"
notification "Shipper TMS" "Purchase VGM" "Container ${CONTAINER_NR} — measurement received, weight withheld"
ok "Shipper is now waiting for the VGM certificate"

next "Shipper confirms the VGM purchase — sends payment confirmation to Certiweight" \
     "EDC data-plane — Shipper → Certiweight (policy-governed, bearer token)"

# ── Step 4: Shipper TMS → Certiweight (advance waitPurchaseVGM via EDC) ─────────
step "Step 4 of 5 — Shipper confirms VGM purchase  [EDC]"
party P2 "Sending PURCHASE_CONFIRMED to Certiweight through the dataspace …"
RESP=$(edc_patch "${P1_DP}" "${P1_TOKEN}" \
  "${CERT_ID}" "2" "PURCHASE_CONFIRMED" \
  "{\"internalApiUrl\":\"${ERP_URL}/vgm-purchased\",
    \"payloadData\":\"{\\\"containerNr\\\":\\\"${CONTAINER_NR}\\\",\\\"certificateRef\\\":\\\"CERT-2026-001\\\"}\"}")
ok "Certiweight received: PURCHASE_CONFIRMED (version $(echo $RESP | python3 -c "import sys,json; print(json.load(sys.stdin)['version'])"))"
notification "Certiweight ERP" "VGM Certificate Issued" "Certificate: CERT-2026-001 — Payment processed"
ok "Certiweight weighing process is now COMPLETE"

next "Certiweight delivers the VGM certificate to the Shipper" \
     "EDC data-plane — Certiweight → Shipper (policy-governed, bearer token)"

# ── Step 5: Certiweight ERP → Shipper (advance waitVGMPurchased via EDC) ─────────
step "Step 5 of 5 — Certificate delivered: Certiweight notifies Shipper  [EDC]"
party P1 "Sending VGM_PURCHASED (certificate CERT-2026-001) to Shipper through the dataspace …"
RESP=$(edc_patch "${P2_DP}" "${P2_TOKEN}" \
  "${SHIP_ID}" "4" "VGM_PURCHASED" \
  "{\"grossMass\":24500,
    \"certificateRef\":\"CERT-2026-001\",
    \"certificateUrl\":\"https://certiweight.example.com/certs/CERT-2026-001.pdf\"}")
ok "Shipper received: VGM_PURCHASED (version $(echo $RESP | python3 -c "import sys,json; print(json.load(sys.stdin)['version'])"))"
notification "Shipper TMS" "VGM Certificate Downloaded" "CERT-2026-001  |  https://certiweight.example.com/certs/CERT-2026-001.pdf"
ok "Shipper VGM process is now COMPLETE"

# ─────────────────────────────────────────────────────────────────────────────
hdr "Section 4 — Final state verification"
# ─────────────────────────────────────────────────────────────────────────────

info "Reading final states through the EDC data-plane …"
P2_FINAL=$(edc_list_instances "${P2_DP}" "${P2_TOKEN}")
P2_SHIP_STATE=$(echo "$P2_FINAL" | python3 -c "
import sys, json
items = json.load(sys.stdin).get('items', [])
for i in items:
    if i.get('id') == '${SHIP_ID}':
        print(i.get('state'))
        break
" 2>/dev/null || echo "not found")
ok "Shipper final state (via EDC):      ${P2_SHIP_STATE}"

P1_FINAL=$(edc_list_instances "${P1_DP}" "${P1_TOKEN}")
P1_CERT_STATE=$(echo "$P1_FINAL" | python3 -c "
import sys, json
items = json.load(sys.stdin).get('items', [])
for i in items:
    if i.get('id') == '${CERT_ID}':
        print(i.get('state'))
        break
" 2>/dev/null || echo "not found")
ok "Certiweight final state (via EDC):  ${P1_CERT_STATE}"

info "Confirming directly from each process service …"
SHIP_FINAL=$(curl -sf "${P2_PS}/serviceInstances/${SHIP_ID}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['state'], '(version ' + str(d['version']) + ')')")
CERT_FINAL=$(curl -sf "${P1_PS}/serviceInstances/${CERT_ID}" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['state'], '(version ' + str(d['version']) + ')')")
ok "Shipper (P2):     ${SHIP_FINAL}"
ok "Certiweight (P1): ${CERT_FINAL}"

# ─────────────────────────────────────────────────────────────────────────────
echo -e "" >&2
echo -e "${BOLD}╔══════════════════════════════════════════════════════════════╗${RESET}" >&2
echo -e "${BOLD}║  ${GREEN}✓  Demo complete — both workflows finished successfully${RESET}${BOLD}      ║${RESET}" >&2
echo -e "${BOLD}╠══════════════════════════════════════════════════════════════╣${RESET}" >&2
echo -e "${BOLD}║${RESET}                                                              ${BOLD}║${RESET}" >&2
echo -e "${BOLD}║${RESET}  Certiweight ID : ${CERT_ID}  ${BOLD}   ║${RESET}" >&2
echo -e "${BOLD}║${RESET}  Shipper ID     : ${SHIP_ID}  ${BOLD}   ║${RESET}" >&2
echo -e "${BOLD}║${RESET}                                                              ${BOLD}║${RESET}" >&2
echo -e "${BOLD}║${RESET}  Open the frontends to see the completed instances:          ${BOLD}║${RESET}" >&2
echo -e "${BOLD}║${RESET}    ${CYAN}http://localhost:3002${RESET}  (Certiweight)                      ${BOLD}║${RESET}" >&2
echo -e "${BOLD}║${RESET}    ${CYAN}http://localhost:3003${RESET}  (Shipper)                          ${BOLD}║${RESET}" >&2
echo -e "${BOLD}║${RESET}                                                              ${BOLD}║${RESET}" >&2
echo -e "${BOLD}╚══════════════════════════════════════════════════════════════╝${RESET}" >&2
echo -e "" >&2
