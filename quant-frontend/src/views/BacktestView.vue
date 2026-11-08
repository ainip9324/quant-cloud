<template>
  <div>
    <!-- 提交回测 -->
    <el-card>
      <template #header>提交回测</template>
      <el-form :inline="true" style="display: flex; flex-wrap: wrap; gap: 12px; align-items: center;">
        <el-form-item label="策略">
          <el-select v-model="form.strategyId" style="width: 160px;" placeholder="选择策略">
            <el-option v-for="s in strategies" :key="s.id" :label="s.id" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="市场">
          <el-select v-model="form.marketType" style="width: 170px;" @change="loadSymbols">
            <el-option v-for="option in MARKET_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="交易对">
          <el-select v-model="form.symbol" style="width: 140px;">
            <el-option v-for="s in symbols" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="周期">
          <el-select v-model="form.interval" style="width: 110px;">
            <el-option v-for="option in intervals" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="K线数">
          <el-input-number v-model="form.limit" :min="100" :max="1000" :step="100" style="width: 130px;" />
        </el-form-item>
        <el-form-item label="杠杆">
          <el-input-number v-model="form.leverage" :min="1" :max="100" :step="1" style="width: 110px;" />
          <span style="margin-left: 4px; color: var(--qc-text-dim); font-size: 12px;">x</span>
        </el-form-item>
        <el-form-item label="初始资金">
          <el-input-number v-model="form.initialCapital" :min="0" :step="10000" style="width: 160px;" />
          <span style="margin-left: 4px; color: var(--qc-text-dim); font-size: 12px;">USDT</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="submit" :loading="submitting" :disabled="polling">开始回测</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 任务状态（提交/查询后展示） -->
    <el-card v-if="task.id" style="margin-top: 20px;">
      <template #header>
        <div style="display: flex; align-items: center; gap: 12px; flex-wrap: wrap;">
          <span style="font-weight: 600;">任务 #{{ task.id }}</span>
          <el-tag type="info" effect="plain">{{ marketLabel(task.marketType) }}</el-tag>
          <el-tag :type="statusType(task.status)">{{ task.status }}</el-tag>
          <span v-if="task.status === 'WAITING'" style="color: var(--qc-text-dim);">排队中...</span>
          <span v-if="task.status === 'RUNNING'" style="color: var(--qc-accent);">回测执行中...</span>
          <template v-if="task.result">
            <template v-if="task.result.leverage > 0">
              <el-tag type="primary" size="small">{{ task.result.leverage }}x 杠杆</el-tag>
              <el-tag type="info" size="small">逐仓</el-tag>
              <el-tag type="primary" size="small" effect="plain">初始资金 {{ task.result.initialCapital?.toLocaleString() }} USDT</el-tag>
            </template>
            <el-tag v-else type="info" size="small">配置不可用（旧版本结果）</el-tag>
          </template>
          <el-button v-if="task.status === 'SUCCESS'" type="success" size="small" style="margin-left: auto;" @click="$router.push(`/replay/${task.id}`)">
            查看策略回放
          </el-button>
          <el-button v-if="task.status === 'SUCCESS'" type="primary" size="small" :loading="aiCreating" @click="generateAiReview">
            生成 AI 复盘
          </el-button>
        </div>
      </template>

      <!-- 失败信息 -->
      <el-alert v-if="task.status === 'FAILED'" :title="task.errorMsg || '回测失败'" type="error" :closable="false" show-icon />

      <!-- 结果区（SUCCESS 时展示） -->
      <template v-if="task.result">
        <!-- 结果指标（强化层级） -->
        <div class="qc-metrics">
          <div class="qc-metrics__grid">
            <div class="qc-card qc-metric">
              <div class="qc-metric__label">收益率</div>
              <div
                class="qc-metric__value"
                :style="{ color: task.result.returnRate >= 0 ? 'var(--qc-down)' : 'var(--qc-up)' }"
              >
                {{ task.result.returnRate.toFixed(4) }}%
              </div>
              <div class="qc-metric__desc">最终权益 / 初始资金</div>
            </div>
            <div class="qc-card qc-metric">
              <div class="qc-metric__label">最大回撤</div>
              <div class="qc-metric__value" style="color: var(--qc-up);">{{ task.result.maxDrawdown.toFixed(4) }}%</div>
              <div class="qc-metric__desc">权益曲线峰值回撤</div>
            </div>
            <div class="qc-card qc-metric">
              <div class="qc-metric__label">交易笔数</div>
              <div class="qc-metric__value" style="color: var(--qc-accent);">{{ task.result.trades.length }}</div>
              <div class="qc-metric__desc">含开仓 / 平仓 / 风控</div>
            </div>
          </div>
        </div>

        <!-- 权益曲线 -->
        <el-card shadow="never" style="margin-top: 20px;">
          <template #header>权益曲线</template>
          <div ref="chartRef" style="width: 100%; height: 380px;"></div>
        </el-card>

        <!-- 交易明细 -->
        <el-card shadow="never" style="margin-top: 20px;">
          <template #header>交易明细（{{ task.result.trades.length }} 笔）</template>
          <el-table :data="task.result.trades" border stripe size="small" max-height="420">
            <el-table-column type="index" label="#" width="55" align="center" />
            <el-table-column label="时间" width="170">
              <template #default="{ row }">{{ formatTime(row.time) }}</template>
            </el-table-column>
            <el-table-column label="类型" width="110" align="center">
              <template #default="{ row }">
                <el-tag :type="tradeTypeColor(row.type)" size="small">{{ row.type }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="方向" width="70" align="center">
              <template #default="{ row }">
                <!-- 旧任务无 positionSide，按 LONG 兼容显示为"多"（绿色）；SHORT 橙色 -->
                <el-tag :type="row.positionSide === 'SHORT' ? 'warning' : 'success'" size="small" effect="plain">
                  {{ row.positionSide === 'SHORT' ? '空' : '多' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="来源" width="90" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.sourceId" size="small" type="info" effect="plain">{{ row.sourceId }}</el-tag>
                <span v-else style="color: var(--qc-text-dim); font-size: 12px;">-</span>
              </template>
            </el-table-column>
            <el-table-column prop="price" label="价格" width="120" align="right" :formatter="fmtPrice" />
            <el-table-column prop="amount" label="数量" width="100" align="right" :formatter="fmtAmount" />
            <el-table-column label="保证金" width="110" align="right">
              <template #default="{ row }">
                <span :style="{ color: row.margin >= 0 ? 'var(--qc-accent)' : 'var(--qc-down)' }">
                  {{ fmtMargin(row) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="fee" label="手续费" width="90" align="right" :formatter="fmtFee" />
            <el-table-column prop="availableBalance" label="可用保证金" width="120" align="right" :formatter="fmtAvailable" />
            <el-table-column prop="equity" label="权益" width="120" align="right" :formatter="fmtEquity" />
          </el-table>
        </el-card>
      </template>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { createAiReport, getStrategies, submitBacktest, getBacktest } from '../api/core'
import { getSymbols } from '../api/data'
import { INTERVALS, MARKET_OPTIONS, marketLabel, normalizeMarketSelection } from '../utils/marketOptions'
import { colorTheme } from '../utils/colorTheme.js'
import { applyWorkspaceChartStyle, workspaceChartStyle } from '../utils/workspaceChartStyle.js'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()

// 表单
const form = reactive({
  marketType: 'USDT_FUTURES',
  strategyId: 'turtle',
  symbol: 'BTCUSDT',
  interval: '1h',
  limit: 500,
  leverage: 10,
  initialCapital: 100000
})

const strategies = ref([])
const symbols = ref([])
const intervals = computed(() => INTERVALS[form.marketType])
const submitting = ref(false)
const polling = ref(false)
const aiCreating = ref(false)

// 当前任务（提交或查询后填充）
const task = ref({ id: null, marketType: null, status: null, errorMsg: null, result: null })

const chartRef = ref(null)
let chart = null
let pollTimer = null

onMounted(async () => {
  // 加载策略列表 + 交易对列表
  try {
    const [sRes, symRes] = await Promise.all([getStrategies(), getSymbols(form.marketType)])
    strategies.value = sRes.data
    symbols.value = symRes.data
    const normalized = normalizeMarketSelection(form.marketType, form.symbol, form.interval, symbols.value)
    form.symbol = normalized.symbol
    form.interval = normalized.interval
  } catch (e) {
    ElMessage.error('加载数据失败: ' + e.message)
  }

  // 从策略页跳转过来：预选策略
  if (strategies.value.some(strategy => strategy.id === route.query.strategyId)) {
    form.strategyId = route.query.strategyId
  }

  // 从历史页跳转过来：直接加载该任务结果
  if (route.query.id) {
    startPolling(Number(route.query.id))
  }
})

onBeforeUnmount(() => {
  stopPolling()
  chart?.dispose()
})

async function loadSymbols() {
  try {
    const response = await getSymbols(form.marketType)
    symbols.value = response.data
    const normalized = normalizeMarketSelection(form.marketType, form.symbol, form.interval, symbols.value)
    form.symbol = normalized.symbol
    form.interval = normalized.interval
  } catch (e) {
    ElMessage.error('加载交易对失败: ' + e.message)
  }
}

// 路由 query.id 变化时重新加载（同组件跳转）
watch(() => route.query.id, (newId) => {
  if (newId) {
    stopPolling()
    chart?.dispose()
    chart = null
    task.value = { id: null, marketType: null, status: null, errorMsg: null, result: null }
    startPolling(Number(newId))
  }
})

/** 提交回测 */
async function submit() {
  if (!strategies.value.some(strategy => strategy.id === form.strategyId)) {
    ElMessage.warning('请选择可用策略')
    return
  }
  submitting.value = true
  try {
    // 重置上次结果
    chart?.dispose()
    chart = null
    task.value = { id: null, marketType: null, status: null, errorMsg: null, result: null }

    const params = {
      leverage: form.leverage,
      initialCapital: form.initialCapital
    }
    const res = await submitBacktest({
      marketType: form.marketType,
      strategyId: form.strategyId,
      symbol: form.symbol,
      interval: form.interval,
      limit: form.limit,
      params
    })
    ElMessage.success('回测任务已提交')
    startPolling(res.data.id)
  } catch (e) {
    ElMessage.error('提交失败: ' + (e.response?.data?.message || e.message))
  } finally {
    submitting.value = false
  }
}

/** 开始轮询任务状态（每2秒，直到 SUCCESS/FAILED） */
function startPolling(id) {
  task.value.id = id
  polling.value = true
  fetchTask(id) // 立即查一次
  pollTimer = setInterval(() => fetchTask(id), 2000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  polling.value = false
}

async function fetchTask(id) {
  try {
    const res = await getBacktest(id)
    const data = res.data
    task.value.marketType = data.marketType
    task.value.status = data.status
    task.value.errorMsg = data.errorMsg

    if (data.status === 'SUCCESS') {
      stopPolling()
      task.value.result = data.result
      await nextTick()
      renderEquityCurve(data.result.equityCurve)
    } else if (data.status === 'FAILED') {
      stopPolling()
    }
  } catch (e) {
    stopPolling()
    task.value.status = 'FAILED'
    task.value.errorMsg = '查询失败: ' + (e.response?.data?.message || e.message)
  }
}

/** 创建或复用当前 SUCCESS 任务的 AI 复盘，并进入报告详情。 */
async function generateAiReview() {
  if (!task.value.id) return
  aiCreating.value = true
  try {
    const { data } = await createAiReport(task.value.id)
    ElMessage.success(data.reused ? '已打开已有 AI 复盘' : 'AI 复盘生成完成')
    router.push({ name: 'ai-reports', params: { reportId: data.reportId }, query: { taskId: task.value.id } })
  } catch (error) {
    ElMessage.error('生成 AI 复盘失败: ' + (error.response?.data?.message || error.response?.data?.code || error.message))
  } finally {
    aiCreating.value = false
  }
}

/** 渲染权益曲线，x 轴对应 K 线时间。 */
function renderEquityCurve(equityCurve) {
  if (!equityCurve || equityCurve.length === 0) return
  if (!chart) {
    chart = echarts.init(chartRef.value)
    window.addEventListener('resize', resizeChart)
  }
  // 规范化并过滤无效点：p.time/p.equity 显式转 Number，
  // 过滤 NaN/Infinity 或非法时间戳的点（如后端偶发脏数据）
  const points = equityCurve
    .map(p => ({ time: Number(p.time), equity: Number(p.equity) }))
    .filter(p => Number.isFinite(p.time) && Number.isFinite(p.equity)
      && !Number.isNaN(new Date(p.time).getTime()))

  // 旧版兼容：真实毫秒时间戳约 1e12 级别；若 time 全是 0,1,2... 序号
  // （旧引擎/旧格式），按"第 N 个点"展示，避免被 new Date 解析成 1970 年
  const isIndexTime = points.length > 0 && points.every(p => p.time < 1e11)

  const times = points.map(p => p.time)
  const values = points.map(p => p.equity)

  const { axis, tooltip, slider } = workspaceChartStyle(colorTheme.value)

  const option = {
    backgroundColor: 'transparent',
    grid: { left: 14, right: 20, top: 40, bottom: 60, containLabel: true },
    tooltip: {
      ...tooltip,
      trigger: 'axis',
      axisPointer: {
        type: 'line',
        lineStyle: { ...tooltip.axisPointer.lineStyle, type: 'dashed' }
      },
      formatter: (params) => {
        const v = Number(params[0].data)
        const label = isIndexTime
          ? `第 ${params[0].axisValue} 个点`
          : formatChartTime(params[0].axisValue, true)
        return `${label}<br/>权益: ${Number.isFinite(v) ? v.toFixed(2) : '-'}`
      }
    },
    xAxis: {
      ...axis,
      type: 'category',
      data: times,
      axisLabel: {
        ...axis.axisLabel,
        // category 轴回调的 value 可能是字符串，统一走格式化函数
        formatter: (val) => isIndexTime
          ? `第 ${val} 点`
          : formatChartTime(val, false)
      },
      splitLine: { show: false }
    },
    yAxis: {
      ...axis,
      type: 'value',
      scale: true,
      name: '权益 (USDT)'
    },
    dataZoom: [
      { type: 'inside', start: 0, end: 100 },
      {
        ...slider,
        type: 'slider',
        start: 0,
        end: 100,
        height: 22,
        bottom: 8
      }
    ],
    series: [{
      type: 'line',
      data: values,
      smooth: false,
      symbol: 'none',
      lineStyle: { width: 2, color: '#38BDF8', shadowBlur: 8, shadowColor: 'rgba(56,189,248,0.4)' },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(56,189,248,0.28)' },
          { offset: 1, color: 'rgba(56,189,248,0.02)' }
        ])
      }
    }]
  }
  chart.setOption(option, true)
}

/**
 * 统一格式化图表时间。
 *
 * ECharts category 轴回调（axisLabel.formatter / tooltip 的 axisValue）
 * 传入的 value 可能是字符串，new Date("1754006400000") 会按日期字符串
 * 解析得到 Invalid Date。必须先 Number() 显式转数值再格式化。
 *
 * @param {*} value     毫秒时间戳（Number 或字符串）
 * @param {boolean} withTime true=含时分秒，false=仅日期
 * @returns {string} 格式化后的时间；无效输入返回 '-'
 */
function formatChartTime(value, withTime = false) {
  // null/undefined/空字符串统一返回 '-'（Number(null)/Number('') 为 0，会被误解析为 1970 年）
  if (value === null || value === undefined || value === '') return '-'
  const timestamp = Number(value)
  if (!Number.isFinite(timestamp) || Number.isNaN(new Date(timestamp).getTime())) {
    return '-'
  }
  return withTime
    ? new Date(timestamp).toLocaleString()
    : new Date(timestamp).toLocaleDateString()
}

function resizeChart() {
  chart?.resize()
}

/** 状态 → tag 颜色 */
function statusType(status) {
  switch (status) {
    case 'SUCCESS': return 'success'
    case 'RUNNING': return 'primary'
    case 'WAITING': return 'info'
    case 'FAILED': return 'danger'
    default: return 'info'
  }
}

/** 交易类型 → tag 颜色（含空头类型：SHORT_SELL/SHORT_ADD 橙、COVER/SHORT_TP 蓝/绿、SHORT_STOP/SHORT_LIQUIDATION 红） */
function tradeTypeColor(type) {
  switch (type) {
    case 'BUY': return 'success'
    case 'ADD': return 'warning'
    case 'SELL': return 'primary'
    case 'STOP': return 'info'
    case 'TP': return 'success'
    case 'LIQUIDATION': return 'danger'
    case 'SHORT_SELL': return 'warning'
    case 'SHORT_ADD': return 'warning'
    case 'COVER': return 'primary'
    case 'SHORT_TP': return 'success'
    case 'SHORT_STOP': return 'danger'
    case 'SHORT_LIQUIDATION': return 'danger'
    default: return 'info'
  }
}

function formatTime(t) {
  // 复用统一格式化函数，避免字符串时间戳被 new Date 错误解析
  return formatChartTime(t, true)
}

function fmtPrice(row) {
  return row.price.toFixed(2)
}

function fmtAmount(row) {
  return row.amount.toPrecision(4)
}

function fmtMargin(row) {
  return (row.margin >= 0 ? '+' : '') + row.margin.toFixed(2)
}

function fmtFee(row) {
  return row.fee.toFixed(2)
}

function fmtAvailable(row) {
  return row.availableBalance.toFixed(2)
}

function fmtEquity(row) {
  return row.equity.toFixed(2)
}
</script>
