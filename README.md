# pilots-process-service

A Spring Boot service that exposes a [TMF638-style](https://www.tmforum.org/resources/specification/tmf638-service-inventory-management-api-rest-specification-r19-0-0/) **Service Instances API** backed by an embedded [Flowable](https://flowable.com/) BPMN engine. Each service instance maps 1-to-1 with a running BPMN process instance, making the full lifecycle of a cross-party service exchange (request → negotiation → fulfilment → completion) visible as a durable, auditable workflow.

It is designed to sit behind an [Eclipse Dataspace Connector (EDC)](https://github.com/eclipse-tractusx/tractusx-edc) so that two dataspace participants can exchange service instances over a governed, policy-enforced channel.

---

## Table of contents

1. [Architecture overview](#architecture-overview)
2. [Prerequisites](#prerequisites)
3. [Build](#build)
4. [Run standalone](#run-standalone)
5. [Run with Docker (two-participant setup)](#run-with-docker-two-participant-setup)
6. [API reference](#api-reference)
7. [Wiring up with the EDC dataspace](#wiring-up-with-the-edc-dataspace)
8. [Communication flow](#communication-flow)
9. [Writing a custom BPMN process](#writing-a-custom-bpmn-process)

---

## Architecture overview

```
                    ┌──────────────────────────────────────────────────────────┐
                    │                    EDC Dataspace                         │
  ┌────────────┐    │  ┌──────────────┐          ┌──────────────┐              │
  │            │    │  │ P1 Control   │◄─ DSP ──►│ P2 Control   │              │
  │  P2 app /  │    │  │    Plane     │           │    Plane     │             │
  │  consumer  │    │  └──────┬───────┘           └──────┬───────┘             │
  │            │    │         │                          │                     │
  └─────┬──────┘    │  ┌──────▼───────┐           ┌──────▼───────┐             │
        │           │  │  P1 Data     │           │  P2 Data     │             │
        │           │  │    Plane     │           │    Plane     │             │
        │           │  └──────┬───────┘           └──────┬───────┘             │
        │           └─────────┼──────────────────────────┼─────────────────────┘
        │  EDR (pull)         │                          │ (future push-back)
        └─────────────────────▼                          │
                       ┌──────────────┐                  │
                       │  P1 Process  │                  │
                       │   Service    │◄─── direct ──────┘
                       │  :8080       │     callback
                       └──────┬───────┘     from P1
                              │             internal app
                       ┌──────▼───────┐
                       │   Flowable   │
                       │  BPMN engine │
                       └──────┬───────┘
                              │
                       ┌──────▼───────┐
                       │  P1 internal │
                       │     app      │  ◄── ServiceTask POSTs here
                       └──────────────┘
```

Key points:

- **Service instance = BPMN process instance.** Starting a service instance starts a BPMN process. PATCHing it advances the workflow (completes a `userTask` or triggers a `receiveTask`).
- **UUID as business key.** The public API uses a `format: uuid` identifier. Flowable's own numeric process ID is an internal detail; the UUID is stored as the Flowable `businessKey`.
- **Process variables carry state.** The fields `_state`, `_stakeholders`, and `_version` are stored as Flowable process variables prefixed with `_`. All other variables are surfaced as the `parameters` map in the API response.
- **EDC proxy forwards HTTP.** The EDC data-plane acts as an authenticated HTTP proxy. Consumers use a short-lived EDR token to call the process service through the data-plane's public endpoint.

---

## Prerequisites

| Requirement | Version |
|---|---|
| Java | 17+ |
| Gradle (wrapper included) | 8.x |
| Docker + Compose | v2+ |
| pilots-dataspace | running (provides the Docker network and EDC connectors) |

---

## Build

Build the fat JAR locally before building the Docker image (the Dockerfile copies the pre-built JAR):

```bash
./gradlew bootJar -x test
```

Run the tests:

```bash
./gradlew test
```

---

## Run standalone

```bash
./gradlew bootRun
```

The service starts on `http://localhost:8080`. The embedded H2 database is in-memory; all data is lost on restart. The H2 console is available at `http://localhost:8080/h2-console`.

---

## Run with Docker (two-participant setup)

This is the normal mode when developing with `pilots-dataspace`.

### 1. Start the dataspace stack

```bash
cd ../pilots-dataspace
docker compose up -d
```

This creates the `pilots-dataspace_default` Docker network and starts the EDC connectors, identity hubs, vaults, and dashboard.

### 2. Build and start both process service instances

```bash
cd ../pilots-process-service

# Build the JAR first (Docker copies it in)
./gradlew bootJar -x test

# Build the image and start two containers
docker compose up -d
```

Two containers start:

| Container | Host port | Network hostname |
|---|---|---|
| `p1-process-service` | `localhost:8080` | `p1-process-service:8080` |
| `p2-process-service` | `localhost:8081` | `p2-process-service:8080` |

Both join the `pilots-dataspace_default` network so the EDC data-planes can reach them.

### 3. Register as EDC assets

```bash
./register-edc-asset.sh
```

This creates an `HttpData` asset, an open policy, and a contract definition in each participant's EDC connector. The script is idempotent — re-running it is safe (HTTP 409 conflicts are skipped).

**Asset base URLs registered:**

| Participant | Asset ID | Base URL (inside Docker network) |
|---|---|---|
| P1 | `process-service-asset-p1` | `http://p1-process-service:8080/serviceInstances` |
| P2 | `process-service-asset-p2` | `http://p2-process-service:8080/serviceInstances` |

To override for a different deployment:

```bash
P1_PROCESS_SERVICE_URL=http://my-host:8080/serviceInstances ./register-edc-asset.sh
```

---

## API reference

Base URL: `http://localhost:8080`

| Method | Path | Description |
|---|---|---|
| `POST` | `/serviceInstances` | Create a new service instance (starts a BPMN process) |
| `GET` | `/serviceInstances` | List instances with optional filters |
| `GET` | `/serviceInstances/{id}` | Get a single instance (active or completed) |
| `PATCH` | `/serviceInstances/{id}` | Partial update — advances the BPMN process |
| `PUT` | `/serviceInstances/{id}` | Full replace — replaces all parameters and advances |
| `DELETE` | `/serviceInstances/{id}` | Terminate the process instance |

All mutating operations (`PATCH`, `PUT`, `DELETE`) require an `If-Match` header containing the current ETag value. A missing header returns **428 Precondition Required**; a stale value returns **412 Precondition Failed**.

### Create a service instance

```bash
curl -s -X POST http://localhost:8080/serviceInstances \
  -H "Content-Type: application/json" \
  -d '{
    "serviceDefinition": "sample-service-process",
    "stakeholders": [{"role": "customer", "party": "did:web:participant-1"}],
    "parameters": {
      "internalApiUrl": "http://my-internal-app/notify",
      "payloadData": "{\"orderId\": \"order-001\"}"
    }
  }'
```

Response `201 Created`:

```json
{
  "id": "b96dbe16-c42d-49f1-a4c5-689bed8277b0",
  "serviceDefinition": "sample-service-process",
  "state": "STARTED",
  "version": 1,
  "stakeholders": [{"role": "customer", "party": "did:web:participant-1"}],
  "parameters": {"internalApiUrl": "...", "payloadData": "..."},
  "createdAt": "2026-04-28T07:38:46Z"
}
```

The `Location` and `ETag` response headers point to the new resource and carry the initial version (`"1"`).

### Advance the process (PATCH)

```bash
curl -s -X PATCH http://localhost:8080/serviceInstances/b96dbe16-... \
  -H "Content-Type: application/json" \
  -H "If-Match: \"1\"" \
  -d '{"state": "COMPLETED"}'
```

Internally this:
1. Validates the ETag against the stored `_version`.
2. Updates process variables.
3. Completes the active `userTask` (if any), or triggers the waiting `receiveTask`.
4. Returns the updated resource with `version: 2` and `ETag: "2"`.

---

## Wiring up with the EDC dataspace

This section walks through a complete dataspace interaction where **Participant 2 (P2) accesses P1's process service**.

### Prerequisites

- Both EDC connectors are healthy (`docker ps` shows `(healthy)`).
- `register-edc-asset.sh` has been run.

Set up shell variables:

```bash
P1_MGMT="http://localhost:19193/management"
P2_MGMT="http://localhost:29193/management"
API_KEY="password"
P1_DSP="http://participant-1-controlplane:19194/protocol"
P1_DID="did:web:participant-1-identityhub%3A7093"
```

### Step 1 — P2 queries P1's catalog

```bash
curl -s -X POST "$P2_MGMT/v3/catalog/request" \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: $API_KEY" \
  -d "{
    \"@context\": {\"@vocab\": \"https://w3id.org/edc/v0.0.1/ns/\"},
    \"counterPartyAddress\": \"$P1_DSP\",
    \"counterPartyId\": \"$P1_DID\",
    \"protocol\": \"dataspace-protocol-http\"
  }" | jq '[.["dcat:dataset"] | if type == "array" then .[] else . end | .["@id"]]'
```

`process-service-asset-p1` should appear in the list.

### Step 2 — Extract the offer ID

```bash
OFFER_ID=$(curl -s -X POST "$P2_MGMT/v3/catalog/request" \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: $API_KEY" \
  -d "{
    \"@context\": {\"@vocab\": \"https://w3id.org/edc/v0.0.1/ns/\"},
    \"counterPartyAddress\": \"$P1_DSP\",
    \"counterPartyId\": \"$P1_DID\",
    \"protocol\": \"dataspace-protocol-http\"
  }" | jq -r '
    [.["dcat:dataset"] | if type == "array" then .[] else . end]
    | .[]
    | select(.["@id"] == "process-service-asset-p1")
    | .["odrl:hasPolicy"]
    | if type == "array" then .[0]["@id"] else .["@id"] end')

echo "Offer ID: $OFFER_ID"
```

### Step 3 — Negotiate a contract

```bash
NEGOTIATION_ID=$(curl -s -X POST "$P2_MGMT/v3/contractnegotiations" \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: $API_KEY" \
  -d "{
    \"@context\": {\"@vocab\": \"https://w3id.org/edc/v0.0.1/ns/\"},
    \"counterPartyAddress\": \"$P1_DSP\",
    \"counterPartyId\": \"$P1_DID\",
    \"protocol\": \"dataspace-protocol-http\",
    \"policy\": {
      \"@context\": \"http://www.w3.org/ns/odrl.jsonld\",
      \"@id\": \"$OFFER_ID\",
      \"@type\": \"Offer\",
      \"assigner\": \"$P1_DID\",
      \"target\": \"process-service-asset-p1\",
      \"permission\": [],
      \"prohibition\": [],
      \"obligation\": []
    }
  }" | jq -r '.["@id"]')

# Poll until FINALIZED
until [ "$(curl -s "$P2_MGMT/v3/contractnegotiations/$NEGOTIATION_ID" \
  -H "X-Api-Key: $API_KEY" | jq -r '.state')" = "FINALIZED" ]; do sleep 2; done

AGREEMENT_ID=$(curl -s "$P2_MGMT/v3/contractnegotiations/$NEGOTIATION_ID" \
  -H "X-Api-Key: $API_KEY" | jq -r '.contractAgreementId')

echo "Agreement ID: $AGREEMENT_ID"
```

### Step 4 — Start a pull transfer

```bash
TRANSFER_ID=$(curl -s -X POST "$P2_MGMT/v3/transferprocesses" \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: $API_KEY" \
  -d "{
    \"@context\": {\"@vocab\": \"https://w3id.org/edc/v0.0.1/ns/\"},
    \"counterPartyAddress\": \"$P1_DSP\",
    \"counterPartyId\": \"$P1_DID\",
    \"protocol\": \"dataspace-protocol-http\",
    \"contractId\": \"$AGREEMENT_ID\",
    \"assetId\": \"process-service-asset-p1\",
    \"transferType\": \"HttpData-PULL\"
  }" | jq -r '.["@id"]')

# Poll until STARTED
until [ "$(curl -s "$P2_MGMT/v3/transferprocesses/$TRANSFER_ID" \
  -H "X-Api-Key: $API_KEY" | jq -r '.state')" = "STARTED" ]; do sleep 2; done

echo "Transfer ID: $TRANSFER_ID"
```

### Step 5 — Fetch the EDR and call the process service

```bash
EDR=$(curl -s "$P2_MGMT/v3/edrs/$TRANSFER_ID/dataaddress" \
  -H "X-Api-Key: $API_KEY")

TOKEN=$(echo "$EDR" | jq -r '.authorization')
# Note: the endpoint uses the container hostname; substitute localhost for host access
ENDPOINT="http://localhost:38185/public"

# List all service instances
curl -s "$ENDPOINT" -H "Authorization: Bearer $TOKEN" | jq '.items[] | {id, state, version}'
```

The P1 data-plane proxies the request to `http://p1-process-service:8080/serviceInstances` and returns the response.

### Step 6 — Create a service instance through the dataspace

Because the asset was registered with `proxyMethod: true`, P2 can also POST through the data-plane:

```bash
curl -s -X POST "$ENDPOINT" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "serviceDefinition": "sample-service-process",
    "stakeholders": [{"role": "customer", "party": "did:web:participant-2"}],
    "parameters": {
      "internalApiUrl": "http://p1-internal-app/notify",
      "payloadData": "{\"requestedBy\": \"participant-2\"}"
    }
  }'
```

### Step 7 — Advance the process through the dataspace

```bash
INSTANCE_ID="<id from step 6>"
ETAG="1"

curl -s -X PATCH "$ENDPOINT/$INSTANCE_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "If-Match: \"$ETAG\"" \
  -d '{"state": "APPROVED"}'
```

> **Note:** Direct sub-path calls (`$ENDPOINT/$INSTANCE_ID`) work only when the data-plane routes sub-paths to the backend. With tractus-x EDC `HttpData-PULL`, the data-plane always proxies to the asset's registered `baseUrl`. Appending a path to the EDR endpoint forwards that path suffix to the backend — whether this works depends on the EDC version and data-plane configuration. If it returns 404 from the data-plane, register a dedicated asset per instance with the full URL as `baseUrl`.

---

## Communication flow

### Full lifecycle

```
P2 consumer                 EDC dataspace               P1 process service      P1 internal app
     |                           |                              |                      |
     | 1. Negotiate contract     |                              |                      |
     |-------------------------->|                              |                      |
     |<-- Agreement ID ----------|                              |                      |
     |                           |                              |                      |
     | 2. Start PULL transfer    |                              |                      |
     |-------------------------->|                              |                      |
     |<-- EDR (token+endpoint) --|                              |                      |
     |                           |                              |                      |
     | 3. POST /serviceInstances |                              |                      |
     |   (via EDR endpoint)      |                              |                      |
     |-------------------------->| proxy ─────────────────────> |                      |
     |                           |                              | 4. ServiceTask:      |
     |                           |                              |   POST internalUrl ─>|
     |                           |                              |<── 200 OK ───────────|
     |                           |                              |                      |
     |<── 201 {id, state:STARTED}|<──────────────────────────── |                      |
     |                           |                              |                      |
     |                    BPMN paused at receiveTask            |                      |
     |                           |                              |                      |
     | 5. Poll: GET via EDR      |                              |                      |
     |-------------------------->| proxy ─────────────────────> | state: STARTED       |
     |<── {state: "STARTED"} ----|<──────────────────────────── |                      |
     |                           |                              |                      |
     |                           |              (P1 internal app finishes its work)    |
     |                           |                              |                      |
     |                           |              6. Callback: PATCH /serviceInstances/id|
     |                           |               (direct — no EDC needed)              |
     |                           |               {state: "COMPLETED"} ────────────────>|
     |                           |                              | trigger(receiveTask) |
     |                           |                              | BPMN → EndEvent      |
     |                           |                              |                      |
     | 7. Poll: GET via EDR      |                              |                      |
     |-------------------------->|  proxy ─────────────────────>| state: COMPLETED     |
     |<── {state: "COMPLETED"} --| <────────────────────────────|                      |
```

### Step-by-step explanation

**Steps 1–2: Establish the dataspace channel**

P2 negotiates a contract with P1 and starts an `HttpData-PULL` transfer. The transfer produces an EDR — a short-lived Bearer token and the URL of P1's data-plane public endpoint. All subsequent calls to the process service go through this proxy.

**Step 3: Create the service instance**

P2 POSTs to the EDR endpoint. The data-plane authenticates the token, validates the contract, and forwards the request to `http://p1-process-service:8080/serviceInstances`. Flowable starts a new BPMN process instance.

**Step 4: BPMN ServiceTask fires immediately**

As soon as the BPMN process starts, the first activity is a `serviceTask` using `InvokeInternalApiDelegate`. The delegate reads `internalApiUrl` from the process variables and POSTs to it with `payloadData` as the body. This is a **synchronous HTTP call** made inside the Flowable thread — the process pauses at the next activity only after this call returns 2xx.

If the internal app is unreachable or returns a non-2xx status, the delegate throws `BpmnError("INTERNAL_API_ERROR")`. A boundary error event on the service task should catch this and route to an error-handling path (not yet in the sample process).

**Step 4 (BPMN pauses at receiveTask)**

After the service task completes, the process moves to a `receiveTask` and stops. A `receiveTask` does **not** appear in `taskService.createTaskQuery()` — it is an execution-level wait state, not a task. The process stays here until something external triggers it.

**Step 5: P2 polls for state changes**

P2 calls `GET {edrEndpoint}` (which proxies to `GET /serviceInstances`) periodically until it sees the state it is waiting for. The response includes `version`, which is also the ETag for the next mutating call.

**Step 6: P1 internal app calls back**

When P1's internal system finishes processing, it sends a PATCH directly to the process service — **bypassing the EDC**. There is no need for the callback to go through the dataspace because P1 owns the process service:

```bash
curl -X PATCH http://p1-process-service:8080/serviceInstances/{id} \
  -H "Content-Type: application/json" \
  -H "If-Match: \"1\"" \
  -d '{"state": "COMPLETED", "parameters": {"result": "approved"}}'
```

Internally the controller detects that there is no active `userTask`, queries for the waiting execution via `createExecutionQuery().onlyChildExecutions()`, and calls `runtimeService.trigger(executionId)`. The BPMN advances from the `receiveTask` to the `endEvent` and the process instance is archived in Flowable's history tables.

**Step 7: P2 observes completion**

P2's next poll returns `state: "COMPLETED"` and `version: 2`. Because the process has ended, the GET controller falls back to `HistoryService` to retrieve the final variable snapshot.

### Advancing via PATCH vs internal callback

| Who triggers | How | When to use |
|---|---|---|
| **P2 (consumer)** | PATCH through the EDR data-plane proxy | P2 needs to confirm or approve (e.g. "I accept the quote") |
| **P1 internal app** | PATCH directly to the process service (no EDC) | P1's backend finished async work and needs to resume the process |
| **Either party via userTask** | PATCH (controller calls `taskService.complete()`) | Process has a `userTask` at the current position |
| **Either party via receiveTask** | PATCH (controller calls `runtimeService.trigger()`) | Process is waiting at a `receiveTask` |

The controller chooses the right Flowable API automatically: it queries for an active user task first; if none exists it falls back to triggering the waiting execution.

---

## Writing a custom BPMN process

The sample process (`sample-service-process`) is a minimal placeholder. Replace it with a real workflow by:

1. **Create a BPMN file** in `src/main/resources/processes/`. Flowable auto-deploys all `*.bpmn20.xml` files at startup.

2. **Use a meaningful process ID** — the `serviceDefinition` field in the API maps to the BPMN `process id`. Clients must pass this exact string when creating an instance.

3. **Reference the delegate** with `flowable:delegateExpression="${invokeInternalApiDelegate}"` (not `flowable:class`) on any service task that should call an external HTTP endpoint.

4. **Add a boundary error event** on the service task to handle `INTERNAL_API_ERROR` gracefully:

   ```xml
   <boundaryEvent id="apiError" attachedToRef="notifyInternalApp">
     <errorEventDefinition errorRef="internalApiError"/>
   </boundaryEvent>
   <sequenceFlow sourceRef="apiError" targetRef="errorEndEvent"/>
   ```

5. **Required process variables** at start time (passed as `parameters` in the POST body):

   | Variable | Type | Purpose |
   |---|---|---|
   | `internalApiUrl` | `String` | URL the service task POSTs to |
   | `payloadData` | `String` | JSON body forwarded to the internal app (may be `null`) |

6. **State management** — update `state` via PATCH at key transition points. The state string is freeform; use whatever vocabulary matches your service lifecycle (e.g. `REQUESTED → QUOTED → APPROVED → FULFILLED → CLOSED`).

Example minimal process with error handling:

```xml
<process id="my-service-process" isExecutable="true">

  <startEvent id="start"/>
  <sequenceFlow sourceRef="start" targetRef="notifyApp"/>

  <serviceTask id="notifyApp" name="Notify Internal App"
               flowable:delegateExpression="${invokeInternalApiDelegate}"/>
  <boundaryEvent id="onApiError" attachedToRef="notifyApp">
    <errorEventDefinition errorRef="internalApiError"/>
  </boundaryEvent>
  <sequenceFlow sourceRef="onApiError" targetRef="errorEnd"/>
  <sequenceFlow sourceRef="notifyApp" targetRef="waitForReply"/>

  <receiveTask id="waitForReply" name="Wait for Reply"/>
  <sequenceFlow sourceRef="waitForReply" targetRef="end"/>

  <endEvent id="end"/>
  <endEvent id="errorEnd"/>

  <error id="internalApiError" errorCode="INTERNAL_API_ERROR"/>

</process>
```
