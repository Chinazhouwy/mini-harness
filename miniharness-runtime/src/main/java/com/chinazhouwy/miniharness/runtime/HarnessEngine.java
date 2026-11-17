package com.chinazhouwy.miniharness.runtime;

import com.chinazhouwy.miniharness.runtime.capability.DecisionModel;
import com.chinazhouwy.miniharness.runtime.capability.IntentRouter;
import com.chinazhouwy.miniharness.runtime.capability.ModelBasedDecisionModel;
import com.chinazhouwy.miniharness.runtime.capability.RoutingAction;
import com.chinazhouwy.miniharness.runtime.capability.RoutingDecision;
import com.chinazhouwy.miniharness.runtime.core.HarnessException;
import com.chinazhouwy.miniharness.runtime.core.NodeStatus;
import com.chinazhouwy.miniharness.runtime.core.RunBudget;
import com.chinazhouwy.miniharness.runtime.core.RunId;
import com.chinazhouwy.miniharness.runtime.core.RunStatus;
import com.chinazhouwy.miniharness.runtime.core.SessionId;
import com.chinazhouwy.miniharness.runtime.core.TaskId;
import com.chinazhouwy.miniharness.runtime.core.TaskStatus;
import com.chinazhouwy.miniharness.runtime.core.WorkflowRef;
import com.chinazhouwy.miniharness.runtime.evolution.AnalyzerPolicy;
import com.chinazhouwy.miniharness.runtime.evolution.ApprovalService;
import com.chinazhouwy.miniharness.runtime.evolution.CandidateStore;
import com.chinazhouwy.miniharness.runtime.evolution.CaseRunner;
import com.chinazhouwy.miniharness.runtime.evolution.EvalCaseSet;
import com.chinazhouwy.miniharness.runtime.evolution.EvalGatePolicy;
import com.chinazhouwy.miniharness.runtime.evolution.EvalReport;
import com.chinazhouwy.miniharness.runtime.evolution.ExperienceRecord;
import com.chinazhouwy.miniharness.runtime.evolution.ExperienceStore;
import com.chinazhouwy.miniharness.runtime.evolution.FailureKinds;
import com.chinazhouwy.miniharness.runtime.evolution.InMemoryExperienceStore;
import com.chinazhouwy.miniharness.runtime.evolution.TraceAnalyzer;
import com.chinazhouwy.miniharness.runtime.evolution.WorkflowCandidate;
import com.chinazhouwy.miniharness.runtime.evolution.WorkflowEvaluator;
import com.chinazhouwy.miniharness.runtime.event.EventBus;
import com.chinazhouwy.miniharness.runtime.event.EventType;
import com.chinazhouwy.miniharness.runtime.event.HarnessEvent;
import com.chinazhouwy.miniharness.runtime.model.ModelPort;
import com.chinazhouwy.miniharness.runtime.model.NoopModelPort;
import com.chinazhouwy.miniharness.runtime.skill.Skill;
import com.chinazhouwy.miniharness.runtime.skill.SkillStore;
import com.chinazhouwy.miniharness.runtime.task.InMemoryRunStore;
import com.chinazhouwy.miniharness.runtime.task.InMemoryTaskStore;
import com.chinazhouwy.miniharness.runtime.task.Run;
import com.chinazhouwy.miniharness.runtime.task.RunStore;
import com.chinazhouwy.miniharness.runtime.task.Session;
import com.chinazhouwy.miniharness.runtime.task.Task;
import com.chinazhouwy.miniharness.runtime.task.TaskMessage;
import com.chinazhouwy.miniharness.runtime.task.TaskStore;
import com.chinazhouwy.miniharness.runtime.tool.ToolRegistry;
import com.chinazhouwy.miniharness.runtime.workflow.NodeContext;
import com.chinazhouwy.miniharness.runtime.workflow.NodeHandler;
import com.chinazhouwy.miniharness.runtime.workflow.NodeResult;
import com.chinazhouwy.miniharness.runtime.workflow.NodeTypeRegistry;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowCompiler;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowDefinition;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowExecutor;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowExecutionResult;
import com.chinazhouwy.miniharness.runtime.workflow.WorkflowRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HarnessEngine：MiniHarness 的运行时门面，组装全部引擎组件并对外提供业务入口。
 *
 * 设计取舍：
 * - 单体内核、组件可替换（仓储/模型/决策模型均为接口）。
 * - Run 执行走独立线程池（异步），测试用 awaitRun 同步等待；取消通过 Harness 侧标志位实现。
 * - 每次真实执行结束自动写入经验存储；用户质疑会向被核查 Run 的经验追加 WRONG_RESULT 标注，
 *   但缺陷类型的归因只能来自受控案例的复现测试——分析器不做无证据的因果推断。
 */
public class HarnessEngine {

    /** 引擎装配参数。 */
    public record EngineConfig(
            ModelPort modelPort,
            DecisionModel decisionModel, // 可为 null
            int maxModelCallsPerRun,
            int maxToolCallsPerRun,
            long maxRunDurationMs
    ) {
        public static EngineConfig offline() {
            return new EngineConfig(new NoopModelPort(), null, 20, 200, 60_000);
        }
    }

    /** 一次用户输入的处置结果（Chat 与任务创建共用）。 */
    public record UserInputResult(RoutingDecision decision, Task task, String reply) {
    }

    private final EngineConfig config;
    private final EventBus events = new EventBus();
    private final TaskStore taskStore = new InMemoryTaskStore();
    private final RunStore runStore = new InMemoryRunStore();
    private final SkillStore skillStore = new SkillStore();
    private final NodeTypeRegistry nodeRegistry = new NodeTypeRegistry();
    private final ToolRegistry toolRegistry = new ToolRegistry();
    private final WorkflowRegistry workflowRegistry = new WorkflowRegistry(events);
    private final WorkflowCompiler compiler = new WorkflowCompiler(nodeRegistry);
    private final WorkflowExecutor executor = new WorkflowExecutor(nodeRegistry);
    private final ExecutorService runPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "harness-run-pool");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService nodePool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "harness-node");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Run>> inflight = new ConcurrentHashMap<>();

    private final IntentRouter router;
    private final ExperienceStore experienceStore = new InMemoryExperienceStore();
    private final CandidateStore candidateStore = new CandidateStore();
    private final CaseRunner caseRunner;
    private final TraceAnalyzer traceAnalyzer;
    private final WorkflowEvaluator evaluator;
    private final ApprovalService approvalService;

    public HarnessEngine(EngineConfig config, AnalyzerPolicy analyzerPolicy, EvalGatePolicy gatePolicy) {
        this.config = config;
        this.router = new IntentRouter(taskStore, workflowRegistry, skillStore, config.decisionModel());
        this.caseRunner = new CaseRunner(nodeRegistry, toolRegistry, config.modelPort(), events, compiler);
        this.traceAnalyzer = new TraceAnalyzer(analyzerPolicy, compiler);
        this.evaluator = new WorkflowEvaluator(caseRunner, gatePolicy, events);
        this.approvalService = new ApprovalService(candidateStore, workflowRegistry, skillStore, evaluator, events);
    }

    // ---------- 装配 ----------

    public HarnessEngine registerNodeHandler(NodeHandler handler) {
        nodeRegistry.register(handler);
        return this;
    }

    public HarnessEngine registerTool(com.chinazhouwy.miniharness.runtime.tool.ToolSpec spec) {
        toolRegistry.register(spec);
        return this;
    }

    // ---------- 用户输入路由 ----------

    public RoutingDecision route(String userInput) {
        RoutingDecision decision = router.resolve(userInput);
        events.publish(HarnessEvent.builder(EventType.ROUTING_DECIDED)
                .task(decision.taskId())
                .message("路由决策: " + decision.action() + " (" + decision.resolvedBy() + ")")
                .payload(Map.of(
                        "action", decision.action().name(),
                        "intent", decision.intent() == null ? "" : decision.intent(),
                        "company", decision.company() == null ? "" : decision.company(),
                        "resolvedBy", decision.resolvedBy())));
        return decision;
    }

    /** 统一入口：路由 + 建任务/追问。不自动执行 Run。 */
    public UserInputResult handleUserInput(String userInput, SessionId sessionId) {
        RoutingDecision decision = route(userInput);
        switch (decision.action()) {
            case NEW_TASK_WITH_WORKFLOW, NEW_TASK_WITH_SKILL -> {
                Task task = createTaskInternal(userInput, sessionId, decision);
                return new UserInputResult(decision, task,
                        "已创建任务 " + task.id() + "（" + decision.reason() + "）");
            }
            case CONTINUE_TASK -> {
                Task task = requireTask(decision.taskId());
                task.addMessage(TaskMessage.user(userInput));
                task.transitionTo(TaskStatus.ACTIVE);
                events.publish(HarnessEvent.builder(EventType.TASK_MESSAGE_ADDED)
                        .task(task.id())
                        .message("任务追加信息并继续核查")
                        .payload(Map.of("intent", String.valueOf(decision.intent()))));
                return new UserInputResult(decision, task,
                        "已关联任务 " + task.id() + "，将重新核查该任务的结果");
            }
            case CLARIFY_TASK_LINK -> {
                // 歧义时不擅自建任务/合并历史，只把消息留在会话里等待确认。
                session(sessionId).addMessage(TaskMessage.user(userInput));
                return new UserInputResult(decision, null, decision.question());
            }
            case ANSWER_DIRECTLY, AGENT_EXPLORE -> {
                session(sessionId).addMessage(TaskMessage.user(userInput));
                String reply = "当前未匹配到可复用能力。"
                        + (decision.reason() == null ? "" : decision.reason());
                return new UserInputResult(decision, null, reply);
            }
            default -> {
                return new UserInputResult(decision, null, "路由未覆盖: " + decision.action());
            }
        }
    }

    private Task createTaskInternal(String userInput, SessionId sessionId, RoutingDecision decision) {
        Task task = new Task(TaskId.random(), userInput, sessionId, Instant.now());
        task.attachIntent(decision.intent(), decision.company());
        task.putMetadata("routingAction", decision.action().name());
        if (decision.workflow() != null) {
            task.putMetadata("workflow", decision.workflow().toString());
        }
        taskStore.save(task);
        session(sessionId).attachTask(task.id());
        events.publish(HarnessEvent.builder(EventType.TASK_CREATED)
                .task(task.id())
                .message("创建任务: " + task.title())
                .payload(Map.of("intent", String.valueOf(decision.intent()),
                        "company", String.valueOf(decision.company()))));
        return task;
    }

    public Task completeTask(TaskId taskId, String note) {
        Task task = requireTask(taskId);
        task.transitionTo(TaskStatus.COMPLETED);
        events.publish(HarnessEvent.builder(EventType.TASK_STATUS_CHANGED)
                .task(taskId).message("任务完成: " + note));
        return task;
    }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private Session session(SessionId sessionId) {
        return sessions.computeIfAbsent(sessionId.value(),
                k -> new Session(sessionId, Instant.now()));
    }

    public Session sessionById(String sessionIdValue) {
        return sessions.get(sessionIdValue);
    }

    // ---------- Run 执行 ----------

    /** 启动一次执行：workflow 为空则按任务意图匹配已发布版本。 */
    public RunId startRun(TaskId taskId, WorkflowRef explicitWorkflow, String triggeredBy) {
        Task task = requireTask(taskId);
        WorkflowDefinition definition = explicitWorkflow != null
                ? workflowRegistry.find(explicitWorkflow.name(), explicitWorkflow.version())
                .orElseThrow(() -> new HarnessException("WORKFLOW_NOT_FOUND", "Workflow 不存在: " + explicitWorkflow))
                : publishedWorkflowForIntent(task.intent());

        RunId runId = RunId.random();
        Run run = new Run(runId, taskId, definition.ref(), triggeredBy, Instant.now(),
                Map.of("company", task.company() == null ? "" : task.company(),
                        "userQuery", task.userQuery()));
        runStore.save(run);
        task.linkRun(runId);
        if (task.status() == TaskStatus.OPEN) {
            task.transitionTo(TaskStatus.ACTIVE);
        }
        events.publish(HarnessEvent.builder(EventType.TASK_LINKED_RUN)
                .task(taskId).run(runId).workflow(definition.ref())
                .message("任务关联执行 " + runId + "，触发方式 " + triggeredBy));
        events.publish(HarnessEvent.builder(EventType.RUN_QUEUED)
                .task(taskId).run(runId).workflow(definition.ref())
                .message("执行排队: " + definition.ref()));
        events.publish(HarnessEvent.builder(EventType.WORKFLOW_SELECTED)
                .task(taskId).run(runId).workflow(definition.ref())
                .message("选定 Workflow " + definition.ref() + " 执行任务"));

        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancelFlags.put(runId.value(), cancelled);
        CompletableFuture<Run> future = new CompletableFuture<>();
        inflight.put(runId.value(), future);
        Run runRef = run;
        runPool.submit(() -> executeRun(runRef, task, definition, cancelled, triggeredBy, future));
        return runId;
    }

    private void executeRun(Run run, Task task, WorkflowDefinition definition,
                            AtomicBoolean cancelled, String triggeredBy, CompletableFuture<Run> future) {
        try {
            run.transitionTo(RunStatus.RUNNING);
            events.publish(HarnessEvent.builder(EventType.RUN_STARTED)
                    .task(task.id()).run(run.id()).workflow(definition.ref())
                    .message("执行开始: " + definition.ref()));

            RunBudget budget = new RunBudget(config.maxModelCallsPerRun(),
                    config.maxToolCallsPerRun(), config.maxRunDurationMs());
            NodeContext context = new NodeContext(events, toolRegistry, config.modelPort(), budget,
                    cancelled::get, nodePool, new LinkedHashMap<>());
            Map<String, Object> workflowInput = new LinkedHashMap<>(run.input());
            if (workflowInput.get("company") == null || String.valueOf(workflowInput.get("company")).isBlank()) {
                workflowInput.put("company", task.company() == null ? "" : task.company());
            }
            WorkflowExecutionResult result = executor.execute(
                    compiler.compile(definition), definition.ref(), task.id(), run.id(),
                    workflowInput, context);

            Map<String, Object> output = new LinkedHashMap<>();
            for (NodeResult nodeResult : result.nodeResults()) {
                run.appendNodeResult(nodeResult);
                if (nodeResult.status() == NodeStatus.SUCCEEDED && nodeResult.output() != null) {
                    output.putAll(nodeResult.output());
                }
            }
            run.recordOutput(output);
            switch (result.outcome()) {
                case SUCCEEDED -> run.transitionTo(RunStatus.SUCCEEDED);
                case FAILED -> {
                    run.recordError(result.errorCode(), result.errorSummary());
                    run.transitionTo(RunStatus.FAILED);
                }
                case TIMED_OUT -> {
                    run.recordError(result.errorCode(), result.errorSummary());
                    run.transitionTo(RunStatus.TIMED_OUT);
                }
                case CANCELLED -> run.transitionTo(RunStatus.CANCELLED);
                default -> throw new HarnessException("RUN_STATE", "未知执行结局: " + result.outcome());
            }
            publishRunTerminalEvent(run, definition, budget);
            recordExperience(run, definition, triggeredBy);
        } catch (Exception e) {
            run.recordError("RUN_INTERNAL_ERROR", String.valueOf(e.getMessage()));
            try {
                run.transitionTo(RunStatus.FAILED);
            } catch (IllegalStateException ignored) {
                // 状态机兜底：异常路径下若已终态则忽略。
            }
            events.publish(HarnessEvent.builder(EventType.RUN_FAILED)
                    .task(task.id()).run(run.id()).workflow(definition.ref())
                    .message("执行内部错误: " + e.getMessage()));
            recordExperience(run, definition, triggeredBy);
        } finally {
            CompletableFuture<Run> pending = inflight.remove(run.id().value());
            if (pending != null) {
                pending.complete(run);
            }
        }
    }

    private void publishRunTerminalEvent(Run run, WorkflowDefinition definition, RunBudget budget) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("durationMs", budget.elapsedMs());
        payload.put("modelCalls", budget.modelCalls());
        payload.put("toolCalls", budget.toolCalls());
        EventType type = switch (run.status()) {
            case SUCCEEDED -> EventType.RUN_COMPLETED;
            case FAILED -> EventType.RUN_FAILED;
            case TIMED_OUT -> EventType.RUN_TIMED_OUT;
            case CANCELLED -> EventType.RUN_CANCELLED;
            default -> EventType.RUN_FAILED;
        };
        events.publish(HarnessEvent.builder(type)
                .task(run.taskId()).run(run.id()).workflow(definition.ref())
                .message("执行结束: " + run.status() + (run.errorSummary() == null ? "" : " — " + run.errorSummary()))
                .payload(payload));
    }

    /** 执行结束自动沉淀经验记录。 */
    private void recordExperience(Run run, WorkflowDefinition definition, String triggeredBy) {
        List<ExperienceRecord.NodeSummary> nodes = new ArrayList<>();
        for (NodeResult nodeResult : run.nodeResults()) {
            nodes.add(new ExperienceRecord.NodeSummary(nodeResult.nodeId().value(),
                    nodeResult.status(), nodeResult.errorCode(), nodeResult.durationMs()));
        }
        List<ExperienceRecord.FailureAnnotation> annotations = new ArrayList<>();
        if (run.errorCode() != null && FailureKinds.DATA_QUALITY_KINDS.contains(run.errorCode())) {
            String failedNode = null;
            for (NodeResult nodeResult : run.nodeResults()) {
                if (nodeResult.status() == NodeStatus.FAILED) {
                    failedNode = nodeResult.nodeId().value();
                }
            }
            annotations.add(new ExperienceRecord.FailureAnnotation(run.errorCode(), failedNode,
                    "Run " + run.id() + " 失败: " + run.errorSummary()));
        }
        ExperienceRecord record = new ExperienceRecord(run.id(), run.taskId(), definition.ref(),
                triggeredBy, run.status(), run.errorCode(), run.errorSummary(), nodes,
                run.output(), annotations, Instant.now());
        experienceStore.append(record);
        events.publish(HarnessEvent.builder(EventType.EXPERIENCE_RECORDED)
                .task(run.taskId()).run(run.id()).workflow(definition.ref())
                .message("经验已记录（Run " + run.id() + "）"));
    }

    /** 用户质疑：关联被核查 Run，追加 WRONG_RESULT 标注（缺陷类型归因仍需受控案例复现）。 */
    public synchronized Run challengeResult(TaskId taskId, String content, RunId linkedRunId) {
        Task task = requireTask(taskId);
        task.addMessage(TaskMessage.user(content, linkedRunId));
        events.publish(HarnessEvent.builder(EventType.TASK_MESSAGE_ADDED)
                .task(taskId).run(linkedRunId)
                .message("用户质疑结果，关联 Run " + linkedRunId));
        if (linkedRunId != null) {
            experienceStore.annotate(linkedRunId.value(), new ExperienceRecord.FailureAnnotation(
                    FailureKinds.WRONG_RESULT, null,
                    "用户质疑 Run " + linkedRunId + " 的结果正确性: " + content));
        }
        return runStore.find(linkedRunId).orElse(null);
    }

    /** 等待执行完成（测试与端到端验证使用）。 */
    public Run awaitRun(RunId runId, long timeoutMs) {
        CompletableFuture<Run> future = inflight.get(runId.value());
        if (future == null) {
            return runStore.find(runId).orElseThrow(
                    () -> new HarnessException("RUN_NOT_FOUND", "执行不存在: " + runId));
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new HarnessException("RUN_WAIT_TIMEOUT", "等待执行超时: " + runId);
        } catch (Exception e) {
            throw new HarnessException("RUN_WAIT_FAILED", "等待执行失败: " + e.getMessage(), e);
        }
    }

    /** Harness 侧取消（路由、权限、状态由 Harness 管控的体现之一）。 */
    public boolean cancelRun(RunId runId) {
        AtomicBoolean flag = cancelFlags.get(runId.value());
        if (flag == null) {
            return false;
        }
        flag.set(true);
        return true;
    }

    // ---------- 受控案例经验采集 ----------

    /** 在受控案例上运行指定 Workflow 并沉淀经验（v1 缺陷轨迹的来源）。 */
    public List<ExperienceRecord> collectExperiencesFromCases(String workflowName, int version,
                                                              EvalCaseSet caseSet) {
        WorkflowDefinition definition = workflowRegistry.find(workflowName, version)
                .orElseThrow(() -> new HarnessException("WORKFLOW_NOT_FOUND",
                        "Workflow 不存在: " + workflowName + " v" + version));
        List<ExperienceRecord> records = new ArrayList<>();
        for (var evalCase : caseSet.all()) {
            CaseRunner.CaseRunResult result = caseRunner.run(definition, evalCase);
            ExperienceRecord record = caseRunner.toExperience(result, definition);
            experienceStore.append(record);
            records.add(record);
            events.publish(HarnessEvent.builder(EventType.EXPERIENCE_RECORDED)
                    .workflow(definition.ref())
                    .message("受控案例经验已记录: " + evalCase.caseId() + " → "
                            + (result.passed() ? "符合期望" : "未达期望"))
                    .payload(Map.of("caseId", evalCase.caseId(),
                            "passed", result.passed())));
        }
        return records;
    }

    // ---------- 自进化入口 ----------

    public TraceAnalyzer.AnalysisReport analyzeAndPropose(String workflowName) {
        TraceAnalyzer.AnalysisReport report = traceAnalyzer.analyze(workflowName,
                experienceStore, workflowRegistry, candidateStore,
                skillName -> skillStore.existsByName(skillName));
        for (WorkflowCandidate candidate : report.workflowCandidates()) {
            candidateStore.save(candidate);
            events.publish(HarnessEvent.builder(EventType.WORKFLOW_CANDIDATE_CREATED)
                    .workflow(candidate.baseRef())
                    .message("产生 Workflow 候选 " + candidate.id() + "（规则 " + candidate.rule() + "）")
                    .payload(Map.of("candidateId", candidate.id(),
                            "rule", candidate.rule().name(),
                            "sourceRuns", String.join(",", candidate.sourceRunIds()))));
        }
        for (Skill skill : report.skillProposals()) {
            skillStore.save(skill);
            events.publish(HarnessEvent.builder(EventType.SKILL_CANDIDATE_CREATED)
                    .message("产生 Skill 提案 " + skill.id())
                    .payload(Map.of("skillId", skill.id(), "intent", skill.triggerIntent())));
        }
        return report;
    }

    public EvalReport evaluateCandidate(String candidateId, EvalCaseSet caseSet) {
        return approvalService.evaluateCandidate(candidateId, caseSet);
    }

    public WorkflowDefinition approveCandidate(String candidateId, String approver, String note) {
        return approvalService.approve(candidateId, approver, note);
    }

    public WorkflowCandidate rejectCandidate(String candidateId, String approver, String note) {
        return approvalService.reject(candidateId, approver, note);
    }

    public WorkflowDefinition rollbackWorkflow(String workflowName, int targetVersion, String approver) {
        return approvalService.rollback(workflowName, targetVersion, approver);
    }

    // ---------- 访问器 ----------

    public EventBus events() {
        return events;
    }

    public TaskStore tasks() {
        return taskStore;
    }

    public RunStore runs() {
        return runStore;
    }

    public SkillStore skills() {
        return skillStore;
    }

    public CandidateStore candidates() {
        return candidateStore;
    }

    public ExperienceStore experiences() {
        return experienceStore;
    }

    public WorkflowRegistry workflows() {
        return workflowRegistry;
    }

    public NodeTypeRegistry nodeRegistry() {
        return nodeRegistry;
    }

    public ToolRegistry tools() {
        return toolRegistry;
    }

    private Task requireTask(TaskId taskId) {
        return taskStore.find(taskId).orElseThrow(
                () -> new HarnessException("TASK_NOT_FOUND", "任务不存在: " + taskId));
    }

    private WorkflowDefinition publishedWorkflowForIntent(String intent) {
        if (intent == null) {
            throw new HarnessException("WORKFLOW_NOT_MATCHED", "任务缺少意图，无法匹配 Workflow");
        }
        for (String name : workflowRegistry.knownWorkflowNames()) {
            Optional<WorkflowDefinition> published = workflowRegistry.published(name);
            if (published.isPresent() && intent.equals(published.get().intent())) {
                return published.get();
            }
        }
        throw new HarnessException("WORKFLOW_NOT_MATCHED",
                "没有已发布 Workflow 匹配意图: " + intent);
    }
}
