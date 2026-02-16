/**
 * 格式化工具函数
 */

/**
 * 格式化字节大小
 * @param bytes 字节数
 * @returns 格式化后的字符串，如 "1.5 MB"
 */
export function formatBytes(bytes: number | undefined | null): string {
  if (bytes === undefined || bytes === null || bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0, size = bytes
  while (size >= 1024 && i < 3) { size /= 1024; i++ }
  return `${size.toFixed(1)} ${units[i]}`
}

/**
 * 格式化时间
 * @param time ISO 时间字符串
 * @returns 本地化时间字符串
 */
export function formatTime(time: string | null | undefined): string {
  if (!time) return '-'
  return new Date(time).toLocaleString('zh-CN')
}
