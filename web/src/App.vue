/**
 * 应用骨架:三段布局 —— 左栏会话列表(260px)+ 右侧聊天窗(顶栏会话标题 + 模式标签 +
 * Session log + 对话/轨迹 tab + 消息区 + 卡片式 Composer + 分段状态栏)。
 * 组合层只做联动:
 * - 切换/新建会话 → showChat(id):把聊天视图切到该会话的消息桶(先用 localStorage 缓存即时渲染,
 *   再拉服务端历史覆盖),并重置 agent 选择(流式 onMeta 回写 sessionId 不经过此处,不会误清)
 * - onMeta 报告后端新建会话 → useChat 内已迁移消息桶,此处回写 sessionId 并刷新列表首页
 * - Agent 选中变化 → bindAgent(会话, agent 真实主键),成功/失败均以顶栏轻提示呈现(3 秒自动消失);
 * - 窄屏(≤768px):左侧 56px 图标竖条栏,侧栏转抽屉模式(遮罩 + 滑入滑出),桌面内联折叠不变;
 *   选中/新建会话后自动收起。AgentSelect 位于 Composer 控制位(对齐截图模型选择位)
 * - 占位交互(附件/工作区权限/消息反馈/搜索/设置)统一顶栏轻提示「功能开发中」
 */
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import SessionList from './components/SessionList.vue'
import ChatWindow from './components/ChatWindow.vue'
import AgentSelect from './components/AgentSelect.vue'
import TraceView from './components/TraceView.vue'
import { useSessions } from './composables/useSessions'
import { useChat } from './composables/useChat'
import { useAgents } from './composables/useAgents'

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
} = useSessions()

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
  onNewSession: () => {
    void loadFirst()
  },
  // 占位会话名自动改名(与服务端 touchSession 同口径):首轮成功后把「新会话」替换为首条提问
  onRoundSucceeded: (userText) => renameIfPlaceholder(currentSessionId.value, userText),
})

const { agents, load: loadAgents, bind: bindAgentTo } = useAgents()

onMounted(() => {
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

/** 选中会话:聊天视图切到该会话(消息按会话保留),重置 agent 选择;窄屏自动收抽屉 */
function onSelect(id: string): void {
  if (isNarrow.value) drawerOpen.value = false
  if (id === currentSessionId.value) return
  selectSession(id)
  showChat(id)
  selectedAgentId.value = null
  activeTab.value = 'chat' // 切会话回对话视图(轨迹按会话惰性加载,切回即看)
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
    showChat(currentSessionId.value)
    selectedAgentId.value = null
    activeTab.value = 'chat' // 新会话从空轨迹页等视图回到对话
    if (isNarrow.value) drawerOpen.value = false
  } catch (e) {
    showTip(`新建会话失败:${errText(e)}`, true)
  }
}

/** 顶栏标题:当前会话名(会话未选中/已删除时回退「新对话」) */
const currentSessionName = computed(
  () => sessions.value.find((s) => s.id === currentSessionId.value)?.name ?? '新对话',
)

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

/** 侧栏收起/展开(收起后入口在顶栏左侧;仅桌面内联折叠,窄屏走抽屉) */
const sidebarCollapsed = ref(false)

/** 窄屏判定(≤768px):侧栏由内联折叠切换为抽屉模式(遮罩 + 滑入滑出) */
const narrowMql = window.matchMedia('(max-width: 768px)')
const isNarrow = ref(narrowMql.matches)
function onNarrowChange(e: MediaQueryListEvent): void {
  isNarrow.value = e.matches
  // 模式切换时互斥状态复位:桌面折叠/抽屉展开互不残留
  if (e.matches) {
    sidebarCollapsed.value = false
    drawerOpen.value = false
  } else {
    drawerOpen.value = false
  }
}
narrowMql.addEventListener('change', onNarrowChange)

/** 抽屉开合(仅窄屏生效;桌面端该状态无样式效果) */
const drawerOpen = ref(false)

/** 侧栏头部收起钮:窄屏=收抽屉;桌面=内联折叠 */
function onSidebarCollapse(): void {
  if (isNarrow.value) drawerOpen.value = false
  else sidebarCollapsed.value = true
}

/** 顶栏展开钮:窄屏常显(侧栏常态离屏)点击开抽屉;桌面仅折叠态显示 */
function onSidebarExpand(): void {
  if (isNarrow.value) drawerOpen.value = true
  else sidebarCollapsed.value = false
}

/** Escape 关抽屉(窄屏可达性) */
function onGlobalKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape' && drawerOpen.value) drawerOpen.value = false
}
window.addEventListener('keydown', onGlobalKeydown)
onBeforeUnmount(() => {
  narrowMql.removeEventListener('change', onNarrowChange)
  window.removeEventListener('keydown', onGlobalKeydown)
})

/** 主题切换:夜间(Telemetry Dark,默认)⇄ 日间(DeepSeek 蓝白);持久化 localStorage,按钮在顶栏右上角 */
const theme = ref<'dark' | 'light'>(document.documentElement.dataset.theme === 'light' ? 'light' : 'dark')
function toggleTheme(): void {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
  localStorage.setItem('web-theme', theme.value)
  document.documentElement.dataset.theme = theme.value
  document.documentElement.style.colorScheme = theme.value
}

/** 对话/轨迹 tab(轨迹为占位) */
const activeTab = ref<'chat' | 'trace'>('chat')

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
  <div
    class="app-shell"
    :class="{ 'app-streaming': streaming, 'sidebar-collapsed': sidebarCollapsed, 'drawer-open': drawerOpen }"
  >
    <!-- 窄屏左侧图标竖条栏:新会话/搜索占位 + 底部设置占位(桌面端隐藏;
         历史入口与顶栏侧栏开关重复,不设) -->
    <nav v-if="isNarrow" class="icon-rail">
      <img class="rail-logo" src="/deer_logo.png" alt="" />
      <button class="rail-btn" type="button" title="新会话" @click="onCreate">+</button>
      <button class="rail-btn" type="button" title="搜索" @click="showTip('功能开发中')">
        <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="11" cy="11" r="7" fill="none" stroke="currentColor" stroke-width="1.8" />
          <path d="m20 20-4.35-4.35" stroke="currentColor" stroke-width="1.8" fill="none" stroke-linecap="round" />
        </svg>
      </button>
      <button class="rail-btn rail-btn-bottom" type="button" title="设置" @click="showTip('功能开发中')">⚙</button>
    </nav>
    <!-- 窄屏抽屉遮罩:点击关闭(桌面端不渲染) -->
    <button
      v-if="isNarrow"
      class="drawer-mask"
      type="button"
      aria-label="关闭侧栏"
      @click="drawerOpen = false"
    ></button>
    <SessionList
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
      <header class="chat-header">
        <!-- 侧栏收起时的展开入口 -->
        <button
          v-if="sidebarCollapsed || isNarrow"
          class="icon-btn"
          type="button"
          :title="isNarrow ? '打开侧栏' : '展开侧栏'"
          @click="onSidebarExpand"
        >☰</button>
        <span class="chat-title">{{ currentSessionName }}</span>
        <!-- 轻提示:无条件渲染容器,仅由 tip 是否为空决定显隐,避免被条件渲染链路吞掉 -->
        <span v-if="tip" class="chat-tip" :class="{ 'chat-tip-error': tip.error }" role="status">{{
          tip.text
        }}</span>
        <!-- 右上控制组:会话日志下载(无会话时置灰)+ 主题切换 -->
        <div class="chat-header-right">
          <button
            class="session-log"
            type="button"
            title="下载会话日志(纯文本)"
            :disabled="currentSessionId === ''"
            @click="downloadSessionLog"
          >Session log ↓</button>
          <button
            v-if="!sidebarCollapsed"
            class="icon-btn"
            type="button"
            :title="theme === 'dark' ? '切换日间模式' : '切换夜间模式'"
            @click="toggleTheme"
          >{{ theme === 'dark' ? '☀' : '☾' }}</button>
        </div>
      </header>

      <!-- 对话/轨迹 tab(轨迹为占位) -->
      <div class="chat-tabs">
        <button
          class="chat-tab"
          :class="{ 'chat-tab-active': activeTab === 'chat' }"
          type="button"
          @click="activeTab = 'chat'"
        >对话</button>
        <button
          class="chat-tab"
          :class="{ 'chat-tab-active': activeTab === 'trace' }"
          type="button"
          @click="activeTab = 'trace'"
        >轨迹</button>
      </div>

      <ChatWindow
        v-if="activeTab === 'chat'"
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
</template>
