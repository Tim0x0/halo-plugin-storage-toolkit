import { onMounted, onUnmounted, ref } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { Toast } from '@halo-dev/components'
import { API_ENDPOINTS } from '@/constants/api'
import type { EnvironmentContext, EnvironmentStatus } from './context'

export function useEnvironmentController(): EnvironmentContext {
  const status = ref<EnvironmentStatus | null>(null)
  const loading = ref(false)
  const installing = ref<string | null>(null)
  const error = ref('')
  let timer: ReturnType<typeof setTimeout> | undefined
  let disposed = false

  function message(cause: unknown) {
    const e = cause as { response?: { data?: { detail?: string; message?: string } } }
    return e.response?.data?.detail || e.response?.data?.message || '环境信息获取失败，请重试'
  }
  function schedule(data: EnvironmentStatus) {
    clearTimeout(timer)
    const active = data.dependencies.find(item => item.state === 'INSTALLING')
    installing.value = active?.id || null
    if (!disposed && active) {
      timer = setTimeout(load, 1500)
    }
  }
  async function load() {
    try {
      const { data } = await axiosInstance.get<EnvironmentStatus>(API_ENDPOINTS.ENVIRONMENT)
      status.value = data
      error.value = ''
      schedule(data)
    } catch (cause) { error.value = message(cause) }
  }
  async function check() {
    loading.value = true
    try {
      const { data } = await axiosInstance.post<EnvironmentStatus>(API_ENDPOINTS.ENVIRONMENT_CHECK)
      status.value = data
      error.value = ''
      schedule(data)
    } catch (cause) { error.value = message(cause) }
    finally { loading.value = false }
  }
  async function install(id: string) {
    if (installing.value) return
    installing.value = id
    error.value = ''
    try {
      const { data } = await axiosInstance.post<EnvironmentStatus>(API_ENDPOINTS.ENVIRONMENT_INSTALL(id))
      status.value = data
      schedule(data)
      const dependency = data.dependencies.find(item => item.id === id)
      if (dependency?.state === 'READY') Toast.success(`${dependency.label}已就绪`)
      else if (dependency?.state === 'RESTART_REQUIRED') Toast.warning('依赖已安装，请按提示重启 Halo')
      else if (dependency?.state === 'DOWNLOAD_FAILED') Toast.error('下载失败，请查看原因后重试')
    } catch (cause) {
      error.value = message(cause)
      await load()
    }
  }
  onMounted(async () => {
    loading.value = true
    await load()
    loading.value = false
  })
  onUnmounted(() => { disposed = true; clearTimeout(timer) })
  return { status, loading, installing, error, load, check, install }
}
