import { createRouter, createWebHistory } from 'vue-router'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/chat' },
    { path: '/chat', component: () => import('../views/ChatView.vue') },
    { path: '/tasks', component: () => import('../views/TasksView.vue') },
    { path: '/runs', component: () => import('../views/RunInspectorView.vue') },
    { path: '/canvas', component: () => import('../views/WorkflowCanvasView.vue') },
    { path: '/evolution', component: () => import('../views/EvolutionReviewView.vue') }
  ]
})
