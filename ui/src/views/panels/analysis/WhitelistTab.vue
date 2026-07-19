<template>
  <div class="whitelist-tab">
    <!-- 操作栏 -->
    <div class="st-toolbar">
      <div class="st-toolbar-left">
        <VButton type="secondary" @click="showAddDialog">添加白名单</VButton>
        <VButton :loading="loading" @click="fetchWhitelist">刷新</VButton>
        <VButton type="danger" ghost :disabled="whitelist.length === 0" @click="clearAll">
          清空全部
        </VButton>
        <span class="list-info" v-if="whitelist.length > 0">
          共 {{ whitelist.length }} 条
        </span>
      </div>
      <div class="st-toolbar-right">
        <SearchInput v-model="searchKeyword" placeholder="搜索 URL 或备注..." @search="handleSearch" />
      </div>
    </div>

    <!-- 提示信息 -->
    <VAlert
      type="info"
      title="白名单说明"
      description="白名单用于忽略断链检测中的特定 URL，支持精确匹配和前缀匹配两种模式"
    />

    <!-- 白名单列表 -->
    <div class="whitelist-container st-card">
      <VLoading v-if="loading" />

      <VEmpty
        v-else-if="filteredWhitelist.length === 0"
        :title="whitelist.length === 0 ? '白名单为空' : '未找到匹配项'"
        :message="whitelist.length === 0 ? '添加白名单以忽略断链检测中的特定 URL' : '尝试调整搜索关键词'"
      >
        <template #actions>
          <VButton v-if="whitelist.length === 0" type="secondary" @click="showAddDialog">
            添加白名单
          </VButton>
        </template>
      </VEmpty>

      <template v-else>
        <div class="st-table-wrapper">
          <table class="st-table whitelist-table">
            <thead>
              <tr>
                <th class="col-url">URL</th>
                <th class="col-note">备注</th>
                <th class="col-pattern">匹配模式</th>
                <th class="col-time">添加时间</th>
                <th class="col-action">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in paginatedWhitelist" :key="item.name">
                <td class="cell-url">
                  <span class="url-text" :title="item.url">{{ truncateUrl(item.url) }}</span>
                </td>
                <td>{{ item.note || '-' }}</td>
                <td>
                  <StatusBadge :tone="item.matchMode === 'exact' ? 'success' : 'info'">
                    {{ item.matchMode === 'exact' ? '精确匹配' : '前缀匹配' }}
                  </StatusBadge>
                </td>
                <td class="col-time">{{ formatTime(item.createdAt) }}</td>
                <td class="col-action">
                  <VButton size="sm" type="danger" ghost @click="confirmDelete(item)">删除</VButton>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 分页 -->
        <VPagination v-if="filteredWhitelist.length > 0" v-model:page="page" v-model:size="pageSize" :total="filteredWhitelist.length" :size-options="PAGE_SIZE_OPTIONS" />
      </template>
    </div>

    <!-- 添加白名单弹窗 -->
    <WhitelistFormModal v-if="showAddModal" @close="showAddModal = false" @saved="handleSaved" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { Dialog, Toast, VAlert, VButton, VEmpty, VLoading, VPagination } from '@halo-dev/components'
import { PAGE_SIZE_OPTIONS, DEFAULT_PAGE_SIZE } from '@/constants/pagination'
import { API_ENDPOINTS } from '@/constants/api'
import StatusBadge from '@/components/StatusBadge.vue'
import SearchInput from '@/components/SearchInput.vue'
import WhitelistFormModal from '@/views/panels/components/WhitelistFormModal.vue'

interface WhitelistEntry {
  name: string
  url: string
  note: string | null
  createdAt: string
  matchMode: string
}

// 数据
const whitelist = ref<WhitelistEntry[]>([])
const loading = ref(false)
const page = ref(1)
const pageSize = ref(DEFAULT_PAGE_SIZE)
const searchKeyword = ref('')

// 弹窗相关
const showAddModal = ref(false)

// 搜索过滤后的列表
const filteredWhitelist = computed(() => {
  if (!searchKeyword.value.trim()) {
    return whitelist.value
  }
  const keyword = searchKeyword.value.toLowerCase()
  return whitelist.value.filter(item => {
    const urlMatch = item.url.toLowerCase().includes(keyword)
    const noteMatch = item.note?.toLowerCase().includes(keyword)
    return urlMatch || noteMatch
  })
})

// 分页数据
const paginatedWhitelist = computed(() => {
  const start = (page.value - 1) * pageSize.value
  const end = start + pageSize.value
  return filteredWhitelist.value.slice(start, end)
})

// 获取白名单列表
const fetchWhitelist = async () => {
  loading.value = true
  try {
    const { data } = await axiosInstance.get<WhitelistEntry[]>(API_ENDPOINTS.WHITELIST)
    whitelist.value = data || []
    // 按添加时间倒序排列
    whitelist.value.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
  } finally {
    loading.value = false
  }
}

// 搜索（SearchInput 已内置防抖，回调里回退到第一页）
const handleSearch = () => {
  page.value = 1
}

// 截断 URL
const truncateUrl = (url: string): string => {
  if (!url) return ''
  return url.length > 60 ? url.substring(0, 60) + '...' : url
}

// 格式化时间
const formatTime = (time: string | null): string => {
  if (!time) return ''
  return new Date(time).toLocaleString('zh-CN')
}

// 显示添加对话框
const showAddDialog = () => {
  showAddModal.value = true
}

// 添加成功后刷新列表并关闭弹窗（保持原有的提交后行为）
const handleSaved = async () => {
  await fetchWhitelist()
  showAddModal.value = false
}

// 确认删除
const confirmDelete = (item: WhitelistEntry) => {
  Dialog.warning({
    title: '确认删除',
    description: `确定要删除白名单项 "${item.url}" 吗？`,
    confirmText: '删除',
    confirmType: 'danger',
    async onConfirm() {
      try {
        await axiosInstance.delete(`${API_ENDPOINTS.WHITELIST}/${item.name}`)
        // 手动从列表中移除
        whitelist.value = whitelist.value.filter(i => i.name !== item.name)
        Toast.success('删除成功')
        return true
      } catch (error: unknown) {
        const err = error as { response?: { data?: { message?: string } }; message?: string }
        Toast.error('删除失败: ' + (err.response?.data?.message || err.message || '未知错误'))
        return false
      }
    }
  })
}

// 清空全部
const clearAll = () => {
  Dialog.warning({
    title: '确认清空',
    description: `确定要清空所有白名单吗？此操作不可恢复，共 ${whitelist.value.length} 条记录将被删除。`,
    confirmText: '清空',
    confirmType: 'danger',
    async onConfirm() {
      try {
        await axiosInstance.delete(API_ENDPOINTS.WHITELIST_CLEAR_ALL)
        // 手动清空列表
        whitelist.value = []
        Toast.success('清空成功')
        return true
      } catch (error: unknown) {
        const err = error as { response?: { data?: { message?: string } }; message?: string }
        Toast.error('清空失败: ' + (err.response?.data?.message || err.message || '未知错误'))
        return false
      }
    }
  })
}

// 初始化
onMounted(() => {
  fetchWhitelist()
})
</script>

<style scoped>
.whitelist-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.list-info {
  font-size: 13px;
  color: var(--st-text-secondary);
}

/* 列表容器 */
.whitelist-container {
  overflow: hidden;
  padding-bottom: 4px;
}

/* 表格：列宽等本地细节（基础样式来自 .st-table） */
.whitelist-table {
  table-layout: fixed;
  min-width: 700px;
}

.whitelist-table th,
.whitelist-table td {
  white-space: nowrap;
}

.col-url { width: 36%; }
.col-note { width: 16%; }
.col-pattern { width: 12%; }
.col-time { width: 24%; color: var(--st-text-tertiary); font-size: 13px; }
.col-action { width: 80px; text-align: center; }

.cell-url {
  overflow: hidden;
}

.url-text {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--st-error-text);
  font-family: ui-monospace, monospace;
  font-size: 13px;
}

/* 响应式 */
@media (max-width: 768px) {
  .st-toolbar-left,
  .st-toolbar-right {
    flex-wrap: wrap;
    width: 100%;
  }
}
</style>
