/**
 * LLM 管理端点：供应商 / 模型 / 路由 / Agent 配置。
 *
 * 与 `/api/admin` 对齐：响应体直接暴露表字段（含禁用行），**凭证值不落库也不返回**，
 * 只给 credentialRef（环境变量名）。密钥仍由部署侧环境变量提供，web 端不写密钥。
 */
import { request } from './http'

/** 供应商视图（GET /api/admin/providers） */
export interface LlmProviderView {
  id: number
  code: string
  baseUrl: string
  /** 凭证引用名（环境变量名，如 DASHSCOPE_API_KEY） */
  credentialRef: string
  /** 协议：openai / anthropic 等 */
  protocol: string
  /** 1 启用 / 0 禁用 */
  enabled: number
}

/** 模型视图（GET /api/admin/models） */
export interface LlmModelView {
  id: number
  providerId: number
  providerCode: string
  upstreamName: string
  displayName: string
  capabilities: string | null
  defaultParams: string | null
  enabled: number
}

/** 供应商新增/编辑入参（POST|PUT /api/admin/providers） */
export interface LlmProviderUpsert {
  code: string
  baseUrl: string
  credentialRef: string
  protocol: string
  enabled: number
}

/** 路由成员视图（GET /api/admin/routes） */
export interface LlmRouteMemberView {
  modelId: number
  upstreamName: string
  providerCode: string
  priority: number
  enabled: number
}

/** 路由视图（GET /api/admin/routes） */
export interface LlmRouteView {
  id: number
  code: string
  description: string | null
  strategy: string
  enabled: number
  members: LlmRouteMemberView[]
}

/** 路由成员入参；priority 在同一路由内必须唯一 */
export interface LlmRouteMemberUpsert {
  modelId: number
  priority: number
  enabled: number
}

/** 路由新增/编辑入参（POST|PUT /api/admin/routes） */
export interface LlmRouteUpsert {
  code: string
  description: string | null
  strategy: string
  enabled: number
  members: LlmRouteMemberUpsert[]
}

/** Agent 管理视图（GET /api/admin/agents）；name 是注册标识，不含在可写字段内 */
export interface AdminAgentView {
  id: number
  name: string
  description: string | null
  routeId: number
  routeCode: string | null
  tools: string | null
  knowledge: string | null
  thinking: number | null
  status: number
  isInternal: number
}

/** Agent 配置入参（PUT /api/admin/agents/{id}） */
export interface AdminAgentUpsert {
  description: string | null
  routeId: number
  tools: string | null
  knowledge: string | null
  thinking: number
  status: number
  isInternal: number
}

const JSON_HEADERS = { 'Content-Type': 'application/json' }

/** 全部供应商（含禁用的） */
export function listLlmProviders(): Promise<LlmProviderView[]> {
  return request('/api/admin/providers')
}

/** 全部模型（含禁用的）；页面按 providerId 分组统计 */
export function listLlmModels(): Promise<LlmModelView[]> {
  return request('/api/admin/models')
}

/** 新增供应商；code 重复/协议未知时后端 400 + 原因 */
export function createLlmProvider(body: LlmProviderUpsert): Promise<LlmProviderView> {
  return request('/api/admin/providers', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(body),
  })
}

/** 编辑供应商（整行提交；启停也走这里，enabled 取反） */
export function updateLlmProvider(id: number, body: LlmProviderUpsert): Promise<LlmProviderView> {
  return request(`/api/admin/providers/${id}`, {
    method: 'PUT',
    headers: JSON_HEADERS,
    body: JSON.stringify(body),
  })
}

/** 仅更新模型上下文窗口；其余 capability 键由后端保留，避免整段回传误删 */
export function updateModelContextWindow(id: number, contextWindow: number): Promise<LlmModelView> {
  return request(`/api/admin/models/${id}/context-window`, {
    method: 'PUT',
    headers: JSON_HEADERS,
    body: JSON.stringify({ contextWindow }),
  })
}

/** 全部路由（含禁用的），成员按 priority 升序 */
export function listLlmRoutes(): Promise<LlmRouteView[]> {
  return request('/api/admin/routes')
}

/** 新增路由；至少一个成员，且同一路由内 priority 唯一 */
export function createLlmRoute(body: LlmRouteUpsert): Promise<LlmRouteView> {
  return request('/api/admin/routes', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify(body),
  })
}

/** 编辑路由（整行提交；成员列表整体替换） */
export function updateLlmRoute(id: number, body: LlmRouteUpsert): Promise<LlmRouteView> {
  return request(`/api/admin/routes/${id}`, {
    method: 'PUT',
    headers: JSON_HEADERS,
    body: JSON.stringify(body),
  })
}

/** 全部 Agent（含内部角色）；routeCode 为 null 表示未绑定 */
export function listAdminAgents(): Promise<AdminAgentView[]> {
  return request('/api/admin/agents')
}

/** 保存 Agent 配置；agent_name 不可改，routeId 必须指向可用路由 */
export function updateAdminAgent(id: number, body: AdminAgentUpsert): Promise<AdminAgentView> {
  return request(`/api/admin/agents/${id}`, {
    method: 'PUT',
    headers: JSON_HEADERS,
    body: JSON.stringify(body),
  })
}

/** 从 capabilities JSON 读上下文窗口（仅展示用）；缺失/损坏/非正数返回 0 */
export function parseContextWindow(capabilities: string | null): number {
  if (!capabilities) return 0
  try {
    const node = JSON.parse(capabilities) as { context_window?: unknown } | null
    const value = node?.context_window
    return typeof value === 'number' && Number.isFinite(value) && value > 0 ? Math.floor(value) : 0
  } catch {
    return 0
  }
}
