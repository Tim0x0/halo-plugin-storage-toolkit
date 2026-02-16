/**
 * 引用源类型图标
 */
export const SOURCE_TYPE_ICONS: Record<string, string> = {
  'Post': '📝',
  'SinglePage': '📄',
  'Comment': '💬',
  'Reply': '🗨️',
  'Moment': '📸',
  'Photo': '🖼️',
  'Doc': '📚',
  'SystemSetting': '⚙️',
  'PluginSetting': '🔌',
  'ThemeSetting': '🎨',
  'User': '👤'
}

/**
 * 引用源类型标签
 */
export const SOURCE_TYPE_LABELS: Record<string, string> = {
  'Post': '文章',
  'SinglePage': '页面',
  'Comment': '评论',
  'Reply': '回复',
  'Moment': '瞬间',
  'Photo': '图库',
  'Doc': '文档',
  'SystemSetting': '系统设置',
  'PluginSetting': '插件设置',
  'ThemeSetting': '主题设置',
  'User': '用户'
}

/**
 * 引用源类型样式类
 */
export const SOURCE_TYPE_CLASSES: Record<string, string> = {
  'Post': 'tag-blue',
  'SinglePage': 'tag-blue',
  'Comment': 'tag-pink',
  'Reply': 'tag-pink',
  'Moment': 'tag-orange',
  'Photo': 'tag-orange',
  'Doc': 'tag-indigo',
  'SystemSetting': 'tag-purple',
  'PluginSetting': 'tag-purple',
  'ThemeSetting': 'tag-purple',
  'User': 'tag-amber'
}

/**
 * 获取引用源图标
 */
export function getSourceTypeIcon(type: string): string {
  return SOURCE_TYPE_ICONS[type] || '📦'
}

/**
 * 获取引用源标签
 */
export function getSourceTypeLabel(type: string): string {
  return SOURCE_TYPE_LABELS[type] || type
}

/**
 * 获取引用源样式类
 */
export function getSourceTypeClass(type: string): string {
  return SOURCE_TYPE_CLASSES[type] || ''
}

/**
 * 获取文件图标
 */
export function getFileIcon(mediaType: string | null): string {
  if (!mediaType) return '📄'
  if (mediaType.startsWith('image/')) return '🖼️'
  if (mediaType.startsWith('video/')) return '🎬'
  if (mediaType.startsWith('audio/')) return '🎵'
  if (mediaType.includes('pdf')) return '📕'
  if (mediaType.includes('zip') || mediaType.includes('rar')) return '📦'
  return '📄'
}

/**
 * 判断是否为图片类型
 */
export function isImage(mediaType: string | null): boolean {
  return mediaType?.startsWith('image/') ?? false
}

/**
 * 引用源类型排序优先级
 */
const SOURCE_TYPE_ORDER: Record<string, number> = {
  'Post': 0,
  'SinglePage': 1,
  'Comment': 2,
  'Reply': 3,
  'Moment': 4,
  'Photo': 5,
  'Doc': 6,
  'SystemSetting': 7,
  'PluginSetting': 8,
  'ThemeSetting': 9,
  'User': 10
}

/**
 * 按来源类型排序
 */
export function sortBySourceType<T extends { sourceType: string }>(sources: T[] | undefined | null): T[] {
  if (!sources || sources.length === 0) return []
  return [...sources].sort((a, b) => {
    const orderA = SOURCE_TYPE_ORDER[a.sourceType] ?? 99
    const orderB = SOURCE_TYPE_ORDER[b.sourceType] ?? 99
    return orderA - orderB
  })
}

/**
 * 获取唯一的引用源类型列表
 */
export function getUniqueSourceTypes<T extends { sourceType: string }>(sources: T[]): string[] {
  if (!sources) return []
  return [...new Set(sources.map(s => s.sourceType))]
}

