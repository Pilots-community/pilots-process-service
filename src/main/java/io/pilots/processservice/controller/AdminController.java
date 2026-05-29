package io.pilots.processservice.controller;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final RepositoryService repositoryService;

    public AdminController(RepositoryService repositoryService) {
        this.repositoryService = repositoryService;
    }

    /**
     * POST /admin/deploy — upload and hot-deploy a BPMN file.
     * The engine stays up; new process instances will use the new definition immediately.
     * Existing in-flight instances are unaffected (they stay on the version they started with).
     */
    @PostMapping("/deploy")
    public ResponseEntity<Map<String, Object>> deploy(@RequestParam("file") MultipartFile file)
            throws IOException {

        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.bpmn20.xml";

        Deployment deployment = repositoryService.createDeployment()
                .name(filename)
                .addInputStream(filename, file.getInputStream())
                .deploy();

        List<ProcessDefinition> defs = repositoryService.createProcessDefinitionQuery()
                .deploymentId(deployment.getId())
                .list();

        List<Map<String, Object>> processes = defs.stream()
                .map(d -> Map.<String, Object>of(
                        "key",     d.getKey(),
                        "name",    d.getName() != null ? d.getName() : "",
                        "version", d.getVersion()
                ))
                .toList();

        return ResponseEntity.ok(Map.of(
                "deploymentId", deployment.getId(),
                "file",         filename,
                "processes",    processes
        ));
    }

    /**
     * GET /admin/deployments — list every deployed version of every process, grouped by process key.
     * Each entry includes the processDefinitionId needed to fetch the BPMN XML below.
     */
    @GetMapping("/deployments")
    public ResponseEntity<Map<String, List<Map<String, Object>>>> listDeployments() {
        List<ProcessDefinition> all = repositoryService.createProcessDefinitionQuery()
                .orderByProcessDefinitionKey().asc()
                .orderByProcessDefinitionVersion().asc()
                .list();

        Map<String, List<Map<String, Object>>> grouped = all.stream()
                .collect(Collectors.groupingBy(
                        ProcessDefinition::getKey,
                        Collectors.mapping(d -> Map.<String, Object>of(
                                "processDefinitionId", d.getId(),
                                "version",             d.getVersion(),
                                "deploymentId",        d.getDeploymentId(),
                                "name",                d.getName() != null ? d.getName() : "",
                                "bpmnUrl",             "/admin/deployments/" + d.getId() + "/bpmn"
                        ), Collectors.toList())
                ));

        return ResponseEntity.ok(grouped);
    }

    /**
     * GET /admin/deployments/{processDefinitionId}/bpmn — return the raw BPMN XML for a specific version.
     * Paste into https://bpmn.io to visualise, or diff two versions in the terminal:
     *   diff <(curl .../bpmn?v=1) <(curl .../bpmn?v=2)
     */
    @GetMapping(value = "/deployments/{processDefinitionId}/bpmn", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<byte[]> getBpmn(@PathVariable String processDefinitionId) throws IOException {
        ProcessDefinition def = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId)
                .singleResult();

        if (def == null) {
            return ResponseEntity.notFound().build();
        }

        try (InputStream xml = repositoryService.getProcessModel(processDefinitionId)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .body(xml.readAllBytes());
        }
    }
}
