/**
 * 数据可视化调色板（ECharts 饼图 / CSS 条形图共用）
 *
 * 注意：这是图表数据系色，不与 tokens.css 的语义状态色混用——
 * 图表里的红/绿代表数据分类，不代表成功/失败语义。
 */

/** 附件类型 → 颜色 */
export const CHART_TYPE_COLORS: Record<string, string> = {
  image: '#3b82f6',
  video: '#8b5cf6',
  audio: '#ec4899',
  document: '#10b981',
  other: '#a1a1aa'
}

/** 循环取色调色板（策略/分组等无固定类目的系列） */
export const CHART_PALETTE = ['#3b82f6', '#f59e0b', '#10b981', '#ef4444', '#8b5cf6', '#06b6d4']

/** 按索引循环取调色板颜色 */
export function paletteColor(index: number): string {
  return CHART_PALETTE[index % CHART_PALETTE.length]
}
