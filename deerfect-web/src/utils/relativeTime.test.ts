import { describe, expect, it } from 'vitest'
import { relativeTime } from './relativeTime'

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

describe('relativeTime', () => {
  const now = new Date('2026-09-25T12:00:00').getTime()

  it('非法/零值时间戳返回空串', () => {
    expect(relativeTime(0, now)).toBe('')
    expect(relativeTime(Number.NaN, now)).toBe('')
    expect(relativeTime(-5, now)).toBe('')
  })

  it('1 分钟内返回「刚刚」,未来时间戳(时钟偏差)同样按刚刚处理', () => {
    expect(relativeTime(now - 30_000, now)).toBe('刚刚')
    expect(relativeTime(now, now)).toBe('刚刚')
    expect(relativeTime(now + 5 * MINUTE, now)).toBe('刚刚')
  })

  it('1 小时内按分钟,1 天内按小时', () => {
    expect(relativeTime(now - 5 * MINUTE, now)).toBe('5分钟')
    expect(relativeTime(now - 59 * MINUTE, now)).toBe('59分钟')
    expect(relativeTime(now - 3 * HOUR, now)).toBe('3小时')
  })

  it('30 天内按天,超过回退 M/D 日期', () => {
    expect(relativeTime(now - 2 * DAY, now)).toBe('2天')
    expect(relativeTime(now - 29 * DAY, now)).toBe('29天')
    expect(relativeTime(now - 31 * DAY, now)).toBe('8/25')
  })
})
