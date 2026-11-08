<template>
  <section class="ai-report-page">
    <el-card class="ai-report-hero">
      <div class="ai-report-hero__content">
        <div>
          <p class="ai-report-hero__eyebrow">AI RESEARCH COPILOT</p>
          <h1>回测 AI 复盘</h1>
          <p>基于已核验的回测事实与策略事件生成分析；分析结论和可信证据分开呈现。</p>
        </div>
        <div class="ai-report-hero__actions">
          <el-input-number v-model="taskId" :min="1" :precision="0" controls-position="right" />
          <el-button :loading="loading" @click="loadReports">查询报告</el-button>
          <el-button type="primary" :loading="creating" @click="createReport">生成 AI 复盘</el-button>
        </div>
      </div>
    </el-card>

    <el-alert
      v-if="errorMessage"
      class="ai-report-alert"
      type="error"
      show-icon
      :closable="false"
      :title="errorMessage"
    />

    <el-card v-if="reports.length || loading" class="ai-report-list" v-loading="loading">
      <template #header>
        <div class="ai-report-card-title">
          <span>报告尝试</span>
          <span class="ai-report-card-title__hint">任务 #{{ taskId || '-' }}</span>
        </div>
      </template>
      <el-empty v-if="!reports.length && !loading" description="尚未生成复盘报告" :image-size="72" />
      <div v-else class="ai-report-attempts">
        <button
          v-for="item in reports"
          :key="item.id"
          class="ai-report-attempt"
          :class="{ active: item.id === selectedId }"
          @click="openReport(item.id)"
        >
          <span class="ai-report-attempt__status" :class="`is-${item.status.toLowerCase()}`"></span>
          <span>
            <b>Attempt {{ item.attemptNo }}</b>
            <small>{{ item.status }} · {{ formatTime(item.createTime) }}</small>
          </span>
          <span class="ai-report-attempt__model">{{ item.actualModel || item.requestedModel || '-' }}</span>
        </button>
      </div>
    </el-card>

    <el-skeleton v-if="detailLoading" :rows="10" animated />

    <template v-else-if="reportDetail">
      <el-card class="ai-report-summary">
        <template #header>
          <div class="ai-report-card-title">
            <span>复盘摘要</span>
            <div class="ai-report-meta">
              <el-tag :type="statusType(reportDetail.status)">{{ reportDetail.status }}</el-tag>
              <el-tag effect="plain">Attempt {{ reportDetail.attemptNo }}</el-tag>
              <el-tag effect="plain">请求 {{ reportDetail.requestedModel || '-' }}</el-tag>
              <el-tag v-if="reportDetail.actualModel" type="success" effect="plain">实际 {{ reportDetail.actualModel }}</el-tag>
              <el-button type="warning" link :loading="regenerating" @click="regenerate">重新生成</el-button>
            </div>
          </div>
        </template>

        <template v-if="reportDetail.status === 'SUCCESS' && reportDetail.report">
          <h2>{{ reportDetail.report.headline }}</h2>
          <p class="ai-report-summary__text">{{ reportDetail.report.summary }}</p>
          <div class="ai-report-facts">
            <div v-for="fact in overviewFacts" :key="fact.label" class="ai-report-fact">
              <span>{{ fact.label }}</span><b>{{ fact.value }}</b>
            </div>
          </div>
        </template>
        <el-alert v-else-if="reportDetail.status === 'GENERATING'" type="info" :closable="false" show-icon title="报告生成中，请稍后刷新。" />
        <el-alert v-else type="error" :closable="false" show-icon :title="reportDetail.errorCode || '报告生成失败'" />
      </el-card>

      <template v-if="reportDetail.status === 'SUCCESS' && reportDetail.report">
        <div class="ai-report-grid">
          <el-card>
            <template #header>AI 发现</template>
            <el-empty v-if="!reportDetail.report.findings?.length" description="暂无发现" :image-size="56" />
            <article v-for="(item, index) in reportDetail.report.findings" :key="`finding-${index}`" class="ai-report-item">
              <h3>{{ item.title }}</h3><p>{{ item.detail }}</p>
              <EvidenceChips :ids="item.evidenceIds" :evidence="evidenceById" @locate="locateEvidence" />
            </article>
          </el-card>

          <el-card>
            <template #header>风险提示</template>
            <el-empty v-if="!reportDetail.report.riskWarnings?.length" description="暂无风险提示" :image-size="56" />
            <article v-for="(item, index) in reportDetail.report.riskWarnings" :key="`warning-${index}`" class="ai-report-item ai-report-item--warning">
              <h3>{{ item.title }}</h3><p>{{ item.detail }}</p>
              <EvidenceChips :ids="item.evidenceIds" :evidence="evidenceById" @locate="locateEvidence" />
            </article>
          </el-card>
        </div>

        <el-card class="ai-report-section">
          <template #header>待验证实验假设</template>
          <el-empty v-if="!reportDetail.report.experimentHypotheses?.length" description="暂无待验证假设" :image-size="56" />
          <article v-for="(item, index) in reportDetail.report.experimentHypotheses" :key="`hypothesis-${index}`" class="ai-report-item ai-report-item--hypothesis">
            <el-tag type="warning" size="small">{{ item.status }}</el-tag>
            <p>{{ item.hypothesis }}</p>
            <EvidenceChips :ids="item.evidenceIds" :evidence="evidenceById" @locate="locateEvidence" />
          </article>
        </el-card>

        <el-card class="ai-report-section">
          <template #header>可信证据</template>
          <el-table :data="reportDetail.report.verifiedEvidence || []" size="small" max-height="360">
            <el-table-column prop="evidenceId" label="ID" width="70" />
            <el-table-column prop="type" label="类型" width="80" />
            <el-table-column label="服务端核验内容">
              <template #default="{ row }"><code>{{ evidenceText(row) }}</code></template>
            </el-table-column>
            <el-table-column label="定位" width="100" align="right">
              <template #default="{ row }">
                <el-button v-if="eventSequence(row)" type="primary" link @click="locateEvidence(row.evidenceId)">去回放</el-button>
                <span v-else class="ai-report-muted">-</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </template>
    </template>

    <el-empty v-else-if="taskId && !loading" description="选择一份报告，或生成新的 AI 复盘。" :image-size="90" />
  </section>
</template>

<script setup>
import { computed, defineComponent, h, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElTag } from 'element-plus'
import { createAiReport, getAiReport, listAiReports, regenerateAiReport } from '../api/core'

const EvidenceChips = defineComponent({
  props: { ids: Array, evidence: Object },
  emits: ['locate'],
  setup(props, { emit }) {
    return () => h('div', { class: 'ai-report-evidence-chips' }, (props.ids || []).map(id => h(ElTag, {
      key: id, size: 'small', effect: 'plain', type: props.evidence?.[id]?.type === 'EVENT' ? 'primary' : 'info',
      onClick: () => emit('locate', id)
    }, () => id)))
  }
})

const route = useRoute()
const router = useRouter()
const taskId = ref(Number(route.query.taskId) || 0)
const reports = ref([])
const selectedId = ref(Number(route.params.reportId) || 0)
const reportDetail = ref(null)
const loading = ref(false)
const detailLoading = ref(false)
const creating = ref(false)
const regenerating = ref(false)
const errorMessage = ref('')

const evidenceById = computed(() => Object.fromEntries((reportDetail.value?.report?.verifiedEvidence || []).map(item => [item.evidenceId, item])))
const overviewFacts = computed(() => {
  const performance = reportDetail.value?.report?.deterministicFacts?.performance || {}
  const overview = reportDetail.value?.report?.deterministicFacts?.overview || {}
  return [
    { label: '收益率', value: percent(performance.returnRate) },
    { label: '最大回撤', value: percent(performance.maxDrawdown) },
    { label: '成交记录', value: overview.fillCount ?? '-' },
    { label: '完成仓位', value: overview.completedPositionCount ?? '-' }
  ]
})

onMounted(() => { if (taskId.value) loadReports() })
watch(() => route.params.reportId, id => { if (id) openReport(Number(id), false) })

async function loadReports() {
  if (!taskId.value) return ElMessage.warning('请输入回测任务 ID')
  loading.value = true
  errorMessage.value = ''
  try {
    const { data } = await listAiReports(taskId.value)
    reports.value = data || []
    const target = selectedId.value || reports.value[0]?.id
    if (target) await openReport(target, false)
  } catch (error) {
    errorMessage.value = messageOf(error, '加载报告列表失败')
  } finally { loading.value = false }
}

async function createReport() {
  if (!taskId.value) return ElMessage.warning('请输入回测任务 ID')
  creating.value = true
  errorMessage.value = ''
  try {
    const { data } = await createAiReport(taskId.value)
    selectedId.value = data.reportId
    await router.replace({ name: 'ai-reports', params: { reportId: data.reportId }, query: { taskId: taskId.value } })
    await loadReports()
    ElMessage.success(data.reused ? '已打开可复用的 AI 复盘' : 'AI 复盘生成完成')
  } catch (error) {
    errorMessage.value = messageOf(error, '生成 AI 复盘失败')
  } finally { creating.value = false }
}

async function regenerate() {
  if (!reportDetail.value?.id) return
  regenerating.value = true
  try {
    const { data } = await regenerateAiReport(reportDetail.value.id)
    selectedId.value = data.reportId
    await router.replace({ name: 'ai-reports', params: { reportId: data.reportId }, query: { taskId: taskId.value } })
    await loadReports()
    ElMessage.success(`已创建 Attempt ${data.attemptNo}`)
  } catch (error) {
    errorMessage.value = messageOf(error, '重新生成失败')
  } finally { regenerating.value = false }
}

async function openReport(id, syncRoute = true) {
  if (!id) return
  selectedId.value = Number(id)
  detailLoading.value = true
  errorMessage.value = ''
  try {
    const { data } = await getAiReport(selectedId.value)
    reportDetail.value = data
    taskId.value = data.taskId
    if (syncRoute) await router.replace({ name: 'ai-reports', params: { reportId: id }, query: { taskId: data.taskId } })
  } catch (error) {
    errorMessage.value = messageOf(error, '加载报告详情失败')
  } finally { detailLoading.value = false }
}

function locateEvidence(id) {
  const sequence = eventSequence(evidenceById.value[id])
  if (!sequence) return ElMessage.info('该证据不对应单个策略事件。')
  router.push({ name: 'replay', params: { id: taskId.value }, query: { sequence } })
}

function eventSequence(evidence) { return evidence?.type === 'EVENT' ? evidence.detail?.sequence : null }
function evidenceText(evidence) { return Object.entries(evidence.detail || {}).map(([key, value]) => `${key}: ${value}`).join(' · ') || '-' }
function percent(value) { return Number.isFinite(Number(value)) ? `${Number(value).toFixed(2)}%` : '-' }
function formatTime(value) { return value ? new Date(value).toLocaleString() : '-' }
function statusType(status) { return status === 'SUCCESS' ? 'success' : status === 'GENERATING' ? 'warning' : 'danger' }
function messageOf(error, fallback) {
  const code = error.response?.data?.code
  if (code === 'REPLAY_NOT_AVAILABLE') return '该任务没有回放数据，无法生成可信 AI 复盘，请重新运行回测。'
  return error.response?.data?.message || code || error.message || fallback
}
</script>

<style scoped>
.ai-report-page { display: grid; gap: 20px; }
.ai-report-hero { overflow: hidden; background: linear-gradient(115deg, var(--qc-accent-soft), var(--qc-card-bg) 48%); }
.ai-report-hero__content, .ai-report-card-title, .ai-report-meta { display: flex; align-items: center; justify-content: space-between; gap: 14px; flex-wrap: wrap; }
.ai-report-hero h1 { margin: 4px 0 8px; font-size: 28px; color: var(--qc-text, #f8fafc); }
.ai-report-hero p { margin: 0; color: var(--qc-text-dim); }
.ai-report-hero__eyebrow { color: var(--qc-accent); font-size: 11px; font-weight: 700; letter-spacing: .18em; }
.ai-report-hero__actions { display: flex; align-items: center; gap: 10px; }
.ai-report-attempts { display: grid; gap: 8px; }
.ai-report-attempt { border: 1px solid var(--qc-border, #27313b); border-radius: 10px; background: var(--qc-fill-subtle); color: inherit; display: flex; align-items: center; gap: 12px; padding: 12px; text-align: left; cursor: pointer; }
.ai-report-attempt:hover, .ai-report-attempt.active { border-color: var(--qc-accent); background: rgba(56,189,248,.08); }
.ai-report-attempt small { display: block; color: var(--qc-text-dim); margin-top: 3px; }
.ai-report-attempt__status { width: 8px; height: 8px; border-radius: 50%; background: #94a3b8; }.is-success { background: #22c55e; }.is-generating { background: #38bdf8; box-shadow: 0 0 10px #38bdf8; }.is-failed { background: #ef4444; }
.ai-report-attempt__model { margin-left: auto; color: var(--qc-text-dim); font-size: 12px; }
.ai-report-summary h2 { margin: 0 0 8px; }.ai-report-summary__text { color: var(--qc-text-dim); line-height: 1.8; }
.ai-report-facts { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-top: 18px; }.ai-report-fact { padding: 12px; background: rgba(56,189,248,.06); border-radius: 8px; }.ai-report-fact span { display:block; color:var(--qc-text-dim); font-size:12px; }.ai-report-fact b { display:block; color:var(--qc-accent); font-size:20px; margin-top:4px; }
.ai-report-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; }.ai-report-section { margin-top: 20px; }
.ai-report-item { padding: 14px 0; border-bottom: 1px solid var(--qc-border, #27313b); }.ai-report-item:last-child { border-bottom: 0; }.ai-report-item h3 { margin: 0 0 8px; font-size: 15px; }.ai-report-item p { margin: 0 0 10px; color: var(--qc-text-dim); line-height: 1.7; }.ai-report-item--warning h3 { color: var(--qc-warning-text); }.ai-report-item--hypothesis { border-left: 2px solid #f59e0b; padding-left: 12px; }
.ai-report-evidence-chips { display: flex; flex-wrap: wrap; gap: 6px; }.ai-report-evidence-chips :deep(.el-tag) { cursor: pointer; }.ai-report-muted, .ai-report-card-title__hint { color: var(--qc-text-dim); font-size: 12px; }
.ai-report-alert { margin: 0; }
@media (max-width: 760px) { .ai-report-grid { grid-template-columns: 1fr; }.ai-report-facts { grid-template-columns: repeat(2, minmax(0, 1fr)); }.ai-report-hero__actions { width: 100%; }.ai-report-hero__actions :deep(.el-input-number) { flex: 1; } }
</style>
