package io.pilots.processservice.delegate;

import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Flowable delegate that makes a synchronous HTTP POST to an internal application.
 * Implements both {@link JavaDelegate} (for serviceTask) and {@link ExecutionListener}
 * (for intermediateThrowEvent execution listeners).
 *
 * Required process variables:
 * <ul>
 *   <li>{@code internalApiUrl}  – fully-qualified URL to POST to</li>
 *   <li>{@code payloadData}     – request body string (typically JSON); may be null</li>
 * </ul>
 *
 * On a non-2xx response a {@link BpmnError} with code {@code INTERNAL_API_ERROR} is thrown.
 */
public class InvokeInternalApiDelegate implements JavaDelegate, ExecutionListener {

    private final RestClient restClient;
    private final String urlVariable;
    private final String payloadVariable;

    public InvokeInternalApiDelegate(RestClient restClient) {
        this(restClient, "internalApiUrl", "payloadData");
    }

    public InvokeInternalApiDelegate(RestClient restClient, String urlVariable, String payloadVariable) {
        this.restClient = restClient;
        this.urlVariable = urlVariable;
        this.payloadVariable = payloadVariable;
    }

    @Override
    public void notify(DelegateExecution execution) {
        execute(execution);
    }

    @Override
    public void execute(DelegateExecution execution) {
        String url = (String) execution.getVariable(urlVariable);
        String payload = (String) execution.getVariable(payloadVariable);

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
