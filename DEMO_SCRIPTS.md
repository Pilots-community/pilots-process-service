# Demo Scripts — VGM Container Weighing Pilot

---

## Demo 1 — 2-minute Overview

**Goal:** Show two independent companies collaborating through a shared dataspace, each seeing their own view of the same VGM workflow advancing in real time.

**Setup before going on stage:**
- Both frontends open side by side: `http://localhost:3002` (Certiweight) and `http://localhost:3003` (Van Moer / Shipper)
- One active VGM request already created on the Shipper side (skip the booking step to save time)
- Terminal ready with `./vgm-e2e-dataspace.sh` but not yet running

---

### Script

*[Both frontends visible on screen, side by side.]*

**"Two companies, two systems — one shared process."**

On the left, Certiweight: a container weighing company. On the right, Van Moer Logistics: the shipper requesting a VGM certificate for their container.

Van Moer has already submitted a VGM request. You can see it here — container number, booking number, liner. State: Order Created.

*[Point to P2 frontend, the STARTED state in the progress bar.]*

Now Certiweight picks it up. They run a script that acts as their ERP backend.

*[Hit Enter on the script — step through to ORDER_CONFIRMED.]*

Certiweight confirms the order. Watch what happens on Van Moer's side.

*[Point to P2 frontend — state advances to Order Confirmed.]*

The update crossed the dataspace — Van Moer's system received it through a policy-governed EDC channel. Neither party called the other's API directly.

*[Hit Enter — step through to TRUCKER_ANNOUNCED on Certiweight side. Show the Certiweight frontend advancing.]*

The truck arrives. Certiweight registers the transport company — here on their own frontend.

*[Hit Enter — TRUCKER_ANNOUNCED reaches the Shipper side.]*

Van Moer sees it too: truck has arrived.

*[Hit Enter — MEASUREMENT_RECEIVED.]*

Container weighed. Certiweight notifies Van Moer that the measurement is in — but notice: **no weight yet**. The actual mass is only released when the purchase is confirmed.

*[Hit Enter — PURCHASE_CONFIRMED, then VGM_PURCHASED. Point to the weight and certificate URL appearing on the Shipper side.]*

Van Moer confirms the purchase. Certiweight issues the certificate. The weight — 24,500 kg — and the certificate link appear on Van Moer's screen. Process complete, both sides in sync.

**"Different companies, different systems, different BPMN processes — one coherent workflow through the dataspace."**

---
---

## Demo 2 — 20-minute Deep Dive

**Goal:** Explain the full architecture, walk through the live flow step by step, and demonstrate how the BPMN process definition drives everything — including a live change to the process.

**Setup before going on stage:**
- Both frontends open: `http://localhost:3002` and `http://localhost:3003`
- A BPMN viewer ready (e.g. bpmn.io or the Flowable modeler) with both process files loaded
- Terminal ready
- One clean VGM request created on P2 (no prior runs)
- Editor open on `shipper-vgm-process.bpmn20.xml`

---

### Part 1 — The Problem (2 min)

*[Slide or whiteboard: two boxes labelled "Certiweight ERP" and "Van Moer TMS" with a question mark between them.]*

Container shipping requires a Verified Gross Mass certificate before a container can be loaded. Today, that involves emails, phone calls, PDFs, manual entry into TMS systems.

The challenge is not just the workflow — it's that **two independent companies** need to share data and progress together, without either one hosting the other's system, and without a central platform that both must trust.

We want: each party runs their own system, their own process, under their own control — and they collaborate through a dataspace that enforces the agreed policy.

That's what this pilot shows.

---

### Part 2 — Architecture (4 min)

*[Diagram: two stacks, each with a frontend, a process service, and an EDC connector. An arrow between the connectors labelled "EDC Dataspace".]*

Each participant — Certiweight and Van Moer — runs an identical stack:

**Process Service** — a Spring Boot application with an embedded Flowable BPMN engine. This is the core. It exposes a single REST API: `/serviceInstances`. You POST to create a process instance, you PATCH to advance it. The BPMN definition — the XML file — is what drives the actual workflow logic.

**Frontend** — a lightweight single-page app. No framework, no build step. It talks directly to the process service. The UI adapts to whatever process definition is configured: it reads the state machine, shows the right columns, and hides internal variables automatically.

**EDC Connector** — the Eclipse Dataspace Connector. Every cross-party API call goes through here. Before any data flows, both sides negotiate a contract. The connector issues a short-lived bearer token. The data plane proxies the actual call. Neither party exposes their process service directly to the outside world.

The two sides run completely independent BPMN processes. They share no database. The only thing connecting them is the events they send each other through the dataspace.

---

### Part 3 — The BPMN Processes (4 min)

*[Open the BPMN viewer. Show certiweight-vgm-process.bpmn20.xml first.]*

Let's look at Certiweight's process.

It starts automatically when the POST arrives. The first node is a **Service Task** — it immediately calls Certiweight's ERP to say "order received." This fires synchronously before the API even returns. Then it parks at a **Receive Task** — it pauses and waits for Certiweight's operator to say the truck has arrived.

When that PATCH comes in — state: TRUCKER_ANNOUNCED — the engine wakes up, runs the next Service Task which notifies the ERP that the container has been weighed, and parks again waiting for the Shipper to confirm purchase.

When that confirmation arrives — through the dataspace — the engine runs through payment processing, certificate creation, sends the VGM Purchased notification, and the process ends.

*[Switch to shipper-vgm-process.bpmn20.xml.]*

The Shipper's process is simpler. It's all Receive Tasks — it just waits. It waits for order confirmation, trucker announcement, the measurement notification, and finally the certificate. The only Service Task fires when the measurement comes in — it calls the TMS to trigger the VGM purchase approval.

Notice: **the Shipper's process has no idea what Certiweight's process looks like**, and vice versa. They share a contract — an agreed sequence of state transitions — not a process definition. Each party models their own side.

---

### Part 4 — Live Flow (5 min)

*[Return to both frontends side by side. Create a new VGM request on P2 if not already done.]*

Let's run it live.

Van Moer creates a VGM request. Container TCKU1234567, booking BKG-2026-001, Maersk Line, Port of Antwerp.

*[Submit form on P2 frontend. State shows STARTED.]*

The process instance is created on Van Moer's process service. It immediately parks at the first Receive Task — waiting for Certiweight to confirm the order.

Now Certiweight's side.

*[Run `./vgm-e2e-dataspace.sh`. Step through each pause.]*

The script represents Certiweight's ERP backend. First it negotiates contracts with both EDC connectors — one so Certiweight can call Van Moer's process service, one so Van Moer can call Certiweight's. This happens once per session.

*[Hit Enter — Certiweight creates its own process instance.]*

Certiweight creates their process instance. Notice: immediately the ERP is notified — that's the first Service Task firing automatically. Certiweight's state: Order Received. Now it waits.

*[Hit Enter — ORDER_CONFIRMED sent to Shipper via EDC.]*

Through the dataspace, Certiweight sends ORDER_CONFIRMED to Van Moer's process instance. Watch the Shipper frontend.

*[State on P2 updates to Order Confirmed.]*

Van Moer's system received it. The EDC connector verified the bearer token, the data plane proxied the PATCH, and the Flowable engine advanced the process.

*[Hit Enter — Certiweight TRUCKER_ANNOUNCED.]*

Truck arrives. On the Certiweight frontend, the operator advances to TRUCKER_ANNOUNCED and enters the transport company name. That PATCH triggers the next Service Task — the ERP is called to log the measurement.

*[Hit Enter — TRUCKER_ANNOUNCED sent to Shipper via EDC.]*

Certiweight notifies Van Moer: truck is on site.

*[Hit Enter — MEASUREMENT_RECEIVED to Shipper.]*

The measurement is done. Certiweight notifies Van Moer. But look — **the weight is not in this message**. Van Moer's instance shows MEASUREMENT_RECEIVED but no `grossMass` yet. That's intentional — the weight is Certiweight's data, and they only release it upon purchase confirmation.

Van Moer's process engine fires its one Service Task: it calls the TMS to initiate the purchase approval.

*[Hit Enter — PURCHASE_CONFIRMED from Shipper to Certiweight via EDC.]*

Van Moer's TMS confirms the purchase. This PATCH goes to Certiweight through the dataspace. The Certiweight engine wakes up, runs payment processing and certificate creation — both placeholder service tasks today, real integrations in production — and sends the final notification.

*[Hit Enter — VGM_PURCHASED to Shipper with grossMass + certificate.]*

Certificate delivered. Now the weight appears on Van Moer's side — 24,500 kg — along with the certificate reference and URL. Both processes complete.

---

### Part 5 — Changing the BPMN (3 min)

*[Open the editor on shipper-vgm-process.bpmn20.xml.]*

Here's where the architecture pays off. The BPMN process definition is just an XML file. The API doesn't change. The frontend doesn't change. You drop in a new file, restart the service, and the new flow is live.

We just added TRUCKER_ANNOUNCED to the Shipper's process earlier this week. The Shipper didn't have that state before — they went straight from ORDER_CONFIRMED to MEASUREMENT_RECEIVED. Adding it took three things: a new receive task in the XML, one line in the frontend state flow config, and one extra PATCH step in the script.

The `parameters` map is open — any process variable that isn't prefixed with `_` surfaces in the API. The frontend reads whatever comes back and displays it. A completely different process definition — say, a hazardous goods inspection workflow — would be picked up by the same frontend, same API, same EDC channel. You'd just configure a different `serviceDefinition` URI.

This is the key design decision: the **process definition is the configuration**, not the code.

---

### Part 6 — Data Sovereignty (2 min)

*[Point to the parameters section in the Shipper frontend at MEASUREMENT_RECEIVED state.]*

One more thing worth highlighting: the weight doesn't travel until the purchase is confirmed. This is not just a UI decision — it's enforced at the data level. Certiweight's process only includes `grossMass` in the VGM_PURCHASED notification. Before that point, the number doesn't exist in Van Moer's process instance.

The EDC policy governs *who* can access Van Moer's and Certiweight's process services and *when*. The BPMN governs *what data* moves at each step. Together they give each party control over their data without needing a central broker.

In a production setup you'd add policies on the contract level — for example, requiring the VGM purchase confirmation before the certificate can be downloaded, or restricting access to a specific DID.

---

### Closing (30 sec)

**"Each party runs their own system. Their own process. Under their own control. The dataspace handles the trust layer. The BPMN handles the workflow. The API stays the same regardless of what the process looks like."**

This pilot demonstrates that a process sharing infrastructure can work across independent parties without central coordination — and that changing the business process doesn't require changing the platform.

---

## Pre-demo Checklist

```
[ ] docker compose up -d (both process-service and pilots-dataspace stacks)
[ ] ./register-edc-asset.sh  (run once to register assets with both EDC connectors)
[ ] Open http://localhost:3003  — create one VGM request, note the container number
[ ] Open http://localhost:3002  — confirm empty or only old completed instances
[ ] Both frontends side by side on screen
[ ] Terminal ready with ./vgm-e2e-dataspace.sh (not yet running)
[ ] BPMN viewer loaded with both .bpmn20.xml files (for 20-min demo)
[ ] Editor open on shipper-vgm-process.bpmn20.xml (for 20-min demo)
[ ] Hard-refresh both browser tabs (Ctrl+Shift+R) to clear any JS cache
```
