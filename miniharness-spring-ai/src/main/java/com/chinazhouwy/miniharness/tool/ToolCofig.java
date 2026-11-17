package com.chinazhouwy.miniharness.tool;

import org.springaicommunity.agent.tools.GlobTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolDefinitions;

import java.lang.reflect.Method;

public class ToolCofig {


    public static GlobTool getGlobTool(String path){
        return GlobTool.builder()
                .workingDirectory(path)
                .allowedDirectory(path)
                .maxDepth(3)
                .maxResults(10)
                .build();
    }

    /**
     * 创建一个自定义名称的 Glob 工具。
     *
     * @param path     工具可以搜索的目录
     * @param toolName 发给模型的工具名称，例如 find_files
     */
    public static ToolCallback getNamedGlobTool(String path, String toolName)
            throws NoSuchMethodException {
        // 1. 创建真正执行文件搜索的工具对象
        GlobTool globTool = getGlobTool(path);

        // 2. 获取它的 glob(String pattern, String path) 方法
        Method globMethod = GlobTool.class.getMethod(
                "glob",
                String.class,
                String.class
        );

        // 3. 从原方法生成定义，保留原描述和参数 schema，只覆盖名称
        ToolDefinition definition = ToolDefinitions.builder(globMethod)
                .name(toolName)
                .build();

        // 4. 把新定义与原方法、原对象绑定
        return MethodToolCallback.builder()
                .toolDefinition(definition)
                .toolMethod(globMethod)
                .toolObject(globTool)
                .build();
    }


    @Tool(name = "hello", description = "向指定的人打招呼")
    public String sayHello(@ToolParam(description = "人的名字") String name) {
        return "你好，" + name;
    }

}
