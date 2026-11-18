package com.chinazhouwy.miniharness.agentscope.study;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.chinazhouwy.miniharness.model.ModelConfig;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.chinazhouwy.miniharness.agentscope.study.RemoteSkillRepositories.HttpSkillRepository;
import static com.chinazhouwy.miniharness.agentscope.study.RemoteSkillRepositories.NacosConfigSkillRepository;

/** Skills：名称与描述用于发现，正文通过加载工具进入模型，具体操作仍由 Java 工具执行。 */
@Tag("live")
class SkillsStudyTest {

    /**
     * 观察一趟 Agent 执行中“发现技能 → 加载正文 → 按正文调用工具 → 最终回答”的顺序。
     * <p>随机标记仅放在正文；检查每轮实际模型请求能否看到标记，避免把 Java 已读取文件等同于模型已收到正文。
     */
    @Test
    @DisplayName("Skills：先发现描述，按需加载 SKILL.md，再调用 Java 工具生成简报")
    void loadSkillThenUseTool(@TempDir Path directory) throws Exception {
        Path skills = directory.resolve("skills");
        Path skillDir = Files.createDirectories(skills.resolve("ticket-brief"));
        String marker = "SKILL-" + UUID.randomUUID();
        // 这是本测试中 AgentScope 读取的临时技能，不安装到 Codex 的 skills 目录。
        // --- 包围的 YAML 是发现信息；后面的 Markdown 才是按需交给模型的具体步骤。
        Files.writeString(skillDir.resolve("SKILL.md"), skillMarkdown(marker));
        // false 表示这个仓库不提供写入操作；它不是对所有工具和文件访问的统一权限限制。
        try (var repository = new FileSystemSkillRepository(skills, false)) {
            runSkill(repository, directory, marker);
        }
    }

    /** 本机真实 HTTP 往返：名称列表和正文分开返回，后面的技能加载／执行流程与本地目录相同。 */
    @Test
    @DisplayName("HTTP Skills：从临时 HTTP 服务读取目录和 Markdown，再按技能执行工具")
    void loadSkillFromHttp(@TempDir Path directory) throws Exception {
        String marker = "HTTP-SKILL-" + UUID.randomUUID();
        // 只监听本机，由系统分配空闲端口；无需提前部署远端服务。
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/skills", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = switch (path) {
                case "/skills" -> "[\"ticket-brief\"]";
                case "/skills/ticket-brief" -> skillMarkdown(marker);
                default -> "技能不存在";
            };
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", path.equals("/skills")
                    ? "application/json; charset=utf-8" : "text/markdown; charset=utf-8");
            exchange.sendResponseHeaders(path.equals("/skills") || path.equals("/skills/ticket-brief") ? 200 : 404,
                    bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        try (var repository = new HttpSkillRepository(endpoint, "http-demo-team")) {
            runSkill(repository, directory, marker);
        } finally {
            server.stop(0);
            System.out.println("[HTTP 技能服务] 已关闭");
        }
    }

    /** 用配置映射替代 ConfigService.getConfig；只模拟配置读取，Agent 和模型仍执行真实流程。 */
    @Test
    @DisplayName("模拟 Nacos 配置 Skills：按 dataId／group 读取 SKILL.md，不连接 Nacos")
    void loadSkillFromNacosConfigStub(@TempDir Path directory) throws Exception {
        String marker = "CONFIG-SKILL-" + UUID.randomUUID();
        Map<String, String> configs = Map.of("skills.ticket-brief.md", skillMarkdown(marker));
        try (var repository = new NacosConfigSkillRepository("demo-team", "AGENT_SKILLS", List.of("ticket-brief"),
                (dataId, group) -> {
                    System.out.println("[模拟 getConfig] dataId=" + dataId + " group=" + group);
                    return "AGENT_SKILLS".equals(group) ? configs.get(dataId) : null;
                })) {
            runSkill(repository, directory, marker);
        }
    }

    /**
     * 真实 SDK 接线示意：先在对应 namespace／group 发布 skills.ticket-brief.md，再配置地址和认证。
     * <p>默认禁用，不会因为运行整个测试类而连接本机 Nacos；接入的是普通配置中心，不是 Nacos AI Skill ZIP 服务。
     */
    @Test
    @Disabled("仅展示真实 Nacos SDK 接线；准备服务、配置项及认证后再手动启用")
    @DisplayName("Nacos SDK 接入示意：普通配置项作为只读技能仓库（默认不运行）")
    void connectRealNacosConfigExample(@TempDir Path directory) throws Exception {
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, "127.0.0.1:8848");
        properties.setProperty(PropertyKeyConst.NAMESPACE, "demo-team"); // 填 namespace ID，而不是控制台显示名称。
        // 若服务启用认证，请在这里接入实际凭据配置，不要把密码写入代码。
        var configService = NacosFactory.createConfigService(properties);
        try (var repository = new NacosConfigSkillRepository("demo-team", "AGENT_SKILLS", List.of("ticket-brief"),
                (dataId, group) -> configService.getConfig(dataId, group, 5000))) {
            ModelConfig settings = ModelConfig.loadProperties();
            var model = OpenAIChatModel.builder().apiKey(settings.apiKey()).baseUrl(settings.baseUrl())
                    .modelName(settings.model()).stream(true).build();
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new TicketTool());
            try (var agent = ReActAgent.builder().name("nacos-config-skills").model(model).toolkit(toolkit)
                    .sysPrompt("先加载匹配的技能正文，再按步骤查询工单并用中文回答。")
                    .skillRepository(repository).skillWorkDir(directory.resolve("runtime")).build()) {
                System.out.println(agent.call("请按工单简报技能，查询 DEMO-001。").block(Duration.ofMinutes(2)).getTextContent());
            }
        } finally {
            configService.shutDown();
        }
    }

    /** 三种来源共用同一份技能内容，随机标记只出现在正文里。 */
    private String skillMarkdown(String marker) {
        return """
                ---
                name: ticket-brief
                description: 查询工单进展并生成简短简报，用户要求工单状态简报时使用。
                ---
                # 工单简报
                1. 调用 query_ticket，ticketId 使用用户给出的工单编号。
                2. 根据工具实际返回的状态生成简报，不编造处置结果。
                3. 最终回答以【工单简报】开头，包含编号、状态和核验标记：%s
                """.formatted(marker);
    }

    /** 仓库来源改变，模型请求观察、按需加载与工具执行代码保持相同。 */
    private void runSkill(AgentSkillRepository repository, Path directory, String marker) throws Exception {
        ModelConfig settings = ModelConfig.loadProperties();
        var model = OpenAIChatModel.builder().apiKey(settings.apiKey()).baseUrl(settings.baseUrl())
                .modelName(settings.model()).nativeStructuredOutput(false).stream(true).build();
        var ticketTool = new TicketTool();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(ticketTool);
        List<Boolean> bodyVisible = new ArrayList<>();
        MiddlewareBase trace = new MiddlewareBase() {
            @Override
            public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext context, ModelCallInput input,
                                               Function<ModelCallInput, Flux<AgentEvent>> next) {
                return Flux.defer(() -> {
                    // 工具结果放在内容块内；序列化整个消息列表检查，不能只看顶层 getTextContent。
                    String messages = JsonUtils.getJsonCodec().toJson(input.messages());
                    boolean visible = messages.contains(marker);
                    bodyVisible.add(visible);
                    System.out.println("[模型请求 " + bodyVisible.size() + "] 看得到技能目录="
                            + messages.contains("ticket-brief") + "，看得到正文标记=" + visible);
                    return next.apply(input);
                });
            }

            @Override
            public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
                                            Function<ActingInput, Flux<AgentEvent>> next) {
                return Flux.defer(() -> {
                    input.toolCalls().forEach(call -> System.out.println("[工具请求] " + call.getName()
                            + " arguments=" + call.getInput()));
                    return next.apply(input);
                });
            }
        };
        try (ReActAgent agent = ReActAgent.builder().name("skills-study").model(model).toolkit(toolkit)
                .sysPrompt("你是工单助手。匹配技能时先加载技能正文，再执行其中的步骤，依据工具结果用中文回答。")
                // 自动安装 DynamicSkillMiddleware，并注册 load_skill_through_path 供模型加载正文／附属资源。
                .skillRepository(repository)
                // 技能物化文件也放在 JUnit 临时目录中；本例不启用脚本执行。
                .skillWorkDir(directory.resolve("runtime"))
                .middleware(trace)
                .build()) {
            var answer = agent.call("请按工单简报技能，查询 DEMO-001 的进展并生成简报。")
                    .block(Duration.ofMinutes(2));
            System.out.println("[最终回答] " + answer.getTextContent());
            // 验证正文后来才进入模型请求，Java 工具确实执行，最终回答也使用了仅存在于技能正文的标记。
            assertTrue(!bodyVisible.getFirst() && bodyVisible.contains(true) && ticketTool.calls > 0
                    && answer.getTextContent().contains(marker), "应先发现、再加载正文并执行工具");
        }
    }

    /** 固定演示数据；Skill 定义步骤，这个 Java 方法提供真正可执行的能力。 */
    public static class TicketTool {
        int calls;

        @Tool(name = "query_ticket", description = "查询演示工单的当前处理状态")
        public String queryTicket(@ToolParam(name = "ticketId", description = "工单编号") String ticketId) {
            calls++;
            String result = "工单编号=" + ticketId + "；当前状态=处理中";
            System.out.println("[Java 工具执行] " + result);
            return result;
        }
    }
}
