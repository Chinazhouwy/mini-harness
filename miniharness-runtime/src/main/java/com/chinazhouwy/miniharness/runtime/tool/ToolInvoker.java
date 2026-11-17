package com.chinazhouwy.miniharness.runtime.tool;

import com.chinazhouwy.miniharness.runtime.core.BudgetExceededException;
import com.chinazhouwy.miniharness.runtime.core.RunId;
import com.chinazhouwy.miniharness.runtime.core.TaskId;
import com.chinazhouwy.miniharness.runtime.event.EventBus;
import com.chinazhouwy.miniharness.runtime.event.EventType;
import com.chinazhouwy.miniharness.runtime.event.HarnessEvent;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具调用器：统一承担 事件发射、预算扣减、超时、重试。
 * 路由、权限、预算都由 Harness 管控，工具实现方只提供确定性逻辑。
 */
public class ToolInvoker {

    public static final int DEFAULT_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_MAX_ATTEMPTS = 1;

    private final ToolRegistry registry;
    private final EventBus events;
    private final ExecutorService executor;

    public ToolInvoker(ToolRegistry registry, EventBus events, ExecutorService executor) {
        this.registry = registry;
        this.events = events;
        this.executor = executor;
    }

    /**
     * 执行工具：发射 TOOL_REQUESTED / TOOL_COMPLETED / TOOL_FAILED 事件。
     * @param maxAttempts 总尝试次数（含首次），失败后短暂等待重试
     * @return 工具输出
     * @throws ToolFailureException 工具失败或超时（已耗尽重试次数）
     */
    public Map<String, Object> invoke(String toolName,
                                      Map<String, Object> args,
                                      TaskId taskId,
                                      RunId runId,
                                      boolean cancelled,
                                      ToolContext.BudgetHandle budget,
                                      int timeoutMs,
                                      int maxAttempts) {
        ToolSpec spec = registry.get(toolName);
        if (cancelled) {
            throw new ToolFailureException("RUN_CANCELLED", "运行已取消，工具调用被拒绝: " + toolName);
        }
        budget.chargeToolCall(toolName);

        Exception lastFailure = null;
        for (int attempt = 1; attempt <= Math.max(1, maxAttempts); attempt++) {
            events.publish(HarnessEvent.builder(EventType.TOOL_REQUESTED)
                    .task(taskId).run(runId)
                    .message("调用工具 " + toolName + " (第" + attempt + "次)")
                    .payload(Map.of("tool", toolName, "args", args, "attempt", attempt)));
            try {
                Map<String, Object> result = runWithTimeout(spec, args, cancelled, budget, timeoutMs);
                events.publish(HarnessEvent.builder(EventType.TOOL_COMPLETED)
                        .task(taskId).run(runId)
                        .message("工具完成 " + toolName)
                        .payload(Map.of("tool", toolName, "attempt", attempt)));
                return result;
            } catch (ToolFailureException e) {
                // 明确的业务失败不重试（例如口径校验失败），直接上抛由节点处理。
                events.publish(HarnessEvent.builder(EventType.TOOL_FAILED)
                        .task(taskId).run(runId)
                        .message("工具失败 " + toolName + ": " + e.errorCode())
                        .payload(Map.of("tool", toolName, "errorCode", e.errorCode())));
                throw e;
            } catch (Exception e) {
                lastFailure = e;
                events.publish(HarnessEvent.builder(EventType.TOOL_FAILED)
                        .task(taskId).run(runId)
                        .message("工具异常 " + toolName + " (第" + attempt + "次): " + e.getMessage())
                        .payload(Map.of("tool", toolName, "attempt", attempt,
                                "error", String.valueOf(e.getMessage()))));
                if (attempt < maxAttempts) {
                    sleepQuietly(50);
                }
            }
        }
        throw new ToolFailureException("TOOL_FAILED",
                "工具 " + toolName + " 重试 " + maxAttempts + " 次后仍失败: " + rootMessage(lastFailure), lastFailure);
    }

    private Map<String, Object> runWithTimeout(ToolSpec spec, Map<String, Object> args,
                                               boolean cancelled, ToolContext.BudgetHandle budget,
                                               int timeoutMs) throws Exception {
        Future<Map<String, Object>> future = executor.submit(() ->
                spec.executor().execute(args, new ToolContext(cancelled, budget)));
        try {
            return future.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ToolFailureException("TOOL_TIMEOUT",
                    "工具 " + spec.name() + " 超过 " + timeoutMs + "ms 未完成");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof BudgetExceededException be) {
                throw be;
            }
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new ToolFailureException("TOOL_FAILED", String.valueOf(cause.getMessage()), cause);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String rootMessage(Exception e) {
        if (e == null) {
            return "unknown";
        }
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
