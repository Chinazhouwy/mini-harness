package com.chinazhouwy.miniharness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.micrometer.common.util.StringUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class MiniHarnessDemo {

    protected static final String BASE_URL = "https://api.deepseek.com";
    protected static final String MODEL = "deepseek-v4-flash";
    protected static final String API_KEY = System.getenv("DEEPSEEK_API_KEY");
    private static final Logger log = LoggerFactory.getLogger(MiniHarnessDemo.class);

    public static void main(String[] args) {
        new MiniHarnessDemo().test();
    }

    public void test() {
        log.info("Starting MiniHarnessDemo...");

        List<QuestionAttempt> attempts = new ArrayList<>();
        List<Message> history = new ArrayList<>();
        InterviewState interviewState = InterviewState.IDLE;

        // 加载历史信息
        Result result = loadHistoryInfo(attempts, history);

        String currentQuestion = "";

        ChatClient intentClient = createIntentClient();
        
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {

            log.info("now InterviewState {}",interviewState);

            saveHistoryInfo(result.attempts(), result.history());

            System.out.print("\n你 > ");
            String line = scanner.nextLine();

            if (exitCommend(line)) {
                System.out.println("退出对话。");
                break;
            }

            System.out.printf("User: %s%n", line);

            IntentResult response = getIntentResult(intentClient, line, result);

            switch (response.intent()) {
                case NEXT_QUESTION -> {
                    String res = generateNextQuestion( result.history(), currentQuestion);
                    System.out.printf("Assistant: %s%n", res);
                    result.history().add(new AssistantMessage(res));
                    currentQuestion = res;
                    interviewState = InterviewState.WAITING_ANSWER;
                }
                case EXPLAIN -> {
                    String res = explainQuestion( result.history());
                    System.out.printf("Assistant: %s%n", res);
                    result.history().add(new AssistantMessage(res));
                    interviewState = InterviewState.WAITING_ANSWER;
                }
                case HINT -> {
                    String res = giveHint(result.history());
                    System.out.printf("Assistant: %s%n", res);
                    result.history().add(new AssistantMessage(res));
                    interviewState = InterviewState.WAITING_ANSWER;

                }
                case EXIT -> {
                    System.out.println("退出面试。");
                    return;
                }
                case ANSWER -> {
                    result.history().add(new UserMessage(line));
                    // 刚开始没有问题，就不做评估
                    if (StringUtils.isNotEmpty(currentQuestion)) {
                        AnswerEvaluation evaluation = createEvaluatorClient( result.history());
                        System.out.printf("Assistant: evaluation 评分：%d，评价：%s，遗漏：%s%n", evaluation.score(),
                                evaluation.comment(), evaluation.missingPoint());
                        result.attempts().add(new QuestionAttempt(currentQuestion, line,evaluation));
                    }
                    String res = continueInterview(result.history(), currentQuestion,line);
                    System.out.printf("Assistant: %s%n", res);
                    result.history().add(new AssistantMessage(res));
                    interviewState = InterviewState.FEEDBACK;
                }
                default -> System.out.println("未识别的意图，请重新输入。");
            }

        }
    }

    private static @Nullable IntentResult getIntentResult(ChatClient intentClient, String line, Result result) {
        IntentResult response = intentClient
                .prompt()
                .user(line)
                .messages(result.history())
                .call()
                .entity(IntentResult.class);
        System.out.printf("[DEBUG] intent: %s%n", response);
        return response;
    }

    private static boolean exitCommend(String line) {
        return "exit".equalsIgnoreCase(line.trim()) || "quit".equalsIgnoreCase(line.trim());
    }

    private static @NonNull ChatClient createIntentClient() {
        ChatClient intentClient = ChatClient.builder(createChatModel())
                .defaultSystem("""
                        你负责识别用户在面试过程中的意图。
                        
                        可选意图：
                        ANSWER：用户正在回答当前题目
                        NEXT_QUESTION：用户要求切换到下一题
                        EXPLAIN：用户要求讲解当前题目  
                        HINT：用户表示不会或要求提示
                        EXIT：用户要求结束面试
                        """)
                .build();
        return intentClient;
    }

    private static @NonNull Result loadHistoryInfo(List<QuestionAttempt> attempts, List<Message> history) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            java.io.File fileAttempts = new java.io.File("data/attempts.json");
            if (fileAttempts.exists()) {
                attempts = mapper.readValue(fileAttempts, new com.fasterxml.jackson.core.type.TypeReference<List<QuestionAttempt>>() {});
            }
            java.io.File fileHistroy = new java.io.File("data/history.json");
            if (fileHistroy.exists()) {
                history = mapper.readValue(fileHistroy, new com.fasterxml.jackson.core.type.TypeReference<List<Message>>() {});
            }
        } catch (Exception e) {
            System.err.println("加载 history.json , attempts.json 失败: " + e.getMessage());
        }
        Result result = new Result(attempts, history);
        return result;
    }

    private record Result(List<QuestionAttempt> attempts, List<Message> history) {
    }

    private static void saveHistoryInfo(List<QuestionAttempt> attempts, List<Message> history) {
        if (attempts.size() > 0) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                java.io.File file = new java.io.File("data/attempts.json");
                java.io.File parentDir = file.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                mapper.writer().writeValue(file, attempts);
            } catch (Exception e) {
                System.err.println("保存 attempts.json 失败: " + e.getMessage());
            }
        }

        if (history.size() > 0) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                java.io.File file = new java.io.File("data/history.json");
                java.io.File parentDir = file.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }
                mapper.writer().writeValue(file, history);
            } catch (Exception e) {
                System.err.println("保存 history.json 失败: " + e.getMessage());
            }
        }
    }

    private static class ChatModelHolder {
        private static final ChatModel INSTANCE;

        static {
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

    private static ChatModel createChatModel() {
        return ChatModelHolder.INSTANCE;
    }

    private AnswerEvaluation createEvaluatorClient( List<Message> history) {
        ChatClient evaluatorClient = ChatClient.builder(createChatModel())
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


    private String generateNextQuestion( List<Message> history, String currentQuestion) {
        return ChatClient.builder(createChatModel())
                .defaultSystem("你是一名 Java 面试官。")
                .build()
                .prompt()
                .user("""
                        上一道题是：
                        %s
                        
                        请换一道不同知识点的 Java 面试题，不得重复上一题。
                        只输出问题本身。
                        """.formatted(currentQuestion))
                .messages(history)
                .call()
                .content();
    }

    private String explainQuestion( List<Message> history) {
        return ChatClient.builder(createChatModel())
                .defaultSystem("你是一名 Java 面试官。")
                .build()
                .prompt()
                .user("要求讲解当前题目")
                .messages(history)
                .call()
                .content();
    }

    private String giveHint(List<Message> history) {
        return ChatClient.builder(createChatModel())
                .defaultSystem("你是一名 Java 面试官。")
                .build()
                .prompt()
                .user("针对当前题目只给一个渐进式提示。最多三句话，不要直接给出完整答案。")
                .messages(history)
                .call()
                .content();
    }

    private String continueInterview(List<Message> history, String currentQuestion,String line) {
        return ChatClient.builder(createChatModel())
                .defaultSystem("你是一名 Java 面试官。")
                .build()
                .prompt()
                .user("""
                        你是面试官。根据用户刚才的回答进行一句简短反馈，
                        然后只提出一个针对其遗漏点的追问。
                        不要直接给出完整答案。
                    """)
                .messages(history)
                .call()
                .content();
    }

}
