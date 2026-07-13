package com.chinazhouwy.miniharness;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;

public class InterviewLLMService {

    // 这里直接放当前 Demo 使用的 Provider 地址和模型名，学习时最容易看到配置从哪里来。
    // Key 不写死在源码，而从本机环境变量读取，避免不小心提交到 Git。
    // 运行前：export DEEPSEEK_API_KEY='你的 key'
    protected static final String BASE_URL = "https://api.deepseek.com";
    protected static final String MODEL = "deepseek-v4-flash";
    protected static final String API_KEY = System.getenv("DEEPSEEK_API_KEY");

    /**
     * 用静态内部类延迟创建并复用一个 ChatModel。
     *
     * <p>这样每次出题、评测、追问时不会重新创建 HTTP 客户端。Java 的类初始化保证
     * INSTANCE 第一次被访问时才初始化，并且线程安全。</p>
     */
    public static class ChatModelHolder {
        private static final ChatModel INSTANCE;

        static {
            // DeepSeek 提供 OpenAI-compatible 接口，因此可以复用 OpenAI Java Client。
            // OpenAIClient 负责 HTTP 通信；OpenAiChatModel 才是 Spring AI 侧的 ChatModel 实现。
            OpenAIClient openAiClient = OpenAIOkHttpClient.builder()
                    .apiKey(API_KEY)
                    .baseUrl(BASE_URL)
                    .build();
            INSTANCE = OpenAiChatModel.builder()
                    .openAiClient(openAiClient)
                    .openAiClientAsync(openAiClient.async())
                    .options(
                            OpenAiChatOptions
                                    .builder()
                                    .model(MODEL)
                                    .build()
                    )
                    .build();
        }
    }

    /** 返回复用的 Spring AI ChatModel；名称沿用早期 Demo，实际不会每次新建模型。 */
    public static ChatModel createChatModel() {
        return ChatModelHolder.INSTANCE;
    }


    /**
     * 将自由文本转成有限的 {@link InterviewIntent}。
     *
     * <p>messages(result.history()) 会把已保存的上下文一同提供给模型。这样“继续说一下”
     * 这类依赖上下文的话，模型才有机会理解。它不是 ChatMemory：历史由当前代码显式传入。</p>
     */
    public static @Nullable IntentResult getIntentResult(ChatClient intentClient, String line, Result result) {
        IntentResult response = intentClient
                .prompt()
                .user(line)
                .messages(result.history())
                .call()
                .entity(IntentResult.class);
        return response;
    }


    /**
     * 创建“只做意图分类”的 ChatClient。
     *
     * <p>系统提示词列出允许返回的意图，是为了缩小模型的自由度；{@code entity(IntentResult.class)}
     * 再把结果转换为 Java Record。
     * 所以这两个枚举虽然已为后续预留，暂时不会稳定地产生。</p>
     */
    public static @NonNull ChatClient createIntentClient() {
        ChatClient intentClient = chatClientBuilder()
                .defaultSystem("""
                        你负责识别用户在面试过程中的意图。
                        
                        可选意图：
                            ANSWER,  //用户回答
                            QUESTION, // 用户请求下一题
                            HINT, // 用户请求提示
                            EXPLAIN, // 用户请求讲解
                            FOLLOW_UP,  // 用户针对评分、答案或讲解继续提问
                            CHAT,  //用户聊天
                            EXIT //用户退出
                        """)
                .build();
        return intentClient;
    }

    
    /**
     * 调用专门的评测 Prompt，并把模型输出转换为 {@link AnswerEvaluation}。
     *
     * <p>这里刻意使用单独 ChatClient，而非复用意图识别 Client：两者任务不同，System Prompt
     * 也应该不同。当前结构化输出没有 Java 侧校验，所以它是学习样例，不可直接当作可靠评分。</p>
     */
    public static AnswerEvaluation createEvaluatorClient(List<Message> history) {
        ChatClient evaluatorClient = chatClientBuilder()
                .defaultSystem("""
                        你是 Java 面试答案评审器。
                        
                        请根据面试题和用户回答进行评价：
                        score：0 到 10 分, 6分为及格，10分为满分
                        correct：回答是否基本正确
                        comment：简短评价
                        missingPoint：最重要的遗漏点，没有则返回空字符串
                        """)
                .build();

        AnswerEvaluation evaluation = evaluatorClient.prompt()
                .messages(history)
                .user("""
                        请评价用户刚才对当前面试题的回答。
                        只评价答案，不要继续提问。
                        """)
                .call()
                .entity(AnswerEvaluation.class);

        return evaluation;
    }


    /** 让模型围绕当前上下文讲解题目。 */
    public static String chat(List<Message> history) {
        return chatClientBuilder()
                .defaultSystem("你是一名 Java 面试官。")
                .build()
                .prompt()
//                .user("")
                .messages(history)
                .call()
                .content();
    }



    /** 根据已有对话和上一题生成新题；当前只靠 Prompt 约束，不保证绝对不重复。 */
    public static String generateQuestion(List<Message> history) {
        return chatClientBuilder()
                .defaultSystem("你是一名 Java 面试官。")
                .build()
                .prompt()
                .user("""
                        请换一道不同知识点的 Java 面试题，不得重复前面的题。
                        只输出问题本身。
                        """)
                .messages(history)
                .call()
                .content();
    }

    public static String giveHint(List<Message> history) {
        return chatClientBuilder()
                .defaultSystem("你是一名 Java 面试官。")
                .build()
                .prompt()
                .user("针对当前题目只给一个渐进式提示。最多三句话，不要直接给出完整答案。")
                .messages(history)
                .call()
                .content();
    }

    /** 让模型围绕当前上下文讲解题目。 */
    public static String explainQuestion(List<Message> history) {
        return chatClientBuilder()
                .defaultSystem("你是一名 Java 面试官。")
                .build()
                .prompt()
                .user("要求讲解当前题目")
                .messages(history)
                .call()
                .content();
    }

    public static String followUpQuestion(List<Message> history) {
        return chatClientBuilder()
                .defaultSystem("你是一名 Java 面试官。")
                .build()
                .prompt()
                .user("针对当前题目和你给出的答案，用户继续提问。请回答用户的问题。")
                .messages(history)
                .call()
                .content();
    }


    /**
     * 根据刚才的答案给出简短反馈和一个追问。
     *
     * <p>参数 {@code currentQuestion} 和 {@code line} 当前没有直接拼到 Prompt 中，因为二者
     * 已经在 history 里；保留参数是为了提醒这个方法的业务输入是什么。后续重构时可以删除
     * 未使用参数，或者改为只传一个明确的 QuestionRound。</p>
     */
    public static String continueInterview(List<Message> history, String currentQuestion, String line) {
        return chatClientBuilder()
                .defaultSystem("你是一名 Java 面试官。")
                .build()
                .prompt()
                .user("""
                         请根据面试题和用户回答进行评价：
                        score：0 到 10 分, 6分为及格，10分为满分
                        correct：回答是否基本正确
                        comment：简短评价
                        missingPoint：最重要的遗漏点，没有则返回空字符串
                        feedback: 根据用户刚才的回答进行反馈，给出完整答案，对于用户的遗漏点和错误点进行讲解。
                    """)
                .messages(history)
                .call()
                .content();
    }

    /**
     * 给当前 Demo 创建带 Spring AI 原生日志 Advisor 的 ChatClient Builder。
     *
     * <p>{@link SimpleLoggerAdvisor} 在模型调用前输出 {@code ChatClientRequest}，调用后输出
     * {@code ChatResponse}。它看到的是 Spring AI 已经组装好的请求，所以比手工打印每个
     * {@code .user(...)} / {@code .messages(...)} 更接近真实调用。</p>
     *
     * <p>要在控制台看到内容，还需要在 {@code application.yml} 中将这个类的日志级别设为
     * {@code DEBUG}。它会输出用户回答和 Prompt，只适合本地学习。</p>
     */
    private static ChatClient.Builder chatClientBuilder() {
        return ChatClient.builder(createChatModel())
                .defaultAdvisors(new SimpleLoggerAdvisor());
    }


}
