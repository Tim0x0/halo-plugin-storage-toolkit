<template>
  <section class="st-card task-history">
    <div class="st-toolbar">
      <strong>任务中心</strong>
      <div class="task-actions">
        <VButton v-if="expanded" size="sm" :loading="loading" @click="load">刷新历史</VButton>
        <VButton size="sm" :aria-expanded="expanded" @click="expanded = !expanded">{{
          expanded ? '收起历史' : `展开历史 (${total})`
        }}</VButton>
      </div>
    </div>
    <template v-if="expanded">
      <p v-if="error" role="alert">{{ error }}</p>
      <VLoading v-if="loading && !tasks.length" />
      <VEmpty v-else-if="!tasks.length" title="暂无历史任务" />
      <div v-for="task in tasks" :key="task.taskId || ''" class="task-row">
        <div class="task-heading">
          <strong>{{ statusLabel(task) }}</strong>
          <span>{{ formatTime(task.startTime) }}</span>
          <span>{{ task.progress?.processed || 0 }}/{{ task.progress?.total || 0 }}</span>
          <span
            >成功 {{ task.progress?.succeeded || 0 }} · 失败 {{ task.progress?.failed || 0 }} · 跳过
            {{ task.skippedCount }}</span
          >
        </div>
        <p v-if="task.errorMessage" class="task-warning">{{ task.errorMessage }}</p>
        <p v-if="task.parentTaskId">失败项重试任务</p>
        <p v-if="task.reviewAttachmentNames.length" class="task-warning">
          {{ task.reviewAttachmentNames.length }}
          个文件需核查上传或引用替换结果，自动继续会跳过这些文件。
        </p>
        <details
          v-if="
            task.failedItems?.length ||
            task.skippedItems?.length ||
            task.reviewAttachmentNames.length
          "
        >
          <summary>查看明细</summary>
          <p v-for="item in task.failedItems || []" :key="item.attachmentName">
            {{ item.displayName }}：{{ item.error }}{{ item.retryable ? '' : '（需人工核查）' }}
          </p>
          <p v-for="item in task.skippedItems || []" :key="item.attachmentName">
            {{ item.displayName }}：{{ item.reason }}
          </p>
          <p v-for="name in task.reviewAttachmentNames" :key="name">需核查附件：{{ name }}</p>
        </details>
        <div class="task-actions">
          <VButton
            v-if="
              task.pendingCount > 0 &&
              ['CANCELLED', 'ERROR', 'INTERRUPTED'].includes(task.phase || '')
            "
            size="sm"
            :disabled="busy || acting"
            @click="run(task, 'resume')"
            >继续未执行项 ({{ task.pendingCount }})</VButton
          >
          <VButton
            v-if="task.failedItems?.some((item) => item.retryable)"
            size="sm"
            :disabled="busy || acting"
            @click="run(task, 'retry')"
            >重试失败项</VButton
          >
        </div>
      </div>
      <VPagination v-if="total > 10" v-model:page="page" :size="10" :total="total" />
    </template>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { VButton, VEmpty, VLoading, VPagination, Toast } from '@halo-dev/components'
import { API_ENDPOINTS } from '@/constants/api'
import { formatTime } from '@/utils/format'
import type { TaskStatus } from '@/types/task'
const props = defineProps<{ busy: boolean }>()
const emit = defineEmits<{ started: [] }>()
const expanded = ref(false)
const tasks = ref<TaskStatus[]>([])
const page = ref(1),
  total = ref(0),
  loading = ref(false),
  acting = ref(false),
  error = ref('')
const phaseLabels: Record<string, string> = {
  PENDING: '等待中',
  PROCESSING: '处理中',
  CANCELLING: '取消中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  ERROR: '执行失败',
  INTERRUPTED: '已中断',
}
function statusLabel(task: TaskStatus) {
  if (task.phase === 'COMPLETED' && task.progress?.failed)
    return task.progress.succeeded ? '部分失败' : '处理失败'
  return phaseLabels[task.phase || ''] || task.phase
}
async function load() {
  loading.value = true
  error.value = ''
  try {
    const { data } = await axiosInstance.get(API_ENDPOINTS.BATCH_PROCESSING_TASKS, {
      params: { page: page.value, size: 10 },
    })
    tasks.value = data.items
    total.value = data.total
  } catch {
    error.value = '任务历史加载失败，请重试'
  } finally {
    loading.value = false
  }
}
async function run(task: TaskStatus, action: 'resume' | 'retry') {
  acting.value = true
  try {
    await axiosInstance.post(
      `${API_ENDPOINTS.BATCH_PROCESSING_TASKS}/${encodeURIComponent(task.taskId || '')}/${action}`,
    )
    Toast.success(action === 'resume' ? '已继续任务' : '已创建失败项重试任务')
    emit('started')
    await load()
  } catch (cause) {
    const e = cause as { response?: { data?: { message?: string } } }
    Toast.error(e.response?.data?.message || '任务启动失败')
  } finally {
    acting.value = false
  }
}
watch(page, load)
watch(() => props.busy, load)
onMounted(load)
</script>

<style scoped>
.task-history {
  padding: 16px;
}
.task-row {
  border-top: 1px solid var(--st-border-subtle);
  padding: 14px 0;
  overflow-wrap: anywhere;
}
.task-heading,
.task-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}
.task-heading span,
details {
  font-size: 12px;
  color: var(--st-text-secondary);
}
.task-warning {
  color: var(--st-color-warning, #9a5b00);
  font-size: 13px;
}
.task-row .task-actions {
  margin-top: 10px;
}
</style>
