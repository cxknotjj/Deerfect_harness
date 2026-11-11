/**
 * 相对时间格式化:把毫秒时间戳转为截图口径的灰色短文案
 * (「刚刚」「N分钟」「N小时」「N天」),超过 30 天回退为 M/D 日期。
 * 纯函数无副作用,便于单测;未来时间戳(时钟偏差)按「刚刚」处理。
 */

/** 单条相对时间格式化(公开纯函数,便于测试) */
export function relativeTime(ts: number, now: number = Date.now()): string {
  if (!Number.isFinite(ts) || ts <= 0) return ''
  const diff = Math.max(0, now - ts)
  const minute = 60_000
  const hour = 60 * minute
  const day = 24 * hour

  if (diff < minute) return '刚刚'
  if (diff < hour) return `${Math.floor(diff / minute)}分钟`
  if (diff < day) return `${Math.floor(diff / hour)}小时`
  if (diff < 30 * day) return `${Math.floor(diff / day)}天`
  const d = new Date(ts)
  return `${d.getMonth() + 1}/${d.getDate()}`
}
