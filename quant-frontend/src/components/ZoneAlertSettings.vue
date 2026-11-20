<template>
  <details class="panel zone-alert-settings" @toggle="onToggle">
    <summary>账号提醒设置</summary>
    <p v-if="error" role="alert">{{ error }}</p>
    <form v-if="settings" @submit.prevent="save">
      <fieldset :disabled="busy">
        <label><input v-model="settings.enabled" type="checkbox" /> 开启实时盘面提醒</label>
        <label><input v-model="settings.emailEnabled" type="checkbox" :disabled="!settings.emailEditable && !settings.email" /> 发送提醒邮件</label>
        <label>收件邮箱
          <input v-model.trim="settings.email" type="email" maxlength="254" :readonly="!settings.emailEditable"
            :required="settings.emailEnabled" placeholder="name@example.com" />
        </label>
        <button type="submit">保存提醒设置</button>
        <span v-if="saved" role="status">已保存</span>
      </fieldset>
    </form>
    <button v-else type="button" :disabled="busy" @click="load">加载提醒设置</button>
  </details>
</template>

<script setup>
import { ref } from 'vue'
import api from '../api/core.js'

const path = '/core/zone-alerts/settings'
const settings = ref(null)
const busy = ref(false)
const saved = ref(false)
const error = ref('')

function onToggle(event) {
  if (event.target.open && !settings.value) load()
}

async function load() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try { settings.value = (await api.get(path)).data }
  catch { error.value = '无法读取提醒设置，请重试。' }
  finally { busy.value = false }
}

async function save() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  saved.value = false
  try {
    const { enabled, emailEnabled, email } = settings.value
    settings.value = (await api.put(path, { enabled, emailEnabled, email })).data
    saved.value = true
  } catch { error.value = '保存失败，请检查邮箱地址后重试。' }
  finally { busy.value = false }
}
</script>

<style scoped>
.zone-alert-settings { margin: 12px 0; }
summary { cursor: pointer; }
fieldset { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin: 12px 0 0; border: 0; padding: 0; }
button, input[type="email"] { color: var(--qc-text); background: var(--qc-control); border: 1px solid var(--qc-control-border); border-radius: 7px; padding: 7px 10px; }
</style>
