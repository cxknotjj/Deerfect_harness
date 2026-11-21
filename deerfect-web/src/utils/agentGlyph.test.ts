import { describe, expect, it } from 'vitest'
import { agentGlyphFor, agentGlyphKey, BUILTIN_AGENT_GLYPHS } from './agentGlyph'

const BUILTIN_NAMES = [
  'general',
  'deepseek',
  'multi-agent',
  'lead',
  'aggregator',
  'researcher',
  'coder',
  'analyst',
  'writer',
  'nailong',
  'java-tutor',
  'route-judge',
  'wms',
  'profile',
  'summary',
  'trigger-judge',
]

describe('agentGlyph', () => {
  it('covers all 16 built-in agents with unique shapes', () => {
    expect(Object.keys(BUILTIN_AGENT_GLYPHS)).toHaveLength(16)
    const keys = BUILTIN_NAMES.map((name) => agentGlyphKey(name))
    expect(keys.filter((key) => key !== null)).toHaveLength(16)
    expect(new Set(keys).size).toBe(16)
  })

  it('every built-in agent has drawable geometry', () => {
    for (const name of BUILTIN_NAMES) {
      const glyph = agentGlyphFor(name)
      expect(glyph).not.toBeNull()
      const parts = (glyph?.paths.length ?? 0) + (glyph?.dots?.length ?? 0) + (glyph?.ellipses?.length ?? 0)
      expect(parts, name).toBeGreaterThan(0)
    }
  })

  it('normalizes names and falls back for unknown agents', () => {
    expect(agentGlyphKey(' General ')).toBe('sparkles')
    expect(agentGlyphKey('DEEPSEEK')).toBe('atom')
    expect(agentGlyphKey('custom-agent')).toBeNull()
    expect(agentGlyphKey('  ')).toBeNull()
  })
})
