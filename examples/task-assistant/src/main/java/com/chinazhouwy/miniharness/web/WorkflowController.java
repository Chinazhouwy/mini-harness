package com.chinazhouwy.miniharness.web;

import com.chinazhouwy.miniharness.runtime.HarnessEngine;
import com.chinazhouwy.miniharness.runtime.core.HarnessException;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowDefinition;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Workflow Canvas 后端：版本列表、发布指针、版本差异、回滚。 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final HarnessEngine engine;

    public WorkflowController(HarnessEngine engine) {
        this.engine = engine;
    }

    @GetMapping
    public List<Map<String, Object>> listWorkflows() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String name : engine.workflows().knownWorkflowNames()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("publishedVersion", engine.workflows().publishedVersion(name).orElse(null));
            List<Map<String, Object>> versions = new ArrayList<>();
            for (WorkflowDefinition definition : engine.workflows().versions(name)) {
                versions.add(ApiMappers.workflowDefinition(definition,
                        definition.ref().version() == engine.workflows().publishedVersion(name).orElse(-1)));
            }
            entry.put("versions", versions);
            List<Map<String, Object>> history = new ArrayList<>();
            for (WorkflowRegistry.Publication publication : engine.workflows().publicationHistory(name)) {
                history.add(ApiMappers.publication(publication));
            }
            entry.put("publicationHistory", history);
            result.add(entry);
        }
        return result;
    }

    /** 版本差异（v1 → v2 节点/边对称差）。 */
    @GetMapping("/{name}/diff")
    public Map<String, Object> diff(@PathVariable String name,
                                    @RequestParam int fromVersion,
                                    @RequestParam int toVersion) {
        WorkflowDefinition from = engine.workflows().find(name, fromVersion)
                .orElseThrow(() -> new HarnessException("WORKFLOW_NOT_FOUND",
                        "版本不存在: " + name + " v" + fromVersion));
        WorkflowDefinition to = engine.workflows().find(name, toVersion)
                .orElseThrow(() -> new HarnessException("WORKFLOW_NOT_FOUND",
                        "版本不存在: " + name + " v" + toVersion));
        return ApiMappers.workflowDiff(from, to);
    }

    public record RollbackRequest(int targetVersion, String operator) {
    }

    @PostMapping("/{name}/rollback")
    public ResponseEntity<Map<String, Object>> rollback(@PathVariable String name,
                                                        @RequestBody RollbackRequest request) {
        String operator = request.operator() == null || request.operator().isBlank()
                ? "operator" : request.operator();
        WorkflowDefinition rolled = engine.rollbackWorkflow(name, request.targetVersion(), operator);
        return ResponseEntity.ok(Map.of(
                "name", name,
                "publishedVersion", rolled.ref().version(),
                "operator", operator));
    }
}
