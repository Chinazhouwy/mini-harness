package com.chinazhouwy.miniharness.runtime.event;

import com.chinazhouwy.miniharness.runtime.core.NodeId;
import com.chinazhouwy.miniharness.runtime.core.RunId;
import com.chinazhouwy.miniharness.runtime.core.TaskId;
import com.chinazhouwy.miniharness.runtime.core.WorkflowRef;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 结构化执行事件：type + 关联身份（taskId/runId/nodeId/workflow）+ 人类可读说明 + 结构化 payload。
 * 全局自增 seq 保证事件顺序可排序，是前端实时展示与执行审计的基本单元。
 */
public record HarnessEvent(
        long seq,
        String eventId,
        EventType type,
        TaskId taskId,
        RunId runId,
        NodeId nodeId,
        WorkflowRef workflow,
        String message,
        Map<String, Object> payload,
        Instant occurredAt
) {
    public HarnessEvent {
        if (payload == null) {
            payload = Map.of();
        }
    }

    public static Builder builder(EventType type) {
        return new Builder(type);
    }

    /** 便捷构建器；身份字段均可选，按事件语义填写。 */
    public static final class Builder {
        private final EventType type;
        private TaskId taskId;
        private RunId runId;
        private NodeId nodeId;
        private WorkflowRef workflow;
        private String message = "";
        private Map<String, Object> payload = Map.of();

        private Builder(EventType type) {
            this.type = type;
        }

        public Builder task(TaskId taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder run(RunId runId) {
            this.runId = runId;
            return this;
        }

        public Builder node(NodeId nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder workflow(WorkflowRef workflow) {
            this.workflow = workflow;
            return this;
        }

        public Builder message(String message) {
            this.message = message == null ? "" : message;
            return this;
        }

        public Builder payload(Map<String, Object> payload) {
            this.payload = payload == null ? Map.of() : Map.copyOf(payload);
            return this;
        }

        public HarnessEvent build(long seq) {
            return new HarnessEvent(seq, UUID.randomUUID().toString(), type, taskId, runId, nodeId,
                    workflow, message, payload, Instant.now());
        }
    }
}
