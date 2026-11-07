/**
 * PWA 运行时能力:
 * - useOfflineGate:断网门。应用壳已由 service worker 预缓存,断网仍能启动,由此页替代主界面。
 * - useUnreadBadge:主屏未读角标(Badging API)。仅 Chromium 系支持,iOS Safari 无此能力时静默跳过。
 * 监听在返回的 stop() 中注销,由调用方在卸载时执行——组合函数不依赖组件生命周期,便于单测。
 */
import { ref } from 'vue'

/** Badging API 尚未进入 TS DOM lib,按能力探测做最小声明 */
type BadgingNavigator = Navigator & {
  setAppBadge?: (contents?: number) => Promise<void>
  clearAppBadge?: () => Promise<void>
}

export function useOfflineGate() {
  const offline = ref(!navigator.onLine)
  /** 启动时就断网:恢复联网后直接重载,避免带着「初始化全部失败」的空壳继续用 */
  const bootOffline = offline.value

  function onOffline(): void {
    offline.value = true
  }
  function onOnline(): void {
    if (bootOffline) {
      window.location.reload()
      return
    }
    offline.value = false
  }

  window.addEventListener('offline', onOffline)
  window.addEventListener('online', onOnline)

  /** 离线页的重连按钮:重载后仍断网则 SW 继续供给外壳,联网则进入正常流程 */
  function retry(): void {
    window.location.reload()
  }
  function stop(): void {
    window.removeEventListener('offline', onOffline)
    window.removeEventListener('online', onOnline)
  }

  return { offline, retry, stop }
}

export function useUnreadBadge() {
  const nav = navigator as BadgingNavigator
  let count = 0

  async function sync(): Promise<void> {
    try {
      if (count > 0) await nav.setAppBadge?.(count)
      else await nav.clearAppBadge?.()
    } catch {
      /* 角标只是增强信息:浏览器拒绝或无权限不影响聊天 */
    }
  }

  /** 一轮回复收尾时调用:页面可见不计未读,不可见才累加 */
  function bump(): void {
    if (document.visibilityState === 'visible') return
    count += 1
    void sync()
  }

  function clear(): void {
    if (count === 0) return
    count = 0
    void sync()
  }

  function onVisibility(): void {
    if (document.visibilityState === 'visible') clear()
  }
  document.addEventListener('visibilitychange', onVisibility)

  function stop(): void {
    document.removeEventListener('visibilitychange', onVisibility)
  }

  return { bump, clear, stop }
}
