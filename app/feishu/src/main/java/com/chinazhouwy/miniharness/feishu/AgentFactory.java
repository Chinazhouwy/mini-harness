package com.chinazhouwy.miniharness.feishu;

import io.agentscope.core.ReActAgent;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

/** 每次回复创建一个最小 Agent，避免多条飞书消息共享同一会话上下文。 */
public class AgentFactory {
    private static final String SYSTEM_PROMPT = "你是飞书里的 AI 助手，用简洁、自然的中文回答用户。";

    private final OpenAIChatModel model;

    public AgentFactory(OpenAIChatModel model) {
        this.model = model;
    }

    public ReActAgent create() {
        return ReActAgent.builder()
                .name("feishu-assistant")
                .model(model)
                .sysPrompt(SYSTEM_PROMPT)
                .build();
    }
}
