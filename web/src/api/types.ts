/**
 * API 类型定义(独立手写,零复制后端代码)。
 * 类型以 server HTTP JSON 实际响应为准,字段名对齐后端 DTO 的序列化形状:
 * ChatRequest / ChatResponse / SseMeta / SessionPageView / AgentsView /
 * SessionCreatedView / SessionAgentView;后端新增字段时旧客户端宽容忽略。
 */

/** 执行路径:auto=服务端判定(规则+LLM),agentic/graph=显式覆盖 */
export type RoutingMode = 'auto' | 'agentic' | 'graph'

/** 聊天请求体(POST /api/chat/stream;message 必填,sessionId/agentId/routing 可选) */
export interface ChatRequest {
  message: string
  sessionId?: string
  agentId?: number
  /** 显式执行路径(auto 等同不传:服务端自动判定) */
  routing?: RoutingMode
}

/** 路由决策快照(服务端 RouteInfo;流末 meta / 同步响应透出,供 UI 展示与排障) */
export interface RouteInfo {
  /** agentic / graph */
  path: string
  /** 判定置信度 0~1(override/绑定 Agent 恒 1.0) */
  confidence: number
  /** 工作流形态:Graph 路径拆分出的独立工作流 */
  workstreams: string[]
  /** 交付物形态:Graph 路径预期产物 */
  deliverables: string[]
  reason: string
  /** 决策来源:OVERRIDE / RULE / LLM / FALLBACK / BOUND_AGENT / RESUME */
  source: string
}

/** 知识库检索命中出处(RAG 引用) */
export interface KnowledgeSource {
  docName: string
  title: string
  score: number
}

/** 同步聊天响应(POST /api/chat) */
export interface ChatResponse {
  sessionId: string
  newSession: boolean
  goalId: string | null
  status: string
  reply: string | null
  error: string | null
  sources: KnowledgeSource[] | null
  /** 实际使用的 agent 名(回退/分流可观察,可能为空) */
  agent: string | null
  /** 本轮路由决策(路径/置信度/工作流;失败场景可能为 null) */
  route: RouteInfo | null
}

/** 流式 meta 事件负载(流末发送) */
export interface SseMeta {
  sessionId: string
  newSession: boolean
  goalId: string | null
  status: string | null
  error: string | null
  sources: KnowledgeSource[] | null
  /** 本轮路由决策(路径/置信度/工作流;失败场景可能为 null) */
  route: RouteInfo | null
}

/** 进度事件负载(event: progress 的 data JSON) */
export interface ProgressPayload {
  stage: string
  detail: string
}

/** Agent 条目:真实响应为名称集合(无 id/描述等字段) */
/** Agent 列表条目:后端返回 agent 表主键 id + 名称(web 下拉直接用真实 id 绑定会话) */
export interface AgentView {
  id: number
  name: string
}

/** Agent 列表响应(GET /api/harness/agents) */
export interface AgentListView {
  agents: AgentView[]
}


/** 单条会话(SessionPageView.Item;lastQuestion 首条提问前为 null) */
export interface SessionView {
  id: string
  name: string
  creator: string | null
  lastQuestion: string | null
  /** 最近活跃时刻(epoch 毫秒,侧栏相对时间用);旧数据/异常为 null(前端以首见时间兜底) */
  lastActiveAt?: number | null
}

/** 会话分页响应(GET /api/harness/sessions) */
export interface SessionPage {
  page: number
  size: number
  total: number
  pages: number
  sessions: SessionView[]
}

/** 新建会话响应(POST /api/harness/sessions) */
export interface SessionCreatedView {
  sessionId: string
  sessionName: string
}

/** 会话内切换 Agent 响应(POST /api/harness/sessions/{id}/agent) */
export interface SessionAgentView {
  sessionId: string
  agentId: number
  agentName: string | null
}

/** 单条历史消息(GET /api/harness/sessions/{id}/messages;快照只存 user / assistant) */
export interface SessionMessageView {
  role: 'user' | 'assistant'
  content: string
  /** 消息真实时刻(epoch 毫秒;user=发送、assistant=完成);旧快照无时间为 null */
  ts?: number | null
}

/** 会话历史响应(GET /api/harness/sessions/{id}/messages) */
export interface SessionMessagesView {
  sessionId: string
  messages: SessionMessageView[]
}

/** 工具调用观测条目(GET /api/tool-calls?sessionId=;对齐 ToolCallLogEntity 序列化) */
export interface ToolCallItem {
  id: number
  agentName: string | null
  toolName: string | null
  serverName: string | null
  argsSummary: string | null
  status: string | null
  durationMs: number | null
  errorMsg: string | null
  createdAt: string | null
  /** 所属轮次(一条用户消息触发的完整处理);旧数据/submit 直发为 null */
  turnId?: string | null
  /** 所属执行链(一次 Agent 执行整体);旧数据为 null */
  traceId?: string | null
  /** 父 LLM 调用的 span_id;根调用/旧数据为 null(前端缺失时按根缩进) */
  parentSpan?: string | null
}

/** RAG 检索观测条目(GET /api/kb-retrievals?sessionId=;对齐 KbRetrievalLogEntity 序列化,V34) */
export interface KbRetrievalItem {
  id: number
  sessionId: string
  /** 调用方角色(lead/researcher/general 等;预取为被预取的 agent) */
  agentName: string | null
  /** 来源:entry_prefetch / lead_prefetch / subtask_prefetch / inline_query / cache_hit */
  source: string | null
  /** 检索查询文本(超长截断 512) */
  query: string | null
  /** 检索的知识库列表(CSV) */
  kbs: string | null
  /** 注入条数(预算截留后;cache_hit 行为 null;无命中为 0) */
  hitCount: number | null
  /** 检索耗时(毫秒;cache_hit ≈ 0) */
  durationMs: number | null
  /** 1-成功 0-降级/失败 */
  ok: number | null
  /** 降级原因(超时/异常/池拒绝;成功为 null) */
  errorMsg: string | null
  /** 所属轮次(执行期检索携带;预取为 null) */
  turnId?: string | null
  /** 所属执行链(执行期检索携带;预取为 null) */
  traceId?: string | null
  /** kb_retrieval_log 未记录 span,恒为 null(结构对齐 ToolCallItem 供缩进计算访问) */
  parentSpan?: string | null
  /** 检索发起时刻(排序键) */
  startedAt: string | null
  createdAt: string | null
}

/** 运行监控时间窗汇总(GET /api/monitor;全部渠道) */
export interface MonitorSummary {
  calls: number
  errors: number
  errorRate: number
  avgTtftMs: number
  p95TtftMs: number
  avgDurationMs: number
  p95DurationMs: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
  toolCalls: number
  toolErrors: number
}

/** 渠道分布条目(对齐 MonitorSnapshot.Channel;channel 为 qq/web/cli/unknown 等原始键) */
export interface MonitorChannel {
  channel: string
  calls: number
  errors: number
  totalTokens: number
  toolCalls: number
  toolErrors: number
}

/** 时间窗内活跃会话(对齐 MonitorSnapshot.Session;calls/tokens 为窗口内 LLM 聚合) */
export interface MonitorSession {
  sessionId: number
  channel: string
  creator: string | null
  agentName: string | null
  sessionName: string | null
  lastActiveAt: string | null
  calls: number
  totalTokens: number
}

/** 运行监控 LLM 调用条目(对齐 MonitorSnapshot.LlmCall) */
export interface MonitorLlmCall {
  id: number
  sessionId: string | null
  channel: string
  agentName: string | null
  model: string | null
  callKind: string | null
  status: string | null
  firstTokenMs: number | null
  durationMs: number
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  traceId: string | null
  createdAt: string | null
}

/** 运行监控工具调用条目(对齐 MonitorSnapshot.ToolCall) */
export interface MonitorToolCall {
  id: number
  sessionId: string | null
  channel: string
  agentName: string | null
  toolName: string | null
  serverName: string | null
  status: string | null
  durationMs: number
  traceId: string | null
  createdAt: string | null
}

/** 运行监控小时桶(对齐 MonitorSnapshot.Hourly) */
export interface MonitorHourly {
  bucket: string
  calls: number
  tokens: number
  avgTtftMs: number
}

/** 计时器累计统计(对齐 RuntimeMetrics.TimerStats) */
export interface MonitorTimerStats {
  count: number
  avgMs: number
  maxMs: number
}

/** 进程内 Micrometer 指标(对齐 RuntimeMetrics;/actuator/prometheus 同源,重启清零) */
export interface MonitorRuntime {
  uptimeSeconds: number
  processCpuUsage: number
  heapUsedBytes: number
  heapMaxBytes: number
  liveThreads: number
  sseActive: number
  llmCalls: number
  llmErrors: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
  routeAgentic: number
  routeGraph: number
  graphFallbacks: number
  llmDuration: MonitorTimerStats
  firstToken: MonitorTimerStats
}

/** 组件状态(对齐 ComponentStatus;enabled=配置启用,available=当前可用) */
export interface MonitorComponent {
  name: string
  enabled: boolean
  available: boolean
  detail: string
}

/** 运行监控响应(GET /api/monitor?hours=&agent=&channel=&limit=) */
export interface MonitorSnapshot {
  summary: MonitorSummary
  channels: MonitorChannel[]
  sessions: MonitorSession[]
  llmCalls: MonitorLlmCall[]
  toolCalls: MonitorToolCall[]
  hourly: MonitorHourly[]
  runtime: MonitorRuntime
  components: MonitorComponent[]
}

/** LLM 调用观测条目(GET /api/llm-calls?sessionId=;对齐 LlmCallLogEntity 序列化) */
export interface LlmCallItem {
  id: number
  agentName: string | null
  model: string | null
  callKind: string | null
  status: string | null
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  durationMs: number | null
  errorMsg: string | null
  createdAt: string | null
  /** 调用发起时刻（轨迹时序排序键）；历史行/未采集为 null（排序与展示回退 createdAt） */
  startedAt?: string | null
  outputSummary?: string | null
  firstTokenMs?: number | null
  cachedTokens?: number | null
  attempt?: number | null
  maxAttempts?: number | null
  /** 所属轮次(一条用户消息触发的完整处理);旧数据/submit 直发为 null */
  turnId?: string | null
  /** 所属执行链(一次 Agent 执行整体);route-judge/memory-profile 为 null */
  traceId?: string | null
  /** 本次调用的 span 标识(发起前生成);旧数据为 null */
  spanId?: string | null
  /** 父调用 span_id;根调用/旧数据为 null(前端缺失时按根缩进) */
  parentSpan?: string | null
}
