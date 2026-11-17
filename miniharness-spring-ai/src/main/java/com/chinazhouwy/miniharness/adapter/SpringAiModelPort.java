package com.chinazhouwy.miniharness.adapter;

import com.chinazhouwy.miniharness.runtime.model.ModelPort;
import com.chinazhouwy.miniharness.runtime.model.ModelReply;
import com.chinazhouwy.miniharness.runtime.model.ModelRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;

/**
 * ModelPort 的 Spring AI 适配器：把 runtime 的框架无关契约接到 OpenAiChatModel。
 * MiniHarness 内核不感知 Spring AI 类型；本类是唯一的桥。
 * 失败语义：模型调用异常向上抛出（NoopModelPort 模式下根本不会走到这里）。
 */
public class SpringAiModelPort implements ModelPort {

    private final OpenAiChatModel chatModel;

    public SpringAiModelPort(OpenAiChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public ModelReply complete(ModelRequest request) {
        ChatClient client = ChatClient.builder(chatModel).build();
        long start = System.nanoTime();
        String content = client.prompt()
                .system(request.system() == null ? "" : request.system())
                .user(request.user())
                .call()
                .content();
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        // OpenAI 兼容网关下 usage 字段可得性不一，这里以字符数估算；仅用于成本观察，不参与正确性判断。
        return new ModelReply(content == null ? "" : content,
                request.user().length() / 4, (content == null ? 0 : content.length()) / 4, latencyMs);
    }
}
