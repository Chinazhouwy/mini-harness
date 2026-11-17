package com.chinazhouwy.miniharness.springai.study;

import com.chinazhouwy.miniharness.model.ModelCreator;
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
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/** Spring AI 基础调用学习入口。BeforeAll 创建模型连接配置，方法分别演示同步、流式、工具、记忆和输出转换；本类依赖真实模型，默认由 live 标签排除。 */
@org.junit.jupiter.api.Tag("live")
public class SpringAiStudyTest {

    private static OpenAiChatModel model;

    @BeforeAll
    static void before() throws IOException {
        // 复用现有配置与 HTTP 请求日志，能够核对实际发给模型的历史消息。
        model = new ModelCreator().getModel();
    }

    /**
     * 认识 ChatClient 的最小同步调用。
     * <p>关键点：system/user 组成模型消息；call 选择同步响应，content 提取最终文本。
     * <p>观察：看发出的消息角色与返回文字；没有注册工具时只是普通问答。
     */
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

    /**
     * 认识流式响应的订阅与完成。
     * <p>关键点：stream().content() 返回 Flux；blockLast 订阅并等待，doOnNext 观察每次发出的文本片段。
     * <p>观察：对照订阅前后的输出，片段不保证是完整单词或一句话。
     */
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

    /**
     * 通过 @Tool 注册 Java 方法供模型选择。
     * <p>关键点：tools 将对象的工具定义交给请求；自动工具循环会执行工具并携带结果再次请求模型。
     * <p>观察：看 Java 方法的执行日志和最终回答；工具返回的是本例固定任务状态。
     */
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

    /**
     * 统计一趟用户请求中的模型调用轮次。
     * <p>关键点：较大的 Advisor order 让 trace 位于默认工具循环内部；每次 nextCall 经过一次该观察器。
     * <p>观察：按模型调用次数和 hasToolCalls 日志识别中间轮与最终轮，不等于统计网络层 SDK 重试。
     */
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



    /**
     * 使用 SimpleLoggerAdvisor 观察 ChatClient 请求和响应。
     * <p>关键点：日志是否可见同时取决于 Advisor 配置和日志级别；日志对象不是原始 HTTP 字节。
     * <p>观察：对照本模块的 HTTP 拦截器输出，理解客户端层与网络层各自观察什么。
     */
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


    /**
     * 观察聊天记忆的存取、窗口和会话隔离。
     * <p>关键点：Repository 保存消息，MessageWindowChatMemory 管窗口，Advisor 按 CONVERSATION_ID 读取和写回。
     * <p>观察：同时查看 A/B 的 HTTP 历史和 memory.get，不能只凭模型回答对错判定隔离。
     */
    @Test
    void memoryChat() {
        // 1. 创建存储和窗口管理
        ChatMemory memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(10)
                .build();

        // 2. 创建带记忆 Advisor 的客户端
        ChatClient client = ChatClient.builder(model)
                // 把记忆 Advisor 挂到客户端后，后续请求才能按会话参数自动读写历史；只 new ChatMemory 不会影响模型输入。
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

        // 同一个 client 和 memory，换一个 conversationId，不应带入 A 的历史。
        String other = client.prompt()
                .user("我刚才说的任务是什么？如果没有相关记录，请明确说不知道。")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "study-002"))
                .call()
                .content();
        System.out.println("新会话：" + other);

        // 对照 HTTP 请求体和存储；不要仅凭模型是否回答正确判断会话隔离。
        System.out.println("会话 A 保存的消息：" + memory.get(conversationId));
        System.out.println("会话 B 保存的消息：" + memory.get("study-002"));
    }

    record TaskInfo(String name, String status) {}

    /**
     * 把模型响应转换为目标 record。
     * <p>关键点：entity 依据类型组织格式并反序列化；TaskInfo 字段只是数据结构，没有业务校验逻辑。
     * <p>观察：核对生成的任务名和状态；本例不执行或保存任务。
     */
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

    /**
     * 观察应用查到的资料如何进入模型上下文。
     * <p>关键点：这里显式把固定资料拼到用户消息，不包含向量检索，也不会自动保存长期记忆。
     * <p>观察：从请求体确认资料已发送；回答应以这段资料为依据。
     */
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
