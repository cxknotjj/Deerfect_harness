import { describe, expect, it } from 'vitest'
import { agentTone, joinTools, routeMark, routeSummary, splitTools } from './routeAgent'

describe('routeAgent helpers', () => {
  it('routeSummary keeps the positioning phrase before the colon', () => {
    expect(routeSummary('主对话模型：当前绑定 deepseek-v4.1-flash-1017')).toBe('主对话模型')
    expect(routeSummary('多 Agent 编排: qwen3.7-flash')).toBe('多 Agent 编排')
    expect(routeSummary('  ')).toBe('未填写描述')
    expect(routeSummary(null)).toBe('未填写描述')
  })

  it('routeMark shortens route codes without dropping casing', () => {
    expect(routeMark('main')).toBe('M')
    expect(routeMark('judge')).toBe('J')
    expect(routeMark('multi')).toBe('Mu')
    expect(routeMark('')).toBe('?')
  })

  it('agentTone is stable across calls', () => {
    expect(agentTone('general')).toBe(agentTone('general'))
    expect(['blue', 'purple', 'orange', 'green', 'pink', 'teal']).toContain(agentTone('general'))
  })

  it('splitTools trims commas and drops blanks', () => {
    expect(splitTools('web, sandbox.read, , history')).toEqual(['web', 'sandbox.read', 'history'])
    expect(splitTools(null)).toEqual([])
    expect(joinTools([' web ', ''])).toBe('web')
    expect(joinTools([])).toBeNull()
  })
})
