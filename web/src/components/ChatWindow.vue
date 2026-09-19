/**
 * 聊天窗主体:消息滚动区(任何消息变化自动滚底)+ 底部输入卡片与状态栏。
 * 空消息时显示占位引导(切换会话后 API 无历史端点,统一空窗)。
 * controls 插槽透传给 Composer 左下控制位(放 Agent 选择)。
 */
<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import MessageBubble from './MessageBubble.vue'
import Composer from './Composer.vue'
import type { MessageItem } from '../composables/useChat'

const props = defineProps<{ messages: MessageItem[]; streaming: boolean }>()
const emit = defineEmits<{ send: [text: string]; stop: [] }>()

const scrollEl = ref<HTMLDivElement | null>(null)

/** 消息新增 / token 追加 / 进度更新后滚到底部(getter 源 + deep 捕获引用替换与内部变更) */
watch(
  () => props.messages,
  () => {
    void nextTick(() => {
      const el = scrollEl.value
      if (el) el.scrollTop = el.scrollHeight
    })
  },
  { deep: true },
)
</script>

<template>
  <section class="chat-window">
    <div ref="scrollEl" class="chat-scroll">
      <div v-if="messages.length === 0" class="chat-empty">
        <img class="chat-empty-logo" src="/deer_logo.png" alt="" />
        <p class="chat-empty-title">开始新的对话</p>
        <p class="chat-empty-sub">在下方输入消息发送,或从左侧选择会话</p>
      </div>
      <!-- msg-row-streaming 落到最后一条消息上,CSS 据此追加流式光标 -->
      <MessageBubble
        v-for="m in messages"
        :key="m.id"
        :message="m"
        :class="{ 'msg-row-streaming': streaming && m.id === messages[messages.length - 1]?.id }"
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
