package com.chinazhouwy.miniharness.runtime.tool;

import java.util.Map;
import java.util.Objects;

/**
 * 工具注册项：名称、描述、声明式输入 schema（前端与审计展示用）、确定性执行函数。
 * 执行函数必须是确定性 Java 逻辑——模型生成的数字永远不能当作可信数据来源。
 */
public record ToolSpec(
        String name,
        String description,
        Map<String, String> inputSchema,
        ToolExecutor executor
) {
    public ToolSpec {
        Objects.requireNonNull(name);
        Objects.requireNonNull(executor);
        if (inputSchema == null) {
            inputSchema = Map.of();
        }
    }

    @FunctionalInterface
    public interface ToolExecutor {
        Map<String, Object> execute(Map<String, Object> args, ToolContext context) throws Exception;
    }
}
