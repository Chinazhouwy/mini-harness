package com.chinazhouwy.miniharness;

import com.chinazhouwy.miniharness.model.ModelCreator;
import com.chinazhouwy.miniharness.tool.FileSearchTool;
import com.chinazhouwy.miniharness.tool.ToolCofig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
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
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

class HarnessCoreDemo {

    public static class CounterTool {
        private int calls;

        @Tool(name = "ping", description = "执行一次连通性检查，返回 pong")
        public String ping() {
            calls++;
            return "pong";
        }
    }

    @Test
    void chatWithToolAutoRegisterParam() throws Exception {
        ChatClient client = ChatClient.create(new ModelCreator().getModel());
        CounterTool tool = new CounterTool();

        // 工具定义仍发给模型，但不自动执行；因此读取完整响应，而不是只读 content。
        var response = client.prompt().user("请调用 ping 工具，然后报告检查结果。")
                .tools(tool).advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                .call().chatResponse();
        System.out.println("关闭自动循环，模型工具请求：" + response.getResult().getOutput().getToolCalls());
        System.out.println("工具实际执行次数：" + tool.calls);

        String answer = client.prompt().user("请调用 ping 工具，然后报告检查结果。")
                .tools(tool).advisors(AdvisorParams.toolCallingAdvisorAutoRegister(true))
                .call().content();
        System.out.println("开启自动循环，最终回答：" + answer);
        System.out.println("工具实际执行次数：" + tool.calls);
    }

    @Test
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
    void memoryWindowIsBuilderOption() {
        // maxMessages 是组件配置，不是 .advisors(a -> a.param(...)) 的参数。
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(2).build();
        memory.add("A", List.of(new UserMessage("第一问"), new AssistantMessage("第一答")));
        memory.add("A", List.of(new UserMessage("第二问"), new AssistantMessage("第二答")));
        memory.add("B", List.of(new UserMessage("第二问"), new AssistantMessage("第二答")));
        System.out.println("窗口最多两条，当前消息：\n" + memory.get("A"));
    }

    @Test
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
    void chatWithRealModelWithPath() throws Exception {
        ModelCreator modelCreator = new ModelCreator();
        // 手动创建对象时，需要自己调用初始化方法。
        OpenAiChatModel model = modelCreator.getModel();

        System.out.println("===============模型回答：==================");
        new HarnessCore(model).run("请用中文简短回答。",
                        "请调用 Glob 工具，查找工作目录中的 Java 文件，列出文件名。",
                        Arrays.asList(ToolCofig.getGlobTool("src/test/java/com/chinazhouwy/miniharness/springai/study")),
                        true)
                .doOnNext(System.out::print)
                .blockLast(Duration.ofSeconds(90));
        System.out.println();
    }


    @Test
    void chatWithRealModelWithMyPathTool() throws Exception {
        ModelCreator modelCreator = new ModelCreator();
        // 手动创建对象时，需要自己调用初始化方法。
        OpenAiChatModel model = modelCreator.getModel();

        System.out.println("===============模型回答：==================");
        new HarnessCore(model).run("请用中文简短回答。",
                        "查找工作目录中的 Java 文件，列出文件名。",
                        Arrays.asList(new FileSearchTool("src/test/java/com/chinazhouwy/miniharness/springai/study")),
                        true)
//                .doOnNext(chunk -> System.out.println("【最终回答片段】 " + chunk))
                .blockLast(Duration.ofSeconds(90));
        System.out.println();
    }

    @Test
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
                                "src/test/java/com/chinazhouwy/miniharness/springai/study")),
                        true)
                // 由 TraceStreamAdvisor 输出每轮聚合结果，避免流式文本与日志交错。
                .blockLast(Duration.ofSeconds(90));
        System.out.println();
    }

}
