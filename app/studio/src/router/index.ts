/**
 * ============================================================================
 * 路由表：浏览器网址 → 页面组件 的映射
 * ============================================================================
 *
 * 【路由是什么？】
 * 用户在地址栏输入 /tasks（或点左侧菜单的“Task Center”），
 * Vue Router 就把右侧内容区替换成 TasksView 组件渲染出来的内容。
 * 没有整页刷新——这就是“单页应用（SPA）”。
 *
 * 【为什么后端还有个 SPA 回退？】
 * 因为刷新 /tasks 这类“深链接”时，请求先到 Java 后端；后端没有 /tasks 这个
 * 文件，会把请求转发给 index.html（见 task-assistant 的 SpaForwardController），
 * 前端拿到 index.html 后再由本路由表接手渲染对应页面。
 *
 * 【懒加载说明】
 * () => import('../views/XxxView.vue') 写法表示“访问该页面时才下载它的 JS”，
 * 首页加载更快（比如 Workflow Canvas 依赖 Vue Flow，体积较大，没访问就不加载）。
 */
import { createRouter, createWebHistory } from 'vue-router'

export const router = createRouter({
  // history 模式：用真实的网址路径（/tasks），而不是老式的 /#/tasks。
  history: createWebHistory(),
  routes: [
    // 首页重定向到 Chat——对话是本系统最常见的入口。
    { path: '/', redirect: '/chat' },
    { path: '/chat', component: () => import('../views/ChatView.vue') },
    { path: '/tasks', component: () => import('../views/TasksView.vue') },
    { path: '/runs', component: () => import('../views/RunInspectorView.vue') },
    { path: '/canvas', component: () => import('../views/WorkflowCanvasView.vue') },
    { path: '/evolution', component: () => import('../views/EvolutionReviewView.vue') }
  ]
})
