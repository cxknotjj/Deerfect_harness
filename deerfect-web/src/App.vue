/**
 * 应用骨架：全局导航（AppNav）+ 内容区。
 * - 页面：仪表盘 / 对话 / 轨迹 / 监控，由导航切换；对话页内嵌会话列表 + 聊天窗
 * - 会话联动：切换/新建会话 → showChat(id)；onMeta 回写 sessionId 并刷新列表首页
 * - Agent 选中变化 → bindAgent，成功/失败以顶栏轻提示呈现（3 秒消失）
 * - 窄屏（≤768px）：导航转抽屉 + 顶部条；会话列表为第二层抽屉，选中/新建后自动收起
 * - 占位交互（搜索/设置/反馈）统一顶栏轻提示「功能开发中」
 */
<script setup lang="ts">
import { computed, onBeforeUnmount, onErrorCaptured, onMounted, ref, watch } from 'vue'
import AppNav from './components/AppNav.vue'
import DashboardView from './components/DashboardView.vue'
import SessionList from './components/SessionList.vue'
import ChatWindow from './components/ChatWindow.vue'
import AgentSelect from './components/AgentSelect.vue'
import RoutingSelect from './components/RoutingSelect.vue'
import type { RoutingMode } from './api/types'
import TraceView from './components/TraceView.vue'
import MonitorView from './components/MonitorView.vue'
import LoginView from './components/LoginView.vue'
import OfflineView from './components/OfflineView.vue'
import { api, setUnauthorizedHandler } from './api'
import { useSessions } from './composables/useSessions'
import { useChat } from './composables/useChat'
import { useAgents } from './composables/useAgents'
import { useOfflineGate, useUnreadBadge } from './composables/pwa'
import { reportGlobalError, useGlobalError } from './composables/globalError'

type PageId = 'dashboard' | 'chat' | 'monitor'

const { message: globalError, dismiss: dismissGlobalError } = useGlobalError()

// 子树异常在这里接住：返回 false 阻止继续向上，页面其余部分仍可用（而非整页白屏）
onErrorCaptured((err, _instance, info) => {
  reportGlobalError(err, `组件异常（${info}）`)
  return false
})

const {
  sessions,
  currentSessionId,
  hasMore,
  loading: sessionsLoading,
  loadFirst,
  loadMore,
  create: createSession,
  select: selectSession,
  renameIfPlaceholder,
  remove: removeSession,
} = useSessions((msg) => showTip(msg, true))

/** 显式执行路径(auto=服务端判定;agentic/graph 随每轮请求下发覆盖) */
const selectedRouting = ref<RoutingMode>('auto')

/** 当前页：仪表盘为默认落地页 */
const page = ref<PageId>('dashboard')

/** 对话页内的分段：对话 / 轨迹（轨迹按会话，跟随会话列表） */
const chatTab = ref<'chat' | 'trace'>('chat')

const {
  messages,
  streaming,
  send,
  stop,
  show: showChat,
  remove: deleteMessage,
  regenerate,
  drop: dropChatOf,
} = useChat({
  getSessionId: () => currentSessionId.value,
  setSessionId: (id) => {
    currentSessionId.value = id
  },
  getRouting: () => (selectedRouting.value === 'auto' ? undefined : selectedRouting.value),
  onNewSession: () => {
    void loadFirst()
  },
  // 占位会话名自动改名(与服务端 touchSession 同口径):首轮成功后把「新会话」替换为首条提问;
  // sid 为流发起时快照,旧流收尾时用户已切走也不会改错会话
  onRoundSucceeded: (sid, userText) => renameIfPlaceholder(sid, userText),
})

/**
 * PWA:断网时由离线门替代主界面(应用壳由 service worker 预缓存,仍可启动);
 * 页面不可见期间完成的回复计入主屏未读角标,回到前台自动清除(Badging API 缺失时静默)。
 */
const { offline, retry: retryConnection, stop: stopOfflineGate } = useOfflineGate()
const { bump: markReplyUnread, stop: stopUnreadBadge } = useUnreadBadge()
watch(streaming, (now, was) => {
  if (was && !now) markReplyUnread()
})

const { agents, load: loadAgents, bind: bindAgentTo } = useAgents((msg) => showTip(msg, true))

/** 登录门:服务端启用登录通道(app.security.password 非空)且未登录时,以登录视图替代主界面。
 *  运行中任意请求 401(登录态过期/被踢)经统一钩子回到登录视图;登录成功后重载列表与 Agent 下拉 */
const needsLogin = ref(false)
const authEnabled = ref(false)
const loginBusy = ref(false)
const loginError = ref('')

async function onLogin(password: string): Promise<void> {
  if (loginBusy.value) return
  loginBusy.value = true
  loginError.value = ''
  try {
    await api.login(password)
    needsLogin.value = false
    void loadFirst()
    void loadAgents()
  } catch (e) {
    loginError.value = errText(e)
  } finally {
    loginBusy.value = false
  }
}

async function onLogout(): Promise<void> {
  try {
    await api.logout()
  } catch {
    /* 服务端不可达也照常切登录视图:本地 Cookie 已随响应/过期失效 */
  }
  needsLogin.value = true
}

onMounted(async () => {
  setUnauthorizedHandler(() => {
    needsLogin.value = true
  })
  try {
    const state = await api.authState()
    authEnabled.value = state.enabled
    if (state.enabled && !state.authenticated) {
      needsLogin.value = true
      return // 未登录不拉业务数据,避免一堆 401 噪音
    }
  } catch {
    /* 探测失败不阻塞(如反代短暂抖动):交由业务请求自身的 401 钩子兜底 */
  }
  void loadFirst()
  void loadAgents()
})

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/** 顶栏轻提示(3 秒自动消失;error 为 true 时红色;重复触发重置计时) */
const tip = ref<{ text: string; error: boolean } | null>(null)
let tipTimer: number | undefined
function showTip(text: string, error = false): void {
  tip.value = { text, error }
  window.clearTimeout(tipTimer)
  tipTimer = window.setTimeout(() => {
    tip.value = null
  }, 3000)
}
function clearTip(): void {
  window.clearTimeout(tipTimer)
  tip.value = null
}

/** 导航切页：关闭窄屏导航抽屉与轻提示 */
function goPage(next: PageId): void {
  page.value = next
  if (next === 'chat') chatTab.value = 'chat'
  navOpen.value = false
  clearTip()
}

/** 选中会话:聊天视图切到该会话(消息按会话保留),重置 agent 选择;窄屏自动收抽屉 */
function onSelect(id: string): void {
  if (isNarrow.value) drawerOpen.value = false
  if (id === currentSessionId.value) return
  selectSession(id)
  showChat(id)
  selectedAgentId.value = null
  chatTab.value = 'chat' // 切会话回对话视图（轨迹按会话惰性加载，切回即看）
  clearTip()
}

/** 删除会话:服务端成功后移除列表项;删的是当前会话时,终止流、切回草稿空态并清本地消息痕迹 */
async function onRemoveSession(id: string): Promise<void> {
  try {
    await removeSession(id)
  } catch (e) {
    showTip(`删除会话失败:${errText(e)}`, true)
    return
  }
  if (id === currentSessionId.value) {
    stop()
    dropChatOf(id)
    showChat('')
    selectedAgentId.value = null
  } else {
    dropChatOf(id)
  }
  showTip('会话已删除')
}

/** 新建会话:成功后自动选中(useSessions.create)并把聊天视图切到新会话空桶 */
async function onCreate(): Promise<void> {
  try {
    await createSession()
    page.value = 'chat'
    chatTab.value = 'chat'
    showChat(currentSessionId.value)
    selectedAgentId.value = null
    if (isNarrow.value) drawerOpen.value = false
  } catch (e) {
    showTip(`新建会话失败:${errText(e)}`, true)
  }
}

/** 顶栏标题：对话页显示当前会话名 */
const currentSessionName = computed(
  () => sessions.value.find((s) => s.id === currentSessionId.value)?.name ?? '新对话',
)

/** 窄屏顶部条标题 */
const pageTitle = computed(() => {
  if (page.value === 'monitor') return '监控'
  if (page.value !== 'chat') return '仪表盘'
  return chatTab.value === 'trace' ? '轨迹' : currentSessionName.value
})

/** 当前选中的 agent id(数字,列表下标 + 1;null = 默认 Agent) */
const selectedAgentId = ref<number | null>(null)

/** agent 选中变化:绑定到当前会话,成功后顶栏轻提示(mock 回复会体现新 agent 名) */
async function onAgentChange(agentId: number | null): Promise<void> {
  selectedAgentId.value = agentId
  const sid = currentSessionId.value
  if (agentId === null || sid === '') return
  try {
    const name = await bindAgentTo(sid, agentId)
    // 提示不依赖 agentName 是否为空:两种情况都给可见反馈
    showTip(name === null ? 'Agent 已切换' : `已切换到「${name}」`)
  } catch (e) {
    showTip(`切换 Agent 失败:${errText(e)}`, true)
  }
}

/** 会话列表面板隐藏（仅桌面；窄屏走抽屉） */
const sessionsHidden = ref(false)

/** 窄屏判定(≤768px):导航转抽屉，会话列表为第二层抽屉 */
const narrowMql = window.matchMedia('(max-width: 768px)')
const isNarrow = ref(narrowMql.matches)
function onNarrowChange(e: MediaQueryListEvent): void {
  isNarrow.value = e.matches
  // 模式切换时复位所有抽屉/隐藏态，避免跨模式残留
  navOpen.value = false
  drawerOpen.value = false
  sessionsHidden.value = false
}
narrowMql.addEventListener('change', onNarrowChange)

/** 导航抽屉（仅窄屏生效） */
const navOpen = ref(false)

/** 会话抽屉（仅窄屏生效）；开合伴随焦点移入/归还（可达性） */
const drawerOpen = ref(false)
const sidebarEl = ref<InstanceType<typeof SessionList> | null>(null)
const expandBtnEl = ref<HTMLButtonElement | null>(null)
watch(drawerOpen, (open) => {
  if (!isNarrow.value) return
  if (open) {
    // 打开:焦点移入侧栏首个可聚焦元素(键盘用户可直接 Tab 遍历会话项)
    requestAnimationFrame(() => {
      const root = sidebarEl.value?.$el as HTMLElement | undefined
      const first = root?.querySelector<HTMLElement>('button, [tabindex="0"]')
      first?.focus()
    })
  } else if (expandBtnEl.value) {
    expandBtnEl.value.focus() // 关闭:焦点归还展开钮
  }
})

/** 会话列表收起钮：窄屏收抽屉；桌面隐藏整列（顶栏可再展开） */
function onSidebarCollapse(): void {
  if (isNarrow.value) drawerOpen.value = false
  else sessionsHidden.value = true
}

/** 会话列表展开钮：窄屏开抽屉；桌面恢复整列 */
function onSidebarExpand(): void {
  if (isNarrow.value) drawerOpen.value = true
  else sessionsHidden.value = false
}

/** Escape 关闭抽屉(窄屏可达性) */
function onGlobalKeydown(e: KeyboardEvent): void {
  if (e.key !== 'Escape') return
  if (navOpen.value) navOpen.value = false
  else if (drawerOpen.value) drawerOpen.value = false
}
window.addEventListener('keydown', onGlobalKeydown)
onBeforeUnmount(() => {
  narrowMql.removeEventListener('change', onNarrowChange)
  window.removeEventListener('keydown', onGlobalKeydown)
  stopOfflineGate()
  stopUnreadBadge()
})

/** 主题切换:夜间(Telemetry Dark,默认)⇄ 日间(DeepSeek 蓝白);持久化 localStorage,入口在导航底部 */
const theme = ref<'dark' | 'light'>(document.documentElement.dataset.theme === 'light' ? 'light' : 'dark')
function toggleTheme(): void {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
  localStorage.setItem('web-theme', theme.value)
  document.documentElement.dataset.theme = theme.value
  document.documentElement.style.colorScheme = theme.value
}

/** 下载当前会话纯文本日志:消息按角色拼接(【user】/【assistant】),Blob 触发 a.download */
function downloadSessionLog(): void {
  if (currentSessionId.value === '') return
  const text = messages.value.map((m) => `【${m.role}】${m.content}`).join('\n\n')
  const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }))
  const a = document.createElement('a')
  a.href = url
  a.download = `session-${currentSessionId.value}.txt`
  a.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <!-- 全局错误横幅:组件抛错/异步失败时的可见提示。fixed 定位,不占布局;可手动关闭，8s 自动消失 -->
  <div v-if="globalError" class="global-error-bar" role="alert">
    <span class="global-error-text">{{ globalError }}</span>
    <button class="global-error-close" type="button" aria-label="关闭提示" @click="dismissGlobalError">✕</button>
  </div>
  <!-- 离线门优先于登录门:断网时先告知不可用,恢复联网后再走登录/主界面 -->
  <OfflineView v-if="offline" @retry="retryConnection" />
  <!-- 登录门:服务端启用登录且未登录时替代主界面(静态资源公开,门只锁数据接口) -->
  <LoginView v-else-if="needsLogin" :busy="loginBusy" :error="loginError" @submit="onLogin" />
  <div
    v-else
    class="app-shell"
    :class="{ 'app-streaming': streaming, 'nav-open': navOpen, 'drawer-open': drawerOpen }"
  >
    <AppNav
      :page="page"
      :theme="theme"
      :auth-enabled="authEnabled"
      @navigate="goPage"
      @toggle-theme="toggleTheme"
      @logout="onLogout"
    />
    <!-- 窄屏导航抽屉遮罩 -->
    <button v-if="isNarrow" class="nav-mask" type="button" aria-label="关闭导航" @click="navOpen = false"></button>
    <!-- 窄屏会话抽屉遮罩（点击关闭） -->
    <button
      v-if="isNarrow"
      class="drawer-mask"
      type="button"
      aria-label="关闭会话列表"
      @click="drawerOpen = false"
    ></button>

    <div class="app-main">
      <!-- 窄屏顶部条：导航入口 + 品牌 + 当前页 -->
      <div v-if="isNarrow" class="mobile-bar">
        <button class="icon-btn" type="button" aria-label="打开导航" @click="navOpen = true">☰</button>
        <img class="mobile-logo" src="/deer_logo.png" alt="" />
        <span class="mobile-title">{{ pageTitle }}</span>
        <button
          v-if="page === 'chat'"
          ref="expandBtnEl"
          class="icon-btn mobile-action"
          type="button"
          aria-label="打开会话列表"
          title="会话列表"
          @click="onSidebarExpand"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01" />
          </svg>
        </button>
      </div>

      <DashboardView v-if="page === 'dashboard'" />

      <div v-else-if="page === 'chat'" class="chat-layout" :class="{ 'sessions-hidden': sessionsHidden }">
        <SessionList
          ref="sidebarEl"
          :sessions="sessions"
          :current-id="currentSessionId"
          :has-more="hasMore"
          :loading="sessionsLoading"
          @select="onSelect"
          @create="onCreate"
          @more="loadMore"
          @collapse="onSidebarCollapse"
          @settings="showTip('设置功能开发中')"
          @remove="onRemoveSession"
        />

        <section class="chat-pane">
          <!-- 主区顶栏：对话 / 轨迹 / 监控 三段式 + 右侧会话日志（对齐控制台参考图） -->
          <div class="chat-tabs" role="tablist">
            <button
              v-if="!isNarrow && sessionsHidden"
              class="icon-btn"
              type="button"
              title="展开会话列表"
              aria-label="展开会话列表"
              @click="onSidebarExpand"
            >☰</button>
            <button
              class="chat-tab"
              :class="{ 'chat-tab-active': chatTab === 'chat' }"
              type="button"
              role="tab"
              :aria-selected="chatTab === 'chat'"
              @click="chatTab = 'chat'"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2Z" />
              </svg>
              对话
            </button>
            <button
              class="chat-tab"
              :class="{ 'chat-tab-active': chatTab === 'trace' }"
              type="button"
              role="tab"
              :aria-selected="chatTab === 'trace'"
              @click="chatTab = 'trace'"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M3 12h4l2-6 4 12 2-6h6" />
              </svg>
              轨迹
            </button>
            <span v-if="tip" class="chat-tip" :class="{ 'chat-tip-error': tip.error }" role="status">{{
              tip.text
            }}</span>
            <div class="chat-tabs-right">
              <button
                class="session-log"
                type="button"
                title="下载会话日志(纯文本)"
                :disabled="currentSessionId === ''"
                @click="downloadSessionLog"
              >会话日志 ↓</button>
            </div>
          </div>

          <ChatWindow
            v-if="chatTab === 'chat'"
            :messages="messages"
            :streaming="streaming"
            :session-id="currentSessionId"
            @send="send"
            @stop="stop"
            @delete="deleteMessage"
            @regenerate="regenerate"
            @feedback="showTip('功能开发中')"
            @placeholder="showTip('功能开发中')"
          >
            <template #controls>
              <RoutingSelect v-model="selectedRouting" />
              <AgentSelect
                :agents="agents"
                :model-value="selectedAgentId"
                :disabled="currentSessionId === ''"
                @update:model-value="onAgentChange"
              />
            </template>
          </ChatWindow>
          <TraceView v-else :session-id="currentSessionId" />
        </section>
      </div>

      <MonitorView v-else />
    </div>
  </div>
</template>
