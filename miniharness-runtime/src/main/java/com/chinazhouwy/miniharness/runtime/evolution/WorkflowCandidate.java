package com.chinazhouwy.miniharness.runtime.evolution;

import com.chinazhouwy.miniharness.runtime.core.WorkflowRef;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowDefinition;

import java.time.Instant;
import java.util.List;

/**
 * Workflow 候选：由轨迹分析产生的新版本提案。
 * 候选只是提案——它不能修改任何已发布流程；发布必须经评测 + 人工批准。
 * proposedDefinition 的版本号在创建时为占位值，批准发布时由账本分配真实版本号。
 */
public class WorkflowCandidate {

    public enum CandidateStatus { PENDING_EVAL, EVALUATED_PASS, EVALUATED_FAIL, APPROVED, REJECTED }

    public enum ProposalRule {
        /** 在数据计算节点前插入「口径验证」节点。 */
        INSERT_VALIDATION_NODE,
        /** 删除长期失败且下游无消费的冗余节点。 */
        REMOVE_REDUNDANT_NODE,
        /** 其它（人工构造的对照候选，例如过激规则）。 */
        MANUAL
    }

    private final String id;
    private final WorkflowRef baseRef;
    private final WorkflowDefinition proposedDefinition;
    private final ProposalRule rule;
    private final String rationale;
    private final List<String> sourceRunIds;
    private final List<String> applicableConditions;
    private final Instant createdAt;
    private CandidateStatus status;
    private EvalReport evalReport;
    private String decidedBy;
    private String decisionNote = "";
    private Instant decidedAt;

    public WorkflowCandidate(String id, WorkflowRef baseRef, WorkflowDefinition proposedDefinition,
                             ProposalRule rule, String rationale, List<String> sourceRunIds,
                             List<String> applicableConditions, Instant createdAt) {
        this.id = id;
        this.baseRef = baseRef;
        this.proposedDefinition = proposedDefinition;
        this.rule = rule;
        this.rationale = rationale;
        this.sourceRunIds = List.copyOf(sourceRunIds);
        this.applicableConditions = List.copyOf(applicableConditions);
        this.createdAt = createdAt;
        this.status = CandidateStatus.PENDING_EVAL;
    }

    public synchronized void recordEvaluation(EvalReport report) {
        if (status != CandidateStatus.PENDING_EVAL) {
            throw new IllegalStateException("候选 " + id + " 当前状态 " + status + " 不允许记录评测");
        }
        this.evalReport = report;
        this.status = report.passed() ? CandidateStatus.EVALUATED_PASS : CandidateStatus.EVALUATED_FAIL;
    }

    public synchronized void approve(String by, String note) {
        if (status != CandidateStatus.EVALUATED_PASS) {
            throw new IllegalStateException("候选 " + id + " 未通过评测（当前 " + status + "），不允许批准");
        }
        this.status = CandidateStatus.APPROVED;
        this.decidedBy = by;
        this.decisionNote = note == null ? "" : note;
        this.decidedAt = Instant.now();
    }

    public synchronized void reject(String by, String note) {
        if (status == CandidateStatus.APPROVED) {
            throw new IllegalStateException("候选 " + id + " 已批准，不可拒绝；如需撤回请使用回滚");
        }
        if (status == CandidateStatus.REJECTED) {
            throw new IllegalStateException("候选 " + id + " 已是拒绝状态");
        }
        this.status = CandidateStatus.REJECTED;
        this.decidedBy = by;
        this.decisionNote = note == null ? "" : note;
        this.decidedAt = Instant.now();
    }

    public String id() {
        return id;
    }

    public WorkflowRef baseRef() {
        return baseRef;
    }

    public WorkflowDefinition proposedDefinition() {
        return proposedDefinition;
    }

    public ProposalRule rule() {
        return rule;
    }

    public String rationale() {
        return rationale;
    }

    public List<String> sourceRunIds() {
        return sourceRunIds;
    }

    public List<String> applicableConditions() {
        return applicableConditions;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public synchronized CandidateStatus status() {
        return status;
    }

    public synchronized EvalReport evalReport() {
        return evalReport;
    }

    public synchronized String decidedBy() {
        return decidedBy;
    }

    public synchronized String decisionNote() {
        return decisionNote;
    }

    public synchronized Instant decidedAt() {
        return decidedAt;
    }
}
