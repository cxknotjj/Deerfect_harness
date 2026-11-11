import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { baseHeaders, request, requestVoid, setUnauthorizedHandler } from './http'
import { authState, login, logout } from './auth'
import { bindAgent, createSession, deleteSession, listAgents, listMessages, listSessions } from './sessions'
import { listKbRetrievals, listLlmCalls, listToolCalls, monitor, monitorContext } from './observability'

/** 最小 Response 替身（只实现被测代码用到的字段） */
function response(body: unknown, status = 200): {
  ok: boolean
  status: number
  json: () => Promise<unknown>
  text: () => Promise<string>
} {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    text: async () => (typeof body === 'string' ? body : JSON.stringify(body)),
  }
}

const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
  setUnauthorizedHandler(null)
})

describe('http 基础设施', () => {
  it('request 解析 JSON 并携带公共头', async () => {
    fetchMock.mockResolvedValue(response({ ok: true }))
    await expect(request<{ ok: boolean }>('/api/x')).resolves.toEqual({ ok: true })
    expect(fetchMock).toHaveBeenCalledWith('/api/x', expect.objectContaining({ headers: {}, signal: expect.any(AbortSignal) }))
  })

  it('VITE_API_KEY 已配置时注入 X-API-Token', () => {
    vi.stubEnv('VITE_API_KEY', 'secret')
    expect(baseHeaders()).toEqual({ 'X-API-Token': 'secret' })
  })

  it('401 触发统一登录钩子并抛出状态码', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    fetchMock.mockResolvedValue(response('unauthorized', 401))

    await expect(request('/api/x')).rejects.toThrow('HTTP 401: unauthorized')
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('requestVoid 只校验 2xx，不解析 JSON', async () => {
    fetchMock.mockResolvedValue(response('', 204))
    await expect(requestVoid('/api/x', 'DELETE')).resolves.toBeUndefined()
    expect(fetchMock).toHaveBeenCalledWith('/api/x', expect.objectContaining({ method: 'DELETE', headers: {}, signal: expect.any(AbortSignal) }))
  })

  it('requestVoid 非 2xx 抛错', async () => {
    fetchMock.mockResolvedValue(response('boom', 500))
    await expect(requestVoid('/api/x', 'POST')).rejects.toThrow('HTTP 500: boom')
  })
})

describe('auth 端点', () => {
  it('authState 走 /api/auth/me', async () => {
    fetchMock.mockResolvedValue(response({ enabled: true, authenticated: false }))
    await expect(authState()).resolves.toEqual({ enabled: true, authenticated: false })
    expect(fetchMock.mock.calls[0][0]).toBe('/api/auth/me')
  })

  it('login 发送口令 JSON 并在 2xx 时静默成功', async () => {
    fetchMock.mockResolvedValue(response('', 204))
    await expect(login('口令')).resolves.toBeUndefined()
    expect(fetchMock).toHaveBeenCalledWith('/api/auth/login', expect.objectContaining({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: '口令' }),
      // 普通请求也带超时信号（此前是裸 fetch，后端挂起会无限等待）
      signal: expect.any(AbortSignal),
    }))
  })

  it('login 按状态码给出可读错误', async () => {
    fetchMock.mockResolvedValueOnce(response('', 429))
    await expect(login('x')).rejects.toThrow('失败次数过多，请稍后再试')
    fetchMock.mockResolvedValueOnce(response('', 403))
    await expect(login('x')).rejects.toThrow('服务端未启用登录')
    fetchMock.mockResolvedValueOnce(response('', 401))
    await expect(login('x')).rejects.toThrow('口令错误')
  })

  it('logout 走 POST /api/auth/logout', async () => {
    fetchMock.mockResolvedValue(response('', 204))
    await logout()
    expect(fetchMock).toHaveBeenCalledWith('/api/auth/logout', expect.objectContaining({ method: 'POST', headers: {}, signal: expect.any(AbortSignal) }))
  })
})

describe('sessions 端点', () => {
  it('列表/分页/新建/绑定/历史/删除路径与方法', async () => {
    fetchMock.mockResolvedValue(response({}))

    await listAgents()
    expect(fetchMock.mock.calls.at(-1)?.[0]).toBe('/api/harness/agents')

    await listSessions(2, 20)
    expect(fetchMock.mock.calls.at(-1)?.[0]).toBe('/api/harness/sessions?page=2&size=20')

    await createSession('会话 名')
    expect(fetchMock.mock.calls.at(-1)).toEqual([
      `/api/harness/sessions?name=${encodeURIComponent('会话 名')}`,
      expect.objectContaining({ method: 'POST', headers: {}, signal: expect.any(AbortSignal) }),
    ])

    await bindAgent('s/1', 7)
    expect(fetchMock.mock.calls.at(-1)).toEqual([
      '/api/harness/sessions/s%2F1/agent?agentId=7',
      expect.objectContaining({ method: 'POST', headers: {}, signal: expect.any(AbortSignal) }),
    ])

    await listMessages('s 1')
    expect(fetchMock.mock.calls.at(-1)?.[0]).toBe('/api/harness/sessions/s%201/messages')

    await deleteSession('s 1')
    expect(fetchMock.mock.calls.at(-1)).toEqual([
      '/api/harness/sessions/s%201',
      expect.objectContaining({ method: 'DELETE', headers: {}, signal: expect.any(AbortSignal) }),
    ])
  })
})

describe('observability 端点', () => {
  it('三张观测台账按 sessionId 编码并带 limit', async () => {
    fetchMock.mockResolvedValue(response([]))

    await listToolCalls('s 1')
    expect(fetchMock.mock.calls.at(-1)?.[0]).toBe('/api/tool-calls?sessionId=s%201&limit=200')

    await listLlmCalls('s/1', 10)
    expect(fetchMock.mock.calls.at(-1)?.[0]).toBe('/api/llm-calls?sessionId=s%2F1&limit=10')

    await listKbRetrievals('s1')
    expect(fetchMock.mock.calls.at(-1)?.[0]).toBe('/api/kb-retrievals?sessionId=s1&limit=200')
  })

  it('monitor 默认 24h/50 条，过滤条件为空时省略', async () => {
    fetchMock.mockResolvedValue(response({}))

    await monitor()
    expect(fetchMock.mock.calls.at(-1)?.[0]).toBe('/api/monitor?hours=24&limit=50')

    await monitor(1, 'general', 'qq', 10)
    expect(fetchMock.mock.calls.at(-1)?.[0]).toBe('/api/monitor?hours=1&limit=10&agent=general&channel=qq')
  })

  it('monitorContext 无快照返回 null，其余非 2xx 抛错', async () => {
    fetchMock.mockResolvedValueOnce(response('', 404))
    await expect(monitorContext('s1')).resolves.toBeNull()

    fetchMock.mockResolvedValueOnce(response('boom', 500))
    await expect(monitorContext('s1')).rejects.toThrow('HTTP 500: boom')
  })

  it('monitorContext 命中返回快照 JSON', async () => {
    fetchMock.mockResolvedValueOnce(response({ segments: [] }))
    await expect(monitorContext('s1')).resolves.toEqual({ segments: [] })
    expect(fetchMock.mock.calls.at(-1)?.[0]).toBe('/api/monitor/context?sessionId=s1')
  })
})
