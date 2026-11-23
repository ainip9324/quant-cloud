/**
 * Zone Reversal 区间诊断（前端共享，BacktestView / StrategyReplayView 复用）
 *
 * 基于回放快照（K线 + 结构化事件）对每个区间推断"武装条件 / 最终状态 / 未交易原因"。
 * 不修改任何后端交易规则，只做可解释性展示。
 *
 * 诊断结论依赖真实结构化事件（ZONE_EFFECTIVE / ARMED_ABOVE / ARMED_BELOW /
 * ENTRY_PLANNED / ENTRY_REJECTED_RR / ENTRY_REJECTED / ENTRY_EXECUTED /
 * STOPPED / TAKE_PROFIT / CLOSED / LIQUIDATED / ZONE_DISABLED），
 * 与 ZoneReversalStrategy 的事件语义保持一致。
 *
 * 最终状态采用"逐事件归约"：事件先按 sequence 升序排列（缺 sequence 时按
 * time + 原始索引稳定排序），再按状态机逐个归约，而不是用全历史 includes() 推断。
 */

/** 事件码 → 状态机下一状态（undefined 表示不改变状态） */
const STATE_CODE = {
  ZONE_EFFECTIVE: 'DISARMED',
  ARMED_ABOVE: 'ARMED_ABOVE',
  ARMED_BELOW: 'ARMED_BELOW',
  ENTRY_EXECUTED: 'IN_POSITION',
  STOPPED: 'DISARMED',
  TAKE_PROFIT: 'DISARMED',
  CLOSED: 'DISARMED',
  LIQUIDATED: 'DISARMED',
  ZONE_DISABLED: 'DISABLED'
}

/** 决定"未交易原因"的决策事件（按最后一个判断，旧拒绝不覆盖后来的重新武装） */
const DECIDE_CODES = ['ENTRY_REJECTED_RR', 'ENTRY_REJECTED', 'ARMED_ABOVE', 'ARMED_BELOW', 'ENTRY_PLANNED']

/** 数值安全格式化（NaN/Infinity → '-'） */
function fmt(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(0) : '-'
}

/** sequence 合法性：null/undefined/空字符串/纯空白都不能视为有效 sequence */
function isValidSeq(v) {
  if (v === null || v === undefined || v === '') return false
  if (typeof v === 'string') {
    const text = v.trim()
    if (text === '' || !/^\d+$/.test(text)) return false
    const n = Number(text)
    return Number.isSafeInteger(n) && n > 0
  }
  return typeof v === 'number' && Number.isSafeInteger(v) && v > 0
}

/** time 合法性：null/undefined/空字符串/非数字 → 显式兜底为 null */
function toTime(v) {
  if (v === null || v === undefined || v === '') return null
  if (typeof v === 'string') {
    const text = v.trim()
    if (text === '') return null
    const n = Number(text)
    return Number.isFinite(n) ? n : null
  }
  return typeof v === 'number' && Number.isFinite(v) ? v : null
}

/**
 * 事件排序：
 * - 全部事件都有合法 sequence → 按 sequence 升序（相等时按 time 升序，再按原始索引稳定）
 * - 只要存在缺失/非法 sequence → 整组统一按 time 升序 + 原始索引稳定排序
 * - 非法 time 显式兜底：排在最后（同非法时按原始索引保持稳定）
 * 每轮调用返回新数组，不修改入参。
 */
export function sortEvents(evs) {
  const arr = evs.map((e, i) => ({ e, i }))
  const allValidSeq = arr.every(x => isValidSeq(x.e.sequence))
  arr.sort((a, b) => {
    if (allValidSeq) {
      const sa = Number(a.e.sequence)
      const sb = Number(b.e.sequence)
      if (sa !== sb) return sa - sb
    }
    const ta = toTime(a.e.time)
    const tb = toTime(b.e.time)
    if (ta !== tb) {
      if (ta === null) return 1 // 非法 time 排最后
      if (tb === null) return -1
      return ta - tb
    }
    return a.i - b.i
  })
  return arr.map(x => x.e)
}

/**
 * 逐事件归约状态机。
 * DISABLED 是终态：一旦进入 ZONE_DISABLED，之后任何 ARMED_ABOVE/ARMED_BELOW/
 * ENTRY_EXECUTED 等事件都不能覆盖。
 * @returns 最后一个状态（DISARMED / ARMED_ABOVE / ARMED_BELOW / IN_POSITION / DISABLED）
 */
export function reduceZoneState(zoneEvs) {
  let s = 'DISARMED'
  for (const e of zoneEvs) {
    const ns = STATE_CODE[e.code]
    if (ns) {
      if (s === 'DISABLED') continue
      s = ns
    }
  }
  return s
}

/**
 * 计算单个区间的派生值（中点/武装线），与后端公式一致：
 * mid = (lower+upper)/2；above = upper + width×rearmBufferRatio；below = lower - width×rearmBufferRatio
 */
function zoneDerived(zone, rearmBufferRatio) {
  const lower = Number(zone.lower)
  const upper = Number(zone.upper)
  const rbr = Number.isFinite(Number(rearmBufferRatio)) ? Number(rearmBufferRatio) : 0.2
  const width = upper - lower
  return {
    lower,
    upper,
    mid: (lower + upper) / 2,
    above: upper + width * rbr,
    below: lower - width * rbr
  }
}

/**
 * 对每个区间生成诊断。
 *
 * @param {Array} zones      区间配置 [{id, lower, upper, effectiveFrom}]
 * @param {Array} bars       K线快照 [{time, ...}]
 * @param {Array} events     结构化事件 [{code, sourceId, time, sequence, details, ...}]
 * @param {Object} params    策略参数（rearmBufferRatio 等）
 * @returns {Array<{zoneId, lower, upper, mid, above, below, hasTraded, finalStatus, diagnosis}>}
 */
export function diagnoseZones(zones, bars, events, params) {
  const evs = Array.isArray(events) ? events : []
  const barTimes = (Array.isArray(bars) ? bars : []).map(b => Number(b.time)).filter(t => Number.isFinite(t))
  const lastBarTime = barTimes.length > 0 ? Math.max(...barTimes) : 0
  const rbr = params ? Number(params.rearmBufferRatio) : 0.2
  const rearm = Number.isFinite(rbr) ? rbr : 0.2

  return (Array.isArray(zones) ? zones : []).map(z => {
    const id = z.id
    const d = zoneDerived(z, rearm)
    // 单区间事件：按 sequence 升序（缺 sequence 时 time + 原始索引稳定排序）
    const zoneEvs = sortEvents(evs.filter(e => e.sourceId === id))
    const hasEffective = zoneEvs.some(e => e.code === 'ZONE_EFFECTIVE')
    // hasTraded 表示"历史上是否成交过"，不能代替最终状态
    const hasTraded = zoneEvs.some(e => e.code === 'ENTRY_EXECUTED')
    // 最终状态：逐事件归约，而不是全历史 includes()
    const state = reduceZoneState(zoneEvs)
    const finalStatus = hasEffective ? state : '未生效'

    // 未交易原因（仅 0 笔成交时给出）：
    // 按最后一次 arm/plan/reject 事件判断，旧拒绝不能覆盖后来的重新武装
    let diagnosis = null
    if (!hasTraded) {
      if (!hasEffective) {
        diagnosis = Number(z.effectiveFrom) > lastBarTime
          ? '回测范围内尚未生效（生效时间晚于最后一根 K 线）'
          : '尚未生效'
      } else if (state === 'DISABLED') {
        diagnosis = '区间已禁用（连续止损达到阈值）'
      } else {
        const lastDecide = [...zoneEvs].reverse().find(e => DECIDE_CODES.includes(e.code))
        if (!lastDecide) {
          diagnosis = '生效后价格未明确离开区间（未触发武装）'
        } else if (lastDecide.code === 'ARMED_ABOVE') {
          diagnosis = `已从上方武装，但之后未回落至中点 ${fmt(d.mid)}`
        } else if (lastDecide.code === 'ARMED_BELOW') {
          diagnosis = `已从下方武装，但之后未反弹至中点 ${fmt(d.mid)}`
        } else if (lastDecide.code === 'ENTRY_REJECTED_RR') {
          const rr = lastDecide.details?.rr
          diagnosis = `触发过，但前方区间截断后 RR 不足（${Number.isFinite(Number(rr)) ? Number(rr).toFixed(2) : '-'}）`
        } else if (lastDecide.code === 'ENTRY_REJECTED') {
          diagnosis = `触发过，但引擎因 ${lastDecide.details?.reasonCode || '未知原因'} 拒绝`
        } else {
          diagnosis = '已触发入场计划但未成交'
        }
      }
    }

    return { zoneId: id, ...d, hasTraded, finalStatus, diagnosis }
  })
}
