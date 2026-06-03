package io.pilots.processservice.delegate;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.flowable.common.engine.api.delegate.Expression;
import org.flowable.engine.delegate.BpmnError;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HttpPostDelegateTest {

    private MockWebServer server;
    private HttpPostDelegate delegate;

    @Mock
    private DelegateExecution execution;

    @Mock
    private Expression urlExpression;

    @Mock
    private Expression payloadExpression;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        RestClient restClient = RestClient.builder().build();
        delegate = new HttpPostDelegate(restClient);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void execute_completesNormally_whenServerReturns2xx() throws InterruptedException {
        String url = server.url("/internal/notify").toString();
        server.enqueue(new MockResponse().setResponseCode(200));

        // Inject field expressions via reflection (simulating Flowable field injection)
        when(urlExpression.getValue(execution)).thenReturn(url);
        when(payloadExpression.getValue(execution)).thenReturn("{\"orderId\":\"123\"}");
        ReflectionTestUtils.setField(delegate, "url", urlExpression);
        ReflectionTestUtils.setField(delegate, "payload", payloadExpression);

        assertThatCode(() -> delegate.execute(execution)).doesNotThrowAnyException();

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getHeader("Content-Type")).contains("application/json");
        assertThat(request.getBody().readUtf8()).isEqualTo("{\"orderId\":\"123\"}");
    }

    @Test
    void execute_throwsBpmnError_whenServerReturnsNon2xx() {
        String url = server.url("/internal/notify").toString();
        server.enqueue(new MockResponse()
                .setResponseCode(422)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"unprocessable\"}"));

        when(urlExpression.getValue(execution)).thenReturn(url);
        when(payloadExpression.getValue(execution)).thenReturn("{\"orderId\":\"123\"}");
        ReflectionTestUtils.setField(delegate, "url", urlExpression);
        ReflectionTestUtils.setField(delegate, "payload", payloadExpression);

        assertThatThrownBy(() -> delegate.execute(execution))
                .isInstanceOf(BpmnError.class)
                .satisfies(e -> {
                    BpmnError error = (BpmnError) e;
                    assertThat(error.getErrorCode()).isEqualTo("HTTP_POST_ERROR");
                    assertThat(error.getMessage()).contains("422");
                });
    }

    @Test
    void execute_throwsBpmnError_whenUrlIsNull() {
        // No url field set — both expressions are null by default
        assertThatThrownBy(() -> delegate.execute(execution))
                .isInstanceOf(BpmnError.class)
                .satisfies(e -> {
                    BpmnError error = (BpmnError) e;
                    assertThat(error.getErrorCode()).isEqualTo("HTTP_POST_ERROR");
                    assertThat(error.getMessage()).contains("url field is missing or blank");
                });
    }
}
