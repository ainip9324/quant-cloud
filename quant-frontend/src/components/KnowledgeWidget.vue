<template>
  <aside v-if="visible" class="knowledge-widget" aria-label="策略知识问答" :style="viewportStyle">
    <section
      v-if="open"
      id="knowledge-panel"
      ref="panel"
      tabindex="-1"
      class="knowledge-panel"
      role="dialog"
      aria-labelledby="knowledge-title"
      @keydown.esc.stop.prevent="closePanel"
    >
      <header class="knowledge-header">
        <div>
          <h2 id="knowledge-title">策略知识问答</h2>
          <p>了解交易机制、策略、风险和回测方法</p>
        </div>
        <button class="knowledge-close" type="button" aria-label="关闭问答" @click="closePanel">×</button>
      </header>

      <div ref="feed" class="knowledge-feed">
        <div class="knowledge-faqs">
          <h3>常问问题</h3>
          <button
            v-for="faq in KNOWLEDGE_FAQS"
            :key="faq.question"
            class="knowledge-faq"
            type="button"
            data-testid="knowledge-faq"
            @click="append(faq)"
          >{{ faq.question }}</button>
        </div>
        <div role="log" aria-live="polite" aria-relevant="additions text">
          <article v-for="entry in entries" :key="entry.id" class="knowledge-exchange">
            <p class="knowledge-question">{{ entry.question }}</p>
            <p v-if="entry.status === 'GENERAL'" class="knowledge-status">通用知识 · 模型讲解</p>
            <p class="knowledge-answer" data-testid="knowledge-answer">{{ entry.answer }}</p>
            <details v-for="source in entry.sources" :key="source.id" class="knowledge-source">
              <summary>{{ source.title }} · {{ source.version }}</summary>
              <p>{{ source.category }}</p>
              <p>{{ source.content }}</p>
              <a v-if="source.url?.startsWith('https://')" :href="source.url" target="_blank" rel="noopener noreferrer">查看出处</a>
            </details>
          </article>
          <p v-if="pending" class="knowledge-status">正在整理回答…</p>
        </div>
      </div>

      <form class="knowledge-form" data-testid="knowledge-form" @submit.prevent="send()">
        <p v-if="error" class="knowledge-error" role="alert">{{ error }}</p>
        <button v-if="guestRetry" type="button" class="knowledge-faq" :disabled="pending" @click="send(true)">
          以访客身份提问
        </button>
        <label for="knowledge-question">输入交易知识问题</label>
        <div class="knowledge-input-row">
          <textarea
            id="knowledge-question"
            :value="draft"
            rows="2"
            :disabled="pending"
            data-testid="knowledge-input"
            :placeholder="`例如：${exampleQuestion}`"
            @input="draft = $event.target.value"
            @keydown.enter="onEnter"
          ></textarea>
          <button class="knowledge-send" type="submit" :disabled="pending || !draft.trim()">发送</button>
        </div>
        <p class="knowledge-status">
          <span v-if="remaining !== null">今日可用 {{ remaining }} 次</span>
          <span v-else>每题最多 {{ QUESTION_LIMIT }} 字</span>
          <span v-if="resetLabel"> · {{ resetLabel }} 刷新额度</span>
        </p>
      </form>
    </section>

    <button
      ref="launcher"
      class="knowledge-launcher"
      type="button"
      :aria-expanded="open"
      aria-controls="knowledge-panel"
      data-testid="knowledge-launcher"
      @click="togglePanel"
    >
      <svg viewBox="0 0 24 24" width="20" height="20" fill="none" aria-hidden="true">
        <path d="M5 4h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H10l-5 4v-4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" />
        <path d="M7 9h10M7 13h7" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
      </svg>
      <span>策略问答</span>
    </button>
  </aside>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute } from 'vue-router'
import { askKnowledge } from '../api/knowledge.js'
import { KNOWLEDGE_FAQS } from '../utils/knowledgeFaqs.js'

const QUESTION_LIMIT = 300
const HISTORY_LIMIT = 20

// 示例用于占位提示。常问问题的按钮和静态答案来自 KNOWLEDGE_FAQS。
const QUESTION_EXAMPLES = [
  ...KNOWLEDGE_FAQS.map(faq => faq.question),
  '永续合约的资金费率是什么？',
  '逐仓和全仓有什么区别？',
  '手续费和滑点为什么会影响回测？',
  '最大回撤是怎么算的？'
]

const route = useRoute()
const visible = computed(() => route.name === 'landing' || Boolean(route.meta.workspace))

const open = ref(false)
const draft = ref('')
const exampleQuestion = ref('')
const entries = ref([])
// AbortController 同时用于取消和判断请求是否过期，按对象身份比较。
const pending = shallowRef(null)
const error = ref('')
const guestRetry = ref(false)
const remaining = ref(null)
const resetAt = ref(null)

const launcher = ref(null)
const panel = ref(null)
const feed = ref(null)
const viewportStyle = shallowRef(null)

let nextId = 1
let session = localStorage.getItem('quant_token')

const resetLabel = computed(() => {
  if (!resetAt.value) return ''
  const date = new Date(resetAt.value)
  return Number.isNaN(date.getTime()) ? '' : new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23'
  }).format(date)
})

function syncSession() {
  const token = localStorage.getItem('quant_token')
  if (token === session) return

  // 登录身份变化时，草稿、历史和额度都随上一身份一并清除。
  session = token
  pending.value?.abort()
  pending.value = null
  entries.value = []
  draft.value = ''
  error.value = ''
  guestRetry.value = false
  remaining.value = null
  resetAt.value = null
}

// 本标签页通过路由检查身份，其他标签页通过 storage 事件同步。
watch(() => route.fullPath, syncSession)

function syncViewport() {
  const viewport = window.visualViewport
  if (!viewport) return

  // 移动端键盘会缩小可视区域，用两种视口的底部差值把输入框抬到键盘上方。
  const inset = Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop)
  viewportStyle.value = inset > 1 ? {
    '--knowledge-keyboard': `${inset}px`,
    '--knowledge-viewport': `${viewport.height}px`
  } : null
}

onMounted(() => {
  window.addEventListener('storage', syncSession)
  window.visualViewport?.addEventListener('resize', syncViewport)
  window.visualViewport?.addEventListener('scroll', syncViewport)
  syncViewport()
})
onBeforeUnmount(() => {
  window.removeEventListener('storage', syncSession)
  window.visualViewport?.removeEventListener('resize', syncViewport)
  window.visualViewport?.removeEventListener('scroll', syncViewport)
  pending.value?.abort()
  pending.value = null
})

async function togglePanel() {
  if (open.value) return closePanel()
  const examples = QUESTION_EXAMPLES.filter(question => question !== exampleQuestion.value)
  exampleQuestion.value = examples[Math.floor(Math.random() * examples.length)]
  open.value = true
  await nextTick()
  panel.value?.focus?.()
}

async function closePanel() {
  open.value = false
  await nextTick()
  launcher.value?.focus?.()
}

async function append(entry) {
  entries.value = [...entries.value.slice(-(HISTORY_LIMIT - 1)), { ...entry, id: nextId++ }]
  await nextTick()
  if (feed.value) feed.value.scrollTop = feed.value.scrollHeight
}

function onEnter(event) {
  // Shift+Enter 留给换行，组合输入期间的回车留给输入法选词。229 兼容部分浏览器。
  if (event.shiftKey || event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  send()
}

async function send(asGuest = false) {
  syncSession()
  if (pending.value) return
  const question = draft.value.trim()
  if (!question || Array.from(question).length > QUESTION_LIMIT) {
    error.value = `请输入 1 至 ${QUESTION_LIMIT} 字的问题。`
    return
  }

  error.value = ''
  guestRetry.value = false
  const request = new AbortController()
  const token = asGuest ? null : session
  pending.value = request

  try {
    const result = await askKnowledge(question, { token, signal: request.signal })
    syncSession()
    // 即使取消时响应已经到达，也不能把上一身份的内容写进当前会话。
    if (pending.value !== request) return
    remaining.value = result.remaining ?? null
    resetAt.value = result.resetAt ?? null
    draft.value = ''
    await append({
      question,
      status: result.status,
      answer: result.answer,
      sources: result.sources ?? []
    })
  } catch (failure) {
    syncSession()
    if (pending.value !== request) return
    const status = failure.response?.status
    const details = failure.response?.data

    if (status === 401 && token) {
      // 由用户决定是否改用访客额度，保留问题供再次提交。
      error.value = '登录状态已过期，可以重新登录或使用访客额度。'
      guestRetry.value = true
    } else if (status === 429 && details?.code === 'KNOWLEDGE_QUOTA_EXHAUSTED') {
      error.value = '今日问答额度已用完。'
      remaining.value = 0
      resetAt.value = details.resetAt
    } else {
      error.value = details?.message || '暂时无法回答，请稍后再试。'
    }
  } finally {
    // 旧请求的 finally 也可能晚到，只清理本请求的等待状态。
    if (pending.value === request) pending.value = null
  }
}
</script>

<style scoped>
.knowledge-widget {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 1200;
  color: #e9f3fa;
}
.knowledge-widget.knowledge-light {
  --knowledge-panel: #fff;
  --knowledge-border: #d5dee9;
  --knowledge-muted: #526274;
  --knowledge-control: #eff4f9;
  --knowledge-question: #e1edff;
  --knowledge-accent: #1d4ed8;
  --knowledge-action-text: #fff;
  --knowledge-error: #a92121;
  --knowledge-shadow: rgb(25 46 75 / 18%);
  color: #182334;
  color-scheme: light;
}
.knowledge-widget button {
  font: inherit;
  cursor: pointer;
}
.knowledge-widget button:disabled {
  cursor: default;
  opacity: .55;
}
.knowledge-widget :focus-visible {
  outline: 2px solid var(--knowledge-accent, #7de4d6);
  outline-offset: 3px;
}
.knowledge-launcher, .knowledge-send {
  background: var(--knowledge-accent, #82e4d4);
  color: var(--knowledge-action-text, #102725);
  border: 0;
  font-weight: 700;
}
.knowledge-launcher {
  display: flex;
  align-items: center;
  gap: 8px;
  border-radius: 28px;
  padding: 12px 17px;
  margin-left: auto;
  box-shadow: 0 8px 24px var(--knowledge-shadow, #0007);
}
.knowledge-panel {
  width: min(390px, calc(100vw - 48px));
  height: min(620px, calc(100dvh - 105px));
  display: flex;
  flex-direction: column;
  margin-bottom: 12px;
  background: var(--knowledge-panel, #101d2b);
  border: 1px solid var(--knowledge-border, #34465b);
  border-radius: 20px;
  box-shadow: 0 18px 45px var(--knowledge-shadow, #0009);
  overflow: hidden;
}
.knowledge-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 16px;
  border-bottom: 1px solid var(--knowledge-border, #34465b);
}
.knowledge-header h2 {
  margin: 0;
  font-size: 16px;
}
.knowledge-header p, .knowledge-status {
  margin: 5px 0 0;
  font-size: 12px;
  color: var(--knowledge-muted, #aebfce);
}
.knowledge-close {
  border: 0;
  border-radius: 8px;
  background: var(--knowledge-control, #26394c);
  color: inherit;
  font-size: 22px !important;
  min-width: 32px;
}
.knowledge-feed {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 16px;
  overscroll-behavior: contain;
}
.knowledge-faqs {
  display: grid;
  justify-items: start;
  gap: 7px;
}
.knowledge-faqs h3 {
  margin: 0 0 3px;
  font-size: 13px;
}
.knowledge-faq {
  border: 1px solid var(--knowledge-border, #416074);
  border-radius: 8px;
  background: var(--knowledge-control, #1b3042);
  color: var(--knowledge-muted, #dcf7f3);
  padding: 6px 9px;
  text-align: left;
}
.knowledge-exchange {
  margin-top: 14px;
  font-size: 14px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.knowledge-question, .knowledge-answer {
  padding: 9px 11px;
  margin: 6px 0;
  border-radius: 11px;
  white-space: pre-wrap;
}
.knowledge-question {
  background: var(--knowledge-question, #234638);
  margin-left: 24px;
}
.knowledge-answer {
  background: var(--knowledge-control, #1b2a39);
  margin-right: 16px;
}
.knowledge-source {
  margin: 5px 0;
  padding: 5px 9px;
  border-radius: 6px;
  border: 1px solid var(--knowledge-border, #34465b);
  font-size: 12px;
}
.knowledge-source summary {
  cursor: pointer;
}
.knowledge-source p {
  margin: 6px 0;
}
.knowledge-source a {
  color: var(--knowledge-accent, #8fe6d6);
}
.knowledge-form {
  border-top: 1px solid var(--knowledge-border, #34465b);
  padding: 12px 16px;
}
.knowledge-form label {
  display: block;
  font-size: 13px;
  margin-bottom: 6px;
}
.knowledge-error {
  color: var(--knowledge-error, #ffb4ac);
  margin: 0 0 7px;
  font-size: 13px;
}
.knowledge-input-row {
  display: flex;
  align-items: stretch;
  gap: 8px;
}
.knowledge-input-row textarea {
  min-width: 0;
  flex: 1;
  resize: none;
  border: 1px solid var(--knowledge-border, #416074);
  border-radius: 9px;
  background: var(--knowledge-control, #0b1621);
  color: inherit;
  padding: 8px;
  font: inherit;
  font-size: 16px;
}
.knowledge-send {
  border-radius: 9px;
  padding: 0 13px;
}
@media (min-width: 761px) and (pointer: fine) {
  .knowledge-on-landing .knowledge-panel {
    height: min(620px, max(160px, calc(100dvh - 160px)));
  }
}
/* 键盘弹出时使用可视视口的高度和偏移量。 */
@media (pointer: coarse), (max-width: 560px) {
  .knowledge-widget {
    right: 12px;
    bottom: calc(max(12px, env(safe-area-inset-bottom)) + var(--knowledge-keyboard, 0px));
  }
  .knowledge-panel {
    width: min(390px, calc(100vw - 24px));
    height: min(620px, max(120px, calc(var(--knowledge-viewport, 100dvh) - 80px)));
    overflow: auto;
  }
}
</style>
