/**
 * 聊天窗主体（超长组件拆分 2026-11-05）：只负责装配三块视图与透传事件。
 * - 消息滚动区（空态引导 / 智能跟随滚动 / 消息气泡）→ chat/ChatMessageList
 * - 输入卡片 → Composer（controls 插槽透传 App 的 Agent 选择）
 * - 分段式状态栏（消息数 / token / 流式状态 / 最近耗时）→ chat/ChatStatusBar
 * MessageBubble 的 feedback 与 Composer 的 placeholder 均向上冒泡,由 App 统一轻提示。
 */
<script setup lang="ts">
import Composer from './Composer.vue'
import ChatMessageList from './chat/ChatMessageList.vue'
import ChatStatusBar from './chat/ChatStatusBar.vue'
import type { MessageItem } from '../composables/useChat'

defineProps<{ messages: MessageItem[]; streaming: boolean; sessionId: string }>()
const emit = defineEmits<{
  send: [text: string]
  stop: []
  delete: [id: number]
  regenerate: [id: number]
  feedback: [type: string]
  placeholder: [label: string]
}>()
</script>

<template>
  <section class="chat-window">
    <ChatMessageList
      :messages="messages"
      :streaming="streaming"
      @send="emit('send', $event)"
      @delete="emit('delete', $event)"
      @regenerate="emit('regenerate', $event)"
      @feedback="emit('feedback', $event)"
    />

    <footer class="composer-wrap">
      <Composer
        :disabled="streaming"
        :session-id="sessionId"
        @send="emit('send', $event)"
        @stop="emit('stop')"
        @placeholder="emit('placeholder', $event)"
      >
        <template #controls><slot name="controls" /></template>
      </Composer>
      <ChatStatusBar :messages="messages" :streaming="streaming" :session-id="sessionId" />
    </footer>
  </section>
</template>
