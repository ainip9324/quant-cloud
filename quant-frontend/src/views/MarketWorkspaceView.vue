<template>
  <section class="market-workspace">
    <header class="workspace-header">
      <div><p class="eyebrow">MARKET WORKSPACE</p><h1>实时盘面 <span>USDT 永续</span></h1><p class="muted">观察价格与区域的相对位置，辅助人工研究。</p></div>
      <div class="header-actions"><button @click="showAccount = !showAccount">我的账户</button></div>
    </header>
    <aside v-if="showAccount" class="panel account" aria-label="我的账户">{{ account?.username || account?.email || '当前账户' }} · {{ isAdmin ? '管理员' : '观察者（只读）' }} · <a href="/account">修改密码</a></aside>

    <div class="toolbar panel">
      <label>交易对<select v-model="symbol" :disabled="saving" data-testid="symbol"><option>BTCUSDT</option><option>ETHUSDT</option></select></label>
      <label>分析周期<select v-model="interval" :disabled="saving" data-testid="interval"><option v-for="item in INTERVALS.USDT_FUTURES" :key="item.value" :value="item.value">{{ item.label }}</option></select></label>
      <div class="quote"><strong>{{ latestPrice }}</strong><small>市场时间 {{ time(snapshot?.marketTime) }}</small></div>
      <div class="connection" :class="{ live: effectiveStatus === 'LIVE' }" role="status"><span class="dot"></span>{{ STATUS_LABELS[effectiveStatus] }}</div>
      <button @click="restart" :disabled="saving">重新加载</button>
    </div>
    <ZoneAlertPanel v-if="account?.id" :key="account.id" :user-id="account.id" :zones="snapshot?.zones || []">
      <ZoneAlertSettings />
    </ZoneAlertPanel>
    <p v-if="connection.state === 'UNAVAILABLE'" class="notice" role="alert">实时盘面尚未启用或暂时不可用，将自动重试。历史图和旧状态不代表当前行情。</p>
    <p v-else-if="effectiveStatus !== 'LIVE'" class="notice" role="status">{{ snapshot ? '当前数据未处于实时状态。保留最后图形供参考，区域角色暂不作为当前观察。' : '正在加载盘面…' }}</p>
    <p v-if="snapshot?.historyStatus !== 'READY' && snapshot" class="notice">{{ snapshot.historyStatus === 'STALE' ? '历史 K 线尚未补齐，已保留最近可用数据。' : '历史 K 线暂不可用，正在重试。' }}</p>

    <div class="workspace-grid">
      <section class="panel chart-panel">
        <div class="panel-heading">
          <h2>{{ symbol }} <small>{{ interval }}</small></h2>
          <ChartIndicators v-model="showEma" />
          <div v-if="isAdmin" class="drawing-tools">
            <div class="shape-switch" role="group" aria-label="绘图工具">
              <button type="button" :aria-pressed="!drawChannel" :disabled="drawing || saving"
                @click="drawChannel = false" data-testid="draw-rectangle">
                <svg viewBox="0 0 20 20" aria-hidden="true"><rect x="3" y="5" width="14" height="10" rx="1.5" /><path d="M3 10h14" stroke-dasharray="2 2" /></svg>
                水平区间
              </button>
              <button type="button" :aria-pressed="drawChannel" :disabled="drawing || saving"
                @click="drawChannel = true" data-testid="draw-channel">
                <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M3 10 17 4v6L3 16Z" /><path d="m3 13 14-6" stroke-dasharray="2 2" /></svg>
                平行通道
              </button>
            </div>
            <button type="button" class="draw-action" :class="{ active: drawing }" :aria-pressed="drawing" :disabled="!canDraw"
              @click="drawing = !drawing" data-testid="draw-zone">
              <svg v-if="!drawing" viewBox="0 0 20 20" aria-hidden="true"><path d="m12 4 4 4M4 12l9-9a1.4 1.4 0 0 1 2 0l2 2a1.4 1.4 0 0 1 0 2l-9 9-5 1Z" /></svg>
              <svg v-else viewBox="0 0 20 20" aria-hidden="true"><path d="m5 5 10 10M15 5 5 15" /></svg>
              {{ drawing ? '取消绘制' : '开始绘制' }}
            </button>
          </div>
        </div>
        <p v-if="drawing" class="chart-footnote" role="status">{{ drawChannel ? '前两点确定斜边，第三点确定通道宽度。' : '点击矩形两个对角，选定价格与起止时间。' }} 按 Esc 退出绘制。</p>
        <MarketWorkspaceChart :snapshot="chartSnapshot" :draft="preview" :drawing="drawing" :channel="drawChannel"
          :editable="canDraw" :selected-id="draft?.id" :show-ema="showEma"
          @select-zone="selectChartZone" @adjust-zone="adjustChartZone" @archive-zone="archiveChartZone" @cancel-edit="cancelDraft"
          @draw-range="applyDrawnRange" @cancel-drawing="drawing = false" />
        <p v-if="isAdmin && !drawing && draft?.id" class="chart-footnote" role="status">
          {{ archiveId === draft.id ? '再按一次 Delete 确认归档。Esc 取消。' : '拖动图形或控制点调整，保存后生效。Delete 归档，Esc 取消编辑。' }}
        </p>
        <p class="chart-footnote">实线为已保存区域，蓝色虚线为未保存预览，灰色为停用或待观察。已加载 {{ snapshot?.candles.length || 0 }} 根历史 K 线，最多 {{ WORKSPACE_CANDLE_LIMIT }} 根；可拖动或缩放查看更早行情。</p>
        <p v-if="showEma" class="chart-footnote">EMA20 按当前图表已收盘 K 线计算。</p>
      </section>
      <aside class="panel zones-panel">
        <div class="panel-heading"><h2>交易区域 <small>{{ snapshot?.zones.length || 0 }}</small></h2><button v-if="isAdmin" @click="newDraft" :disabled="saving" data-testid="new-zone">新增区域</button></div>
        <p v-if="!snapshot?.zones.length" class="empty">{{ isAdmin ? '尚无区域，可在图表上绘制。' : '管理员尚未设置共享区域。' }}</p>
        <article v-for="zone in snapshot?.zones || []" :key="zone.id" class="zone-card">
          <div class="zone-heading"><strong>#{{ zone.id }} <small>v{{ zone.revision }} · {{ zone.endLowerPrice != null ? '平行通道' : '水平区间' }}</small></strong><span class="zone-state">{{ zone.lifecycle === 'DISABLED' ? '已停用' : expired(zone) ? '已失效' : current(zone) ? STATE_LABELS[zone.observation.state] : '待观察' }}</span></div>
          <p class="price-range">{{ zonePrice.format(zone.lowerPrice) }} — {{ zonePrice.format(zone.upperPrice) }}</p>
          <p class="muted">{{ current(zone) ? ROLE_LABELS[zone.observation.role] : '暂无当前角色' }} · 生效 {{ time(zone.effectiveFrom) }}</p>
          <p class="muted">失效 {{ zone.effectiveUntil ? time(zone.effectiveUntil) : '向右延伸' }}<span v-if="zone.endLowerPrice != null"> · 终点下沿 {{ zone.endLowerPrice }}</span></p>
          <p v-if="zone.note" class="zone-note">{{ zone.note }}</p>
          <div v-if="isAdmin" class="zone-actions">
            <button @click="edit(zone)" :disabled="saving" :data-testid="`edit-${zone.id}`">编辑</button>
            <button @click="lifecycle(zone, zone.lifecycle === 'ACTIVE' ? 'disable' : 'enable')" :disabled="saving">{{ zone.lifecycle === 'ACTIVE' ? '停用' : '启用' }}</button>
            <button @click="lifecycle(zone, 'archive')" :disabled="saving" class="danger">{{ archiveId === zone.id ? '确认归档' : '归档' }}</button>
          </div>
        </article>

        <form v-if="isAdmin && draft" ref="zoneForm" class="zone-form" @submit.prevent="save" data-testid="zone-form">
          <h3>{{ draft.id ? `编辑 #${draft.id} · v${draft.revision}` : '新建交易区域' }}</h3>
          <label>下沿<input v-model="draft.lowerPrice" inputmode="decimal" required autocomplete="off" data-testid="lower" /></label>
          <label>上沿<input v-model="draft.upperPrice" inputmode="decimal" required autocomplete="off" data-testid="upper" /></label>
          <label>生效时间（本地）<input v-model="draft.effectiveFrom" type="datetime-local" step="0.001" required data-testid="effective" /></label>
          <label>失效时间（本地）<input v-model="draft.effectiveUntil" type="datetime-local" step="0.001" :required="draft.endLowerPrice != null" data-testid="until" /></label>
          <p v-if="draft.endLowerPrice == null" class="muted">失效时间留空则向右延伸。</p>
          <label v-if="draft.endLowerPrice != null">终点下沿<input v-model="draft.endLowerPrice" inputmode="decimal" required data-testid="end-lower" /></label>
          <label>备注<textarea v-model="draft.note" maxlength="500" rows="2" data-testid="note"></textarea></label>
          <p v-if="previewError" class="muted">{{ previewError }}</p>
          <p v-if="conflict" class="notice" role="alert">区域已被修改。草稿已保留，请载入最新版本后再编辑保存。</p>
          <button v-if="conflict" type="button" @click="reloadDraft" :disabled="saving">用最新版本替换草稿</button>
          <div class="form-actions"><button class="primary" type="submit" :disabled="saving || drawing || conflict || !preview" data-testid="save-zone">{{ saving ? '保存中…' : '保存区域' }}</button><button type="button" @click="cancelDraft" :disabled="saving">取消</button></div>
        </form>
        <p v-else-if="!isAdmin" class="readonly">只读盘面 · 区域由管理员维护</p>
      </aside>
    </div>

    <MarketBriefingPanel :can-edit="isAdmin" />

    <section class="panel events-panel">
      <div class="panel-heading"><h2>区域事件</h2><span class="muted">最新事件优先 · 每页 10 条</span></div>
      <p v-if="eventError" role="alert">{{ eventError }} <button @click="loadEvents()">重试</button></p>
      <p v-else-if="eventsLoading && !events" role="status">正在加载区域事件…</p>
      <p v-else-if="events?.total === 0" class="empty">暂无状态变化事件。首次观察、断线恢复及修改区域会建立基线，不记录穿越事件。</p>
      <ol class="timeline"><li v-for="event in events?.records || []" :key="event.id">
        <time>{{ time(event.marketTime) }}</time><div><strong>#{{ event.zoneId }} · v{{ event.zoneRevision }}</strong><p>{{ STATE_LABELS[event.fromState] || event.fromState }} → {{ STATE_LABELS[event.toState] || event.toState }} <span class="muted">@ {{ event.observedPrice }}</span></p><small class="muted">当时区域 {{ event.lowerPrice }} — {{ event.upperPrice }}<span v-if="event.note"> · {{ event.note }}</span></small></div>
      </li></ol>
      <div v-if="events?.total > 0" class="zone-actions" aria-label="区域事件分页">
        <button :disabled="eventsLoading || eventPage === 1" @click="goToEventPage(eventPage - 1)">上一页</button>
        <span>第 {{ eventPage }} / {{ eventPages }} 页</span>
        <button :disabled="eventsLoading || eventPage >= eventPages" @click="goToEventPage(eventPage + 1)">下一页</button>
      </div>
    </section>
    <p v-if="message" class="feedback" role="alert" data-testid="feedback">{{ message }}</p>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import MarketWorkspaceChart from '../components/MarketWorkspaceChart.vue'
import ChartIndicators from '../components/ChartIndicators.vue'
import ZoneAlertPanel from '../components/ZoneAlertPanel.vue'
import ZoneAlertSettings from '../components/ZoneAlertSettings.vue'
import MarketBriefingPanel from '../components/MarketBriefingPanel.vue'
import { getMe } from '../api/core.js'
import { changeZoneLifecycle, connectWorkspace, createZone, getWorkspace, getZoneEvents, updateZone } from '../api/marketWorkspace.js'
import { INTERVALS } from '../utils/marketOptions.js'
import { WORKSPACE_CANDLE_LIMIT } from '../utils/marketWorkspaceLimits.js'
import { workspaceSelections } from '../utils/workspaceSelections.js'
import { localDateTime, ROLE_LABELS, STATE_LABELS, STATUS_LABELS, workspaceStatus, zoneCommand } from '../utils/marketWorkspace.js'

const previous = workspaceSelections.market
const symbol = ref(previous?.symbol ?? 'BTCUSDT'), interval = ref(previous?.interval ?? '1h')
const snapshot = ref(null), account = ref(null)
const connection = ref({ state: 'CONNECTING' }), receivedAt = ref(0), now = ref(Date.now())
const draft = ref(null), saving = ref(false), conflict = ref(false), archiveId = ref(null), message = ref(''), showAccount = ref(false)
const drawing = ref(false), zoneForm = ref(null)
const drawChannel = ref(previous?.drawChannel ?? false)
const showEma = ref(previous?.showEma ?? false)
const scope = computed(() => ({ marketType: 'USDT_FUTURES', symbol: symbol.value, interval: interval.value }))
const zonePrice = new Intl.NumberFormat('en-US', { useGrouping: false, minimumFractionDigits: 2, maximumFractionDigits: 2 })
const events = ref(null)
const eventPage = ref(1)
const eventsLoading = ref(false)
const eventError = ref('')
const eventPages = computed(() => Math.max(1, Math.ceil((events.value?.total ?? 0) / 10)))
let eventRequest

async function loadEvents() {
  eventRequest?.abort()
  const request = new AbortController()
  eventRequest = request
  eventsLoading.value = true
  eventError.value = ''
  try {
    const data = await getZoneEvents(scope.value, eventPage.value, request.signal)
    if (request.signal.aborted) return
    if (eventPage.value > Math.max(1, Math.ceil(data.total / 10))) {
      await goToEventPage(1)
      return
    }
    events.value = data
  } catch {
    if (!request.signal.aborted) eventError.value = '无法加载区域事件。'
  } finally {
    if (eventRequest === request) eventsLoading.value = false
  }
}

function goToEventPage(page) {
  eventPage.value = page
  return loadEvents()
}
const isAdmin = computed(() => account.value?.role === 'ADMIN')
const canDraw = computed(() => isAdmin.value && !saving.value && !conflict.value &&
  Boolean(snapshot.value?.candles.length || snapshot.value?.currentCandle))
const quoteFormat = new Intl.NumberFormat('en-US', { useGrouping: false, minimumFractionDigits: 1, maximumFractionDigits: 1 })
const latestPrice = computed(() => {
  const price = snapshot.value?.lastPrice
  return price && Number.isFinite(Number(price)) ? quoteFormat.format(price) : '—'
})
const effectiveStatus = computed(() => workspaceStatus(snapshot.value, connection.value.state, receivedAt.value, now.value))
const chartSnapshot = computed(() => snapshot.value ? { ...snapshot.value, zones: snapshot.value.zones.map(zone => ({ ...zone,
  observation: zone.observation ? { ...zone.observation, current: current(zone) } : null })) } : null)
const preview = computed(() => { try { return draft.value ? zoneCommand(draft.value, scope.value) : null } catch { return null } })
const previewError = computed(() => { try { if (draft.value) zoneCommand(draft.value, scope.value); return '' } catch (error) { return error.message } })
let client, clockTimer, refreshController, generation = 0, disposed = false
function time(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function expired(zone) { return zone.effectiveUntil != null && now.value >= zone.effectiveUntil }
function current(zone) { return !expired(zone) && effectiveStatus.value === 'LIVE' && zone.lifecycle === 'ACTIVE' && zone.observation?.current && zone.observation.stateRevision === zone.revision }
function apply(data) {
  if (snapshot.value?.streamId === data.streamId && snapshot.value.version >= data.version) return
  snapshot.value = data; receivedAt.value = Date.now()
  if (draft.value?.id) {
    const latest = data.zones.find(z => z.id === draft.value.id)
    if (!latest || latest.revision !== draft.value.revision) conflict.value = true
  }
}
function restart() {
  drawing.value = false
  client?.close(); refreshController?.abort(); const active = ++generation
  connection.value = { state: 'CONNECTING' }
  client = connectWorkspace({ ...scope.value }, {
    onSnapshot: data => { if (!disposed && active === generation) apply(data) },
    onStatus: status => { if (!disposed && active === generation) connection.value = status }
  })
}
function newDraft() {
  if (!isAdmin.value) return
  drawing.value = false
  archiveId.value = null
  conflict.value = false
  message.value = ''
  draft.value = { lowerPrice: '', upperPrice: '', effectiveFrom: localDateTime(), effectiveUntil: '', note: '' }
}
async function applyDrawnRange(range) {
  if (!drawing.value || !canDraw.value) return
  if (!draft.value) newDraft()
  drawing.value = false
  Object.assign(draft.value, range, {
    effectiveFrom: localDateTime(range.effectiveFrom),
    effectiveUntil: localDateTime(range.effectiveUntil),
    endLowerPrice: range.endLowerPrice ?? null
  })
  await nextTick()
  zoneForm.value?.scrollIntoView?.({ block: 'nearest' })
}
function cancelDraft() {
  drawing.value = false
  draft.value = null
  conflict.value = false
  archiveId.value = null
}
function edit(zone) {
  if (!isAdmin.value) return
  drawing.value = false
  drawChannel.value = zone.endLowerPrice != null
  draft.value = { id: zone.id, revision: zone.revision, lowerPrice: zone.lowerPrice, upperPrice: zone.upperPrice,
    effectiveFrom: localDateTime(zone.effectiveFrom), effectiveUntil: zone.effectiveUntil ? localDateTime(zone.effectiveUntil) : '',
    endLowerPrice: zone.endLowerPrice ?? null, note: zone.note }
  conflict.value = false; archiveId.value = null; message.value = ''
}

function selectChartZone(zone) {
  if (!canDraw.value || drawing.value || draft.value?.id === zone.id) return
  if (draft.value) {
    const source = snapshot.value?.zones.find(item => item.id === draft.value.id)
    const fields = ['lowerPrice', 'upperPrice', 'effectiveFrom', 'effectiveUntil', 'endLowerPrice', 'note']
    if (!source || !preview.value || fields.some(key => String(source[key] ?? '') !== String(preview.value[key] ?? ''))) {
      message.value = '请先保存或取消当前编辑，再选择其他图形。'
      return
    }
  }
  edit(zone)
}

function adjustChartZone(geometry) {
  if (!canDraw.value || drawing.value || !draft.value?.id) return
  const { lowerPrice, upperPrice, endLowerPrice, effectiveFrom, effectiveUntil } = geometry
  Object.assign(draft.value, { lowerPrice, upperPrice, endLowerPrice,
    effectiveFrom: localDateTime(effectiveFrom), effectiveUntil: effectiveUntil == null ? '' : localDateTime(effectiveUntil) })
  archiveId.value = null
}

function archiveChartZone(id) {
  if (!canDraw.value || drawing.value || draft.value?.id !== id) return
  const zone = snapshot.value?.zones.find(item => item.id === id)
  if (zone?.revision === draft.value.revision) return lifecycle(zone, 'archive')
}
async function refresh() {
  const active = generation
  refreshController?.abort(); const request = new AbortController(); refreshController = request
  try {
    const data = await getWorkspace({ ...scope.value }, request.signal)
    if (!disposed && active === generation) { apply(data); return data }
    return null
  } finally { if (refreshController === request) refreshController = null }
}
async function reloadDraft() {
  const id = draft.value?.id, active = generation
  try { const data = await refresh(); if (!data) return; const latest = data.zones.find(zone => zone.id === id); if (latest) edit(latest); else { draft.value = null; conflict.value = false; message.value = '该区域已归档。' } }
  catch { if (!disposed && active === generation) message.value = '刷新失败，请稍后重试。' }
}
function errorMessage(error) {
  const code = error.response?.data?.code
  return ({ MARKET_ZONE_OVERLAP: '价格和有效时间均与已有区域重叠（停用区域仍占位）。', MARKET_ZONE_LIMIT: '当前标的与周期已达到 100 个区域上限。', MARKET_ZONE_ARCHIVED: '该区域已归档，不能继续修改。', MARKET_ZONES_UNAVAILABLE: '区域维护暂未启用。', INVALID_MARKET_ZONE: '区域参数无效，请检查价格、起止时间和备注。' })[code] || '操作失败，请刷新后重试。'
}
async function save() {
  if (!isAdmin.value || saving.value || drawing.value || conflict.value || !draft.value) return
  let body; try { body = zoneCommand(draft.value, scope.value) } catch (error) { message.value = error.message; return }
  saving.value = true; message.value = ''
  try {
    if (draft.value.id) await updateZone(draft.value.id, body); else await createZone(body)
    if (disposed) return
    draft.value = null; conflict.value = false; message.value = '区域已保存，等待当前行情建立观察。'
    try { await refresh() } catch { message.value = '区域已保存，盘面稍后自动刷新。' }
  } catch (error) {
    if (error.response?.data?.code === 'MARKET_ZONE_VERSION_CONFLICT') conflict.value = true
    else message.value = errorMessage(error)
  } finally { saving.value = false }
}
async function lifecycle(zone, action) {
  if (!isAdmin.value || saving.value) return
  if (action === 'archive' && archiveId.value !== zone.id) { archiveId.value = zone.id; return }
  saving.value = true; message.value = ''
  try { await changeZoneLifecycle(zone, action); if (disposed) return; archiveId.value = null; if (draft.value?.id === zone.id) draft.value = null; await refresh() }
  catch (error) { message.value = error.response?.data?.code === 'MARKET_ZONE_VERSION_CONFLICT' ? '区域版本已变化，请重新加载后操作。' : errorMessage(error) }
  finally { saving.value = false }
}
watch(canDraw, allowed => { if (!allowed) drawing.value = false })
watch([symbol, interval], () => {
  snapshot.value = null; draft.value = null; conflict.value = false; archiveId.value = null; message.value = ''
  events.value = null
  restart()
  goToEventPage(1)
})
watch(() => snapshot.value?.events[0]?.id, (id, previousId) => {
  if (id != null && id !== previousId && eventPage.value === 1) loadEvents()
})
onMounted(async () => {
  loadEvents()
  restart(); clockTimer = setInterval(() => { now.value = Date.now() }, 1000)
  try { const response = await getMe(); if (!disposed) account.value = response.data } catch { /* Existing session interceptor handles 401/403. */ }
})
onBeforeUnmount(() => {
  eventRequest?.abort()
  workspaceSelections.market = { symbol: symbol.value, interval: interval.value, drawChannel: drawChannel.value, showEma: showEma.value }
  disposed = true; generation++; client?.close(); refreshController?.abort(); clearInterval(clockTimer)
})
</script>

<style scoped>
.market-workspace { max-width: 1500px; margin: 12px auto 48px; padding: 0 24px; min-width: 0; }
.workspace-header,.toolbar,.panel-heading,.header-actions,.zone-heading,.zone-actions,.form-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.workspace-header,.panel-heading,.zone-heading { justify-content: space-between; }
.workspace-header { margin-bottom: 22px; }.eyebrow { font-size: 11px; color: var(--qc-accent); letter-spacing: .18em; margin: 0 0 8px; }
h1 { margin: 0; font-size: 27px; }h1 span { font-size: 12px; font-weight: 400; color: var(--qc-secondary); margin-left: 8px; }h2,h3 { font-size: 15px; margin: 0; }small { font-size: 11px; font-weight: 400; }h2 small { margin-left: 6px; color: var(--qc-secondary); }
.panel { background: var(--qc-surface); border: 1px solid var(--qc-border); border-radius: 14px; padding: 18px; min-width: 0; }.account { margin-bottom: 12px; }.toolbar { margin-bottom: 12px; }.toolbar label { display: grid; gap: 5px; font-size: 11px; color: var(--qc-secondary); }
.drawing-tools { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin-left: auto; }
.shape-switch { display: inline-flex; gap: 2px; padding: 3px; border: 1px solid var(--qc-border); border-radius: 10px; background: var(--qc-control); }
.shape-switch button,.draw-action { display: inline-flex; align-items: center; justify-content: center; gap: 7px; min-height: 32px; white-space: nowrap; }
.shape-switch button { padding: 6px 10px; border: 1px solid transparent; border-radius: 7px; background: transparent; color: var(--qc-secondary); }
.shape-switch button:hover:enabled { background: var(--qc-surface); color: var(--qc-text); }
.shape-switch button[aria-pressed="true"] { background: var(--qc-surface); border-color: var(--qc-control-border); color: var(--qc-accent); box-shadow: 0 1px 3px rgba(0,0,0,.06); }
.drawing-tools svg { width: 16px; height: 16px; flex: none; fill: none; stroke: currentColor; stroke-width: 1.4; stroke-linejoin: round; stroke-linecap: round; }
.draw-action { padding: 8px 12px; border-color: var(--qc-accent); color: var(--qc-accent); background: var(--qc-accent-soft); }
.draw-action.active { color: var(--qc-on-accent); background: var(--qc-accent); }
button,select,input,textarea { font: inherit; color: var(--qc-text); border: 1px solid var(--qc-control-border); background: var(--qc-control); border-radius: 7px; padding: 8px 11px; box-sizing: border-box; }button { cursor: pointer; font-size: 12px; }button:hover { border-color: var(--qc-accent); }button:disabled { opacity: .45; cursor: default; }button:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible { outline: 2px solid var(--qc-accent); outline-offset: 2px; }.primary { background: var(--qc-accent); color: var(--qc-on-accent); border-color: var(--qc-accent); }.danger { color: var(--qc-danger-text); }
.quote { display: grid; gap: 5px; margin-left: auto; }.quote strong { font-size: 24px; font-variant-numeric: tabular-nums; word-break: break-all; }.quote small,.muted,.chart-footnote,.readonly { color: var(--qc-secondary); }.connection { display: flex; gap: 7px; align-items: center; color: var(--qc-warning-text); font-size: 12px; }.dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }.connection.live { color: var(--qc-positive-text); }
.notice { color: var(--qc-warning-text); font-size: 12px; line-height: 1.7; }.workspace-grid { display: grid; grid-template-columns: minmax(0,1fr) 350px; gap: 16px; margin-top: 16px; }.chart-panel { align-self: start; }.chart-footnote { font-size: 11px; line-height: 1.7; margin: 0; }.zones-panel { max-height: 830px; overflow-y: auto; }.panel-heading { margin-bottom: 12px; }.panel-heading > .muted { font-size: 11px; }
.zone-card { padding: 15px 0; border-top: 1px solid var(--qc-border); font-size: 12px; }.zone-state { color: var(--qc-accent); }.price-range { font-variant-numeric: tabular-nums; word-break: break-all; }.zone-note { white-space: pre-wrap; overflow-wrap: anywhere; color: var(--qc-text-dim); }.zone-actions { gap: 6px; }.zone-actions button { padding: 5px 10px; }.zone-form { border-top: 1px solid var(--qc-border); padding-top: 18px; display: grid; gap: 12px; }.zone-form label { display: grid; gap: 6px; font-size: 12px; color: var(--qc-secondary); }.zone-form input,.zone-form textarea { width: 100%; min-width: 0; }.form-actions { gap: 8px; }.empty,.readonly { font-size: 12px; line-height: 1.8; padding: 14px 0; }
.events-panel { margin-top: 16px; }.timeline { list-style: none; padding: 0; margin: 0; }.timeline li { display: grid; grid-template-columns: 170px minmax(0,1fr); gap: 18px; padding: 15px 0; border-top: 1px solid var(--qc-border); font-size: 12px; overflow-wrap: anywhere; }.timeline time { color: var(--qc-secondary); font-variant-numeric: tabular-nums; }.timeline p { margin: 8px 0; }.feedback { background: var(--qc-accent-soft); border: 1px solid var(--qc-border-hover); padding: 12px; font-size: 13px; border-radius: 8px; }
@media(max-width: 1000px) { .workspace-grid { grid-template-columns: minmax(0,1fr); }.zones-panel { max-height: none; } }
@media(max-width: 600px) { .drawing-tools { width: 100%; margin-left: 0; gap: 8px; }.shape-switch { flex: 1; }.shape-switch button { flex: 1; padding: 6px 8px; } }
@media(max-width: 600px) { .market-workspace { padding: 0 12px; }.workspace-header { gap: 16px; }h1 { font-size: 23px; }.toolbar { gap: 14px; }.quote { margin-left: 0; width: 100%; }.panel { padding: 14px; }.timeline li { grid-template-columns: minmax(0,1fr); gap: 8px; }.chart-panel { padding: 14px 4px; }.chart-panel .panel-heading,.chart-footnote { margin-left: 10px; margin-right: 10px; } }
</style>
