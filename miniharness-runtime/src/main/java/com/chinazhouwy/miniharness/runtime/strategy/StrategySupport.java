package com.chinazhouwy.miniharness.runtime.strategy;

import com.chinazhouwy.miniharness.runtime.core.NodeStatus;
import com.chinazhouwy.miniharness.runtime.workflow.CompiledWorkflow;
import com.chinazhouwy.miniharness.runtime.workflow.NodeResult;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowExecutionResult;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowExecutor;

import java.util.LinkedHashMap;
import java.util.Map;

/** 策略共用执行工具：统一走标准执行器，保证事件/预算/超时语义与主链路一致。 */
final class StrategySupport {

    private StrategySupport() {
    }

    /**
     * 按给定注册表执行定义（策略可传入包装过节点的注册表以注入模型咨询）。
     * 返回执行结果；调用方再从 RunBudget 读取模型/工具计数。
     */
    static WorkflowExecutionResult execute(ExecutionStrategy.StrategyRequest request,
                                           com.chinazhouwy.miniharness.runtime.workflow.NodeTypeRegistry registry) {
        CompiledWorkflow compiled = request.compiler().compile(request.definition());
        return new WorkflowExecutor(registry)
                .execute(compiled, request.definition().ref(),
                        request.context().taskId(), request.context().runId(),
                        request.input(), request.context());
    }

    /** 把执行器的节点结果按执行器口径合并为输出 Map（成功节点的 output 依次覆盖）。 */
    static Map<String, Object> mergeOutputs(WorkflowExecutionResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        for (NodeResult nodeResult : result.nodeResults()) {
            if (nodeResult.status() == NodeStatus.SUCCEEDED && nodeResult.output() != null) {
                output.putAll(nodeResult.output());
            }
        }
        return output;
    }

    /** 组装策略结果；模型/工具计数取自 RunBudget 真实扣减。 */
    static ExecutionStrategy.StrategyResult result(ExecutionStrategy strategy,
                                                   Map<String, Object> output,
                                                   com.chinazhouwy.miniharness.runtime.core.RunBudget budget,
                                                   long promptChars,
                                                   long durationMs,
                                                   String notes) {
        return new ExecutionStrategy.StrategyResult(
                strategy.name(), output,
                budget.modelCalls(), budget.toolCalls(), promptChars, durationMs, notes);
    }
}
