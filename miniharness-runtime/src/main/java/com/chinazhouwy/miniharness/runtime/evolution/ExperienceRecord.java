package com.chinazhouwy.miniharness.runtime.evolution;

import com.chinazhouwy.miniharness.runtime.core.NodeStatus;
import com.chinazhouwy.miniharness.runtime.core.RunId;
import com.chinazhouwy.miniharness.runtime.core.RunStatus;
import com.chinazhouwy.miniharness.runtime.core.TaskId;
import com.chinazhouwy.miniharness.runtime.core.WorkflowRef;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 经验记录：一次执行（真实任务或评测案例）的轨迹快照与失败标注。
 * 自进化的唯一输入来源——分析器只相信这里保存的客观记录，不重新解读模型自述。
 */
public record ExperienceRecord(
        RunId runId,
        TaskId taskId,
        WorkflowRef workflow,
        String triggeredBy,
        RunStatus outcome,
        String errorCode,
        String errorSummary,
        List<NodeSummary> nodes,
        Map<String, Object> output,
        List<FailureAnnotation> annotations,
        Instant createdAt
) {
    public ExperienceRecord {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        output = output == null ? Map.of() : Map.copyOf(output);
    }

    /** 节点轨迹摘要。 */
    public record NodeSummary(String nodeId, NodeStatus status, String errorCode, long durationMs) {
    }

    /** 失败标注：结构化缺陷类型 + 发生节点 + 证据说明。 */
    public record FailureAnnotation(String kind, String nodeId, String detail) {
    }

    public boolean hasAnnotationKind(String kind) {
        return annotations.stream().anyMatch(a -> a.kind().equals(kind));
    }
}
