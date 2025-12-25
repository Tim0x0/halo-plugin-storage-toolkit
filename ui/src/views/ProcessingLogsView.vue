<template>
  <div class="processing-logs-page">
    <VPageHeader title="处理日志">
      <template #icon>
        <IconList class="mr-2 self-center" />
      </template>
      <template #actions>
        <VSpace>
          <VButton @click="handleRefresh" :loading="loading">刷新</VButton>
          <VButton type="danger" @click="handleClearAll" :loading="clearing">清空日志</VButton>
        </VSpace>
      </template>
    </VPageHeader>

    <div class="p-4">
      <!-- 统计卡片 -->
      <div class="stats-grid" v-if="stats">
        <div class="stat-card">
          <div class="stat-value">{{ stats.totalProcessed }}</div>
          <div class="stat-label">总处理数</div>
        </div>
        <div class="stat-card">
          <div class="stat-value stat-success">{{ stats.successCount }}</div>
          <div class="stat-label">成功</div>
        </div>
        <div class="stat-card">
          <div class="stat-value stat-partial">{{ stats.partialCount }}</div>
          <div class="stat-label">部分成功</div>
        </div>
        <div class="stat-card">
          <div class="stat-value stat-failed">{{ stats.failedCount }}</div>
          <div class="stat-label">失败</div>
        </div>
        <div class="stat-card">
          <div class="stat-value stat-skipped">{{ stats.skippedCount }}</div>
          <div class="stat-label">跳过</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ formatBytes(stats.totalSavedBytes) }}</div>
          <div class="stat-label">节省空间</div>
        </div>
      </div>

      <!-- 筛选 -->
      <div class="filter-bar">
        <input
          v-model="filters.filename"
          type="text"
          placeholder="搜索文件名..."
          class="filter-input"
          @input="debouncedFetch"
        />
        <select v-model="filters.status" class="filter-select" @change="fetchLogs">
          <option value="">全部状态</option>
          <option value="SUCCESS">成功</option>
          <option value="PARTIAL">部分成功</option>
          <option value="FAILED">失败</option>
          <option value="SKIPPED">跳过</option>
        </select>
      </div>

      <!-- 日志列表 -->
      <div class="logs-container">
        <VLoading v-if="loading" />
        <div v-else-if="logs.length === 0" class="empty-state">
          <div class="empty-icon">📋</div>
          <div class="empty-text">暂无处理日志</div>
        </div>
        <table v-else class="logs-table">
          <thead>
            <tr>
              <th class="col-filename">文件名</th>
              <th class="col-source">来源</th>
              <th class="col-status">状态</th>
              <th class="col-size">原始大小</th>
              <th class="col-size">处理后</th>
              <th class="col-ratio">压缩率</th>
              <th class="col-time">处理时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="log in logs" :key="log.metadata?.name">
              <td class="col-filename">
                <div class="filename" :title="log.spec?.originalFilename">
                  {{ log.spec?.originalFilename }}
                </div>
                <div class="filename-result" v-if="log.spec?.resultFilename && log.spec?.status === 'SUCCESS'">
                  → {{ log.spec?.resultFilename }}
                </div>
                <div class="error-msg" v-if="log.spec?.errorMessage" :title="log.spec?.errorMessage">
                  {{ log.spec?.errorMessage }}
                </div>
              </td>
              <td class="col-source">
                <span :class="getSourceBadgeClass(log.spec?.source)">
                  {{ getSourceText(log.spec?.source) }}
                </span>
              </td>
              <td class="col-status">
                <span :class="getStatusBadgeClass(log.spec?.status)">
                  {{ getStatusText(log.spec?.status) }}
                </span>
              </td>
              <td class="col-size">{{ formatBytes(log.spec?.originalSize) }}</td>
              <td class="col-size">{{ formatBytes(log.spec?.resultSize) }}</td>
              <td class="col-ratio">
                <span :class="getCompressionClass(log.spec)">
                  {{ getCompressionRatio(log.spec) }}
                </span>
              </td>
              <td class="col-time">{{ formatDate(log.spec?.processedAt) }}</td>
            </tr>
          </tbody>
        </table>

        <!-- 分页 -->
        <div class="pagination" v-if="total > 0">
          <VPagination 
            :page="page"
            :size="pageSize"
            :total="total" 
            :size-options="[20, 30, 50, 100]"
            @change="handlePaginationChange"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { VPageHeader, VButton, VSpace, VLoading, VPagination, IconList, Dialog, Toast } from '@halo-dev/components'
import { axiosInstance } from '@halo-dev/api-client'

interface ProcessingLogSpec {
  originalFilename: string
  resultFilename: string
  originalSize: number
  resultSize: number
  status: string
  processedAt: string
  processingDuration: number
  errorMessage?: string
  source?: string
}

interface ProcessingLog {
  metadata?: { name: string }
  spec?: ProcessingLogSpec
}

interface Stats {
  totalProcessed: number
  successCount: number
  failedCount: number
  skippedCount: number
  partialCount: number
  totalSavedBytes: number
}

const logs = ref<ProcessingLog[]>([])
const stats = ref<Stats | null>(null)
const loading = ref(false)
const clearing = ref(false)
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const filters = ref({ filename: '', status: '' })

let debounceTimer: ReturnType<typeof setTimeout> | null = null

const debouncedFetch = () => {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(fetchLogs, 300)
}

const fetchLogs = async () => {
  loading.value = true
  try {
    const { data } = await axiosInstance.get('/apis/storage-toolkit.timxs.com/v1alpha1/processinglogs', {
      params: {
        page: page.value,
        size: pageSize.value,
        ...(filters.value.filename && { filename: filters.value.filename }),
        ...(filters.value.status && { status: filters.value.status })
      }
    })
    logs.value = data.items || []
    total.value = data.total || 0
  } catch (error) {
    console.error('Failed to fetch logs:', error)
    Toast.error('获取日志列表失败')
    logs.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const fetchStats = async () => {
  try {
    const { data } = await axiosInstance.get('/apis/storage-toolkit.timxs.com/v1alpha1/processinglogs/stats')
    stats.value = data
  } catch (error) {
    console.error('Failed to fetch stats:', error)
    Toast.error('获取统计信息失败')
    stats.value = { 
      totalProcessed: 0, 
      successCount: 0, 
      failedCount: 0, 
      skippedCount: 0, 
      partialCount: 0, 
      totalSavedBytes: 0 
    }
  }
}

const handleRefresh = async () => {
  await Promise.all([fetchLogs(), fetchStats()])
}

const handlePaginationChange = ({ page: newPage, size: newSize }: { page: number; size: number }) => {
  page.value = newPage
  pageSize.value = newSize
  fetchLogs()
}

const handleClearAll = () => {
  Dialog.warning({
    title: '确认清空',
    description: '确定要清空所有处理日志吗？此操作不可恢复。',
    confirmType: 'danger',
    confirmText: '清空',
    cancelText: '取消',
    async onConfirm() {
      clearing.value = true
      try {
        const { data: result } = await axiosInstance.delete('/apis/storage-toolkit.timxs.com/v1alpha1/processinglogs/all')
        if (result.success) {
          Toast.success(`已清空 ${result.deleted} 条日志`)
          // 重置数据
          logs.value = []
          stats.value = { 
            totalProcessed: 0, 
            successCount: 0, 
            failedCount: 0, 
            skippedCount: 0, 
            partialCount: 0, 
            totalSavedBytes: 0 
          }
          total.value = 0
          page.value = 1
          // 从服务器刷新确认
          await fetchLogs()
          await fetchStats()
        } else {
          Toast.error('清空失败')
        }
      } catch (error) {
        Toast.error('清空失败')
      } finally {
        clearing.value = false
      }
    }
  })
}

const formatBytes = (bytes: number | undefined): string => {
  if (bytes === undefined || bytes === null || bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return `${size.toFixed(1)} ${units[i]}`
}

const formatDate = (dateStr: string | undefined): string => {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleString('zh-CN')
}

const getStatusBadgeClass = (status: string | undefined): string => {
  if (!status) return 'status-badge'
  const s = status.toUpperCase()
  return `status-badge status-${s.toLowerCase()}`
}

const getStatusText = (status: string | undefined): string => {
  if (!status) return '-'
  const s = status.toUpperCase()
  switch (s) {
    case 'SUCCESS': return '成功'
    case 'FAILED': return '失败'
    case 'SKIPPED': return '跳过'
    case 'PARTIAL': return '部分成功'
    default: return status
  }
}

const getCompressionRatio = (spec: ProcessingLogSpec | undefined): string => {
  if (!spec || !spec.originalSize || spec.originalSize === 0) return '-'
  if (spec.resultSize === spec.originalSize) return '0%'
  const ratio = ((1 - spec.resultSize / spec.originalSize) * 100).toFixed(0)
  return `${ratio}%`
}

const getCompressionClass = (spec: ProcessingLogSpec | undefined): string => {
  if (!spec || !spec.originalSize || spec.originalSize === 0) return ''
  const ratio = 1 - spec.resultSize / spec.originalSize
  if (ratio > 0.2) return 'compression-good'
  if (ratio > 0) return 'compression-ok'
  return 'compression-none'
}

const getSourceBadgeClass = (source: string | undefined): string => {
  if (!source) return 'source-badge'
  return `source-badge source-${source.toLowerCase()}`
}

const getSourceText = (source: string | undefined): string => {
  if (!source) return '-'
  switch (source.toLowerCase()) {
    case 'console': return '控制台'
    case 'editor': return '编辑器'
    case 'console-editor': return '控制台编辑器'
    case 'uc-editor': return 'UC编辑器'
    case 'attachment-manager': return '附件管理'
    default: return source
  }
}

onMounted(() => {
  handleRefresh()
})
</script>

<style scoped>
.processing-logs-page {
  min-height: 100vh;
  background: #f5f5f5;
}

/* 统计卡片 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}

.stat-card {
  background: white;
  border-radius: 8px;
  padding: 20px;
  text-align: center;
  box-shadow: 0 1px 3px rgba(0,0,0,0.1);
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  margin-bottom: 4px;
}

.stat-success { color: #16a34a; }
.stat-failed { color: #dc2626; }
.stat-partial { color: #ca8a04; }
.stat-skipped { color: #6b7280; }

.stat-label {
  font-size: 14px;
  color: #666;
}

/* 筛选栏 */
.filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.filter-input {
  flex: 1;
  max-width: 300px;
  height: 38px;
  padding: 0 12px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  font-size: 14px;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.filter-input:hover {
  border-color: #d1d5db;
}

.filter-input:focus {
  outline: none;
  border-color: #4f46e5;
  box-shadow: 0 0 0 2px rgba(79, 70, 229, 0.1);
}

.filter-select {
  height: 38px;
  padding: 0 32px 0 12px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  font-size: 14px;
  background-color: white;
  min-width: 130px;
  cursor: pointer;
  color: #374151;
  appearance: none;
  -webkit-appearance: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 20 20' fill='%236b7280'%3E%3Cpath fill-rule='evenodd' d='M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z' clip-rule='evenodd'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 8px center;
  background-size: 16px;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.filter-select:hover {
  border-color: #d1d5db;
}

.filter-select:focus {
  outline: none;
  border-color: #4f46e5;
  box-shadow: 0 0 0 2px rgba(79, 70, 229, 0.1);
}

/* 日志容器 */
.logs-container {
  background: white;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.1);
  overflow: hidden;
}

/* 空状态 */
.empty-state {
  padding: 60px 20px;
  text-align: center;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 12px;
}

.empty-text {
  color: #999;
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
  border-bottom: 1px solid #eee;
}

/* 列宽定义 */
.col-filename { width: 30%; min-width: 180px; }
.col-source { width: 80px; }
.col-status { width: 90px; }
.col-size { width: 85px; }
.col-ratio { width: 70px; }
.col-time { width: 150px; }

.logs-table tr:hover {
  background: #fafafa;
}

/* 文件名列 */
.filename {
  font-weight: 500;
  word-break: break-all;
  white-space: normal;
  line-height: 1.4;
}

.filename-result {
  font-size: 12px;
  color: #10b981;
  margin-top: 4px;
}

.error-msg {
  font-size: 11px;
  color: #ef4444;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}

/* 时间列 */
.col-time {
  color: #999;
  font-size: 13px;
}

/* 状态标签 */
.status-badge {
  display: inline-block;
  padding: 4px 10px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}

.status-success {
  background: #dcfce7;
  color: #166534;
}

.status-failed {
  background: #fee2e2;
  color: #991b1b;
}

.status-skipped {
  background: #f3f4f6;
  color: #4b5563;
}

.status-partial {
  background: #fef3c7;
  color: #92400e;
}

/* 来源标签 */
.source-badge {
  display: inline-block;
  padding: 3px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
}

.source-console {
  background: #e0e7ff;
  color: #3730a3;
}

.source-editor {
  background: #d1fae5;
  color: #065f46;
}

.source-console-editor {
  background: #e0e7ff;
  color: #3730a3;
}

.source-uc-editor {
  background: #fce7f3;
  color: #9d174d;
}

.source-attachment-manager {
  background: #d1fae5;
  color: #065f46;
}

/* 压缩率 */
.compression-good {
  color: #16a34a;
  font-weight: 500;
}

.compression-ok {
  color: #6b7280;
}

.compression-none {
  color: #9ca3af;
}

/* 分页 */
.pagination {
  padding: 16px;
  display: flex;
  justify-content: flex-end;
  border-top: 1px solid #f0f0f0;
}

/* 响应式 */
@media (max-width: 1200px) {
  .stats-grid {
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (max-width: 768px) {
  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  
  .filter-bar {
    flex-direction: column;
  }
  
  .filter-input {
    max-width: none;
  }
}
</style>
