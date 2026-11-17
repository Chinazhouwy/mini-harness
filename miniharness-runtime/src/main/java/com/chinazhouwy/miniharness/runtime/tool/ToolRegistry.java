package com.chinazhouwy.miniharness.runtime.tool;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 工具注册表：按名称登记工具；注册动作属于 Harness 管控，不由模型决定。 */
public class ToolRegistry {

    private final Map<String, ToolSpec> tools = new ConcurrentHashMap<>();

    public ToolRegistry register(ToolSpec spec) {
        ToolSpec existing = tools.putIfAbsent(spec.name(), spec);
        if (existing != null) {
            throw new IllegalArgumentException("工具重复注册: " + spec.name());
        }
        return this;
    }

    public ToolSpec get(String name) {
        ToolSpec spec = tools.get(name);
        if (spec == null) {
            throw new ToolFailureException("TOOL_NOT_FOUND", "工具未注册: " + name);
        }
        return spec;
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    public Map<String, ToolSpec> all() {
        return Map.copyOf(tools);
    }
}
