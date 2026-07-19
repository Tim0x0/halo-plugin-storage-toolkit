<template>
  <div class="duplicate-tab">
    <!-- 操作栏 -->
    <div class="st-toolbar">
      <div class="st-toolbar-left">
        <VButton type="secondary" :loading="scanning" @click="startScan">开始扫描</VButton>
        <VButton type="danger" ghost :disabled="scanning || !stats.lastScanTime" @click="clearRecords">
          清空记录
        </VButton>
        <VButton
          v-if="duplicateGroups.length > 0"
          type="danger"
          :disabled="totalSelectedCount === 0"
          @click="deleteSelected"
        >
          删除选中 ({{ totalSelectedCount }})
        </VButton>
        <span class="scan-info" v-if="stats.lastScanTime && !scanning">上次扫描：{{ formatTime(stats.lastScanTime) }}</span>
        <span class="scan-info error" v-else-if="stats.phase === 'ERROR'">扫描失败：{{ stats.errorMessage }}</span>
        <span class="status-hint" v-if="stats.enableRemoteStorage === false">🌐 仅扫描本地存储</span>
        <span class="status-hint" v-else-if="stats.enableRemoteStorage === true">🌐 已启用远程扫描</span>
      </div>
    </div>

    <!-- 提示 -->
    <VAlert
      type="info"
      title="检测说明"
      description="基于文件 MD5 哈希检测完全相同的文件，可删除冗余副本节省存储空间"
    />

    <!-- 扫描进度条 -->
    <div class="progress-section st-card" v-if="scanning">
      <StProgressBar
        :percent="progressPercent"
        tone="success"
        show-label
        :indeterminate="stats.totalCount <= 0"
      >
        <template #label>
          <span v-if="stats.totalCount <= 0">准备中...</span>
          <span v-else>正在扫描... {{ stats.scannedCount }}/{{ stats.totalCount }}</span>
        </template>
      </StProgressBar>
    </div>

    <!-- 统计 -->
    <div class="stats-row">
      <StatCard label="重复组" :value="stats.duplicateGroupCount" :icon="IconExchange" />
      <StatCard label="重复文件" :value="stats.duplicateFileCount" :icon="IconGrid" />
      <StatCard label="可节省空间" :value="formatBytes(stats.savableSize)" tone="success" :icon="IconSave" />
    </div>

    <!-- 重复文件组 -->
    <div class="duplicate-groups" v-if="duplicateGroups.length > 0">
      <div class="group-card st-card st-card--hover" v-for="group in duplicateGroups" :key="group.md5Hash">
        <div class="group-header">
          <div class="group-info">
            <input
              type="checkbox"
              class="group-checkbox"
              :checked="isGroupAllSelected(group)"
              :indeterminate="isGroupIndeterminate(group)"
              @change="toggleGroupSelect(group)"
            />
            <span class="group-hash">{{ group.md5Hash.substring(0, 8) }}...</span>
            <span class="group-count">{{ group.fileCount }} 个相同文件</span>
            <span class="group-size">单个 {{ formatBytes(group.fileSize) }}</span>
          </div>
        </div>
        <div class="group-files">
          <div
            class="file-item"
            v-for="file in group.files"
            :key="file.attachmentName"
            :class="{ recommended: file.recommended, selected: isFileSelected(group.md5Hash, file.attachmentName) }"
          >
            <div class="file-main">
              <input
                type="checkbox"
                class="file-checkbox"
                :checked="isFileSelected(group.md5Hash, file.attachmentName)"
                @change="toggleFileSelect(group.md5Hash, file.attachmentName)"
              />
              <img
                v-if="file.permalink && isImage(file.mediaType)"
                :src="utils.attachment.getThumbnailUrl(file.permalink, 'S')"
                class="file-thumb"
                loading="lazy"
              />
              <span v-else class="file-icon">{{ getFileIcon(file.mediaType) }}</span>
              <span class="file-name" @click="openPreview(file, group.fileSize)" :title="file.displayName">{{ file.displayName }}</span>
            </div>
            <div class="file-meta">
              <span class="group-badge" :title="file.groupDisplayName || '未分组'">{{ file.groupDisplayName || '未分组' }}</span>
              <StatusBadge v-if="file.recommended" tone="success">推荐保留</StatusBadge>
              <StatusBadge v-if="file.referenceCount < 0" tone="neutral">未扫描</StatusBadge>
              <StatusBadge v-else :tone="file.referenceCount > 0 ? 'success' : 'warning'">
                {{ file.referenceCount }} 次引用
              </StatusBadge>
              <span class="file-date" v-if="file.uploadTime">{{ formatTime(file.uploadTime) }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 分页 -->
        <VPagination v-if="total > 0" v-model:page="page" v-model:size="pageSize" :total="total" :size-options="PAGE_SIZE_OPTIONS" />
    </div>

    <!-- 空状态 -->
    <VEmpty
      v-else-if="!scanning && stats.phase === 'COMPLETED'"
      title="没有发现重复文件"
      message="所有文件都是唯一的"
    />

    <!-- 未扫描状态 -->
    <VEmpty
      v-else-if="!scanning && !stats.lastScanTime"
      title="尚未进行扫描"
      message="点击「开始扫描」检测重复文件"
    >
      <template #actions>
        <VButton type="secondary" @click="startScan">开始扫描</VButton>
      </template>
    </VEmpty>

    <!-- 预览模态框 -->
    <DuplicatePreviewModal
      v-if="showPreview && previewFile"
      :file="previewFile"
      :size="previewFileSize"
      @close="showPreview = false"
    />
  </div>

  <!-- 删除确认对话框 -->
  <VModal
    v-model:visible="showDeleteDialog"
    :width="480"
    mount-to-body
    @close="handleDeleteCancel"
  >
    <template #header>确认删除重复附件</template>
    <template #default>
      <div class="st-scope delete-confirm-content">
        <!-- 描述内容 -->
        <div class="confirm-description">
          确定要永久删除选中的 <strong>{{ totalSelectedCount }}</strong> 个重复附件文件吗？此操作将删除存储中的文件，不可恢复。
        </div>

        <!-- 测试版提示 -->
        <div class="confirm-alert confirm-alert-beta">
          <span class="alert-icon">🧪</span>
          <span>当前为测试版功能，使用前请备份重要数据</span>
        </div>

        <!-- 选项区域 -->
        <div class="confirm-option">
          <label class="confirm-checkbox-label">
            <input
              type="checkbox"
              v-model="deleteReplaceReferences"
              class="confirm-checkbox"
            />
            <span class="confirm-checkbox-text">
              <span class="checkbox-main">自动替换引用</span>
              <span class="checkbox-sub">测试版功能，有风险请谨慎使用</span>
            </span>
          </label>
          <div class="confirm-option-hint">⚠️ 此功能会修改文章/页面/评论/设置中的链接内容</div>
        </div>

        <!-- 底部提示 -->
        <div class="confirm-tip">
          💡 建议先执行「引用扫描」以确保引用正确替换
        </div>
      </div>
    </template>
    <template #footer>
      <div class="confirm-footer">
        <VButton type="danger" @click="handleDeleteConfirm">删除</VButton>
        <VButton @click="handleDeleteCancel">取消</VButton>
      </div>
    </template>
  </VModal>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import type { DuplicateStats, DuplicateGroup, DuplicateFile } from '@/types/duplicate'
import { axiosInstance } from '@halo-dev/api-client'
import { Dialog, Toast, VModal, VButton, VPagination, VAlert, VEmpty, IconExchange, IconGrid, IconSave } from '@halo-dev/components'
import { PAGE_SIZE_OPTIONS, DEFAULT_PAGE_SIZE } from '@/constants/pagination'
import { API_ENDPOINTS } from '@/constants/api'
import { formatBytes, formatTime } from '@/utils/format'
import { getFileIcon, isImage } from '@/composables/useReferenceSource'
import StatCard from '@/components/StatCard.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import StProgressBar from '@/components/StProgressBar.vue'
import DuplicatePreviewModal from '@/views/panels/components/DuplicatePreviewModal.vue'
import { utils } from '@halo-dev/ui-shared'

// 删除确认弹窗
const showDeleteDialog = ref(false)
const deleteReplaceReferences = ref(true)

const stats = ref<DuplicateStats>({
  phase: null,
  lastScanTime: null,
  startTime: null,
  totalCount: 0,
  scannedCount: 0,
  duplicateGroupCount: 0,
  duplicateFileCount: 0,
  savableSize: 0,
  errorMessage: null,
  enableRemoteStorage: false
})

const duplicateGroups = ref<DuplicateGroup[]>([])
const page = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)
const total = ref(0)
const scanning = ref(false)
// 选中状态: { md5Hash: [attachmentName, ...] }
const selectedFiles = ref<Record<string, string[]>>({})

// 预览相关
const showPreview = ref(false)
const previewFile = ref<DuplicateFile | null>(null)
const previewFileSize = ref<number>(0)

// 轮询定时器
const pollTimer = ref<number>()

const openPreview = (file: DuplicateFile, size: number) => {
  previewFile.value = file
  previewFileSize.value = size
  showPreview.value = true
}

const progressPercent = computed(() => {
  if (stats.value.totalCount <= 0) return 0
  const percent = (stats.value.scannedCount / stats.value.totalCount) * 100
  return Math.min(100, Math.round(percent * 100) / 100)
})

const totalSelectedCount = computed(() => {
  return Object.values(selectedFiles.value).reduce((sum, arr) => sum + arr.length, 0)
})

// 检查文件是否被选中
const isFileSelected = (md5Hash: string, attachmentName: string): boolean => {
  return selectedFiles.value[md5Hash]?.includes(attachmentName) ?? false
}

// 检查组内是否全选（排除推荐保留项）
const isGroupAllSelected = (group: DuplicateGroup): boolean => {
  const selectableFiles = group.files.filter(f => !f.recommended)
  if (selectableFiles.length === 0) return false
  const selected = selectedFiles.value[group.md5Hash] || []
  return selectableFiles.every(f => selected.includes(f.attachmentName))
}

// 检查组内是否部分选中
const isGroupIndeterminate = (group: DuplicateGroup): boolean => {
  const selected = selectedFiles.value[group.md5Hash] || []
  if (selected.length === 0) return false
  return !isGroupAllSelected(group)
}

// 切换单个文件选中
const toggleFileSelect = (md5Hash: string, attachmentName: string) => {
  if (!selectedFiles.value[md5Hash]) {
    selectedFiles.value[md5Hash] = []
  }
  const index = selectedFiles.value[md5Hash].indexOf(attachmentName)
  if (index === -1) {
    selectedFiles.value[md5Hash].push(attachmentName)
  } else {
    selectedFiles.value[md5Hash].splice(index, 1)
  }
}

// 切换组内全选（排除推荐保留项）
const toggleGroupSelect = (group: DuplicateGroup) => {
  const selectableFiles = group.files.filter(f => !f.recommended)
  if (isGroupAllSelected(group)) {
    selectedFiles.value[group.md5Hash] = []
  } else {
    selectedFiles.value[group.md5Hash] = selectableFiles.map(f => f.attachmentName)
  }
}

// 删除选中的文件
const deleteSelected = () => {
  if (totalSelectedCount.value === 0) return
  showDeleteDialog.value = true
}

// 确认删除
const handleDeleteConfirm = async () => {
  showDeleteDialog.value = false

  const toDeleteMap = { ...selectedFiles.value }
  let totalDeleted = 0
  let totalFailed = 0

  // 按组删除，每组独立处理
  for (const [md5Hash, attachmentNames] of Object.entries(toDeleteMap)) {
    if (attachmentNames.length === 0) continue
    try {
      const { data } = await axiosInstance.delete(API_ENDPOINTS.CLEANUP_DUPLICATES(md5Hash), {
        data: { attachmentNames, replaceReferences: deleteReplaceReferences.value }
      })

      totalDeleted += data.deletedCount || 0
      totalFailed += data.failedCount || 0

      // 根据后端返回的 errors 解析失败的附件名
      const failedNames = new Set(
        (data.errors || []).map((e: string) => {
          const idx = e.indexOf(': ')
          return idx > 0 ? e.substring(0, idx) : e
        })
      )
      // 只从 UI 移除实际删除成功的文件
      const deleted = attachmentNames.filter((n: string) => !failedNames.has(n))
      if (deleted.length > 0) {
        const group = duplicateGroups.value.find(g => g.md5Hash === md5Hash)
        if (group) {
          group.files = group.files.filter(f => !deleted.includes(f.attachmentName))
          group.fileCount = group.files.length
          if (group.files.length <= 1) {
            duplicateGroups.value = duplicateGroups.value.filter(g => g.md5Hash !== md5Hash)
            total.value = Math.max(0, total.value - 1)
          }
        }
      }
    } catch {
      // HTTP 级别错误，整组视为失败
      totalFailed += attachmentNames.length
    }
  }

  // 汇总提示
  if (totalFailed === 0) {
    Toast.success(`删除成功，共 ${totalDeleted} 个文件`)
  } else if (totalDeleted > 0) {
    Toast.warning(`部分删除成功：${totalDeleted} 个成功，${totalFailed} 个失败`)
  } else {
    Toast.error('删除失败')
  }

  selectedFiles.value = {}
}

// 取消删除
const handleDeleteCancel = () => {
  showDeleteDialog.value = false
}

// 获取统计数据
const fetchStats = async () => {
  try {
    const { data } = await axiosInstance.get<DuplicateStats>(API_ENDPOINTS.DUPLICATES_STATS)
    stats.value = data
    scanning.value = data.phase === 'SCANNING'
  } catch (error) {
    console.error('获取统计数据失败', error)
  }
}

// 获取重复组列表
const fetchDuplicateGroups = async () => {
  try {
    const { data } = await axiosInstance.get(API_ENDPOINTS.DUPLICATES, {
      params: { page: page.value, size: pageSize.value }
    })
    duplicateGroups.value = data.items || []
    total.value = data.total || 0
  } catch (error) {
    console.error('获取重复组列表失败', error)
  }
}

// 开始扫描
const startScan = async () => {
  scanning.value = true
  try {
    await axiosInstance.post(API_ENDPOINTS.DUPLICATES_SCAN)
    // 轮询扫描状态
    pollScanStatus()
  } catch {
    scanning.value = false
    // 错误信息由 Halo 统一处理，这里不需要额外弹窗
  }
}

// 清空记录
const clearRecords = () => {
  Dialog.warning({
    title: '确认清空',
    description: '确定要清空所有重复检测记录吗？此操作不可恢复。',
    confirmType: 'danger',
    confirmText: '清空',
    cancelText: '取消',
    async onConfirm() {
      try {
        await axiosInstance.delete(API_ENDPOINTS.DUPLICATES_CLEAR)
        Toast.success('重复检测记录已清空')
        // 重置状态
        stats.value = {
          phase: null,
          lastScanTime: null,
          startTime: null,
          totalCount: 0,
          scannedCount: 0,
          duplicateGroupCount: 0,
          duplicateFileCount: 0,
          savableSize: 0,
          errorMessage: null,
          enableRemoteStorage: false
        }
        duplicateGroups.value = []
        total.value = 0
      } catch {
        Toast.error('清空记录失败')
      }
    }
  })
}

// 轮询扫描状态
const pollScanStatus = () => {
  const poll = async () => {
    try {
      await fetchStats()
      if (stats.value.phase === 'SCANNING') {
        pollTimer.value = window.setTimeout(poll, 1000)
      } else {
        scanning.value = false
        fetchDuplicateGroups()
      }
    } catch (error) {
      console.error('轮询扫描状态失败:', error)
      scanning.value = false
    }
  }
  poll()
}

onMounted(async () => {
  await fetchStats()
  if (stats.value.phase === 'SCANNING') {
    scanning.value = true
    pollScanStatus()
  } else if (stats.value.lastScanTime) {
    await fetchDuplicateGroups()
  }
})

onUnmounted(() => {
  if (pollTimer.value) {
    clearTimeout(pollTimer.value)
  }
})

watch([page, pageSize], () => {
  fetchDuplicateGroups()
})
</script>


<style scoped>
.duplicate-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.scan-info { font-size: 13px; color: var(--st-text-secondary); }
.scan-info.error { color: var(--st-error-text); }

.status-hint {
  font-size: 13px;
  font-weight: 500;
  color: var(--st-info-text);
}

/* 进度条容器（外观由 .st-card 提供，此处只补内边距） */
.progress-section {
  padding: 16px;
}

.stats-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.duplicate-groups {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* 分组卡片外观由 .st-card 提供，此处保留圆角裁剪 */
.group-card {
  overflow: hidden;
}

.group-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  background: var(--st-bg-hover);
  border-bottom: 1px solid var(--st-border-subtle);
}

.group-info {
  display: flex;
  align-items: center;
  gap: 16px;
}

.group-checkbox {
  width: 16px;
  height: 16px;
  cursor: pointer;
  accent-color: var(--st-color-primary);
}

.group-hash {
  font-family: monospace;
  font-size: 13px;
  color: var(--st-text-secondary);
  background: var(--st-bg-muted);
  padding: 2px 8px;
  border-radius: var(--st-radius-sm);
}

.group-count { font-size: 14px; color: var(--st-text-primary); font-weight: 500; }
.group-size { font-size: 13px; color: var(--st-text-secondary); }

.group-files {
  display: flex;
  flex-direction: column;
}

.file-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--st-border-subtle);
  transition: background var(--st-duration) var(--st-ease);
}

.file-item:last-child { border-bottom: none; }

.file-item:hover {
  background: var(--st-bg-hover);
}

.file-item.recommended {
  background: var(--st-success-bg);
}

.file-item.selected,
.file-item.selected:hover {
  background: var(--st-color-primary-soft);
}

.file-main {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.file-checkbox {
  width: 16px;
  height: 16px;
  cursor: pointer;
  flex-shrink: 0;
  accent-color: var(--st-color-primary);
}

.file-checkbox:disabled {
  cursor: not-allowed;
  opacity: 0.4;
}

.file-icon {
  font-size: 16px;
  flex-shrink: 0;
}
.file-thumb {
  width: 32px;
  height: 32px;
  object-fit: cover;
  border-radius: var(--st-radius-sm);
  flex-shrink: 0;
}
.file-name {
  font-size: 14px;
  color: var(--st-text-primary);
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
  transition: color var(--st-duration) var(--st-ease);
}

.file-name:hover {
  color: var(--st-color-primary);
}

.group-badge {
  font-size: 11px;
  padding: 2px 6px;
  background: var(--st-bg-muted);
  color: var(--st-text-secondary);
  border-radius: var(--st-radius-sm);
  flex-shrink: 0;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.file-date { font-size: 13px; color: var(--st-text-tertiary); }

/* 删除确认对话框样式 */
.delete-confirm-content {
  padding: 4px 0;
}

.confirm-description {
  font-size: 14px;
  color: var(--st-text-primary);
  line-height: 1.5;
  margin-bottom: 12px;
}

.confirm-description strong {
  font-weight: 600;
}

.confirm-alert {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--st-radius-md);
  font-size: 13px;
  line-height: 1.5;
  margin-top: 8px;
}

.confirm-alert-beta {
  background: var(--st-warning-bg);
  color: var(--st-warning-text-strong);
  border: 1px solid var(--st-warning-border);
}

.alert-icon {
  flex-shrink: 0;
  font-size: 14px;
}

.confirm-option {
  margin-top: 16px;
  padding: 12px;
  background: var(--st-bg-hover);
  border: 1px solid var(--st-border);
  border-radius: var(--st-radius-md);
}

.confirm-checkbox-label {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  cursor: pointer;
  user-select: none;
}

.confirm-checkbox {
  width: 16px;
  height: 16px;
  cursor: pointer;
  margin-top: 1px;
  flex-shrink: 0;
  border-radius: var(--st-radius-sm);
  border: 1px solid var(--st-border-input);
  accent-color: var(--st-color-primary);
}

.confirm-checkbox-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.checkbox-main {
  font-size: 14px;
  font-weight: 500;
  color: var(--st-warning-text);
}

.checkbox-sub {
  font-size: 12px;
  color: var(--st-text-secondary);
}

.confirm-option-hint {
  margin-top: 8px;
  padding-left: 24px;
  font-size: 12px;
  color: var(--st-warning-text-strong);
}

.confirm-tip {
  display: block;
  margin-top: 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--st-text-secondary);
}

.confirm-footer {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  width: 100%;
}

/* 响应式 */
@media (max-width: 768px) {
  .stats-row {
    grid-template-columns: 1fr;
  }

  .file-item {
    flex-wrap: wrap;
    gap: 4px;
  }

  .file-main {
    flex: 1;
    min-width: 0;
  }

  .file-meta {
    width: 100%;
    padding-left: 24px;
    gap: 8px;
  }

  .group-badge {
    max-width: 80px;
  }

  .group-info {
    flex-wrap: wrap;
    gap: 8px;
  }
}

@media (max-width: 640px) {
  .group-badge {
    max-width: 80px;
  }
}
</style>
