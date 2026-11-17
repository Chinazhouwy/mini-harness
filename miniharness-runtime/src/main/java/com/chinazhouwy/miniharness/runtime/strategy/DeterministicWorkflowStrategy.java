package com.chinazhouwy.miniharness.runtime.strategy;

import com.chinazhouwy.miniharness.runtime.workflow.WorkflowExecutionResult;

import java.util.Map;

/** 策略 A 的对照基线：完全确定性执行，0 次模型调用。 */
public class DeterministicWorkflowStrategy implements ExecutionStrategy {

    @Override
    public String name() {
        return "deterministic-workflow";
    }

    @Override
    public StrategyResult execute(StrategyRequest request) {
        long start = System.nanoTime();
        WorkflowExecutionResult execution = StrategySupport.execute(request, request.registry());
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        return StrategySupport.result(this, StrategySupport.mergeOutputs(execution),
                request.context().budget(), 0, durationMs,
                "确定性执行（对照基线）：全部节点走注册表处理器，0 次模型调用");
    }
}
