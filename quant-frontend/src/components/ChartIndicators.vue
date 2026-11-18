<template>
  <details class="indicators">
    <summary>指标 <span v-if="ema">· EMA20</span></summary>
    <div class="indicator-menu">
      <label><input type="checkbox" v-model="ema" /> <span class="swatch" aria-hidden="true"></span> EMA20</label>
    </div>
  </details>
</template>

<script setup>
const ema = defineModel({ type: Boolean })
</script>

<style scoped>
.indicators { position: relative; width: max-content; font-size: 13px; }
summary, .indicator-menu {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-bg-color-overlay);
  color: var(--el-text-color-primary);
}
summary { cursor: pointer; padding: 6px 10px; }
summary span { color: var(--el-color-primary); }
.indicator-menu { position: absolute; top: calc(100% + 4px); left: 0; z-index: 10; padding: 8px 10px; box-shadow: var(--el-box-shadow-light); }
label { display: flex; gap: 6px; align-items: center; white-space: nowrap; cursor: pointer; }
.swatch { width: 14px; height: 2px; background: #a855f7; }
</style>
