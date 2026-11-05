import { describe, expect, it, vi } from 'vitest'
import type { KbRetrievalItem, LlmCallItem, ToolCallItem } from '../api'
import {
  computeBands,
  computeDepths,
  computeSegments,
  computeStats,
  fmtCreatedAt,
  fmtLlmTime,
  fmtMs,
  fmtTokenFlow,
  fmtTokens,
  isRowError,
  sourceLabel,
  type TraceRow,
} from './traceFormat'

/** 构造最小观测记录（只填测试关心的字段，其余按接口默认 null） */
function llm(partial: Partial<LlmCallItem> & { id: number }): LlmCallItem {
  return {
    agentName: null,
    model: null,
    callKind: null,
    status: null,
    promptTokens: null,
    completionTokens: null,
    totalTokens: null,
    durationMs: null,
    errorMsg: null,
    createdAt: null,
    ...partial,
  }
}

function tool(partial: Partial<ToolCallItem> & { id: number }): ToolCallItem {
  return {
    agentName: null,
    toolName: null,
    serverName: null,
    argsSummary: null,
    status: null,
    durationMs: null,
    errorMsg: null,
    createdAt: null,
    ...partial,
  }
}

function kb(partial: Partial<KbRetrievalItem> & { id: number }): KbRetrievalItem {
  return {
    sessionId: 's1',
    agentName: null,
    source: null,
    query: null,
    kbs: null,
    hitCount: null,
    durationMs: null,
    ok: 1,
    errorMsg: null,
    startedAt: null,
    createdAt: null,
    ...partial,
  }
}

function llmRow(id: number, partial: Partial<LlmCallItem> = {}): TraceRow {
  return { kind: 'llm', key: `l${id}`, ts: id, item: llm({ id, ...partial }) }
}

function toolRow(id: number, partial: Partial<ToolCallItem> = {}): TraceRow {
  return { kind: 'tool', key: `t${id}`, ts: id, item: tool({ id, ...partial }) }
}

function kbRow(id: number, partial: Partial<KbRetrievalItem> = {}): TraceRow {
  return { kind: 'kb', key: `k${id}`, ts: id, item: kb({ id, ...partial }) }
}

describe('traceFormat 展示格式化', () => {
  it('时长按 ms/s/分秒 三档收敛', () => {
    expect(fmtMs(null)).toBe('—')
    expect(fmtMs(812)).toBe('812ms')
    expect(fmtMs(4200)).toBe('4.2s')
    expect(fmtMs(265_000)).toBe('4m25s')
    expect(fmtMs(240_000)).toBe('4m')
  })

  it('token 数紧凑计数；缺失显示占位', () => {
    expect(fmtTokens(null)).toBe('—')
    expect(fmtTokens(812)).toBe('812')
    expect(fmtTokens(1234)).toBe('1.2k')
  })

  it('token 流向：缺输出只显示输入，全缺返回 null', () => {
    expect(fmtTokenFlow(null, null)).toBeNull()
    expect(fmtTokenFlow(100, null)).toBe('输入 100 tok')
    expect(fmtTokenFlow(100, 2345)).toBe('输入 100 · 输出 2.3k tok')
  })

  it('RAG 来源标签：已知值中文化，未知值原样，空值回退 RAG', () => {
    expect(sourceLabel('entry_prefetch')).toBe('入口预取')
    expect(sourceLabel('lead_prefetch')).toBe('lead 预取')
    expect(sourceLabel('subtask_prefetch')).toBe('子任务预取')
    expect(sourceLabel('inline_query')).toBe('现查')
    expect(sourceLabel('cache_hit')).toBe('缓存命中')
    expect(sourceLabel('custom')).toBe('custom')
    expect(sourceLabel(null)).toBe('RAG')
  })

  it('时间：当日 HH:mm:ss，跨日 MM-dd HH:mm，非法值空串', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2026, 10, 5, 12, 0, 0))
    expect(fmtCreatedAt('2026-11-05T09:08:07')).toBe('09:08:07')
    expect(fmtCreatedAt('2026-11-01T09:08:07')).toBe('11-01 09:08')
    vi.useRealTimers()
    expect(fmtCreatedAt(null)).toBe('')
    expect(fmtCreatedAt('not-a-date')).toBe('')
  })

  it('LLM 行时间优先 startedAt，回退 createdAt', () => {
    expect(fmtLlmTime(llm({ id: 1, startedAt: '2026-11-05T09:00:00', createdAt: '2026-11-05T10:00:00' })))
      .toBe('09:00:00')
    expect(fmtLlmTime(llm({ id: 2, createdAt: '2026-11-05T10:00:00' }))).toBe('10:00:00')
  })

  it('错误态：LLM/工具看 status，RAG 看 ok', () => {
    expect(isRowError(llmRow(1, { status: 'ERROR' }))).toBe(true)
    expect(isRowError(llmRow(2, { status: 'OK' }))).toBe(false)
    expect(isRowError(toolRow(3, { status: 'ERROR' }))).toBe(true)
    expect(isRowError(kbRow(4, { ok: 0 }))).toBe(true)
    expect(isRowError(kbRow(5, { ok: 1 }))).toBe(false)
  })
})

describe('traceFormat 统计与分段', () => {
  it('统计汇总：耗时、次数、token 分向、缓存命中率与错误数', () => {
    const rows = [
      llmRow(1, { durationMs: 100, promptTokens: 100, completionTokens: 50, cachedTokens: 40, status: 'OK' }),
      llmRow(2, { durationMs: 200, promptTokens: 100, completionTokens: 80, status: 'ERROR' }),
      toolRow(3, { durationMs: 30, status: 'OK' }),
      kbRow(4, { durationMs: 10, hitCount: 3, ok: 0 }),
    ]

    expect(computeStats(rows)).toEqual({
      totalMs: 340,
      llmCount: 2,
      toolCount: 1,
      kbCount: 1,
      kbHits: 3,
      promptTotal: 200,
      completionTotal: 130,
      cachedTotal: 40,
      cachedSamples: 1,
      cacheRate: 20,
      errors: 2,
    })
  })

  it('缩进深度：沿 parentSpan 上爬，缺失父节点按根，成环不死循环', () => {
    const root = llmRow(1, { spanId: 's1' })
    const child = llmRow(2, { spanId: 's2', parentSpan: 's1' })
    const grand = llmRow(3, { spanId: 's3', parentSpan: 's2' })
    const depths = computeDepths([root, child, grand])
    expect(depths.get('l1')).toBe(0)
    expect(depths.get('l2')).toBe(1)
    expect(depths.get('l3')).toBe(2)

    const orphan = llmRow(4, { parentSpan: 'missing' })
    expect(computeDepths([orphan]).get('l4')).toBe(0)

    const a = llmRow(5, { spanId: 'a', parentSpan: 'b' })
    const b = llmRow(6, { spanId: 'b', parentSpan: 'a' })
    const cycleDepths = computeDepths([a, b])
    // 成环时只向上爬一层即被 seen 截断，不会无限循环
    expect(cycleDepths.get('l5')).toBe(1)
    expect(cycleDepths.get('l6')).toBe(1)
  })

  it('分段：无 turnId 退化为单一平铺段', () => {
    const segments = computeSegments([llmRow(1), toolRow(2)])
    expect(segments).toHaveLength(1)
    expect(segments[0].kind).toBe('flat')
    expect(segments[0].rows.map((d) => d.row.key)).toEqual(['l1', 't2'])
  })

  it('分段：turnId 聚合为轮次小节，平铺行保持原位', () => {
    const rows = [
      llmRow(1, { turnId: 't1', spanId: 's1' }),
      toolRow(2, { turnId: 't1' }),
      llmRow(3, { turnId: null }),
      llmRow(4, { turnId: 't2' }),
    ]
    const segments = computeSegments(rows)

    expect(segments.map((s) => s.kind)).toEqual(['turn', 'flat', 'turn'])
    const firstTurn = segments[0]
    expect(firstTurn.kind === 'turn' && firstTurn.turnNo).toBe(1)
    expect(firstTurn.rows.map((d) => d.row.key)).toEqual(['l1', 't2'])
    expect(segments[1].rows.map((d) => d.row.key)).toEqual(['l3'])
    const secondTurn = segments[2]
    expect(secondTurn.kind === 'turn' && secondTurn.turnNo).toBe(2)
  })

  it('色块条带：极短调用 grow 保底 1，错误独立标记，标题带类型', () => {
    const bands = computeBands([
      llmRow(1, { durationMs: 0, model: 'qwen', status: 'OK' }),
      toolRow(2, { durationMs: 20, toolName: 'read_file', status: 'ERROR' }),
      kbRow(3, { durationMs: 5, source: 'inline_query', ok: 1 }),
    ])

    expect(bands[0].grow).toBe(1)
    expect(bands[0].title).toBe('LLM qwen · 0ms')
    expect(bands[1].error).toBe(true)
    expect(bands[1].title).toBe('TOOL read_file · 20ms')
    expect(bands[2].error).toBe(false)
    expect(bands[2].title).toBe('RAG inline_query · 5ms')
  })
})
