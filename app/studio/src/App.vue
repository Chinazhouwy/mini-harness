<!--
  ============================================================================
  根组件：整页布局骨架（左侧菜单 + 右侧内容区）
  ============================================================================
  【Vue 组件是什么？】
  一个 .vue 文件 = 一个页面片段，分三段：
    <script setup>  —— 数据与逻辑（TypeScript）
    <template>      —— 页面长相（HTML 模板，{{ }} 里可以放变量）
    <style>         —— 该组件的样式（本项目把通用样式放 style.css，所以这里没有）

  【本组件做什么？】
  不加载业务数据，只做两件事：
    1. 页面加载时（onMounted）建立一条 SSE 长连接，接收后端推送的实时事件，
       存进全局 store——之后所有页面（Chat/RunInspector 等）都能读到这些事件；
    2. 渲染左侧导航（router-link 点击后切换右侧 <router-view> 显示的页面）。

  【什么是 SSE？】
  Server-Sent Events：浏览器与后端保持一条单向长连接，后端一有新事件（比如
  “某个节点执行成功了”）就推过来。比起“前端每隔 1 秒问一次后端”，SSE 是
  后端主动推，实时且省请求。实现见 src/api/client.ts 的 openEventStream。
-->
<script setup lang="ts">
import { onMounted } from 'vue'
// useHarnessStore() 拿到全局状态对象（定义在 stores/harness.ts）。
// Pinia 的用法：组件里调用一次，之后读 store.events、调 store.connectSse() 即可。
import { useHarnessStore } from './stores/harness'

// 全局建立 SSE 连接：只要打开本站，事件流就开始接收（所有页面共享同一份）。
const store = useHarnessStore()
// onMounted = “组件第一次渲染到页面上之后”执行的钩子，适合放初始化逻辑。
onMounted(() => store.connectSse())
</script>

<template>
  <!-- 整页布局：flex 左右两栏（样式见 style.css 的 .layout/.sidebar/.main） -->
  <div class="layout">
    <!-- 左侧固定导航栏 -->
    <aside class="sidebar">
      <div class="brand">MiniHarness</div>
      <div class="brand-sub">Self-Evolving Agent Runtime v0.1<br />（合成金融数据演示）</div>
      <nav>
        <!-- router-link：点击后浏览器地址栏变成对应路径，
             右侧 <router-view> 会渲染 router/index.ts 里配置的页面组件 -->
        <router-link to="/chat">Chat 对话</router-link>
        <router-link to="/tasks">Task Center</router-link>
        <router-link to="/runs">Run Inspector</router-link>
        <router-link to="/canvas">Workflow Canvas</router-link>
        <router-link to="/evolution">Evolution Review</router-link>
      </nav>
      <!-- SSE 连接状态小卡片：绿字=已连接（并显示已收到的事件条数），红字=未连接。
           store.connected / store.events 是响应式的：后端每推一条事件，
           这里的数字会自动 +1，不需要手动刷新页面——这就是 Vue 的“响应式”。 -->
      <div class="panel" style="margin-top: 20px; font-size: 11px; padding: 10px">
        <div>事件流状态：</div>
        <div :class="store.connected ? 'ok-text' : 'error-text'">
          {{ store.connected ? `已连接（${store.events.length} 条事件）` : '未连接' }}
        </div>
      </div>
    </aside>
    <!-- 右侧内容区：当前路由对应的页面渲染在这里 -->
    <main class="main">
      <router-view />
    </main>
  </div>
</template>
