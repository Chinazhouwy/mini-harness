package com.chinazhouwy.feishubot.bot;

import com.chinazhouwy.feishubot.monitor.AgentEventStore;
import com.chinazhouwy.feishubot.monitor.ModelRequestLogMiddleware;
import com.chinazhouwy.feishubot.schedule.ScheduleTaskTool;
import com.chinazhouwy.feishubot.tool.DeepSeekWebTools;
import com.chinazhouwy.feishubot.tool.FeishuTools;
import com.chinazhouwy.feishubot.tool.ToolList;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import java.nio.file.Path;

/** 每次回复新建一个 Agent，从对应 Bot 的文件加载提示词。 */
public class AgentFactory {
    private final OpenAIChatModel model;
    private final JsonFileAgentStateStore stateStore;
    private final FeishuTools tools;
    private final DeepSeekWebTools webTools;
    private final AgentEventStore eventStore;
    private final AgentWorkspace workspace;

    public AgentFactory(OpenAIChatModel model, String stateDir, FeishuTools tools, DeepSeekWebTools webTools,
                        AgentEventStore eventStore, AgentWorkspace workspace) {
        this.model = model;
        this.stateStore = new JsonFileAgentStateStore(Path.of(stateDir));
        this.tools = tools;
        this.webTools = webTools;
        this.eventStore = eventStore;
        this.workspace = workspace;
    }

    public ReActAgent create(Bot bot) {
        return create(bot, null, null);
    }

    public ReActAgent create(Bot bot, ScheduleTaskTool scheduleTask) {
        return create(bot, null, scheduleTask);
    }

    ReActAgent create(Bot bot, String chatId, ScheduleTaskTool scheduleTask) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        toolkit.registerTool(webTools);
        toolkit.registerAgentTool(tools.spawnAgent(model, stateStore, webTools, eventStore));
        if (scheduleTask != null) {
            toolkit.registerTool(scheduleTask);
        }
        toolkit.registerTool(new ToolList(toolkit));
        return ReActAgent.builder()
                .name(bot.id())
                .model(model)
                .toolkit(toolkit)
                .sysPrompt(workspace.systemPrompt(bot.id(), chatId))
                .stateStore(stateStore)
                .middleware(new ModelRequestLogMiddleware())
                .middleware(eventStore.middleware())
                .build();
    }

}
