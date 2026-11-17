package com.chinazhouwy.miniharness.runtime;

import com.chinazhouwy.miniharness.runtime.core.RunBudget;
import com.chinazhouwy.miniharness.runtime.core.RunId;
import com.chinazhouwy.miniharness.runtime.core.TaskId;
import com.chinazhouwy.miniharness.runtime.domain.finance.FinancePack;
import com.chinazhouwy.miniharness.runtime.domain.finance.FinancePackInstaller;
import com.chinazhouwy.miniharness.runtime.event.EventBus;
import com.chinazhouwy.miniharness.runtime.event.EventType;
import com.chinazhouwy.miniharness.runtime.model.ModelPort;
import com.chinazhouwy.miniharness.runtime.model.NoopModelPort;
import com.chinazhouwy.miniharness.runtime.model.ScriptedModelPort;
import com.chinazhouwy.miniharness.runtime.strategy.DeterministicWorkflowStrategy;
import com.chinazhouwy.miniharness.runtime.strategy.ExecutionStrategy;
import com.chinazhouwy.miniharness.runtime.strategy.RoutedWorkflowStrategy;
import com.chinazhouwy.miniharness.runtime.strategy.SingleAgentStrategy;
import com.chinazhouwy.miniharness.runtime.tool.ToolInvoker;
import com.chinazhouwy.miniharness.runtime.workflow.NodeContext;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowCompiler;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行策略对比实验（离线、可重复）：同一真实任务（盈利能力研究，case-clean）分别以
 * A 单Agent（每节点咨询模型）、B 规则路由（仅 needsModel 节点咨询）、确定性基线执行。
 * 对比模型调用次数、提示词成本（字符）、工具调用与耗时；输出正确性以确定性计算为准。
 *
 * 诚实边界：脚本化模型无网络延迟，耗时差异在离线模式不显著；
 * 输出质量在离线下三策略必然一致（确定性计算未被模型触碰）——这正是 Harness 管控的价值。
 */
class StrategyComparisonTest {

    private final ExecutorService nodePool = Executors.newCachedThreadPool();

    /** 排除墙钟字段（generatedAt），保留全部业务字段用于策略间输出一致性比较。 */
    private static Map<String, Object> withoutTimestamp(Map<String, Object> output) {
        Map<String, Object> filtered = new java.util.LinkedHashMap<>(output);
        filtered.remove("generatedAt");
        return filtered;
    }

    /** 构造真实金融任务的策略请求：装好 Domain Pack，脚本化模型按参数注入。 */
    private ExecutionStrategy.StrategyRequest request(ModelPort modelPort, RunBudget budget,
                                                      EventBus.Collector collector) {
        HarnessEngine engine = new HarnessEngine(HarnessEngine.EngineConfig.offline(),
                com.chinazhouwy.miniharness.runtime.evolution.AnalyzerPolicy.financeDefaults(),
                com.chinazhouwy.miniharness.runtime.evolution.EvalGatePolicy.defaults());
        FinancePackInstaller.install(engine, "SEED");
        engine.events().subscribe(collector);
        WorkflowDefinition v1 = FinancePack.workflowV1(1);
        NodeContext context = new NodeContext(engine.events(), TaskId.random(), RunId.random(),
                engine.tools(), new ToolInvoker(engine.tools(), engine.events(), nodePool),
                modelPort, budget, () -> false, nodePool, new java.util.HashMap<>());
        return new ExecutionStrategy.StrategyRequest(
                new WorkflowCompiler(engine.nodeRegistry()), engine.nodeRegistry(), v1,
                Map.of("caseId", "case-clean"), context);
    }

    @Test
    @DisplayName("A/B/基线对比：同一任务下 B 组 0 次模型调用，A 组每节点 1 次，输出一致且正确")
    void comparesStrategiesOnRealFinanceTask() {
        String scriptedReview = "复核完成：输入口径与节点安排无明显异常。";
        // ---- 确定性基线 ----
        EventBus.Collector baselineCollector = new EventBus.Collector();
        ExecutionStrategy.StrategyResult baseline = new DeterministicWorkflowStrategy().execute(
                request(new NoopModelPort(), new RunBudget(20, 50, 60_000), baselineCollector));

        // ---- B 组：规则路由 ----
        EventBus.Collector routedCollector = new EventBus.Collector();
        ExecutionStrategy.StrategyResult routed = new RoutedWorkflowStrategy().execute(
                request(new ScriptedModelPort(List.of(), scriptedReview),
                        new RunBudget(20, 50, 60_000), routedCollector));

        // ---- A 组：单 Agent（每节点咨询） ----
        EventBus.Collector singleCollector = new EventBus.Collector();
        ExecutionStrategy.StrategyResult single = new SingleAgentStrategy().execute(
                request(new ScriptedModelPort(List.of(), scriptedReview),
                        new RunBudget(20, 50, 60_000), singleCollector));

        // 调用次数：B 组 0（金融节点全部确定性），A 组 = 节点数（3），基线 0。
        assertEquals(0, baseline.modelCalls());
        assertEquals(0, routed.modelCalls(), "规则路由对确定性金融任务不应调用模型");
        assertEquals(3, single.modelCalls(), "单Agent模式每个节点咨询 1 次模型");
        // 提示词成本只在 A 组产生。
        assertTrue(single.promptChars() > 0);
        assertEquals(0, routed.promptChars());
        // 工具调用一致（数据加载 1 次）。
        assertEquals(1, routed.toolCalls());
        assertEquals(1, single.toolCalls());
        // 输出完全一致且正确（模型未触碰确定性计算）；generatedAt 是墙钟字段，比较时排除。
        assertEquals(0.11, (Double) routed.output().get("netMarginLatest"), 1e-9);
        assertEquals(withoutTimestamp(routed.output()), withoutTimestamp(single.output()), "A/B 输出必须一致");
        assertEquals(withoutTimestamp(routed.output()), withoutTimestamp(baseline.output()));
        // 事件审计：A 组 3 次 MODEL_CALLED，B 组 0 次。
        assertEquals(3, singleCollector.all().stream().filter(e -> e.type() == EventType.MODEL_CALLED).count());
        assertEquals(0, routedCollector.all().stream().filter(e -> e.type() == EventType.MODEL_CALLED).count());

        // 对比表（真实测量值，写入测试日志）。
        System.out.println("=== ExecutionStrategy 对比（任务: 盈利能力研究 case-clean, 离线脚本化模型） ===");
        System.out.printf("%-24s %-10s %-12s %-10s %-10s%n", "策略", "模型调用", "提示词字符", "工具调用", "耗时ms");
        System.out.printf("%-24s %-10d %-12d %-10d %-10d%n", baseline.strategy(), baseline.modelCalls(),
                baseline.promptChars(), baseline.toolCalls(), baseline.durationMs());
        System.out.printf("%-24s %-10d %-12d %-10d %-10d%n", routed.strategy(), routed.modelCalls(),
                routed.promptChars(), routed.toolCalls(), routed.durationMs());
        System.out.printf("%-24s %-10d %-12d %-10d %-10d%n", single.strategy(), single.modelCalls(),
                single.promptChars(), single.toolCalls(), single.durationMs());
        // 脚本化模型无网络延迟，耗时差在离线模式不显著——只验证记录存在。
        assertTrue(routed.durationMs() >= 0);
        assertTrue(single.durationMs() >= 0);
    }

    @Test
    @DisplayName("无模型环境：规则路由照常完成，单Agent 显式失败（Harness 管控，绝不静默假装成功）")
    void routedSurvivesWithoutModelButSingleAgentFails() {
        EventBus.Collector routedCollector = new EventBus.Collector();
        ExecutionStrategy.StrategyResult routed = new RoutedWorkflowStrategy().execute(
                request(new NoopModelPort(), new RunBudget(20, 50, 60_000), routedCollector));
        assertEquals(0.11, (Double) routed.output().get("netMarginLatest"), 1e-9,
                "规则路由不需要模型，离线环境正常完成任务");

        EventBus.Collector singleCollector = new EventBus.Collector();
        ExecutionStrategy.StrategyResult single = new SingleAgentStrategy().execute(
                request(new NoopModelPort(), new RunBudget(20, 50, 60_000), singleCollector));
        assertTrue(single.output().isEmpty(), "单Agent在无模型环境所有节点失败，输出为空");
        assertNotEquals(0.11, single.output().get("netMarginLatest"));
    }
}
