package com.chinazhouwy.miniharness.runtime.core;

/** 执行预算（模型调用次数 / 工具调用次数 / 时长）耗尽时抛出。 */
public class BudgetExceededException extends HarnessException {
    public BudgetExceededException(String message) {
        super("BUDGET_EXCEEDED", message);
    }
}
