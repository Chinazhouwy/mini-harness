<script setup lang="ts">
import { onMounted } from 'vue'
import { useHarnessStore } from './stores/harness'

// 全局建立 SSE 连接：所有页面共享实时事件流。
const store = useHarnessStore()
onMounted(() => store.connectSse())
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">MiniHarness</div>
      <div class="brand-sub">Self-Evolving Agent Runtime v0.1<br />（合成金融数据演示）</div>
      <nav>
        <router-link to="/chat">Chat 对话</router-link>
        <router-link to="/tasks">Task Center</router-link>
        <router-link to="/runs">Run Inspector</router-link>
        <router-link to="/canvas">Workflow Canvas</router-link>
        <router-link to="/evolution">Evolution Review</router-link>
      </nav>
      <div class="panel" style="margin-top: 20px; font-size: 11px; padding: 10px">
        <div>事件流状态：</div>
        <div :class="store.connected ? 'ok-text' : 'error-text'">
          {{ store.connected ? `已连接（${store.events.length} 条事件）` : '未连接' }}
        </div>
      </div>
    </aside>
    <main class="main">
      <router-view />
    </main>
  </div>
</template>
