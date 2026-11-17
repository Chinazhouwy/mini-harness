package com.chinazhouwy.miniharness.runtime.event;

/**
 * 执行事件类型。覆盖执行调度（Run/Node）、模型与工具调用、能力路由与自进化审计。
 * 事件是前端实时展示与执行审计的唯一来源；任何重要状态变化都应发射事件。
 */
public enum EventType {
    // 任务与会话
    TASK_CREATED,
    TASK_STATUS_CHANGED,
    TASK_MESSAGE_ADDED,
    TASK_LINKED_RUN,
    // 路由决策
    ROUTING_DECIDED,
    AWAITING_USER_CONFIRMATION,
    // 执行生命周期
    RUN_QUEUED,
    RUN_STARTED,
    RUN_COMPLETED,
    RUN_FAILED,
    RUN_TIMED_OUT,
    RUN_CANCELLED,
    // Workflow
    WORKFLOW_SELECTED,
    NODE_STARTED,
    NODE_SUCCEEDED,
    NODE_FAILED,
    NODE_SKIPPED,
    NODE_RETRYING,
    // 模型与工具
    MODEL_CALLED,
    MODEL_REPLIED,
    MODEL_REFUSED,
    TOOL_REQUESTED,
    TOOL_COMPLETED,
    TOOL_FAILED,
    // 预算与超时
    BUDGET_EXCEEDED,
    // 自进化
    EXPERIENCE_RECORDED,
    SKILL_CANDIDATE_CREATED,
    WORKFLOW_CANDIDATE_CREATED,
    EVALUATION_COMPLETED,
    CANDIDATE_APPROVED,
    CANDIDATE_REJECTED,
    WORKFLOW_PUBLISHED,
    WORKFLOW_ROLLED_BACK,
    SKILL_STATUS_CHANGED
}
