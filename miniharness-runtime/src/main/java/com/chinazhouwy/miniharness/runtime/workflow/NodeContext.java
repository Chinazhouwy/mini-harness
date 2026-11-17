package com.chinazhouwy.miniharness.runtime.workflow;

import com.chinazhouwy.miniharness.runtime.core.RunBudget;
import com.chinazhouwy.miniharness.runtime.core.RunId;
import com.chinazhouwy.miniharness.runtime.core.TaskId;
import com.chinazhouwy.miniharness.runtime.event.EventBus;
import com.chinazhouwy.miniharness.runtime.model.ModelPort;
import com.chinazhouwy.miniharness.runtime.tool.ToolInvoker;
import com.chinazhouwy.miniharness.runtime.tool.ToolRegistry;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

/**
 * 节点执行上下文：节点处理器可经工具调用器访问工具与模型，
 * 预算/取消/事件发射统一由 Harness 层管控。attributes 提供运行内共享暂存。
 */
public record NodeContext(
        EventBus events,
        TaskId taskId,
        RunId runId,
        ToolRegistry tools,
        ToolInvoker toolInvoker,
        ModelPort model,
        RunBudget budget,
        BooleanSupplier cancelled,
        ExecutorService executor,
        Map<String, Object> attributes
) {
}
