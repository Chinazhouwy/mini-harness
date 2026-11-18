<!--
  ============================================================================
  Task Center 页：任务列表 + 任务详情 + 执行历史（对应任务书“Task Center”页面）
  ============================================================================
  【这一页做什么】
  1. 打开页面时调 GET /api/tasks 拉全部任务，表格展示（ID/标题/公司/状态/Run 数）；
  2. 点“详情”→ 调 GET /api/tasks/{id} 拉任务详情（消息、Run 列表），
     再逐个 GET /api/runs/{runId} 拉每次执行的完整结果；
  3. 点“执行”→ 调 POST /api/tasks/{id}/runs 发起一次新执行（跑当前已发布版本）；
  4. 底部输入框可以“追加信息或质疑”（如：这个利润率好像不对）——
     调 POST /api/tasks/{id}/messages，后端会关联原执行并自动发起核查。

  【为什么“执行”要等多下才刷新？】
  后端执行是异步的（HTTP 请求立即返回 runId，跑在后台线程）。点“执行”后
  重新拉任务详情，若执行还在 RUNNING，等 SSE 事件或手动再点“刷新”即可。
-->
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { Run, Task } from '../api/types'

const tasks = ref<Task[]>([])        // 任务列表（表格数据源）
const selected = ref<Task | null>(null)   // 当前点开的任务
const taskRuns = ref<Run[]>([])      // 该任务的全部执行详情
const loading = ref(false)           // “刷新”按钮的加载态
const error = ref('')                // 错误信息
const appendText = ref('')           // 底部“追加信息”输入框内容

/** 拉取任务列表。 */
async function refresh() {
  loading.value = true
  try {
    tasks.value = await api.tasks()
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

/**
 * 打开某任务详情：先拉任务（含消息与 runIds），再逐个拉 Run 详情。
 * （任务数和 Run 数都很小，逐个请求足够；要优化可等后端提供批量接口。）
 */
async function open(task: Task) {
  error.value = ''
  try {
    // 先存局部变量再赋给 ref：await 之后 TS 无法继续窄化 ref 的类型。
    const current = await api.task(task.id)
    selected.value = current
    taskRuns.value = (current.runIds ?? []).map((id: string) => ({
      id
    })) as Run[]
    // 逐个拉取 run 详情（数量很小）。
    const details: Run[] = []
    for (const runId of current.runIds) {
      details.push(await api.run(runId))
    }
    taskRuns.value = details
  } catch (e) {
    error.value = String(e)
  }
}

/** 对该任务发起一次新执行（跑当前已发布版本的 Workflow）。 */
async function startRun(task: Task) {
  try {
    const { runId } = await api.startRun(task.id, {})
    await open(task)
    error.value = ''
    selected.value = await api.task(task.id)
  } catch (e) {
    error.value = String(e)
  }
}

/** 提交追加信息/质疑。后端语义：
 *  - 唯一活跃任务可关联 → 自动发起核查执行（返回 verificationRunId）；
 *  - 关联有歧义 → 返回确认问题（绝不擅自合并历史任务）。 */
async function appendMessage() {
  if (!selected.value || !appendText.value.trim()) return
  try {
    await api.appendMessage(selected.value.id, appendText.value.trim())
    appendText.value = ''
    selected.value = await api.task(selected.value.id)
  } catch (e) {
    error.value = String(e)
  }
}

onMounted(refresh)
</script>

<template>
  <h1 class="page-title">Task Center</h1>
  <p class="page-sub">任务、执行历史与结果。数据全部来自后端真实状态。</p>
  <div v-if="error" class="error-text">{{ error }}</div>

  <!-- 任务列表表格 -->
  <div class="panel">
    <div class="row">
      <h3 style="margin: 0">任务列表（{{ tasks.length }}）</h3>
      <button class="secondary" @click="refresh">刷新</button>
    </div>
    <table>
      <thead>
        <tr><th>ID</th><th>标题</th><th>公司</th><th>状态</th><th>Run 数</th><th>更新时间</th><th></th></tr>
      </thead>
      <tbody>
        <!-- :style 高亮当前选中的行；:class 把状态枚举映射成徽章颜色 -->
        <tr v-for="task in tasks" :key="task.id"
            :style="selected?.id === task.id ? 'background: var(--panel-2)' : ''">
          <td class="mono">{{ task.id }}</td>
          <td>{{ task.title }}</td>
          <td>{{ task.company ?? '—' }}</td>
          <td><span class="badge" :class="task.status">{{ task.status }}</span></td>
          <td>{{ task.runIds.length }}</td>
          <td class="dim">{{ task.updatedAt?.slice(11, 19) }}</td>
          <td>
            <button class="secondary" @click="open(task)">详情</button>
            <button style="margin-left: 6px" @click="startRun(task)">执行</button>
          </td>
        </tr>
        <tr v-if="!tasks.length"><td colspan="7" class="dim">暂无任务，去 Chat 页面创建一个</td></tr>
      </tbody>
    </table>
  </div>

  <!-- 选中任务后显示的左右两栏详情 -->
  <div v-if="selected" class="grid-2">
    <!-- 左栏：任务基本信息 + 消息时间线 + 追加信息输入框 -->
    <div class="panel">
      <h3>任务详情 {{ selected.id }}</h3>
      <div class="dim">意图: {{ selected.intent ?? '—' }} · 公司: {{ selected.company ?? '—' }}</div>
      <div class="col" style="margin-top: 8px">
        <div v-for="(msg, i) in selected.messages" :key="i" class="chat-msg" :class="msg.role === 'USER' ? 'user' : 'harness'">
          {{ msg.content }}
          <div class="meta" v-if="msg.linkedRunId">关联 Run: {{ msg.linkedRunId }}</div>
        </div>
      </div>
      <div class="row" style="margin-top: 10px">
        <input v-model="appendText" type="text" style="flex: 1" placeholder="追加信息或质疑（如：这个利润率好像不对）" />
        <button @click="appendMessage">提交</button>
      </div>
    </div>

    <!-- 右栏：该任务每次执行的摘要（状态/错误码/结论/节点轨迹） -->
    <div class="panel">
      <h3>执行历史（{{ taskRuns.length }}）</h3>
      <div v-for="run in taskRuns" :key="run.id" class="event-line" style="padding: 8px 0">
        <div class="row">
          <span class="mono">{{ run.id }}</span>
          <span class="badge" :class="run.status">{{ run.status }}</span>
          <span class="dim">{{ run.workflow }}</span>
        </div>
        <div v-if="run.errorCode" class="error-text mono">{{ run.errorCode }} · {{ run.errorSummary }}</div>
        <div v-else-if="run.output?.conclusion" class="ok-text">
          {{ run.output.conclusion }} · netMarginLatest={{ run.output.netMarginLatest }}
        </div>
        <div class="dim mono">触发: {{ run.triggeredBy }} · 节点:
          <span v-for="n in run.nodeResults" :key="n.nodeId" style="margin-right: 6px">
            {{ n.nodeId }}({{ n.status }})
          </span>
        </div>
      </div>
      <div v-if="!taskRuns.length" class="dim">暂无执行</div>
    </div>
  </div>
</template>
