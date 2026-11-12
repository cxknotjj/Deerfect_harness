/**
 * API 聚合出口：按领域拆分的实现模块在此汇总，保持既有 `realApi` / 类型导出不变。
 * - http.ts：请求头、401 钩子、JSON/无体请求
 * - auth.ts：登录态、登录、登出
 * - sessions.ts：Agent 与会话端点
 * - observability.ts：工具/LLM/RAG 台账与监控
 * - chatStream.ts：SSE 流式聊天
 */
import type { ChatRequest, KbRetrievalItem, LlmCallItem, MonitorContextComposition, MonitorSnapshot, ToolCallItem } from './types'
import type { AgentListView, SessionAgentView, SessionCreatedView, SessionMessagesView, SessionPage } from './types'
import { authState, login, logout } from './auth'
import type { AuthStateView } from './auth'
import { isAbort, streamChat } from './chatStream'
import type { StreamHandlers } from './chatStream'
import { notifyUnauthorized, setUnauthorizedHandler } from './http'
import {
  bindAgent,
  createSession,
  deleteSession,
  listAgents,
  listMessages,
  listSessions,
} from './sessions'
import {
  listKbRetrievals,
  listLlmCalls,
  listToolCalls,
  monitor,
  monitorContext,
} from './observability'

/** 统一 API 面：真实现与 mock 实现同签名，由 index.ts 按 VITE_USE_MOCK 二选一导出 */
export interface Api {
  listAgents(): Promise<AgentListView>
  listSessions(page?: number, size?: number): Promise<SessionPage>
  createSession(name?: string): Promise<SessionCreatedView>
  bindAgent(sessionId: string, agentId: number): Promise<SessionAgentView>
  listMessages(sessionId: string): Promise<SessionMessagesView>
  deleteSession(sessionId: string): Promise<void>
  listToolCalls(sessionId: string): Promise<ToolCallItem[]>
  listLlmCalls(sessionId: string): Promise<LlmCallItem[]>
  listKbRetrievals(sessionId: string): Promise<KbRetrievalItem[]>
  monitor(hours?: number, agent?: string, channel?: string, limit?: number): Promise<MonitorSnapshot>
  monitorContext(sessionId: string): Promise<MonitorContextComposition | null>
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
  listKbRetrievals,
  monitor,
  monitorContext,
  streamChat,
  authState,
  login,
  logout,
}

// 兼容既有调用方的具名导出（实现已下沉到领域模块）
export { setUnauthorizedHandler, notifyUnauthorized }
export { authState, login, logout }
export type { AuthStateView }
export { isAbort, streamChat }
export type { StreamHandlers }
export {
  bindAgent,
  createSession,
  deleteSession,
  listAgents,
  listMessages,
  listSessions,
  listKbRetrievals,
  listLlmCalls,
  listToolCalls,
  monitor,
  monitorContext,
}
