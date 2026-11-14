package com.chinazhouwy.miniharness;

import com.chinazhouwy.miniharness.tool.FileSearchTool;
import com.chinazhouwy.miniharness.tool.ToolCofig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

class HarnessCoreDemo {

    @Test
    void chatWithRealModel() throws Exception {
        HarnessCore harness = new HarnessCore();
        // 手动创建对象时，需要自己调用初始化方法。
        harness.initModel();

        System.out.println("模型回答：");
        harness.run("你是 Java 学习助手，请用中文简短回答。",
                        "用两句话解释 Agent 的模型和工具各自负责什么。",
                        null,
                        true)
                .doOnNext(System.out::print)
                .blockLast(Duration.ofSeconds(90));
        System.out.println();
    }
    @Test
    void chatWithRealModelWithPath() throws Exception {
        HarnessCore harness = new HarnessCore();
        // 手动创建对象时，需要自己调用初始化方法。
        harness.initModel();

        System.out.println("===============模型回答：==================");
        harness.run("请用中文简短回答。",
                        "请调用 Glob 工具，查找工作目录中的 Java 文件，列出文件名。",
                        Arrays.asList(ToolCofig.getGlobTool("src/test/java/com/chinazhouwy/miniharness/springai/study")),
                        true)
                .doOnNext(System.out::print)
                .blockLast(Duration.ofSeconds(90));
        System.out.println();
    }


    @Test
    void chatWithRealModelWithMyPathTool() throws Exception {
        HarnessCore harness = new HarnessCore();
        // 手动创建对象时，需要自己调用初始化方法。
        harness.initModel();

        System.out.println("===============模型回答：==================");
        harness.run("请用中文简短回答。",
                        "查找工作目录中的 Java 文件，列出文件名。",
                        Arrays.asList(new FileSearchTool("src/test/java/com/chinazhouwy/miniharness/springai/study")),
                        true)
//                .doOnNext(chunk -> System.out.println("【最终回答片段】 " + chunk))
                .blockLast(Duration.ofSeconds(90));
        System.out.println();
    }

    @Test
    void chatWithMultipleToolRounds() throws Exception {
        HarnessCore harness = new HarnessCore();
        harness.initModel();

        // 第二次搜索依赖第一次返回的文件名，便于观察多轮消息与工具结果的回填。
        String task = """
                请按顺序完成两次工具调用，并在两次都完成后再回答：
                1. 调用 find_files，使用 **/*.java 查找 Java 文件。
                2. 从第一次结果中选一个实际存在的文件名，再次调用 find_files，
                   使用该文件名构造精确匹配的 glob 模式查找它。
                最后简短说明你选了哪个文件以及第二次搜索的结果。
                """;

        System.out.println("=============== 多轮工具调用 demo ===============");
        harness.run("请按照用户要求依次调用工具，并用中文回答。", task,
                        Arrays.asList(new FileSearchTool(
                                "src/test/java/com/chinazhouwy/miniharness/springai/study")),
                        true)
                // 由 TraceStreamAdvisor 输出每轮聚合结果，避免流式文本与日志交错。
                .blockLast(Duration.ofSeconds(90));
        System.out.println();
    }

}
