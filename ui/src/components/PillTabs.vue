<template>
  <nav class="st-pill-tabs">
    <button
      v-for="tab in tabs"
      :key="tab.id"
      type="button"
      :class="['pill-btn', { active: modelValue === tab.id }]"
      @click="$emit('update:modelValue', tab.id)"
    >
      <component :is="tab.icon" v-if="tab.icon" class="pill-icon" />
      <span class="pill-text">{{ tab.label }}</span>
      <span v-if="tab.count !== undefined" class="pill-count">{{ tab.count }}</span>
    </button>
  </nav>
</template>

<script setup lang="ts">
import type { PillTabItem } from '@/types/ui'

defineProps<{
  tabs: PillTabItem[]
  modelValue: string
}>()

defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()
</script>

<style scoped>
.st-pill-tabs {
  display: flex;
  gap: 2px;
  background: var(--st-bg-surface);
  padding: 6px;
  border: 1px solid var(--st-border-card);
  border-radius: var(--st-radius-lg);
  box-shadow: var(--st-shadow-card);
}

.pill-btn {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 9px 16px;
  font-size: 14px;
  font-weight: 500;
  color: var(--st-text-secondary);
  background: transparent;
  border: none;
  border-radius: var(--st-radius-md);
  cursor: pointer;
  transition: var(--st-transition);
  white-space: nowrap;
}

.pill-btn:hover {
  color: var(--st-text-primary);
  background: var(--st-bg-muted);
}

.pill-btn.active {
  color: var(--st-text-primary);
  background: var(--st-bg-muted);
}

.pill-btn.active::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--st-color-primary);
  flex-shrink: 0;
}

.pill-icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}

.pill-count {
  padding: 0 6px;
  font-size: 12px;
  line-height: 18px;
  border-radius: 9px;
  background: var(--st-bg-muted);
  color: var(--st-text-secondary);
}

.pill-btn.active .pill-count {
  background: var(--st-color-primary-soft);
  color: var(--st-text-primary);
}

@media (max-width: 768px) {
  .st-pill-tabs {
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
  }
}

@media (max-width: 640px) {
  .pill-btn {
    padding: 8px 12px;
    font-size: 13px;
  }

  .pill-icon {
    display: none;
  }
}
</style>
