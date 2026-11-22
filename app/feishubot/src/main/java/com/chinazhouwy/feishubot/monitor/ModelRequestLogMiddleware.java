package com.chinazhouwy.feishubot.monitor;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 打印每次真正发给模型的完整消息列表。
 *
 * <p>消息来自 AgentScope 的每次模型调用，包含记忆/历史恢复出来的消息，
 * 也包含工具调用结果；这里只做日志，不改请求内容。
 */
public class ModelRequestLogMiddleware implements MiddlewareBase {
    private static final Logger log = LoggerFactory.getLogger(ModelRequestLogMiddleware.class);
    private static final AtomicLong REQUEST_NO = new AtomicLong();

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_MODEL_CALL);
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        long no = REQUEST_NO.incrementAndGet();
        List<Msg> messages = input.messages();
        var toolNames = input.tools().stream().map(ToolSchema::getName).sorted().toList();
        log.info("==== 真实模型请求 #{} 开始 sessionId={} userId={} 消息数={} 工具数={} 工具={} ====",
                no, ctx.getSessionId(), ctx.getUserId(), messages.size(), toolNames.size(), toolNames);
        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            log.info("请求 #{} 消息[{}] role={} content={}", no, i, msg.getRole(), display(msg));
        }
        log.info("==== 真实模型请求 #{} 结束 ====", no);
        return next.apply(input);
    }

    /** 用框架自己的 JSON 序列化，尽量还原真正送给模型的消息结构。 */
    private static String display(Msg msg) {
        try {
            return JsonUtils.getJsonCodec().toJson(msg);
        } catch (Exception e) {
            return "序列化失败: " + e.getMessage();
        }
    }
}
