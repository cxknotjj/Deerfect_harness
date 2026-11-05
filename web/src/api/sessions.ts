/**
 * 会话与 Agent 端点：Agent 列表、会话分页/创建/删除、会话内 Agent 绑定、历史消息。
 */
import type {
  AgentListView,
  SessionAgentView,
  SessionCreatedView,
  SessionMessagesView,
  SessionPage,
} from './types'
import { request, requestVoid } from './http'

/** Agent 列表（GET /api/harness/agents） */
export function listAgents(): Promise<AgentListView> {
  return request('/api/harness/agents')
}

/** 会话分页（GET /api/harness/sessions?page=&size=） */
export function listSessions(page = 1, size = 10): Promise<SessionPage> {
  return request(`/api/harness/sessions?page=${page}&size=${size}`)
}

/** 新建会话（POST /api/harness/sessions?name=） */
export function createSession(name = '新会话'): Promise<SessionCreatedView> {
  return request(`/api/harness/sessions?name=${encodeURIComponent(name)}`, { method: 'POST' })
}

/** 会话内切换 Agent（POST /api/harness/sessions/{id}/agent?agentId=） */
export function bindAgent(sessionId: string, agentId: number): Promise<SessionAgentView> {
  return request(`/api/harness/sessions/${encodeURIComponent(sessionId)}/agent?agentId=${agentId}`, {
    method: 'POST',
  })
}

/** 会话历史消息（GET /api/harness/sessions/{id}/messages；无历史返回空数组） */
export function listMessages(sessionId: string): Promise<SessionMessagesView> {
  return request(`/api/harness/sessions/${encodeURIComponent(sessionId)}/messages`)
}

/** 删除会话（DELETE /api/harness/sessions/{id}；服务端幂等，成功返回空体） */
export function deleteSession(sessionId: string): Promise<void> {
  return requestVoid(`/api/harness/sessions/${encodeURIComponent(sessionId)}`, 'DELETE')
}
