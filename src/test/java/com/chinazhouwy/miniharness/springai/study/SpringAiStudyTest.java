package com.chinazhouwy.miniharness.springai.study;

import com.chinazhouwy.miniharness.agentscope.study.ContextPolicyTest;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

public class SpringAiStudyTest {

    private static OpenAiChatModel model;

    @BeforeAll
    static void before() throws IOException {
        Properties props = new Properties();
        try (InputStream in = ContextPolicyTest.class.getClassLoader()
                .getResourceAsStream("openrouter.local.properties")) {
            props.load(in);
        }
        model = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .apiKey(props.getProperty("openrouter.api.key"))
                        .baseUrl(props.getProperty("openrouter.base.url"))
                        .model(props.getProperty("openrouter.model"))
                        .build())
                .build();
    }

    @Test
    void firstChat() {
        // 1. 创建模型连接配置
        // 2. 用模型创建 ChatClient
        ChatClient client = ChatClient.create(model);

        // 3. 构造请求并取得回答
        String answer = client.prompt()
                .system("你是一个 Java 学习助手，请简洁回答。")
                .user("用一句话解释什么是 Reactor 的 Mono。")
                .call()
                .content();

        System.out.println(answer);
    }

    @Test
    void streamChat() {
        ChatClient client = ChatClient.create(model);

        Flux<String> chunks = client.prompt()
                .system("你是一个 Java 学习助手，请简洁回答。")
                .user("用一句话解释什么是 Reactor 的 Mono。")
                .stream()
                .content();

        System.out.println("准备订阅流式回答");
        chunks.doOnNext(chunk -> System.out.println("收到片段：[" + chunk + "]"))
                .doOnComplete(() -> System.out.println("回答完成"))
                .blockLast();
    }

    @Test
    void toolChat() {
        ChatClient client = ChatClient.create(model);

        String answer = client.prompt()
                .system("你是一个任务助手，请根据工具结果回答。")
                .user("我的午餐拍照任务完成了吗？")
                .tools(new TaskTools())
                .call()
                .content();

        System.out.println("最终回答：" + answer);
    }

    @Test
    void observeToolLoop() {
        ChatClient client = ChatClient.create(model);
        AtomicInteger modelCalls = new AtomicInteger();

        CallAdvisor trace = new CallAdvisor() {
            @Override
            public String getName() {
                return "model-call-trace";
            }

            @Override
            public int getOrder() {
                // 放到默认工具循环内部，观察每一轮模型调用。
                return 0;
            }

            @Override
            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                int round = modelCalls.incrementAndGet();
                System.out.println("第 " + round + " 次模型调用，消息数：" + request.prompt().getInstructions().size());
                ChatClientResponse response = chain.nextCall(request);
                System.out.println("第 " + round + " 次模型响应包含工具调用：" + (response.chatResponse() != null && response.chatResponse().hasToolCalls()));
                return response;
            }
        };

        String answer = client.prompt()
                .system("你是任务助手，请先查询工具，然后根据工具结果回答。")
                .user("我的午餐拍照任务完成了吗？")
                .tools(new TaskTools())
                .advisors(trace)
                .call()
                .content();

        System.out.println("最终回答：" + answer);
        System.out.println("模型调用总次数：" + modelCalls.get());
    }



    @Test
    void advisorChat() {
        ChatClient client = ChatClient.create(model);

        String answer = client.prompt()
                .advisors(new SimpleLoggerAdvisor())
                .user("用一句话解释 Java 中的线程池。")
                .call()
                .content();

        System.out.println("最终回答：" + answer);
    }


    @Test
    void memoryChat() {
        // 1. 创建存储和窗口管理
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(10)
                .build();

        // 2. 创建带记忆 Advisor 的客户端
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(memory).build()
                )
                .build();

        String conversationId = "study-001";

        // 第一轮
        String first = client.prompt()
                .user("我今天的任务是拍午餐照片，记住它。")
                .advisors(a -> a.param(
                        ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        System.out.println("第一轮：" + first);

        // 第二轮：使用相同的 conversationId
        String second = client.prompt()
                .user("我刚才说的任务是什么？")
                .advisors(a -> a.param(
                        ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        System.out.println("第二轮：" + second);

        // 查看当前存储的消息
        System.out.println("保存的消息：" + memory.get(conversationId));
    }

    record TaskInfo(String name, String status) {}

    @Test
    void structuredOutput() {
        ChatClient client = ChatClient.create(model);

        TaskInfo task = client.prompt()
                .user("创建一条任务：拍午餐照片，状态为待完成。")
                .call()
                .entity(TaskInfo.class);

        System.out.println(task.name());
        System.out.println(task.status());
    }

    @Test
    void contextChat() {
        ChatClient client = ChatClient.create(model);

        // 模拟从数据库或文件中查到的资料
        String context = "用户今天有一项任务：拍午餐照片，状态是待完成。";

        String answer = client.prompt()
                .system("请根据提供的资料回答。资料不足时直接说明。")
                .user("资料：" + context + "\n问题：我今天有什么任务？")
                .call()
                .content();

        System.out.println(answer);
    }

    static class TaskTools {

        @Tool(description = "查询用户的午餐拍照任务状态")
        public String queryLunchTask() {
            System.out.println("Java 工具执行了：queryLunchTask");

            // 先用固定数据演示，后续再换成数据库查询
            return "午餐拍照任务：待完成";
        }
    }

}
