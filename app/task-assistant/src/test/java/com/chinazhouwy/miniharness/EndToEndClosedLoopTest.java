package com.chinazhouwy.miniharness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端闭环验证（全部走 HTTP API，不直连引擎）：
 * 用户输入 → 匹配研究任务 → 执行 Workflow v1 → 事件与结果落账 → 产生改进候选
 * → 评测 v1 vs v2 → 拒绝过激候选 → 批准 v2 → 再次执行（行为确实不同）→ 回滚与恢复。
 * 离线模式运行（miniharness.model.mode=offline），不依赖外部模型与行情。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "miniharness.model.mode=offline")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndToEndClosedLoopTest {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private String taskId;
    private String candidateId;
    private String aggressiveCandidateId;

    // ---------- HTTP 小工具 ----------

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(url(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "GET " + path + " → " + response.body());
        return mapper.readTree(response.body());
    }

    private JsonNode post(String path, String jsonBody) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(url(path)))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "POST " + path + " → " + response.body());
        return mapper.readTree(response.body());
    }

    private JsonNode post(String path, Object body) throws Exception {
        return post(path, mapper.writeValueAsString(body));
    }

    // ---------- 断言小工具 ----------

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        assertNotNull(value, "字段缺失: " + field + " in " + node);
        return value.asDouble();
    }

    private JsonNode waitRunTerminal(String runId) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode run = get("/api/runs/" + runId);
            String status = text(run, "status");
            if (!"QUEUED".equals(status) && !"RUNNING".equals(status)) {
                return run;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("等待执行结束超时: " + runId);
    }

    private String startRunAndWait(String taskId, java.util.Map<String, Object> input) throws Exception {
        JsonNode response = post("/api/tasks/" + taskId + "/runs",
                java.util.Map.of("input", input));
        return text(response, "runId");
    }

    private String newResearchTask(String caseId) throws Exception {
        JsonNode response = post("/api/tasks", java.util.Map.of(
                "input", "研究华夏制造的盈利能力，分析利润率变化",
                "metadata", java.util.Map.of("caseId", caseId)));
        assertEquals("NEW_TASK_WITH_WORKFLOW", text(response, "action"));
        return text(response.path("task"), "id");
    }

    // ---------- 闭环步骤 ----------

    @Test
    @Order(1)
    @DisplayName("步骤1: 系统就绪——金融 Domain Pack 已装好，Workflow v1 已发布")
    void systemReady() throws Exception {
        JsonNode info = get("/api/system/info");
        assertEquals("offline", text(info, "modelMode"));
        assertEquals(1, info.path("publishedWorkflows").path("profitability-research").asInt(),
                "v1 应已发布");
    }

    @Test
    @Order(2)
    @DisplayName("步骤2: 用户输入创建研究任务并执行 v1（正常数据 → 正确净利率 0.11）")
    void createTaskAndRunV1() throws Exception {
        taskId = newResearchTask("case-clean");
        assertNotNull(taskId);

        String runId = startRunAndWait(taskId, java.util.Map.of("caseId", "case-clean"));
        JsonNode run = waitRunTerminal(runId);
        assertEquals("SUCCEEDED", text(run, "status"));
        JsonNode output = run.path("output");
        assertEquals(0.11, number(output, "netMarginLatest"), 1e-9);
        assertTrue(output.has("provenance"), "报告带证据链");
        assertTrue(output.path("provenance").size() > 0);
    }

    @Test
    @Order(3)
    @DisplayName("步骤3: 故意构造的失败场景——v1 对单位不一致数据产出错误净利率")
    void v1FailsOnUnitMismatch() throws Exception {
        String runId = startRunAndWait(taskId, java.util.Map.of("caseId", "case-unit-mismatch"));
        JsonNode run = waitRunTerminal(runId);
        assertEquals("SUCCEEDED", text(run, "status"), "v1 无口径校验：执行成功但结果错误");
        double wrongMargin = number(run.path("output"), "netMarginLatest");
        assertNotEquals(0.11, wrongMargin, 1e-6);
        assertTrue(wrongMargin < 0.11, "混入万元行后净利率被拉低: " + wrongMargin);
    }

    @Test
    @Order(4)
    @DisplayName("步骤4: 用户质疑「利润率不对」→ 关联原 Run 并触发核查执行")
    void challengeLinksAndRechecks() throws Exception {
        JsonNode response = post("/api/tasks/" + taskId + "/messages",
                java.util.Map.of("content", "这个利润率好像不对"));
        assertEquals("true", text(response, "linked"));
        String verificationRunId = text(response, "verificationRunId");
        assertNotNull(verificationRunId);
        JsonNode run = waitRunTerminal(verificationRunId);
        assertEquals("SUCCEEDED", text(run, "status"), "v1 复核依旧得到同样（错误）结果");
    }

    @Test
    @Order(5)
    @DisplayName("步骤5: 一键实验——轨迹分析产出 v2 候选(过门禁) + 过激候选(被拦)")
    void prepareEvolutionExperiment() throws Exception {
        JsonNode summary = post("/api/evolution/experiment/prepare", java.util.Map.of());
        assertEquals("true", text(summary, "candidatePassedGate"));
        assertEquals("true", text(summary, "aggressiveFailedGate"));
        candidateId = text(summary, "candidateId");
        aggressiveCandidateId = text(summary, "aggressiveCandidateId");
        assertNotNull(candidateId);
        assertNotNull(aggressiveCandidateId);

        JsonNode baseline = summary.path("candidateReport").path("baselineHoldout");
        JsonNode candidate = summary.path("candidateReport").path("candidateHoldout");
        assertEquals(0.25, number(baseline, "accuracy"), 1e-9, "v1 留出集正确率");
        assertEquals(1.0, number(candidate, "accuracy"), 1e-9, "v2 留出集正确率");
        assertEquals(0.0, number(baseline, "errorDetectionRate"), 1e-9);
        assertEquals(1.0, number(candidate, "errorDetectionRate"), 1e-9);
        assertEquals(0, candidate.path("failedCaseIds").size(), "v2 留出集零回归");
    }

    @Test
    @Order(6)
    @DisplayName("步骤6: 拒绝过激候选 → 发布指针不动；批准 v2 → 发布 v2")
    void decideCandidates() throws Exception {
        JsonNode rejected = post("/api/evolution/candidates/" + aggressiveCandidateId + "/reject",
                java.util.Map.of("approver", "operator-b", "note", "过激规则拒绝正常数据"));
        assertEquals(1, rejected.path("publishedVersion").asInt(), "拒绝不影响发布指针");

        JsonNode approved = post("/api/evolution/candidates/" + candidateId + "/approve",
                java.util.Map.of("approver", "operator-a", "note", "指标达标"));
        assertEquals(2, approved.path("publishedVersion").asInt());
    }

    @Test
    @Order(7)
    @DisplayName("步骤7: 版本差异可查——v1→v2 新增口径验证节点")
    void versionDiffVisible() throws Exception {
        JsonNode diff = get("/api/workflows/profitability-research/diff?fromVersion=1&toVersion=2");
        List<String> added = new java.util.ArrayList<>();
        diff.path("addedNodes").forEach(n -> added.add(n.asText()));
        assertTrue(added.contains("data-quality-validation"), "diff 必须反映真实节点差异: " + added);
    }

    @Test
    @Order(8)
    @DisplayName("步骤8: 再次执行——v2 对同一缺陷输入拒绝（行为确实不同）")
    void v2RejectsDefectiveInput() throws Exception {
        String task = newResearchTask("case-unit-mismatch");
        String runId = startRunAndWait(task, java.util.Map.of());
        JsonNode run = waitRunTerminal(runId);
        assertEquals("FAILED", text(run, "status"));
        assertEquals("UNIT_MISMATCH", text(run, "errorCode"));
        assertTrue(text(run, "errorSummary").contains("单位不一致"));
    }

    @Test
    @Order(9)
    @DisplayName("步骤9: 事件审计完整——从建任务到发布全链路可追溯")
    void eventAuditTrail() throws Exception {
        JsonNode events = get("/api/events");
        assertTrue(events.size() > 20, "事件量应覆盖全流程: " + events.size());
        assertTrue(containsType(events, "TASK_CREATED"));
        assertTrue(containsType(events, "WORKFLOW_SELECTED"));
        assertTrue(containsType(events, "TOOL_REQUESTED"));
        assertTrue(containsType(events, "NODE_SUCCEEDED"));
        assertTrue(containsType(events, "RUN_COMPLETED"));
        assertTrue(containsType(events, "TASK_MESSAGE_ADDED"));
        assertTrue(containsType(events, "WORKFLOW_CANDIDATE_CREATED"));
        assertTrue(containsType(events, "EVALUATION_COMPLETED"));
        assertTrue(containsType(events, "CANDIDATE_REJECTED"));
        assertTrue(containsType(events, "CANDIDATE_APPROVED"));
        assertTrue(containsType(events, "WORKFLOW_PUBLISHED"));
        JsonNode sample = events.get(events.size() - 1);
        assertNotNull(sample.get("seq"));
        assertNotNull(sample.get("occurredAt"));
    }

    @Test
    @Order(10)
    @DisplayName("步骤10: 回滚演示——指针回到 v1 旧行为恢复，再恢复到 v2")
    void rollbackAndRestore() throws Exception {
        JsonNode rolled = post("/api/workflows/profitability-research/rollback",
                java.util.Map.of("targetVersion", 1, "operator", "operator-a"));
        assertEquals(1, rolled.path("publishedVersion").asInt());

        String task = newResearchTask("case-unit-mismatch");
        String runId = startRunAndWait(task, java.util.Map.of());
        assertEquals("SUCCEEDED", text(waitRunTerminal(runId), "status"), "回滚后 v1 行为恢复");

        JsonNode restored = post("/api/workflows/profitability-research/rollback",
                java.util.Map.of("targetVersion", 2, "operator", "operator-a"));
        assertEquals(2, restored.path("publishedVersion").asInt(), "回滚到曾发布过的 v2");
    }

    private boolean containsType(JsonNode events, String type) {
        for (JsonNode event : events) {
            if (type.equals(event.path("type").asText())) {
                return true;
            }
        }
        return false;
    }
}
