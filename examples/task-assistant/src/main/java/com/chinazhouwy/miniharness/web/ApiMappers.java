package com.chinazhouwy.miniharness.web;

import com.chinazhouwy.miniharness.runtime.core.NodeId;
import com.chinazhouwy.miniharness.runtime.evolution.EvalCase;
import com.chinazhouwy.miniharness.runtime.evolution.EvalReport;
import com.chinazhouwy.miniharness.runtime.evolution.ExperienceRecord;
import com.chinazhouwy.miniharness.runtime.evolution.WorkflowCandidate;
import com.chinazhouwy.miniharness.runtime.event.HarnessEvent;
import com.chinazhouwy.miniharness.runtime.skill.Skill;
import com.chinazhouwy.miniharness.runtime.task.Run;
import com.chinazhouwy.miniharness.runtime.task.Session;
import com.chinazhouwy.miniharness.runtime.task.Task;
import com.chinazhouwy.miniharness.runtime.workflow.NodeResult;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowDefinition;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎对象 → 前端 JSON 的统一映射。集中在此，避免引擎内部类型直接暴露给 API。
 */
public final class ApiMappers {

    private ApiMappers() {
    }

    public static Map<String, Object> event(HarnessEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("seq", event.seq());
        map.put("eventId", event.eventId());
        map.put("type", event.type().name());
        map.put("taskId", id(event.taskId()));
        map.put("runId", id(event.runId()));
        map.put("nodeId", id(event.nodeId()));
        map.put("workflow", event.workflow() == null ? null : event.workflow().toString());
        map.put("message", event.message());
        map.put("payload", event.payload());
        map.put("occurredAt", event.occurredAt().toString());
        return map;
    }

    public static Map<String, Object> task(Task task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", task.id().value());
        map.put("title", task.title());
        map.put("userQuery", task.userQuery());
        map.put("company", task.company());
        map.put("intent", task.intent());
        map.put("status", task.status().name());
        map.put("createdAt", task.createdAt().toString());
        map.put("updatedAt", task.updatedAt().toString());
        map.put("metadata", task.metadata());
        List<Map<String, Object>> messages = new ArrayList<>();
        for (var message : task.messages()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", message.role().name());
            m.put("content", message.content());
            m.put("at", message.at().toString());
            m.put("linkedRunId", id(message.linkedRunId()));
            messages.add(m);
        }
        map.put("messages", messages);
        List<String> runs = new ArrayList<>();
        for (var runId : task.runIds()) {
            runs.add(runId.value());
        }
        map.put("runIds", runs);
        return map;
    }

    public static Map<String, Object> run(Run run) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", run.id().value());
        map.put("taskId", run.taskId().value());
        map.put("workflow", run.workflow().toString());
        map.put("triggeredBy", run.triggeredBy());
        map.put("status", run.status().name());
        map.put("queuedAt", run.queuedAt().toString());
        map.put("startedAt", run.startedAt() == null ? null : run.startedAt().toString());
        map.put("finishedAt", run.finishedAt() == null ? null : run.finishedAt().toString());
        map.put("errorCode", run.errorCode());
        map.put("errorSummary", run.errorSummary());
        map.put("input", run.input());
        map.put("output", run.output());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (NodeResult node : run.nodeResults()) {
            nodes.add(nodeResult(node));
        }
        map.put("nodeResults", nodes);
        return map;
    }

    public static Map<String, Object> nodeResult(NodeResult node) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodeId", node.nodeId().value());
        map.put("status", node.status().name());
        map.put("output", node.output());
        map.put("errorCode", node.errorCode());
        map.put("errorMessage", node.errorMessage());
        map.put("attempts", node.attempts());
        map.put("startedAt", node.startedAt() == null ? null : node.startedAt().toString());
        map.put("finishedAt", node.finishedAt() == null ? null : node.finishedAt().toString());
        map.put("durationMs", node.durationMs());
        return map;
    }

    public static Map<String, Object> workflowDefinition(WorkflowDefinition definition, boolean published) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", definition.ref().name());
        map.put("version", definition.ref().version());
        map.put("intent", definition.intent());
        map.put("description", definition.description());
        map.put("published", published);
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (var node : definition.nodes()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", node.id().value());
            n.put("type", node.type());
            n.put("params", node.params());
            n.put("timeoutMs", node.timeoutMs());
            n.put("maxAttempts", node.maxAttempts());
            nodes.add(n);
        }
        map.put("nodes", nodes);
        List<Map<String, Object>> edges = new ArrayList<>();
        for (var edge : definition.edges()) {
            edges.add(Map.of("from", edge.from().value(), "to", edge.to().value()));
        }
        map.put("edges", edges);
        return map;
    }

    public static Map<String, Object> candidate(WorkflowCandidate candidate) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", candidate.id());
        map.put("base", candidate.baseRef().toString());
        map.put("rule", candidate.rule().name());
        map.put("rationale", candidate.rationale());
        map.put("sourceRunIds", candidate.sourceRunIds());
        map.put("applicableConditions", candidate.applicableConditions());
        map.put("status", candidate.status().name());
        map.put("createdAt", candidate.createdAt().toString());
        map.put("decidedBy", candidate.decidedBy());
        map.put("decisionNote", candidate.decisionNote());
        map.put("decidedAt", candidate.decidedAt() == null ? null : candidate.decidedAt().toString());
        map.put("proposedDefinition", workflowDefinition(candidate.proposedDefinition(), false));
        if (candidate.evalReport() != null) {
            map.put("evalReport", evalReport(candidate.evalReport()));
        }
        return map;
    }

    public static Map<String, Object> evalReport(EvalReport report) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("candidateId", report.candidateId());
        map.put("passed", report.passed());
        map.put("baselineHoldout", metrics(report.baselineHoldout()));
        map.put("candidateHoldout", metrics(report.candidateHoldout()));
        map.put("baselineTrain", metrics(report.baselineTrain()));
        map.put("candidateTrain", metrics(report.candidateTrain()));
        List<Map<String, Object>> gates = new ArrayList<>();
        for (var gate : report.gateResults()) {
            gates.add(Map.of("rule", gate.rule(), "passed", gate.passed(), "detail", gate.detail()));
        }
        map.put("gateResults", gates);
        List<Map<String, Object>> cases = new ArrayList<>();
        for (var result : report.candidateResults()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("caseId", result.evalCase().caseId());
            c.put("split", result.evalCase().split());
            c.put("expected", result.evalCase().expected().name());
            c.put("passed", result.passed());
            c.put("outcome", result.outcome().name());
            c.put("errorCode", result.errorCode());
            c.put("detail", result.passDetail());
            c.put("toolCalls", result.toolCalls());
            c.put("durationMs", result.durationMs());
            cases.add(c);
        }
        map.put("candidateCaseResults", cases);
        return map;
    }

    public static Map<String, Object> metrics(EvalReport.Metrics metrics) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("totalCases", metrics.totalCases());
        map.put("passedCases", metrics.passedCases());
        map.put("accuracy", metrics.accuracy());
        map.put("errorDetectionRate", metrics.errorDetectionRate());
        map.put("errorDetectionCases", metrics.errorDetectionCases());
        map.put("avgDurationMs", metrics.avgDurationMs());
        map.put("avgToolCalls", metrics.avgToolCalls());
        map.put("failedCaseIds", metrics.failedCaseIds());
        return map;
    }

    public static Map<String, Object> skill(Skill skill) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", skill.id());
        map.put("name", skill.name());
        map.put("triggerIntent", skill.triggerIntent());
        map.put("triggerPattern", skill.triggerPattern());
        map.put("instruction", skill.instruction());
        map.put("sourceRunIds", skill.sourceRunIds());
        map.put("status", skill.status().name());
        map.put("note", skill.note());
        map.put("createdAt", skill.createdAt().toString());
        return map;
    }

    public static Map<String, Object> experience(ExperienceRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("runId", id(record.runId()));
        map.put("taskId", id(record.taskId()));
        map.put("workflow", record.workflow() == null ? null : record.workflow().toString());
        map.put("triggeredBy", record.triggeredBy());
        map.put("outcome", record.outcome().name());
        map.put("errorCode", record.errorCode());
        map.put("errorSummary", record.errorSummary());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (var node : record.nodes()) {
            nodes.add(Map.of("nodeId", node.nodeId(), "status", node.status().name(),
                    "errorCode", String.valueOf(node.errorCode()), "durationMs", node.durationMs()));
        }
        map.put("nodes", nodes);
        List<Map<String, Object>> annotations = new ArrayList<>();
        for (var annotation : record.annotations()) {
            annotations.add(Map.of("kind", annotation.kind(), "nodeId", String.valueOf(annotation.nodeId()),
                    "detail", annotation.detail()));
        }
        map.put("annotations", annotations);
        map.put("createdAt", record.createdAt().toString());
        return map;
    }

    public static Map<String, Object> session(Session session) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", session.id().value());
        map.put("createdAt", session.createdAt().toString());
        List<String> tasks = new ArrayList<>();
        for (var taskId : session.taskIds()) {
            tasks.add(taskId.value());
        }
        map.put("taskIds", tasks);
        List<Map<String, Object>> messages = new ArrayList<>();
        for (var message : session.messages()) {
            messages.add(Map.of("role", message.role().name(), "content", message.content(),
                    "at", message.at().toString()));
        }
        map.put("messages", messages);
        return map;
    }

    /** 版本差异：节点集合与边集合的对称差异。 */
    public static Map<String, Object> workflowDiff(WorkflowDefinition from, WorkflowDefinition to) {
        Map<String, Object> diff = new LinkedHashMap<>();
        List<String> addedNodes = new ArrayList<>();
        List<String> removedNodes = new ArrayList<>();
        List<String> unchangedNodes = new ArrayList<>();
        Map<String, WorkflowDefinition> dummy = Map.of();
        var toNodes = to.nodeMap();
        var fromNodes = from.nodeMap();
        for (NodeId id : toNodes.keySet()) {
            (fromNodes.containsKey(id) ? unchangedNodes : addedNodes).add(id.value());
        }
        for (NodeId id : fromNodes.keySet()) {
            if (!toNodes.containsKey(id)) {
                removedNodes.add(id.value());
            }
        }
        diff.put("addedNodes", addedNodes);
        diff.put("removedNodes", removedNodes);
        diff.put("unchangedNodes", unchangedNodes);
        List<Map<String, Object>> addedEdges = new ArrayList<>();
        var fromEdges = new java.util.HashSet<>(from.edges());
        var toEdges = new java.util.HashSet<>(to.edges());
        for (var edge : toEdges) {
            if (!fromEdges.contains(edge)) {
                addedEdges.add(Map.of("from", edge.from().value(), "to", edge.to().value()));
            }
        }
        List<Map<String, Object>> removedEdges = new ArrayList<>();
        for (var edge : fromEdges) {
            if (!toEdges.contains(edge)) {
                removedEdges.add(Map.of("from", edge.from().value(), "to", edge.to().value()));
            }
        }
        diff.put("addedEdges", addedEdges);
        diff.put("removedEdges", removedEdges);
        diff.put("from", from.ref().toString());
        diff.put("to", to.ref().toString());
        return diff;
    }

    public static Map<String, Object> publication(WorkflowRegistry.Publication publication) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("workflowName", publication.workflowName());
        map.put("version", publication.version());
        map.put("candidateId", publication.candidateId());
        map.put("publishedBy", publication.publishedBy());
        map.put("at", publication.at().toString());
        map.put("rollback", publication.rollback());
        return map;
    }

    private static String id(com.chinazhouwy.miniharness.runtime.core.TaskId id) {
        return id == null ? null : id.value();
    }

    private static String id(com.chinazhouwy.miniharness.runtime.core.RunId id) {
        return id == null ? null : id.value();
    }

    private static String id(NodeId id) {
        return id == null ? null : id.value();
    }
}
