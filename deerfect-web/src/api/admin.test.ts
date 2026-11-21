import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createLlmRoute,
  listAdminAgents,
  listLlmRoutes,
  updateAdminAgent,
  updateLlmRoute,
} from './admin'
import type { AdminAgentUpsert, LlmRouteUpsert } from './admin'

function response(body: unknown, status = 200) {
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
})

const routeBody: LlmRouteUpsert = {
  code: 'main',
  description: '主对话模型',
  strategy: 'priority',
  enabled: 1,
  members: [{ modelId: 12, priority: 10, enabled: 1 }],
}

const agentBody: AdminAgentUpsert = {
  description: '通用 AI 助手（默认）',
  routeId: 1,
  tools: 'web, memory',
  knowledge: null,
  thinking: 1,
  status: 1,
  isInternal: 0,
}

describe('路由与 Agent 管理端点', () => {
  it('路由列表/新增/更新路径与方法', async () => {
    fetchMock.mockResolvedValue(response([]))
    await listLlmRoutes()
    expect(fetchMock.mock.calls.at(-1)?.[0]).toBe('/api/admin/routes')

    fetchMock.mockResolvedValue(response({ id: 1 }))
    await createLlmRoute(routeBody)
    expect(fetchMock.mock.calls.at(-1)).toEqual([
      '/api/admin/routes',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(routeBody),
      }),
    ])

    await updateLlmRoute(7, routeBody)
    expect(fetchMock.mock.calls.at(-1)).toEqual([
      '/api/admin/routes/7',
      expect.objectContaining({
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(routeBody),
      }),
    ])
  })

  it('Agent 列表/配置更新路径与方法', async () => {
    fetchMock.mockResolvedValue(response([]))
    await listAdminAgents()
    expect(fetchMock.mock.calls.at(-1)?.[0]).toBe('/api/admin/agents')

    fetchMock.mockResolvedValue(response({ id: 3 }))
    await updateAdminAgent(3, agentBody)
    expect(fetchMock.mock.calls.at(-1)).toEqual([
      '/api/admin/agents/3',
      expect.objectContaining({
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(agentBody),
      }),
    ])
  })
})
