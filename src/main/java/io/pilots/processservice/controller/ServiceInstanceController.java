package io.pilots.processservice.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pilots.processservice.api.ServiceInstancesApi;
import io.pilots.processservice.api.model.ServiceInstance;
import io.pilots.processservice.api.model.ServiceInstanceCollection;
import io.pilots.processservice.api.model.ServiceInstanceCreate;
import io.pilots.processservice.api.model.ServiceInstancePatch;
import io.pilots.processservice.api.model.ServiceInstanceReplace;
import io.pilots.processservice.api.model.Stakeholder;
import io.pilots.processservice.api.model.StakeholderRole;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.eventsubscription.api.EventSubscription;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller implementing the Service Instances API contract.
 *
 * <p>Each service instance maps 1-to-1 with a Flowable process instance. The
 * externally-visible {@code id} is a UUID stored as the Flowable
 * {@code businessKey}; Flowable's own numeric process-instance ID is an
 * internal detail. Process variables carry the client-supplied
 * {@code parameters} plus two private tracking entries:
 * <ul>
 *   <li>{@code _state}          – current service-instance state string</li>
 *   <li>{@code _stakeholders}   – JSON-encoded stakeholder list for response
 *                                  round-tripping (Flowable does not natively
 *                                  store non-serialisable POJOs)</li>
 * </ul>
 */
@RestController
public class ServiceInstanceController implements ServiceInstancesApi {

    // Variable name prefix used to distinguish internal tracking variables
    // from client-supplied business parameters.
    private static final String INTERNAL_PREFIX = "_";

    private final RuntimeService runtimeService;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final ObjectMapper objectMapper;

    public ServiceInstanceController(RuntimeService runtimeService,
                                     TaskService taskService,
                                     HistoryService historyService,
                                     ObjectMapper objectMapper) {
        this.runtimeService = runtimeService;
        this.taskService = taskService;
        this.historyService = historyService;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // POST /serviceInstances
    // -------------------------------------------------------------------------

    @Override
    public ResponseEntity<ServiceInstance> createServiceInstance(ServiceInstanceCreate body) {
        // The serviceDefinition URI string is used as the BPMN process definition key.
        String processKey = body.getServiceDefinition().toString();

        // Client parameters become Flowable process variables directly.
        Map<String, Object> variables = new HashMap<>(body.getParameters());

        // Store tracking data as underscore-prefixed variables so they can be
        // recovered in subsequent PATCH calls without a separate persistence layer.
        String initialState = body.getState() != null ? body.getState() : "STARTED";
        variables.put("_state", initialState);
        variables.put("_stakeholders", serializeStakeholders(body.getStakeholders()));
        variables.put("_version", 1);

        // Generate our own UUID and pass it as the Flowable business key.
        // This decouples the public API identifier from Flowable's internal
        // numeric process-instance ID and satisfies the spec's format:uuid constraint.
        UUID id = UUID.randomUUID();

        ProcessInstance pi;
        try {
            pi = runtimeService.startProcessInstanceByKey(processKey, id.toString(), variables);
        } catch (FlowableObjectNotFoundException ex) {
            // No deployed process definition matches the given serviceDefinition key.
            // Callers must deploy a BPMN process whose key equals the serviceDefinition URI.
            return ResponseEntity.unprocessableEntity().build();
        }

        OffsetDateTime now = OffsetDateTime.now();

        ServiceInstance response = new ServiceInstance()
                .id(id)
                .serviceDefinition(body.getServiceDefinition())
                .stakeholders(body.getStakeholders())
                .serviceOffering(body.getServiceOffering())
                .serviceQuotation(body.getServiceQuotation())
                .parameters(body.getParameters())
                .payload(body.getPayload())
                .payloadMimeType(body.getPayloadMimeType())
                .state(initialState)
                .version(1)
                .createdAt(now)
                .updatedAt(now)
                .eventCount(0);

        return ResponseEntity
                .created(URI.create("/serviceInstances/" + id))
                .eTag("1")
                .body(response);
    }

    // -------------------------------------------------------------------------
    // PATCH /serviceInstances/{serviceInstanceId}
    // -------------------------------------------------------------------------

    @Override
    public ResponseEntity<ServiceInstance> patchServiceInstance(UUID serviceInstanceId,
                                                                String ifMatch,
                                                                ServiceInstancePatch patch) {
        // Absent header → 428 is handled by GlobalExceptionHandler before we reach here.
        String etag = ifMatch.replace("\"", "").strip();

        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(serviceInstanceId.toString())
                .singleResult();

        if (pi == null) {
            return ResponseEntity.notFound().build();
        }

        String flowableId = pi.getId();

        // Read all variables upfront (before task completion, which may end the process).
        Map<String, Object> allVars = new HashMap<>(runtimeService.getVariables(flowableId));

        // ETag vs. stored version check.
        int currentVersion = readVersion(allVars);
        if (!String.valueOf(currentVersion).equals(etag)) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).build();
        }

        int newVersion = currentVersion + 1;

        // Build a single update map: client parameters + internal tracking fields.
        Map<String, Object> updates = new HashMap<>();
        if (patch.getParameters() != null) {
            updates.putAll(patch.getParameters());
        }

        String state;
        if (patch.getState() != null) {
            state = patch.getState();
            updates.put("_state", state);
        } else {
            state = allVars.getOrDefault("_state", "ACTIVE").toString();
        }

        if (patch.getStakeholders() != null) {
            updates.put("_stakeholders", serializeStakeholders(patch.getStakeholders()));
        }

        updates.put("_version", newVersion);
        runtimeService.setVariables(flowableId, updates);

        // Merge updates into the local snapshot for building the response.
        allVars.putAll(updates);

        Map<String, Object> parameters = new HashMap<>();
        allVars.forEach((k, v) -> {
            if (!k.startsWith(INTERNAL_PREFIX)) parameters.put(k, v);
        });

        List<Stakeholder> stakeholders = patch.getStakeholders() != null
                ? patch.getStakeholders()
                : deserializeStakeholders((String) allVars.get("_stakeholders"));

        URI serviceDefinition = patch.getServiceDefinition() != null
                ? patch.getServiceDefinition()
                : URI.create(pi.getProcessDefinitionKey());

        advanceProcess(flowableId);

        OffsetDateTime now = OffsetDateTime.now();

        return ResponseEntity.ok()
                .eTag(String.valueOf(newVersion))
                .body(new ServiceInstance()
                        .id(serviceInstanceId)
                        .serviceDefinition(serviceDefinition)
                        .stakeholders(stakeholders)
                        .serviceOffering(patch.getServiceOffering())
                        .serviceQuotation(patch.getServiceQuotation())
                        .parameters(parameters)
                        .payload(patch.getPayload())
                        .payloadMimeType(patch.getPayloadMimeType())
                        .state(state)
                        .version(newVersion)
                        .createdAt(now)
                        .updatedAt(now)
                        .eventCount(0));
    }

    // -------------------------------------------------------------------------
    // DELETE /serviceInstances/{serviceInstanceId}
    // -------------------------------------------------------------------------

    @Override
    public ResponseEntity<Void> deleteServiceInstance(UUID serviceInstanceId, String ifMatch) {
        // Absent header → 428 handled by GlobalExceptionHandler.
        String etag = ifMatch.replace("\"", "").strip();

        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(serviceInstanceId.toString())
                .singleResult();

        if (pi == null) {
            return ResponseEntity.notFound().build();
        }

        String flowableId = pi.getId();

        if (!String.valueOf(readVersion(runtimeService.getVariables(flowableId))).equals(etag)) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).build();
        }

        runtimeService.deleteProcessInstance(flowableId, "Deleted via API");
        return ResponseEntity.noContent().build();
    }

    @org.springframework.web.bind.annotation.GetMapping("/serviceInstances/{id}/currentActivities")
    public ResponseEntity<Map<String, Object>> getCurrentActivities(
            @org.springframework.web.bind.annotation.PathVariable("id") UUID id) {
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(id.toString())
                .singleResult();

        List<String> activities;
        if (pi != null) {
            activities = runtimeService.getActiveActivityIds(pi.getId());
        } else {
            HistoricProcessInstance hpi = historyService
                    .createHistoricProcessInstanceQuery()
                    .processInstanceBusinessKey(id.toString())
                    .singleResult();
            if (hpi == null) return ResponseEntity.notFound().build();
            activities = List.of();
        }

        return ResponseEntity.ok(Map.of("activities", activities));
    }

    @Override
    public ResponseEntity<ServiceInstance> getServiceInstance(UUID serviceInstanceId) {
        // Try the active (running) process first.
        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(serviceInstanceId.toString())
                .singleResult();

        Map<String, Object> allVars;
        String processDefinitionKey;

        if (pi != null) {
            allVars = runtimeService.getVariables(pi.getId());
            processDefinitionKey = pi.getProcessDefinitionKey();
        } else {
            // Fall back to history for completed/terminated instances.
            HistoricProcessInstance hpi = historyService
                    .createHistoricProcessInstanceQuery()
                    .processInstanceBusinessKey(serviceInstanceId.toString())
                    .singleResult();
            if (hpi == null) {
                return ResponseEntity.notFound().build();
            }
            allVars = new HashMap<>();
            historyService.createHistoricVariableInstanceQuery()
                    .processInstanceId(hpi.getId())
                    .list()
                    .forEach(v -> allVars.put(v.getVariableName(), v.getValue()));
            processDefinitionKey = hpi.getProcessDefinitionKey();
        }

        Object stateObj = allVars.get("_state");
        String state = stateObj != null ? stateObj.toString() : "UNKNOWN";

        int version = readVersion(allVars);

        Map<String, Object> parameters = new HashMap<>();
        allVars.forEach((k, v) -> {
            if (!k.startsWith(INTERNAL_PREFIX)) {
                parameters.put(k, v);
            }
        });

        List<Stakeholder> stakeholders = deserializeStakeholders((String) allVars.get("_stakeholders"));

        OffsetDateTime now = OffsetDateTime.now();

        return ResponseEntity.ok()
                .eTag(String.valueOf(version))
                .body(new ServiceInstance()
                        .id(serviceInstanceId)
                        .serviceDefinition(URI.create(processDefinitionKey))
                        .stakeholders(stakeholders)
                        .parameters(parameters)
                        .state(state)
                        .version(version)
                        .createdAt(now)
                        .updatedAt(now)
                        .eventCount(0));
    }

    @Override
    public ResponseEntity<ServiceInstanceCollection> listServiceInstances(
            URI serviceDefinition, URI serviceOffering, URI serviceQuotation,
            String state, StakeholderRole stakeholderRole, String stakeholderParty,
            OffsetDateTime createdAfter, OffsetDateTime createdBefore,
            OffsetDateTime updatedAfter, OffsetDateTime updatedBefore,
            String lastEventType, String parameterKey, String parameterValue,
            Integer limit, Integer offset) {

        // HistoricProcessInstanceQuery covers both active and finished instances.
        HistoricProcessInstanceQuery query = historyService
                .createHistoricProcessInstanceQuery()
                .orderByProcessInstanceStartTime().asc();

        if (serviceDefinition != null) {
            query.processDefinitionKey(serviceDefinition.toString());
        }
        if (createdAfter != null) {
            query.startedAfter(java.util.Date.from(createdAfter.toInstant()));
        }
        if (createdBefore != null) {
            query.startedBefore(java.util.Date.from(createdBefore.toInstant()));
        }

        List<HistoricProcessInstance> rawList = query.list();

        List<ServiceInstance> allItems = new ArrayList<>();
        for (HistoricProcessInstance hpi : rawList) {
            // Only process instances whose businessKey is one of our UUIDs.
            UUID instanceId;
            try {
                instanceId = UUID.fromString(hpi.getBusinessKey());
            } catch (IllegalArgumentException | NullPointerException e) {
                continue;
            }

            // Load variables: live for active, historic for finished.
            Map<String, Object> allVars;
            if (hpi.getEndTime() == null) {
                allVars = runtimeService.getVariables(hpi.getId());
            } else {
                allVars = new HashMap<>();
                historyService.createHistoricVariableInstanceQuery()
                        .processInstanceId(hpi.getId())
                        .list()
                        .forEach(v -> allVars.put(v.getVariableName(), v.getValue()));
            }

            String instanceState = allVars.getOrDefault("_state", "UNKNOWN").toString();

            // --- In-memory filters ---
            if (state != null && !state.equals(instanceState)) {
                continue;
            }

            List<Stakeholder> stakeholders = deserializeStakeholders((String) allVars.get("_stakeholders"));
            if (stakeholderRole != null || stakeholderParty != null) {
                boolean match = stakeholders.stream().anyMatch(s -> {
                    boolean roleOk = stakeholderRole == null || stakeholderRole.equals(s.getRole());
                    boolean partyOk = stakeholderParty == null || stakeholderParty.equals(s.getParty());
                    return roleOk && partyOk;
                });
                if (!match) continue;
            }

            Map<String, Object> parameters = new HashMap<>();
            allVars.forEach((k, v) -> {
                if (!k.startsWith(INTERNAL_PREFIX)) parameters.put(k, v);
            });

            if (parameterKey != null) {
                Object val = parameters.get(parameterKey);
                if (val == null) continue;
                if (parameterValue != null && !parameterValue.equals(val.toString())) continue;
            }

            OffsetDateTime createdAt = hpi.getStartTime() != null
                    ? hpi.getStartTime().toInstant().atOffset(ZoneOffset.UTC)
                    : OffsetDateTime.now();

            allItems.add(new ServiceInstance()
                    .id(instanceId)
                    .serviceDefinition(URI.create(hpi.getProcessDefinitionKey()))
                    .stakeholders(stakeholders)
                    .parameters(parameters)
                    .state(instanceState)
                    .version(readVersion(allVars))
                    .createdAt(createdAt)
                    .updatedAt(createdAt)
                    .eventCount(0));
        }

        int total = allItems.size();
        int safeOffset = offset != null ? offset : 0;
        int safeLimit = limit != null ? limit : 50;

        List<ServiceInstance> page = allItems.stream()
                .skip(safeOffset)
                .limit(safeLimit)
                .collect(Collectors.toList());

        return ResponseEntity.ok(new ServiceInstanceCollection(page, page.size(), safeLimit, safeOffset)
                .total(total));
    }

    // -------------------------------------------------------------------------
    // PUT /serviceInstances/{serviceInstanceId}
    // -------------------------------------------------------------------------

    @Override
    public ResponseEntity<ServiceInstance> replaceServiceInstance(UUID serviceInstanceId,
                                                                  String ifMatch,
                                                                  ServiceInstanceReplace body) {
        // Absent header → 428 handled by GlobalExceptionHandler.
        String etag = ifMatch.replace("\"", "").strip();

        ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(serviceInstanceId.toString())
                .singleResult();

        if (pi == null) {
            return ResponseEntity.notFound().build();
        }

        String flowableId = pi.getId();
        Map<String, Object> currentVars = runtimeService.getVariables(flowableId);

        int currentVersion = readVersion(currentVars);
        if (!String.valueOf(currentVersion).equals(etag)) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).build();
        }

        int newVersion = currentVersion + 1;

        // Full replace: remove any parameter variable not present in the new body.
        Map<String, Object> newParams = body.getParameters() != null ? body.getParameters() : Map.of();
        List<String> toRemove = currentVars.keySet().stream()
                .filter(k -> !k.startsWith(INTERNAL_PREFIX) && !newParams.containsKey(k))
                .collect(Collectors.toList());
        if (!toRemove.isEmpty()) {
            runtimeService.removeVariables(flowableId, toRemove);
        }

        String state = body.getState() != null ? body.getState() : "ACTIVE";

        Map<String, Object> updates = new HashMap<>(newParams);
        updates.put("_state", state);
        updates.put("_stakeholders", serializeStakeholders(body.getStakeholders()));
        updates.put("_version", newVersion);
        runtimeService.setVariables(flowableId, updates);

        advanceProcess(flowableId);

        OffsetDateTime now = OffsetDateTime.now();

        URI serviceDefinition = body.getServiceDefinition() != null
                ? body.getServiceDefinition()
                : URI.create(pi.getProcessDefinitionKey());

        return ResponseEntity.ok()
                .eTag(String.valueOf(newVersion))
                .body(new ServiceInstance()
                        .id(serviceInstanceId)
                        .serviceDefinition(serviceDefinition)
                        .stakeholders(body.getStakeholders())
                        .serviceOffering(body.getServiceOffering())
                        .serviceQuotation(body.getServiceQuotation())
                        .parameters(newParams)
                        .payload(body.getPayload())
                        .payloadMimeType(body.getPayloadMimeType())
                        .state(state)
                        .version(newVersion)
                        .createdAt(now)
                        .updatedAt(now)
                        .eventCount(0));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Advance the process instance one step. Tried in priority order:
     * 1. Active userTask → taskService.complete()
     * 2. Waiting intermediateCatchEvent with message subscription → messageEventReceived()
     * 3. Waiting receiveTask → runtimeService.trigger()
     */
    private void advanceProcess(String flowableId) {
        Task task = taskService.createTaskQuery()
                .processInstanceId(flowableId)
                .singleResult();
        if (task != null) {
            taskService.complete(task.getId());
            return;
        }

        EventSubscription sub = runtimeService.createEventSubscriptionQuery()
                .processInstanceId(flowableId)
                .eventType("message")
                .singleResult();
        if (sub != null) {
            runtimeService.messageEventReceived(sub.getEventName(), sub.getExecutionId());
            return;
        }

        Execution waiting = runtimeService.createExecutionQuery()
                .processInstanceId(flowableId)
                .onlyChildExecutions()
                .singleResult();
        if (waiting != null) {
            runtimeService.trigger(waiting.getId());
        }
    }

    private int readVersion(Map<String, Object> vars) {
        Object v = vars.get("_version");
        return (v instanceof Number) ? ((Number) v).intValue() : 1;
    }

    private String serializeStakeholders(List<Stakeholder> stakeholders) {
        try {
            return objectMapper.writeValueAsString(stakeholders);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize stakeholders", e);
        }
    }

    private List<Stakeholder> deserializeStakeholders(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Stakeholder>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
