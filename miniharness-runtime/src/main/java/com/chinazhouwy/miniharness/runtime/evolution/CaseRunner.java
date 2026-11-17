package com.chinazhouwy.miniharness.runtime.evolution;

import com.chinazhouwy.miniharness.runtime.core.NodeStatus;
import com.chinazhouwy.miniharness.runtime.core.RunBudget;
import com.chinazhouwy.miniharness.runtime.core.RunId;
import com.chinazhouwy.miniharness.runtime.core.RunStatus;
import com.chinazhouwy.miniharness.runtime.core.TaskId;
import com.chinazhouwy.miniharness.runtime.event.EventBus;
import com.chinazhouwy.miniharness.runtime.event.EventType;
import com.chinazhouwy.miniharness.runtime.event.HarnessEvent;
import com.chinazhouwy.miniharness.runtime.model.ModelPort;
import com.chinazhouwy.miniharness.runtime.model.NoopModelPort;
import com.chinazhouwy.miniharness.runtime.tool.ToolRegistry;
import com.chinazhouwy.miniharness.runtime.workflow.CompiledWorkflow;
import com.chinazhouwy.miniharness.runtime.workflow.NodeContext;
import com.chinazhouwy.miniharness.runtime.workflow.NodeResult;
import com.chinazhouwy.miniharness.runtime.workflow.NodeTypeRegistry;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowCompiler;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowDefinition;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowExecutor;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowExecutionResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 案例执行器：把一个 WorkflowDefinition 在受控案例上真实跑一遍。
 * 评测与「v1 测试轨迹采集」共用本类，保证指标口径一致。
 * 工具注册表与模型端口由构造注入——评测使用与生产一致的确定性工具；离线评测用 NoopModelPort。
 */
public class CaseRunner {

    private final NodeTypeRegistry registry;
    private final ToolRegistry toolRegistry;
    private final ModelPort modelPort;
    private final EventBus events;
    private final WorkflowCompiler compiler;
    private final AtomicLong evalRunCounter = new AtomicLong();

    public CaseRunner(NodeTypeRegistry registry, ToolRegistry toolRegistry, ModelPort modelPort,
                      EventBus events, WorkflowCompiler compiler) {
        this.registry = registry;
        this.toolRegistry = toolRegistry;
        this.modelPort = modelPort == null ? new NoopModelPort() : modelPort;
        this.events = events;
        this.compiler = compiler;
    }

    /** 单案例执行结果。 */
    public record CaseRunResult(
            EvalCase evalCase,
            RunStatus outcome,
            String errorCode,
            Map<String, Object> output,
            int toolCalls,
            int modelCalls,
            long durationMs,
            List<NodeResult> nodeResults,
            boolean passed,
            String passDetail
    ) {
    }

    public CaseRunResult run(WorkflowDefinition definition, EvalCase evalCase) {
        CompiledWorkflow compiled = compiler.compile(definition);
        RunBudget budget = new RunBudget(50, 50, 60_000);
        ExecutorService nodePool = Executors.newCachedThreadPool();
        try {
            RunId runId = new RunId("eval-" + evalRunCounter.incrementAndGet());
            TaskId evalTaskId = new TaskId("eval-case-" + evalCase.caseId());
            events.publish(HarnessEvent.builder(EventType.RUN_STARTED)
                    .task(evalTaskId).run(runId).workflow(definition.ref())
                    .message("评测案例执行 " + evalCase.caseId() + " @ " + definition.ref())
                    .payload(Map.of("caseId", evalCase.caseId(), "split", evalCase.split())));
            NodeContext context = new NodeContext(events, evalTaskId, runId, toolRegistry,
                    new com.chinazhouwy.miniharness.runtime.tool.ToolInvoker(toolRegistry, events, nodePool),
                    modelPort, budget, () -> false, nodePool, new HashMap<>());
            WorkflowExecutionResult result = new WorkflowExecutor(registry)
                    .execute(compiled, definition.ref(), evalTaskId, runId,
                            evalCase.workflowInput(), context);

            Map<String, Object> output = new LinkedHashMap<>();
            for (NodeResult nodeResult : result.nodeResults()) {
                if (nodeResult.output() != null) {
                    output.putAll(nodeResult.output());
                }
            }
            boolean passed;
            String detail;
            if (evalCase.expected() == EvalCase.Expected.CORRECT_REPORT) {
                Object actual = output.get(evalCase.metricKey());
                boolean metricMatches = actual instanceof Number number && evalCase.metricValue() != null
                        && Math.abs(number.doubleValue() - evalCase.metricValue())
                        <= Math.max(1e-9, Math.abs(evalCase.metricValue()) * 1e-6);
                passed = result.outcome() == RunStatus.SUCCEEDED && metricMatches;
                detail = passed ? "指标 " + evalCase.metricKey() + "=" + actual + " 与期望一致"
                        : "期望 CORRECT_REPORT(" + evalCase.metricKey() + "=" + evalCase.metricValue()
                        + ")，实际 outcome=" + result.outcome() + ", metric=" + actual;
            } else {
                passed = result.outcome() == RunStatus.FAILED
                        && evalCase.expectedErrorKinds().contains(result.errorCode());
                detail = passed ? "按期望拒绝，errorCode=" + result.errorCode()
                        : "期望 REJECTED(" + evalCase.expectedErrorKinds() + ")，实际 outcome="
                        + result.outcome() + ", errorCode=" + result.errorCode();
            }
            events.publish(HarnessEvent.builder(EventType.RUN_COMPLETED)
                    .task(evalTaskId).run(runId).workflow(definition.ref())
                    .message("评测案例完成 " + evalCase.caseId() + ": " + (passed ? "通过" : "未通过"))
                    .payload(Map.of("caseId", evalCase.caseId(), "passed", passed)));
            return new CaseRunResult(evalCase, result.outcome(), result.errorCode(),
                    Map.copyOf(output), budget.toolCalls(), budget.modelCalls(), result.durationMs(),
                    result.nodeResults(), passed, detail);
        } finally {
            nodePool.shutdownNow();
        }
    }

    /** 把案例执行结果转成经验记录：失败案例按注入缺陷类型做归因标注。 */
    public ExperienceRecord toExperience(CaseRunResult result, WorkflowDefinition definition) {
        List<ExperienceRecord.NodeSummary> nodes = new ArrayList<>();
        for (NodeResult nodeResult : result.nodeResults()) {
            nodes.add(new ExperienceRecord.NodeSummary(nodeResult.nodeId().value(),
                    nodeResult.status(), nodeResult.errorCode(), nodeResult.durationMs()));
        }
        List<ExperienceRecord.FailureAnnotation> annotations = new ArrayList<>();
        if (!result.passed()) {
            if (result.evalCase().injectedDefectKind() != null) {
                annotations.add(new ExperienceRecord.FailureAnnotation(
                        result.evalCase().injectedDefectKind(),
                        annotationNodeId(result),
                        "评测案例 " + result.evalCase().caseId() + " 暴露缺陷: " + result.passDetail()));
            } else if (result.errorCode() != null
                    && FailureKinds.DATA_QUALITY_KINDS.contains(result.errorCode())) {
                annotations.add(new ExperienceRecord.FailureAnnotation(result.errorCode(),
                        annotationNodeId(result),
                        "评测案例 " + result.evalCase().caseId() + " 失败: " + result.passDetail()));
            }
        }
        // 评测经验用 eval-case 任务标识充当证据引用（无真实 Task/Run），保证候选来源可追溯。
        return new ExperienceRecord(null,
                new com.chinazhouwy.miniharness.runtime.core.TaskId("eval-case-" + result.evalCase().caseId()),
                definition.ref(), "EVALUATION", result.outcome(),
                result.errorCode(), result.passDetail(), nodes, result.output(), annotations,
                Instant.now());
    }

    /** 归因节点：优先案例声明的指标产出节点，其次最后一个失败节点，最后最后一个成功节点。 */
    private String annotationNodeId(CaseRunResult result) {
        if (result.evalCase().metricProducerNode() != null
                && !result.evalCase().metricProducerNode().isBlank()) {
            return result.evalCase().metricProducerNode();
        }
        String lastFailed = null;
        String lastSucceeded = null;
        for (NodeResult nodeResult : result.nodeResults()) {
            if (nodeResult.status() == NodeStatus.FAILED) {
                lastFailed = nodeResult.nodeId().value();
            }
            if (nodeResult.status() == NodeStatus.SUCCEEDED) {
                lastSucceeded = nodeResult.nodeId().value();
            }
        }
        return lastFailed != null ? lastFailed : lastSucceeded;
    }
}
