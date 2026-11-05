/**
 * 流式聊天（POST /api/chat/stream）：逐行解析 SSE 帧并分发到回调。
 * 协议：chunked 文本，每帧两行 `event: <名>` + `data: <载荷>`（无空行）。
 */
import type { ChatRequest, ProgressPayload, SseMeta } from './types'
import { baseHeaders, notifyUnauthorized } from './http'

/** SSE 事件名与结束标记（与 harness-api 的 SseProtocol 常量字面量一致） */
const EVENT_META = 'meta'
const EVENT_ERROR = 'error'
const EVENT_PROGRESS = 'progress'
const EVENT_TOKEN = 'token'
const DONE_MARKER = '[DONE]'

/** 流式聊天回调集（全部可选，按需订阅） */
export interface StreamHandlers {
  /** 流末元数据（sessionId/goalId/status/sources） */
  onMeta?: (meta: SseMeta) => void
  /** 执行进度（多 Agent 编排阶段反馈，{stage,detail}） */
  onProgress?: (progress: ProgressPayload) => void
  /** 内容 token（换行已解码还原） */
  onToken?: (token: string) => void
  /** 服务端 error 事件（流内业务错误） */
  onError?: (message: string) => void
  /** token 流结束（读到 [DONE]） */
  onDone?: () => void
}

/** JSON 解析失败返回 null（SSE 协议层容错，不向上抛） */
function parseJson<T>(raw: string): T | null {
  try {
    return JSON.parse(raw) as T
  } catch {
    return null
  }
}

/**
 * 还原 SseProtocol.escapeLineBreaks 的转义（接收侧）：
 * 字面量 \n → 换行、\r → 回车、\\ → 反斜杠；非法序列原样保留。
 */
function unescapeLineBreaks(s: string): string {
  if (!s.includes('\\')) return s
  let out = ''
  for (let i = 0; i < s.length; i++) {
    const c = s.charAt(i)
    if (c === '\\' && i + 1 < s.length) {
      const n = s.charAt(++i)
      if (n === 'n') out += '\n'
      else if (n === 'r') out += '\r'
      else if (n === '\\') out += '\\'
      else out += c + n
    } else {
      out += c
    }
  }
  return out
}

/** 剥掉 "data:" 前缀与其后至多一个空格（SSE 规范），保留载荷原文（缩进空格不丢）；
 *  尾部 \r 去除（服务端若发 \r\n 行尾，split('\n') 后载荷尾残留 \r 会污染 JSON/token） */
function dataPayload(line: string): string {
  const rest = line.slice('data:'.length)
  const noSpace = rest.startsWith(' ') ? rest.slice(1) : rest
  return noSpace.endsWith('\r') ? noSpace.slice(0, -1) : noSpace
}

/**
 * 流式聊天（POST /api/chat/stream）：body.getReader() + TextDecoder 逐行解析 SSE 帧。
 * - 每个 data: 行即分发（与 CLI readSse 一致；服务端每元素自带 event+data，无空行，空行仅忽略）
 * - event 字段分发后复位：历史上 token 块可能缺 event: 行，无 event: 的 data 一律按 token 兜底
 * - token 载荷做换行转义解码；读到 [DONE] 调 onDone；meta 载荷为 JSON
 * - 传入 AbortSignal 可取消；主动取消静默结束（不视为错误），其余读取异常向上抛
 * - 发起失败（连接建立瞬间的 keep-alive 竞态/网络瞬断）自动重试一次：仅兜「尚未拿到响应头」
 *   的失败，流建立后的中断不在此列（部分输出已上屏，重发与否交用户手动重试）；abort 不重试
 */
export async function streamChat(
  req: ChatRequest,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const initiate = (): Promise<Response> =>
    fetch('/api/chat/stream', {
      method: 'POST',
      headers: { ...baseHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
      signal,
    })

  let resp: Response
  try {
    resp = await initiate()
  } catch (e) {
    if (isAbort(e)) return
    if (signal?.aborted) return
    // 发起失败自动重试一次（短暂退避，给链路层竞态自愈窗口）；期间点停止则尊重取消
    await new Promise((resolve) => setTimeout(resolve, 400))
    if (signal?.aborted) return
    try {
      resp = await initiate()
    } catch (e2) {
      if (isAbort(e2)) return
      throw e2
    }
  }
  if (!resp.ok || !resp.body) {
    if (resp.status === 401) notifyUnauthorized()
    throw new Error(`HTTP ${resp.status}: ${await resp.text()}`)
  }

  // 当前帧事件名；在 data 行分发后复位，保证「无 event: 的 data」落入 token 兜底
  let event: string | null = null

  const handleToken = (data: string): void => {
    // [DONE] 与 token 共用 event: token 块，读到 DONE 不再发 token 回调
    if (data === DONE_MARKER) {
      handlers.onDone?.()
    } else {
      handlers.onToken?.(unescapeLineBreaks(data))
    }
  }

  const dispatch = (line: string): void => {
    const data = dataPayload(line)
    const type = event
    event = null
    if (type === EVENT_META) {
      const meta = parseJson<SseMeta>(data)
      if (meta) handlers.onMeta?.(meta)
    } else if (type === EVENT_ERROR) {
      handlers.onError?.(data)
    } else if (type === EVENT_PROGRESS) {
      // 进度载荷为 {"stage":..,"detail":..}；非 JSON 时降级为原文 detail
      const progress = parseJson<ProgressPayload>(data)
      handlers.onProgress?.(progress ?? { stage: 'progress', detail: data })
    } else if (type === EVENT_TOKEN || type === null) {
      handleToken(data)
    }
    // 未知事件忽略（向前兼容）
  }

  const handleLine = (line: string): void => {
    if (line.trim() === '') return
    if (line.startsWith('event:')) {
      event = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      dispatch(line)
    }
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let idx = buffer.indexOf('\n')
      while (idx >= 0) {
        handleLine(buffer.slice(0, idx))
        buffer = buffer.slice(idx + 1)
        idx = buffer.indexOf('\n')
      }
    }
    const rest = buffer + decoder.decode()
    if (rest !== '') handleLine(rest)
  } catch (e) {
    if (isAbort(e)) return
    throw e
  }
}

/** 主动取消判定（单点导出）：useChat 收尾共用，避免双处 instanceof 判断漂移 */
export function isAbort(e: unknown): boolean {
  return e instanceof DOMException && e.name === 'AbortError'
}
