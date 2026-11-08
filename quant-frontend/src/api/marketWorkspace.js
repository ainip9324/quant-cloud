import api from './core.js'
import { WORKSPACE_CANDLE_LIMIT } from '../utils/marketWorkspaceLimits.js'

const ROOT = '/core/market-workspace'
const BRIEFING = '/core/market-briefing'
export async function getWorkspace(scope, signal) { return (await api.get(ROOT, { params: scope, signal })).data }
export async function getMarketBriefing(signal) {
  return (await api.get(BRIEFING, { signal })).data
}
export async function publishMarketBriefing(body, signal) {
  return (await api.put(BRIEFING, body, { signal })).data
}
export function createZone(body) { return api.post('/core/market-zones', body) }
export function updateZone(id, body) { return api.put(`/core/market-zones/${id}`, body) }
export function changeZoneLifecycle(zone, action) {
  if (!['enable', 'disable', 'archive'].includes(action)) throw new Error('Invalid zone action')
  return api.post(`/core/market-zones/${zone.id}/${action}`, { expectedVersion: zone.revision })
}

/** Streaming UTF-8/SSE parser; no DOM EventSource and no token in the URL. */
export async function readWorkspaceEvents(body, signal, receive) {
  if (!body) throw new Error('Missing stream body')
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let line = '', name = 'message', data = [], size = 0, previousCR = false
  function consumeLine() {
    if (!line) {
      if (data.length) receive({ name, data: data.join('\n') })
      name = 'message'; data = []; size = 0
    } else if (!line.startsWith(':')) {
      const colon = line.indexOf(':')
      const field = colon < 0 ? line : line.slice(0, colon)
      let value = colon < 0 ? '' : line.slice(colon + 1)
      if (value.startsWith(' ')) value = value.slice(1)
      if (field === 'event') name = value
      if (field === 'data') data.push(value)
    }
    line = ''
  }
  const cancel = () => { void reader.cancel().catch(() => {}) }
  signal.addEventListener('abort', cancel, { once: true })
  try {
    while (!signal.aborted) {
      const { value, done } = await reader.read()
      if (done) return
      for (const char of decoder.decode(value, { stream: true })) {
        if (previousCR && char === '\n') { previousCR = false; continue }
        previousCR = char === '\r'
        if (++size > 1_000_000) throw new Error('Oversized stream frame')
        if (char === '\n' || char === '\r') consumeLine()
        else line += char
      }
    }
  } finally {
    signal.removeEventListener('abort', cancel)
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

/** Owns re-snapshot, reconnect, silence timeout and cancellation for exactly one scope. */
export function connectWorkspace(scope, { onSnapshot, onStatus }, { load = getWorkspace, fetcher = globalThis.fetch } = {}) {
  let stopped = false, controller, retryTimer, silenceTimer, attempt = 0, streamId, version = 0
  const status = (state, code = '') => { if (!stopped) onStatus({ state, code }) }
  const armTimeout = () => {
    clearTimeout(silenceTimer)
    silenceTimer = setTimeout(() => controller?.abort(), 10_000)
  }
  function apply(snapshot) {
    if (stopped || snapshot?.marketType !== scope.marketType || snapshot?.symbol !== scope.symbol || snapshot?.interval !== scope.interval
      || typeof snapshot.streamId !== 'string' || !Number.isSafeInteger(snapshot.version) || snapshot.version < 1
      || !Number.isFinite(snapshot.serverTime) || !Array.isArray(snapshot.candles) || snapshot.candles.length > WORKSPACE_CANDLE_LIMIT
      || !Array.isArray(snapshot.zones) || snapshot.zones.length > 100 || !Array.isArray(snapshot.events) || snapshot.events.length > 20) {
      throw new Error('Invalid workspace snapshot')
    }
    if (streamId === snapshot.streamId && snapshot.version <= version) return false
    streamId = snapshot.streamId; version = snapshot.version
    onSnapshot(snapshot); armTimeout(); return true
  }
  function authFailure(code) {
    if (code === 401) {
      localStorage.removeItem('quant_token'); localStorage.removeItem('quant_user')
      location.href = '/login'
    } else location.href = '/forbidden'
  }
  async function run() {
    let failed = false
    controller = new AbortController(); armTimeout(); status('CONNECTING')
    try {
      const initial = await load(scope, controller.signal)
      if (stopped || controller.signal.aborted) return
      apply(initial)
      const token = localStorage.getItem('quant_token')
      if (!token) throw Object.assign(new Error('Authentication required'), { status: 401 })
      const url = `/api${ROOT}/stream?${new URLSearchParams(scope)}`
      const response = await fetcher(url, { headers: { 'Q-Token': token, Accept: 'text/event-stream' },
        signal: controller.signal, cache: 'no-store', credentials: 'same-origin' })
      if (!response.ok) throw Object.assign(new Error('Workspace stream unavailable'), { status: response.status })
      if (!response.headers.get('content-type')?.includes('text/event-stream')) throw new Error('Invalid stream response')
      await readWorkspaceEvents(response.body, controller.signal, event => {
        if (stopped) return
        if (event.name === 'workspace') {
          if (apply(JSON.parse(event.data))) { attempt = 0; status('CONNECTED') }
        } else if (event.name === 'unavailable') { status('DEGRADED'); armTimeout() }
      })
    } catch (error) {
      failed = true
      if (stopped) return
      const code = error.status || error.response?.status
      if (code === 401 || code === 403) { status('DENIED', code); stopped = true; authFailure(code); return }
      status(code === 503 ? 'UNAVAILABLE' : 'RECONNECTING', code || '')
      if (code === 400) { stopped = true; return }
    } finally {
      controller.abort(); clearTimeout(silenceTimer)
      if (!stopped) {
        // EOF, lease expiry and silence all re-fetch a full snapshot before subscribing.
        if (!failed) status('RECONNECTING')
        retryTimer = setTimeout(run, Math.min(15_000, 1000 * 2 ** Math.min(attempt++, 4)))
      }
    }
  }
  void run()
  return { close() { stopped = true; clearTimeout(retryTimer); clearTimeout(silenceTimer); controller?.abort() } }
}
