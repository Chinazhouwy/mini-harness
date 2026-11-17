package com.chinazhouwy.miniharness.runtime.evolution;

import com.chinazhouwy.miniharness.runtime.core.HarnessException;
import com.chinazhouwy.miniharness.runtime.core.WorkflowRef;
import com.chinazhouwy.miniharness.runtime.event.EventBus;
import com.chinazhouwy.miniharness.runtime.event.EventType;
import com.chinazhouwy.miniharness.runtime.event.HarnessEvent;
import com.chinazhouwy.miniharness.runtime.skill.Skill;
import com.chinazhouwy.miniharness.runtime.skill.SkillStore;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowDefinition;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowRegistry;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 审批服务：候选从「已评测」到「已发布」的唯一通道。
 * 后端校验批准条件（必须 EVALUATED_PASS）；拒绝不影响发布指针；回滚只能回到曾发布过的版本。
 * 进化引擎的其它任何组件都无权调用 WorkflowRegistry.publish。
 */
public class ApprovalService {

    private final CandidateStore candidateStore;
    private final WorkflowRegistry workflowRegistry;
    private final SkillStore skillStore;
    private final WorkflowEvaluator evaluator;
    private final EventBus events;

    public ApprovalService(CandidateStore candidateStore, WorkflowRegistry workflowRegistry,
                           SkillStore skillStore, WorkflowEvaluator evaluator, EventBus events) {
        this.candidateStore = candidateStore;
        this.workflowRegistry = workflowRegistry;
        this.skillStore = skillStore;
        this.evaluator = evaluator;
        this.events = events;
    }

    /** 对候选执行对比评测（基线 = 候选所基于的版本）。 */
    public synchronized EvalReport evaluateCandidate(String candidateId, EvalCaseSet caseSet) {
        WorkflowCandidate candidate = require(candidateId);
        if (candidate.status() != WorkflowCandidate.CandidateStatus.PENDING_EVAL) {
            throw new HarnessException("CANDIDATE_STATE",
                    "候选 " + candidateId + " 状态为 " + candidate.status() + "，不允许重新评测");
        }
        WorkflowRef baseRef = candidate.baseRef();
        WorkflowDefinition baseline = workflowRegistry.find(baseRef.name(), baseRef.version())
                .orElseThrow(() -> new HarnessException("WORKFLOW_NOT_FOUND",
                        "候选基线版本不存在: " + baseRef));
        EvalReport report = evaluator.evaluate(baseline, candidate.proposedDefinition(), caseSet, candidateId);
        candidate.recordEvaluation(report);
        return report;
    }

    /** 批准并发布：分配新版本号、登记定义、移动发布指针。 */
    public synchronized WorkflowDefinition approve(String candidateId, String approver, String note) {
        WorkflowCandidate candidate = require(candidateId);
        if (candidate.status() != WorkflowCandidate.CandidateStatus.EVALUATED_PASS) {
            throw new HarnessException("APPROVAL_REJECTED",
                    "候选 " + candidateId + " 未通过评测（当前 " + candidate.status() + "），批准被后端校验拒绝");
        }
        String workflowName = candidate.baseRef().name();
        int newVersion = workflowRegistry.nextVersion(workflowName);
        WorkflowDefinition proposed = candidate.proposedDefinition();
        WorkflowDefinition published = new WorkflowDefinition(
                new WorkflowRef(workflowName, newVersion), proposed.intent(), proposed.description(),
                proposed.nodes(), proposed.edges());
        workflowRegistry.install(published);
        workflowRegistry.publish(workflowName, newVersion, approver, candidateId);
        candidate.approve(approver, note);
        degradeSkillsCompiledInto(workflowName, approver);
        events.publish(HarnessEvent.builder(EventType.CANDIDATE_APPROVED)
                .workflow(published.ref())
                .message("候选 " + candidateId + " 被批准并发布为 " + published.ref() + "，审批人 " + approver)
                .payload(Map.of("candidateId", candidateId, "newVersion", newVersion,
                        "approver", approver)));
        return published;
    }

    /** 拒绝候选：发布指针不动，旧版本继续服务。 */
    public synchronized WorkflowCandidate reject(String candidateId, String approver, String note) {
        WorkflowCandidate candidate = require(candidateId);
        candidate.reject(approver, note);
        events.publish(HarnessEvent.builder(EventType.CANDIDATE_REJECTED)
                .workflow(candidate.baseRef())
                .message("候选 " + candidateId + " 被拒绝，当前发布版本保持不变，审批人 " + approver)
                .payload(Map.of("candidateId", candidateId, "approver", approver,
                        "note", note == null ? "" : note)));
        return candidate;
    }

    /** 回滚：发布指针回到某个曾发布过的版本。 */
    public synchronized WorkflowDefinition rollback(String workflowName, int targetVersion, String approver) {
        WorkflowDefinition definition = workflowRegistry.rollback(workflowName, targetVersion, approver);
        return definition;
    }

    /** 已固化为 Workflow 的 Skill 自动降级，避免双重匹配（删除/合并留待后续任务）。 */
    private void degradeSkillsCompiledInto(String workflowName, String approver) {
        List<Skill> skills = skillStore.all();
        for (Skill skill : skills) {
            if (Skill.SkillStatus.ACTIVE.equals(skill.status())
                    && skill.name().contains(workflowName)) {
                skill.changeStatus(Skill.SkillStatus.COMPILED_INTO_WORKFLOW,
                        "已由 " + approver + " 固化为 Workflow " + workflowName);
                events.publish(HarnessEvent.builder(EventType.SKILL_STATUS_CHANGED)
                        .message("Skill " + skill.id() + " 降级为 COMPILED_INTO_WORKFLOW")
                        .payload(Map.of("skillId", skill.id(), "workflow", workflowName)));
            }
        }
    }

    private WorkflowCandidate require(String candidateId) {
        Optional<WorkflowCandidate> found = candidateStore.find(candidateId);
        return found.orElseThrow(() -> new HarnessException("CANDIDATE_NOT_FOUND",
                "候选不存在: " + candidateId));
    }
}
