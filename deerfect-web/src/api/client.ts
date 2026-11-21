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
import type {
  CapabilityReloadResult,
  CapabilityReport,
  KbDocument,
  KnowledgeSearchConfig,
  KnowledgeSearchHit,
  KnowledgeSyncResult,
  MemoryItem,
  PageResult,
  ScheduleItem,
  SkillItem,
} from './types'
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
import {
  createLlmProvider,
  createLlmRoute,
  listAdminAgents,
  listLlmModels,
  listLlmProviders,
  listLlmRoutes,
  updateAdminAgent,
  updateLlmProvider,
  updateLlmRoute,
  updateModelContextWindow,
} from './admin'
import type {
  AdminAgentUpsert,
  AdminAgentView,
  LlmModelView,
  LlmProviderUpsert,
  LlmProviderView,
  LlmRouteUpsert,
  LlmRouteView,
} from './admin'
import {
  capabilities,
  createMemory,
  deleteKbDocument,
  deleteMemory,
  deleteSchedule,
  knowledgeSearchConfig,
  listKbDocuments,
  listMemories,
  listSchedules,
  listSkills,
  reloadCapabilities,
  searchKnowledge,
  syncKnowledge,
} from './resources'

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
  /* 资源面：知识库 / 记忆 / 技能 / 定时排期 / MCP 能力清单 */
  listKbDocuments(page?: number, size?: number): Promise<PageResult<KbDocument>>
  knowledgeSearchConfig(): Promise<KnowledgeSearchConfig>
  searchKnowledge(
    query: string,
    kbs: string[],
    topK: number,
    minScore: number,
    fusionMinScore: number,
  ): Promise<KnowledgeSearchHit[]>
  syncKnowledge(): Promise<KnowledgeSyncResult>
  deleteKbDocument(docName: string): Promise<boolean>
  listMemories(status?: string, limit?: number): Promise<MemoryItem[]>
  createMemory(content: string, key?: string): Promise<MemoryItem>
  deleteMemory(id: number): Promise<void>
  listSkills(): Promise<SkillItem[]>
  listSchedules(): Promise<ScheduleItem[]>
  deleteSchedule(id: string): Promise<void>
  capabilities(): Promise<CapabilityReport>
  reloadCapabilities(): Promise<CapabilityReloadResult>
  /* LLM 管理：供应商与模型（凭证值不落库，只给环境变量引用名） */
  listLlmProviders(): Promise<LlmProviderView[]>
  listLlmModels(): Promise<LlmModelView[]>
  createLlmProvider(body: LlmProviderUpsert): Promise<LlmProviderView>
  updateLlmProvider(id: number, body: LlmProviderUpsert): Promise<LlmProviderView>
  updateModelContextWindow(id: number, contextWindow: number): Promise<LlmModelView>
  /* 路由与 Agent 管理 */
  listLlmRoutes(): Promise<LlmRouteView[]>
  createLlmRoute(body: LlmRouteUpsert): Promise<LlmRouteView>
  updateLlmRoute(id: number, body: LlmRouteUpsert): Promise<LlmRouteView>
  listAdminAgents(): Promise<AdminAgentView[]>
  updateAdminAgent(id: number, body: AdminAgentUpsert): Promise<AdminAgentView>
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
  listKbDocuments,
  knowledgeSearchConfig,
  searchKnowledge,
  syncKnowledge,
  deleteKbDocument,
  listMemories,
  createMemory,
  deleteMemory,
  listSkills,
  listSchedules,
  deleteSchedule,
  capabilities,
  reloadCapabilities,
  listLlmProviders,
  listLlmModels,
  createLlmProvider,
  updateLlmProvider,
  updateModelContextWindow,
  listLlmRoutes,
  createLlmRoute,
  updateLlmRoute,
  listAdminAgents,
  updateAdminAgent,
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
