<template>
  <section class="briefing" aria-labelledby="market-briefing-title">
    <header class="briefing-header">
      <div>
        <h2 id="market-briefing-title">交易简报</h2>
        <p>管理员发布 · 仅供研究参考</p>
      </div>
      <div class="briefing-actions">
        <button type="button" :disabled="loading || Boolean(draft)" @click="load()">刷新简报</button>
        <button v-if="canEdit && briefing && !draft" type="button" :disabled="loading" @click="beginEdit">
          编辑简报
        </button>
      </div>
    </header>

    <p v-if="loading && !briefing" role="status">正在加载简报…</p>
    <p v-if="loadError" class="briefing-error" role="alert">{{ loadError }}</p>
    <template v-if="briefing">
      <div v-if="publishedSections.length" class="briefing-sections">
        <article v-for="section in publishedSections" :key="section.key">
          <h3>{{ section.label }}</h3>
          <p>{{ briefing[section.key] }}</p>
        </article>
      </div>
      <p v-else class="briefing-muted">管理员尚未发布交易简报。</p>
      <p v-if="publishedSections.length && briefing.updatedAt" class="briefing-muted">
        更新于 <time :datetime="new Date(briefing.updatedAt).toISOString()">
          {{ new Date(briefing.updatedAt).toLocaleString('zh-CN', { hour12: false }) }}
        </time>
      </p>
    </template>

    <form v-if="canEdit && draft" class="briefing-editor" @submit.prevent="publish">
      <label v-for="section in sections" :key="section.key">
        {{ section.label }}
        <textarea v-model="draft[section.key]" :maxlength="maxLength" :disabled="saving || loading"
          rows="3" :placeholder="section.placeholder"></textarea>
      </label>
      <p class="briefing-muted">每栏最多 {{ maxLength }} 字，全部留空可撤下简报。</p>
      <p v-if="saveError" class="briefing-error" role="alert">{{ saveError }}</p>
      <div class="briefing-actions">
        <button v-if="conflict" type="button" :disabled="loading" @click="load(true)">载入新版并替换草稿</button>
        <button class="briefing-primary" type="submit" :disabled="saving || loading || conflict">
          {{ saving ? '保存中…' : '保存并发布' }}
        </button>
        <button type="button" :disabled="saving || loading" @click="cancelEdit">取消</button>
      </div>
    </form>
    <p v-if="published" class="briefing-success" role="status">简报已更新。</p>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { getMarketBriefing, publishMarketBriefing } from '../api/marketWorkspace.js'

const props = defineProps({ canEdit: Boolean })
const maxLength = 1000
const sections = [
  { key: 'outlook', label: '交易观点', placeholder: '记录近期交易思路或需要关注的价位' },
  { key: 'events', label: '近期事件', placeholder: '记录重要日程、事件时间或关注点' },
  { key: 'risks', label: '风险提醒', placeholder: '记录波动、流动性或其他交易风险' }
]

const briefing = ref(null)
const draft = ref(null)
const loading = ref(false)
const saving = ref(false)
const conflict = ref(false)
const published = ref(false)
const loadError = ref('')
const saveError = ref('')
const publishedSections = computed(() => sections.filter(section => briefing.value?.[section.key]))
const requests = new AbortController()

function cancelEdit() {
  draft.value = null
  saveError.value = ''
  conflict.value = false
}

function beginEdit() {
  if (!props.canEdit || !briefing.value) return
  const { outlook, events, risks, revision } = briefing.value
  draft.value = { outlook, events, risks, expectedRevision: revision }
  saveError.value = ''
  conflict.value = false
  published.value = false
}

async function load(replaceDraft = false) {
  if (loading.value || saving.value) return
  loading.value = true
  loadError.value = ''
  published.value = false
  try {
    const current = await getMarketBriefing(requests.signal)
    if (requests.signal.aborted) return
    briefing.value = current
    if (replaceDraft) beginEdit()
  } catch {
    if (!requests.signal.aborted) {
      loadError.value = briefing.value
        ? '简报刷新失败，仍显示上一次加载的内容。'
        : '暂时无法读取简报，请稍后刷新。'
    }
  } finally {
    loading.value = false
  }
}

async function publish() {
  if (!props.canEdit || !draft.value || saving.value || loading.value || conflict.value) return
  saving.value = true
  saveError.value = ''
  try {
    const current = await publishMarketBriefing({ ...draft.value }, requests.signal)
    if (requests.signal.aborted) return
    briefing.value = current
    loadError.value = ''
    cancelEdit()
    published.value = true
  } catch (error) {
    if (requests.signal.aborted) return
    conflict.value = error.response?.status === 409
    saveError.value = conflict.value
      ? '简报已有新版，草稿已保留。请先复制要保留的文字，再载入新版。'
      : '保存失败，草稿已保留。请检查每栏长度或稍后重试。'
  } finally {
    saving.value = false
  }
}

onMounted(() => load())
onBeforeUnmount(() => requests.abort())
</script>

<style scoped>
.briefing {
  margin-top: 16px;
  padding: 18px;
  border: 1px solid var(--qc-border);
  border-radius: 14px;
  background: var(--qc-surface);
}

.briefing-header,
.briefing-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.briefing-header {
  justify-content: space-between;
  margin-bottom: 14px;
}

h2, h3 {
  margin: 0;
  font-size: 15px;
}

.briefing-header p,
.briefing-muted {
  margin: 6px 0 0;
  color: var(--qc-secondary);
  font-size: 12px;
}

.briefing-sections {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 230px), 1fr));
  gap: 12px;
}

.briefing-sections article {
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--qc-border);
  border-radius: 10px;
  background: var(--qc-fill-subtle);
}

.briefing-sections p {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.65;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.briefing-editor {
  display: grid;
  gap: 12px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--qc-border);
}

.briefing-editor label {
  display: grid;
  gap: 6px;
  font-size: 13px;
}

textarea,
button {
  padding: 8px 11px;
  border: 1px solid var(--qc-control-border);
  border-radius: 7px;
  background: var(--qc-control);
  color: var(--qc-text);
  font: inherit;
}

textarea {
  box-sizing: border-box;
  width: 100%;
  resize: vertical;
}

button {
  font-size: 12px;
  cursor: pointer;
}

button:hover {
  border-color: var(--qc-accent);
}

button:disabled {
  opacity: .5;
  cursor: default;
}

.briefing-primary {
  background: var(--qc-accent);
  color: var(--qc-on-accent);
}

.briefing-error,
.briefing-success {
  margin: 6px 0;
  font-size: 13px;
}

.briefing-error { color: var(--qc-danger-text); }
.briefing-success { color: var(--qc-positive-text); }
</style>
