package com.chinazhouwy.miniharness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class ChatDemo implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ChatDemo.class);

    private final ChatClient chatClient;

    public ChatDemo(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public void run(String... args) {
        String prompt = "用一句话介绍什么是 Spring AI";

        log.info("=== Spring AI 快速入门 ===");
        log.info("Prompt: {}", prompt);
        log.info("正在调用模型...");

        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        log.info("模型回复: {}", response);
        log.info("=== 完成 ===");
    }
}
