<template>
  <div class="app-shell">
    <!-- 顶部居中悬浮毛玻璃胶囊导航 -->
    <nav v-if="route.meta.workspace" class="qc-nav">
      <div class="qc-nav__brand">
        <span class="qc-nav__logo">◈</span>
        <span class="qc-nav__title">QUANT<em>CLOUD</em></span>
      </div>
      <div class="qc-nav__menu">
        <button type="button" class="qc-nav__item" @click="$router.push({ name: 'landing' })">
          返回首页
        </button>
        <button
          v-for="item in visibleMenus"
          :key="item.name"
          class="qc-nav__item"
          :class="{ active: activeMenu === item.name }"
          @click="$router.push(item.path)"
        >
          {{ item.label }}
        </button>
      </div>
      <div class="qc-nav__actions">
        <ThemeToggle class="qc-nav__theme" />
        <button type="button" class="qc-nav__item qc-nav__logout" @click="logout">退出登录</button>
      </div>
    </nav>

    <main :class="route.meta.workspace ? 'qc-page' : 'public-page'">
      <router-view />
    </main>
    <KnowledgeWidget :class="{ 'knowledge-on-landing': isLanding, 'knowledge-light': colorTheme === 'light' }" />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, watchEffect } from 'vue'
import { useRoute } from 'vue-router'
import { logout } from './auth/logout.js'
import { workspaceMenusForRole } from './auth/roleUi.js'
import KnowledgeWidget from './components/KnowledgeWidget.vue'
import ThemeToggle from './components/ThemeToggle.vue'
import { colorTheme, syncColorTheme } from './utils/colorTheme.js'

const route = useRoute()
const isLanding = computed(() => route.name === 'landing')

function onStorage(event) {
  syncColorTheme(event)
  if (event.key !== 'quant_token' || event.newValue !== null || localStorage.getItem('quant_token')) return
  if (route.meta.workspace) window.location.replace('/')
  else if (isLanding.value) window.location.reload()
}

function onPageShow(event) {
  if (event.persisted && route.meta.workspace && !localStorage.getItem('quant_token')) {
    window.location.replace('/')
  }
}

onMounted(() => {
  window.addEventListener('storage', onStorage)
  window.addEventListener('pageshow', onPageShow)
})
onBeforeUnmount(() => {
  window.removeEventListener('storage', onStorage)
  window.removeEventListener('pageshow', onPageShow)
})

// Element Plus 提示会挂到 body 下，从文档根节点继承配色。
watchEffect(() => {
  const usesSharedPalette = route.meta.workspace || route.name === 'login' || route.name === 'register'
  document.documentElement.toggleAttribute(
    'data-qc-light',
    Boolean(usesSharedPalette && colorTheme.value === 'light')
  )
})

const visibleMenus = computed(() => {
  route.fullPath
  try {
    const role = JSON.parse(localStorage.getItem('quant_user') || 'null')?.role
    return workspaceMenusForRole(role)
  } catch {
    return []
  }
})

// 顶部导航悬浮在页面上方，主内容需预留导航高度
const activeMenu = computed(() => route.name || 'home')
</script>

<style scoped>
.app-shell {
  min-height: 100vh;
}
.qc-page {
  padding-top: 92px;
}

.public-page {
  min-height: 100vh;
}
</style>
