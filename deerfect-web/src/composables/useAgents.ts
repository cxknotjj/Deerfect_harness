/**
 * Agent 列表与会话绑定:列表条目即后端 agent 表主键(id + name),
 * bindAgent 直接用真实 id(旧「下标 + 1」约定已随后端补 id 字段废弃)。
 */
import { ref } from 'vue'
import { api } from '../api'
import type { AgentView } from '../api/types'

export function useAgents(onError?: (msg: string) => void) {
  const agents = ref<AgentView[]>([])
  const loading = ref(false)

  /** 加载 Agent 列表;失败经 onError 轻提示(原为静默吞掉,下拉为空无解释),可重试 */
  async function load(): Promise<void> {
    loading.value = true
    try {
      const resp = await api.listAgents()
      agents.value = resp.agents
    } catch (e) {
      onError?.(`Agent 列表加载失败:${e instanceof Error ? e.message : String(e)}`)
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
