<template>
  <div class="toolkit-wrapper st-scope">
    <header class="st-app-header">
      <div class="st-brand"><IconFolder /><strong>存储工具箱</strong><span>附件与图片管理</span></div>
      <div class="st-header-actions">
        <a
          class="st-community-link"
          href="https://qm.qq.com/q/YqlBX8d2Ei"
          target="_blank"
          rel="noopener noreferrer"
          aria-label="加入 QQ 讨论群：Halo 插件主题交流群（新窗口打开）"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8v.5Z" />
            <path d="M8 11h8M8 15h5" />
          </svg>
          <span>加入 QQ 讨论群</span>
        </a>
        <VButton size="sm" @click="router.push('/plugins/storage-toolkit')">
          <template #icon><IconSettings /></template>插件设置
        </VButton>
      </div>
    </header>

    <div class="toolkit-body">
      <div v-if="environmentStatus && !environmentStatus.storageReady && currentTab !== 'environment'" class="st-environment-notice" role="status">
        <span>日志存储尚未就绪，批量任务、清理与链接替换暂不可用。已有附件可正常访问。</span>
        <VButton size="sm" @click="switchTab('environment')">查看环境</VButton>
      </div>
      <PillTabs :tabs="tabs" :model-value="currentTab" @update:model-value="switchTab" />

      <main class="toolkit-main">
        <header v-if="currentTab !== 'environment' && !storageBlocked" class="st-section-heading">
          <div><h1>{{ sectionTitle }}</h1><p>{{ sectionDescription }}</p></div>
        </header>
        <section v-if="storageBlocked" class="st-unavailable">
          <h2>先完成日志存储准备</h2>
          <p>{{ environmentStatus?.storageMessage }}</p>
          <VButton @click="switchTab('environment')">前往环境检测</VButton>
        </section>
        <EnvironmentPanel v-else-if="currentTab === 'environment'" />
        <StatisticsPanel v-else-if="currentTab === 'statistics'" />
        <AnalysisPanel v-else-if="currentTab === 'analysis'" />
        <BatchProcessingPanel v-else-if="currentTab === 'batch'" />
        <ProcessingLogsPanel v-else-if="currentTab === 'logs'" />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, provide, onMounted, watch, defineAsyncComponent, markRaw } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
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
import { environmentKey } from '@/environment/context'
import { useEnvironmentController } from '@/environment/useEnvironmentController'
import EnvironmentPanel from './panels/EnvironmentPanel.vue'
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
  { id: 'logs', label: '处理日志', icon: markRaw(IconHistoryLine) },
  { id: 'environment', label: '环境检测', icon: markRaw(IconSettings) }
]

const environment = useEnvironmentController()
provide(environmentKey, environment)
const environmentStatus = environment.status
const storageBlocked = computed(() => !!environmentStatus.value && !environmentStatus.value.storageReady && ['batch', 'logs'].includes(currentTab.value))

const sectionDescriptions: Record<string, string> = {
  statistics: '查看附件占用、文件类型与存储分布。',
  analysis: '检查引用、重复文件和断链，整理存储空间。',
  batch: '按当前图片配置处理附件，查看进度与历史任务。',
  logs: '查看图片处理、文件清理与链接替换记录。',
}
const sectionTitle = computed(() => tabs.find(tab => tab.id === currentTab.value)?.label || '')
const sectionDescription = computed(() => sectionDescriptions[currentTab.value] || '')

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
.toolkit-wrapper { container: storage-toolkit / inline-size; background: var(--st-bg-page); color: var(--st-text-primary); min-height: 100vh; min-width: 0; width: 100%; }
.st-app-header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px 20px; min-height: 76px; padding: 18px 32px; border-bottom: 1px solid var(--st-border); }
.st-header-actions { display: flex; align-items: center; gap: 10px; margin-left: auto; }
.st-community-link { display: inline-flex; align-items: center; justify-content: center; gap: 7px; min-height: 36px; padding: 8px 12px; border: 1px solid var(--st-text-primary); border-radius: 9px; background: var(--st-text-primary); color: var(--st-bg-surface); font-size: 12px; font-weight: 550; line-height: 18px; text-decoration: none; white-space: nowrap; transition: filter var(--st-duration) var(--st-ease); }
.st-community-link:hover { filter: brightness(1.1); }
.st-community-link:active { filter: brightness(0.95); }
.st-community-link > svg { width: 16px; height: 16px; flex-shrink: 0; }
.st-brand { display: inline-flex; align-items: center; gap: 11px; min-width: 0; }
.st-brand > svg { width: 22px; height: 22px; flex-shrink: 0; }
.st-brand strong { font-size: 20px; font-weight: 650; letter-spacing: -0.6px; white-space: nowrap; }
.st-brand span { padding-left: 14px; margin-left: 3px; border-left: 1px solid var(--st-border); font-size: 12px; color: var(--st-text-secondary); white-space: nowrap; }
.toolkit-body { padding: 0 32px 32px; max-width: 1580px; margin: 0 auto; }
.toolkit-body > .st-pill-tabs { margin-bottom: 28px; min-height: 54px; }
.toolkit-main { min-height: calc(100vh - 190px); min-width: 0; }
.st-environment-notice { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 10px; padding: 12px 0; border-bottom: 1px solid var(--st-border); font-size: 12px; color: var(--st-warning-text-strong); }
.st-unavailable { display: grid; justify-items: start; align-content: center; gap: 16px; min-height: 300px; }
.st-unavailable h2 { font-size: 22px; font-weight: 550; margin: 0; }
.st-unavailable p { color: var(--st-text-secondary); margin: 0; }
@container storage-toolkit (max-width: 700px) {
  .st-app-header { padding: 15px 18px; min-height: 66px; gap: 12px; }
  .st-brand strong { font-size: 18px; }
  .st-brand span { display: none; }
  .toolkit-body { padding: 0 18px 24px; }
  .toolkit-body > .st-pill-tabs { margin-bottom: 22px; }
}
</style>
