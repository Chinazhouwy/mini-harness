<template>
  <div class="detail">
    <section class="header">
      <div>
        <p class="eyebrow">Task Detail</p>
        <h1>{{ task?.request.stockCode || '回测任务' }}</h1>
      </div>
      <RouterLink class="back" to="/tasks">返回任务列表</RouterLink>
    </section>

    <p v-if="error" class="error">{{ error }}</p>

    <template v-if="task?.report">
      <section class="metrics">
        <article>
          <span>最终权益</span>
          <strong>{{ money(task.report.metrics.finalEquity) }}</strong>
        </article>
        <article>
          <span>总收益</span>
          <strong :class="task.report.metrics.totalReturn >= 0 ? 'positive' : 'negative'">
            {{ percent(task.report.metrics.totalReturn) }}
          </strong>
        </article>
        <article>
          <span>最大回撤</span>
          <strong>{{ percent(task.report.metrics.maxDrawdown) }}</strong>
        </article>
        <article>
          <span>交易次数</span>
          <strong>{{ task.report.metrics.tradeCount }}</strong>
        </article>
      </section>

      <section class="panel">
        <div class="panel-head">
          <h2>质检建议</h2>
          <span v-if="quality" :class="quality.passed ? 'pass' : 'fail'">{{ quality.passed ? 'PASS' : 'REVIEW' }}</span>
        </div>
        <ul v-if="quality">
          <li v-for="suggestion in quality.suggestions" :key="suggestion">{{ suggestion }}</li>
        </ul>
      </section>

      <section class="grid">
        <article class="panel">
          <div class="panel-head">
            <h2>信号</h2>
            <span>{{ task.report.signals.length }} 条</span>
          </div>
          <div class="mobile-list">
            <div v-for="signal in task.report.signals" :key="signal.tradeDate + signal.type">
              <b :class="signal.type.toLowerCase()">{{ signal.type }}</b>
              <span>{{ signal.tradeDate }}</span>
              <span>{{ money(signal.price) }}</span>
              <small>{{ signal.reason }}</small>
            </div>
          </div>
        </article>

        <article class="panel">
          <div class="panel-head">
            <h2>订单</h2>
            <span>{{ task.report.orders.length }} 笔</span>
          </div>
          <div class="mobile-list">
            <div v-for="order in task.report.orders" :key="order.orderId">
              <b :class="order.side.toLowerCase()">{{ order.side }}</b>
              <span>{{ order.tradeDate }}</span>
              <span>{{ money(order.amount) }}</span>
              <small>{{ Number(order.quantity).toFixed(2) }} 股 @ {{ money(order.price) }}</small>
            </div>
          </div>
        </article>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import axios from 'axios'
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

type Task = {
  taskId: string
  request: { stockCode: string }
  report?: {
    metrics: {
      finalEquity: number
      totalReturn: number
      maxDrawdown: number
      tradeCount: number
    }
    signals: Array<{ tradeDate: string; type: string; price: number; reason: string }>
    orders: Array<{ orderId: string; tradeDate: string; side: string; price: number; quantity: number; amount: number }>
  }
}

type Quality = {
  passed: boolean
  suggestions: string[]
}

const route = useRoute()
const task = ref<Task | null>(null)
const quality = ref<Quality | null>(null)
const error = ref('')

async function loadTask() {
  error.value = ''
  const taskId = String(route.params.taskId)
  try {
    const [taskResponse, qualityResponse] = await Promise.all([
      axios.get(`/api/reference/tasks/${taskId}`),
      axios.get(`/api/reference/tasks/${taskId}/quality`)
    ])
    task.value = taskResponse.data
    quality.value = qualityResponse.data
  } catch (caught) {
    const maybeResponse = axios.isAxiosError(caught) ? caught.response?.data : null
    error.value = maybeResponse?.message || '任务详情加载失败。'
  }
}

function money(value: number) {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(Number(value || 0))
}

function percent(value: number) {
  return `${(Number(value || 0) * 100).toFixed(2)}%`
}

onMounted(loadTask)
</script>

<style scoped>
.detail {
  display: grid;
  gap: 20px;
}

.header,
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.eyebrow {
  margin: 0 0 6px;
  color: #c44b31;
  font-weight: 800;
  text-transform: uppercase;
}

h1,
h2 {
  margin: 0;
}

h1 {
  font-size: 34px;
}

.back {
  color: #102321;
  font-weight: 800;
}

.metrics,
.grid {
  display: grid;
  gap: 14px;
}

.metrics {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.metrics article,
.panel {
  border: 1px solid #d8ded8;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.84);
  padding: 18px;
}

.metrics span {
  color: #62726d;
  font-size: 13px;
}

.metrics strong {
  display: block;
  margin-top: 9px;
  font-size: 24px;
}

.grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.mobile-list {
  display: grid;
  gap: 10px;
  margin-top: 12px;
}

.mobile-list div {
  display: grid;
  gap: 5px;
  padding: 12px;
  border-radius: 6px;
  background: #f2f5ef;
}

.mobile-list small {
  color: #62726d;
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

@media (max-width: 860px) {
  .header,
  .panel-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .metrics,
  .grid {
    grid-template-columns: 1fr;
  }

  h1 {
    font-size: 28px;
  }
}
</style>
