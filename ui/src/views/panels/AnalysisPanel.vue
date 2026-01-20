<template>
  <div class="analysis">
    <div class="sub-tabs">
      <button
        v-for="tab in subTabs"
        :key="tab.id"
        :class="['sub-tab', { active: currentSubTab === tab.id }]"
        @click="$emit('update:sub-tab', tab.id)"
      >
        {{ tab.label }}
      </button>
    </div>

    <div class="sub-content">
      <ReferenceTab v-if="currentSubTab === 'reference'" />
      <DuplicateTab v-else-if="currentSubTab === 'duplicate'" />
      <BrokenLinkTab v-else-if="currentSubTab === 'broken-link'" />
      <WhitelistTab v-else-if="currentSubTab === 'whitelist'" />
    </div>
  </div>
</template>

<script setup lang="ts">
import ReferenceTab from './analysis/ReferenceTab.vue'
import DuplicateTab from './analysis/DuplicateTab.vue'
import BrokenLinkTab from './analysis/BrokenLinkTab.vue'
import WhitelistTab from './analysis/WhitelistTab.vue'

const subTabs = [
  { id: 'reference', label: '引用统计' },
  { id: 'duplicate', label: '重复检测' },
  { id: 'broken-link', label: '断链检测' },
  { id: 'whitelist', label: '白名单管理' }
]

defineProps<{ currentSubTab: string }>()
defineEmits<{ (e: 'update:sub-tab', value: string): void }>()
</script>

<style scoped>
.analysis {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.sub-tabs {
  display: flex;
  gap: 24px;
  border-bottom: 1px solid #e4e4e7;
  padding-bottom: 0;
}

.sub-tab {
  padding: 12px 4px;
  font-size: 14px;
  font-weight: 500;
  color: #71717a;
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  cursor: pointer;
  transition: all 0.15s;
}

.sub-tab:hover {
  color: #3f3f46;
}

.sub-tab.active {
  color: #18181b;
  border-bottom-color: #18181b;
}

.sub-content {
  min-height: 400px;
}
</style>
