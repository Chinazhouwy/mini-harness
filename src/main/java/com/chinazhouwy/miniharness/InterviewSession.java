package com.chinazhouwy.miniharness;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 一场面试会话的领域草图。
 *
 * <p>它试图把“当前面试处于什么阶段”“已经问过哪些题”“完整对话历史是什么”放在一起。
 * 当前真正运行的控制台 Demo 仍然使用三个局部变量表达这些信息，因此这个类暂时只用于
 * 记录准备抽取的边界，尚未接入实际流程。</p>
 */
public class InterviewSession {

    /** 当前面试阶段，例如正在等候回答或正在讨论反馈。 */
    private InterviewState state;

    /** 本场面试已经生成的题目。 */
    private List<InterviewQuestion> questions;

    /** 用于回放的最小对话历史，不等同于模型每次实际发送的上下文窗口。 */
    private List<StoredMessage> history;

    /**
     * 从 data/ 目录恢复上次练习留下的最小数据。
     *
     * <p>attempts.json 使用项目自己的 Record，history.json 则先读成 {@link StoredMessage}，
     * 再恢复为 Spring AI 的 {@link Message}。这避免把框架 Message 的内部字段长期当成
     * 本项目的数据格式。</p>
     */
    public static @NonNull Result loadHistoryInfo(List<QuestionAttempt> attempts, List<Message> history) {
        StoredSession storedSession = StoredSession.empty();
        try {
            ObjectMapper mapper = new ObjectMapper();
            java.io.File fileAttempts = new java.io.File("data/attempts.json");
            if (fileAttempts.exists()) {
                attempts = mapper.readValue(fileAttempts, new com.fasterxml.jackson.core.type.TypeReference<List<QuestionAttempt>>() {});
            }
            java.io.File fileHistroy = new java.io.File("data/history.json");
            if (fileHistroy.exists()) {
                List<StoredMessage> storedMessages = mapper.readValue(fileHistroy, new com.fasterxml.jackson.core.type.TypeReference<List<StoredMessage>>() {});
                history = toAiMessages(storedMessages);
            }
            java.io.File fileSession = new java.io.File("data/session.json");
            if (fileSession.exists()) {
                storedSession = mapper.readValue(fileSession, StoredSession.class);
            }
        } catch (Exception e) {
            System.err.println("加载本地会话数据失败: " + e.getMessage());
        }
        Result result = new Result(attempts, history, storedSession.state(), storedSession.currentQuestion());
        return result;
    }


    /**
     * 将当前练习保存到 JSON 文件。
     *
     * <p>这里分别保存 attempts 和 history，是为了直观看到两类数据的差别：attempts 面向
     * 评测回放，history 面向多轮上下文。正式系统会需要原子性、版本与错误恢复；Demo
     * 先只保留最容易看懂的文件写入。</p>
     */
    public static void saveHistoryInfo(
            List<QuestionAttempt> attempts,
            List<Message> history,
            InterviewState state,
            String currentQuestion
    ) {
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
                List<StoredMessage> storedMessages = toStoredMessages(history);
                mapper.writer().writeValue(file, storedMessages);
            } catch (Exception e) {
                System.err.println("保存 history.json 失败: " + e.getMessage());
            }
        }

        // 不管 history 是否为空都保存 session：它记录的是“当前流程处于哪里”，而不是对话内容。
        try {
            ObjectMapper mapper = new ObjectMapper();
            java.io.File file = new java.io.File("data/session.json");
            java.io.File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            mapper.writer().writeValue(file, new StoredSession(state, currentQuestion));
        } catch (Exception e) {
            System.err.println("保存 session.json 失败: " + e.getMessage());
        }
    }

    /** 将 Spring AI 运行时消息压缩为自己的 JSON 格式。当前只处理用户与助手两种消息。 */
    public static List<StoredMessage> toStoredMessages(List<Message> history) {
        List<StoredMessage> storedMessages = new ArrayList<>();

        for (Message message : history) {
            if (message instanceof UserMessage userMessage) {
                storedMessages.add(new StoredMessage(
                        ChatRole.USER,
                        userMessage.getText()
                ));
            } else if (message instanceof AssistantMessage assistantMessage) {
                storedMessages.add(new StoredMessage(
                        ChatRole.ASSISTANT,
                        assistantMessage.getText()
                ));
            }
        }

        return storedMessages;
    }

    /** 将磁盘中的简化消息恢复成能再次发送给模型的 Spring AI Message。 */
    public static List<Message> toAiMessages(List<StoredMessage> storedMessages) {
        List<Message> history = new ArrayList<>();

        for (StoredMessage message : storedMessages) {
            switch (message.role()) {
                case USER ->
                        history.add(new UserMessage(message.content()));

                case ASSISTANT ->
                        history.add(new AssistantMessage(message.content()));
            }
        }

        return history;
    }
}
