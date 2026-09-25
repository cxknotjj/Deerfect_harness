/**
 * 消息气泡:
 * - user:右对齐浅色圆角气泡(纯文本,pre-wrap 保留换行)
 * - assistant:左对齐 markdown 渲染(marked.parse + DOMPurify.sanitize 后 v-html);
 *   代码块带语言标签与复制按钮(v-html 内按钮走事件委托);常显操作栏
 *   (复制/赞/踩/分享 + 右侧相对时间,赞踩分享仅 emit 不弹提示)
 * - progress:气泡上方「图标 + 灰字」行式执行轨迹(stage · detail)
 * - error:红色错误样式 + 「重试」按钮(交由父级重发最后一条 user 消息)
 */
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import type { MessageItem } from '../composables/useChat'
import { relativeTime } from '../utils/relativeTime'

// 聊天场景:markdown 单换行渲染为 <br>,阅读更自然
marked.setOptions({ breaks: true })

const props = defineProps<{ message: MessageItem; streaming?: boolean; canRegenerate?: boolean }>()
const emit = defineEmits<{ retry: []; delete: []; regenerate: []; feedback: [type: string] }>()

/** 单次 markdown 渲染(marked.parse + DOMPurify 净化 + 代码块包装) */
function renderHtml(): string {
  const { role, content, error } = props.message
  if (role !== 'assistant' || error || content === '') return ''
  const parsed = marked.parse(content)
  // marked.parse 在非 async 配置下返回 string,此处类型收窄兜底
  return wrapCodeBlocks(DOMPurify.sanitize(typeof parsed === 'string' ? parsed : ''))
}

/** 流式节流渲染(优化审查 2026-09-25 中高危项):流式期间每个 token 都对全文重跑
 *  parse+sanitize 是 O(n²),长回复越写越卡。改为 ~120ms 节流增量刷新——
 *  流式中的内容延迟一拍无感,流结束(streaming 翻 false)立即渲染最终全文 */
const RENDER_INTERVAL_MS = 120
const html = ref('')
let renderTimer: number | undefined
function renderNow(): void {
  window.clearTimeout(renderTimer)
  renderTimer = undefined
  html.value = renderHtml()
}
watch(
  () => [props.message.content, props.message.error, props.streaming],
  () => {
    if (props.streaming) {
      if (renderTimer === undefined) {
        renderTimer = window.setTimeout(renderNow, RENDER_INTERVAL_MS)
      }
    } else {
      renderNow()
    }
  },
  { immediate: true },
)
onUnmounted(() => window.clearTimeout(renderTimer))

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

    <!-- assistant:markdown 渲染(已净化)+ 代码块复制(委托)+ 常显操作栏(复制/赞/踩/分享 + 相对时间) -->
    <template v-else>
      <div class="msg-bubble msg-bubble-assistant md-body" @click="onBodyClick" v-html="html"></div>
      <div class="msg-actions">
        <button
          class="msg-action-btn"
          type="button"
          :title="copied ? '已复制' : '复制'"
          @click="copyContent"
        >{{ copied ? '✓' : '⧉' }}</button>
        <!-- 赞/踩/分享为占位:仅 emit feedback(like/dislike/share),提示由外层统一处理 -->
        <button class="msg-action-btn" type="button" title="赞" @click="emit('feedback', 'like')">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3zM7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"/></svg>
        </button>
        <button class="msg-action-btn" type="button" title="踩" @click="emit('feedback', 'dislike')">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3zM17 2h2.67A2.31 2.31 0 0 1 22 4v7a2.31 2.31 0 0 1-2.33 2H17"/></svg>
        </button>
        <button class="msg-action-btn" type="button" title="分享" @click="emit('feedback', 'share')">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 17 17 7"/><path d="M8 7h9v9"/></svg>
        </button>
        <!-- 历史回显 ts=0,无原始时间故不展示 -->
        <span v-if="message.ts > 0" class="msg-time">{{ relativeTime(message.ts) }}</span>
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
