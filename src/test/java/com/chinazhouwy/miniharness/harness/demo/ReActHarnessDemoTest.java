package com.chinazhouwy.miniharness.harness.demo;

import com.chinazhouwy.miniharness.harness.domain.ArtifactRef;
import com.chinazhouwy.miniharness.harness.domain.MissionDefinition;
import com.chinazhouwy.miniharness.harness.domain.MissionStatus;
import com.chinazhouwy.miniharness.harness.domain.TaskNode;
import com.chinazhouwy.miniharness.harness.domain.TaskStatus;
import com.chinazhouwy.miniharness.harness.execution.TaskExecutionResult;
import com.chinazhouwy.miniharness.harness.execution.TaskExecutor;
import com.chinazhouwy.miniharness.harness.persistence.FileHarnessStateStore;
import com.chinazhouwy.miniharness.harness.runtime.HarnessOrchestrator;
import com.chinazhouwy.miniharness.harness.runtime.RunStopReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReAct 内循环的最小教学 Demo。直接运行本类或单个 @Test，不启动 Spring、不读取 .env、不访问模型。
 *
 * <p>先读 twoToolsThenFinalAnswer，再读 MiniReActHarness.run，最后读 reactLoopFitsInsideExistingHarness。
 * 这里是手写机制实验，不是 AgentScope 的移植；任务级状态仍由项目原有 Harness 管理。
 */
class ReActHarnessDemoTest {

    private static final String QUESTION = "线程池提交任务后怎么执行？请查询题目与评分标准。";
    private static final String ANSWER = "先尝试核心线程，再入队；队列满后尝试扩容，仍无法接收则拒绝。";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("普通回答：没有工具且文本可见时，一轮结束")
    void directAnswerDoesNotExecuteTools() {
        AtomicInteger executions = new AtomicInteger();
        MiniReActHarness harness = new MiniReActHarness(3, Map.of("unused", args -> {
            executions.incrementAndGet();
            return "不应执行";
        }), call -> true);

        Run result = harness.run(QUESTION, history -> Turn.answer(ANSWER));

        assertEquals(StopReason.FINAL_ANSWER, result.reason());
        assertEquals(ANSWER, result.answer());
        assertEquals(1, result.modelCalls());
        assertEquals(0, executions.get());
        assertEquals(2, result.history().size());
    }

    @Test
    @DisplayName("主线：模型提出两个工具请求，Java 执行，下一轮读取结果后回答")
    void twoToolsThenFinalAnswer() {
        AtomicInteger toolExecutions = new AtomicInteger();
        MiniReActHarness harness = new MiniReActHarness(3, interviewTools(toolExecutions), call -> true);

        Run result = harness.run(QUESTION, twoRoundModel());

        // User -> Assistant(tool_use x2) -> Observation(t1) -> Observation(t2) -> Assistant(text)
        assertEquals(StopReason.FINAL_ANSWER, result.reason());
        assertEquals(ANSWER, result.answer());
        assertEquals(2, result.modelCalls());
        assertEquals(2, toolExecutions.get());
        assertEquals(List.of("t1", "t2"), observations(result.history()).stream()
                .map(ToolResult::callId).toList());
        assertTrue(observations(result.history()).stream().allMatch(r -> r.status() == ToolStatus.SUCCESS));
        assertEquals(5, result.history().size());
        result.trace().forEach(System.out::println);
    }

    @Test
    @DisplayName("工具异常变成 ERROR 观察值：下一轮能看到失败原因")
    void toolFailureBecomesAnObservation() {
        MiniReActHarness harness = new MiniReActHarness(3,
                Map.of("broken", args -> { throw new IllegalStateException("模拟查询超时"); }), call -> true);

        Run result = harness.run(QUESTION, history -> {
            List<ToolResult> results = observations(history);
            if (results.isEmpty()) {
                return Turn.tools(new ToolCall("fail-1", "broken", Map.of()));
            }
            assertEquals(ToolStatus.ERROR, results.getFirst().status());
            assertTrue(results.getFirst().content().contains("模拟查询超时"));
            return Turn.answer("查询失败，本轮无法取得资料。");
        });

        assertEquals(StopReason.FINAL_ANSWER, result.reason());
        assertEquals(2, result.modelCalls());
        assertEquals("fail-1", observations(result.history()).getFirst().callId());
    }

    @Test
    @DisplayName("权限拒绝发生在调用前：工具本体零执行，但仍补齐对应结果")
    void deniedToolDoesNotExecute() {
        AtomicInteger writes = new AtomicInteger();
        MiniReActHarness harness = new MiniReActHarness(2, Map.of("writeFile", args -> {
            writes.incrementAndGet();
            return "已写入";
        }), call -> !call.name().equals("writeFile"));

        Run result = harness.run(QUESTION, history -> observations(history).isEmpty()
                ? Turn.tools(new ToolCall("deny-1", "writeFile", Map.of()))
                : Turn.answer("写操作被拒绝，改为说明步骤。"));

        assertEquals(0, writes.get());
        assertEquals(ToolStatus.DENIED, observations(result.history()).getFirst().status());
        assertEquals("deny-1", observations(result.history()).getFirst().callId());
        assertEquals(StopReason.FINAL_ANSWER, result.reason());
    }

    @Test
    @DisplayName("Java 参数校验与未知工具：生成 ERROR 结果，不伪装成成功")
    void invalidArgumentsAndUnknownToolBecomeErrorResults() {
        AtomicInteger executions = new AtomicInteger();
        MiniReActHarness harness = new MiniReActHarness(2, interviewTools(executions), call -> true);

        Run result = harness.run(QUESTION, history -> observations(history).isEmpty()
                ? Turn.tools(new ToolCall("bad-1", "loadQuestion", Map.of()),
                        new ToolCall("bad-2", "notRegistered", Map.of()))
                : Turn.answer("两个请求均失败，请更正参数和工具名。"));

        assertEquals(0, executions.get());
        assertEquals(List.of(ToolStatus.ERROR, ToolStatus.ERROR), observations(result.history()).stream()
                .map(ToolResult::status).toList());
        assertTrue(observations(result.history()).get(0).content().contains("code 必填"));
        assertTrue(observations(result.history()).get(1).content().contains("工具未注册"));
    }

    @Test
    @DisplayName("模型一直请求工具：到达推理轮数上限，明确返回 MAX_ITERATIONS")
    void iterationLimitDoesNotPretendToBeAFinalAnswer() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolExecutions = new AtomicInteger();
        MiniReActHarness harness = new MiniReActHarness(2, interviewTools(toolExecutions), call -> true);

        Run result = harness.run(QUESTION, history -> Turn.tools(new ToolCall(
                "loop-" + modelCalls.incrementAndGet(), "loadQuestion", Map.of("code", "thread-pool"))));

        assertEquals(StopReason.MAX_ITERATIONS, result.reason());
        assertEquals("", result.answer());
        assertEquals(2, result.modelCalls());
        assertEquals(2, toolExecutions.get());
        assertEquals(2, observations(result.history()).size());
        // Demo 直接报告预算耗尽；AgentScope v2.0.4 的普通路径还会尝试额外 summary 调用。
    }

    @Test
    @DisplayName("空白且无工具不等于最终答案：继续推理，但仍有预算约束")
    void blankResponseContinuesUntilBudgetIsExhausted() {
        Run result = new MiniReActHarness(2, Map.of(), call -> true)
                .run(QUESTION, history -> Turn.answer("  "));

        assertEquals(StopReason.MAX_ITERATIONS, result.reason());
        assertEquals(2, result.modelCalls());
        assertTrue(observations(result.history()).isEmpty());
    }

    @Test
    @DisplayName("同批重复工具 ID 在执行前拒绝，避免无法配对的结果")
    void duplicateCallIdsFailBeforeAnyToolRuns() {
        AtomicInteger executions = new AtomicInteger();
        MiniReActHarness harness = new MiniReActHarness(2, interviewTools(executions), call -> true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> harness.run(QUESTION, history -> Turn.tools(
                        new ToolCall("same-id", "loadQuestion", Map.of("code", "thread-pool")),
                        new ToolCall("same-id", "loadRubric", Map.of("code", "thread-pool")))));

        assertTrue(error.getMessage().contains("重复"));
        assertEquals(0, executions.get());
        assertThrows(IllegalArgumentException.class,
                () -> new MiniReActHarness(0, Map.of(), call -> true));
    }

    @Test
    @DisplayName("接入已有 Harness：任务内部跑 ReAct，任务间用快照恢复，不重做已验收任务")
    void reactLoopFitsInsideExistingHarness() throws IOException {
        AtomicInteger answerExecutions = new AtomicInteger();
        AtomicInteger toolExecutions = new AtomicInteger();
        TaskExecutor executor = context -> {
            if (context.task().id().equals("answer")) {
                answerExecutions.incrementAndGet();
                Run run = new MiniReActHarness(3, interviewTools(toolExecutions), call -> true)
                        .run(QUESTION, twoRoundModel());
                if (run.reason() != StopReason.FINAL_ANSWER) {
                    return TaskExecutionResult.failure("ReAct 未完成", run.reason().name(), run.trace());
                }
                return artifactResult("answer", run.answer(), run.trace());
            }
            assertTrue(context.artifacts().stream().anyMatch(a -> a.id().equals("answer")));
            return artifactResult("report", "基于已保存答案生成报告：" + ANSWER, List.of("input=answer"));
        };
        MissionDefinition definition = new MissionDefinition("react-demo", "用工具查资料并形成报告",
                List.of("答案文件和报告文件存在"), List.of(
                        new TaskNode("answer", "ReAct 查询与回答", List.of(), TaskStatus.PENDING, 1, "", ""),
                        new TaskNode("report", "形成报告", List.of("answer"), TaskStatus.PENDING, 1, "", "")));
        Path stateDir = tempDir.resolve("state");
        HarnessOrchestrator first = new HarnessOrchestrator(
                new FileHarnessStateStore(stateDir), executor, new DemoTaskVerifier());
        first.createMission(definition);
        var checkpoint = first.advanceOneStep("react-demo").snapshot();
        assertEquals(TaskStatus.ACCEPTED, checkpoint.task("answer").status());
        assertEquals(TaskStatus.READY, checkpoint.task("report").status());

        // 丢弃 Store/Orchestrator 实例，重新从磁盘快照加载；不是恢复 Java 栈或 ReAct 中间一步。
        HarnessOrchestrator restarted = new HarnessOrchestrator(
                new FileHarnessStateStore(stateDir), executor, new DemoTaskVerifier());
        assertEquals(TaskStatus.ACCEPTED, restarted.resume("react-demo").task("answer").status());
        var completed = restarted.runUntilPause("react-demo", 5);

        assertEquals(RunStopReason.COMPLETED, completed.stopReason());
        assertEquals(MissionStatus.SUCCEEDED, completed.snapshot().mission().status());
        assertEquals(1, answerExecutions.get());
        assertEquals(2, toolExecutions.get());
        assertEquals(1, completed.snapshot().attemptsFor("answer").size());
        assertEquals(ANSWER, Files.readString(tempDir.resolve("answer.txt")));
        assertTrue(Files.readString(tempDir.resolve("report.txt")).contains(ANSWER));
        System.out.println("[HARNESS] answer=ACCEPTED -> checkpoint -> restart -> report=ACCEPTED");
        System.out.println("[HARNESS] answerExecutions=1, toolExecutions=2, mission=SUCCEEDED");
    }

    private TaskExecutionResult artifactResult(String id, String content, List<String> evidence) {
        try {
            Path file = Files.writeString(tempDir.resolve(id + ".txt"), content);
            return TaskExecutionResult.success(content, evidence,
                    List.of(new ArtifactRef(id, "text/plain", file.toString(), "本次测试生成的真实文本文件")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Function<Map<String, String>, String>> interviewTools(AtomicInteger executions) {
        return Map.of(
                "loadQuestion", args -> {
                    requireCode(args); // 校验在业务计数前；不是 JSON Schema 校验器。
                    executions.incrementAndGet();
                    return "题目：线程池提交一个任务后的完整执行流程是什么？";
                },
                "loadRubric", args -> {
                    requireCode(args);
                    executions.incrementAndGet();
                    return "评分点：核心线程 -> 队列 -> 非核心线程 -> 拒绝策略";
                });
    }

    private static void requireCode(Map<String, String> args) {
        String code = args.get("code");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code 必填");
        }
        if (!code.equals("thread-pool")) {
            throw new IllegalArgumentException("不支持的 code: " + code);
        }
    }

    private static Model twoRoundModel() {
        return history -> {
            List<ToolResult> results = observations(history);
            if (results.isEmpty()) {
                assertEquals(new User(QUESTION), history.getFirst());
                return Turn.tools(new ToolCall("t1", "loadQuestion", Map.of("code", "thread-pool")),
                        new ToolCall("t2", "loadRubric", Map.of("code", "thread-pool")));
            }
            // 关键断言：第二次“模型调用”确实拿到了两个结果，而不是只打印了工具日志。
            assertEquals(4, history.size());
            assertEquals(List.of("t1", "t2"), results.stream().map(ToolResult::callId).toList());
            assertTrue(results.get(0).content().contains("题目"));
            assertTrue(results.get(1).content().contains("拒绝策略"));
            return Turn.answer(ANSWER);
        };
    }

    private static List<ToolResult> observations(List<Message> history) {
        return history.stream().filter(Observation.class::isInstance).map(Observation.class::cast)
                .map(Observation::result).toList();
    }

    /** 从这里开始是可逐行断点的小型 ReAct 内循环；全部只用于本测试。 */
    private record MiniReActHarness(
            int maxIterations,
            Map<String, Function<Map<String, String>, String>> tools,
            Predicate<ToolCall> allowed
    ) {
        MiniReActHarness {
            if (maxIterations < 1) throw new IllegalArgumentException("maxIterations 必须大于 0");
            tools = Map.copyOf(tools);
        }

        Run run(String prompt, Model model) {
            List<Message> history = new ArrayList<>();
            List<String> trace = new ArrayList<>();
            Set<String> callIds = new HashSet<>();
            history.add(new User(prompt));
            for (int iteration = 0; iteration < maxIterations; iteration++) {
                trace.add("[REASONING] iteration=" + iteration + ", messages=" + history.size());
                // Reasoning：传不可变快照，模型只能返回建议，不能直接修改运行时历史。
                Turn response = model.generate(List.copyOf(history));
                Set<String> batchIds = new HashSet<>();
                for (ToolCall call : response.calls()) {
                    if (callIds.contains(call.id()) || !batchIds.add(call.id())) {
                        throw new IllegalArgumentException("重复的 tool_call ID: " + call.id());
                    }
                }
                callIds.addAll(batchIds);
                history.add(new Assistant(response));
                if (response.calls().isEmpty()) {
                    if (response.text() != null && !response.text().isBlank()) {
                        trace.add("[FINISH] reason=FINAL_ANSWER");
                        return new Run(StopReason.FINAL_ANSWER, response.text(), iteration + 1, history, trace);
                    }
                    trace.add("[EMPTY] 没有可见答案，继续下一轮");
                    continue;
                }
                // Acting -> Observe：逐个执行、按 ID 写回，整个批次完成才到下一轮 Reasoning。
                for (ToolCall call : response.calls()) {
                    ToolResult result = execute(call);
                    history.add(new Observation(result));
                    trace.add("[ACTING] id=" + call.id() + ", tool=" + call.name()
                            + ", status=" + result.status() + ", output=" + result.content());
                }
            }
            trace.add("[FINISH] reason=MAX_ITERATIONS");
            return new Run(StopReason.MAX_ITERATIONS, "", maxIterations, history, trace);
        }

        private ToolResult execute(ToolCall call) {
            if (!allowed.test(call)) {
                return new ToolResult(call.id(), call.name(), ToolStatus.DENIED, "工具被拒绝，未执行");
            }
            Function<Map<String, String>, String> tool = tools.get(call.name());
            if (tool == null) {
                return new ToolResult(call.id(), call.name(), ToolStatus.ERROR, "工具未注册: " + call.name());
            }
            try {
                return new ToolResult(call.id(), call.name(), ToolStatus.SUCCESS, tool.apply(call.arguments()));
            } catch (Exception e) {
                return new ToolResult(call.id(), call.name(), ToolStatus.ERROR,
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    @FunctionalInterface
    private interface Model {
        Turn generate(List<Message> history);
    }

    private sealed interface Message permits User, Assistant, Observation { }
    private record User(String text) implements Message { }
    private record Assistant(Turn turn) implements Message { }
    private record Observation(ToolResult result) implements Message { }
    private record ToolCall(String id, String name, Map<String, String> arguments) {
        ToolCall { arguments = Map.copyOf(arguments); }
    }
    private record Turn(String text, List<ToolCall> calls) {
        Turn { calls = List.copyOf(calls); }
        static Turn answer(String text) { return new Turn(text, List.of()); }
        static Turn tools(ToolCall... calls) { return new Turn("", List.of(calls)); }
    }
    private enum ToolStatus { SUCCESS, ERROR, DENIED }
    private enum StopReason { FINAL_ANSWER, MAX_ITERATIONS }
    private record ToolResult(String callId, String tool, ToolStatus status, String content) { }
    private record Run(StopReason reason, String answer, int modelCalls,
                       List<Message> history, List<String> trace) {
        Run { history = List.copyOf(history); trace = List.copyOf(trace); }
    }
}
