package com.chinazhouwy.miniharness.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

/**
 * 观察每轮流式模型请求和聚合响应。它位于工具循环内侧，所以工具结果回填后
 * 的下一次模型调用也会再次经过这里；打印的是框架对象，不是原始 HTTP 报文。
 * 学习日志可能包含提示词、工具结果和模型内容，只应在可信环境中启用。
 */
public class TraceStreamAdvisor implements StreamAdvisor {


    private static final Logger log = LoggerFactory.getLogger(TraceStreamAdvisor.class);

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                 StreamAdvisorChain streamAdvisorChain) {
        // 每次订阅独立聚合；原始片段仍然继续向下游流式传递。
        return Flux.defer(() -> new ChatClientMessageAggregator().aggregateChatClientResponse(
                streamAdvisorChain.nextStream(chatClientRequest), response -> {
                    ChatResponse chatResponse = response.chatResponse();
                    if (chatResponse == null) {
                        return;
                    }
                    for (Generation generation : chatResponse.getResults()) {
                        AssistantMessage message = generation.getOutput();
                        log.info("[模型本轮响应] 文本={} 工具调用={} 结束原因={} 用量={}",
                                message.getText(), message.getToolCalls(),
                                generation.getMetadata().getFinishReason(), chatResponse.getMetadata().getUsage());
                    }
                }));
    }

    @Override
    public String getName() {
        return "TraceStreamAdvisor";
    }

    @Override
    public int getOrder() {
        // 较大的 order 位于工具循环之后；循环重入下游链时可以观察每一轮。
        return ToolCallingAdvisor.DEFAULT_ORDER + 1;
    }
}
