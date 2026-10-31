/**
 * mock 实现:与真实现同签名,内存态模拟会话/绑定/流式回复。
 * streamChat 直接以「解码后语义」回调(不走线级 event:/data: 转义,
 * 该线级路径由真实现与 server 联调验证);支持 AbortSignal 取消吐字。
 */
import type { Api, StreamHandlers } from '../client'
import type {
  AgentListView,
  ChatRequest,
  KbRetrievalItem,
  LlmCallItem,
  MonitorChannel,
  MonitorLlmCall,
  MonitorSnapshot,
  MonitorToolCall,
  SessionAgentView,
  SessionCreatedView,
  SessionMessagesView,
  SessionPage,
  SseMeta,
  ToolCallItem,
} from '../types'
import { initialSessions, initialTraces, mockAgents } from './data'
import type { MockMessage } from './data'

// 内存态:会话档案 / 每会话消息记录 / 会话绑定的 agent 名 / 调用观测记录
const sessions = initialSessions.map((s) => ({ ...s.session }))
const memory = new Map<string, MockMessage[]>(initialSessions.map((s) => [s.session.id, [...s.messages]]))
const traces = new Map(
  Object.entries(initialTraces).map(([id, t]) => [id, { llm: [...t.llm], tool: [...t.tool] }]),
)
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
    // mock 无路由判定：以 Agentic 路径占位，与真实响应形状一致
    route: { path: 'agentic', confidence: 1, workstreams: [], deliverables: [], reason: 'mock', source: 'RULE' },
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

/** 会话历史消息:直接回吐内存态转录(与真实现同形) */
async function listMessages(sessionId: string): Promise<SessionMessagesView> {
  return { sessionId, messages: (memory.get(sessionId) ?? []).map((m) => ({ ...m })) }
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

/** 删除会话:移除内存态会话档案、转录与绑定(与真实现幂等语义一致) */
async function deleteSession(sessionId: string): Promise<void> {
  const idx = sessions.findIndex((s) => s.id === sessionId)
  if (idx !== -1) sessions.splice(idx, 1)
  memory.delete(sessionId)
  boundAgents.delete(sessionId)
  traces.delete(sessionId)
}

/** 调用观测:回吐该会话预置记录(无记录会话返回空数组,与真实现语义一致) */
async function listToolCalls(sessionId: string): Promise<ToolCallItem[]> {
  return (traces.get(sessionId)?.tool ?? []).map((t) => ({ ...t }))
}

async function listLlmCalls(sessionId: string): Promise<LlmCallItem[]> {
  return (traces.get(sessionId)?.llm ?? []).map((t) => ({ ...t }))
}

/** RAG 检索观测 mock:固定一条样例(覆盖来源/命中/降级三形态),与真实现语义一致 */
async function listKbRetrievals(sessionId: string): Promise<KbRetrievalItem[]> {
  const now = new Date().toISOString()
  const sample: KbRetrievalItem[] = [
    {
      id: 1,
      sessionId,
      agentName: 'general',
      source: 'entry_prefetch',
      query: '编排超时机制如何配置',
      kbs: 'kb-guide',
      hitCount: 3,
      durationMs: 812,
      ok: 1,
      errorMsg: null,
      turnId: null,
      traceId: null,
      startedAt: now,
      createdAt: now,
    },
  ]
  return sample.map((s) => ({ ...s }))
}

/** 运行监控 mock:覆盖各渠道 TTFT/错误/工具/小时趋势,结构对齐 GET /api/monitor */
async function monitor(hours = 24, agent = '', channel = ''): Promise<MonitorSnapshot> {
  const allLlm: MonitorLlmCall[] = [
    {
      id: 1349, sessionId: '241', channel: 'qq', agentName: 'nailong', model: 'deepseek-v4.1-flash-1017',
      callKind: 'STREAM', status: 'OK', firstTokenMs: 2800, durationMs: 3116,
      promptTokens: 873, completionTokens: 107, totalTokens: 980,
      traceId: 'b1f0d4c2a9e14c8f', createdAt: '2026-10-31 20:24:43',
    },
    {
      id: 1348, sessionId: '241', channel: 'qq', agentName: 'nailong', model: 'deepseek-v4.1-flash-1017',
      callKind: 'STREAM', status: 'OK', firstTokenMs: 2363, durationMs: 2637,
      promptTokens: 861, completionTokens: 96, totalTokens: 957,
      traceId: '7c2e9a1b4d6f8035', createdAt: '2026-10-31 20:24:23',
    },
    {
      id: 1347, sessionId: '241', channel: 'qq', agentName: 'nailong', model: 'deepseek-v4.1-flash-1017',
      callKind: 'STREAM', status: 'ERROR', firstTokenMs: null, durationMs: 5489,
      promptTokens: 902, completionTokens: null, totalTokens: 902,
      traceId: '4d8a0e6f2b7c1934', createdAt: '2026-10-31 20:14:54',
    },
    {
      id: 1346, sessionId: '221', channel: 'qq', agentName: 'writer', model: 'deepseek-v4.1-flash-1017',
      callKind: 'STREAM', status: 'OK', firstTokenMs: 3047, durationMs: 3202,
      promptTokens: 744, completionTokens: 88, totalTokens: 832,
      traceId: '9a3f5e2d8c1b7046', createdAt: '2026-10-31 20:13:37',
    },
    {
      id: 1345, sessionId: '1', channel: 'cli', agentName: 'general', model: 'deepseek-v4.1-flash-1017',
      callKind: 'SYNC', status: 'OK', firstTokenMs: null, durationMs: 4210,
      promptTokens: 512, completionTokens: 64, totalTokens: 576,
      traceId: 'e1b2c3d4a5f60718', createdAt: '2026-10-31 19:58:12',
    },
    {
      id: 1343, sessionId: '300', channel: 'web', agentName: 'general', model: 'deepseek-v4.1-flash-1017',
      callKind: 'STREAM', status: 'OK', firstTokenMs: 1180, durationMs: 1440,
      promptTokens: 486, completionTokens: 73, totalTokens: 559,
      traceId: 'f2a1b0c9d8e7f605', createdAt: '2026-10-31 19:40:18',
    },
  ]
  const allTools: MonitorToolCall[] = [
    {
      id: 296, sessionId: '232', channel: 'qq', agentName: 'researcher', toolName: 'tavily_search',
      serverName: 'tavily', status: 'OK', durationMs: 16232,
      traceId: 'd1654b1bb3b643e280e3dd66b856ee58', createdAt: '2026-10-26 20:42:11',
    },
    {
      id: 292, sessionId: '230', channel: 'qq', agentName: 'researcher', toolName: 'fetchUrl',
      serverName: 'tavily', status: 'OK', durationMs: 1146,
      traceId: 'a5a72b268ecb441f90850ddc2410bf50', createdAt: '2026-10-25 21:29:13',
    },
  ]
  const agentScopedLlm = agent ? allLlm.filter((x) => x.agentName === agent) : allLlm
  const agentScopedTools = agent ? allTools.filter((x) => x.agentName === agent) : allTools
  const filteredLlm = channel ? agentScopedLlm.filter((x) => x.channel === channel) : agentScopedLlm
  const filteredTools = channel ? agentScopedTools.filter((x) => x.channel === channel) : agentScopedTools

  // 渠道分布:应用 agent 过滤、不应用渠道过滤(保证筛选项完整)
  const channelMap = new Map<string, MonitorChannel>()
  function ensureChannel(key: string): MonitorChannel {
    const existing = channelMap.get(key)
    if (existing) return existing
    const created: MonitorChannel = {
      channel: key, calls: 0, errors: 0, totalTokens: 0, toolCalls: 0, toolErrors: 0,
    }
    channelMap.set(key, created)
    return created
  }
  for (const c of agentScopedLlm) {
    const row = ensureChannel(c.channel)
    row.calls++
    if (c.status !== 'OK') row.errors++
    row.totalTokens += c.totalTokens ?? 0
  }
  for (const t of agentScopedTools) {
    const row = ensureChannel(t.channel)
    row.toolCalls++
    if (t.status !== 'OK') row.toolErrors++
  }
  const channels = [...channelMap.values()].sort((a, b) => b.calls - a.calls)

  const calls = filteredLlm.length
  const errors = filteredLlm.filter((x) => x.status !== 'OK').length
  const totalTokens = filteredLlm.reduce((sum, x) => sum + (x.totalTokens ?? 0), 0)
  const ttfts = filteredLlm.map((x) => x.firstTokenMs).filter((x): x is number => x != null)
  const avgTtft = ttfts.length ? Math.round(ttfts.reduce((a, b) => a + b, 0) / ttfts.length) : 0
  const sessionStats = (sessionId: string): { calls: number; totalTokens: number } => {
    const rows = filteredLlm.filter((x) => x.sessionId === sessionId)
    return { calls: rows.length, totalTokens: rows.reduce((sum, x) => sum + (x.totalTokens ?? 0), 0) }
  }
  const sessionRows: MonitorSnapshot['sessions'] = [
    {
      sessionId: 241, channel: 'qq', creator: 'qq:2322055372', agentName: 'nailong',
      sessionName: '你是谁啊', lastActiveAt: '2026-10-31 20:24:43', ...sessionStats('241'),
    },
    {
      sessionId: 221, channel: 'qq', creator: 'qq:2322055372', agentName: 'writer',
      sessionName: '记住：我最喜欢的数字是 7', lastActiveAt: '2026-10-16 22:47:27', ...sessionStats('221'),
    },
    {
      sessionId: 300, channel: 'web', creator: 'anonymous', agentName: 'general',
      sessionName: '流式渲染验证', lastActiveAt: '2026-10-31 19:40:18', ...sessionStats('300'),
    },
    ...sessions.slice(0, 2).map((s): MonitorSnapshot['sessions'][number] => ({
      sessionId: Number(s.id), channel: 'cli', creator: s.creator ?? 'cli', agentName: 'general',
      sessionName: s.name, lastActiveAt: '2026-10-31 19:58:12', ...sessionStats(s.id),
    })),
  ].filter((s) => (!agent || s.agentName === agent) && (!channel || s.channel === channel))
  return {
    summary: {
      calls,
      errors,
      errorRate: calls ? errors / calls : 0,
      avgTtftMs: avgTtft,
      p95TtftMs: ttfts.length ? Math.max(...ttfts) : 0,
      avgDurationMs: calls
        ? Math.round(filteredLlm.reduce((sum, x) => sum + x.durationMs, 0) / calls)
        : 0,
      p95DurationMs: calls ? Math.max(...filteredLlm.map((x) => x.durationMs)) : 0,
      promptTokens: filteredLlm.reduce((sum, x) => sum + (x.promptTokens ?? 0), 0),
      completionTokens: filteredLlm.reduce((sum, x) => sum + (x.completionTokens ?? 0), 0),
      totalTokens,
      toolCalls: filteredTools.length,
      toolErrors: filteredTools.filter((x) => x.status !== 'OK').length,
    },
    channels,
    sessions: sessionRows,
    llmCalls: filteredLlm,
    toolCalls: filteredTools,
    hourly: [
      { bucket: '10-31 11:00', calls: 0, tokens: 0, avgTtftMs: 0 },
      { bucket: '10-31 12:00', calls: 1, tokens: 832, avgTtftMs: 3047 },
      { bucket: '10-31 13:00', calls: 0, tokens: 0, avgTtftMs: 0 },
      { bucket: '10-31 14:00', calls: 1, tokens: 902, avgTtftMs: 0 },
      { bucket: '10-31 15:00', calls: 0, tokens: 0, avgTtftMs: 0 },
      { bucket: '10-31 16:00', calls: 0, tokens: 0, avgTtftMs: 0 },
      { bucket: '10-31 17:00', calls: 0, tokens: 0, avgTtftMs: 0 },
      { bucket: '10-31 18:00', calls: 0, tokens: 0, avgTtftMs: 0 },
      { bucket: '10-31 19:00', calls: 2, tokens: 1135, avgTtftMs: 1180 },
      { bucket: '10-31 20:00', calls: 2, tokens: 1937, avgTtftMs: 2582 },
    ].slice(-Math.max(1, Math.min(hours, 10))),
    runtime: {
      uptimeSeconds: 1830,
      processCpuUsage: 0.021,
      heapUsedBytes: 187_000_000,
      heapMaxBytes: 4_294_967_296,
      liveThreads: 48,
      sseActive: 1,
      llmCalls: 6,
      llmErrors: 1,
      promptTokens: 4378,
      completionTokens: 428,
      totalTokens: 4806,
      routeAgentic: 4,
      routeGraph: 2,
      graphFallbacks: 0,
      llmDuration: { count: 6, avgMs: 3349, maxMs: 5489 },
      firstToken: { count: 4, avgMs: 2347, maxMs: 3047 },
    },
    components: [
      {
        name: 'NapCat / QQ', enabled: true, available: true,
        detail: '可达（http://127.0.0.1:3000）',
      },
      {
        name: 'Docker 沙箱', enabled: true, available: false,
        detail: '未检测到 Docker，沙箱类工具未启用',
      },
      {
        name: 'MCP · tavily', enabled: true, available: true,
        detail: '已连接（http），5 个工具',
      },
      {
        name: 'MCP · wms', enabled: true, available: false,
        detail: '连接/发现失败，该 server 工具面为空',
      },
    ],
  }
}

/** mock API 实现(与 realApi 同签名) */
export const mockApi: Api = {
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
  streamChat,
  // mock 模式无鉴权:enabled=false 让登录门直接放行
  authState: async () => ({ enabled: false, authenticated: true }),
  login: async () => {},
  logout: async () => {},
}
