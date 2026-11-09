package com.chinazhouwy.miniharness.agentscope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Harness 区第一专题：架构总览——"裸引擎 vs 成品车"。
 *
 * <p>裸引擎（ReActAgent）零注入：给什么用什么，不给就啥都没有。
 * 成品车（HarnessAgent）出厂预装一整车：文件工具全家桶、shell、web 抓取/搜索、
 * memory 三件套、subagent、skill、plan 模式……
 *
 * <p>本 demo 从三个角度盘点这台车：代码注册表、模型自报、磁盘产物。
 */
public class HarnessTest {

    private static OpenAIChatModel model;

    @BeforeAll
    static void before() throws IOException {
        Properties props = new Properties();
        try (InputStream in = HarnessTest.class.getClassLoader()
                .getResourceAsStream("openrouter.local.properties")) {
            props.load(in);
        }
        model = OpenAIChatModel.builder()
                .apiKey(props.getProperty("openrouter.api.key"))
                .modelName(props.getProperty("openrouter.model"))
                .baseUrl(props.getProperty("openrouter.base.url"))
                .nativeStructuredOutput(false)
                .stream(true)
                .build();
    }

    @Test
    @DisplayName("Harness 3：memory_search 失效定位——绕过模型直接调 WorkspaceManager")
    public void test03MemorySearchProbe() throws IOException {
        Path ws = Path.of("./data/harness-mem");

        try (HarnessAgent agent = HarnessAgent.builder()
                .name("mem-demo").agentId("mem-demo").sysPrompt("you are a helper")
                .model(model)
                .workspace(ws)
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .build()) {

            RuntimeContext rc = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sProbe-" + UUID.randomUUID()).build();

            WorkspaceManager wm = agent.getWorkspaceManager();

            // memory_search 内部干的就是这两步：listMemoryFilePaths 拿清单 → readManagedWorkspaceFileUtf8 逐个读
            List<String> paths = wm.listMemoryFilePaths(rc);
            System.out.println("===== listMemoryFilePaths 返回 " + paths.size() + " 条 =====");
            for (String p : paths) {
                String c = wm.readManagedWorkspaceFileUtf8(rc, p);
                System.out.println("  读 [" + p + "] → " + (c == null || c.isEmpty() ? "空" : "有内容 " + c.length() + " 字符"));
            }

            // 它实际往哪找？对照物理上真实存在的文件
            System.out.println("===== 路径对照 =====");
            System.out.println("  getMemoryDir(rc) = " + wm.getMemoryDir(rc));
            System.out.println("  物理存在 {ws}/zhouwy/MEMORY.md? "
                    + Files.isRegularFile(ws.resolve("zhouwy").resolve("MEMORY.md")));
            System.out.println("  物理存在 {ws}/zhouwy/memory/2026-11-08.md? "
                    + Files.isRegularFile(ws.resolve("zhouwy").resolve("memory").resolve("2026-11-08.md")));

            // 拆开读的两个分支分别测：
            // 分支1 = filesystem 层（readWithOverride 的第一优先），分支2 = workspace 根目录回退
            AbstractFilesystem fs = wm.getFilesystem();
            if (fs != null) {
                ReadResult r = fs.read(rc, "MEMORY.md", 0, 0);
                System.out.println("===== 分支1 filesystem.read(MEMORY.md) =====");
                System.out.println("  isSuccess=" + r.isSuccess() + " 内容长度=" + (r.fileData() != null && r.fileData().content() != null ? r.fileData().content().length() : -1));
            }
            System.out.println("===== 分支2 workspace 根回退 =====");
            System.out.println("  回退读 " + ws.resolve("MEMORY.md").toAbsolutePath() + " 存在? " + Files.isRegularFile(ws.resolve("MEMORY.md")));
        }
    }

    @Test
    @DisplayName("Harness 2：Memory——save 落盘成文件，全新会话靠 search 捞回来")
    public void test02MemoryCycle() throws IOException {
        // 用绝对路径：相对路径 "./x" 会触发 WorkspaceManager 的 startsWith 守卫缺陷
        // （normalize 吃掉 "./" 后与未归一化的 workspace 字段元素对不上，读文件永远被拦）
        Path ws = Path.of("./data/harness-mem").toAbsolutePath().normalize();

        // ---- 会话 1：让模型把一个事实写进长期记忆 ----
        try (HarnessAgent agent = HarnessAgent.builder()
                .name("mem-demo").agentId("mem-demo").sysPrompt("you are a helper")
                .model(model)
                .workspace(ws)
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .middleware(new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput in,
                                                     Function<ActingInput, Flux<AgentEvent>> next) {
                        return next.apply(in)
                                .doFirst(() -> System.out.println("⚙ [" + ctx.getSessionId().substring(0, 8) + "] 工具调用: "
                                        + in.toolCalls().stream()
                                                .map(tc -> tc.getName() + " " + tc.getInput())
                                                .toList()));
                    }
                })
                .build()) {

            RuntimeContext ctx1 = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sMem1-" + UUID.randomUUID()).build();
            Msg ret1 = agent.call(List.of(new UserMessage(
                    "请把这句话存进你的长期记忆：我最喜欢的数字是 42。")), ctx1).block();
            System.out.println("会话1 回答: " + ret1.getTextContent());

            // agent 关闭时才把 memory 异步落盘，所以磁盘检查和第二个会话都在 try 外
        }

        // ---- 磁盘：memory 落成了什么文件 ----
        // 位置是固定约定：{workspace}/{userId}/memory/YYYY-MM-DD.md（按天流水账）+ MEMORY.md（索引）
        System.out.println("===== memory 磁盘文件 =====");
        try (Stream<Path> walk = Files.walk(ws, 5)) {
            walk.filter(p -> p.toString().endsWith(".md"))
                    .sorted()
                    .forEach(p -> {
                        System.out.println("  " + ws.relativize(p));
                        try {
                            Files.readAllLines(p).forEach(l -> System.out.println("    | " + l));
                        } catch (IOException e) {
                            System.out.println("    (读不出来: " + e.getMessage() + ")");
                        }
                    });
        }

        // ---- 探针实验：验证 memory_search 读文件用的是"workspace 根目录"还是"用户目录" ----
        // 真实 memory 文件在 {ws}/zhouwy/memory/（按用户分目录）。
        // 在根目录 {ws}/memory/ 放一个同名探针文件——如果会话 2 的 memory_search
        // 搜到了"根目录探针"字样，就证明它读的是根目录，路径基准错位实锤
        Path rootMemory = ws.resolve("memory");
        Files.createDirectories(rootMemory);
        Files.writeString(rootMemory.resolve("2026-11-08.md"),
                "- 用户最喜欢的数字是 42\n根目录探针：这行来自 workspace 根目录的 memory 文件");
        System.out.println("已放置根目录探针: memory/2026-11-08.md");

        // ---- 会话 2：全新 sessionId = 没有任何聊天历史，同一个 workspace = 记忆还在 ----
        // 关键验证：注意工具调用监控。如果一条工具都没调——
        // 说明答案是系统提示里注入的 MEMORY.md 索引给的，不是它现场查的
        try (HarnessAgent agent2 = HarnessAgent.builder()
                .name("mem-demo").agentId("mem-demo").sysPrompt("you are a helper")
                .model(model)
                .workspace(ws)
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .middleware(new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput in,
                                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
                        return next.apply(in)
                                .doFirst(() -> {
                                    // 直接看这次发给模型的真实请求：有没有注入 memory_context、有没有 42
                                    String dump = in.messages().stream()
                                            .map(m -> m.getRole() + ": " + m.getTextContent())
                                            .collect(Collectors.joining("\n---\n"));
                                    System.out.println("📨 [" + ctx.getSessionId().substring(0, 8)
                                            + "] 本次请求消息数=" + in.messages().size()
                                            + " | 含 <memory_context>=" + dump.contains("memory_context")
                                            + " | 含 42=" + dump.contains("42"));
                                    if (dump.contains("memory_context")) {
                                        int i = dump.indexOf("memory_context");
                                        System.out.println("   ↳ 注入片段: " + dump.substring(i,
                                                Math.min(i + 200, dump.length())).replace("\n", " ⏎ "));
                                    }
                                });
                    }

                    @Override
                    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput in,
                                                     Function<ActingInput, Flux<AgentEvent>> next) {
                        // 每个工具的结果文本是分段增量来的：按调用 id 攒起来，结束时整段打印
                        Map<String, StringBuilder> buffers = new HashMap<>();
                        return next.apply(in)
                                .doFirst(() -> System.out.println("⚙ [" + ctx.getSessionId().substring(0, 8) + "] 工具调用: "
                                        + in.toolCalls().stream()
                                                .map(tc -> tc.getName() + " " + tc.getInput())
                                                .toList()))
                                .doOnNext(ev -> {
                                    if (ev instanceof ToolResultTextDeltaEvent delta) {
                                        buffers.computeIfAbsent(delta.getToolCallId(),
                                                k -> new StringBuilder()).append(delta.getDelta());
                                    } else if (ev instanceof ToolResultEndEvent end) {
                                        String text = buffers.containsKey(end.getToolCallId())
                                                ? buffers.get(end.getToolCallId()).toString() : "";
                                        System.out.println("   ↳ [" + end.getToolCallName() + "] 结果: "
                                                + (text.length() > 150 ? text.substring(0, 150) + "…" : text));
                                    }
                                });
                    }
                })
                .build()) {

            RuntimeContext ctx2 = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sMem2-" + UUID.randomUUID()).build();
            Msg ret2 = agent2.call(List.of(new UserMessage(
                    "我最喜欢的数字是多少？")), ctx2).block();
            System.out.println("会话2（全新会话，无聊天历史）回答: " + ret2.getTextContent());
        }
    }

    @Test
    @DisplayName("Harness 1：成品车盘点——预装工具清单 + 模型自报 + 磁盘产物")
    public void test01Inventory() throws IOException {
        Path ws = Path.of("./data/harness-inv");

        // 什么都不 disable：让 harness 把预装的全装上。
        // 对比：PermissionTest 里我们 disableMemoryTools().disableSubagents() 是在"卸配置"；
        // 这里一个都不卸，看原厂状态。
        try (HarnessAgent agent = HarnessAgent.builder()
                .name("inv-demo").agentId("inv-demo").sysPrompt("you are a helper")
                .model(model)
                .workspace(ws)
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .build()) {

            // ---- 视角 1：代码注册表——harness 实际装了哪些工具 ----
            Toolkit toolkit = agent.getToolkit();
            System.out.println("===== 预装工具清单（共 " + toolkit.getToolNames().size() + " 个）=====");
            toolkit.getToolNames().stream().sorted().forEach(n -> System.out.println("  - " + n));

            // ---- 视角 2：模型自报——模型眼里的可用工具（来自发给它的工具 schema）----
            RuntimeContext ctx = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sInv-" + UUID.randomUUID()).build();
            Msg ret = agent.call(List.of(new UserMessage(
                    "列出你现在能调用的所有工具的名字，一行一个，用中文解释下解释。")), ctx).block();
            System.out.println("===== 模型自报 =====");
            System.out.println(ret.getTextContent());

            // agent 关闭后 memory 等异步写入才会落盘，所以磁盘盘点放在 try 外面
        }

        // ---- 视角 3：磁盘产物——跑完之后 workspace 里长出了什么 ----
        System.out.println("===== workspace 磁盘结构 =====");
        try (Stream<Path> walk = Files.walk(ws, 4)) {
            walk.filter(p -> !p.getFileName().toString().equals(".DS_Store"))
                    .sorted()
                    .forEach(p -> System.out.println("  " + ws.relativize(p)));
        }
    }

    @Test
    @DisplayName("Harness 4：Overlay 两层文件系统——上层优先读，写永远落上层")
    public void test04OverlayLayers() throws IOException {
        // 两个目录都放在 data/ 下，不碰项目根目录里的真实文件。
        // workspace 用绝对路径：相对路径会触发 WorkspaceManager 那个 startsWith 守卫缺陷。
        Path ws = Path.of("./data/harness-overlay").toAbsolutePath().normalize();
        Path project = Path.of("./data/harness-overlay-project").toAbsolutePath().normalize();
        Files.createDirectories(ws);
        Files.createDirectories(project);

        // 每次跑之前把上层残留的 probe.txt 删掉。
        // 不清场的话，上次跑留下的文件会让第一次 read 直接命中上层，看不到"回退到下层"。
        try (Stream<Path> walk = Files.walk(ws, 4)) {
            for (Path p : walk.filter(p -> p.getFileName().toString().equals("probe.txt")).toList()) {
                Files.delete(p);
            }
        }

        // 下层（项目层，只读）先放一份同名文件。
        // 故意叫 probe.txt，不用 AGENTS.md——那是框架自己会管的受管文件，H3-2 再讲。
        Files.writeString(project.resolve("probe.txt"), "LOWER：项目层的内容");

        try (HarnessAgent agent = HarnessAgent.builder()
                .name("overlay-demo").agentId("overlay-demo").sysPrompt("you are a helper")
                .model(model)
                .workspace(ws)
                // 把下层指到演示目录。不写这行时，下层默认是项目根 user.dir。
                .filesystem(new LocalFilesystemSpec().project(project))
                .stateStore(new JsonFileAgentStateStore(Path.of("./data/.agentscope-state")))
                .build()) {

            RuntimeContext rc = RuntimeContext.builder()
                    .userId("zhouwy").sessionId("sOverlay-" + UUID.randomUUID()).build();

            // 【新 API 1】getFilesystem() = 拿这个 agent 实际在用的文件系统
            AbstractFilesystem fs = agent.getWorkspaceManager().getFilesystem();
            System.out.println("===== 实际生效的文件系统 =====");
            System.out.println("  " + fs.getClass().getSimpleName());

            // 【新 API 2】upper() / lower() = 把叠在一起的两层拆开看
            // 源码 LocalFilesystemSpec.toFilesystem()：upper=workspace（读写）、lower=project（只读）
            if (fs instanceof OverlayFilesystem ov) {
                System.out.println("  upper = " + ov.upper().getClass().getSimpleName());
                System.out.println("  lower = " + ov.lower().getClass().getSimpleName());
            }

            // 【新 API 3】read(rc, 路径, 0, 0) = 不经过模型直接读
            // 最后两个 0 是起始行 / 读几行；limit=0 在 2.0.4 里表示"读到末尾"，不是读 0 行
            // 【新 API 4】ReadResult.fileData().content() = 从结果里取文本
            ReadResult r1 = fs.read(rc, "probe.txt", 0, 0);
            System.out.println("===== ① 上层没有同名文件 → 读下层的 =====");
            System.out.println("  " + (r1.isSuccess() ? r1.fileData().content() : r1.error()));

            // 【新 API 5】write(rc, 路径, 内容) = 直接写，不走模型
            // OverlayFilesystem.write 源码只有一行 return upper.write(...)，所以永远落上层。
            // 注意：LocalFilesystem.write 拒绝覆盖已存在的文件，同名再写会返回 fail。
            WriteResult w = fs.write(rc, "probe.txt", "UPPER：工作区层的内容");
            System.out.println("===== ② 写一次 =====");
            System.out.println("  成功? " + w.isSuccess() + "   返回路径 = " + w.path()
                    + "   错误 = " + w.error());

            ReadResult r2 = fs.read(rc, "probe.txt", 0, 0);
            System.out.println("===== ③ 再读 → 上层盖住下层 =====");
            System.out.println("  " + (r2.isSuccess() ? r2.fileData().content() : r2.error()));

            System.out.println("===== ④ 磁盘实证 =====");
            System.out.println("  下层 = " + Files.readString(project.resolve("probe.txt")));
            // Files.walk 就是"把这个目录下所有文件列出来"，这里只挑名为 probe.txt 的
            List<Path> upperFiles;
            try (Stream<Path> walk = Files.walk(ws, 4)) {
                upperFiles = walk.filter(p -> p.getFileName().toString().equals("probe.txt")).toList();
            }
            for (Path p : upperFiles) {
                System.out.println("  上层 " + p + " = " + Files.readString(p));
            }
        }
    }
}
