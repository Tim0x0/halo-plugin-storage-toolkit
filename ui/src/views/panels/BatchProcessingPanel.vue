<template>
  <div class="batch-processing-tab">
    <!-- 操作栏 -->
    <div class="st-toolbar">
      <div class="st-toolbar-left">
        <VButton
          type="secondary"
          :loading="processing"
          :disabled="processing || selectedAttachments.length === 0"
          @click="startProcessing"
        >
          {{ processing ? '处理中...' : `开始处理 (${selectedAttachments.length})` }}
        </VButton>
        <VButton type="danger" ghost :disabled="!processing" @click="cancelTask">
          取消任务
        </VButton>
        <VButton :disabled="processing" @click="handleRefresh">
          刷新
        </VButton>
        <span class="status-hint warning" v-if="!settings.keepOriginalFile">
          ⚠️ 不保留原图模式
        </span>
        <span class="status-hint info" v-if="!settings.enableRemoteStorage">
          🌐 远程存储未启用
        </span>
      </div>
      <div class="st-toolbar-right">
        <SearchInput v-model="searchKeyword" placeholder="搜索文件名..." @search="handleSearch" />
      </div>
    </div>

    <!-- 警告提示 -->
    <VAlert
      v-if="!settings.keepOriginalFile"
      type="warning"
      title="不保留原图模式已开启"
      description="当前设置为「不保留原图」，处理后的文件将会被重命名，导致原有链接失效！原文件将被删除。"
    />

    <!-- 配置说明 -->
    <VAlert
      type="info"
      title="配置说明"
      description="批量处理使用插件设置中「图片处理」分组的配置（过滤规则、格式转换、水印等），请先在插件设置中配置好处理参数。"
    />

    <!-- 扫描进度条 - 仅在处理中显示 -->
    <div class="progress-section st-card" v-if="processing">
      <StProgressBar
        :percent="progressPercent"
        tone="success"
        show-label
        :indeterminate="!status.progress || status.progress.total <= 0"
      >
        <template #label>
          <span v-if="!status.progress || status.progress.total <= 0">准备中...</span>
          <span v-else>正在处理... {{ status.progress.processed }}/{{ status.progress.total }}</span>
        </template>
      </StProgressBar>
    </div>

    <!-- 处理结果统计 - 仅在完成后显示 -->
    <div class="stats-row" v-if="showResults">
      <StatCard label="成功" :value="lastResult.succeeded" tone="success" :icon="IconCheckboxCircle" />
      <StatCard label="失败" :value="lastResult.failed" tone="error" :icon="IconCloseCircle" />
      <StatCard label="跳过" :value="lastResult.skipped" :icon="IconForbidLine" />
      <StatCard label="节省空间" :value="formatBytes(lastResult.savedBytes)" tone="success" :icon="IconSave" />
    </div>

    <!-- 失败/跳过日志 - 仅在有失败或跳过时显示 -->
    <div class="log-section st-card" v-if="showResults && (lastResult.failedItems.length > 0 || lastResult.skippedItems.length > 0)">
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
            <span class="log-name" :title="item.displayName">{{ item.displayName }}</span>
            <span class="log-reason" :title="item.error">{{ item.error }}</span>
          </div>
        </template>
        <template v-else>
          <div class="log-item skipped" v-for="item in lastResult.skippedItems" :key="item.attachmentName">
            <span class="log-name" :title="item.displayName">{{ item.displayName }}</span>
            <span class="log-reason" :title="item.reason">{{ item.reason }}</span>
          </div>
        </template>
      </div>
    </div>

    <TaskHistory :busy="processing" @started="onHistoryTaskStarted" />

    <!-- 附件列表 - 表格样式 -->
    <div class="list-card st-card">
      <VLoading v-if="loading" />
      <VEmpty v-else-if="attachments.length === 0" title="没有符合条件的附件" />
      <template v-else>
        <div class="st-table-wrapper">
          <table class="st-table batch-table">
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
                <th class="col-filename">文件名</th>
                <th class="col-type">类型</th>
                <th class="col-size">大小</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="att in attachments"
                :key="att.name"
                :class="{ 'st-table-row--selected': selectedAttachments.includes(att.name) }"
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
                    :src="utils.attachment.getThumbnailUrl(att.permalink, 'S')"
                    class="file-thumbnail"
                    loading="lazy"
                    @error="(e: Event) => (e.target as HTMLImageElement).style.display = 'none'"
                  />
                  <span v-else class="file-icon">{{ getFileIcon(att.mediaType) }}</span>
                  <span class="file-name-text" :title="att.displayName">{{ att.displayName }}</span>
                </td>
                <td>{{ att.mediaType }}</td>
                <td>{{ formatBytes(att.size) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 分页 -->
        <VPagination v-if="total > 0" v-model:page="page" v-model:size="pageSize" :total="total" :size-options="PAGE_SIZE_OPTIONS"></VPagination>
      </template>
    </div>

    <!-- 预览模态框 -->
    <BatchPreviewModal
      v-if="showPreview && previewAttachment"
      :attachment="previewAttachment"
      :policy-display-name="policyDisplayName"
      :group-display-name="groupDisplayName"
      @close="showPreview = false"
    />
  </div>

  <!-- 批量处理确认对话框 -->
  <VModal
    v-model:visible="showCustomDialog"
    :width="480"
    mount-to-body
    :title="getDialogTitle()"
    @close="handleDialogCancel"
  >
    <template #default>
      <div class="st-scope batch-confirm-content">
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

        <OperationImpactPreview v-if="showCustomDialog" operation="BATCH"
          :attachment-names="selectedAttachments" :replace-references="replaceReferences"
          @ready="previewEligibleNames = $event" />

        <!-- 底部提示 -->
        <div class="confirm-tip">
          引用影响将按当前内容实时预览，执行时再次核验。
        </div>
      </div>
    </template>
    <template #footer>
      <div class="confirm-footer">
        <VButton
          :type="getConfirmType() === 'danger' ? 'danger' : 'primary'"
          :disabled="previewEligibleNames.length === 0"
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
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { Dialog, Toast, VModal, VButton, VPagination, VAlert, VEmpty, VLoading, IconCheckboxCircle, IconCloseCircle, IconForbidLine, IconSave } from '@halo-dev/components'
import { PAGE_SIZE_OPTIONS, DEFAULT_PAGE_SIZE } from '@/constants/pagination'
import { API_ENDPOINTS } from '@/constants/api'
import { formatBytes } from '@/utils/format'
import { getFileIcon, isImage } from '@/composables/useReferenceSource'
import { utils } from '@halo-dev/ui-shared'
import StatCard from '@/components/StatCard.vue'
import StProgressBar from '@/components/StProgressBar.vue'
import SearchInput from '@/components/SearchInput.vue'
import BatchPreviewModal from '@/views/panels/components/BatchPreviewModal.vue'
import TaskHistory from './components/TaskHistory.vue'
import OperationImpactPreview from './components/OperationImpactPreview.vue'

interface AttachmentItem {
  name: string
  displayName: string
  size: number
  mediaType: string
  permalink: string
  groupName: string
  policyName: string
  /** 删除中（metadata.deletionTimestamp 已设置，等待 Halo GC） */
  deleting: boolean
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

let pollingTimer: ReturnType<typeof setTimeout> | null = null
let settleTimer: ReturnType<typeof setTimeout> | null = null
let settleRetries = 0

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
      policyName: att.spec.policyName || '',
      deleting: !!att.metadata.deletionTimestamp
    }))
    total.value = data.total || 0
    scheduleSettleRefetch()
  } catch (error) {
    console.error('获取附件列表失败:', error)
  } finally {
    loading.value = false
  }
}

/**
 * 数据驱动的补拉（对齐 Halo 官方附件列表的 refetchInterval 模式）：
 * 列表中存在"删除中"或"reconciler 尚未生成 permalink 的图片"时每秒重拉，
 * 数据稳定即停止；设重试上限防止个别附件永远无 permalink 导致的无限轮询。
 */
const hasUnsettledAttachments = () =>
  attachments.value.some(att => att.deleting || (isImage(att.mediaType) && !att.permalink))

const scheduleSettleRefetch = () => {
  if (!hasUnsettledAttachments()) {
    settleRetries = 0
    return
  }
  if (settleTimer || settleRetries >= 10) return
  settleRetries++
  settleTimer = setTimeout(() => {
    settleTimer = null
    fetchAttachments()
  }, 1000)
}

// 开始处理
const replaceReferences = ref(true)  // 默认勾选替换引用
const showCustomDialog = ref(false)
const previewEligibleNames = ref<string[]>([])

const startProcessing = () => {
  if (selectedAttachments.value.length === 0) return

  // 显示自定义对话框
  showCustomDialog.value = true
}

// 处理自定义对话框确认
const handleDialogConfirm = async () => {
  if (!previewEligibleNames.value.length) return
  showCustomDialog.value = false
  await executeBatchProcessing()
}

const onHistoryTaskStarted = () => {
  clearResults()
  processing.value = true
  startPolling()
}

// 执行批量处理任务
const executeBatchProcessing = async () => {
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

  // 清空选择并刷新列表（列表若含删除中/未就绪条目，fetchAttachments 内部会数据驱动地补拉直至稳定）
  selectedAttachments.value = []
  settleRetries = 0
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
  settleRetries = 0
  if (page.value !== 1) {
    page.value = 1
  } else {
    fetchAttachments()
  }
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

// 搜索（防抖由 SearchInput 组件负责）
const handleSearch = () => {
  selectedAttachments.value = []  // 搜索时清空选择
  if (page.value !== 1) {
    page.value = 1
  } else {
    fetchAttachments()
  }
}

watch([page, pageSize], ([, newSize], [, oldSize]) => {
  if (newSize !== oldSize) {
    selectedAttachments.value = []
  }
  fetchAttachments()
})

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
    } catch {
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
    } catch {
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
  if (settleTimer) clearTimeout(settleTimer)
})
</script>

<style scoped>
.batch-processing-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.status-hint {
  font-size: 13px;
  font-weight: 500;
}

.status-hint.warning {
  color: var(--st-warning-text);
}

.status-hint.info {
  color: var(--st-info-text);
}

/* 进度条容器（外观由 .st-card 提供，此处只补内边距） */
.progress-section {
  padding: 16px;
}

/* 统计 */
.stats-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

/* 日志（外观由 .st-card 提供，此处保留圆角裁剪） */
.log-section {
  overflow: hidden;
}

.log-tabs {
  display: flex;
  border-bottom: 1px solid var(--st-border);
}

.log-tab {
  padding: 12px 20px;
  font-size: 13px;
  background: none;
  border: none;
  color: var(--st-text-secondary);
  cursor: pointer;
}

.log-tab.active {
  color: var(--st-text-primary);
  font-weight: 500;
  border-bottom: 2px solid var(--st-text-primary);
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
  border-bottom: 1px solid var(--st-border-subtle);
}

.log-item:last-child { border-bottom: none; }

.log-item.failed { background: var(--st-error-bg); }
.log-item.skipped { background: var(--st-warning-bg); }

.log-name {
  font-size: 13px;
  color: var(--st-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
  flex: 1;
}

.log-reason {
  font-size: 12px;
  color: var(--st-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex-shrink: 0;
  max-width: 50%;
}

/* 列表卡片（外观由 .st-card 提供，此处保留圆角裁剪） */
.list-card {
  overflow: hidden;
}

/* 表格：列宽等本地细节（基础样式来自 .st-table） */
.batch-table {
  table-layout: fixed;
  min-width: 520px;
}

.batch-table td {
  white-space: nowrap;
}

.col-checkbox {
  width: 40px;
  text-align: center;
}

.col-filename { width: 60%; }
.col-type { width: 20%; }
.col-size { width: 20%; }

.col-checkbox input[type="checkbox"] {
  width: 16px;
  height: 16px;
  cursor: pointer;
  accent-color: var(--st-color-primary);
}

.batch-table tbody tr {
  cursor: pointer;
}

.cell-name {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  min-width: 0;
}

.cell-name:hover .file-name-text {
  color: var(--st-color-primary);
}

.file-thumbnail {
  width: 32px;
  height: 32px;
  object-fit: cover;
  border-radius: var(--st-radius-sm);
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
  min-width: 0;
  transition: color var(--st-duration) var(--st-ease);
}

/* 批量处理确认对话框 */
.batch-confirm-content {
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

.confirm-alert-warning {
  background: var(--st-error-bg);
  color: var(--st-error-text);
  border: 1px solid var(--st-error-border);
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

.text-danger {
  color: var(--st-error-text);
  font-weight: 600;
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
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
