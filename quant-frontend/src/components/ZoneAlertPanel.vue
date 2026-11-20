<template>
  <slot v-if="inbox" />
  <details v-if="inbox" class="panel zone-alerts">
    <summary>区域提醒<span v-if="latest"> · {{ latest }}</span></summary>
    <div class="alert-tools">
      <button type="button" :disabled="enablingSound" @click="toggleSound">{{ sound ? '关闭网页音效' : '启用并试听' }}</button>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!available.length">当前盘面暂无可订阅区域。</p>
    <label v-for="zone in available" :key="zone.id">
      <input type="checkbox" :checked="inbox.subscriptions.includes(zone.id)" :disabled="pending"
        @change="change(zone.id, $event.target.checked)" />
      #{{ zone.id }} {{ zone.endLowerPrice != null ? '平行通道' : '水平区间' }} {{ zone.note || '' }}
    </label>
    <p v-if="noticeError" role="alert">{{ noticeError }} <button type="button" @click="loadNotices(noticePage?.page ?? 1)">重试</button></p>
    <p v-else-if="noticeLoading && !noticePage" role="status">正在加载提醒记录…</p>
    <p v-else-if="noticePage?.total === 0">暂无提醒。</p>
    <ul v-if="noticePage?.records.length" :aria-busy="noticeLoading">
      <li v-for="notice in noticePage.records" :key="notice.id">
        {{ describe(notice) }} · {{ new Date(notice.marketTime).toLocaleString() }} · {{ mailStatus[notice.emailStatus] }}
      </li>
    </ul>
    <div v-if="noticePage?.total > 0" class="alert-tools" aria-label="提醒记录分页">
      <button type="button" :disabled="noticeLoading || noticePage.page <= 1" @click="loadNotices(noticePage.page - 1)">上一页</button>
      <span>第 {{ noticePage.page }} / {{ Math.ceil(noticePage.total / noticePage.size) }} 页</span>
      <button type="button" :disabled="noticeLoading || noticePage.page * noticePage.size >= noticePage.total"
        @click="loadNotices(noticePage.page + 1)">下一页</button>
    </div>
  </details>
  <p class="alert-announcement" role="status" aria-live="polite">{{ latest }}</p>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { getZoneAlertNotices, getZoneAlerts, subscribeZoneAlert } from '../api/zoneAlerts.js'
import zoneAlertSound from '../assets/audio/zone-alert.mp3'

const props = defineProps({ userId: Number, zones: { type: Array, default: () => [] } })
const inbox = ref(null)
const noticePage = ref(null)
const noticeLoading = ref(false)
const noticeError = ref('')
const latest = ref('')
const error = ref('')
const pending = ref(false)
const sound = ref(false)
const enablingSound = ref(false)
const available = computed(() => props.zones.filter(zone => inbox.value?.subscriptions.includes(zone.id)
  || (zone.lifecycle === 'ACTIVE' && (zone.effectiveUntil == null || zone.effectiveUntil > Date.now()))))
const mailStatus = { PENDING: '邮件待发送', SENDING: '邮件发送中', ACCEPTED: '邮件服务器已接收',
  FAILED: '邮件发送未确认', SKIPPED: '邮件未启用或已取消', EXPIRED: '邮件已过期' }
let cursor
let timer
let audio
let busy = false
let disposed = false

function describe(notice) {
  return `${notice.symbol} ${notice.interval} #${notice.zoneId} · 接近预警 · ${notice.direction === 'LONG' ? '多头方向' : '空头方向'}`
}

function playSound() {
  audio.currentTime = 0
  return audio.play()
}

async function toggleSound() {
  if (sound.value) {
    sound.value = false
    audio.pause()
    return
  }
  enablingSound.value = true
  error.value = ''
  try {
    // Reuse the same media element unlocked by the user's preview click.
    audio ??= new Audio(zoneAlertSound)
    await playSound()
    if (!disposed) sound.value = true
  } catch {
    if (!disposed) error.value = '当前浏览器未能启用提醒音效。'
  } finally {
    enablingSound.value = false
  }
}

async function playNotice(id) {
  if (document.hidden || !sound.value) return
  const key = `quant.zone-alert-audio.${props.userId}`
  const play = async () => {
    if (disposed || document.hidden || !sound.value || Number(localStorage.getItem(key)) >= id) return
    await playSound()
    localStorage.setItem(key, String(id))
  }
  if (navigator.locks) await navigator.locks.request(key, play)
  else await play()
}

async function loadNotices(page) {
  if (disposed || noticeLoading.value) return
  noticeLoading.value = true
  noticeError.value = ''
  try {
    const data = await getZoneAlertNotices(page)
    if (!disposed) noticePage.value = data
  } catch {
    if (!disposed) noticeError.value = '暂时无法加载提醒记录。'
  } finally {
    noticeLoading.value = false
  }
}

async function refresh() {
  if (busy || disposed) return
  busy = true
  try {
    const data = await getZoneAlerts()
    if (disposed) return
    inbox.value = data
    if (!noticePage.value || noticePage.value.page === 1) void loadNotices(1)
    const newest = data.notices[0]
    if (newest && cursor != null && newest.id > cursor) {
      latest.value = describe(newest)
      try { await playNotice(newest.id) } catch { /* Keep the visual notice if audio/storage fails. */ }
    }
    cursor = newest?.id ?? cursor ?? 0
  } catch (failure) {
    if (failure.response?.status === 404) clearInterval(timer)
    else if (inbox.value) error.value = '暂时无法刷新区域提醒。'
  } finally { busy = false }
}

async function change(id, enabled) {
  pending.value = true
  error.value = ''
  try {
    await subscribeZoneAlert(id, enabled)
    const subscriptions = inbox.value.subscriptions
    inbox.value.subscriptions = enabled ? [...new Set([...subscriptions, id])] : subscriptions.filter(item => item !== id)
    await refresh()
  } catch {
    error.value = '订阅修改失败，请重试。'
    inbox.value = { ...inbox.value }
  } finally { pending.value = false }
}

onMounted(() => {
  timer = setInterval(refresh, 5000)
  refresh()
})
onBeforeUnmount(() => {
  disposed = true
  clearInterval(timer)
  audio?.pause()
})
</script>

<style scoped>
.zone-alerts { margin: 12px 0; font-size: 13px; }
summary, label { cursor: pointer; }
.alert-tools { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin: 12px 0; }
label { display: block; margin: 8px 0; }
li { margin: 6px 0; }
.alert-announcement { margin: 0; position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
</style>
