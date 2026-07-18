<template>
  <div class="st-progress">
    <div v-if="showLabel" class="progress-label">
      <slot name="label"></slot>
      <span class="progress-percent">{{ Math.round(percent) }}%</span>
    </div>
    <div class="progress-track" :style="{ height: `${height}px` }">
      <div
        :class="['progress-fill', `tone-${tone}`, { indeterminate }]"
        :style="indeterminate ? {} : { width: `${clamped}%` }"
      ></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ProgressTone } from '@/types/ui'

const props = withDefaults(
  defineProps<{
    percent?: number
    tone?: ProgressTone
    showLabel?: boolean
    height?: number
    indeterminate?: boolean
  }>(),
  {
    percent: 0,
    tone: 'primary',
    showLabel: false,
    height: 8,
    indeterminate: false
  }
)

const clamped = computed(() => Math.min(100, Math.max(0, props.percent)))
</script>

<style scoped>
.st-progress {
  width: 100%;
}

.progress-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
  font-size: 13px;
  color: var(--st-text-secondary);
}

.progress-percent {
  font-variant-numeric: tabular-nums;
  color: var(--st-text-primary);
  font-weight: 500;
}

.progress-track {
  width: 100%;
  background: var(--st-bg-muted);
  border-radius: var(--st-radius-sm);
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  border-radius: var(--st-radius-sm);
  transition: width 300ms var(--st-ease);
}

.progress-fill.tone-primary { background: var(--st-color-primary); }
.progress-fill.tone-success { background: var(--st-success-text); }
.progress-fill.tone-error { background: var(--st-error-text); }
.progress-fill.tone-warning { background: var(--st-warning-text); }

.progress-fill.indeterminate {
  width: 40%;
  animation: st-indeterminate 1.2s ease-in-out infinite;
}

@keyframes st-indeterminate {
  0% { margin-left: -40%; }
  100% { margin-left: 100%; }
}
</style>
