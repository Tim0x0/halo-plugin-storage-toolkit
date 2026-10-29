<template>
  <div class="environment-panel">
    <header class="st-section-heading">
      <div>
        <h1>环境检测</h1>
        <p>检查 Halo 的运行环境与图片处理、日志存储依赖。</p>
      </div>
      <VButton :loading="loading" :disabled="!!installing" @click="check">重新检测</VButton>
    </header>
    <VAlert v-if="error" type="error" title="环境检测失败" :description="error" />
    <VLoading v-if="!status && loading" />
    <template v-else-if="status">
      <section class="environment-facts st-card" aria-label="运行环境">
        <div><span>系统</span><strong>{{ status.platform.distribution }}</strong></div>
        <div><span>架构</span><strong>{{ status.platform.architecture }}</strong></div>
        <div><span>运行方式</span><strong>{{ status.platform.deployment === 'CONTAINER' ? '容器环境' : '待确认' }}</strong></div>
        <div><span>Java</span><strong>{{ status.platform.javaVersion }}</strong></div>
        <p>{{ status.platform.deploymentEvidence }}。依赖以 Halo 所在环境为准。</p>
      </section>
      <div class="environment-summary" role="status">
        <span :class="['environment-dot', { ready: allReady }]" />
        <span>{{ allReady ? '所有依赖均已就绪' : '部分功能需要处理依赖后使用' }}</span>
        <span v-if="status.checkedAt" class="environment-time">上次检测 {{ formatTime(status.checkedAt) }}</span>
      </div>
      <section class="dependency-list st-card" aria-label="功能依赖">
        <article v-for="dependency in status.dependencies" :key="dependency.id" class="dependency-row">
          <div class="dependency-symbol" aria-hidden="true">{{ symbols[dependency.id] }}</div>
          <div class="dependency-content">
            <div class="dependency-title">
              <h2>{{ dependency.label }}</h2>
              <span :class="['dependency-state', { ready: dependency.state === 'READY' }]">{{ labels[dependency.state] || dependency.state }}</span>
            </div>
            <p>{{ dependency.message }}</p>
            <details>
              <summary>依赖信息</summary>
              <dl>
                <div><dt>版本</dt><dd>{{ dependency.version }}</dd></div>
                <div><dt>来源</dt><dd>{{ dependency.source === 'BUNDLED' ? '安装包内置' : dependency.source === 'DOWNLOADED' ? '已下载' : '尚未加载' }}</dd></div>
                <div><dt>运行平台</dt><dd>{{ status.platform.os }} / {{ status.platform.architecture }}{{ status.platform.libc !== 'none' ? ` / ${status.platform.libc}` : '' }}</dd></div>
              </dl>
            </details>
          </div>
          <div class="dependency-action">
            <VButton v-if="dependency.canDownload || installing === dependency.id" type="secondary"
              :loading="installing === dependency.id" :disabled="!!installing || loading"
              @click="install(dependency.id)">下载并激活</VButton>
            <span v-if="dependency.canDownload" class="dependency-size">首次下载约 {{ formatBytes(dependency.downloadBytes) }}</span>
          </div>
        </article>
      </section>
      <aside class="environment-note">
        <h2>安装后如何生效</h2>
        <p>首次补齐依赖会自动尝试激活并自检。若提示需要重启，请重启 Halo；Docker 部署重启容器，JAR 部署重启 Java 服务。</p>
        <p>下载内容会校验版本和完整性，并保存在 Halo 数据目录中。Docker 部署请保持数据目录持久化，重建容器后可继续使用。</p>
      </aside>
    </template>
    <div v-else class="environment-note">暂未获取运行环境，请点击“重新检测”。</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { VAlert, VButton, VLoading } from '@halo-dev/components'
import { useEnvironment } from '@/environment/context'
import { formatBytes, formatTime } from '@/utils/format'
const { status, loading, installing, error, check, install } = useEnvironment()
const allReady = computed(() => status.value?.dependencies.every(dependency => dependency.state === 'READY'))
const symbols: Record<string, string> = { sqlite: 'SQL', webp: 'W', avif: 'A' }
const labels: Record<string, string> = {
  READY: '已就绪', MISSING: '需要安装', UNSUPPORTED: '暂不支持', INSTALLING: '安装中',
  DOWNLOAD_FAILED: '下载失败', LOAD_FAILED: '加载失败', RESTART_REQUIRED: '需重启 Halo', AVAILABLE: '检查中',
}
</script>

<style scoped>
.environment-panel { display: flex; flex-direction: column; gap: 24px; max-width: 1120px; }
.environment-facts { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 24px; padding: 24px; }
.environment-facts > div { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.environment-facts span, .environment-facts p { color: var(--st-text-secondary); font-size: 12px; }
.environment-facts strong { font-size: 14px; font-weight: 550; overflow-wrap: anywhere; }
.environment-facts p { grid-column: 1 / -1; margin: 0; }
.environment-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; font-size: 13px; }
.environment-time { margin-left: auto; color: var(--st-text-tertiary); font-size: 12px; }
.environment-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--st-warning-text); }
.environment-dot.ready { background: var(--st-success-text); }
.dependency-row { display: flex; align-items: flex-start; gap: 18px; padding: 24px; }
.dependency-row + .dependency-row { border-top: 1px solid var(--st-border); }
.dependency-symbol { display: grid; place-items: center; width: 40px; height: 40px; border: 1px solid var(--st-border); border-radius: 10px; background: var(--st-bg-hover); color: var(--st-text-secondary); font-size: 12px; font-weight: 650; flex-shrink: 0; }
.dependency-content { flex: 1; min-width: 0; }
.dependency-title { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.dependency-title h2 { margin: 0; font-size: 14px; font-weight: 550; }
.dependency-state { color: var(--st-warning-text-strong); font-size: 11px; }
.dependency-state.ready { color: var(--st-success-text-strong); }
.dependency-content p { margin: 8px 0 12px; color: var(--st-text-secondary); font-size: 12px; line-height: 1.8; overflow-wrap: anywhere; }
.dependency-content details { color: var(--st-text-secondary); font-size: 12px; }
.dependency-content summary { cursor: pointer; }
.dependency-content dl { margin: 10px 0 0; display: flex; flex-direction: column; gap: 8px; }
.dependency-content dl > div { display: flex; gap: 20px; }
.dependency-content dd { margin: 0; color: var(--st-text-primary); overflow-wrap: anywhere; }
.dependency-action { display: flex; flex-direction: column; align-items: flex-end; gap: 8px; flex-shrink: 0; }
.dependency-size { color: var(--st-text-tertiary); font-size: 11px; }
.environment-note { padding: 0 2px; color: var(--st-text-secondary); font-size: 12px; line-height: 1.8; }
.environment-note h2 { margin: 0 0 8px; color: var(--st-text-primary); font-size: 13px; font-weight: 550; }
.environment-note p { margin: 5px 0; }
@media (max-width: 700px) {
  .environment-facts { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; padding: 20px; }
  .dependency-row { flex-wrap: wrap; padding: 20px; gap: 12px; }
  .dependency-action { width: 100%; align-items: flex-start; padding-left: 52px; }
  .environment-time { width: 100%; margin-left: 14px; }
}
</style>
