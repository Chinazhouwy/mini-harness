package com.chinazhouwy.miniharness.runtime.core;

/**
 * 运行预算：模型调用次数、工具调用次数、总时长上限 + 实际用量计数。
 * 预算扣减是 Harness 行为，不由模型或工具自觉遵守；超限抛 BudgetExceededException。
 */
public final class RunBudget {

    private final int maxModelCalls;
    private final int maxToolCalls;
    private final long maxDurationMs;
    private final long startNanos = System.nanoTime();
    private int modelCalls;
    private int toolCalls;

    public RunBudget(int maxModelCalls, int maxToolCalls, long maxDurationMs) {
        this.maxModelCalls = maxModelCalls;
        this.maxToolCalls = maxToolCalls;
        this.maxDurationMs = maxDurationMs;
    }

    public static RunBudget unlimited() {
        return new RunBudget(Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    public synchronized void chargeModelCall() {
        if (modelCalls >= maxModelCalls) {
            throw new BudgetExceededException(
                    "模型调用预算耗尽: " + modelCalls + "/" + maxModelCalls);
        }
        modelCalls++;
    }

    public synchronized void chargeToolCall(String toolName) {
        if (toolCalls >= maxToolCalls) {
            throw new BudgetExceededException(
                    "工具调用预算耗尽: " + toolCalls + "/" + maxToolCalls + "，触发于 " + toolName);
        }
        toolCalls++;
    }

    public synchronized boolean durationExceeded() {
        long usedMs = (System.nanoTime() - startNanos) / 1_000_000;
        return usedMs > maxDurationMs;
    }

    public synchronized int modelCalls() {
        return modelCalls;
    }

    public synchronized int toolCalls() {
        return toolCalls;
    }

    public synchronized long elapsedMs() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
