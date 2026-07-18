<template>
  <VModal
    :visible="true"
    title="断链详情"
    :width="640"
    mount-to-body
    :layer-closable="true"
    @close="$emit('close')"
  >
    <!-- teleport 到 body 后需带 st-scope，否则 CSS 变量无法解析 -->
    <div class="st-scope detail-body">
      <!-- 断链信息 -->
      <div class="st-field-list">
        <div class="st-field-item">
          <span class="st-field-label">断链 URL</span>
          <span class="st-field-value field-url">{{ link?.originalUrl || link?.url }}</span>
        </div>
        <div class="st-field-item">
          <span class="st-field-label">断链原因</span>
          <span class="st-field-value">
            <StatusBadge :tone="getReasonTone(link?.reason)">
              {{ getReasonLabel(link?.reason) }}
            </StatusBadge>
          </span>
        </div>
        <div class="st-field-item">
          <span class="st-field-label">发现时间</span>
          <span class="st-field-value">{{ formatTime(link?.discoveredAt || '') }}</span>
        </div>
        <div class="st-field-item">
          <span class="st-field-label">出现次数</span>
          <span class="st-field-value">{{ link?.sourceCount }} 处</span>
        </div>
      </div>

      <!-- 来源列表 -->
      <ReferenceList :references="link?.sources" title="来源位置" />

      <!-- 替换区域 -->
      <div class="replace-section">
        <div class="replace-header" @click="toggleReplaceForm">
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
    </div>

    <template #footer>
      <VButton @click="ignoreSingle">添加到忽略白名单</VButton>
      <VButton type="secondary" :loading="replacing" @click="handleReplaceClick">
        替换 URL
      </VButton>
    </template>
  </VModal>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { Dialog, Toast, VModal, VButton } from '@halo-dev/components'
import { API_ENDPOINTS } from '@/constants/api'
import { formatTime } from '@/utils/format'
import StatusBadge from '@/components/StatusBadge.vue'
import ReferenceList from '@/components/ReferenceList.vue'
import type { BadgeTone } from '@/types/ui'

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

const props = defineProps<{
  link: BrokenLinkVo | null
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'whitelisted', url: string): void
  (e: 'replaced', payload: { url: string; refetch: boolean }): void
}>()

// 替换相关状态
const showReplaceForm = ref(false)
const replaceNewUrl = ref('')
const replacing = ref(false)
const replaceInputError = ref(false)
const replaceInputRef = ref<HTMLInputElement | null>(null)

const toggleReplaceForm = () => {
  showReplaceForm.value = !showReplaceForm.value
  replaceInputError.value = false
}

// 断链原因标签（就近映射保留本地）
const getReasonLabel = (reason: string | undefined): string => {
  if (!reason) return '未知原因'
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

// 单个忽略（提交逻辑原样保留，成功后 emit whitelisted 交由父组件移除列表项并关闭）
const ignoreSingle = () => {
  if (!props.link) return
  const whitelistUrl = props.link.originalUrl || props.link.url
  const currentUrl = props.link.url

  Dialog.warning({
    title: '确认忽略',
    description: `确定要将此 URL 添加到忽略白名单吗？此 URL 将不再被标记为断链。`,
    confirmText: '确定忽略',
    cancelText: '取消',
    async onConfirm() {
      try {
        await axiosInstance.post(API_ENDPOINTS.WHITELIST_BATCH, { urls: [whitelistUrl] })
        Toast.success('已添加到忽略白名单')
        emit('whitelisted', currentUrl)
        return true
      } catch (error: unknown) {
        const err = error as { response?: { data?: { message?: string } }; message?: string }
        Toast.error('操作失败: ' + (err.response?.data?.message || err.message || '未知错误'))
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

// 执行断链替换（成功后 emit replaced，父组件保持原有移除/刷新逻辑）
const executeReplace = async () => {
  if (!props.link || !replaceNewUrl.value) return

  const oldUrl = props.link.url
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
      emit('replaced', { url: oldUrl, refetch: false })
    } else if (data.successCount > 0) {
      Toast.warning(`部分替换成功：${data.successCount} 成功，${data.failedCount} 失败`)
      emit('replaced', { url: oldUrl, refetch: true })
    } else {
      const failMsg = data.failures?.map((f: { sourceTitle?: string; sourceName?: string; errorMessage?: string }) => `${f.sourceTitle || f.sourceName}: ${f.errorMessage}`).join('; ') || '未知错误'
      Toast.error(`替换失败：${failMsg}`)
    }
  } catch (error: unknown) {
    const err = error as { response?: { data?: { message?: string } }; message?: string }
    Toast.error('替换操作失败: ' + (err.response?.data?.message || err.message || '未知错误'))
  } finally {
    replacing.value = false
  }
}
</script>

<style scoped>
.detail-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.field-url {
  font-family: monospace;
  font-size: 12px;
  color: var(--st-text-secondary);
}

/* 来源列表区域 - 布局覆盖（随 ReferenceList 一起搬入并 token 化） */
.detail-body :deep(.reference-section) {
  max-height: 320px;
  overflow-y: auto;
  padding: 0;
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}

.detail-body :deep(.reference-section:hover) {
  scrollbar-color: var(--st-border) transparent;
}

.detail-body :deep(.reference-section::-webkit-scrollbar) {
  width: 6px;
}

.detail-body :deep(.reference-section::-webkit-scrollbar-track) {
  background: transparent;
}

.detail-body :deep(.reference-section::-webkit-scrollbar-thumb) {
  background: transparent;
  border-radius: 3px;
}

.detail-body :deep(.reference-section:hover::-webkit-scrollbar-thumb) {
  background: var(--st-border);
}

/* 替换区域 */
.replace-section {
  border-top: 1px solid var(--st-border-subtle);
}

.replace-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 0 0;
  cursor: pointer;
  user-select: none;
}

.replace-toggle {
  font-size: 10px;
  color: var(--st-text-tertiary);
}

.replace-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--st-text-primary);
}

.replace-form {
  padding-top: 12px;
}

.replace-input {
  width: 100%;
  height: 38px;
  padding: 0 12px;
  font-size: 14px;
  color: var(--st-text-primary);
  background: var(--st-bg-surface);
  border: 1px solid var(--st-border);
  border-radius: var(--st-radius-md);
  outline: none;
  transition: var(--st-transition);
}

.replace-input:focus {
  border-color: var(--st-focus-border);
  box-shadow: var(--st-focus-ring);
}

.replace-input-error {
  border-color: var(--st-error-text);
  background-color: var(--st-error-bg);
}

.replace-input-error:focus {
  border-color: var(--st-error-text);
  box-shadow: none;
}
</style>
