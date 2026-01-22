/**
 * 重复检测相关类型定义
 */

/**
 * 扫描状态
 */
export interface DuplicateStats {
  /** 扫描阶段 */
  phase: 'scanning' | 'completed' | 'error' | null
  /** 上次扫描时间 */
  lastScanTime: string | null
  /** 扫描开始时间 */
  startTime: string | null
  /** 总附件数 */
  totalCount: number
  /** 已扫描数 */
  scannedCount: number
  /** 重复组数 */
  duplicateGroupCount: number
  /** 重复文件数 */
  duplicateFileCount: number
  /** 可节省空间（字节） */
  savableSize: number
  /** 错误信息 */
  errorMessage: string | null
  /** 是否启用远程存储扫描 */
  enableRemoteStorage?: boolean
}

/**
 * 重复文件信息
 */
export interface DuplicateFile {
  /** 附件名称 */
  attachmentName: string
  /** 显示名称 */
  displayName: string
  /** 媒体类型 */
  mediaType: string | null
  /** 永久链接 */
  permalink: string | null
  /** 上传时间 */
  uploadTime: string | null
  /** 分组名称 */
  groupName: string | null
  /** 分组显示名称 */
  groupDisplayName: string | null
  /** 存储策略名称 */
  policyName: string | null
  /** 存储策略显示名称 */
  policyDisplayName: string | null
  /** 引用次数 */
  referenceCount: number
  /** 是否推荐保留 */
  isRecommended: boolean
}

/**
 * 重复组
 */
export interface DuplicateGroup {
  /** MD5 哈希值 */
  md5Hash: string
  /** 文件大小（字节） */
  fileSize: number
  /** 组内文件数量 */
  fileCount: number
  /** 可节省空间（字节） */
  savableSize: number
  /** 推荐保留的附件名称 */
  recommendedKeep: string | null
  /** 预览 URL */
  previewUrl: string | null
  /** 媒体类型 */
  mediaType: string | null
  /** 组内文件列表 */
  files: DuplicateFile[]
}

/**
 * 分页结果
 */
export interface ListResult<T> {
  page: number
  size: number
  total: number
  items: T[]
}
