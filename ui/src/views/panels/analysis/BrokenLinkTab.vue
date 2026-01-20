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
      <span>扫描内容中引用了不存在附件的链接，与引用统计共用同一扫描结果</span>
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
                <span class="url-text" :title="link.url">{{ truncateUrl(link.url) }}</span>
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
              <span class="info-value info-url">{{ selectedLink?.url }}</span>
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
          <div class="reference-section" v-if="selectedLink?.sources?.length">
            <div class="section-header">
              <span class="section-title">来源位置</span>
              <span class="section-count">{{ selectedLink.sources.length }} 处</span>
            </div>
            <div class="reference-list">
              <a
                class="reference-item"
                v-for="source in selectedLink.sources"
                :key="source.name"
                :href="source.sourceUrl || 'javascript:void(0)'"
                :target="source.sourceUrl ? '_blank' : undefined"
                :class="{ 'no-link': !source.sourceUrl }"
              >
                <span class="ref-icon">{{ getSourceTypeIcon(source.sourceType) }}</span>
                <div class="ref-content">
                  <span class="ref-title">{{ getRefDisplayTitle(source) }}</span>
                  <div class="ref-tags">
                    <span class="ref-tag">{{ getReferenceTypeLabel(source) }}</span>
                    <span class="ref-tag deleted" v-if="source.deleted">回收站</span>
                  </div>
                </div>
                <span class="ref-arrow" v-if="source.sourceUrl">→</span>
              </a>
            </div>
          </div>

          <!-- 操作区域 -->
          <div class="action-section">
            <button class="btn-action-ignore" @click="ignoreSingle(selectedLink?.url)">
              添加到忽略白名单
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { Dialog, Toast } from '@halo-dev/components'
import { PAGE_SIZE_OPTIONS, DEFAULT_PAGE_SIZE } from '@/constants/pagination'
import { API_ENDPOINTS } from '@/constants/api'

interface BrokenLinkStats {
  phase: string | null
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
  sources: BrokenLinkSource[]
  sourceCount: number
  discoveredAt: string
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
const searchKeyword = ref('')
const showDetailModal = ref(false)
const selectedLink = ref<BrokenLinkVo | null>(null)
const selectedUrls = ref<string[]>([])
const sortField = ref('sourceCount')
const sortDesc = ref(true)

const searchDebounceTimer = ref<number>()

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
        await axiosInstance.post(API_ENDPOINTS.BROKEN_LINKS_WHITELIST, { urls: selectedUrls.value })
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
const ignoreSingle = async (url: string | undefined) => {
  if (!url) return

  Dialog.warning({
    title: '确认忽略',
    description: `确定要将此 URL 添加到忽略白名单吗？此 URL 将不再被标记为断链。`,
    confirmText: '确定忽略',
    cancelText: '取消',
    async onConfirm() {
      try {
        await axiosInstance.post(API_ENDPOINTS.BROKEN_LINKS_WHITELIST, { urls: [url] })
        Toast.success('已添加到忽略白名单')
        showDetailModal.value = false
        // 从列表中移除
        brokenLinks.value = brokenLinks.value.filter(item => item.url !== url)
        total.value = Math.max(0, total.value - 1)
        return true
      } catch (error: any) {
        Toast.error('操作失败: ' + (error.response?.data?.message || error.message))
        return false
      }
    }
  })
}

// 轮询扫描状态
const pollScanStatus = () => {
  const poll = async () => {
    await fetchStats()
    if (stats.value.phase === 'SCANNING') {
      setTimeout(poll, 2000)
    } else {
      scanning.value = false
      fetchBrokenLinks()
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

const formatTime = (time: string | null): string => {
  if (!time) return ''
  return new Date(time).toLocaleString('zh-CN')
}

const truncateUrl = (url: string): string => {
  if (!url) return ''
  return url.length > 60 ? url.substring(0, 60) + '...' : url
}

const showDetail = async (link: BrokenLinkVo) => {
  selectedLink.value = link
  showDetailModal.value = true

  if (link?.sources) {
    for (const source of link.sources) {
      // 1. 异步获取 Setting 引用的 group label
      if (SETTING_TYPES.includes(source.sourceType) && source.settingName && source.referenceType) {
        await fetchSettingGroupLabel(source.settingName, source.referenceType)
      }

      // 2. 异步解析评论/回复的关联标题
      if ((source.sourceType === 'Comment' || source.sourceType === 'Reply') && source.sourceTitle && !source.sourceUrl) {
        // sourceTitle 格式: "Kind:name"
        const colonIndex = source.sourceTitle.indexOf(':')
        if (colonIndex > 0) {
          const kind = source.sourceTitle.substring(0, colonIndex)
          const name = source.sourceTitle.substring(colonIndex + 1)
          try {
            const { data } = await axiosInstance.get(API_ENDPOINTS.REFERENCES_SUBJECT(kind, name))
            if (data.title || data.url) {
              // 更新本地显示
              source.sourceTitle = data.title || source.sourceTitle
              source.sourceUrl = data.url
            }
          } catch (e) {
            console.debug('解析引用源失败:', e)
          }
        }
      }

      // 3. 异步解析文档的标题和链接
      if (source.sourceType === 'Doc' && source.sourceTitle && !source.sourceUrl) {
        // sourceTitle 格式: "Doc:doc-name"
        const match = source.sourceTitle.match(/^Doc:(.+)$/)
        if (match) {
          const [, docName] = match
          try {
            const { data } = await axiosInstance.get(API_ENDPOINTS.REFERENCES_SUBJECT('Doc', docName))
            if (data.title || data.url) {
              // 更新本地显示
              source.sourceTitle = data.title || source.sourceTitle
              source.sourceUrl = data.url
            }
          } catch (e) {
            console.debug('解析文档引用源失败:', e)
          }
        }
      }
    }
  }
}

const getRefDisplayTitle = (ref: BrokenLinkSource): string => {
  if (ref.sourceType === 'Comment' || ref.sourceType === 'Reply' || ref.sourceType === 'Doc') {
    if (ref.sourceUrl) {
      return ref.sourceTitle || ref.sourceName
    }
    return '加载中...'
  }
  return ref.sourceTitle || ref.sourceName
}

const getUniqueSourceTypes = (sources: BrokenLinkSource[]): string[] => {
  if (!sources) return []
  return [...new Set(sources.map(s => s.sourceType))]
}

const getSourceTypeLabel = (type: string): string => {
  const labels: Record<string, string> = {
    'Post': '文章',
    'SinglePage': '页面',
    'Comment': '评论',
    'Reply': '回复',
    'Moment': '瞬间',
    'Photo': '图库',
    'Doc': '文档',
    'SystemSetting': '系统设置',
    'PluginSetting': '插件设置',
    'ThemeSetting': '主题设置',
    'User': '用户'
  }
  return labels[type] || type
}

const getSourceTypeIcon = (type: string): string => {
  const icons: Record<string, string> = {
    'Post': '📝',
    'SinglePage': '📄',
    'Comment': '💬',
    'Reply': '🗨️',
    'Moment': '📸',
    'Photo': '🖼️',
    'Doc': '📚',
    'SystemSetting': '⚙️',
    'PluginSetting': '🔌',
    'ThemeSetting': '🎨',
    'User': '👤'
  }
  return icons[type] || '📦'
}

const getSourceTypeClass = (type: string): string => {
  const classes: Record<string, string> = {
    'Post': 'tag-blue',
    'SinglePage': 'tag-blue',
    'Comment': 'tag-pink',
    'Reply': 'tag-pink',
    'Moment': 'tag-orange',
    'Photo': 'tag-orange',
    'Doc': 'tag-indigo',
    'SystemSetting': 'tag-purple',
    'PluginSetting': 'tag-purple',
    'ThemeSetting': 'tag-purple',
    'User': 'tag-amber'
  }
  return classes[type] || ''
}

// Setting 类型常量
const SETTING_TYPES = ['SystemSetting', 'PluginSetting', 'ThemeSetting']

// Setting group label 缓存
const settingGroupLabelCache = ref<Record<string, string>>({})

const getReferenceTypeLabel = (ref: { sourceType: string; settingName?: string | null; referenceType?: string | null }): string => {
  const referenceType = ref.referenceType
  if (!referenceType) return ''

  const labels: Record<string, string> = {
    'cover': '封面',
    'content': '内容',
    'media': '媒体',
    'comment': '评论',
    'reply': '回复',
    'avatar': '头像',
    'icon': '图标',
    'basic': '基本设置'
  }

  // 静态映射优先
  if (labels[referenceType]) {
    return labels[referenceType]
  }

  // Setting 类型，检查缓存或返回 referenceType
  if (SETTING_TYPES.includes(ref.sourceType) && ref.settingName && referenceType) {
    const cacheKey = `${ref.settingName}:${referenceType}`
    if (settingGroupLabelCache.value[cacheKey]) {
      return settingGroupLabelCache.value[cacheKey]
    }
    // TODO: 异步获取 Setting group label
    return referenceType
  }

  return referenceType
}

// 异步获取 Setting group label
const fetchSettingGroupLabel = async (settingName: string, groupKey: string): Promise<string> => {
  const cacheKey = `${settingName}:${groupKey}`
  if (settingGroupLabelCache.value[cacheKey]) {
    return settingGroupLabelCache.value[cacheKey]
  }

  try {
    const { data } = await axiosInstance.get(API_ENDPOINTS.REFERENCES_SETTING_GROUP_LABEL(settingName, groupKey))
    settingGroupLabelCache.value[cacheKey] = data.label
    return data.label
  } catch (e) {
    settingGroupLabelCache.value[cacheKey] = groupKey
    return groupKey
  }
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

/* 信息区域 */
.info-section {
  padding: 16px;
  border-bottom: 1px solid #f4f4f5;
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

/* 来源列表区域 */
.reference-section {
  padding: 16px;
  border-bottom: 1px solid #f4f4f5;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.section-title {
  font-size: 13px;
  font-weight: 500;
  color: #18181b;
}

.section-count {
  font-size: 12px;
  color: #a1a1aa;
}

.reference-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.reference-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: #fafafa;
  border-radius: 6px;
  text-decoration: none;
  transition: background 0.15s;
}

.reference-item:hover:not(.no-link) {
  background: #f4f4f5;
}

.reference-item.no-link {
  cursor: default;
}

.ref-icon {
  font-size: 16px;
  flex-shrink: 0;
  line-height: 1;
}

.ref-content {
  flex: 1;
  min-width: 0;
}

.ref-title {
  font-size: 13px;
  color: #18181b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.4;
  display: block;
}

.ref-tags {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
}

.ref-tag {
  font-size: 11px;
  padding: 1px 6px;
  background: #e4e4e7;
  color: #52525b;
  border-radius: 3px;
}

.ref-tag.deleted {
  background: #fee2e2;
  color: #dc2626;
}

.ref-arrow {
  font-size: 12px;
  color: #a1a1aa;
  flex-shrink: 0;
}

/* 操作区域 */
.action-section {
  padding: 16px;
  display: flex;
  justify-content: center;
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
</style>
