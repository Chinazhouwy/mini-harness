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
        // 这里手工构建 OpenAI-compatible 客户端。
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
        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .openAiClientAsync(openAiClient.async())
                .options(
                        OpenAiChatOptions
                                .builder()
                                .model(MODEL)
                                .build()
                )
                .build();

        ChatClient deepseekClient = ChatClient.builder(chatModel)
                .defaultSystem("你是一个简洁、准确的技术学习助手。")
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
            String response = deepseekClient
                    .prompt()
                    .user(line)
                    .messages(history)
                    .call()
                    .content();
            history.add(new UserMessage(line));
            history.add(new AssistantMessage(response));
            System.out.printf("Assistant: %s%n", response);
        }
    }

}
