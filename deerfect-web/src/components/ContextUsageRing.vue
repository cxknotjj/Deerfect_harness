<script setup lang="ts">
/**
 * 上下文构成圆环（输入区右下角）：
 * 视觉复刻参考图——浅灰圆形底、细环、上下接缝留白、圆角端点、slate 渐变弧；
 * 环内深色弧表示 system 组上下文占比，浅灰轨道表示其余部分。
 * 点击展开的方框直接展示 GET /api/monitor/context 返回的 parts（label/tokens/percent），
 * 不引入参考图的分类文案或上下文上限。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '../api'
import type { MonitorContextComposition } from '../api'

const props = defineProps<{ sessionId: string; streaming: boolean }>()

/** 圆环上下两个接缝的半间隙（度）：进度弧与轨道弧分别向内让出该角度 */
const RING_GAP_DEG = 5
const RING_CENTER = 11
const RING_RADIUS = 8

/** 接口 part.group → 展示色（仅用于方框内的分组色点/分段条） */
const GROUP_COLORS: Record<string, string> = {
  system: '#6366F1',
  retrieval: '#9C74F7',
  tools: '#34C393',
  conversation: '#F6B039',
}

/** 接口 part.group → 分组名（仅用于分段条悬停提示） */
const GROUP_LABELS: Record<string, string> = {
  system: '系统提示词',
  retrieval: 'RAG 知识块',
  tools: '工具 Schema',
  conversation: '对话消息',
}

const open = ref(false)
const loading = ref(false)
const error = ref('')
const composition = ref<MonitorContextComposition | null>(null)
const rootEl = ref<HTMLElement | null>(null)
let requestSeq = 0

async function load(): Promise<void> {
  const sid = props.sessionId
  const seq = ++requestSeq
  if (!sid) {
    composition.value = null
    loading.value = false
    error.value = ''
    return
  }
  loading.value = true
  error.value = ''
  try {
    const snapshot = await api.monitorContext(sid)
    if (seq !== requestSeq) return
    composition.value = snapshot
  } catch (e) {
    if (seq !== requestSeq) return
    composition.value = null
    error.value = `上下文快照加载失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

function toggle(): void {
  open.value = !open.value
  if (open.value) void load()
}

function close(): void {
  open.value = false
}

function onDocumentClick(e: MouseEvent): void {
  const root = rootEl.value
  if (root && !root.contains(e.target as Node)) close()
}

function onKeydown(e: KeyboardEvent): void {
  if (e.key === 'Escape') close()
}

function removeDocumentListeners(): void {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onKeydown)
}

watch(open, (isOpen) => {
  if (isOpen) {
    document.addEventListener('click', onDocumentClick)
    document.addEventListener('keydown', onKeydown)
  } else {
    removeDocumentListeners()
  }
})

// 会话切换 / 流式结束都刷新快照：圆环在收起状态也保持最新占比
watch(() => props.sessionId, () => void load(), { immediate: true })
watch(() => props.streaming, (on, old) => {
  if (old && !on) void load()
})

onBeforeUnmount(removeDocumentListeners)

const totalTokens = computed(() => composition.value?.estimatedTotalTokens ?? 0)
const systemTokens = computed(() => (composition.value?.parts ?? [])
  .filter((part) => part.group === 'system')
  .reduce((sum, part) => sum + part.tokens, 0))
const ringPercent = computed(() => totalTokens.value > 0
  ? Math.min(100, (systemTokens.value / totalTokens.value) * 100)
  : 0)
const ringKey = computed(() => `${composition.value?.turnId ?? 'none'}:${totalTokens.value}`)

/** 分段条：按接口 group 汇总（只用于总览；明细仍按接口 parts 原样列出） */
const groupSegments = computed(() => {
  const totals = new Map<string, number>()
  for (const part of composition.value?.parts ?? []) {
    totals.set(part.group, (totals.get(part.group) ?? 0) + part.tokens)
  }
  return [...totals.entries()]
    .filter(([, tokens]) => tokens > 0)
    .map(([group, tokens]) => ({
      key: group,
      label: GROUP_LABELS[group] ?? group,
      color: GROUP_COLORS[group] ?? '#94A3B8',
      tokens,
      percent: totalTokens.value > 0 ? (tokens / totalTokens.value) * 100 : 0,
    }))
})

/** 极坐标：角度以 12 点方向为 0°、顺时针递增 */
function polar(deg: number, radius: number): { x: number; y: number } {
  const rad = ((deg - 90) * Math.PI) / 180
  return {
    x: RING_CENTER + radius * Math.cos(rad),
    y: RING_CENTER + radius * Math.sin(rad),
  }
}

/** 生成顺时针圆弧路径（起止角留白的圆角端点由 stroke-linecap 负责） */
function arcPath(startDeg: number, endDeg: number): string {
  if (endDeg - startDeg < 0.1) return ''
  const start = polar(startDeg, RING_RADIUS)
  const end = polar(endDeg, RING_RADIUS)
  const largeArc = endDeg - startDeg > 180 ? 1 : 0
  return `M ${start.x.toFixed(2)} ${start.y.toFixed(2)}`
    + ` A ${RING_RADIUS} ${RING_RADIUS} 0 ${largeArc} 1 ${end.x.toFixed(2)} ${end.y.toFixed(2)}`
}

const progressPath = computed(() => {
  const arcDeg = ringPercent.value * 3.6
  if (arcDeg <= 0) return ''
  // 极小占比也保留一小段可见弧
  const end = Math.max(RING_GAP_DEG + 0.6, arcDeg - RING_GAP_DEG)
  return arcPath(RING_GAP_DEG, Math.min(360 - RING_GAP_DEG, end))
})
const trackPath = computed(() => {
  const start = Math.min(360 - RING_GAP_DEG, ringPercent.value * 3.6 + RING_GAP_DEG)
  return arcPath(start, 360 - RING_GAP_DEG)
})

function groupColor(group: string): string {
  return GROUP_COLORS[group] ?? '#94A3B8'
}

function fmtNumber(value: number): string {
  return value.toLocaleString('zh-CN')
}

function fmtPercent(value: number): string {
  return `${value.toFixed(1)}%`
}

/** epoch 毫秒 → 月-日 时:分 */
function fmtClock(ms: number): string {
  if (!ms) return '—'
  const d = new Date(ms)
  const pad = (v: number): string => String(v).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
</script>

<template>
  <div ref="rootEl" class="usage">
    <button
      class="usage-trigger"
      :class="{ live: streaming }"
      type="button"
      title="上下文构成"
      aria-label="上下文构成"
      :aria-expanded="open"
      @click.stop="toggle"
    >
      <svg class="usage-ring" viewBox="0 0 22 22" :key="ringKey" aria-hidden="true">
        <defs>
          <linearGradient id="usage-arc-gradient" x1="0" y1="0" x2="0.9" y2="1">
            <stop offset="0%" class="usage-stop-from" />
            <stop offset="100%" class="usage-stop-to" />
          </linearGradient>
        </defs>
        <path v-if="trackPath" class="usage-ring-track" :d="trackPath" fill="none" />
        <path
          v-if="progressPath"
          class="usage-ring-arc"
          :d="progressPath"
          fill="none"
          pathLength="100"
          stroke-dasharray="100"
        />
      </svg>
    </button>

    <section v-if="open" class="usage-popover" role="dialog" aria-label="上下文构成" @click.stop>
      <header class="usage-head">
        <h3>上下文构成</h3>
        <button class="usage-close" type="button" aria-label="关闭" @click="close">
          <svg viewBox="0 0 16 16" aria-hidden="true">
            <path d="M3 3l10 10M13 3L3 13" />
          </svg>
        </button>
      </header>

      <div v-if="loading" class="usage-state">加载中…</div>
      <div v-else-if="error" class="usage-state usage-state-error">{{ error }}</div>
      <div v-else-if="!composition" class="usage-state">该会话暂无最近请求快照（先发一条消息再试）</div>

      <template v-else>
        <div class="usage-summary">
          <strong>{{ ringPercent.toFixed(1) }}%</strong>
          <span>系统上下文 {{ fmtNumber(systemTokens) }} / {{ fmtNumber(totalTokens) }} tok</span>
        </div>
        <div class="usage-meta">
          <span>{{ composition.agentName ?? '—' }}</span>
          <span>{{ composition.model ?? '—' }}</span>
          <span>轮次 {{ composition.turnId ?? '—' }}</span>
          <span>{{ fmtClock(composition.capturedAt) }}</span>
        </div>
        <div class="usage-bar" role="img" :aria-label="`上下文构成 ${fmtNumber(totalTokens)} tokens`">
          <span
            v-for="segment in groupSegments"
            :key="segment.key"
            class="usage-seg"
            :style="{ width: `${segment.percent}%`, background: segment.color }"
            :title="`${segment.label} · ${fmtNumber(segment.tokens)} tok · ${fmtPercent(segment.percent)}`"
          ></span>
        </div>
        <ul class="usage-legend">
          <li v-for="part in composition.parts" :key="part.key">
            <span class="usage-dot" :style="{ background: groupColor(part.group) }"></span>
            <span class="usage-label">{{ part.label }}</span>
            <span class="usage-value">{{ fmtNumber(part.tokens) }} tok · {{ fmtPercent(part.percent) }}</span>
          </li>
        </ul>
      </template>
    </section>
  </div>
</template>

<style scoped>
.usage {
  position: relative;
  display: inline-flex;
  flex-shrink: 0;
}

.usage-trigger {
  width: 32px;
  height: 32px;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 50%;
  background: var(--usage-btn-bg);
  cursor: pointer;
  transition: background 0.15s, transform 0.12s var(--ease-out-quart);
}

.usage-trigger:hover {
  background: var(--usage-btn-hover);
}

.usage-trigger:active {
  transform: scale(0.94);
}

.usage-trigger:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 2px;
}

.usage-trigger.live .usage-ring {
  animation: usage-breathe 1.6s ease-in-out infinite;
}

.usage-ring {
  width: 22px;
  height: 22px;
  display: block;
  overflow: visible;
}

.usage-ring-track {
  stroke: var(--usage-ring-track);
  stroke-width: 2.2;
  stroke-linecap: round;
}

.usage-ring-arc {
  stroke: url(#usage-arc-gradient);
  stroke-width: 2.2;
  stroke-linecap: round;
  animation: usage-draw 0.8s var(--ease-out-quart) both;
}

.usage-stop-from {
  stop-color: var(--usage-ring-from);
}

.usage-stop-to {
  stop-color: var(--usage-ring-to);
}

.usage-popover {
  position: absolute;
  right: 0;
  bottom: calc(100% + 12px);
  z-index: 60;
  width: 392px;
  max-width: calc(100vw - 32px);
  max-height: min(560px, calc(100vh - 260px));
  overflow-y: auto;
  padding: 20px 24px 18px;
  border: 1px solid var(--grid-line);
  border-radius: 16px;
  background: var(--bg-panel);
  color: var(--ink);
  box-shadow: var(--menu-shadow);
}

.usage-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.usage-head h3 {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.01em;
}

.usage-close {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--ink-dim);
  cursor: pointer;
  transition: color 0.15s, background 0.15s;
}

.usage-close:hover {
  color: var(--ink);
  background: var(--hover-veil);
}

.usage-close svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
}

.usage-summary {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-height: 38px;
}

.usage-summary strong {
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -0.01em;
  font-variant-numeric: tabular-nums;
}

.usage-summary span {
  font-size: 13px;
  color: var(--ink-dim);
  font-variant-numeric: tabular-nums;
}

.usage-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin: 4px 0 2px;
  font-size: 11px;
  color: var(--ink-dim);
}

.usage-meta span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}

.usage-bar {
  display: flex;
  height: 8px;
  margin: 12px 0 10px;
  border-radius: 999px;
  overflow: hidden;
  background: var(--usage-bar-track);
}

.usage-seg {
  height: 100%;
  flex-shrink: 0;
}

.usage-legend {
  list-style: none;
  margin: 2px 0 0;
  padding: 0;
}

.usage-legend li {
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 30px;
  font-size: 13px;
}

.usage-dot {
  width: 9px;
  height: 9px;
  flex-shrink: 0;
  border-radius: 50%;
}

.usage-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.usage-value {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--ink-dim);
  font-variant-numeric: tabular-nums;
}

.usage-state {
  padding: 26px 4px 18px;
  text-align: center;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-dim);
}

.usage-state-error {
  color: var(--accent-warm);
}

@keyframes usage-draw {
  from {
    stroke-dashoffset: 100;
  }
  to {
    stroke-dashoffset: 0;
  }
}

@keyframes usage-breathe {
  0%,
  100% {
    opacity: 0.55;
  }
  50% {
    opacity: 1;
  }
}

@media (max-width: 520px) {
  .usage-popover {
    /* 圆环右侧还有 36px 发送键 + 10px 间距：右移同宽，保证浮层贴住可视区右缘且不溢出 */
    right: -46px;
    max-width: calc(100vw - 40px);
    max-height: min(520px, calc(100vh - 240px));
    padding: 18px 18px 16px;
    border-radius: 14px;
  }

  .usage-summary strong {
    font-size: 26px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .usage-ring-arc,
  .usage-trigger.live .usage-ring {
    animation: none;
  }
}
</style>
