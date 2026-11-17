package com.chinazhouwy.miniharness.runtime.tool;

import java.util.Map;

/**
 * 工具执行上下文：取消信号 + 预算回调。
 * 工具实现应轮询 cancelled()；预算通过 requestBudget() 申报，超限抛 BudgetExceededException。
 */
public record ToolContext(boolean cancelled, BudgetHandle budget) {

    public ToolContext {
        if (budget == null) {
            budget = BudgetHandle.UNLIMITED;
        }
    }

    /** 运行级预算句柄；工具每发起一次外部调用前应调用 charge()。 */
    public interface BudgetHandle {
        BudgetHandle UNLIMITED = new BudgetHandle() {
            @Override
            public void chargeToolCall(String toolName) {
            }

            @Override
            public void chargeModelCall() {
            }
        };

        void chargeToolCall(String toolName);

        void chargeModelCall();
    }
}
