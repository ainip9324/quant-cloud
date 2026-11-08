import { DEFAULT_VISIBLE_CANDLES, WORKSPACE_CANDLE_LIMIT } from './marketWorkspaceLimits.js'
import { workspaceChartStyle } from './workspaceChartStyle.js'

export const STATE_LABELS = { ABOVE: '区域上方', APPROACHING_FROM_ABOVE: '从上方接近', INSIDE: '区域内', APPROACHING_FROM_BELOW: '从下方接近', BELOW: '区域下方' }
export const ROLE_LABELS = { SUPPORT: '支撑背景', RESISTANCE: '阻力背景', INSIDE: '区域内' }
export const STATUS_LABELS = { LIVE: '实时', STOPPED: '已停止', CONNECTING: '连接中', RECONNECTING: '重新连接', RECOVERING: '恢复数据', DEGRADED: '数据延迟' }
export const periodMillis = interval => ({ '15min': 900_000, '1h': 3_600_000, '4h': 14_400_000, '1day': 86_400_000 })[interval]
export function workspaceStatus(snapshot, connection, receivedAt, now = Date.now()) {
  if (connection === 'UNAVAILABLE') return 'STOPPED'
  if (connection === 'DEGRADED') return 'DEGRADED'
  if (!snapshot) return connection === 'UNAVAILABLE' ? 'STOPPED' : 'CONNECTING'
  if (connection !== 'CONNECTED' || now - receivedAt > 10_000) return 'RECONNECTING'
  if (snapshot.status === 'LIVE' && (!snapshot.marketTime || !snapshot.receivedAt || now - snapshot.marketTime > 10_000
    || now - snapshot.receivedAt > 10_000 || snapshot.marketTime > now + 10_000 || snapshot.receivedAt > now + 10_000)) return 'DEGRADED'
  return STATUS_LABELS[snapshot.status] ? snapshot.status : 'DEGRADED'
}
export function localDateTime(time = Date.now()) {
  const date = new Date(time - new Date(time).getTimezoneOffset() * 60_000)
  return date.toISOString().slice(0, 23)
}
export function zoneCommand(draft, scope) {
  const units = value => {
    const text = String(value).trim()
    if (!/^\d{1,16}(?:\.\d{1,8})?$/.test(text)) throw new Error('价格须为正数，最多 16 位整数、8 位小数')
    const [whole, fraction = ''] = text.split('.')
    return BigInt(whole) * 100000000n + BigInt(fraction.padEnd(8, '0'))
  }
  const lower = units(draft.lowerPrice), upper = units(draft.upperPrice)
  if (lower <= 0n || upper <= lower) throw new Error('上沿必须大于下沿，下沿须大于 0')
  const effectiveFrom = new Date(draft.effectiveFrom).getTime()
  if (!Number.isSafeInteger(effectiveFrom) || effectiveFrom <= 0 || effectiveFrom > 253402300799999) throw new Error('请选择有效的生效时间')
  if ((draft.note || '').length > 500) throw new Error('备注最多 500 字')
  return { ...scope, lowerPrice: String(draft.lowerPrice).trim(), upperPrice: String(draft.upperPrice).trim(),
    effectiveFrom, note: draft.note || '', ...(draft.id ? { expectedVersion: draft.revision } : {}) }
}
export function chartOption(snapshot, draft, viewport, theme = 'dark') {
  const { axis, tooltip, slider } = workspaceChartStyle(theme)
  const bars = new Map()
  for (const bar of [...(snapshot?.candles || []), ...(snapshot?.currentCandle ? [snapshot.currentCandle] : [])]) {
    const values = [bar.time, bar.open, bar.close, bar.low, bar.high].map(Number)
    if (values.every(Number.isFinite) && values[0] > 0 && values[3] > 0 && values[4] >= values[3]) bars.set(values[0], values)
  }
  const candles = [...bars.values()].sort((a, b) => a[0] - b[0]).slice(-WORKSPACE_CANDLE_LIMIT)
  const period = periodMillis(snapshot?.interval) || 3_600_000
  const first = candles[0]?.[0], end = candles.length ? candles.at(-1)[0] + period : undefined
  let zoom = { start: 0, end: 100 }
  if (candles.length) {
    let startValue = candles[Math.max(0, candles.length - DEFAULT_VISIBLE_CANDLES)][0], endValue = end
    if (Number.isFinite(viewport?.startValue) && Number.isFinite(viewport?.endValue) && viewport.endValue > viewport.startValue) {
      const width = Math.min(end - first, viewport.endValue - viewport.startValue)
      // Follow new candles only at the right edge; a historical view stays at its market times.
      endValue = viewport.end >= 99.9 ? end : Math.max(first + width, Math.min(end, viewport.endValue))
      startValue = Math.max(first, endValue - width)
    }
    zoom = { startValue, endValue, rangeMode: ['value', 'value'] }
  }
  const areas = []
  function area(zone, preview = false) {
    const lower = Number(zone.lowerPrice), upper = Number(zone.upperPrice)
    if (!Number.isFinite(lower) || !Number.isFinite(upper) || lower <= 0 || upper <= lower || !first || zone.effectiveFrom > end) return
    const role = zone.observation?.current && zone.lifecycle === 'ACTIVE' ? zone.observation.role : null
    const color = preview ? '#38bdf8' : role === 'SUPPORT' ? '#22c55e' : role === 'RESISTANCE' ? '#fb923c' : '#94a3b8'
    areas.push([{ name: preview ? '未保存预览' : `#${zone.id} ${ROLE_LABELS[role] || '待观察'}`, xAxis: Math.max(first, zone.effectiveFrom), yAxis: lower,
      itemStyle: { color: `${color}18`, borderColor: color, borderWidth: preview ? 2 : 1, borderType: preview || zone.lifecycle === 'DISABLED' ? 'dashed' : 'solid' },
      label: { color, position: 'insideTopLeft', fontSize: 10 } }, { xAxis: end, yAxis: upper }])
  }
  for (const zone of snapshot?.zones || []) area(zone)
  if (draft) area(draft, true)
  return {
    animation: false,
    backgroundColor: 'transparent',
    tooltip: { ...tooltip, trigger: 'axis', renderMode: 'richText' },
    grid: { left: 12, right: 72, top: 22, bottom: 65, containLabel: true },
    xAxis: {
      ...axis,
      type: 'time',
      min: first,
      max: end,
      axisLabel: { ...axis.axisLabel, hideOverlap: true },
      splitLine: { show: false }
    },
    yAxis: { ...axis, scale: true, position: 'right' },
    dataZoom: [{ type: 'inside', ...zoom }, { ...slider, type: 'slider', ...zoom, bottom: 10, height: 20 }],
    series: [{ name: 'K 线', type: 'candlestick', data: candles, encode: { x: 0, y: [1, 2, 3, 4] },
      itemStyle: { color: '#22c55e', color0: '#ef4444', borderColor: '#22c55e', borderColor0: '#ef4444' },
      markArea: { silent: true, data: areas } }]
  }
}

