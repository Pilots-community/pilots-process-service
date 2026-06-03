package io.pilots.processservice.delegate;

import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Generic Flowable delegate that makes a synchronous HTTP POST.
 * URL and payload are injected via BPMN flowable:field elements,
 * so no new Java code is needed for different endpoints.
 *
 * Usage in BPMN:
 *   <serviceTask flowable:delegateExpression="${httpPostDelegate}">
 *     <extensionElements>
 *       <flowable:field name="url"     expression="${someUrlVariable}"/>
 *       <flowable:field name="payload" expression="${somePayloadVariable}"/>
 *     </extensionElements>
 *   </serviceTask>
 */
public class HttpPostDelegate implements JavaDelegate {

    private final RestClient restClient;

    // Injected by Flowable from BPMN flowable:field elements
    private Expression url;
    private Expression payload;

    public HttpPostDelegate(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String targetUrl  = url     != null ? (String) url.getValue(execution)     : null;
        String body       = payload != null ? (String) payload.getValue(execution) : null;

        if (targetUrl == null || targetUrl.isBlank()) {
            throw new BpmnError("HTTP_POST_ERROR", "url field is missing or blank");
        }

        restClient.post()
                .uri(targetUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body != null ? body : "")
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        (req, res) -> {
                            throw new BpmnError("HTTP_POST_ERROR",
                                    "POST returned HTTP " + res.getStatusCode().value()
                                            + " [url=" + targetUrl + "]");
                        })
                .toBodilessEntity();
    }
}
