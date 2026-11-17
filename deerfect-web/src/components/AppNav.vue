<script setup lang="ts">
/**
 * 全局导航壳层：品牌头 + 工作区 + 新建会话 + 分组导航 + 系统诊断/外观/用户位。
 *
 * 只负责「切页」与外观切换：页面状态由 App.vue 持有，本组件通过 navigate 上报；
 * 新建会话同样是动作型条目（emit new-session），不在此维护会话状态。
 * 轨迹与监控属于对话页内的 tab（按会话/运行态观测），不占全局导航。
 *
 * 几何对齐控制台参考图：卡片圆角 14px、条目标高 38px/圆角 11px/间距 4px、
 * 活动项左侧 3px 竖条；分组标签与底部面板留白比列表更宽一档。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useSystemHealth } from '../composables/systemHealth'
import { WORKSPACES_EVENT, activeWorkspaceName } from '../utils/workspaces'

type PageId =
  | 'dashboard'
  | 'chat'
  | 'knowledge'
  | 'memory'
  | 'skills'
  | 'calendar'
  | 'mcp'
  | 'monitor'
  | 'settings'
  | 'security'

const props = defineProps<{
  page: PageId
  theme: 'dark' | 'light'
  authEnabled: boolean
  /** 各页条目计数（可选）：仅 >0 时在导航项右侧显示徽标 */
  counts?: Partial<Record<PageId, number>>
}>()

const emit = defineEmits<{
  navigate: [page: PageId]
  'new-session': []
  'set-theme': [theme: 'dark' | 'light']
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
    id: 'knowledge',
    label: '知识库',
    group: '核心',
    paths: ['M4 19.5A2.5 2.5 0 0 1 6.5 17H20', 'M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z'],
  },
  {
    id: 'memory',
    label: '记忆',
    group: '核心',
    paths: [
      'M4.5 6c0-1.66 3.36-3 7.5-3s7.5 1.34 7.5 3-3.36 3-7.5 3-7.5-1.34-7.5-3z',
      'M4.5 6v6c0 1.66 3.36 3 7.5 3s7.5-1.34 7.5-3V6',
      'M4.5 12v6c0 1.66 3.36 3 7.5 3s7.5-1.34 7.5-3v-6',
    ],
  },
  {
    id: 'skills',
    label: '技能',
    group: '连接',
    paths: ['M13.5 2.5 5 13.5h5.5L10 21.5 19 10h-5.5z'],
  },
  {
    id: 'calendar',
    label: '内容日历',
    group: '连接',
    paths: ['M4 8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z', 'M4 10.5h16', 'M8.5 3v4M15.5 3v4'],
  },
  {
    id: 'mcp',
    label: 'MCP',
    group: '连接',
    paths: ['M9 3v5M15 3v5', 'M6.5 8h11v2.5a5.5 5.5 0 0 1-11 0z', 'M12 16v5'],
  },
  {
    id: 'monitor',
    label: '监控',
    group: '观测',
    paths: ['M3 20h18', 'M6 20v-7', 'M11 20V6', 'M16 20v-4', 'M21 20V9'],
  },
  {
    id: 'settings',
    label: '设置',
    group: '系统',
    paths: [
      'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
      'M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z',
    ],
  },
  {
    id: 'security',
    label: '安全',
    group: '系统',
    paths: ['M12 3 4.5 6v6c0 4.5 3.2 7.6 7.5 9 4.3-1.4 7.5-4.5 7.5-9V6z', 'm9 12 2 2 4-4'],
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

function badgeOf(id: PageId): number | undefined {
  const count = props.counts?.[id]
  return count !== undefined && count > 0 ? count : undefined
}

const { health } = useSystemHealth()

/** 当前工作区名：设置页「打开」后通过 workspaces-changed 事件同步 */
const workspaceName = ref(activeWorkspaceName())
function onWorkspacesChanged(): void {
  workspaceName.value = activeWorkspaceName()
}
onMounted(() => window.addEventListener(WORKSPACES_EVENT, onWorkspacesChanged))
onBeforeUnmount(() => window.removeEventListener(WORKSPACES_EVENT, onWorkspacesChanged))

const healthText = computed(() => {
  if (health.value === 'online') return '在线'
  if (health.value === 'offline') return '离线'
  return '检测中'
})
</script>

<template>
  <aside class="app-nav" aria-label="主导航">
    <header class="app-nav-head">
      <span class="nav-logo-wrap" aria-hidden="true">
        <img class="nav-logo" src="/deer_logo.png" alt="" />
      </span>
      <div class="nav-brand">
        <div class="nav-brand-row">
          <span class="nav-brand-name">DeerFect</span>
          <span class="nav-brand-tag">HARNESS</span>
        </div>
        <span class="nav-brand-sub">本地 Agent 控制台</span>
      </div>
    </header>

    <div class="nav-top">
      <!-- 工作区：当前为单实例部署，保留层级但不做假下拉 -->
      <button class="nav-workspace" type="button" title="当前工作区">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z" />
        </svg>
        <span class="nav-workspace-name">{{ workspaceName }}</span>
        <span class="nav-workspace-role">管理员</span>
        <svg class="nav-chevron" viewBox="0 0 24 24" aria-hidden="true">
          <path d="m7 10 5 5 5-5" />
        </svg>
      </button>

      <button class="nav-new" type="button" data-testid="nav-new-session" @click="emit('new-session')">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 5v14M5 12h14" />
        </svg>
        <span>新会话</span>
      </button>
    </div>

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
          <span v-if="badgeOf(item.id)" class="nav-badge">{{ badgeOf(item.id) }}</span>
        </button>
      </div>
    </nav>

    <footer class="app-nav-foot">
      <button
        class="nav-health"
        :class="`nav-health-${health}`"
        type="button"
        title="服务端可达性；组件详情见监控页"
        @click="emit('navigate', 'monitor')"
      >
        <span class="nav-health-dot" aria-hidden="true"></span>
        <span class="nav-health-label">系统诊断</span>
        <span class="nav-health-state">{{ healthText }}</span>
      </button>

      <div class="nav-panel">
        <div class="nav-panel-row">
          <span class="nav-panel-label">外观</span>
          <div class="nav-segment" role="group" aria-label="外观">
            <button
              class="nav-segment-btn"
              :class="{ 'nav-segment-btn-active': theme === 'light' }"
              type="button"
              title="日间"
              aria-label="日间"
              :aria-pressed="theme === 'light'"
              @click="emit('set-theme', 'light')"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <circle cx="12" cy="12" r="4" />
                <path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M18.4 5.6 17 7M7 17l-1.4 1.4" />
              </svg>
            </button>
            <button
              class="nav-segment-btn"
              :class="{ 'nav-segment-btn-active': theme === 'dark' }"
              type="button"
              title="夜间"
              aria-label="夜间"
              :aria-pressed="theme === 'dark'"
              @click="emit('set-theme', 'dark')"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M20.5 14.5A8.5 8.5 0 0 1 9.5 3.5a8.5 8.5 0 1 0 11 11Z" />
              </svg>
            </button>
          </div>
        </div>
      </div>

      <div class="nav-user">
        <span class="nav-avatar" aria-hidden="true">管</span>
        <div class="nav-user-text">
          <span class="nav-user-name">管理员</span>
          <span class="nav-user-role">{{ authEnabled ? '已登录' : '本地实例' }}</span>
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
    </footer>
  </aside>
</template>
