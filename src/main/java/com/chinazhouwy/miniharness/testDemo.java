package com.chinazhouwy.miniharness;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
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

public class testDemo {

    protected static final String BASE_URL = "https://api.deepseek.com";
    protected static final String MODEL = "deepseek-v4-flash";
    protected static final String API_KEY = System.getenv("DEEPSEEK_API_KEY");

    public static void main(String[] args) {
            new testDemo().test();
    }

    public void test() {

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
            System.out.printf("Assistant: %s%n", response);

            switch (response.intent()) {
                case NEXT_QUESTION -> {
//                    history.add(new UserMessage(line));
                    String res = generateNextQuestion(chatModel, history);
                    System.out.printf("Assistant: %s%n", res);
                    history.add(new AssistantMessage(res));
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
                    String res = continueInterview(chatModel, history,line);
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

    
    private String generateNextQuestion(ChatModel chatModel, List<Message> history) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是一名 Java 面试官。")
                .build()
                .prompt()
                .user("请提出一道 Java 面试题，只输出问题本身。")
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
                .user("表示不会或需要提示")
                .messages(history)
                .call()
                .content();
    }

    private String continueInterview(ChatModel chatModel, List<Message> history,String line ) {
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
