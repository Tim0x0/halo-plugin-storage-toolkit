import { inject, type InjectionKey, type Ref } from 'vue'

export interface DependencyStatus {
  id: string
  label: string
  version: string
  state: string
  source: string
  message: string
  canDownload: boolean
  downloadBytes: number
}
export interface EnvironmentStatus {
  platform: {
    os: string
    architecture: string
    libc: string
    distribution: string
    deployment: string
    deploymentEvidence: string
    javaVersion: string
  }
  storageReady: boolean
  storageMessage: string | null
  checkedAt: string | null
  dependencies: DependencyStatus[]
}
export interface EnvironmentContext {
  status: Ref<EnvironmentStatus | null>
  loading: Ref<boolean>
  installing: Ref<string | null>
  error: Ref<string>
  load: () => Promise<void>
  check: () => Promise<void>
  install: (id: string) => Promise<void>
}
export const environmentKey: InjectionKey<EnvironmentContext> = Symbol('storage-toolkit-environment')
export function useEnvironment() {
  const context = inject(environmentKey)
  if (!context) throw new Error('Environment context is unavailable')
  return context
}
