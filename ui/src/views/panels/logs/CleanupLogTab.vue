<template>
  <div class="cleanup-log-tab">
    <!-- 统计卡片 -->
    <div class="stats-grid" v-if="stats">
      <StatCard label="总删除数" :value="stats.totalCount" :icon="IconDeleteBin" />
      <StatCard label="重复文件" :value="stats.duplicateCount" tone="warning" :icon="IconGrid" />
      <StatCard label="未引用文件" :value="stats.unreferencedCount" tone="warning" :icon="IconForbidLine" />
      <StatCard label="释放空间" :value="formatBytes(stats.freedBytes)" tone="success" :icon="IconSave" />
    </div>

    <!-- 筛选栏 -->
    <div class="st-toolbar">
      <div class="st-toolbar-left">
        <SearchInput v-model="filterFilename" placeholder="搜索文件名..." @search="handleFilterChange" />
        <select v-model="filterReason" class="st-select" @change="handleFilterChange">
          <option value="">全部类型</option>
          <option value="DUPLICATE">重复文件</option>
          <option value="UNREFERENCED">未引用文件</option>
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

      <VEmpty v-else-if="logs.length === 0" title="暂无清理日志" message="执行重复文件或未引用附件清理后，删除记录会显示在这里">
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
                  <StatusBadge :tone="getReasonTone(log.reason)">
                    {{ getReasonLabel(log.reason) }}
                  </StatusBadge>
                </td>
                <td class="col-operator">{{ log.operator || '-' }}</td>
                <td class="col-time">{{ formatTime(log.deletedAt) }}</td>
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
import { Dialog, Toast, VButton, VEmpty, VLoading, VPagination, IconDeleteBin, IconGrid, IconForbidLine, IconSave } from '@halo-dev/components'
import { API_ENDPOINTS } from '@/constants/api'
import { PAGE_SIZE_OPTIONS, DEFAULT_PAGE_SIZE } from '@/constants/pagination'
import { formatBytes, formatTime } from '@/utils/format'
import StatCard from '@/components/StatCard.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import SearchInput from '@/components/SearchInput.vue'
import type { BadgeTone } from '@/types/ui'

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

const fetchLogs = async () => {
  loading.value = true
  try {
    const params: Record<string, string | number> = { page: page.value, size: pageSize.value }
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

const getReasonTone = (reason: string | undefined): BadgeTone => {
  return reason === 'DUPLICATE' ? 'warning' : 'error'
}

const getReasonLabel = (reason: string | undefined): string => {
  const labels: Record<string, string> = {
    'DUPLICATE': '重复文件',
    'UNREFERENCED': '未引用文件'
  }
  return labels[reason || ''] || reason || '-'
}

watch([page, pageSize], () => {
  fetchLogs()
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

/* 日志容器 */
.logs-container {
  overflow: hidden;
  padding-bottom: 4px;
}

/* 表格：列宽等本地细节（基础样式来自 .st-table） */
.logs-table {
  table-layout: fixed;
  min-width: 680px;
}

.logs-table th,
.logs-table td {
  white-space: nowrap;
}

.col-filename { width: 34%; }
.col-size { width: 13%; }
.col-reason { width: 14%; }
.col-operator { width: 12%; }
.col-time { width: 27%; color: var(--st-text-tertiary); font-size: 13px; }

.filename {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 响应式 */
@media (max-width: 768px) {
  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
