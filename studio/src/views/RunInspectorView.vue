<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import type { HarnessEvent, Run } from '../api/types'
import { useHarnessStore } from '../stores/harness'

const store = useHarnessStore()
const runs = ref<Run[]>([])
const selected = ref<Run | null>(null)
const runEvents = ref<HarnessEvent[]>([])
const error = ref('')

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
watch(() => store.events.length, refresh)

/** REST 历史 + SSE 实时合并（按 seq 去重）。 */
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

    <div v-if="selected">
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

      <div class="panel">
        <h3>结构化输出</h3>
        <pre class="json">{{ JSON.stringify(selected.output, null, 2) }}</pre>
      </div>
    </div>
  </div>
</template>
