<script setup lang="ts">
/**
 * 设置（配置中心）：左侧二级导航 + 右侧内容区。
 *
 * 2026-11-16 按配置中心参考图重排：
 *   模型管理（/api/admin/providers|models，供应商卡片 + 启用/禁用 + 配置 + 自定义）
 *   外观 / 本机偏好 / 实例信息（原有设置项，改为二级 tab）
 *   安全 / 监控 / 技能管理为跳转项（复用全局页面，不在设置内重复实现）
 *
 * 凭证只展示环境变量引用名：后端不落库密钥，web 也不写密钥。
 */
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import type { SessionView } from '../api'
import SystemSettingsPanel from './settings/SystemSettingsPanel.vue'
import TtsSettingsPanel from './settings/TtsSettingsPanel.vue'
import WorkspaceSettingsPanel from './settings/WorkspaceSettingsPanel.vue'
import RouteAgentPanel from './settings/RouteAgentPanel.vue'
import ModelManagementPanel from './settings/ModelManagementPanel.vue'

defineProps<{
  theme: 'dark' | 'light'
  themeMode: 'light' | 'dark' | 'system'
  /** 真实会话列表：工作区卡片用它统计归属会话数 */
  sessions: SessionView[]
}>()

const emit = defineEmits<{
  'set-theme': [theme: 'dark' | 'light']
  'set-theme-mode': [mode: 'light' | 'dark' | 'system']
  navigate: [page: 'security' | 'monitor' | 'skills']
}>()

type TabKey = 'workspace' | 'models' | 'routing' | 'tts' | 'system' | 'appearance' | 'prefs' | 'instance'
type NavKey = TabKey | 'security' | 'monitor' | 'skills'
interface NavItem {
  key: NavKey
  label: string
  icon: string[]
  link?: boolean
}

const NAV_GROUPS: { label: string; items: NavItem[] }[] = [
  {
    label: '配置',
    items: [
      {
        key: 'models',
        label: '模型管理',
        icon: ['M12 3 3 8l9 5 9-5-9-5z', 'M3 8v8l9 5 9-5V8', 'M12 13v8'],
      },
      {
        key: 'routing',
        label: '路由与 Agent',
        icon: ['M5 5h4l3 7h7', 'M5 19h4l3-7', 'M19 12h2'],
      },
      {
        key: 'workspace',
        label: '工作区',
        icon: ['M4 6a2 2 0 0 1 2-2h4l2 2h6a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z'],
      },
      {
        key: 'tts',
        label: '语音合成',
        icon: ['M11 5 6 9H3v6h3l5 4z', 'M16 9a4 4 0 0 1 0 6', 'M19 6a8 8 0 0 1 0 12'],
      },
    ],
  },
  {
    label: '界面',
    items: [
      {
        key: 'system',
        label: '系统设置',
        icon: [
          'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
          'M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z',
        ],
      },
      {
        key: 'appearance',
        label: '外观',
        icon: ['M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z', 'M12 2v2M12 20v2M2 12h2M20 12h2'],
      },
      {
        key: 'prefs',
        label: '本机偏好',
        icon: ['M4 8h16M4 16h16', 'M9 8a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z', 'M15 19a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z'],
      },
    ],
  },
  {
    label: '实例',
    items: [
      {
        key: 'instance',
        label: '实例信息',
        icon: ['M4 5h16v6H4z', 'M4 13h16v6H4z', 'M7 8h.01M7 16h.01'],
      },
    ],
  },
  {
    label: '快捷入口',
    items: [
      { key: 'security', label: '安全', icon: ['M12 3 4.5 6v6c0 4.5 3.2 7.6 7.5 9 4.3-1.4 7.5-4.5 7.5-9V6z'], link: true },
      { key: 'monitor', label: '监控', icon: ['M3 20h18', 'M6 20v-7', 'M11 20V6', 'M16 20v-4', 'M21 20V9'], link: true },
      { key: 'skills', label: '技能管理', icon: ['M13.5 2.5 5 13.5h5.5L10 21.5 19 10h-5.5z'], link: true },
    ],
  },
]

const TABS: Record<TabKey, { title: string; sub: string }> = {
  workspace: { title: '工作区', sub: '本地工作目录、会话归属与成员访问' },
  models: { title: '模型管理', sub: '配置模型提供商、凭证引用和模型列表' },
  routing: { title: '路由与 Agent', sub: '模型路由配置与 Agent 绑定关系' },
  tts: { title: '语音合成', sub: '文本转语音服务与默认语音参数' },
  system: { title: '系统设置', sub: '界面偏好、运行时行为与本地数据管理' },
  appearance: { title: '外观', sub: '主题保存在本机 localStorage，不随账号同步' },
  prefs: { title: '本机偏好', sub: '只影响这个浏览器，不会写回服务端' },
  instance: { title: '实例信息', sub: '来自正在运行的后端接口，— 表示该项不可用' },
}

const tab = ref<TabKey>('models')
const current = computed(() => TABS[tab.value])
/** 系统设置 / 语音合成面板的保存、试听入口在页头右侧，通过 ref 调用 */
const systemPanel = ref<InstanceType<typeof SystemSettingsPanel> | null>(null)
const ttsPanel = ref<InstanceType<typeof TtsSettingsPanel> | null>(null)
/** 工作区的新建 / 导入按钮同样在页头右侧 */
const workspacePanel = ref<InstanceType<typeof WorkspaceSettingsPanel> | null>(null)
/** 路由与 Agent 的刷新、新建入口同样在页头右侧 */
const routeAgentPanel = ref<InstanceType<typeof RouteAgentPanel> | null>(null)
/** 模型管理的刷新入口在页头右侧，卡片与弹窗都在面板内 */
const modelPanel = ref<InstanceType<typeof ModelManagementPanel> | null>(null)

function onNav(item: NavItem): void {
  if (item.link) {
    emit('navigate', item.key as 'security' | 'monitor' | 'skills')
    return
  }
  tab.value = item.key as TabKey
}

/* ---------- 本机偏好重置反馈 ---------- */
const actionNote = ref('')

/* ---------- 外观 / 本机偏好 / 实例信息 ---------- */
const DETAILS_KEY = 'monitor-details-open'
const THEME_KEY = 'web-theme'
const detailsCollapsed = ref(false)

function readDetailPref(): void {
  try {
    const raw = localStorage.getItem(DETAILS_KEY)
    if (!raw) {
      detailsCollapsed.value = false
      return
    }
    const saved = JSON.parse(raw) as Record<string, boolean>
    detailsCollapsed.value = saved.sessions === false && saved.llm === false && saved.tools === false
  } catch {
    detailsCollapsed.value = false
  }
}

const agents = ref<number | null>(null)
const servers = ref<number | null>(null)
const tools = ref<number | null>(null)
const docs = ref<number | null>(null)
const instanceLoading = ref(false)
const instanceError = ref('')

async function loadInstance(): Promise<void> {
  instanceLoading.value = true
  instanceError.value = ''
  const [a, c, k] = await Promise.allSettled([
    api.listAgents(),
    api.capabilities(),
    api.listKbDocuments(1, 1),
  ])
  agents.value = a.status === 'fulfilled' ? a.value.agents.length : null
  if (c.status === 'fulfilled') {
    servers.value = c.value.entries.filter((e) => e.source.startsWith('mcp')).length
    tools.value = c.value.totalTools
  }
  docs.value = k.status === 'fulfilled' ? k.value.total : null
  if (a.status === 'rejected' && c.status === 'rejected' && k.status === 'rejected') {
    instanceError.value = '实例信息读取失败：后端不可达'
  }
  instanceLoading.value = false
}

const stats = computed(() => [
  { label: 'Agent', value: agents.value == null ? '—' : String(agents.value) },
  { label: 'MCP Server', value: servers.value == null ? '—' : String(servers.value) },
  { label: '已发现工具', value: tools.value == null ? '—' : String(tools.value) },
  { label: '知识库文档', value: docs.value == null ? '—' : String(docs.value) },
])

function resetPrefs(): void {
  localStorage.removeItem(THEME_KEY)
  localStorage.removeItem(DETAILS_KEY)
  emit('set-theme', 'dark')
  readDetailPref()
  actionNote.value = '本机偏好已重置：主题回到夜间，监控明细恢复展开'
}

onMounted(() => {
  readDetailPref()
  void loadInstance()
})
</script>

<template>
  <section class="settings-page" aria-label="设置">
    <aside class="settings-nav">
      <span class="settings-nav-eyebrow">配置中心</span>
      <h1 class="settings-nav-title">设置</h1>
      <nav class="settings-nav-body">
        <div v-for="group in NAV_GROUPS" :key="group.label" class="settings-nav-group">
          <div class="settings-nav-label">{{ group.label }}</div>
          <button
            v-for="item in group.items"
            :key="item.key"
            class="settings-nav-item"
            :class="{ 'is-active': !item.link && tab === item.key }"
            type="button"
            :data-testid="`settings-nav-${item.key}`"
            @click="onNav(item)"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path v-for="d in item.icon" :key="d" :d="d" />
            </svg>
            <span>{{ item.label }}</span>
            <span v-if="item.link" class="settings-nav-arrow" aria-hidden="true">↗</span>
          </button>
        </div>
      </nav>
    </aside>

    <div class="settings-main">
      <header class="settings-main-head">
        <div>
          <h2>{{ current.title }}</h2>
          <p>{{ current.sub }}</p>
        </div>
        <div class="settings-main-actions">
          <template v-if="tab === 'workspace'">
            <button class="res-btn" type="button" @click="workspacePanel?.startCreate('import')">导入工作区</button>
            <button class="res-btn res-btn-primary" type="button" @click="workspacePanel?.startCreate('create')">+ 新建工作区</button>
          </template>
          <template v-else-if="tab === 'models'">
            <button class="res-btn" type="button" @click="modelPanel?.reload()">刷新</button>
          </template>
          <template v-else-if="tab === 'routing'">
            <button class="res-btn" type="button" @click="routeAgentPanel?.reload()">刷新</button>
            <button class="res-btn" type="button" @click="routeAgentPanel?.startCreateRoute()">+ 新建路由</button>
          </template>
          <template v-else-if="tab === 'system'">
            <button class="res-btn" type="button" @click="systemPanel?.restoreDefaults()">恢复默认</button>
            <button class="res-btn res-btn-primary" type="button" @click="systemPanel?.save()">保存设置</button>
          </template>
          <template v-else-if="tab === 'tts'">
            <button class="res-btn" type="button" @click="ttsPanel?.speak()">试听示例</button>
          </template>
        </div>
      </header>

      <p v-if="actionNote" class="res-note" role="status">{{ actionNote }}</p>

      <!-- 工作区：本地目录登记 + 会话归属 + 协作成员 -->
      <WorkspaceSettingsPanel
        v-if="tab === 'workspace'"
        ref="workspacePanel"
        :sessions="sessions"
        @navigate="emit('navigate', 'security')"
      />

      <!-- 路由与 Agent：路由卡片 + Agent 卡片 + 配置弹窗 -->
      <RouteAgentPanel v-else-if="tab === 'routing'" ref="routeAgentPanel" />

      <!-- 模型管理：提供商卡片 + 配置/模型弹窗 -->
      <ModelManagementPanel v-else-if="tab === 'models'" ref="modelPanel" />

      <!-- 系统设置：界面 / 运行时 / 本地数据 -->
      <SystemSettingsPanel
        v-else-if="tab === 'system'"
        ref="systemPanel"
        :theme-mode="themeMode"
        @set-theme-mode="emit('set-theme-mode', $event)"
      />

      <!-- 语音合成：真实服务状态 + 本机试听参数 -->
      <TtsSettingsPanel v-else-if="tab === 'tts'" ref="ttsPanel" />

      <!-- 外观 -->
      <template v-else-if="tab === 'appearance'">
        <div class="settings-theme">
          <button
            class="settings-theme-card"
            :class="{ 'is-active': theme === 'light' }"
            type="button"
            :aria-pressed="theme === 'light'"
            @click="emit('set-theme', 'light')"
          >
            <span class="settings-swatch is-light" aria-hidden="true"><i></i><i></i><i></i></span>
            <span class="settings-theme-name">日间</span>
            <span class="settings-theme-desc">蓝白画布，适合白天与投影</span>
            <span v-if="theme === 'light'" class="res-chip res-chip-accent">当前</span>
          </button>
          <button
            class="settings-theme-card"
            :class="{ 'is-active': theme === 'dark' }"
            type="button"
            :aria-pressed="theme === 'dark'"
            @click="emit('set-theme', 'dark')"
          >
            <span class="settings-swatch is-dark" aria-hidden="true"><i></i><i></i><i></i></span>
            <span class="settings-theme-name">夜间</span>
            <span class="settings-theme-desc">深蓝黑 + 图纸网格，适合长时间盯屏</span>
            <span v-if="theme === 'dark'" class="res-chip res-chip-accent">当前</span>
          </button>
        </div>
      </template>

      <!-- 本机偏好 -->
      <template v-else-if="tab === 'prefs'">
        <section class="res-panel" aria-label="本机偏好">
          <div class="settings-rows">
            <div class="settings-row">
              <div class="settings-row-text">
                <span class="settings-row-title">监控明细区</span>
                <span class="settings-row-desc">活跃会话 / LLM 调用 / 工具调用三张表的折叠状态</span>
              </div>
              <span class="res-chip">{{ detailsCollapsed ? '全部收起' : '展开' }}</span>
            </div>
            <div class="settings-row">
              <div class="settings-row-text">
                <span class="settings-row-title">重置界面偏好</span>
                <span class="settings-row-desc">清除主题与监控折叠记录，恢复默认（夜间 + 展开）</span>
              </div>
              <button class="res-btn" type="button" @click="resetPrefs">重置</button>
            </div>
          </div>
        </section>
      </template>

      <!-- 实例信息 -->
      <template v-else>
        <p v-if="instanceError" class="res-alert" role="alert">{{ instanceError }}</p>
        <div class="settings-stats">
          <div v-for="stat in stats" :key="stat.label" class="res-stat">
            <span class="res-stat-label">{{ stat.label }}</span>
            <span class="res-stat-value">{{ stat.value }}</span>
          </div>
        </div>
        <section class="res-panel" aria-label="运维入口">
          <div class="res-panel-head">
            <div>
              <h3 class="res-panel-title">运维入口</h3>
              <p class="res-panel-sub">
                {{ instanceLoading ? '读取中…' : '与监控页同源，直接读运行时指标' }}
              </p>
            </div>
            <a class="res-btn" href="/actuator/prometheus" target="_blank" rel="noopener">Prometheus 指标 ↗</a>
          </div>
        </section>
      </template>
    </div>
  </section>
</template>
