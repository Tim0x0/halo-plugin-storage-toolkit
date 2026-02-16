/**
 * API 路径常量
 */
const API_PREFIX = '/apis/console.api.storage-toolkit.timxs.com/v1alpha1'

export const API_ENDPOINTS = {
  // 统计
  STATISTICS: `${API_PREFIX}/statistics`,

  // 白名单
  WHITELIST: `${API_PREFIX}/whitelist`,
  WHITELIST_SEARCH: `${API_PREFIX}/whitelist/search`,
  WHITELIST_BATCH: `${API_PREFIX}/whitelist/batch`,
  WHITELIST_CLEAR_ALL: `${API_PREFIX}/whitelist/all`,

  // 断链
  BROKEN_LINKS: `${API_PREFIX}/brokenlinks`,
  BROKEN_LINKS_SCAN: `${API_PREFIX}/brokenlinks/scan`,
  BROKEN_LINKS_STATUS: `${API_PREFIX}/brokenlinks/status`,
  BROKEN_LINKS_REPLACE: `${API_PREFIX}/brokenlinks/replace`,

  // 处理日志
  PROCESSING_LOGS: `${API_PREFIX}/processinglogs`,
  PROCESSING_LOGS_STATS: `${API_PREFIX}/processinglogs/stats`,

  // URL 替换日志
  URL_REPLACE_LOGS: `${API_PREFIX}/urlreplacelogs`,
  URL_REPLACE_LOGS_STATS: `${API_PREFIX}/urlreplacelogs/stats`,

  // 清理日志
  CLEANUP_LOGS: `${API_PREFIX}/cleanuplogs`,
  CLEANUP_LOGS_STATS: `${API_PREFIX}/cleanuplogs/stats`,

  // 引用统计
  REFERENCES: `${API_PREFIX}/references`,
  REFERENCES_SCAN: `${API_PREFIX}/references/scan`,
  REFERENCES_STATS: `${API_PREFIX}/references/stats`,
  REFERENCES_CLEAR: `${API_PREFIX}/references/clear`,
  REFERENCES_POLICY: (policyName: string) => `${API_PREFIX}/references/policy/${policyName}`,
  REFERENCES_GROUP: (groupName: string) => `${API_PREFIX}/references/group/${groupName}`,
  REFERENCES_SUBJECT: (kind: string, name: string) => `${API_PREFIX}/references/subject/${kind}/${name}`,
  REFERENCES_SOURCE: (attachmentName: string, sourceName: string) => `${API_PREFIX}/references/${attachmentName}/source/${sourceName}`,
  REFERENCES_SETTING_GROUP_LABEL: (settingName: string, groupKey: string) => `${API_PREFIX}/references/settings/${settingName}/groups/${groupKey}/label`,

  // 重复检测
  DUPLICATES: `${API_PREFIX}/duplicates`,
  DUPLICATES_SCAN: `${API_PREFIX}/duplicates/scan`,
  DUPLICATES_STATS: `${API_PREFIX}/duplicates/stats`,
  DUPLICATES_CLEAR: `${API_PREFIX}/duplicates/clear`,

  // 批量处理
  BATCH_PROCESSING_TASKS: `${API_PREFIX}/batchprocessing/tasks`,
  BATCH_PROCESSING_STATUS: `${API_PREFIX}/batchprocessing/status`,
  BATCH_PROCESSING_SETTINGS: `${API_PREFIX}/batchprocessing/settings`,
  BATCH_PROCESSING_CANCEL: `${API_PREFIX}/batchprocessing/tasks/current`,

  // 清理操作
  CLEANUP_DUPLICATES: (md5Hash: string) => `${API_PREFIX}/cleanup/duplicates/${md5Hash}`,
  CLEANUP_UNREFERENCED: `${API_PREFIX}/cleanup/unreferenced`,
} as const
