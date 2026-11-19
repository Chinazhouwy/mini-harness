/**
 * ============================================================================
 * 后端数据类型定义（TypeScript interface）
 * ============================================================================
 * 【给小白的话】
 * 这里的每个 interface 描述“后端某个接口返回的 JSON 长什么样”。
 * 后端生成这些 JSON 的代码在 app/task-assistant 的 ApiMappers.java——
 * 两边的字段名一一对应，改任何一边都要同步另一边。
 *
 * 【interface 是什么？】
 * 它不参与运行，只是给 TypeScript 看的“字段清单”：有了它，编辑器能在你写
 * task.title 时自动补全、写错字段名时直接标红，避免手滑。
 */

/** 一条执行事件：后端每发生一件值得记录的事（节点开始/工具调用/运行结束…）就产生一条。 */
export interface HarnessEvent {
  seq: number
  eventId: string
  type: string
  taskId: string | null
  runId: string | null
  nodeId: string | null
  workflow: string | null
  message: string
  payload: Record<string, unknown>
  occurredAt: string
}

/** 任务内的一条消息（用户输入 / Harness 回复）；质疑类消息可关联被核查的执行。 */
export interface TaskMessage {
  role: 'USER' | 'HARNESS'
  content: string
  at: string
  linkedRunId: string | null
}

/** 长期任务：一次研究诉求的完整档案（意图、消息、历次执行）。 */
export interface Task {
  id: string
  title: string
  userQuery: string
  company: string | null
  intent: string | null
  status: string
  createdAt: string
  updatedAt: string
  metadata: Record<string, unknown>
  messages: TaskMessage[]
  runIds: string[]
}

/** 单个节点的一次执行结果（Workflow 的每一步）。SKIPPED 表示因上游失败而未执行。 */
export interface NodeResult {
  nodeId: string
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED'
  output: Record<string, unknown>
  errorCode: string | null
  errorMessage: string | null
  attempts: number
  startedAt: string | null
  finishedAt: string | null
  durationMs: number
}

/** 一次执行（Run）：关联任务与某个 Workflow 版本，SUCCEEDED 不代表业务结果正确。 */
export interface Run {
  id: string
  taskId: string
  workflow: string
  triggeredBy: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'TIMED_OUT' | 'CANCELLED'
  queuedAt: string
  startedAt: string | null
  finishedAt: string | null
  errorCode: string | null
  errorSummary: string | null
  input: Record<string, unknown>
  output: Record<string, unknown>
  nodeResults: NodeResult[]
  events?: HarnessEvent[]
}

/** Workflow 的一个节点声明（做什么 + 参数 + 超时/重试策略）。 */
export interface WorkflowNodeSpec {
  id: string
  type: string
  params: Record<string, unknown>
  timeoutMs: number
  maxAttempts: number
}

/** Workflow 的一个版本。定义不可变：演化=创建新版本而不是改旧的。 */
export interface WorkflowVersion {
  name: string
  version: number
  intent: string | null
  description: string
  published: boolean
  nodes: WorkflowNodeSpec[]
  edges: { from: string; to: string }[]
}

/** 一个 Workflow 的全部版本、当前发布指针与发布/回滚审计历史。 */
export interface WorkflowInfo {
  name: string
  publishedVersion: number | null
  versions: WorkflowVersion[]
  publicationHistory: {
    workflowName: string
    version: number
    candidateId: string | null
    publishedBy: string
    at: string
    rollback: boolean
  }[]
}

/** 两个 Workflow 版本的结构差异（新增/删除了哪些节点与边），供 Evolution Review 展示。 */
export interface WorkflowDiff {
  from: string
  to: string
  addedNodes: string[]
  removedNodes: string[]
  unchangedNodes: string[]
  addedEdges: { from: string; to: string }[]
  removedEdges: { from: string; to: string }[]
}

/** 一个版本在某组评测案例上的聚合指标（正确率/错误检出率/成本等，全部来自真实执行）。 */
export interface EvalMetrics {
  totalCases: number
  passedCases: number
  accuracy: number
  errorDetectionRate: number
  errorDetectionCases: number
  avgDurationMs: number
  avgToolCalls: number
  failedCaseIds: string[]
}

/**
 * 候选与基线的对比评测报告。
 * baselineTrain / candidateTrain = 训练集指标；baselineHoldout / candidateHoldout = 留出集指标；
 * gateResults = 5 条发布门禁的逐条判定；candidateCaseResults = 逐案例明细。
 */
export interface EvalReport {
  candidateId: string
  passed: boolean
  baselineHoldout: EvalMetrics
  candidateHoldout: EvalMetrics
  baselineTrain: EvalMetrics
  candidateTrain: EvalMetrics
  gateResults: { rule: string; passed: boolean; detail: string }[]
  candidateCaseResults: {
    caseId: string
    split: string
    expected: string
    passed: boolean
    outcome: string
    errorCode: string | null
    detail: string
    toolCalls: number
    durationMs: number
  }[]
}

/** Workflow 候选：轨迹分析提出的新版本提案，批准后才成为正式发布版本。 */
export interface Candidate {
  id: string
  base: string
  rule: string
  rationale: string
  sourceRunIds: string[]
  applicableConditions: string[]
  status: 'PENDING_EVAL' | 'EVALUATED_PASS' | 'EVALUATED_FAIL' | 'APPROVED' | 'REJECTED'
  createdAt: string
  decidedBy: string | null
  decisionNote: string
  decidedAt: string | null
  proposedDefinition: WorkflowVersion
  evalReport?: EvalReport
  diffAgainstPublished?: WorkflowDiff
}

/** 系统概况（/api/system/info）：各仓库的条目数与当前发布的 Workflow 版本。 */
export interface SystemInfo {
  app: string
  modelMode: string
  tasks: number
  runs: number
  events: number
  experiences: number
  candidates: number
  skills: number
  workflows: string[]
  publishedWorkflows: Record<string, number>
  disclaimer: string
}

/** Chat 接口的回复：action 说明路由动作；needsUserConfirmation=true 时必须展示 question 等用户确认。 */
export interface ChatReply {
  sessionId: string
  action: string
  reply: string
  reason: string | null
  needsUserConfirmation: boolean
  question?: string | null
  clarificationCandidates: string[]
  taskId?: string
  runId?: string
}
