import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useOfflineGate, useUnreadBadge } from './pwa'

/** navigator.onLine 在 jsdom 上可重定义,逐例复位避免跨用例污染 */
function setOnline(value: boolean): void {
  Object.defineProperty(navigator, 'onLine', { configurable: true, get: () => value })
}

function setVisibility(state: DocumentVisibilityState): void {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
}

describe('useOfflineGate', () => {
  afterEach(() => setOnline(true))

  it('联网初始不显示离线门,断网/恢复事件切换状态', () => {
    setOnline(true)
    const gate = useOfflineGate()

    expect(gate.offline.value).toBe(false)
    window.dispatchEvent(new Event('offline'))
    expect(gate.offline.value).toBe(true)
    window.dispatchEvent(new Event('online'))
    expect(gate.offline.value).toBe(false)

    gate.stop()
  })

  it('启动即断网时直接显示离线门', () => {
    setOnline(false)
    const gate = useOfflineGate()
    expect(gate.offline.value).toBe(true)
    gate.stop()
  })

  it('stop 后不再响应网络事件', () => {
    setOnline(true)
    const gate = useOfflineGate()
    gate.stop()

    window.dispatchEvent(new Event('offline'))
    expect(gate.offline.value).toBe(false)
  })
})

describe('useUnreadBadge', () => {
  const setAppBadge = vi.fn<(contents?: number) => Promise<void>>()
  const clearAppBadge = vi.fn<() => Promise<void>>()

  beforeEach(() => {
    setVisibility('hidden')
    Object.defineProperty(navigator, 'setAppBadge', { configurable: true, value: setAppBadge })
    Object.defineProperty(navigator, 'clearAppBadge', { configurable: true, value: clearAppBadge })
    setAppBadge.mockClear()
    clearAppBadge.mockClear()
  })

  afterEach(() => {
    delete (navigator as { setAppBadge?: unknown }).setAppBadge
    delete (navigator as { clearAppBadge?: unknown }).clearAppBadge
    setVisibility('visible')
  })

  it('页面不可见时每轮回复累加角标,回到前台清零', () => {
    const badge = useUnreadBadge()

    badge.bump()
    expect(setAppBadge).toHaveBeenLastCalledWith(1)
    badge.bump()
    expect(setAppBadge).toHaveBeenLastCalledWith(2)

    setVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(clearAppBadge).toHaveBeenCalledTimes(1)

    badge.stop()
  })

  it('页面可见时不计未读', () => {
    setVisibility('visible')
    const badge = useUnreadBadge()

    badge.bump()
    expect(setAppBadge).not.toHaveBeenCalled()
    expect(clearAppBadge).not.toHaveBeenCalled()

    badge.stop()
  })

  it('浏览器不支持 Badging API 时静默不抛错', () => {
    delete (navigator as { setAppBadge?: unknown }).setAppBadge
    delete (navigator as { clearAppBadge?: unknown }).clearAppBadge

    const badge = useUnreadBadge()
    expect(() => badge.bump()).not.toThrow()

    badge.stop()
  })
})
