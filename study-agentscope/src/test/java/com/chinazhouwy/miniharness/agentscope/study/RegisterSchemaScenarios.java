package com.chinazhouwy.miniharness.agentscope.study;

import com.chinazhouwy.miniharness.model.ModelConfig;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultMessage;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Flux;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.UUID;
import java.util.function.Function;

/**
 * registerSchema 四种「外部执行方」的完整闭环，一个文件从头跑到尾。
 *
 * <p>主流程只有一条：登记说明书 → 第一次 call 挂起 → 外部执行 → 回填 → 第二次 call 拿答复。
 * 四种场景的差别全部集中在 {@link #executeOutside(Scene, String)} 里，框架侧一行都不改。
 *
 * <p>运行（工作目录设为 study-agentscope 模块目录）：
 * <pre>{@code
 * java -cp "target/test-classes:target/classes:$(cat /tmp/miniharness-cp.txt)" \
 *      com.chinazhouwy.miniharness.agentscope.study.RegisterSchemaScenarios database
 * }</pre>
 * 场景参数：console / database（默认）/ http / approval。
 * IDE 里跑 main 时把场景名填进 Program arguments，配置文件统一放在仓库根目录 config/openrouter.local.properties。
 */
public class RegisterSchemaScenarios {

    /** 外部执行方是谁 */
    enum Scene {
        CONSOLE,    // 人敲一行
        DATABASE,   // 查库
        HTTP,       // 调接口
        APPROVAL    // 等审批结论
    }

    private static OpenAIChatModel model;

    public static void main(String[] args) throws IOException {
        Scene scene;
        if (args.length == 0) {
            scene = Scene.DATABASE;
        } else {
            scene = Scene.valueOf(args[0].trim().toUpperCase());
        }
        initModel();
        run(scene, "SKU-9527");
    }

    /**
     * 初始化模型。JUnit 的 @BeforeAll 只有跑测试时才执行，所以自己写 main 必须调这个，
     * 否则 .model(null) 会让 getModel(ctx) 返回 null 并抛 NPE。
     */
    // JUnit 的 BeforeAll 不会在直接运行 main 时自动执行；因此 main 自己调用这个幂等初始化方法。
    private static void initModel() throws IOException {
        if (model != null) {
            return;
        }
        Properties props = ModelConfig.loadLocalProperties();
        model = OpenAIChatModel.builder()
                .apiKey(props.getProperty("openrouter.api.key"))
                .modelName(props.getProperty("openrouter.model"))
                .baseUrl(props.getProperty("openrouter.base.url"))
                // false 选择框架的非原生结构化输出路径；普通问答仍正常使用该模型配置。
                .nativeStructuredOutput(false)
                // 这里控制模型响应的流式接收；外层 agent.call 仍可聚合为一个最终 Msg，事件流用 streamEvents 观察。
                .stream(true)
                .build();
    }

    // ===================== 完整闭环：这一段四种场景完全共用 =====================

    private static void run(Scene scene, String sku) throws IOException {

        // ① 一份「只有说明书」的工具：名字、描述、参数，没有任何执行代码
        ToolSchema schema = ToolSchema.builder()
                .name("query_inventory")
                .description("查询仓库库存。该工具由外部系统执行，框架内部没有实现。")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of("sku", Map.of("type", "string")),
                        "required", List.of("sku")))
                .build();

        // 每个场景一个独立工作区 + 独立会话，互不干扰
        Path ws = Path.of("./data/schema-" + scene.name().toLowerCase())
                .toAbsolutePath().normalize();
        Files.createDirectories(ws.resolve("zhouwy"));

        RoundLog log = new RoundLog();
        try (HarnessAgent agent = HarnessAgent.builder()
                .name("scenario-demo").agentId("scenario-demo")
                .sysPrompt("你是一个仓库助手。查询库存时必须调用 query_inventory，"
                        + "不要使用其他工具，也不要凭空编造库存数字。")
                .model(model)
                .workspace(ws)
                .registerExternalSchemas(List.of(schema))
                .middleware(log)
                .build()) {

            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy")
                    .sessionId("sSchema-" + scene.name() + "-" + UUID.randomUUID())
                    .build();

            System.out.println("========== 场景：" + scene + " ==========");
            System.out.println("\n===== ① 登记说明书 =====");
            var tool = agent.getToolkit().getTool("query_inventory");
            System.out.println("  【说明书】name=" + schema.getName() + " | " + schema.getDescription());
            System.out.println("  【参数】" + schema.getParameters());
            System.out.println("  【注册结果】getTool="
                    + (tool == null ? "null" : tool.getClass().getSimpleName())
                    + " | isExternalTool=" + agent.getToolkit().isExternalTool("query_inventory"));

            // ② 第一次 call：模型点工具，框架不执行，直接挂起
            log.phase = "第 1 次 call";
            System.out.println("\n===== ② 第一次 call：让模型查 " + sku + " =====");
            Msg suspended = agent.call(List.of(new UserMessage(
                    "查一下 " + sku + " 的库存，把结果告诉我。")), ctx).block();

            System.out.println("  【call 返回】generateReason="
                    + (suspended == null ? "null" : suspended.getGenerateReason()));
            List<ToolUseBlock> uses = suspended == null
                    ? List.of() : suspended.getContentBlocks(ToolUseBlock.class);
            for (ToolUseBlock u : uses) {
                System.out.println("  【挂起的调用】id=" + u.getId()
                        + " | name=" + u.getName() + " | input=" + u.getInput());
            }
            for (ToolResultBlock r : suspended == null
                    ? List.<ToolResultBlock>of() : suspended.getContentBlocks(ToolResultBlock.class)) {
                System.out.println("  【框架给的占位结果】state=" + r.getState()
                        + " | isSuspended=" + r.isSuspended()
                        + " | output=" + (r.getOutput().isEmpty() ? "空（框架没执行）" : r.getOutput()));
            }
            if (uses.isEmpty()) {
                System.out.println("  模型这趟没点这个工具，后面就不回填了。");
                return;
            }
            ToolUseBlock call = uses.get(0);
            Object skuArg = call.getInput() == null ? null : call.getInput().get("sku");
            String skuFromModel = skuArg == null ? sku : String.valueOf(skuArg);

            // ③ 外部执行：这一整段都是普通 Java，框架完全不参与
            System.out.println("\n===== ③ 外部执行（框架外，普通 Java） =====");
            String externalResult = executeOutside(scene, skuFromModel);
            System.out.println("  【交给模型的结果】" + externalResult);

            // ④ 回填：结果挂回同一个 callId，换一个 runId 接着聊
            log.phase = "第 2 次 call";
            System.out.println("\n===== ④ 回填 =====");
            Msg resume = ToolResultMessage.builder()
                    .result(call.getId(), call.getName(), externalResult)
                    .build();
            RuntimeContext ctx2 = RuntimeContext.builder(ctx).runId(null).build();
            System.out.println("  【回填消息】role=" + resume.getRole()
                    + " | 结果挂回调用 id=" + call.getId());
            System.out.println("  【新执行】runId " + ctx.getRunId() + " → " + ctx2.getRunId());

            // ⑤ 第二次 call：模型看到结果，接着答
            System.out.println("\n===== ⑤ 第二次 call：模型接着答 =====");
            Msg answer = agent.call(List.of(resume), ctx2).block();
            System.out.println("  【第二次 call 返回】generateReason="
                    + (answer == null ? "null" : answer.getGenerateReason()));
            System.out.println("  【模型最终答复】"
                    + headTail(answer == null ? "" : answer.getTextContent(), 200));
            // 模型看到不满意的结果时可能再点一次工具，那这趟会再次挂起，得再回填一轮
            if (answer != null && !answer.getContentBlocks(ToolUseBlock.class).isEmpty()) {
                System.out.println("  【注意】这趟又挂起了，模型想再查一次：");
                for (ToolUseBlock u : answer.getContentBlocks(ToolUseBlock.class)) {
                    System.out.println("         " + u.getName() + " " + u.getInput());
                }
                System.out.println("         按上面同样的办法再回填一轮即可。");
            }
        }
    }

    // ============ 唯一有差别的地方：结果字符串从哪来 ============

    private static String executeOutside(Scene scene, String sku) {
        switch (scene) {
            case CONSOLE:
                return fromConsole(sku);
            case DATABASE:
                return fromDatabase(sku);
            case HTTP:
                return fromHttp(sku);
            case APPROVAL:
                return fromApproval(sku);
            default:
                throw new IllegalArgumentException("未知场景：" + scene);
        }
    }

    /** ① 人敲一行：Scanner 读控制台，程序停在这里等 */
    private static String fromConsole(String sku) {
        System.out.println("  【外部动作】等人敲一行 " + sku + " 的库存结果");
        System.out.print("  > ");
        System.out.flush();
        Scanner in = new Scanner(System.in);
        String line = in.hasNextLine() ? in.nextLine() : null;
        String result = line == null || line.isBlank() ? "（没读到输入）" : line.trim();
        System.out.println("  【外部返回】" + result);
        return result;
    }

    /** ② 查库：这里用一张本地 Map 代替你的 DAO / JdbcTemplate / MyBatis */
    private static final Map<String, Integer> STOCK = Map.of(
            "SKU-9527", 42,
            "SKU-1001", 0,
            "SKU-8888", 7);

    private static String fromDatabase(String sku) {
        System.out.println("  【外部动作】SELECT stock FROM inventory WHERE sku = '" + sku + "'");
        Integer count = STOCK.get(sku);
        String result = count == null
                ? "查无此商品：" + sku
                : sku + " 当前库存 " + count + " 件（库存表返回）";
        System.out.println("  【外部返回】" + result);
        return result;
    }

    /**
     * ③ 调接口：为了让 demo 不依赖外部地址，这里临时起一个本地库存服务，
     * 再用 JDK 自带的 HttpClient 真发一次 GET。真实项目把起服务那段换成你的地址即可，
     * HttpClient 这段不变。
     */
    private static String fromHttp(String sku) {
        HttpServer server = null;
        try {
            // 一个最简单的 HTTP 服务：解析 sku 参数，从同一张库存表取数
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            int port = server.getAddress().getPort();
            server.createContext("/stock", exchange -> {
                String raw = exchange.getRequestURI().getRawQuery();
                String gotSku = raw == null || !raw.contains("=")
                        ? "" : URLDecoder.decode(raw.substring(raw.indexOf('=') + 1),
                                StandardCharsets.UTF_8);
                Integer count = STOCK.get(gotSku);
                String body = count == null
                        ? "查无此商品：" + gotSku
                        : gotSku + " 当前库存 " + count + " 件（HTTP 库存服务返回）";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            server.start();

            String url = "http://127.0.0.1:" + port + "/stock?sku="
                    + URLEncoder.encode(sku, StandardCharsets.UTF_8);
            System.out.println("  【外部动作】GET " + url);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString());
            String result = resp.statusCode() == 200
                    ? resp.body()
                    : "接口返回 " + resp.statusCode() + "：" + resp.body();
            System.out.println("  【外部返回】" + headTail(result, 100));
            return result;
        } catch (Exception e) {
            // 查失败也回填一句，让模型知道没查到，而不是干等
            String result = "接口调用失败：" + e;
            System.out.println("  【外部返回】" + result);
            return result;
        } finally {
            if (server != null) {
                server.stop(0);
            }
        }
    }

    /** ④ 审批流：这里用「等一个人敲 y/n」代表审批系统给的结论 */
    private static String fromApproval(String sku) {
        System.out.println("  【外部动作】把 " + sku + " 的出库申请送进审批系统，等结论回来");
        System.out.print("  > 批准吗？(y/n)：");
        System.out.flush();
        Scanner in = new Scanner(System.in);
        String decision = in.hasNextLine() ? in.nextLine().trim() : "";
        String result = "y".equalsIgnoreCase(decision)
                ? sku + " 出库已批准，可以执行"
                : sku + " 出库被拒绝，不要执行";
        System.out.println("  【外部返回】" + result);
        return result;
    }

    // ============ 日志：把每轮请求里完整的消息清单打出来 ============

    private static final class RoundLog implements MiddlewareBase {

        private int round;
        private String phase = "";

        @Override
        public Flux<AgentEvent> onModelCall(Agent a, RuntimeContext ctx, ModelCallInput in,
                                            Function<ModelCallInput, Flux<AgentEvent>> next) {
            int r = ++round;
            String tag = phase;
            return next.apply(in).doFirst(() -> {
                List<Msg> msgs = in.messages();
                System.out.println("\n  ----- " + tag + " / 第 " + r
                        + " 轮请求 | 共 " + msgs.size() + " 条消息 -----");
                for (int i = 0; i < msgs.size(); i++) {
                    Msg m = msgs.get(i);
                    String role = String.valueOf(m.getRole());
                    if (role.contains("SYSTEM")) {
                        String t = m.getTextContent() == null ? "" : m.getTextContent();
                        System.out.println("    " + (i + 1) + ". 【系统提示词】" + t.length()
                                + "字 | " + headTail(t, 50));
                        continue;
                    }
                    System.out.println("    " + (i + 1) + ". [" + role + "]");
                    for (ToolUseBlock u : m.getContentBlocks(ToolUseBlock.class)) {
                        System.out.println("         ToolUse    name=" + u.getName()
                                + " id=" + u.getId() + " input=" + u.getInput());
                    }
                    for (ToolResultBlock res : m.getContentBlocks(ToolResultBlock.class)) {
                        System.out.println("         ToolResult name=" + res.getName()
                                + " id=" + res.getId() + " state=" + res.getState()
                                + " suspended=" + res.isSuspended()
                                + " output=" + headTail(String.valueOf(res.getOutput()), 100));
                    }
                    for (TextBlock t : m.getContentBlocks(TextBlock.class)) {
                        System.out.println("         Text       " + headTail(t.getText(), 100));
                    }
                }
            });
        }
    }

    /** 开头 n 字 + 末尾 n 字，中间 ....；换行压成空格，保证只占一行 */
    private static String headTail(String s, int n) {
        String one = s == null ? "" : s.replace("\n", " ").replace("\r", " ").trim();
        if (one.length() <= n * 2) {
            return one;
        }
        return one.substring(0, n) + " .... " + one.substring(one.length() - n);
    }
}
