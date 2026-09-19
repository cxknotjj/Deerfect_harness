/**
 * 消息气泡:
 * - user:右对齐浅色圆角气泡(纯文本,pre-wrap 保留换行)
 * - assistant:左对齐 markdown 渲染(marked.parse + DOMPurify.sanitize 后 v-html),
 *   hover 显现「复制」操作按钮
 * - progress:气泡上方「图标 + 灰字」行式执行轨迹(stage · detail)
 * - error:红色错误样式(错误文案纯文本展示)
 */
<script setup lang="ts">
import { computed, ref } from 'vue'
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

/** 复制原文到剪贴板,成功后按钮短暂变 ✓(剪贴板不可用时静默) */
const copied = ref(false)
let copyTimer: number | undefined
async function copyContent(): Promise<void> {
  try {
    await navigator.clipboard.writeText(props.message.content)
    copied.value = true
    window.clearTimeout(copyTimer)
    copyTimer = window.setTimeout(() => {
      copied.value = false
    }, 2000)
  } catch {
    /* 剪贴板不可用(非安全上下文/权限拒绝),忽略 */
  }
}
</script>

<template>
  <div class="msg-row" :class="message.role === 'user' ? 'msg-row-user' : 'msg-row-assistant'">
    <!-- assistant 消息头:mono 小字标注时间 -->
    <div v-if="message.role === 'assistant'" class="msg-meta">{{
      new Date(message.ts).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
    }}</div>

    <!-- 执行进度轨迹(图标 + 灰字行式) -->
    <div v-if="message.progress.length > 0" class="msg-progress">
      <span v-for="(p, i) in message.progress" :key="i" class="msg-progress-item">
        {{ p.stage }}<template v-if="p.detail"> · {{ p.detail }}</template>
      </span>
    </div>

    <!-- 错误消息:警示橙样式 -->
    <div v-if="message.error" class="msg-bubble msg-bubble-error">{{ message.content }}</div>

    <!-- user:右对齐浅色圆角小块 -->
    <div v-else-if="message.role === 'user'" class="msg-bubble msg-bubble-user">{{ message.content }}</div>

    <!-- assistant:markdown 渲染(内容已经 DOMPurify 净化)+ 复制按钮 -->
    <template v-else>
      <div class="msg-bubble msg-bubble-assistant md-body" v-html="html"></div>
      <div class="msg-actions">
        <button
          class="msg-action-btn"
          type="button"
          :title="copied ? '已复制' : '复制'"
          @click="copyContent"
        >{{ copied ? '✓' : '⧉' }}</button>
      </div>
    </template>
  </div>
</template>
