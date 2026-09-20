/**
 * 会话列表状态:首页加载、分页「加载更多」、新建(自动选中)、选中。
 * 数据一律来自统一 api 出口(mock / 真实实现同签名)。
 */
import { computed, ref } from 'vue'
import { api } from '../api'
import type { SessionView } from '../api'

/** 每页条数(与后端 listSessions 分页参数对应) */
const PAGE_SIZE = 10

export function useSessions() {
  /** 已加载的会话(首页 + 已「加载更多」的页) */
  const sessions = ref<SessionView[]>([])
  /** 服务端总会话数(用于判断是否还有下一页) */
  const total = ref(0)
  /** 最近一次拉取的页码 */
  const page = ref(1)
  /** 当前选中会话 id(空串表示未选中) */
  const currentSessionId = ref('')
  const loading = ref(false)

  /** 是否还有更多页:已加载条数少于总数 */
  const hasMore = computed(() => sessions.value.length < total.value)

  /** 拉取一页;replace 为 true 时重置列表(首页/刷新),否则向后追加并按 id 去重 */
  async function fetchPage(target: number, replace: boolean): Promise<void> {
    if (loading.value) return
    loading.value = true
    try {
      const resp = await api.listSessions(target, PAGE_SIZE)
      page.value = resp.page
      total.value = resp.total
      if (replace) {
        sessions.value = resp.sessions
      } else {
        const seen = new Set(sessions.value.map((s) => s.id))
        sessions.value = [...sessions.value, ...resp.sessions.filter((s) => !seen.has(s.id))]
      }
    } finally {
      loading.value = false
    }
  }

  /** 首页加载(替换语义) */
  async function loadFirst(): Promise<void> {
    await fetchPage(1, true)
  }

  /** 加载下一页(无更多或加载中时忽略) */
  async function loadMore(): Promise<void> {
    if (!hasMore.value || loading.value) return
    await fetchPage(page.value + 1, false)
  }

  /** 新建会话:列表头部插入并自动选中新会话 */
  async function create(name = '新会话'): Promise<void> {
    const resp = await api.createSession(name)
    if (!sessions.value.some((s) => s.id === resp.sessionId)) {
      sessions.value.unshift({
        id: resp.sessionId,
        name: resp.sessionName,
        creator: null,
        lastQuestion: null,
      })
      total.value += 1
    }
    currentSessionId.value = resp.sessionId
  }

  /** 选中会话(仅改选中状态;聊天窗清空由外层联动,避免流式 onMeta 更新会话时误清空) */
  function select(id: string): void {
    currentSessionId.value = id
  }

  /** 删除会话:服务端成功后才从本地列表移除并修正 total;失败原样抛错由调用方处理(列表不动) */
  async function remove(id: string): Promise<void> {
    await api.deleteSession(id)
    sessions.value = sessions.value.filter((s) => s.id !== id)
    total.value = Math.max(0, total.value - 1)
  }

  return { sessions, total, currentSessionId, loading, hasMore, loadFirst, loadMore, create, select, remove }
}
