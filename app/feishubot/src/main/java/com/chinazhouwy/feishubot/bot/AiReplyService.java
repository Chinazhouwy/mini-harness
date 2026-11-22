package com.chinazhouwy.feishubot.bot;

import com.chinazhouwy.feishubot.messaging.FeishuSender;
import com.chinazhouwy.feishubot.monitor.AgentEventStore;
import com.chinazhouwy.feishubot.schedule.BotTaskScheduler;
import com.chinazhouwy.feishubot.schedule.SchedTask;
import com.chinazhouwy.feishubot.schedule.ScheduleTaskTool;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** 用 bot + chat_id 作为 AgentScope 的 sessionId，会话由框架自动落盘和恢复。 */
@Service
public class AiReplyService {
    private static final Logger log = LoggerFactory.getLogger(AiReplyService.class);

    private final AgentFactory agentFactory;
    private final BotRegistry botRegistry;
    private final AgentEventStore eventStore;
    private final BotTaskScheduler scheduler;
    private final FeishuSender sender;

    public AiReplyService(AgentFactory agentFactory, BotRegistry botRegistry, AgentEventStore eventStore,
                          BotTaskScheduler scheduler, FeishuSender sender) {
        this.agentFactory = agentFactory;
        this.botRegistry = botRegistry;
        this.eventStore = eventStore;
        this.scheduler = scheduler;
        this.sender = sender;
    }

    /** 由监听器创建一次上下文，保证收到、Agent 调用、发送同属一个 traceId。 */
    public RuntimeContext newContext(String chatId, String messageId) {
        Bot bot = botRegistry.defaultBot();
        return eventStore.newContext(bot.id() + ":" + chatId, messageId);
    }

    public String reply(String chatId, String userText, RuntimeContext context) {
        Bot bot = botRegistry.defaultBot();
        var scheduleTask = new ScheduleTaskTool(scheduler, bot.id(), chatId, this::runScheduled);
        return run(bot, userText, context, scheduleTask);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreScheduledTasks() {
        scheduler.restore(this::runScheduled);
    }

    private void runScheduled(SchedTask task) {
        RuntimeContext context = eventStore.newContext(task.botId() + ":task:" + task.taskId(), null);
        eventStore.appEvent(context, "SCHEDULED_TASK", "START", task.taskId(), task.instruction());
        try {
            Bot bot = botRegistry.requireBot(task.botId());
            String answer = run(bot, task.instruction(), context, null);
            sender.sendText(task.chatId(), answer);
            eventStore.appEvent(context, "MESSAGE_SENT", "OK", "feishu", answer);
            eventStore.appEvent(context, "SCHEDULED_TASK", "OK", task.taskId(), null);
        } catch (Exception e) {
            eventStore.appEvent(context, "SCHEDULED_TASK", "FAILED", task.taskId(), e.getMessage());
            log.error("定时任务执行失败 bot={} taskId={}", task.botId(), task.taskId(), e);
        }
    }

    private String run(Bot bot, String userText, RuntimeContext context, ScheduleTaskTool scheduleTask) {
        String sessionId = context.getSessionId();
        log.info("==== 发给模型的请求开始 ====");
        log.info("bot={} name={} sessionId={} userId={}", bot.id(), bot.name(), sessionId, "feishu-user");
        log.info("systemPrompt={}", agentFactory.systemPrompt(bot));
        log.info("userMessage={}", display(userText));
        log.info("==== 发给模型的请求结束 ====");
        try (var agent = agentFactory.create(bot, scheduleTask)) {
            String answer = agent.streamEvents(userText, context)
                    .doOnNext(event -> eventStore.agentEvent(context, event))
                    .filter(AgentResultEvent.class::isInstance)
                    .map(event -> ((AgentResultEvent) event).getResult())
                    .filter(result -> result != null && result.getTextContent() != null)
                    .map(result -> result.getTextContent())
                    .blockLast(Duration.ofMinutes(2));
            String text = answer == null || answer.isBlank() ? "（模型返回了空回复）" : answer;
            eventStore.appEvent(context, "AGENT_REPLY", "OK", bot.name(), text);
            log.info("AgentScope 会话完成 bot={} sessionId={}", bot.name(), sessionId);
            log.info("模型回复={}", display(text));
            return "【" + bot.name() + "】" + text;
        } catch (Exception e) {
            eventStore.appEvent(context, "AGENT_REPLY", "FAILED", bot.name(), String.valueOf(e.getMessage()));
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
