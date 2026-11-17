package com.chinazhouwy.miniharness.runtime;

import com.chinazhouwy.miniharness.runtime.core.BudgetExceededException;
import com.chinazhouwy.miniharness.runtime.core.NodeId;
import com.chinazhouwy.miniharness.runtime.core.RunBudget;
import com.chinazhouwy.miniharness.runtime.core.NodeStatus;
import com.chinazhouwy.miniharness.runtime.core.RunId;
import com.chinazhouwy.miniharness.runtime.core.RunStatus;
import com.chinazhouwy.miniharness.runtime.core.TaskId;
import com.chinazhouwy.miniharness.runtime.core.TaskStatus;
import com.chinazhouwy.miniharness.runtime.core.WorkflowRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 状态机与预算的可验证失败边界。 */
class StateMachineAndBudgetTest {

    @Test
    @DisplayName("Task 状态机：非法转换被拒绝，合法转换被放行")
    void taskStateTransitions() {
        TaskStatus open = TaskStatus.OPEN;
        assertTrue(open.canTransitionTo(TaskStatus.ACTIVE));
        assertTrue(open.canTransitionTo(TaskStatus.CANCELLED));
        assertFalse(open.canTransitionTo(TaskStatus.COMPLETED), "OPEN 不允许直接完成");
        assertThrows(com.chinazhouwy.miniharness.runtime.core.IllegalTransitionException.class,
                () -> open.requireTransitionTo(TaskStatus.COMPLETED));
        TaskStatus completed = TaskStatus.COMPLETED;
        assertFalse(completed.canTransitionTo(TaskStatus.ACTIVE), "终态不允许复活");
    }

    @Test
    @DisplayName("Run 状态机：QUEUED 不允许直接失败成功，终态封闭")
    void runStateTransitions() {
        assertFalse(RunStatus.QUEUED.canTransitionTo(RunStatus.SUCCEEDED));
        assertTrue(RunStatus.QUEUED.canTransitionTo(RunStatus.RUNNING));
        assertTrue(RunStatus.RUNNING.canTransitionTo(RunStatus.TIMED_OUT));
        assertThrows(com.chinazhouwy.miniharness.runtime.core.IllegalTransitionException.class,
                () -> RunStatus.FAILED.requireTransitionTo(RunStatus.RUNNING));
    }

    @Test
    @DisplayName("RunBudget：模型调用超限抛 BudgetExceededException")
    void budgetEnforcement() {
        RunBudget budget = new RunBudget(1, 2, Long.MAX_VALUE);
        budget.chargeModelCall();
        assertThrows(BudgetExceededException.class, budget::chargeModelCall);
        budget.chargeToolCall("t1");
        budget.chargeToolCall("t2");
        assertThrows(BudgetExceededException.class, () -> budget.chargeToolCall("t3"));
        assertEquals(1, budget.modelCalls());
        assertEquals(2, budget.toolCalls());
    }

    @Test
    @DisplayName("WorkflowRef：版本必须为正整数")
    void workflowRefValidation() {
        assertThrows(IllegalArgumentException.class, () -> new WorkflowRef("w", 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowRef("", 1));
        assertEquals("w@v2", new WorkflowRef("w", 2).toString());
    }

    @Test
    @DisplayName("ID 类型：可读且稳定")
    void ids() {
        TaskId taskId = TaskId.random();
        RunId runId = RunId.random();
        assertTrue(taskId.value().startsWith("task-"));
        assertTrue(runId.value().startsWith("run-"));
        assertEquals(taskId, new TaskId(taskId.value()));
        Set.of(NodeId.class, NodeStatus.class); // 引用保持 import 一致性
    }
}
