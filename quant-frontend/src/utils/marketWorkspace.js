import { DEFAULT_VISIBLE_CANDLES, WORKSPACE_CANDLE_LIMIT } from './marketWorkspaceLimits.js'
import { workspaceChartStyle } from './workspaceChartStyle.js'
import { ema20, ema20Series } from './chartEma.js'

// 仅扩展时间轴，绝不生成空白蜡烛或请求未来行情。
export const FUTURE_AXIS_PERIODS = 1000
export const VISIBLE_FUTURE_PERIODS = 20
const priceAxisLabel = new Intl.NumberFormat('en-US', { maximumFractionDigits: 8 })

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
  const effectiveUntil = draft.effectiveUntil ? new Date(draft.effectiveUntil).getTime() : null
  if (effectiveUntil !== null && (!Number.isSafeInteger(effectiveUntil) || effectiveUntil <= effectiveFrom ||
      effectiveUntil > 253402300799999)) throw new Error('失效时间必须晚于生效时间')
  const endLowerPrice = draft.endLowerPrice == null ? null : String(draft.endLowerPrice).trim()
  if (endLowerPrice !== null) {
    if (effectiveUntil === null) throw new Error('平行通道须设置失效时间')
    const endLower = units(endLowerPrice)
    if (endLower <= 0n || endLower + upper - lower >= 1000000000000000000000000n) throw new Error('通道终点价格超出有效范围')
  }
  if ((draft.note || '').length > 500) throw new Error('备注最多 500 字')
  return { ...scope, lowerPrice: String(draft.lowerPrice).trim(), upperPrice: String(draft.upperPrice).trim(),
    effectiveFrom, effectiveUntil, endLowerPrice, note: draft.note || '', ...(draft.id ? { expectedVersion: draft.revision } : {}) }
}
export function chartOption(snapshot, draft, viewport, theme = 'dark', showEma = false) {
  const { axis, tooltip, slider } = workspaceChartStyle(theme)
  const bars = new Map()
  for (const bar of [...(snapshot?.candles || []), ...(snapshot?.currentCandle ? [snapshot.currentCandle] : [])]) {
    const values = [bar.time, bar.open, bar.close, bar.low, bar.high].map(Number)
    if (values.every(Number.isFinite) && values[0] > 0 && values[3] > 0 && values[4] >= values[3]) bars.set(values[0], values)
  }
  const candles = [...bars.values()].sort((a, b) => a[0] - b[0]).slice(-WORKSPACE_CANDLE_LIMIT)
  const period = periodMillis(snapshot?.interval) || 3_600_000
  const first = candles[0]?.[0], end = candles.length ? candles.at(-1)[0] + period : undefined
  const axisEnd = end == null ? undefined : end + FUTURE_AXIS_PERIODS * period
  const recent = candles.slice(-20)
  const low = recent.length ? Math.min(...recent.map(c => c[3])) : 0
  const high = recent.length ? Math.max(...recent.map(c => c[4])) : 1
  const pricePadding = Math.max(high - low, high * 0.02, 0.01) * 0.1
  let zoom = { start: 0, end: 100 }
  if (candles.length) {
    let endValue = end + VISIBLE_FUTURE_PERIODS * period
    let startValue = Math.max(first, endValue - DEFAULT_VISIBLE_CANDLES * period)
    if (Number.isFinite(viewport?.startValue) && Number.isFinite(viewport?.endValue) && viewport.endValue > viewport.startValue) {
      const width = Math.min(axisEnd - first, viewport.endValue - viewport.startValue)
      // Follow new candles only at the right edge; a historical view stays at its market times.
      const oldEnd = viewport.marketEnd
      const following = Number.isFinite(oldEnd) && (Math.abs(viewport.endValue - oldEnd) < period * 0.01
        || Math.abs(viewport.endValue - oldEnd - VISIBLE_FUTURE_PERIODS * period) < period * 0.01)
      const padding = following ? viewport.endValue - oldEnd : 0
      endValue = following ? end + padding : Math.max(first + width, Math.min(axisEnd, viewport.endValue))
      startValue = Math.max(first, endValue - width)
    }
    // ECharts derives the mode from the supplied bounds. A fixed value mode
    // would override percent-based wheel input during a later option merge.
    zoom = { startValue, endValue }
  }
  const areas = [], channels = []
  function area(zone, preview = false) {
    const lower = Number(zone.lowerPrice), upper = Number(zone.upperPrice)
    const from = Math.max(first, zone.effectiveFrom), until = Math.min(axisEnd, zone.effectiveUntil ?? axisEnd)
    if (!Number.isFinite(lower) || !Number.isFinite(upper) || lower <= 0 || upper <= lower || !first || from >= until) return
    const role = zone.observation?.current && zone.lifecycle === 'ACTIVE' &&
      (!zone.effectiveUntil || snapshot.serverTime < zone.effectiveUntil) ? zone.observation.role : null
    const color = preview ? '#38bdf8' : role === 'SUPPORT' ? '#22c55e' : role === 'RESISTANCE' ? '#fb923c' : '#94a3b8'
    if (zone.endLowerPrice != null) {
      const slope = (Number(zone.endLowerPrice) - lower) / (zone.effectiveUntil - zone.effectiveFrom)
      const startLower = lower + slope * (from - zone.effectiveFrom)
      const endLower = lower + slope * (until - zone.effectiveFrom)
      if (![startLower, endLower].every(Number.isFinite)) return
      channels.push({
        value: [from, startLower, startLower + upper - lower, until, endLower, endLower + upper - lower],
        itemStyle: { color: `${color}18`, borderColor: color, borderWidth: preview ? 2 : 1,
          borderType: preview ? 'dashed' : 'solid' }
      })
      return
    }
    areas.push([{ name: preview ? '未保存预览' : `#${zone.id} ${ROLE_LABELS[role] || '待观察'}`, xAxis: from, yAxis: lower,
      itemStyle: { color: `${color}18`, borderColor: color, borderWidth: preview ? 2 : 1, borderType: preview || zone.lifecycle === 'DISABLED' ? 'dashed' : 'solid' },
      label: { color, position: 'insideTopLeft', fontSize: 10 } }, { xAxis: until, yAxis: upper }])
  }
  for (const zone of snapshot?.zones || []) area(zone)
  if (draft) area(draft, true)
  const closed = showEma ? candles.filter(([time]) => time + period <= snapshot?.serverTime) : []
  const average = ema20(closed.map(bar => bar[2]))
  return {
    animation: false,
    backgroundColor: 'transparent',
    tooltip: { ...tooltip, trigger: 'axis', renderMode: 'richText' },
    grid: { left: 12, right: 72, top: 22, bottom: 65, containLabel: true },
    xAxis: {
      ...axis,
      type: 'time',
      min: first,
      max: axisEnd,
      axisLabel: { ...axis.axisLabel, hideOverlap: true },
      splitLine: { show: false }
    },
    // 完全拖入未来时没有可见蜡烛，仍保留最近行情价格范围供绘图。
    yAxis: { ...axis, scale: true, position: 'right',
      axisLabel: { ...axis.axisLabel, formatter: value => priceAxisLabel.format(value) },
      min: range => Number.isFinite(range.min) ? null : Math.max(0, low - pricePadding),
      max: range => Number.isFinite(range.max) ? null : high + pricePadding },
    // 通道横跨多个时间坐标，局部进入视图时保留整条数据，再由绘图区裁剪。
    // 未提交的滚轮范围会在行情重绘时丢失，先同步应用手势再处理推送。
    dataZoom: [{ type: 'inside', moveOnMouseMove: false, throttle: 0, filterMode: 'weakFilter', minValueSpan: 2 * period, ...zoom },
      { ...slider, type: 'slider', throttle: 0, filterMode: 'weakFilter', minValueSpan: 2 * period,
        ...zoom, bottom: 10, height: 20 }],
    series: [{ id: 'workspace-candles', name: 'K 线', type: 'candlestick', data: candles, encode: { x: 0, y: [1, 2, 3, 4] },
      itemStyle: { color: '#22c55e', color0: '#ef4444', borderColor: '#22c55e', borderColor0: '#ef4444' },
      markArea: { silent: true, data: areas } },
    { name: '平行通道', type: 'custom', silent: true, data: channels,
      encode: { x: [0, 3], y: [1, 2, 4, 5] },
      renderItem(params, api) {
        const grid = params.coordSys
        return { type: 'polygon',
          shape: { points: [[0, 1], [0, 2], [3, 5], [3, 4]].map(([x, y]) => api.coord([api.value(x), api.value(y)])) },
          style: api.style(), clipPath: { type: 'rect', shape: { x: grid.x, y: grid.y, width: grid.width, height: grid.height } } }
      }
    }, ...(showEma ? [{
      ...ema20Series(closed.map(([time], index) => [time, average[index]])), encode: { x: 0, y: 1 }
    }] : [])]
  }
}

