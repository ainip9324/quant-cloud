<template>
  <div>
    <el-card>
      <template #header>
        <div class="history-head">
          <span class="history-head__title">回测历史记录</span>
          <div class="history-head__filters">
            <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width: 140px;" @change="onFilterChange">
              <el-option label="等待中" value="WAITING" />
              <el-option label="执行中" value="RUNNING" />
              <el-option label="成功" value="SUCCESS" />
              <el-option label="失败" value="FAILED" />
            </el-select>
            <el-select v-model="filterMarketType" placeholder="全部市场" clearable style="width: 170px;" @change="onFilterChange">
              <el-option v-for="option in MARKET_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
            <el-button @click="loadTasks" :loading="loading">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table class="history-table" :data="tasks" v-loading="loading" border stripe>
        <el-table-column prop="id" label="ID" width="70" align="center" />
        <el-table-column prop="strategyId" label="策略" width="100" />
        <el-table-column label="市场" width="130"><template #default="{ row }">{{ marketLabel(row.marketType) }}</template></el-table-column>
        <el-table-column prop="symbol" label="交易对" width="110" />
        <el-table-column prop="interval" label="周期" width="80" align="center" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="收益率" width="120" align="right">
          <template #default="{ row }">
            <span v-if="row.result" :style="{ color: row.result.returnRate >= 0 ? '#ef232a' : '#14b143' }">
              {{ row.result.returnRate.toFixed(4) }}%
            </span>
            <span v-else style="color: #999;">-</span>
          </template>
        </el-table-column>
        <el-table-column label="最大回撤" width="120" align="right">
          <template #default="{ row }">
            <span v-if="row.result">{{ row.result.maxDrawdown.toFixed(4) }}%</span>
            <span v-else style="color: #999;">-</span>
          </template>
        </el-table-column>
        <el-table-column label="交易笔数" width="100" align="center">
          <template #default="{ row }">
            <span v-if="row.result">{{ row.result.trades.length }}</span>
            <span v-else style="color: #999;">-</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <time class="history-created-time" :datetime="row.createTime">{{ formatTime(row.createTime) }}</time>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="center" fixed="right">
          <template #default="{ row }">
            <div class="history-actions">
              <el-button type="primary" link @click="$router.push({ path: '/backtest', query: { id: row.id } })">
                查看
              </el-button>
              <el-button v-if="row.status === 'SUCCESS'" type="success" link @click="$router.push(`/replay/${row.id}`)">
                回放
              </el-button>
              <el-button v-if="row.status === 'SUCCESS'" type="primary" link @click="$router.push({ name: 'ai-reports', query: { taskId: row.id } })">
                AI复盘
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!loading && tasks.length === 0" description="暂无回测任务" />

      <!-- 分页 -->
      <div v-if="total > 0" style="margin-top: 16px; display: flex; justify-content: flex-end;">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @size-change="onSizeChange"
          @current-change="onPageChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { listBacktests } from '../api/core'
import { ElMessage } from 'element-plus'
import { MARKET_OPTIONS, marketLabel } from '../utils/marketOptions'

const tasks = ref([])
const loading = ref(false)
const filterStatus = ref('')
const filterMarketType = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)

onMounted(() => {
  loadTasks()
})

async function loadTasks() {
  loading.value = true
  try {
    const res = await listBacktests({
      status: filterStatus.value || undefined,
      marketType: filterMarketType.value || undefined,
      page: currentPage.value,
      size: pageSize.value
    })
    tasks.value = res.data.list || []
    total.value = res.data.total || 0
  } catch (e) {
    ElMessage.error('加载历史记录失败: ' + (e.response?.data?.message || e.message))
  } finally {
    loading.value = false
  }
}

/** 状态筛选变化 */
function onFilterChange() {
  currentPage.value = 1
  loadTasks()
}

/** 每页条数变化 */
function onSizeChange() {
  currentPage.value = 1
  loadTasks()
}

/** 页码变化 */
function onPageChange() {
  loadTasks()
}

/** 状态 → tag 类型颜色（SUCCESS 绿、RUNNING 青蓝、WAITING 灰、FAILED 红） */
function statusType(status) {
  switch (status) {
    case 'SUCCESS': return 'success'
    case 'RUNNING': return 'primary'
    case 'WAITING': return 'info'
    case 'FAILED': return 'danger'
    default: return 'info'
  }
}

/** 格式化时间（兼容 LocalDateTime 的 ISO 字符串） */
function formatTime(t) {
  if (!t) return '-'
  return new Date(t).toLocaleString()
}
</script>

<style scoped>
.history-created-time {
  white-space: nowrap;
}
.history-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: nowrap;
  gap: 12px;
  white-space: nowrap;
}
.history-actions :deep(.el-button) {
  flex-shrink: 0;
  margin-left: 0;
}
/* Fixed cells must cover the scrolling columns beneath the transparent theme. */
.history-table :deep(.el-table__header .el-table-fixed-column--right) {
  background: var(--qc-fixed-header);
}
.history-table :deep(.el-table__body tr .el-table-fixed-column--right) {
  background: var(--qc-fixed-cell);
}
.history-table :deep(.el-table__body tr.el-table__row--striped .el-table-fixed-column--right) {
  background: var(--qc-fixed-stripe);
}
.history-table :deep(.el-table__body tr:hover .el-table-fixed-column--right) {
  background: var(--qc-fixed-hover);
}
.history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.history-head__title {
  white-space: nowrap;
  font-weight: 600;
}
.history-head__filters {
  display: flex;
  align-items: center;
  gap: 12px;
}
@media (max-width: 640px) {
  .history-head__filters {
    width: 100%;
  }
  .history-head__filters .el-select {
    flex: 1;
  }
}
</style>
