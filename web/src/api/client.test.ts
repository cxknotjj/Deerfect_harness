import { afterEach, describe, expect, it, vi } from 'vitest'
import { login, realApi, setUnauthorizedHandler } from './client'
import type { SessionPage } from './types'

/** 最小 Response 桩:仅本测试用到的 ok/status/json/text */
function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status < 400,
    status,
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response
}

const emptyPage: SessionPage = { page: 1, size: 10, total: 0, pages: 0, sessions: [] }

describe('client 鉴权接线', () => {
  afterEach(() => {
    setUnauthorizedHandler(null)
    vi.unstubAllGlobals()
  })

  it('REST 401 触发统一未登录钩子并抛错', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(401, { code: 401 })))
    await expect(realApi.listSessions()).rejects.toThrow('HTTP 401')
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('业务 2xx 不触发钩子', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, emptyPage)))
    await realApi.listSessions()
    expect(handler).not.toHaveBeenCalled()
  })

  it('流式发起 401 同样触发钩子', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(401, { code: 401 })))
    await expect(realApi.streamChat({ message: 'hi' }, {})).rejects.toThrow('HTTP 401')
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('login 自身 401(口令错误)映射错误文案,不触发未登录钩子', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(401, { code: 401 })))
    await expect(login('bad')).rejects.toThrow('口令错误')
    expect(handler).not.toHaveBeenCalled()
  })

  it('login 429/403 分别映射限流与未启用文案', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(429, {})))
    await expect(login('x')).rejects.toThrow('失败次数过多')
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(403, {})))
    await expect(login('x')).rejects.toThrow('服务端未启用登录')
  })

  it('登录成功静默返回', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, { code: 0 })))
    await expect(login('right')).resolves.toBeUndefined()
  })
})
