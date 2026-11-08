import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { request, requestVoid, setUnauthorizedHandler } from './http'

/** 造一个响应对象：够 http.ts 用（ok / status / json / text） */
function response(status: number, body: unknown = {}): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response
}

describe('http 请求超时', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    setUnauthorizedHandler(null)
  })

  it('后端挂起时按超时中断，并抛出可读错误（而不是无限等待）', async () => {
    // 永不 resolve 的 fetch，只在收到 abort 时 reject —— 模拟「连接建立了但后端不响应」
    const fetchMock = vi.fn((_path: string, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => {
        reject(new DOMException('Aborted', 'AbortError'))
      })
    }))
    vi.stubGlobal('fetch', fetchMock)

    const pending = request('/api/harness/sessions')
    const assertion = expect(pending).rejects.toThrow('请求超时（30s）：/api/harness/sessions')
    await vi.advanceTimersByTimeAsync(30_000)
    await assertion
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('正常响应不受超时影响', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response(200, { items: [] })))

    const result = await request<{ items: unknown[] }>('/api/harness/sessions')

    expect(result).toEqual({ items: [] })
  })

  it('超时可被 VITE_API_TIMEOUT_MS 覆盖', async () => {
    vi.stubEnv('VITE_API_TIMEOUT_MS', '1000')
    const fetchMock = vi.fn((_path: string, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => {
        reject(new DOMException('Aborted', 'AbortError'))
      })
    }))
    vi.stubGlobal('fetch', fetchMock)

    const pending = requestVoid('/api/memories/1', 'DELETE')
    const assertion = expect(pending).rejects.toThrow('请求超时（1s）')
    await vi.advanceTimersByTimeAsync(1_000)
    await assertion
    vi.unstubAllEnvs()
  })

  it('非超时的网络错误原样抛出，不误报为超时', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    }))

    await expect(request('/api/x')).rejects.toThrow('Failed to fetch')
  })

  it('401 仍触发统一登录钩子', async () => {
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    vi.stubGlobal('fetch', vi.fn(async () => response(401, { code: 401 })))

    await expect(request('/api/harness/sessions')).rejects.toThrow('HTTP 401')
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })
})
