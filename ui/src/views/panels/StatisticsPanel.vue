<template>
  <div class="statistics-panel">
    <!-- 加载状态 -->
    <VLoading v-if="loading" />

    <!-- 错误状态 -->
    <VAlert v-else-if="error" type="error" title="获取统计数据失败" :description="error">
      <template #actions>
        <VButton size="sm" @click="fetchStatistics">重试</VButton>
      </template>
    </VAlert>

    <!-- 空状态 -->
    <VEmpty
      v-else-if="isEmpty"
      title="暂无附件数据"
      message="上传附件后将在此显示统计信息"
    />

    <!-- 正常内容 -->
    <template v-else>
      <!-- 顶部统计 -->
      <div class="stats-grid">
        <StatCard label="总存储空间" :value="formatBytes(totalSize)" :icon="IconDatabase2Line" />
        <StatCard label="附件总数" :value="totalCount.toLocaleString()" :icon="IconDashboard" />
        <StatCard label="存储策略" :value="policyCount" :icon="IconListSettings" />
        <StatCard label="分组数量" :value="groupCount" :icon="IconFolder" />
      </div>

      <!-- 图表区域 -->
      <div class="charts-grid">
        <!-- 类型分布 -->
        <div class="panel-card st-card">
          <div class="card-title">类型分布</div>
          <div class="pie-chart-area">
            <div class="pie-chart" ref="typeChartRef"></div>
            <div class="pie-legend">
              <div class="legend-item" v-for="item in typeDataWithColor" :key="item.key">
                <span class="legend-dot" :style="{ background: item.color }"></span>
                <span class="legend-name">{{ item.name }}</span>
                <span class="legend-value">{{ item.percent }} %</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 策略分布 -->
        <div class="panel-card st-card">
          <div class="card-title"><span>策略分布 <span class="title-tag">Top 5</span></span></div>
          <div class="pie-chart-area" v-if="policyDataWithColor.length > 0">
            <div class="pie-chart" ref="policyChartRef"></div>
            <div class="pie-legend">
              <div class="legend-item" v-for="item in policyDataWithColor" :key="item.key">
                <span class="legend-dot" :style="{ background: item.color }"></span>
                <span class="legend-name">{{ item.name }}</span>
                <span class="legend-value">{{ item.percent }} %</span>
              </div>
            </div>
          </div>
          <div v-else class="no-data">暂无策略数据</div>
        </div>
      </div>

      <!-- 分组统计 -->
      <div class="panel-card st-card">
        <div class="card-title">分组统计</div>
        <div class="bar-chart" v-if="groupData.length > 0">
          <div class="bar-row" v-for="item in groupData" :key="item.key">
            <div class="bar-label">{{ item.name }}</div>
            <div class="bar-track">
              <div class="bar-fill" :style="{ width: item.percent + '%', background: chartPrimary }"></div>
            </div>
            <div class="bar-value">{{ formatBytes(item.size) }}</div>
          </div>
        </div>
        <div v-else class="no-data">暂无分组数据</div>
      </div>

      <!-- 详细数据表格 -->
      <div class="panel-card st-card">
        <div class="card-title">
          <span>详细数据</span>
          <PillTabs v-model="currentTab" :tabs="tabs" />
        </div>
        <div class="st-table-wrapper" v-if="currentData.length > 0">
          <table class="st-table data-table">
            <thead>
              <tr>
                <th class="col-name">名称</th>
                <th class="col-count">文件数</th>
                <th class="col-size">存储大小</th>
                <th class="col-percent">占比</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in currentData" :key="item.key">
                <td class="col-name">
                  <span class="item-icon">{{ getIcon(item.icon) }}</span>
                  <span class="table-name" :title="item.name">{{ item.name }}</span>
                </td>
                <td>{{ item.count.toLocaleString() }}</td>
                <td>{{ formatBytes(item.size) }}</td>
                <td>
                  <div class="percent-cell">
                    <div class="percent-bar">
                      <div class="percent-fill" :style="{ width: item.percent + '%', background: chartPrimary }"></div>
                    </div>
                    <span class="percent-text">{{ item.percent }} %</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="no-data">暂无数据</div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, shallowRef, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { VAlert, VButton, VEmpty, VLoading, IconDatabase2Line, IconDashboard, IconListSettings, IconFolder } from '@halo-dev/components'
import type { StatisticsData } from '@/types/statistics'
import { API_ENDPOINTS } from '@/constants/api'
import { CHART_TYPE_COLORS, CHART_PALETTE, paletteColor } from '@/constants/colors'
import StatCard from '@/components/StatCard.vue'
import PillTabs from '@/components/PillTabs.vue'
import echarts from '@/echarts'

// 状态
const loading = ref(false)
const error = ref<string | null>(null)
const statisticsData = ref<StatisticsData | null>(null)

// ECharts 实例（使用 shallowRef 避免组件重新挂载时的问题）
const typeChartRef = ref<HTMLElement | null>(null)
const policyChartRef = ref<HTMLElement | null>(null)
const typeChart = shallowRef<echarts.ECharts | null>(null)
const policyChart = shallowRef<echarts.ECharts | null>(null)

// Tab 配置
const tabs = [
  { id: 'type', label: '按类型' },
  { id: 'policy', label: '按策略' },
  { id: 'group', label: '按分组' }
]
const currentTab = ref('type')

// 条形图 / 占比条填充色（数据系主色）
const chartPrimary = CHART_PALETTE[0]

// 图标映射
const iconMap: Record<string, string> = {
  image: '🖼️',
  video: '🎬',
  audio: '🎵',
  document: '📄',
  file: '📦',
  storage: '💾',
  folder: '📁',
  cloud: '☁️'
}

// 计算属性
const isEmpty = computed(() => {
  return statisticsData.value?.total.attachmentCount === 0
})

const totalSize = computed(() => statisticsData.value?.total.totalSize ?? 0)
const totalCount = computed(() => statisticsData.value?.total.attachmentCount ?? 0)
const policyCount = computed(() => statisticsData.value?.total.policyCount ?? 0)
const groupCount = computed(() => statisticsData.value?.total.groupCount ?? 0)

const typeData = computed(() => statisticsData.value?.byType ?? [])
const policyData = computed(() => statisticsData.value?.byPolicy ?? [])
const groupData = computed(() => statisticsData.value?.byGroup ?? [])

// 添加颜色的数据
const typeDataWithColor = computed(() => {
  return typeData.value.map(item => ({
    ...item,
    color: CHART_TYPE_COLORS[item.key] || CHART_TYPE_COLORS.other
  }))
})

const policyDataWithColor = computed(() => {
  // 饼图只显示 Top 5
  return policyData.value.slice(0, 5).map((item, index) => ({
    ...item,
    color: paletteColor(index)
  }))
})

// 详细列表显示全部
const policyDataAllWithColor = computed(() => {
  return policyData.value.map((item, index) => ({
    ...item,
    color: paletteColor(index)
  }))
})

const groupDataWithColor = computed(() => {
  return groupData.value.map((item, index) => ({
    ...item,
    color: paletteColor(index)
  }))
})

const currentData = computed(() => {
  switch (currentTab.value) {
    case 'policy':
      return policyDataAllWithColor.value
    case 'group':
      return groupDataWithColor.value
    default:
      return typeDataWithColor.value
  }
})

// ECharts 饼图配置
const createPieOption = (data: Array<{ name: string; count: number; size: number; percent: number; color: string }>) => {
  return {
    tooltip: {
      trigger: 'item',
      renderMode: 'html',
      formatter: (params: { data: { name: string; count: number; size: number; percent: number } }) => {
        const d = params.data
        return `${d.name}<br/>文件数: ${d.count.toLocaleString()}<br/>大小: ${formatBytes(d.size)}<br/>占比: ${d.percent} %`
      }
    },
    series: [{
      type: 'pie',
      radius: ['50%', '80%'],
      center: ['50%', '50%'],
      avoidLabelOverlap: false,
      label: { show: false },
      emphasis: {
        label: { show: false }
      },
      labelLine: { show: false },
      data: data.map(item => ({
        name: item.name,
        value: item.size,
        count: item.count,
        size: item.size,
        percent: item.percent,
        itemStyle: { color: item.color }
      }))
    }]
  }
}

// 初始化图表
const initCharts = () => {
  // 创建新实例（如果不存在）
  if (!typeChart.value && typeChartRef.value) {
    typeChart.value = echarts.init(typeChartRef.value)
  }
  if (!policyChart.value && policyChartRef.value) {
    policyChart.value = echarts.init(policyChartRef.value)
  }
  updateCharts()
}

// 更新图表（使用 setOption 而非销毁重建，避免闪烁）
const updateCharts = () => {
  if (typeChart.value && typeDataWithColor.value.length > 0) {
    typeChart.value.setOption(createPieOption(typeDataWithColor.value), true)
  }
  if (policyChart.value && policyDataWithColor.value.length > 0) {
    policyChart.value.setOption(createPieOption(policyDataWithColor.value), true)
  }
}

// 监听数据变化
watch(() => statisticsData.value, () => {
  nextTick(() => {
    initCharts()
  })
})

// 方法
const getIcon = (iconKey: string): string => {
  return iconMap[iconKey] || '📦'
}

const formatBytes = (bytes: number): string => {
  if (!bytes) return '0 B'
  const u = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0, s = bytes
  while (s >= 1024 && i < u.length - 1) { s /= 1024; i++ }
  return s.toFixed(i > 0 ? 1 : 0) + ' ' + u[i]
}

const fetchStatistics = async () => {
  loading.value = true
  error.value = null

  try {
    const { data } = await axiosInstance.get<StatisticsData>(API_ENDPOINTS.STATISTICS)
    statisticsData.value = data
  } catch (e) {
    error.value = e instanceof Error ? e.message : '获取统计数据失败'
    console.error('Failed to fetch statistics:', e)
  } finally {
    loading.value = false
  }
}

// resize 处理
const handleResize = () => {
  typeChart.value?.resize()
  policyChart.value?.resize()
}

// 生命周期
onMounted(() => {
  fetchStatistics()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  typeChart.value?.dispose()
  policyChart.value?.dispose()
})
</script>


<style scoped>
.statistics-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 无数据提示（卡片内小范围占位） */
.no-data {
  padding: 40px 20px;
  text-align: center;
  color: var(--st-text-tertiary);
  font-size: 14px;
}

/* 顶部统计 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

/* 图表区域 */
.charts-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

/* 卡片基础样式来自 .st-card，此处仅保留内边距 */
.panel-card {
  padding: 20px;
}

.card-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 15px;
  font-weight: 600;
  color: var(--st-text-primary);
  margin-bottom: 16px;
}

.title-tag {
  display: inline-block;
  margin-left: 8px;
  padding: 2px 6px;
  font-size: 11px;
  font-weight: 500;
  color: var(--st-tag-amber-text);
  background: var(--st-tag-amber-bg);
  border-radius: var(--st-radius-sm);
  vertical-align: middle;
}

/* 饼图区域 */
.pie-chart-area {
  display: flex;
  align-items: center;
  gap: 24px;
}

.pie-chart {
  width: 140px;
  height: 140px;
  flex-shrink: 0;
}

.pie-chart svg {
  width: 100%;
  height: 100%;
}

.pie-legend {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  flex-shrink: 0;
}

.legend-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--st-text-secondary);
}

.legend-value {
  color: var(--st-text-secondary);
  font-weight: 500;
}

/* 条形图 */
.bar-chart {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.bar-row {
  display: grid;
  grid-template-columns: 100px 1fr 80px;
  align-items: center;
  gap: 12px;
}

.bar-label {
  font-size: 13px;
  color: var(--st-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bar-track {
  height: 20px;
  background: var(--st-bg-muted);
  border-radius: var(--st-radius-sm);
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  border-radius: var(--st-radius-sm);
  transition: width var(--st-duration) var(--st-ease);
}

.bar-value {
  font-size: 13px;
  color: var(--st-text-secondary);
  text-align: right;
}

/* 表格：列宽等本地细节（基础样式来自 .st-table） */
.data-table {
  min-width: 480px;
  table-layout: fixed;
}

.data-table td {
  white-space: nowrap;
}

.item-icon {
  flex-shrink: 0;
}

.col-name {
  display: flex;
  align-items: center;
  gap: 8px;
}

.table-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}

.col-count { width: 20%; }
.col-size { width: 25%; }
.col-percent { width: 25%; }

.percent-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.percent-bar {
  flex: 1;
  max-width: 100px;
  height: 6px;
  background: var(--st-bg-muted);
  border-radius: 3px;
  overflow: hidden;
}

.percent-fill {
  height: 100%;
  border-radius: 3px;
}

.percent-text {
  font-size: 13px;
  color: var(--st-text-secondary);
  min-width: 40px;
}

/* 响应式 */
@media (max-width: 768px) {
  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .charts-grid {
    grid-template-columns: 1fr;
  }
  .pie-chart-area {
    flex-direction: column;
  }
  .bar-row {
    grid-template-columns: 80px 1fr 70px;
  }
}

@media (max-width: 640px) {
  .stats-grid {
    grid-template-columns: 1fr;
  }
  .data-table th,
  .data-table td {
    padding: 10px 12px;
  }
}
</style>
