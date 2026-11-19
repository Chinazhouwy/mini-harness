package com.chinazhouwy.miniharness.feishu;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import java.nio.file.Path;

/** 每次回复新建一个 Agent；AgentScope 按 userId/sessionId 从文件恢复会话。 */
public class AgentFactory {
    private static final String SYSTEM_PROMPT = "你是飞书里的 AI 助手，用简洁、自然的中文回答用户。"
            + "可用工具只有四个：read 读取文件、write 新建文件、edit 修改文件、bash 执行白名单命令。";

    private final OpenAIChatModel model;
    private final JsonFileAgentStateStore stateStore;
    private final FeishuTools tools;

    public AgentFactory(OpenAIChatModel model, String stateDir, FeishuTools tools) {
        this.model = model;
        this.stateStore = new JsonFileAgentStateStore(Path.of(stateDir));
        this.tools = tools;
    }

    public ReActAgent create() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        return ReActAgent.builder()
                .name("feishu-assistant")
                .model(model)
                .toolkit(toolkit)
                .sysPrompt(SYSTEM_PROMPT)
                .stateStore(stateStore)
                .build();
    }
}
