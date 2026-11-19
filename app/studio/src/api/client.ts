/**
 * ============================================================================
 * 后端 API 客户端：前端与 Java 后端通信的唯一通道
 * ============================================================================
 *
 * 【怎么用】
 * 页面里不要直接写 fetch(...)，统一调这里的方法，例如：
 *   import { api } from '../api/client'
 *   const tasks = await api.tasks()          // GET /api/tasks
 *   await api.createTask('研究华夏制造...')  // POST /api/tasks
 *
 * 【数据形态】
 * 所有接口的返回 JSON 都由后端 app/task-assistant 的 ApiMappers.java 生成，
 * 字段定义见同目录 types.ts——两边的字段名是一一对应的。
 *
 * 【错误怎么处理】
 * 后端出错时返回 4xx/5xx + {errorCode, message}（见 GlobalExceptionHandler.java）。
 * handle() 会把错误码与信息拼成可读文本抛出，页面用 try/catch 显示给用户。
 */
// 各接口返回 JSON 的字段定义；与后端 ApiMappers.java 一一对应。
import type {
  Candidate, ChatReply, HarnessEvent, Run, SystemInfo, Task, WorkflowDiff, WorkflowInfo
} from './types'

// 统一处理响应：非 2xx 就抛出带 errorCode 的 Error；2xx 就解析 JSON 返回。
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

/** GET 请求的薄封装。 */
export async function apiGet<T>(path: string): Promise<T> {
  return handle<T>(await fetch(path))
}

/** POST 请求的薄封装（body 自动转 JSON，Content-Type 固定 application/json）。 */
export async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  return handle<T>(await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body ?? {})
  }))
}

// ----------------------------------------------------------------------------
// 业务方法清单（路径与后端各 @RestController 一一对应）：
//   TaskController      → tasks / task / createTask / appendMessage / startRun
//   RunController       → run / runs / events（REST 回放）
//   EventStreamController → openEventStream（SSE 实时流）
//   WorkflowController  → workflows / diff / rollback
//   EvolutionController → prepareExperiment / candidates / approve / reject
//   ChatController      → chat
// ----------------------------------------------------------------------------
export const api = {
  /** 系统概况（事件数/任务数/已发布 Workflow 版本），SystemController.info。 */
  systemInfo: () => apiGet<SystemInfo>('/api/system/info'),
  tasks: () => apiGet<Task[]>('/api/tasks'),
  task: (id: string) => apiGet<Task>(`/api/tasks/${id}`),
  /** 创建任务：后端会先做能力路由（规则→任务→Workflow→Skill→兜底）。 */
  createTask: (input: string, metadata?: Record<string, unknown>) =>
    apiPost<{ action: string; task?: Task; question?: string; needsUserConfirmation?: boolean }>(
      '/api/tasks', { input, metadata: metadata ?? {} }),
  /** 追加信息/质疑（如“这个利润率好像不对”）：后端自动关联原执行并发起核查。 */
  appendMessage: (taskId: string, content: string, linkedRunId?: string) =>
    apiPost<{ linked?: boolean; verificationRunId?: string; question?: string; reply?: string }>(
      `/api/tasks/${taskId}/messages`, { content, linkedRunId }),
  /** 发起一次执行，返回 runId；执行是异步的，进度靠 SSE 或轮询 run()。 */
  startRun: (taskId: string, input?: Record<string, unknown>) =>
    apiPost<{ runId: string }>(`/api/tasks/${taskId}/runs`, { input: input ?? {} }),
  run: (runId: string) => apiGet<Run>(`/api/runs/${runId}`),
  runs: () => apiGet<Run[]>('/api/runs'),
  /** 历史事件回放（REST）；实时事件走 openEventStream（SSE）。 */
  events: (runId?: string, since = 0) =>
    apiGet<HarnessEvent[]>(`/api/events?since=${since}${runId ? `&runId=${runId}` : ''}`),
  /** 全部 Workflow 及其版本/发布指针/发布历史。 */
  workflows: () => apiGet<WorkflowInfo[]>('/api/workflows'),
  /** 两个版本的结构差异（新增/删除了哪些节点与边），供 Evolution Review 展示。 */
  diff: (name: string, from: number, to: number) =>
    apiGet<WorkflowDiff>(`/api/workflows/${name}/diff?fromVersion=${from}&toVersion=${to}`),
  /** 回滚发布指针到某个曾发布过的版本（后端校验目标版本必须发布过）。 */
  rollback: (name: string, targetVersion: number, operator: string) =>
    apiPost<{ publishedVersion: number }>(`/api/workflows/${name}/rollback`,
      { targetVersion, operator }),
  /** 一键自进化实验：v1 受控测试 → 轨迹分析 → v2 候选 + 过激对照候选 → 对比评测。 */
  prepareExperiment: () =>
    apiPost<{ candidateId: string; aggressiveCandidateId: string; candidatePassedGate: boolean; aggressiveFailedGate: boolean }>(
      '/api/evolution/experiment/prepare'),
  candidates: () => apiGet<Candidate[]>('/api/evolution/candidates'),
  candidate: (id: string) => apiGet<Candidate>(`/api/evolution/candidates/${id}`),
  /** 对候选执行对比评测（评测结果来自真实执行，不采信模型自述）。 */
  evaluateCandidate: (id: string) =>
    apiPost<Candidate['evalReport']>(`/api/evolution/candidates/${id}/evaluate`),
  /** 批准并发布新版本——后端强制要求候选已通过评测门禁，否则 409。 */
  approveCandidate: (id: string, approver: string, note: string) =>
    apiPost<{ publishedVersion: number }>(`/api/evolution/candidates/${id}/approve`,
      { approver, note }),
  /** 拒绝候选：发布指针不动，旧版本继续服务。 */
  rejectCandidate: (id: string, approver: string, note: string) =>
    apiPost<{ publishedVersion: number }>(`/api/evolution/candidates/${id}/reject`,
      { approver, note }),
  /** Chat 页专用：一条消息走完 路由→建任务/关联→自动发起执行。 */
  chat: (sessionId: string, input: string) =>
    apiPost<ChatReply>('/api/chat', { sessionId, input })
}

/**
 * 打开 SSE 实时事件流；返回“关闭连接”的函数（目前页面刷新时浏览器自动断开，
 * 所以调用方通常不需要手动 close）。
 *
 * @param since    从第几条事件开始收（0 = 从现在开始的全部新事件）
 * @param onEvent  每收到一条事件回调一次
 * @param onStatus 连接状态变化回调（连上=true / 断开=false），驱动侧边栏状态灯
 */
export function openEventStream(
  since: number,
  onEvent: (event: HarnessEvent) => void,
  onStatus?: (connected: boolean) => void
): () => void {
  // EventSource 是浏览器内置的 SSE 客户端；断线后它会自动重连。
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
