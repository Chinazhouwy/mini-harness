package com.chinazhouwy.miniharness.feishu;

import io.agentscope.core.agent.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** 用 bot + chat_id 作为 AgentScope 的 sessionId，会话由框架自动落盘和恢复。 */
@Service
public class AiReplyService {
    private static final Logger log = LoggerFactory.getLogger(AiReplyService.class);

    private final AgentFactory agentFactory;
    private final BotRegistry botRegistry;

    public AiReplyService(AgentFactory agentFactory, BotRegistry botRegistry) {
        this.agentFactory = agentFactory;
        this.botRegistry = botRegistry;
    }

    public String reply(String chatId, String userText) {
        Bot bot = botRegistry.defaultBot();
        String sessionId = bot.id() + ":" + chatId;
        RuntimeContext context = RuntimeContext.builder()
                .userId("feishu-user")
                .sessionId(sessionId)
                .build();
        try (var agent = agentFactory.create(bot)) {
            String answer = agent.call(userText, context)
                    .block(Duration.ofMinutes(2))
                    .getTextContent();
            log.info("AgentScope 会话完成 bot={} sessionId={}", bot.name(), sessionId);
            String text = answer == null || answer.isBlank() ? "（模型返回了空回复）" : answer;
            return "【" + bot.name() + "】" + text;
        } catch (Exception e) {
            log.error("AgentScope 调用模型失败：{}", e.getMessage(), e);
            throw e;
        }
    }
}
