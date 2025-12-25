import { definePlugin } from '@halo-dev/ui-shared'
import ProcessingLogsView from './views/ProcessingLogsView.vue'
import { IconBookRead } from '@halo-dev/components'
import { markRaw } from 'vue'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'ToolsRoot',
      route: {
        path: '/storage-toolkit/logs',
        name: 'ProcessingLogs',
        component: ProcessingLogsView,
        meta: {
          title: '存储处理日志',
          searchable: true,
          menu: {
            name: '存储处理日志',
            group: 'tool',
            icon: markRaw(IconBookRead),
            priority: 100,
          },
        },
      },
    },
  ],
  extensionPoints: {},
})
