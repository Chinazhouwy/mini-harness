package com.chinazhouwy.miniharness.feishu;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import java.nio.file.Path;

/** 每次回复新建一个 Agent；用哪个 Bot 的人设，由调用方传入。 */
public class AgentFactory {
    private static final String TOOL_HINT = "可用工具有七个：read 读取文件、write 新建文件、edit 修改文件、bash 执行白名单命令、"
            + "web_search 联网搜索、web_fetch 阅读链接、spawn_agent 创建子 Agent 处理独立任务。"
            + "遇到时效性问题用 web_search 搜索、用 web_fetch 读原文；可拆开的独立任务用 spawn_agent 委派，并把返回里的 session_id 告诉用户。";

    private final OpenAIChatModel model;
    private final JsonFileAgentStateStore stateStore;
    private final FeishuTools tools;
    private final DeepSeekWebTools webTools;

    public AgentFactory(OpenAIChatModel model, String stateDir, FeishuTools tools, DeepSeekWebTools webTools) {
        this.model = model;
        this.stateStore = new JsonFileAgentStateStore(Path.of(stateDir));
        this.tools = tools;
        this.webTools = webTools;
    }

    public String systemPrompt(Bot bot) {
        return bot.persona() + TOOL_HINT;
    }

    public ReActAgent create(Bot bot) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        toolkit.registerTool(webTools);
        toolkit.registerAgentTool(tools.spawnAgent(model, stateStore));
        return ReActAgent.builder()
                .name(bot.id())
                .model(model)
                .toolkit(toolkit)
                .sysPrompt(systemPrompt(bot))
                .stateStore(stateStore)
                .middleware(new ModelRequestLogMiddleware())
                .build();
    }

}
