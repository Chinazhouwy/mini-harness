package com.chinazhouwy.miniharness.runtime.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 脚本化模型端口：按顺序返回预置回复，用于离线验证调度与路由行为。
 * 记录每次收到的提示词，供测试断言「问模型什么」；调用计数用于策略对比实验。
 */
public class ScriptedModelPort implements ModelPort {

    private final Deque<String> replies = new ArrayDeque<>();
    private final List<String> prompts = new ArrayList<>();
    private final String fallbackReply;

    public ScriptedModelPort(List<String> replies, String fallbackReply) {
        this.replies.addAll(replies);
        this.fallbackReply = fallbackReply;
    }

    @Override
    public synchronized ModelReply complete(ModelRequest request) {
        prompts.add(request.user());
        String polled = replies.poll();
        String content = polled != null ? polled : fallbackReply;
        return new ModelReply(content, request.user().length() / 4, content.length() / 4, 5);
    }

    public synchronized int callCount() {
        return prompts.size();
    }

    public synchronized List<String> receivedPrompts() {
        return List.copyOf(prompts);
    }
}
