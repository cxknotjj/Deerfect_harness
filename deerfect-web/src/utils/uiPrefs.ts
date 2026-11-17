/**
 * 本机界面偏好：字号缩放、侧栏密度、会话恢复、自动检查更新。
 *
 * 这些项只影响当前浏览器（localStorage），不写服务端；App 启动时统一应用，
 * 设置页保存后即时生效。
 */

export type Density = 'compact' | 'standard'
export type ZoomScale = '0.9' | '1' | '1.1'
export type ThemeMode = 'light' | 'dark' | 'system'

export const UI_KEYS = {
  themeMode: 'web-theme-mode',
  zoom: 'ui-zoom',
  density: 'ui-density',
  restoreSession: 'ui-restore-session',
  autoUpdate: 'ui-auto-update',
  lastSession: 'ui-last-session',
  ttsPrefs: 'ui-tts-prefs',
} as const

/** 字号缩放：zoom 为 1 时清空内联样式，交回 UA 默认 */
export function applyZoom(scale: string): void {
  document.documentElement.style.zoom = scale === '1' ? '' : scale
}

/** 侧栏密度：data-density 由 nav.css 消费 */
export function applyDensity(density: Density): void {
  document.documentElement.dataset.density = density
}

/** 启动时应用（main.ts 在挂载前调用，避免首帧跳动） */
export function loadUiPrefs(): void {
  applyZoom(localStorage.getItem(UI_KEYS.zoom) ?? '1')
  const density = localStorage.getItem(UI_KEYS.density)
  applyDensity(density === 'compact' ? 'compact' : 'standard')
}

export function readBool(key: string, fallback: boolean): boolean {
  const raw = localStorage.getItem(key)
  if (raw === '1') return true
  if (raw === '0') return false
  return fallback
}

export function writeBool(key: string, value: boolean): void {
  localStorage.setItem(key, value ? '1' : '0')
}
