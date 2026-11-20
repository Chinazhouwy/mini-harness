package com.chinazhouwy.miniharness.feishu;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.UserId;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Spring 常驻的飞书长连接监听器：启动时建立 WebSocket，destroy 时关闭连接和线程池。
 */
@Component
public class FeishuBotListener {
    private static final Logger log = LoggerFactory.getLogger(FeishuBotListener.class);

    private final AppCredentials credentials;
    private final FeishuSender sender;
    private final AiReplyService aiReplyService;
    private final ExecutorService workers;
    private final com.lark.oapi.ws.Client wsClient;

    public FeishuBotListener(AppCredentials credentials,
                             FeishuSender sender,
                             AiReplyService aiReplyService) {
        this.credentials = credentials;
        this.sender = sender;
        this.aiReplyService = aiReplyService;

        ThreadFactory workerFactory = Thread.ofVirtual().name("feishu-ai-worker-", 0).factory();
        this.workers = Executors.newThreadPerTaskExecutor(workerFactory);

        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        log.info("事件线程收到 im.message.receive_v1，提交虚拟线程");
                        workers.submit(() -> handleMessage(event));
                    }
                })
                .build();
        this.wsClient = new com.lark.oapi.ws.Client.Builder(
                credentials.appId(), credentials.appSecret())
                .eventHandler(dispatcher)
                .build();
    }

    /** Spring Bean 创建后由生命周期注解触发，等价于原来的 main 启动长连接。 */
    @jakarta.annotation.PostConstruct
    public void start() {
        wsClient.start();
        log.info("飞书长连接已启动，appId={}", mask(credentials.appId()));
    }

    @PreDestroy
    public void stop() {
        workers.shutdownNow();
        wsClient.close();
        log.info("飞书长连接已关闭");
    }

    private void handleMessage(P2MessageReceiveV1 event) {
        log.info("工作线程 {} 开始执行", Thread.currentThread().getName());
        try {
            if (event == null || event.getEvent() == null) {
                log.info("丢弃空事件");
                return;
            }
            EventMessage message = event.getEvent().getMessage();
            log.info("工作线程开始处理；message={}；sender={}",
                    describe(message), describeSender(event.getEvent().getSender()));
            if (message == null) {
                log.info("丢弃无 message 字段的事件");
                return;
            }
            if (!"text".equals(message.getMessageType())) {
                log.info("忽略非文本消息 type={}", message.getMessageType());
                return;
            }
            String text = FeishuMessageCodec.extractText(message.getContent());
            String chatId = message.getChatId();
            log.info("文本解析结果：长度={}；内容={}；chatId={}",
                    text == null ? 0 : text.length(), display(text), display(chatId));
            if (text == null || text.isBlank() || chatId == null || chatId.isBlank()) {
                log.info("忽略空消息或无 chat_id 的消息 messageId={}", message.getMessageId());
                return;
            }
            log.info("开始调用 AI messageId={} chatId={}", message.getMessageId(), chatId);
            long start = System.nanoTime();
            String answer = aiReplyService.reply(chatId, text);
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            log.info("AI 回复生成成功 messageId={}，耗时={}ms，长度={}，内容={}",
                    message.getMessageId(), latencyMs,
                    answer == null ? 0 : answer.length(), display(answer));
            sender.sendText(chatId, answer);
            log.info("回复发送成功 messageId={} chatId={}", message.getMessageId(), chatId);
        } catch (Exception e) {
            log.error("处理消息失败 {}", e.getMessage(), e);
        }
    }

    private static String describe(EventMessage m) {
        if (m == null) return "null";
        return "messageId=" + display(m.getMessageId())
                + ", rootId=" + display(m.getRootId())
                + ", parentId=" + display(m.getParentId())
                + ", chatId=" + display(m.getChatId())
                + ", chatType=" + display(m.getChatType())
                + ", threadId=" + display(m.getThreadId())
                + ", type=" + display(m.getMessageType())
                + ", createTime=" + display(m.getCreateTime())
                + ", updateTime=" + display(m.getUpdateTime())
                + ", rawContent=" + display(m.getContent())
                + ", mentions=" + (m.getMentions() == null ? 0 : m.getMentions().length)
                + ", userAgent=" + display(m.getUserAgent());
    }

    private static String describeSender(EventSender sender) {
        if (sender == null) return "null";
        UserId ids = sender.getSenderId();
        return "senderType=" + display(sender.getSenderType())
                + ", tenantKey=" + display(sender.getTenantKey())
                + ", openId=" + display(ids == null ? null : ids.getOpenId())
                + ", userId=" + display(ids == null ? null : ids.getUserId())
                + ", unionId=" + display(ids == null ? null : ids.getUnionId());
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

    private static String mask(String appId) {
        if (appId == null || appId.length() <= 6) return "***";
        return appId.substring(0, 6) + "***";
    }
}