<template>
  <div class="batch-processing-tab">
    <!-- 操作栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <button class="btn-primary" @click="startProcessing" :disabled="processing || selectedAttachments.length === 0">
          <span v-if="processing">处理中...</span>
          <span v-else>开始处理 ({{ selectedAttachments.length }})</span>
        </button>
        <button class="btn-cancel" @click="cancelTask" :disabled="!processing">
          取消任务
        </button>
        <button class="btn-refresh" @click="handleRefresh" :disabled="processing">
          刷新
        </button>
        <span class="status-hint warning" v-if="!settings.keepOriginalFile">
          ⚠️ 不保留原图模式
        </span>
        <span class="status-hint info" v-if="!settings.enableRemoteStorage">
          🌐 远程存储未启用
        </span>
      </div>
      <div class="toolbar-right">
        <input
          type="text"
          v-model="searchKeyword"
          placeholder="搜索文件名..."
          class="search-input"
          @input="handleSearchDebounced"
        />
      </div>
    </div>

    <!-- 警告提示 -->
    <div class="notice warning" v-if="!settings.keepOriginalFile">
      <span class="notice-icon">⚠️</span>
      <span>当前设置为「不保留原图」，处理后的文件将会被重命名，导致原有链接失效！原文件将被删除。</span>
    </div>

    <!-- 配置说明 -->
    <div class="notice info">
      <span class="notice-icon">💡</span>
      <span>批量处理使用插件设置中「图片处理」分组的配置（过滤规则、格式转换、水印等），请先在插件设置中配置好处理参数。</span>
    </div>

    <!-- 扫描进度条 - 仅在处理中显示 -->
    <div class="progress-section" v-if="processing">
      <div class="progress-header">
        <span class="progress-text" v-if="!status.progress || status.progress.total <= 0">准备中...</span>
        <span class="progress-text" v-else>正在处理... {{ status.progress.processed }}/{{ status.progress.total }}</span>
        <span class="progress-percent" v-if="status.progress && status.progress.total > 0">{{ progressPercent }}%</span>
      </div>
      <div class="progress-bar" v-if="status.progress && status.progress.total > 0">
        <div class="progress-fill" :style="{ width: progressPercent + '%' }"></div>
      </div>
    </div>

    <!-- 处理结果统计 - 仅在完成后显示 -->
    <div class="stats-row" v-if="showResults">
      <div class="stat-box">
        <span class="stat-num green">{{ lastResult.succeeded }}</span>
        <span class="stat-text">成功</span>
      </div>
      <div class="stat-box">
        <span class="stat-num red">{{ lastResult.failed }}</span>
        <span class="stat-text">失败</span>
      </div>
      <div class="stat-box">
        <span class="stat-num orange">{{ lastResult.skipped }}</span>
        <span class="stat-text">跳过</span>
      </div>
      <div class="stat-box">
        <span class="stat-num blue">{{ formatBytes(lastResult.savedBytes) }}</span>
        <span class="stat-text">节省空间</span>
      </div>
    </div>

    <!-- 失败/跳过日志 - 仅在有失败或跳过时显示 -->
    <div class="log-section" v-if="showResults && (lastResult.failedItems.length > 0 || lastResult.skippedItems.length > 0)">
      <div class="log-tabs">
        <button
          class="log-tab"
          :class="{ active: logTab === 'failed' }"
          @click="logTab = 'failed'"
          v-if="lastResult.failedItems.length > 0"
        >
          失败 ({{ lastResult.failedItems.length }})
        </button>
        <button
          class="log-tab"
          :class="{ active: logTab === 'skipped' }"
          @click="logTab = 'skipped'"
          v-if="lastResult.skippedItems.length > 0"
        >
          跳过 ({{ lastResult.skippedItems.length }})
        </button>
      </div>
      <div class="log-list">
        <template v-if="logTab === 'failed'">
          <div class="log-item failed" v-for="item in lastResult.failedItems" :key="item.attachmentName">
            <span class="log-name">{{ item.displayName }}</span>
            <span class="log-reason">{{ item.error }}</span>
          </div>
        </template>
        <template v-else>
          <div class="log-item skipped" v-for="item in lastResult.skippedItems" :key="item.attachmentName">
            <span class="log-name">{{ item.displayName }}</span>
            <span class="log-reason">{{ item.reason }}</span>
          </div>
        </template>
      </div>
    </div>

    <!-- 附件列表 - 表格样式 -->
    <div class="card">
      <div v-if="loading" class="loading-state">加载中...</div>
      <div v-else-if="attachments.length === 0" class="empty-state">
        没有符合条件的附件
      </div>
      <template v-else>
        <table class="data-table">
          <thead>
            <tr>
              <th class="col-checkbox">
                <input
                  type="checkbox"
                  :checked="isAllSelected"
                  :indeterminate="isIndeterminate"
                  @change="toggleSelectAll"
                  :disabled="processing"
                />
              </th>
              <th>文件名</th>
              <th>类型</th>
              <th>大小</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="att in attachments"
              :key="att.name"
              :class="{ selected: selectedAttachments.includes(att.name) }"
              @click="!processing && toggleSelect(att.name)"
            >
              <td class="col-checkbox" @click.stop>
                <input
                  type="checkbox"
                  :checked="selectedAttachments.includes(att.name)"
                  @change="toggleSelect(att.name)"
                  :disabled="processing"
                />
              </td>
              <td class="cell-name" @click.stop="openPreview(att)">
                <img
                  v-if="att.permalink && isImage(att.mediaType)"
                  :src="att.permalink"
                  class="file-thumbnail"
                  loading="lazy"
                  @error="(e: Event) => (e.target as HTMLImageElement).style.display = 'none'"
                />
                <span v-else class="file-icon">{{ getFileIcon(att.mediaType) }}</span>
                <span class="file-name-text">{{ att.displayName }}</span>
              </td>
              <td>{{ att.mediaType }}</td>
              <td>{{ formatBytes(att.size) }}</td>
            </tr>
          </tbody>
        </table>

        <!-- 分页 -->
        <div class="pagination" v-if="total > 0">
          <div class="page-info">共 {{ total }} 条，已选 {{ selectedAttachments.length }} 条</div>
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

    <!-- 预览模态框 -->
    <div class="modal-overlay" v-if="showPreview" @click.self="showPreview = false">
      <div class="modal-content">
        <div class="modal-header">
          <h3>{{ previewAttachment?.displayName }}</h3>
          <button class="modal-close" @click="showPreview = false">×</button>
        </div>
        <div class="modal-body">
          <!-- 预览区域 -->
          <div class="preview-area" v-if="previewAttachment?.permalink && isImage(previewAttachment.mediaType)">
            <img :src="previewAttachment.permalink" class="preview-image" />
          </div>
          <div class="preview-area preview-placeholder" v-else>
            <span class="preview-icon">{{ getFileIcon(previewAttachment?.mediaType ?? null) }}</span>
          </div>

          <!-- 文件信息 -->
          <div class="info-section">
            <div class="info-item">
              <span class="info-label">大小</span>
              <span class="info-value">{{ formatBytes(previewAttachment?.size || 0) }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">类型</span>
              <span class="info-value">{{ previewAttachment?.mediaType || '未知' }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">存储策略</span>
              <span class="info-value">{{ policyDisplayName ?? '加载中...' }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">分组</span>
              <span class="info-value">{{ groupDisplayName ?? '加载中...' }}</span>
            </div>
            <div class="info-item" v-if="previewAttachment?.permalink">
              <span class="info-label">链接</span>
              <span class="info-value info-url">{{ previewAttachment.permalink }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- 批量处理确认对话框 -->
  <VModal
    v-model:visible="showCustomDialog"
    :width="480"
    :closable="true"
    :mask-closable="true"
    @close="handleDialogCancel"
  >
    <template #header>{{ getDialogTitle() }}</template>
    <template #default>
      <div class="batch-confirm-content">
        <!-- 描述内容 -->
        <div class="confirm-description">
          确定要处理选中的 <strong>{{ selectedAttachments.length }}</strong> 个附件吗？
        </div>

        <!-- 测试版提示 -->
        <div class="confirm-alert confirm-alert-beta">
          <span class="alert-icon">🧪</span>
          <span>当前为测试版功能，使用前请备份重要数据</span>
        </div>

        <!-- 不保留原图警告 -->
        <div class="confirm-alert confirm-alert-warning" v-if="!settings.keepOriginalFile">
          <span class="alert-icon">⚠️</span>
          <span><strong>当前为「不保留原图」模式，原文件将被删除，此操作不可恢复！</strong></span>
        </div>

        <!-- 选项区域 -->
        <div class="confirm-option">
          <label class="confirm-checkbox-label">
            <input
              type="checkbox"
              v-model="replaceReferences"
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
        <VButton
          :type="getConfirmType() === 'danger' ? 'danger' : 'primary'"
          @click="handleDialogConfirm"
        >
          开始处理
        </VButton>
        <VButton @click="handleDialogCancel">取消</VButton>
      </div>
    </template>
  </VModal>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { Dialog, Toast, VModal, VButton } from '@halo-dev/components'
import { PAGE_SIZE_OPTIONS, DEFAULT_PAGE_SIZE } from '@/constants/pagination'
import { API_ENDPOINTS } from '@/constants/api'
import { formatBytes } from '@/utils/format'
import { getFileIcon, isImage } from '@/composables/useReferenceSource'

interface AttachmentItem {
  name: string
  displayName: string
  size: number
  mediaType: string
  permalink: string
  groupName: string
  policyName: string
}

interface Progress {
  total: number
  processed: number
  succeeded: number
  failed: number
}

interface FailedItem {
  attachmentName: string
  displayName: string
  error: string
}

interface SkippedItem {
  attachmentName: string
  displayName: string
  reason: string
}

interface Status {
  phase: string | null
  progress: Progress | null
  failedItems: FailedItem[] | null
  skippedItems: SkippedItem[] | null
  skippedCount: number
  savedBytes: number
  errorMessage: string | null
}

interface LastResult {
  succeeded: number
  failed: number
  skipped: number
  savedBytes: number
  failedItems: FailedItem[]
  skippedItems: SkippedItem[]
}

interface Settings {
  keepOriginalFile: boolean
  enableRemoteStorage: boolean
}

// 状态
const status = ref<Status>({
  phase: null,
  progress: null,
  failedItems: null,
  skippedItems: null,
  skippedCount: 0,
  savedBytes: 0,
  errorMessage: null
})

const settings = ref<Settings>({
  keepOriginalFile: true,
  enableRemoteStorage: false
})

// 处理结果（完成后保留显示）
const showResults = ref(false)
const lastResult = ref<LastResult>({
  succeeded: 0,
  failed: 0,
  skipped: 0,
  savedBytes: 0,
  failedItems: [],
  skippedItems: []
})

// 附件列表
const attachments = ref<AttachmentItem[]>([])
const selectedAttachments = ref<string[]>([])
const page = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)
const total = ref(0)
const loading = ref(false)
const processing = ref(false)

// 筛选
const searchKeyword = ref('')

// 日志 Tab
const logTab = ref<'failed' | 'skipped'>('failed')

// 预览相关
const showPreview = ref(false)
const previewAttachment = ref<AttachmentItem | null>(null)
const policyDisplayName = ref<string | null>(null)
const groupDisplayName = ref<string | null>(null)

let searchDebounceTimer: ReturnType<typeof setTimeout> | null = null
let pollingTimer: ReturnType<typeof setTimeout> | null = null

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

const progressPercent = computed(() => {
  if (!status.value.progress || status.value.progress.total === 0) return 0
  return Math.round((status.value.progress.processed / status.value.progress.total) * 100)
})

const isAllSelected = computed(() => {
  return attachments.value.length > 0 &&
    attachments.value.every(item => selectedAttachments.value.includes(item.name))
})

const isIndeterminate = computed(() => {
  return selectedAttachments.value.length > 0 && !isAllSelected.value
})

// 获取设置
const fetchSettings = async () => {
  try {
    const { data } = await axiosInstance.get(API_ENDPOINTS.BATCH_PROCESSING_SETTINGS)
    settings.value = data
  } catch (error) {
    console.error('获取设置失败:', error)
  }
}

// 获取状态
const fetchStatus = async () => {
  try {
    const { data } = await axiosInstance.get(API_ENDPOINTS.BATCH_PROCESSING_STATUS)
    status.value = data
    processing.value = data.phase === 'PENDING' || data.phase === 'PROCESSING' || data.phase === 'CANCELLING'
  } catch (error) {
    console.error('获取状态失败:', error)
  }
}

// 获取附件列表
const fetchAttachments = async () => {
  loading.value = true
  try {
    const params: Record<string, string | number> = {
      page: page.value,
      size: pageSize.value,
      sort: 'metadata.creationTimestamp,desc'
    }
    if (searchKeyword.value) {
      params.keyword = searchKeyword.value
    }

    const { data } = await axiosInstance.get('/apis/api.console.halo.run/v1alpha1/attachments', { params })

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    attachments.value = (data.items || []).map((att: Record<string, any>) => ({
      name: att.metadata.name,
      displayName: att.spec.displayName,
      size: att.spec.size || 0,
      mediaType: att.spec.mediaType || '',
      permalink: att.status?.permalink || '',
      groupName: att.spec.groupName || '',
      policyName: att.spec.policyName || ''
    }))
    total.value = data.total || 0
  } catch (error) {
    console.error('获取附件列表失败:', error)
  } finally {
    loading.value = false
  }
}

// 开始处理
const replaceReferences = ref(true)  // 默认勾选替换引用
const showCustomDialog = ref(false)

const startProcessing = () => {
  if (selectedAttachments.value.length === 0) return

  // 显示自定义对话框
  showCustomDialog.value = true
}

// 处理自定义对话框确认
const handleDialogConfirm = async () => {
  showCustomDialog.value = false

  try {
    // 清除上次结果
    clearResults()

    await axiosInstance.post(API_ENDPOINTS.BATCH_PROCESSING_TASKS, {
      attachmentNames: selectedAttachments.value,
      replaceReferences: replaceReferences.value
    })
    Toast.success('任务已创建')
    processing.value = true
    startPolling()
  } catch (error: unknown) {
    const err = error as { response?: { data?: { message?: string } } }
    Toast.error(err.response?.data?.message || '创建任务失败')
  }
}

// 处理自定义对话框取消
const handleDialogCancel = () => {
  showCustomDialog.value = false
}

// 取消任务
const cancelTask = async () => {
  Dialog.warning({
    title: '确认取消',
    description: '确定要取消当前任务吗？已处理的文件不会回滚。',
    confirmType: 'danger',
    confirmText: '取消任务',
    cancelText: '返回',
    async onConfirm() {
      try {
        await axiosInstance.delete(API_ENDPOINTS.BATCH_PROCESSING_CANCEL)
        Toast.success('任务已取消')
      } catch (error: unknown) {
        const err = error as { response?: { data?: { message?: string } } }
        Toast.error(err.response?.data?.message || '取消失败')
      }
    }
  })
}

// 轮询状态
const startPolling = () => {
  stopPolling()
  const poll = async () => {
    await fetchStatus()
    if (processing.value) {
      pollingTimer = setTimeout(poll, 1000)
    } else {
      // 处理完成，保存结果并刷新
      onProcessingComplete()
    }
  }
  poll()
}

const stopPolling = () => {
  if (pollingTimer) {
    clearTimeout(pollingTimer)
    pollingTimer = null
  }
}

// 处理完成后的回调
const onProcessingComplete = () => {
  // 保存结果用于显示
  lastResult.value = {
    succeeded: status.value.progress?.succeeded || 0,
    failed: status.value.progress?.failed || 0,
    skipped: status.value.skippedCount || 0,
    savedBytes: status.value.savedBytes || 0,
    failedItems: status.value.failedItems || [],
    skippedItems: status.value.skippedItems || []
  }
  showResults.value = true

  // 设置默认日志Tab
  if (lastResult.value.failedItems.length > 0) {
    logTab.value = 'failed'
  } else if (lastResult.value.skippedItems.length > 0) {
    logTab.value = 'skipped'
  }

  // 清空选择并刷新列表
  selectedAttachments.value = []
  fetchAttachments()
}

// 清除结果显示
const clearResults = () => {
  showResults.value = false
  lastResult.value = {
    succeeded: 0,
    failed: 0,
    skipped: 0,
    savedBytes: 0,
    failedItems: [],
    skippedItems: []
  }
}

// 刷新按钮处理
const handleRefresh = () => {
  clearResults()
  selectedAttachments.value = []
  page.value = 1
  fetchAttachments()
}

// 选择操作
const toggleSelect = (name: string) => {
  if (processing.value) return
  const index = selectedAttachments.value.indexOf(name)
  if (index === -1) {
    selectedAttachments.value.push(name)
  } else {
    selectedAttachments.value.splice(index, 1)
  }
}

const toggleSelectAll = () => {
  if (processing.value) return
  if (isAllSelected.value) {
    selectedAttachments.value = []
  } else {
    selectedAttachments.value = attachments.value.map(a => a.name)
  }
}

// 分页
const handleSearchDebounced = () => {
  if (searchDebounceTimer) clearTimeout(searchDebounceTimer)
  searchDebounceTimer = setTimeout(() => {
    page.value = 1
    selectedAttachments.value = []  // 搜索时清空选择
    fetchAttachments()
  }, 300)
}

const changePage = (newPage: number) => {
  if (newPage >= 1 && newPage <= totalPages.value) {
    page.value = newPage
    fetchAttachments()
  }
}

const handlePageSizeChange = () => {
  page.value = 1
  selectedAttachments.value = []
  fetchAttachments()
}

// 打开预览
const openPreview = async (att: AttachmentItem) => {
  previewAttachment.value = att
  policyDisplayName.value = null
  groupDisplayName.value = null
  showPreview.value = true

  // 异步获取 Policy displayName
  if (att.policyName) {
    try {
      const { data } = await axiosInstance.get(API_ENDPOINTS.REFERENCES_POLICY(att.policyName))
      policyDisplayName.value = data.displayName
    } catch (e) {
      policyDisplayName.value = att.policyName
    }
  } else {
    policyDisplayName.value = '默认策略'
  }

  // 异步获取 Group displayName
  if (att.groupName) {
    try {
      const { data } = await axiosInstance.get(API_ENDPOINTS.REFERENCES_GROUP(att.groupName))
      groupDisplayName.value = data.displayName
    } catch (e) {
      groupDisplayName.value = att.groupName
    }
  } else {
    groupDisplayName.value = '未分组'
  }
}

// 对话框配置
const getDialogTitle = () => '确认批量处理'

const getConfirmType = () => {
  return settings.value.keepOriginalFile ? 'primary' : 'danger'
}

onMounted(async () => {
  await Promise.all([
    fetchSettings(),
    fetchStatus()
  ])

  if (processing.value) {
    startPolling()
  } else {
    await fetchAttachments()
  }
})

onUnmounted(() => {
  stopPolling()
  if (searchDebounceTimer) {
    clearTimeout(searchDebounceTimer)
  }
})
</script>

<style scoped>
.batch-processing-tab {
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

.btn-primary {
  padding: 8px 16px;
  font-size: 14px;
  background: #18181b;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-primary:hover:not(:disabled) {
  background: #27272a;
}

.btn-primary:disabled {
  background: #a1a1aa;
  cursor: not-allowed;
}

.btn-cancel {
  padding: 8px 16px;
  font-size: 14px;
  background: white;
  color: #dc2626;
  border: 1px solid #fecaca;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-cancel:hover:not(:disabled) {
  background: #fef2f2;
  border-color: #f87171;
}

.btn-cancel:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-refresh {
  padding: 8px 16px;
  font-size: 14px;
  background: white;
  color: #52525b;
  border: 1px solid #e4e4e7;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-refresh:hover:not(:disabled) {
  background: #f4f4f5;
  border-color: #d4d4d8;
  color: #18181b;
}

.btn-refresh:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.status-hint {
  font-size: 13px;
  font-weight: 500;
}

.status-hint.warning {
  color: #d97706;
}

.status-hint.info {
  color: #2563eb;
}

.search-input {
  padding: 8px 12px;
  font-size: 14px;
  border: 1px solid #e4e4e7;
  border-radius: 6px;
  background: white;
  width: 200px;
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

.notice.warning {
  background: #fef3c7;
  color: #92400e;
}

.notice.info {
  background: #eff6ff;
  color: #1d4ed8;
}

/* 统计 */
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
.stat-num.red { color: #dc2626; }
.stat-num.orange { color: #d97706; }
.stat-num.blue { color: #2563eb; }

.stat-text { font-size: 13px; color: #71717a; margin-top: 4px; }

/* 日志 */
.log-section {
  background: white;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
}

.log-tabs {
  display: flex;
  border-bottom: 1px solid #e5e7eb;
}

.log-tab {
  padding: 12px 20px;
  font-size: 13px;
  background: none;
  border: none;
  color: #71717a;
  cursor: pointer;
}

.log-tab.active {
  color: #18181b;
  font-weight: 500;
  border-bottom: 2px solid #18181b;
  margin-bottom: -1px;
}

.log-list {
  max-height: 200px;
  overflow-y: auto;
}

.log-item {
  display: flex;
  justify-content: space-between;
  padding: 10px 16px;
  border-bottom: 1px solid #f4f4f5;
}

.log-item:last-child { border-bottom: none; }

.log-item.failed { background: #fef2f2; }
.log-item.skipped { background: #fffbeb; }

.log-name {
  font-size: 13px;
  color: #18181b;
}

.log-reason {
  font-size: 12px;
  color: #71717a;
}

/* 卡片和表格 */
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

.data-table tbody tr {
  transition: background 0.15s;
  cursor: pointer;
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

.cell-name {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.cell-name:hover .file-name-text {
  color: #2563eb;
}

.file-thumbnail {
  width: 32px;
  height: 32px;
  object-fit: cover;
  border-radius: 4px;
  flex-shrink: 0;
}

.file-icon {
  font-size: 20px;
  width: 32px;
  text-align: center;
}

.file-name-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color 0.15s;
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

/* 批量处理确认对话框 - 参考 Halo 官方样式 */
.batch-confirm-content {
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

.confirm-alert-warning {
  background: #fef2f2;
  color: #dc2626;
  border: 1px solid #fecaca;
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
