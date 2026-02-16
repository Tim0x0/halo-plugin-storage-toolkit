<template>
  <div class="duplicate-tab">
    <!-- 操作栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <button class="btn-scan" @click="startScan" :disabled="scanning">
          <span v-if="scanning">扫描中...</span>
          <span v-else>扫描重复文件</span>
        </button>
        <button class="btn-clear" @click="clearRecords" :disabled="scanning || !stats.lastScanTime">
          清空记录
        </button>
        <button 
          class="btn-delete" 
          @click="deleteSelected" 
          :disabled="totalSelectedCount === 0"
          v-if="duplicateGroups.length > 0"
        >
          删除选中 ({{ totalSelectedCount }})
        </button>
        <span class="scan-info" v-if="stats.lastScanTime && !scanning">上次扫描：{{ formatTime(stats.lastScanTime) }}</span>
        <span class="scan-info error" v-else-if="stats.phase === 'ERROR'">扫描失败：{{ stats.errorMessage }}</span>
        <span class="status-hint info" v-if="stats.enableRemoteStorage === false">🌐 仅扫描本地存储</span>
        <span class="status-hint info" v-else-if="stats.enableRemoteStorage === true">🌐 已启用远程扫描</span>
      </div>
    </div>

    <!-- 提示 -->
    <div class="notice info">
      <span class="notice-icon">💡</span>
      <span>基于文件 MD5 哈希检测完全相同的文件，可删除冗余副本节省存储空间</span>
    </div>

    <!-- 扫描进度条 -->
    <div class="progress-section" v-if="scanning">
      <div class="progress-header">
        <span class="progress-text" v-if="stats.totalCount <= 0">准备中...</span>
        <span class="progress-text" v-else>正在扫描... {{ stats.scannedCount }}/{{ stats.totalCount }}</span>
        <span class="progress-percent" v-if="stats.totalCount > 0">{{ progressPercent }}%</span>
      </div>
      <div class="progress-bar" v-if="stats.totalCount > 0">
        <div class="progress-fill" :style="{ width: progressPercent + '%' }"></div>
      </div>
    </div>

    <!-- 统计 -->
    <div class="stats-row">
      <div class="stat-box">
        <span class="stat-num">{{ stats.duplicateGroupCount }}</span>
        <span class="stat-text">重复组</span>
      </div>
      <div class="stat-box">
        <span class="stat-num">{{ stats.duplicateFileCount }}</span>
        <span class="stat-text">重复文件</span>
      </div>
      <div class="stat-box">
        <span class="stat-num green">{{ formatBytes(stats.savableSize) }}</span>
        <span class="stat-text">可节省空间</span>
      </div>
    </div>

    <!-- 重复文件组 -->
    <div class="duplicate-groups" v-if="duplicateGroups.length > 0">
      <div class="group-card" v-for="group in duplicateGroups" :key="group.md5Hash">
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
                :src="file.permalink" 
                class="file-thumb"
                loading="lazy"
              />
              <span v-else class="file-icon">{{ getFileIcon(file.mediaType) }}</span>
              <span class="file-name" @click="openPreview(file, group.fileSize)">{{ file.displayName }}</span>
              <span class="group-badge">{{ file.groupDisplayName || '未分组' }}</span>
              <span class="recommended-badge" v-if="file.recommended">推荐保留</span>
            </div>
            <div class="file-meta">
              <span class="file-refs not-scanned" v-if="file.referenceCount < 0">
                未扫描
              </span>
              <span class="file-refs" v-else :class="file.referenceCount > 0 ? 'has-ref' : 'no-ref'">
                {{ file.referenceCount }} 次引用
              </span>
              <span class="file-date" v-if="file.uploadTime">{{ formatTime(file.uploadTime) }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 分页 -->
      <div class="pagination" v-if="total > pageSize">
        <div class="page-info">共 {{ total }} 组</div>
        <div class="page-controls">
          <button type="button" class="page-btn" :disabled="page <= 1" @click="changePage(page - 1)">上一页</button>
          <span class="page-num">{{ page }} / {{ totalPages }}</span>
          <button type="button" class="page-btn" :disabled="page >= totalPages" @click="changePage(page + 1)">下一页</button>
        </div>
        <select v-model="pageSize" class="page-size" @change="onPageSizeChange">
          <option v-for="size in PAGE_SIZE_OPTIONS" :key="size" :value="size">
            {{ size }}组/页
          </option>
        </select>
      </div>
    </div>

    <!-- 空状态 -->
    <div class="empty-state" v-else-if="!scanning && stats.phase === 'COMPLETED'">
      <span class="empty-icon">✨</span>
      <span class="empty-text">没有发现重复文件</span>
      <span class="empty-hint">所有文件都是唯一的</span>
    </div>

    <!-- 未扫描状态 -->
    <div class="empty-state" v-else-if="!scanning && !stats.lastScanTime">
      <span class="empty-icon">🔍</span>
      <span class="empty-text">尚未进行扫描</span>
      <span class="empty-hint">点击「扫描重复文件」开始检测</span>
    </div>

    <!-- 预览模态框 -->
    <div class="modal-overlay" v-if="showPreview" @click.self="showPreview = false">
      <div class="modal-content">
        <div class="modal-header">
          <h3>{{ previewFile?.displayName }}</h3>
          <button class="modal-close" @click="showPreview = false">×</button>
        </div>
        <div class="modal-body">
          <!-- 预览区域 -->
          <div class="preview-area" v-if="previewFile?.permalink && isImage(previewFile.mediaType)">
            <img :src="previewFile.permalink" class="preview-image" />
          </div>
          <div class="preview-area preview-placeholder" v-else>
            <span class="preview-icon">{{ getFileIcon(previewFile?.mediaType ?? null) }}</span>
          </div>

          <!-- 文件信息 -->
          <div class="info-section">
            <div class="info-item">
              <span class="info-label">大小</span>
              <span class="info-value">{{ formatBytes(previewFileSize) }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">类型</span>
              <span class="info-value">{{ previewFile?.mediaType || '未知' }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">存储策略</span>
              <span class="info-value">{{ previewFile?.policyDisplayName || previewFile?.policyName || '默认策略' }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">分组</span>
              <span class="info-value">{{ previewFile?.groupDisplayName || '未分组' }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">上传时间</span>
              <span class="info-value">{{ previewFile?.uploadTime ? formatTime(previewFile.uploadTime) : '-' }}</span>
            </div>
            <div class="info-item" v-if="previewFile?.permalink">
              <span class="info-label">链接</span>
              <span class="info-value info-url">{{ previewFile.permalink }}</span>
            </div>
          </div>

          <!-- 引用列表 -->
          <ReferenceList :references="previewFile?.references" />
        </div>
      </div>
    </div>
  </div>

  <!-- 删除确认对话框 -->
  <VModal
    v-model:visible="showDeleteDialog"
    :width="480"
    :closable="true"
    :mask-closable="true"
    @close="handleDeleteCancel"
  >
    <template #header>确认删除重复附件</template>
    <template #default>
      <div class="delete-confirm-content">
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
import { ref, computed, onMounted, onUnmounted } from 'vue'
import type { DuplicateStats, DuplicateGroup, DuplicateFile } from '@/types/duplicate'
import { axiosInstance } from '@halo-dev/api-client'
import { Dialog, Toast, VModal, VButton } from '@halo-dev/components'
import { PAGE_SIZE_OPTIONS, DEFAULT_PAGE_SIZE } from '@/constants/pagination'
import { API_ENDPOINTS } from '@/constants/api'
import { formatBytes, formatTime } from '@/utils/format'
import { getFileIcon, isImage } from '@/composables/useReferenceSource'
import ReferenceList from '@/components/ReferenceList.vue'

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

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

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
    } catch (error: any) {
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
  } catch (error: any) {
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
      } catch (error: any) {
        Toast.error('清空记录失败')
        console.error('清空记录失败:', error)
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

const changePage = (newPage: number) => {
  if (newPage >= 1 && newPage <= totalPages.value) {
    page.value = newPage
    fetchDuplicateGroups()
  }
}

const onPageSizeChange = () => {
  page.value = 1
  fetchDuplicateGroups()
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
</script>


<style scoped>
.duplicate-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.toolbar-left {
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
}

.btn-scan:disabled { background: #a1a1aa; cursor: not-allowed; }

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

.scan-info { font-size: 13px; color: #71717a; }
.scan-info.error { color: #dc2626; }

.status-hint {
  font-size: 13px;
  font-weight: 500;
}

.status-hint.info {
  color: #2563eb;
}

/* 进度条 */
.progress-section {
  background: white;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 16px;
}

.progress-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.progress-text {
  font-size: 13px;
  color: #18181b;
}

.progress-percent {
  font-size: 13px;
  color: #16a34a;
  font-weight: 500;
}

.progress-bar {
  height: 6px;
  background: #f3f4f6;
  border-radius: 4px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: #16a34a;
  border-radius: 4px;
  transition: width 0.3s ease;
}

.notice {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  border-radius: 8px;
  font-size: 13px;
}

.notice.info {
  background: #eff6ff;
  color: #1d4ed8;
}

.notice.error {
  background: #fef2f2;
  color: #dc2626;
}

.stats-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
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

.stat-text { font-size: 13px; color: #71717a; margin-top: 4px; }

.duplicate-groups {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.group-card {
  background: white;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
}

.group-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  background: #fafafa;
  border-bottom: 1px solid #f4f4f5;
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
}

.group-hash {
  font-family: monospace;
  font-size: 13px;
  color: #71717a;
  background: #f4f4f5;
  padding: 2px 8px;
  border-radius: 4px;
}

.group-count { font-size: 14px; color: #18181b; font-weight: 500; }
.group-size { font-size: 13px; color: #71717a; }

.group-files {
  display: flex;
  flex-direction: column;
}

.file-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #f4f4f5;
}

.file-item:last-child { border-bottom: none; }

.file-item.selected {
  background: #eff6ff;
}

.file-item.selected:hover {
  background: #dbeafe;
}

.file-item.recommended {
  background: #f0fdf4;
}

.file-main {
  display: flex;
  align-items: center;
  gap: 8px;
}

.file-checkbox {
  width: 16px;
  height: 16px;
  cursor: pointer;
}

.file-checkbox:disabled {
  cursor: not-allowed;
  opacity: 0.4;
}

.file-icon { font-size: 16px; }
.file-thumb {
  width: 32px;
  height: 32px;
  object-fit: cover;
  border-radius: 4px;
  flex-shrink: 0;
}
.file-name { font-size: 14px; color: #18181b; }

.group-badge {
  font-size: 11px;
  padding: 2px 6px;
  background: #f4f4f5;
  color: #71717a;
  border-radius: 4px;
  margin-left: 8px;
}

.recommended-badge {
  font-size: 11px;
  padding: 2px 6px;
  background: #059669;
  color: white;
  border-radius: 4px;
}

.file-meta {
  display: flex;
  align-items: center;
  gap: 16px;
}

.file-refs {
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 4px;
  font-weight: 500;
}

.file-refs.has-ref { background: #dcfce7; color: #166534; }
.file-refs.no-ref { background: #fef3c7; color: #92400e; }
.file-refs.not-scanned { background: #f3f4f6; color: #4b5563; }

.file-date { font-size: 13px; color: #a1a1aa; }

/* 分页 */
.pagination {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  background: white;
  border-radius: 8px;
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

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px;
  background: white;
  border-radius: 8px;
}

.empty-icon { font-size: 48px; margin-bottom: 16px; }
.empty-text { font-size: 16px; color: #18181b; }
.empty-hint { font-size: 13px; color: #a1a1aa; margin-top: 4px; }

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

.file-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color 0.15s;
  cursor: pointer;
}

.file-name:hover {
  color: #2563eb;
}

/* 删除确认对话框样式 */
.delete-confirm-content {
  padding: 4px 0;
}

.confirm-description {
  font-size: 14px;
  color: #374151;
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
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.5;
  margin-top: 8px;
}

.confirm-alert-beta {
  background: #fffbeb;
  color: #92400e;
  border: 1px solid #fcd34d;
}

.alert-icon {
  flex-shrink: 0;
  font-size: 14px;
}

.confirm-option {
  margin-top: 16px;
  padding: 12px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
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
  border-radius: 4px;
  border: 1px solid #d1d5db;
}

.confirm-checkbox-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.checkbox-main {
  font-size: 14px;
  font-weight: 500;
  color: #d97706;
}

.checkbox-sub {
  font-size: 12px;
  color: #6b7280;
}

.confirm-option-hint {
  margin-top: 8px;
  padding-left: 24px;
  font-size: 12px;
  color: #b45309;
}

.confirm-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  font-size: 13px;
  color: #6b7280;
}

.confirm-footer {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  width: 100%;
}
</style>
