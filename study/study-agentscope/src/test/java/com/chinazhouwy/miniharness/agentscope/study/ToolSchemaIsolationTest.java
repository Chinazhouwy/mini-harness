package com.chinazhouwy.miniharness.agentscope.study;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 回归此前混用 Schema 4.x/5.x 时发生 NoSuchMethodError 的实际入口。 */
class ToolSchemaIsolationTest {
    public static class SearchTool {
        @Tool(name = "find_files", description = "按关键词查找文件")
        public String findFiles(@ToolParam(name = "keyword", description = "文件名关键词") String keyword) {
            return keyword;
        }
    }

    /**
     * 离线回归带参数工具注册时的 Schema 生成。
     * <p>关键点：Toolkit.registerTool 解析 @Tool/@ToolParam，实际走 AgentScope 所需的 victools 依赖。
     * <p>观察：注册成功且能按名字取得工具；该入口曾因混用 Schema 4.x/5.x 发生二进制方法缺失。
     */
    @Test
    @DisplayName("AgentScope 独立依赖：带参数工具可正常生成 Schema")
    void registersParameterizedTool() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new SearchTool());
        assertNotNull(toolkit.getTool("find_files"));
    }
}
