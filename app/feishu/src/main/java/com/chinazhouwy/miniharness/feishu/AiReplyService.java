package com.chinazhouwy.miniharness.feishu;

import io.agentscope.core.agent.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** 用飞书 chat_id 作为 AgentScope 的 sessionId，会话由框架自动落盘和恢复。 */
@Service
public class AiReplyService {
    private static final Logger log = LoggerFactory.getLogger(AiReplyService.class);

    private final AgentFactory agentFactory;

    public AiReplyService(AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    public String reply(String chatId, String userText) {
        RuntimeContext context = RuntimeContext.builder()
                .userId("feishu-user")
                .sessionId(chatId)
                .build();
        try (var agent = agentFactory.create()) {
            String answer = agent.call(userText, context)
                    .block(Duration.ofMinutes(2))
                    .getTextContent();
            log.info("AgentScope 会话完成 sessionId={}", chatId);
            return answer == null || answer.isBlank() ? "（模型返回了空回复）" : answer;
        } catch (Exception e) {
            log.error("AgentScope 调用模型失败：{}", e.getMessage(), e);
            throw e;
        }
    }
}
