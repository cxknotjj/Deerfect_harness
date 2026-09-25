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
  LlmCallItem,
  ProgressPayload,
  SessionAgentView,
  SessionCreatedView,
  SessionMessagesView,
  SessionPage,
  SseMeta,
  ToolCallItem,
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

/**
 * 登录态:凭据不进前端代码——浏览器经 /api/auth/login 以口令换 HttpOnly Cookie(harness_login),
 * 同源请求自动携带,此处零凭据接线。401 由 setUnauthorizedHandler 注入的钩子统一接管(切登录视图)。
 */
let onUnauthorized: (() => void) | null = null

/** 注入 401 统一钩子(App 挂载时调用;传 null 解除) */
export function setUnauthorizedHandler(fn: (() => void) | null): void {
  onUnauthorized = fn
}

function notifyUnauthorized(): void {
  onUnauthorized?.()
}

/** 通用 REST 请求:JSON 解析,非 2xx 抛错(消息含状态码与响应体);401 触发统一登录钩子 */
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const resp = await fetch(path, { ...init, headers: baseHeaders() })
  if (!resp.ok) {
    if (resp.status === 401) notifyUnauthorized()
    throw new Error(`HTTP ${resp.status}: ${await resp.text()}`)
  }
  return (await resp.json()) as T
}

/** 登录态探测(GET /api/auth/me):enabled=服务端是否启用登录通道,authenticated=当前浏览器是否已登录 */
export interface AuthStateView {
  enabled: boolean
  authenticated: boolean
}

export function authState(): Promise<AuthStateView> {
  return request('/api/auth/me')
}

/**
 * 登录(POST /api/auth/login):成功后浏览器持有 HttpOnly Cookie。
 * 独立 fetch 而非 request():登录自身的 401(口令错误)不得触发全局未登录钩子。
 */
export async function login(password: string): Promise<void> {
  const resp = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  })
  if (resp.ok) return
  if (resp.status === 429) throw new Error('失败次数过多，请稍后再试')
  if (resp.status === 403) throw new Error('服务端未启用登录')
  throw new Error('口令错误')
}

/** 登出(POST /api/auth/logout):服务端吊销登录态 + 清除 Cookie;幂等 */
export function logout(): Promise<void> {
  return requestVoid('/api/auth/logout')
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

/** 会话历史消息(GET /api/harness/sessions/{id}/messages;无历史返回空数组) */
export function listMessages(sessionId: string): Promise<SessionMessagesView> {
  return request(`/api/harness/sessions/${encodeURIComponent(sessionId)}/messages`)
}

/** 无响应体请求(DELETE):仅校验 2xx,不解析 JSON;401 触发统一登录钩子 */
async function requestVoid(path: string): Promise<void> {
  const resp = await fetch(path, { method: 'DELETE', headers: baseHeaders() })
  if (!resp.ok) {
    if (resp.status === 401) notifyUnauthorized()
    throw new Error(`HTTP ${resp.status}: ${await resp.text()}`)
  }
}

/** 删除会话(DELETE /api/harness/sessions/{id};服务端幂等,成功返回空体) */
export function deleteSession(sessionId: string): Promise<void> {
  return requestVoid(`/api/harness/sessions/${encodeURIComponent(sessionId)}`)
}

/** 工具调用观测(GET /api/tool-calls?sessionId=;按 id 倒序,上限 200) */
export function listToolCalls(sessionId: string, limit = 200): Promise<ToolCallItem[]> {
  return request(`/api/tool-calls?sessionId=${encodeURIComponent(sessionId)}&limit=${limit}`)
}

/** LLM 调用观测(GET /api/llm-calls?sessionId=;按 id 倒序,上限 200) */
export function listLlmCalls(sessionId: string, limit = 200): Promise<LlmCallItem[]> {
  return request(`/api/llm-calls?sessionId=${encodeURIComponent(sessionId)}&limit=${limit}`)
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

/** 剥掉 "data:" 前缀与其后至多一个空格(SSE 规范),保留载荷原文(缩进空格不丢);
 *  尾部 \r 去除(服务端若发 \r\n 行尾,split('\n') 后载荷尾残留 \r 会污染 JSON/token) */
function dataPayload(line: string): string {
  const rest = line.slice('data:'.length)
  const noSpace = rest.startsWith(' ') ? rest.slice(1) : rest
  return noSpace.endsWith('\r') ? noSpace.slice(0, -1) : noSpace
}

/**
 * 流式聊天(POST /api/chat/stream):body.getReader() + TextDecoder 逐行解析 SSE 帧。
 * - 每个 data: 行即分发(与 CLI readSse 一致;服务端每元素自带 event+data,无空行,空行仅忽略)
 * - event 字段分发后复位:历史上 token 块可能缺 event: 行,无 event: 的 data 一律按 token 兜底
 * - token 载荷做换行转义解码;读到 [DONE] 调 onDone;meta 载荷为 JSON
 * - 传入 AbortSignal 可取消;主动取消静默结束(不视为错误),其余读取异常向上抛
 * - 发起失败(连接建立瞬间的 keep-alive 竞态/网络瞬断)自动重试一次:仅兜「尚未拿到响应头」
 *   的失败,流建立后的中断不在此列(部分输出已上屏,重发与否交用户手动重试);abort 不重试
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
    // 发起失败自动重试一次(短暂退避,给链路层竞态自愈窗口);期间点停止则尊重取消
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

/** 主动取消判定(单点导出):useChat 收尾共用,避免双处 instanceof 判断漂移 */
export function isAbort(e: unknown): boolean {
  return e instanceof DOMException && e.name === 'AbortError'
}

/** 统一 API 面:真实现与 mock 实现同签名,由 index.ts 按 VITE_USE_MOCK 二选一导出 */
export interface Api {
  listAgents(): Promise<AgentListView>
  listSessions(page?: number, size?: number): Promise<SessionPage>
  createSession(name?: string): Promise<SessionCreatedView>
  bindAgent(sessionId: string, agentId: number): Promise<SessionAgentView>
  listMessages(sessionId: string): Promise<SessionMessagesView>
  deleteSession(sessionId: string): Promise<void>
  listToolCalls(sessionId: string): Promise<ToolCallItem[]>
  listLlmCalls(sessionId: string): Promise<LlmCallItem[]>
  streamChat(req: ChatRequest, handlers: StreamHandlers, signal?: AbortSignal): Promise<void>
  authState(): Promise<AuthStateView>
  login(password: string): Promise<void>
  logout(): Promise<void>
}

/** 真实 HTTP 实现 */
export const realApi: Api = {
  listAgents,
  listSessions,
  createSession,
  bindAgent,
  listMessages,
  deleteSession,
  listToolCalls,
  listLlmCalls,
  streamChat,
  authState,
  login,
  logout,
}
