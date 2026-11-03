import type { MonitorComponent, MonitorTimerStats } from '../api'

/**
 * 运行监控的展示格式化：纯函数，无 Vue 依赖，便于单测。
 * 数值口径与后端 DTO 对齐（毫秒 / 字节 / 秒 / 0-1 比例）。
 */

/** epoch 毫秒 → 月-日 时:分:秒 */
export function fmtClock(ms: number): string {
  if (!ms) return '—'
  const d = new Date(ms)
  const pad = (v: number): string => String(v).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 毫秒 → 350ms / 1.2s；缺失或负数显示 — */
export function fmtMs(value: number | null | undefined): string {
  if (value == null || value < 0) return '—'
  return value >= 1000 ? `${(value / 1000).toFixed(1)}s` : `${value}ms`
}

export function fmtNumber(value: number | null | undefined): string {
  return value == null ? '—' : value.toLocaleString('zh-CN')
}

/** 0-1 比例 → 12.3% */
export function fmtPercent(value: number): string {
  return `${(value * 100).toFixed(1)}%`
}

/** 字节 → 紧凑容量：187MB / 4.0GB */
export function fmtBytes(bytes: number | null | undefined): string {
  if (bytes == null || bytes <= 0) return '—'
  const gb = bytes / 1024 ** 3
  return gb >= 1 ? `${gb.toFixed(1)}GB` : `${Math.round(bytes / 1024 ** 2)}MB`
}

/** 秒 → 紧凑运行时长：35s / 12m / 2h15m / 5d4h */
export function fmtUptime(seconds: number | null | undefined): string {
  if (seconds == null || seconds <= 0) return '—'
  const total = Math.floor(seconds)
  if (total < 60) return `${total}s`
  if (total < 3600) return `${Math.floor(total / 60)}m`
  if (total < 86400) return `${Math.floor(total / 3600)}h${Math.floor((total % 3600) / 60)}m`
  return `${Math.floor(total / 86400)}d${Math.floor((total % 86400) / 3600)}h`
}

/** 计时器累计文案：次数 + 均值/最大；无样本时明确提示，避免显示 0ms 误导 */
export function fmtTimer(stats: MonitorTimerStats | null | undefined): string {
  if (!stats || stats.count === 0) return '暂无样本'
  return `${fmtNumber(stats.count)} 次 · 均值 ${fmtMs(stats.avgMs)} · 最大 ${fmtMs(stats.maxMs)}`
}

/** ISO 时间串 → MM-DD HH:mm */
export function fmtTime(value: string | null | undefined): string {
  if (!value) return '—'
  const text = value.replace('T', ' ')
  return text.length >= 16 ? text.slice(5, 16) : text
}

/** 调用状态 → 样式类（只有 OK 视为正常） */
export function statusClass(status: string | null | undefined): string {
  return status === 'OK' ? 'status-ok' : 'status-bad'
}

/** 组件状态：未启用（灰）/ 可用（蓝）/ 不可用（橙） */
export function componentLabel(component: MonitorComponent): string {
  if (!component.enabled) return '未启用'
  return component.available ? '可用' : '不可用'
}

export function componentClass(component: MonitorComponent): string {
  if (!component.enabled) return 'status-off'
  return component.available ? 'status-ok' : 'status-bad'
}

/** 渠道键 → 展示名；后端已把 anonymous/web 归并为 web，未知会话为 unknown */
export function channelLabel(key: string | null | undefined): string {
  switch (key) {
    case 'qq':
      return 'QQ'
    case 'cli':
      return 'CLI'
    case 'web':
      return 'Web'
    case 'unknown':
    case '':
    case null:
    case undefined:
      return '未知'
    default:
      return key
  }
}

/** 上下文构成分组 → 堆叠条/色点样式类（system / retrieval / tools / conversation） */
export function ctxGroupClass(group: string): string {
  return `ctx-${group}`
}
