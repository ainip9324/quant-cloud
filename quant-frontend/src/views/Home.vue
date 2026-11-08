<template>
  <div>
    <!-- 顶部欢迎/状态区 -->
    <section class="qc-hero qc-card">
      <div class="qc-hero__left">
        <p class="qc-hero__eyebrow">QUANTITATIVE TRADING PLATFORM</p>
        <h1 class="qc-hero__title">量化交易云平台</h1>
        <p class="qc-hero__subtitle">多策略 · 逐仓永续合约 · 真实行情驱动 · 一键回测验证</p>
      </div>
      <div class="qc-hero__right">
        <div class="qc-hero__clock">
          <span class="qc-hero__clock-label">SYSTEM TIME</span>
          <span class="qc-hero__clock-value">{{ now }}</span>
        </div>
      </div>
    </section>

    <!-- 平台指标 -->
    <section class="qc-metrics" v-loading="loading" element-loading-text="检测中...">
      <div class="qc-metrics__grid">
        <div v-for="card in cards" :key="card.title" class="qc-card qc-metric">
          <div class="qc-metric__label">{{ card.title }}</div>
          <div class="qc-metric__value" style="color: var(--qc-accent);">{{ card.value }}</div>
          <div class="qc-metric__desc">{{ card.desc }}</div>
        </div>
      </div>
    </section>

    <!-- 服务健康状态（带呼吸点） -->
    <section class="qc-card" style="margin-top: 20px;">
      <div class="qc-card__header">
        <h3 class="qc-card__title">服务状态</h3>
        <span class="qc-health__status" style="color: var(--qc-text-dim);">REAL-TIME PROBE</span>
      </div>
      <div class="qc-card__body">
        <div class="qc-health-grid">
          <div
            v-for="s in healthList"
            :key="s.key"
            class="qc-card qc-health"
          >
            <span :class="['qc-health__dot', s.dotClass]"></span>
            <div class="qc-health__meta">
              <div class="qc-health__name">{{ s.name }}</div>
              <div :class="['qc-health__status', s.statusClass]">{{ s.text }}</div>
            </div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { checkHealth as checkDataHealth } from '../api/data'
import { checkCoreHealth, listBacktests } from '../api/core'

const loading = ref(false)
const now = ref(formatNow())
let clockTimer = null

const cards = ref([
  { title: '策略数', value: '2', desc: 'EMA20 / 海龟' },
  { title: '回测任务', value: '—', desc: '历史任务统计' },
  { title: '交易对', value: '5', desc: 'BTC / ETH / ...' },
  { title: '执行引擎', value: 'MQ', desc: 'RocketMQ 异步调度' }
])

const services = reactive({
  data: '',
  core: '',
  mq: '',
  gateway: false
})

const healthList = computed(() => [
  {
    key: 'data',
    name: '数据服务 · data-service',
    text: services.data || '检测中',
    dotClass: services.data === 'UP' ? 'qc-health__dot--up' : (services.data === 'DOWN' ? 'qc-health__dot--down' : 'qc-health__dot--pending'),
    statusClass: services.data === 'UP' ? 'qc-health__status--up' : (services.data === 'DOWN' ? 'qc-health__status--down' : 'qc-health__status--pending')
  },
  {
    key: 'core',
    name: '核心服务 · core-service',
    text: services.core || '检测中',
    dotClass: services.core === 'UP' ? 'qc-health__dot--up' : (services.core === 'DOWN' ? 'qc-health__dot--down' : 'qc-health__dot--pending'),
    statusClass: services.core === 'UP' ? 'qc-health__status--up' : (services.core === 'DOWN' ? 'qc-health__status--down' : 'qc-health__status--pending')
  },
  {
    key: 'gateway',
    name: 'API 网关 · gateway',
    text: services.gateway ? 'UP' : 'DOWN',
    dotClass: services.gateway ? 'qc-health__dot--up' : 'qc-health__dot--down',
    statusClass: services.gateway ? 'qc-health__status--up' : 'qc-health__status--down'
  },
  {
    key: 'mq',
    name: '消息队列 · RocketMQ',
    text: services.mq || '检测中',
    dotClass: services.mq === 'UP' ? 'qc-health__dot--up' : (services.mq === 'DOWN' ? 'qc-health__dot--down' : 'qc-health__dot--pending'),
    statusClass: services.mq === 'UP' ? 'qc-health__status--up' : (services.mq === 'DOWN' ? 'qc-health__status--down' : 'qc-health__status--pending')
  }
])

onMounted(async () => {
  loading.value = true
  clockTimer = setInterval(() => { now.value = formatNow() }, 1000)

  // 并行检测：data-service / core-service 健康检查 + 当前账号回测任务总数（后端按 userId 隔离）
  const results = await Promise.allSettled([
    checkDataHealth(),
    checkCoreHealth(),
    listBacktests({ page: 1, size: 1 })
  ])

  // data-service
  if (results[0].status === 'fulfilled') {
    services.data = results[0].value.data.status || 'UP'
  } else {
    services.data = 'DOWN'
  }

  // core-service + MQ
  if (results[1].status === 'fulfilled') {
    const coreData = results[1].value.data
    services.core = coreData.status || 'UP'
    services.mq = coreData.mq?.status || 'UP'
  } else {
    services.core = 'DOWN'
    services.mq = 'DOWN'
  }

  // 回测任务总数：用服务端 total（分页总条数），不通过当前页 list.length 统计；失败仅显示 —，不影响健康检查
  const taskCard = cards.value.find(c => c.title === '回测任务')
  if (results[2].status === 'fulfilled') {
    const total = results[2].value.data?.total
    taskCard.value = typeof total === 'number' ? String(total) : '—'
  } else {
    taskCard.value = '—'
  }

  // gateway：能收到 API 响应说明 gateway 正常
  services.gateway = results[0].status === 'fulfilled' || results[1].status === 'fulfilled'

  loading.value = false
})

onBeforeUnmount(() => {
  if (clockTimer) clearInterval(clockTimer)
})

function formatNow() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}
</script>

<style scoped>
.qc-hero {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 34px 40px;
}
.qc-hero__left {
  min-width: 0;
}
.qc-hero__eyebrow {
  margin: 0 0 10px;
  font-size: 12px;
  letter-spacing: 3px;
  color: var(--qc-accent);
  font-weight: 600;
}
.qc-hero__title {
  margin: 0;
  font-size: 30px;
  font-weight: 700;
  color: var(--qc-text);
  letter-spacing: 1px;
}
.qc-hero__subtitle {
  margin: 12px 0 0;
  font-size: 14px;
  color: var(--qc-text-dim);
}
.qc-hero__right {
  flex-shrink: 0;
  text-align: right;
}
.qc-hero__clock-label {
  display: block;
  font-size: 11px;
  letter-spacing: 2px;
  color: var(--qc-text-faint);
}
.qc-hero__clock-value {
  display: block;
  margin-top: 6px;
  font-size: 18px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--qc-text);
}

.qc-metrics {
  margin-top: 20px;
}
.qc-metrics__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
}

@media (max-width: 700px) {
  .qc-hero {
    flex-direction: column;
    align-items: flex-start;
    padding: 24px;
  }
  .qc-hero__right {
    text-align: left;
  }
  .qc-hero__title {
    font-size: 24px;
  }
}
</style>
