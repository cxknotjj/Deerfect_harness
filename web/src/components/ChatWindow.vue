/**
 * 聊天窗主体:消息滚动区(智能跟随:仅当用户停在底部附近才自动滚底,上翻阅读不被拉回)
 * + 底部输入卡片与分段式状态栏。空消息时显示占位引导与快捷提问(点击即发)。
 * controls 插槽透传给 Composer 右下控制位(放 Agent 选择);MessageBubble 的
 * feedback 与 Composer 的 placeholder 均向上冒泡,由 App 统一轻提示。
 */
<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import MessageBubble from './MessageBubble.vue'
import Composer from './Composer.vue'
import { api } from '../api'
import type { MessageItem } from '../composables/useChat'

const props = defineProps<{ messages: MessageItem[]; streaming: boolean; sessionId: string }>()
const emit = defineEmits<{
  send: [text: string]
  stop: []
  delete: [id: number]
  regenerate: [id: number]
  feedback: [type: string]
  placeholder: [label: string]
}>()

const scrollEl = ref<HTMLDivElement | null>(null)
/** 用户是否停在底部附近:是才自动跟随滚动;上翻阅读时不再强拉回底 */
const nearBottom = ref(true)

function onScroll(): void {
  const el = scrollEl.value
  if (el) nearBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 100
}

/** 消息新增 / token 追加 / 进度更新后,仅当停在底部附近时滚到底部 */
watch(
  () => props.messages,
  () => {
    if (!nearBottom.value) return
    void nextTick(() => {
      const el = scrollEl.value
      if (el) el.scrollTop = el.scrollHeight
    })
  },
  { deep: true },
)

/** 最近一次流式耗时:streaming 置 true 起表,false 结算(毫秒);无数据不显示分段 */
const lastElapsedMs = ref<number | null>(null)
let streamStartTs = 0
watch(
  () => props.streaming,
  (on) => {
    if (on) {
      streamStartTs = performance.now()
    } else if (streamStartTs > 0) {
      lastElapsedMs.value = Math.round(performance.now() - streamStartTs)
      streamStartTs = 0
    }
  },
)

/** 会话累计 token(观测端点汇总,与轨迹页同源):输入/输出分向;无会话不显示分段 */
const tokenFlow = ref<{ input: number; output: number } | null>(null)

async function loadTokens(): Promise<void> {
  if (props.sessionId === '') {
    tokenFlow.value = null
    return
  }
  try {
    const calls = await api.listLlmCalls(props.sessionId)
    let input = 0
    let output = 0
    for (const c of calls) {
      input += c.promptTokens ?? 0
      output += c.completionTokens ?? 0
    }
    tokenFlow.value = { input, output }
  } catch {
    /* 观测拉取失败静默:状态栏不因统计缺失报错 */
  }
}

watch(() => props.sessionId, loadTokens, { immediate: true })
// 流结束后刷新本轮消耗:观测落库在服务端异步执行,留 1s 缓冲再拉
watch(
  () => props.streaming,
  (on, old) => {
    if (old === true && on === false) setTimeout(loadTokens, 1000)
  },
)

/** token 数 → 紧凑计数:812 / 1.2k / 435.0k(与轨迹页口径一致) */
function fmtTokens(n: number): string {
  if (n < 1000) return String(n)
  return `${(n / 1000).toFixed(1)}k`
}

/** 空会话快捷提问(点击即发) */
const SUGGESTIONS = [
  '介绍一下这个项目的架构',
  '用 Python 写一个快速排序',
  'RAG 知识库是怎么工作的?',
  '帮我写一个单元测试示例',
]

/** 错误消息重试:重新发送最后一条 user 消息(新增气泡,与会话记忆语义一致) */
function onRetry(): void {
  if (props.streaming) return
  const lastUser = [...props.messages].reverse().find((m) => m.role === 'user')
  if (lastUser) {
    nearBottom.value = true
    emit('send', lastUser.content)
  }
}
</script>

<template>
  <section class="chat-window">
    <div ref="scrollEl" class="chat-scroll" @scroll="onScroll">
      <div v-if="messages.length === 0" class="chat-empty">
        <img class="chat-empty-logo" src="/deer_logo.png" alt="" />
        <p class="chat-empty-title">开始新的对话</p>
        <p class="chat-empty-sub">在下方输入消息,或试试这些问题</p>
        <div class="chat-empty-suggest">
          <button v-for="q in SUGGESTIONS" :key="q" class="suggest-chip" type="button" @click="emit('send', q)">
            {{ q }}
          </button>
        </div>
      </div>
      <!-- msg-row-streaming 落到最后一条消息上,CSS 据此追加流式光标 -->
      <MessageBubble
        v-for="m in messages"
        :key="m.id"
        :message="m"
        :streaming="streaming"
        :can-regenerate="m.role === 'assistant' && m.id === messages[messages.length - 1]?.id"
        :class="{ 'msg-row-streaming': streaming && m.id === messages[messages.length - 1]?.id }"
        @retry="onRetry"
        @delete="emit('delete', m.id)"
        @regenerate="emit('regenerate', m.id)"
        @feedback="emit('feedback', $event)"
      />
    </div>

    <footer class="composer-wrap">
      <Composer
        :disabled="streaming"
        @send="emit('send', $event)"
        @stop="emit('stop')"
        @placeholder="emit('placeholder', $event)"
      >
        <template #controls><slot name="controls" /></template>
      </Composer>
      <!-- 分段式状态栏:仅真实数据(消息数 / token 消耗 / 流式状态 / 最近耗时),无会话内容时不显示 -->
      <div v-if="messages.length > 0" class="chat-statusbar">
        <span>{{ messages.length }} 条消息</span>
        <template v-if="tokenFlow !== null">
          <span class="statusbar-sep">│</span>
          <span>输入 {{ fmtTokens(tokenFlow.input) }} tok</span>
          <span class="statusbar-sep">│</span>
          <span>输出 {{ fmtTokens(tokenFlow.output) }} tok</span>
        </template>
        <span class="statusbar-sep">│</span>
        <span :class="{ 'chat-statusbar-live': streaming }">{{ streaming ? '生成中…' : '空闲' }}</span>
        <template v-if="lastElapsedMs !== null">
          <span class="statusbar-sep">│</span>
          <span>上次 {{ (lastElapsedMs / 1000).toFixed(1) }}s</span>
        </template>
      </div>
    </footer>
  </section>
</template>
