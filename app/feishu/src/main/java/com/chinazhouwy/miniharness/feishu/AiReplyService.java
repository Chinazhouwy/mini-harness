package com.chinazhouwy.miniharness.feishu;

import io.agentscope.core.agent.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/** 把飞书文本交给 AgentScope4j 生成回答；不注册工具，也不保留跨消息会话。 */
@Service
public class AiReplyService {
    private static final Logger log = LoggerFactory.getLogger(AiReplyService.class);

    private final AgentFactory agentFactory;

    public AiReplyService(AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    public String reply(String userText) {
        RuntimeContext context = RuntimeContext.builder()
                .userId("feishu-user")
                .sessionId(UUID.randomUUID().toString())
                .build();
        try (var agent = agentFactory.create()) {
            String answer = agent.call(userText, context)
                    .block(Duration.ofMinutes(2))
                    .getTextContent();
            return answer == null || answer.isBlank() ? "（模型返回了空回复）" : answer;
        } catch (Exception e) {
            log.error("AgentScope 调用模型失败：{}", e.getMessage(), e);
            throw e;
        }
    }
}
