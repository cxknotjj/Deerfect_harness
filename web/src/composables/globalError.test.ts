import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { reportGlobalError, resetGlobalErrorForTest, useGlobalError } from './globalError'

/**
 * 全局错误横幅：上报后可见、8s 自动消失、可手动关闭、堆栈不被塞进横幅。
 */
describe('globalError', () => {
  const { message, dismiss } = useGlobalError()

  beforeEach(() => {
    vi.useFakeTimers()
    vi.spyOn(console, 'error').mockImplementation(() => {})
    resetGlobalErrorForTest()
  })

  afterEach(() => {
    resetGlobalErrorForTest()
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('上报后显示「类型: 首行消息」，多行堆栈不进入横幅', () => {
    reportGlobalError(new Error('boom\n    at somewhere'))

    expect(message.value).toBe('Error: boom')
  })

  it('带上下文时前缀上下文，便于区分是渲染错误还是异步错误', () => {
    reportGlobalError(new Error('db down'), '未处理的异步错误')

    expect(message.value).toBe('未处理的异步错误：Error: db down')
  })

  it('非 Error 值也能显示（Promise 里 reject 字符串的常见情况）', () => {
    reportGlobalError('plain failure')

    expect(message.value).toBe('plain failure')
  })

  it('8 秒后自动消失', () => {
    reportGlobalError(new Error('temporary'))

    vi.advanceTimersByTime(8_000)

    expect(message.value).toBe('')
  })

  it('再次上报会重置自动消失计时（新错误不会被旧计时提前清掉）', () => {
    reportGlobalError(new Error('first'))
    vi.advanceTimersByTime(5_000)
    reportGlobalError(new Error('second'))

    vi.advanceTimersByTime(5_000)
    expect(message.value).toBe('Error: second')

    vi.advanceTimersByTime(3_000)
    expect(message.value).toBe('')
  })

  it('可手动关闭', () => {
    reportGlobalError(new Error('dismiss me'))

    dismiss()

    expect(message.value).toBe('')
  })

  it('始终在控制台保留完整异常（横幅只负责告知）', () => {
    const err = new Error('with stack')
    reportGlobalError(err)

    expect(console.error).toHaveBeenCalledWith('[web]', '全局异常', err)
  })
})
