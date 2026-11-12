/**
 * 会话最近活跃时间缓存:SessionView 暂无 updated_at 类时间字段(后端未返回),
 * 以「前端首次见到该会话」的时间近似其最近活跃时间——首次加载按「刚刚」起步,
 * 随时间流逝由相对时间函数换算;后端补时间字段后整体切换,删除本模块。
 * 模块级 Map 生命周期与页面一致,刷新后重置(重新从「刚刚」起步),不落盘。
 */

const mtimes = new Map<string, number>()

/** 取会话最近活跃时间(毫秒时间戳);未见过的会话返回 null */
export function getMtime(id: string): number | null {
  return mtimes.get(id) ?? null
}

/** 记录会话首次见到的时间(已有记录则不覆盖,幂等),返回其最近活跃时间 */
export function touch(id: string): number {
  const existing = mtimes.get(id)
  if (existing !== undefined) return existing
  const now = Date.now()
  mtimes.set(id, now)
  return now
}
