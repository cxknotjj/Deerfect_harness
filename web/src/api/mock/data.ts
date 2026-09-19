/**
 * mock 内嵌数据:字段形状与真实 HTTP JSON 一致。
 * Agent 对齐 GET /api/harness/agents(名称集合,无 id 字段);
 * mock 的 agentId 约定为数组下标 + 1(从 1 起),仅供 bindAgent 演示使用。
 */
import type { SessionView } from '../types'

/** mock Agent 名单(1 → general,2 → coder) */
export const mockAgents = ['general', 'coder']

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
  {
    session: { id: '3', name: '知识库检索', creator: 'cli', lastQuestion: '命中出处怎么展示?' },
    messages: [
      { role: 'user', content: 'RAG 知识库是怎么工作的?' },
      {
        role: 'assistant',
        content: [
          '## RAG 检索增强',
          '',
          '回答前先对知识库做向量检索,把命中片段作为上下文喂给模型:',
          '',
          '- 文档切块后向量化入库',
          '- 提问时检索最相似片段',
          '- 回答内联标注【出处N】,回合尾透出 `sources`',
        ].join('\n'),
      },
      { role: 'user', content: '命中出处怎么展示?' },
      {
        role: 'assistant',
        content: [
          '## 出处展示',
          '',
          'meta 事件的 `sources` 携带命中列表,元素形状如下:',
          '',
          '```json',
          '[{ "docName": "deploy.md", "title": "部署指南", "score": 0.87 }]',
          '```',
          '',
          '前端可直接按 `docName + title` 渲染为可点击引用。',
        ].join('\n'),
      },
    ],
  },
]
