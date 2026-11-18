package com.chinazhouwy.miniharness.runtime.capability;

import com.chinazhouwy.miniharness.runtime.core.TaskId;
import com.chinazhouwy.miniharness.runtime.core.TaskStatus;
import com.chinazhouwy.miniharness.runtime.skill.Skill;
import com.chinazhouwy.miniharness.runtime.skill.SkillStore;
import com.chinazhouwy.miniharness.runtime.task.Task;
import com.chinazhouwy.miniharness.runtime.task.TaskStore;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowDefinition;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 能力路由器：按固定优先级依次决策（任务书第二节“Capability Engine”的实现）。
 *
 * 【五级优先级——为什么是这个顺序】
 *  1. 规则意图抽取（RuleIntentExtractor，纯正则，0 模型调用）
 *     —— 能用确定性规则解决的事绝不麻烦模型；
 *  2. 质疑消息 → 活跃任务关联（resolveChallenge）
 *     —— “这个利润率不对”必须落到具体的任务/Run 上核查；
 *     候选 0 个或 >1 个都请求用户确认，绝不擅自合并历史；
 *  3. 已发布 Workflow 匹配（resolveResearch 按 definition.intent）
 *     —— 有固化流程就复用流程；
 *  4. Skill 匹配（复用“怎么做的知识”，但暂不参与执行）；
 *  5. 兜底 AGENT_EXPLORE —— 都不匹配才轮到开放探索（无模型部署时明确告知无法处理）。
 *
 * 【DecisionModel 的位置】
 * 仅在第 2 级出现多候选歧义时被询问（ModelBasedDecisionModel 走 ModelPort）。
 * 它的置信度未校准，只作参考；模型答不上来/答错编号都回退到“请求用户确认”。
 * 路由的最终决定权永远在 Harness，不在模型。
 */
public class IntentRouter {

    private final TaskStore taskStore;
    private final WorkflowRegistry workflowRegistry;
    private final SkillStore skillStore;
    private final RuleIntentExtractor extractor = new RuleIntentExtractor();
    private final DecisionModel decisionModel; // 可为 null：纯规则模式

    public IntentRouter(TaskStore taskStore, WorkflowRegistry workflowRegistry, SkillStore skillStore) {
        this(taskStore, workflowRegistry, skillStore, null);
    }

    public IntentRouter(TaskStore taskStore, WorkflowRegistry workflowRegistry,
                        SkillStore skillStore, DecisionModel decisionModel) {
        this.taskStore = taskStore;
        this.workflowRegistry = workflowRegistry;
        this.skillStore = skillStore;
        this.decisionModel = decisionModel;
    }

    public RoutingDecision resolve(String userInput) {
        Optional<RuleIntentExtractor.ExtractedIntent> extracted = extractor.extract(userInput);
        if (extracted.isEmpty()) {
            return RoutingDecision.unmatched("未匹配到已知意图，进入自主探索（当前无模型部署时无法执行）");
        }
        RuleIntentExtractor.ExtractedIntent intent = extracted.get();

        if (RuleIntentExtractor.INTENT_CHALLENGE_RESULT.equals(intent.intent())) {
            return resolveChallenge(userInput, intent);
        }
        return resolveResearch(intent);
    }

    /** 质疑类消息：尝试关联带执行记录的活跃任务；0 个或多个候选都请求用户确认。 */
    private RoutingDecision resolveChallenge(String userInput, RuleIntentExtractor.ExtractedIntent intent) {
        List<Task> candidates = new ArrayList<>();
        for (Task task : taskStore.findAll()) {
            if (task.status().isTerminal()) {
                continue;
            }
            if (task.runIds().isEmpty()) {
                continue;
            }
            if (intent.company() != null && !intent.company().isBlank()
                    && task.company() != null && !task.company().equals(intent.company())) {
                continue;
            }
            candidates.add(task);
        }
        if (candidates.size() == 1) {
            Task target = candidates.get(0);
            return new RoutingDecision(RoutingAction.CONTINUE_TASK, intent.intent(), intent.company(),
                    target.id(), null, null, false, null,
                    "唯一活跃任务可关联: " + target.title(), "active-task-resolver", List.of());
        }
        if (candidates.isEmpty()) {
            return new RoutingDecision(RoutingAction.CLARIFY_TASK_LINK, intent.intent(), intent.company(),
                    null, null, null, true,
                    "没有找到可核查的活跃任务，请指出任务编号或公司名称",
                    "质疑消息未能关联任何活跃任务", "active-task-resolver", List.of());
        }
        // 多候选：先试可插拔决策模型（未校准，仅参考），失败则请求用户确认。
        if (decisionModel != null) {
            List<String> labels = candidates.stream().map(t -> t.id().value() + " " + t.title()).toList();
            Optional<DecisionModel.SemanticJudgment> judgment = decisionModel.judge(
                    new DecisionModel.SemanticQuery("判断用户质疑最可能指向哪个任务", userInput, labels));
            if (judgment.isPresent()) {
                String label = judgment.get().label();
                for (Task task : candidates) {
                    if (label.startsWith(task.id().value())) {
                        return new RoutingDecision(RoutingAction.CONTINUE_TASK, intent.intent(), intent.company(),
                                task.id(), null, null, false, null,
                                "轻量决策模型辅助选择（置信度未校准，仅作参考）: " + judgment.get().explanation(),
                                "decision-model-resolver", List.of());
                    }
                }
            }
        }
        List<TaskId> ids = candidates.stream().map(Task::id).toList();
        return new RoutingDecision(RoutingAction.CLARIFY_TASK_LINK, intent.intent(), intent.company(),
                null, null, null, true,
                "存在 " + candidates.size() + " 个可能相关的任务，请确认要核查哪一个",
                "任务关联存在歧义，不自动合并", "active-task-resolver", ids);
    }

    /** 研究类消息：优先匹配已发布 Workflow，其次 Skill，最后兜底。 */
    private RoutingDecision resolveResearch(RuleIntentExtractor.ExtractedIntent intent) {
        for (WorkflowDefinition definition : publishedDefinitions()) {
            if (intent.intent().equals(definition.intent())) {
                return new RoutingDecision(RoutingAction.NEW_TASK_WITH_WORKFLOW, intent.intent(), intent.company(),
                        null, definition.ref(), null, false, null,
                        "匹配到已发布 Workflow " + definition.ref(), "workflow-resolver", List.of());
            }
        }
        for (Skill skill : skillStore.all()) {
            if (Skill.SkillStatus.ACTIVE.equals(skill.status())
                    && intent.intent().equals(skill.triggerIntent())) {
                return new RoutingDecision(RoutingAction.NEW_TASK_WITH_SKILL, intent.intent(), intent.company(),
                        null, null, skill.id(), false, null,
                        "匹配到 Skill " + skill.name(), "skill-resolver", List.of());
            }
        }
        return RoutingDecision.unmatched(
                "意图 " + intent.intent() + " 没有已发布 Workflow 或 Skill，进入自主探索");
    }

    private List<WorkflowDefinition> publishedDefinitions() {
        List<WorkflowDefinition> result = new ArrayList<>();
        for (String name : publishedNames()) {
            workflowRegistry.published(name).ifPresent(result::add);
        }
        return result;
    }

    private List<String> publishedNames() {
        return workflowRegistry.knownWorkflowNames();
    }
}
