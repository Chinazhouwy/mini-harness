package com.chinazhouwy.miniharness;

import io.micrometer.common.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * 当前最小可运行的控制台面试原型。
 *
 * <p>它不是通用 Harness，也还不是一套严谨的面试业务系统。这个类的价值在于把几个
 * Spring AI 能力真正串起来，让后续的抽象来自已经遇到的问题：</p>
 *
 * <ol>
 *     <li>先让模型识别用户意图；</li>
 *     <li>按意图出题、提示、讲解或评测；</li>
 *     <li>将回答和评测保存为最小 JSON 快照；</li>
 *     <li>根据当前回答生成一句反馈和一个追问。</li>
 * </ol>
 *
 * <p>阅读本类时，优先看 {@link #test()}：它展示了“用户输入 -> 意图识别 -> Java switch
 * 控制流程 -> 模型调用 -> 保存历史”的完整路径。</p>
 */
public class MiniHarnessDemo {

    
    private static final Logger log = LoggerFactory.getLogger(MiniHarnessDemo.class);

    public static void main(String[] args) {
        new MiniHarnessDemo().test();
    }

    /**
     * 启动一个基于标准输入的面试循环。
     *
     * <p>输入 {@code exit} 或 {@code quit} 退出；其他输入会先交给意图识别模型，
     * 再由下面的 {@code switch} 选择具体业务动作。这个 switch 很重要：模型只建议
     * 意图，真正执行“出下一题、评测还是退出”的仍是 Java 代码。</p>
     */
    public void test() {
        log.info("Starting MiniHarnessDemo...");

        // attempts 保存“题目 + 回答 + 评测”，面向复盘。
        // history 保存多轮消息，面向给模型提供上下文。
        // 这两个列表现在都在内存中，启动时会尝试从 data/ 目录恢复。
        List<QuestionAttempt> attempts = new ArrayList<>();
        List<Message> history = new ArrayList<>();


        InterviewState interviewState = InterviewState.IDLE;

        Result result = InterviewSession.loadHistoryInfo(attempts, history);

        String currentQuestion = "";

        ChatClient intentClient = InterviewLLMService.createIntentClient();
        
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {

            log.info("now InterviewState {}",interviewState);

            InterviewSession.saveHistoryInfo(result.attempts(), result.history());

            System.out.print("\n你 > ");
            String line = scanner.nextLine();

            if (exitCommend(line)) {
                System.out.println("退出对话。");
                break;
            }

            System.out.printf("User: %s%n", line);

            // 先让模型把自然语言归类为 InterviewIntent。
            IntentResult response = InterviewLLMService.getIntentResult(intentClient, line, result);

            result.history().add(new UserMessage(line));

            switch (response.intent()) {
                case CHAT -> {
                    String res = InterviewLLMService.chat(result.history());
                    System.out.printf("Assistant: %s%n", res);
                    result.history().add(new AssistantMessage(res));
                    interviewState = InterviewState.IDLE;
                }
                case QUESTION -> {
                    // currentQuestion 只保存当前题的文本。后续真正抽取领域模型后，
                    String res = InterviewLLMService.generateQuestion(result.history());
                    System.out.printf("Assistant: %s%n", res);
                    result.history().add(new AssistantMessage(res));
                    currentQuestion = res;
                    interviewState = InterviewState.WAITING_ANSWER;
                }
                case HINT -> {
                    String res = InterviewLLMService.giveHint(result.history());
                    System.out.printf("Assistant: %s%n", res);
                    result.history().add(new AssistantMessage(res));
                    interviewState = InterviewState.WAITING_ANSWER;
                }
                case EXPLAIN -> {
                    String res = InterviewLLMService.explainQuestion( result.history());
                    System.out.printf("Assistant: %s%n", res);
                    result.history().add(new AssistantMessage(res));
                    interviewState = InterviewState.DISCUSSING;
                }
                case FOLLOW_UP -> {
                    String res = InterviewLLMService.followUpQuestion( result.history());
                    System.out.printf("Assistant: %s%n", res);
                    result.history().add(new AssistantMessage(res));
                    interviewState = InterviewState.DISCUSSING;
                }
                case ANSWER -> {
                    // 刚启动还没有当前题时，不应把普通聊天误当成面试回答。
                    if (StringUtils.isNotEmpty(currentQuestion)) {
                        // entity(AnswerEvaluation.class) 演示结构化输出：
                        // 模型返回的不再只是文字，而是被 Spring AI 映射为一个 Java Record。
                        AnswerEvaluation evaluation = InterviewLLMService.createEvaluatorClient(result.history());
                        System.out.printf("Assistant: evaluation 评分：%d，评价：%s，遗漏：%s%n", evaluation.score(),
                                evaluation.comment(), evaluation.missingPoint());
                        result.attempts().add(new QuestionAttempt(currentQuestion, line,evaluation));
                    }
                    // 评测和追问目前是两个独立模型调用：
                    // 前者给结构化的最小评分，后者给面试中的自然语言反馈。
                    // 这正是后续要观察和改造的地方：两次调用的结论可能并不完全一致。
                    String res = InterviewLLMService.continueInterview(result.history(), currentQuestion, line);
                    System.out.printf("Assistant: %s%n", res);
                    result.history().add(new AssistantMessage(res));
                    interviewState = InterviewState.DISCUSSING;
                }
                case EXIT -> {
                    System.out.println("退出面试。");
                    return;
                }
                default -> System.out.println("未识别的意图，请重新输入。");
            }

        }
    }


    /** 这是本地命令的快速出口，不需要为“exit”再额外花一次模型调用。 */
    private static boolean exitCommend(String line) {
        return "exit".equalsIgnoreCase(line.trim()) || "quit".equalsIgnoreCase(line.trim());
    }

}
