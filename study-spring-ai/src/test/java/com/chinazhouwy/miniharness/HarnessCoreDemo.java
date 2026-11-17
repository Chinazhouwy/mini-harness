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

    @Test
    @DisplayName("工具异常策略：回填模型或抛给调用方")
    void chatWithToolFailure() throws Exception {
        OpenAiChatModel model = new ModelCreator().getModel();
        for (boolean alwaysThrow : List.of(false, true)) {
            System.out.println("\n[工具异常策略] alwaysThrow=" + alwaysThrow);
            var manager = ToolCallingManager.builder()
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

    @Test
    @DisplayName("自定义输出转换器：生成范围约束并解析对象")
    void chatWithCustomOutputConverter() throws Exception {
        var converter = new CustomOutputExamples.TaskConverter();
        System.out.println("[自定义转换器生成的 Schema]\n" + converter.getJsonSchema());
        // validateSchema 使用同一个转换器提供的 Schema 校验；不能只依赖 convert()。
        var task = ChatClient.create(new ModelCreator().getModel()).prompt()
                .user("提取任务：复习 Java，30 分钟。")
                .call()
                .entity(converter, spec -> spec.validateSchema())
                ;
        System.out.println("[自定义转换器结果] " + task);
    }

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

    @Test
    @DisplayName("结构化输出校验失败后，反馈错误并重新生成")
    void chatWithStructuredOutputValidation() throws Exception {
        // 从类型和 @Schema 注解生成规则，不分析构造方法中的 if。
        var validation = StructuredOutputValidationAdvisor.builder()
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

    @Test
    @DisplayName("检索过滤参数：按文档元数据选择上下文")
    void chatWithFilterParam() throws Exception {
        // 固定向量仅用于演示元数据过滤，不代表语义 Embedding 模型。
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
                                            .param("tenantId", "tenant-001")
                                            .param("traceId", "demo-trace-001")
                )
                .call().content());
    }

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

    @Test
    @DisplayName("会话记忆：同会话读取历史，不同会话隔离")
    void chatWithMemory() throws Exception {

        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(10).build();

        ChatClient client = ChatClient.builder(new ModelCreator().getModel())
                .defaultSystem("请用中文简短回答；不知道的信息不要猜测。")
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();

        System.out.println("A 记录：" + client.prompt().user("我叫小王，请记住。")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "A"))
                .call().content());

        System.out.println("A 询问：" + client.prompt().user("我叫什么？")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "A"))
                .call().content());

        System.out.println("B 询问：" + client.prompt().user("我叫什么？")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "B"))
                .call().content());

    }

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
    @Test
    @DisplayName("真实模型：调用原始 Glob 工具查找文件")
    void chatWithRealModelWithPath() throws Exception {
        ModelCreator modelCreator = new ModelCreator();
        // 手动创建对象时，需要自己调用初始化方法。
        OpenAiChatModel model = modelCreator.getModel();

        System.out.println("===============模型回答：==================");
        new HarnessCore(model).run("请用中文简短回答。",
                        "请调用 Glob 工具，查找工作目录中的 Java 文件，列出文件名。",
                        Arrays.asList(ToolCofig.getGlobTool(ModelConfig.findProjectFile(
                                "study-spring-ai/src/test/java/com/chinazhouwy/miniharness/springai/study").toString())),
                        true)
                .doOnNext(System.out::print)
                .blockLast(Duration.ofSeconds(90));
        System.out.println();
    }


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
