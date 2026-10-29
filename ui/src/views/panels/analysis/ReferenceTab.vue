<template>
  <div class="reference-tab">
    <!-- 操作栏 -->
    <div class="st-toolbar">
      <div class="st-toolbar-left">
        <VButton type="secondary" :loading="scanning" @click="startScan">开始扫描</VButton>
        <VButton type="danger" ghost :disabled="scanning || !stats.lastScanTime" @click="clearRecords">清空记录</VButton>
        <VButton
          type="danger"
          :loading="deleting"
          @click="deleteSelected"
          :disabled="scanning || deleting || selectedAttachments.length === 0"
          v-if="filterType === 'unreferenced' && attachmentList.length > 0"
        >
          删除选中 ({{ selectedAttachments.length }})
        </VButton>
        <span class="scan-info" v-if="stats.lastScanTime">上次扫描：{{ formatTime(stats.lastScanTime) }}</span>
        <span class="scan-info error" v-else-if="stats.phase === 'ERROR'">扫描失败：{{ stats.errorMessage }}</span>
      </div>
      <div class="st-toolbar-right">
        <div class="filter-wrapper">
          <span class="filter-hint" v-if="filterType !== 'unreferenced'">切换至「未引用」可批量删除附件</span>
          <select v-model="filterType" class="st-select" @change="handleFilterChange">
            <option value="all">全部</option>
            <option value="referenced">已引用</option>
            <option value="unreferenced">未引用</option>
          </select>
        </div>
        <SearchInput v-model="searchQuery" placeholder="搜索文件名..." @search="handleSearch" />
      </div>
    </div>

    <!-- 提示信息 -->
    <VAlert
      type="warning"
      title="扫描范围"
      description="支持扫描文章、页面、评论、封面图、系统设置、插件设置、主题设置，可在插件设置中开启瞬间、图库和文档扫描"
    />
    <VAlert
      v-if="cleanupErrors.length > 0"
      type="warning"
      title="部分附件未能删除"
      :description="cleanupErrors.join('；')"
    />

    <!-- 统计概览 -->
    <div class="stats-row">
      <StatCard label="引用率" :value="`${referenceRate}%`" :icon="IconDashboard" />
      <StatCard label="已引用" :value="stats.referencedCount" tone="success" :icon="IconLink" />
      <StatCard label="未引用" :value="stats.unreferencedCount" tone="warning" :icon="IconForbidLine" />
      <StatCard label="未引用占用" :value="formatBytes(stats.unreferencedSize)" tone="warning" :icon="IconSave" />
    </div>

    <!-- 附件列表 -->
    <div class="ref-card st-card">
      <VLoading v-if="loading" />

      <VEmpty
        v-else-if="!stats.lastScanTime && stats.phase !== 'SCANNING'"
        title="尚未扫描"
        message="请先点击「开始扫描」按钮进行扫描"
      >
        <template #actions>
          <VButton type="secondary" :loading="scanning" @click="startScan">开始扫描</VButton>
        </template>
      </VEmpty>

      <VEmpty v-else-if="scanning" title="正在扫描..." message="扫描完成后将显示附件引用情况" />

      <VEmpty v-else-if="attachmentList.length === 0" title="没有符合条件的附件" />

      <template v-else>
        <div class="st-table-wrapper">
          <table class="st-table ref-table">
            <thead>
              <tr>
                <th class="col-checkbox" v-if="filterType === 'unreferenced'">
                  <input
                    type="checkbox"
                    :checked="isAllSelected"
                    :indeterminate="isIndeterminate"
                    @change="toggleSelectAll"
                  />
                </th>
                <th class="col-filename">文件名</th>
                <th class="col-type">类型</th>
                <th class="col-size">大小</th>
                <th class="col-ref-count st-th--sortable" @click="toggleSort('referenceCount')">
                  引用次数
                  <span v-if="sortField === 'referenceCount'">{{ sortDesc ? '↓' : '↑' }}</span>
                </th>
                <th class="col-ref-location">引用位置</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in attachmentList"
                :key="item.attachmentName"
                :class="{
                  'st-table-row--highlighted': highlightedAttachment === item.attachmentName,
                  'st-table-row--selected': selectedAttachments.includes(item.attachmentName)
                }"
              >
                <td class="col-checkbox" v-if="filterType === 'unreferenced'">
                  <input
                    type="checkbox"
                    :checked="selectedAttachments.includes(item.attachmentName)"
                    @change="toggleSelect(item.attachmentName)"
                  />
                </td>
                <td class="cell-name">
                  <div class="cell-name-inner">
                    <img
                      v-if="item.mediaType?.startsWith('image/') && item.permalink"
                      :src="utils.attachment.getThumbnailUrl(item.permalink, 'S')"
                      class="file-thumbnail"
                      @error="(e: Event) => (e.target as HTMLImageElement).style.display = 'none'"
                    />
                    <span v-else class="file-icon">{{ getFileIcon(item.mediaType) }}</span>
                    <span class="file-name-text" @click="showReferenceDetail(item)" :title="item.displayName">{{ item.displayName }}</span>
                  </div>
                </td>
                <td>{{ item.mediaType }}</td>
                <td>{{ formatBytes(item.size) }}</td>
                <td>
                  <StatusBadge
                    :tone="item.referenceCount > 0 ? 'success' : 'warning'"
                    :style="{ cursor: item.referenceCount > 0 ? 'pointer' : 'default' }"
                    @click="item.referenceCount > 0 && showReferenceDetail(item)"
                  >
                    {{ item.referenceCount }}
                  </StatusBadge>
                </td>
                <td class="col-ref-location">
                  <div class="ref-locations" v-if="item.references && item.references.length > 0">
                    <span
                      :class="['st-tag', getSourceTypeClass(type)]"
                      v-for="type in getUniqueSourceTypes(item.references)"
                      :key="type"
                      :title="getSourceTypeLabel(type)"
                    >
                      {{ getSourceTypeLabel(type) }}
                    </span>
                  </div>
                  <span class="no-location" v-else>-</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 分页 -->
        <VPagination v-if="total > 0" v-model:page="page" v-model:size="pageSize" :total="total" :size-options="PAGE_SIZE_OPTIONS" />
      </template>
    </div>

    <!-- 引用详情对话框 -->
    <ReferenceDetailModal
      v-if="showDetailModal"
      :attachment="selectedAttachment"
      :policy-display-name="policyDisplayName"
      :group-display-name="groupDisplayName"
      @close="showDetailModal = false"
      @reference-resolved="handleReferenceResolved"
    />
  </div>
  <VModal v-model:visible="showCleanupPreview" title="删除影响预览" :width="720" mount-to-body>
    <div class="st-scope">
      <p>确认后将永久删除可执行的附件，跳过项会保留。</p>
      <OperationImpactPreview v-if="showCleanupPreview" operation="UNREFERENCED"
        :attachment-names="selectedAttachments" @ready="previewEligibleNames = $event" />
    </div>
    <template #footer>
      <VButton type="danger" :disabled="!previewEligibleNames.length" @click="confirmCleanup">删除</VButton>
      <VButton @click="showCleanupPreview = false">取消</VButton>
    </template>
  </VModal>

</template>


<script setup lang="ts">
import OperationImpactPreview from '../components/OperationImpactPreview.vue'
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { axiosInstance } from '@halo-dev/api-client'
import { VModal, Dialog, Toast, VAlert, VButton, VEmpty, VLoading, VPagination, IconDashboard, IconLink, IconForbidLine, IconSave } from '@halo-dev/components'
import { PAGE_SIZE_OPTIONS, DEFAULT_PAGE_SIZE } from '@/constants/pagination'
import { API_ENDPOINTS } from '@/constants/api'
import { formatBytes, formatTime } from '@/utils/format'
import type { ReferenceSource } from '@/types/duplicate'
import { getFileIcon, getSourceTypeLabel, getSourceTypeClass, getUniqueSourceTypes } from '@/composables/useReferenceSource'
import type { ReferenceSourceItem } from '@/components/ReferenceList.vue'
import StatCard from '@/components/StatCard.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import SearchInput from '@/components/SearchInput.vue'
import ReferenceDetailModal from '@/views/panels/components/ReferenceDetailModal.vue'
import { utils } from '@halo-dev/ui-shared'

interface AttachmentReferenceVo {
  attachmentName: string
  displayName: string
  mediaType: string
  size: number
  permalink: string | null
  policyName: string | null
  groupName: string | null
  referenceCount: number
  references: ReferenceSource[]
}

interface StatsResponse {
  phase: 'SCANNING' | 'COMPLETED' | 'ERROR' | null
  lastScanTime: string | null
  totalAttachments: number
  referencedCount: number
  unreferencedCount: number
  unreferencedSize: number
  errorMessage: string | null
}

interface CleanupResultResponse {
  deletedCount: number
  failedCount: number
  freedSize: number
  errors: string[]
}

const route = useRoute()

const loading = ref(false)
const scanning = ref(false)
const deleting = ref(false)
const cleanupErrors = ref<string[]>([])
const filterType = ref('all')
const searchQuery = ref('')
const page = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)
const total = ref(0)
const sortField = ref('referenceCount')
const sortDesc = ref(true)

const stats = ref<StatsResponse>({
  phase: null,
  lastScanTime: null,
  totalAttachments: 0,
  referencedCount: 0,
  unreferencedCount: 0,
  unreferencedSize: 0,
  errorMessage: null
})

const attachmentList = ref<AttachmentReferenceVo[]>([])
const showDetailModal = ref(false)
const selectedAttachment = ref<AttachmentReferenceVo | null>(null)
const highlightedAttachment = ref<string | null>(null)
const policyDisplayName = ref<string | null>(null)
const groupDisplayName = ref<string | null>(null)
const selectedAttachments = ref<string[]>([])

const referenceRate = computed(() => {
  const total = stats.value?.totalAttachments ?? 0
  const referenced = stats.value?.referencedCount ?? 0
  if (total === 0) return '0.00'
  return ((referenced / total) * 100).toFixed(2)
})

const isAllSelected = computed(() => {
  return attachmentList.value.length > 0 &&
    attachmentList.value.every(item => selectedAttachments.value.includes(item.attachmentName))
})

const isIndeterminate = computed(() => {
  return selectedAttachments.value.length > 0 && !isAllSelected.value
})

let pollTimerRef: ReturnType<typeof setTimeout> | null = null

const handleSearch = () => {
  if (page.value !== 1) {
    page.value = 1
  } else {
    fetchReferences()
  }
}

const handleFilterChange = () => {
  selectedAttachments.value = []
  if (page.value !== 1) {
    page.value = 1
  } else {
    fetchReferences()
  }
}

const toggleSelectAll = () => {
  if (isAllSelected.value) {
    selectedAttachments.value = []
  } else {
    selectedAttachments.value = attachmentList.value.map(item => item.attachmentName)
  }
}

const toggleSelect = (attachmentName: string) => {
  const index = selectedAttachments.value.indexOf(attachmentName)
  if (index === -1) {
    selectedAttachments.value.push(attachmentName)
  } else {
    selectedAttachments.value.splice(index, 1)
  }
}

const showCleanupPreview = ref(false)
const previewEligibleNames = ref<string[]>([])
const deleteSelected = () => {
  if (deleting.value || !selectedAttachments.value.length) return
  showCleanupPreview.value = true
}
const confirmCleanup = async () => {
  if (!previewEligibleNames.value.length) return
  showCleanupPreview.value = false
      deleting.value = true
      cleanupErrors.value = []
      try {
        const toDelete = [...previewEligibleNames.value]
        const { data } = await axiosInstance.delete<CleanupResultResponse>(API_ENDPOINTS.CLEANUP_UNREFERENCED, {
          data: { attachmentNames: toDelete }
        })
        cleanupErrors.value = data.errors || []
        const failedNames = toDelete.filter(name =>
          cleanupErrors.value.some(error => error.startsWith(`${name}: `))
        )
        // 无法逐项识别失败时保留选择，避免把失败项当作成功移除。
        selectedAttachments.value = data.failedCount === 0 ? []
          : failedNames.length === data.failedCount ? failedNames : toDelete
        if (data.failedCount === 0) {
          Toast.success(`删除成功，共 ${data.deletedCount} 个文件`)
        } else if (data.deletedCount > 0) {
          Toast.warning(`部分删除成功：${data.deletedCount} 个成功，${data.failedCount} 个失败`)
        } else {
          Toast.error(`删除失败，共 ${data.failedCount} 个文件未能删除`)
        }
        await Promise.all([fetchReferences(), fetchStats()])
      } catch (error: unknown) {
        const err = error as { response?: { data?: { message?: string } }; message?: string }
        Toast.error('删除失败: ' + (err.response?.data?.message || err.message || '未知错误'))
      } finally {
        deleting.value = false
      }
}

const toggleSort = (field: string) => {
  if (sortField.value === field) {
    sortDesc.value = !sortDesc.value
  } else {
    sortField.value = field
    sortDesc.value = true
  }
  fetchReferences()
}

const fetchStats = async () => {
  try {
    const { data } = await axiosInstance.get<StatsResponse>(API_ENDPOINTS.REFERENCES_STATS)
    stats.value = data
    scanning.value = data.phase === 'SCANNING'
  } catch (error) {
    console.error('获取统计数据失败:', error)
  }
}

const fetchReferences = async () => {
  loading.value = true
  try {
    const params = new URLSearchParams({
      filter: filterType.value,
      page: String(page.value),
      size: String(pageSize.value)
    })
    if (searchQuery.value) {
      params.set('keyword', searchQuery.value)
    }
    if (sortField.value) {
      params.set('sort', `${sortField.value},${sortDesc.value ? 'desc' : 'asc'}`)
    }

    const { data } = await axiosInstance.get(`${API_ENDPOINTS.REFERENCES}?${params.toString()}`)
    attachmentList.value = data.items || []
    total.value = data.total || 0
    const lastPage = Math.max(1, Math.ceil(total.value / pageSize.value))
    if (page.value > lastPage) {
      page.value = lastPage
    }
  } catch (error) {
    console.error('获取引用列表失败:', error)
  } finally {
    loading.value = false
  }
}

const startScan = async () => {
  scanning.value = true
  // 后端在 performScan 第一步就删光了旧的 AttachmentReference 记录，
  // 前端不主动清就会一直显示上一次的结果，直到扫描结束才刷新
  attachmentList.value = []
  total.value = 0
  selectedAttachments.value = []
  page.value = 1
  try {
    await axiosInstance.post(API_ENDPOINTS.REFERENCES_SCAN)
    // 轮询扫描状态
    pollScanStatus()
  } catch {
    scanning.value = false
    // 错误信息由 Halo 统一处理，这里不需要额外弹窗
  }
}

const clearRecords = () => {
  Dialog.warning({
    title: '确认清空',
    description: '确定要清空所有引用扫描记录吗？此操作不可恢复。',
    confirmType: 'danger',
    confirmText: '清空',
    cancelText: '取消',
    async onConfirm() {
      try {
        await axiosInstance.delete(API_ENDPOINTS.REFERENCES_CLEAR)
        Toast.success('引用扫描记录已清空')
        // 重置状态
        stats.value = {
          phase: null,
          lastScanTime: null,
          totalAttachments: 0,
          referencedCount: 0,
          unreferencedCount: 0,
          unreferencedSize: 0,
          errorMessage: null
        }
        attachmentList.value = []
        total.value = 0
      } catch {
        Toast.error('清空记录失败')
      }
    }
  })
}

const pollScanStatus = () => {
  const poll = async () => {
    try {
      await fetchStats()
      if (stats.value.phase === 'SCANNING') {
        pollTimerRef = setTimeout(poll, 2000)
      } else {
        scanning.value = false
        fetchReferences()
      }
    } catch (error) {
      console.error('轮询扫描状态失败:', error)
      scanning.value = false
    }
  }
  poll()
}

const showReferenceDetail = async (item: AttachmentReferenceVo) => {
  selectedAttachment.value = item
  policyDisplayName.value = null
  groupDisplayName.value = null
  showDetailModal.value = true

  // 异步获取 Policy displayName
  if (item.policyName) {
    try {
      const { data } = await axiosInstance.get(API_ENDPOINTS.REFERENCES_POLICY(item.policyName))
      policyDisplayName.value = data.displayName
    } catch {
      policyDisplayName.value = item.policyName
    }
  } else {
    policyDisplayName.value = '默认策略'
  }

  // 异步获取 Group displayName
  if (item.groupName) {
    try {
      const { data } = await axiosInstance.get(API_ENDPOINTS.REFERENCES_GROUP(item.groupName))
      groupDisplayName.value = data.displayName
    } catch {
      groupDisplayName.value = item.groupName
    }
  } else {
    groupDisplayName.value = '未分组'
  }
}

// 引用解析完成后更新后端缓存
const handleReferenceResolved = async (resolvedRef: ReferenceSourceItem) => {
  if (!selectedAttachment.value) return
  try {
    await axiosInstance.put(
      API_ENDPOINTS.REFERENCES_SOURCE(selectedAttachment.value.attachmentName, resolvedRef.sourceName),
      null,
      { params: { sourceTitle: resolvedRef.sourceTitle, sourceUrl: resolvedRef.sourceUrl } }
    )
  } catch (e) {
    console.debug('更新后端缓存失败:', e)
  }
}

// 处理 URL 参数（从 Halo 附件管理跳转）
const handleUrlParams = () => {
  const attachmentName = route.query.attachment as string
  if (attachmentName) {
    highlightedAttachment.value = attachmentName
    searchQuery.value = ''
    // 3 秒后取消高亮
    setTimeout(() => {
      highlightedAttachment.value = null
    }, 3000)
  }
}

onMounted(async () => {
  await fetchStats()
  if (stats.value.phase === 'SCANNING') {
    scanning.value = true
    pollScanStatus()
  } else if (stats.value.lastScanTime) {
    await fetchReferences()
  }
  handleUrlParams()
})

onUnmounted(() => {
  if (pollTimerRef) {
    clearTimeout(pollTimerRef)
  }
})

watch([page, pageSize], ([, newSize], [, oldSize]) => {
  if (newSize !== oldSize) {
    selectedAttachments.value = []
  }
  // 扫描期间不拉取：后端的旧数据清理是异步的，此时拉取可能把尚未清完的旧数据
  // 又填回列表。扫描结束时 pollScanStatus 会统一刷新
  if (scanning.value) return
  fetchReferences()
})

watch(() => route.query.attachment, () => {
  handleUrlParams()
})
</script>


<style scoped>
.reference-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.scan-info {
  font-size: 13px;
  color: var(--st-text-secondary);
}

.scan-info.error {
  color: var(--st-error-text);
}

.filter-wrapper {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-hint {
  font-size: 12px;
  color: var(--st-success-text);
  white-space: nowrap;
}

/* 统计概览 */
.stats-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

/* 附件列表容器 */
.ref-card {
  overflow: hidden;
}

/* 表格：列宽等本地细节（基础样式来自 .st-table） */
.ref-table {
  table-layout: fixed;
  min-width: 560px;
}

.ref-table td {
  font-size: 14px;
  white-space: nowrap;
}

.col-checkbox {
  width: 40px;
  text-align: center;
}

.col-filename { width: 30%; }
.col-type { width: 15%; }
.col-size { width: 12%; }
.col-ref-count { width: 14%; }
.col-ref-location { width: 29%; }

.col-checkbox input[type="checkbox"] {
  width: 16px;
  height: 16px;
  cursor: pointer;
}

.cell-name {
  cursor: pointer;
  min-width: 0;
}

.cell-name-inner {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.cell-name:hover .file-name-text {
  color: var(--st-info-text);
}

.file-name-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  transition: color var(--st-duration) var(--st-ease);
}

.file-icon {
  font-size: 16px;
}

.file-thumbnail {
  width: 24px;
  height: 24px;
  object-fit: cover;
  border-radius: var(--st-radius-sm);
  flex-shrink: 0;
}

.ref-locations {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.no-location {
  color: var(--st-text-tertiary);
}

/* 响应式 */
@media (max-width: 768px) {
  .stats-row {
    grid-template-columns: repeat(2, 1fr);
  }

  .filter-hint {
    display: none;
  }
}
</style>
