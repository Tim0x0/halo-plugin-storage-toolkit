<template>
  <div class="url-replace-log-tab">
    <!-- 统计卡片 -->
    <div class="stats-grid" v-if="stats">
      <div class="stat-card">
        <div class="stat-value">{{ stats.totalCount }}</div>
        <div class="stat-label">总替换数</div>
      </div>
      <div class="stat-card">
        <div class="stat-value stat-success">{{ stats.successCount }}</div>
        <div class="stat-label">成功</div>
      </div>
      <div class="stat-card">
        <div class="stat-value stat-failed">{{ stats.failedCount }}</div>
        <div class="stat-label">失败</div>
      </div>
      <div class="stat-card">
        <div class="stat-value stat-source">{{ stats.brokenLinkCount }}</div>
        <div class="stat-label">断链替换</div>
      </div>
      <div class="stat-card">
        <div class="stat-value stat-source">{{ stats.duplicateCount }}</div>
        <div class="stat-label">重复扫描</div>
      </div>
      <div class="stat-card">
        <div class="stat-value stat-source">{{ stats.batchProcessingCount }}</div>
        <div class="stat-label">批量处理</div>
      </div>
    </div>

    <!-- 筛选栏 -->
    <div class="filter-bar">
      <input
        v-model="filterKeyword"
        type="text"
        placeholder="搜索 URL 或标题..."
        class="filter-input"
        @input="debouncedFetch"
      />
      <select v-model="filterSource" class="filter-select" @change="handleFilterChange">
        <option value="">全部来源</option>
        <option value="broken-link">断链替换</option>
        <option value="duplicate">重复扫描</option>
        <option value="batch-processing">批量处理</option>
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
        <div class="empty-text">暂无替换日志</div>
      </div>

      <table v-else class="logs-table">
        <thead>
          <tr>
            <th class="col-url">URL</th>
            <th class="col-type">内容类型</th>
            <th class="col-title">内容标题</th>
            <th class="col-ref-type">引用位置</th>
            <th class="col-source">替换来源</th>
            <th class="col-status">状态</th>
            <th class="col-time">替换时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="log in logs" :key="log.name">
            <td class="col-url">
              <div class="url-original" :title="log.oldUrl">
                {{ log.oldUrl }}
              </div>
              <div class="url-result" v-if="log.newUrl && log.success">
                → {{ log.newUrl }}
              </div>
              <div class="error-msg" v-if="!log.success && log.errorMessage" :title="log.errorMessage">
                {{ log.errorMessage }}
              </div>
            </td>
            <td class="col-type">
              <span :class="['type-badge', getSourceTypeClass(log.sourceType)]">
                {{ getSourceTypeLabel(log.sourceType) }}
              </span>
            </td>
            <td class="col-title">{{ log.sourceTitle || log.sourceName || '-' }}</td>
            <td class="col-ref-type">
              <div class="ref-type-tags" v-if="log.referenceType">
                <span
                  v-for="rt in log.referenceType.split(',')"
                  :key="rt"
                  class="ref-type-tag"
                >{{ getReferenceTypeLabel(rt) }}</span>
              </div>
              <span v-else>-</span>
            </td>
            <td class="col-source">
              <span :class="['source-badge', getSourceClass(log.source)]">
                {{ getSourceLabel(log.source) }}
              </span>
            </td>
            <td class="col-status">
              <span :class="['status-badge', log.success ? 'status-success' : 'status-failed']">
                {{ log.success ? '成功' : '失败' }}
              </span>
            </td>
            <td class="col-time">{{ formatTime(log.replacedAt) }}</td>
          </tr>
        </tbody>
      </table>

      <!-- 分页 -->
      <VPagination v-if="total > 0" v-model:page="page" v-model:size="pageSize" :total="total" :size-options="PAGE_SIZE_OPTIONS" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { Dialog, Toast, VPagination } from '@halo-dev/components'
import { API_ENDPOINTS } from '@/constants/api'
import { PAGE_SIZE_OPTIONS, DEFAULT_PAGE_SIZE } from '@/constants/pagination'
import { formatTime } from '@/utils/format'
import { getSourceTypeLabel, getSourceTypeClass } from '@/composables'

interface UrlReplaceLog {
  name: string
  oldUrl: string
  newUrl: string
  sourceType: string
  sourceName: string
  sourceTitle: string | null
  referenceType: string | null
  source: string
  replacedAt: string
  success: boolean
  errorMessage: string | null
}

interface Stats {
  totalCount: number
  successCount: number
  failedCount: number
  brokenLinkCount: number
  duplicateCount: number
  batchProcessingCount: number
}

const logs = ref<UrlReplaceLog[]>([])
const stats = ref<Stats | null>(null)
const loading = ref(false)
const clearing = ref(false)
const page = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)
const total = ref(0)
const filterSource = ref('')
const filterKeyword = ref('')

let debounceTimer: ReturnType<typeof setTimeout> | null = null

const debouncedFetch = () => {
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = setTimeout(() => {
    page.value = 1
    fetchLogs()
  }, 300)
}

const fetchLogs = async () => {
  loading.value = true
  try {
    const params: Record<string, any> = { page: page.value, size: pageSize.value }
    if (filterSource.value) params.source = filterSource.value
    if (filterKeyword.value) params.keyword = filterKeyword.value

    const { data } = await axiosInstance.get(API_ENDPOINTS.URL_REPLACE_LOGS, { params })
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
    const { data } = await axiosInstance.get(API_ENDPOINTS.URL_REPLACE_LOGS_STATS)
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
    description: '确定要清空所有替换日志吗？此操作不可恢复。',
    confirmType: 'danger',
    confirmText: '清空',
    cancelText: '取消',
    async onConfirm() {
      clearing.value = true
      try {
        const { data: result } = await axiosInstance.delete(API_ENDPOINTS.URL_REPLACE_LOGS)
        if (result.success) {
          Toast.success(`已清空 ${result.deleted} 条日志`)
          logs.value = []
          total.value = 0
          page.value = 1
          stats.value = {
            totalCount: 0,
            successCount: 0,
            failedCount: 0,
            brokenLinkCount: 0,
            duplicateCount: 0,
            batchProcessingCount: 0
          }
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

const getSourceLabel = (source: string): string => {
  const labels: Record<string, string> = {
    'broken-link': '断链替换',
    'duplicate': '重复扫描',
    'batch-processing': '批量处理'
  }
  return labels[source] || source
}

const getSourceClass = (source: string): string => {
  const classes: Record<string, string> = {
    'broken-link': 'source-broken-link',
    'duplicate': 'source-duplicate',
    'batch-processing': 'source-batch'
  }
  return classes[source] || ''
}

const getReferenceTypeLabel = (refType: string): string => {
  const labels: Record<string, string> = {
    'cover': '封面',
    'content': '内容',
    'draft': '草稿',
    'media': '媒体',
    'comment': '评论',
    'reply': '回复',
    'avatar': '头像',
    'icon': '图标',
    'basic': '基本设置'
  }
  return labels[refType] || refType
}

onUnmounted(() => {
  if (debounceTimer) clearTimeout(debounceTimer)
})

watch([page, pageSize], () => {
  fetchLogs()
})

onMounted(() => handleRefresh())
</script>

<style scoped>
.url-replace-log-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 统计卡片 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
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
.stat-failed { color: #dc2626; }
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
.col-url { width: 22%; min-width: 140px; }
.col-type { width: 90px; }
.col-title { width: 20%; min-width: 120px; }
.col-ref-type { width: 120px; }
.col-source { width: 90px; }
.col-status { width: 70px; }
.col-time { width: 150px; color: #999; font-size: 13px; }

/* URL 列 */
.url-original {
  font-size: 13px;
  font-weight: 500;
  word-break: break-all;
  line-height: 1.4;
}

.url-result {
  font-size: 12px;
  color: #10b981;
  margin-top: 4px;
  word-break: break-all;
}

.error-msg {
  font-size: 11px;
  color: #ef4444;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 内容类型标签 */
.type-badge {
  display: inline-block;
  padding: 3px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
  background: #f3f4f6;
  color: #4b5563;
}

.type-badge.tag-blue {
  background: #dbeafe;
  color: #1d4ed8;
}

.type-badge.tag-pink {
  background: #fce7f3;
  color: #be185d;
}

.type-badge.tag-orange {
  background: #ffedd5;
  color: #c2410c;
}

.type-badge.tag-indigo {
  background: #e0e7ff;
  color: #4338ca;
}

.type-badge.tag-purple {
  background: #f3e8ff;
  color: #7c3aed;
}

.type-badge.tag-amber {
  background: #fef3c7;
  color: #b45309;
}

.ref-type-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.ref-type-tag {
  display: inline-block;
  padding: 2px 6px;
  border-radius: 3px;
  font-size: 11px;
  background: #f3f4f6;
  color: #6b7280;
  white-space: nowrap;
}

/* 来源标签 */
.source-badge {
  display: inline-block;
  padding: 3px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
  background: #f3f4f6;
  color: #4b5563;
}

.source-broken-link {
  background: #fee2e2;
  color: #991b1b;
}

.source-duplicate {
  background: #fef3c7;
  color: #92400e;
}

.source-batch {
  background: #dbeafe;
  color: #1d4ed8;
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
