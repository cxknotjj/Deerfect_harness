/**
 * mock 实现:与真实现同签名,内存态模拟会话/绑定/流式回复。
 * streamChat 直接以「解码后语义」回调(不走线级 event:/data: 转义,
 * 该线级路径由真实现与 server 联调验证);支持 AbortSignal 取消吐字。
 */
import type { Api, StreamHandlers } from '../client'
import type {
  AgentListView,
  ChatRequest,
  SessionAgentView,
  SessionCreatedView,
  SessionPage,
  SseMeta,
} from '../types'
import { initialSessions, mockAgents } from './data'
import type { MockMessage } from './data'

// 内存态:会话档案 / 每会话消息记录 / 会话绑定的 agent 名
const sessions = initialSessions.map((s) => ({ ...s.session }))
const memory = new Map<string, MockMessage[]>(initialSessions.map((s) => [s.session.id, [...s.messages]]))
const boundAgents = new Map<string, string>()
let nextId = initialSessions.length + 1

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/** 按输入拼接的中文演示回复:标题 + 列表 + 代码块,体现当前绑定的 agent 名 */
function mockReply(message: string, agentName: string, sessionId: string, newSession: boolean): string {
  return [
    `## 关于「${message}」的回答`,
    '',
    `这是 mock 模式的演示回复,由 **${agentName}** 生成,用于验证前端流式渲染链路。`,
    '',
    '### 本次回合要点',
    '',
    `- 会话 ID:\`${sessionId}\`(新会话:${newSession ? '是' : '否'})`,
    `- 当前 Agent:**${agentName}**`,
    '- 流式输出:约每 30-60ms 吐 1-3 个 token,可随时取消',
    '',
    '### 示例代码',
    '',
    '```ts',
    '// 验证 markdown 代码块渲染',
    'export function greet(name: string): string {',
    "  return `你好,${name}!`",
    '}',
    '```',
    '',
    '需要真实回答时,把 `.env.development` 的 `VITE_USE_MOCK` 改为 `false` 并启动 server 即可。',
  ].join('\n')
}

/** 确保会话存在并记录用户消息,返回 [sessionId, 是否新建] */
function ensureSession(req: ChatRequest): [string, boolean] {
  const sessionId = req.sessionId?.trim() ?? ''
  if (sessionId !== '') {
    ;(memory.get(sessionId) ?? memory.set(sessionId, []).get(sessionId)!).push({
      role: 'user',
      content: req.message,
    })
    const session = sessions.find((s) => s.id === sessionId)
    if (session) session.lastQuestion = req.message
    return [sessionId, false]
  }
  // 未带 sessionId:新建会话(与 server 行为一致)
  const newId = String(nextId++)
  sessions.unshift({ id: newId, name: '新会话', creator: 'web', lastQuestion: req.message })
  memory.set(newId, [{ role: 'user', content: req.message }])
  return [newId, true]
}

async function streamChat(req: ChatRequest, handlers: StreamHandlers, signal?: AbortSignal): Promise<void> {
  const [sessionId, newSession] = ensureSession(req)
  const agentName = boundAgents.get(sessionId) ?? mockAgents[0].name

  // 流首 agent 归属进度 + 编排进度(形状与 server 的 progress 事件一致)
  handlers.onProgress?.({ stage: 'agent', detail: agentName })
  await sleep(120)
  if (signal?.aborted) return
  handlers.onProgress?.({ stage: '编排子任务', detail: `正在拆解「${req.message}」并编排子任务` })
  await sleep(120)
  if (signal?.aborted) return

  // 打字机分片:每 30-60ms 吐 1-3 个 token(每 token 2-6 个字符,分片含换行验证多行渲染)
  const reply = mockReply(req.message, agentName, sessionId, newSession)
  const chars = Array.from(reply)
  let i = 0
  while (i < chars.length) {
    await sleep(30 + Math.random() * 30)
    if (signal?.aborted) return
    let chunk = ''
    const tokens = 1 + Math.floor(Math.random() * 3)
    for (let t = 0; t < tokens && i < chars.length; t++) {
      const size = 2 + Math.floor(Math.random() * 5)
      chunk += chars.slice(i, i + size).join('')
      i += size
    }
    handlers.onToken?.(chunk)
  }

  // 收尾顺序与 server 一致:token 流 → [DONE] → meta
  const transcript = memory.get(sessionId)
  transcript?.push({ role: 'assistant', content: reply })
  handlers.onDone?.()
  const meta: SseMeta = {
    sessionId,
    newSession,
    goalId: null,
    status: 'SUCCEEDED',
    error: null,
    sources: null,
  }
  handlers.onMeta?.(meta)
}

async function listAgents(): Promise<AgentListView> {
  return { agents: mockAgents.map((a) => ({ ...a })) }
}

async function listSessions(page = 1, size = 10): Promise<SessionPage> {
  const total = sessions.length
  const start = (page - 1) * size
  return {
    page,
    size,
    total,
    pages: Math.ceil(total / size),
    sessions: sessions.slice(start, start + size).map((s) => ({ ...s })),
  }
}

async function createSession(name = '新会话'): Promise<SessionCreatedView> {
  const sessionId = String(nextId++)
  sessions.unshift({ id: sessionId, name, creator: 'web', lastQuestion: null })
  memory.set(sessionId, [])
  return { sessionId, sessionName: name }
}

async function bindAgent(sessionId: string, agentId: number): Promise<SessionAgentView> {
  if (!sessions.some((s) => s.id === sessionId)) {
    throw new Error(`会话不存在: ${sessionId}`)
  }
  const agent = mockAgents.find((a) => a.id === agentId)
  if (agent === undefined) {
    throw new Error(`agentId 不存在: ${agentId}`)
  }
  const agentName = agent.name
  // 绑定后该会话后续 mock 回复与流首进度均体现新 agent 名
  boundAgents.set(sessionId, agentName)
  return { sessionId, agentId, agentName }
}

/** mock API 实现(与 realApi 同签名) */
export const mockApi: Api = { listAgents, listSessions, createSession, bindAgent, streamChat }
