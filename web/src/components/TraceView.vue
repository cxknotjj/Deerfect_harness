/**
 * 轨迹 tab:会话调用时间线(LLM + 工具)。
 * 顶部 mono 统计 chips(总耗时 / 调用次数 / token / 错误)+ 时长比例色块条带 +
 * 按时间升序逐行日志(类型徽章 + 名称 + 元信息 + 等宽摘要)。
 * 数据经 api 层取观测接口(listToolCalls/listLlmCalls,mock/真实自动切换),
 * 本组件纯前端消费,零后端改动。sessionId 变化即重拉;竞态用请求序号丢弃
 * 过期响应;失败降级为空态。
 */
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '../api'
import type { LlmCallItem, ToolCallItem } from '../api'

type TraceRow =
  | { kind: 'llm'; key: string; ts: number; item: LlmCallItem }
  | { kind: 'tool'; key: string; ts: number; item: ToolCallItem }

const props = defineProps<{ sessionId: string }>()

const loading = ref(false)
const rows = ref<TraceRow[]>([])
let fetchSeq = 0

async function load(): Promise<void> {
  const seq = ++fetchSeq
  if (props.sessionId === '') {
    rows.value = []
    return
  }
  loading.value = true
  try {
    const sid = props.sessionId
    const [tools, llms] = await Promise.all([api.listToolCalls(sid), api.listLlmCalls(sid)])
    if (seq !== fetchSeq) return
    rows.value = [
      ...llms.map((i): TraceRow => ({ kind: 'llm', key: `l${i.id}`, ts: Date.parse(i.createdAt ?? ''), item: i })),
      ...tools.map((i): TraceRow => ({ kind: 'tool', key: `t${i.id}`, ts: Date.parse(i.createdAt ?? ''), item: i })),
    ].sort((a, b) => a.ts - b.ts)
  } catch {
    if (seq === fetchSeq) rows.value = []
  } finally {
    if (seq === fetchSeq) loading.value = false
  }
}

watch(() => props.sessionId, load, { immediate: true })

/** 汇总统计:总耗时 Σ(全部调用)、次数、token 分向总量、缓存命中(Σcached/Σprompt)与错误数。
 *  缓存命中率口径:全量分母(供应商未返回缓存信息的调用视为未命中摊入);
 *  cachedSamples 为有缓存数据的记录数,为 0 时前端不展示命中率(样本不足) */
const stats = computed(() => {
  let totalMs = 0
  let llmCount = 0
  let toolCount = 0
  let promptTotal = 0
  let completionTotal = 0
  let cachedTotal = 0
  let cachedSamples = 0
  let errors = 0
  for (const r of rows.value) {
    totalMs += r.item.durationMs ?? 0
    if (r.item.status === 'ERROR') errors++
    if (r.kind === 'llm') {
      llmCount++
      promptTotal += r.item.promptTokens ?? 0
      completionTotal += r.item.completionTokens ?? 0
      if (r.item.cachedTokens != null) {
        cachedTotal += r.item.cachedTokens
        cachedSamples++
      }
    } else {
      toolCount++
    }
  }
  const cacheRate = promptTotal > 0 ? (cachedTotal / promptTotal) * 100 : 0
  return { totalMs, llmCount, toolCount, promptTotal, completionTotal, cachedTotal, cachedSamples, cacheRate, errors }
})

/** 调用发生时间(绝对时间,观测回看语义):当日 HH:mm:ss,跨日 MM-dd HH:mm */
function fmtCreatedAt(createdAt: string | null | undefined): string {
  if (!createdAt) return ''
  const d = new Date(createdAt)
  if (Number.isNaN(d.getTime())) return ''
  const now = new Date()
  const sameDay = d.getFullYear() === now.getFullYear()
    && d.getMonth() === now.getMonth() && d.getDate() === now.getDate()
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  const ss = String(d.getSeconds()).padStart(2, '0')
  return sameDay ? `${hh}:${mm}:${ss}` : `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${hh}:${mm}`
}

/** 色块条带:块宽按时长占比(flex-grow),极短调用保底可见;错误块独立着色 */
const bands = computed(() =>
  rows.value.map((r) => ({
    key: r.key,
    kind: r.kind,
    error: r.item.status === 'ERROR',
    grow: Math.max(r.item.durationMs ?? 0, 1),
    title:
      r.kind === 'llm'
        ? `LLM ${r.item.model ?? ''} · ${fmtMs(r.item.durationMs)}`
        : `TOOL ${r.item.toolName ?? ''} · ${fmtMs(r.item.durationMs)}`,
  })),
)

/** 毫秒 → 紧凑时长:812ms / 4.2s / 4m25s */
function fmtMs(ms: number | null | undefined): string {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  const m = Math.floor(ms / 60_000)
  const s = Math.round((ms % 60_000) / 1000)
  return s > 0 ? `${m}m${s}s` : `${m}m`
}

/** token 数 → 紧凑计数:812 / 1.2k / 435.0k */
function fmtTokens(n: number | null | undefined): string {
  if (n == null) return '—'
  if (n < 1000) return String(n)
  return `${(n / 1000).toFixed(1)}k`
}

/** LLM 行 token 段:「输入 X · 输出 Y tok」;缺输出只显示输入,全缺不显示(null) */
function fmtTokenFlow(p: number | null, c: number | null): string | null {
  if (p == null && c == null) return null
  if (c == null) return `输入 ${fmtTokens(p)} tok`
  return `输入 ${fmtTokens(p)} · 输出 ${fmtTokens(c)} tok`
}
</script>

<template>
  <div class="trace-view">
    <template v-if="rows.length > 0">
      <div class="trace-stats">
        <span class="trace-chip">总耗时 {{ fmtMs(stats.totalMs) }}</span>
        <span class="trace-chip">LLM {{ stats.llmCount }} 次</span>
        <span class="trace-chip">工具 {{ stats.toolCount }} 次</span>
        <span class="trace-chip">输入 {{ fmtTokens(stats.promptTotal) }} tok</span>
        <span class="trace-chip">输出 {{ fmtTokens(stats.completionTotal) }} tok</span>
        <span v-if="stats.cachedSamples > 0" class="trace-chip">缓存命中 {{ stats.cacheRate.toFixed(1) }}%</span>
        <span v-if="stats.errors > 0" class="trace-chip trace-chip-error">错误 {{ stats.errors }}</span>
      </div>
      <div class="trace-band">
        <span
          v-for="b in bands"
          :key="b.key"
          class="trace-band-block"
          :class="[`is-${b.kind}`, { 'is-error': b.error }]"
          :style="{ flexGrow: b.grow }"
          :title="b.title"
        ></span>
      </div>
      <div class="trace-list">
        <div
          v-for="r in rows"
          :key="r.key"
          class="trace-row"
          :class="{ 'is-error': r.item.status === 'ERROR' }"
        >
          <span class="trace-kind" :class="`is-${r.kind}`">{{ r.kind === 'llm' ? 'LLM' : 'TOOL' }}</span>
          <template v-if="r.kind === 'llm'">
            <span class="trace-name" :title="r.item.model ?? ''">{{ r.item.model ?? 'LLM' }}</span>
            <span class="trace-meta">
              <template v-if="fmtCreatedAt(r.item.createdAt)">{{ fmtCreatedAt(r.item.createdAt) }} · </template>{{ r.item.agentName ?? '—' }} · {{ r.item.callKind === 'STREAM' ? '流式' : '同步' }}
              · {{ fmtMs(r.item.durationMs) }}<template v-if="fmtTokenFlow(r.item.promptTokens, r.item.completionTokens) !== null">
                · {{ fmtTokenFlow(r.item.promptTokens, r.item.completionTokens) }}</template><template v-if="r.item.firstTokenMs != null">
                · 首 token {{ fmtMs(r.item.firstTokenMs) }}</template><template v-if="r.item.cachedTokens != null">
                · 缓存 {{ fmtTokens(r.item.cachedTokens) }} tok</template><template v-if="(r.item.attempt ?? 1) > 1">
                · 尝试 {{ r.item.attempt }}/{{ r.item.maxAttempts ?? '?' }}</template>
            </span>
            <span
              v-if="r.item.errorMsg || r.item.outputSummary"
              class="trace-detail"
              :title="r.item.errorMsg ?? r.item.outputSummary ?? ''"
            >{{ r.item.errorMsg ?? r.item.outputSummary }}</span>
          </template>
          <template v-else>
            <span class="trace-name" :title="r.item.toolName ?? ''">{{ r.item.toolName ?? 'TOOL' }}</span>
            <span class="trace-meta">
              <template v-if="fmtCreatedAt(r.item.createdAt)">{{ fmtCreatedAt(r.item.createdAt) }} · </template>{{ r.item.agentName ?? '—' }} · {{ fmtMs(r.item.durationMs) }}
              <template v-if="r.item.serverName"> · {{ r.item.serverName }}</template>
            </span>
            <span class="trace-detail" :title="r.item.errorMsg ?? r.item.argsSummary ?? ''">
              {{ r.item.errorMsg ?? r.item.argsSummary }}
            </span>
          </template>
        </div>
      </div>
    </template>
    <div v-else class="trace-empty">{{ loading ? '加载中…' : '此会话暂无调用记录' }}</div>
  </div>
</template>
