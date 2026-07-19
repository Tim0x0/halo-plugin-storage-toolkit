<template>
  <div class="broken-link-tab">
    <!-- 操作栏 -->
    <div class="st-toolbar">
      <div class="st-toolbar-left">
        <VButton type="secondary" :loading="scanning" @click="startScan">开始扫描</VButton>
        <VButton type="danger" ghost :disabled="scanning || !stats.lastScanTime" @click="clearRecords">
          清空记录
        </VButton>
        <VButton
          v-if="brokenLinks.length > 0"
          :disabled="selectedUrls.length === 0"
          @click="ignoreSelected"
        >
          忽略选中 ({{ selectedUrls.length }})
        </VButton>
        <span class="scan-info" v-if="stats.lastScanTime && !scanning">上次扫描：{{ formatTime(stats.lastScanTime) }}</span>
        <span class="scan-info error" v-else-if="stats.phase === 'ERROR'">扫描失败：{{ stats.errorMessage }}</span>
      </div>
      <div class="st-toolbar-right">
        <select v-model="filterSourceType" class="st-select" @change="handleFilterChange">
          <option value="">全部来源</option>
          <option value="Post">文章</option>
          <option value="SinglePage">页面</option>
          <option value="Comment">评论</option>
          <option value="Reply">回复</option>
          <option value="Moment">瞬间</option>
          <option value="Photo">图库</option>
          <option value="Doc">文档</option>
          <option value="SystemSetting">系统设置</option>
          <option value="PluginSetting">插件设置</option>
          <option value="ThemeSetting">主题设置</option>
          <option value="User">用户</option>
        </select>
        <select v-model="filterReason" class="st-select" @change="handleFilterChange">
          <option value="">全部类型</option>
          <option value="HTTP_ERROR">HTTP 错误</option>
          <option value="HTTP_TIMEOUT">请求超时</option>
          <option value="CONNECTION_FAILED">连接失败</option>
          <option value="ATTACHMENT_NOT_FOUND">附件库丢失</option>
        </select>
        <SearchInput v-model="searchKeyword" placeholder="搜索URL或标题..." @search="handleFilterChange" />
      </div>
    </div>

    <!-- 提示 -->
    <VAlert
      type="info"
      title="检测说明"
      description="扫描内容中引用了不存在附件的链接，与引用统计使用相同的扫描配置"
    />

    <!-- 统计 -->
    <div class="stats-row">
      <StatCard label="检查链接数" :value="stats.checkedLinkCount" :icon="IconLink" />
      <StatCard label="断链数" :value="stats.brokenLinkCount" tone="error" :icon="IconCloseCircle" />
    </div>

    <!-- 断链列表 -->
    <div class="st-card">
      <VLoading v-if="loading" />

      <VEmpty
        v-else-if="!stats.lastScanTime && !scanning"
        title="尚未进行扫描"
        message="点击「开始扫描」检测内容中的失效链接"
      >
        <template #actions>
          <VButton type="secondary" :loading="scanning" @click="startScan">开始扫描</VButton>
        </template>
      </VEmpty>

      <VEmpty
        v-else-if="brokenLinks.length === 0"
        title="没有发现断链"
        message="所有媒体链接都有效"
      />

      <template v-else>
        <div class="st-table-wrapper">
          <table class="st-table broken-table">
            <thead>
              <tr>
                <th class="col-checkbox">
                  <input
                    type="checkbox"
                    :checked="isAllSelected"
                    :indeterminate="isIndeterminate"
                    @change="toggleSelectAll"
                  />
                </th>
                <th class="col-url-head">断链 URL</th>
                <th class="col-source">来源位置</th>
                <th class="col-reason">断链原因</th>
                <th class="col-time">发现时间</th>
                <th class="col-count st-th--sortable" @click="toggleSort('sourceCount')">
                  出现次数
                  <span v-if="sortField === 'sourceCount'">{{ sortDesc ? '↓' : '↑' }}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="link in brokenLinks"
                :key="link.url"
                :class="{ 'st-table-row--selected': selectedUrls.includes(link.url) }"
              >
                <td class="col-checkbox">
                  <input
                    type="checkbox"
                    :checked="selectedUrls.includes(link.url)"
                    @change="toggleSelect(link.url)"
                  />
                </td>
                <td class="cell-url" @click="showDetail(link)">
                  <span class="url-text" :title="link.originalUrl || link.url">{{ truncateUrl(link.originalUrl || link.url) }}</span>
                </td>
                <td>
                  <div class="source-locations">
                    <span
                      :class="['st-tag', getSourceTypeClass(type)]"
                      v-for="type in getUniqueSourceTypes(link.sources)"
                      :key="type"
                      :title="getSourceTypeLabel(type)"
                    >
                      {{ getSourceTypeLabel(type) }}
                    </span>
                  </div>
                </td>
                <td>
                  <StatusBadge :tone="getReasonTone(link.reason)">
                    {{ getReasonLabel(link.reason) }}
                  </StatusBadge>
                </td>
                <td class="col-time">{{ formatTime(link.discoveredAt) }}</td>
                <td>
                  <span
                    class="source-count"
                    @click="showDetail(link)"
                    :title="'点击查看详情'"
                  >
                    {{ link.sourceCount }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 分页 -->
        <VPagination v-if="total > 0" v-model:page="page" v-model:size="pageSize" :total="total" :size-options="PAGE_SIZE_OPTIONS" />
      </template>
    </div>

    <!-- 详情弹窗 -->
    <BrokenLinkDetailModal
      v-if="showDetailModal"
      :link="selectedLink"
      @close="showDetailModal = false"
      @whitelisted="handleWhitelisted"
      @replaced="handleReplaced"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { Dialog, Toast, VAlert, VButton, VEmpty, VLoading, VPagination, IconLink, IconCloseCircle } from '@halo-dev/components'
import { PAGE_SIZE_OPTIONS, DEFAULT_PAGE_SIZE } from '@/constants/pagination'
import { API_ENDPOINTS } from '@/constants/api'
import { formatTime } from '@/utils/format'
import {
  getSourceTypeLabel,
  getSourceTypeClass,
  getUniqueSourceTypes
} from '@/composables'
import StatCard from '@/components/StatCard.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import SearchInput from '@/components/SearchInput.vue'
import BrokenLinkDetailModal from '@/views/panels/components/BrokenLinkDetailModal.vue'
import type { BadgeTone } from '@/types/ui'

interface BrokenLinkStats {
  phase: 'SCANNING' | 'COMPLETED' | 'ERROR' | null
  startTime: string | null
  lastScanTime: string | null
  scannedContentCount: number
  checkedLinkCount: number
  brokenLinkCount: number
  errorMessage: string | null
}

interface BrokenLinkSource {
  name: string
  sourceType: string
  sourceName: string
  sourceTitle: string | null
  sourceUrl: string | null
  deleted: boolean | null
  settingName: string | null
  referenceType: string | null
}

interface BrokenLinkVo {
  url: string
  originalUrl: string | null
  sources: BrokenLinkSource[]
  sourceCount: number
  discoveredAt: string
  reason?: string
}

const stats = ref<BrokenLinkStats>({
  phase: null,
  startTime: null,
  lastScanTime: null,
  scannedContentCount: 0,
  checkedLinkCount: 0,
  brokenLinkCount: 0,
  errorMessage: null
})

const brokenLinks = ref<BrokenLinkVo[]>([])
const page = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)
const total = ref(0)
const scanning = ref(false)
const loading = ref(false)
const filterSourceType = ref('')
const filterReason = ref('')
const searchKeyword = ref('')
const showDetailModal = ref(false)
const selectedLink = ref<BrokenLinkVo | null>(null)
const selectedUrls = ref<string[]>([])
const sortField = ref('sourceCount')
const sortDesc = ref(true)

const pollTimer = ref<number>()

const isAllSelected = computed(() => {
  return brokenLinks.value.length > 0 &&
    brokenLinks.value.every(item => selectedUrls.value.includes(item.url))
})

const isIndeterminate = computed(() => {
  return selectedUrls.value.length > 0 && !isAllSelected.value
})

const handleFilterChange = () => {
  if (page.value !== 1) {
    page.value = 1
  } else {
    fetchBrokenLinks()
  }
}

const toggleSelectAll = () => {
  if (isAllSelected.value) {
    selectedUrls.value = []
  } else {
    selectedUrls.value = brokenLinks.value.map(item => item.url)
  }
}

const toggleSelect = (url: string) => {
  const index = selectedUrls.value.indexOf(url)
  if (index === -1) {
    selectedUrls.value.push(url)
  } else {
    selectedUrls.value.splice(index, 1)
  }
}

const toggleSort = (field: string) => {
  if (sortField.value === field) {
    sortDesc.value = !sortDesc.value
  } else {
    sortField.value = field
    sortDesc.value = true
  }
  fetchBrokenLinks()
}

// 获取状态
const fetchStats = async () => {
  try {
    const { data } = await axiosInstance.get<BrokenLinkStats>(API_ENDPOINTS.BROKEN_LINKS_STATUS)
    stats.value = data
    scanning.value = data.phase === 'SCANNING'
  } catch (error) {
    console.error('获取断链扫描状态失败', error)
  }
}

// 获取断链列表
const fetchBrokenLinks = async () => {
  loading.value = true
  try {
    const params: Record<string, string | number> = { page: page.value, size: pageSize.value }
    if (filterSourceType.value) params.sourceType = filterSourceType.value
    if (filterReason.value) params.reason = filterReason.value
    if (searchKeyword.value) params.keyword = searchKeyword.value
    if (sortField.value) params.sort = `${sortField.value},${sortDesc.value ? 'desc' : 'asc'}`

    const { data } = await axiosInstance.get(API_ENDPOINTS.BROKEN_LINKS, { params })
    brokenLinks.value = data.items || []
    total.value = data.total || 0
  } catch (error) {
    console.error('获取断链列表失败', error)
  } finally {
    loading.value = false
  }
}

// 开始扫描
const startScan = async () => {
  scanning.value = true
  try {
    await axiosInstance.post(API_ENDPOINTS.BROKEN_LINKS_SCAN)
    pollScanStatus()
  } catch {
    scanning.value = false
  }
}

// 清空记录
const clearRecords = () => {
  Dialog.warning({
    title: '确认清空',
    description: '确定要清空所有断链扫描记录吗？',
    confirmType: 'danger',
    confirmText: '清空',
    cancelText: '取消',
    async onConfirm() {
      try {
        await axiosInstance.delete(API_ENDPOINTS.BROKEN_LINKS)
        Toast.success('断链扫描记录已清空')
        stats.value = {
          phase: null,
          startTime: null,
          lastScanTime: null,
          scannedContentCount: 0,
          checkedLinkCount: 0,
          brokenLinkCount: 0,
          errorMessage: null
        }
        brokenLinks.value = []
        total.value = 0
        selectedUrls.value = []
      } catch {
        Toast.error('清空记录失败')
      }
    }
  })
}

// 批量忽略选中
const ignoreSelected = () => {
  if (selectedUrls.value.length === 0) return

  Dialog.warning({
    title: '确认忽略',
    description: `确定要将选中的 ${selectedUrls.value.length} 个 URL 添加到忽略白名单吗？这些 URL 将不再被标记为断链。`,
    confirmText: '确定忽略',
    cancelText: '取消',
    async onConfirm() {
      try {
        // 将选中的 url 映射为原始路径（相对路径保持相对，完整 URL 保持完整）
        const whitelistUrls = selectedUrls.value.map(url => {
          const link = brokenLinks.value.find(l => l.url === url)
          return link?.originalUrl || url
        })
        await axiosInstance.post(API_ENDPOINTS.WHITELIST_BATCH, { urls: whitelistUrls })
        Toast.success('已添加到忽略白名单')
        // 从列表中移除已忽略的项
        brokenLinks.value = brokenLinks.value.filter(
          item => !selectedUrls.value.includes(item.url)
        )
        total.value = Math.max(0, total.value - selectedUrls.value.length)
        selectedUrls.value = []
      } catch (error: unknown) {
        const err = error as { response?: { data?: { message?: string } }; message?: string }
        Toast.error('操作失败: ' + (err.response?.data?.message || err.message || '未知错误'))
      }
    }
  })
}

// 详情弹窗：单个忽略后移除列表项
const handleWhitelisted = (url: string) => {
  brokenLinks.value = brokenLinks.value.filter(item => item.url !== url)
  total.value = Math.max(0, total.value - 1)
  showDetailModal.value = false
}

// 详情弹窗：替换成功后移除列表项（部分成功则刷新）
const handleReplaced = (payload: { url: string; refetch: boolean }) => {
  if (payload.refetch) {
    fetchBrokenLinks()
  } else {
    brokenLinks.value = brokenLinks.value.filter(item => item.url !== payload.url)
    total.value = Math.max(0, total.value - 1)
  }
  showDetailModal.value = false
}

// 轮询扫描状态
const pollScanStatus = () => {
  const poll = async () => {
    try {
      await fetchStats()
      if (stats.value.phase === 'SCANNING') {
        pollTimer.value = window.setTimeout(poll, 2000)
      } else {
        scanning.value = false
        fetchBrokenLinks()
      }
    } catch (error) {
      console.error('轮询扫描状态失败:', error)
      scanning.value = false
    }
  }
  poll()
}

const truncateUrl = (url: string): string => {
  if (!url) return ''
  return url.length > 60 ? url.substring(0, 60) + '...' : url
}

const showDetail = (link: BrokenLinkVo) => {
  selectedLink.value = link
  showDetailModal.value = true
}

const getReasonLabel = (reason: string | undefined): string => {
  if (!reason) return '未知原因'
  // 特殊错误：固定映射
  const map: Record<string, string> = {
    'ATTACHMENT_NOT_FOUND': '附件库丢失',
    'HTTP_TIMEOUT': '请求超时',
    'CONNECTION_FAILED': '连接失败'
  }
  // 不在 map 中直接返回（如 "HTTP 403"）
  return map[reason] ?? reason
}

// 断链原因语义色：HTTP 错误类 → error，超时/连接/附件丢失 → warning
const getReasonTone = (reason: string | undefined): BadgeTone => {
  if (!reason) return 'neutral'
  if (reason.startsWith('HTTP ')) return 'error'
  const map: Record<string, BadgeTone> = {
    'HTTP_TIMEOUT': 'warning',
    'CONNECTION_FAILED': 'warning',
    'ATTACHMENT_NOT_FOUND': 'warning'
  }
  return map[reason] ?? 'neutral'
}

onMounted(async () => {
  await fetchStats()
  if (stats.value.phase === 'SCANNING') {
    scanning.value = true
    pollScanStatus()
  } else if (stats.value.lastScanTime) {
    await fetchBrokenLinks()
  }
})

onUnmounted(() => {
  if (pollTimer.value) {
    clearTimeout(pollTimer.value)
  }
})

watch([page, pageSize], ([, newSize], [, oldSize]) => {
  if (newSize !== oldSize) {
    selectedUrls.value = []
  }
  fetchBrokenLinks()
})
</script>

<style scoped>
.broken-link-tab {
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

/* 统计 */
.stats-row {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

/* 表格：列宽等本地细节（基础样式来自 .st-table） */
.broken-table {
  table-layout: fixed;
  min-width: 700px;
}

.broken-table th,
.broken-table td {
  white-space: nowrap;
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

.col-url-head { width: 34%; }
.col-source { width: 14%; }
.col-reason { width: 13%; }
.col-time { width: 22%; color: var(--st-text-secondary); }
.col-count { width: 17%; }

.cell-url {
  cursor: pointer;
}

.cell-url:hover .url-text {
  color: var(--st-info-text);
}

.url-text {
  font-family: monospace;
  font-size: 13px;
  color: var(--st-text-secondary);
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color var(--st-duration) var(--st-ease);
}

.source-locations {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.source-count {
  display: inline-block;
  min-width: 24px;
  padding: 4px 10px;
  text-align: center;
  border-radius: var(--st-radius-sm);
  font-size: 12px;
  font-weight: 500;
  background: var(--st-error-bg);
  color: var(--st-error-text);
  cursor: pointer;
  transition: background var(--st-duration) var(--st-ease);
}

.source-count:hover {
  background: var(--st-error-border);
}

/* 响应式 */
@media (max-width: 768px) {
  .st-toolbar-left,
  .st-toolbar-right {
    flex-wrap: wrap;
    width: 100%;
  }

  .st-select {
    flex: 1;
    min-width: 0;
  }
}

@media (max-width: 640px) {
  .stats-row {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
