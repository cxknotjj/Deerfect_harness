/**
 * 会话消息本地缓存(localStorage):刷新页面或切回旧会话时先渲染本地副本(避免空窗),
 * 随后由服务端历史覆盖(以服务端为准)。
 * 仅持久化展示必需字段(role/content/ts),执行进度与错误态属临时状态不入库。
 * 写入是尽力而为:Web Storage 不可用或容量不足时静默降级,不影响聊天主流程。
 */

const STORAGE_KEY = 'harness-chat-cache'
/** 最多保留的会话数(超出按 savedAt 淘汰更早的) */
const MAX_SESSIONS = 20
/** 单会话最多保留的消息条数(超出保留最近的部分) */
const MAX_MESSAGES = 200

/** 落盘的单条消息(MessageItem 的展示子集) */
export interface CachedMessage {
  id: number
  role: 'user' | 'assistant'
  content: string
  ts: number
}

/** 单个会话的缓存条目(savedAt 用于会话级淘汰,避免依赖消息时间戳) */
interface CacheEntry {
  savedAt: number
  messages: CachedMessage[]
}

type CacheShape = Record<string, CacheEntry>

/** 读取整份缓存;不存在/损坏/不可用时返回空对象 */
function read(): CacheShape {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw === null) return {}
    const parsed: unknown = JSON.parse(raw)
    return parsed !== null && typeof parsed === 'object' ? (parsed as CacheShape) : {}
  } catch {
    return {}
  }
}

/** 裁剪:丢弃空会话,单会话限长,会话总数按最近写入淘汰 */
function prune(data: CacheShape): CacheShape {
  const out: CacheShape = {}
  for (const [id, entry] of Object.entries(data)) {
    if (!Array.isArray(entry?.messages) || entry.messages.length === 0) continue
    out[id] = { savedAt: entry.savedAt ?? 0, messages: entry.messages.slice(-MAX_MESSAGES) }
  }
  Object.keys(out)
    .sort((a, b) => out[b].savedAt - out[a].savedAt)
    .slice(MAX_SESSIONS)
    .forEach((id) => delete out[id])
  return out
}

/** 写入整份缓存;容量不足时淘汰最早写入的会话重试一次,仍失败则放弃(缓存非必需) */
function write(data: CacheShape): void {
  const kept = prune(data)
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(kept))
  } catch {
    const ids = Object.keys(kept).sort((a, b) => kept[a].savedAt - kept[b].savedAt)
    if (ids.length === 0) return
    delete kept[ids[0]]
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(kept))
    } catch {
      /* 容量仍不足,放弃本次缓存 */
    }
  }
}

/** 读取某会话的本地消息(无缓存返回空数组) */
export function loadCache(sessionId: string): CachedMessage[] {
  const list = read()[sessionId]?.messages
  return Array.isArray(list) ? list : []
}

/** 保存某会话的消息(空数组视为删除该会话缓存) */
export function saveCache(sessionId: string, messages: CachedMessage[]): void {
  const data = read()
  if (messages.length === 0) {
    delete data[sessionId]
  } else {
    data[sessionId] = { savedAt: Date.now(), messages: messages.slice(-MAX_MESSAGES) }
  }
  write(data)
}