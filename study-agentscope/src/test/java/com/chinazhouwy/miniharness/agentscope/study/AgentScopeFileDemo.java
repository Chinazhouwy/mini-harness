package com.chinazhouwy.miniharness.agentscope.study;

import com.chinazhouwy.miniharness.model.ModelConfig;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/** 真实模型文件工具示例；从 study-agentscope 模块运行，不再需要类路径替换 profile。 */
@org.junit.jupiter.api.Tag("live")
class AgentScopeFileDemo {
    public static class FileTools {
        private final Path directory;
        final AtomicInteger calls = new AtomicInteger();

        FileTools(Path directory) { this.directory = directory; }

        @Tool(name = "find_java_files", description = "列出预设学习目录中的 Java 文件")
        public String findJavaFiles() throws Exception {
            calls.incrementAndGet();
            try (var paths = Files.list(directory)) {
                String result = paths.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".java"))
                        .sorted().reduce((a, b) -> a + "\n" + b).orElse("没有找到 Java 文件");
                System.out.println("[工具实际执行] find_java_files\n" + result);
                return result;
            }
        }
    }

    public static class FilteredFileTools {
        private final Path directory;

        FilteredFileTools(Path directory) { this.directory = directory; }

        @Tool(name = "find_files", description = "按文件名中包含的文本查找预设学习目录中的 Java 文件")
        public String findFiles(@ToolParam(name = "keyword", description = "文件名包含的文本，例如 Loop") String keyword)
                throws Exception {
            System.out.println("[工具入参] keyword=" + keyword);
            try (var paths = Files.list(directory)) {
                String result = paths.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".java") && name.contains(keyword))
                        .sorted().reduce((a, b) -> a + "\n" + b).orElse("没有匹配的 Java 文件");
                System.out.println("[工具结果] " + result);
                return result;
            }
        }
    }


    @Test
    @org.junit.jupiter.api.DisplayName("AgentScope：使用真实模型调用文件查询工具")
    void agentUsesFileTool() throws Exception {
        Properties properties = ModelConfig.loadLocalProperties();
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(properties.getProperty("miniharness.api.key"))
                .baseUrl(properties.getProperty("miniharness.base.url"))
                .modelName(properties.getProperty("miniharness.model"))
                .stream(true)
                .build();
        FileTools files = new FileTools(ModelConfig.findProjectFile("study-agentscope/src/test/java/com/chinazhouwy/miniharness/agentscope/study"));
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(files);
        try (ReActAgent agent = ReActAgent.builder().name("file-agent")
                .sysPrompt("请使用工具并根据结果用中文简短回答。")
                .model(model).toolkit(toolkit).maxIters(4).build()) {
            var answer = agent.call("请调用 find_java_files，列出学习目录中的 Java 文件。").block(Duration.ofSeconds(90));
            System.out.println("[最终回答] " + answer.getTextContent());
            System.out.println("[工具执行次数] " + files.calls.get());
        }
    }
}
