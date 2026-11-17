package com.chinazhouwy.miniharness;

import com.chinazhouwy.miniharness.model.ModelConfig;

import com.chinazhouwy.miniharness.model.ModelCreator;
import com.chinazhouwy.miniharness.tool.FileSearchTool;
import com.chinazhouwy.miniharness.tool.ToolCofig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolCallLimitBehavior;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** Spring AI 功能学习入口。每个方法独立观察一个能力；类级 live 标签使 Maven 默认跳过真实模型示例。部分方法故意演示失败，请按方法运行并对照 HTTP 日志。 */
@org.junit.jupiter.api.Tag("live")
class HarnessCoreDemo {

    public static class FailingReportTool {
        private int calls;

        @Tool(name = "load_report", description = "读取学习报告，报告未生成时会失败")
        public String loadReport() {
            calls++;
            System.out.println("[工具实际执行] load_report 即将抛异常");
            throw new IllegalStateException("演示：报告尚未生成");
        }
    }

    /**
     * 对照工具异常交给模型解释和抛给 Java 调用方两条路径。
     * <p>关键点：alwaysThrow=false 将受处理的工具异常变成工具结果；true 则传播 ToolExecutionException。
     * <p>观察：对照两次请求日志、最终回答和 catch 输出；这里的报告工具始终模拟失败。
     */
    @Test
    @DisplayName("工具异常策略：回填模型或抛给调用方")
    void chatWithToolFailure() throws Exception {
        OpenAiChatModel model = new ModelCreator().getModel();
        for (boolean alwaysThrow : List.of(false, true)) {
            System.out.println("\n[工具异常策略] alwaysThrow=" + alwaysThrow);
            var manager = ToolCallingManager.builder()
                    // 这里替换的是工具执行异常策略；模型请求本身的网络异常不经过这个处理器。
                    .toolExecutionExceptionProcessor(new DefaultToolExecutionExceptionProcessor(alwaysThrow))
                    .build();
            ChatClient client = ChatClient.builder(model)
                    .defaultAdvisors(ToolCallingAdvisor.builder().toolCallingManager(manager).build())
                    .build();
            FailingReportTool tool = new FailingReportTool();
            try {
                String answer = client.prompt()
                        .system("请调用工具；若工具返回失败信息，简短如实解释，不要再次调用或编造报告。")
                        .user("请调用 load_report 读取我的学习报告。")
                        .tools(tool)
                        .call()
                        .content();
                System.out.println("[模型最终回答] " + answer);
            } catch (ToolExecutionException exception) {
                if (!alwaysThrow) {
                    throw exception;
                }
                System.out.println("[Java 调用方收到异常] " + exception.getClass().getSimpleName());
            }
            System.out.println("[工具执行次数] " + tool.calls);
        }
    }

    /**
     * 用自定义转换器扩展模型输出契约。
     * <p>关键点：转换器提供提示格式、Schema 和 Java 转换；validateSchema 增加独立的 Schema 校验。
     * <p>观察：先看 minutes 的范围规则，再看模型返回值；Java 解析成功不等于业务值都合法。
     */
    @Test
    @DisplayName("自定义输出转换器：生成范围约束并解析对象")
    void chatWithCustomOutputConverter() throws Exception {
        var converter = new CustomOutputExamples.TaskConverter();
        System.out.println("[自定义转换器生成的 Schema]\n" + converter.getJsonSchema());
        // validateSchema 使用同一个转换器提供的 Schema 校验；不能只依赖 convert()。
        var task = ChatClient.create(new ModelCreator().getModel()).prompt()
                .user("提取任务：复习 Java，30 分钟。")
                .call()
                // converter 的 Schema 用于格式提示与校验，convert 用于转 Java；validateSchema 不是调用所有 Java 校验注解。
                .entity(converter, spec -> spec.validateSchema())
                ;
        System.out.println("[自定义转换器结果] " + task);
    }

    /**
     * 把自定义 Java 注解翻译成 JSON Schema。
     * <p>关键点：自定义规则由本例的 victools resolver 读取；outputJsonSchema 负责校验，system 显式把规则发给模型。
     * <p>观察：核对 Schema 的 minimum/maximum 和最终对象；不要把自定义注解当成框架自动支持。
     */
    @Test
    @DisplayName("自定义范围注解：生成 Schema 并校验输出")
    void chatWithCustomSchemaGenerator() throws Exception {
        String schema = CustomOutputExamples.generateSchema(CustomOutputExamples.AnnotatedTask.class);
        System.out.println("[自定义注解生成的 Schema]\n" + schema);
        var validation = StructuredOutputValidationAdvisor.builder()
                                                        .outputJsonSchema(schema)
                                                        .maxRepeatAttempts(2)
                                                        .build();
        String json = ChatClient.builder(new ModelCreator().getModel())
                                    .defaultAdvisors(validation)
                                    .build()
                                    .prompt()
                                    // outputJsonSchema 负责校验；这里显式把这份自定义规则发给模型。
                                    .system("只输出符合以下 Schema 的 JSON 对象，不要代码围栏：\n" + schema)
                                    .user("提取任务：复习 Java，30 分钟。")
                                    .call()
                                    .content();
        var task = new BeanOutputConverter<>(CustomOutputExamples.AnnotatedTask.class).convert(json);
        System.out.println("[自定义注解结果] " + task);
    }

    record StudyTask(String name, int minutes, String priority) {}

    /**
     * 对照提示词约束与模型服务原生结构化输出。
     * <p>关键点：useProviderStructuredOutput 改变请求协议；能否使用 json_schema 由实际模型服务决定。
     * <p>观察：对照 HTTP 请求体；本例保留过不支持原生格式的失败路径，服务能力应以当次结果为准。
     */
    @Test
    @DisplayName("对照提示词与原生 Schema 输出（当前服务不支持原生格式）")
    void chatWithNativeStructuredOutput() throws Exception {
        // 当前配置的服务实测不支持 json_schema，第二次请求会返回 HTTP 400。
        // 保留错误用于观察能力边界；换成支持原生 Schema 的服务后可再次运行。
        ChatClient client = ChatClient.create(new ModelCreator().getModel());
        for (boolean nativeOutput : List.of(false, true)) {
            System.out.println("\n[结构化输出模式] " + (nativeOutput ? "模型接口 Schema" : "提示词 Schema"));
            StudyTask task = client.prompt()
                    .system("提取学习任务，priority 使用 HIGH、MEDIUM 或 LOW。")
                    .user("复习 Java 并发，30 分钟，优先级高。")
                    .call()
                    .entity(StudyTask.class, spec -> {
                        // 对照 HTTP 请求：默认把 Schema 放入提示词；原生方式使用 response_format。
                        // 原生方式需要模型服务支持；成功返回本身不等于验证了所有 Schema 约束。
                        if (nativeOutput) {
                            spec.useProviderStructuredOutput();
                        }
                    });
            System.out.println("[解析结果] " + task);
        }
    }

    /**
     * 观察 Schema 校验失败后如何请求模型修正。
     * <p>关键点：outputType 根据类型与受支持的注解生成规则；maxRepeatAttempts=2 表示最多追加两次修正请求。
     * <p>观察：数 HTTP 请求并查看错误反馈；最终还会调用 record 构造校验，其异常不会自动再请求模型。
     */
    @Test
    @DisplayName("结构化输出校验失败后，反馈错误并重新生成")
    void chatWithStructuredOutputValidation() throws Exception {
        // 从类型和 @Schema 注解生成规则，不分析构造方法中的 if。
        var validation = StructuredOutputValidationAdvisor.builder()
                // outputType 从类型生成校验 Schema；校验失败反馈给模型的内容来自 Advisor，不来自 record 构造异常。
                .outputType(ValidatedStudyTask.class)
                .maxRepeatAttempts(2) // 首次请求 + 最多两次修正请求
                .build();
        ChatClient client = ChatClient.builder(new ModelCreator().getModel())
                .defaultAdvisors(validation).build();

        // 教学用：主动要求首次返回非法值，便于观察真实模型收到校验错误后修正。
        // 具体重试次数以 HTTP 请求日志为准，模型不保证每次都按首次要求输出。
        String json = client.prompt()
                .system("只输出 JSON 对象，不要 Markdown 代码围栏或解释。")
                .user("""
                        首次请原样输出：{"name":"复习 Java","minutes":-30,"priority":"HIGH"}
                        如果请求中出现 Output JSON validation failed，请根据错误修正 JSON，
                        将负数时长改成对应的正数，其他字段保持不变。
                        """)
                .call().content();
        System.out.println("[校验流程返回的 JSON] " + json);

        // 2.0.2 中重试耗尽仍可能返回不合规文本，因此保留最终业务校验。
        // 构造失败会抛给调用方，这一步不会再触发模型重试。
        ValidatedStudyTask task = new BeanOutputConverter<>(ValidatedStudyTask.class).convert(json);
        System.out.println("[最终业务对象] " + task);
    }

    // @Schema 属于 OpenAPI 注解；当前 Schema 生成器读取它形成描述和约束，构造器中的 if 是另一层 Java 业务校验。
    record ValidatedStudyTask(
            @Schema(description = "任务名", minLength = 1) String name,
            @Schema(description = "学习时长（分钟）", minimum = "1", maximum = "180") int minutes,
            @Schema(allowableValues = {"HIGH", "MEDIUM", "LOW"}) String priority) {
        // JSON 类型正确，不代表业务数据有效；构造对象时明确校验边界。
        ValidatedStudyTask {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("任务名不能为空");
            }
            if (minutes < 1 || minutes > 180) {
                throw new IllegalArgumentException("学习时长必须在 1～180 分钟之间");
            }
            if (priority == null || !List.of("HIGH", "MEDIUM", "LOW").contains(priority)) {
                throw new IllegalArgumentException("优先级必须为 HIGH、MEDIUM 或 LOW");
            }
        }
    }

    /**
     * 离线区分类型正确与业务值正确。
     * <p>关键点：普通 record 可以接收负时长；ValidatedStudyTask 的紧凑构造器显式检查范围与枚举值。
     * <p>观察：非法对象可创建，但受校验的对象构造失败；本方法不请求模型。
     */
    @Test
    @DisplayName("Java 对象类型正确，仍需业务字段校验")
    void structuredOutputNeedsBusinessValidation() {
        // 用固定非法数据展示边界，避免依赖模型恰好生成错误结果；本例不请求模型。
        StudyTask raw = new StudyTask("复习 Java", -30, "HIGH");
        System.out.println("类型正确的对象，仍可能包含非法值：" + raw);
        var error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ValidatedStudyTask(raw.name(), raw.minutes(), raw.priority()));
        System.out.println("业务校验拦截：" + error.getMessage());
    }

    /**
     * 把模型文本结果映射为 Java record。
     * <p>关键点：entity(StudyTask.class) 组织格式要求并完成反序列化；此处没有创建真实学习任务。
     * <p>观察：查看 HTTP 提示词和三个字段；模型给出的值仍需由业务自行核验。
     */
    @Test
    @DisplayName("将模型响应转换为学习任务 record")
    void chatWithStructuredOutput() throws Exception {
        ChatClient client = ChatClient.create(new ModelCreator().getModel());

        // entity() 根据目标 Java 类型生成格式要求，并将模型响应转换为对象。
        // 本例只提取任务信息，没有实际创建或执行任务。
        StudyTask task = client.prompt()
                .system("提取学习任务，priority 使用 HIGH、MEDIUM 或 LOW。")
                .user("我要复习 Java 并发，预计用 30 分钟，优先级高。")
                .call()
                .entity(StudyTask.class);

        System.out.println("任务名：" + task.name());
        System.out.println("分钟数：" + task.minutes());
        System.out.println("优先级：" + task.priority());
    }

    public static class CounterTool {
        private int calls;

        @Tool(name = "ping", description = "执行一次连通性检查，返回 pong")
        public String ping() {
            calls++;
            return "pong";
        }
    }

    /**
     * 对照是否由 ChatClient 自动组织工具循环。
     * <p>关键点：关闭 toolCallingAdvisorAutoRegister 仍可发送工具定义，但要自行处理模型返回的工具请求。
     * <p>观察：先看工具请求存在但执行次数为零，再看开启后的执行与最终回答。
     */
    @Test
    @DisplayName("工具循环参数：关闭与开启自动注册")
    void chatWithToolAutoRegisterParam() throws Exception {
        ChatClient client = ChatClient.create(new ModelCreator().getModel());
        CounterTool tool = new CounterTool();

        // 工具定义仍发给模型，但不自动执行；因此读取完整响应，而不是只读 content。
        var response = client.prompt().user("请调用 ping 工具，然后报告检查结果。")
                .tools(tool).advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                .call().chatResponse();
        System.out.println("关闭自动循环，模型工具请求：" + response.getResult().getOutput().getToolCalls());
        System.out.println("工具实际执行次数：" + tool.calls);
        System.out.println("=========================================================================================");
        String answer = client.prompt().user("中文回答，请调用 ping 工具，然后报告检查结果。")
                .tools(tool).advisors(AdvisorParams.toolCallingAdvisorAutoRegister(true))
                .call().content();
        System.out.println("开启自动循环，最终回答：" + answer);
        System.out.println("工具实际执行次数：" + tool.calls);
    }

    /**
     * 观察 Advisor 参数如何筛选检索上下文。
     * <p>关键点：文档 metadata.type 是标签；FILTER_EXPRESSION 指定筛选条件，固定向量只为演示过滤。
     * <p>观察：两次问题相同但标签不同；核对 HTTP 中加入的资料，不能用本例评价语义检索效果。
     */
    @Test
    @DisplayName("检索过滤参数：按文档元数据选择上下文")
    void chatWithFilterParam() throws Exception {
        // 固定向量仅用于演示元数据过滤，不代表语义 Embedding 模型。
        // 这里没有调用真实 Embedding 服务；所有向量固定相同，因此实验只说明 metadata 过滤的效果。
        EmbeddingModel embedding = new EmbeddingModel() {
            @Override
            public float[] embed(Document document) { return new float[]{1, 0}; }

            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                return new EmbeddingResponse(
                        IntStream.range(0, request.getInstructions().size())
                                .mapToObj(i -> new Embedding(new float[]{1, 0}, i))
                                .toList());
            }
        };
        SimpleVectorStore store = SimpleVectorStore.builder(embedding).build();
        store.add(List.of(
                new Document("Java 学习组每周一晚上七点开会。", Map.of("type", "Java")),
                new Document("Python 学习组每周五晚上八点开会。", Map.of("type", "Python"))));

        ChatClient client = ChatClient.builder(new ModelCreator().getModel())
                .defaultAdvisors(QuestionAnswerAdvisor.builder(store).build())
                .build();

        // 相同问题只改变过滤参数；HTTP 日志可以核对实际加入提示词的文档。
        for (String type : List.of("Java", "Python")) {
            String answer = client.prompt().user("学习组什么时候开会？")
                    .advisors(a -> a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, "type == '" + type + "'"))
                    .call().content();
            System.out.println(type + " 文档回答：" + answer);
        }
    }

    /**
     * 观察应用自定义参数传递到哪里。
     * <p>关键点：param 写入 Advisor 请求上下文，由自定义 Advisor 显式读取；不会自动进入提示词、工具或 MDC。
     * <p>观察：对照日志中的 userId/tenantId/traceId 与 HTTP 消息，确认参数和模型上下文的边界。
     */
    @Test
    @DisplayName("自定义 Advisor 参数：读取用户、租户和追踪编号")
    void chatWithCustomAdvisorParams() throws Exception {
        CallAdvisor trace = new CallAdvisor() {
            public String getName() { return "custom-params-demo"; }
            public int getOrder() { return 0; }

            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                // 自定义参数必须由自己的 Advisor 消费；这里仅输出，不加入模型提示词。
                System.out.println("自定义参数 userId=" + request.context().get("userId")
                        + " tenantId=" + request.context().get("tenantId")
                        + " traceId=" + request.context().get("traceId"));
                return chain.nextCall(request);
            }
        };
        ChatClient client = ChatClient.builder(new ModelCreator().getModel()).defaultAdvisors(trace).build();
        System.out.println(client.prompt().user("请用一句话打招呼。")
                .advisors(a -> a.param("userId", "user-001")
                                            // 这些值由自定义 Advisor 从 request.context 读取；身份认证、租户权限与日志上下文都需要应用另外接入。
                                            .param("tenantId", "tenant-001")
                                            .param("traceId", "demo-trace-001")
                )
                .call().content());
    }

    /**
     * 离线观察会话记忆的消息窗口。
     * <p>关键点：maxMessages 在记忆组件构建时设置；它限制消息数量，不是 token 数，也不是单次 Advisor 参数。
     * <p>观察：查看 A 的旧消息被淘汰，B 仍按自己的会话 ID 存储。
     */
    @Test
    @DisplayName("记忆窗口配置：只保留最近消息")
    void memoryWindowIsBuilderOption() {
        // maxMessages 是组件配置，不是 .advisors(a -> a.param(...)) 的参数。
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(2).build();
        memory.add("A", List.of(new UserMessage("第一问"), new AssistantMessage("第一答")));
        memory.add("A", List.of(new UserMessage("第二问"), new AssistantMessage("第二答")));
        memory.add("B", List.of(new UserMessage("第二问"), new AssistantMessage("第二答")));
        System.out.println("窗口最多两条，当前消息：\n" + memory.get("A"));
    }

    /**
     * 用同一个客户端观察会话接续与隔离。
     * <p>关键点：MessageChatMemoryAdvisor 请求前按 CONVERSATION_ID 取历史，回答后写回记忆。
     * <p>观察：A 的第二次请求应包含之前问答；B 的请求不应带入 A 的历史。
     */
    @Test
    @DisplayName("会话记忆：同会话读取历史，不同会话隔离")
    void chatWithMemory() throws Exception {

        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(10).build();

        ChatClient client = ChatClient.builder(new ModelCreator().getModel())
                .defaultSystem("请用中文简短回答；不知道的信息不要猜测。")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();

        System.out.println("A 记录：" + client.prompt().user("我叫小王，请记住。")
                // 同一个 ID 才会读取同一组历史；应用需根据真实用户/会话分配 ID，固定 A/B 只用于演示。
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "A"))
                .call().content());

        System.out.println("A 询问：" + client.prompt().user("我叫什么？")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "A"))
                .call().content());

        System.out.println("B 询问：" + client.prompt().user("我叫什么？")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "B"))
                .call().content());

    }

    /**
     * 观察 HarnessCore 的流式聊天入口。
     * <p>关键点：stream=true 使用流式内容接口；doOnNext 收到片段，blockLast 等待完成并限制等待时长。
     * <p>观察：观察片段输出；片段边界由流处理决定，不等于一句话或一个完整业务对象。
     */
    @Test
    @DisplayName("真实模型：基础流式对话")
    void chatWithRealModel() throws Exception {
        System.out.println("模型回答：");
        new HarnessCore(new ModelCreator().getModel()).run("你是 Java 学习助手，请用中文简短回答。",
                        "用两句话解释 Agent 的模型和工具各自负责什么。",
                        null,
                        true)
                .doOnNext(System.out::print)
                .blockLast(Duration.ofSeconds(90));
        System.out.println();
    }
    /**
     * 把社区 Glob 工具交给模型调用。
     * <p>关键点：ToolCofig 为 Glob 配置工作目录和允许目录；findProjectFile 兼容从根目录或模块目录启动。
     * <p>观察：观察模型工具请求、真实文件结果和后续回答；Glob 搜索范围受目录与深度配置影响。
     */
    @Test
    @DisplayName("真实模型：调用原始 Glob 工具查找文件")
    void chatWithRealModelWithPath() throws Exception {
        ModelCreator modelCreator = new ModelCreator();
        // 手动创建对象时，需要自己调用初始化方法。
        OpenAiChatModel model = modelCreator.getModel();

        System.out.println("===============模型回答：==================");
        // run 的最后一个 true 选择流式分支；工具请求及参数可先由底层聚合，再交给工具循环执行。
        new HarnessCore(model).run("请用中文简短回答。",
                        "请调用 Glob 工具，查找工作目录中的 Java 文件，列出文件名。",
                        Arrays.asList(ToolCofig.getGlobTool(ModelConfig.findProjectFile(
                                "study-spring-ai/src/test/java/com/chinazhouwy/miniharness/springai/study").toString())),
                        true)
                .doOnNext(System.out::print)
                .blockLast(Duration.ofSeconds(90));
        System.out.println();
    }


    /**
     * 观察自定义工具名称与 Java 实现的绑定。
     * <p>关键点：FileSearchTool 用 @Tool 暴露 find_files，内部委托 GlobTool；模型看到的名字来自工具定义。
     * <p>观察：核对工具请求名和 FileSearchTool 的执行日志；提示词提到名称不会自动重命名工具。
     */
    @Test
    @DisplayName("真实模型：调用自定义名称的文件搜索工具")
    void chatWithRealModelWithMyPathTool() throws Exception {
        ModelCreator modelCreator = new ModelCreator();
        // 手动创建对象时，需要自己调用初始化方法。
        OpenAiChatModel model = modelCreator.getModel();

        System.out.println("===============模型回答：==================");
        new HarnessCore(model).run("请用中文简短回答。",
                        "查找工作目录中的 Java 文件，列出文件名。",
                        Arrays.asList(new FileSearchTool(ModelConfig.findProjectFile(
                                "study-spring-ai/src/test/java/com/chinazhouwy/miniharness/springai/study").toString())),
                        true)
//                .doOnNext(chunk -> System.out.println("【最终回答片段】 " + chunk))
                .blockLast(Duration.ofSeconds(90));
        System.out.println();
    }

    /**
     * 观察一趟 call 中多次工具调用的消息累积。
     * <p>关键点：模型根据工具结果决定下一步；ToolCallingAdvisor 负责执行工具并继续请求模型。
     * <p>观察：按调用 ID 配对请求与结果，数模型轮次；提示词表达多步要求不保证每次都产生同样的批次。
     */
    @Test
    @DisplayName("真实模型：工具结果回填后继续下一轮调用")
    void chatWithMultipleToolRounds() throws Exception {
        ModelCreator modelCreator = new ModelCreator();
        OpenAiChatModel model = modelCreator.getModel();

        // 第二次搜索依赖第一次返回的文件名，便于观察多轮消息与工具结果的回填。
        String task = """
                请按顺序完成两次工具调用，并在两次都完成后再回答：
                1. 调用 find_files，使用 **/*.java 查找 Java 文件。
                2. 从第一次结果中选一个实际存在的文件名，再次调用 find_files，
                   使用该文件名构造精确匹配的 glob 模式查找它。
                最后简短说明你选了哪个文件以及第二次搜索的结果。
                """;

        System.out.println("=============== 多轮工具调用 demo ===============");
        new HarnessCore(model).run("请按照用户要求依次调用工具，并用中文回答。", task,
                        Arrays.asList(new FileSearchTool(
                                ModelConfig.findProjectFile(
                                "study-spring-ai/src/test/java/com/chinazhouwy/miniharness/springai/study").toString())),
                        true)
                // 由 TraceStreamAdvisor 输出每轮聚合结果，避免流式文本与日志交错。
                .blockLast(Duration.ofSeconds(90));
        System.out.println();
    }

}
