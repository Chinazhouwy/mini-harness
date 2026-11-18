<!--
  ============================================================================
  Workflow Canvas 页：把后端真实流程画成图（对应任务书“Workflow Canvas”页面）
  ============================================================================
  【这一页做什么】
  1. 下拉框选 Workflow 名称与版本（数据来自 GET /api/workflows，含发布指针标记）；
  2. 用 Vue Flow（流程图库）把选中版本的“节点+依赖边”画出来——
     节点与边的坐标/连线全部由后端 WorkflowDefinition 计算，前端只是画笔；
  3. 可选“叠加 Run”：选一次真实执行后，节点边框按该执行的真实状态着色
     （绿=成功，红=失败，黄=运行中，灰=被跳过）。

  【分层布局怎么算的？】
  节点的横向位置 = 它在依赖图里的“深度”：没有任何上游的节点在第 0 列，
  依赖越多层越靠右；同层内纵向依次排开。计算逻辑在 flowNodes 的 computed 里
  （递归 resolveDepth）。第一版只做可视化展示，不做自由拖拽编排（任务书允许）。

  【Vue Flow 用法速记】
  <VueFlow :nodes="..." :edges="..."/> 接收两个数组：
  节点 { id, position:{x,y}, label, class }，边 { id, source, target }。
  样式（.flow-node、status-* 类）见 style.css。
-->
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { VueFlow, type Edge, type Node } from '@vue-flow/core'
// Vue Flow 自带的基础样式（不引入连线/节点会画不出来）。
import '@vue-flow/core/dist/style.css'
import { api } from '../api/client'
import type { Run, WorkflowInfo, WorkflowVersion } from '../api/types'
import { useHarnessStore } from '../stores/harness'

const store = useHarnessStore()
const workflows = ref<WorkflowInfo[]>([])       // 后端全部 Workflow（含版本与发布指针）
const selectedName = ref('')                    // 当前选中的 Workflow 名
const selectedVersion = ref<number | null>(null)// 当前选中的版本号
const overlayRunId = ref('')                    // 叠加显示的执行 ID（空=不叠加）
const overlayRun = ref<Run | null>(null)        // 该执行的详情（节点状态着色用）
const error = ref('')

const runOptions = ref<Run[]>([])               // 叠加下拉框的可选执行列表

/** 当前选中的版本对象（找不到返回 null，模板里画空画布）。 */
const version = computed<WorkflowVersion | null>(() => {
  const info = workflows.value.find(w => w.name === selectedName.value)
  if (!info) return null
  return info.versions.find(v => v.version === selectedVersion.value) ?? null
})

/** 从后端拉 Workflow 列表与执行列表；首次打开自动选中“已发布版本”。 */
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

/**
 * 画布节点：由选中版本的定义计算而来。
 * 分层算法：深度=上游链最长距离；x=深度*230（列距），y=同层序号*110（行距）。
 * 若叠加了执行，节点 class 加上 status-xxx，样式表据此着色边框。
 */
const flowNodes = computed<Node[]>(() => {
  const def = version.value
  if (!def) return []
  // depth：每个节点的层数；incoming：节点 → 它的上游节点列表。
  const depth = new Map<string, number>()
  const incoming = new Map<string, string[]>()
  for (const e of def.edges) {
    incoming.set(e.to, [...(incoming.get(e.to) ?? []), e.from])
  }
  // 递归求深度：无上游=0，否则=所有上游深度的最大值+1。
  const resolveDepth = (id: string): number => {
    if (depth.has(id)) return depth.get(id)!
    const ups = incoming.get(id) ?? []
    const d = ups.length === 0 ? 0 : Math.max(...ups.map(resolveDepth)) + 1
    depth.set(id, d)
    return d
  }
  for (const n of def.nodes) resolveDepth(n.id)
  // 同层内再排个序号，决定纵向位置。
  const byLayer = new Map<number, string[]>()
  for (const n of def.nodes) {
    const d = depth.get(n.id) ?? 0
    byLayer.set(d, [...(byLayer.get(d) ?? []), n.id])
  }
  // 叠加执行时，取每个节点的真实状态（用于着色）。
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

/** 画布连线：直接来自后端定义的依赖边；叠加执行时“上游完成、下游在跑”的边做流动动画。 */
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

/** 切换叠加的执行时拉取其详情（节点状态用于着色与动画判断）。 */
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
// 有新事件到达时刷新（比如叠加的执行刚结束，状态需要更新）。
watch(() => store.events.length, refresh)
</script>

<template>
  <h1 class="page-title">Workflow Canvas</h1>
  <p class="page-sub">真实执行流程可视化：节点依赖来自后端 WorkflowDefinition，节点状态可叠加真实 Run。第一版不做自由拖拽编排。</p>
  <div v-if="error" class="error-text">{{ error }}</div>

  <!-- 控制条：选 Workflow / 选版本 / 选叠加的执行 -->
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

  <!-- 画布：Vue Flow 组件，fit-view-on-init 让图自动缩放到可视区 -->
  <div class="canvas-wrap">
    <VueFlow :nodes="flowNodes" :edges="flowEdges" fit-view-on-init />
  </div>

  <!-- 叠加执行时的节点状态清单 -->
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
