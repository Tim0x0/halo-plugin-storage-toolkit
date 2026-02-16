<template>
  <div class="logs-panel-container">
    <!-- Tab 导航 -->
    <div class="tab-nav">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        :class="['tab-btn', { active: activeTab === tab.id }]"
        @click="activeTab = tab.id"
      >
        {{ tab.label }}
      </button>
    </div>

    <!-- Tab 内容 -->
    <div class="tab-content">
      <ProcessingLogTab v-if="activeTab === 'processing'" />
      <CleanupLogTab v-else-if="activeTab === 'cleanup'" />
      <UrlReplaceLogTab v-else-if="activeTab === 'replace'" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import ProcessingLogTab from './logs/ProcessingLogTab.vue'
import CleanupLogTab from './logs/CleanupLogTab.vue'
import UrlReplaceLogTab from './logs/UrlReplaceLogTab.vue'

const tabs = [
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

.tab-nav {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid #e5e7eb;
  padding-bottom: 0;
}

.tab-btn {
  padding: 10px 20px;
  font-size: 14px;
  font-weight: 500;
  color: #6b7280;
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  transition: all 0.2s;
  margin-bottom: -1px;
}

.tab-btn:hover {
  color: #374151;
}

.tab-btn.active {
  color: #4f46e5;
  border-bottom-color: #4f46e5;
}

.tab-content {
  flex: 1;
}
</style>
