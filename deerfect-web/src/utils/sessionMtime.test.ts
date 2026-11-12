import { describe, expect, it, vi } from 'vitest'
import { getMtime, touch } from './sessionMtime'

/** 会话首次见到的近似活跃时间（模块级 Map，测试用互不重复的 id 避免串扰） */
describe('sessionMtime', () => {
  it('未记录的会话返回 null', () => {
    expect(getMtime('unknown-1')).toBeNull()
  })

  it('touch 记录首次见到的时间并返回', () => {
    vi.useFakeTimers()
    vi.setSystemTime(1_000_000)
    expect(touch('s-1')).toBe(1_000_000)
    expect(getMtime('s-1')).toBe(1_000_000)
    vi.useRealTimers()
  })

  it('touch 幂等：已有记录不被覆盖', () => {
    vi.useFakeTimers()
    vi.setSystemTime(2_000_000)
    expect(touch('s-2')).toBe(2_000_000)
    vi.setSystemTime(3_000_000)
    expect(touch('s-2')).toBe(2_000_000)
    expect(getMtime('s-2')).toBe(2_000_000)
    vi.useRealTimers()
  })
})
