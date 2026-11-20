package com.chinazhouwy.miniharness.feishu;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import java.nio.file.Path;

/** 每次回复新建一个 Agent；用哪个 Bot 的人设，由调用方传入。 */
public class AgentFactory {
    private static final String TOOL_HINT = "可用工具有五个：read 读取文件、write 新建文件、edit 修改文件、bash 执行白名单命令、"
            + "spawn_agent 创建子 Agent 处理独立任务。"
            + "遇到可拆开的独立任务时，用 spawn_agent 委派，并把返回里的 session_id 告诉用户，便于后续追问。";

    private final OpenAIChatModel model;
    private final JsonFileAgentStateStore stateStore;
    private final FeishuTools tools;

    public AgentFactory(OpenAIChatModel model, String stateDir, FeishuTools tools) {
        this.model = model;
        this.stateStore = new JsonFileAgentStateStore(Path.of(stateDir));
        this.tools = tools;
    }

    public ReActAgent create(Bot bot) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        toolkit.registerAgentTool(tools.spawnAgent(model, stateStore));
        return ReActAgent.builder()
                .name(bot.id())
                .model(model)
                .toolkit(toolkit)
                .sysPrompt(bot.persona() + TOOL_HINT)
                .stateStore(stateStore)
                .build();
    }

}
