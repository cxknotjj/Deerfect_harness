import type { KbRetrievalItem, LlmCallItem, ToolCallItem } from '../api'

/**
 * 轨迹页（TraceView）的展示派生逻辑（自组件拆出，2026-11-05）：
 * 时间线排序类型、轮次分段、parentSpan 缩进、统计/色块条带与格式化纯函数。
 * 无 Vue 依赖，便于单测；组件只负责取数与模板。
 */

export type TraceRow =
  | { kind: 'llm'; key: string; ts: number; item: LlmCallItem }
  | { kind: 'tool'; key: string; ts: number; item: ToolCallItem }
  | { kind: 'kb'; key: string; ts: number; item: KbRetrievalItem }

/** 带缩进深度的展示行:depth = 同轮内 parentSpan 链层级(根调用 = 0) */
export type DisplayRow = { row: TraceRow; depth: number }

/** 轨迹分段:flat = 无 turnId 的平铺段(旧数据,现状渲染);turn = 轮次小节 */
export type TraceSegment =
  | { kind: 'flat'; key: string; rows: DisplayRow[] }
  | { kind: 'turn'; key: string; turnNo: number; rows: DisplayRow[] }

/** 每级缩进像素(层级深浅视觉可辨即可) */
export const INDENT_PX = 20

/** 汇总统计:总耗时 / 次数 / token 分向 / 缓存命中 / 错误数 */
export interface TraceStats {
  totalMs: number
  llmCount: number
  toolCount: number
  kbCount: number
  kbHits: number
  promptTotal: number
  completionTotal: number
  cachedTotal: number
  cachedSamples: number
  cacheRate: number
  errors: number
}

export interface TraceBand {
  key: string
  kind: TraceRow['kind']
  error: boolean
  grow: number
  title: string
}

/** 调用发生时间(绝对时间,观测回看语义):当日 HH:mm:ss,跨日 MM-dd HH:mm */
export function fmtCreatedAt(createdAt: string | null | undefined): string {
  if (!createdAt) return ''
  const d = new Date(createdAt)
  if (Number.isNaN(d.getTime())) return ''
  const now = new Date()
  const sameDay = d.getFullYear() === now.getFullYear()
    && d.getMonth() === now.getMonth() && d.getDate() === now.getDate()
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  const ss = String(d.getSeconds()).padStart(2, '0')
  return sameDay
    ? `${hh}:${mm}:${ss}`
    : `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${hh}:${mm}`
}

/** LLM 行展示时刻:发起时刻优先(与排序口径一致),历史行回退落库时刻 */
export function fmtLlmTime(item: LlmCallItem): string {
  return fmtCreatedAt(item.startedAt ?? item.createdAt)
}

/** 毫秒 → 紧凑时长:812ms / 4.2s / 4m25s */
export function fmtMs(ms: number | null | undefined): string {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  const m = Math.floor(ms / 60_000)
  const s = Math.round((ms % 60_000) / 1000)
  return s > 0 ? `${m}m${s}s` : `${m}m`
}

/** RAG 来源 → 中文标签(口径与 kb_retrieval_log.source 一致) */
export function sourceLabel(source: string | null): string {
  switch (source) {
    case 'entry_prefetch':
      return '入口预取'
    case 'lead_prefetch':
      return 'lead 预取'
    case 'subtask_prefetch':
      return '子任务预取'
    case 'inline_query':
      return '现查'
    case 'cache_hit':
      return '缓存命中'
    default:
      return source ?? 'RAG'
  }
}

/** 行错误态:llm/tool 看 status,RAG 看 ok(判别联合按 kind 收窄) */
export function isRowError(r: TraceRow): boolean {
  if (r.kind === 'kb') return r.item.ok === 0
  return r.item.status === 'ERROR'
}

/** token 数 → 紧凑计数:812 / 1.2k / 435.0k */
export function fmtTokens(n: number | null | undefined): string {
  if (n == null) return '—'
  if (n < 1000) return String(n)
  return `${(n / 1000).toFixed(1)}k`
}

/** LLM 行 token 段:「输入 X · 输出 Y tok」;缺输出只显示输入,全缺不显示(null) */
export function fmtTokenFlow(p: number | null, c: number | null): string | null {
  if (p == null && c == null) return null
  if (c == null) return `输入 ${fmtTokens(p)} tok`
  return `输入 ${fmtTokens(p)} · 输出 ${fmtTokens(c)} tok`
}

/** 同轮内按 parentSpan 建父子链,计算每行缩进深度(根 = 0)。
 *  仅 LLM 行携带 spanId 作指针锚点;父 span 找不到(数据缺失)或成环时按根截断,不报错 */
export function computeDepths(turnRows: TraceRow[]): Map<string, number> {
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
export function computeSegments(rows: TraceRow[]): TraceSegment[] {
  const turnRows = new Map<string, TraceRow[]>()
  const turnNo = new Map<string, number>()
  for (const r of rows) {
    const tid = r.item.turnId
    if (!tid) continue
    const bucket = turnRows.get(tid)
    if (bucket) bucket.push(r)
    else turnRows.set(tid, [r])
    if (!turnNo.has(tid)) turnNo.set(tid, turnNo.size + 1)
  }
  if (turnRows.size === 0) {
    return [{ kind: 'flat', key: 'flat', rows: rows.map((row) => ({ row, depth: 0 })) }]
  }
  const segments: TraceSegment[] = []
  const emitted = new Set<string>()
  let flatBuf: DisplayRow[] = []
  let flatSeq = 0
  const flushFlat = (): void => {
    if (flatBuf.length > 0) {
      segments.push({ kind: 'flat', key: `flat-${flatSeq++}`, rows: flatBuf })
      flatBuf = []
    }
  }
  for (const r of rows) {
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
    segments.push({
      kind: 'turn',
      key: `turn-${tid}`,
      turnNo: turnNo.get(tid)!,
      rows: list.map((row) => ({ row, depth: depths.get(row.key) ?? 0 })),
    })
  }
  flushFlat()
  return segments
}

/** 汇总统计（缓存命中率口径:全量分母,供应商未返回缓存信息的调用视为未命中摊入） */
export function computeStats(rows: TraceRow[]): TraceStats {
  let totalMs = 0
  let llmCount = 0
  let toolCount = 0
  let kbCount = 0
  let kbHits = 0
  let promptTotal = 0
  let completionTotal = 0
  let cachedTotal = 0
  let cachedSamples = 0
  let errors = 0
  for (const r of rows) {
    totalMs += r.item.durationMs ?? 0
    if (r.kind === 'llm') {
      llmCount++
      if (r.item.status === 'ERROR') errors++
      promptTotal += r.item.promptTokens ?? 0
      completionTotal += r.item.completionTokens ?? 0
      if (r.item.cachedTokens != null) {
        cachedTotal += r.item.cachedTokens
        cachedSamples++
      }
    } else if (r.kind === 'tool') {
      toolCount++
      if (r.item.status === 'ERROR') errors++
    } else {
      kbCount++
      kbHits += r.item.hitCount ?? 0
      if (r.item.ok === 0) errors++
    }
  }
  const cacheRate = promptTotal > 0 ? (cachedTotal / promptTotal) * 100 : 0
  return {
    totalMs,
    llmCount,
    toolCount,
    kbCount,
    kbHits,
    promptTotal,
    completionTotal,
    cachedTotal,
    cachedSamples,
    cacheRate,
    errors,
  }
}

/** 色块条带:块宽按时长占比(flex-grow),极短调用保底可见;错误块独立着色 */
export function computeBands(rows: TraceRow[]): TraceBand[] {
  return rows.map((r) => ({
    key: r.key,
    kind: r.kind,
    error: isRowError(r),
    grow: Math.max(r.item.durationMs ?? 0, 1),
    title:
      r.kind === 'llm'
        ? `LLM ${r.item.model ?? ''} · ${fmtMs(r.item.durationMs)}`
        : r.kind === 'kb'
          ? `RAG ${r.item.source ?? ''} · ${fmtMs(r.item.durationMs)}`
          : `TOOL ${r.item.toolName ?? ''} · ${fmtMs(r.item.durationMs)}`,
  }))
}
