/**
 * 真实 HTTP API 实现(原生 fetch,无第三方运行时依赖)。
 * 端点与协议以 server 实际实现为准:
 * - REST:GET /api/harness/agents、GET|POST /api/harness/sessions、POST /api/harness/sessions/{id}/agent
 * - 流式:POST /api/chat/stream,chunked 文本,每帧两行 `event: <名>` + `data: <载荷>`(无空行)
 * 鉴权:server 未启用强制 API-Key 过滤(仓库中的 api-key 均为模型供应商凭据),
 * 此处仅预留可选注入:配置 VITE_API_KEY 时附带 X-API-Key 头,未配置则不带。
 */
import type {
  AgentListView,
  ChatRequest,
  ProgressPayload,
  SessionAgentView,
  SessionCreatedView,
  SessionPage,
  SseMeta,
} from './types'

/** SSE 事件名与结束标记(与 shared 模块 SseProtocol 常量字面量一致) */
const EVENT_META = 'meta'
const EVENT_ERROR = 'error'
const EVENT_PROGRESS = 'progress'
const EVENT_TOKEN = 'token'
const DONE_MARKER = '[DONE]'

/** 公共请求头;VITE_API_KEY 未配置时不注入鉴权头 */
function baseHeaders(): Record<string, string> {
  const headers: Record<string, string> = {}
  const key: string | undefined = import.meta.env.VITE_API_KEY
  if (key) headers['X-API-Key'] = key
  return headers
}

/** 通用 REST 请求:JSON 解析,非 2xx 抛错(消息含状态码与响应体) */
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const resp = await fetch(path, { ...init, headers: baseHeaders() })
  if (!resp.ok) {
    throw new Error(`HTTP ${resp.status}: ${await resp.text()}`)
  }
  return (await resp.json()) as T
}

/** Agent 列表(GET /api/harness/agents) */
export function listAgents(): Promise<AgentListView> {
  return request('/api/harness/agents')
}

/** 会话分页(GET /api/harness/sessions?page=&size=) */
export function listSessions(page = 1, size = 10): Promise<SessionPage> {
  return request(`/api/harness/sessions?page=${page}&size=${size}`)
}

/** 新建会话(POST /api/harness/sessions?name=) */
export function createSession(name = '新会话'): Promise<SessionCreatedView> {
  return request(`/api/harness/sessions?name=${encodeURIComponent(name)}`, { method: 'POST' })
}

/** 会话内切换 Agent(POST /api/harness/sessions/{id}/agent?agentId=) */
export function bindAgent(sessionId: string, agentId: number): Promise<SessionAgentView> {
  return request(`/api/harness/sessions/${encodeURIComponent(sessionId)}/agent?agentId=${agentId}`, {
    method: 'POST',
  })
}

/** 流式聊天回调集(全部可选,按需订阅) */
export interface StreamHandlers {
  /** 流末元数据(sessionId/goalId/status/sources) */
  onMeta?: (meta: SseMeta) => void
  /** 执行进度(多 Agent 编排阶段反馈,{stage,detail}) */
  onProgress?: (progress: ProgressPayload) => void
  /** 内容 token(换行已解码还原) */
  onToken?: (token: string) => void
  /** 服务端 error 事件(流内业务错误) */
  onError?: (message: string) => void
  /** token 流结束(读到 [DONE]) */
  onDone?: () => void
}

function parseJson<T>(raw: string): T | null {
  try {
    return JSON.parse(raw) as T
  } catch {
    return null
  }
}

/**
 * 还原 SseProtocol.escapeLineBreaks 的转义(接收侧):
 * 字面量 \n → 换行、\r → 回车、\\ → 反斜杠;非法序列原样保留。
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

/** 剥掉 "data:" 前缀与其后至多一个空格(SSE 规范),保留载荷原文(缩进空格不丢) */
function dataPayload(line: string): string {
  const rest = line.slice('data:'.length)
  return rest.startsWith(' ') ? rest.slice(1) : rest
}

/**
 * 流式聊天(POST /api/chat/stream):body.getReader() + TextDecoder 逐行解析 SSE 帧。
 * - 每个 data: 行即分发(与 CLI readSse 一致;服务端每元素自带 event+data,无空行,空行仅忽略)
 * - event 字段分发后复位:历史上 token 块可能缺 event: 行,无 event: 的 data 一律按 token 兜底
 * - token 载荷做换行转义解码;读到 [DONE] 调 onDone;meta 载荷为 JSON
 * - 传入 AbortSignal 可取消;主动取消静默结束(不视为错误),其余读取异常向上抛
 */
export async function streamChat(
  req: ChatRequest,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  let resp: Response
  try {
    resp = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { ...baseHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
      signal,
    })
  } catch (e) {
    if (isAbort(e)) return
    throw e
  }
  if (!resp.ok || !resp.body) {
    throw new Error(`HTTP ${resp.status}: ${await resp.text()}`)
  }

  // 当前帧事件名;在 data 行分发后复位,保证「无 event: 的 data」落入 token 兜底
  let event: string | null = null

  const handleToken = (data: string): void => {
    // [DONE] 与 token 共用 event: token 块,读到 DONE 不再发 token 回调
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
      // 进度载荷为 {"stage":..,"detail":..};非 JSON 时降级为原文 detail
      const progress = parseJson<ProgressPayload>(data)
      handlers.onProgress?.(progress ?? { stage: 'progress', detail: data })
    } else if (type === EVENT_TOKEN || type === null) {
      handleToken(data)
    }
    // 未知事件忽略(向前兼容)
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

function isAbort(e: unknown): boolean {
  return e instanceof DOMException && e.name === 'AbortError'
}

/** 统一 API 面:真实现与 mock 实现同签名,由 index.ts 按 VITE_USE_MOCK 二选一导出 */
export interface Api {
  listAgents(): Promise<AgentListView>
  listSessions(page?: number, size?: number): Promise<SessionPage>
  createSession(name?: string): Promise<SessionCreatedView>
  bindAgent(sessionId: string, agentId: number): Promise<SessionAgentView>
  streamChat(req: ChatRequest, handlers: StreamHandlers, signal?: AbortSignal): Promise<void>
}

/** 真实 HTTP 实现 */
export const realApi: Api = { listAgents, listSessions, createSession, bindAgent, streamChat }
