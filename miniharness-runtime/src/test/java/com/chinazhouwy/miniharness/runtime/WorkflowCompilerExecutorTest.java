package com.chinazhouwy.miniharness.runtime;

import com.chinazhouwy.miniharness.runtime.core.HarnessException;
import com.chinazhouwy.miniharness.runtime.core.NodeId;
import com.chinazhouwy.miniharness.runtime.core.NodeStatus;
import com.chinazhouwy.miniharness.runtime.core.RunBudget;
import com.chinazhouwy.miniharness.runtime.core.RunId;
import com.chinazhouwy.miniharness.runtime.core.RunStatus;
import com.chinazhouwy.miniharness.runtime.core.TaskId;
import com.chinazhouwy.miniharness.runtime.core.WorkflowRef;
import com.chinazhouwy.miniharness.runtime.event.EventBus;
import com.chinazhouwy.miniharness.runtime.event.EventType;
import com.chinazhouwy.miniharness.runtime.model.NoopModelPort;
import com.chinazhouwy.miniharness.runtime.tool.ToolRegistry;
import com.chinazhouwy.miniharness.runtime.workflow.CompiledWorkflow;
import com.chinazhouwy.miniharness.runtime.workflow.NodeContext;
import com.chinazhouwy.miniharness.runtime.workflow.NodeHandler;
import com.chinazhouwy.miniharness.runtime.workflow.NodeInput;
import com.chinazhouwy.miniharness.runtime.workflow.NodeOutput;
import com.chinazhouwy.miniharness.runtime.workflow.NodeSpec;
import com.chinazhouwy.miniharness.runtime.workflow.NodeTypeRegistry;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowCompiler;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowDefinition;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowEdge;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowExecutionResult;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Workflow 编译与执行：拓扑校验、失败传播、重试、超时、预算、取消与事件序列。 */
class WorkflowCompilerExecutorTest {

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final EventBus events = new EventBus();
    private final TaskId taskId = TaskId.random();
    private final RunId runId = RunId.random();
    private final WorkflowRef ref = new WorkflowRef("demo", 1);

    /** 透传成功节点：把输入原样放入输出，供传播断言使用。 */
    static NodeHandler passHandler() {
        return new NodeHandler() {
            @Override
            public String type() {
                return "pass";
            }

            @Override
            public NodeOutput execute(NodeInput input) {
                return NodeOutput.success(Map.of("echo", input.workflowInput().getOrDefault("v", "none"),
                        "node", input.nodeId().toString()));
            }
        };
    }

    /** 业务失败节点：结构化 errorCode。 */
    static NodeHandler failHandler() {
        return new NodeHandler() {
            @Override
            public String type() {
                return "fail";
            }

            @Override
            public NodeOutput execute(NodeInput input) {
                return NodeOutput.failed("BUSINESS_FAIL", "业务校验未通过");
            }
        };
    }

    /** 前 N 次抛异常之后成功的节点：验证重试。 */
    static NodeHandler flakyHandler(int failuresBeforeSuccess) {
        AtomicInteger calls = new AtomicInteger();
        return new NodeHandler() {
            @Override
            public String type() {
                return "flaky";
            }

            @Override
            public NodeOutput execute(NodeInput input) {
                if (calls.incrementAndGet() <= failuresBeforeSuccess) {
                    throw new IllegalStateException("暂时性故障");
                }
                return NodeOutput.success(Map.of("calls", calls.get()));
            }
        };
    }

    /** 长时间睡眠节点：验证超时。 */
    static NodeHandler sleepHandler(long millis) {
        return new NodeHandler() {
            @Override
            public String type() {
                return "sleepy";
            }

            @Override
            public NodeOutput execute(NodeInput input) throws InterruptedException {
                Thread.sleep(millis);
                return NodeOutput.success(Map.of());
            }
        };
    }

    NodeTypeRegistry registry(NodeHandler... handlers) {
        NodeTypeRegistry registry = new NodeTypeRegistry();
        registry.register(passHandler());
        registry.register(failHandler());
        for (NodeHandler h : handlers) {
            registry.register(h);
        }
        return registry;
    }

    NodeContext context(NodeTypeRegistry registry, RunBudget budget, AtomicBoolean cancelled) {
        ToolRegistry tools = new ToolRegistry();
        return new NodeContext(events, taskId, runId, tools,
                new com.chinazhouwy.miniharness.runtime.tool.ToolInvoker(tools, events, executor),
                new NoopModelPort(), budget,
                cancelled == null ? () -> false : cancelled::get, executor, new HashMap<>());
    }

    @Test
    @DisplayName("编译器：合法 DAG 编译出拓扑序")
    void compilesValidDag() {
        NodeTypeRegistry registry = registry();
        WorkflowCompiler compiler = new WorkflowCompiler(registry);
        WorkflowDefinition def = new WorkflowDefinition(ref, "三节点链",
                List.of(NodeSpec.of("a", "pass"), NodeSpec.of("b", "pass"), NodeSpec.of("c", "pass")),
                List.of(WorkflowEdge.of("a", "b"), WorkflowEdge.of("b", "c")));
        CompiledWorkflow compiled = compiler.compile(def);
        assertEquals(List.of(new NodeId("a"), new NodeId("b"), new NodeId("c")), compiled.topoOrder());
    }

    @Test
    @DisplayName("编译器：环依赖与悬空边被拒绝")
    void rejectsCycleAndDanglingEdge() {
        WorkflowCompiler compiler = new WorkflowCompiler(registry());
        WorkflowDefinition cyclic = new WorkflowDefinition(ref, "环",
                List.of(NodeSpec.of("a", "pass"), NodeSpec.of("b", "pass")),
                List.of(WorkflowEdge.of("a", "b"), WorkflowEdge.of("b", "a")));
        HarnessException e1 = assertThrows(HarnessException.class, () -> compiler.compile(cyclic));
        assertEquals("WORKFLOW_CYCLIC", e1.errorCode());

        WorkflowDefinition dangling = new WorkflowDefinition(ref, "悬空边",
                List.of(NodeSpec.of("a", "pass")),
                List.of(WorkflowEdge.of("a", "ghost")));
        HarnessException e2 = assertThrows(HarnessException.class, () -> compiler.compile(dangling));
        assertEquals("WORKFLOW_INVALID", e2.errorCode());

        WorkflowDefinition unknownType = new WorkflowDefinition(ref, "未知类型",
                List.of(NodeSpec.of("a", "mystery")), List.of());
        HarnessException e3 = assertThrows(HarnessException.class, () -> compiler.compile(unknownType));
        assertEquals("HANDLER_NOT_FOUND", e3.errorCode());
    }

    @Test
    @DisplayName("执行器：成功路径产出完整节点结果与事件序列")
    void executesSuccessPath() {
        NodeTypeRegistry registry = registry();
        WorkflowCompiler compiler = new WorkflowCompiler(registry);
        WorkflowDefinition def = new WorkflowDefinition(ref, "链",
                List.of(NodeSpec.of("a", "pass"), NodeSpec.of("b", "pass")),
                List.of(WorkflowEdge.of("a", "b")));
        EventBus.Collector collector = new EventBus.Collector();
        events.subscribe(collector);
        WorkflowExecutionResult result = new WorkflowExecutor(registry)
                .execute(compiler.compile(def), ref, taskId, runId, Map.of("v", "x"),
                        context(registry, RunBudget.unlimited(), null));

        assertEquals(RunStatus.SUCCEEDED, result.outcome());
        assertEquals(2, result.nodeResults().size());
        assertEquals(NodeStatus.SUCCEEDED, result.nodeResults().get(1).status());

        List<EventType> types = collector.ofRun(runId.value()).stream().map(e -> e.type()).toList();
        assertTrue(types.contains(EventType.NODE_STARTED));
        assertTrue(types.indexOf(EventType.NODE_STARTED) < types.indexOf(EventType.NODE_SUCCEEDED));
        // 事件均携带 workflow 版本与 taskId/runId。
        assertNotNull(collector.ofRun(runId.value()).get(0).taskId());
        assertEquals(ref, collector.ofRun(runId.value()).get(0).workflow());
    }

    @Test
    @DisplayName("执行器：节点业务失败 → 下游跳过 → Run 失败")
    void propagatesFailure() {
        NodeTypeRegistry registry = registry();
        WorkflowCompiler compiler = new WorkflowCompiler(registry);
        WorkflowDefinition def = new WorkflowDefinition(ref, "失败链",
                List.of(NodeSpec.of("a", "fail"), NodeSpec.of("b", "pass")),
                List.of(WorkflowEdge.of("a", "b")));
        WorkflowExecutionResult result = new WorkflowExecutor(registry)
                .execute(compiler.compile(def), ref, taskId, runId, Map.of(),
                        context(registry, RunBudget.unlimited(), null));
        assertEquals(RunStatus.FAILED, result.outcome());
        assertEquals("BUSINESS_FAIL", result.errorCode());
        assertEquals(NodeStatus.FAILED, result.byNode().get(new NodeId("a")).status());
        assertEquals(NodeStatus.SKIPPED, result.byNode().get(new NodeId("b")).status());
        assertNotNull(result.firstFailed());
    }

    @Test
    @DisplayName("执行器：瞬时异常按重试策略恢复")
    void retriesTransientFailure() {
        NodeTypeRegistry registry = registry(flakyHandler(1));
        WorkflowCompiler compiler = new WorkflowCompiler(registry);
        WorkflowDefinition def = new WorkflowDefinition(ref, "重试",
                List.of(NodeSpec.of("a", "pass"), NodeSpec.of("flaky1", "flaky", Map.of(), 1_000, 3)),
                List.of());
        EventBus.Collector collector = new EventBus.Collector();
        events.subscribe(collector);
        WorkflowExecutionResult result = new WorkflowExecutor(registry)
                .execute(compiler.compile(def), ref, taskId, runId, Map.of(),
                        context(registry, RunBudget.unlimited(), null));
        assertEquals(RunStatus.SUCCEEDED, result.outcome());
        assertEquals(2, result.byNode().get(new NodeId("flaky1")).attempts());
        assertTrue(collector.ofRun(runId.value()).stream()
                .anyMatch(e -> e.type() == EventType.NODE_RETRYING));
    }

    @Test
    @DisplayName("执行器：节点超时导致失败")
    void timesOutSlowNode() {
        NodeTypeRegistry registry = registry(sleepHandler(2_000));
        WorkflowCompiler compiler = new WorkflowCompiler(registry);
        WorkflowDefinition def = new WorkflowDefinition(ref, "超时",
                List.of(NodeSpec.of("slow", "sleepy", Map.of(), 100, 1)), List.of());
        WorkflowExecutionResult result = new WorkflowExecutor(registry)
                .execute(compiler.compile(def), ref, taskId, runId, Map.of(),
                        context(registry, RunBudget.unlimited(), null));
        assertEquals(RunStatus.FAILED, result.outcome());
        assertEquals(NodeStatus.FAILED, result.byNode().get(new NodeId("slow")).status());
        assertTrue(result.byNode().get(new NodeId("slow")).errorMessage().contains("超时"));
    }

    @Test
    @DisplayName("执行器：时长预算耗尽 → TIMED_OUT，剩余节点跳过")
    void enforcesDurationBudget() {
        NodeTypeRegistry registry = registry(sleepHandler(300));
        WorkflowCompiler compiler = new WorkflowCompiler(registry);
        WorkflowDefinition def = new WorkflowDefinition(ref, "预算",
                List.of(NodeSpec.of("slow", "sleepy", Map.of(), 5_000, 1), NodeSpec.of("b", "pass")),
                List.of(WorkflowEdge.of("slow", "b")));
        WorkflowExecutionResult result = new WorkflowExecutor(registry)
                .execute(compiler.compile(def), ref, taskId, runId, Map.of(),
                        context(registry, new RunBudget(10, 10, 100), null));
        assertEquals(RunStatus.TIMED_OUT, result.outcome());
        assertEquals("DURATION_BUDGET_EXCEEDED", result.errorCode());
        assertEquals(NodeStatus.SKIPPED, result.byNode().get(new NodeId("b")).status());
    }

    @Test
    @DisplayName("执行器：取消信号 → CANCELLED，未执行节点跳过")
    void cancelsRun() {
        NodeTypeRegistry registry = registry();
        WorkflowCompiler compiler = new WorkflowCompiler(registry);
        WorkflowDefinition def = new WorkflowDefinition(ref, "取消",
                List.of(NodeSpec.of("a", "pass"), NodeSpec.of("b", "pass")),
                List.of(WorkflowEdge.of("a", "b")));
        AtomicBoolean cancelled = new AtomicBoolean(true);
        WorkflowExecutionResult result = new WorkflowExecutor(registry)
                .execute(compiler.compile(def), ref, taskId, runId, Map.of(),
                        context(registry, RunBudget.unlimited(), cancelled));
        assertEquals(RunStatus.CANCELLED, result.outcome());
        assertEquals(NodeStatus.SKIPPED, result.byNode().get(new NodeId("b")).status());
    }

    @Test
    @DisplayName("执行器：分支 DAG 中一个分支失败不影响已完成的兄弟分支结果")
    void branchFailureKeepsSiblingResults() {
        NodeTypeRegistry registry = registry();
        WorkflowCompiler compiler = new WorkflowCompiler(registry);
        // a → b(失败) / a → c(成功)：c 已在拓扑序中先或后执行，结果都应保留。
        WorkflowDefinition def = new WorkflowDefinition(ref, "分支",
                List.of(NodeSpec.of("a", "pass"), NodeSpec.of("c", "pass"), NodeSpec.of("b", "fail")),
                List.of(WorkflowEdge.of("a", "c"), WorkflowEdge.of("a", "b"), WorkflowEdge.of("c", "b")));
        WorkflowExecutionResult result = new WorkflowExecutor(registry)
                .execute(compiler.compile(def), ref, taskId, runId, Map.of(),
                        context(registry, RunBudget.unlimited(), null));
        assertEquals(RunStatus.FAILED, result.outcome());
        assertEquals(NodeStatus.SUCCEEDED, result.byNode().get(new NodeId("c")).status());
    }

    @Test
    @DisplayName("无模型模式：节点内模型调用显式失败，绝不静默成功")
    void noopModelRefuses() {
        NoopModelPort port = new NoopModelPort();
        assertThrows(HarnessException.class,
                () -> port.complete(new com.chinazhouwy.miniharness.runtime.model.ModelRequest("s", "u", Map.of())));
    }
}
