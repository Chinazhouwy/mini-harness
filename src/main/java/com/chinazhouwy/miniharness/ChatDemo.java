package com.chinazhouwy.miniharness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class ChatDemo implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ChatDemo.class);

    private final ChatClient chatClient;

    public ChatDemo(ChatClient.Builder chatClientBuilder) {
        // ============================================================
        // 构建 ChatClient 时可以预设默认 System Prompt
        // ============================================================
        this.chatClient = chatClientBuilder
                .defaultSystem("你是一个乐于助人的助手，回答简洁、准确。")
                .build();
    }

    @Override
    public void run(String... args) throws Exception {
        example1_basicCall();
        example2_systemPrompt();
        example3_parameterizedPrompt();
        example4_tokenUsage();
        example5_streaming();
        example6_structuredOutput();
        example7_multiTurnConversation();
    }

    // ============================================================
    // 示例 1：最基本的调用 — prompt().user().call().content()
    // ============================================================
    void example1_basicCall() {
        log.info("========== 示例 1：基础调用 ==========");

        String response = chatClient.prompt()
                .user("用一句话介绍什么是 Spring AI")
                .call()
                .content();

        log.info("回复: {}", response);
    }

    // ============================================================
    // 示例 2：临时覆盖 System Prompt
    // ============================================================
    void example2_systemPrompt() {
        log.info("========== 示例 2：覆盖 System Prompt ==========");

        String response = chatClient.prompt()
                .system("你是一个精通中国古代诗词的诗人，每次回复都要引用一句古诗。")
                .user("春天来了，有什么感想？")
                .call()
                .content();

        log.info("回复: {}", response);
    }

    // ============================================================
    // 示例 3：参数化 Prompt 模板 — 避免字符串拼接
    // ============================================================
    void example3_parameterizedPrompt() {
        log.info("========== 示例 3：参数化 Prompt ==========");

        String language = "Java";
        String topic = "虚拟线程";

        String response = chatClient.prompt()
                .user(u -> u
                        .text("请用 {language} 语言解释什么是 {topic}")
                        .param("language", language)
                        .param("topic", topic))
                .call()
                .content();

        log.info("回复: {}", response);
    }

    // ============================================================
    // 示例 4：获取 Token 用量等元数据 — call().chatResponse()
    // ============================================================
    void example4_tokenUsage() {
        log.info("========== 示例 4：Token 用量 ==========");

        ChatResponse chatResponse = chatClient.prompt()
                .user("用 50 字以内介绍 Java 的垃圾回收机制")
                .call()
                .chatResponse();

        log.info("回复: {}", chatResponse.getResult().getOutput().getText());

        var usage = chatResponse.getMetadata().getUsage();
        if (usage != null) {
            log.info("Token 用量 — 输入: {}, 输出: {}, 总计: {}",
                    usage.getPromptTokens(),
                    usage.getCompletionTokens(),
                    usage.getTotalTokens());
        }
    }

    // ============================================================
    // 示例 5：流式输出 — stream().content() 返回 Flux<String>
    // ============================================================
    void example5_streaming() {
        log.info("========== 示例 5：流式输出 ==========");

        chatClient.prompt()
                .user("数 1 到 10，每个数字一行，数完说'结束'。")
                .stream()
                .content()
                .doOnNext(chunk -> System.out.print(chunk))
                .doOnComplete(() -> System.out.println())
                .blockLast(); // 阻塞等待流结束

        log.info("（流式输出已实时打印到控制台）");
    }

    // ============================================================
    // 示例 6：结构化输出 — entity() 直接映射为 Java Record
    // ============================================================
    record PersonInfo(String name, int age, String city) {}

    void example6_structuredOutput() {
        log.info("========== 示例 6：结构化输出 ==========");

        PersonInfo person = chatClient.prompt()
                .user("随机生成一个人的信息，包含 name、age、city")
                .call()
                .entity(PersonInfo.class);

        log.info("结构化结果 — 姓名: {}, 年龄: {}, 城市: {}", person.name(), person.age(), person.city());
    }

    // ============================================================
    // 示例 7：多轮对话 — Advisor 自动管理历史上下文
    // ============================================================
    void example7_multiTurnConversation() {
        log.info("========== 示例 7：多轮对话 ==========");

        // 1. 创建内存存储和记忆窗口
        var repository = new InMemoryChatMemoryRepository();
        var chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(20)
                .build();

        // 2. 创建 Advisor，负责在每次调用时自动注入历史消息
        var advisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

        String conversationId = "demo-conversation-001";

        // 第一轮
        String reply1 = chatClient.prompt()
                .user("我叫张三，是一名 Java 开发工程师。")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .advisors(advisor)
                .call()
                .content();
        log.info("第一轮回复: {}", reply1);

        // 第二轮 — 不需要重新介绍自己，模型记住了上下文
        String reply2 = chatClient.prompt()
                .user("我叫什么名字？我是做什么的？")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .advisors(advisor)
                .call()
                .content();
        log.info("第二轮回复: {}", reply2);
    }
}
