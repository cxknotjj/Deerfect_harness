/**
 * 本机工作区登记（浏览器本地）。
 *
 * 后端没有工作区资源：沙箱路径固定 `/workspace`，会话表也不带工作区字段。
 * 所以这里的「本地工作区」只是一份 local注册表 + 会话归属映射：
 *   - 决定 Web 端如何按目录分组展示、新会话归入哪个工作区；
 *   - 「打开」的工作区名会同步到侧栏；
 *   - 路径仅作标记，不改变服务端实际工作目录。
 *
 * 状态全部存 localStorage，跨页通过 `workspaces-changed` 事件同步。
 */

export interface WorkspaceRecord {
  id: string
  name: string
  /** 本地目录；空串表示「服务端进程目录」（Web 端不可见） */
  path: string
  /** 归属 Agent；空数组 = 全部 Agent */
  agentIds: number[]
  archived: boolean
  createdAt: number
}

export const WORKSPACE_DEFAULT_ID = 'default'
export const WORKSPACES_EVENT = 'workspaces-changed'

const KEYS = {
  list: 'ui-workspaces',
  active: 'ui-active-workspace',
  sessions: 'ui-session-workspace',
} as const

/** 首次进入时的初始工作区：mock 模式给一份贴近参考图的样例，真实模式只登记默认工作区 */
function seedWorkspaces(): WorkspaceRecord[] {
  const make = (id: string, name: string, path: string): WorkspaceRecord => ({
    id,
    name,
    path,
    agentIds: [],
    archived: false,
    createdAt: 0,
  })
  if (import.meta.env.VITE_USE_MOCK === 'true') {
    return [
      make('ws-zzf', 'zzf_test', 'D:\\DoubaWork\\zzf_test'),
      make(WORKSPACE_DEFAULT_ID, '默认工作区', 'D:\\DoubaWork\\default'),
      make('ws-sandbox', '沙盒实验', 'D:\\DoubaWork\\sandbox'),
    ]
  }
  return [make(WORKSPACE_DEFAULT_ID, '默认工作区', '')]
}

function normalize(raw: unknown): WorkspaceRecord | null {
  if (!raw || typeof raw !== 'object') return null
  const item = raw as Partial<WorkspaceRecord>
  if (typeof item.id !== 'string' || !item.id) return null
  if (typeof item.name !== 'string' || !item.name) return null
  return {
    id: item.id,
    name: item.name,
    path: typeof item.path === 'string' ? item.path : '',
    agentIds: Array.isArray(item.agentIds)
      ? item.agentIds.filter((n): n is number => typeof n === 'number')
      : [],
    archived: item.archived === true,
    createdAt: typeof item.createdAt === 'number' ? item.createdAt : 0,
  }
}

function writeRaw(list: WorkspaceRecord[]): void {
  localStorage.setItem(KEYS.list, JSON.stringify(list))
}

function notify(): void {
  window.dispatchEvent(new Event(WORKSPACES_EVENT))
}

/** 读工作区列表；首次访问（或数据损坏）时写入种子数据，不触发事件 */
export function readWorkspaces(): WorkspaceRecord[] {
  try {
    const raw = localStorage.getItem(KEYS.list)
    if (raw) {
      const parsed = JSON.parse(raw) as unknown
      if (Array.isArray(parsed)) {
        const list = parsed.map(normalize).filter((w): w is WorkspaceRecord => w !== null)
        if (list.length > 0) return list
      }
    }
  } catch {
    /* 损坏就回落到种子数据 */
  }
  const seeded = seedWorkspaces()
  writeRaw(seeded)
  return seeded
}

/** 覆盖写入并广播（设置页改动后侧栏名称同步刷新） */
export function writeWorkspaces(list: WorkspaceRecord[]): void {
  writeRaw(list)
  notify()
}

/** 当前打开的工作区：存储值失效（被归档/删除）时回落到第一个未归档项 */
export function activeWorkspaceId(): string {
  const list = readWorkspaces()
  const stored = localStorage.getItem(KEYS.active)
  const found = list.find((w) => w.id === stored && !w.archived)
  if (found) return found.id
  const fallback = list.find((w) => !w.archived) ?? list[0]
  return fallback ? fallback.id : WORKSPACE_DEFAULT_ID
}

export function activeWorkspaceName(): string {
  const list = readWorkspaces()
  const id = activeWorkspaceId()
  return list.find((w) => w.id === id)?.name ?? '默认工作区'
}

export function setActiveWorkspace(id: string): void {
  localStorage.setItem(KEYS.active, id)
  notify()
}

export function createWorkspace(name: string, path: string): WorkspaceRecord {
  const list = readWorkspaces()
  const record: WorkspaceRecord = {
    id: `ws-${Date.now().toString(36)}`,
    name,
    path,
    agentIds: [],
    archived: false,
    createdAt: Date.now(),
  }
  writeWorkspaces([...list, record])
  return record
}

export function updateWorkspace(id: string, patch: Partial<Omit<WorkspaceRecord, 'id'>>): void {
  const list = readWorkspaces()
  writeWorkspaces(list.map((w) => (w.id === id ? { ...w, ...patch } : w)))
}

/** 删除工作区：归属它的会话映射一并清掉（读不到映射即回落到默认工作区） */
export function removeWorkspace(id: string): void {
  writeWorkspaces(readWorkspaces().filter((w) => w.id !== id))
  const map = readSessionMap()
  let changed = false
  for (const [sessionId, wsId] of Object.entries(map)) {
    if (wsId === id) {
      delete map[sessionId]
      changed = true
    }
  }
  if (changed) localStorage.setItem(KEYS.sessions, JSON.stringify(map))
  if (localStorage.getItem(KEYS.active) === id) localStorage.removeItem(KEYS.active)
  notify()
}

/* ---------- 会话归属：sessionId → workspaceId，缺省归默认工作区 ---------- */

export function readSessionMap(): Record<string, string> {
  try {
    const raw = localStorage.getItem(KEYS.sessions)
    if (!raw) return {}
    const parsed = JSON.parse(raw) as unknown
    if (parsed && typeof parsed === 'object') return parsed as Record<string, string>
  } catch {
    /* 损坏视为空映射 */
  }
  return {}
}

export function workspaceOfSession(sessionId: string): string {
  const id = readSessionMap()[sessionId]
  if (!id) return WORKSPACE_DEFAULT_ID
  return readWorkspaces().some((w) => w.id === id) ? id : WORKSPACE_DEFAULT_ID
}

/** 新会话落库后调用：归入当前打开的工作区 */
export function assignSessionToWorkspace(sessionId: string): void {
  if (!sessionId) return
  const map = readSessionMap()
  map[sessionId] = activeWorkspaceId()
  localStorage.setItem(KEYS.sessions, JSON.stringify(map))
}
