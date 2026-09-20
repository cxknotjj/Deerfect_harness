/**
 * 消息气泡:
 * - user:右对齐浅色圆角气泡(纯文本,pre-wrap 保留换行)
 * - assistant:左对齐 markdown 渲染(marked.parse + DOMPurify.sanitize 后 v-html);
 *   代码块带语言标签与复制按钮(v-html 内按钮走事件委托);hover 显现「复制原文」
 * - progress:气泡上方「图标 + 灰字」行式执行轨迹(stage · detail)
 * - error:红色错误样式 + 「重试」按钮(交由父级重发最后一条 user 消息)
 */
<script setup lang="ts">
import { computed, ref } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import type { MessageItem } from '../composables/useChat'

// 聊天场景:markdown 单换行渲染为 <br>,阅读更自然
marked.setOptions({ breaks: true })

const props = defineProps<{ message: MessageItem; streaming?: boolean; canRegenerate?: boolean }>()
const emit = defineEmits<{ retry: []; delete: []; regenerate: [] }>()

/** assistant 正文 markdown → HTML(经 DOMPurify 净化后再包装代码块头部) */
const html = computed(() => {
  const { role, content, error } = props.message
  if (role !== 'assistant' || error || content === '') return ''
  const parsed = marked.parse(content)
  // marked.parse 在非 async 配置下返回 string,此处类型收窄兜底
  return wrapCodeBlocks(DOMPurify.sanitize(typeof parsed === 'string' ? parsed : ''))
})

/** 给每个代码块包一层头部(语言标签 + 复制按钮);lang 做字符白名单收敛防注入 */
function wrapCodeBlocks(html: string): string {
  return html
    .replace(/<pre><code([^>]*)>/g, (_, attrs: string) => {
      const lang = (/language-([\w+#.-]+)/.exec(attrs)?.[1] ?? 'text').toLowerCase()
      return (
        '<div class="code-block"><div class="code-block-head">' +
        `<span class="code-lang">${lang}</span>` +
        '<button type="button" class="code-copy">复制</button></div>' +
        `<pre><code${attrs}>`
      )
    })
    .replace(/<\/code><\/pre>/g, '</code></pre></div>')
}

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

/** v-html 内代码块复制按钮的事件委托 */
let codeTimer: number | undefined
async function onBodyClick(e: MouseEvent): Promise<void> {
  const btn = (e.target as HTMLElement).closest('.code-copy')
  if (!(btn instanceof HTMLButtonElement)) return
  const pre = btn.closest('.code-block')?.querySelector('pre')
  if (!pre) return
  try {
    await navigator.clipboard.writeText(pre.textContent ?? '')
    btn.textContent = '已复制'
    window.clearTimeout(codeTimer)
    codeTimer = window.setTimeout(() => {
      btn.textContent = '复制'
    }, 1500)
  } catch {
    /* 剪贴板不可用,忽略 */
  }
}
</script>

<template>
  <div class="msg-row" :class="message.role === 'user' ? 'msg-row-user' : 'msg-row-assistant'">
    <!-- assistant 消息头:mono 小字标注时间(历史回显 ts=0,无原始时间故不展示) -->
    <div v-if="message.role === 'assistant' && message.ts > 0" class="msg-meta">{{
      new Date(message.ts).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
    }}</div>

    <!-- 执行进度轨迹(图标 + 灰字行式) -->
    <div v-if="message.progress.length > 0" class="msg-progress">
      <span v-for="(p, i) in message.progress" :key="i" class="msg-progress-item">
        {{ p.stage }}<template v-if="p.detail"> · {{ p.detail }}</template>
      </span>
    </div>

    <!-- 错误消息:警示橙样式 + 重试 -->
    <template v-if="message.error">
      <div class="msg-bubble msg-bubble-error">{{ message.content }}</div>
      <div class="msg-actions">
        <button class="msg-retry" type="button" :disabled="streaming" @click="emit('retry')">↻ 重试</button>
      </div>
    </template>

    <!-- user:右对齐浅色圆角小块 + 复制/删除(hover 显现) -->
    <template v-else-if="message.role === 'user'">
      <div class="msg-bubble msg-bubble-user">{{ message.content }}</div>
      <div class="msg-actions">
        <button
          class="msg-action-btn"
          type="button"
          :title="copied ? '已复制' : '复制'"
          @click="copyContent"
        >{{ copied ? '✓' : '⧉' }}</button>
        <button class="msg-action-btn" type="button" title="删除这轮对话" @click="emit('delete')">✕</button>
      </div>
    </template>

    <!-- assistant:markdown 渲染(已净化)+ 代码块复制(委托)+ 复制原文 / 重新生成 -->
    <template v-else>
      <div class="msg-bubble msg-bubble-assistant md-body" @click="onBodyClick" v-html="html"></div>
      <div class="msg-actions">
        <button
          class="msg-action-btn"
          type="button"
          :title="copied ? '已复制' : '复制'"
          @click="copyContent"
        >{{ copied ? '✓' : '⧉' }}</button>
        <!-- 仅最后一条回复可重新生成:复用其上一条提问原地重跑 -->
        <button
          v-if="canRegenerate"
          class="msg-action-btn"
          type="button"
          :title="streaming ? '生成中…' : '重新生成'"
          :disabled="streaming"
          @click="emit('regenerate')"
        >↻</button>
      </div>
    </template>
  </div>
</template>
