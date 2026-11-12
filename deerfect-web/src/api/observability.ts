/**
 * 观测端点：工具/LLM 调用台账、RAG 检索台账、运行监控与会话上下文构成。
 */
import type {
  KbRetrievalItem,
  LlmCallItem,
  MonitorContextComposition,
  MonitorSnapshot,
  ToolCallItem,
} from './types'
import { baseHeaders, notifyUnauthorized, request } from './http'

/** 工具调用观测（GET /api/tool-calls?sessionId=；按 id 倒序，上限 200） */
export function listToolCalls(sessionId: string, limit = 200): Promise<ToolCallItem[]> {
  return request(`/api/tool-calls?sessionId=${encodeURIComponent(sessionId)}&limit=${limit}`)
}

/** LLM 调用观测（GET /api/llm-calls?sessionId=；按 id 倒序，上限 200） */
export function listLlmCalls(sessionId: string, limit = 200): Promise<LlmCallItem[]> {
  return request(`/api/llm-calls?sessionId=${encodeURIComponent(sessionId)}&limit=${limit}`)
}

/** RAG 检索观测（GET /api/kb-retrievals?sessionId=；按 id 倒序，上限 200） */
export function listKbRetrievals(sessionId: string, limit = 200): Promise<KbRetrievalItem[]> {
  return request(`/api/kb-retrievals?sessionId=${encodeURIComponent(sessionId)}&limit=${limit}`)
}

/** 运行监控（GET /api/monitor?hours=&agent=&channel=&limit=；agent/channel 省略 = 不过滤） */
export function monitor(hours = 24, agent = '', channel = '', limit = 50): Promise<MonitorSnapshot> {
  const params = new URLSearchParams({ hours: String(hours), limit: String(limit) })
  if (agent) params.set('agent', agent)
  if (channel) params.set('channel', channel)
  return request(`/api/monitor?${params.toString()}`)
}

/**
 * 会话上下文构成（GET /api/monitor/context?sessionId=）：
 * 命中返回快照；无快照（未发消息/过期/淘汰/重启）返回 404 → 返回 null 供页面展示空态。
 */
export async function monitorContext(sessionId: string): Promise<MonitorContextComposition | null> {
  const resp = await fetch(`/api/monitor/context?sessionId=${encodeURIComponent(sessionId)}`, {
    headers: baseHeaders(),
  })
  if (resp.status === 404) return null
  if (!resp.ok) {
    if (resp.status === 401) notifyUnauthorized()
    throw new Error(`HTTP ${resp.status}: ${await resp.text()}`)
  }
  return (await resp.json()) as MonitorContextComposition
}
