<!--
  消息滚动区（自 ChatWindow 拆出，2026-11-05）：消息气泡列表 + 空态引导 + 智能跟随滚动。
  智能跟随语义不变：仅当用户停在底部附近才自动滚底,上翻阅读时不被拉回。
-->
<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import MessageBubble from '../MessageBubble.vue'
import type { MessageItem } from '../../composables/useChat'
import { BRAND_MARK } from '../../utils/brand'

const props = defineProps<{ messages: MessageItem[]; streaming: boolean }>()
const emit = defineEmits<{
  send: [text: string]
  delete: [id: number]
  regenerate: [id: number]
  feedback: [type: string]
}>()

/** 空态 logo：public 资源要带 BASE_URL，Pages 子路径下才能加载 */

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

/** 空会话快捷提问(点击即发)；图标按顺序对应记忆/文档/流程/插件四类意图 */
const SUGGESTIONS = [
  '介绍一下这个项目的架构',
  '用 Python 写一个快速排序',
  'RAG 知识库是怎么工作的?',
  '帮我写一个单元测试示例',
]

const SUGGEST_ICONS: string[][] = [
  ['M6 3.8h7.5L18 8.2V20a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4.8a1 1 0 0 1 1-1Z', 'M13.2 4v4.4H18', 'M9 13h6', 'M9 16.5h4'],
  ['M4 5h16v14H4z', 'M8 9h8', 'M8 13h5'],
  ['M4 7h5v5H4z', 'M15 12h5v5h-5z', 'M9 9h3a3 3 0 0 1 3 3v2'],
  ['M9 7V4M15 7V4', 'M7 7h10v5a5 5 0 0 1-10 0z', 'M12 17v3'],
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
  <div ref="scrollEl" class="chat-scroll" @scroll="onScroll">
    <div v-if="messages.length === 0" class="chat-empty">
      <img class="chat-empty-logo" :src="BRAND_MARK" alt="" />
      <p class="chat-empty-title">开始新的对话</p>
      <p class="chat-empty-sub">在下方输入消息,或试试这些问题</p>
      <div class="chat-empty-suggest">
        <button
          v-for="(q, i) in SUGGESTIONS"
          :key="q"
          class="suggest-card"
          type="button"
          @click="emit('send', q)"
        >
          <span class="suggest-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24">
              <path v-for="d in SUGGEST_ICONS[i % SUGGEST_ICONS.length]" :key="d" :d="d" />
            </svg>
          </span>
          <span class="suggest-text">{{ q }}</span>
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
</template>
