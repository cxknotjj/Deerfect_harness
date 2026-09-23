/**
 * mock 内嵌数据:字段形状与真实 HTTP JSON 一致。
 * Agent 对齐 GET /api/harness/agents(名称集合,无 id 字段);
 * mock 的 agentId 约定为数组下标 + 1(从 1 起),仅供 bindAgent 演示使用。
 */
import type { LlmCallItem, SessionView, ToolCallItem } from '../types'

/** mock Agent 名单(id 与「下标 + 1」约定一致;结构对齐真模式的 AgentItemView) */
export const mockAgents = [
  { id: 1, name: 'general' },
  { id: 2, name: 'coder' },
]

/** mock 会话消息(role/content 与聊天上下文形状一致) */
export interface MockMessage {
  role: 'user' | 'assistant'
  content: string
}

/** mock 会话:会话档案 + 消息记录 */
export interface MockSession {
  session: SessionView
  messages: MockMessage[]
}

export const initialSessions: MockSession[] = [
  {
    session: { id: '1', name: 'Qwen 接入排查', creator: 'cli', lastQuestion: '模型调用报 401 怎么排查?' },
    messages: [
      { role: 'user', content: '怎么接入一个新的模型服务商?' },
      {
        role: 'assistant',
        content: [
          '## 接入新服务商',
          '',
          '按约定优于配置,两步即可:',
          '',
          '1. 设置环境变量,如 `MOONSHOT_API_KEY`',
          '2. 在 yaml 的 `app.providers` 下声明模型清单',
          '',
          '### 示例配置',
          '',
          '```yaml',
          'app:',
          '  providers:',
          '    moonshot:',
          '      models:',
          '        - kimi-k2',
          '```',
          '',
          '落库后热刷新注册表,无需重启。',
        ].join('\n'),
      },
      { role: 'user', content: '模型调用报 401 怎么排查?' },
      {
        role: 'assistant',
        content: [
          '## 401 排查思路',
          '',
          '- 确认环境变量已导出:`echo $MOONSHOT_API_KEY`',
          '- 确认 key 未过期且具备对应模型的调用权限',
          '- 查看启动日志是否有「未配置 API Key」告警',
          '',
          '若仍失败,查看调用记录里的原始响应体即可定位原因。',
        ].join('\n'),
      },
    ],
  },
  {
    session: { id: '2', name: '多 Agent 编排', creator: 'cli', lastQuestion: '复杂任务是怎么拆解的?' },
    messages: [
      { role: 'user', content: '多 Agent 编排是什么?' },
      {
        role: 'assistant',
        content: [
          '## 多 Agent 编排',
          '',
          '复杂目标先由 lead Agent 拆解为子任务,再分派给专家 Agent 执行,最后聚合结果:',
          '',
          '- **拆解**:分析目标,产出子任务清单',
          '- **分派**:按子任务领域选择专家 Agent',
          '- **聚合**:汇总各子任务结果并综合作答',
          '',
          '编排期间通过 `progress` 事件向客户端透出阶段反馈。',
        ].join('\n'),
      },
      { role: 'user', content: '复杂任务是怎么拆解的?' },
      {
        role: 'assistant',
        content: [
          '## 拆解示例',
          '',
          '以「给模块补齐单元测试」为例,大致拆为:',
          '',
          '1. 扫描模块结构,列出待测类',
          '2. 逐类生成测试用例',
          '3. 运行测试并修复失败项',
          '',
          '### 检查点',
          '',
          '```text',
          'PENDING → RUNNING → SUCCEEDED / FAILED',
          '```',
          '',
          '失败可按 `goalId` 从检查点续跑,无需从头执行。',
        ].join('\n'),
      },
    ],
  },
]

/** mock 调用观测记录:按会话预置 LLM/工具调用(会话 1 含 1 条 ERROR 演示错误态),
    字段形状与 GET /api/tool-calls、GET /api/llm-calls 真实响应一致 */
export const initialTraces: Record<string, { llm: LlmCallItem[]; tool: ToolCallItem[] }> = {
  '1': {
    llm: [
      {
        id: 1,
        agentName: 'general',
        model: 'qwen-plus',
        callKind: 'STREAM',
        status: 'OK',
        promptTokens: 1284,
        completionTokens: 412,
        totalTokens: 1696,
        durationMs: 4250,
        errorMsg: null,
        createdAt: '2026-09-22T09:00:10',
        outputSummary: '接入新服务商:两步即可——设置环境变量(如 MOONSHOT_API_KEY),在 yaml 的 app.providers 下声明模型清单,落库后热刷新注册表。',
        firstTokenMs: 820,
        cachedTokens: 0,
      },
      {
        id: 2,
        agentName: 'general',
        model: 'qwen-plus',
        callKind: 'STREAM',
        status: 'OK',
        promptTokens: 2140,
        completionTokens: 356,
        totalTokens: 2496,
        durationMs: 3810,
        errorMsg: null,
        createdAt: '2026-09-22T09:01:02',
        outputSummary: '401 排查思路:确认环境变量已导出、key 未过期且具备对应模型调用权限;仍失败时查看调用记录里的原始响应体定位。',
        firstTokenMs: 910,
        cachedTokens: 512,
      },
    ],
    tool: [
      {
        id: 1,
        agentName: 'general',
        toolName: 'grep_web',
        serverName: 'tavily',
        argsSummary: '{"query": "moonshot api 401 invalid api key"}',
        status: 'OK',
        durationMs: 1340,
        errorMsg: null,
        createdAt: '2026-09-22T09:00:52',
      },
      {
        id: 2,
        agentName: 'general',
        toolName: 'fetchUrl',
        serverName: 'tavily',
        argsSummary: 'https://platform.moonshot.cn/docs/guide/start-using',
        status: 'ERROR',
        durationMs: 5020,
        errorMsg: 'HTTP 504: upstream timeout after 5s',
        createdAt: '2026-09-22T09:00:58',
      },
    ],
  },
  '2': {
    llm: [
      {
        id: 3,
        agentName: 'lead',
        model: 'qwen-plus',
        callKind: 'SYNC',
        status: 'OK',
        promptTokens: 860,
        completionTokens: 240,
        totalTokens: 1100,
        durationMs: 2100,
        errorMsg: null,
        createdAt: '2026-09-22T10:12:00',
      },
      {
        id: 4,
        agentName: 'general',
        model: 'qwen-plus',
        callKind: 'STREAM',
        status: 'OK',
        promptTokens: 1955,
        completionTokens: 630,
        totalTokens: 2585,
        durationMs: 5140,
        errorMsg: null,
        createdAt: '2026-09-22T10:12:12',
      },
    ],
    tool: [
      {
        id: 3,
        agentName: 'lead',
        toolName: 'run_code',
        serverName: null,
        argsSummary: '{"code": "const r = await tools.grep_web(\'多agent编排模式\')"}',
        status: 'OK',
        durationMs: 890,
        errorMsg: null,
        createdAt: '2026-09-22T10:12:06',
      },
    ],
  },
}
