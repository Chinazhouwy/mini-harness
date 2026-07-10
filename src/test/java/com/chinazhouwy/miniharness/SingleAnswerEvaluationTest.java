package com.chinazhouwy.miniharness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@SpringBootTest
@Tag("llm")
class SingleAnswerEvaluationTest {

    private static final Logger log = LoggerFactory.getLogger(SingleAnswerEvaluationTest.class);

    private static final String QUESTION = """
            线程池提交一个任务后的完整执行流程是什么？\
            """;

    private static final String CANDIDATE_ANSWER = """
            任务提交后，线程池会先创建核心线程执行任务。
            核心线程满了以后，任务会进入阻塞队列。
            队列满了以后继续创建线程，达到最大线程数以后执行拒绝策略。\
            """;

    private static final String EVALUATION_PROMPT = """
            你是一名严格但客观的高级 Java 工程师面试官。

            请评测候选人对下面问题的回答。

            问题：
            %s

            候选人回答：
            %s

            请按下面结构回答：

            1. 回答正确的部分
            2. 回答遗漏的部分
            3. 回答中错误或不够严谨的部分
            4. 你会继续追问的问题
            5. 对这段回答的总体判断

            要求：
            - 不要因为回答听起来流畅就默认其完整。
            - 区分"核心流程正确"和"真正理解实现边界"。
            - 不要虚构候选人没有说过的内容。
            - 暂时不要输出 JSON。\
            """.formatted(QUESTION, CANDIDATE_ANSWER);

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    private ChatClient chatClient;

    @BeforeEach
    void setUp() {
        String apiKey = System.getenv("AI_API_KEY");
        Assumptions.assumeTrue(
                apiKey != null && !apiKey.isBlank(),
                "跳过：未设置 AI_API_KEY 环境变量。设置 AI_API_KEY、AI_BASE_URL、AI_MODEL 后运行 mvn test -Dgroups=\"llm\" 执行此测试。"
        );

        String baseUrl = System.getenv("AI_BASE_URL");
        Assumptions.assumeTrue(
                baseUrl != null && !baseUrl.isBlank(),
                "跳过：未设置 AI_BASE_URL 环境变量。"
        );

        String model = System.getenv().getOrDefault("AI_MODEL", "gpt-4o");

        log.info("模型配置: baseUrl={}, model={}", baseUrl, model);
        chatClient = chatClientBuilder.build();
    }

    @Test
    void evaluateSingleAnswer() throws IOException {
        log.info("=== 开始单题回答评测 ===");
        log.info("问题: {}", QUESTION);
        log.info("候选人回答: {}", CANDIDATE_ANSWER);
        log.info("正在调用模型...");

        String modelOutput;
        try {
            modelOutput = chatClient.prompt()
                    .user(EVALUATION_PROMPT)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("模型调用失败: {}", e.getMessage(), e);
            throw new AssertionError("模型调用失败，请检查 AI_API_KEY、AI_BASE_URL 和 AI_MODEL 配置是否正确。错误: " + e.getMessage(), e);
        }

        log.info("=== 模型原始输出 ===\n{}", modelOutput);
        log.info("=== 评测完成 ===");

        // Save output to target/experiments/
        saveExperimentOutput(modelOutput);

        // Basic assertions on the output
        org.junit.jupiter.api.Assertions.assertNotNull(modelOutput, "模型输出不应为 null");
        org.junit.jupiter.api.Assertions.assertFalse(modelOutput.isBlank(), "模型输出不应为空");
    }

    private void saveExperimentOutput(String modelOutput) throws IOException {
        Path outputDir = Paths.get("target", "experiments");
        Files.createDirectories(outputDir);

        Path outputFile = outputDir.resolve("001-single-answer-evaluation-output.md");

        String model = System.getenv().getOrDefault("AI_MODEL", "unknown");
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        String content = """
                # 实验 001：单题回答评测 — 模型输出

                - **执行时间**: %s
                - **使用模型**: %s

                ## 问题

                %s

                ## 候选人回答

                %s

                ## 模型原始输出

                %s
                """.formatted(timestamp, model, QUESTION, CANDIDATE_ANSWER, modelOutput);

        Files.writeString(outputFile, content);
        log.info("实验输出已保存到: {}", outputFile.toAbsolutePath());
    }
}
