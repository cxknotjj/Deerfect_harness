import { describe, expect, it } from 'vitest'
import { fmtBytes, fmtDateTime, fmtDayParts, localTimeMs } from './resourceFormat'

/**
 * 后端 LocalDateTime 无时区后缀：这些用例刻意用本地墙上时间字符串，
 * 断言不依赖机器所在时区（不把字符串当 UTC 解析）。
 */
describe('resourceFormat', () => {
  it('fmtDateTime 输出月-日 时:分，不产生 UTC 偏移', () => {
    expect(fmtDateTime('2026-11-15T09:30:12')).toBe('11-15 09:30')
    expect(fmtDateTime('2026-01-02 08:05:00')).toBe('01-02 08:05')
  })

  it('缺失或无法解析的时间显示 —', () => {
    expect(fmtDateTime(null)).toBe('—')
    expect(fmtDateTime('')).toBe('—')
    expect(fmtDateTime('not-a-date')).toBe('—')
    expect(fmtDayParts(null)).toEqual({ day: '—', week: '—' })
  })

  it('fmtDayParts 给出日期块与本地星期', () => {
    // 2026-11-15 是周日；用 new Date 的本地解释与实现一致
    expect(new Date('2026-11-15T09:30:12').getDay()).toBe(0)
    expect(fmtDayParts('2026-11-15T09:30:12')).toEqual({ day: '11-15', week: '周日' })
  })

  it('fmtBytes 按 B / KB / MB 分档', () => {
    expect(fmtBytes(0)).toBe('0 B')
    expect(fmtBytes(512)).toBe('512 B')
    expect(fmtBytes(9825)).toBe('9.6 KB')
    expect(fmtBytes(1024 * 1024 * 2)).toBe('2.0 MB')
    expect(fmtBytes(null)).toBe('0 B')
  })

  it('localTimeMs 可排序，缺失值排末尾', () => {
    const earlier = localTimeMs('2026-11-15T09:00:00')
    const later = localTimeMs('2026-11-15T10:00:00')
    expect(later).toBeGreaterThan(earlier)
    expect(localTimeMs(null)).toBe(Number.POSITIVE_INFINITY)
  })
})
