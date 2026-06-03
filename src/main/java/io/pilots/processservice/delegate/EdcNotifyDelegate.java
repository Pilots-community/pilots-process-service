package io.pilots.processservice.delegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Generic Flowable delegate that advances a counterparty process instance
 * through the EDC data-plane using a bearer token.
 *
 * Per-task fields (injected via BPMN flowable:field):
 *   targetInstanceId - UUID of the counterparty process instance
 *   notifyState      - state string to set on the target
 *   notifyParams     - JSON object string to merge as parameters (may be null/blank)
 *
 * Per-process variables (set at process creation time by the caller):
 *   edcDataPlaneUrl  - base URL of the counterparty EDC data-plane
 *                      e.g. http://participant-2-dataplane:48185/public
 *   edcBearerToken   - EDR bearer token from the EDC contract negotiation
 *
 * No-op when edcDataPlaneUrl or edcBearerToken is blank (test environments).
 *
 * Usage in BPMN:
 *   <serviceTask flowable:delegateExpression="${edcNotifyDelegate}">
 *     <extensionElements>
 *       <flowable:field name="targetInstanceId" expression="${shipperInstanceId}"/>
 *       <flowable:field name="notifyState"      stringValue="ORDER_CONFIRMED"/>
 *       <flowable:field name="notifyParams"     stringValue="{}"/>
 *     </extensionElements>
 *   </serviceTask>
 */
public class EdcNotifyDelegate implements JavaDelegate {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    // Injected by Flowable from BPMN flowable:field elements
    private Expression targetInstanceId;
    private Expression notifyState;
    private Expression notifyParams;

    public EdcNotifyDelegate(RestClient restClient, ObjectMapper objectMapper) {
        this.restClient   = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String edcUrl   = (String) execution.getVariable("edcDataPlaneUrl");
        String token    = (String) execution.getVariable("edcBearerToken");

        if (edcUrl == null || edcUrl.isBlank() || token == null || token.isBlank()) {
            return; // no-op in test / standalone environments
        }

        String instanceId = targetInstanceId != null
                ? (String) targetInstanceId.getValue(execution) : null;
        String state      = notifyState != null
                ? (String) notifyState.getValue(execution) : null;
        String params     = notifyParams != null
                ? (String) notifyParams.getValue(execution) : null;

        if (instanceId == null || instanceId.isBlank() || state == null || state.isBlank()) {
            return;
        }

        String body;
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("state", state);
            if (params != null && !params.isBlank()) {
                root.set("parameters", objectMapper.readTree(params));
            } else {
                root.set("parameters", objectMapper.createObjectNode());
            }
            body = objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new BpmnError("EDC_NOTIFY_ERROR",
                    "Failed to build notify body: " + e.getMessage());
        }

        String url = edcUrl + "/" + instanceId + "/advance";

        restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .body(body)
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        (req, res) -> {
                            throw new BpmnError("EDC_NOTIFY_ERROR",
                                    "EDC notify returned HTTP " + res.getStatusCode().value()
                                            + " [url=" + url + "]");
                        })
                .toBodilessEntity();
    }
}
