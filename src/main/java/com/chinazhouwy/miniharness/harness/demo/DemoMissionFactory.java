package com.chinazhouwy.miniharness.harness.demo;

import com.chinazhouwy.miniharness.harness.domain.MissionDefinition;
import com.chinazhouwy.miniharness.harness.domain.TaskNode;
import com.chinazhouwy.miniharness.harness.domain.TaskStatus;

import java.util.List;
import java.util.UUID;

public final class DemoMissionFactory {

    private DemoMissionFactory() {
    }

    public static MissionDefinition create() {
        return create("demo-" + UUID.randomUUID());
    }

    public static MissionDefinition create(String missionId) {
        return new MissionDefinition(
                missionId,
                "完成一个可回放的长程任务 Demo",
                List.of("四个任务按依赖顺序完成", "最终报告 Artifact 存在"),
                List.of(
                        task("prepare", "准备输入", List.of()),
                        task("execute", "执行任务", List.of("prepare")),
                        task("verify", "验证产物", List.of("execute")),
                        task("report", "生成报告", List.of("verify"))
                )
        );
    }

    private static TaskNode task(String id, String title, List<String> dependencies) {
        return new TaskNode(id, title, dependencies, TaskStatus.PENDING, 2, "等待前置任务", "");
    }
}
