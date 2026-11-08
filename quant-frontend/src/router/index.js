import { createRouter, createWebHistory } from 'vue-router'
import { workspaceHomeForRole } from '../auth/roleUi.js'

const routes = [
  { path: '/', name: 'landing', component: () => import('../views/PublicLanding.vue'), meta: { public: true } },
  { path: '/login', name: 'login', component: () => import('../views/AuthView.vue'), meta: { public: true } },
  { path: '/register', name: 'register', component: () => import('../views/AuthView.vue'), meta: { public: true } },
  { path: '/workspace', name: 'home', component: () => import('../views/Home.vue'), meta: { workspace: true, role: 'ADMIN' } },
  { path: '/market', name: 'market', component: () => import('../views/MarketWorkspaceView.vue'), meta: { workspace: true } },
  { path: '/account', name: 'account', component: () => import('../views/AccountView.vue'), meta: { workspace: true } },
  { path: '/forbidden', name: 'forbidden', component: () => import('../views/ForbiddenView.vue'), meta: { workspace: true } },
  { path: '/kline', name: 'kline', redirect: () => ({ path: '/replay' }), meta: { workspace: true, role: 'ADMIN' } },
  { path: '/strategy', name: 'strategy', component: () => import('../views/StrategyView.vue'), meta: { workspace: true, role: 'ADMIN' } },
  { path: '/backtest', name: 'backtest', component: () => import('../views/BacktestView.vue'), meta: { workspace: true, role: 'ADMIN' } },
  { path: '/history', name: 'history', component: () => import('../views/HistoryView.vue'), meta: { workspace: true, role: 'ADMIN' } },
  { path: '/ai-reports/:reportId?', name: 'ai-reports', component: () => import('../views/AiReportView.vue'), meta: { workspace: true, role: 'ADMIN' } },
  // Week 7：策略决策回放（/kline 兼容重定向到 /replay）
  { path: '/replay/:id?', name: 'replay', component: () => import('../views/StrategyReplayView.vue'), meta: { workspace: true, role: 'ADMIN' } },
]

const router = createRouter({
  history: createWebHistory(),
  routes
})
router.beforeEach((to) => {
  const loggedIn = Boolean(localStorage.getItem('quant_token'))
  let role = null
  try { role = JSON.parse(localStorage.getItem('quant_user') || 'null')?.role || null } catch { role = null }
  if (to.meta.workspace && !loggedIn) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.meta.role && role !== to.meta.role) return { name: 'forbidden' }
  if ((to.name === 'login' || to.name === 'register') && loggedIn) return { path: workspaceHomeForRole(role) }
})

export default router
