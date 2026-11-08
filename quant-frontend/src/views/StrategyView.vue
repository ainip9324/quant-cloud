<template>
  <div>
    <el-card>
      <template #header>
        <div style="display: flex; align-items: center; justify-content: space-between;">
          <span>策略管理</span>
          <el-button @click="loadStrategies" :loading="loading">刷新</el-button>
        </div>
      </template>

      <el-table :data="strategies" v-loading="loading" border stripe>
        <el-table-column prop="id" label="策略标识" width="160" />
        <el-table-column prop="className" label="实现类" />
        <el-table-column prop="minPeriod" label="指标预热 K 线数" width="160" align="center">
          <template #header>
            <el-tooltip content="策略开始交易前，至少需要积累的历史 K 线数量；不是 1m/5m/1h 等 K 线时间周期" placement="top">
              <span>指标预热 K 线数</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="center">
          <template #default="{ row }">
            <el-button type="primary" link @click="$router.push({ path: '/backtest', query: { strategyId: row.id } })">
              去回测
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && strategies.length === 0" description="暂无策略" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getStrategies } from '../api/core'
import { ElMessage } from 'element-plus'

const strategies = ref([])
const loading = ref(false)

onMounted(() => {
  loadStrategies()
})

async function loadStrategies() {
  loading.value = true
  try {
    const res = await getStrategies()
    strategies.value = res.data
  } catch (e) {
    ElMessage.error('加载策略列表失败: ' + (e.response?.data?.message || e.message))
  } finally {
    loading.value = false
  }
}
</script>
