/**
 * 轨迹 tab:会话调用时间线(LLM + 工具)。
 * 顶部 mono 统计 chips(总耗时 / 调用次数 / token / 错误)+ 时长比例色块条带 +
 * 按时间升序逐行日志(类型徽章 + 名称 + 元信息 + 等宽摘要)。摘要默认单行截断,
 * 双击行展开完整内容(单选),再次双击恢复;切换会话即复位。
 * 数据经 api 层取观测接口(listToolCalls/listLlmCalls,mock/真实自动切换),
 * 本组件纯前端消费,零后端改动。sessionId 变化即重拉;竞态用请求序号丢弃
 * 过期响应;失败降级为空态。
 * 分段与缩进:有 turnId 的记录按轮分组渲染小节(「第 N 轮 · M 步」,N=时间序
 * 首现顺序,纯前端推导);同轮内按 parentSpan 链逐级缩进。无 turnId 的旧数据
 * 保持原时间线平铺,不分组不缩进。
 */
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '../api'
import type { LlmCallItem, ToolCallItem } from '../api'

type TraceRow =
  | { kind: 'llm'; key: string; ts: number; item: LlmCallItem }
  | { kind: 'tool'; key: string; ts: number; item: ToolCallItem }

/** 带缩进深度的展示行:depth = 同轮内 parentSpan 链层级(根调用 = 0) */
type DisplayRow = { row: TraceRow; depth: number }
/** 轨迹分段:flat = 无 turnId 的平铺段(旧数据,现状渲染);turn = 轮次小节 */
type TraceSegment =
  | { kind: 'flat'; key: string; rows: DisplayRow[] }
  | { kind: 'turn'; key: string; turnNo: number; rows: DisplayRow[] }

/** 每级缩进像素(层级深浅视觉可辨即可) */
const INDENT_PX = 20

const props = defineProps<{ sessionId: string }>()

const loading = ref(false)
const rows = ref<TraceRow[]>([])
/** 双击展开的行 key(单选;再次双击恢复,切会话复位) */
const expandedKey = ref('')
let fetchSeq = 0

async function load(): Promise<void> {
  const seq = ++fetchSeq
  expandedKey.value = ''
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

/** 同轮内按 parentSpan 建父子链,计算每行缩进深度(根 = 0)。
 *  仅 LLM 行携带 spanId 作指针锚点;父 span 找不到(数据缺失)或成环时按根截断,不报错 */
function computeDepths(turnRows: TraceRow[]): Map<string, number> {
  const spanRow = new Map<string, TraceRow>()
  for (const r of turnRows) {
    const sid = r.kind === 'llm' ? r.item.spanId : null
    if (sid) spanRow.set(sid, r)
  }
  const depths = new Map<string, number>()
  for (const r of turnRows) {
    // 沿 parentSpan 逐级上爬;seen 防御环状指针,深度上限兜底脏数据
    const seen = new Set<string>([r.key])
    let cur: TraceRow = r
    let depth = 0
    while (depth < 32) {
      const pid = cur.item.parentSpan
      const parent = pid ? spanRow.get(pid) : undefined
      if (!parent || seen.has(parent.key)) break
      seen.add(parent.key)
      cur = parent
      depth++
    }
    depths.set(r.key, depth)
  }
  return depths
}

/**
 * 展示分段:时间线里 turnId 非空的记录归入轮次小节(同一 turnId 聚合一段,
 * 出现在其首条记录的时间位置,轮次号 N = 时间序首现顺序),turnId 为空的
 * 旧数据行保持原位平铺、深度恒 0。整个会话都没有 turnId 时退化为单一平铺段,
 * 渲染结果与旧版完全一致。
 */
const segments = computed<TraceSegment[]>(() => {
  const all = rows.value
  const turnRows = new Map<string, TraceRow[]>()
  const turnNo = new Map<string, number>()
  for (const r of all) {
    const tid = r.item.turnId
    if (!tid) continue
    const bucket = turnRows.get(tid)
    if (bucket) bucket.push(r)
    else turnRows.set(tid, [r])
    if (!turnNo.has(tid)) turnNo.set(tid, turnNo.size + 1)
  }
  if (turnRows.size === 0) {
    return [{ kind: 'flat', key: 'flat', rows: all.map((row) => ({ row, depth: 0 })) }]
  }
  const segs: TraceSegment[] = []
  const emitted = new Set<string>()
  let flatBuf: DisplayRow[] = []
  let flatSeq = 0
  const flushFlat = (): void => {
    if (flatBuf.length > 0) {
      segs.push({ kind: 'flat', key: `flat-${flatSeq++}`, rows: flatBuf })
      flatBuf = []
    }
  }
  for (const r of all) {
    const tid = r.item.turnId
    if (!tid) {
      flatBuf.push({ row: r, depth: 0 })
      continue
    }
    if (emitted.has(tid)) continue
    emitted.add(tid)
    flushFlat()
    const list = turnRows.get(tid)!
    const depths = computeDepths(list)
    segs.push({
      kind: 'turn',
      key: `turn-${tid}`,
      turnNo: turnNo.get(tid)!,
      rows: list.map((row) => ({ row, depth: depths.get(row.key) ?? 0 })),
    })
  }
  flushFlat()
  return segs
})

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

/** 双击行:展开该行完整摘要;已展开则恢复默认截断样式 */
function toggleExpand(key: string): void {
  expandedKey.value = expandedKey.value === key ? '' : key
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
          <!-- 分段渲染:轮次段先出「第 N 轮 · M 步」标题,平铺段(旧数据)无标题;行解构保持原标记 -->
          <template v-for="seg in segments" :key="seg.key">
            <div v-if="seg.kind === 'turn'" class="trace-turn-head">
              第 {{ seg.turnNo }} 轮 · {{ seg.rows.length }} 步
            </div>
            <div
              v-for="{ row: r, depth } in seg.rows"
              :key="r.key"
              class="trace-row"
              :class="{ 'is-error': r.item.status === 'ERROR', 'is-expanded': expandedKey === r.key }"
              :style="depth > 0 ? { marginLeft: `${depth * INDENT_PX}px` } : undefined"
              @dblclick="toggleExpand(r.key)"
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
          </template>
        </div>
      </template>
      <div v-else class="trace-empty">{{ loading ? '加载中…' : '此会话暂无调用记录' }}</div>
  </div>
</template>
