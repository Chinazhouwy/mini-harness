package com.chinazhouwy.miniharness.runtime.strategy;

import com.chinazhouwy.miniharness.runtime.workflow.NodeContext;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowCompiler;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowDefinition;

import java.util.Map;

/**
 * 执行策略：最小可插拔接口，决定一次任务执行中「哪些步骤问模型、哪些走确定性逻辑」。
 *
 * 设计边界（与任务书第五节对应）：
 * - 路由、权限、执行状态始终由 Harness 管控——模型只在策略显式安排的位置被调用，
 *   且金融计算等确定性逻辑永远不依赖模型输出（模型只做解释/复核，不能改动计算结果）。
 * - 策略实现必须能在无模型环境（NoopModelPort）下明确失败而不是静默假装成功。
 * - 真实模型的 A/B/C 质量对比属于 live 入口任务；离线脚本化模型只能可靠对比
 *   调用次数与调度开销（脚本模型无网络延迟，时延差在离线下不显著）。
 */
public interface ExecutionStrategy {

    /** 策略名（用于对比报告与事件审计）。 */
    String name();

    StrategyResult execute(StrategyRequest request);

    /** 策略执行请求：编译器 + 节点注册表 + 流程定义 + 输入 + 节点上下文（工具/模型/预算/事件）。 */
    record StrategyRequest(
            WorkflowCompiler compiler,
            com.chinazhouwy.miniharness.runtime.workflow.NodeTypeRegistry registry,
            WorkflowDefinition definition,
            Map<String, Object> input,
            NodeContext context
    ) {
    }

    /**
     * 策略执行结果。
     * modelCalls/toolCalls/promptChars 来自 RunBudget 真实计数；durationMs 为墙钟；
     * output 为全部成功节点输出的合并（同执行器口径）。
     */
    record StrategyResult(
            String strategy,
            Map<String, Object> output,
            int modelCalls,
            int toolCalls,
            long promptChars,
            long durationMs,
            String notes
    ) {
    }
}
