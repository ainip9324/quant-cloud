import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000
})
api.interceptors.request.use(config => {
  const token = localStorage.getItem('quant_token')
  if (token) config.headers['Q-Token'] = token
  return config
})
api.interceptors.response.use(r => r, error => {
  if (error.response?.status === 401) {
    localStorage.removeItem('quant_token'); localStorage.removeItem('quant_user')
    if (!location.pathname.startsWith('/login')) location.href = '/login'
  }
  if (error.response?.status === 403 && location.pathname !== '/forbidden') location.href = '/forbidden'
  return Promise.reject(error)
})

export function login(data) { return api.post('/core/auth/login', data) }
export function register(data) { return api.post('/core/auth/register', data) }
export function getMe() { return api.get('/core/auth/me') }
export function changePassword(data) { return api.post('/core/auth/password', data) }

/** 策略列表 */
export function getStrategies() {
  return api.get('/core/strategies')
}

/** 提交回测任务（异步执行） */
export function submitBacktest(data) {
  return api.post('/core/backtest', data)
}

/** 查询单个回测任务结果 */
export function getBacktest(id) {
  return api.get(`/core/backtest/${id}`)
}

/**
 * 查询策略决策回放快照（Week 7：只返回任务回测时持久化的 K 线与结构化事件）
 * 错误码约定：404 TASK_NOT_FOUND / 409 REPLAY_NOT_READY / 409 REPLAY_NOT_AVAILABLE
 */
export function getBacktestReplay(id) {
  return api.get(`/core/backtest/${id}/replay`)
}

/**
 * 回测任务列表（分页 + 状态筛选）
 * @param {Object} params - { status?, strategyId?, marketType?, page?, size? }
 * @returns {total, page, size, list}
 */
export function listBacktests(params = {}) {
  return api.get('/core/backtest', { params })
}

/** core-service 健康检查（含 MQ 状态） */
export function checkCoreHealth() {
  return api.get('/core/health')
}

/** 创建当前回测任务的 AI 复盘；服务端会复用同一指纹的成功报告。 */
export function createAiReport(taskId) {
  return api.post('/ai/reports', { taskId })
}

/** 查询单份 AI 复盘详情（含服务端核验过的证据）。 */
export function getAiReport(reportId) {
  return api.get(`/ai/reports/${reportId}`)
}

/** 查询某个回测任务的 AI 复盘尝试列表。 */
export function listAiReports(taskId, params = {}) {
  return api.get('/ai/reports', { params: { taskId, page: 1, size: 20, ...params } })
}

/** 显式创建该报告的下一次复盘尝试。 */
export function regenerateAiReport(reportId) {
  return api.post(`/ai/reports/${reportId}/regenerate`)
}

export default api
