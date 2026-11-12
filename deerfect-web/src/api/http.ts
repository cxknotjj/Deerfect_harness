/**
 * HTTP 基础设施：公共请求头、401 统一钩子、JSON/无体请求封装。
 * 端点实现按领域拆分到 auth / sessions / observability / chatStream，client.ts 只做聚合。
 */

/** 公共请求头；VITE_API_KEY 未配置时不注入鉴权头 */
export function baseHeaders(): Record<string, string> {
  const headers: Record<string, string> = {}
  const key: string | undefined = import.meta.env.VITE_API_KEY
  if (key) headers['X-API-Token'] = key
  return headers
}

/**
 * 请求超时（毫秒）：默认 30s，可用 VITE_API_TIMEOUT_MS 覆盖。
 *
 * <p>为什么必须有：后端挂起时（数据库锁等待、上游 LLM 卡住），没有超时的 fetch 会一直挂着——
 * 用户既看不到 loading 结束也看不到错误，只能刷新页面。SSE 链路本来就有帧间超时，
 * 但普通 REST 之前是裸 fetch。
 */
const DEFAULT_TIMEOUT_MS = 30_000

function timeoutMs(): number {
  const raw = Number(import.meta.env.VITE_API_TIMEOUT_MS)
  return Number.isFinite(raw) && raw > 0 ? raw : DEFAULT_TIMEOUT_MS
}

/**
 * 合并公共头与调用方头：调用方优先。
 *
 * <p>必须合并而不是覆盖——login 要带 Content-Type: application/json，
 * 直接 `headers: baseHeaders()` 会把它吞掉，后端 @RequestBody 解析失败。
 */
function mergeHeaders(init?: RequestInit): Record<string, string> {
  const merged: Record<string, string> = { ...baseHeaders() }
  const extra = init?.headers
  if (!extra) {
    return merged
  }
  if (typeof Headers !== 'undefined' && extra instanceof Headers) {
    extra.forEach((value, key) => {
      merged[key] = value
    })
    return merged
  }
  return { ...merged, ...(extra as Record<string, string>) }
}

/**
 * 带超时的 fetch：超时中断并把 AbortError 翻译成可读错误。
 *
 * <p>注意：调用方目前都不传 signal；若将来要支持「用户主动取消」，需要在这里合并
 * 两个 signal（AbortSignal.any），而不是直接覆盖。
 */
export async function fetchWithTimeout(path: string, init?: RequestInit): Promise<Response> {
  const controller = new AbortController()
  const ms = timeoutMs()
  const timer = setTimeout(() => controller.abort(), ms)
  try {
    return await fetch(path, { ...init, headers: mergeHeaders(init), signal: controller.signal })
  } catch (err) {
    // 只有「我们自己的 timer 触发」才算超时；其它中断原因原样抛出，避免误报。
    // cause 保留原始 AbortError，否则排查时只能看到一句「超时」，不知道是谁中断的
    if (controller.signal.aborted) {
      throw new Error(`请求超时（${Math.round(ms / 1000)}s）：${path}`, { cause: err })
    }
    throw err
  } finally {
    clearTimeout(timer)
  }
}

/**
 * 登录态：凭据不进前端代码——浏览器经 /api/auth/login 以口令换 HttpOnly Cookie（harness_login），
 * 同源请求自动携带，此处零凭据接线。401 由 setUnauthorizedHandler 注入的钩子统一接管（切登录视图）。
 */
let onUnauthorized: (() => void) | null = null

/** 注入 401 统一钩子（App 挂载时调用；传 null 解除） */
export function setUnauthorizedHandler(fn: (() => void) | null): void {
  onUnauthorized = fn
}

/** 通知 401 钩子（模块内部使用） */
export function notifyUnauthorized(): void {
  onUnauthorized?.()
}

/** 通用 REST 请求：JSON 解析，非 2xx 抛错（消息含状态码与响应体）；401 触发统一登录钩子 */
export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const resp = await fetchWithTimeout(path, init)
  if (!resp.ok) {
    if (resp.status === 401) notifyUnauthorized()
    throw new Error(`HTTP ${resp.status}: ${await resp.text()}`)
  }
  return (await resp.json()) as T
}

/** 无响应体请求（HTTP 方法由调用方指定）：仅校验 2xx，不解析 JSON；401 触发统一登录钩子 */
export async function requestVoid(path: string, method: string): Promise<void> {
  const resp = await fetchWithTimeout(path, { method })
  if (!resp.ok) {
    if (resp.status === 401) notifyUnauthorized()
    throw new Error(`HTTP ${resp.status}: ${await resp.text()}`)
  }
}
