package com.chinazhouwy.miniharness.runtime.capability;

/** 能力路由的处置动作，按优先级依次尝试：规则 → 活跃任务 → 已发布 Workflow → Skill → 兜底探索。 */
public enum RoutingAction {
    /** 确定性规则直接处理（无需建任务）。 */
    ANSWER_DIRECTLY,
    /** 归属已有活跃任务（继续/核查）。 */
    CONTINUE_TASK,
    /** 任务关联存在歧义，需用户确认（绝不擅自合并历史）。 */
    CLARIFY_TASK_LINK,
    /** 新建任务并复用已发布 Workflow。 */
    NEW_TASK_WITH_WORKFLOW,
    /** 新建任务并按 Skill 执行（当前为记录性路由）。 */
    NEW_TASK_WITH_SKILL,
    /** 都不匹配，进入 Agent 自主探索。 */
    AGENT_EXPLORE
}
