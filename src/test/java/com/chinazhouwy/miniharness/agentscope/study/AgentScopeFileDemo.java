package com.chinazhouwy.miniharness.agentscope.study;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 使用真实模型对照 AgentScope 工具调用。
 * 带参数的方法会生成 Schema，请从根目录通过 Maven profile 运行：
 * mvn -Pagentscope-study -Dtest=AgentScopeFileDemo test
 * 该 profile 只调整测试运行类路径，IDE 直接 Run 不会应用它。
 */
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

//    @Test
//    void agentPassesToolArguments() throws Exception {
//        Path root = Path.of("").toAbsolutePath().normalize();
//        Properties properties = new Properties();
//        try (InputStream input = Files.newInputStream(root.resolve("src/main/resources/openrouter.local.properties"))) {
//            properties.load(input);
//        }
//        OpenAIChatModel model = OpenAIChatModel.builder()
//                .apiKey(properties.getProperty("miniharness.api.key"))
//                .baseUrl(properties.getProperty("miniharness.base.url"))
//                .modelName(properties.getProperty("miniharness.model"))
//                .stream(true).build();
//        Toolkit toolkit = new Toolkit();
//        toolkit.registerTool(new FilteredFileTools(root.resolve(
//                "src/test/java/com/chinazhouwy/miniharness/springai/study")));
//        try (ReActAgent agent = ReActAgent.builder().name("filtered-file-agent")
//                .sysPrompt("请调用工具，根据真实工具结果用中文简短回答。")
//                .model(model).toolkit(toolkit).maxIters(4).build()) {
//            var answer = agent.call("请查找学习目录中，文件名包含 Loop 的 Java 文件。")
//                    .block(Duration.ofSeconds(90));
//            System.out.println("[最终回答] " + answer.getTextContent());
//        }
//    }

    @Test
    void agentUsesFileTool() throws Exception {
        // 从项目根目录运行测试，沿用本地配置，不输出密钥。
        Path root = Path.of("").toAbsolutePath().normalize();
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(root.resolve("src/main/resources/openrouter.local.properties"))) {
            properties.load(input);
        }
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(properties.getProperty("miniharness.api.key"))
                .baseUrl(properties.getProperty("miniharness.base.url"))
                .modelName(properties.getProperty("miniharness.model"))
                .stream(true)
                .build();
        FileTools files = new FileTools(root.resolve("src/test/java/com/chinazhouwy/miniharness/springai/study"));
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
