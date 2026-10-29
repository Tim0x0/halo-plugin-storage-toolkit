export interface TaskStatus {
  taskId: string | null
  parentTaskId: string | null
  phase: string | null
  progress: { total: number; processed: number; succeeded: number; failed: number } | null
  failedItems:
    | { attachmentName: string; displayName: string; error: string; retryable: boolean }[]
    | null
  skippedItems: { attachmentName: string; displayName: string; reason: string }[] | null
  reviewAttachmentNames: string[]
  pendingCount: number
  skippedCount: number
  savedBytes: number
  startTime: string | null
  endTime: string | null
  errorMessage: string | null
}
