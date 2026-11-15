<script setup lang="ts">
/**
 * 全局导航壳层：品牌头 + 工作区 + 分组导航（核心 / 观测）+ 底部外观与用户位。
 *
 * 只负责「切页」这一件事：页面状态由 App.vue 持有，本组件通过 navigate 上报；
 * 主题与退出同样是动作型条目，不在此维护状态。
 * 轨迹与监控属于对话页内的 tab（按会话/运行态观测），不占全局导航。
 */
import { computed } from 'vue'

type PageId = 'dashboard' | 'chat' | 'monitor'

const props = defineProps<{
  page: PageId
  theme: 'dark' | 'light'
  authEnabled: boolean
}>()

const emit = defineEmits<{
  navigate: [page: PageId]
  'toggle-theme': []
  logout: []
}>()

interface NavItem {
  id: PageId
  label: string
  group: string
  /** 24×24 viewBox 下的路径集合；统一走 1.7px 描边线性图标 */
  paths: string[]
}

const NAV: NavItem[] = [
  {
    id: 'dashboard',
    label: '仪表盘',
    group: '核心',
    paths: ['M4 4h6.5v7H4z', 'M13.5 4H20v4.5h-6.5z', 'M13.5 11.5H20V20h-6.5z', 'M4 14h6.5v6H4z'],
  },
  {
    id: 'chat',
    label: '对话',
    group: '核心',
    paths: ['M20 15a2 2 0 0 1-2 2H8l-4 4V5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2z'],
  },
  {
    id: 'monitor',
    label: '监控',
    group: '观测',
    paths: ['M3 20h18', 'M6 20v-7', 'M11 20V6', 'M16 20v-4', 'M21 20V9'],
  },
]

/** 分组顺序按 NAV 首次出现，避免另维护一份分组清单 */
const groups = computed(() => {
  const order: string[] = []
  const map = new Map<string, NavItem[]>()
  for (const item of NAV) {
    if (!map.has(item.group)) {
      map.set(item.group, [])
      order.push(item.group)
    }
    map.get(item.group)!.push(item)
  }
  return order.map((name) => ({ name, items: map.get(name)! }))
})

const themeLabel = computed(() => (props.theme === 'dark' ? '暗色' : '亮色'))
</script>

<template>
  <aside class="app-nav" aria-label="主导航">
    <div class="app-nav-head">
      <img class="nav-logo" src="/deer_logo.png" alt="" />
      <div class="nav-brand">
        <span class="nav-brand-name">DeerFect</span>
        <span class="nav-brand-sub">HARNESS</span>
      </div>
    </div>

    <!-- 工作区：当前为单实例部署，保留层级但不做假下拉 -->
    <button class="nav-workspace" type="button" title="当前工作区">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z" />
      </svg>
      <span class="nav-workspace-name">默认工作区</span>
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="m7 10 5 5 5-5" />
      </svg>
    </button>

    <nav class="nav-scroll">
      <div v-for="group in groups" :key="group.name" class="nav-group">
        <div class="nav-group-label">{{ group.name }}</div>
        <button
          v-for="item in group.items"
          :key="item.id"
          class="nav-item"
          :class="{ 'nav-item-active': page === item.id }"
          type="button"
          :data-testid="`nav-${item.id}`"
          :aria-current="page === item.id ? 'page' : undefined"
          @click="emit('navigate', item.id)"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path v-for="d in item.paths" :key="d" :d="d" />
          </svg>
          <span class="nav-item-label">{{ item.label }}</span>
        </button>
      </div>
    </nav>

    <div class="app-nav-foot">
      <button
        class="nav-item nav-theme"
        type="button"
        :title="theme === 'dark' ? '切换亮色' : '切换暗色'"
        @click="emit('toggle-theme')"
      >
        <svg v-if="theme === 'dark'" viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="12" cy="12" r="4" />
          <path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M18.4 5.6 17 7M7 17l-1.4 1.4" />
        </svg>
        <svg v-else viewBox="0 0 24 24" aria-hidden="true">
          <path d="M20.5 14.5A8.5 8.5 0 0 1 9.5 3.5a8.5 8.5 0 1 0 11 11Z" />
        </svg>
        <span class="nav-item-label">外观</span>
        <span class="nav-theme-state">{{ themeLabel }}</span>
      </button>

      <div class="nav-user">
        <span class="nav-avatar" aria-hidden="true">管</span>
        <div class="nav-user-text">
          <span class="nav-user-name">管理员</span>
          <span class="nav-user-role">本地实例</span>
        </div>
        <button
          v-if="authEnabled"
          class="nav-logout"
          type="button"
          title="退出登录"
          aria-label="退出登录"
          @click="emit('logout')"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M9 21H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3" />
            <path d="m16 17 5-5-5-5" />
            <path d="M21 12H9" />
          </svg>
        </button>
      </div>
    </div>
  </aside>
</template>
