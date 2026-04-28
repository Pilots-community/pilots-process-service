# How it works — service instances and BPMN communication

This document explains how service instances are created, how the BPMN process advances, and how two dataspace participants communicate through the lifecycle of a shared service. A concrete invoicing example is used throughout.

---

## The core mechanic

The process service is a **mediator**. It sits between the EDC dataspace (what the outside world sees) and a participant's internal systems (ERP, billing system, etc.). The BPMN process is the script that defines *what happens automatically* and *where to pause and wait for input from the other party*.

There are two types of BPMN wait states:

| Element | Behaviour | How to advance |
|---|---|---|
| `serviceTask` | Fires automatically, calls an internal HTTP endpoint, moves on immediately | Nothing — the engine does it |
| `receiveTask` / `userTask` | Pauses and waits | A `PATCH /serviceInstances/{id}` call |

Every `PATCH` call does two things in sequence:

1. Updates process variables (state, parameters, version)
2. Advances past the current wait state — either by completing a `userTask` or triggering a `receiveTask`

---

## Invoicing example

Two participants: **P1 = supplier**, **P2 = buyer**.

### The BPMN process (lives on P1's process service)

```
StartEvent
  │
  ▼
ServiceTask: "Create invoice in ERP"          ← fires automatically on POST
  │  calls P1's internal ERP via InvokeInternalApiDelegate
  ▼
ReceiveTask: "Wait for buyer approval"         ← pauses here, state = INVOICE_SENT
  │
  ▼  (triggered when P2 sends a PATCH)
ExclusiveGateway: approved or rejected?
  │                        │
  ▼ [approved]             ▼ [rejected]
ServiceTask                ServiceTask
"Mark paid in ERP"         "Log rejection in ERP"
  │                        │
  ▼                        ▼
EndEvent                 EndEvent
state = COMPLETED        state = REJECTED
```

The gateway reads the `approvalDecision` process variable (set by the PATCH body) to decide which branch to take. Both ServiceTasks fire automatically before the process ends — no further external input is needed.

---

### Step-by-step

#### Step 1 — P1 creates the invoice

P1 calls their own process service directly. No EDC needed — it is their own system.

```bash
POST http://p1-process-service:8080/serviceInstances
Content-Type: application/json

{
  "serviceDefinition": "invoice-process",
  "stakeholders": [
    {"role": "supplier", "party": "did:web:participant-1"},
    {"role": "buyer",    "party": "did:web:participant-2"}
  ],
  "parameters": {
    "internalApiUrl": "http://p1-erp/invoices/create",
    "payloadData":    "{\"amount\": 5000, \"currency\": \"EUR\", \"invoiceRef\": \"INV-001\"}",
    "invoiceRef":     "INV-001"
  }
}
```

What the engine does immediately:

1. Flowable starts the `invoice-process` BPMN process.
2. The `serviceTask "Create invoice in ERP"` fires synchronously — `InvokeInternalApiDelegate` POSTs to `http://p1-erp/invoices/create`.
3. The ERP creates the invoice and returns `200 OK`.
4. The BPMN reaches the `receiveTask` and **pauses**.
5. The controller returns:

```json
{
  "id": "b96dbe16-...",
  "state": "INVOICE_SENT",
  "version": 1
}
```

#### Step 2 — P2 discovers and reads the invoice

P2 negotiates a contract with P1's EDC connector, starts a `HttpData-PULL` transfer, and receives an EDR (a short-lived Bearer token and the URL of P1's data-plane public endpoint). P2 then queries the list of service instances through the proxy:

```bash
GET http://p1-dataplane:38185/public
Authorization: Bearer <edr-token>
```

Response:

```json
{
  "items": [{
    "id": "b96dbe16-...",
    "state": "INVOICE_SENT",
    "parameters": {
      "invoiceRef": "INV-001",
      "amount": 5000,
      "currency": "EUR"
    }
  }],
  "total": 1
}
```

P2's application reads the invoice details from `parameters` and presents them for review.

#### Step 3 — P2 approves (or rejects)

P2 sends a `PATCH` through the EDR proxy. The `If-Match` header must carry the current version from the previous response.

```bash
PATCH http://p1-dataplane:38185/public/b96dbe16-...
Authorization: Bearer <edr-token>
Content-Type: application/json
If-Match: "1"

{
  "state": "APPROVED",
  "parameters": {
    "approvalDecision": "approved",
    "approvedBy": "alice@participant-2.com"
  }
}
```

What the engine does:

1. Validates the ETag (`"1"` matches stored `_version = 1`).
2. Sets `_state = APPROVED`, `approvalDecision = approved`, `_version = 2`.
3. No active `userTask` exists — the controller finds the waiting `receiveTask` execution and calls `runtimeService.trigger()`.
4. The BPMN resumes: the exclusive gateway reads `approvalDecision = "approved"` and takes the approval branch.
5. `serviceTask "Mark paid in ERP"` fires automatically — POSTs to P1's ERP.
6. The process reaches `EndEvent` and is archived in Flowable's history.
7. The controller returns:

```json
{
  "id": "b96dbe16-...",
  "state": "APPROVED",
  "version": 2
}
```

#### Step 4 — P1 sees the result

P1 reads the final state from their own process service. Because the process has ended, the controller retrieves the result from Flowable's history tables:

```bash
GET http://p1-process-service:8080/serviceInstances/b96dbe16-...

→ { "state": "APPROVED", "version": 2 }
```

---

### Multi-step flows

For longer negotiations — for example, supplier sends invoice → buyer requests a correction → supplier corrects → buyer approves — add more `receiveTask` nodes to the BPMN. Each one maps to a `PATCH` call. The `state` field tells both parties whose turn it is:

| state | meaning | who acts next |
|---|---|---|
| `INVOICE_SENT` | waiting for buyer review | P2 PATCHes |
| `CORRECTION_REQUESTED` | waiting for supplier to fix | P1 PATCHes |
| `INVOICE_CORRECTED` | waiting for buyer re-review | P2 PATCHes |
| `APPROVED` | process complete | nobody |
| `REJECTED` | process complete | nobody |

The corresponding BPMN looks like this:

```
StartEvent
  → ServiceTask "Create invoice in ERP"
  → ReceiveTask "Wait for buyer response"          state = INVOICE_SENT
  → ExclusiveGateway
      [correction_requested]
        → ServiceTask "Notify ERP of correction request"
        → ReceiveTask "Wait for corrected invoice"   state = CORRECTION_REQUESTED
        → ServiceTask "Resubmit to ERP"
        → ReceiveTask "Wait for re-approval"         state = INVOICE_CORRECTED
        → (back to gateway)
      [approved]
        → ServiceTask "Mark paid in ERP"             state = APPROVED
        → EndEvent
      [rejected]
        → ServiceTask "Log rejection in ERP"         state = REJECTED
        → EndEvent
```

---

## The two-process-service design (symmetric)

The architecture above uses **one process instance on P1** that P2 interacts with through the EDC. Because both participants run their own process service, a more symmetric design is also possible: each party has their own BPMN process that tracks their own internal steps, and the two are correlated by a shared business key.

```
P1 process service                       P2 process service
  invoice-process                          review-process
  id: b96dbe16-...                         id: f3a1cc72-...
  parameters:                              parameters:
    invoiceRef: INV-001        ←correlate→   correlationId: b96dbe16-...
    state: INVOICE_SENT                      state: PENDING_REVIEW
         │                                          │
  Advanced by P2 via EDC PATCH          Advanced by P2's own internal app
  (buyer approval triggers P1's         (internal finance sign-off triggers
   receiveTask)                          P2's own userTask)
```

In this design:

1. P1 creates a service instance on `p1-process-service`.
2. P1 (or P1's BPMN) also creates a corresponding instance on `p2-process-service` (via EDC) to give P2 their own tracking process.
3. P2's internal approval workflow advances P2's own process.
4. When P2's process reaches its final decision, a ServiceTask in P2's BPMN calls back and PATCHes P1's instance (through the EDC) to advance P1's `receiveTask`.
5. Both parties can independently query their own process service for the current state of their side of the exchange.

This is the full bidirectional model the architecture is designed to support.

---

## What maps to what

| BPMN element | API operation | who calls it |
|---|---|---|
| Start the process | `POST /serviceInstances` | process owner, or counterparty via EDC proxy |
| `serviceTask` | fires automatically | nobody — BPMN engine calls `internalApiUrl` |
| `receiveTask` / `userTask` | `PATCH /serviceInstances/{id}` | whoever's turn it is (via EDC or directly) |
| Process variables | `parameters` in request/response body | set on POST or any PATCH |
| Gateway decision | process variable read by the gateway (e.g. `approvalDecision`) | value set in the PATCH body before the gateway |
| Process ended | `GET` falls back to `HistoryService` automatically | any party |

---

## The callback pattern

When P1's internal app needs to resume the BPMN (e.g. after an async operation), it calls back directly — no EDC is needed because P1 owns their own process service:

```bash
PATCH http://p1-process-service:8080/serviceInstances/b96dbe16-...
Content-Type: application/json
If-Match: "1"

{
  "state": "INVOICE_CORRECTED",
  "parameters": { "correctionNote": "Updated line item 3" }
}
```

The controller detects that the process is at a `receiveTask`, calls `runtimeService.trigger()`, and the BPMN advances. P2 sees the new state on their next poll through the EDC.
