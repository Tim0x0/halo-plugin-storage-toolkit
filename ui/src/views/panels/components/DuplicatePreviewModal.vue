<template>
  <VModal
    :visible="true"
    :title="file.displayName"
    :width="600"
    mount-to-body
    :layer-closable="true"
    @close="$emit('close')"
  >
    <!-- teleport 到 body 后需带 st-scope，否则 CSS 变量无法解析 -->
    <div class="st-scope dup-preview">
      <!-- 预览区域 -->
      <div class="preview-area" v-if="file.permalink && isImage(file.mediaType)">
        <img :src="file.permalink" class="preview-image" />
      </div>
      <div class="preview-area preview-placeholder" v-else>
        <span class="preview-icon">{{ getFileIcon(file.mediaType ?? null) }}</span>
      </div>

      <!-- 文件信息 -->
      <div class="info-section">
        <div class="st-field-list">
          <div class="st-field-item">
            <span class="st-field-label">大小</span>
            <span class="st-field-value">{{ formatBytes(size) }}</span>
          </div>
          <div class="st-field-item">
            <span class="st-field-label">类型</span>
            <span class="st-field-value">{{ file.mediaType || '未知' }}</span>
          </div>
          <div class="st-field-item">
            <span class="st-field-label">存储策略</span>
            <span class="st-field-value">{{ file.policyDisplayName || file.policyName || '默认策略' }}</span>
          </div>
          <div class="st-field-item">
            <span class="st-field-label">分组</span>
            <span class="st-field-value">{{ file.groupDisplayName || '未分组' }}</span>
          </div>
          <div class="st-field-item">
            <span class="st-field-label">上传时间</span>
            <span class="st-field-value">{{ file.uploadTime ? formatTime(file.uploadTime) : '-' }}</span>
          </div>
          <div class="st-field-item" v-if="file.permalink">
            <span class="st-field-label">链接</span>
            <span class="st-field-value field-url">{{ file.permalink }}</span>
          </div>
        </div>
      </div>

      <!-- 引用列表 -->
      <ReferenceList :references="file.references" />
    </div>
  </VModal>
</template>

<script setup lang="ts">
import { VModal } from '@halo-dev/components'
import type { DuplicateFile } from '@/types/duplicate'
import { formatBytes, formatTime } from '@/utils/format'
import { getFileIcon, isImage } from '@/composables/useReferenceSource'
import ReferenceList from '@/components/ReferenceList.vue'

defineProps<{
  file: DuplicateFile
  size: number
}>()

defineEmits<{
  (e: 'close'): void
}>()
</script>

<style scoped>
.dup-preview {
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

/* 文件信息区域 */
.info-section {
  border-bottom: 1px solid var(--st-border-subtle);
  padding-bottom: 16px;
}

.field-url {
  font-size: 12px;
  color: var(--st-text-secondary);
}
</style>
