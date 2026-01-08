import { definePlugin } from '@halo-dev/ui-shared'
import { IconFolder } from '@halo-dev/components'
import { markRaw } from 'vue'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/storage-toolkit',
        name: 'StorageToolkit',
        component: () => import('./views/StorageToolkitView.vue'),
        meta: {
          permissions: ['plugin:storage-toolkit:manage'],
          menu: {
            name: '存储工具箱',
            group: 'tool',
            icon: markRaw(IconFolder),
          },
        },
      },
    },
  ],
})
