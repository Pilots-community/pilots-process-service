# How it works — Example 2: VGM weighing service

This document walks through a complete two-party VGM (Verified Gross Mass) service exchange between a **weighing company (Certiweight, provider)** and a **shipper (consumer)**. It uses two correlated BPMN processes — one running on each participant's process service — and shows exactly which API call advances each step.

BPMN files:
- [`certiweight-vgm-process.bpmn20.xml`](../src/main/resources/processes/certiweight-vgm-process.bpmn20.xml)
- [`shipper-vgm-process.bpmn20.xml`](../src/main/resources/processes/shipper-vgm-process.bpmn20.xml)

---

## The two processes side by side

```
Certiweight (P1)                              Shipper (P2)
p1-process-service:8080                       p2-process-service:8080
certiweightVGMProcess                         shipperProcess

Start                                         Start
  |                                             |
ServiceTask: Send Order Created                ReceiveTask: Wait for Order Created
  | calls internalApiUrl (ERP)                   | paused — waiting for Certiweight PATCH
  |                                             |
ReceiveTask: Wait for Trucker           <--+   |
  | paused — waiting for                  |    |   (Certiweight PATCHes Shipper via EDC)
  | Certiweight's own PATCH               +----+-->
  |                                             |
ServiceTask: Send Measurement Created          ReceiveTask: Wait for Measurement Created
  | calls internalApiUrl (ERP)                   | paused — waiting for Certiweight PATCH
  |                                             |
ReceiveTask: Wait for Purchase VGM      <--+   |
  | paused — waiting for Shipper          |    |   (Certiweight PATCHes Shipper via EDC)
  | PATCH through EDC                     +----+-->
  |                                             |
ServiceTask: Process Payment             <--+  ServiceTask: Send Purchase VGM
  | auto (no-op placeholder)              |      | calls internalApiUrl (TMS)
ServiceTask: Create Certificate          |      |
  | auto (no-op placeholder)             +------+  (Shipper PATCHes Certiweight via EDC)
ServiceTask: Send VGM Purchased                |
  | calls internalApiUrl (ERP)           +--+  ReceiveTask: Wait for VGM Purchased
End                                      |  |    | paused — waiting for Certiweight PATCH
                                         |  +--->|   (Certiweight PATCHes Shipper via EDC)
                                         |       |
                                         |     ServiceTask: Download Certificate
                                         |       | auto (no-op placeholder)
                                         |     End
```

---

## Step-by-step API calls

### Setup

```bash
P1_PS="http://localhost:8080"        # Certiweight's process service (direct)
P2_PS="http://localhost:8081"        # Shipper's process service (direct)
P1_EDR="http://localhost:38185/public"  # Certiweight's data-plane public endpoint
P2_EDR="http://localhost:48185/public"  # Shipper's data-plane public endpoint
P1_TOKEN="<bearer token from P2's EDR transfer to P1>"
P2_TOKEN="<bearer token from P1's EDR transfer to P2>"
```

---

### Step 1 — Shipper creates their process instance

The Shipper's process starts with a `receiveTask`, so it pauses immediately. It must exist before Certiweight can signal it.

```bash
SHIP_RESP=$(curl -s -X POST "$P2_PS/serviceInstances" \
  -H "Content-Type: application/json" \
  -d '{
    "serviceDefinition": "shipperProcess",
    "stakeholders": [
      {"role": "customer", "party": "did:web:participant-2"},
      {"role": "provider", "party": "did:web:participant-1"}
    ],
    "parameters": {
      "containerNr": "TCKU1234567",
      "bookingNr":   "BKG-2026-001"
    }
  }')

SHIP_ID=$(echo $SHIP_RESP | jq -r '.id')
echo "Shipper instance: $SHIP_ID  state: $(echo $SHIP_RESP | jq -r '.state')"
# → state: STARTED  (process paused at waitOrderCreated)
```

---

### Step 2 — Certiweight creates their process instance

Certiweight creates their own instance, storing the Shipper's instance ID so they know where to send cross-process PATCHes later. `internalApiUrl` for `sendOrderCreated` is set here.

```bash
CERT_RESP=$(curl -s -X POST "$P1_PS/serviceInstances" \
  -H "Content-Type: application/json" \
  -d "{
    \"serviceDefinition\": \"certiweightVGMProcess\",
    \"stakeholders\": [
      {\"role\": \"provider\", \"party\": \"did:web:participant-1\"},
      {\"role\": \"customer\", \"party\": \"did:web:participant-2\"}
    ],
    \"parameters\": {
      \"internalApiUrl\": \"http://certiweight-erp/api/vgm/order-created\",
      \"payloadData\":    \"{\\\"containerNr\\\": \\\"TCKU1234567\\\", \\\"bookingNr\\\": \\\"BKG-2026-001\\\"}\",
      \"shipperInstanceId\": \"$SHIP_ID\"
    }
  }")

CERT_ID=$(echo $CERT_RESP | jq -r '.id')
echo "Certiweight instance: $CERT_ID  state: $(echo $CERT_RESP | jq -r '.state')"
# → state: STARTED
```

**What fires automatically:**
- `sendOrderCreated` ServiceTask executes — POSTs to `http://certiweight-erp/api/vgm/order-created`
- Process pauses at `waitTruckerAnnounced`

**Certiweight's ERP must also** PATCH Shipper's instance to advance `waitOrderCreated`:

```bash
curl -s -X PATCH "$P2_EDR/$SHIP_ID" \
  -H "Authorization: Bearer $P2_TOKEN" \
  -H "Content-Type: application/json" \
  -H "If-Match: \"1\"" \
  -d "{
    \"state\": \"ORDER_CONFIRMED\",
    \"parameters\": {
      \"certiweightInstanceId\": \"$CERT_ID\",
      \"containerNr\": \"TCKU1234567\"
    }
  }"
# Shipper's waitOrderCreated is triggered → advances to waitMeasurementCreated
```

---

### Step 3 — Trucker arrives, Certiweight records the measurement

This is Certiweight's own internal event. Certiweight's system PATCHes their own process service directly (no EDC needed). The PATCH must also set the `internalApiUrl` for the next ServiceTask (`sendMeasurementCreated`).

```bash
curl -s -X PATCH "$P1_PS/serviceInstances/$CERT_ID" \
  -H "Content-Type: application/json" \
  -H "If-Match: \"1\"" \
  -d '{
    "state": "TRUCKER_ANNOUNCED",
    "parameters": {
      "internalApiUrl": "http://certiweight-erp/api/vgm/measurement-created",
      "payloadData":    "{\"containerNr\": \"TCKU1234567\", \"grossMass\": 24500, \"unit\": \"kg\"}",
      "truckId":        "TRK-42",
      "grossMass":      24500
    }
  }'
# waitTruckerAnnounced is triggered
# → sendMeasurementCreated ServiceTask fires automatically
#   → POSTs to http://certiweight-erp/api/vgm/measurement-created
# → process pauses at waitPurchaseVGM
```

**Certiweight's ERP must also** PATCH Shipper's instance to advance `waitMeasurementCreated`. This PATCH sets the `internalApiUrl` for Shipper's `sendPurchaseVGM` ServiceTask:

```bash
curl -s -X PATCH "$P2_EDR/$SHIP_ID" \
  -H "Authorization: Bearer $P2_TOKEN" \
  -H "Content-Type: application/json" \
  -H "If-Match: \"2\"" \
  -d '{
    "state": "MEASUREMENT_RECEIVED",
    "parameters": {
      "internalApiUrl": "http://shipper-tms/api/vgm/purchase",
      "payloadData":    "{\"containerNr\": \"TCKU1234567\", \"grossMass\": 24500}",
      "grossMass":      24500
    }
  }'
# Shipper's waitMeasurementCreated is triggered
# → sendPurchaseVGM ServiceTask fires automatically
#   → POSTs to http://shipper-tms/api/vgm/purchase
# → process pauses at waitVGMPurchased
```

---

### Step 4 — Shipper confirms purchase

Shipper's `sendPurchaseVGM` already fired (step 3). Shipper's TMS must now PATCH Certiweight's instance through the EDC to advance `waitPurchaseVGM`. This PATCH sets the `internalApiUrl` for Certiweight's `sendVGMPurchased` ServiceTask:

```bash
curl -s -X PATCH "$P1_EDR/$CERT_ID" \
  -H "Authorization: Bearer $P1_TOKEN" \
  -H "Content-Type: application/json" \
  -H "If-Match: \"2\"" \
  -d '{
    "state": "PURCHASE_CONFIRMED",
    "parameters": {
      "internalApiUrl": "http://certiweight-erp/api/vgm/purchased",
      "payloadData":    "{\"containerNr\": \"TCKU1234567\", \"certificateRef\": \"CERT-2026-001\"}"
    }
  }'
# waitPurchaseVGM is triggered
# → taskProcessPayment auto-completes (placeholder)
# → taskCreateCertificate auto-completes (placeholder)
# → sendVGMPurchased ServiceTask fires automatically
#   → POSTs to http://certiweight-erp/api/vgm/purchased
# → process ends (COMPLETED)
```

---

### Step 5 — Certiweight notifies Shipper that VGM is ready

Certiweight's ERP PATCHes Shipper's instance to deliver the certificate reference and advance `waitVGMPurchased`:

```bash
curl -s -X PATCH "$P2_EDR/$SHIP_ID" \
  -H "Authorization: Bearer $P2_TOKEN" \
  -H "Content-Type: application/json" \
  -H "If-Match: \"3\"" \
  -d '{
    "state": "VGM_PURCHASED",
    "parameters": {
      "certificateRef": "CERT-2026-001",
      "certificateUrl": "https://certiweight.example.com/certs/CERT-2026-001.pdf"
    }
  }'
# waitVGMPurchased is triggered
# → taskDownloadCertificate auto-completes (placeholder)
# → process ends (COMPLETED)
```

---

## Summary of all PATCHes

| Step | Who sends | Target | Advances | Sets internalApiUrl for next |
|---|---|---|---|---|
| 2a | Certiweight ERP | Shipper (via EDC) | `waitOrderCreated` | n/a |
| 3a | Certiweight (direct) | Certiweight | `waitTruckerAnnounced` | `sendMeasurementCreated` |
| 3b | Certiweight ERP | Shipper (via EDC) | `waitMeasurementCreated` | `sendPurchaseVGM` |
| 4 | Shipper TMS (via EDC) | Certiweight | `waitPurchaseVGM` | `sendVGMPurchased` |
| 5 | Certiweight ERP | Shipper (via EDC) | `waitVGMPurchased` | n/a |

---

## The internalApiUrl convention

Because `InvokeInternalApiDelegate` always reads the `internalApiUrl` process variable, and multiple ServiceTasks in a process can have different target URLs, the rule is:

> **The PATCH that triggers a ReceiveTask must include the `internalApiUrl` (and `payloadData`) for the ServiceTask that immediately follows it.**

This works because the controller calls `runtimeService.setVariables()` before `runtimeService.trigger()`, so the new URL is in place when the engine resumes and fires the next ServiceTask.

At process creation (POST), `internalApiUrl` is set for the **first** ServiceTask only.

---

## Cross-process communication pattern

Both participants own their process service. Cross-party PATCHes always go through the EDC:

```
Certiweight ERP                      EDC dataspace                  Shipper process service
     |                                    |                                 |
     | PATCH /serviceInstances/{ship-id} -+- proxy (P2 asset) ------------>|
     |   Authorization: Bearer <P2 EDR token>                              |
     |   If-Match: "N"                                                     |
     |   {state: ..., parameters: {internalApiUrl: ..., payloadData: ...}} |
     |<-------------------------------------------------------------------200
```

Certiweight's own process service is called directly (no EDC):

```
Certiweight ERP                      Certiweight process service
     |                                       |
     | PATCH /serviceInstances/{cert-id} --->|
     |   If-Match: "N"                       |
     |   {state: ..., parameters: {...}}     |
     |<-----------------------------------200
```

The Shipper's TMS calls Certiweight's process service through the EDC using P1's asset:

```
Shipper TMS                         EDC dataspace                  Certiweight process service
     |                                   |                                 |
     | PATCH /serviceInstances/{cert-id}-+- proxy (P1 asset) ------------>|
     |   Authorization: Bearer <P1 EDR token>                             |
     |<------------------------------------------------------------------200
```
