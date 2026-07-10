package com.chinazhouwy.miniharness;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;

@Tag("llm")
class SpringAiChatClientDemoTest extends SpringAiLlmDemoSupport {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatClientDemoTest.class);

    @Test
    void basicCall() {
        requireLlmConfig();

        // 示例 1：最基础的一次聊天调用。
        //
        // 调用链拆开看：
        // 1. chatClient()：拿到公共支持类里创建好的 ChatClient。
        // 2. prompt()：开始构造一次模型请求。
        // 3. user(...)：添加用户消息，也就是你真正问模型的话。
        // 4. call()：同步调用模型，当前线程会等待模型返回。
        // 5. content()：只取最终文本内容。
        //
        // 这是最适合入门的一条链路，先别急着管结构化输出、工具调用、记忆。
        String response = chatClient()
                .prompt()
                .user("用一句话介绍 Spring AI 解决什么问题。")
                .call()
                .content();

        log.info("Model: {}", configuredModel());
        log.info("Basic response: {}", response);
    }

    @Test
    void systemPromptOverridesDefaultBehavior() {
        requireLlmConfig();

        // 示例 2：System Prompt。
        //
        // user(...) 是“用户问了什么”。
        // system(...) 是“模型应该以什么身份、什么规则回答”。
        //
        // 公共 chatClient() 里已经设置了一个 defaultSystem：
        // “你是一个简洁、准确的技术学习助手。”
        //
        // 这里再次调用 system(...)，就是覆盖这一次请求的角色。
        // 以后 MiniHarness 会很依赖这个能力：
        // - 评测答案时：system = 严格面试评测器
        // - 生成追问时：system = 追问生成器
        // - 生成复盘时：system = 复盘报告助手
        String response = chatClient()
                .prompt()
                .system("你是一名严格的 Java 高级工程师面试官，只指出关键判断。")
                .user("候选人说：线程池队列满了就一定创建新线程。这个说法严谨吗？")
                .call()
                .content();

        log.info("System prompt response: {}", response);
    }

    @Test
    void parameterizedPromptTemplate() {
        requireLlmConfig();

        // 示例 3：参数化 Prompt。
        //
        // 很多 Prompt 的结构是固定的，只是里面几个变量会变。
        // 比如：
        // - language 可能是 Java、Go、Python
        // - topic 可能是 volatile、线程池、AQS
        //
        // user(user -> user.text(...).param(...)) 这种写法就是模板填参：
        // text 里写 {language}、{topic}
        // param 里给这些占位符传值
        //
        // 以后做评测时，问题、候选人回答、Rubric 都可以这样填进去。
        String response = chatClient()
                .prompt()
                .user(user -> user
                        .text("请用 {language} 解释 {topic}，限制在 80 字以内。")
                        .param("language", "Java")
                        .param("topic", "虚拟线程适合的场景"))
                .call()
                .content();

        log.info("Parameterized prompt response: {}", response);
    }

    @Test
    void chatResponseMetadataAndTokenUsage() {
        requireLlmConfig();

        // 示例 4：拿完整 ChatResponse，而不是只拿 content。
        //
        // content() 最简单，但它只给你文本。
        // chatResponse() 会保留更多信息，例如：
        // - 模型输出文本
        // - token 用量
        // - finish reason
        // - provider 返回的其他元数据
        //
        // 学 Demo 时 content() 够用。
        // 以后做 MiniHarness 时，ChatResponse 更重要，因为你需要记录：
        // - 这次评测用了多少 token
        // - 模型是否正常结束
        // - 调用成本大概是多少
        ChatResponse response = chatClient()
                .prompt()
                .user("用 50 字以内说明 volatile 保证什么，不保证什么。")
                .call()
                .chatResponse();

        log.info("Response text: {}", response.getResult().getOutput().getText());
        logUsage(log, response);
    }

    @Test
    void streamingContent() {
        requireLlmConfig();

        // 示例 5：流式输出。
        //
        // call() 是等模型全部生成完，再一次性返回。
        // stream() 是模型边生成，Java 这边边收到。
        //
        // stream().content() 返回 Flux<String>：
        // - Flux 是 Reactor 的响应式流类型
        // - 每个 chunk 是模型吐出来的一小段文本
        //
        // 这里 doOnNext 里同时做两件事：
        // - collected.append(chunk)：把流式片段收集起来，最后还能得到完整回答
        // - System.out.print(chunk)：模拟控制台实时打印
        //
        // 以后如果做页面，SSE/WebSocket 基本就是把这些 chunk 推给前端。
        StringBuilder collected = new StringBuilder();

        chatClient()
                .prompt()
                .user("分三点说明 synchronized 和 ReentrantLock 的区别，每点一行。")
                .stream()
                .content()
                .doOnNext(chunk -> {
                    collected.append(chunk);
                    System.out.print(chunk);
                })
                .doOnComplete(System.out::println)
                .blockLast();

        log.info("Collected streaming response: {}", collected);
    }
}
