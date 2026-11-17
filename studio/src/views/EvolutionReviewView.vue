<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import type { Candidate, EvalMetrics, WorkflowDiff } from '../api/types'
import { useHarnessStore } from '../stores/harness'

const store = useHarnessStore()
const candidates = ref<Candidate[]>([])
const selected = ref<Candidate | null>(null)
const diff = ref<WorkflowDiff | null>(null)
const operator = ref('operator-a')
const note = ref('')
const preparing = ref(false)
const message = ref('')
const error = ref('')

async function refresh() {
  try {
    candidates.value = await api.candidates()
    if (selected.value) {
      const found = candidates.value.find(c => c.id === selected.value!.id)
      selected.value = found ?? null
      diff.value = null
    }
  } catch (e) {
    error.value = String(e)
  }
}

async function prepare() {
  preparing.value = true
  error.value = ''
  message.value = ''
  try {
    const result = await api.prepareExperiment()
    message.value = `实验就绪：v2 候选 ${result.candidateId}（${result.candidatePassedGate ? '通过门禁' : '未过门禁'}），`
      + `过激对照候选 ${result.aggressiveCandidateId}（${result.aggressiveFailedGate ? '被门禁拦下' : '意外通过'}）`
    await refresh()
  } catch (e) {
    error.value = String(e)
  } finally {
    preparing.value = false
  }
}

async function open(candidate: Candidate) {
  error.value = ''
  try {
    selected.value = await api.candidate(candidate.id)
    diff.value = selected.value.diffAgainstPublished ?? null
  } catch (e) {
    error.value = String(e)
  }
}

async function decide(approve: boolean) {
  if (!selected.value) return
  error.value = ''
  try {
    const result = approve
      ? await api.approveCandidate(selected.value.id, operator.value, note.value)
      : await api.rejectCandidate(selected.value.id, operator.value, note.value)
    message.value = approve
      ? `已批准并发布 v${result.publishedVersion}`
      : `已拒绝候选，发布指针保持 v${result.publishedVersion}`
    note.value = ''
    await refresh()
  } catch (e) {
    error.value = String(e)
  }
}

async function rollback(version: number) {
  error.value = ''
  try {
    const result = await api.rollback('profitability-research', version, operator.value)
    message.value = `已回滚到 v${result.publishedVersion}`
    await refresh()
  } catch (e) {
    error.value = String(e)
  }
}

function metricRow(label: string, baseline: EvalMetrics, candidate: EvalMetrics, field: keyof EvalMetrics) {
  return { label, baseline: baseline[field], candidate: candidate[field] }
}

onMounted(refresh)
watch(() => store.events.length, refresh)
</script>

<template>
  <h1 class="page-title">Evolution Review</h1>
  <p class="page-sub">候选流程版本差异、改进原因、评测对比与审批操作。批准/拒绝均受后端校验。</p>
  <div v-if="error" class="error-text">{{ error }}</div>
  <div v-if="message" class="ok-text">{{ message }}</div>

  <div class="panel">
    <div class="row">
      <button :disabled="preparing" @click="prepare">
        {{ preparing ? '实验运行中…' : '运行自进化实验（v1 受控测试 → 轨迹分析 → v2 候选 → 对比评测）' }}
      </button>
      <button class="secondary" @click="refresh">刷新</button>
      <span class="dim">审批人</span>
      <input v-model="operator" type="text" style="width: 120px" />
    </div>
    <div class="dim" style="margin-top: 8px">
      提示：首次使用请先在 Chat 页面跑一次任务（可选），然后点上方按钮准备实验。评测结果来自真实执行。
    </div>
  </div>

  <div class="panel">
    <h3>候选列表（{{ candidates.length }}）</h3>
    <table>
      <thead><tr><th>候选</th><th>规则</th><th>状态</th><th>来源Run</th><th>决策人</th><th></th></tr></thead>
      <tbody>
        <tr v-for="c in candidates" :key="c.id">
          <td class="mono">{{ c.id }}</td>
          <td>{{ c.rule }}</td>
          <td><span class="badge" :class="c.status">{{ c.status }}</span></td>
          <td class="dim mono">{{ c.sourceRunIds.join(', ') || '—' }}</td>
          <td>{{ c.decidedBy ?? '—' }}</td>
          <td><button class="secondary" @click="open(c)">详情</button></td>
        </tr>
        <tr v-if="!candidates.length"><td colspan="6" class="dim">暂无候选，点上方按钮运行实验</td></tr>
      </tbody>
    </table>
  </div>

  <div v-if="selected" class="grid-2">
    <div>
      <div class="panel">
        <h3>候选 {{ selected.id }}（{{ selected.status }}）</h3>
        <div class="col">
          <div><b>改进原因：</b>{{ selected.rationale }}</div>
          <div><b>适用条件：</b>
            <ul style="margin: 4px 0; padding-left: 18px">
              <li v-for="cond in selected.applicableConditions" :key="cond">{{ cond }}</li>
            </ul>
          </div>
          <div><b>来源 Run：</b><span class="mono">{{ selected.sourceRunIds.join(', ') || '—' }}</span></div>
          <div v-if="selected.decisionNote"><b>决策备注：</b>{{ selected.decisionNote }}（{{ selected.decidedBy }}）</div>
        </div>
      </div>

      <div class="panel">
        <h3>版本差异（{{ diff?.from ?? selected.proposedDefinition.name + '@v?' }} → 候选）</h3>
        <div v-if="diff">
          <div>新增节点：<span class="ok-text mono">{{ diff.addedNodes.join(', ') || '无' }}</span></div>
          <div>删除节点：<span class="error-text mono">{{ diff.removedNodes.join(', ') || '无' }}</span></div>
          <div>新增边：<span class="mono">{{ diff.addedEdges.map(e => `${e.from}→${e.to}`).join(', ') || '无' }}</span></div>
        </div>
        <div v-else class="dim">与当前发布版本的差异见 proposedDefinition（后端字段 diffAgainstPublished）</div>
        <div class="dim" style="margin-top: 8px">
          候选节点：{{ selected.proposedDefinition.nodes.map(n => n.id).join(' → ') }}
        </div>
      </div>

      <div class="panel" v-if="selected.status === 'EVALUATED_PASS'">
        <h3>审批操作</h3>
        <div class="col">
          <textarea v-model="note" rows="2" placeholder="审批备注（可选）"></textarea>
          <div class="row">
            <button @click="decide(true)">批准并发布新版本</button>
            <button class="danger" @click="decide(false)">拒绝候选</button>
          </div>
        </div>
      </div>
      <div class="panel" v-else-if="selected.status === 'PENDING_EVAL'">
        <h3>审批操作</h3>
        <div class="dim">候选尚未评测：点击「运行自进化实验」完成对比评测后才能审批。</div>
      </div>
    </div>

    <div v-if="selected.evalReport">
      <div class="panel">
        <h3>评测对比（留出集，真实执行）
          <span class="badge" :class="selected.evalReport.passed ? 'EVALUATED_PASS' : 'EVALUATED_FAIL'">
            {{ selected.evalReport.passed ? '通过门禁' : '未通过门禁' }}
          </span>
        </h3>
        <table>
          <thead><tr><th>指标</th><th>v1（基线）</th><th>候选</th></tr></thead>
          <tbody>
            <tr><td>正确率</td><td>{{ selected.evalReport.baselineHoldout.accuracy }}</td>
              <td>{{ selected.evalReport.candidateHoldout.accuracy }}</td></tr>
            <tr><td>错误检出率</td><td>{{ selected.evalReport.baselineHoldout.errorDetectionRate }}</td>
              <td>{{ selected.evalReport.candidateHoldout.errorDetectionRate }}</td></tr>
            <tr><td>平均耗时(ms)</td><td>{{ selected.evalReport.baselineHoldout.avgDurationMs }}</td>
              <td>{{ selected.evalReport.candidateHoldout.avgDurationMs }}</td></tr>
            <tr><td>平均工具调用</td><td>{{ selected.evalReport.baselineHoldout.avgToolCalls }}</td>
              <td>{{ selected.evalReport.candidateHoldout.avgToolCalls }}</td></tr>
            <tr><td>失败案例</td><td>{{ selected.evalReport.baselineHoldout.failedCaseIds.join(', ') || '无' }}</td>
              <td>{{ selected.evalReport.candidateHoldout.failedCaseIds.join(', ') || '无' }}</td></tr>
          </tbody>
        </table>
      </div>

      <div class="panel">
        <h3>门禁判定</h3>
        <div v-for="gate in selected.evalReport.gateResults" :key="gate.rule" class="event-line">
          <span :class="gate.passed ? 'ok-text' : 'error-text'">{{ gate.passed ? '✓' : '✗' }}</span>
          <span class="mono"> {{ gate.rule }}</span> — {{ gate.detail }}
        </div>
      </div>

      <div class="panel">
        <h3>逐案例结果（候选）</h3>
        <table>
          <thead><tr><th>案例</th><th>集</th><th>期望</th><th>实际</th><th>通过</th><th>工具调用</th></tr></thead>
          <tbody>
            <tr v-for="c in selected.evalReport.candidateCaseResults" :key="c.caseId">
              <td class="mono">{{ c.caseId }}</td>
              <td>{{ c.split }}</td>
              <td>{{ c.expected }}</td>
              <td class="mono">{{ c.outcome }}{{ c.errorCode ? `(${c.errorCode})` : '' }}</td>
              <td :class="c.passed ? 'ok-text' : 'error-text'">{{ c.passed ? '✓' : '✗' }}</td>
              <td>{{ c.toolCalls }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>
