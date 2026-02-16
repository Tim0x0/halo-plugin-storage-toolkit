<template>
  <div class="broken-link-tab">
    <!-- 操作栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <button class="btn-scan" @click="startScan" :disabled="scanning">
          <span v-if="scanning">扫描中...</span>
          <span v-else>扫描断链</span>
        </button>
        <button class="btn-clear" @click="clearRecords" :disabled="scanning || !stats.lastScanTime">
          清空记录
        </button>
        <button
          class="btn-ignore"
          @click="ignoreSelected"
          :disabled="selectedUrls.length === 0"
          v-if="brokenLinks.length > 0"
        >
          忽略选中 ({{ selectedUrls.length }})
        </button>
        <span class="scan-info" v-if="stats.lastScanTime && !scanning">上次扫描：{{ formatTime(stats.lastScanTime) }}</span>
        <span class="scan-info error" v-else-if="stats.phase === 'ERROR'">扫描失败：{{ stats.errorMessage }}</span>
      </div>
      <div class="toolbar-right">
        <select v-model="filterSourceType" class="filter-select" @change="handleFilterChange">
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
        <select v-model="filterReason" class="filter-select" @change="handleFilterChange">
          <option value="">全部类型</option>
          <option value="HTTP_ERROR">HTTP 错误</option>
          <option value="HTTP_TIMEOUT">请求超时</option>
          <option value="CONNECTION_FAILED">连接失败</option>
          <option value="ATTACHMENT_NOT_FOUND">附件库丢失</option>
        </select>
        <input
          type="text"
          v-model="searchKeyword"
          placeholder="搜索URL或标题..."
          class="search-input"
          @input="handleSearchDebounced"
        />
      </div>
    </div>

    <!-- 提示 -->
    <div class="notice info">
      <span class="notice-icon">💡</span>
      <span>扫描内容中引用了不存在附件的链接，与引用统计使用相同的扫描配置</span>
    </div>

    <!-- 统计 -->
    <div class="stats-row">
      <div class="stat-box">
        <span class="stat-num">{{ stats.checkedLinkCount }}</span>
        <span class="stat-text">检查链接数</span>
      </div>
      <div class="stat-box">
        <span class="stat-num red">{{ stats.brokenLinkCount }}</span>
        <span class="stat-text">断链数</span>
      </div>
    </div>

    <!-- 断链列表 -->
    <div class="card">
      <div v-if="loading" class="loading-state">加载中...</div>
      <div v-else-if="!stats.lastScanTime && stats.phase !== 'SCANNING'" class="empty-state">
        <span class="empty-icon">🔗</span>
        <span class="empty-text">尚未进行扫描</span>
        <span class="empty-hint">点击「扫描断链」开始检测</span>
      </div>
      <div v-else-if="brokenLinks.length === 0" class="empty-state">
        <span class="empty-icon">✨</span>
        <span class="empty-text">没有发现断链</span>
        <span class="empty-hint">所有媒体链接都有效</span>
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
                />
              </th>
              <th>断链 URL</th>
              <th>来源位置</th>
              <th>断链原因</th>
              <th>发现时间</th>
              <th class="sortable" @click="toggleSort('sourceCount')">
                出现次数
                <span v-if="sortField === 'sourceCount'">{{ sortDesc ? '↓' : '↑' }}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="link in brokenLinks" :key="link.url">
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
                    :class="['location-tag', getSourceTypeClass(type)]"
                    v-for="type in getUniqueSourceTypes(link.sources)"
                    :key="type"
                    :title="getSourceTypeLabel(type)"
                  >
                    {{ getSourceTypeLabel(type) }}
                  </span>
                </div>
              </td>
              <td>
                <span :class="['reason-tag', getReasonClass(link.reason)]">
                  {{ getReasonLabel(link.reason) }}
                </span>
              </td>
              <td>{{ formatTime(link.discoveredAt) }}</td>
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

        <!-- 分页 -->
        <div class="pagination" v-if="total > 0">
          <div class="page-info">共 {{ total }} 条</div>
          <div class="page-controls">
            <button type="button" class="page-btn" :disabled="page <= 1" @click="changePage(page - 1)">上一页</button>
            <span class="page-num">{{ page }} / {{ totalPages }}</span>
            <button type="button" class="page-btn" :disabled="page >= totalPages" @click="changePage(page + 1)">下一页</button>
          </div>
          <select v-model="pageSize" class="page-size" @change="onPageSizeChange">
            <option v-for="size in PAGE_SIZE_OPTIONS" :key="size" :value="size">
              {{ size }}条/页
            </option>
          </select>
        </div>
      </template>
    </div>

    <!-- 详情对话框 -->
    <div class="modal-overlay" v-if="showDetailModal" @click.self="showDetailModal = false">
      <div class="modal-content">
        <div class="modal-header">
          <h3>断链详情</h3>
          <button class="modal-close" @click="showDetailModal = false">×</button>
        </div>
        <div class="modal-body">
          <!-- 断链信息 -->
          <div class="info-section">
            <div class="info-item">
              <span class="info-label">断链 URL</span>
              <span class="info-value info-url">{{ selectedLink?.originalUrl || selectedLink?.url }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">断链原因</span>
              <span class="info-value">
                <span :class="['reason-tag', getReasonClass(selectedLink?.reason)]">
                  {{ getReasonLabel(selectedLink?.reason) }}
                </span>
              </span>
            </div>
            <div class="info-item">
              <span class="info-label">发现时间</span>
              <span class="info-value">{{ formatTime(selectedLink?.discoveredAt || '') }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">出现次数</span>
              <span class="info-value">{{ selectedLink?.sourceCount }} 处</span>
            </div>
          </div>

          <!-- 来源列表 -->
          <ReferenceList
            :references="selectedLink?.sources"
            title="来源位置"
          />

          <!-- 替换区域 -->
          <div class="replace-section">
            <div class="replace-header" @click="showReplaceForm = !showReplaceForm; replaceInputError = false">
              <span class="replace-toggle">{{ showReplaceForm ? '▼' : '▶' }}</span>
              <span class="replace-title">替换为新 URL</span>
            </div>
            <div class="replace-form" v-if="showReplaceForm">
              <input
                type="text"
                ref="replaceInputRef"
                v-model="replaceNewUrl"
                placeholder="输入新 URL"
                :class="['replace-input', { 'replace-input-error': replaceInputError }]"
                @input="replaceInputError = false"
              />
            </div>
          </div>

          <!-- 操作区域 -->
          <div class="action-section">
            <button class="btn-action-ignore" @click="ignoreSingle(selectedLink)">
              添加到忽略白名单
            </button>
            <button class="btn-action-replace" @click="handleReplaceClick" :disabled="replacing">
              {{ replacing ? '替换中...' : '替换 URL' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { Dialog, Toast } from '@halo-dev/components'
import { PAGE_SIZE_OPTIONS, DEFAULT_PAGE_SIZE } from '@/constants/pagination'
import { API_ENDPOINTS } from '@/constants/api'
import { formatTime } from '@/utils/format'
import {
  getSourceTypeLabel,
  getSourceTypeClass,
  getUniqueSourceTypes
} from '@/composables'
import ReferenceList from '@/components/ReferenceList.vue'

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

// 替换相关状态
const showReplaceForm = ref(false)
const replaceNewUrl = ref('')
const replacing = ref(false)
const replaceInputError = ref(false)
const replaceInputRef = ref<HTMLInputElement | null>(null)

const searchDebounceTimer = ref<number>()
const pollTimer = ref<number>()

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

const isAllSelected = computed(() => {
  return brokenLinks.value.length > 0 &&
    brokenLinks.value.every(item => selectedUrls.value.includes(item.url))
})

const isIndeterminate = computed(() => {
  return selectedUrls.value.length > 0 && !isAllSelected.value
})

const handleSearchDebounced = () => {
  clearTimeout(searchDebounceTimer.value)
  searchDebounceTimer.value = window.setTimeout(() => {
    page.value = 1
    fetchBrokenLinks()
  }, 300)
}

const handleFilterChange = () => {
  page.value = 1
  fetchBrokenLinks()
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
    const params: Record<string, any> = { page: page.value, size: pageSize.value }
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
  } catch (error: any) {
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
      } catch (error: any) {
        Toast.error('清空记录失败')
        console.error('清空记录失败:', error)
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
      } catch (error: any) {
        Toast.error('操作失败: ' + (error.response?.data?.message || error.message))
      }
    }
  })
}

// 单个忽略
const ignoreSingle = async (link: BrokenLinkVo | null) => {
  if (!link) return
  const whitelistUrl = link.originalUrl || link.url

  Dialog.warning({
    title: '确认忽略',
    description: `确定要将此 URL 添加到忽略白名单吗？此 URL 将不再被标记为断链。`,
    confirmText: '确定忽略',
    cancelText: '取消',
    async onConfirm() {
      try {
        await axiosInstance.post(API_ENDPOINTS.WHITELIST_BATCH, { urls: [whitelistUrl] })
        Toast.success('已添加到忽略白名单')
        showDetailModal.value = false
        // 从列表中移除
        brokenLinks.value = brokenLinks.value.filter(item => item.url !== link.url)
        total.value = Math.max(0, total.value - 1)
        return true
      } catch (error: any) {
        Toast.error('操作失败: ' + (error.response?.data?.message || error.message))
        return false
      }
    }
  })
}

// 处理替换按钮点击
const handleReplaceClick = () => {
  if (replaceNewUrl.value.trim()) {
    // URL 已填写，直接执行替换
    executeReplace()
  } else {
    // URL 为空，展开表单并标红
    showReplaceForm.value = true
    replaceInputError.value = true
    // 等待 DOM 更新后聚焦输入框
    setTimeout(() => {
      replaceInputRef.value?.focus()
    }, 50)
  }
}

// 执行断链替换
const executeReplace = async () => {
  if (!selectedLink.value || !replaceNewUrl.value) return

  const oldUrl = selectedLink.value.url
  const newUrl = replaceNewUrl.value.trim()

  if (!newUrl) {
    Toast.warning('请输入新 URL')
    return
  }

  replacing.value = true
  try {
    const { data } = await axiosInstance.post(API_ENDPOINTS.BROKEN_LINKS_REPLACE, {
      oldUrl,
      newUrl
    })

    if (data.allSuccess) {
      Toast.success(`替换成功，共 ${data.successCount} 处`)
      showDetailModal.value = false
      showReplaceForm.value = false
      replaceNewUrl.value = ''
      // 从列表中移除
      brokenLinks.value = brokenLinks.value.filter(item => item.url !== oldUrl)
      total.value = Math.max(0, total.value - 1)
    } else if (data.successCount > 0) {
      Toast.warning(`部分替换成功：${data.successCount} 成功，${data.failedCount} 失败`)
      // 刷新列表
      await fetchBrokenLinks()
      showDetailModal.value = false
      showReplaceForm.value = false
      replaceNewUrl.value = ''
    } else {
      const failMsg = data.failures?.map((f: any) => `${f.sourceTitle || f.sourceName}: ${f.errorMessage}`).join('; ') || '未知错误'
      Toast.error(`替换失败：${failMsg}`)
    }
  } catch (error: any) {
    Toast.error('替换操作失败: ' + (error.response?.data?.message || error.message))
  } finally {
    replacing.value = false
  }
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

const changePage = (newPage: number) => {
  if (newPage >= 1 && newPage <= totalPages.value) {
    page.value = newPage
    fetchBrokenLinks()
  }
}

const onPageSizeChange = () => {
  page.value = 1
  selectedUrls.value = []
  fetchBrokenLinks()
}

const truncateUrl = (url: string): string => {
  if (!url) return ''
  return url.length > 60 ? url.substring(0, 60) + '...' : url
}

const showDetail = (link: BrokenLinkVo) => {
  selectedLink.value = link
  showDetailModal.value = true
  showReplaceForm.value = false
  replaceNewUrl.value = ''
  replaceInputError.value = false
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

const getReasonClass = (reason: string | undefined): string => {
  if (!reason) return 'tag-gray'
  // HTTP 状态码（如 "HTTP 403"、"HTTP 404"）统一使用红色
  if (reason.startsWith('HTTP ')) return 'tag-red'
  const map: Record<string, string> = {
    'HTTP_TIMEOUT': 'tag-orange',
    'CONNECTION_FAILED': 'tag-orange',
    'ATTACHMENT_NOT_FOUND': 'tag-amber'
  }
  return map[reason] ?? 'tag-gray'
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
  if (searchDebounceTimer.value) {
    clearTimeout(searchDebounceTimer.value)
  }
})
</script>

<style scoped>
.broken-link-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
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

.btn-ignore {
  padding: 8px 16px;
  font-size: 14px;
  background: #f59e0b;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-ignore:hover:not(:disabled) {
  background: #d97706;
}

.btn-ignore:disabled {
  background: #fcd34d;
  cursor: not-allowed;
}

.scan-info { font-size: 13px; color: #71717a; }
.scan-info.error { color: #dc2626; }

.filter-select, .search-input {
  padding: 8px 12px;
  font-size: 14px;
  border: 1px solid #e4e4e7;
  border-radius: 6px;
  background: white;
}

.search-input {
  width: 200px;
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

.stats-row {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
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

.stat-num.red { color: #dc2626; }

.stat-text { font-size: 13px; color: #71717a; margin-top: 4px; }

.card {
  background: white;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
}

.loading-state {
  padding: 48px;
  text-align: center;
  color: #71717a;
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

.data-table td {
  font-size: 14px;
  color: #18181b;
}

.data-table tbody tr:hover {
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

.cell-url {
  max-width: 300px;
  cursor: pointer;
}

.cell-url:hover .url-text {
  color: #2563eb;
}

.url-text {
  font-family: monospace;
  font-size: 13px;
  color: #71717a;
  word-break: break-all;
  transition: color 0.15s;
}

.source-locations {
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

.reason-tag {
  font-size: 12px;
  padding: 2px 8px;
  background: #f4f4f5;
  border-radius: 4px;
  color: #3f3f46;
  white-space: nowrap;
}

.reason-tag.tag-red {
  background: #fee2e2;
  color: #dc2626;
}

.reason-tag.tag-orange {
  background: #ffedd5;
  color: #c2410c;
}

.reason-tag.tag-amber {
  background: #fef3c7;
  color: #b45309;
}

.reason-tag.tag-gray {
  background: #f4f4f5;
  color: #71717a;
}

.source-count {
  display: inline-block;
  min-width: 24px;
  padding: 4px 10px;
  text-align: center;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  background: #fee2e2;
  color: #dc2626;
  cursor: pointer;
}

.source-count:hover {
  background: #fecaca;
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

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px;
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
  flex-shrink: 0;
}

.modal-header h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  color: #18181b;
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
}

.modal-close:hover {
  background: #f4f4f5;
  color: #71717a;
}

.modal-body {
  padding: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

/* 信息区域 */
.info-section {
  padding: 16px;
  border-bottom: 1px solid #f4f4f5;
  flex-shrink: 0;
}

.info-item {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 8px 0;
  gap: 16px;
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
  min-width: 70px;
}

.info-value {
  font-size: 13px;
  color: #18181b;
  text-align: right;
  word-break: break-all;
  flex: 1;
}

.info-value.info-url {
  font-size: 12px;
  color: #71717a;
  font-family: monospace;
}

/* 来源列表区域 - 布局覆盖 */
:deep(.reference-section) {
  padding: 16px;
  border-bottom: 1px solid #f4f4f5;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}

:deep(.reference-section:hover) {
  scrollbar-color: rgba(0, 0, 0, 0.2) transparent;
}

:deep(.reference-section::-webkit-scrollbar) {
  width: 6px;
}

:deep(.reference-section::-webkit-scrollbar-track) {
  background: transparent;
}

:deep(.reference-section::-webkit-scrollbar-thumb) {
  background: transparent;
  border-radius: 3px;
}

:deep(.reference-section:hover::-webkit-scrollbar-thumb) {
  background: rgba(0, 0, 0, 0.2);
}

:deep(.empty-references) {
  border-bottom: 1px solid #f4f4f5;
}

/* 操作区域 */
.action-section {
  padding: 16px;
  display: flex;
  justify-content: center;
  gap: 12px;
  flex-shrink: 0;
}

.btn-action-ignore {
  display: inline-block;
  padding: 8px 16px;
  font-size: 14px;
  background: #f59e0b;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-action-ignore:hover {
  background: #d97706;
}

.btn-action-replace {
  display: inline-block;
  padding: 8px 16px;
  font-size: 14px;
  background: #18181b;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-action-replace:hover {
  background: #3f3f46;
}

.btn-action-replace:disabled {
  background: #a1a1aa;
  cursor: not-allowed;
}

/* 替换区域 */
.replace-section {
  border-top: 1px solid #f4f4f5;
  flex-shrink: 0;
}

.replace-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  cursor: pointer;
  user-select: none;
}

.replace-header:hover {
  background: #fafafa;
}

.replace-toggle {
  font-size: 10px;
  color: #a1a1aa;
}

.replace-title {
  font-size: 13px;
  font-weight: 500;
  color: #18181b;
}

.replace-form {
  padding: 0 16px 16px;
}

.replace-input {
  width: 100%;
  height: 38px;
  padding: 0 12px;
  font-size: 14px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  outline: none;
}

.replace-input:focus {
  border-color: #4f46e5;
}

.replace-input-error {
  border-color: #ef4444;
  background-color: #fef2f2;
}

.replace-input-error:focus {
  border-color: #ef4444;
}
</style>
