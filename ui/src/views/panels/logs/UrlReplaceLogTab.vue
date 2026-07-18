<template>
  <div class="url-replace-log-tab">
    <!-- 统计卡片 -->
    <div class="stats-grid" v-if="stats">
      <StatCard label="总替换数" :value="stats.totalCount" />
      <StatCard label="成功" :value="stats.successCount" tone="success" />
      <StatCard label="失败" :value="stats.failedCount" tone="error" />
      <StatCard label="断链替换" :value="stats.brokenLinkCount" tone="warning" />
      <StatCard label="重复扫描" :value="stats.duplicateCount" tone="warning" />
      <StatCard label="批量处理" :value="stats.batchProcessingCount" tone="warning" />
    </div>

    <!-- 筛选栏 -->
    <div class="st-toolbar">
      <div class="st-toolbar-left">
        <SearchInput v-model="filterKeyword" placeholder="搜索 URL 或标题..." @search="handleFilterChange" />
        <select v-model="filterSource" class="st-select" @change="handleFilterChange">
          <option value="">全部来源</option>
          <option value="broken-link">断链替换</option>
          <option value="duplicate">重复扫描</option>
          <option value="batch-processing">批量处理</option>
        </select>
      </div>
      <div class="st-toolbar-right">
        <VButton :loading="loading" @click="handleRefresh">刷新</VButton>
        <VButton type="danger" ghost :disabled="clearing" @click="clearLogs">
          {{ clearing ? '清空中...' : '清空日志' }}
        </VButton>
      </div>
    </div>

    <!-- 日志列表 -->
    <div class="logs-container st-card">
      <VLoading v-if="loading" />

      <VEmpty v-else-if="logs.length === 0" title="暂无替换日志" message="断链替换、重复文件清理或批量处理执行 URL 替换后，记录会显示在这里">
        <template #actions>
          <VButton @click="handleRefresh">刷新</VButton>
        </template>
      </VEmpty>

      <template v-else>
        <div class="st-table-wrapper">
          <table class="st-table logs-table">
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
                  <div class="url-result" v-if="log.newUrl && log.success" :title="log.newUrl">
                    → {{ log.newUrl }}
                  </div>
                  <div class="error-msg" v-if="!log.success && log.errorMessage" :title="log.errorMessage">
                    {{ log.errorMessage }}
                  </div>
                </td>
                <td class="col-type">
                  <span :class="['st-tag', getSourceTypeClass(log.sourceType)]">
                    {{ getSourceTypeLabel(log.sourceType) }}
                  </span>
                </td>
                <td class="col-title" :title="log.sourceTitle || log.sourceName || ''">{{ log.sourceTitle || log.sourceName || '-' }}</td>
                <td class="col-ref-type">
                  <div class="ref-type-tags" v-if="log.referenceType">
                    <span
                      v-for="rt in log.referenceType.split(',')"
                      :key="rt"
                      class="st-tag"
                    >{{ getReferenceTypeLabel(rt) }}</span>
                  </div>
                  <span v-else>-</span>
                </td>
                <td class="col-source">
                  <StatusBadge :tone="getSourceTone(log.source)">
                    {{ getSourceLabel(log.source) }}
                  </StatusBadge>
                </td>
                <td class="col-status">
                  <StatusBadge :tone="log.success ? 'success' : 'error'">
                    {{ log.success ? '成功' : '失败' }}
                  </StatusBadge>
                </td>
                <td class="col-time">{{ formatTime(log.replacedAt) }}</td>
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
import { formatTime } from '@/utils/format'
import { getSourceTypeLabel, getSourceTypeClass } from '@/composables'
import StatCard from '@/components/StatCard.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import SearchInput from '@/components/SearchInput.vue'
import type { BadgeTone } from '@/types/ui'

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

const fetchLogs = async () => {
  loading.value = true
  try {
    const params: Record<string, string | number> = { page: page.value, size: pageSize.value }
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
      } catch {
        Toast.error('清空失败')
      } finally {
        clearing.value = false
      }
    }
  })
}

const handleFilterChange = () => {
  if (page.value !== 1) {
    page.value = 1
  } else {
    fetchLogs()
  }
}

const getSourceLabel = (source: string): string => {
  const labels: Record<string, string> = {
    'broken-link': '断链替换',
    'duplicate': '重复扫描',
    'batch-processing': '批量处理'
  }
  return labels[source] || source
}

const getSourceTone = (source: string): BadgeTone => {
  switch (source) {
    case 'broken-link': return 'error'
    case 'duplicate': return 'warning'
    case 'batch-processing': return 'info'
    default: return 'neutral'
  }
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
.col-url { width: 23%; }
.col-type { width: 10%; }
.col-title {
  width: 16%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.col-ref-type { width: 12%; }
.col-source { width: 10%; }
.col-status { width: 9%; }
.col-time { width: 20%; color: var(--st-text-tertiary); font-size: 13px; }

/* URL 列 */
.url-original {
  font-size: 13px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.url-result {
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

/* 引用位置标签容器 */
.ref-type-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

/* 响应式 */
@media (max-width: 768px) {
  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
