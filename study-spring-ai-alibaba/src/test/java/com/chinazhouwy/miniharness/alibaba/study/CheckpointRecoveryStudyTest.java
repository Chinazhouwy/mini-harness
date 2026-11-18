package com.chinazhouwy.miniharness.alibaba.study;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.file.FileSystemSaver;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/** 只观察文件检查点的暂停与恢复，不请求模型，也不执行真实发布。 */
public class CheckpointRecoveryStudyTest {

    @Test
    @DisplayName("检查点：发布前暂停，重建 Saver 和执行图后继续")
    void pauseAndResume(@TempDir Path directory) throws Exception {
        // 注册状态键；当前版本从检查点恢复时，只装入已声明更新策略的字段。
        StateGraph graph = new StateGraph(() -> Map.of("draft", new ReplaceStrategy()));
        graph.addNode("draft", node_async(state -> {
            System.out.println("[生成草稿] 工单 DEMO-001：处理完成");
            return Map.of("draft", "工单 DEMO-001：处理完成");
        }));
        graph.addNode("publish", node_async(state -> {
            System.out.println("[模拟发布] " + state.value("draft").orElseThrow());
            return Map.of();
        }));
        graph.addEdge(START, "draft").addEdge("draft", "publish").addEdge("publish", END);

        // threadId 定位同一份任务检查点，与 Java 线程编号无关。
        var task = RunnableConfig.builder().threadId("ticket-DEMO-001").build();
        var first = graph.compile(checkpointConfig(directory));
        first.invoke(Map.of(), task);
        System.out.println("[已暂停] 下一节点=" + first.getState(task).next());

        // 重新创建文件 Saver 和执行图，仅复用图定义、目录与任务 ID；没有重启 JVM。
        var restored = graph.compile(checkpointConfig(directory));
        System.out.println("[重新读取] " + restored.getState(task).state().data());
        // withResume 显式恢复旧执行；null 表示不提供新输入，单独传 null 不等于恢复。
        restored.invoke((Map<String, Object>) null, task.withResume());
        System.out.println("[恢复完成] 下一节点=" + restored.getState(task).next());
    }

    /** 保存状态与下一节点，并在 publish 节点执行前暂停；这不是权限审批配置。 */
    private CompileConfig checkpointConfig(Path directory) {
        var saver = FileSystemSaver.builder().targetFolder(directory).build();
        return CompileConfig.builder().saverConfig(SaverConfig.builder().register(saver).build())
                .interruptBefore("publish").build();
    }
}
