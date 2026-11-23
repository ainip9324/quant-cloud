<template>
  <div>
    <!-- 顶部任务栏 -->
    <el-card>
      <template #header>
        <div class="rp-head">
          <span class="rp-head__title">策略决策回放</span>
          <div class="rp-head__tools">
            <el-select v-model="taskId" placeholder="选择历史成功任务" style="width: 220px;" @change="onTaskChange" :loading="taskLoading">
              <el-option v-for="t in tasks" :key="t.id" :label="`#${t.id} ${t.strategyId} ${marketLabel(t.marketType)} ${t.symbol} ${t.interval}`" :value="t.id" />
            </el-select>
            <el-button @click="loadTaskOptions" :loading="taskLoading">刷新</el-button>
          </div>
        </div>
      </template>
      <div v-if="meta" class="rp-meta">
        <el-tag type="info" effect="plain">任务 #{{ meta.taskId }}</el-tag>
        <el-tag type="primary" effect="plain">{{ meta.strategyId }}</el-tag>
        <el-tag type="success" effect="plain">{{ marketLabel(meta.marketType) }}</el-tag>
        <el-tag type="success" effect="plain">{{ meta.symbol }}</el-tag>
        <el-tag effect="plain">{{ meta.interval }}</el-tag>
        <span class="rp-meta__range">{{ formatTime(meta.startTs) }} ~ {{ formatTime(meta.endTs) }}</span>
        <el-tag type="warning" effect="plain" size="small">回放快照（非实时行情）</el-tag>
        <el-tag v-if="meta.params?.execution?.strategyVersion === 'ema20-signal-v2'" effect="plain" size="small">双向回踩 · 收盘达 1R 后允许信号平仓</el-tag>
        <el-tag v-if="meta.params?.chartObjects?.version === 3" type="warning" effect="plain" size="small">按配置生效时间（可事后设定）</el-tag>
        <el-tag v-if="meta.params?.chartObjects?.revisions?.some(r => r.channel && r.endLower != null)"
          type="warning" effect="plain" size="small">通道触及：收盘边界与 OHLC 近似</el-tag>
        <!-- 图层开关 -->
        <div class="rp-layers">
          <el-checkbox v-model="layers.zones">区间</el-checkbox>
          <el-checkbox v-model="layers.trades">成交</el-checkbox>
          <el-checkbox v-model="layers.rejected">拒绝信号</el-checkbox>
          <el-checkbox v-model="layers.lines">止损止盈线</el-checkbox>
          <el-checkbox v-model="layers.states">状态事件</el-checkbox>
        </div>
      </div>
      <!-- 未完成/失败/旧任务提示 -->
      <el-alert v-if="notReadyMsg" :title="notReadyMsg" type="warning" :closable="false" show-icon />
    </el-card>

    <!-- 主回放图 -->
    <el-card style="margin-top: 20px;">
      <ChartIndicators v-if="meta" v-model="showEma" />
      <div ref="chartRef" style="width: 100%; height: 480px;"></div>
      <el-empty v-if="!meta && !loading" description="请在上方选择要回放的历史任务" :image-size="80" style="margin-top: -80px;" />
    </el-card>

    <!-- 区间状态汇总（武装条件 / 最终状态 / 未交易原因） -->
    <el-card v-if="zoneDiags.length > 0" style="margin-top: 20px;">
      <template #header>
        <div style="display: flex; align-items: center; gap: 12px; flex-wrap: wrap;">
          <span>区间状态汇总（{{ zoneDiags.length }} 个）</span>
          <span style="font-size: 12px; color: var(--qc-text-dim);">未交易原因基于 K 线快照 + 结构化事件自动推断</span>
        </div>
      </template>
      <el-table :data="zoneDiags" border stripe size="small">
        <el-table-column prop="zoneId" label="区间" width="110" />
        <el-table-column label="区间价格" width="140" align="right">
          <template #default="{ row }">{{ fmtNum(row.lower) }} ~ {{ fmtNum(row.upper) }}</template>
        </el-table-column>
        <el-table-column label="中点触发" width="100" align="right">
          <template #default="{ row }">{{ fmtNum(row.mid) }}</template>
        </el-table-column>
        <el-table-column label="武装阈值" width="180" align="center">
          <template #default="{ row }">上&gt;{{ fmtNum(row.above) }} / 下&lt;{{ fmtNum(row.below) }}</template>
        </el-table-column>
        <el-table-column label="成交" width="70" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.hasTraded" type="success" size="small">是</el-tag>
            <el-tag v-else type="info" size="small">否</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最终状态" width="140" align="center">
          <template #default="{ row }">
            <span :style="{ color: row.finalStatus === 'DISABLED' ? '#ef232a' : '', fontWeight: row.finalStatus === 'DISABLED' ? 600 : '' }">{{ row.finalStatus }}</span>
          </template>
        </el-table-column>
        <el-table-column label="未交易原因" min-width="320">
          <template #default="{ row }">{{ row.diagnosis || '—' }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 决策时间线 -->
    <el-card v-if="meta" style="margin-top: 20px;">
      <template #header>
        <div style="display: flex; align-items: center; gap: 12px; flex-wrap: wrap;">
          <span>决策时间线（{{ events.length }} 条）</span>
          <span style="font-size: 12px; color: var(--qc-text-dim);">点击事件定位到对应 K 线</span>
        </div>
      </template>
      <el-table :data="events" border stripe size="small" max-height="420" highlight-current-row @row-click="locateEvent">
        <el-table-column prop="sequence" label="#" width="60" align="center" />
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatTime(row.time) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="130" align="center">
          <template #default="{ row }">
            <el-tag :type="categoryColor(row.category)" size="small">{{ row.category }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="事件" width="220">
          <template #default="{ row }">
            <span :style="{ color: codeColor(row), fontWeight: 600 }" :title="row.code">{{ eventLabel(row) }}</span>
            <span v-if="row.sourceId" style="margin-left: 6px; font-size: 12px; color: var(--qc-text-dim);">{{ row.sourceId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="价格" width="110" align="right">
          <template #default="{ row }">
            <span v-if="row.price">{{ fmtNum(row.price) }}</span>
            <span v-else style="color: var(--qc-text-dim);">-</span>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="320">
          <template #default="{ row }">{{ eventSummary(row) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { listBacktests, getBacktestReplay } from '../api/core'
import { diagnoseZones, sortEvents } from '../utils/zoneDiagnosis'
import { historicalZoneSegments } from '../utils/chartObjectReplay.js'
import { ElMessage } from 'element-plus'
import { marketLabel } from '../utils/marketOptions'
import { colorTheme } from '../utils/colorTheme.js'
import { applyWorkspaceChartStyle, workspaceChartStyle } from '../utils/workspaceChartStyle.js'
import { ema20, ema20Series } from '../utils/chartEma.js'
import { workspaceSelections } from '../utils/workspaceSelections.js'
import ChartIndicators from '../components/ChartIndicators.vue'

const REPLAY_ZOOM_THROTTLE_MS = 20

const route = useRoute()
const router = useRouter()
const previous = workspaceSelections.replay

const taskId = ref(null)
const tasks = ref([])
const taskLoading = ref(false)
const loading = ref(false)
const meta = ref(null)        // {taskId,strategyId,symbol,interval,startTs,endTs,params,result}
const replay = ref(null)      // {schemaVersion,bars,events}
const notReadyMsg = ref('')
const showEma = ref(previous?.showEma ?? false)

const layers = reactive({ zones: true, trades: true, rejected: true, lines: true, states: true, ...previous?.layers })

const chartRef = ref(null)
let chart = null
let onResize = null

const events = computed(() => replay.value?.events || [])

// Build lookups once per snapshot, rather than scanning every candle for each hover event.
const replayIndex = computed(() => {
  const times = (replay.value?.bars || []).map(bar => bar.time)
  const indexByTime = new Map()
  times.forEach((time, index) => {
    const key = Number(time)
    // Preserve findIndex's first-match behavior and its non-matching NaN semantics.
    if (!Number.isNaN(key) && !indexByTime.has(key)) indexByTime.set(key, index)
  })
  const eventsByIndex = new Map()
  for (const event of events.value) {
    const index = indexByTime.get(Number(event.time))
    if (index === undefined) continue
    if (!eventsByIndex.has(index)) eventsByIndex.set(index, [])
    eventsByIndex.get(index).push(event)
  }
  return { times, indexByTime, eventsByIndex }
})

// 区间状态汇总（武装条件 / 最终状态 / 未交易原因，基于快照 + 事件推断）
const zoneDiags = computed(() => {
  if (!meta.value || !replay.value) return []
  const zones = meta.value.params?.zones
  if (!Array.isArray(zones) || zones.length === 0) return []
  return diagnoseZones(zones, replay.value.bars || [], replay.value.events || [], meta.value.params)
})

// ==================== 任务选择 ====================

async function loadTaskOptions() {
  taskLoading.value = true
  try {
    // 优先 Zone Reversal，再补全其余成功任务（EMA/Turtle 基准回放）
    const [zRes, allRes] = await Promise.allSettled([
      listBacktests({ status: 'SUCCESS', strategyId: 'zone-reversal', page: 1, size: 50 }),
      listBacktests({ status: 'SUCCESS', page: 1, size: 50 })
    ])
    const zList = zRes.status === 'fulfilled' ? (zRes.value.data?.list || []) : []
    const aList = allRes.status === 'fulfilled' ? (allRes.value.data?.list || []) : []
    const seen = new Set()
    tasks.value = [...zList, ...aList].filter(t => {
      if (seen.has(t.id)) return false
      seen.add(t.id)
      return true
    })
    if (route.params.id) {
      taskId.value = Number(route.params.id)
      await loadReplay()
    } else if (tasks.value.length > 0) {
      taskId.value = tasks.value.find(task => task.id === previous?.taskId)?.id ?? tasks.value[0].id
      await loadReplay()
    }
  } catch (e) {
    ElMessage.error('加载任务列表失败: ' + (e.response?.data?.message || e.message))
  } finally {
    taskLoading.value = false
  }
}

function onTaskChange(id) {
  if (id) router.replace({ path: `/replay/${id}` })
  // 路由变化由 watch(route.params.id) 触发 loadReplay
}

// ==================== 加载回放快照 ====================

async function loadReplay() {
  if (!taskId.value) return
  loading.value = true
  notReadyMsg.value = ''
  try {
    const res = await getBacktestReplay(taskId.value)
    const data = res.data
    // startTs/endTs 缺失时回退到回放快照 bars 首尾 time（旧任务无时间范围字段）
    const rpBars = data.replay?.bars || []
    const fallbackStart = rpBars.length > 0 ? rpBars[0].time : null
    const fallbackEnd = rpBars.length > 0 ? rpBars[rpBars.length - 1].time : null
    meta.value = {
      taskId: data.taskId,
      strategyId: data.strategyId,
      marketType: data.marketType,
      symbol: data.symbol,
      interval: data.interval,
      startTs: data.startTs ?? fallbackStart,
      endTs: data.endTs ?? fallbackEnd,
      params: data.params || {},
      result: data.result || {}
    }
    replay.value = data.replay
    await nextTick()
    renderChart()
  } catch (e) {
    replay.value = null
    meta.value = null
    const status = e.response?.status
    const code = e.response?.data?.code
    if (status === 404 || code === 'TASK_NOT_FOUND') {
      notReadyMsg.value = '任务不存在或无权访问'
    } else if (code === 'REPLAY_NOT_READY') {
      notReadyMsg.value = '回测任务尚未完成，无法回放，请稍后重试'
    } else if (code === 'REPLAY_NOT_AVAILABLE') {
      // 旧任务无回放快照：明确提示重新运行，绝不请求实时 K 线
      notReadyMsg.value = '该任务没有回放数据（旧版本结果），请重新运行回测后再回放'
    } else {
      notReadyMsg.value = '回放加载失败: ' + (e.response?.data?.message || e.message)
    }
    disposeChart()
  } finally {
    loading.value = false
  }
}

// ==================== 图表 ====================

function disposeChart() {
  if (import.meta.env.DEV) window.__qcReplayChart = null
  chart?.dispose()
  chart = null
}

/** 取 bar 时间列表 */
function barTimes() {
  return replayIndex.value.times
}

/** bar.time -> 索引（category 轴用时间串定位） */
function timeToIndex(time) {
  return replayIndex.value.indexByTime.get(Number(time)) ?? -1
}

/** 事件码 → 颜色（前端依赖 code，不解析中文） */
function codeColor(row) {
  const c = row.code
  if (c === 'ENTRY_EXECUTED' || c === 'ARMED_ABOVE') return '#14b143'
  if (c === 'ARMED_BELOW') return '#f59e0b'
  if (c === 'STOPPED' || c === 'LIQUIDATED' || c === 'ZONE_DISABLED') return '#ef232a'
  if (c === 'TAKE_PROFIT') return '#38bdf8'
  if (c === 'ENTRY_REJECTED' || c === 'ENTRY_REJECTED_RR') return '#8b5cf6'
  if (c === 'TAKE_PROFIT_TRUNCATED') return '#c084fc'
  return '#94a3b8'
}

function categoryColor(cat) {
  if (cat === 'ZONE_STATE') return 'warning'
  if (cat === 'DECISION') return 'primary'
  if (cat === 'EXECUTION') return 'success'
  return 'info'
}

/** 事件码 → 区间带状态（与 zoneDiagnosis 状态机一致；无关事件返回 null 跳过） */
function zoneStateOf(code) {
  switch (code) {
    case 'ZONE_EFFECTIVE': return 'DISARMED'
    case 'ARMED_ABOVE': return 'ARMED_ABOVE'
    case 'ARMED_BELOW': return 'ARMED_BELOW'
    case 'ENTRY_EXECUTED': return 'IN_POSITION'
    case 'STOPPED':
    case 'TAKE_PROFIT':
    case 'CLOSED':
    case 'LIQUIDATED': return 'DISARMED'
    case 'ZONE_DISABLED': return 'DISABLED'
    default: return null
  }
}

/**
 * 构建区间状态分段（从 effectiveFrom 开始，绝不向左扩展）：
 * 初始未武装灰蓝；ARMED_ABOVE 偏绿、ARMED_BELOW 偏橙、IN_POSITION 淡蓝、DISABLED 红灰。
 *
 * 按 sequence 顺序逐事件归约：
 * - idx === segStart 时更新当前状态但不切段（同根事件影响从本根起）
 * - 仅 idx < segStart 跳过；单根 K 线状态段也保留
 * - ENTRY_EXECUTED → IN_POSITION，退出事件 → DISARMED，成交后区间带不再保持武装色
 * 返回 [{zoneId, lower, upper, from, to, state}]，from/to 为 bar 索引（闭区间），
 * lower/upper 为该区间的价格边界（供 markArea 纵轴使用）。
 */
function buildZoneSegments(zones, evs, times) {
  const segs = []
  const sortedEvs = sortEvents(evs || [])
  for (const z of zones || []) {
    const ef = Number(z.effectiveFrom)
    if (!Number.isFinite(ef)) continue
    const startIdx = times.findIndex(t => Number(t) >= ef)
    if (startIdx < 0) continue
    const n = times.length
    let state = 'DISARMED'
    let segStart = startIdx
    const commit = (endIdx) => {
      if (endIdx >= segStart) segs.push({
        zoneId: z.id,
        lower: Number(z.lower),
        upper: Number(z.upper),
        from: segStart,
        to: endIdx,
        state
      })
    }
    for (const ev of sortedEvs) {
      if (ev.sourceId !== z.id) continue
      const next = zoneStateOf(ev.code)
      if (!next) continue
      // DISABLED 是终态：一旦禁用，后续任何 ARMED_ABOVE/ARMED_BELOW/ENTRY_EXECUTED 都不能覆盖
      if (state === 'DISABLED') continue
      const idx = timeToIndex(ev.time)
      if (idx < 0 || idx < startIdx) continue
      // 仅 idx < segStart 跳过：事件索引早于当前段起点时不允许回退切段
      if (idx < segStart) continue
      if (idx === segStart) {
        // 同根事件：更新当前状态但不切段
        state = next
      } else {
        commit(idx - 1)
        segStart = idx
        state = next
      }
    }
    commit(n - 1)
  }
  return segs
}

function zoneColor(state) {
  switch (state) {
    case 'ARMED_ABOVE': return 'rgba(20,177,67,0.14)'
    case 'ARMED_BELOW': return 'rgba(245,158,11,0.14)'
    case 'IN_POSITION': return 'rgba(56,189,248,0.16)'
    case 'DISABLED': return 'rgba(239,35,42,0.12)'
    default: return 'rgba(148,163,184,0.10)' // DISARMED 灰蓝
  }
}

/** 信号点：按事件类型映射符号与颜色 */
function signalPoints(evs) {
  const trades = []
  const rejected = []
  const states = []
  for (const ev of evs) {
    const idx = timeToIndex(ev.time)
    if (idx < 0) continue
    const y = Number(ev.price)
    const x = idx
    if (ev.code === 'ENTRY_EXECUTED') {
      const side = ev.positionSide
      const label = emaFillLabel(ev) || (side === 'SHORT' ? '开空' : '开多')
      trades.push({ x, y, symbol: side === 'SHORT' ? 'arrow' : 'pin', size: 16, color: side === 'SHORT' ? '#f59e0b' : '#14b143', label })
    } else if (ev.code === 'TAKE_PROFIT') {
      trades.push({ x, y, symbol: 'diamond', size: 13, color: '#38bdf8', label: '止盈' })
    } else if (ev.code === 'STOPPED') {
      trades.push({ x, y, symbol: 'cross', size: 14, color: '#ef232a', label: '止损' })
    } else if (ev.code === 'LIQUIDATED') {
      trades.push({ x, y, symbol: 'rect', size: 13, color: '#ef232a', label: '强平' })
    } else if (ev.code === 'CLOSED') {
      trades.push({ x, y, symbol: 'circle', size: 11, color: '#94a3b8', label: '信号平仓' })
    } else if (ev.code === 'ENTRY_REJECTED' || ev.code === 'ENTRY_REJECTED_RR') {
      rejected.push({ x, y, symbol: 'circle', size: 11, color: 'rgba(139,92,246,0.65)', label: ev.code === 'ENTRY_REJECTED_RR' ? 'RR不足拒绝' : '引擎拒绝' })
    } else if (ev.code === 'ENTRY_FILTERED_VOLATILITY') {
      rejected.push({ x, y, symbol: 'circle', size: 9, color: '#8b5cf6', label: '低波动过滤' })
    } else if (ev.category === 'ZONE_STATE' && ev.code !== 'ZONE_EFFECTIVE') {
      states.push({ x, y: null, code: ev.code, label: ev.message })
    }
  }
  return { trades, rejected, states }
}

/** EMA 信号只在下一根实际 K 线开盘成交；缺少同方向信号证据时沿用通用标记。 */
function emaFillLabel(row) {
  if (meta.value?.strategyId !== 'ema20' || !['LONG', 'SHORT'].includes(row.positionSide)) return ''
  const short = row.positionSide === 'SHORT'
  const prior = replayIndex.value.eventsByIndex.get(timeToIndex(row.time) - 1) || []
  const signal = prior.find(event => event.code === 'SIGNAL_CREATED'
    && event.details?.action === (short ? 'OPEN_SHORT' : 'OPEN_LONG'))
  const version = signal?.details?.strategyVersion
  if (version !== 'ema20-signal-v1' && version !== 'ema20-signal-v2') return ''
  const retest = version === 'ema20-signal-v2' && prior.some(event =>
    event.code === 'EMA_RETEST_CANDIDATE' && event.positionSide === row.positionSide)
  return `${retest ? '回踩' : '穿越'}${short ? '开空' : '开多'}`
}

const emaEventLabels = {
  EMA_RETEST_CANDIDATE: '回踩入场候选',
  ENTRY_FILTERED_VOLATILITY: '波动过滤',
  EMA_EXIT_UNLOCKED: '1R 离场已解锁',
  EMA_EXIT_BLOCKED: '信号平仓被拦截'
}
const diagnosticPrice = new Intl.NumberFormat('zh-CN', { maximumSignificantDigits: 12 })

function eventLabel(row) {
  return (row.code === 'ENTRY_EXECUTED' && emaFillLabel(row)) || emaEventLabels[row.code] || row.code
}

/** 止损/最终止盈线：入场计划（ENTRY_PLANNED details.stop/actualTp）→ 对应退出事件时间 */
function stopTpLines(evs) {
  const lines = []
  const plans = evs.filter(e => e.code === 'ENTRY_PLANNED')
  const exits = evs.filter(e => ['STOPPED', 'TAKE_PROFIT', 'CLOSED', 'LIQUIDATED'].includes(e.code))
  for (const exit of exits) {
    // 找该来源在退出前最近的一次入场计划
    const plan = plans
      .filter(p => p.sourceId === exit.sourceId && Number(p.time) <= Number(exit.time))
      .pop()
    if (!plan) continue
    const d = plan.details || {}
    const stop = Number(d.stop)
    const tp = Number(d.actualTp !== undefined && d.actualTp !== null ? d.actualTp : d.rawTp)
    if (!Number.isFinite(stop)) continue
    const fromIdx = timeToIndex(plan.time)
    const toIdx = timeToIndex(exit.time)
    if (fromIdx < 0 || toIdx < 0) continue
    lines.push({ fromIdx, toIdx, stop, tp: Number.isFinite(tp) ? tp : null })
  }
  return lines
}

function fmtNum(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : '-'
}

function formatTime(t) {
  if (t === null || t === undefined || t === '') return '-'
  const ts = Number(t)
  if (!Number.isFinite(ts) || ts <= 0 || Number.isNaN(new Date(ts).getTime())) return '-'
  return new Date(ts).toLocaleString()
}

/** HTML 转义：所有动态文本（sourceId/message/detailSummary 等）拼接前必须转义，防止注入 */
function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;'
  }[c]))
}

function detailSummary(row) {
  const d = row.details
  if (!d) return '-'
  const parts = []
  if (d.reasonCode) parts.push(`原因: ${d.reasonCode}`)
  if (d.stopCount !== undefined) parts.push(`连续止损 ${d.stopCount}/${d.threshold}`)
  if (d.rr !== undefined) parts.push(`实际RR=${fmtNum(d.rr)}`)
  if (d.actualTp !== undefined && d.rawTp !== undefined && Number(d.actualTp) !== Number(d.rawTp)) parts.push(`止盈 ${fmtNum(d.rawTp)}→${fmtNum(d.actualTp)}`)
  if (d.entry !== undefined) parts.push(`入场 ${fmtNum(d.entry)}`)
  return parts.join('；') || '-'
}

function eventSummary(row) {
  const details = row.details
  const summary = row.message || detailSummary(row)
  if (row.code === 'ENTRY_FILTERED_VOLATILITY' && details?.threshold != null) {
    const side = details.action === 'OPEN_SHORT' ? '开空' : '开多'
    const ratio = details.volatilityRatio
    const actual = ratio == null ? '' : `波动比 ${fmtNum(ratio * 100)}% · `
    return `${summary} · ${side}候选 · ${actual}门槛 ${fmtNum(details.threshold * 100)}%`
  }
  if ((row.code === 'EMA_EXIT_UNLOCKED' || row.code === 'EMA_EXIT_BLOCKED') && details?.unlockPrice != null) {
    return `${summary} · 1R 解锁价 ${diagnosticPrice.format(details.unlockPrice)}`
  }
  if (row.code !== 'RISK_EVALUATED' || details?.totalStopRisk == null || details.stopRiskLimit == null) return summary
  const result = details.decision === 'APPROVED' ? '通过' : '拒绝'
  return `${summary} · 加仓${result} · 原仓 ${fmtNum(details.existingStopRisk)} + 新增 ${fmtNum(details.additionalStopRisk)} = 预计止损风险 ${fmtNum(details.totalStopRisk)} USDT / 上限 ${fmtNum(details.stopRiskLimit)} USDT`
}

function renderChart(preserveZoom = false) {
  if (!replay.value || !replay.value.bars || replay.value.bars.length === 0) return
  if (!chart) {
    chart = echarts.init(chartRef.value)
    // 仅开发环境暴露图表实例，便于本地 E2E 验收，不污染生产 window。
    if (import.meta.env.DEV) window.__qcReplayChart = chart
    onResize = () => chart?.resize()
    window.addEventListener('resize', onResize)
  }
  const bars = replay.value.bars
  const times = barTimes()
  const eventsByIndex = replayIndex.value.eventsByIndex
  const zones = meta.value.params?.zones || []
  const evs = replay.value.events || []
  const periods = { '1min': 60000, '5min': 300000, '15min': 900000, '30m': 1800000,
    '1h': 3600000, '4h': 14400000, '1day': 86400000, '1week': 604800000 }
  const segs = meta.value.params?.chartObjects
    ? historicalZoneSegments(meta.value.params.chartObjects, times, periods[meta.value.interval])
    : buildZoneSegments(zones, evs, times)
  const sig = signalPoints(evs)
  const lines = stopTpLines(evs)

  const { axis, tooltip, slider } = workspaceChartStyle(colorTheme.value)
  const currentZoom = preserveZoom ? chart.getOption()?.dataZoom?.[0] : null
  const zoom = { start: currentZoom?.start ?? 0, end: currentZoom?.end ?? 100 }
  const average = showEma.value ? ema20(bars.map(bar => bar.close)) : []

  const series = [{
    type: 'candlestick',
    name: 'K线',
    data: bars.map(b => [b.open, b.close, b.low, b.high]),
    itemStyle: {
      color: '#22c55e', color0: '#ef4444',
      borderColor: '#22c55e', borderColor0: '#ef4444'
    }
  }]
  if (showEma.value) series.push(ema20Series(average))

  // 区间带统一使用 custom rect。from/to 是闭区间的类目索引；矩形扩至首尾 K 线的半个
  // category band，因而单根可见、相邻状态段无空白，也不会把时间戳误当作 category 索引。
  if (layers.zones && segs.length > 0) {
    series.push({
      type: 'custom', name: '区间带', z: 1, silent: true, clip: true,
      // 防止 end index 被 ECharts 默认映射到 y 轴，拉低价格轴范围。
      encode: { x: [0, 1], y: [2, 3, 5, 6] },
      renderItem: (params, api) => {
        const from = api.value(0)
        const to = api.value(1)
        const lower = api.value(2)
        const upper = api.value(3)
        const state = api.value(4)
        const first = api.coord([from, lower])
        const last = api.coord([to, lower])
        const top = api.coord([from, upper])[1]
        const bottom = api.coord([from, lower])[1]
        // api.size 的 x 为一个 category band；仅一根 bar 时也以 coordSys 宽度兜底。
        const band = Math.abs(api.size([1, 0])[0]) || params.coordSys.width / Math.max(times.length, 1)
        const left = Math.min(first[0], last[0]) - band / 2
        const right = Math.max(first[0], last[0]) + band / 2
        if (api.value(7)) {
          const lowerEnd = api.coord([to, api.value(5)])[1]
          const upperEnd = api.coord([to, api.value(6)])[1]
          const grid = params.coordSys
          return { type: 'polygon', shape: { points: [[left, bottom], [left, top], [right, upperEnd], [right, lowerEnd]] },
            style: { fill: zoneColor(state), stroke: 'none' },
            clipPath: { type: 'rect', shape: { x: grid.x, y: grid.y, width: grid.width, height: grid.height } } }
        }
        const shape = { x: left, y: top, width: right - left, height: bottom - top }
        const clipped = echarts.graphic.clipRectByRect(shape, params.coordSys)
        return clipped && {
          type: 'rect',
          shape: clipped,
          style: { fill: zoneColor(state), stroke: 'none' }
        }
      },
      data: segs.map(s => ({ value: [s.from, s.to, s.lower, s.upper, s.state,
        s.lowerTo ?? s.lower, s.upperTo ?? s.upper, s.channel ? 1 : 0] }))
    })
  }

  // 止损/止盈线：x 用类目索引 l.fromIdx/l.toIdx，由 custom series 绘制线段。
  // custom series 让坐标与区间带统一为 category 索引，不依赖 markLine 的类目解析。
  if (layers.lines && lines.length > 0) {
    const lineItems = []
    for (const l of lines) {
      lineItems.push({ value: [l.fromIdx, l.toIdx, l.stop, 0] }) // 止损红
      if (l.tp) lineItems.push({ value: [l.fromIdx, l.toIdx, l.tp, 1] }) // 止盈绿
    }
    series.push({
      type: 'custom', name: '止损止盈线', z: 3, silent: true, clip: true,
      encode: { x: [0, 1], y: 2 },
      renderItem: (params, api) => {
        const from = api.value(0)
        const to = api.value(1)
        const y = api.value(2)
        const isTp = api.value(3)
        const p1 = api.coord([from, y])
        const p2 = api.coord([to, y])
        return {
          type: 'line',
          shape: { x1: p1[0], y1: p1[1], x2: p2[0], y2: p2[1] },
          style: {
            stroke: isTp ? 'rgba(20,177,67,0.55)' : 'rgba(239,35,42,0.55)',
            lineWidth: 1,
            lineDash: [4, 4],
            opacity: 0.55
          }
        }
      },
      data: lineItems
    })
  }

  // 成交标记
  if (layers.trades && sig.trades.length > 0) {
    series.push({
      type: 'scatter', name: '成交',
      data: sig.trades.map(t => ({
        value: [t.x, t.y],
        symbol: t.symbol, symbolSize: t.size,
        itemStyle: { color: t.color },
        label: { show: true, position: 'top', formatter: t.label, color: t.color, fontSize: 10 }
      })),
      z: 10
    })
  }

  // 拒绝信号
  if (layers.rejected && sig.rejected.length > 0) {
    series.push({
      type: 'scatter', name: '拒绝信号',
      data: sig.rejected.map(t => ({
        value: [t.x, t.y],
        symbol: t.symbol, symbolSize: t.size,
        itemStyle: { color: t.color },
        label: { show: true, position: 'bottom', formatter: t.label, color: t.color, fontSize: 10 }
      })),
      z: 9
    })
  }

  // 状态事件（散点，价格位置取区间的视觉近似：y 用所在 K 线 low*0.995）
  if (layers.states && sig.states.length > 0) {
    series.push({
      type: 'scatter', name: '状态事件',
      data: sig.states.map(s => {
        const bar = bars[s.x]
        return { value: [s.x, bar.low * 0.995], symbol: 'circle', symbolSize: 7, itemStyle: { color: codeColor({ code: s.code }) } }
      }),
      z: 8
    })
  }

  const option = {
    animation: false,
    backgroundColor: 'transparent',
    tooltip: {
      ...tooltip,
      trigger: 'axis',
      confine: true,
      enterable: true,
      hideDelay: 180,
      axisPointer: { ...tooltip.axisPointer, type: 'cross' },
      formatter: (params) => {
        const p = params[0]
        if (!p) return ''
        const bar = bars[p.dataIndex]
        const time = formatTime(times[p.dataIndex])
        let html = `<b>${time}</b><br/>`
        if (bar) html += `开 ${fmtNum(bar.open)} 高 ${fmtNum(bar.high)} 低 ${fmtNum(bar.low)} 收 ${fmtNum(bar.close)}<br/>`
        if (average[p.dataIndex] != null) html += `EMA20 ${fmtNum(average[p.dataIndex])}<br/>`
        const evAt = eventsByIndex.get(p.dataIndex) || []
        if (evAt.length > 0) {
          html += '<br/>'
          for (const e of evAt) {
            html += `<span style="color:${codeColor(e)}">● ${escapeHtml(eventLabel(e))}</span>${e.sourceId ? ' [' + escapeHtml(e.sourceId) + ']' : ''}<br/>`
            html += `<span style="color:var(--qc-text-dim);font-size:11px;">${escapeHtml(eventSummary(e))}</span><br/>`
          }
        }
        // 留出外框与内边距；长事件换行，事件过多时在提示框内滚动。
        const maxWidth = Math.max(1, Math.min(440, chart.getWidth() - 32))
        const maxHeight = Math.max(1, Math.min(300, chart.getHeight() - 32))
        return `<div style="max-width:${maxWidth}px;max-height:${maxHeight}px;white-space:normal;overflow-wrap:anywhere;overflow-y:auto;overscroll-behavior:contain">${html}</div>`
      }
    },
    legend: {
      show: false
    },
    grid: { left: 14, right: 20, top: 30, bottom: 60, containLabel: true },
    xAxis: {
      ...axis,
      type: 'category',
      data: times,
      boundaryGap: true,
      axisLabel: {
        ...axis.axisLabel,
        formatter: (val, index) => formatTime(Number(val)).slice(0, 16)
      },
      splitLine: { show: false }
    },
    yAxis: {
      ...axis,
      type: 'value', scale: true,
      name: '价格 (USDT)'
    },
    dataZoom: [
      { type: 'inside', ...zoom, filterMode: 'weakFilter', throttle: REPLAY_ZOOM_THROTTLE_MS },
      {
        ...slider,
        type: 'slider', ...zoom, height: 22, bottom: 8, filterMode: 'weakFilter',
        throttle: REPLAY_ZOOM_THROTTLE_MS
      }
    ],
    series
  }
  chart.setOption(option, true)
}

/** 点击事件行：dataZoom 定位到事件所在 K 线附近 */
function locateEvent(row) {
  if (!chart || !replay.value?.bars) return
  const idx = timeToIndex(row.time)
  if (idx < 0) return
  const n = replay.value.bars.length
  const span = Math.max(6, Math.ceil(n * 0.1))
  const start = Math.max(0, (idx - span) / n * 100)
  const end = Math.min(100, (idx + span) / n * 100)
  chart.dispatchAction({ type: 'dataZoom', start, end })
  ElMessage.info(`定位到 ${formatTime(row.time)}（第 ${idx + 1} 根 K 线）`)
}

onMounted(async () => {
  await loadTaskOptions()
})

// 路由 /replay/:id 变化（同组件跳转）时重新加载
watch(() => route.params.id, async (newId) => {
  if (!newId) return
  taskId.value = Number(newId)
  await loadReplay()
})

// 图层开关变化后重新渲染图表：关闭图层时对应 series/markArea 必须真正从 option 中移除
watch(layers, () => {
  if (replay.value && meta.value) renderChart()
}, { deep: true })

onBeforeUnmount(() => {
  workspaceSelections.replay = { taskId: taskId.value, showEma: showEma.value, layers: { ...layers } }
  disposeChart()
  if (onResize) window.removeEventListener('resize', onResize)
})
watch(showEma, () => renderChart(true))
watch(colorTheme, theme => applyWorkspaceChartStyle(chart, theme))
</script>

<style scoped>
.rp-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.rp-head__title {
  font-weight: 600;
  white-space: nowrap;
}
.rp-head__tools {
  display: flex;
  align-items: center;
  gap: 10px;
}
.rp-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.rp-meta__range {
  font-size: 12px;
  color: var(--qc-text-dim);
}
.rp-layers {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  font-size: 12px;
}
</style>
