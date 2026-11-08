import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000
})

// data-service 与 core-service 都经过 gateway；所有工作台请求必须携带同一份登录令牌。
api.interceptors.request.use(config => {
  const token = localStorage.getItem('quant_token')
  if (token) config.headers['Q-Token'] = token
  return config
})

api.interceptors.response.use(response => response, error => {
  if (error.response?.status === 401) {
    localStorage.removeItem('quant_token')
    localStorage.removeItem('quant_user')
    if (!location.pathname.startsWith('/login')) {
      location.href = '/login'
    }
  }
  if (error.response?.status === 403 && location.pathname !== '/forbidden') location.href = '/forbidden'
  return Promise.reject(error)
})

/** 获取K线数据 */
export function getKlines(marketType, symbol, interval, limit = 200) {
  return api.get('/data/klines', { params: { marketType, symbol, interval, limit } })
}

/** 获取支持的交易对 */
export function getSymbols(marketType) {
  return api.get('/data/symbols', { params: { marketType } })
}

/** 同步K线 */
export function syncKlines(marketType, symbol, interval, limit = 1000) {
  return api.post('/data/sync', null, { params: { marketType, symbol, interval, limit } })
}

/** 健康检查 */
export function checkHealth() {
  return api.get('/data/health')
}

export default api
