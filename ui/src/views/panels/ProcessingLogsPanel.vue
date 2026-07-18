<template>
  <div class="logs-panel-container">
    <PillTabs v-model="activeTab" :tabs="tabs" />

    <div class="tab-content">
      <ProcessingLogTab v-if="activeTab === 'processing'" />
      <CleanupLogTab v-else-if="activeTab === 'cleanup'" />
      <UrlReplaceLogTab v-else-if="activeTab === 'replace'" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import PillTabs from '@/components/PillTabs.vue'
import type { PillTabItem } from '@/types/ui'
import ProcessingLogTab from './logs/ProcessingLogTab.vue'
import CleanupLogTab from './logs/CleanupLogTab.vue'
import UrlReplaceLogTab from './logs/UrlReplaceLogTab.vue'

const tabs: PillTabItem[] = [
  { id: 'processing', label: '图片处理' },
  { id: 'cleanup', label: '清理记录' },
  { id: 'replace', label: '替换记录' }
]

const activeTab = ref(localStorage.getItem('storage-toolkit-logs-sub-tab') || 'processing')
watch(activeTab, val => localStorage.setItem('storage-toolkit-logs-sub-tab', val))
</script>

<style scoped>
.logs-panel-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.tab-content {
  flex: 1;
}
</style>
