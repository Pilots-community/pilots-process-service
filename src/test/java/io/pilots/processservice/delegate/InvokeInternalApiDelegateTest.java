package io.pilots.processservice.delegate;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvokeInternalApiDelegateTest {

    private MockWebServer server;
    private InvokeInternalApiDelegate delegate;

    @Mock
    private DelegateExecution execution;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        // Plain RestClient — no base URL; the full URL comes from the process variable
        RestClient restClient = RestClient.builder().build();
        delegate = new InvokeInternalApiDelegate(restClient);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void execute_completesNormally_whenInternalApiReturns2xx() throws InterruptedException {
        String url = server.url("/internal/notify").toString();
        server.enqueue(new MockResponse().setResponseCode(200));

        when(execution.getVariable("internalApiUrl")).thenReturn(url);
        when(execution.getVariable("payloadData")).thenReturn("{\"orderId\":\"123\"}");

        assertThatCode(() -> delegate.execute(execution)).doesNotThrowAnyException();

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getHeader("Content-Type")).contains("application/json");
        assertThat(request.getBody().readUtf8()).isEqualTo("{\"orderId\":\"123\"}");
    }

    @Test
    void execute_throwsBpmnError_whenInternalApiReturnsNon2xx() {
        String url = server.url("/internal/notify").toString();
        server.enqueue(new MockResponse()
                .setResponseCode(422)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"unprocessable\"}"));

        when(execution.getVariable("internalApiUrl")).thenReturn(url);
        when(execution.getVariable("payloadData")).thenReturn("{\"orderId\":\"123\"}");

        assertThatThrownBy(() -> delegate.execute(execution))
                .isInstanceOf(BpmnError.class)
                .satisfies(e -> {
                    BpmnError error = (BpmnError) e;
                    assertThat(error.getErrorCode()).isEqualTo("INTERNAL_API_ERROR");
                    assertThat(error.getMessage()).contains("422");
                });
    }
}
