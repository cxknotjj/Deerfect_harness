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
  const resp = await fetch(path, { ...init, headers: baseHeaders() })
  if (!resp.ok) {
    if (resp.status === 401) notifyUnauthorized()
    throw new Error(`HTTP ${resp.status}: ${await resp.text()}`)
  }
  return (await resp.json()) as T
}

/** 无响应体请求（HTTP 方法由调用方指定）：仅校验 2xx，不解析 JSON；401 触发统一登录钩子 */
export async function requestVoid(path: string, method: string): Promise<void> {
  const resp = await fetch(path, { method, headers: baseHeaders() })
  if (!resp.ok) {
    if (resp.status === 401) notifyUnauthorized()
    throw new Error(`HTTP ${resp.status}: ${await resp.text()}`)
  }
}
