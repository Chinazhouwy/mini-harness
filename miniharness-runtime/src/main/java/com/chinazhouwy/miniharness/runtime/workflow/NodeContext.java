package com.chinazhouwy.miniharness.runtime.workflow;

import com.chinazhouwy.miniharness.runtime.core.RunBudget;
import com.chinazhouwy.miniharness.runtime.event.EventBus;
import com.chinazhouwy.miniharness.runtime.model.ModelPort;
import com.chinazhouwy.miniharness.runtime.tool.ToolRegistry;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

/**
 * 节点执行上下文：节点处理器可通过它访问工具、模型与事件，但预算/取消/权限仍由 Harness 管控。
 * attributes 提供运行内共享暂存（例如把验证报告传给报告节点）。
 */
public record NodeContext(
        EventBus events,
        ToolRegistry tools,
        ModelPort model,
        RunBudget budget,
        BooleanSupplier cancelled,
        ExecutorService executor,
        Map<String, Object> attributes
) {
}
