package com.chinazhouwy.miniharness.runtime;

import com.chinazhouwy.miniharness.runtime.capability.DecisionModel;
import com.chinazhouwy.miniharness.runtime.capability.IntentRouter;
import com.chinazhouwy.miniharness.runtime.capability.ModelBasedDecisionModel;
import com.chinazhouwy.miniharness.runtime.capability.RoutingAction;
import com.chinazhouwy.miniharness.runtime.capability.RoutingDecision;
import com.chinazhouwy.miniharness.runtime.capability.RuleIntentExtractor;
import com.chinazhouwy.miniharness.runtime.core.TaskId;
import com.chinazhouwy.miniharness.runtime.core.TaskStatus;
import com.chinazhouwy.miniharness.runtime.core.WorkflowRef;
import com.chinazhouwy.miniharness.runtime.model.ModelRequest;
import com.chinazhouwy.miniharness.runtime.model.NoopModelPort;
import com.chinazhouwy.miniharness.runtime.model.ScriptedModelPort;
import com.chinazhouwy.miniharness.runtime.skill.Skill;
import com.chinazhouwy.miniharness.runtime.skill.SkillStore;
import com.chinazhouwy.miniharness.runtime.task.InMemoryTaskStore;
import com.chinazhouwy.miniharness.runtime.task.Run;
import com.chinazhouwy.miniharness.runtime.task.Task;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowDefinition;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 能力路由器：规则优先、任务关联、歧义确认、兜底与可插拔决策模型。 */
class IntentRouterTest {

    private final InMemoryTaskStore taskStore = new InMemoryTaskStore();
    private final WorkflowRegistry workflowRegistry = new WorkflowRegistry(new com.chinazhouwy.miniharness.runtime.event.EventBus());
    private final SkillStore skillStore = new SkillStore();

    private void seedPublishedResearchWorkflow() {
        WorkflowDefinition definition = new WorkflowDefinition(
                new WorkflowRef("profitability-research", 1),
                RuleIntentExtractor.INTENT_PROFITABILITY_RESEARCH,
                "盈利能力研究",
                List.of(), List.of());
        workflowRegistry.install(definition);
        workflowRegistry.publish("profitability-research", 1, "SEED", null);
    }

    private Task activeResearchTask(String company) {
        Task task = new Task(TaskId.random(), "研究" + company + "的盈利能力",
                com.chinazhouwy.miniharness.runtime.core.SessionId.random(), Instant.now());
        task.attachIntent(RuleIntentExtractor.INTENT_PROFITABILITY_RESEARCH, company);
        task.transitionTo(TaskStatus.ACTIVE);
        task.linkRun(com.chinazhouwy.miniharness.runtime.core.RunId.random());
        taskStore.save(task);
        return task;
    }

    @Test
    @DisplayName("规则抽取：研究意图与质疑意图均可识别")
    void extractsIntents() {
        RuleIntentExtractor extractor = new RuleIntentExtractor();
        var research = extractor.extract("研究贵州茅台的盈利能力，分析利润率变化");
        assertTrue(research.isPresent());
        assertEquals(RuleIntentExtractor.INTENT_PROFITABILITY_RESEARCH, research.get().intent());
        assertEquals("贵州茅台", research.get().company());

        var challenge = extractor.extract("这个利润率好像不对");
        assertTrue(challenge.isPresent());
        assertEquals(RuleIntentExtractor.INTENT_CHALLENGE_RESULT, challenge.get().intent());

        assertTrue(extractor.extract("今天天气怎么样").isEmpty());
    }

    @Test
    @DisplayName("研究输入命中已发布 Workflow → 新建任务路由")
    void routesResearchToPublishedWorkflow() {
        seedPublishedResearchWorkflow();
        IntentRouter router = new IntentRouter(taskStore, workflowRegistry, skillStore);
        RoutingDecision decision = router.resolve("研究宁德时代的盈利能力");
        assertEquals(RoutingAction.NEW_TASK_WITH_WORKFLOW, decision.action());
        assertEquals("profitability-research@v1", decision.workflow().toString());
        assertEquals("宁德时代", decision.company());
    }

    @Test
    @DisplayName("无已发布 Workflow 时研究输入 → 兜底探索")
    void routesResearchToFallbackWhenNothingPublished() {
        IntentRouter router = new IntentRouter(taskStore, workflowRegistry, skillStore);
        RoutingDecision decision = router.resolve("研究比亚迪的盈利能力");
        assertEquals(RoutingAction.AGENT_EXPLORE, decision.action());
    }

    @Test
    @DisplayName("质疑消息唯一活跃任务 → 直接关联，不建新任务")
    void challengeLinksToSingleActiveTask() {
        Task task = activeResearchTask("贵州茅台");
        IntentRouter router = new IntentRouter(taskStore, workflowRegistry, skillStore);
        RoutingDecision decision = router.resolve("这个利润率好像不对");
        assertEquals(RoutingAction.CONTINUE_TASK, decision.action());
        assertEquals(task.id(), decision.taskId());
        assertFalse(decision.needsUserConfirmation());
    }

    @Test
    @DisplayName("质疑消息多个活跃任务 → 请求确认，绝不擅自合并")
    void challengeWithAmbiguityAsksUser() {
        activeResearchTask("贵州茅台");
        activeResearchTask("宁德时代");
        IntentRouter router = new IntentRouter(taskStore, workflowRegistry, skillStore);
        RoutingDecision decision = router.resolve("这个利润率好像不对");
        assertEquals(RoutingAction.CLARIFY_TASK_LINK, decision.action());
        assertTrue(decision.needsUserConfirmation());
        assertEquals(2, decision.clarificationCandidates().size());
    }

    @Test
    @DisplayName("质疑消息无活跃任务 → 请求用户补充")
    void challengeWithoutTaskAsksUser() {
        IntentRouter router = new IntentRouter(taskStore, workflowRegistry, skillStore);
        RoutingDecision decision = router.resolve("这个利润率好像不对");
        assertEquals(RoutingAction.CLARIFY_TASK_LINK, decision.action());
        assertTrue(decision.question().contains("没有找到"));
    }

    @Test
    @DisplayName("歧义时可插拔决策模型辅助选择（无模型则仍请求确认）")
    void decisionModelDisambiguates() {
        Task moutai = activeResearchTask("贵州茅台");
        activeResearchTask("宁德时代");
        // 脚本化模型：返回第一个候选编号（候选标签以 taskId 开头）。
        ScriptedModelPort port = new ScriptedModelPort(List.of("1"), "0");
        DecisionModel model = new ModelBasedDecisionModel(port);
        IntentRouter router = new IntentRouter(taskStore, workflowRegistry, skillStore, model);
        RoutingDecision decision = router.resolve("这个利润率好像不对");
        assertEquals(RoutingAction.CONTINUE_TASK, decision.action());
        assertEquals(moutai.id(), decision.taskId());
        assertTrue(decision.reason().contains("未校准"));
        assertEquals(1, port.callCount());

        // 无模型时同一输入保持「请求确认」。
        IntentRouter plainRouter = new IntentRouter(taskStore, workflowRegistry, skillStore);
        assertEquals(RoutingAction.CLARIFY_TASK_LINK, plainRouter.resolve("这个利润率好像不对").action());
    }

    @Test
    @DisplayName("决策模型拒绝回答或输出非法编号时回退到用户确认")
    void decisionModelFailureFallsBack() {
        activeResearchTask("贵州茅台");
        activeResearchTask("宁德时代");
        // 模型返回 0（无法判断）→ 回退请求确认。
        ScriptedModelPort port = new ScriptedModelPort(List.of("0"), "0");
        IntentRouter router = new IntentRouter(taskStore, workflowRegistry, skillStore,
                new ModelBasedDecisionModel(port));
        RoutingDecision decision = router.resolve("这个利润率好像不对");
        assertEquals(RoutingAction.CLARIFY_TASK_LINK, decision.action());

        // NoopModelPort（无模型部署）→ 模型判定直接 empty，同样回退。
        IntentRouter noopRouter = new IntentRouter(taskStore, workflowRegistry, skillStore,
                new ModelBasedDecisionModel(new NoopModelPort()));
        assertEquals(RoutingAction.CLARIFY_TASK_LINK, noopRouter.resolve("这个利润率好像不对").action());
    }

    @Test
    @DisplayName("Skill 命中：无 Workflow 但存在 ACTIVE Skill → 按 Skill 路由")
    void routesToSkillWhenNoWorkflow() {
        Skill skill = new Skill("skill-profit", "盈利研究技能",
                RuleIntentExtractor.INTENT_PROFITABILITY_RESEARCH, null,
                "按固定清单核查数据口径后计算利润率", List.of("run-1"), Instant.now());
        skillStore.save(skill);
        IntentRouter router = new IntentRouter(taskStore, workflowRegistry, skillStore);
        RoutingDecision decision = router.resolve("研究五粮液的盈利能力");
        assertEquals(RoutingAction.NEW_TASK_WITH_SKILL, decision.action());
        assertEquals("skill-profit", decision.skillId());
    }

    @Test
    @DisplayName("Run 记录可关联质疑消息（任务-运行-消息链路完整）")
    void taskMessageCarriesLinkedRun() {
        Task task = activeResearchTask("贵州茅台");
        Run run = new Run(com.chinazhouwy.miniharness.runtime.core.RunId.random(), task.id(),
                new WorkflowRef("profitability-research", 1), "USER", Instant.now(), java.util.Map.of());
        task.addMessage(com.chinazhouwy.miniharness.runtime.task.TaskMessage.user("利润率不对", run.id()));
        assertEquals(run.id(), task.messages().get(task.messages().size() - 1).linkedRunId());
        Optional.of(run);
    }
}
