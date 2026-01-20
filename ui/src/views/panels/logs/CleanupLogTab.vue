<template>
  <div class="cleanup-log-tab">
    <!-- 统计卡片 -->
    <div class="stats-row" v-if="stats">
      <div class="stat-box">
        <span class="stat-num">{{ stats.totalCount }}</span>
        <span class="stat-text">总删除数</span>
      </div>
      <div class="stat-box">
        <span class="stat-num orange">{{ stats.duplicateCount }}</span>
        <span class="stat-text">重复文件</span>
      </div>
      <div class="stat-box">
        <span class="stat-num orange">{{ stats.unreferencedCount }}</span>
        <span class="stat-text">未引用文件</span>
      </div>
      <div class="stat-box">
        <span class="stat-num green">{{ formatBytes(stats.freedBytes) }}</span>
        <span class="stat-text">释放空间</span>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="filter-bar">
      <input
        v-model="filterFilename"
        type="text"
        placeholder="搜索文件名..."
        class="filter-input"
        @input="debouncedFetch"
      />
      <select v-model="filterReason" class="filter-select" @change="handleFilterChange">
        <option value="">全部类型</option>
        <option value="DUPLICATE">重复文件</option>
        <option value="UNREFERENCED">未引用文件</option>
      </select>
      <div class="filter-actions">
        <button type="button" class="btn-refresh" @click="handleRefresh" :disabled="loading">
          {{ loading ? '加载中...' : '刷新' }}
        </button>
        <button type="button" class="btn-clear" @click="clearLogs" :disabled="!stats || stats.totalCount === 0">
          清空日志
        </button>
      </div>
    </div>

    <!-- 日志列表 -->
    <div class="card">
      <div v-if="loading" class="loading-state">加载中...</div>
      <div v-else-if="logs.length === 0" class="empty-state">暂无清理日志</div>
      <template v-else>
        <table class="data-table">
          <thead>
            <tr>
              <th>文件名</th>
              <th>大小</th>
              <th>删除原因</th>
              <th>操作人</th>
              <th>删除时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="log in logs" :key="log.name">
              <td>{{ log.displayName }}</td>
              <td>{{ formatBytes(log.size || 0) }}</td>
              <td>
                <span :class="['reason-tag', getReasonClass(log.reason)]">
                  {{ getReasonLabel(log.reason) }}
                </span>
              </td>
              <td>{{ log.operator || '-' }}</td>
              <td>{{ formatTime(log.deletedAt) }}</td>
            </tr>
          </tbody>
        </table>

        <!-- 分页 -->
        <div class="pagination" v-if="total > pageSize">
          <div class="page-info">共 {{ total }} 条</div>
          <div class="page-controls">
            <button type="button" class="page-btn" :disabled="page <= 1" @click="changePage(page - 1)">上一页</button>
            <span class="page-num">{{ page }} / {{ totalPages }}</span>
            <button type="button" class="page-btn" :disabled="page >= totalPages" @click="changePage(page + 1)">下一页</button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { Dialog, Toast } from '@halo-dev/components'

interface CleanupLog {
  name: string
  attachmentName: string
  displayName: string
  size: number
  reason: string
  operator: string
  deletedAt: string
  errorMessage: string | null
}

interface Stats {
  totalCount: number
  duplicateCount: number
  unreferencedCount: number
  freedBytes: number
}

const API_BASE = '/apis/console.api.storage-toolkit.timxs.com/v1alpha1/cleanup'

const logs = ref<CleanupLog[]>([])
const stats = ref<Stats | null>(null)
const loading = ref(false)
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const filterReason = ref('')
const filterFilename = ref('')

let debounceTimer: ReturnType<typeof setTimeout> | null = null

const debouncedFetch = () => {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    page.value = 1
    fetchLogs()
  }, 300)
}

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

const fetchLogs = async () => {
  loading.value = true
  try {
    const params: Record<string, any> = { page: page.value, size: pageSize.value }
    if (filterReason.value) params.reason = filterReason.value
    if (filterFilename.value) params.filename = filterFilename.value

    const { data } = await axiosInstance.get(`${API_BASE}/logs`, { params })
    logs.value = data.items || []
    total.value = data.total || 0
  } catch (error) {
    console.error('获取日志失败:', error)
  } finally {
    loading.value = false
  }
}

const fetchStats = async () => {
  try {
    const { data } = await axiosInstance.get(`${API_BASE}/logs/stats`)
    stats.value = data
  } catch (error) {
    console.error('获取统计失败:', error)
  }
}

const handleRefresh = async () => {
  await Promise.all([fetchLogs(), fetchStats()])
}

const clearLogs = () => {
  Dialog.warning({
    title: '确认清空',
    description: '确定要清空所有清理日志吗？此操作不可恢复。',
    confirmType: 'danger',
    confirmText: '清空',
    cancelText: '取消',
    async onConfirm() {
      try {
        await axiosInstance.delete(`${API_BASE}/logs`)
        Toast.success('日志已清空')
        logs.value = []
        total.value = 0
        stats.value = { totalCount: 0, duplicateCount: 0, unreferencedCount: 0, freedBytes: 0 }
      } catch (error) {
        Toast.error('清空失败')
      }
    }
  })
}

const handleFilterChange = () => {
  page.value = 1
  fetchLogs()
}

const changePage = (newPage: number) => {
  page.value = newPage
  fetchLogs()
}

const formatBytes = (bytes: number): string => {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0, size = bytes
  while (size >= 1024 && i < 3) { size /= 1024; i++ }
  return `${size.toFixed(1)} ${units[i]}`
}

const formatTime = (isoString: string | undefined): string => {
  if (!isoString) return '-'
  return new Date(isoString).toLocaleString('zh-CN')
}

const getReasonClass = (reason: string | undefined): string => {
  return reason === 'DUPLICATE' ? 'reason-duplicate' : 'reason-unreferenced'
}

const getReasonLabel = (reason: string | undefined): string => {
  const labels: Record<string, string> = {
    'DUPLICATE': '重复文件',
    'UNREFERENCED': '未引用文件'
  }
  return labels[reason || ''] || reason || '-'
}

onMounted(() => {
  fetchStats()
  fetchLogs()
})
</script>

<style scoped>
.cleanup-log-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.stats-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.stat-box {
  background: white;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 16px 20px;
  text-align: center;
}

.stat-num {
  display: block;
  font-size: 24px;
  font-weight: 600;
  color: #18181b;
}

.stat-num.green { color: #16a34a; }
.stat-num.orange { color: #d97706; }

.stat-text {
  font-size: 13px;
  color: #71717a;
  margin-top: 4px;
}

/* 筛选栏 */
.filter-bar {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}

.filter-input {
  flex: 1;
  max-width: 300px;
  height: 38px;
  padding: 0 12px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  font-size: 14px;
}

.filter-input:focus {
  outline: none;
  border-color: #4f46e5;
}

.filter-select {
  height: 38px;
  padding: 0 12px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  font-size: 14px;
  background: white;
  min-width: 130px;
}

.filter-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

.btn-refresh,
.btn-clear {
  height: 38px;
  padding: 0 16px;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  border: 1px solid;
}

.btn-refresh {
  background: white;
  color: #374151;
  border-color: #e5e7eb;
}

.btn-refresh:hover:not(:disabled) {
  background: #f9fafb;
}

.btn-clear {
  background: #fef2f2;
  color: #dc2626;
  border-color: #fecaca;
}

.btn-clear:hover:not(:disabled) {
  background: #fee2e2;
}

.btn-refresh:disabled,
.btn-clear:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.card {
  background: white;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
}

.loading-state, .empty-state {
  padding: 48px;
  text-align: center;
  color: #71717a;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
}

.data-table th, .data-table td {
  padding: 12px 16px;
  text-align: left;
  border-bottom: 1px solid #f4f4f5;
}

.data-table th {
  font-size: 12px;
  font-weight: 500;
  color: #71717a;
  background: #fafafa;
}

.data-table td {
  font-size: 14px;
  color: #18181b;
}

.reason-tag {
  display: inline-block;
  padding: 2px 8px;
  font-size: 12px;
  border-radius: 4px;
}

.reason-duplicate { background: #fef3c7; color: #92400e; }
.reason-unreferenced { background: #fee2e2; color: #dc2626; }

.pagination {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  border-top: 1px solid #f4f4f5;
}

.page-info { font-size: 13px; color: #71717a; }

.page-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}

.page-btn {
  height: 32px;
  padding: 0 12px;
  font-size: 13px;
  background: white;
  color: #374151;
  border: 1px solid #e4e4e7;
  border-radius: 4px;
  cursor: pointer;
}

.page-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.page-num { font-size: 13px; color: #374151; }

/* 响应式 */
@media (max-width: 768px) {
  .stats-row {
    grid-template-columns: repeat(2, 1fr);
  }
  .filter-bar {
    flex-direction: column;
    align-items: stretch;
  }
  .filter-input {
    max-width: none;
  }
  .filter-actions {
    margin-left: 0;
    justify-content: flex-end;
  }
}
</style>
