/** REST + SSE 客户端：全部数据来自真实后端，无 Mock。 */
import type {
  Candidate, ChatReply, HarnessEvent, Run, SystemInfo, Task, WorkflowDiff, WorkflowInfo
} from './types'

async function handle<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let message = `${response.status}`
    try {
      const body = await response.json()
      message = body.errorCode ? `${body.errorCode}: ${body.message}` : JSON.stringify(body)
    } catch {
      message = `${response.status} ${response.statusText}`
    }
    throw new Error(message)
  }
  return await response.json() as T
}

export async function apiGet<T>(path: string): Promise<T> {
  return handle<T>(await fetch(path))
}

export async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  return handle<T>(await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body ?? {})
  }))
}

export const api = {
  systemInfo: () => apiGet<SystemInfo>('/api/system/info'),
  tasks: () => apiGet<Task[]>('/api/tasks'),
  task: (id: string) => apiGet<Task>(`/api/tasks/${id}`),
  createTask: (input: string, metadata?: Record<string, unknown>) =>
    apiPost<{ action: string; task?: Task; question?: string; needsUserConfirmation?: boolean }>(
      '/api/tasks', { input, metadata: metadata ?? {} }),
  appendMessage: (taskId: string, content: string, linkedRunId?: string) =>
    apiPost<{ linked?: boolean; verificationRunId?: string; question?: string; reply?: string }>(
      `/api/tasks/${taskId}/messages`, { content, linkedRunId }),
  startRun: (taskId: string, input?: Record<string, unknown>) =>
    apiPost<{ runId: string }>(`/api/tasks/${taskId}/runs`, { input: input ?? {} }),
  run: (runId: string) => apiGet<Run>(`/api/runs/${runId}`),
  runs: () => apiGet<Run[]>('/api/runs'),
  events: (runId?: string, since = 0) =>
    apiGet<HarnessEvent[]>(`/api/events?since=${since}${runId ? `&runId=${runId}` : ''}`),
  workflows: () => apiGet<WorkflowInfo[]>('/api/workflows'),
  diff: (name: string, from: number, to: number) =>
    apiGet<WorkflowDiff>(`/api/workflows/${name}/diff?fromVersion=${from}&toVersion=${to}`),
  rollback: (name: string, targetVersion: number, operator: string) =>
    apiPost<{ publishedVersion: number }>(`/api/workflows/${name}/rollback`,
      { targetVersion, operator }),
  prepareExperiment: () =>
    apiPost<{ candidateId: string; aggressiveCandidateId: string; candidatePassedGate: boolean; aggressiveFailedGate: boolean }>(
      '/api/evolution/experiment/prepare'),
  candidates: () => apiGet<Candidate[]>('/api/evolution/candidates'),
  candidate: (id: string) => apiGet<Candidate>(`/api/evolution/candidates/${id}`),
  evaluateCandidate: (id: string) =>
    apiPost<Candidate['evalReport']>(`/api/evolution/candidates/${id}/evaluate`),
  approveCandidate: (id: string, approver: string, note: string) =>
    apiPost<{ publishedVersion: number }>(`/api/evolution/candidates/${id}/approve`,
      { approver, note }),
  rejectCandidate: (id: string, approver: string, note: string) =>
    apiPost<{ publishedVersion: number }>(`/api/evolution/candidates/${id}/reject`,
      { approver, note }),
  chat: (sessionId: string, input: string) =>
    apiPost<ChatReply>('/api/chat', { sessionId, input })
}

/** SSE 实时事件流；返回关闭函数。 */
export function openEventStream(
  since: number,
  onEvent: (event: HarnessEvent) => void,
  onStatus?: (connected: boolean) => void
): () => void {
  const source = new EventSource(`/api/events/stream?since=${since}`)
  source.onopen = () => onStatus?.(true)
  source.onerror = () => onStatus?.(false)
  source.onmessage = (message) => {
    try {
      onEvent(JSON.parse(message.data) as HarnessEvent)
    } catch (e) {
      console.error('事件解析失败', e)
    }
  }
  return () => source.close()
}
