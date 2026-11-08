<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; align-items: center; gap: 12px;">
          <span>K线图</span>
          <el-select v-model="marketType" style="width: 180px;" @change="loadSymbols">
            <el-option v-for="option in MARKET_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-select v-model="symbol" style="width: 140px;" @change="loadKlines">
            <el-option v-for="s in symbols" :key="s" :label="s" :value="s" />
          </el-select>
          <el-select v-model="interval" style="width: 100px;" @change="loadKlines">
            <el-option v-for="option in intervals" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-input-number v-model="limit" :min="50" :max="1000" :step="100" style="width: 120px;" @change="loadKlines" />
          <el-button type="primary" @click="loadKlines" :loading="loading">刷新</el-button>
          <el-button @click="syncData" :loading="syncing">同步数据</el-button>
        </div>
      </template>
      <div ref="chartRef" style="width: 100%; height: 500px;"></div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'
import { getKlines, getSymbols, syncKlines } from '../api/data'
import { INTERVALS, MARKET_OPTIONS, marketLabel, normalizeMarketSelection } from '../utils/marketOptions'
import { ElMessage } from 'element-plus'

const symbol = ref('BTCUSDT')
const marketType = ref('USDT_FUTURES')
const interval = ref('1h')
const limit = ref(200)
const loading = ref(false)
const syncing = ref(false)
const symbols = ref([])
const intervals = computed(() => INTERVALS[marketType.value])
const chartRef = ref(null)
let chart = null

onMounted(async () => {
  // 获取交易对列表
  try {
    const res = await getSymbols(marketType.value)
    symbols.value = res.data
    const normalized = normalizeMarketSelection(marketType.value, symbol.value, interval.value, symbols.value)
    symbol.value = normalized.symbol
    interval.value = normalized.interval
  } catch { symbols.value = ['BTCUSDT'] }

  // 初始化图表
  await nextTick()
  chart = echarts.init(chartRef.value)
  window.addEventListener('resize', () => chart?.resize())
  await loadKlines()
})

onBeforeUnmount(() => {
  chart?.dispose()
})

async function loadSymbols() {
  try {
    const res = await getSymbols(marketType.value)
    symbols.value = res.data
    const normalized = normalizeMarketSelection(marketType.value, symbol.value, interval.value, symbols.value)
    symbol.value = normalized.symbol
    interval.value = normalized.interval
    await loadKlines()
  } catch { symbols.value = [] }
}

async function loadKlines() {
  loading.value = true
  try {
    const res = await getKlines(marketType.value, symbol.value, interval.value, limit.value)
    renderChart(res.data)
    ElMessage.success(`加载 ${res.data.length} 条K线`)
  } catch (e) {
    ElMessage.error('加载失败: ' + (e.response?.data?.message || e.message))
  } finally {
    loading.value = false
  }
}

async function syncData() {
  syncing.value = true
  try {
    const res = await syncKlines(marketType.value, symbol.value, interval.value, 1000)
    ElMessage.success(`同步 ${res.data.synced} 条`)
    await loadKlines()
  } catch (e) {
    ElMessage.error('同步失败: ' + e.message)
  } finally {
    syncing.value = false
  }
}

function renderChart(klines) {
  if (!klines || klines.length === 0) return

  const dates = klines.map(k => new Date(k.time).toLocaleString())

  // 深色终端风格：透明背景、半透明坐标轴、暗色 tooltip；蜡烛保持红涨绿跌
  const axisLabelColor = 'rgba(255,255,255,0.5)'
  const axisLineColor = 'rgba(255,255,255,0.14)'
  const splitLineColor = 'rgba(255,255,255,0.06)'

  // x 轴标签抽样：约 6~8 个，按数据长度自适应步长（tooltip 仍显示完整时间）
  const targetLabelCount = 7
  const labelStep = Math.max(1, Math.ceil(klines.length / targetLabelCount))

  const option = {
    backgroundColor: 'transparent',
    title: {
      text: `${marketLabel(marketType.value)} · ${symbol.value} ${interval.value}`,
      left: 'center',
      textStyle: { color: 'rgba(255,255,255,0.85)', fontSize: 14, fontWeight: 600 }
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross', lineStyle: { color: 'rgba(56,189,248,0.5)' } },
      backgroundColor: 'rgba(17,20,24,0.92)',
      borderColor: 'rgba(255,255,255,0.14)',
      textStyle: { color: '#e5e7eb', fontSize: 12 }
    },
    xAxis: {
      type: 'category',
      data: dates,
      axisLine: { lineStyle: { color: axisLineColor } },
      axisLabel: {
        rotate: 45,
        color: axisLabelColor,
        interval: labelStep,
        // 按索引抽样展示（约 6~8 个），格式化为紧凑的 MM-DD HH:mm
        formatter: (val, index) => {
          if (index % labelStep !== 0) return ''
          const k = klines[index]
          if (!k) return ''
          const d = new Date(k.time)
          if (Number.isNaN(d.getTime())) return ''
          const pad = (n) => String(n).padStart(2, '0')
          return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
        }
      },
      splitLine: { show: false }
    },
    yAxis: {
      type: 'value',
      scale: true,
      axisLine: { lineStyle: { color: axisLineColor } },
      axisLabel: { color: axisLabelColor },
      splitLine: { lineStyle: { color: splitLineColor } }
    },
    dataZoom: [
      { type: 'inside', start: 0, end: 100 },
      {
        type: 'slider',
        start: 0,
        end: 100,
        height: 22,
        bottom: 8,
        borderColor: 'rgba(255,255,255,0.1)',
        backgroundColor: 'rgba(255,255,255,0.04)',
        fillerColor: 'rgba(56,189,248,0.14)',
        dataBackground: { lineStyle: { color: 'rgba(255,255,255,0.25)' }, areaStyle: { color: 'rgba(255,255,255,0.06)' } },
        handleStyle: { color: 'rgba(255,255,255,0.7)', borderColor: 'rgba(255,255,255,0.3)' },
        textStyle: { color: 'rgba(255,255,255,0.45)' }
      }
    ],
    series: [{
      type: 'candlestick',
      data: klines.map(k => [k.open, k.close, k.low, k.high]),
      itemStyle: {
        color: '#ef232a',        // 阳线（上涨）
        color0: '#14b143',       // 阴线（下跌）
        borderColor: '#ef232a',
        borderColor0: '#14b143'
      }
    }]
  }
  chart.setOption(option, true)
}
</script>
