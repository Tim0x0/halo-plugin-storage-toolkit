<template>
  <div class="toolkit-wrapper">
    <VPageHeader title="存储工具箱">
      <template #icon>
        <IconFolder class="mr-2 self-center" />
      </template>
    </VPageHeader>

    <div class="toolkit-body">
      <nav class="toolkit-tabs">
        <button
          v-for="tab in tabs"
          :key="tab.id"
          :class="['tab-btn', { active: currentTab === tab.id }]"
          @click="switchTab(tab.id)"
        >
          <span class="tab-icon">{{ tab.icon }}</span>
          <span class="tab-text">{{ tab.label }}</span>
        </button>
      </nav>

      <main class="toolkit-main">
        <StatisticsPanel v-if="currentTab === 'statistics'" />
        <AnalysisPanel v-else-if="currentTab === 'analysis'" />
        <BatchProcessingPanel v-else-if="currentTab === 'batch'" />
        <ProcessingLogsPanel v-else-if="currentTab === 'logs'" />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { VPageHeader, IconFolder } from '@halo-dev/components'
import ProcessingLogsPanel from './panels/ProcessingLogsPanel.vue'
import StatisticsPanel from './panels/StatisticsPanel.vue'
import AnalysisPanel from './panels/AnalysisPanel.vue'
import BatchProcessingPanel from './panels/BatchProcessingPanel.vue'

const route = useRoute()
const router = useRouter()

const tabs = [
  { id: 'statistics', label: '存储统计', icon: '📊' },
  { id: 'analysis', label: '附件分析', icon: '🔍' },
  { id: 'batch', label: '批量处理', icon: '⚡' },
  { id: 'logs', label: '处理日志', icon: '📝' }
]

const STORAGE_KEY = 'storage-toolkit-tab'

const currentTab = ref('statistics')

const switchTab = (tabId: string) => {
  currentTab.value = tabId
  localStorage.setItem(STORAGE_KEY, tabId)
  // 同步更新 URL，保留其他 query 参数
  router.replace({
    query: { ...route.query, tab: tabId }
  })
}

onMounted(() => {
  // 优先使用 URL 参数
  const tabFromUrl = route.query.tab as string
  if (tabFromUrl && tabs.some(t => t.id === tabFromUrl)) {
    currentTab.value = tabFromUrl
    localStorage.setItem(STORAGE_KEY, tabFromUrl)
  } else if (tabFromUrl === 'overview') {
    // 兼容旧 URL：overview -> statistics，同步更新 URL
    currentTab.value = 'statistics'
    localStorage.setItem(STORAGE_KEY, 'statistics')
    router.replace({ query: { ...route.query, tab: 'statistics' } })
  } else {
    // 否则使用 localStorage
    const savedTab = localStorage.getItem(STORAGE_KEY)
    if (savedTab && tabs.some(t => t.id === savedTab)) {
      currentTab.value = savedTab
    } else if (savedTab === 'overview') {
      currentTab.value = 'statistics'
      localStorage.setItem(STORAGE_KEY, 'statistics')
    }
  }
})

// 监听 URL 变化
watch(() => route.query.tab, (newTab) => {
  if (newTab && tabs.some(t => t.id === newTab)) {
    currentTab.value = newTab as string
  }
})
</script>

<style scoped>
.toolkit-wrapper {
  background: var(--color-fill-secondary, #f4f4f5);
  min-height: 100vh;
}

.toolkit-body {
  padding: 16px;
}

.toolkit-tabs {
  display: flex;
  gap: 2px;
  background: white;
  padding: 6px;
  border-radius: 8px;
  margin-bottom: 16px;
  box-shadow: 0 1px 2px rgba(0,0,0,0.04);
}

.tab-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 18px;
  font-size: 14px;
  font-weight: 500;
  color: #71717a;
  background: transparent;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}

.tab-btn:hover {
  color: #3f3f46;
  background: #f4f4f5;
}

.tab-btn.active {
  color: #18181b;
  background: #f4f4f5;
}

.tab-icon {
  font-size: 15px;
}

.toolkit-main {
  min-height: calc(100vh - 180px);
}
</style>
