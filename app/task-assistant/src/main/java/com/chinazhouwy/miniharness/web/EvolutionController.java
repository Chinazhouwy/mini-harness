package com.chinazhouwy.miniharness.web;

import com.chinazhouwy.miniharness.runtime.HarnessEngine;
import com.chinazhouwy.miniharness.runtime.domain.finance.FinanceEvolutionExperiment;
import com.chinazhouwy.miniharness.runtime.domain.finance.FinancePack;
import com.chinazhouwy.miniharness.runtime.evolution.EvalReport;
import com.chinazhouwy.miniharness.runtime.evolution.ExperienceRecord;
import com.chinazhouwy.miniharness.runtime.evolution.TraceAnalyzer;
import com.chinazhouwy.miniharness.runtime.evolution.WorkflowCandidate;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowDefinition;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Evolution Review 后端：实验准备、候选列表/详情、评测、批准、拒绝、经验列表。 */
@RestController
@RequestMapping("/api/evolution")
public class EvolutionController {

    private final HarnessEngine engine;

    public EvolutionController(HarnessEngine engine) {
        this.engine = engine;
    }

    /** 一键准备实验（可重复执行）：v1 受控测试 → 轨迹分析 → v2 候选 + 过激对照候选 → 对比评测。 */
    @PostMapping("/experiment/prepare")
    public ResponseEntity<Map<String, Object>> prepareExperiment() {
        FinanceEvolutionExperiment.ExperimentSummary summary = FinanceEvolutionExperiment.prepare(engine);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("candidateId", summary.candidateId());
        response.put("aggressiveCandidateId", summary.aggressiveCandidateId());
        response.put("candidatePassedGate", summary.candidatePassedGate());
        response.put("aggressiveFailedGate", summary.aggressiveFailedGate());
        response.put("analysis", Map.of(
                "notes", summary.analysis().notes(),
                "workflowCandidates", summary.analysis().workflowCandidates().size(),
                "skillProposals", summary.analysis().skillProposals().size()));
        response.put("candidateReport", ApiMappers.evalReport(summary.candidateReport()));
        response.put("aggressiveReport", ApiMappers.evalReport(summary.aggressiveReport()));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/candidates")
    public List<Map<String, Object>> candidates() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkflowCandidate candidate : engine.candidates().all()) {
            result.add(ApiMappers.candidate(candidate));
        }
        return result;
    }

    @GetMapping("/candidates/{id}")
    public ResponseEntity<Map<String, Object>> candidateDetail(@PathVariable String id) {
        WorkflowCandidate candidate = engine.candidates().find(id)
                .orElseThrow(() -> new com.chinazhouwy.miniharness.runtime.core.HarnessException(
                        "CANDIDATE_NOT_FOUND", "候选不存在: " + id));
        Map<String, Object> detail = ApiMappers.candidate(candidate);
        // 附加与当前发布版本的差异。
        var published = engine.workflows().published(candidate.baseRef().name());
        published.ifPresent(current -> detail.put("diffAgainstPublished",
                ApiMappers.workflowDiff(current, candidate.proposedDefinition())));
        return ResponseEntity.ok(detail);
    }

    public record EvaluateRequest(String caseSet) {
    }

    /** 对候选执行对比评测（案例集固定为金融 Domain Pack 受控集）。 */
    @PostMapping("/candidates/{id}/evaluate")
    public ResponseEntity<Map<String, Object>> evaluate(@PathVariable String id,
                                                        @RequestBody(required = false) EvaluateRequest request) {
        EvalReport report = engine.evaluateCandidate(id, FinancePack.evalCases());
        return ResponseEntity.ok(ApiMappers.evalReport(report));
    }

    public record DecideRequest(String approver, String note) {
    }

    @PostMapping("/candidates/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable String id,
                                                       @RequestBody DecideRequest request) {
        String approver = approver(request);
        WorkflowDefinition published = engine.approveCandidate(id, approver,
                request.note() == null ? "" : request.note());
        return ResponseEntity.ok(Map.of(
                "approved", true,
                "workflow", published.ref().name(),
                "publishedVersion", published.ref().version(),
                "approver", approver));
    }

    @PostMapping("/candidates/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable String id,
                                                      @RequestBody DecideRequest request) {
        String approver = approver(request);
        WorkflowCandidate rejected = engine.rejectCandidate(id, approver,
                request.note() == null ? "" : request.note());
        return ResponseEntity.ok(Map.of(
                "rejected", true,
                "candidateId", rejected.id(),
                "publishedVersion", engine.workflows()
                        .publishedVersion(rejected.baseRef().name()).orElse(-1),
                "approver", approver));
    }

    @GetMapping("/experiences")
    public List<Map<String, Object>> experiences() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ExperienceRecord record : engine.experiences().all()) {
            result.add(ApiMappers.experience(record));
        }
        return result;
    }

    @GetMapping("/skills")
    public List<Map<String, Object>> skills() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var skill : engine.skills().all()) {
            result.add(ApiMappers.skill(skill));
        }
        return result;
    }

    private String approver(DecideRequest request) {
        return request == null || request.approver() == null || request.approver().isBlank()
                ? "operator" : request.approver();
    }
}
