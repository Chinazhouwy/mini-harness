package com.chinazhouwy.miniharness.feishu;

import io.agentscope.core.agent.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** 把飞书文本与同一会话的最近历史交给 AgentScope4j 生成回答。 */
@Service
public class AiReplyService {
    private static final Logger log = LoggerFactory.getLogger(AiReplyService.class);

    private final AgentFactory agentFactory;
    private final ChatHistoryStore historyStore;

    public AiReplyService(AgentFactory agentFactory, ChatHistoryStore historyStore) {
        this.agentFactory = agentFactory;
        this.historyStore = historyStore;
    }

    public String reply(String chatId, String userText) {
        List<ChatHistoryStore.ChatMessage> history = historyStore.read(chatId);
        String prompt = buildPrompt(history, userText);
        RuntimeContext context = RuntimeContext.builder()
                .userId("feishu-user")
                .sessionId(UUID.randomUUID().toString())
                .build();
        try (var agent = agentFactory.create()) {
            String answer = agent.call(prompt, context)
                    .block(Duration.ofMinutes(2))
                    .getTextContent();
            String reply = answer == null || answer.isBlank() ? "（模型返回了空回复）" : answer;
            historyStore.appendUser(chatId, userText);
            historyStore.appendAssistant(chatId, reply);
            log.info("会话历史已更新 chatId={} 历史条数={} 本次新增=2",
                    mask(chatId), history.size());
            return reply;
        } catch (Exception e) {
            log.error("AgentScope 调用模型失败：{}", e.getMessage(), e);
            throw e;
        }
    }

    private static String buildPrompt(List<ChatHistoryStore.ChatMessage> history, String userText) {
        if (history.isEmpty()) return userText;
        StringBuilder prompt = new StringBuilder();
        prompt.append("以下是同一飞书会话的最近对话，按时间从旧到新：\n");
        for (ChatHistoryStore.ChatMessage message : history) {
            prompt.append("【").append("user".equals(message.role()) ? "用户" : "助手")
                    .append("】").append(message.text()).append('\n');
        }
        prompt.append("\n请结合以上内容回答当前问题：\n").append(userText);
        return prompt.toString();
    }

    private static String mask(String chatId) {
        return chatId == null || chatId.length() <= 8 ? "***" : chatId.substring(0, 8) + "***";
    }
}
