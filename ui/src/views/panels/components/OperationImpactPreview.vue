<template>
  <section class="impact-preview" aria-live="polite">
    <VLoading v-if="loading" />
    <VAlert v-else-if="error" type="error" title="影响预览失败" :description="error" />
    <template v-else-if="preview">
      <p>
        <strong>执行 {{ preview.eligibleCount }} 个 · 跳过 {{ preview.skippedCount }} 个</strong>
        <span v-if="operation !== 'BATCH'">
          · 预计释放 {{ formatBytes(preview.estimatedFreedBytes) }}</span
        >
      </p>
      <p class="preview-hint">
        已读取当前引用；执行时会再次核验。{{
          operation === 'BATCH' ? '实际处理结果以图片内容为准。' : '跳过项将保留。'
        }}
      </p>
      <div class="impact-list">
        <details v-for="item in preview.items" :key="item.attachmentName" class="impact-item">
          <summary>
            {{ item.action }} · {{ item.displayName }} · {{ formatBytes(item.size) }}
          </summary>
          <p>{{ item.note }}</p>
          <p v-if="item.keptAttachmentName">保留附件：{{ item.keptDisplayName || item.keptAttachmentName }}</p>
          <ul v-if="item.references.length">
            <li v-for="(source, index) in item.references" :key="index">
              {{ source.sourceTitle || source.sourceName }} · {{ source.sourceType }} ·
              {{ source.referenceType }}
              {{ item.modifiedSources.length ? '（将更新链接）' : '（保留现有链接）' }}
            </li>
          </ul>
        </details>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { VAlert, VLoading } from '@halo-dev/components'
import { API_ENDPOINTS } from '@/constants/api'
import { formatBytes } from '@/utils/format'
interface Source {
  sourceTitle: string
  sourceName: string
  sourceType: string
  referenceType: string
}
interface Preview {
  eligibleCount: number
  skippedCount: number
  estimatedFreedBytes: number
  items: {
    attachmentName: string
    displayName: string
    size: number
    action: string
    note: string
    eligible: boolean
    keptAttachmentName: string | null
    keptDisplayName: string | null
    references: Source[]
    modifiedSources: Source[]
  }[]
}
const props = defineProps<{
  operation: 'BATCH' | 'UNREFERENCED' | 'DUPLICATE'
  attachmentNames: string[]
  replaceReferences?: boolean
  groups?: Record<string, string[]>
}>()
const emit = defineEmits<{ ready: [eligibleNames: string[]] }>()
const loading = ref(true),
  error = ref(''),
  preview = ref<Preview | null>(null)
let controller: AbortController | null = null
watch(
  () =>
    JSON.stringify([props.operation, props.attachmentNames, props.replaceReferences, props.groups]),
  async () => {
    controller?.abort()
    const request = new AbortController()
    controller = request
    loading.value = true
    error.value = ''
    emit('ready', [])
    try {
      const { data } = await axiosInstance.post<Preview>(
        API_ENDPOINTS.OPERATION_PREVIEW,
        {
          operation: props.operation,
          attachmentNames: props.attachmentNames,
          replaceReferences: props.replaceReferences ?? false,
          groups: props.groups,
        },
        { signal: request.signal },
      )
      if (request.signal.aborted) return
      preview.value = data
      emit(
        'ready',
        data.items.filter((item) => item.eligible).map((item) => item.attachmentName),
      )
    } catch (cause) {
      if (request.signal.aborted) return
      const e = cause as { response?: { data?: { message?: string } } }
      error.value = e.response?.data?.message || '无法读取当前内容，暂不能执行此操作'
    } finally {
      if (!request.signal.aborted) loading.value = false
    }
  },
  { immediate: true },
)
onUnmounted(() => controller?.abort())
</script>

<style scoped>
.impact-preview {
  margin-top: 16px;
  font-size: 13px;
}
.preview-hint {
  color: var(--st-text-secondary);
  font-size: 12px;
}
.impact-list {
  max-height: 300px;
  overflow: auto;
  border: 1px solid var(--st-border-subtle);
  border-radius: 8px;
}
.impact-item {
  padding: 10px;
  border-bottom: 1px solid var(--st-border-subtle);
  overflow-wrap: anywhere;
}
.impact-item summary {
  cursor: pointer;
}
.impact-item p,
.impact-item ul {
  color: var(--st-text-secondary);
  font-size: 12px;
}
</style>
