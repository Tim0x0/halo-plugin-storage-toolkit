<template>
  <nav class="st-pill-tabs" aria-label="功能导航">
    <button
      v-for="tab in tabs"
      :key="tab.id"
      type="button"
      :class="['pill-btn', { active: modelValue === tab.id }]"
      :aria-current="modelValue === tab.id ? 'page' : undefined"
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
  gap: 26px;
  border-bottom: 1px solid var(--st-border);
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: thin;
}
.pill-btn {
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
  gap: 7px;
  min-height: 48px;
  padding: 0 1px;
  border: 0;
  border-bottom: 2px solid transparent;
  color: var(--st-text-secondary);
  background: transparent;
  font: inherit;
  font-size: 13px;
  white-space: nowrap;
  cursor: pointer;
  transition: color 150ms ease, border-color 150ms ease;
}
.pill-btn:hover { color: var(--st-text-primary); }
.pill-btn.active { color: var(--st-text-primary); border-bottom-color: var(--st-text-primary); font-weight: 600; }
.pill-btn:focus-visible { outline: 2px solid var(--st-focus-border); outline-offset: -5px; border-radius: 4px; }
.pill-icon { width: 15px; height: 15px; flex-shrink: 0; }
.pill-count { padding: 0 6px; font-size: 11px; border-radius: 8px; background: var(--st-bg-muted); }
@media (max-width: 640px) { .st-pill-tabs { gap: 20px; } .pill-btn { min-height: 44px; } }
</style>
