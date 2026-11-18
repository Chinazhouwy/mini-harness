<!--
  ============================================================================
  Evolution Review 页：自进化实验的操作台（对应任务书“Evolution Review”页面）
  ============================================================================
  【这一页做什么——也是整个项目的“点睛之笔”】
  1. 点“运行自进化实验”→ 调 POST /api/evolution/experiment/prepare：
     后端在 Workflow v1 上跑受控测试 → 从失败轨迹中挖掘缺陷签名 →
     自动提出 v2 候选（插入口径验证节点）+ 一个故意“过激”的对照候选 →
     在训练/留出两套案例上真实执行对比评测 → 给出 5 条门禁判定；
  2. 候选列表展示每个候选的状态（待评测/通过门禁/未通过/已批准/已拒绝）；
  3. 点“详情”→ 展示改进原因、来源证据（失败 Run 列表）、版本差异、
     评测对比表（正确率/错误检出率/耗时/成本/回归）、逐案例结果；
  4. 通过门禁的候选可以“批准并发布新版本”或“拒绝”——
     批准后发布指针移到新版本，旧版本保留可回滚；拒绝则指针不动。
     批准/拒绝都由后端校验（未通过门禁的候选批准会被 409 拒绝）。

  【为什么这一页值得仔细看】
  它把“Agent 系统能否从失败经验中自我改进”这个问题变成了可点击、可审计的
  操作流程：改进不是模型嘴上说的，而是有来源 Run、有对比评测数字、有人工审批。
-->
<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import type { Candidate, EvalMetrics, WorkflowDiff } from '../api/types'
import { useHarnessStore } from '../stores/harness'

const store = useHarnessStore()
const candidates = ref<Candidate[]>([])   // 候选列表
const selected = ref<Candidate | null>(null)  // 当前查看的候选详情
const diff = ref<WorkflowDiff | null>(null)   // 候选与当前发布版本的差异
const operator = ref('operator-a')        // 审批人姓名（审计用）
const note = ref('')                      // 审批备注
const preparing = ref(false)              // “运行实验”按钮的加载态
const message = ref('')                   // 操作结果提示（绿字）
const error = ref('')                     // 错误提示（红字）

/** 刷新候选列表；保持当前选中的候选为最新状态。 */
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

/**
 * 一键运行实验。后端做了四件事：
 * (1) v1 受控测试沉淀失败经验；(2) 轨迹分析产出 v2 候选；
 * (3) 手动构造过激对照候选；(4) 两个候选分别对比评测。
 * 返回的 candidatePassedGate/aggressiveFailedGate 直接告诉页面门禁结论。
 */
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

/** 打开候选详情（后端会附上与当前发布版本的差异 diffAgainstPublished）。 */
async function open(candidate: Candidate) {
  error.value = ''
  try {
    // 先存局部变量再赋给 ref：await 之后 TS 无法继续窄化 ref 的类型。
    const detail = await api.candidate(candidate.id)
    selected.value = detail
    diff.value = detail.diffAgainstPublished ?? null
  } catch (e) {
    error.value = String(e)
  }
}

/** 批准（发布新版本）或拒绝（指针不动）。两者都受后端校验。 */
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

/** 回滚发布指针到某个曾发布过的版本（演示用固定 profitability-research）。 */
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

onMounted(refresh)
// 实验事件（候选创建/评测完成/批准）通过 SSE 到达时自动刷新列表。
watch(() => store.events.length, refresh)
</script>

<template>
  <h1 class="page-title">Evolution Review</h1>
  <p class="page-sub">候选流程版本差异、改进原因、评测对比与审批操作。批准/拒绝均受后端校验。</p>
  <div v-if="error" class="error-text">{{ error }}</div>
  <div v-if="message" class="ok-text">{{ message }}</div>

  <!-- 实验控制条 -->
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

  <!-- 候选列表 -->
  <div class="panel">
    <h3>候选列表（{{ candidates.length }}）</h3>
    <table>
      <thead><tr><th>候选</th><th>规则</th><th>状态</th><th>来源Run</th><th>决策人</th><th></th></tr></thead>
      <tbody>
        <tr v-for="c in candidates" :key="c.id">
          <td class="mono">{{ c.id }}</td>
          <td>{{ c.rule }}</td>
          <td><span class="badge" :class="c.status">{{ c.status }}</span></td>
          <!-- 来源 Run/案例就是“改进的证据”：改进从哪些失败记录里来 -->
          <td class="dim mono">{{ c.sourceRunIds.join(', ') || '—' }}</td>
          <td>{{ c.decidedBy ?? '—' }}</td>
          <td><button class="secondary" @click="open(c)">详情</button></td>
        </tr>
        <tr v-if="!candidates.length"><td colspan="6" class="dim">暂无候选，点上方按钮运行实验</td></tr>
      </tbody>
    </table>
  </div>

  <!-- 选中候选的详情（左右两栏） -->
  <div v-if="selected" class="grid-2">
    <div>
      <!-- 左上：改进原因 / 适用条件 / 来源证据 / 决策记录 -->
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

      <!-- 左中：版本差异（新增/删除了哪些节点与边） -->
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

      <!-- 左下：审批操作区（只有通过门禁的候选才显示按钮） -->
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

    <!-- 右侧：评测报告（仅在候选评测过之后显示） -->
    <div v-if="selected.evalReport">
      <!-- 右上：留出集指标对比（全部来自真实执行） -->
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

      <!-- 右中：门禁判定（5 条硬规则，逐条给出通过与否及理由） -->
      <div class="panel">
        <h3>门禁判定</h3>
        <div v-for="gate in selected.evalReport.gateResults" :key="gate.rule" class="event-line">
          <span :class="gate.passed ? 'ok-text' : 'error-text'">{{ gate.passed ? '✓' : '✗' }}</span>
          <span class="mono"> {{ gate.rule }}</span> — {{ gate.detail }}
        </div>
      </div>

      <!-- 右下：逐案例结果（每个案例在候选上的真实执行结果） -->
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
