<template>
  <VModal
    :visible="true"
    :title="attachment?.displayName || ''"
    :width="600"
    mount-to-body
    :layer-closable="true"
    @close="$emit('close')"
  >
    <!-- teleport 到 body 后需带 st-scope，否则 CSS 变量无法解析 -->
    <div class="st-scope ref-detail">
      <!-- 预览区域 -->
      <div
        class="preview-area"
        v-if="attachment?.mediaType?.startsWith('image/') && attachment?.permalink"
      >
        <img :src="attachment.permalink" class="preview-image" />
      </div>
      <div class="preview-area preview-placeholder" v-else>
        <span class="preview-icon">{{ getFileIcon(attachment?.mediaType || '') }}</span>
      </div>

      <!-- 文件信息 -->
      <div class="st-field-list">
        <div class="st-field-item">
          <span class="st-field-label">大小</span>
          <span class="st-field-value">{{ formatBytes(attachment?.size || 0) }}</span>
        </div>
        <div class="st-field-item">
          <span class="st-field-label">类型</span>
          <span class="st-field-value">{{ attachment?.mediaType || '未知' }}</span>
        </div>
        <div class="st-field-item">
          <span class="st-field-label">存储策略</span>
          <span class="st-field-value">{{ policyDisplayName ?? '加载中...' }}</span>
        </div>
        <div class="st-field-item">
          <span class="st-field-label">分组</span>
          <span class="st-field-value">{{ groupDisplayName ?? '加载中...' }}</span>
        </div>
        <div class="st-field-item" v-if="attachment?.permalink">
          <span class="st-field-label">链接</span>
          <span class="st-field-value st-field-value--url">{{ attachment.permalink }}</span>
        </div>
      </div>

      <!-- 引用列表 -->
      <ReferenceList
        :references="attachment?.references"
        @reference-resolved="$emit('reference-resolved', $event)"
      />
    </div>
  </VModal>
</template>

<script setup lang="ts">
import { VModal } from '@halo-dev/components'
import { formatBytes } from '@/utils/format'
import { getFileIcon } from '@/composables/useReferenceSource'
import type { ReferenceSource } from '@/types/duplicate'
import ReferenceList from '@/components/ReferenceList.vue'
import type { ReferenceSourceItem } from '@/components/ReferenceList.vue'

interface AttachmentDetail {
  displayName: string
  mediaType: string
  size: number
  permalink: string | null
  references: ReferenceSource[]
}

defineProps<{
  attachment: AttachmentDetail | null
  policyDisplayName: string | null
  groupDisplayName: string | null
}>()

defineEmits<{
  (e: 'close'): void
  (e: 'reference-resolved', ref: ReferenceSourceItem): void
}>()
</script>

<style scoped>
.ref-detail {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 预览区域 */
.preview-area {
  width: 100%;
  height: 200px;
  background: var(--st-bg-hover);
  border-radius: var(--st-radius-md);
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
  background: var(--st-bg-muted);
}

.preview-icon {
  font-size: 48px;
  opacity: 0.4;
}

/* 链接值使用次级色 + 更小字号 */
.st-field-value--url {
  font-size: 12px;
  color: var(--st-text-secondary);
}
</style>
