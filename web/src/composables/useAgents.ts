/**
 * Agent 列表与会话绑定。
 * 已知后端限制:GET /api/harness/agents 只返回名称集合(AgentView = string,无 id);
 * bindAgent 需要数字 agentId —— 约定 agentId = 列表下标 + 1(与 mock 保持一致)。
 */
import { ref } from 'vue'
import { api } from '../api'

export function useAgents() {
  const agents = ref<string[]>([])
  const loading = ref(false)

  /** 加载 Agent 名称列表 */
  async function load(): Promise<void> {
    loading.value = true
    try {
      const resp = await api.listAgents()
      agents.value = resp.agents
    } finally {
      loading.value = false
    }
  }

  /** 将会话绑定到指定 agent,返回 agent 名(可能为空,用于提示) */
  async function bind(sessionId: string, agentId: number): Promise<string | null> {
    const resp = await api.bindAgent(sessionId, agentId)
    return resp.agentName
  }

  return { agents, loading, bind, load }
}
