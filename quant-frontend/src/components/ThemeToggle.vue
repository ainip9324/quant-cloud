<template>
  <button type="button" class="theme-toggle" :aria-label="action" :title="action" @click="toggleColorTheme">
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <template v-if="colorTheme === 'dark'">
        <circle cx="12" cy="12" r="4" />
        <path d="M12 2v2m0 16v2M2 12h2m16 0h2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4m0-14.2-1.4 1.4M6.3 17.7l-1.4 1.4" />
      </template>
      <path v-else d="M21 13.1A9 9 0 0 1 10.9 3 9 9 0 1 0 21 13.1Z" />
    </svg>
  </button>
</template>

<script setup>
import { computed } from 'vue'
import { colorTheme, toggleColorTheme } from '../utils/colorTheme.js'

const action = computed(() => colorTheme.value === 'dark' ? '切换到白天模式' : '切换到黑夜模式')
</script>

<style scoped>
.theme-toggle svg {
  width: 22px;
  height: 22px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.65;
  stroke-linecap: round;
  stroke-linejoin: round;
}
</style>
