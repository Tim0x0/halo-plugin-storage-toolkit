<template>
  <div class="reference-tab">
    <!-- 操作栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <button class="btn-scan" @click="startScan" :disabled="scanning">
          <span v-if="scanning">扫描中...</span>
          <span v-else>开始扫描</span>
        </button>
        <button class="btn-clear" @click="clearRecords" :disabled="scanning || !stats.lastScanTime">
          清空记录
        </button>
        <button 
          class="btn-delete" 
          @click="deleteSelected" 
          :disabled="scanning || selectedAttachments.length === 0"
          v-if="filterType === 'unreferenced' && attachmentList.length > 0"
        >
          删除选中 ({{ selectedAttachments.length }})
        </button>
        <span class="scan-info" v-if="stats.lastScanTime">上次扫描：{{ formatTime(stats.lastScanTime) }}</span>
        <span class="scan-info" v-else-if="stats.phase === 'SCANNING'">正在扫描...</span>
        <span class="scan-info error" v-else-if="stats.phase === 'ERROR'">扫描失败：{{ stats.errorMessage }}</span>
      </div>
      <div class="toolbar-right">
        <div class="filter-wrapper">
           <span class="filter-hint" v-if="filterType !== 'unreferenced'">切换至「未引用」可批量删除附件</span>
            <select v-model="filterType" class="filter-select" @change="handleFilterChange">
            <option value="all">全部</option>
            <option value="referenced">已引用</option>
            <option value="unreferenced">未引用</option>
          </select>
        </div>
        <input
          type="text"
          v-model="searchQuery"
          placeholder="搜索文件名..."
          class="search-input"
          @input="handleSearchDebounced"
        />
      </div>
    </div>

    <!-- 提示信息 -->
    <div class="notice warning">
      <span class="notice-icon">💡</span>
      <span>支持扫描文章、页面、评论、封面图、系统设置、插件设置、主题设置，可在插件设置中开启瞬间、图库和文档扫描</span>
    </div>

    <!-- 统计概览 -->
    <div class="stats-row">
      <div class="stat-box">
        <span class="stat-num">{{ referenceRate }}%</span>
        <span class="stat-text">引用率</span>
      </div>
      <div class="stat-box">
        <span class="stat-num green">{{ stats.referencedCount }}</span>
        <span class="stat-text">已引用</span>
      </div>
      <div class="stat-box">
        <span class="stat-num orange">{{ stats.unreferencedCount }}</span>
        <span class="stat-text">未引用</span>
      </div>
      <div class="stat-box">
        <span class="stat-num orange">{{ formatBytes(stats.unreferencedSize) }}</span>
        <span class="stat-text">未引用占用</span>
      </div>
    </div>

    <!-- 附件列表 -->
    <div class="card">
      <div v-if="loading" class="loading-state">加载中...</div>
      <div v-else-if="!stats.lastScanTime && stats.phase !== 'SCANNING'" class="empty-state">
        请先点击「开始扫描」按钮进行扫描
      </div>
      <div v-else-if="attachmentList.length === 0" class="empty-state">
        没有符合条件的附件
      </div>
      <template v-else>
        <table class="data-table">
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
              <th>文件名</th>
              <th>类型</th>
              <th>大小</th>
              <th class="sortable" @click="toggleSort('referenceCount')">
                引用次数
                <span v-if="sortField === 'referenceCount'">{{ sortDesc ? '↓' : '↑' }}</span>
              </th>
              <th>引用位置</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in attachmentList" :key="item.attachmentName" :class="{ highlighted: highlightedAttachment === item.attachmentName, selected: selectedAttachments.includes(item.attachmentName) }">
              <td class="col-checkbox" v-if="filterType === 'unreferenced'">
                <input 
                  type="checkbox" 
                  :checked="selectedAttachments.includes(item.attachmentName)"
                  @change="toggleSelect(item.attachmentName)"
                />
              </td>
              <td class="cell-name">
                <img 
                  v-if="item.mediaType?.startsWith('image/') && item.permalink" 
                  :src="item.permalink" 
                  class="file-thumbnail"
                  @error="(e: Event) => (e.target as HTMLImageElement).style.display = 'none'"
                />
                <span v-else class="file-icon">{{ getFileIcon(item.mediaType) }}</span>
                <span class="file-name-text" @click="showReferenceDetail(item)">{{ item.displayName }}</span>
              </td>
              <td>{{ item.mediaType }}</td>
              <td>{{ formatBytes(item.size) }}</td>
              <td>
                <span 
                  :class="['ref-count', item.referenceCount > 0 ? 'has-ref' : 'no-ref']"
                  @click="item.referenceCount > 0 && showReferenceDetail(item)"
                  :style="{ cursor: item.referenceCount > 0 ? 'pointer' : 'default' }"
                >
                  {{ item.referenceCount }}
                </span>
              </td>
              <td>
                <div class="ref-locations" v-if="item.references && item.references.length > 0">
                  <span 
                    :class="['location-tag', getSourceTypeClass(type)]" 
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

        <!-- 分页 -->
        <div class="pagination" v-if="total > 0">
          <div class="page-info">共 {{ total }} 条</div>
          <div class="page-controls">
            <button type="button" class="page-btn" :disabled="page <= 1" @click="changePage(page - 1)">上一页</button>
            <span class="page-num">{{ page }} / {{ totalPages }}</span>
            <button type="button" class="page-btn" :disabled="page >= totalPages" @click="changePage(page + 1)">下一页</button>
          </div>
          <select v-model="pageSize" class="page-size" @change="handlePageSizeChange">
            <option v-for="size in PAGE_SIZE_OPTIONS" :key="size" :value="size">
              {{ size }}条/页
            </option>
          </select>
        </div>
      </template>
    </div>

    <!-- 引用详情对话框 -->
    <div class="modal-overlay" v-if="showDetailModal" @click.self="showDetailModal = false">
      <div class="modal-content">
        <div class="modal-header">
          <h3>{{ selectedAttachment?.displayName }}</h3>
          <button class="modal-close" @click="showDetailModal = false">×</button>
        </div>
        <div class="modal-body">
          <!-- 预览区域 -->
          <div class="preview-area" v-if="selectedAttachment?.mediaType?.startsWith('image/') && selectedAttachment?.permalink">
            <img :src="selectedAttachment.permalink" class="preview-image" />
          </div>
          <div class="preview-area preview-placeholder" v-else>
            <span class="preview-icon">{{ getFileIcon(selectedAttachment?.mediaType || '') }}</span>
          </div>
          
          <!-- 文件信息 -->
          <div class="info-section">
            <div class="info-item">
              <span class="info-label">大小</span>
              <span class="info-value">{{ formatBytes(selectedAttachment?.size || 0) }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">类型</span>
              <span class="info-value">{{ selectedAttachment?.mediaType || '未知' }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">存储策略</span>
              <span class="info-value">{{ policyDisplayName ?? '加载中...' }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">分组</span>
              <span class="info-value">{{ groupDisplayName ?? '加载中...' }}</span>
            </div>
            <div class="info-item" v-if="selectedAttachment?.permalink">
              <span class="info-label">链接</span>
              <span class="info-value info-url">{{ selectedAttachment.permalink }}</span>
            </div>
          </div>
          
          <!-- 引用列表 -->
          <ReferenceList
            :references="selectedAttachment?.references"
            @reference-resolved="handleReferenceResolved"
          />
        </div>
      </div>
    </div>
  </div>
</template>


<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { axiosInstance } from '@halo-dev/api-client'
import { Dialog, Toast } from '@halo-dev/components'
import { PAGE_SIZE_OPTIONS, DEFAULT_PAGE_SIZE } from '@/constants/pagination'
import { API_ENDPOINTS } from '@/constants/api'
import { formatBytes, formatTime } from '@/utils/format'
import type { ReferenceSource } from '@/types/duplicate'
import { getFileIcon, getSourceTypeLabel, getSourceTypeClass, getUniqueSourceTypes } from '@/composables/useReferenceSource'
import ReferenceList from '@/components/ReferenceList.vue'
import type { ReferenceSourceItem } from '@/components/ReferenceList.vue'

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

const route = useRoute()

const loading = ref(false)
const scanning = ref(false)
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

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

const isAllSelected = computed(() => {
  return attachmentList.value.length > 0 && 
    attachmentList.value.every(item => selectedAttachments.value.includes(item.attachmentName))
})

const isIndeterminate = computed(() => {
  return selectedAttachments.value.length > 0 && !isAllSelected.value
})

let searchDebounceTimer: ReturnType<typeof setTimeout> | null = null
let pollTimerRef: ReturnType<typeof setTimeout> | null = null

const handleSearchDebounced = () => {
  if (searchDebounceTimer) clearTimeout(searchDebounceTimer)
  searchDebounceTimer = setTimeout(() => {
    page.value = 1
    fetchReferences()
  }, 300)
}

const handleFilterChange = () => {
  page.value = 1
  selectedAttachments.value = []
  fetchReferences()
}

const handlePageSizeChange = () => {
  page.value = 1
  selectedAttachments.value = []
  fetchReferences()
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

const deleteSelected = () => {
  if (selectedAttachments.value.length === 0) return

  Dialog.warning({
    title: '确认删除附件',
    description: `确定要永久删除选中的 ${selectedAttachments.value.length} 个附件文件吗？此操作将删除存储中的文件，不可恢复。`,
    confirmType: 'danger',
    confirmText: '删除',
    cancelText: '取消',
    async onConfirm() {
      try {
        const toDelete = [...selectedAttachments.value]
        await axiosInstance.delete(API_ENDPOINTS.CLEANUP_UNREFERENCED, {
          data: { attachmentNames: toDelete }
        })
        Toast.success('删除成功')
        // 从前端列表中移除已删除项，不请求后端
        attachmentList.value = attachmentList.value.filter(
          item => !toDelete.includes(item.attachmentName)
        )
        total.value = Math.max(0, total.value - toDelete.length)
        selectedAttachments.value = []
      } catch (error: any) {
        Toast.error('删除失败: ' + (error.response?.data?.message || error.message))
      }
    }
  })
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

const changePage = (newPage: number) => {
  if (newPage >= 1 && newPage <= totalPages.value) {
    page.value = newPage
    fetchReferences()
  }
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
  } catch (error) {
    console.error('获取引用列表失败:', error)
  } finally {
    loading.value = false
  }
}

const startScan = async () => {
  scanning.value = true
  try {
    await axiosInstance.post(API_ENDPOINTS.REFERENCES_SCAN)
    // 轮询扫描状态
    pollScanStatus()
  } catch (error: any) {
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
      } catch (error: any) {
        Toast.error('清空记录失败')
        console.error('清空记录失败:', error)
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
    } catch (e) {
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
    } catch (e) {
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
  if (searchDebounceTimer) {
    clearTimeout(searchDebounceTimer)
  }
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

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.toolbar-left, .toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.btn-scan {
  padding: 8px 16px;
  font-size: 14px;
  background: #18181b;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-scan:hover:not(:disabled) {
  background: #27272a;
}

.btn-scan:disabled {
  background: #a1a1aa;
}

.btn-clear {
  padding: 8px 16px;
  font-size: 14px;
  background: white;
  color: #dc2626;
  border: 1px solid #fecaca;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-clear:hover:not(:disabled) {
  background: #fef2f2;
  border-color: #f87171;
}

.btn-clear:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-delete {
  padding: 8px 16px;
  font-size: 14px;
  background: #dc2626;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-delete:hover:not(:disabled) {
  background: #b91c1c;
}

.btn-delete:disabled {
  background: #fca5a5;
  cursor: not-allowed;
}

.scan-info {
  font-size: 13px;
  color: #71717a;
}

.scan-info.error {
  color: #dc2626;
}

.filter-wrapper {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-select, .search-input {
  padding: 8px 12px;
  font-size: 14px;
  border: 1px solid #e4e4e7;
  border-radius: 6px;
  background: white;
}

.filter-hint {
  font-size: 12px;
  color: #16a34a;
  white-space: nowrap;
}

.search-input {
  width: 200px;
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

.notice {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border-radius: 8px;
  font-size: 13px;
}

.notice.warning {
  background: #fef3c7;
  color: #92400e;
}

.card {
  background: white;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 0;
  overflow: hidden;
}

.loading-state, .empty-state {
  padding: 48px;
  text-align: center;
  color: #71717a;
  font-size: 14px;
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

.data-table th.sortable {
  cursor: pointer;
  user-select: none;
}

.data-table th.sortable:hover {
  background: #f4f4f5;
}

.col-checkbox {
  width: 40px;
  text-align: center;
}

.col-checkbox input[type="checkbox"] {
  width: 16px;
  height: 16px;
  cursor: pointer;
}

.data-table td {
  font-size: 14px;
  color: #18181b;
}

.data-table tr.highlighted {
  background: #fef3c7;
}

.data-table tbody tr {
  transition: background 0.15s;
}

.data-table tbody tr:hover {
  background: #fafafa;
}

.data-table tbody tr.selected {
  background: #eff6ff;
}

.data-table tbody tr.selected:hover {
  background: #dbeafe;
}

.data-table tbody tr.highlighted:hover {
  background: #fef3c7;
}

.cell-name {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.cell-name:hover .file-name-text {
  color: #2563eb;
}

.file-name-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color 0.15s;
}

.file-icon {
  font-size: 16px;
}

.ref-count {
  display: inline-block;
  min-width: 24px;
  padding: 4px 10px;
  text-align: center;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}

.ref-count.has-ref {
  background: #dcfce7;
  color: #166534;
}

.ref-count.no-ref {
  background: #fef3c7;
  color: #92400e;
}

.ref-locations {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.location-tag {
  font-size: 12px;
  padding: 2px 8px;
  background: #f4f4f5;
  border-radius: 4px;
  color: #3f3f46;
}

.location-tag.tag-blue {
  background: #dbeafe;
  color: #1d4ed8;
}

.location-tag.tag-green {
  background: #dcfce7;
  color: #15803d;
}

.location-tag.tag-teal {
  background: #ccfbf1;
  color: #0f766e;
}

.location-tag.tag-pink {
  background: #fce7f3;
  color: #be185d;
}

.location-tag.tag-purple {
  background: #f3e8ff;
  color: #7c3aed;
}

.location-tag.tag-orange {
  background: #ffedd5;
  color: #c2410c;
}

.location-tag.tag-indigo {
  background: #e0e7ff;
  color: #4338ca;
}

.location-tag.tag-cyan {
  background: #cffafe;
  color: #0891b2;
}

.location-tag.tag-amber {
  background: #fef3c7;
  color: #b45309;
}

.no-location {
  color: #a1a1aa;
}

/* 分页 */
.pagination {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  border-top: 1px solid #f4f4f5;
}

.page-info {
  font-size: 13px;
  color: #71717a;
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
  border: 1px solid #e4e4e7;
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
  border: 1px solid #e4e4e7;
  border-radius: 4px;
  background: white;
}

/* 模态框 */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-content {
  background: white;
  border-radius: 8px;
  width: 90%;
  max-width: 560px;
  max-height: 85vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04);
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 16px;
  border-bottom: 1px solid #f4f4f5;
}

.modal-header h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  color: #18181b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  padding-right: 12px;
}

.modal-close {
  width: 28px;
  height: 28px;
  border: none;
  background: none;
  font-size: 20px;
  color: #a1a1aa;
  cursor: pointer;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.modal-close:hover {
  background: #f4f4f5;
  color: #71717a;
}

.modal-body {
  padding: 0;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}

.modal-body:hover {
  scrollbar-color: rgba(0, 0, 0, 0.2) transparent;
}

.modal-body::-webkit-scrollbar {
  width: 6px;
}

.modal-body::-webkit-scrollbar-track {
  background: transparent;
}

.modal-body::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: 3px;
}

.modal-body:hover::-webkit-scrollbar-thumb {
  background: rgba(0, 0, 0, 0.2);
}

/* 预览区域 */
.preview-area {
  width: 100%;
  height: 200px;
  background: #fafafa;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.preview-area .preview-image {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.preview-area.preview-placeholder {
  background: #f4f4f5;
}

.preview-icon {
  font-size: 48px;
  opacity: 0.4;
}

/* 文件信息区域 */
.info-section {
  padding: 16px;
  border-bottom: 1px solid #f4f4f5;
}

.info-item {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 8px 0;
}

.info-item:first-child {
  padding-top: 0;
}

.info-item:last-child {
  padding-bottom: 0;
}

.info-label {
  font-size: 13px;
  color: #71717a;
  flex-shrink: 0;
}

.info-value {
  font-size: 13px;
  color: #18181b;
  text-align: right;
  word-break: break-all;
  margin-left: 16px;
}

.info-value.info-url {
  font-size: 12px;
  color: #71717a;
}

.file-thumbnail {
  width: 24px;
  height: 24px;
  object-fit: cover;
  border-radius: 4px;
  flex-shrink: 0;
}
</style>
