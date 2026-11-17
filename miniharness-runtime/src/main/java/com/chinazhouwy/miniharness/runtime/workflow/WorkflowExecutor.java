package com.chinazhouwy.miniharness.runtime.workflow;

import com.chinazhouwy.miniharness.runtime.core.NodeId;
import com.chinazhouwy.miniharness.runtime.core.NodeStatus;
import com.chinazhouwy.miniharness.runtime.core.RunStatus;
import com.chinazhouwy.miniharness.runtime.event.EventType;
import com.chinazhouwy.miniharness.runtime.event.HarnessEvent;
import com.chinazhouwy.miniharness.runtime.core.RunId;
import com.chinazhouwy.miniharness.runtime.core.TaskId;
import com.chinazhouwy.miniharness.runtime.core.WorkflowRef;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Workflow 执行器：按编译期拓扑序逐节点执行，承担
 * 取消检查、时长预算检查、失败/跳过传播、节点超时与重试、节点级事件发射。
 * 调度与管控全部由 Harness 完成；节点处理器只写确定性业务逻辑。
 */
public class WorkflowExecutor {

    private final NodeTypeRegistry registry;

    public WorkflowExecutor(NodeTypeRegistry registry) {
        this.registry = registry;
    }

    public WorkflowExecutionResult execute(CompiledWorkflow compiled,
                                           WorkflowRef workflowRef,
                                           TaskId taskId,
                                           RunId runId,
                                           Map<String, Object> workflowInput,
                                           NodeContext context) {
        Instant startedAt = Instant.now();
        List<NodeResult> results = new ArrayList<>();
        Map<NodeId, NodeOutput> outputs = new HashMap<>();
        RunStatus outcome = RunStatus.SUCCEEDED;
        String errorCode = null;
        String errorSummary = null;

        for (NodeId nodeId : compiled.topoOrder()) {
            // 取消检查：Harness 主动终止，未执行节点全部标记 SKIPPED。
            if (context.cancelled().getAsBoolean()) {
                outcome = RunStatus.CANCELLED;
                errorCode = "RUN_CANCELLED";
                errorSummary = "执行被取消于节点 " + nodeId;
                results.addAll(skipRemaining(compiled, results));
                break;
            }
            // 时长预算检查。
            if (context.budget().durationExceeded()) {
                outcome = RunStatus.TIMED_OUT;
                errorCode = "DURATION_BUDGET_EXCEEDED";
                errorSummary = "执行时长超出预算，止于节点 " + nodeId;
                results.addAll(skipRemaining(compiled, results));
                break;
            }
            // 上游失败/跳过传播。
            List<NodeId> upstream = List.copyOf(compiled.upstreamOf().get(nodeId));
            NodeId blockedBy = null;
            for (NodeId up : upstream) {
                NodeOutput upOutput = outputs.get(up);
                if (upOutput != null && upOutput.status() != NodeStatus.SUCCEEDED) {
                    blockedBy = up;
                    break;
                }
            }
            if (blockedBy != null) {
                NodeOutput skipped = NodeOutput.skipped("上游节点 " + blockedBy + " 未成功，跳过本节点");
                outputs.put(nodeId, skipped);
                results.add(new NodeResult(nodeId, NodeStatus.SKIPPED, skipped.data(), "SKIPPED",
                        "上游节点 " + blockedBy + " 未成功", 0, null, Instant.now(), 0));
                context.events().publish(HarnessEvent.builder(EventType.NODE_SKIPPED)
                        .task(taskId).run(runId).node(nodeId).workflow(workflowRef)
                        .message("节点 " + nodeId + " 被跳过: 上游 " + blockedBy + " 未成功")
                        .payload(Map.of("blockedBy", blockedBy.toString())));
                continue;
            }

            NodeResult result = runNode(compiled.definition(), workflowRef, taskId, runId, nodeId,
                    workflowInput, outputs, context);
            results.add(result);
            NodeOutput output = result.status() == NodeStatus.SUCCEEDED
                    ? NodeOutput.success(result.output())
                    : new NodeOutput(result.status(), result.output(), result.errorCode(), result.errorMessage());
            outputs.put(nodeId, output);
            if (result.status() == NodeStatus.FAILED) {
                outcome = RunStatus.FAILED;
                errorCode = result.errorCode();
                errorSummary = "节点 " + nodeId + " 失败: " + result.errorMessage();
                break; // 失败即停，剩余节点在下方统一标记 SKIPPED。
            }
        }

        if (outcome != RunStatus.SUCCEEDED && !allNodesAccounted(compiled, results)) {
            results.addAll(skipRemaining(compiled, results));
        }

        Instant finishedAt = Instant.now();
        long durationMs = Duration.between(startedAt, finishedAt).toMillis();
        return new WorkflowExecutionResult(outcome, results, errorCode, errorSummary, startedAt, finishedAt, durationMs);
    }

    private boolean allNodesAccounted(CompiledWorkflow compiled, List<NodeResult> results) {
        Map<NodeId, NodeResult> map = new LinkedHashMap<>();
        for (NodeResult r : results) {
            map.put(r.nodeId(), r);
        }
        for (NodeId id : compiled.topoOrder()) {
            if (!map.containsKey(id)) {
                return false;
            }
        }
        return true;
    }

    /** 把尚未执行/尚未记录的节点统一补记为 SKIPPED，保证节点结果覆盖完整拓扑。 */
    private List<NodeResult> skipRemaining(CompiledWorkflow compiled, List<NodeResult> existing) {
        Map<NodeId, NodeResult> seen = new LinkedHashMap<>();
        for (NodeResult r : existing) {
            seen.put(r.nodeId(), r);
        }
        List<NodeResult> additions = new ArrayList<>();
        for (NodeId id : compiled.topoOrder()) {
            if (!seen.containsKey(id)) {
                NodeOutput skipped = NodeOutput.skipped("流程提前结束，未执行");
                additions.add(new NodeResult(id, NodeStatus.SKIPPED, skipped.data(), "SKIPPED",
                        "流程提前结束", 0, null, Instant.now(), 0));
            }
        }
        return additions;
    }

    /** 执行单个节点：事件、超时、重试。业务失败（NodeOutput FAILED）不重试。 */
    private NodeResult runNode(WorkflowDefinition definition,
                               WorkflowRef workflowRef,
                               TaskId taskId,
                               RunId runId,
                               NodeId nodeId,
                               Map<String, Object> workflowInput,
                               Map<NodeId, NodeOutput> outputs,
                               NodeContext context) {
        NodeSpec spec = definition.node(nodeId).orElseThrow();
        NodeHandler handler = registry.require(spec.type());
        NodeInput input = new NodeInput(nodeId, workflowInput, spec.params(), outputs, context);
        int maxAttempts = spec.maxAttempts();
        Instant firstStart = Instant.now();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            context.events().publish(HarnessEvent.builder(EventType.NODE_STARTED)
                    .task(taskId).run(runId).node(nodeId).workflow(workflowRef)
                    .message("节点 " + nodeId + " 开始执行")
                    .payload(Map.of("type", spec.type(), "attempt", attempt)));
            long nodeStart = System.nanoTime();
            try {
                CompletableFuture<NodeOutput> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        return handler.execute(input);
                    } catch (Exception e) {
                        throw new NodeExecutionWrappedException(e);
                    }
                }, context.executor());
                NodeOutput output;
                try {
                    output = future.get(spec.timeoutMs(), TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    future.cancel(true);
                    throw new NodeExecutionWrappedException(
                            new java.util.concurrent.TimeoutException("节点超时 " + spec.timeoutMs() + "ms"));
                } catch (java.util.concurrent.ExecutionException ee) {
                    // get() 会把异步线程内抛出的异常包一层；解包还原成处理器原始异常再统一处理。
                    Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                    if (cause instanceof NodeExecutionWrappedException wrapped) {
                        throw wrapped;
                    }
                    throw new NodeExecutionWrappedException(cause);
                }
                long durationMs = (System.nanoTime() - nodeStart) / 1_000_000;
                if (output.status() == NodeStatus.SUCCEEDED) {
                    context.events().publish(HarnessEvent.builder(EventType.NODE_SUCCEEDED)
                            .task(taskId).run(runId).node(nodeId).workflow(workflowRef)
                            .message("节点 " + nodeId + " 执行成功")
                            .payload(Map.of("durationMs", durationMs)));
                    return new NodeResult(nodeId, NodeStatus.SUCCEEDED, output.data(), null, null,
                            attempt, firstStart, Instant.now(), durationMs);
                }
                // 业务失败：结构化 errorCode，不重试。
                context.events().publish(HarnessEvent.builder(EventType.NODE_FAILED)
                        .task(taskId).run(runId).node(nodeId).workflow(workflowRef)
                        .message("节点 " + nodeId + " 业务失败: " + output.errorCode())
                        .payload(Map.of("errorCode", String.valueOf(output.errorCode()),
                                "errorMessage", String.valueOf(output.errorMessage()))));
                return new NodeResult(nodeId, NodeStatus.FAILED, output.data(), output.errorCode(),
                        output.errorMessage(), attempt, firstStart, Instant.now(), durationMs);
            } catch (NodeExecutionWrappedException we) {
                Throwable cause = we.getCause() != null ? we.getCause() : we;
                long durationMs = (System.nanoTime() - nodeStart) / 1_000_000;
                if (attempt < maxAttempts) {
                    context.events().publish(HarnessEvent.builder(EventType.NODE_RETRYING)
                            .task(taskId).run(runId).node(nodeId).workflow(workflowRef)
                            .message("节点 " + nodeId + " 第" + attempt + "次执行失败，准备重试: " + cause.getMessage())
                            .payload(Map.of("attempt", attempt,
                                    "error", String.valueOf(cause.getMessage()))));
                    sleepQuietly(spec.retryBackoffMs());
                    continue;
                }
                String message = cause instanceof com.chinazhouwy.miniharness.runtime.core.BudgetExceededException be
                        ? be.getMessage()
                        : cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
                String code = cause instanceof com.chinazhouwy.miniharness.runtime.core.HarnessException he
                        ? he.errorCode()
                        : "NODE_ERROR";
                context.events().publish(HarnessEvent.builder(EventType.NODE_FAILED)
                        .task(taskId).run(runId).node(nodeId).workflow(workflowRef)
                        .message("节点 " + nodeId + " 异常失败: " + code)
                        .payload(Map.of("errorCode", code, "errorMessage", message)));
                return new NodeResult(nodeId, NodeStatus.FAILED, Map.of(), code, message,
                        attempt, firstStart, Instant.now(), durationMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                long durationMs = (System.nanoTime() - nodeStart) / 1_000_000;
                return new NodeResult(nodeId, NodeStatus.FAILED, Map.of(), "NODE_INTERRUPTED",
                        "节点执行被中断", 1, firstStart, Instant.now(), durationMs);
            }
        }
        // 理论不可达：for 循环内必然 return。
        return new NodeResult(nodeId, NodeStatus.FAILED, Map.of(), "NODE_ERROR", "未预期的执行路径",
                maxAttempts, firstStart, Instant.now(), 0);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 把处理器抛出的受检异常包成非受检，统一走重试/失败路径。 */
    static final class NodeExecutionWrappedException extends RuntimeException {
        NodeExecutionWrappedException(Throwable cause) {
            super(cause);
        }
    }
}
