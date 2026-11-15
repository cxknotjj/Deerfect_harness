/**
 * 资源面展示格式化：后端 LocalDateTime（无时区后缀）与文件大小。
 * 纯函数，无 Vue 依赖，便于单测。
 *
 * 注意：后端返回的是本地墙上时间（如 2026-09-10T11:56:34），不能当 UTC 解析；
 * 用 new Date(value) 让浏览器按本地时区解释，避免额外 +8 小时偏移。
 */

function parseLocal(value: string | null | undefined): Date | null {
  if (!value) return null
  const normalized = value.includes(' ') ? value.replace(' ', 'T') : value
  const d = new Date(normalized)
  return Number.isNaN(d.getTime()) ? null : d
}

function pad(n: number): string {
  return String(n).padStart(2, '0')
}

/** LocalDateTime → "MM-DD HH:mm"；缺失或无法解析显示 — */
export function fmtDateTime(value: string | null | undefined): string {
  const d = parseLocal(value)
  if (!d) return '—'
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** LocalDateTime → 日期块用的 "MM-DD" / "周二"；缺失或无法解析显示 — */
export function fmtDayParts(value: string | null | undefined): { day: string; week: string } {
  const d = parseLocal(value)
  if (!d) return { day: '—', week: '—' }
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getDay()]
  return { day: `${pad(d.getMonth() + 1)}-${pad(d.getDate())}`, week }
}

/** 字节 → "9.6 KB" / "1.2 MB"；0 或负值显示 0 B */
export function fmtBytes(value: number | null | undefined): string {
  const n = value ?? 0
  if (n <= 0) return '0 B'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
}

/** LocalDateTime → epoch 毫秒（排序用）；无法解析返回 Number.POSITIVE_INFINITY 排到末尾 */
export function localTimeMs(value: string | null | undefined): number {
  const d = parseLocal(value)
  return d ? d.getTime() : Number.POSITIVE_INFINITY
}
