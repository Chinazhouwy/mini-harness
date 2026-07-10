package com.chinazhouwy.miniharness;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

abstract class SpringAiLlmDemoSupport {

    // ============================================================
    // Demo 手工配置区
    // ============================================================
    // 这是学习 Spring AI API 的 Demo，不是正式应用配置。
    // URL、模型和 Key 直接放这里最直观：改这几行就能跑。
    //
    // 注意：不要把真实 API Key 提交到 Git。你可以本地临时改 API_KEY，
    // 或者把下面一行改成 System.getenv("AI_API_KEY")。
    // ============================================================
    protected static final String BASE_URL = "https://api.deepseek.com";
    protected static final String MODEL = "deepseek-v4-flash";
    protected static final String API_KEY = System.getenv("DEEPSEEK_API_KEY");

    // 工具调用不是所有模型都支持。想试工具 Demo 时改成 true。
    // 如果模型不支持 tools，普通聊天、流式、结构化输出仍然可以先学。
    protected static final boolean RUN_TOOL_DEMOS = false;

    // ChatModel 是 Spring AI 最核心的“模型调用抽象”。
    //
    // 你可以先这样理解：
    // - OpenAIClient：更底层的 OpenAI Java SDK 客户端，负责 HTTP 通信。
    // - OpenAiChatModel：Spring AI 对聊天模型的封装，统一成 ChatModel 接口。
    // - ChatClient：Spring AI 在 ChatModel 上再包一层 fluent API，让写 prompt 更顺手。
    //
    // 这个 Demo 里保留 chatModel 字段，是为了既能演示 ChatClient，也能演示更底层的 ChatModel。
    protected final ChatModel chatModel = createChatModel();

    /**
     * 这些 Demo 会真的调用远程模型。
     *
     * 默认 API_KEY 是占位符时，测试会被 JUnit 标记为 skipped，而不是失败。
     * 这样你执行 mvn test 时不会因为没填 key 就一片红。
     */
    protected void requireLlmConfig() {
        Assumptions.assumeTrue(hasText(API_KEY) && !"replace-with-your-api-key".equals(API_KEY),
                "Fill API_KEY in SpringAiLlmDemoSupport to run Spring AI LLM demos.");
    }

    /**
     * 工具调用需要模型支持 function/tool calling。
     *
     * 有些 OpenAI-compatible 服务虽然普通聊天能跑，但 tools 不一定兼容。
     * 所以工具 Demo 额外加一个 RUN_TOOL_DEMOS 开关，避免你一开始学习基础 API 时被工具兼容性卡住。
     */
    protected void requireToolDemoEnabled() {
        requireLlmConfig();
        Assumptions.assumeTrue(RUN_TOOL_DEMOS,
                "Set RUN_TOOL_DEMOS=true in SpringAiLlmDemoSupport when the model supports tool calling.");
    }

    protected ChatClient chatClient() {
        // ChatClient.builder(chatModel)：
        // 把底层 ChatModel 包装成链式调用 API。
        //
        // defaultSystem(...)：
        // 给这个 ChatClient 设置一个默认系统提示词。每次请求如果没有单独指定 system，
        // 就会使用这里的默认角色。后面的 Demo 也会展示如何在单次请求里覆盖它。
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一个简洁、准确的技术学习助手。")
                .build();
    }

    protected String configuredModel() {
        return MODEL;
    }

    protected void logUsage(Logger log, ChatResponse response) {
        // Usage 是模型返回的 token 用量。
        // 注意：不是所有服务商都会返回 usage；所以这里要允许 usage 为 null。
        // 以后做 MiniHarness 时，token 用量可以用于成本统计、性能观察和评测记录。
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            log.info("Token usage: provider did not return usage metadata");
            return;
        }
        log.info("Token usage: prompt={}, completion={}, total={}",
                usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static ChatModel createChatModel() {
        // 这里手工构建 OpenAI-compatible 客户端。
        //
        // 为什么不用 application.yml？
        // 因为现在就是入门 Demo：URL、模型、Key 写在一个地方更直观。
        // 等以后做正式应用，再把这些配置迁移到 application.yml 或环境变量。
        OpenAIClient openAiClient = OpenAIOkHttpClient.builder()
                .apiKey(API_KEY)
                .baseUrl(BASE_URL)
                .build();

        // OpenAiChatModel 是 Spring AI 对 OpenAI 聊天模型的适配。
        //
        // openAiClientAsync(openAiClient.async())：
        // 同时提供异步客户端，流式输出等能力会用到。
        //
        // OpenAiChatOptions：
        // 放模型参数，例如 model、temperature、maxTokens 等。
        return OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .openAiClientAsync(openAiClient.async())
                .options(OpenAiChatOptions.builder().model(MODEL).build())
                .build();
    }
}
