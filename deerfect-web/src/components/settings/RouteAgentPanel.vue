<script setup lang="ts">
/**
 * 「路由与 Agent」页：路由卡片区 + Agent 卡片区。
 *
 * 路由配置、管理成员、Agent 配置都在本页弹窗内完成；写操作走 /api/admin，
 * 成功后直接替换本地行，不整页重拉。Agent 数量与绑定关系由 agents.routeId 现算。
 */
import { computed, onMounted, ref } from 'vue'
import { api } from '../../api'
import type {
  AdminAgentUpsert,
  AdminAgentView,
  LlmModelView,
  LlmRouteMemberUpsert,
  LlmRouteUpsert,
  LlmRouteView,
} from '../../api/admin'
import { agentTone, routeMark, routeSummary } from '../../utils/routeAgent'
import AgentConfigModal from './AgentConfigModal.vue'
import AgentGlyph from './AgentGlyph.vue'
import RouteConfigModal from './RouteConfigModal.vue'
import RouteMembersModal from './RouteMembersModal.vue'

type ModalKind = 'route' | 'members' | 'agent'

const routes = ref<LlmRouteView[]>([])
const models = ref<LlmModelView[]>([])
const agents = ref<AdminAgentView[]>([])
const loading = ref(false)
const error = ref('')
const note = ref('')

const modal = ref<ModalKind | null>(null)
const modalError = ref('')
const saving = ref(false)
const activeRoute = ref<LlmRouteView | null>(null)
const activeAgent = ref<AdminAgentView | null>(null)
const creatingRoute = ref(false)

const sortedAgents = computed(() =>
  [...agents.value].sort((a, b) => a.isInternal - b.isInternal || a.id - b.id),
)
const expertCount = computed(() => agents.value.filter((agent) => agent.isInternal === 0).length)
const internalCount = computed(() => agents.value.filter((agent) => agent.isInternal === 1).length)
const totalMembers = computed(() => routes.value.reduce((sum, route) => sum + route.members.length, 0))
const activeRouteAgentCount = computed(() =>
  activeRoute.value ? agents.value.filter((agent) => agent.routeId === activeRoute.value?.id).length : 0,
)

function bindingCount(route: LlmRouteView): number {
  return agents.value.filter((agent) => agent.routeId === route.id).length
}

async function loadAll(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [routeRows, modelRows, agentRows] = await Promise.all([
      api.listLlmRoutes(),
      api.listLlmModels(),
      api.listAdminAgents(),
    ])
    routes.value = routeRows
    models.value = modelRows
    agents.value = agentRows
  } catch (e) {
    error.value = `路由与 Agent 读取失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    loading.value = false
  }
}

function openRoute(route: LlmRouteView): void {
  activeRoute.value = route
  activeAgent.value = null
  creatingRoute.value = false
  modalError.value = ''
  modal.value = 'route'
}

function openCreateRoute(): void {
  activeRoute.value = null
  activeAgent.value = null
  creatingRoute.value = true
  modalError.value = ''
  modal.value = 'route'
}

function openMembers(route: LlmRouteView): void {
  activeRoute.value = route
  activeAgent.value = null
  creatingRoute.value = false
  modalError.value = ''
  modal.value = 'members'
}

function openAgent(agent: AdminAgentView): void {
  activeAgent.value = agent
  activeRoute.value = null
  creatingRoute.value = false
  modalError.value = ''
  modal.value = 'agent'
}

function closeModal(): void {
  if (saving.value) return
  modal.value = null
  modalError.value = ''
}

function replaceRoute(updated: LlmRouteView): void {
  routes.value = routes.value.map((route) => (route.id === updated.id ? updated : route))
}

async function saveRoute(payload: { routeId: number | null; body: LlmRouteUpsert }): Promise<void> {
  saving.value = true
  modalError.value = ''
  try {
    if (payload.routeId === null) {
      const created = await api.createLlmRoute(payload.body)
      routes.value = [...routes.value, created]
      note.value = `已创建路由 ${created.code}`
    } else {
      const updated = await api.updateLlmRoute(payload.routeId, payload.body)
      replaceRoute(updated)
      note.value = `已保存路由 ${updated.code} 的配置`
    }
    modal.value = null
  } catch (e) {
    modalError.value = `保存失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    saving.value = false
  }
}

async function saveMembers(members: LlmRouteMemberUpsert[]): Promise<void> {
  const route = activeRoute.value
  if (!route) return
  saving.value = true
  modalError.value = ''
  try {
    const updated = await api.updateLlmRoute(route.id, {
      code: route.code,
      description: route.description,
      strategy: route.strategy,
      enabled: route.enabled,
      members,
    })
    replaceRoute(updated)
    note.value = `已更新 ${updated.code} 的路由成员`
    modal.value = null
  } catch (e) {
    modalError.value = `保存失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    saving.value = false
  }
}

async function saveAgent(body: AdminAgentUpsert): Promise<void> {
  const agent = activeAgent.value
  if (!agent) return
  saving.value = true
  modalError.value = ''
  try {
    const updated = await api.updateAdminAgent(agent.id, body)
    agents.value = agents.value.map((item) => (item.id === updated.id ? updated : item))
    note.value = `已保存 Agent ${updated.name} 的配置`
    modal.value = null
  } catch (e) {
    modalError.value = `保存失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void loadAll()
})

defineExpose({ reload: loadAll, startCreateRoute: openCreateRoute })
</script>

<template>
  <section class="ra-page" aria-label="路由与 Agent">
    <p v-if="error" class="res-alert" role="alert">{{ error }}</p>
    <p v-else-if="note" class="res-note" role="status">{{ note }}</p>

    <p v-if="loading && routes.length === 0" class="res-loading">正在读取路由与 Agent…</p>

    <template v-else>
      <div class="ra-section-head">
        <div>
          <h3>路由管理</h3>
          <p>{{ routes.length }} 个路由 · {{ totalMembers }} 个成员</p>
        </div>
      </div>

      <div v-if="routes.length" class="ra-grid ra-grid-routes">
        <article v-for="route in routes" :key="route.id" class="ra-card ra-route-card">
          <header class="ra-card-head">
            <span class="ra-mark" aria-hidden="true">{{ routeMark(route.code) }}</span>
            <div class="ra-card-title">
              <span class="ra-name">{{ route.code }}</span>
            </div>
            <span class="ra-status" :class="route.enabled === 1 ? 'is-on' : 'is-off'">
              {{ route.enabled === 1 ? '已启用' : '已停用' }}
            </span>
          </header>

          <p class="ra-card-desc" :title="route.description ?? ''">{{ routeSummary(route.description) }}</p>

          <div class="ra-member-box">
            <span class="ra-member-label">成员</span>
            <div v-if="route.members[0]" class="ra-member-line">
              <span class="ra-member-name" :title="route.members[0].upstreamName">
                {{ route.members[0].upstreamName }}
              </span>
              <span class="ra-priority">P{{ route.members[0].priority }}</span>
            </div>
            <span v-if="route.members.length > 1" class="ra-member-more">+{{ route.members.length - 1 }}</span>
            <span v-else-if="route.members.length === 0" class="ra-member-empty">无成员</span>
          </div>

          <footer class="ra-card-foot">
            <span class="ra-binding">绑定 {{ bindingCount(route) }} 个 Agent</span>
            <div class="ra-card-actions">
              <button class="res-btn res-btn-sm" type="button" @click="openMembers(route)">管理成员</button>
              <button class="res-btn res-btn-sm" type="button" @click="openRoute(route)">配置</button>
            </div>
          </footer>
        </article>
      </div>
      <p v-else class="ra-empty">还没有路由，点右上角「+ 新建路由」创建第一条</p>

      <div class="ra-section-head ra-section-gap">
        <div>
          <h3>Agent 管理</h3>
          <p>{{ agents.length }} 个 Agent · {{ expertCount }} 个专家 · {{ internalCount }} 个内部</p>
        </div>
      </div>

      <div v-if="agents.length" class="ra-grid ra-grid-agents">
        <article v-for="agent in sortedAgents" :key="agent.id" class="ra-card ra-agent-card">
          <header class="ra-card-head">
            <span class="ra-avatar" :class="`is-${agentTone(agent.name)}`">
              <AgentGlyph :name="agent.name" />
            </span>
            <div class="ra-card-title">
              <span class="ra-name">{{ agent.name }}</span>
            </div>
            <span class="ra-route-pill" :class="{ 'is-unbound': !agent.routeCode }">
              {{ agent.routeCode ?? '未绑定' }}
            </span>
          </header>

          <p class="ra-card-desc ra-agent-desc" :title="agent.description ?? ''">
            {{ agent.description ?? '未填写描述' }}
          </p>

          <footer class="ra-card-foot">
            <span class="ra-kind">{{ agent.isInternal === 1 ? '内部' : '专家' }}</span>
            <div class="ra-card-actions">
              <span class="ra-status" :class="agent.status === 1 ? 'is-on' : 'is-off'">
                {{ agent.status === 1 ? '已启用' : '已停用' }}
              </span>
              <button class="res-btn res-btn-sm" type="button" @click="openAgent(agent)">配置</button>
            </div>
          </footer>
        </article>
      </div>
      <p v-else class="ra-empty">还没有 Agent 数据</p>
    </template>

    <RouteConfigModal
      :open="modal === 'route'"
      :route="activeRoute"
      :creating="creatingRoute"
      :models="models"
      :agent-count="activeRouteAgentCount"
      :saving="saving"
      :error="modalError"
      @close="closeModal"
      @save="saveRoute"
    />
    <RouteMembersModal
      :open="modal === 'members'"
      :route="activeRoute"
      :models="models"
      :saving="saving"
      :error="modalError"
      @close="closeModal"
      @save="saveMembers"
    />
    <AgentConfigModal
      :open="modal === 'agent'"
      :agent="activeAgent"
      :routes="routes"
      :saving="saving"
      :error="modalError"
      @close="closeModal"
      @save="saveAgent"
    />
  </section>
</template>
