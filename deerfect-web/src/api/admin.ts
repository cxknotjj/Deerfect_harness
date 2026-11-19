/**
 * LLM 管理端点：供应商 / 模型（只读列表 + 供应商新增/编辑 + 启停）。
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
