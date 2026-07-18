<template>
  <div class="processing-log-tab">
    <!-- 统计卡片 -->
    <div class="stats-grid" v-if="stats">
      <StatCard label="总处理数" :value="stats.totalProcessed" />
      <StatCard label="成功" :value="stats.successCount" tone="success" />
      <StatCard label="部分成功" :value="stats.partialCount" tone="warning" />
      <StatCard label="失败" :value="stats.failedCount" tone="error" />
      <StatCard label="跳过" :value="stats.skippedCount" />
      <StatCard label="节省空间" :value="formatBytes(stats.totalSavedBytes)" />
    </div>

    <!-- 筛选栏 -->
    <div class="st-toolbar">
      <div class="st-toolbar-left">
        <SearchInput v-model="filters.filename" placeholder="搜索文件名..." @search="handleFilterChange" />
        <select v-model="filters.status" class="st-select" @change="handleFilterChange">
          <option value="">全部状态</option>
          <option value="SUCCESS">成功</option>
          <option value="PARTIAL">部分成功</option>
          <option value="FAILED">失败</option>
          <option value="SKIPPED">跳过</option>
        </select>
      </div>
      <div class="st-toolbar-right">
        <VButton :loading="loading" @click="handleRefresh">刷新</VButton>
        <VButton type="danger" ghost :disabled="clearing" @click="handleClearAll">
          {{ clearing ? '清空中...' : '清空日志' }}
        </VButton>
      </div>
    </div>

    <!-- 日志列表 -->
    <div class="logs-container st-card">
      <VLoading v-if="loading" />

      <VEmpty v-else-if="logs.length === 0" title="暂无处理日志" message="上传图片经过水印或格式转换处理后，处理记录会显示在这里">
        <template #actions>
          <VButton @click="handleRefresh">刷新</VButton>
        </template>
      </VEmpty>

      <template v-else>
        <div class="st-table-wrapper">
          <table class="st-table logs-table">
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
                  <div class="filename-result" v-if="log.spec?.resultFilename && log.spec?.status === 'SUCCESS'" :title="log.spec?.resultFilename">
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
                  <StatusBadge :tone="getStatusTone(log.spec?.status)">
                    {{ getStatusText(log.spec?.status) }}
                  </StatusBadge>
                </td>
                <td class="col-size">{{ formatBytes(log.spec?.originalSize) }}</td>
                <td class="col-size">{{ formatBytes(log.spec?.resultSize) }}</td>
                <td class="col-ratio">
                  <span :class="getCompressionClass(log.spec)">
                    {{ getCompressionRatio(log.spec) }}
                  </span>
                </td>
                <td class="col-time">{{ formatTime(log.spec?.processedAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 分页 -->
        <VPagination v-if="total > 0" v-model:page="page" v-model:size="pageSize" :total="total" :size-options="PAGE_SIZE_OPTIONS" />
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { Dialog, Toast, VButton, VEmpty, VLoading, VPagination } from '@halo-dev/components'
import { API_ENDPOINTS } from '@/constants/api'
import { PAGE_SIZE_OPTIONS, DEFAULT_PAGE_SIZE } from '@/constants/pagination'
import { formatBytes, formatTime } from '@/utils/format'
import StatCard from '@/components/StatCard.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import SearchInput from '@/components/SearchInput.vue'
import type { BadgeTone } from '@/types/ui'

interface ProcessingLogSpec {
  originalFilename: string
  resultFilename: string
  originalSize: number
  resultSize: number
  status: string
  processedAt: string
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
const pageSize = ref(DEFAULT_PAGE_SIZE)
const total = ref(0)
const filters = ref({ filename: '', status: '' })

const fetchLogs = async () => {
  loading.value = true
  try {
    const { data } = await axiosInstance.get(API_ENDPOINTS.PROCESSING_LOGS, {
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
    logs.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const fetchStats = async () => {
  try {
    const { data } = await axiosInstance.get(API_ENDPOINTS.PROCESSING_LOGS_STATS)
    stats.value = data
  } catch {
    stats.value = { totalProcessed: 0, successCount: 0, failedCount: 0, skippedCount: 0, partialCount: 0, totalSavedBytes: 0 }
  }
}

const handleRefresh = async () => {
  await Promise.all([fetchLogs(), fetchStats()])
}

const handleFilterChange = () => {
  if (page.value !== 1) {
    page.value = 1
  } else {
    fetchLogs()
  }
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
        const { data: result } = await axiosInstance.delete(API_ENDPOINTS.PROCESSING_LOGS)
        if (result.success) {
          Toast.success(`已清空 ${result.deleted} 条日志`)
          logs.value = []
          stats.value = { totalProcessed: 0, successCount: 0, failedCount: 0, skippedCount: 0, partialCount: 0, totalSavedBytes: 0 }
          total.value = 0
          page.value = 1
        }
      } catch {
        Toast.error('清空失败')
      } finally {
        clearing.value = false
      }
    }
  })
}

const getStatusTone = (status: string | undefined): BadgeTone => {
  switch ((status || '').toUpperCase()) {
    case 'SUCCESS': return 'success'
    case 'FAILED': return 'error'
    case 'PARTIAL': return 'warning'
    default: return 'neutral'
  }
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

const getSourceBadgeClass = (source: string | undefined): string => {
  if (!source) return 'st-tag'
  switch (source.toLowerCase()) {
    case 'console':
    case 'console-editor': return 'st-tag st-tag--indigo'
    case 'editor':
    case 'attachment-manager': return 'st-tag st-tag--green'
    case 'uc-editor': return 'st-tag st-tag--pink'
    case 'batch-processing': return 'st-tag st-tag--orange'
    default: return 'st-tag'
  }
}

const getSourceText = (source: string | undefined): string => {
  if (!source) return '-'
  switch (source.toLowerCase()) {
    case 'console': return '控制台'
    case 'editor': return '编辑器'
    case 'console-editor': return '控制台编辑器'
    case 'uc-editor': return 'UC编辑器'
    case 'attachment-manager': return '附件管理'
    case 'batch-processing': return '批量处理'
    default: return source
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

watch([page, pageSize], () => {
  fetchLogs()
})

onMounted(() => handleRefresh())
</script>

<style scoped>
.processing-log-tab {
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

/* 日志容器 */
.logs-container {
  overflow: hidden;
  padding-bottom: 4px;
}

/* 表格：列宽等本地细节（基础样式来自 .st-table） */
.logs-table {
  table-layout: fixed;
  min-width: 760px;
}

.logs-table th,
.logs-table td {
  white-space: nowrap;
}

.logs-table.st-table td {
  vertical-align: top;
}

/* 列宽 */
.col-filename { width: 27%; }
.col-source { width: 12%; }
.col-status { width: 10%; }
.col-size { width: 11%; }
.col-ratio { width: 7%; }
.col-time { width: 22%; color: var(--st-text-tertiary); font-size: 13px; }

/* 文件名 */
.filename {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.filename-result {
  font-size: 12px;
  color: var(--st-success-text);
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.error-msg {
  font-size: 11px;
  color: var(--st-error-text);
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 压缩率 */
.compression-good {
  color: var(--st-success-text);
  font-weight: 500;
}

.compression-ok {
  color: var(--st-text-secondary);
}

.compression-none {
  color: var(--st-text-tertiary);
}

/* 响应式 */
@media (max-width: 768px) {
  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
