<template>
  <div class="statistics-panel">
    <!-- 加载状态 -->
    <div v-if="loading" class="loading-state">
      <div class="loading-spinner"></div>
      <span>加载中...</span>
    </div>

    <!-- 错误状态 -->
    <div v-else-if="error" class="error-state">
      <div class="error-icon">⚠️</div>
      <div class="error-message">{{ error }}</div>
      <button class="retry-btn" @click="fetchStatistics">重试</button>
    </div>

    <!-- 空状态 -->
    <div v-else-if="isEmpty" class="empty-state">
      <div class="empty-icon">📭</div>
      <div class="empty-message">暂无附件数据</div>
      <div class="empty-hint">上传附件后将在此显示统计信息</div>
    </div>

    <!-- 正常内容 -->
    <template v-else>
      <!-- 顶部统计 -->
      <div class="stats-grid">
        <div class="stat-item">
          <div class="stat-value">{{ formatBytes(totalSize) }}</div>
          <div class="stat-label">总存储空间</div>
        </div>
        <div class="stat-item">
          <div class="stat-value">{{ totalCount.toLocaleString() }}</div>
          <div class="stat-label">附件总数</div>
        </div>
        <div class="stat-item">
          <div class="stat-value">{{ policyCount }}</div>
          <div class="stat-label">存储策略</div>
        </div>
        <div class="stat-item">
          <div class="stat-value">{{ groupCount }}</div>
          <div class="stat-label">分组数量</div>
        </div>
      </div>

      <!-- 图表区域 -->
      <div class="charts-grid">
        <!-- 类型分布 -->
        <div class="panel-card">
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
        <div class="panel-card">
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
      <div class="panel-card">
        <div class="card-title">分组统计</div>
        <div class="bar-chart" v-if="groupData.length > 0">
          <div class="bar-row" v-for="item in groupData" :key="item.key">
            <div class="bar-label">{{ item.name }}</div>
            <div class="bar-track">
              <div class="bar-fill" :style="{ width: item.percent + '%' }"></div>
            </div>
            <div class="bar-value">{{ formatBytes(item.size) }}</div>
          </div>
        </div>
        <div v-else class="no-data">暂无分组数据</div>
      </div>

      <!-- 详细数据表格 -->
      <div class="panel-card">
        <div class="card-title">
          <span>详细数据</span>
          <div class="tab-btns">
            <button 
              v-for="tab in tabs" 
              :key="tab.id"
              :class="['tab-btn', { active: currentTab === tab.id }]"
              @click="switchTab(tab.id)"
            >{{ tab.label }}</button>
          </div>
        </div>
        <div class="table-wrapper" v-if="currentData.length > 0">
        <table class="data-table">
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
                    <div class="percent-fill" :style="{ width: item.percent + '%' }"></div>
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
import type { StatisticsData, CategoryStats } from '@/types/statistics'
import { API_ENDPOINTS } from '@/constants/api'
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

// 颜色配置
const typeColors: Record<string, string> = {
  image: '#3b82f6',
  video: '#8b5cf6',
  audio: '#ec4899',
  document: '#10b981',
  other: '#6b7280'
}

const policyColors = ['#3b82f6', '#f59e0b', '#10b981', '#ef4444', '#8b5cf6', '#06b6d4']

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
    color: typeColors[item.key] || '#6b7280'
  }))
})

const policyDataWithColor = computed(() => {
  // 饼图只显示 Top 5
  return policyData.value.slice(0, 5).map((item, index) => ({
    ...item,
    color: policyColors[index % policyColors.length]
  }))
})

// 详细列表显示全部
const policyDataAllWithColor = computed(() => {
  return policyData.value.map((item, index) => ({
    ...item,
    color: policyColors[index % policyColors.length]
  }))
})

const groupDataWithColor = computed(() => {
  return groupData.value.map((item, index) => ({
    ...item,
    color: policyColors[index % policyColors.length]
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
      formatter: (params: any) => {
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
const switchTab = (tabId: string) => {
  currentTab.value = tabId
}

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

/* 加载状态 */
.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: #6b7280;
}

.loading-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid #e5e7eb;
  border-top-color: #3b82f6;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin-bottom: 12px;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 错误状态 */
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  background: #fff;
  border-radius: 8px;
}

.error-icon {
  font-size: 48px;
  margin-bottom: 16px;
}

.error-message {
  font-size: 14px;
  color: #ef4444;
  margin-bottom: 16px;
}

.retry-btn {
  padding: 8px 20px;
  font-size: 14px;
  color: #fff;
  background: #3b82f6;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}

.retry-btn:hover {
  background: #2563eb;
}

/* 空状态 */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  background: #fff;
  border-radius: 8px;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 16px;
}

.empty-message {
  font-size: 16px;
  font-weight: 500;
  color: #374151;
  margin-bottom: 8px;
}

.empty-hint {
  font-size: 14px;
  color: #9ca3af;
}

/* 无数据提示 */
.no-data {
  padding: 40px 20px;
  text-align: center;
  color: #9ca3af;
  font-size: 14px;
}

/* 顶部统计 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.stat-item {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 16px 20px;
  text-align: center;
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  color: #111;
}

.stat-label {
  font-size: 13px;
  color: #666;
  margin-top: 4px;
}

/* 图表区域 */
.charts-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.panel-card {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 20px;
}

.card-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 15px;
  font-weight: 600;
  color: #111;
  margin-bottom: 16px;
}

.title-tag {
  display: inline-block;
  margin-left: 8px;
  padding: 2px 6px;
  font-size: 11px;
  font-weight: 500;
  color: #92400e;
  background: #fef3c7;
  border-radius: 4px;
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
  color: #374151;
}

.legend-value {
  color: #6b7280;
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
  color: #374151;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bar-track {
  height: 20px;
  background: #f3f4f6;
  border-radius: 4px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  background: linear-gradient(90deg, #3b82f6, #60a5fa);
  border-radius: 4px;
  transition: width 0.3s;
}

.bar-value {
  font-size: 13px;
  color: #6b7280;
  text-align: right;
}

/* Tab 切换 */
.tab-btns {
  display: flex;
  gap: 4px;
  background: #f3f4f6;
  padding: 3px;
  border-radius: 6px;
}

.tab-btn {
  padding: 5px 12px;
  font-size: 13px;
  color: #6b7280;
  background: transparent;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.15s ease;
  user-select: none;
}

.tab-btn:hover:not(.active) {
  color: #374151;
  background: rgba(255,255,255,0.5);
}

.tab-btn:active {
  transform: scale(0.98);
}

.tab-btn.active {
  color: #111;
  background: #fff;
  box-shadow: 0 1px 2px rgba(0,0,0,0.05);
}

/* 表格 */
.data-table {
  width: 100%;
  min-width: 480px;
  table-layout: fixed;
  border-collapse: collapse;
}

.table-wrapper {
  overflow-x: auto;
}

.data-table th,
.data-table td {
  padding: 12px 16px;
  text-align: left;
  border-bottom: 1px solid #f3f4f6;
}

.data-table th {
  font-size: 12px;
  font-weight: 500;
  color: #6b7280;
  background: #fafafa;
}

.data-table td {
  font-size: 14px;
  color: #18181b;
  white-space: nowrap;
}

.data-table tbody tr:hover {
  background: #fafafa;
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
  background: #f3f4f6;
  border-radius: 3px;
  overflow: hidden;
}

.percent-fill {
  height: 100%;
  background: #3b82f6;
  border-radius: 3px;
}

.percent-text {
  font-size: 13px;
  color: #6b7280;
  min-width: 40px;
}

/* 响应式 */
@media (max-width: 1024px) {
  .stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 768px) {
  .charts-grid {
    grid-template-columns: 1fr;
  }
  .pie-chart-area {
    flex-direction: column;
  }
  .bar-row {
    grid-template-columns: 80px 1fr 70px;
  }
  .stat-value {
    font-size: 20px;
  }
}

@media (max-width: 640px) {
  .stats-grid {
    grid-template-columns: 1fr;
  }
  .stat-value {
    font-size: 18px;
  }
  .data-table th,
  .data-table td {
    padding: 10px 12px;
  }
}
</style>
