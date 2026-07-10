package com.chinazhouwy.miniharness;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

@Tag("llm")
class SpringAiChatMemoryDemoTest extends SpringAiLlmDemoSupport {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatMemoryDemoTest.class);

    @Test
    void keepConversationContextWithMemoryAdvisor() {
        requireLlmConfig();

        // 示例：Chat Memory。
        //
        // 大模型本身是“无状态”的：
        // 第一次你告诉它“我叫张三”，第二次如果不把历史消息再发过去，
        // 它并不会天然记得你叫张三。
        //
        // MessageChatMemoryAdvisor 的作用，就是在你每次调用模型前，
        // 自动把同一个 conversationId 下的历史消息拼回 Prompt 里。
        //
        // 注意一个很重要的边界：
        // ChatMemory 不是完整的业务聊天记录数据库。
        // 它只是“这次要发给模型看的上下文窗口”。
        //
        // MiniHarness 以后如果要做面试回放、证据追踪、评分记录，
        // 仍然应该单独保存完整 InterviewTurn。
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(10)
                .build();

        // Advisor 可以理解为 ChatClient 请求链上的拦截器。
        // MessageChatMemoryAdvisor 会在请求前后读写 ChatMemory。
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();

        // conversationId 用来区分不同对话。
        // 同一个 conversationId 下，第二轮才能看到第一轮历史。
        String conversationId = "spring-ai-memory-demo";

        String firstReply = chatClient()
                .prompt()
                .user("我叫张三，正在准备 Java 高级开发面试。")
                // 这一行把 conversationId 传给 MemoryAdvisor。
                // 如果不传，同一个 Memory 里也不知道当前请求属于哪段对话。
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .advisors(memoryAdvisor)
                .call()
                .content();

        String secondReply = chatClient()
                .prompt()
                .user("我刚才说我在准备什么？")
                // 第二次使用同一个 conversationId，模型才能“看到”第一轮内容。
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .advisors(memoryAdvisor)
                .call()
                .content();

        log.info("First reply: {}", firstReply);
        log.info("Second reply using memory: {}", secondReply);
    }
}
