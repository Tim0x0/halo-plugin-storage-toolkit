<template>
  <div class="stat-card st-card">
    <div v-if="icon" :class="['stat-icon-chip', `chip-${tone}`]">
      <component :is="icon" class="stat-icon" />
    </div>
    <div class="stat-body">
      <div class="stat-label">{{ label }}</div>
      <div :class="['stat-value', `tone-${tone}`]">
        <span v-if="loading" class="stat-skeleton"></span>
        <slot v-else name="value">{{ value }}</slot>
      </div>
      <div v-if="hint" class="stat-hint">{{ hint }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { Component } from 'vue'
import type { StatTone } from '@/types/ui'

withDefaults(
  defineProps<{
    label: string
    value?: string | number
    icon?: Component
    tone?: StatTone
    hint?: string
    loading?: boolean
  }>(),
  {
    value: '',
    tone: 'default',
    loading: false
  }
)
</script>

<style scoped>
.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 20px;
  min-width: 0;
  transition: var(--st-transition);
}

.stat-card:hover {
  box-shadow: var(--st-shadow-card-hover);
}

/* 图标块：语义色浅底 */
.stat-icon-chip {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  border-radius: var(--st-radius-md);
  background: var(--st-bg-muted);
  color: var(--st-text-secondary);
}

.stat-icon-chip.chip-success {
  background: var(--st-success-bg);
  color: var(--st-success-text);
}

.stat-icon-chip.chip-warning {
  background: var(--st-warning-bg);
  color: var(--st-warning-text);
}

.stat-icon-chip.chip-error {
  background: var(--st-error-bg);
  color: var(--st-error-text);
}

.stat-icon-chip.chip-info {
  background: var(--st-info-bg);
  color: var(--st-info-text);
}

.stat-icon {
  width: 20px;
  height: 20px;
}

.stat-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.stat-label {
  font-size: 13px;
  color: var(--st-text-secondary);
}

.stat-value {
  font-size: 22px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--st-text-primary);
  font-variant-numeric: tabular-nums;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.stat-value.tone-success { color: var(--st-success-text); }
.stat-value.tone-warning { color: var(--st-warning-text); }
.stat-value.tone-error { color: var(--st-error-text); }
.stat-value.tone-info { color: var(--st-info-text); }

.stat-hint {
  font-size: 12px;
  color: var(--st-text-tertiary);
}

.stat-skeleton {
  display: inline-block;
  width: 64px;
  height: 22px;
  border-radius: var(--st-radius-sm);
  background: linear-gradient(90deg, var(--st-bg-muted) 25%, var(--st-bg-hover) 50%, var(--st-bg-muted) 75%);
  background-size: 200% 100%;
  animation: st-skeleton 1.2s ease-in-out infinite;
}

@keyframes st-skeleton {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

@media (max-width: 640px) {
  .stat-card { padding: 12px 16px; }
  .stat-value { font-size: 18px; }
  .stat-icon-chip { width: 34px; height: 34px; }
}
</style>
