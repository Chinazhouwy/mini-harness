package com.chinazhouwy.miniharness.feishu;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import java.nio.file.Path;

/** 每次回复新建一个 Agent；AgentScope 按 userId/sessionId 从文件恢复会话。 */
public class AgentFactory {
    private static final String SYSTEM_PROMPT = "你是飞书里的 AI 助手，用简洁、自然的中文回答用户。";

    private final OpenAIChatModel model;
    private final JsonFileAgentStateStore stateStore;

    public AgentFactory(OpenAIChatModel model, String stateDir) {
        this.model = model;
        this.stateStore = new JsonFileAgentStateStore(Path.of(stateDir));
    }

    public ReActAgent create() {
        return ReActAgent.builder()
                .name("feishu-assistant")
                .model(model)
                .sysPrompt(SYSTEM_PROMPT)
                .stateStore(stateStore)
                .build();
    }
}
