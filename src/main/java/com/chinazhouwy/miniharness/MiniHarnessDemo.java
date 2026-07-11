package com.chinazhouwy.miniharness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.micrometer.common.util.StringUtils;
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

    public static void main(String[] args) {
        new MiniHarnessDemo().test();
    }

    public void test() {

        List<QuestionAttempt> attempts = new ArrayList<>();

        String currentQuestion = "";

        ChatModel chatModel = createChatModel();
        ChatClient intentClient = ChatClient.builder(chatModel)
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

        List<Message> history = new ArrayList<>();


        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {

            if (attempts.size() > 0) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    java.io.File file = new java.io.File("data/history.json");
                    java.io.File parentDir = file.getParentFile();
                    if (parentDir != null && !parentDir.exists()) {
                        parentDir.mkdirs();
                    }
                    mapper.writer().writeValue(file, attempts);
                } catch (Exception e) {
                    System.err.println("保存 attempts.json 失败: " + e.getMessage());
                }
            }

            System.out.print("\n你 > ");
            String line = scanner.nextLine();
            if ("exit".equalsIgnoreCase(line.trim()) || "quit".equalsIgnoreCase(line.trim())) {
                System.out.println("退出对话。");
                break;
            }
            System.out.printf("User: %s%n", line);

            IntentResult response = intentClient
                    .prompt()
                    .user(line)
                    .messages(history)
                    .call()
                    .entity(IntentResult.class);
            System.out.printf("[DEBUG] intent: %s%n", response);

            switch (response.intent()) {
                case NEXT_QUESTION -> {
//                    history.add(new UserMessage(line));
                    String res = generateNextQuestion(chatModel, history, currentQuestion);
                    System.out.printf("Assistant: %s%n", res);
                    history.add(new AssistantMessage(res));
                    currentQuestion = res;
                }
                case EXPLAIN -> {
//                    history.add(new UserMessage(line));
                    String res = explainQuestion(chatModel, history);
                    System.out.printf("Assistant: %s%n", res);
                    history.add(new AssistantMessage(res));
                }
                case HINT -> {
//                    history.add(new UserMessage(line));
                    String res = giveHint(chatModel, history);
                    System.out.printf("Assistant: %s%n", res);
                    history.add(new AssistantMessage(res));
                }
                case EXIT -> {
//                    history.add(new UserMessage(line));
                    System.out.println("退出面试。");
                    return;
                }
                case ANSWER -> {
                    history.add(new UserMessage(line));
                    if (StringUtils.isEmpty(line)) {
                        AnswerEvaluation evaluation = createEvaluatorClient(chatModel, history);
                        System.out.printf("Assistant: evaluation 评分：%d，评价：%s，遗漏：%s%n", evaluation.score(),
                                evaluation.comment(), evaluation.missingPoint());
                        QuestionAttempt attempt = new QuestionAttempt(
                                currentQuestion,
                                line,
                                evaluation
                        );
                        attempts.add(attempt);
                    }
                    String res = continueInterview(chatModel, history, line);
                    System.out.printf("Assistant: %s%n", res);
                    history.add(new AssistantMessage(res));
                }
                default -> System.out.println("未识别的意图，请重新输入。");
            }

        }
    }

    private ChatModel createChatModel() {
        OpenAIClient openAiClient = OpenAIOkHttpClient.builder()
                .apiKey(API_KEY)
                .baseUrl(BASE_URL)
                .build();
        return OpenAiChatModel.builder()
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

    private AnswerEvaluation createEvaluatorClient(ChatModel chatModel, List<Message> history) {
        ChatClient evaluatorClient = ChatClient.builder(chatModel)
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


    private String generateNextQuestion(ChatModel chatModel, List<Message> history, String currentQuestion) {
        return ChatClient.builder(chatModel)
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

    private String explainQuestion(ChatModel chatModel, List<Message> history) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一名 Java 面试官。")
                .build()
                .prompt()
                .user("要求讲解当前题目")
                .messages(history)
                .call()
                .content();
    }

    private String giveHint(ChatModel chatModel, List<Message> history) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一名 Java 面试官。")
                .build()
                .prompt()
                .user("针对当前题目只给一个渐进式提示。最多三句话，不要直接给出完整答案。")
                .messages(history)
                .call()
                .content();
    }

    private String continueInterview(ChatModel chatModel, List<Message> history, String line) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一名 Java 面试官。")
                .build()
                .prompt()
//                .user("用户回答了当前面试题，他的回答是：" + line + "，请继续面试。")
                .messages(history)
                .call()
                .content();
    }

}
