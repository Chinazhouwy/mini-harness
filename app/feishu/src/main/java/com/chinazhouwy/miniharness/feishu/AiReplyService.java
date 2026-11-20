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
        log.info("==== 发给模型的请求开始 ====");
        log.info("bot={} name={} sessionId={} userId={}", bot.id(), bot.name(), sessionId, "feishu-user");
        log.info("systemPrompt={}", agentFactory.systemPrompt(bot));
        log.info("userMessage={}", display(userText));
        log.info("==== 发给模型的请求结束 ====");
        try (var agent = agentFactory.create(bot)) {
            String answer = agent.call(userText, context)
                    .block(Duration.ofMinutes(2))
                    .getTextContent();
            log.info("AgentScope 会话完成 bot={} sessionId={}", bot.name(), sessionId);
            String text = answer == null || answer.isBlank() ? "（模型返回了空回复）" : answer;
            log.info("模型回复={}", display(text));
            return "【" + bot.name() + "】" + text;
        } catch (Exception e) {
            log.error("AgentScope 调用模型失败：{}", e.getMessage(), e);
            throw e;
        }
    }

    /** 把换行、制表等控制字符转成可见转义，避免日志被拆成多行。 */
    private static String display(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(value.length() + 2).append('\'');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(ch);
            }
        }
        return out.append('\'').toString();
    }
}
