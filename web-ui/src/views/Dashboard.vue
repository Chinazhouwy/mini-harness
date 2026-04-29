<template>
  <div class="dashboard">
    <section class="header">
      <div>
        <p class="eyebrow">Reference MVP</p>
        <h1>策略闭环控制台</h1>
      </div>
      <button :disabled="loading" @click="runBacktest">
        {{ loading ? '运行中...' : '运行回测' }}
      </button>
    </section>

    <section class="control-panel">
      <label>
        股票代码
        <input v-model="form.stockCode" />
      </label>
      <label>
        开始日期
        <input v-model="form.startDate" type="date" />
      </label>
      <label>
        结束日期
        <input v-model="form.endDate" type="date" />
      </label>
      <label>
        快线
        <input v-model.number="form.fastWindow" min="2" type="number" />
      </label>
      <label>
        慢线
        <input v-model.number="form.slowWindow" min="3" type="number" />
      </label>
    </section>

    <p v-if="error" class="error">{{ error }}</p>

    <section class="panel" v-if="recentTasks.length">
      <div class="panel-head">
        <h2>最近任务</h2>
        <RouterLink to="/tasks">查看全部</RouterLink>
      </div>
      <div class="recent-tasks">
        <RouterLink
          v-for="task in recentTasks"
          :key="task.taskId"
          :to="`/tasks/${task.taskId}`"
        >
          <strong>{{ task.request.stockCode }}</strong>
          <span>{{ task.status }}</span>
          <small v-if="task.report">
            收益 {{ percent(task.report.metrics.totalReturn) }} / 回撤 {{ percent(task.report.metrics.maxDrawdown) }}
          </small>
          <small v-else>{{ task.errorMessage || '暂无报告' }}</small>
        </RouterLink>
      </div>
    </section>

    <section class="metrics" v-if="report">
      <article>
        <span>最终权益</span>
        <strong>{{ money(report.metrics.finalEquity) }}</strong>
      </article>
      <article>
        <span>总收益</span>
        <strong :class="tone(report.metrics.totalReturn)">{{ percent(report.metrics.totalReturn) }}</strong>
      </article>
      <article>
        <span>最大回撤</span>
        <strong>{{ percent(report.metrics.maxDrawdown) }}</strong>
      </article>
      <article>
        <span>交易次数</span>
        <strong>{{ report.metrics.tradeCount }}</strong>
      </article>
    </section>

    <section class="grid" v-if="report">
      <article class="panel">
        <div class="panel-head">
          <h2>最近信号</h2>
          <span>{{ report.signals.length }} 条</span>
        </div>
        <table>
          <thead>
            <tr>
              <th>日期</th>
              <th>类型</th>
              <th>价格</th>
              <th>原因</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="signal in report.signals.slice(-8).reverse()" :key="signal.tradeDate + signal.type">
              <td>{{ signal.tradeDate }}</td>
              <td><b :class="signal.type.toLowerCase()">{{ signal.type }}</b></td>
              <td>{{ money(signal.price) }}</td>
              <td>{{ signal.reason }}</td>
            </tr>
          </tbody>
        </table>
      </article>

      <article class="panel">
        <div class="panel-head">
          <h2>模拟订单</h2>
          <span>{{ report.orders.length }} 笔</span>
        </div>
        <table>
          <thead>
            <tr>
              <th>日期</th>
              <th>方向</th>
              <th>数量</th>
              <th>金额</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="order in report.orders.slice(-8).reverse()" :key="order.orderId">
              <td>{{ order.tradeDate }}</td>
              <td><b :class="order.side.toLowerCase()">{{ order.side }}</b></td>
              <td>{{ Number(order.quantity).toFixed(2) }}</td>
              <td>{{ money(order.amount) }}</td>
            </tr>
          </tbody>
        </table>
      </article>
    </section>

    <section class="panel" v-if="quality">
      <div class="panel-head">
        <h2>质检反馈</h2>
        <span :class="quality.passed ? 'pass' : 'fail'">{{ quality.passed ? 'PASS' : 'REVIEW' }}</span>
      </div>
      <div class="quality">
        <div v-for="check in quality.checks" :key="check.name">
          <b :class="check.passed ? 'pass' : 'fail'">{{ check.passed ? '通过' : '未通过' }}</b>
          <span>{{ check.name }}</span>
          <small>{{ check.message }}</small>
        </div>
      </div>
      <ul>
        <li v-for="suggestion in quality.suggestions" :key="suggestion">{{ suggestion }}</li>
      </ul>
    </section>
  </div>
</template>

<script setup lang="ts">
import axios from 'axios'
import { onMounted, ref } from 'vue'

type Metrics = {
  finalEquity: number
  totalReturn: number
  maxDrawdown: number
  tradeCount: number
}

type Signal = {
  tradeDate: string
  type: string
  price: number
  reason: string
}

type Order = {
  orderId: string
  tradeDate: string
  side: string
  quantity: number
  amount: number
}

type Report = {
  metrics: Metrics
  signals: Signal[]
  orders: Order[]
}

type Quality = {
  passed: boolean
  checks: Array<{ name: string; passed: boolean; message: string }>
  suggestions: string[]
}

type TaskSummary = {
  taskId: string
  status: string
  errorMessage?: string
  request: {
    stockCode: string
  }
  report?: {
    metrics: {
      totalReturn: number
      maxDrawdown: number
    }
  }
}

const form = ref({
  stockCode: '000001',
  startDate: '2024-01-01',
  endDate: '2024-12-31',
  fastWindow: 5,
  slowWindow: 20
})

const loading = ref(false)
const error = ref('')
const taskId = ref('')
const report = ref<Report | null>(null)
const quality = ref<Quality | null>(null)
const recentTasks = ref<TaskSummary[]>([])

async function runBacktest() {
  loading.value = true
  error.value = ''
  quality.value = null

  try {
    const response = await axios.post('/api/reference/backtest', {
      ...form.value,
      initialCash: 100000,
      maxPositionRatio: 0.8,
      stopLossRatio: 0.08
    })
    taskId.value = response.data.taskId
    report.value = response.data.report

    const qualityResponse = await axios.get(`/api/reference/quality/${taskId.value}`)
    quality.value = qualityResponse.data
    await loadRecentTasks()
  } catch (caught) {
    const maybeResponse = axios.isAxiosError(caught) ? caught.response?.data : null
    error.value = maybeResponse?.errorMessage || maybeResponse?.message || '回测请求失败，请确认 strategy-service 已启动且有行情数据。'
  } finally {
    loading.value = false
  }
}

async function loadRecentTasks() {
  try {
    const response = await axios.get('/api/reference/tasks?limit=5')
    recentTasks.value = response.data
  } catch {
    recentTasks.value = []
  }
}

function money(value: number) {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(Number(value || 0))
}

function percent(value: number) {
  return `${(Number(value || 0) * 100).toFixed(2)}%`
}

function tone(value: number) {
  return Number(value) >= 0 ? 'positive' : 'negative'
}

onMounted(loadRecentTasks)
</script>

<style scoped>
.dashboard {
  display: grid;
  gap: 22px;
}

.header {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 18px;
}

.eyebrow {
  margin: 0 0 6px;
  color: #c44b31;
  font-weight: 800;
  text-transform: uppercase;
}

h1 {
  margin: 0;
  font-size: 34px;
}

button {
  border: 0;
  border-radius: 6px;
  background: #c44b31;
  color: white;
  padding: 12px 18px;
  font-weight: 800;
  cursor: pointer;
}

button:disabled {
  opacity: 0.6;
  cursor: wait;
}

.control-panel,
.metrics,
.grid {
  display: grid;
  gap: 14px;
}

.control-panel {
  grid-template-columns: repeat(5, minmax(120px, 1fr));
  padding: 18px;
  border: 1px solid #d8ded8;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.72);
}

label {
  display: grid;
  gap: 7px;
  font-size: 13px;
  font-weight: 700;
  color: #4c5d58;
}

input {
  width: 100%;
  border: 1px solid #c8d0ca;
  border-radius: 6px;
  padding: 10px;
  background: white;
}

.metrics {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.metrics article,
.panel {
  border: 1px solid #d8ded8;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.82);
  box-shadow: 0 12px 34px rgba(16, 35, 33, 0.06);
}

.metrics article {
  padding: 18px;
}

.metrics span,
.panel-head span {
  color: #62726d;
  font-size: 13px;
}

.metrics strong {
  display: block;
  margin-top: 9px;
  font-size: 26px;
}

.grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.panel {
  padding: 18px;
  overflow: auto;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

h2 {
  margin: 0;
  font-size: 18px;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

th,
td {
  padding: 10px 8px;
  border-bottom: 1px solid #e3e7e2;
  text-align: left;
  vertical-align: top;
}

th {
  color: #63736f;
  font-weight: 800;
}

.buy,
.positive,
.pass {
  color: #157347;
}

.sell,
.negative,
.fail,
.error {
  color: #b02a22;
}

.quality {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.recent-tasks {
  display: grid;
  gap: 10px;
}

.recent-tasks a {
  display: grid;
  grid-template-columns: 100px 80px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
  padding: 12px;
  border-radius: 6px;
  background: #f2f5ef;
  color: inherit;
  text-decoration: none;
}

.recent-tasks small {
  color: #62726d;
}

.quality div {
  display: grid;
  gap: 6px;
  padding: 12px;
  border-radius: 6px;
  background: #f2f5ef;
}

.quality small {
  color: #63736f;
}

@media (max-width: 980px) {
  .control-panel,
  .metrics,
  .grid,
  .quality {
    grid-template-columns: 1fr;
  }

  .recent-tasks a {
    grid-template-columns: 1fr;
  }

  .header {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
