<template>
  <div class="analysis">
    <PillTabs v-model="activeTab" :tabs="subTabs" />

    <div class="sub-content">
      <ReferenceTab v-if="activeTab === 'reference'" />
      <DuplicateTab v-else-if="activeTab === 'duplicate'" />
      <BrokenLinkTab v-else-if="activeTab === 'broken-link'" />
      <WhitelistTab v-else-if="activeTab === 'whitelist'" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import PillTabs from '@/components/PillTabs.vue'
import type { PillTabItem } from '@/types/ui'
import ReferenceTab from './analysis/ReferenceTab.vue'
import DuplicateTab from './analysis/DuplicateTab.vue'
import BrokenLinkTab from './analysis/BrokenLinkTab.vue'
import WhitelistTab from './analysis/WhitelistTab.vue'

const subTabs: PillTabItem[] = [
  { id: 'reference', label: '引用统计' },
  { id: 'duplicate', label: '重复检测' },
  { id: 'broken-link', label: '断链检测' },
  { id: 'whitelist', label: '白名单管理' }
]

const activeTab = ref(localStorage.getItem('storage-toolkit-analysis-sub-tab') || 'reference')
watch(activeTab, val => localStorage.setItem('storage-toolkit-analysis-sub-tab', val))
</script>

<style scoped>
.analysis {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.sub-content {
  min-height: 400px;
}
</style>
