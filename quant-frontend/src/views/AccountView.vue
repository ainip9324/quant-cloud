<template>
  <section class="account-page">
    <header><p class="eyebrow">MY ACCOUNT</p><h1>我的账户</h1></header>
    <div class="account-card">
      <p v-if="account">{{ account.username }} · {{ account.email }} · {{ account.role === 'ADMIN' ? '管理员' : '观察者' }}</p>
      <p v-else class="muted">正在读取账户信息…</p>
      <h2>修改密码</h2>
      <p class="muted">修改后当前浏览器需要重新登录；其他已登录会话将在到期后失效。</p>
      <form @submit.prevent="submit" data-testid="password-form">
        <label>当前密码<input v-model="form.currentPassword" type="password" autocomplete="current-password" required :disabled="saving" data-testid="current-password" /></label>
        <label>新密码<input v-model="form.newPassword" type="password" autocomplete="new-password" required minlength="8" :disabled="saving" data-testid="new-password" /></label>
        <small class="muted">至少8位，最多72个UTF-8字节；中文等字符通常占多个字节。</small>
        <label>确认新密码<input v-model="form.confirm" type="password" autocomplete="new-password" required :disabled="saving" data-testid="confirm-password" /></label>
        <p v-if="error" role="alert">{{ error }}</p>
        <button type="submit" :disabled="saving || !account" data-testid="save-password">{{ saving ? '正在修改…' : '修改密码并重新登录' }}</button>
      </form>
    </div>
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { changePassword, getMe } from '../api/core.js'

const router = useRouter()
const account = ref(null), saving = ref(false), error = ref('')
const form = ref({ currentPassword: '', newPassword: '', confirm: '' })

onMounted(async () => {
  try { account.value = (await getMe()).data }
  catch (failure) { error.value = failure.response?.data?.message || '读取账户失败，请刷新后重试。' }
})

async function submit() {
  if (saving.value || !account.value) return
  error.value = ''
  if (!form.value.currentPassword) { error.value = '请输入当前密码'; return }
  if (form.value.newPassword.length < 8 || new TextEncoder().encode(form.value.newPassword).length > 72) {
    error.value = '新密码至少8位，且UTF-8编码不超过72字节'; return
  }
  if (form.value.newPassword !== form.value.confirm) { error.value = '两次新密码不一致'; return }
  if (form.value.newPassword === form.value.currentPassword) { error.value = '新密码不能与当前密码相同'; return }
  saving.value = true
  try {
    await changePassword({ currentPassword: form.value.currentPassword, newPassword: form.value.newPassword })
    form.value = { currentPassword: '', newPassword: '', confirm: '' }
    localStorage.removeItem('quant_token')
    localStorage.removeItem('quant_user')
    await router.replace({ name: 'login', query: { passwordChanged: '1' } })
  } catch (failure) {
    error.value = failure.response?.data?.message || '修改失败，请重试。'
  } finally { saving.value = false }
}
</script>

<style scoped>
.account-page { max-width: 680px; margin: 0 auto; padding: 24px 20px 48px; color: var(--qc-text); }
.eyebrow { color: var(--qc-accent); font-size: 11px; letter-spacing: .18em; }
h1 { font-size: 27px; margin: 0 0 24px; }h2 { font-size: 18px; margin-top: 26px; }
.account-card { border: 1px solid var(--qc-border); border-radius: 14px; padding: 24px; background: var(--qc-surface); overflow-wrap: anywhere; }
.muted { color: var(--qc-secondary); font-size: 13px; line-height: 1.7; }
form { display: grid; gap: 16px; margin-top: 24px; }label { display: grid; gap: 8px; font-size: 14px; }
input,button { box-sizing: border-box; width: 100%; min-width: 0; padding: 11px 12px; border: 1px solid var(--qc-control-border); border-radius: 7px; background: var(--qc-control); color: var(--qc-text); font: inherit; }
input:focus-visible,button:focus-visible { outline: 2px solid var(--qc-accent); outline-offset: 2px; }
button { background: var(--qc-accent); color: var(--qc-on-accent); cursor: pointer; }button:disabled { opacity: .5; cursor: default; }
[role="alert"] { color: var(--qc-danger-text); margin: 0; font-size: 13px; }
@media (max-width: 500px) { .account-page { padding: 16px 12px 36px; }.account-card { padding: 18px; } }
</style>
