<template>
  <div class="stat-card st-card">
    <div class="stat-label">
      <component :is="icon" v-if="icon" class="stat-icon" />
      <span>{{ label }}</span>
    </div>
    <div :class="['stat-value', `tone-${tone}`]">
      <span v-if="loading" class="stat-skeleton"></span>
      <slot v-else name="value">{{ value }}</slot>
    </div>
    <div v-if="hint" class="stat-hint">{{ hint }}</div>
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
  flex-direction: column;
  gap: 6px;
  padding: 16px 20px;
  min-width: 0;
}

.stat-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--st-text-secondary);
}

.stat-icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--st-text-primary);
  font-variant-numeric: tabular-nums;
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
  height: 24px;
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
}
</style>
