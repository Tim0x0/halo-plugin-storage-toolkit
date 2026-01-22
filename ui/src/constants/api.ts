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
  WHITELIST_CHECK: `${API_PREFIX}/whitelist/check`,
  WHITELIST_CLEAR_ALL: `${API_PREFIX}/whitelist/all`,

  // 断链
  BROKEN_LINKS: `${API_PREFIX}/broken-links`,
  BROKEN_LINKS_SCAN: `${API_PREFIX}/broken-links/scan`,
  BROKEN_LINKS_STATUS: `${API_PREFIX}/broken-links/status`,
  BROKEN_LINKS_SOURCE_TYPES: `${API_PREFIX}/broken-links/source-types`,
  BROKEN_LINKS_WHITELIST: `${API_PREFIX}/broken-links/whitelist`,

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
  BATCH_PROCESSING_TASKS: `${API_PREFIX}/batch-processing/tasks`,
  BATCH_PROCESSING_STATUS: `${API_PREFIX}/batch-processing/status`,
  BATCH_PROCESSING_SETTINGS: `${API_PREFIX}/batch-processing/settings`,
  BATCH_PROCESSING_CANCEL: `${API_PREFIX}/batch-processing/tasks/current`,

  // 清理
  CLEANUP: `${API_PREFIX}/cleanup`,
  CLEANUP_DUPLICATES: (md5Hash: string) => `${API_PREFIX}/cleanup/duplicates/${md5Hash}`,
  CLEANUP_UNREFERENCED: `${API_PREFIX}/cleanup/unreferenced`,
  CLEANUP_LOGS: `${API_PREFIX}/cleanup/logs`,
  CLEANUP_LOGS_STATS: `${API_PREFIX}/cleanup/logs/stats`,
  CLEANUP_PREVIEW: `${API_PREFIX}/cleanup/preview`,
} as const
