import { describe, expect, it } from 'vitest'
import {
  channelLabel,
  componentClass,
  fmtBytes,
  fmtMs,
  fmtNumber,
  fmtPercent,
  fmtTimer,
  fmtUptime,
  statusClass,
} from './monitorFormat'

describe('monitorFormat 数值格式化', () => {
  it('耗时小于 1s 用 ms，达到 1s 换 s；缺失/负数显示占位', () => {
    expect(fmtMs(0)).toBe('0ms')
    expect(fmtMs(999)).toBe('999ms')
    expect(fmtMs(1500)).toBe('1.5s')
    expect(fmtMs(null)).toBe('—')
    expect(fmtMs(-1)).toBe('—')
  })

  it('计数缺失显示占位，其余按 zh-CN 千分位', () => {
    expect(fmtNumber(null)).toBe('—')
    expect(fmtNumber(12345)).toBe('12,345')
  })

  it('字节按 GB/MB 两档收敛', () => {
    expect(fmtBytes(512 * 1024 ** 2)).toBe('512MB')
    expect(fmtBytes(2 * 1024 ** 3)).toBe('2.0GB')
    expect(fmtBytes(0)).toBe('—')
  })

  it('运行时长按 秒/分/时/天 逐级压缩', () => {
    expect(fmtUptime(35)).toBe('35s')
    expect(fmtUptime(12 * 60)).toBe('12m')
    expect(fmtUptime(2 * 3600 + 15 * 60)).toBe('2h15m')
    expect(fmtUptime(5 * 86400 + 4 * 3600)).toBe('5d4h')
    expect(fmtUptime(null)).toBe('—')
  })

  it('比例按一位小数百分比', () => {
    expect(fmtPercent(0.1234)).toBe('12.3%')
  })

  it('计时器无样本时明确提示，不显示 0ms', () => {
    expect(fmtTimer(null)).toBe('暂无样本')
    expect(fmtTimer({ count: 0, avgMs: 0, maxMs: 0 })).toBe('暂无样本')
    expect(fmtTimer({ count: 3, avgMs: 40, maxMs: 120 })).toBe('3 次 · 均值 40ms · 最大 120ms')
  })
})

describe('monitorFormat 语义映射', () => {
  it('只有 OK 视为正常，其余（含 null）都是异常色', () => {
    expect(statusClass('OK')).toBe('status-ok')
    expect(statusClass('FAILED')).toBe('status-bad')
    expect(statusClass(null)).toBe('status-bad')
  })

  it('组件三态：未启用优先于可用性', () => {
    expect(componentClass({ name: 'mc', enabled: false, available: true, detail: '' })).toBe('status-off')
    expect(componentClass({ name: 'mc', enabled: true, available: true, detail: '' })).toBe('status-ok')
    expect(componentClass({ name: 'mc', enabled: true, available: false, detail: '' })).toBe('status-bad')
  })

  it('渠道键归一：anonymous 已由后端并进 web，未知键原样透出', () => {
    expect(channelLabel('qq')).toBe('QQ')
    expect(channelLabel('cli')).toBe('CLI')
    expect(channelLabel('web')).toBe('Web')
    expect(channelLabel('unknown')).toBe('未知')
    expect(channelLabel(null)).toBe('未知')
    expect(channelLabel('dingtalk')).toBe('dingtalk')
  })
})
