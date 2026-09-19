/**
 * 聊天状态:消息按会话分桶保存(切换会话只切视图,数据不丢)、流式发送、进度收集、
 * 错误呈现与取消。
 * 流程:send() → user 消息立即上屏 → streamChat 流式追加 assistant 消息
 * (progress 进度轨迹 + token 增量)→ 收尾(onDone/流内 error/取消/异常)统一解锁输入
 * 并清空该消息的进度徽标。
 */
import { ref } from 'vue'
import { api } from '../api'
import type { ProgressPayload } from '../api'

/** 单条聊天消息:role 决定对齐;progress 为执行阶段轨迹(流结束后清空);error 标记错误样式 */
export interface MessageItem {
  id: number
  role: 'user' | 'assistant'
  content: string
  progress: ProgressPayload[]
  error: boolean
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

  /** 取桶(无则建空桶);返回原始数组,视图侧经 messages 代理读写保证响应式 */
  function bucketOf(key: string): MessageItem[] {
    let list = store.get(key)
    if (!list) {
      list = []
      store.set(key, list)
    }
    return list
  }

  // 初始视图:草稿桶(未选中会话)
  messages.value = bucketOf(DRAFT_KEY)

  /** 切换会话视图:终止进行中的流,把视图指向目标会话的消息桶(消息按会话保留,不清空) */
  function show(sessionId: string): void {
    stop()
    viewKey = sessionId === '' ? DRAFT_KEY : sessionId
    messages.value = bucketOf(viewKey)
  }

  /** 把错误文案并入当前 assistant 消息(错误样式,而非弹窗) */
  function appendError(target: MessageItem, text: string): void {
    target.error = true
    target.content = target.content === '' ? text : `${target.content}\n\n${text}`
  }

  /** 发送一条消息并流式接收回复 */
  async function send(raw: string): Promise<void> {
    const text = raw.trim()
    if (text === '' || streaming.value) return

    // user 消息与 assistant 占位立即进当前会话桶
    const list = messages.value
    list.push({ id: ++seq, role: 'user', content: text, progress: [], error: false })
    list.push({ id: ++seq, role: 'assistant', content: '', progress: [], error: false })
    // 从数组取回 reactive 代理引用:直接持有原始对象修改不会触发视图更新
    const assistant = list[list.length - 1]

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
    }
  }

  /** 停止当前流:AbortSignal 取消,主动取消不视为错误 */
  function stop(): void {
    controller?.abort()
    controller = null
    streaming.value = false
  }

  return { messages, streaming, send, stop, show }
}
