package io.pilots.processservice.delegate;

import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Flowable JavaDelegate that makes a synchronous HTTP POST to an internal application.
 *
 * Required process variables:
 * <ul>
 *   <li>{@code internalApiUrl}  – fully-qualified URL to POST to</li>
 *   <li>{@code payloadData}     – request body string (typically JSON); may be null</li>
 * </ul>
 *
 * On a non-2xx response a {@link BpmnError} with code {@code INTERNAL_API_ERROR} is thrown
 * so it can be caught by a BPMN boundary error event on the service task.
 *
 * Reference from a BPMN service task:
 * <pre>{@code flowable:delegateExpression="${invokeInternalApiDelegate}"}</pre>
 */
@Component
public class InvokeInternalApiDelegate implements JavaDelegate {

    private final RestClient restClient;

    public InvokeInternalApiDelegate(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void execute(DelegateExecution execution) {
        String url = (String) execution.getVariable("internalApiUrl");
        String payload = (String) execution.getVariable("payloadData");

        if (url == null || url.isBlank()) {
            throw new BpmnError("INTERNAL_API_ERROR",
                    "Process variable 'internalApiUrl' is missing or blank");
        }

        restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload != null ? payload : "")
                .retrieve()
                .onStatus(
                        status -> !status.is2xxSuccessful(),
                        (req, res) -> {
                            throw new BpmnError("INTERNAL_API_ERROR",
                                    "Internal API returned HTTP " + res.getStatusCode().value()
                                            + " [url=" + url + "]");
                        })
                .toBodilessEntity();
    }
}
