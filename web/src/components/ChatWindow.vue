/**
 * 聊天窗主体:消息滚动区(智能跟随:仅当用户停在底部附近才自动滚底,上翻阅读不被拉回)
 * + 底部输入卡片与状态栏。空消息时显示占位引导与快捷提问(点击即发)。
 * controls 插槽透传给 Composer 左下控制位(放 Agent 选择)。
 */
<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import MessageBubble from './MessageBubble.vue'
import Composer from './Composer.vue'
import type { MessageItem } from '../composables/useChat'

const props = defineProps<{ messages: MessageItem[]; streaming: boolean }>()
const emit = defineEmits<{
  send: [text: string]
  stop: []
  delete: [id: number]
  regenerate: [id: number]
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
      />
    </div>

    <footer class="composer-wrap">
      <Composer :disabled="streaming" @send="emit('send', $event)" @stop="emit('stop')">
        <template #controls><slot name="controls" /></template>
      </Composer>
      <!-- 状态栏:仅真实数据(消息数 / 流式状态),无会话内容时不显示 -->
      <div v-if="messages.length > 0" class="chat-statusbar">
        <span>{{ messages.length }} 条消息</span>
        <span :class="{ 'chat-statusbar-live': streaming }">{{ streaming ? '生成中…' : '空闲' }}</span>
      </div>
    </footer>
  </section>
</template>
