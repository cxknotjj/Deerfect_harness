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
import { computed, onUnmounted, ref, watch } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import type { MessageItem, ThinkingBlock } from '../composables/useChat'
import type { ProgressPayload } from '../api'
import { relativeTime } from '../utils/relativeTime'

// 聊天场景:markdown 单换行渲染为 <br>,阅读更自然
marked.setOptions({ breaks: true })

const props = defineProps<{ message: MessageItem; streaming?: boolean; canRegenerate?: boolean }>()
const emit = defineEmits<{ retry: []; delete: []; regenerate: []; feedback: [type: string] }>()

/** 思考折叠块的展开集合(按 stage 单选切换;默认全部折叠) */
const expandedThinking = ref(new Set<string>())
function toggleThinking(stage: string): void {
  const next = new Set(expandedThinking.value)
  if (next.has(stage)) next.delete(stage)
  else next.add(stage)
  expandedThinking.value = next
}
/** 展开态样式判定 */
function isThinkingOpen(stage: string): boolean {
  return expandedThinking.value.has(stage)
}
/** 折叠块标题:工具块(tool 前缀,协议 base 英文)显示层中文化「工具N · 专家名」;流式进行中
 * (该块为最后一个思考块且正在流式)按块类型显示「思考中…/执行中…」,否则显示 stage */
function thinkingTitle(stage: string, index: number): string {
  const title = stage.startsWith('tool') ? '工具' + stage.slice(4) : stage
  const running = stage.startsWith('tool') ? '执行中…' : '思考中…'
  const isLast = index === (props.message.thinking?.length ?? 0) - 1
  return props.streaming && isLast ? `${title} · ${running}` : title
}

/** 混排时间线项:进度行原样;思考块携带原始下标(thinkingTitle 的「最后一个块」语义依赖它) */
type TimelineItem =
  | { kind: 'progress'; p: ProgressPayload; i: number }
  | { kind: 'thinking'; t: ThinkingBlock; i: number }

/** 思考块的锚点行 stage:lead 思考跟「编排」行、子任务思考(思考N)与子任务工具块(toolN)
 *  跟「拆解」行(同组保持到达序)、聚合思考跟「聚合」行;无锚点(主回答思考等)返回 null 挂尾部 */
function anchorStageOf(t: ThinkingBlock): string | null {
  if (t.stage === '思考 · lead') return '编排'
  if (t.stage === '思考 · 聚合') return '聚合'
  if (/^思考\d+ · /.test(t.stage) || t.stage.startsWith('tool')) return '拆解'
  return null
}

/**
 * 执行轨迹混排:思考折叠块不再堆在轨迹尾部,而是插入到触发它的进度行之后
 * (lead 思考在「编排 · 开始拆解」下方、思考N 在「拆解 · 子任务已就绪」下方),
 * 与执行的时序语义一致。锚点行尚未出现(流式中思考先行)或无锚点的块兜底挂尾部;
 * 纯视图顺序编排,不改 progress/thinking 数据结构。
 */
const timeline = computed<TimelineItem[]>(() => {
  const anchored = new Map<string, Array<{ t: ThinkingBlock; i: number }>>()
  const used = new Set<ThinkingBlock>()
  props.message.thinking.forEach((t, i) => {
    const anchor = anchorStageOf(t)
    if (anchor == null) return
    const list = anchored.get(anchor)
    if (list) list.push({ t, i })
    else anchored.set(anchor, [{ t, i }])
  })
  const items: TimelineItem[] = []
  props.message.progress.forEach((p, i) => {
    items.push({ kind: 'progress', p, i })
    const list = anchored.get(p.stage)
    if (list) {
      for (const { t, i: idx } of list) {
        items.push({ kind: 'thinking', t, i: idx })
        used.add(t)
      }
      anchored.delete(p.stage)
    }
  })
  // 尾部兜底:无锚点的思考块 + 锚点行未到达的块(流式中先到先显)
  props.message.thinking.forEach((t, i) => {
    if (!used.has(t)) items.push({ kind: 'thinking', t, i })
  })
  return items
})

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

    <!-- 执行轨迹混排(图标 + 灰字行式):思考折叠块插入到触发它的进度行之后
         (lead 思考跟「编排」行、思考N 跟「拆解」行、聚合思考跟「聚合」行),
         无锚点的块(主回答思考/工具块/锚点行未到达)挂尾部 -->
    <div v-if="timeline.length > 0" class="msg-progress">
      <template v-for="item in timeline" :key="item.kind === 'progress' ? `p${item.i}` : item.t.stage">
        <span v-if="item.kind === 'progress'" class="msg-progress-item">
          {{ item.p.stage }}<template v-if="item.p.detail"> · {{ item.p.detail }}</template>
        </span>
        <div
          v-else
          class="msg-thinking-block"
          :class="{ 'is-open': isThinkingOpen(item.t.stage) }"
        >
          <button
            class="msg-thinking-head"
            type="button"
            :aria-expanded="isThinkingOpen(item.t.stage)"
            @click="toggleThinking(item.t.stage)"
          >{{ thinkingTitle(item.t.stage, item.i) }}</button>
          <pre v-if="isThinkingOpen(item.t.stage)" class="msg-thinking-body">{{ item.t.content }}</pre>
        </div>
      </template>
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
          :aria-label="copied ? '已复制' : '复制'"
          @click="copyContent"
        >{{ copied ? '✓' : '⧉' }}</button>
        <button class="msg-action-btn" type="button" title="删除这轮对话" aria-label="删除这轮对话" @click="emit('delete')">✕</button>
      </div>
    </template>

    <!-- assistant:markdown 渲染(已净化)+ 代码块复制(委托)+ 常显操作栏(复制/赞/踩/分享 + 相对时间) -->
    <template v-else>
      <div class="msg-bubble msg-bubble-assistant md-body" @click="onBodyClick" v-html="html"></div>
      <!-- RAG 知识引用:流末 meta 回传(本轮命中知识库才展示),回答结束后可感知 RAG 是否执行 -->
      <div v-if="message.sources.length > 0" class="msg-sources" title="本轮回答引用的知识库片段">
        <span class="msg-sources-label">知识引用</span>
        <span v-for="(s, i) in message.sources" :key="i" class="msg-source-tag" :title="s.title">
          {{ s.docName }}<template v-if="s.score != null"> · {{ (s.score * 100).toFixed(0) }}%</template>
        </span>
      </div>
      <div class="msg-actions">
        <button
          class="msg-action-btn"
          type="button"
          :title="copied ? '已复制' : '复制'"
          :aria-label="copied ? '已复制' : '复制'"
          @click="copyContent"
        >{{ copied ? '✓' : '⧉' }}</button>
        <!-- 赞/踩/分享为占位:仅 emit feedback(like/dislike/share),提示由外层统一处理 -->
        <button class="msg-action-btn" type="button" title="赞" aria-label="赞" @click="emit('feedback', 'like')">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M14 9V5a3 3 0 0 0-3-3l-4 9v11h11.28a2 2 0 0 0 2-1.7l1.38-9a2 2 0 0 0-2-2.3zM7 22H4a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2h3"/></svg>
        </button>
        <button class="msg-action-btn" type="button" title="踩" aria-label="踩" @click="emit('feedback', 'dislike')">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 15v4a3 3 0 0 0 3 3l4-9V2H5.72a2 2 0 0 0-2 1.7l-1.38 9a2 2 0 0 0 2 2.3zM17 2h2.67A2.31 2.31 0 0 1 22 4v7a2.31 2.31 0 0 1-2.33 2H17"/></svg>
        </button>
        <button class="msg-action-btn" type="button" title="分享" aria-label="分享" @click="emit('feedback', 'share')">
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
          :aria-label="streaming ? '生成中' : '重新生成'"
          :disabled="streaming"
          @click="emit('regenerate')"
        >↻</button>
      </div>
    </template>
  </div>
</template>
