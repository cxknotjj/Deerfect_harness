/**
 * 登录通道：登录态探测、口令换 Cookie、登出。
 */
import { fetchWithTimeout, request, requestVoid } from './http'

/** 登录态探测（GET /api/auth/me）：enabled=服务端是否启用登录通道，authenticated=当前浏览器是否已登录 */
export interface AuthStateView {
  enabled: boolean
  authenticated: boolean
}

export function authState(): Promise<AuthStateView> {
  return request('/api/auth/me')
}

/**
 * 登录（POST /api/auth/login）：成功后浏览器持有 HttpOnly Cookie。
 *
 * <p>不走 request()：登录自身的 401（口令错误）不得触发全局未登录钩子。
 * 但仍走 fetchWithTimeout——后端挂起时登录按钮不能永远转圈。
 */
export async function login(password: string): Promise<void> {
  const resp = await fetchWithTimeout('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  })
  if (resp.ok) return
  if (resp.status === 429) throw new Error('失败次数过多，请稍后再试')
  if (resp.status === 403) throw new Error('服务端未启用登录')
  throw new Error('口令错误')
}

/** 登出（POST /api/auth/logout）：服务端吊销登录态 + 清除 Cookie；幂等 */
export function logout(): Promise<void> {
  return requestVoid('/api/auth/logout', 'POST')
}
