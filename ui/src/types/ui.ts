import type { Component } from 'vue'

/** StatCard 数值着色 */
export type StatTone = 'default' | 'success' | 'warning' | 'error' | 'info'

/** StatusBadge 语义色 */
export type BadgeTone = 'success' | 'error' | 'warning' | 'info' | 'neutral'

/** StProgressBar 填充色 */
export type ProgressTone = 'primary' | 'success' | 'error' | 'warning'

/** PillTabs 单个 tab 配置 */
export interface PillTabItem {
  id: string
  label: string
  icon?: Component
  count?: number
}
