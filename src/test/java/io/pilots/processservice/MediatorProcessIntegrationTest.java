package io.pilots.processservice;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that exercises the full BPMN lifecycle using a real
 * Flowable engine and H2 in-memory database.
 *
 * No Spring beans are mocked.  The outbound HTTP call made by
 * InvokeInternalApiDelegate is intercepted by a MockWebServer instance
 * whose URL is injected via the 'internalApiUrl' process variable —
 * exactly as it would be in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MediatorProcessIntegrationTest {

    @Autowired
    RuntimeService runtimeService;

    @Autowired
    TaskService taskService;       // available for reference; receive tasks use runtimeService.trigger()

    @Autowired
    HistoryService historyService;

    private MockWebServer server;

    @BeforeEach
    void startMockServer() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopMockServer() throws IOException {
        server.shutdown();
    }

    // -------------------------------------------------------------------------

    @Test
    void sampleMediatorProcess_firesDelegate_pausesAtReceiveTask_andCompletesOnTrigger()
            throws Exception {

        // ── Arrange ──────────────────────────────────────────────────────────
        // Enqueue a 200 OK so InvokeInternalApiDelegate does not throw BpmnError
        server.enqueue(new MockResponse().setResponseCode(200));

        // Point the process variable at MockWebServer — no Spring-bean mocking needed
        String notifyUrl = server.url("/internal/notify").toString();
        Map<String, Object> processVars = Map.of(
                "internalApiUrl", notifyUrl,
                "payloadData",    "{\"action\":\"create\"}"
        );

        // ── Act: start the process ────────────────────────────────────────────
        // The service task runs synchronously within startProcessInstanceByKey()
        // (it is not marked flowable:async); the engine advances to the receive
        // task and pauses before returning.
        ProcessInstance pi = runtimeService.startProcessInstanceByKey(
                "sample-service-process", processVars);

        assertThat(pi).as("process instance should be created").isNotNull();

        // ── Assert: delegate fired the outbound HTTP POST ─────────────────────
        RecordedRequest outbound = server.takeRequest(2, TimeUnit.SECONDS);
        assertThat(outbound)
                .as("InvokeInternalApiDelegate must POST to internalApiUrl before pausing")
                .isNotNull();
        assertThat(outbound.getMethod()).isEqualTo("POST");
        assertThat(outbound.getPath()).isEqualTo("/internal/notify");
        assertThat(outbound.getHeader("Content-Type")).contains("application/json");

        // ── Assert: process is paused at the receive task ─────────────────────
        Execution waitExecution = runtimeService.createExecutionQuery()
                .processInstanceId(pi.getId())
                .activityId("waitForInternalAppReply")
                .singleResult();

        assertThat(waitExecution)
                .as("execution must be suspended at 'waitForInternalAppReply' receive task")
                .isNotNull();

        // The receive task is NOT a user task — TaskService query returns nothing
        assertThat(taskService.createTaskQuery()
                .processInstanceId(pi.getId())
                .singleResult())
                .as("receiveTask must not appear in TaskService query (use runtimeService.trigger)")
                .isNull();

        // Historic record exists but endTime is still null (process still running)
        HistoricProcessInstance running = historyService
                .createHistoricProcessInstanceQuery()
                .processInstanceId(pi.getId())
                .singleResult();
        assertThat(running.getEndTime())
                .as("process endTime must be null while waiting at receive task")
                .isNull();

        // ── Act: trigger the receive task ─────────────────────────────────────
        // This simulates the internal application calling back via PATCH /serviceInstances/{id},
        // which should invoke runtimeService.trigger(executionId) — see controller note in Task 5.
        runtimeService.trigger(waitExecution.getId());

        // ── Assert: process has completed ─────────────────────────────────────
        HistoricProcessInstance completed = historyService
                .createHistoricProcessInstanceQuery()
                .processInstanceId(pi.getId())
                .singleResult();

        assertThat(completed).isNotNull();
        assertThat(completed.getEndTime())
                .as("process must have a non-null endTime after the receive task is triggered")
                .isNotNull();
    }
}
