<template>
  <div class="cleanup-log-tab">
    <!-- 统计卡片 -->
    <div class="stats-grid" v-if="stats">
      <div class="stat-card">
        <div class="stat-value">{{ stats.totalCount }}</div>
        <div class="stat-label">总删除数</div>
      </div>
      <div class="stat-card">
        <div class="stat-value stat-source">{{ stats.duplicateCount }}</div>
        <div class="stat-label">重复文件</div>
      </div>
      <div class="stat-card">
        <div class="stat-value stat-source">{{ stats.unreferencedCount }}</div>
        <div class="stat-label">未引用文件</div>
      </div>
      <div class="stat-card">
        <div class="stat-value stat-success">{{ formatBytes(stats.freedBytes) }}</div>
        <div class="stat-label">释放空间</div>
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
        <button type="button" class="btn-clear" @click="clearLogs" :disabled="clearing">
          {{ clearing ? '清空中...' : '清空日志' }}
        </button>
      </div>
    </div>

    <!-- 日志列表 -->
    <div class="logs-container">
      <div v-if="loading" class="loading-state">加载中...</div>

      <div v-else-if="logs.length === 0" class="empty-state">
        <div class="empty-icon">📋</div>
        <div class="empty-text">暂无清理日志</div>
      </div>

      <table v-else class="logs-table">
        <thead>
          <tr>
            <th class="col-filename">文件名</th>
            <th class="col-size">大小</th>
            <th class="col-reason">删除原因</th>
            <th class="col-operator">操作人</th>
            <th class="col-time">删除时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="log in logs" :key="log.name">
            <td class="col-filename">
              <div class="filename" :title="log.displayName">{{ log.displayName }}</div>
            </td>
            <td class="col-size">{{ formatBytes(log.size || 0) }}</td>
            <td class="col-reason">
              <span :class="['reason-badge', getReasonClass(log.reason)]">
                {{ getReasonLabel(log.reason) }}
              </span>
            </td>
            <td class="col-operator">{{ log.operator || '-' }}</td>
            <td class="col-time">{{ formatTime(log.deletedAt) }}</td>
          </tr>
        </tbody>
      </table>

      <!-- 分页 -->
      <div class="pagination" v-if="total > 0">
        <div class="page-info">共 {{ total }} 条</div>
        <div class="page-controls">
          <button type="button" class="page-btn" :disabled="page <= 1" @click="changePage(page - 1)">
            上一页
          </button>
          <span class="page-num">{{ page }} / {{ totalPages }}</span>
          <button type="button" class="page-btn" :disabled="page >= totalPages" @click="changePage(page + 1)">
            下一页
          </button>
        </div>
        <select v-model="pageSize" class="page-size" @change="handlePageSizeChange">
          <option v-for="size in PAGE_SIZE_OPTIONS" :key="size" :value="size">{{ size }}条/页</option>
        </select>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { Dialog, Toast } from '@halo-dev/components'
import { API_ENDPOINTS } from '@/constants/api'
import { PAGE_SIZE_OPTIONS, DEFAULT_PAGE_SIZE } from '@/constants/pagination'
import { formatBytes, formatTime } from '@/utils/format'

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

const logs = ref<CleanupLog[]>([])
const stats = ref<Stats | null>(null)
const loading = ref(false)
const clearing = ref(false)
const page = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)
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

    const { data } = await axiosInstance.get(API_ENDPOINTS.CLEANUP_LOGS, { params })
    logs.value = data.items || []
    total.value = data.total || 0
  } catch (error) {
    console.error('获取日志失败:', error)
    logs.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const fetchStats = async () => {
  try {
    const { data } = await axiosInstance.get(API_ENDPOINTS.CLEANUP_LOGS_STATS)
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
      clearing.value = true
      try {
        const { data: result } = await axiosInstance.delete(API_ENDPOINTS.CLEANUP_LOGS)
        if (result.success) {
          Toast.success(`已清空 ${result.deleted} 条日志`)
          logs.value = []
          total.value = 0
          page.value = 1
          stats.value = { totalCount: 0, duplicateCount: 0, unreferencedCount: 0, freedBytes: 0 }
        }
      } catch (error) {
        Toast.error('清空失败')
      } finally {
        clearing.value = false
      }
    }
  })
}

const handleFilterChange = () => {
  page.value = 1
  fetchLogs()
}

const changePage = (newPage: number) => {
  if (newPage >= 1 && newPage <= totalPages.value) {
    page.value = newPage
    fetchLogs()
  }
}

const handlePageSizeChange = () => {
  page.value = 1
  fetchLogs()
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

onUnmounted(() => {
  if (debounceTimer) clearTimeout(debounceTimer)
})

onMounted(() => handleRefresh())
</script>

<style scoped>
.cleanup-log-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 统计卡片 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.stat-card {
  background: white;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 16px 20px;
  text-align: center;
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  margin-bottom: 4px;
}

.stat-success { color: #16a34a; }
.stat-source { color: #d97706; }

.stat-label {
  font-size: 13px;
  color: #71717a;
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

/* 日志容器 */
.logs-container {
  background: white;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
}

.loading-state,
.empty-state {
  padding: 60px 20px;
  text-align: center;
  color: #71717a;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 16px;
  opacity: 0.8;
}

.empty-text {
  font-size: 14px;
}

/* 表格 */
.logs-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}

.logs-table th,
.logs-table td {
  padding: 12px 16px;
  text-align: left;
  font-size: 14px;
  border-bottom: 1px solid #f0f0f0;
  vertical-align: top;
}

.logs-table th {
  background: #fafafa;
  font-weight: 500;
  font-size: 13px;
  color: #666;
}

.logs-table tr:hover {
  background: #fafafa;
}

/* 列宽 */
.col-filename { width: 35%; min-width: 180px; }
.col-size { width: 85px; }
.col-reason { width: 100px; }
.col-operator { width: 100px; }
.col-time { width: 150px; color: #999; font-size: 13px; }

/* 文件名 */
.filename {
  font-weight: 500;
  word-break: break-all;
  line-height: 1.4;
}

/* 原因标签 */
.reason-badge {
  display: inline-block;
  padding: 3px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
}

.reason-duplicate {
  background: #fef3c7;
  color: #92400e;
}

.reason-unreferenced {
  background: #fee2e2;
  color: #991b1b;
}

/* 分页 */
.pagination {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  border-top: 1px solid #f0f0f0;
  flex-wrap: wrap;
  gap: 12px;
}

.page-info {
  font-size: 13px;
  color: #666;
}

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
  border: 1px solid #e5e7eb;
  border-radius: 4px;
  cursor: pointer;
}

.page-btn:hover:not(:disabled) {
  background: #f9fafb;
}

.page-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.page-num {
  font-size: 13px;
  color: #374151;
  padding: 0 8px;
}

.page-size {
  height: 32px;
  padding: 0 8px;
  font-size: 13px;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
  background: white;
}

/* 响应式 */
@media (max-width: 768px) {
  .stats-grid {
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
