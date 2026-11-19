<!--
  ============================================================================
  Run Inspector 页：单次执行的“体检报告”（对应任务书“Run Inspector”页面）
  ============================================================================
  【这一页做什么】
  1. 左侧表格列出全部执行（GET /api/runs），点击任意一行打开详情；
  2. 右侧三块内容（GET /api/runs/{id}）：
     - 节点结果表：每个节点跑成什么样（状态/错误码/重试次数/耗时）；
     - 事件时间线：该执行产生的事件按 seq 排序回放（REST 历史 + SSE 实时合并）；
     - 结构化输出：执行的最终产物 JSON（如研究报告的结论、净利率、证据链）。
  3. 监听全局事件流：新的实时事件到达时自动刷新列表与详情。

  【为什么事件时间线要“REST + SSE 合并”？】
  SSE 只能收到“建立连接之后”的新事件；如果执行发生在你打开页面前，
  就得靠 REST 回放历史。两者按事件序号 seq 去重合并，保证不重不漏。
-->
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import type { HarnessEvent, Run } from '../api/types'
import { useHarnessStore } from '../stores/harness'

const store = useHarnessStore()
const runs = ref<Run[]>([])                 // 执行列表
const selected = ref<Run | null>(null)      // 当前查看的执行
const runEvents = ref<HarnessEvent[]>([])   // REST 回放的历史事件
const error = ref('')

/** 刷新执行列表；若已选中某个执行则顺便更新它的详情（状态可能变了）。 */
async function refresh() {
  try {
    runs.value = await api.runs()
    if (selected.value) {
      const found = runs.value.find(r => r.id === selected.value!.id)
      if (found) selected.value = await api.run(found.id)
    }
  } catch (e) {
    error.value = String(e)
  }
}

/** 打开某执行详情：拉取执行本身 + 该执行的历史事件（REST 回放）。 */
async function open(run: Run) {
  error.value = ''
  try {
    selected.value = await api.run(run.id)
    // 事件时间线：REST 回放历史 + SSE 补充实时，二者按 seq 去重。
    runEvents.value = await api.events(run.id)
  } catch (e) {
    error.value = String(e)
  }
}

onMounted(refresh)
// 每来一条新事件（SSE 推送）就刷新列表，让“正在跑”的执行状态自动前进。
watch(() => store.events.length, refresh)

/**
 * REST 历史 + SSE 实时合并（按 seq 去重后升序排列）。
 * computed 的含义：依赖（runEvents / store.events / selected）任何一个变化，
 * 这个列表都会自动重算，模板随之更新。
 */
const mergedEvents = computed<HarnessEvent[]>(() => {
  if (!selected.value) return []
  const bySeq = new Map<number, HarnessEvent>()
  for (const e of runEvents.value) bySeq.set(e.seq, e)
  for (const e of store.eventsForRun(selected.value.id)) bySeq.set(e.seq, e)
  return [...bySeq.values()].sort((a, b) => a.seq - b.seq)
})
</script>

<template>
  <h1 class="page-title">Run Inspector</h1>
  <p class="page-sub">执行事件、节点结果、耗时、异常与结构化输出——全部来自真实执行记录。</p>
  <div v-if="error" class="error-text">{{ error }}</div>

  <div class="grid-2">
    <!-- 左侧：执行列表（点击行打开右侧详情） -->
    <div class="panel">
      <h3>执行列表（{{ runs.length }}）</h3>
      <table>
        <thead><tr><th>Run</th><th>Workflow</th><th>状态</th><th>错误码</th><th>触发</th></tr></thead>
        <tbody>
          <tr v-for="run in runs" :key="run.id" @click="open(run)"
              :style="selected?.id === run.id ? 'background: var(--panel-2); cursor: pointer' : 'cursor: pointer'">
            <td class="mono">{{ run.id }}</td>
            <td>{{ run.workflow }}</td>
            <td><span class="badge" :class="run.status">{{ run.status }}</span></td>
            <td class="mono">{{ run.errorCode ?? '—' }}</td>
            <td class="dim">{{ run.triggeredBy }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 右侧：选中执行的详情（三块） -->
    <div v-if="selected">
      <!-- 块 1：节点结果表 -->
      <div class="panel">
        <h3>节点结果 — {{ selected.id }}</h3>
        <table>
          <thead><tr><th>节点</th><th>状态</th><th>错误码</th><th>尝试</th><th>耗时ms</th></tr></thead>
          <tbody>
            <tr v-for="n in selected.nodeResults" :key="n.nodeId">
              <td class="mono">{{ n.nodeId }}</td>
              <td><span class="badge" :class="n.status">{{ n.status }}</span></td>
              <td class="mono">{{ n.errorCode ?? '—' }}</td>
              <td>{{ n.attempts }}</td>
              <td>{{ n.durationMs }}</td>
            </tr>
          </tbody>
        </table>
        <div v-if="selected.errorSummary" class="error-text" style="margin-top: 8px">
          {{ selected.errorSummary }}
        </div>
      </div>

      <!-- 块 2：事件时间线（REST 历史 + SSE 实时合并） -->
      <div class="panel">
        <h3>执行事件时间线（{{ mergedEvents.length }}）</h3>
        <div style="max-height: 260px; overflow-y: auto">
          <div v-if="!mergedEvents.length" class="dim">暂无事件</div>
          <div v-for="e in mergedEvents" :key="e.seq" class="event-line">
            <span class="type">{{ e.type }}</span>
            <span v-if="e.nodeId"> · {{ e.nodeId }}</span> · {{ e.message }}
            <span class="dim">（{{ e.occurredAt?.slice(11, 19) }}）</span>
          </div>
        </div>
      </div>

      <!-- 块 3：结构化输出（研究报告 JSON，含结论/净利率/证据链） -->
      <div class="panel">
        <h3>结构化输出</h3>
        <pre class="json">{{ JSON.stringify(selected.output, null, 2) }}</pre>
      </div>
    </div>
  </div>
</template>
