package com.chinazhouwy.miniharness;

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

        System.out.println("模型回答：");
        harness.run("请用中文简短回答。",
                        "请调用 Glob 工具，查找工作目录中的 Java 文件，列出文件名。",
                        Arrays.asList(ToolCofig.getGlobTool("src/test/java/com/chinazhouwy/miniharness/springai/study")),
                        true)
                .doOnNext(System.out::print)
                .blockLast(Duration.ofSeconds(90));
        System.out.println();
    }


}
