<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { VueFlow, type Edge, type Node } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import { api } from '../api/client'
import type { Run, WorkflowInfo, WorkflowVersion } from '../api/types'
import { useHarnessStore } from '../stores/harness'

const store = useHarnessStore()
const workflows = ref<WorkflowInfo[]>([])
const selectedName = ref('')
const selectedVersion = ref<number | null>(null)
const overlayRunId = ref('')
const overlayRun = ref<Run | null>(null)
const error = ref('')

const version = computed<WorkflowVersion | null>(() => {
  const info = workflows.value.find(w => w.name === selectedName.value)
  if (!info) return null
  return info.versions.find(v => v.version === selectedVersion.value) ?? null
})

const runOptions = ref<Run[]>([])

async function refresh() {
  try {
    workflows.value = await api.workflows()
    runOptions.value = await api.runs()
    if (!selectedName.value && workflows.value.length) {
      selectedName.value = workflows.value[0].name
    }
    if (selectedName.value && selectedVersion.value === null) {
      selectedVersion.value = workflows.value.find(w => w.name === selectedName.value)
        ?.publishedVersion ?? workflows.value[0]?.versions[0]?.version ?? null
    }
  } catch (e) {
    error.value = String(e)
  }
}

const flowNodes = computed<Node[]>(() => {
  const def = version.value
  if (!def) return []
  // 分层布局：按依赖深度排列（第一版只做可视化，不做自由拖拽编排）。
  const depth = new Map<string, number>()
  const incoming = new Map<string, string[]>()
  for (const e of def.edges) {
    incoming.set(e.to, [...(incoming.get(e.to) ?? []), e.from])
  }
  const resolveDepth = (id: string): number => {
    if (depth.has(id)) return depth.get(id)!
    const ups = incoming.get(id) ?? []
    const d = ups.length === 0 ? 0 : Math.max(...ups.map(resolveDepth)) + 1
    depth.set(id, d)
    return d
  }
  for (const n of def.nodes) resolveDepth(n.id)
  const byLayer = new Map<number, string[]>()
  for (const n of def.nodes) {
    const d = depth.get(n.id) ?? 0
    byLayer.set(d, [...(byLayer.get(d) ?? []), n.id])
  }
  const statusById = new Map<string, string>()
  if (overlayRun.value) {
    for (const nr of overlayRun.value.nodeResults ?? []) statusById.set(nr.nodeId, nr.status)
  }
  return def.nodes.map(n => {
    const d = depth.get(n.id) ?? 0
    const index = (byLayer.get(d) ?? []).indexOf(n.id)
    const status = statusById.get(n.id)
    return {
      id: n.id,
      position: { x: 60 + d * 230, y: 40 + index * 110 },
      label: n.id,
      data: { label: `${n.id}\n${n.type}` },
      class: status ? `status-${status}` : ''
    } satisfies Node
  })
})

const flowEdges = computed<Edge[]>(() => {
  const def = version.value
  if (!def) return []
  return def.edges.map((e, i) => ({
    id: `e${i}`,
    source: e.from,
    target: e.to,
    animated: !!overlayRun.value
      && overlayRun.value.nodeResults?.some(n => n.nodeId === e.from && n.status === 'SUCCEEDED')
      && overlayRun.value.nodeResults?.some(n => n.nodeId === e.to && n.status === 'RUNNING')
  }))
})

async function loadOverlay() {
  overlayRun.value = null
  if (overlayRunId.value) {
    try {
      overlayRun.value = await api.run(overlayRunId.value)
    } catch (e) {
      error.value = String(e)
    }
  }
}

onMounted(refresh)
watch(() => store.events.length, refresh)
</script>

<template>
  <h1 class="page-title">Workflow Canvas</h1>
  <p class="page-sub">真实执行流程可视化：节点依赖来自后端 WorkflowDefinition，节点状态可叠加真实 Run。第一版不做自由拖拽编排。</p>
  <div v-if="error" class="error-text">{{ error }}</div>

  <div class="panel">
    <div class="row">
      <select v-model="selectedName">
        <option v-for="w in workflows" :key="w.name" :value="w.name">{{ w.name }}</option>
      </select>
      <select v-model.number="selectedVersion">
        <option v-for="v in workflows.find(w => w.name === selectedName)?.versions ?? []"
                :key="v.version" :value="v.version">
          v{{ v.version }}{{ v.published ? '（已发布）' : '' }}
        </option>
      </select>
      <select v-model="overlayRunId" @change="loadOverlay">
        <option value="">不叠加 Run</option>
        <option v-for="r in runOptions" :key="r.id" :value="r.id">{{ r.id }}（{{ r.status }}）</option>
      </select>
      <button class="secondary" @click="refresh">刷新</button>
    </div>
    <div v-if="version" class="dim" style="margin-top: 8px">{{ version.description }}</div>
  </div>

  <div class="canvas-wrap">
    <VueFlow :nodes="flowNodes" :edges="flowEdges" fit-view-on-init />
  </div>

  <div v-if="overlayRun" class="panel" style="margin-top: 14px">
    <h3>叠加执行：{{ overlayRun.id }}（{{ overlayRun.status }}）</h3>
    <div v-for="n in overlayRun.nodeResults" :key="n.nodeId" class="event-line">
      <span class="badge" :class="n.status">{{ n.status }}</span>
      <span class="mono"> {{ n.nodeId }}</span>
      <span v-if="n.errorCode" class="error-text mono"> · {{ n.errorCode }}</span>
      <span class="dim"> · {{ n.durationMs }}ms</span>
    </div>
  </div>
</template>
