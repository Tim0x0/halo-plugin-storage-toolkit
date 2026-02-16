<template>
  <div class="reference-section" v-if="references?.length">
    <div class="section-header">
      <span class="section-title">{{ title }}</span>
      <span class="section-count">{{ groupedReferences.length }} 处</span>
    </div>
    <div class="reference-list">
      <a
        class="reference-item"
        v-for="group in groupedReferences"
        :key="group.sourceName"
        :href="group.sourceUrl || 'javascript:void(0)'"
        :target="group.sourceUrl ? '_blank' : undefined"
        :class="{ 'no-link': !group.sourceUrl }"
      >
        <span class="ref-icon">{{ getSourceTypeIcon(group.sourceType) }}</span>
        <div class="ref-content">
          <span class="ref-title">{{ getDisplayTitle(group) }}</span>
          <div class="ref-tags">
            <span
              class="ref-tag"
              v-for="tag in group.tags"
              :key="tag"
            >{{ tag }}</span>
            <span class="ref-tag deleted" v-if="group.deleted">回收站</span>
          </div>
        </div>
        <span class="ref-arrow" v-if="group.sourceUrl">→</span>
      </a>
    </div>
  </div>
  <div class="empty-references" v-else>
    <span>{{ emptyText }}</span>
  </div>
</template>

<script setup lang="ts">
import { ref as vueRef, computed, watch } from 'vue'
import { axiosInstance } from '@halo-dev/api-client'
import { API_ENDPOINTS } from '@/constants/api'
import { getSourceTypeIcon, sortBySourceType } from '@/composables/useReferenceSource'

/**
 * 引用源数据接口（兼容 ReferenceSource 和 BrokenLinkSource）
 */
export interface ReferenceSourceItem {
  sourceType: string
  sourceName: string
  sourceTitle: string | null
  sourceUrl: string | null
  deleted?: boolean | null
  referenceType?: string | null
  settingName?: string | null
}

/**
 * 分组后的引用源
 */
interface GroupedReference {
  sourceType: string
  sourceName: string
  sourceTitle: string | null
  sourceUrl: string | null
  deleted: boolean
  tags: string[]
}

const props = withDefaults(defineProps<{
  references: ReferenceSourceItem[] | null | undefined
  title?: string
  emptyText?: string
}>(), {
  title: '引用位置',
  emptyText: '暂无引用记录'
})

const emit = defineEmits<{
  (e: 'reference-resolved', ref: ReferenceSourceItem): void
}>()

// Setting 类型常量
const SETTING_TYPES = ['SystemSetting', 'PluginSetting', 'ThemeSetting']

// 引用类型标签映射
const REFERENCE_TYPE_LABELS: Record<string, string> = {
  'cover': '封面',
  'content': '内容',
  'draft': '草稿',
  'media': '媒体',
  'comment': '评论',
  'reply': '回复',
  'avatar': '头像',
  'icon': '图标',
  'basic': '基本设置'
}

// Setting group label 缓存
const settingGroupLabelCache = vueRef<Record<string, string>>({})

/**
 * 获取引用类型显示标签
 */
function getReferenceTypeLabel(sourceType: string, referenceType: string | null | undefined, settingName: string | null | undefined): string {
  if (!referenceType) return ''

  // 静态映射优先
  if (REFERENCE_TYPE_LABELS[referenceType]) {
    return REFERENCE_TYPE_LABELS[referenceType]
  }

  // Setting 类型，检查缓存
  if (SETTING_TYPES.includes(sourceType) && settingName && referenceType) {
    const cacheKey = `${settingName}:${referenceType}`
    if (settingGroupLabelCache.value[cacheKey]) {
      return settingGroupLabelCache.value[cacheKey]
    }
    return referenceType
  }

  return referenceType
}

/**
 * 异步获取 Setting group label
 */
async function fetchSettingGroupLabel(settingName: string, groupKey: string): Promise<string> {
  const cacheKey = `${settingName}:${groupKey}`
  if (settingGroupLabelCache.value[cacheKey]) {
    return settingGroupLabelCache.value[cacheKey]
  }

  try {
    const { data } = await axiosInstance.get(API_ENDPOINTS.REFERENCES_SETTING_GROUP_LABEL(settingName, groupKey))
    settingGroupLabelCache.value[cacheKey] = data.label
    return data.label
  } catch (e) {
    settingGroupLabelCache.value[cacheKey] = groupKey
    return groupKey
  }
}

/**
 * 获取引用显示标题
 */
function getDisplayTitle(group: GroupedReference): string {
  if (group.sourceType === 'Comment' || group.sourceType === 'Reply' || group.sourceType === 'Doc') {
    if (group.sourceUrl != null) {
      return group.sourceTitle || group.sourceName
    }
    return '加载中...'
  }
  return group.sourceTitle || group.sourceName
}

/**
 * 按 sourceName 分组并合并 referenceType
 */
const groupedReferences = computed<GroupedReference[]>(() => {
  if (!props.references || props.references.length === 0) return []

  const sorted = sortBySourceType(props.references)
  const map = new Map<string, GroupedReference>()

  for (const ref of sorted) {
    const key = ref.sourceName
    if (!map.has(key)) {
      const tags: string[] = []
      const label = getReferenceTypeLabel(ref.sourceType, ref.referenceType, ref.settingName)
      if (label) tags.push(label)

      map.set(key, {
        sourceType: ref.sourceType,
        sourceName: ref.sourceName,
        sourceTitle: ref.sourceTitle,
        sourceUrl: ref.sourceUrl,
        deleted: ref.deleted === true,
        tags
      })
    } else {
      const existing = map.get(key)!
      const label = getReferenceTypeLabel(ref.sourceType, ref.referenceType, ref.settingName)
      if (label && !existing.tags.includes(label)) {
        existing.tags.push(label)
      }
      // 如果任意引用被标记为 deleted，整组标记
      if (ref.deleted === true) {
        existing.deleted = true
      }
      // 使用有 URL 的 source 信息
      if (ref.sourceUrl && !existing.sourceUrl) {
        existing.sourceUrl = ref.sourceUrl
      }
      if (ref.sourceTitle && !existing.sourceTitle) {
        existing.sourceTitle = ref.sourceTitle
      }
    }
  }

  return Array.from(map.values())
})

/**
 * 监听 references 变化，异步解析标题和标签
 */
watch(() => props.references, async (refs) => {
  if (!refs || refs.length === 0) return

  for (const ref of refs) {
    // 异步解析评论/回复的关联标题
    if ((ref.sourceType === 'Comment' || ref.sourceType === 'Reply') && ref.sourceTitle && ref.sourceUrl == null) {
      const colonIndex = ref.sourceTitle.indexOf(':')
      if (colonIndex > 0) {
        const kind = ref.sourceTitle.substring(0, colonIndex)
        const name = ref.sourceTitle.substring(colonIndex + 1)
        try {
          const { data } = await axiosInstance.get(API_ENDPOINTS.REFERENCES_SUBJECT(kind, name))
          if (data.title || data.url) {
            ref.sourceTitle = data.title || ref.sourceTitle
            ref.sourceUrl = data.url || ''
            emit('reference-resolved', ref)
          } else {
            ref.sourceUrl = ''
          }
        } catch (e) {
          console.debug('解析引用源失败:', e)
          ref.sourceUrl = ''
        }
      } else {
        // sourceTitle 不含冒号，无法解析宿主信息，直接标记为已解析
        ref.sourceUrl = ''
      }
    }

    // 异步解析文档的标题和链接
    if (ref.sourceType === 'Doc' && ref.sourceTitle && ref.sourceUrl == null) {
      const match = ref.sourceTitle.match(/^Doc:(.+)$/)
      if (match) {
        const [, docName] = match
        try {
          const { data } = await axiosInstance.get(API_ENDPOINTS.REFERENCES_SUBJECT('Doc', docName))
          if (data.title || data.url) {
            ref.sourceTitle = data.title || ref.sourceTitle
            ref.sourceUrl = data.url || ''
            emit('reference-resolved', ref)
          } else {
            ref.sourceUrl = ''
          }
        } catch (e) {
          console.debug('解析文档引用源失败:', e)
          ref.sourceUrl = ''
        }
      } else {
        // sourceTitle 不匹配 Doc:xxx 格式，直接标记为已解析
        ref.sourceUrl = ''
      }
    }

    // 异步获取 Setting 引用的 group label
    if (SETTING_TYPES.includes(ref.sourceType) && ref.settingName && ref.referenceType) {
      await fetchSettingGroupLabel(ref.settingName, ref.referenceType)
    }
  }
}, { immediate: true })
</script>

<style scoped>
.reference-section {
  padding: 16px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.section-title {
  font-size: 13px;
  font-weight: 500;
  color: #18181b;
}

.section-count {
  font-size: 12px;
  color: #a1a1aa;
}

.reference-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.reference-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  background: #fafafa;
  border-radius: 6px;
  text-decoration: none;
  transition: background 0.15s;
}

.reference-item:hover:not(.no-link) {
  background: #f4f4f5;
}

.reference-item.no-link {
  cursor: default;
}

.ref-icon {
  font-size: 16px;
  flex-shrink: 0;
  line-height: 1;
}

.ref-content {
  flex: 1;
  min-width: 0;
}

.ref-title {
  font-size: 13px;
  color: #18181b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.4;
  display: block;
}

.ref-tags {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
  flex-wrap: wrap;
}

.ref-tag {
  font-size: 11px;
  padding: 1px 6px;
  background: #e4e4e7;
  color: #52525b;
  border-radius: 3px;
}

.ref-tag.deleted {
  background: #fee2e2;
  color: #dc2626;
}

.ref-arrow {
  font-size: 12px;
  color: #a1a1aa;
  flex-shrink: 0;
}

.empty-references {
  padding: 32px 16px;
  text-align: center;
  color: #a1a1aa;
  font-size: 13px;
}
</style>
