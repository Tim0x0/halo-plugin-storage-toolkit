<template>
  <VModal
    title="添加白名单"
    :width="520"
    mount-to-body
    :layer-closable="true"
    @close="$emit('close')"
  >
    <!-- teleport 到 body 后需带 st-scope，否则 CSS 变量无法解析 -->
    <div class="st-scope wl-form">
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

    <template #footer>
      <VSpace>
        <VButton type="secondary" :loading="submitting" @click="submitForm">
          确认添加
        </VButton>
        <VButton @click="$emit('close')">取消</VButton>
      </VSpace>
    </template>
  </VModal>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { Toast, VModal, VButton, VSpace } from '@halo-dev/components'
import { API_ENDPOINTS } from '@/constants/api'

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'saved'): void
}>()

const submitting = ref(false)
const formData = ref({
  url: '',
  matchMode: 'exact' as 'exact' | 'prefix',
  note: ''
})

// 提交表单（校验逻辑原样保留，成功后 emit saved 交由父组件刷新列表并关闭弹窗）
const submitForm = async () => {
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
    emit('saved')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.wl-form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.form-label {
  display: block;
  font-size: 14px;
  font-weight: 500;
  color: var(--st-text-primary);
  margin-bottom: 8px;
}

.required {
  color: var(--st-error-text);
}

.form-input,
.form-textarea {
  width: 100%;
  padding: 10px 12px;
  font-size: 14px;
  color: var(--st-text-primary);
  background: var(--st-bg-surface);
  border: 1px solid var(--st-border);
  border-radius: var(--st-radius-md);
  outline: none;
  transition: var(--st-transition);
  font-family: inherit;
}

.form-input:focus,
.form-textarea:focus {
  border-color: var(--st-focus-border);
  box-shadow: var(--st-focus-ring);
}

.form-input::placeholder,
.form-textarea::placeholder {
  color: var(--st-text-tertiary);
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
  border: 1px solid var(--st-border);
  border-radius: var(--st-radius-md);
  cursor: pointer;
  transition: var(--st-transition);
}

.radio-item:hover {
  background: var(--st-bg-hover);
}

.radio-item input[type="radio"] {
  margin-top: 2px;
  width: 16px;
  height: 16px;
  cursor: pointer;
  accent-color: var(--st-color-primary);
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
  color: var(--st-text-primary);
}

.radio-desc {
  font-size: 13px;
  color: var(--st-text-secondary);
}
</style>
