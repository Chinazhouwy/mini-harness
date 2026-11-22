package com.chinazhouwy.feishubot.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;

import java.util.Comparator;
import java.util.stream.Collectors;

/** 从当前 Agent 的 Toolkit 读取可用工具。 */
public record ToolList(Toolkit toolkit) {
    @Tool(name = "list_tools", description = "查询当前 Agent 可用的工具及其用途。用户询问工具或能力时调用。")
    public ToolResultBlock listTools() {
        String tools = toolkit.getToolSchemas().stream()
                .sorted(Comparator.comparing(ToolSchema::getName))
                .map(tool -> tool.getName() + "：" + tool.getDescription())
                .collect(Collectors.joining("\n"));
        return ToolResultBlock.success(tools);
    }
}
