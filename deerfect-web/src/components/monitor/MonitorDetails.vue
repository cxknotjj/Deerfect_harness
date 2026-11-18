<script setup lang="ts">
/**
 * 监控·明细：三张统计卡 + 一个「明细数据」面板（活跃会话 / LLM / 工具三段折叠）。
 *
 * 与旧版（三个独立折叠卡）相比，这里合并成一张面板：折叠行带数量胶囊与说明，
 * 展开后表格缩进在行下方。
 *
 * 「上下文构成」改为中心弹窗：环状图（四组占比）+ 右侧明细排行；支持遮罩/Esc 关闭、
 * 焦点回弹与 Tab 圈定，复制明细到剪贴板。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../../api'
import type { MonitorContextComposition, MonitorSnapshot } from '../../api'
import {
  channelLabel,
  fmtClock,
  fmtMs,
  fmtNumber,
  fmtTime,
  statusClass,
} from '../../utils/monitorFormat'

const props = defineProps<{
  data: MonitorSnapshot
  sessionsOpen: boolean
  llmOpen: boolean
  toolsOpen: boolean
}>()
const emit = defineEmits<{ toggle: [key: 'sessions' | 'llm' | 'tools' | 'all'] }>()

const allOpen = computed(() => props.sessionsOpen && props.llmOpen && props.toolsOpen)

const stats = computed(() => [
  {
    key: 'sessions',
    label: '活跃会话',
    value: props.data.sessions.length,
    note: '时间窗内最近活跃',
    icon: ['M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z'],
  },
  {
    key: 'llm',
    label: '最近 LLM 调用',
    value: props.data.llmCalls.length,
    note: '按调用时间倒序',
    icon: ['M12 3 3 8l9 5 9-5-9-5z', 'M3 8v8l9 5 9-5V8'],
  },
  {
    key: 'tools',
    label: '最近工具调用',
    value: props.data.toolCalls.length,
    note: '外部与内置工具的执行记录',
    icon: ['M14.7 6.3a4 4 0 0 0 5 5L10.3 20.7a2 2 0 0 1-2.8-2.8z', 'm14 7 3 3'],
  },
])

/* ---------- 上下文构成（按需拉取，序号丢弃过期响应） ---------- */
const contextSessionId = ref('')
const contextData = ref<MonitorContextComposition | null>(null)
const contextLoading = ref(false)
const contextError = ref('')
let contextSeq = 0

async function loadContext(sessionId: string): Promise<void> {
  const sid = String(sessionId)
  const seq = ++contextSeq
  contextSessionId.value = sid
  contextLoading.value = true
  contextError.value = ''
  try {
    const resp = await api.monitorContext(sid)
    if (seq !== contextSeq) return
    contextData.value = resp
  } catch (e) {
    if (seq === contextSeq) {
      contextData.value = null
      contextError.value = `上下文快照加载失败：${e instanceof Error ? e.message : String(e)}`
    }
  } finally {
    if (seq === contextSeq) contextLoading.value = false
  }
}

/* ---------- 上下文弹窗：环状图 + 明细排行 ---------- */
const contextOpen = ref(false)
const modalEl = ref<HTMLElement | null>(null)
const modalCloseEl = ref<HTMLButtonElement | null>(null)
const triggerEl = ref<HTMLElement | null>(null)
const copied = ref<'idle' | 'ok' | 'fail'>('idle')
let copyTimer = 0

/** 图例固定顺序：system → retrieval → tools → conversation */
const GROUP_ORDER = ['system', 'retrieval', 'tools', 'conversation']
const RING_RADIUS = 68
const RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS
/** 环上每段留的弧长间隙，避免两种颜色糊在一起 */
const RING_GAP = 5

const ctxParts = computed(() => contextData.value?.parts ?? [])

const ctxTotal = computed(() => {
  const data = contextData.value
  if (!data) return 0
  const sum = data.parts.reduce((total, part) => total + part.tokens, 0)
  return data.estimatedTotalTokens > 0 ? data.estimatedTotalTokens : sum
})

const ctxGroups = computed(() => {
  const buckets = new Map<string, number>()
  for (const part of ctxParts.value) {
    buckets.set(part.group, (buckets.get(part.group) ?? 0) + part.tokens)
  }
  const extra = Array.from(buckets.keys()).filter((group) => !GROUP_ORDER.includes(group))
  const total = ctxTotal.value || 1
  return [...GROUP_ORDER, ...extra]
    .filter((group) => buckets.has(group))
    .map((group) => {
      const tokens = buckets.get(group) ?? 0
      return { group, tokens, percent: (tokens / total) * 100 }
    })
})

const ringSegments = computed(() => {
  const total = ctxTotal.value || 1
  let offset = 0
  return ctxGroups.value.map((group) => {
    const length = (group.tokens / total) * RING_CIRCUMFERENCE
    const visible = Math.max(length - RING_GAP, 0.5)
    const segment = {
      ...group,
      dash: `${visible} ${RING_CIRCUMFERENCE - visible}`,
      offset: -offset,
    }
    offset += length
    return segment
  })
})

const sortedParts = computed(() => [...ctxParts.value].sort((a, b) => b.tokens - a.tokens))
const maxPartTokens = computed(() => Math.max(1, ...sortedParts.value.map((part) => part.tokens)))

function partWidth(tokens: number): string {
  return `${Math.max(2, (tokens / maxPartTokens.value) * 100)}%`
}

const ringLabel = computed(() => {
  const detail = ctxGroups.value.map((g) => `${g.group} ${g.percent.toFixed(1)}%`).join('，')
  return `上下文构成环状图${detail ? `：${detail}` : ''}`
})

function openContext(sessionId: string, event: MouseEvent): void {
  triggerEl.value = event.currentTarget as HTMLElement
  contextOpen.value = true
  copied.value = 'idle'
  document.body.style.overflow = 'hidden'
  void loadContext(sessionId)
  void nextTick(() => modalCloseEl.value?.focus())
}

function closeContext(): void {
  if (!contextOpen.value) return
  contextSeq++
  contextOpen.value = false
  contextSessionId.value = ''
  contextData.value = null
  contextError.value = ''
  contextLoading.value = false
  document.body.style.overflow = ''
  if (copyTimer) {
    clearTimeout(copyTimer)
    copyTimer = 0
  }
  copied.value = 'idle'
  triggerEl.value?.focus()
  triggerEl.value = null
}

/** Esc 关闭 + Tab 在弹窗内循环（焦点不落到遮罩后的页面） */
function onContextKeydown(event: KeyboardEvent): void {
  if (!contextOpen.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    closeContext()
    return
  }
  if (event.key !== 'Tab') return
  const root = modalEl.value
  if (!root) return
  const nodes = Array.from(
    root.querySelectorAll<HTMLElement>(
      'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    ),
  )
  if (nodes.length === 0) return
  const first = nodes[0]
  const last = nodes[nodes.length - 1]
  const current = document.activeElement as HTMLElement | null
  if (!current || !root.contains(current)) {
    event.preventDefault()
    first.focus()
    return
  }
  if (!event.shiftKey && current === last) {
    event.preventDefault()
    first.focus()
  } else if (event.shiftKey && current === first) {
    event.preventDefault()
    last.focus()
  }
}

async function copyContext(): Promise<void> {
  const data = contextData.value
  if (!data) return
  const lines = [
    `上下文构成 · 会话 ${data.sessionId} · 估算合计 ${ctxTotal.value} tokens`,
    ...ctxGroups.value.map((g) => `${g.group} ${g.tokens} tokens · ${g.percent.toFixed(1)}%`),
    ...sortedParts.value.map(
      (part) => `${part.label}（${part.group}）${part.tokens} tokens · ${part.percent.toFixed(1)}%`,
    ),
  ]
  try {
    await navigator.clipboard.writeText(lines.join('\n'))
    copied.value = 'ok'
  } catch {
    copied.value = 'fail'
  }
  if (copyTimer) clearTimeout(copyTimer)
  copyTimer = window.setTimeout(() => {
    copied.value = 'idle'
  }, 1600)
}

onMounted(() => {
  document.addEventListener('keydown', onContextKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onContextKeydown)
  if (copyTimer) clearTimeout(copyTimer)
  document.body.style.overflow = ''
})
</script>

<template>
  <div class="detail-stats">
    <article v-for="s in stats" :key="s.key" class="detail-stat">
      <span class="detail-stat-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24">
          <path v-for="d in s.icon" :key="d" :d="d" />
        </svg>
      </span>
      <div class="detail-stat-text">
        <span class="detail-stat-label">{{ s.label }}</span>
        <strong class="detail-stat-value">{{ s.value }}</strong>
        <span class="detail-stat-note">{{ s.note }}</span>
      </div>
    </article>
  </div>

  <section class="panel detail-panel" aria-label="明细数据">
    <div class="detail-panel-head">
      <h3>明细数据</h3>
      <span class="detail-panel-note">活跃会话 · LLM 调用 · 工具调用</span>
      <button
        class="res-btn res-btn-sm"
        type="button"
        data-testid="details-toggle-all"
        :aria-expanded="allOpen"
        @click="emit('toggle', 'all')"
      >{{ allOpen ? '全部收起' : '全部展开' }}</button>
    </div>

    <!-- 活跃会话 -->
    <section class="detail-section" :class="{ 'is-open': sessionsOpen }">
      <button class="detail-row" type="button" :aria-expanded="sessionsOpen" @click="emit('toggle', 'sessions')">
        <svg class="detail-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg>
        <span class="detail-row-title">活跃会话</span>
        <span class="detail-row-count">{{ data.sessions.length }}</span>
        <span class="detail-row-note">时间窗内最近活跃</span>
      </button>
      <div v-show="sessionsOpen" class="detail-body">
        <div v-if="data.sessions.length === 0" class="empty">时间窗内暂无活跃会话</div>
        <div v-else class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>会话 ID</th>
                <th>渠道</th>
                <th>Agent</th>
                <th>会话名</th>
                <th>最近活跃</th>
                <th>调用</th>
                <th>Tokens</th>
                <th>上下文</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in data.sessions" :key="s.sessionId">
                <td class="num">{{ s.sessionId }}</td>
                <td><span class="channel-badge">{{ channelLabel(s.channel) }}</span></td>
                <td>{{ s.agentName ?? '—' }}</td>
                <td class="ellipsis" :title="s.sessionName ?? ''">{{ s.sessionName ?? '—' }}</td>
                <td class="num">{{ fmtTime(s.lastActiveAt) }}</td>
                <td class="num">{{ fmtNumber(s.calls) }}</td>
                <td class="num">{{ fmtNumber(s.totalTokens) }}</td>
                <td>
                  <button
                    type="button"
                    class="ctx-btn"
                    :class="{ active: contextOpen && contextSessionId === String(s.sessionId) }"
                    @click="openContext(String(s.sessionId), $event)"
                  >上下文</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <!-- 最近 LLM 调用 -->
    <section class="detail-section" :class="{ 'is-open': llmOpen }">
      <button class="detail-row" type="button" :aria-expanded="llmOpen" @click="emit('toggle', 'llm')">
        <svg class="detail-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg>
        <span class="detail-row-title">最近 LLM 调用</span>
        <span class="detail-row-count">{{ data.llmCalls.length }}</span>
        <span class="detail-row-note">首 token — 表示该调用未采集 TTFT</span>
      </button>
      <div v-show="llmOpen" class="detail-body">
        <div v-if="data.llmCalls.length === 0" class="empty">时间窗内暂无 LLM 调用</div>
        <div v-else class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>时间</th>
                <th>会话</th>
                <th>渠道</th>
                <th>Agent</th>
                <th>模型</th>
                <th>类型</th>
                <th>首 token</th>
                <th>耗时</th>
                <th>Tokens</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="c in data.llmCalls" :key="c.id" class="llm-call-row">
                <td class="num">{{ fmtTime(c.createdAt) }}</td>
                <td class="num">{{ c.sessionId ?? '—' }}</td>
                <td><span class="channel-badge">{{ channelLabel(c.channel) }}</span></td>
                <td>{{ c.agentName ?? '—' }}</td>
                <td class="num ellipsis" :title="c.model ?? ''">{{ c.model ?? '—' }}</td>
                <td class="num">{{ c.callKind ?? '—' }}</td>
                <td class="num">{{ fmtMs(c.firstTokenMs) }}</td>
                <td class="num">{{ fmtMs(c.durationMs) }}</td>
                <td class="num">{{ fmtNumber(c.totalTokens) }}</td>
                <td>
                  <span class="status" :class="statusClass(c.status)">{{ c.status ?? '—' }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <!-- 最近工具调用 -->
    <section class="detail-section" :class="{ 'is-open': toolsOpen }">
      <button class="detail-row" type="button" :aria-expanded="toolsOpen" @click="emit('toggle', 'tools')">
        <svg class="detail-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg>
        <span class="detail-row-title">最近工具调用</span>
        <span class="detail-row-count">{{ data.toolCalls.length }}</span>
        <span class="detail-row-note">外部与内置工具的执行记录</span>
      </button>
      <div v-show="toolsOpen" class="detail-body">
        <div v-if="data.toolCalls.length === 0" class="empty">时间窗内暂无工具调用</div>
        <div v-else class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>时间</th>
                <th>会话</th>
                <th>渠道</th>
                <th>Agent</th>
                <th>工具</th>
                <th>来源</th>
                <th>耗时</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="t in data.toolCalls" :key="t.id">
                <td class="num">{{ fmtTime(t.createdAt) }}</td>
                <td class="num">{{ t.sessionId ?? '—' }}</td>
                <td><span class="channel-badge">{{ channelLabel(t.channel) }}</span></td>
                <td>{{ t.agentName ?? '—' }}</td>
                <td class="num">{{ t.toolName ?? '—' }}</td>
                <td>{{ t.serverName ?? '内置' }}</td>
                <td class="num">{{ fmtMs(t.durationMs) }}</td>
                <td>
                  <span class="status" :class="statusClass(t.status)">{{ t.status ?? '—' }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <p class="detail-hint">展开后按时间倒序列出记录</p>
  </section>

  <!-- 上下文构成弹窗：环状图 + 明细排行 -->
  <Teleport to="body">
    <div v-if="contextOpen" class="ctx-modal-mask" @click.self="closeContext">
      <div
        ref="modalEl"
        class="ctx-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="ctx-modal-title"
      >
        <header class="ctx-modal-head">
          <div class="ctx-modal-title">
            <h3 id="ctx-modal-title">上下文构成</h3>
            <p>估算口径 TokenEstimator · 不含轮内动态工具结果</p>
          </div>
          <button
            ref="modalCloseEl"
            class="ctx-modal-x"
            type="button"
            aria-label="关闭"
            @click="closeContext"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg>
          </button>
        </header>

        <div v-if="contextLoading" class="ctx-modal-state">正在读取上下文快照…</div>
        <div v-else-if="contextError" class="ctx-modal-state is-error" role="alert">{{ contextError }}</div>
        <div v-else-if="!contextData" class="ctx-modal-state">
          该会话暂无最近请求快照（先发一条消息再试）
        </div>

        <template v-else>
          <div class="ctx-modal-meta">
            <span>会话 <code>{{ contextData.sessionId }}</code></span>
            <span>Agent <code>{{ contextData.agentName ?? '—' }}</code></span>
            <span>模型 <code>{{ contextData.model ?? '—' }}</code></span>
            <span>轮次 <code>{{ contextData.turnId ?? '—' }}</code></span>
            <span>采集 <code>{{ fmtClock(contextData.capturedAt) }}</code></span>
          </div>

          <div class="ctx-modal-body">
            <section class="ctx-modal-ring" aria-label="分组占比">
              <div class="ctx-ring" role="img" :aria-label="ringLabel">
                <svg viewBox="0 0 200 200" aria-hidden="true">
                  <circle
                    v-for="seg in ringSegments"
                    :key="seg.group"
                    class="ctx-ring-seg"
                    :class="`ctx-tone-${seg.group}`"
                    cx="100"
                    cy="100"
                    :r="RING_RADIUS"
                    transform="rotate(-90 100 100)"
                    :stroke-dasharray="seg.dash"
                    :stroke-dashoffset="seg.offset"
                  />
                </svg>
                <div class="ctx-ring-center">
                  <strong>{{ fmtNumber(ctxTotal) }}</strong>
                  <span>tokens · 估算合计</span>
                </div>
              </div>
              <ul class="ctx-legend">
                <li v-for="g in ctxGroups" :key="g.group" class="ctx-legend-item">
                  <span class="ctx-legend-dot" :class="`ctx-tone-${g.group}`"></span>
                  <span class="ctx-legend-name">{{ g.group }}</span>
                  <span class="ctx-legend-tokens">{{ fmtNumber(g.tokens) }}</span>
                  <span class="ctx-legend-pct">{{ g.percent.toFixed(1) }}%</span>
                </li>
              </ul>
            </section>

            <section class="ctx-modal-rank" aria-label="明细排行">
              <div class="ctx-rank-head">
                <h4>明细排行</h4>
                <span>共 {{ contextData.parts.length }} 项 · 按 tokens 从高到低</span>
              </div>
              <ol class="ctx-rank-list">
                <li v-for="part in sortedParts" :key="part.key" class="ctx-rank-item">
                  <span class="ctx-rank-dot" :class="`ctx-tone-${part.group}`"></span>
                  <span class="ctx-rank-label" :title="part.label">{{ part.label }}</span>
                  <span class="ctx-rank-bar">
                    <i :class="`ctx-tone-${part.group}`" :style="{ width: partWidth(part.tokens) }"></i>
                  </span>
                  <span class="ctx-rank-tokens">{{ fmtNumber(part.tokens) }}</span>
                  <span class="ctx-rank-pct">{{ part.percent.toFixed(1) }}%</span>
                </li>
              </ol>
            </section>
          </div>

          <footer class="ctx-modal-foot">
            <span>{{ ctxGroups.length }} 组 · {{ contextData.parts.length }} 项 · 估算合计 {{ fmtNumber(ctxTotal) }} tokens</span>
            <button
              class="res-btn"
              type="button"
              :class="{ 'is-fail': copied === 'fail' }"
              @click="copyContext"
            >{{ copied === 'ok' ? '已复制' : copied === 'fail' ? '复制失败' : '复制明细' }}</button>
            <button class="res-btn res-btn-primary" type="button" @click="closeContext">关闭</button>
          </footer>
        </template>
      </div>
    </div>
  </Teleport>

</template>
