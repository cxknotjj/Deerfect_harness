/**
 * mock 实现:与真实现同签名,内存态模拟会话/绑定/流式回复。
 * streamChat 直接以「解码后语义」回调(不走线级 event:/data: 转义,
 * 该线级路径由真实现与 server 联调验证);支持 AbortSignal 取消吐字。
 */
import type { Api, StreamHandlers } from '../client'
import type {
  AgentListView,
  CapabilityEntry,
  CapabilityReloadResult,
  CapabilityReport,
  ChatRequest,
  KbDocument,
  KbRetrievalItem,
  KnowledgeSyncResult,
  LlmCallItem,
  MemoryItem,
  MonitorChannel,
  MonitorContextComposition,
  MonitorLlmCall,
  MonitorSnapshot,
  MonitorToolCall,
  PageResult,
  ScheduleItem,
  SessionAgentView,
  SessionCreatedView,
  SessionMessagesView,
  SessionPage,
  SkillItem,
  SseMeta,
  ToolCallItem,
} from '../types'
import type { LlmModelView, LlmProviderUpsert, LlmProviderView } from '../admin'
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

/**
 * RAG 检索观测 mock：预取 + 现查各一条，时间落在会话 1 的同一轮里，
 * 让轨迹页的耗时分布能同时出现「预取 / 现查」两种颜色。
 */
async function listKbRetrievals(sessionId: string): Promise<KbRetrievalItem[]> {
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
      turnId: 'turn-mock-1',
      traceId: null,
      startedAt: '2026-09-22T09:00:08',
      createdAt: '2026-09-22T09:00:08',
    },
    {
      id: 2,
      sessionId,
      agentName: 'general',
      source: 'inline_query',
      query: '401 与 key 权限的排查顺序',
      kbs: 'kb-ops,kb-guide',
      hitCount: 1,
      durationMs: 240,
      ok: 1,
      errorMsg: null,
      turnId: 'turn-mock-1',
      traceId: null,
      startedAt: '2026-09-22T09:01:05',
      createdAt: '2026-09-22T09:01:05',
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
    // 小时桶按请求窗口动态生成（截止当前整点），保证仪表盘的「近 7 天」聚合始终有数据；
    // calls/tokens 用固定波形，截图与断言可复现
    hourly: (() => {
      const size = Math.max(1, Math.min(hours, 168))
      const pad = (v: number): string => String(v).padStart(2, '0')
      const end = new Date()
      end.setMinutes(0, 0, 0)
      return Array.from({ length: size }, (_, idx) => {
        const d = new Date(end.getTime() - (size - 1 - idx) * 3_600_000)
        const wave = (idx * 7 + 3) % 11
        const calls = wave < 3 ? 2 : wave < 6 ? 1 : 0
        return {
          bucket: `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:00`,
          calls,
          tokens: calls === 0 ? 0 : 420 + wave * 130,
          avgTtftMs: calls === 0 ? 0 : 900 + wave * 160,
        }
      })
    })(),
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
      ttsCalls: 2,
      ttsErrors: 0,
      llmDuration: { count: 6, avgMs: 3349, maxMs: 5489 },
      firstToken: { count: 4, avgMs: 2347, maxMs: 3047 },
      ttsDuration: { count: 2, avgMs: 1360, maxMs: 1890 },
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

/** 会话上下文构成 mock:固定样例(对齐 ContextComposition 形状);未知会话返回 null 演示空态 */
async function monitorContext(sessionId: string): Promise<MonitorContextComposition | null> {
  if (!sessionId || sessionId === 'missing') return null
  return {
    sessionId,
    agentName: 'general',
    model: 'deepseek-v4.1-flash-1017',
    turnId: 'turn-mock-001',
    capturedAt: Date.now(),
    estimatedTotalTokens: 2602,
    parts: [
      { key: 'system.role', label: '角色提示词', group: 'system', tokens: 620, percent: 23.8 },
      { key: 'system.toolIndex', label: '工具索引', group: 'system', tokens: 310, percent: 11.9 },
      { key: 'system.discipline', label: '工具纪律', group: 'system', tokens: 120, percent: 4.6 },
      { key: 'system.output', label: '输出约定', group: 'system', tokens: 30, percent: 1.2 },
      { key: 'system.skillIndex', label: '技能索引', group: 'system', tokens: 90, percent: 3.5 },
      { key: 'system.other', label: '系统拼接', group: 'system', tokens: 12, percent: 0.5 },
      { key: 'retrieval.rag', label: 'RAG 知识块', group: 'retrieval', tokens: 240, percent: 9.2 },
      { key: 'tools.schema', label: '工具 Schema', group: 'tools', tokens: 480, percent: 18.4 },
      { key: 'conversation.history.user', label: '历史 user 消息', group: 'conversation', tokens: 300, percent: 11.5 },
      { key: 'conversation.history.assistant', label: '历史 assistant 消息', group: 'conversation', tokens: 260, percent: 10.0 },
      { key: 'conversation.currentUser', label: '当前 user 消息', group: 'conversation', tokens: 140, percent: 5.4 },
    ],
  }
}

/* ============================================================
   资源面 mock：知识库台账 / 记忆 / 技能 / 定时排期 / MCP 能力清单
   与真实现同形；写操作直接改内存副本，刷新页面即复位
   ============================================================ */

/** 本地时区「YYYY-MM-DDTHH:mm:ss」：对齐后端 LocalDateTime 序列化（无时区后缀） */
function localDateTime(offsetMinutes: number): string {
  const d = new Date(Date.now() + offsetMinutes * 60_000)
  const p = (n: number): string => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

const nowEpoch = Date.now()

const kbDocuments: KbDocument[] = [
  {
    id: 1,
    docName: 'java/SpringBoot常见问题.md',
    kb: 'java',
    title: 'Spring Boot 常见问题',
    mtime: nowEpoch - 3 * 86_400_000,
    chunkCount: 1,
    status: 1,
    createdAt: localDateTime(-60 * 24 * 30),
    updatedAt: localDateTime(-3 * 24 * 60),
  },
  {
    id: 2,
    docName: 'frontend/Vue3组件模式.md',
    kb: 'frontend',
    title: 'Vue3 组件模式',
    mtime: nowEpoch - 5 * 86_400_000,
    chunkCount: 2,
    status: 1,
    createdAt: localDateTime(-60 * 24 * 30),
    updatedAt: localDateTime(-5 * 24 * 60),
  },
  {
    id: 3,
    docName: 'ops/推理服务运维手册.md',
    kb: 'ops',
    title: '智算云推理服务运维手册',
    mtime: nowEpoch - 12 * 86_400_000,
    chunkCount: 4,
    status: 1,
    createdAt: localDateTime(-60 * 24 * 28),
    updatedAt: localDateTime(-12 * 24 * 60),
  },
  {
    id: 4,
    docName: 'default/术语表.md',
    kb: 'default',
    title: '术语表',
    mtime: nowEpoch - 20 * 86_400_000,
    chunkCount: 1,
    status: 1,
    createdAt: localDateTime(-60 * 24 * 20),
    updatedAt: localDateTime(-20 * 24 * 60),
  },
]

let memoryRows: MemoryItem[] = [
  {
    id: 1,
    key: 'tech-stack',
    content: '前端偏好 Vue 3 + TypeScript + Vite，样式用手写 CSS token，不引入组件库。',
    source: 'user',
    sessionId: '1',
    createdAt: localDateTime(-2 * 24 * 60),
    expiresAt: null,
    status: 'ACTIVE',
  },
  {
    id: 2,
    key: null,
    content: '部署走 GitHub Actions 构建镜像并推 GHCR，本地验证用 docker compose。',
    source: 'manual',
    sessionId: null,
    createdAt: localDateTime(-5 * 24 * 60),
    expiresAt: null,
    status: 'ACTIVE',
  },
]

const mockSkills: SkillItem[] = [
  {
    name: 'systematic-debugging',
    description: '出现可复现缺陷、测试失败或需要定位根因时使用，在提出修复方案之前执行。',
    agents: null,
    entryFile: 'systematic-debugging/SKILL.md',
    size: 9825,
    error: null,
  },
  {
    name: 'web-research',
    description: '网页调研纪律：多来源信息收集、交叉核对与出处标注的步骤。',
    agents: ['researcher', 'general'],
    entryFile: 'web-research/web-research.md',
    size: 1210,
    error: null,
  },
  {
    name: 'wms',
    description: '查询 WMS 仓储数据（库存、商品、出入库单、发运、仓库网络、作业任务）。',
    agents: ['wms'],
    entryFile: 'wms/SKILL.md',
    size: 4104,
    error: '缺少 description 字段（示例诊断）',
  },
]

let scheduleRows: ScheduleItem[] = [
  {
    id: 'sched-mock-1',
    objective: '汇总昨日仓库入库异常并生成值班简报',
    agentName: 'wms',
    cron: '0 30 9 * * MON-FRI',
    notifyChannel: 'qq:group-1024',
    enabled: true,
    lastRunAt: localDateTime(-18 * 60),
    nextRunAt: localDateTime(6 * 60),
    lastGoalId: 'goal-mock-1',
    createdAt: localDateTime(-40 * 24 * 60),
  },
  {
    id: 'sched-mock-2',
    objective: '抓取行业资讯并按主题归档到知识库',
    agentName: 'researcher',
    cron: '0 0 20 * * SUN',
    notifyChannel: null,
    enabled: true,
    lastRunAt: localDateTime(-3 * 24 * 60),
    nextRunAt: localDateTime(4 * 24 * 60),
    lastGoalId: null,
    createdAt: localDateTime(-30 * 24 * 60),
  },
]

const mockCapabilities: CapabilityEntry[] = [
  {
    pack: 'tavily',
    source: 'mcp:http',
    location: 'http://localhost:8080/mcp',
    tools: ['search', 'extract', 'crawl', 'map', 'qna'],
    state: 'CONNECTED',
  },
  {
    pack: 'wms',
    source: 'mcp:http',
    location: 'http://localhost:18080/mcp',
    tools: [],
    state: 'FAILED',
  },
]

async function listKbDocuments(page = 1, size = 200): Promise<PageResult<KbDocument>> {
  const start = (page - 1) * size
  return { list: kbDocuments.slice(start, start + size), total: kbDocuments.length, page, size }
}

async function syncKnowledge(): Promise<KnowledgeSyncResult> {
  return { scanned: kbDocuments.length, updated: 0, skipped: kbDocuments.length, chunks: 8 }
}

async function deleteKbDocument(docName: string): Promise<boolean> {
  const index = kbDocuments.findIndex((doc) => doc.docName === docName)
  if (index < 0) return false
  kbDocuments.splice(index, 1)
  return true
}

async function listMemories(status = 'active', limit = 100): Promise<MemoryItem[]> {
  const target = status.toUpperCase()
  return memoryRows.filter((row) => row.status === target).slice(0, limit)
}

async function createMemory(content: string, key?: string): Promise<MemoryItem> {
  const trimmed = key?.trim()
  memoryRows = memoryRows.filter((row) => !trimmed || row.key !== trimmed)
  const row: MemoryItem = {
    id: Math.max(0, ...memoryRows.map((m) => m.id)) + 1,
    key: trimmed ? trimmed : null,
    content,
    source: 'manual',
    sessionId: null,
    createdAt: localDateTime(0),
    expiresAt: null,
    status: 'ACTIVE',
  }
  memoryRows.unshift(row)
  return row
}

async function deleteMemory(id: number): Promise<void> {
  memoryRows = memoryRows.filter((row) => row.id !== id)
}

async function listSkills(): Promise<SkillItem[]> {
  return mockSkills
}

async function listSchedules(): Promise<ScheduleItem[]> {
  return scheduleRows
}

async function deleteSchedule(id: string): Promise<void> {
  scheduleRows = scheduleRows.filter((row) => row.id !== id)
}

async function capabilities(): Promise<CapabilityReport> {
  return {
    totalTools: mockCapabilities.reduce((sum, entry) => sum + entry.tools.length, 0),
    entries: mockCapabilities,
    problems: [],
  }
}

async function reloadCapabilities(): Promise<CapabilityReloadResult> {
  return {
    problems: [],
    tools: 0,
    directory: '/mock/http-tools',
    mcp: {
      servers: mockCapabilities.length,
      previousServers: mockCapabilities.length,
      names: mockCapabilities.map((entry) => entry.pack),
    },
  }
}

/* ============================================================
   LLM 管理 mock：供应商 + 模型（结构对齐 /api/admin）
   凭证只给环境变量引用名，与真实实现一致（不出现密钥值）
   ============================================================ */

let llmProviders: LlmProviderView[] = [
  { id: 1, code: 'dashscope', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode', credentialRef: 'DASHSCOPE_API_KEY', protocol: 'openai', enabled: 1 },
  { id: 2, code: 'deepseek', baseUrl: 'https://api.deepseek.com', credentialRef: 'DEEPSEEK_API_KEY', protocol: 'openai', enabled: 1 },
  { id: 3, code: 'tecent', baseUrl: 'https://chatapi.weixin.qq.com/openai', credentialRef: 'TECENT_API_KEY', protocol: 'openai', enabled: 1 },
]

const llmModels: LlmModelView[] = [
  { id: 1, providerId: 1, providerCode: 'dashscope', upstreamName: 'qwen-plus', displayName: 'qwen-plus', capabilities: null, defaultParams: null, enabled: 1 },
  { id: 2, providerId: 1, providerCode: 'dashscope', upstreamName: 'qwen-max', displayName: 'qwen-max', capabilities: null, defaultParams: null, enabled: 1 },
  { id: 3, providerId: 1, providerCode: 'dashscope', upstreamName: 'deepseek-v4.1-flash-1017', displayName: 'deepseek-v4.1-flash-1017', capabilities: null, defaultParams: null, enabled: 1 },
  { id: 4, providerId: 2, providerCode: 'deepseek', upstreamName: 'deepseek-chat', displayName: 'deepseek-chat', capabilities: null, defaultParams: null, enabled: 1 },
  { id: 5, providerId: 2, providerCode: 'deepseek', upstreamName: 'deepseek-reasoner', displayName: 'deepseek-reasoner', capabilities: null, defaultParams: null, enabled: 1 },
  { id: 6, providerId: 3, providerCode: 'tecent', upstreamName: 'Deepseek-v4-flash', displayName: 'Deepseek-v4-flash', capabilities: null, defaultParams: null, enabled: 1 },
]

async function listLlmProviders(): Promise<LlmProviderView[]> {
  return llmProviders.map((p) => ({ ...p }))
}

async function listLlmModels(): Promise<LlmModelView[]> {
  return llmModels.map((m) => ({ ...m }))
}

async function createLlmProvider(body: LlmProviderUpsert): Promise<LlmProviderView> {
  const row: LlmProviderView = {
    id: Math.max(0, ...llmProviders.map((p) => p.id)) + 1,
    ...body,
  }
  llmProviders = [...llmProviders, row]
  return { ...row }
}

async function updateLlmProvider(id: number, body: LlmProviderUpsert): Promise<LlmProviderView> {
  const index = llmProviders.findIndex((p) => p.id === id)
  if (index < 0) throw new Error(`供应商不存在: ${id}`)
  const row: LlmProviderView = { id, ...body }
  llmProviders = llmProviders.map((p) => (p.id === id ? row : p))
  return { ...row }
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
  monitorContext,
  streamChat,
  // mock 模式无鉴权:enabled=false 让登录门直接放行
  authState: async () => ({ enabled: false, authenticated: true }),
  login: async () => {},
  logout: async () => {},
  listKbDocuments,
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
}
