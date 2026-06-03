package io.pilots.processservice;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the two-party VGM weighing flow.
 *
 * Both BPMN processes (certiweightVGMProcess + shipperProcess) run on the same
 * embedded Flowable engine. A MockWebServer intercepts every outbound POST made
 * by InvokeInternalApiDelegate, standing in for the real ERP/TMS endpoints.
 *
 * Expected outbound calls in order:
 *   1. sendOrderCreated       → /erp/order-created          (on certiweight POST)
 *   2. sendTruckerAnnounced   → /erp/trucker-announced      (on certiweight POST)
 *   3. sendMeasurementCreated → /erp/measurement-created    (on certiweight POST)
 *   4. sendPurchaseVGM        → /tms/purchase-vgm           (on shipper PATCH MEASUREMENT_RECEIVED)
 *   5. sendVGMPurchased       → /erp/vgm-purchased          (on certiweight PATCH PURCHASE_CONFIRMED)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VgmE2ETest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    private MockWebServer mockServer;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        for (int i = 0; i < 5; i++) {
            mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void fullVgmFlow_bothProcessesAdvanceCorrectlyAndComplete() throws Exception {

        String orderCreatedUrl       = mockServer.url("/erp/order-created").toString();
        String truckerAnnouncedUrl   = mockServer.url("/erp/trucker-announced").toString();
        String measurementCreatedUrl = mockServer.url("/erp/measurement-created").toString();
        String purchaseVgmUrl        = mockServer.url("/tms/purchase-vgm").toString();
        String vgmPurchasedUrl       = mockServer.url("/erp/vgm-purchased").toString();

        // ── Step 1: Shipper creates process instance ──────────────────────────
        // Shipper's process starts at a receiveTask → pauses immediately.
        // Must exist before Certiweight can reference its ID.

        ResponseEntity<Map> shipperCreate = restTemplate.postForEntity(
                url("/serviceInstances"),
                Map.of(
                        "serviceDefinition", "shipperProcess",
                        "stakeholders", List.of(
                                Map.of("role", "customer", "party", "did:web:participant-2"),
                                Map.of("role", "provider", "party", "did:web:participant-1")
                        ),
                        "parameters", Map.of(
                                "containerNr", "TCKU1234567",
                                "bookingNr",   "BKG-2026-001"
                        )
                ),
                Map.class);

        assertThat(shipperCreate.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> shipBody = shipperCreate.getBody();
        String shipId = (String) shipBody.get("id");
        assertThat(shipId).isNotNull();
        assertThat(shipBody.get("state")).isEqualTo("STARTED");
        assertThat(shipBody.get("version")).isEqualTo(1);

        assertThat(mockServer.getRequestCount()).isEqualTo(0);

        // ── Step 2: Certiweight creates process instance ──────────────────────
        // Process starts at waitToSendOrder (no sends fire yet).

        ResponseEntity<Map> certCreate = restTemplate.postForEntity(
                url("/serviceInstances"),
                Map.of(
                        "serviceDefinition", "certiweightVGMProcess",
                        "stakeholders", List.of(
                                Map.of("role", "provider", "party", "did:web:participant-1"),
                                Map.of("role", "customer", "party", "did:web:participant-2")
                        ),
                        "parameters", Map.of(
                                "containerNr",       "TCKU1234567",
                                "bookingNr",         "BKG-2026-001",
                                "shipperInstanceId", shipId
                        )
                ),
                Map.class);

        assertThat(certCreate.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> certBody = certCreate.getBody();
        String certId = (String) certBody.get("id");
        assertThat(certId).isNotNull();
        assertThat(certBody.get("state")).isEqualTo("STARTED");
        assertThat(certBody.get("version")).isEqualTo(1);

        // No outbound calls yet — process is paused at waitToSendOrder
        assertThat(mockServer.getRequestCount()).isEqualTo(0);

        // ── Step 2a: ERP triggers sendOrderCreated ────────────────────────────

        ResponseEntity<Map> certPatchOC = patch(certId, "1", "ORDER_CREATED",
                Map.of(
                        "internalApiUrl", orderCreatedUrl,
                        "payloadData",    "{\"containerNr\":\"TCKU1234567\",\"bookingNr\":\"BKG-2026-001\"}"
                ));
        assertThat(certPatchOC.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(certPatchOC.getBody().get("state")).isEqualTo("ORDER_CREATED");
        assertThat(certPatchOC.getBody().get("version")).isEqualTo(2);

        RecordedRequest req1 = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req1).as("sendOrderCreated POST").isNotNull();
        assertThat(req1.getPath()).isEqualTo("/erp/order-created");
        assertThat(req1.getBody().readUtf8()).contains("TCKU1234567");
        assertThat(mockServer.getRequestCount()).isEqualTo(1);

        // ── Step 2b: ERP triggers sendTruckerAnnounced ───────────────────────

        ResponseEntity<Map> certPatchTA = patch(certId, "2", "TRUCKER_ANNOUNCED",
                Map.of(
                        "truckerApiUrl",  truckerAnnouncedUrl,
                        "truckerPayload", "{\"containerNr\":\"TCKU1234567\",\"truckId\":\"TRK-42\"}"
                ));
        assertThat(certPatchTA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(certPatchTA.getBody().get("state")).isEqualTo("TRUCKER_ANNOUNCED");
        assertThat(certPatchTA.getBody().get("version")).isEqualTo(3);

        RecordedRequest req2 = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req2).as("sendTruckerAnnounced POST").isNotNull();
        assertThat(req2.getPath()).isEqualTo("/erp/trucker-announced");
        assertThat(mockServer.getRequestCount()).isEqualTo(2);

        // ── Step 2c: ERP triggers sendMeasurementCreated ─────────────────────

        ResponseEntity<Map> certPatchMC = patch(certId, "3", "MEASUREMENT_CREATED",
                Map.of(
                        "measurementApiUrl",  measurementCreatedUrl,
                        "measurementPayload", "{\"containerNr\":\"TCKU1234567\",\"grossMass\":24500,\"unit\":\"kg\"}"
                ));
        assertThat(certPatchMC.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(certPatchMC.getBody().get("state")).isEqualTo("MEASUREMENT_CREATED");
        assertThat(certPatchMC.getBody().get("version")).isEqualTo(4);

        RecordedRequest req3 = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req3).as("sendMeasurementCreated POST").isNotNull();
        assertThat(req3.getPath()).isEqualTo("/erp/measurement-created");
        assertThat(req3.getBody().readUtf8()).contains("24500");

        // Certiweight is now paused at waitPurchaseVGM
        assertThat(mockServer.getRequestCount()).isEqualTo(3);

        // ── Step 2d: (Certiweight ERP → Shipper) advance waitOrderCreated ─────

        ResponseEntity<Map> shipPatch1 = patch(shipId, "1",
                "ORDER_CONFIRMED",
                Map.of(
                        "certiweightInstanceId", certId,
                        "containerNr",           "TCKU1234567"
                ));

        assertThat(shipPatch1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(shipPatch1.getBody().get("state")).isEqualTo("ORDER_CONFIRMED");
        assertThat(shipPatch1.getBody().get("version")).isEqualTo(2);
        assertThat(mockServer.getRequestCount()).isEqualTo(3);

        // ── Step 2c: (Certiweight ERP → Shipper) advance waitTruckerAnnounced ──

        ResponseEntity<Map> shipPatchTA = patch(shipId, "2",
                "TRUCKER_ANNOUNCED",
                Map.of("transportbedrijf", "Van Moer Transport"));

        assertThat(shipPatchTA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(shipPatchTA.getBody().get("state")).isEqualTo("TRUCKER_ANNOUNCED");
        assertThat(shipPatchTA.getBody().get("version")).isEqualTo(3);
        assertThat(mockServer.getRequestCount()).isEqualTo(3);

        // ── Step 3: (Certiweight ERP → Shipper) advance waitMeasurementCreated ─
        // This PATCH triggers sendPurchaseVGM immediately.

        ResponseEntity<Map> shipPatch2 = patch(shipId, "3",
                "MEASUREMENT_RECEIVED",
                Map.of(
                        "internalApiUrl", purchaseVgmUrl,
                        "payloadData",    "{\"containerNr\":\"TCKU1234567\",\"grossMass\":24500}"
                ));

        assertThat(shipPatch2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(shipPatch2.getBody().get("state")).isEqualTo("MEASUREMENT_RECEIVED");
        assertThat(shipPatch2.getBody().get("version")).isEqualTo(4);

        RecordedRequest req4 = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req4).as("sendPurchaseVGM POST").isNotNull();
        assertThat(req4.getPath()).isEqualTo("/tms/purchase-vgm");
        assertThat(req4.getBody().readUtf8()).contains("TCKU1234567");

        assertThat(mockServer.getRequestCount()).isEqualTo(4);

        // ── Step 4: (Certiweight ERP → Certiweight) advance waitPurchaseVGM ──
        // Payment processed on Certiweight's platform; runs taskProcessPayment,
        // taskCreateCertificate, sendVGMPurchased, then ENDS.

        ResponseEntity<Map> certPatch1 = patch(certId, "4",
                "PURCHASE_CONFIRMED",
                Map.of(
                        "internalApiUrl", vgmPurchasedUrl,
                        "payloadData",    "{\"containerNr\":\"TCKU1234567\",\"certificateRef\":\"CERT-2026-001\"}"
                ));

        assertThat(certPatch1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(certPatch1.getBody().get("state")).isEqualTo("PURCHASE_CONFIRMED");
        assertThat(certPatch1.getBody().get("version")).isEqualTo(5);

        RecordedRequest req5 = mockServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(req5).as("sendVGMPurchased POST").isNotNull();
        assertThat(req5.getPath()).isEqualTo("/erp/vgm-purchased");
        assertThat(req5.getBody().readUtf8()).contains("CERT-2026-001");

        assertThat(mockServer.getRequestCount()).isEqualTo(5);

        // ── Step 5: (Certiweight ERP → Shipper) advance waitVGMPurchased ──────

        ResponseEntity<Map> shipPatch3 = patch(shipId, "4",
                "VGM_PURCHASED",
                Map.of(
                        "grossMass",      24500,
                        "certificateRef", "CERT-2026-001",
                        "certificateUrl", "https://certiweight.example.com/certs/CERT-2026-001.pdf"
                ));

        assertThat(shipPatch3.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(shipPatch3.getBody().get("state")).isEqualTo("VGM_PURCHASED");
        assertThat(shipPatch3.getBody().get("version")).isEqualTo(5);

        assertThat(mockServer.getRequestCount()).isEqualTo(5);

        // ── Verify final state via GET ────────────────────────────────────────

        ResponseEntity<Map> getCert = restTemplate.getForEntity(url("/serviceInstances/" + certId), Map.class);
        assertThat(getCert.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getCert.getBody().get("state")).isEqualTo("PURCHASE_CONFIRMED");
        assertThat(getCert.getBody().get("version")).isEqualTo(5);

        ResponseEntity<Map> getShip = restTemplate.getForEntity(url("/serviceInstances/" + shipId), Map.class);
        assertThat(getShip.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getShip.getBody().get("state")).isEqualTo("VGM_PURCHASED");
        assertThat(getShip.getBody().get("version")).isEqualTo(5);

        // ── Verify both instances appear in LIST ──────────────────────────────

        ResponseEntity<Map> list = restTemplate.getForEntity(url("/serviceInstances"), Map.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) list.getBody().get("total")).isGreaterThanOrEqualTo(2);

        ResponseEntity<Map> certList = restTemplate.getForEntity(
                url("/serviceInstances?serviceDefinition=certiweightVGMProcess"), Map.class);
        assertThat(certList.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> certItems = (List<Map<String, Object>>) certList.getBody().get("items");
        assertThat(certItems).anyMatch(i -> certId.equals(i.get("id")));

        ResponseEntity<Map> shipList = restTemplate.getForEntity(
                url("/serviceInstances?serviceDefinition=shipperProcess"), Map.class);
        assertThat(shipList.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> shipItems = (List<Map<String, Object>>) shipList.getBody().get("items");
        assertThat(shipItems).anyMatch(i -> shipId.equals(i.get("id")));
    }

    // ─────────────────────────────────────────────────────────────────────────

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> patch(String instanceId, String version,
                                      String state, Map<String, Object> parameters) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("If-Match", "\"" + version + "\"");

        Map<String, Object> body = Map.of("state", state, "parameters", parameters);
        return restTemplate.exchange(
                url("/serviceInstances/" + instanceId),
                HttpMethod.PATCH,
                new HttpEntity<>(body, headers),
                Map.class);
    }
}
