/**
 * 聊天状态:消息按会话分桶保存(切换会话只切视图,数据不丢)、流式发送、进度收集、
 * 错误呈现与取消。
 * 流程:send() → user 消息立即上屏 → streamText() 流式追加 assistant 消息
 * (progress 进度轨迹 + token 增量)→ 收尾(onDone/流内 error/取消/异常)统一解锁输入
 * 并清空该消息的进度徽标。
 * 历史与持久化:切回旧会话先用 localStorage 缓存即时渲染,再拉服务端历史覆盖
 * (以服务端为准,见 chatCache.ts);消息进出后把当前会话落盘。
 */
import { ref } from 'vue'
import { api } from '../api'
import type { ProgressPayload, SessionMessageView } from '../api'
import { loadCache, saveCache } from './chatCache'

/** 单条聊天消息:role 决定对齐;progress 为执行阶段轨迹(流结束后清空);error 标记错误样式;
 *  ts 为纯展示字段(消息头时间标注),不参与任何请求/逻辑;ts 为 0 表示时间未知(历史回显),不展示 */
export interface MessageItem {
  id: number
  role: 'user' | 'assistant'
  content: string
  progress: ProgressPayload[]
  error: boolean
  ts: number
}

/** 与会话列表联动所需的最小钩子(避免组合函数相互依赖) */
export interface ChatHooks {
  /** 当前选中会话 id(可能为空串) */
  getSessionId(): string
  /** 回写会话 id:后端在无 sessionId 时可能新建会话(meta 携带新 id) */
  setSessionId(id: string): void
  /** meta 报告后端新建了会话时通知外层刷新列表(可选) */
  onNewSession?(): void
}

/** 未选中会话时的草稿桶 key(本轮后端可能新建会话,onMeta 回传后迁移到真实 id) */
const DRAFT_KEY = '__draft__'

/** 消息 id 自增序列(模块级,重挂载也不重复) */
let seq = 0

export function useChat(hooks: ChatHooks) {
  /** 消息仓库:key = 会话 id;value = 该会话的消息桶(原始数组) */
  const store = new Map<string, MessageItem[]>()
  /** 当前会话的消息视图(指向仓库对应桶;读写经 ref 的响应式代理,切会话仅换引用) */
  const messages = ref<MessageItem[]>([])
  /** 视图当前指向的桶 key(与 hooks.getSessionId() 保持同步) */
  let viewKey = DRAFT_KEY
  /** 是否正在流式接收(streaming 中禁止再次发送) */
  const streaming = ref(false)
  /** 当前流的取消控制器(同一时刻至多一个流) */
  let controller: AbortController | null = null

  /** 由本地缓存构造消息桶(id 序列抬到缓存最大值之后避免重号;进度/错误态不持久化) */
  function fromCache(key: string): MessageItem[] {
    if (key === DRAFT_KEY) return []
    const cached = loadCache(key)
    for (const m of cached) {
      if (m.id > seq) seq = m.id
    }
    return cached.map((m) => ({ ...m, progress: [], error: false }))
  }

  /** 取桶(无则建:优先用本地缓存填充,实现切回旧会话即时渲染) */
  function bucketOf(key: string): MessageItem[] {
    let list = store.get(key)
    if (!list) {
      list = fromCache(key)
      store.set(key, list)
    }
    return list
  }

  /** 当前会话落盘(草稿会话不落盘:onMeta 迁移到真实会话 id 后自然入库) */
  function persist(): void {
    const key = hooks.getSessionId()
    if (key === '') return
    // 空内容(流式中/已取消)与错误轮次不落盘:恢复后无意义,反而误导
    saveCache(
      key,
      messages.value.filter((m) => m.content !== '' && !m.error),
    )
  }

  /** 服务端历史 → 消息项(ts 留 0,历史轮次无原始时间,前端不展示时间标注) */
  function toItem(m: SessionMessageView): MessageItem {
    return {
      id: ++seq,
      role: m.role === 'assistant' ? 'assistant' : 'user',
      content: m.content,
      progress: [],
      error: false,
      ts: 0,
    }
  }

  /** 拉取服务端历史并就地覆盖当前桶(本地缓存负责即时渲染,服务端为准;期间已切走则丢弃结果) */
  async function loadHistory(key: string): Promise<void> {
    if (key === DRAFT_KEY) return
    try {
      const resp = await api.listMessages(key)
      if (viewKey !== key) return
      const view = messages.value
      view.splice(0, view.length, ...resp.messages.map(toItem))
      persist()
    } catch {
      /* 拉取失败静默:本地缓存已渲染,不打断使用 */
    }
  }

  // 初始视图:草稿桶(未选中会话)
  messages.value = bucketOf(DRAFT_KEY)

  /** 切换会话视图:终止进行中的流,把视图指向目标会话的消息桶(消息按会话保留,不清空),
   *  随后拉取服务端历史覆盖(本地缓存先行渲染) */
  function show(sessionId: string): void {
    stop()
    viewKey = sessionId === '' ? DRAFT_KEY : sessionId
    messages.value = bucketOf(viewKey)
    void loadHistory(viewKey)
  }

  /** 把错误文案并入当前 assistant 消息(错误样式,而非弹窗) */
  function appendError(target: MessageItem, text: string): void {
    target.error = true
    target.content = target.content === '' ? text : `${target.content}\n\n${text}`
  }

  /** 流式接收文本并写入指定 assistant 消息(发送与重新生成共用);收尾统一落盘 */
  async function streamText(assistant: MessageItem, text: string): Promise<void> {
    streaming.value = true
    controller = new AbortController()
    try {
      await api.streamChat(
        // 无选中会话时不带 sessionId:后端可能新建并经 meta 回传
        { message: text, sessionId: hooks.getSessionId() || undefined },
        {
          onProgress: (p) => {
            assistant.progress.push(p)
          },
          onToken: (t) => {
            assistant.content += t
          },
          onError: (msg) => {
            appendError(assistant, msg)
          },
          onMeta: (meta) => {
            const sid = meta.sessionId?.trim() ?? ''
            if (sid === '' || sid === hooks.getSessionId()) return
            // 后端在本轮新建会话:把当前消息桶迁移到真实会话 id 下
            // (视图本就指向同一数组,消息无缝延续;旧 key 移除避免残留)
            const current = bucketOf(viewKey)
            store.delete(viewKey)
            store.set(sid, current)
            viewKey = sid
            hooks.setSessionId(sid)
            if (meta.newSession) hooks.onNewSession?.()
          },
        },
        controller.signal,
      )
    } catch (e) {
      // 主动取消静默结束(不视为错误);其余异常(网络/HTTP)显示为 assistant 错误消息
      if (!(e instanceof DOMException && e.name === 'AbortError')) {
        appendError(assistant, e instanceof Error ? e.message : String(e))
      }
    } finally {
      // onDone、流内 error、取消、异常统一收尾:解锁输入 + 清空进度徽标(不留残影)
      controller = null
      streaming.value = false
      assistant.progress = []
      persist()
    }
  }

  /** 发送一条消息并流式接收回复 */
  async function send(raw: string): Promise<void> {
    const text = raw.trim()
    if (text === '' || streaming.value) return

    // user 消息与 assistant 占位立即进当前会话桶
    const list = messages.value
    list.push({ id: ++seq, role: 'user', content: text, progress: [], error: false, ts: Date.now() })
    list.push({ id: ++seq, role: 'assistant', content: '', progress: [], error: false, ts: Date.now() })
    // 从数组取回 reactive 代理引用:直接持有原始对象修改不会触发视图更新
    await streamText(list[list.length - 1], text)
  }

  /** 重新生成:只作用于最后一条 assistant 回复,复用其上一条 user 消息原地重跑(不新增 user 气泡) */
  async function regenerate(id: number): Promise<void> {
    if (streaming.value) return
    const list = messages.value
    const last = list[list.length - 1]
    const prev = list[list.length - 2]
    if (last?.id !== id || last.role !== 'assistant' || prev?.role !== 'user') return
    list.pop()
    list.push({ id: ++seq, role: 'assistant', content: '', progress: [], error: false, ts: Date.now() })
    await streamText(list[list.length - 1], prev.content)
  }

  /** 删除一条 user 消息(连同其后紧邻的 assistant 回复整轮移除;本地视图层,真模式的后端记忆清理待后端端点) */
  function remove(id: number): void {
    const list = messages.value
    const i = list.findIndex((m) => m.id === id)
    if (i === -1) return
    list.splice(i, list[i + 1]?.role === 'assistant' ? 2 : 1)
    persist()
  }

  /** 停止当前流:AbortSignal 取消,主动取消不视为错误 */
  function stop(): void {
    controller?.abort()
    controller = null
    streaming.value = false
  }

  return { messages, streaming, send, stop, show, remove, regenerate }
}