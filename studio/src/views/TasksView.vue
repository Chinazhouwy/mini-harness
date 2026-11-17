<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api/client'
import type { Run, Task } from '../api/types'

const tasks = ref<Task[]>([])
const selected = ref<Task | null>(null)
const taskRuns = ref<Run[]>([])
const loading = ref(false)
const error = ref('')
const appendText = ref('')

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

async function open(task: Task) {
  error.value = ''
  try {
    selected.value = await api.task(task.id)
    taskRuns.value = (selected.value.runIds ?? []).map((id: string) => ({
      id
    })) as Run[]
    // 逐个拉取 run 详情（数量很小）。
    const details: Run[] = []
    for (const runId of selected.value.runIds) {
      details.push(await api.run(runId))
    }
    taskRuns.value = details
  } catch (e) {
    error.value = String(e)
  }
}

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

  <div v-if="selected" class="grid-2">
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
