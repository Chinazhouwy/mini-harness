<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import type { ChatReply, Run } from '../api/types'
import { useHarnessStore } from '../stores/harness'

interface ChatLine {
  role: 'user' | 'harness'
  content: string
  meta?: string
}

const store = useHarnessStore()
const sessionId = ref('sess-' + Math.random().toString(36).slice(2, 10))
const input = ref('')
const lines = ref<ChatLine[]>([])
const activeRunId = ref<string | null>(null)
const activeRun = ref<Run | null>(null)
const sending = ref(false)
const error = ref('')

onMounted(() => {
  lines.value.push({
    role: 'harness',
    content: '你好。试试输入「研究华夏制造的盈利能力，分析利润率变化」——我会匹配已发布的研究 Workflow 并执行。',
    meta: '当前为确定性离线路由（合成数据演示）'
  })
})

watch(() => store.events.length, async () => {
  if (!activeRunId.value) return
  const terminal = store.events.some(e =>
    e.runId === activeRunId.value
    && ['RUN_COMPLETED', 'RUN_FAILED', 'RUN_TIMED_OUT', 'RUN_CANCELLED'].includes(e.type))
  if (terminal && !activeRun.value) await loadRun(activeRunId.value)
})

async function loadRun(runId: string) {
  try {
    activeRun.value = await api.run(runId)
  } catch (e) {
    error.value = String(e)
  }
}

async function send() {
  const text = input.value.trim()
  if (!text || sending.value) return
  input.value = ''
  sending.value = true
  error.value = ''
  activeRun.value = null
  lines.value.push({ role: 'user', content: text })
  try {
    const reply: ChatReply = await api.chat(sessionId.value, text)
    lines.value.push({
      role: 'harness',
      content: reply.reply,
      meta: `action=${reply.action}${reply.taskId ? ` · task=${reply.taskId}` : ''}`
    })
    if (reply.runId) {
      activeRunId.value = reply.runId
      lines.value.push({
        role: 'harness',
        content: `已发起执行 ${reply.runId}，等待结果中…（可在 Run Inspector 查看实时事件）`,
        meta: 'run-started'
      })
      await nextTick()
    }
    if (reply.needsUserConfirmation && reply.question) {
      lines.value.push({ role: 'harness', content: `需要确认：${reply.question}` })
    }
  } catch (e) {
    error.value = String(e)
    lines.value.push({ role: 'harness', content: `出错：${error.value}` })
  } finally {
    sending.value = false
  }
}
</script>

<template>
  <h1 class="page-title">Chat 对话</h1>
  <p class="page-sub">用户输入 → 能力路由（规则优先）→ 建任务/关联核查 → 执行 Workflow。事件实时来自后端 SSE。</p>

  <div class="panel">
    <div class="chat-box">
      <div v-for="(line, i) in lines" :key="i" class="chat-msg" :class="line.role">
        <div>{{ line.content }}</div>
        <div class="meta" v-if="line.meta">{{ line.meta }}</div>
      </div>
      <div v-if="activeRun" class="chat-msg harness">
        <div>
          执行结果：<span :class="activeRun.status === 'SUCCEEDED' ? 'ok-text' : 'error-text'">
            {{ activeRun.status }}</span>
          <span v-if="activeRun.errorCode">（{{ activeRun.errorCode }}）</span>
        </div>
        <div v-if="activeRun.output && activeRun.output.conclusion" class="meta">
          {{ activeRun.output.conclusion }} · netMarginLatest={{ activeRun.output.netMarginLatest }}
        </div>
        <div v-if="activeRun.status !== 'SUCCEEDED'" class="meta">{{ activeRun.errorSummary }}</div>
      </div>
    </div>
    <div class="row" style="margin-top: 12px">
      <input v-model="input" type="text" style="flex: 1" placeholder="输入消息，回车发送"
             @keyup.enter="send" :disabled="sending" />
      <button :disabled="sending || !input.trim()" @click="send">发送</button>
    </div>
  </div>

  <div class="panel">
    <h3>本会话实时事件（SSE）</h3>
    <div v-if="!store.eventsForRun(activeRunId ?? '').length" class="dim">暂无事件</div>
    <div v-for="e in store.eventsForRun(activeRunId ?? '').slice(-30)" :key="e.seq" class="event-line">
      <span class="type">{{ e.type }}</span> · {{ e.message }}
      <span class="dim">（{{ e.occurredAt?.slice(11, 19) }}）</span>
    </div>
  </div>
</template>
