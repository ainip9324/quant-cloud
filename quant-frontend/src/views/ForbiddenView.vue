<template>
  <section class="forbidden-card">
    <p class="code">403</p>
    <h1>当前账户无权访问此功能</h1>
    <p>该区域仅向管理员开放。</p>
    <el-button type="primary" @click="$router.replace('/market')">返回行情工作区</el-button>
  </section>
</template>

<style scoped>
.forbidden-card { max-width: 620px; margin: 70px auto; padding: 48px; text-align: center; border: 1px solid var(--qc-border); border-radius: 18px; background: var(--qc-surface); }
.code { margin: 0; color: var(--qc-danger-text); font-size: 52px; font-weight: 800; } h1 { color: var(--qc-text); } p { color: var(--qc-secondary); }
</style>
