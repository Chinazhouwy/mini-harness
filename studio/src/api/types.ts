/** 后端 API 类型定义（与 ApiMappers 输出一一对应）。 */

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

export interface TaskMessage {
  role: 'USER' | 'HARNESS'
  content: string
  at: string
  linkedRunId: string | null
}

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

export interface WorkflowNodeSpec {
  id: string
  type: string
  params: Record<string, unknown>
  timeoutMs: number
  maxAttempts: number
}

export interface WorkflowVersion {
  name: string
  version: number
  intent: string | null
  description: string
  published: boolean
  nodes: WorkflowNodeSpec[]
  edges: { from: string; to: string }[]
}

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

export interface WorkflowDiff {
  from: string
  to: string
  addedNodes: string[]
  removedNodes: string[]
  unchangedNodes: string[]
  addedEdges: { from: string; to: string }[]
  removedEdges: { from: string; to: string }[]
}

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
