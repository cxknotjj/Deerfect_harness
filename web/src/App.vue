/**
 * 应用骨架:三段布局 —— 左栏会话列表(260px)+ 右侧聊天窗(顶栏 AgentSelect + 消息区 + Composer)。
 * 组合层只做联动:
 * - 切换/新建会话 → showChat(id) 把聊天视图切到该会话的消息桶(消息按会话保留,不清空),
 *   并重置 agent 选择(流式 onMeta 回写 sessionId 不经过此处,不会误清)
 * - onMeta 报告后端新建会话 → useChat 内已迁移消息桶,此处回写 sessionId 并刷新列表首页
 * - Agent 选中变化 → bindAgent(会话, 下标+1),成功/失败均以顶栏轻提示呈现(3 秒自动消失)
 */
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import SessionList from './components/SessionList.vue'
import ChatWindow from './components/ChatWindow.vue'
import AgentSelect from './components/AgentSelect.vue'
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
} = useSessions()

const { messages, streaming, send, stop, show: showChat } = useChat({
  getSessionId: () => currentSessionId.value,
  setSessionId: (id) => {
    currentSessionId.value = id
  },
  onNewSession: () => {
    void loadFirst()
  },
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

/** 选中会话:聊天视图切到该会话(消息按会话保留),重置 agent 选择 */
function onSelect(id: string): void {
  if (id === currentSessionId.value) return
  selectSession(id)
  showChat(id)
  selectedAgentId.value = null
  clearTip()
}

/** 新建会话:成功后自动选中(useSessions.create)并把聊天视图切到新会话空桶 */
async function onCreate(): Promise<void> {
  try {
    await createSession()
    showChat(currentSessionId.value)
    selectedAgentId.value = null
  } catch (e) {
    showTip(`新建会话失败:${errText(e)}`, true)
  }
}

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

/** 主题切换:夜间(Telemetry Dark,默认)⇄ 日间(蓝图图纸);持久化 localStorage */
const theme = ref<'dark' | 'light'>(document.documentElement.dataset.theme === 'light' ? 'light' : 'dark')
function toggleTheme(): void {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
  localStorage.setItem('web-theme', theme.value)
  document.documentElement.dataset.theme = theme.value
  document.documentElement.style.colorScheme = theme.value
}
</script>

<template>
  <div class="app-shell" :class="{ 'app-streaming': streaming }">
    <SessionList
      :sessions="sessions"
      :current-id="currentSessionId"
      :has-more="hasMore"
      :loading="sessionsLoading"
      @select="onSelect"
      @create="onCreate"
      @more="loadMore"
    />

    <section class="chat-pane">
      <header class="chat-header">
        <AgentSelect
          :agents="agents"
          :model-value="selectedAgentId"
          :disabled="currentSessionId === ''"
          @update:model-value="onAgentChange"
        />
        <!-- 轻提示:无条件渲染容器,仅由 tip 是否为空决定显隐,避免被条件渲染链路吞掉 -->
        <span v-if="tip" class="chat-tip" :class="{ 'chat-tip-error': tip.error }" role="status">{{
          tip.text
        }}</span>
        <!-- 主题切换:夜间显示 ☀(进日间),日间显示 ☾(回夜间) -->
        <button
          class="theme-toggle"
          type="button"
          :title="theme === 'dark' ? '切换日间模式' : '切换夜间模式'"
          @click="toggleTheme"
        >
          {{ theme === 'dark' ? '☀' : '☾' }}
        </button>
      </header>

      <ChatWindow :messages="messages" :streaming="streaming" @send="send" @stop="stop" />
    </section>
  </div>
</template>
