package com.chinazhouwy.miniharness.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

/**
 * 观察每轮流式模型请求和响应片段。它位于工具循环内侧，所以工具结果回填后
 * 的下一次模型调用也会再次经过这里；打印的是框架对象，不是原始 HTTP 报文。
 * 学习日志可能包含提示词、工具结果和模型内容，只应在可信环境中启用。
 */
public class TraceStreamAdvisor implements StreamAdvisor {


    private static final Logger log = LoggerFactory.getLogger(TraceStreamAdvisor.class);

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
                                                 StreamAdvisorChain streamAdvisorChain) {
        log.info("[模型请求消息] {}", chatClientRequest.prompt().getInstructions());
        // Reactor 操作符返回新的 Flux，必须返回它，日志回调才属于被订阅的链。
        // 一轮流式响应会有多个片段；这里不保证每条日志都是完整消息。
        return streamAdvisorChain.nextStream(chatClientRequest)
                .doOnNext(response -> log.info("[模型响应片段] {}", response.chatResponse()));
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
