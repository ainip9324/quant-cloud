import axios from 'axios'

// 问答可由访客使用，认证失败由浮窗处理。客户端超时略长于后端，便于接收超时提示。
const api = axios.create({
  baseURL: '/api',
  timeout: 40000
})

export async function askKnowledge(question, { token, signal } = {}) {
  const response = await api.post('/ai/knowledge/ask', { question }, {
    headers: token ? { 'Q-Token': token } : {},
    signal
  })
  return response.data
}
