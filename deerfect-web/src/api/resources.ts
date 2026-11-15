/**
 * 资源面端点：知识库台账、显式记忆、技能目录、定时排期、MCP 能力清单。
 *
 * 与观测端点（observability.ts）分开：这里都是「可管理的资源」，读之外还有
 * 同步/新增/删除/重载等写操作，页面直接消费，不参与聊天链路。
 */
import type {
  CapabilityReloadResult,
  CapabilityReport,
  KbDocument,
  KnowledgeSyncResult,
  MemoryItem,
  PageResult,
  ScheduleItem,
  SkillItem,
} from './types'
import { request, requestVoid } from './http'

const JSON_HEADERS = { 'Content-Type': 'application/json' }

/* ---------- 知识库 ---------- */

/** 文档台账分页（GET /api/knowledge/documents；page 从 1 起，size 上限 200） */
export function listKbDocuments(page = 1, size = 200): Promise<PageResult<KbDocument>> {
  return request(`/api/knowledge/documents?page=${page}&size=${size}`)
}

/**
 * 增量摄取 knowledge/ 目录（POST /api/knowledge/sync）：
 * 只重嵌入 mtime 变更的文档；知识库未启用时后端 503。
 */
export function syncKnowledge(): Promise<KnowledgeSyncResult> {
  return request('/api/knowledge/sync', { method: 'POST' })
}

/** 删除文档（DELETE /api/knowledge/documents/{name}；docName 含子目录，需整段编码） */
export function deleteKbDocument(docName: string): Promise<boolean> {
  return request(`/api/knowledge/documents/${encodeURIComponent(docName)}`, { method: 'DELETE' })
}

/* ---------- 记忆 ---------- */

/** 记忆列表（GET /api/memories?status=&limit=；服务端以 { items } 包裹） */
export async function listMemories(status = 'active', limit = 100): Promise<MemoryItem[]> {
  const view = await request<{ items: MemoryItem[] }>(
    `/api/memories?status=${encodeURIComponent(status)}&limit=${limit}`,
  )
  return view.items
}

/** 新增一条记忆（POST /api/memories；key 相同会覆盖旧记忆） */
export function createMemory(content: string, key?: string): Promise<MemoryItem> {
  return request('/api/memories', {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ content, key: key?.trim() ? key.trim() : null, expiresAt: null }),
  })
}

/** 软删一条记忆（DELETE /api/memories/{id}；保留审计，不再注入） */
export function deleteMemory(id: number): Promise<void> {
  return requestVoid(`/api/memories/${id}`, 'DELETE')
}

/* ---------- 技能 ---------- */

/** 技能目录解析结果（GET /api/skills；error 非空表示该技能格式有问题） */
export function listSkills(): Promise<SkillItem[]> {
  return request('/api/skills')
}

/* ---------- 定时排期（内容日历） ---------- */

/** 全部定时任务（GET /api/schedules；按创建时间倒序） */
export async function listSchedules(): Promise<ScheduleItem[]> {
  const view = await request<{ items: ScheduleItem[] }>('/api/schedules')
  return view.items
}

/** 删除定时任务（DELETE /api/schedules/{id}；已触发的 goal 不受影响） */
export function deleteSchedule(id: string): Promise<void> {
  return requestVoid(`/api/schedules/${encodeURIComponent(id)}`, 'DELETE')
}

/* ---------- MCP / 能力清单 ---------- */

/** 能力清单（GET /api/capabilities；MCP 条目 source 形如 mcp:http） */
export function capabilities(): Promise<CapabilityReport> {
  return request('/api/capabilities')
}

/** 重载外部工具（POST /api/capabilities/reload）：重解析 HTTP 清单与 mcp-config.json */
export function reloadCapabilities(): Promise<CapabilityReloadResult> {
  return request('/api/capabilities/reload', { method: 'POST' })
}
