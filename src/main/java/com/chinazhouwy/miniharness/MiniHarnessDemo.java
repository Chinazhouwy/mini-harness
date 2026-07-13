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


        Result result = InterviewSession.loadHistoryInfo(attempts, history);

        // 这两个值以前每次启动都从空开始，导致“出题后退出、重启再回答”丢失上下文。
        // 现在从 session.json 恢复；若旧数据还没有该文件，则安全地回到 IDLE。
        InterviewState interviewState = result.state();
        String currentQuestion = result.currentQuestion();

        ChatClient intentClient = InterviewLLMService.createIntentClient();
        
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {

            log.info("now InterviewState {}",interviewState);

            System.out.print("\n你 > ");
            String line = scanner.nextLine();

            if (exitCommend(line)) {
                // exit / quit 是本地命令，不需要发送给模型，也不必作为面试对话保存。
                // 但退出前仍要把上一轮已经完成的出题、回答或追问强制写入磁盘，
                // 否则用户在回答后立刻退出时，最后一轮会丢失。
                InterviewSession.saveHistoryInfo(result.attempts(), result.history(), interviewState, currentQuestion);
                System.out.println("退出对话。");
                break;
            }

            System.out.printf("User: %s%n", line);
            System.out.println("[FLOW] 控制台收到用户输入；先调用意图识别，此时它尚未写入 history。");

            // 先让模型把自然语言归类为 InterviewIntent。
            IntentResult response = InterviewLLMService.getIntentResult(intentClient, line, result);

            // ANSWER / HINT / EXPLAIN / FOLLOW_UP 都依赖“当前确实有一道尚在讨论的题”。
            // 以前只有 history 恢复时，currentQuestion 仍为空，程序却继续调用评测和反馈模型，
            // 于是看起来在对话，实际上没有产生有效评测。这里由 Java 根据会话快照提前拦住。
            if (requiresCurrentQuestion(response.intent())
                    && !hasCurrentQuestion(interviewState, currentQuestion)) {
                System.out.println("当前没有可回答的面试题。请先输入“下一题”。");
                continue;
            }

            // 只有意图识别完成后，才把用户消息加入共享 history。这样后续的出题、评测、
            // 提示等调用都能看到本轮输入；但意图识别不会把“当前用户输入”重复发送两次。
            result.history().add(new UserMessage(line));
            System.out.printf("[FLOW] 意图识别结果为 %s；用户消息已加入 history，当前历史共 %d 条。%n",
                    response.intent(), result.history().size());

            switch (response.intent()) {
                case CHAT -> {
                    String res = InterviewLLMService.chat(result.history());
                    System.out.printf("Assistant: %s%n", res);
                    result.history().add(new AssistantMessage(res));
                    //  当前正在等用户回答 Java 题
                    //→ 用户说：“我先问个别的问题”
                    //→ 识别为 CHAT
                    //→ 状态被重置为 IDLE
                    //  interviewState = InterviewState.IDLE;
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
                    // 这一条 exit 来自模型的意图识别，不是上面的本地 exit 命令。
                    // 此时用户原话已经加入 history，因此必须先保存，再 return；
                    // 否则这次“结束面试”的输入以及之前刚完成的状态都不会落盘。
                    InterviewSession.saveHistoryInfo(result.attempts(), result.history(), interviewState, currentQuestion);
                    System.out.println("退出面试。");
                    return;
                }
                default -> System.out.println("未识别的意图，请重新输入。");
            }

            // 一轮交互的完整顺序是：用户消息 -> 模型处理 -> 助手消息 / 评测结果。
            // 所有普通分支走到这里时，本轮数据已经齐全，立即保存而不是等下一次输入。
            // 这样即使用户下一秒关闭控制台，刚才的对话也已经写入 data/ 目录。
            InterviewSession.saveHistoryInfo(result.attempts(), result.history(), interviewState, currentQuestion);

        }
    }


    /** 这是本地命令的快速出口，不需要为“exit”再额外花一次模型调用。 */
    private static boolean exitCommend(String line) {
        return "exit".equalsIgnoreCase(line.trim()) || "quit".equalsIgnoreCase(line.trim());
    }

    /** 判断本次意图是否必须建立在某一道已出题的上下文上。 */
    private static boolean requiresCurrentQuestion(InterviewIntent intent) {
        return switch (intent) {
            case ANSWER, HINT, EXPLAIN, FOLLOW_UP -> true;
            case QUESTION, CHAT, EXIT -> false;
        };
    }

    /**
     * 当前 Demo 不做复杂状态机，只验证最小事实：有当前题，而且还处于可讨论它的阶段。
     *
     * <p>DISCUSSING 也允许回答，因为用户可能正在补充上一轮答案，或回答面试官的追问。</p>
     */
    private static boolean hasCurrentQuestion(InterviewState state, String currentQuestion) {
        return StringUtils.isNotEmpty(currentQuestion)
                && (state == InterviewState.WAITING_ANSWER || state == InterviewState.DISCUSSING);
    }

}
