import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'Dashboard',
    component: () => import('@/views/Dashboard.vue')
  },
  {
    path: '/market',
    name: 'Market',
    component: () => import('@/views/Market.vue')
  },
  {
    path: '/trading',
    name: 'Trading',
    component: () => import('@/views/Trading.vue')
  },
  {
    path: '/agents',
    name: 'Agents',
    component: () => import('@/views/Agents.vue')
  },
  {
    path: '/analytics',
    name: 'Analytics',
    component: () => import('@/views/Analytics.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
