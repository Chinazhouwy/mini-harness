package com.chinazhouwy.miniharness;

import com.chinazhouwy.miniharness.adapter.SpringAiModelPort;
import com.chinazhouwy.miniharness.model.ModelConfig;
import com.chinazhouwy.miniharness.model.ModelCreator;
import com.chinazhouwy.miniharness.runtime.HarnessEngine;
import com.chinazhouwy.miniharness.runtime.core.RunBudget;
import com.chinazhouwy.miniharness.runtime.core.RunId;
import com.chinazhouwy.miniharness.runtime.core.TaskId;
import com.chinazhouwy.miniharness.runtime.domain.finance.FinancePack;
import com.chinazhouwy.miniharness.runtime.domain.finance.FinancePackInstaller;
import com.chinazhouwy.miniharness.runtime.evolution.AnalyzerPolicy;
import com.chinazhouwy.miniharness.runtime.evolution.EvalGatePolicy;
import com.chinazhouwy.miniharness.runtime.model.ModelPort;
import com.chinazhouwy.miniharness.runtime.strategy.ExecutionStrategy;
import com.chinazhouwy.miniharness.runtime.strategy.RoutedWorkflowStrategy;
import com.chinazhouwy.miniharness.runtime.strategy.SingleAgentStrategy;
import com.chinazhouwy.miniharness.runtime.tool.ToolInvoker;
import com.chinazhouwy.miniharness.runtime.workflow.NodeContext;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowCompiler;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实模型下的执行策略对比（@Tag("live")，默认被 excludedGroups 排除，需手动运行）：
 * mvn -pl examples/task-assistant -am test -Dtest=LiveStrategyComparisonTest -DexcludedGroups= test
 * 本地 config/openrouter.local.properties 未配置或不可用时自动跳过（Assumptions）。
 */
@Tag("live")
class LiveStrategyComparisonTest {

    private final ExecutorService nodePool = Executors.newCachedThreadPool();

    @Test
    @DisplayName("真实模型 A/B 对比：调用次数与输出质量（无配置则跳过）")
    void compareWithRealModel() throws Exception {
        ModelPort modelPort = tryBuildModelPort();
        Assumptions.assumeTrue(modelPort != null,
                "未找到可用的模型配置（config/openrouter.local.properties），跳过 live 对比");

        HarnessEngine engine = new HarnessEngine(HarnessEngine.EngineConfig.offline(),
                AnalyzerPolicy.financeDefaults(), EvalGatePolicy.defaults());
        FinancePackInstaller.install(engine, "SEED");
        var def = FinancePack.workflowV1(1);

        // B 组：规则路由（0 次调用预期）
        var routed = run(engine, modelPort, new RoutedWorkflowStrategy(), def);
        // A 组：单 Agent（3 次调用预期）
        var single = run(engine, modelPort, new SingleAgentStrategy(), def);

        System.out.println("=== live ExecutionStrategy 对比 ===");
        System.out.printf("%-24s %-10s %-12s %-10s%n", "策略", "模型调用", "提示词字符", "耗时ms");
        System.out.printf("%-24s %-10d %-12d %-10d%n", routed.strategy(), routed.modelCalls(),
                routed.promptChars(), routed.durationMs());
        System.out.printf("%-24s %-10d %-12d %-10d%n", single.strategy(), single.modelCalls(),
                single.promptChars(), single.durationMs());

        assertEquals(0, routed.modelCalls(), "规则路由对确定性金融任务不应调用模型");
        assertTrue(single.modelCalls() >= 3, "单Agent每节点至少 1 次调用");
        // 真实模型也不得触碰确定性计算结果。
        assertEquals(0.11, (Double) routed.output().get("netMarginLatest"), 1e-9);
        assertEquals(routed.output().get("netMarginLatest"), single.output().get("netMarginLatest"),
                "A/B 的确定性计算结果必须一致");
    }

    private ExecutionStrategy.StrategyResult run(HarnessEngine engine, ModelPort modelPort,
                                                 ExecutionStrategy strategy,
                                                 com.chinazhouwy.miniharness.runtime.workflow.WorkflowDefinition def)
            throws Exception {
        RunBudget budget = new RunBudget(20, 50, 60_000);
        NodeContext context = new NodeContext(engine.events(), TaskId.random(), RunId.random(),
                engine.tools(), new ToolInvoker(engine.tools(), engine.events(), nodePool),
                modelPort, budget, () -> false, nodePool, new java.util.HashMap<>());
        return strategy.execute(new ExecutionStrategy.StrategyRequest(
                new WorkflowCompiler(engine.nodeRegistry()), engine.nodeRegistry(), def,
                Map.of("caseId", "case-clean"), context));
    }

    private ModelPort tryBuildModelPort() {
        try {
            ModelConfig config = ModelConfig.loadProperties();
            if (config.apiKey() == null || config.apiKey().isBlank()
                    || "填写自己的密钥".equals(config.apiKey())) {
                return null;
            }
            return new SpringAiModelPort(new ModelCreator().getModel());
        } catch (Exception e) {
            return null;
        }
    }
}
