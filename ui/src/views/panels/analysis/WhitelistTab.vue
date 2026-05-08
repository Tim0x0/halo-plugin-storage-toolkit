<template>
  <div class="whitelist-tab">
    <!-- 操作栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <button class="btn-add" @click="showAddDialog">
          添加白名单
        </button>
        <button
          class="btn-refresh"
          @click="fetchWhitelist"
          :disabled="loading"
        >
          刷新
        </button>
        <button
          class="btn-clear"
          @click="clearAll"
          :disabled="whitelist.length === 0"
        >
          清空全部
        </button>
        <span class="list-info" v-if="whitelist.length > 0">
          共 {{ whitelist.length }} 条
        </span>
      </div>
      <div class="toolbar-right">
        <input
          type="text"
          v-model="searchKeyword"
          placeholder="搜索 URL 或备注..."
          class="search-input"
          @input="handleSearchDebounced"
        />
      </div>
    </div>

    <!-- 提示信息 -->
    <div class="notice info">
      <span class="notice-icon">💡</span>
      <span>白名单用于忽略断链检测中的特定 URL，支持精确匹配和前缀匹配两种模式</span>
    </div>

    <!-- 白名单列表 -->
    <div class="card">
      <div v-if="loading" class="loading-state">加载中...</div>
      <div v-else-if="filteredWhitelist.length === 0" class="empty-state">
        <span class="empty-icon">{{ whitelist.length === 0 ? '📋' : '🔍' }}</span>
        <span class="empty-text">{{ whitelist.length === 0 ? '白名单为空' : '未找到匹配项' }}</span>
        <button v-if="whitelist.length === 0" class="btn-add" @click="showAddDialog">
          添加白名单
        </button>
      </div>
      <template v-else>
        <div class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th class="col-url">URL</th>
              <th class="col-note">备注</th>
              <th class="col-pattern">匹配模式</th>
              <th class="col-time">添加时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in paginatedWhitelist" :key="item.name">
              <td class="cell-url">
                <span class="url-text" :title="item.url">{{ truncateUrl(item.url) }}</span>
              </td>
              <td>{{ item.note || '-' }}</td>
              <td>
                <span v-if="item.matchMode === 'exact'" class="tag tag-exact">精确匹配</span>
                <span v-else class="tag tag-prefix">前缀匹配</span>
              </td>
              <td>{{ formatTime(item.createdAt) }}</td>
              <td>
                <button class="btn-delete" @click="confirmDelete(item)">
                  删除
                </button>
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
    <div class="modal-overlay" v-if="showModal" @click.self="closeModal">
      <div class="modal-content">
        <div class="modal-header">
          <h3>添加白名单</h3>
          <button class="modal-close" @click="closeModal">×</button>
        </div>
        <div class="modal-body">
          <div class="form-group">
            <label class="form-label">
              URL <span class="required">*</span>
            </label>
            <input
              type="text"
              v-model="formData.url"
              class="form-input"
              placeholder="请输入需要忽略的 URL"
            />
          </div>
          <div class="form-group">
            <label class="form-label">
              匹配模式 <span class="required">*</span>
            </label>
            <div class="radio-group">
              <label class="radio-item">
                <input type="radio" v-model="formData.matchMode" value="exact" />
                <span class="radio-label">
                  <span class="radio-title">精确匹配</span>
                  <span class="radio-desc">URL 必须完全相同</span>
                </span>
              </label>
              <label class="radio-item">
                <input type="radio" v-model="formData.matchMode" value="prefix" />
                <span class="radio-label">
                  <span class="radio-title">前缀匹配</span>
                  <span class="radio-desc">匹配以该 URL 开头的所有链接</span>
                </span>
              </label>
            </div>
          </div>
          <div class="form-group">
            <label class="form-label">备注</label>
            <textarea
              v-model="formData.note"
              class="form-textarea"
              placeholder="添加备注信息（可选）"
              rows="2"
            ></textarea>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-modal btn-modal-cancel" @click="closeModal">取消</button>
          <button class="btn-modal btn-modal-confirm" @click="submitForm" :disabled="submitting">
            {{ submitting ? '添加中...' : '确认添加' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { Dialog, Toast, VPagination } from '@halo-dev/components'
import { PAGE_SIZE_OPTIONS, DEFAULT_PAGE_SIZE } from '@/constants/pagination'
import { API_ENDPOINTS } from '@/constants/api'

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
const searchDebounceTimer = ref<number>()

// 弹窗相关
const showModal = ref(false)
const submitting = ref(false)
const formData = ref({
  url: '',
  matchMode: 'exact' as 'exact' | 'prefix',
  note: ''
})

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
const totalPages = computed(() => Math.ceil(filteredWhitelist.value.length / pageSize.value))

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

// 防抖搜索
const handleSearchDebounced = () => {
  clearTimeout(searchDebounceTimer.value)
  searchDebounceTimer.value = window.setTimeout(() => {
    page.value = 1
  }, 300)
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
  formData.value = {
    url: '',
    matchMode: 'exact',
    note: ''
  }
  showModal.value = true
}

// 关闭弹窗
const closeModal = () => {
  showModal.value = false
}

// 提交表单
const submitForm = async () => {
  // 验证
  if (!formData.value.url.trim()) {
    Toast.error('请输入 URL')
    return
  }

  submitting.value = true
  try {
    await axiosInstance.post(API_ENDPOINTS.WHITELIST, {
      url: formData.value.url.trim(),
      matchMode: formData.value.matchMode,
      note: formData.value.note.trim() || null
    })
    await fetchWhitelist()
    closeModal()
  } finally {
    submitting.value = false
  }
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

.btn-add {
  padding: 8px 16px;
  font-size: 14px;
  background: #18181b;
  color: white;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-add:hover {
  background: #27272a;
}

.btn-refresh {
  padding: 8px 16px;
  font-size: 14px;
  background: white;
  color: #374151;
  border: 1px solid #e4e4e7;
  border-radius: 6px;
  cursor: pointer;
}

.btn-refresh:hover:not(:disabled) {
  background: #f9fafb;
}

.btn-refresh:disabled {
  opacity: 0.5;
  cursor: not-allowed;
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
  padding: 4px 10px;
  font-size: 13px;
  background: #dc2626;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  transition: background 0.15s;
}

.btn-delete:hover {
  background: #b91c1c;
}

.list-info {
  font-size: 14px;
  color: #71717a;
}

.search-input {
  padding: 8px 12px;
  font-size: 14px;
  border: 1px solid #e4e4e7;
  border-radius: 6px;
  width: 200px;
  outline: none;
  transition: border-color 0.15s;
}

.search-input:focus {
  border-color: #18181b;
}

.notice {
  padding: 12px 16px;
  border-radius: 8px;
  display: flex;
  gap: 12px;
  align-items: flex-start;
  font-size: 14px;
}

.notice.info {
  background: #eff6ff;
  color: #1e40af;
}

.notice-icon {
  font-size: 16px;
  flex-shrink: 0;
}

.card {
  background: white;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
  overflow: hidden;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  min-width: 700px;
}

.table-wrapper {
  overflow-x: auto;
}

.data-table th,
.data-table td {
  padding: 12px 16px;
  text-align: left;
  border-bottom: 1px solid #f0f0f0;
  white-space: nowrap;
}

.data-table th {
  background: #fafafa;
  font-weight: 600;
  color: #18181b;
  font-size: 14px;
  white-space: nowrap;
}

.data-table td {
  font-size: 14px;
}

.data-table tr:hover {
  background: #f9fafb;
}

/* 列宽 */
.col-url { width: 36%; }
.col-note { width: 16%; }
.col-pattern { width: 12%; }
.col-time { width: 24%; }

/* 操作列 */
.data-table th:last-child,
.data-table td:last-child {
  width: 60px;
  text-align: center;
}

.cell-url {
  overflow: hidden;
}

.url-text {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #ef4444;
  font-family: ui-monospace, monospace;
  font-size: 13px;
}

.tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}

.tag-exact {
  background: #dcfce7;
  color: #166534;
}

.tag-prefix {
  background: #dbeafe;
  color: #1e40af;
}

.pagination {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  border-top: 1px solid #f0f0f0;
}

.page-info {
  font-size: 14px;
  color: #71717a;
}

.page-controls {
  display: flex;
  align-items: center;
  gap: 12px;
}

.page-btn {
  padding: 6px 12px;
  font-size: 14px;
  background: white;
  border: 1px solid #e4e4e7;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.15s;
}

.page-btn:hover:not(:disabled) {
  background: #f4f4f5;
}

.page-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.page-num {
  font-size: 14px;
  color: #71717a;
  min-width: 60px;
  text-align: center;
}

.page-size {
  padding: 6px 8px;
  font-size: 14px;
  border: 1px solid #e4e4e7;
  border-radius: 4px;
  background: white;
  cursor: pointer;
}

.loading-state, .empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  gap: 16px;
  color: #71717a;
}

.empty-icon {
  font-size: 64px;
  opacity: 0.5;
}

.empty-text {
  font-size: 16px;
}

/* 弹窗样式 - 参考 BrokenLinkTab */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 20px;
}

.modal-content {
  background: white;
  border-radius: 8px;
  width: 100%;
  max-width: 500px;
  max-height: 90vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1);
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  border-bottom: 1px solid #e4e4e7;
}

.modal-header h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #18181b;
}

.modal-close {
  background: none;
  border: none;
  font-size: 28px;
  color: #71717a;
  cursor: pointer;
  padding: 0;
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  transition: all 0.15s;
  line-height: 1;
}

.modal-close:hover {
  background: #f4f4f5;
  color: #18181b;
}

.modal-body {
  padding: 24px;
  overflow-y: auto;
  flex: 1;
}

.modal-body::-webkit-scrollbar {
  width: 6px;
}

.modal-body::-webkit-scrollbar-track {
  background: #f1f1f1;
  border-radius: 3px;
}

.modal-body::-webkit-scrollbar-thumb {
  background: #c1c1c1;
  border-radius: 3px;
}

.modal-body:hover::-webkit-scrollbar-thumb {
  background: #a8a8a8;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 24px;
  border-top: 1px solid #e4e4e7;
  background: #fafafa;
}

.form-group {
  margin-bottom: 20px;
}

.form-group:last-child {
  margin-bottom: 0;
}

.form-label {
  display: block;
  font-size: 14px;
  font-weight: 500;
  color: #18181b;
  margin-bottom: 8px;
}

.required {
  color: #ef4444;
}

.form-input, .form-textarea {
  width: 100%;
  padding: 10px 12px;
  font-size: 14px;
  border: 1px solid #e4e4e7;
  border-radius: 6px;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
  font-family: inherit;
}

.form-input:focus, .form-textarea:focus {
  border-color: #18181b;
  box-shadow: 0 0 0 3px rgba(24, 24, 27, 0.1);
}

.form-input::placeholder, .form-textarea::placeholder {
  color: #a1a1aa;
}

.form-textarea {
  resize: vertical;
  min-height: 60px;
}

.radio-group {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.radio-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px;
  border: 1px solid #e4e4e7;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
}

.radio-item:hover {
  background: #fafafa;
}

.radio-item input[type="radio"] {
  margin-top: 2px;
  width: 16px;
  height: 16px;
  cursor: pointer;
}

.radio-label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
}

.radio-title {
  font-size: 14px;
  font-weight: 500;
  color: #18181b;
}

.radio-desc {
  font-size: 13px;
  color: #71717a;
}

.btn-modal {
  padding: 10px 20px;
  font-size: 14px;
  font-weight: 500;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-modal-cancel {
  background: white;
  color: #52525b;
  border: 1px solid #e4e4e7;
}

.btn-modal-cancel:hover {
  background: #f4f4f5;
}

.btn-modal-confirm {
  background: #18181b;
  color: white;
}

.btn-modal-confirm:hover:not(:disabled) {
  background: #27272a;
}

.btn-modal-confirm:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 响应式 */
@media (max-width: 768px) {
  .toolbar-left, .toolbar-right {
    flex-wrap: wrap;
    width: 100%;
  }

  .search-input {
    width: 100%;
  }
}

@media (max-width: 640px) {
  .pagination {
    flex-wrap: wrap;
    justify-content: center;
    gap: 8px;
  }

  .page-info {
    width: 100%;
    text-align: center;
  }

  .modal-content {
    max-width: 95%;
  }
}
</style>
