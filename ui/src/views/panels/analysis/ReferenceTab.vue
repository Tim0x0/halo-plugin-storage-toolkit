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
        <span class="scan-info" v-if="stats.lastScanTime">上次扫描：{{ formatTime(stats.lastScanTime) }}</span>
        <span class="scan-info" v-else-if="stats.phase === 'scanning'">正在扫描...</span>
        <span class="scan-info error" v-else-if="stats.phase === 'error'">扫描失败：{{ stats.errorMessage }}</span>
      </div>
      <div class="toolbar-right">
        <select v-model="filterType" class="filter-select" @change="handleFilterChange">
          <option value="all">全部</option>
          <option value="referenced">已引用</option>
          <option value="unreferenced">未引用</option>
        </select>
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
    <div class="stats-row" v-if="stats.lastScanTime || stats.phase === 'scanning'">
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
    <div class="stats-row stats-placeholder" v-else>
      <div class="stat-box">
        <span class="stat-num">-</span>
        <span class="stat-text">引用率</span>
      </div>
      <div class="stat-box">
        <span class="stat-num">-</span>
        <span class="stat-text">已引用</span>
      </div>
      <div class="stat-box">
        <span class="stat-num">-</span>
        <span class="stat-text">未引用</span>
      </div>
      <div class="stat-box">
        <span class="stat-num">-</span>
        <span class="stat-text">未引用占用</span>
      </div>
    </div>

    <!-- 附件列表 -->
    <div class="card">
      <div v-if="loading" class="loading-state">加载中...</div>
      <div v-else-if="!stats.lastScanTime && stats.phase !== 'scanning'" class="empty-state">
        请先点击「开始扫描」按钮进行扫描
      </div>
      <div v-else-if="attachmentList.length === 0" class="empty-state">
        没有符合条件的附件
      </div>
      <template v-else>
        <table class="data-table">
          <thead>
            <tr>
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
            <tr v-for="item in attachmentList" :key="item.attachmentName" :class="{ highlighted: highlightedAttachment === item.attachmentName }">
              <td class="cell-name">
                <img 
                  v-if="item.mediaType?.startsWith('image/') && item.permalink" 
                  :src="item.permalink" 
                  class="file-thumbnail"
                  @error="(e: Event) => (e.target as HTMLImageElement).style.display = 'none'"
                />
                <span v-else class="file-icon">{{ getFileIcon(item.mediaType) }}</span>
                {{ item.displayName }}
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
            <option :value="20">20条/页</option>
            <option :value="50">50条/页</option>
            <option :value="100">100条/页</option>
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
          <div class="reference-section" v-if="selectedAttachment?.references?.length">
            <div class="section-header">
              <span class="section-title">引用位置</span>
              <span class="section-count">{{ selectedAttachment.references.length }} 处</span>
            </div>
            <div class="reference-list">
              <a 
                class="reference-item" 
                v-for="ref in selectedAttachment?.references" 
                :key="ref.sourceName + ref.referenceType"
                :href="ref.sourceUrl || 'javascript:void(0)'"
                :target="ref.sourceUrl ? '_blank' : undefined"
                :class="{ 'no-link': !ref.sourceUrl }"
              >
                <span class="ref-icon">{{ getSourceTypeIcon(ref.sourceType) }}</span>
                <div class="ref-content">
                  <span class="ref-title">{{ getRefDisplayTitle(ref) }}</span>
                  <div class="ref-tags">
                    <span class="ref-tag">{{ getReferenceTypeLabel(ref) }}</span>
                    <span class="ref-tag deleted" v-if="ref.deleted">回收站</span>
                  </div>
                </div>
                <span class="ref-arrow" v-if="ref.sourceUrl">→</span>
              </a>
            </div>
          </div>
          <div class="empty-references" v-else>
            <span>暂无引用记录</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>


<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { axiosInstance } from '@halo-dev/api-client'
import { Dialog, Toast } from '@halo-dev/components'

interface ReferenceSource {
  sourceType: string
  sourceName: string
  sourceTitle: string
  sourceUrl: string | null
  deleted: boolean
  referenceType: string | null
  settingName: string | null
}

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
  phase: string | null
  lastScanTime: string | null
  totalAttachments: number
  referencedCount: number
  unreferencedCount: number
  unreferencedSize: number
  errorMessage: string | null
}

const API_BASE = '/apis/console.api.storage-toolkit.timxs.com/v1alpha1/references'

// Setting 类型常量
const SETTING_TYPES = ['SystemSetting', 'PluginSetting', 'ThemeSetting']

const route = useRoute()

const loading = ref(false)
const scanning = ref(false)
const filterType = ref('all')
const searchQuery = ref('')
const page = ref(1)
const pageSize = ref(20)
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

// Setting group label 缓存
const settingGroupLabelCache = ref<Record<string, string>>({})

const referenceRate = computed(() => {
  const total = stats.value?.totalAttachments ?? 0
  const referenced = stats.value?.referencedCount ?? 0
  if (total === 0) return '0.00'
  return ((referenced / total) * 100).toFixed(2)
})

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

let searchDebounceTimer: ReturnType<typeof setTimeout> | null = null

const handleSearchDebounced = () => {
  if (searchDebounceTimer) clearTimeout(searchDebounceTimer)
  searchDebounceTimer = setTimeout(() => {
    page.value = 1
    fetchReferences()
  }, 300)
}

const handleFilterChange = () => {
  page.value = 1
  fetchReferences()
}

const handlePageSizeChange = () => {
  page.value = 1
  fetchReferences()
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
    const { data } = await axiosInstance.get<StatsResponse>(`${API_BASE}/stats`)
    stats.value = data
    scanning.value = data.phase === 'scanning'
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

    const { data } = await axiosInstance.get(`${API_BASE}?${params.toString()}`)
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
    await axiosInstance.post(`${API_BASE}/scan`)
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
        await axiosInstance.delete(`${API_BASE}/clear`)
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
    await fetchStats()
    if (stats.value.phase === 'scanning') {
      setTimeout(poll, 2000)
    } else {
      scanning.value = false
      fetchReferences()
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
      const { data } = await axiosInstance.get(`${API_BASE}/policy/${item.policyName}`)
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
      const { data } = await axiosInstance.get(`${API_BASE}/group/${item.groupName}`)
      groupDisplayName.value = data.displayName
    } catch (e) {
      groupDisplayName.value = item.groupName
    }
  } else {
    groupDisplayName.value = '未分组'
  }

  // 异步解析评论/回复的关联标题
  for (const ref of item.references) {
    if ((ref.sourceType === 'Comment' || ref.sourceType === 'Reply') && ref.sourceTitle && !ref.sourceUrl) {
      // sourceTitle 格式: "Kind:name"
      const colonIndex = ref.sourceTitle.indexOf(':')
      if (colonIndex > 0) {
        const kind = ref.sourceTitle.substring(0, colonIndex)
        const name = ref.sourceTitle.substring(colonIndex + 1)
        try {
          const { data } = await axiosInstance.get(`${API_BASE}/subject/${kind}/${name}`)
          if (data.title || data.url) {
            // 更新本地显示
            ref.sourceTitle = data.title || ref.sourceTitle
            ref.sourceUrl = data.url
            // 更新后端缓存
            await axiosInstance.put(
              `${API_BASE}/${item.attachmentName}/source/${ref.sourceName}`,
              null,
              { params: { sourceTitle: data.title, sourceUrl: data.url } }
            )
          }
        } catch (e) {
          console.debug('解析引用源失败:', e)
        }
      }
    }
    // 异步解析文档的标题和链接
    if (ref.sourceType === 'Doc' && ref.sourceTitle && !ref.sourceUrl) {
      // sourceTitle 格式: "Doc:doc-name"
      const match = ref.sourceTitle.match(/^Doc:(.+)$/)
      if (match) {
        const [, docName] = match
        try {
          const { data } = await axiosInstance.get(`${API_BASE}/subject/Doc/${docName}`)
          if (data.title || data.url) {
            // 更新本地显示
            ref.sourceTitle = data.title || ref.sourceTitle
            ref.sourceUrl = data.url
            // 更新后端缓存
            await axiosInstance.put(
              `${API_BASE}/${item.attachmentName}/source/${ref.sourceName}`,
              null,
              { params: { sourceTitle: data.title, sourceUrl: data.url } }
            )
          }
        } catch (e) {
          console.debug('解析文档引用源失败:', e)
        }
      }
    }
    
    // 异步获取 Setting 引用的 group label
    if (SETTING_TYPES.includes(ref.sourceType) && ref.settingName && ref.referenceType) {
      await fetchSettingGroupLabel(ref.settingName, ref.referenceType)
    }
  }
}

const getFileIcon = (type: string): string => {
  if (!type) return '📦'
  if (type.startsWith('image/')) return '🖼️'
  if (type.startsWith('video/')) return '🎬'
  if (type.includes('pdf')) return '📄'
  return '📦'
}

const getSourceTypeLabel = (type: string): string => {
  const labels: Record<string, string> = {
    'Post': '文章',
    'SinglePage': '页面',
    'Comment': '评论',
    'Reply': '回复',
    'SystemSetting': '系统设置',
    'PluginSetting': '插件设置',
    'ThemeSetting': '主题设置',
    'Moment': '瞬间',
    'Photo': '图库',
    'Doc': '文档',
    'User': '用户'
  }
  return labels[type] || type
}

const getUniqueSourceTypes = (references: ReferenceSource[]): string[] => {
  return [...new Set(references.map(ref => ref.sourceType))]
}

const getSourceTypeIcon = (type: string): string => {
  const icons: Record<string, string> = {
    'Post': '📝',
    'SinglePage': '📄',
    'Comment': '💬',
    'Reply': '🗨️',
    'SystemSetting': '⚙️',
    'PluginSetting': '🔌',
    'ThemeSetting': '🎨',
    'Moment': '📸',
    'Photo': '🖼️',
    'Doc': '📚',
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
    'SystemSetting': 'tag-purple',
    'PluginSetting': 'tag-purple',
    'ThemeSetting': 'tag-purple',
    'Moment': 'tag-orange',
    'Photo': 'tag-orange',
    'Doc': 'tag-indigo',
    'User': 'tag-amber'
  }
  return classes[type] || ''
}

const getReferenceTypeLabel = (ref: ReferenceSource): string => {
  const labels: Record<string, string> = {
    'cover': '封面',
    'content': '内容',
    'media': '媒体',
    'comment': '评论',
    'reply': '回复',
    'avatar': '头像',
    'icon': '图标'
  }
  
  // 静态映射优先
  if (labels[ref.referenceType || '']) {
    return labels[ref.referenceType || '']
  }
  
  // Setting 类型，检查缓存或返回 referenceType
  if (SETTING_TYPES.includes(ref.sourceType) && ref.settingName && ref.referenceType) {
    const cacheKey = `${ref.settingName}:${ref.referenceType}`
    if (settingGroupLabelCache.value[cacheKey]) {
      return settingGroupLabelCache.value[cacheKey]
    }
    // 异步获取（在 showReferenceDetail 中处理）
    return ref.referenceType
  }
  
  return ref.referenceType || ''
}

// 异步获取 Setting group label
const fetchSettingGroupLabel = async (settingName: string, groupKey: string): Promise<string> => {
  const cacheKey = `${settingName}:${groupKey}`
  if (settingGroupLabelCache.value[cacheKey]) {
    return settingGroupLabelCache.value[cacheKey]
  }
  
  try {
    const { data } = await axiosInstance.get(`${API_BASE}/settings/${settingName}/groups/${groupKey}/label`)
    settingGroupLabelCache.value[cacheKey] = data.label
    return data.label
  } catch (e) {
    settingGroupLabelCache.value[cacheKey] = groupKey
    return groupKey
  }
}

const getRefDisplayTitle = (ref: ReferenceSource): string => {
  if (ref.sourceType === 'Comment' || ref.sourceType === 'Reply' || ref.sourceType === 'Doc') {
    if (ref.sourceUrl) {
      return ref.sourceTitle
    }
    return '加载中...'
  }
  return ref.sourceTitle || ref.sourceType
}

const formatBytes = (bytes: number): string => {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0, size = bytes
  while (size >= 1024 && i < 3) { size /= 1024; i++ }
  return `${size.toFixed(1)} ${units[i]}`
}

const formatTime = (isoString: string): string => {
  if (!isoString) return ''
  const date = new Date(isoString)
  return date.toLocaleString('zh-CN')
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
  if (stats.value.phase === 'scanning') {
    scanning.value = true
    pollScanStatus()
  } else if (stats.value.lastScanTime) {
    await fetchReferences()
  }
  handleUrlParams()
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

.scan-info {
  font-size: 13px;
  color: #71717a;
}

.scan-info.error {
  color: #dc2626;
}

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

.data-table tbody tr.highlighted:hover {
  background: #fef3c7;
}

.cell-name {
  display: flex;
  align-items: center;
  gap: 8px;
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

/* 引用列表区域 */
.reference-section {
  padding: 16px;
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

.empty-references {
  padding: 32px 16px;
  text-align: center;
  color: #a1a1aa;
  font-size: 13px;
}

.file-thumbnail {
  width: 24px;
  height: 24px;
  object-fit: cover;
  border-radius: 4px;
  flex-shrink: 0;
}
</style>
