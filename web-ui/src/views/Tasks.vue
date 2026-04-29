<template>
  <div class="tasks">
    <section class="header">
      <div>
        <p class="eyebrow">History</p>
        <h1>回测任务</h1>
      </div>
      <button @click="loadTasks">刷新</button>
    </section>

    <p v-if="error" class="error">{{ error }}</p>

    <section class="task-list">
      <RouterLink
        v-for="task in tasks"
        :key="task.taskId"
        class="task-card"
        :to="`/tasks/${task.taskId}`"
      >
        <div class="task-top">
          <strong>{{ task.request.stockCode }}</strong>
          <span :class="task.status.toLowerCase()">{{ task.status }}</span>
        </div>
        <div class="task-meta">
          <span>{{ task.request.startDate }} 至 {{ task.request.endDate }}</span>
          <span>MA({{ task.request.fastWindow }}, {{ task.request.slowWindow }})</span>
        </div>
        <div class="task-metrics" v-if="task.report">
          <span>收益 {{ percent(task.report.metrics.totalReturn) }}</span>
          <span>回撤 {{ percent(task.report.metrics.maxDrawdown) }}</span>
          <span>交易 {{ task.report.metrics.tradeCount }}</span>
        </div>
        <p v-else>{{ task.errorMessage || '暂无报告' }}</p>
      </RouterLink>
    </section>
  </div>
</template>

<script setup lang="ts">
import axios from 'axios'
import { onMounted, ref } from 'vue'

type Task = {
  taskId: string
  status: string
  errorMessage?: string
  request: {
    stockCode: string
    startDate: string
    endDate: string
    fastWindow: number
    slowWindow: number
  }
  report?: {
    metrics: {
      totalReturn: number
      maxDrawdown: number
      tradeCount: number
    }
  }
}

const tasks = ref<Task[]>([])
const error = ref('')

async function loadTasks() {
  error.value = ''
  try {
    const response = await axios.get('/api/reference/tasks?limit=30')
    tasks.value = response.data
  } catch (caught) {
    const maybeResponse = axios.isAxiosError(caught) ? caught.response?.data : null
    error.value = maybeResponse?.message || '任务列表加载失败，请先启动 strategy-service。'
  }
}

function percent(value: number) {
  return `${(Number(value || 0) * 100).toFixed(2)}%`
}

onMounted(loadTasks)
</script>

<style scoped>
.tasks {
  display: grid;
  gap: 20px;
}

.header,
.task-top,
.task-meta,
.task-metrics {
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

h1 {
  margin: 0;
  font-size: 34px;
}

button {
  border: 0;
  border-radius: 6px;
  background: #102321;
  color: white;
  padding: 11px 16px;
  font-weight: 800;
}

.task-list {
  display: grid;
  gap: 12px;
}

.task-card {
  display: grid;
  gap: 12px;
  padding: 18px;
  border: 1px solid #d8ded8;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.84);
  color: inherit;
  text-decoration: none;
}

.task-card:hover {
  border-color: #9eb1aa;
}

.task-meta,
.task-metrics {
  color: #62726d;
  font-size: 13px;
}

.success {
  color: #157347;
}

.failed,
.error {
  color: #b02a22;
}

@media (max-width: 720px) {
  .header,
  .task-top,
  .task-meta,
  .task-metrics {
    align-items: flex-start;
    flex-direction: column;
  }

  h1 {
    font-size: 28px;
  }
}
</style>
