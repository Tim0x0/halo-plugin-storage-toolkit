<template>
  <span :class="['st-status-badge', `tone-${tone}`]">
    <span v-if="dot" class="badge-dot"></span>
    <slot></slot>
  </span>
</template>

<script setup lang="ts">
import type { BadgeTone } from '@/types/ui'

withDefaults(
  defineProps<{
    tone?: BadgeTone
    dot?: boolean
  }>(),
  {
    tone: 'neutral',
    dot: false
  }
)
</script>

<style scoped>
.st-status-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  font-size: 12px;
  font-weight: 500;
  line-height: 18px;
  border-radius: var(--st-radius-sm);
  white-space: nowrap;
}

.badge-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
}

.st-status-badge.tone-success {
  background: var(--st-success-bg);
  color: var(--st-success-text-strong);
}

.st-status-badge.tone-error {
  background: var(--st-error-bg);
  color: var(--st-error-text-strong);
}

.st-status-badge.tone-warning {
  background: var(--st-warning-bg);
  color: var(--st-warning-text-strong);
}

.st-status-badge.tone-info {
  background: var(--st-info-bg);
  color: var(--st-info-text-strong);
}

.st-status-badge.tone-neutral {
  background: var(--st-bg-muted);
  color: var(--st-text-secondary);
}
</style>
