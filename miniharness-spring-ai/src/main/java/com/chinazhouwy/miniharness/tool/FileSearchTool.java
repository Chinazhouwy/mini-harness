package com.chinazhouwy.miniharness.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.tools.GlobTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** 使用 @Tool 暴露自定义名称，具体搜索和目录约束仍委托给 GlobTool。 */
public class FileSearchTool {

    private static final Logger log = LoggerFactory.getLogger(FileSearchTool.class);

    private final GlobTool globTool;

    public FileSearchTool(String path) {
        this.globTool = ToolCofig.getGlobTool(path);
    }

    @Tool(
            name = "find_files",
            description = "根据 glob 模式查找文件，例如 **/*.java"
    )
    public String findFiles(
            @ToolParam(description = "要匹配的 glob 模式") String pattern,
            @ToolParam(description = "搜索目录；不传时使用默认目录", required = false) String path
    ) {
        log.info("before");
        String result = globTool.glob(pattern, path);
        log.info("after");
        return result;
    }


}
