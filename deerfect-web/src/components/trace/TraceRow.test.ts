import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TraceRowView from './TraceRow.vue'
import type { KbRetrievalItem, LlmCallItem, ToolCallItem } from '../../api'
import type { TraceRow } from '../../utils/traceFormat'

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

function mountRow(row: TraceRow, depth = 0, expanded = false) {
  return mount(TraceRowView, { props: { row, depth, expanded } })
}

describe('TraceRow', () => {
  it('LLM 行渲染类型徽章、模型名与元信息', () => {
    const wrapper = mountRow({
      kind: 'llm',
      key: 'l1',
      ts: 1,
      item: llm({ id: 1, model: 'qwen-max', agentName: 'general', durationMs: 1200, promptTokens: 100, completionTokens: 50 }),
    })

    expect(wrapper.find('.trace-kind').text()).toBe('LLM')
    expect(wrapper.find('.trace-name').text()).toBe('qwen-max')
    expect(wrapper.find('.trace-meta').text()).toContain('general')
    expect(wrapper.find('.trace-meta').text()).toContain('1.2s')
    expect(wrapper.find('.trace-meta').text()).toContain('输入 100 · 输出 50 tok')
  })

  it('工具行渲染 TOOL 徽章与参数摘要，错误态加 is-error', () => {
    const wrapper = mountRow({
      kind: 'tool',
      key: 't1',
      ts: 1,
      item: tool({ id: 1, toolName: 'read_file', status: 'ERROR', argsSummary: 'path=x', durationMs: 20 }),
    })

    expect(wrapper.find('.trace-kind').text()).toBe('TOOL')
    expect(wrapper.find('.trace-name').text()).toBe('read_file')
    expect(wrapper.find('.trace-detail').text()).toBe('path=x')
    expect(wrapper.classes()).toContain('is-error')
  })

  it('RAG 行渲染中文来源标签与命中段数', () => {
    const wrapper = mountRow({
      kind: 'kb',
      key: 'k1',
      ts: 1,
      item: kb({ id: 1, source: 'entry_prefetch', hitCount: 3, kbs: 'kb1', durationMs: 5 }),
    })

    expect(wrapper.find('.trace-kind').text()).toBe('RAG')
    expect(wrapper.find('.trace-name').text()).toBe('入口预取')
    expect(wrapper.find('.trace-meta').text()).toContain('命中 3 段')
  })

  it('深度缩进、展开态类与双击回传 key', async () => {
    const wrapper = mountRow(
      { kind: 'tool', key: 't2', ts: 1, item: tool({ id: 2, toolName: 'grep' }) },
      2,
      true,
    )

    expect(wrapper.find('.trace-row').attributes('style')).toContain('margin-left: 40px')
    expect(wrapper.classes()).toContain('is-expanded')

    await wrapper.find('.trace-row').trigger('dblclick')
    expect(wrapper.emitted('toggle')?.[0]).toEqual(['t2'])
  })
})
