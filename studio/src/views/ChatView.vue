<!--
  ============================================================================
  Chat 页：用户与系统对话的入口（对应任务书“Chat”页面）
  ============================================================================
  【这一页的完整流程】
  1. 用户在输入框打字、点“发送”（或回车）；
  2. 调 POST /api/chat —— 后端 IntentRouter 做能力路由（规则→活跃任务→已发布
     Workflow→Skill→兜底），并自动创建/关联任务、发起一次执行（Run）；
  3. 页面立即显示路由结果（创建了哪个任务、发起哪个执行）；
  4. 后端执行过程中的事件通过 SSE 实时推到下方“本会话实时事件”面板；
  5. 执行结束（收到 RUN_COMPLETED/RUN_FAILED 等终止事件）后，页面调 GET
     /api/runs/{id} 拉取最终结果，显示在对话气泡下方（如“盈利能力基本稳定…”）。

  【小白需要知道的响应式要点】
  - ref() 包装的变量是“响应式”的：改了它，模板里用到它的地方自动更新。
  - watch() 可以监听某个数据的变化（这里监听全局事件流的长度），
    相当于“每来一条新事件就执行一次这段逻辑”。
-->
<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
// api：后端调用封装；Run：执行的 TypeScript 类型定义（字段见 api/types.ts）。
import { api } from '../api/client'
import type { ChatReply, Run } from '../api/types'
import { useHarnessStore } from '../stores/harness'

// ---------- 页面内部状态 ----------
interface ChatLine {
  role: 'user' | 'harness'   // 气泡是谁说的话
  content: string            // 气泡文本
  meta?: string              // 气泡下的小字（如路由动作、任务号）
}

const store = useHarnessStore()
// 会话 ID：同一浏览器的连续对话共享一个 sessionId（后端用它串起 Session 与任务）。
// Math.random 生成一个随机短串即可，真正的身份由后端 Session 记录维护。
const sessionId = ref('sess-' + Math.random().toString(36).slice(2, 10))
const input = ref('')                      // 输入框当前内容（双向绑定）
const lines = ref<ChatLine[]>([])          // 对话气泡列表
const activeRunId = ref<string | null>(null)  // 最近一次发起的执行 ID
const activeRun = ref<Run | null>(null)       // 该执行的最终详情（结束后拉取）
const sending = ref(false)                 // 防抖：发送中禁用按钮
const error = ref('')                      // 出错信息（红字显示）

// 页面加载时放一句欢迎语，提示用户可以输入什么。
onMounted(() => {
  lines.value.push({
    role: 'harness',
    content: '你好。试试输入「研究华夏制造的盈利能力，分析利润率变化」——我会匹配已发布的研究 Workflow 并执行。',
    meta: '当前为确定性离线路由（合成数据演示）'
  })
})

/**
 * 监听全局事件流长度：每来一条新事件就检查一次。
 * 若本次执行已收到“终止事件”（完成/失败/超时/取消）且还没拉过最终详情，
 * 就调 GET /api/runs/{id} 拉取结果展示。
 */
watch(() => store.events.length, async () => {
  if (!activeRunId.value) return
  const terminal = store.events.some(e =>
    e.runId === activeRunId.value
    && ['RUN_COMPLETED', 'RUN_FAILED', 'RUN_TIMED_OUT', 'RUN_CANCELLED'].includes(e.type))
  if (terminal && !activeRun.value) await loadRun(activeRunId.value)
})

/** 拉取执行详情（含结构化输出，如研究报告的结论与净利率）。 */
async function loadRun(runId: string) {
  try {
    activeRun.value = await api.run(runId)
  } catch (e) {
    error.value = String(e)
  }
}

/** 发送一条消息：调后端 → 显示回复 → 若后端自动发起了执行则等待结果。 */
async function send() {
  const text = input.value.trim()
  if (!text || sending.value) return
  input.value = ''          // 清空输入框
  sending.value = true
  error.value = ''
  activeRun.value = null    // 清掉上一次执行的结果气泡
  lines.value.push({ role: 'user', content: text })   // 先显示用户气泡
  try {
    // 核心：一条消息让后端完成“路由 + 建任务/关联 + 发起执行”全流程。
    const reply: ChatReply = await api.chat(sessionId.value, text)
    lines.value.push({
      role: 'harness',
      content: reply.reply,
      meta: `action=${reply.action}${reply.taskId ? ` · task=${reply.taskId}` : ''}`
    })
    if (reply.runId) {
      // 后端已自动发起执行：记录 runId，并提示用户去 Run Inspector 看实时事件。
      activeRunId.value = reply.runId
      lines.value.push({
        role: 'harness',
        content: `已发起执行 ${reply.runId}，等待结果中…（可在 Run Inspector 查看实时事件）`,
        meta: 'run-started'
      })
      await nextTick()   // 等 Vue 把新气泡渲染完再继续（DOM 更新是异步的）
    }
    // 路由有歧义（比如“这个利润率不对”关联到多个任务）时后端会要求用户确认，
    // 绝不擅自合并任务——这里把问题原样展示给用户。
    if (reply.needsUserConfirmation && reply.question) {
      lines.value.push({ role: 'harness', content: `需要确认：${reply.question}` })
    }
  } catch (e) {
    error.value = String(e)
    lines.value.push({ role: 'harness', content: `出错：${error.value}` })
  } finally {
    sending.value = false   // 无论成败，恢复输入框可用
  }
}
</script>

<template>
  <h1 class="page-title">Chat 对话</h1>
  <p class="page-sub">用户输入 → 能力路由（规则优先）→ 建任务/关联核查 → 执行 Workflow。事件实时来自后端 SSE。</p>

  <!-- 对话气泡区：v-for 按数组顺序渲染，:class 按角色切换左右与颜色 -->
  <div class="panel">
    <div class="chat-box">
      <div v-for="(line, i) in lines" :key="i" class="chat-msg" :class="line.role">
        <div>{{ line.content }}</div>
        <div class="meta" v-if="line.meta">{{ line.meta }}</div>
      </div>
      <!-- 执行结束后的结果气泡：绿=SUCCEEDED，红=其他；显示结论与净利率 -->
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
    <!-- 输入行：v-model 双向绑定输入框内容；回车或点击按钮都触发 send() -->
    <div class="row" style="margin-top: 12px">
      <input v-model="input" type="text" style="flex: 1" placeholder="输入消息，回车发送"
             @keyup.enter="send" :disabled="sending" />
      <button :disabled="sending || !input.trim()" @click="send">发送</button>
    </div>
  </div>

  <!-- 实时事件面板：数据来自全局 store（SSE 推送），slice(-30) 只显示最近 30 条 -->
  <div class="panel">
    <h3>本会话实时事件（SSE）</h3>
    <div v-if="!store.eventsForRun(activeRunId ?? '').length" class="dim">暂无事件</div>
    <div v-for="e in store.eventsForRun(activeRunId ?? '').slice(-30)" :key="e.seq" class="event-line">
      <span class="type">{{ e.type }}</span> · {{ e.message }}
      <span class="dim">（{{ e.occurredAt?.slice(11, 19) }}）</span>
    </div>
  </div>
</template>
