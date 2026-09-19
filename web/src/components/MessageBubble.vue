/**
 * 消息气泡:
 * - user:右对齐纯文本(pre-wrap 保留换行)
 * - assistant:左对齐 markdown 渲染(marked.parse + DOMPurify.sanitize 后 v-html)
 * - progress:气泡上方小字灰色阶段徽标(stage · detail)
 * - error:红色错误样式(错误文案纯文本展示)
 */
<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import type { MessageItem } from '../composables/useChat'

// 聊天场景:markdown 单换行渲染为 <br>,阅读更自然
marked.setOptions({ breaks: true })

const props = defineProps<{ message: MessageItem }>()

/** assistant 正文 markdown → HTML(经 DOMPurify 净化);错误消息走纯文本样式,不渲染 markdown */
const html = computed(() => {
  const { role, content, error } = props.message
  if (role !== 'assistant' || error || content === '') return ''
  const parsed = marked.parse(content)
  // marked.parse 在非 async 配置下返回 string,此处类型收窄兜底
  return DOMPurify.sanitize(typeof parsed === 'string' ? parsed : '')
})
</script>

<template>
  <div class="msg-row" :class="message.role === 'user' ? 'msg-row-user' : 'msg-row-assistant'">
    <!-- 执行进度轨迹(小字灰色徽标) -->
    <div v-if="message.progress.length > 0" class="msg-progress">
      <span v-for="(p, i) in message.progress" :key="i" class="msg-progress-item">
        {{ p.stage }}<template v-if="p.detail"> · {{ p.detail }}</template>
      </span>
    </div>

    <!-- 错误消息:红色样式 -->
    <div v-if="message.error" class="msg-bubble msg-bubble-error">{{ message.content }}</div>

    <!-- user:右对齐纯文本 -->
    <div v-else-if="message.role === 'user'" class="msg-bubble msg-bubble-user">{{ message.content }}</div>

    <!-- assistant:markdown 渲染(内容已经 DOMPurify 净化) -->
    <div v-else class="msg-bubble msg-bubble-assistant md-body" v-html="html"></div>
  </div>
</template>
