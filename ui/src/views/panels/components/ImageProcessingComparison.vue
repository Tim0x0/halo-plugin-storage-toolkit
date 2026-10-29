<template>
  <section class="image-comparison">
    <VButton :loading="loading" @click="compare">{{
      comparison ? '重新试处理' : '按当前配置试处理并对比'
    }}</VButton>
    <p class="hint">试处理只生成预览，不会上传文件或修改引用。预览图片最大 20 MB。</p>
    <VAlert v-if="error" type="error" title="试处理失败" :description="error" />
    <template v-if="comparison">
      <VAlert
        v-if="comparison.message"
        :type="comparison.status === 'FAILED' ? 'error' : 'info'"
        :title="
          comparison.status === 'SKIPPED'
            ? '已跳过'
            : comparison.status === 'FAILED'
              ? '处理失败'
              : '处理提示'
        "
        :description="comparison.message"
      />
      <p>
        体积变化：{{ formatBytes(comparison.original.size) }} →
        {{ formatBytes(comparison.result.size) }} （{{ sizeChange }}）
      </p>
      <label class="zoom-control"
        >缩放 {{ zoom }}% <input v-model.number="zoom" type="range" min="100" max="300" step="25"
      /></label>
      <div class="comparison-grid">
        <figure v-for="(variant, index) in [comparison.original, comparison.result]" :key="index">
          <figcaption>
            <strong>{{ index === 0 ? '原图' : '处理结果' }}</strong>
            <span>{{ variant.width }} × {{ variant.height }} · {{ variant.mediaType }}</span>
          </figcaption>
          <div class="image-viewport">
            <img
              :src="variant.dataUrl"
              :alt="index === 0 ? '原图' : '处理结果'"
              :style="{ width: `${zoom}%` }"
            />
          </div>
        </figure>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { VAlert, VButton } from '@halo-dev/components'
import { API_ENDPOINTS } from '@/constants/api'
import { formatBytes } from '@/utils/format'
interface Variant {
  dataUrl: string
  size: number
  width: number
  height: number
  mediaType: string
}
interface Comparison {
  status: string
  message: string | null
  original: Variant
  result: Variant
}
const props = defineProps<{ attachmentName: string }>()
const comparison = ref<Comparison | null>(null),
  loading = ref(false),
  error = ref(''),
  zoom = ref(100)
let controller: AbortController | null = null
const sizeChange = computed(() => {
  if (!comparison.value?.original.size) return '0%'
  const change = (1 - comparison.value.result.size / comparison.value.original.size) * 100
  return `${change >= 0 ? '减小' : '增大'} ${Math.abs(change).toFixed(1)}%`
})
async function compare() {
  controller?.abort()
  const request = new AbortController()
  controller = request
  loading.value = true
  error.value = ''
  try {
    const { data } = await axiosInstance.post<Comparison>(
      API_ENDPOINTS.IMAGE_COMPARISON(props.attachmentName),
      null,
      { signal: request.signal },
    )
    if (!request.signal.aborted) comparison.value = data
  } catch (cause) {
    if (request.signal.aborted) return
    const e = cause as { response?: { data?: { message?: string } } }
    error.value = e.response?.data?.message || '试处理失败，请检查格式与处理配置'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}
onUnmounted(() => controller?.abort())
</script>

<style scoped>
.image-comparison {
  margin-top: 16px;
}
.hint,
figcaption span {
  font-size: 12px;
  color: var(--st-text-secondary);
}
.comparison-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
figure {
  min-width: 0;
  margin: 0;
}
figcaption {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 12px 0;
}
.image-viewport {
  height: 320px;
  overflow: auto;
  background: var(--st-bg-muted);
  border-radius: 8px;
}
.image-viewport img {
  display: block;
  max-width: none;
  height: auto;
}
.zoom-control {
  display: flex;
  align-items: center;
  gap: 12px;
}
@media (max-width: 600px) {
  .comparison-grid {
    grid-template-columns: 1fr;
  }
}
</style>
