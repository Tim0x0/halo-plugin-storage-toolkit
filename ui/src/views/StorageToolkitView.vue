<template>
  <div class="toolkit-wrapper st-scope">
    <VPageHeader title="存储工具箱">
      <template #icon>
        <IconFolder class="mr-2 self-center" />
      </template>
      <template #actions>
        <VButton size="sm" @click="router.push('/plugins/storage-toolkit')">
          <template #icon>
            <IconSettings class="h-full w-full" />
          </template>
          插件设置
        </VButton>
      </template>
    </VPageHeader>

    <div class="toolkit-body">
      <PillTabs :tabs="tabs" :model-value="currentTab" @update:model-value="switchTab" />

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
import { ref, onMounted, watch, defineAsyncComponent, markRaw } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  VPageHeader,
  IconFolder,
  VLoading,
  VButton,
  IconDashboard,
  IconSearch,
  IconMagic,
  IconHistoryLine,
  IconSettings
} from '@halo-dev/components'
import PillTabs from '@/components/PillTabs.vue'
import type { PillTabItem } from '@/types/ui'

const ProcessingLogsPanel = defineAsyncComponent({
  loader: () => import(/* webpackChunkName: "processing-logs-panel" */ './panels/ProcessingLogsPanel.vue'),
  loadingComponent: VLoading
})

const StatisticsPanel = defineAsyncComponent({
  loader: () => import(/* webpackChunkName: "statistics-panel" */ './panels/StatisticsPanel.vue'),
  loadingComponent: VLoading
})

const AnalysisPanel = defineAsyncComponent({
  loader: () => import(/* webpackChunkName: "analysis-panel" */ './panels/AnalysisPanel.vue'),
  loadingComponent: VLoading
})

const BatchProcessingPanel = defineAsyncComponent({
  loader: () => import(/* webpackChunkName: "batch-processing-panel" */ './panels/BatchProcessingPanel.vue'),
  loadingComponent: VLoading
})

const route = useRoute()
const router = useRouter()

const tabs: PillTabItem[] = [
  { id: 'statistics', label: '存储统计', icon: markRaw(IconDashboard) },
  { id: 'analysis', label: '附件分析', icon: markRaw(IconSearch) },
  { id: 'batch', label: '批量处理', icon: markRaw(IconMagic) },
  { id: 'logs', label: '处理日志', icon: markRaw(IconHistoryLine) }
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
  background: var(--st-bg-page);
  min-height: 100vh;
}

.toolkit-body {
  padding: 16px;
}

.st-pill-tabs {
  margin-bottom: 16px;
}

.toolkit-main {
  min-height: calc(100vh - 180px);
}

/* 响应式 */
@media (max-width: 768px) {
  .toolkit-body {
    padding: 12px;
  }
}
</style>
