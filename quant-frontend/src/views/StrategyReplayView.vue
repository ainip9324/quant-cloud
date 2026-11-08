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
        <!-- 图层开关 -->
        <div class="rp-layers">
          <el-checkbox v-model="layers.trades">成交</el-checkbox>
          <el-checkbox v-model="layers.rejected">拒绝信号</el-checkbox>
        </div>
      </div>
      <!-- 未完成/失败/旧任务提示 -->
      <el-alert v-if="notReadyMsg" :title="notReadyMsg" type="warning" :closable="false" show-icon />
    </el-card>

    <!-- 主回放图 -->
    <el-card style="margin-top: 20px;">
      <div ref="chartRef" style="width: 100%; height: 480px;"></div>
      <el-empty v-if="!meta && !loading" description="请在上方选择要回放的历史任务" :image-size="80" style="margin-top: -80px;" />
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
            <span :style="{ color: codeColor(row), fontWeight: 600 }">{{ row.code }}</span>
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
          <template #default="{ row }">{{ row.message || detailSummary(row) }}</template>
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
import { ElMessage } from 'element-plus'
import { marketLabel } from '../utils/marketOptions'
import { colorTheme } from '../utils/colorTheme.js'
import { applyWorkspaceChartStyle, workspaceChartStyle } from '../utils/workspaceChartStyle.js'

const REPLAY_ZOOM_THROTTLE_MS = 20

const route = useRoute()
const router = useRouter()

const taskId = ref(null)
const tasks = ref([])
const taskLoading = ref(false)
const loading = ref(false)
const meta = ref(null)
const replay = ref(null)      // {schemaVersion,bars,events}
const notReadyMsg = ref('')

const layers = reactive({ trades: true, rejected: true })

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

// ==================== 任务选择 ====================

async function loadTaskOptions() {
  taskLoading.value = true
  try {
    const { data } = await listBacktests({ status: 'SUCCESS', page: 1, size: 50 })
    tasks.value = data?.list || []
    if (route.params.id) {
      taskId.value = Number(route.params.id)
      await loadReplay()
    } else if (tasks.value.length > 0) {
      taskId.value = tasks.value[0].id
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
      endTs: data.endTs ?? fallbackEnd
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
  if (c === 'ENTRY_EXECUTED') return '#14b143'
  if (c === 'STOPPED' || c === 'LIQUIDATED') return '#ef232a'
  if (c === 'TAKE_PROFIT') return '#38bdf8'
  if (c === 'ENTRY_REJECTED') return '#8b5cf6'
  return '#94a3b8'
}

function categoryColor(cat) {
  if (cat === 'DECISION') return 'primary'
  if (cat === 'EXECUTION') return 'success'
  return 'info'
}

/** 信号点：按事件类型映射符号与颜色 */
function signalPoints(evs) {
  const trades = []
  const rejected = []
  for (const ev of evs) {
    const idx = timeToIndex(ev.time)
    if (idx < 0) continue
    const y = Number(ev.price)
    const x = idx
    if (ev.code === 'ENTRY_EXECUTED') {
      const side = ev.positionSide
      trades.push({ x, y, symbol: side === 'SHORT' ? 'arrow' : 'pin', size: 16, color: side === 'SHORT' ? '#f59e0b' : '#14b143', label: side === 'SHORT' ? '开空' : '开多' })
    } else if (ev.code === 'TAKE_PROFIT') {
      trades.push({ x, y, symbol: 'diamond', size: 13, color: '#38bdf8', label: '止盈' })
    } else if (ev.code === 'STOPPED') {
      trades.push({ x, y, symbol: 'cross', size: 14, color: '#ef232a', label: '止损' })
    } else if (ev.code === 'LIQUIDATED') {
      trades.push({ x, y, symbol: 'rect', size: 13, color: '#ef232a', label: '强平' })
    } else if (ev.code === 'CLOSED') {
      trades.push({ x, y, symbol: 'circle', size: 11, color: '#94a3b8', label: '信号平仓' })
    } else if (ev.code === 'ENTRY_REJECTED') {
      rejected.push({ x, y, symbol: 'circle', size: 11, color: 'rgba(139,92,246,0.65)', label: '引擎拒绝' })
    }
  }
  return { trades, rejected }
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
  if (d.entry !== undefined) parts.push(`入场 ${fmtNum(d.entry)}`)
  return parts.join('；') || '-'
}

function renderChart() {
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
  const sig = signalPoints(events.value)

  const { axis, tooltip, slider } = workspaceChartStyle(colorTheme.value)

  const series = [{
    type: 'candlestick',
    name: 'K线',
    data: bars.map(b => [b.open, b.close, b.low, b.high]),
    itemStyle: {
      color: '#22c55e', color0: '#ef4444',
      borderColor: '#22c55e', borderColor0: '#ef4444'
    }
  }]

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

  const option = {
    animation: false,
    backgroundColor: 'transparent',
    tooltip: {
      ...tooltip,
      trigger: 'axis',
      axisPointer: { ...tooltip.axisPointer, type: 'cross' },
      formatter: (params) => {
        const p = params[0]
        const bar = bars[p.dataIndex]
        const time = formatTime(times[p.dataIndex])
        let html = `<b>${time}</b><br/>`
        if (bar) html += `开 ${fmtNum(bar.open)} 高 ${fmtNum(bar.high)} 低 ${fmtNum(bar.low)} 收 ${fmtNum(bar.close)}<br/>`
        const evAt = eventsByIndex.get(p.dataIndex) || []
        if (evAt.length > 0) {
          html += '<br/>'
          for (const e of evAt) {
            html += `<span style="color:${codeColor(e)}">● ${escapeHtml(e.code)}</span>${e.sourceId ? ' [' + escapeHtml(e.sourceId) + ']' : ''}<br/>`
            html += `<span style="color:var(--qc-text-dim);font-size:11px;">${escapeHtml(e.message || detailSummary(e))}</span><br/>`
          }
        }
        return html
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
      { type: 'inside', start: 0, end: 100, filterMode: 'weakFilter', throttle: REPLAY_ZOOM_THROTTLE_MS },
      {
        ...slider,
        type: 'slider', start: 0, end: 100, height: 22, bottom: 8, filterMode: 'weakFilter',
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

// 图层开关变化后重新渲染图表：关闭图层时对应 series 必须真正从 option 中移除
watch(layers, () => {
  if (replay.value && meta.value) renderChart()
}, { deep: true })

onBeforeUnmount(() => {
  disposeChart()
  if (onResize) window.removeEventListener('resize', onResize)
})
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
