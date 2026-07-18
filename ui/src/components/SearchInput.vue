<template>
  <div class="st-search">
    <svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
      <circle cx="11" cy="11" r="7" />
      <path d="m21 21-4.35-4.35" stroke-linecap="round" />
    </svg>
    <input
      :value="modelValue"
      type="text"
      class="search-input"
      :placeholder="placeholder"
      @input="onInput"
    />
    <button v-if="modelValue" type="button" class="search-clear" title="清空" @click="onClear">
      ×
    </button>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: string
    placeholder?: string
    delay?: number
  }>(),
  {
    placeholder: '搜索...',
    delay: 300
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  (e: 'search', value: string): void
}>()

let timer: ReturnType<typeof setTimeout> | null = null

const schedule = (value: string) => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => {
    emit('search', value)
    timer = null
  }, props.delay)
}

const onInput = (e: Event) => {
  const value = (e.target as HTMLInputElement).value
  emit('update:modelValue', value)
  schedule(value)
}

const onClear = () => {
  if (timer) clearTimeout(timer)
  timer = null
  emit('update:modelValue', '')
  emit('search', '')
}

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
})
</script>

<style scoped>
.st-search {
  position: relative;
  display: inline-flex;
  align-items: center;
  min-width: 200px;
}

.search-icon {
  position: absolute;
  left: 10px;
  width: 14px;
  height: 14px;
  color: var(--st-text-tertiary);
  pointer-events: none;
}

.search-input {
  width: 100%;
  padding: 8px 28px 8px 30px;
  font-size: 13px;
  color: var(--st-text-primary);
  background: var(--st-bg-surface);
  border: 1px solid var(--st-border);
  border-radius: var(--st-radius-md);
  outline: none;
  transition: var(--st-transition);
}

.search-input::placeholder {
  color: var(--st-text-tertiary);
}

.search-input:focus {
  border-color: var(--st-focus-border);
  box-shadow: var(--st-focus-ring);
}

.search-clear {
  position: absolute;
  right: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  font-size: 14px;
  line-height: 1;
  color: var(--st-text-tertiary);
  background: transparent;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  transition: var(--st-transition);
}

.search-clear:hover {
  color: var(--st-text-primary);
  background: var(--st-bg-muted);
}

@media (max-width: 640px) {
  .st-search {
    min-width: 0;
    width: 100%;
  }
}
</style>
