<template>
  <div class="auth">
    <div class="grid" aria-hidden="true"></div>

    <button type="button" class="brand" @click="$router.push('/')">
      <i>◈</i>
      QUANT<span>CLOUD</span>
    </button>

    <main class="panel">
      <p class="eyebrow">{{ isRegister ? 'CREATE YOUR WORKSPACE' : 'WELCOME BACK' }}</p>
      <h1>{{ isRegister ? '创建你的量化工作台' : '登录 QuantCloud' }}</h1>
      <p class="sub">
        {{ isRegister ? '注册后进入共享行情工作区。' : '使用用户名或邮箱登录你的工作区。' }}
      </p>
      <p v-if="!isRegister && route.query.passwordChanged === '1'" class="notice" role="status">密码已修改，请重新登录。</p>

      <el-form ref="formRef" :model="form" :rules="rules" @submit.prevent="submit">
        <el-form-item v-if="isRegister" prop="username">
          <el-input v-model="form.username" autocomplete="username" placeholder="用户名（3-32 位小写字母、数字或下划线）" />
        </el-form-item>

        <el-form-item v-if="isRegister" prop="email">
          <el-input v-model="form.email" type="email" autocomplete="email" placeholder="邮箱地址" />
        </el-form-item>
        <el-form-item v-else prop="identifier">
          <el-input v-model="form.identifier" autocomplete="username" aria-label="用户名或邮箱" placeholder="用户名或邮箱" />
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :autocomplete="isRegister ? 'new-password' : 'current-password'"
            placeholder="密码（至少 8 位）"
          />
        </el-form-item>

        <el-form-item v-if="isRegister" prop="confirm">
          <el-input
            v-model="form.confirm"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="再次输入密码"
          />
        </el-form-item>

        <el-button class="submit" type="primary" native-type="submit" :loading="loading">
          {{ isRegister ? '创建账户' : '登录工作台' }}
          <b>→</b>
        </el-button>
      </el-form>

      <p class="switch">
        {{ isRegister ? '已有账户？' : '还没有账户？' }}
        <router-link :to="isRegister ? '/login' : '/register'">
          {{ isRegister ? '去登录' : '免费注册' }}
        </router-link>
      </p>

      <p v-if="!isRegister" class="demo">演示账户：demo@quantcloud.local / password</p>
    </main>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login, register } from '../api/core'
import { workspaceHomeForRole } from '../auth/roleUi.js'

const route = useRoute()
const router = useRouter()
const formRef = ref()
const loading = ref(false)
const isRegister = computed(() => route.name === 'register')

const form = ref({
  identifier: '',
  username: '',
  email: '',
  password: '',
  confirm: ''
})

const rules = {
  identifier: [{ required: true, whitespace: true, message: '请输入用户名或邮箱', trigger: 'blur' }],
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  email: [{ required: true, type: 'email', message: '请输入正确邮箱', trigger: 'blur' }],
  password: [{ required: true, min: 8, message: '密码至少 8 位', trigger: 'blur' }],
  confirm: [{
    validator: (_, value, callback) => {
      value === form.value.password
        ? callback()
        : callback(new Error('两次密码不一致'))
    },
    trigger: 'blur'
  }]
}

async function submit() {
  if (loading.value) return
  loading.value = true
  try {
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return
    const response = await (isRegister.value
      ? register({ username: form.value.username, email: form.value.email, password: form.value.password })
      : login({ identifier: form.value.identifier, password: form.value.password }))
    localStorage.setItem('quant_token', response.data.token)
    localStorage.setItem('quant_user', JSON.stringify(response.data.user))

    ElMessage.success(isRegister.value ? '注册成功，欢迎加入' : '登录成功')
    router.replace(workspaceHomeForRole(response.data.user.role))
  } catch (error) {
    ElMessage.error(error.response?.data?.message || error.message || '请求失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth {
  min-height: 100vh;
  display: grid;
  place-items: center;
  position: relative;
  background: var(--qc-bg);
  color: var(--qc-text);
}
.grid {
  position: fixed;
  inset: 0;
  pointer-events: none;
  background-image:
    linear-gradient(var(--qc-grid-line) 1px, transparent 1px),
    linear-gradient(90deg, var(--qc-grid-line) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: radial-gradient(circle at center, #000, transparent 72%);
}
.brand {
  position: absolute;
  z-index: 1;
  top: 26px;
  left: clamp(22px, 7vw, 90px);
  display: flex;
  gap: 9px;
  align-items: center;
  border: 0;
  background: none;
  color: var(--qc-text);
  font-weight: 800;
  letter-spacing: .05em;
  cursor: pointer;
}
.brand i {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 9px;
  background: #0ea5e9;
  color: #fff;
  font-style: normal;
}
.brand span, .eyebrow, a { color: var(--qc-accent); }
.panel {
  position: relative;
  z-index: 1;
  box-sizing: border-box;
  width: min(506px, calc(100% - 42px));
  padding: 42px;
  border: 1px solid var(--qc-border);
  border-radius: 22px;
  background: var(--qc-surface);
  box-shadow: var(--qc-shadow);
  backdrop-filter: blur(18px);
}
.eyebrow { margin: 0 0 13px; font-size: 10px; font-weight: 700; letter-spacing: .18em; }
.panel h1 { margin: 0; font-size: 31px; letter-spacing: -.05em; }
.sub { margin: 13px 0 30px; color: var(--qc-text-dim); font-size: 14px; }
.notice { color: var(--qc-positive-text); font-size: 13px; }
.submit { width: 100%; height: 45px; font-weight: 700; }
.submit b { padding-left: 10px; font-size: 18px; }
.switch, .demo { text-align: center; font-size: 13px; }
.switch { margin: 22px 0 0; color: var(--qc-text-dim); }
.switch a { margin-left: 7px; text-decoration: none; }
.demo { margin: 24px 0 0; padding-top: 18px; border-top: 1px solid var(--qc-border); color: var(--qc-text-faint); font-size: 11px; }
@media (max-width: 500px) {
  .panel { padding: 30px 23px; }
  .brand { top: 20px; left: 22px; }
}
</style>
