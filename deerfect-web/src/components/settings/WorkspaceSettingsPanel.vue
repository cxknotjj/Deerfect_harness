<script setup lang="ts">
/**
 * 设置·工作区：本地工作区登记、会话归属与协作成员。
 *
 * 后端没有工作区资源（沙箱固定 /workspace），这里的工作区是「浏览器本地的目录登记」：
 *   - 列表与会话归属存 localStorage，只影响 Web 端分组与新会话归属；
 *   - 「打开」把某个工作区设为当前，侧栏工作区名同步刷新；
 *   - 路径仅作标记，不改变服务端实际工作目录（页面底部有说明）。
 *
 * 会话数来自真实会话列表，智能体数来自 /api/harness/agents；接口不可用时显示 —。
 */
import { computed, onMounted, ref } from 'vue'
import { api } from '../../api'
import type { AgentView, SessionView } from '../../api'
import {
  WORKSPACE_DEFAULT_ID,
  activeWorkspaceId,
  createWorkspace,
  readWorkspaces,
  removeWorkspace,
  setActiveWorkspace,
  updateWorkspace,
  workspaceOfSession,
  type WorkspaceRecord,
} from '../../utils/workspaces'

const props = defineProps<{ sessions: SessionView[] }>()
const emit = defineEmits<{ navigate: [page: 'security'] }>()

const list = ref<WorkspaceRecord[]>([])
const activeId = ref(WORKSPACE_DEFAULT_ID)
const agents = ref<AgentView[]>([])
const authEnabled = ref<boolean | null>(null)
const note = ref('')
const error = ref('')

/* ---------- 新建 / 导入 ---------- */
const formMode = ref<'create' | 'import' | null>(null)
const draft = ref({ name: '', path: '' })

/* ---------- 单个工作区的行内设置 ---------- */
const editingId = ref<string | null>(null)
const editDraft = ref({ name: '', path: '', agentIds: [] as number[] })

function reload(): void {
  list.value = readWorkspaces()
  activeId.value = activeWorkspaceId()
}

onMounted(() => {
  reload()
  void loadMeta()
})

async function loadMeta(): Promise<void> {
  const [a, s] = await Promise.allSettled([api.listAgents(), api.authState()])
  if (a.status === 'fulfilled') agents.value = a.value.agents
  if (s.status === 'fulfilled') authEnabled.value = s.value.enabled
}

const visible = computed(() => list.value.filter((w) => !w.archived))
const archived = computed(() => list.value.filter((w) => w.archived))
/** 正在设置的工作区：编辑器放在卡片网格下方，占满整行（卡片里放不下路径输入框） */
const editingRecord = computed(() => list.value.find((w) => w.id === editingId.value) ?? null)

/** 每个工作区的真实会话数：无归属映射的会话统一算默认工作区 */
const sessionCounts = computed(() => {
  const counts = new Map<string, number>()
  for (const s of props.sessions) {
    const id = workspaceOfSession(s.id)
    counts.set(id, (counts.get(id) ?? 0) + 1)
  }
  return counts
})

function sessionCount(id: string): number {
  return sessionCounts.value.get(id) ?? 0
}

/** agentIds 为空 = 全部 Agent；接口读不到时显示 — 而不是假数字 */
function agentCount(w: WorkspaceRecord): string {
  if (w.agentIds.length > 0) return String(w.agentIds.length)
  return agents.value.length > 0 ? String(agents.value.length) : '—'
}

function pathText(w: WorkspaceRecord): string {
  return w.path || '服务端进程目录（Web 端不可见）'
}

/** 卡片字母标：优先取目录名首字母（沙盒实验 → sandbox → S），没有路径再用名称首字 */
function markOf(w: WorkspaceRecord): string {
  const segment = w.path.split(/[\\/]/).filter(Boolean).pop() ?? ''
  const first = segment.trim().charAt(0)
  if (/[a-z]/i.test(first)) return first.toUpperCase()
  return w.name.slice(0, 1).toUpperCase()
}

function clearMessages(): void {
  error.value = ''
  note.value = ''
}

function startCreate(mode: 'create' | 'import'): void {
  clearMessages()
  formMode.value = mode
  editingId.value = null
  draft.value = { name: '', path: '' }
}

function deriveName(path: string): string {
  const parts = path.split(/[\\/]/).filter(Boolean)
  return parts[parts.length - 1] ?? ''
}

function submitCreate(): void {
  clearMessages()
  const path = draft.value.path.trim()
  if (!path) {
    error.value = '本地路径不能为空'
    return
  }
  const mode = formMode.value
  const name = draft.value.name.trim() || deriveName(path) || `工作区 ${list.value.length + 1}`
  createWorkspace(name, path)
  formMode.value = null
  reload()
  note.value = `已${mode === 'import' ? '导入' : '创建'}「${name}」`
}

function openWorkspace(w: WorkspaceRecord): void {
  clearMessages()
  setActiveWorkspace(w.id)
  activeId.value = w.id
  note.value = `已打开「${w.name}」：新会话将归入该工作区`
}

function startEdit(w: WorkspaceRecord): void {
  clearMessages()
  formMode.value = null
  if (editingId.value === w.id) {
    editingId.value = null
    return
  }
  editingId.value = w.id
  editDraft.value = { name: w.name, path: w.path, agentIds: [...w.agentIds] }
}

function toggleAgent(id: number): void {
  const ids = editDraft.value.agentIds
  editDraft.value.agentIds = ids.includes(id) ? ids.filter((n) => n !== id) : [...ids, id]
}

function saveEdit(w: WorkspaceRecord): void {
  clearMessages()
  const name = editDraft.value.name.trim()
  if (!name) {
    error.value = '工作区名称不能为空'
    return
  }
  updateWorkspace(w.id, {
    name,
    path: editDraft.value.path.trim(),
    agentIds: [...editDraft.value.agentIds],
  })
  editingId.value = null
  reload()
  note.value = `已保存「${name}」`
}

function archiveWorkspace(w: WorkspaceRecord): void {
  clearMessages()
  updateWorkspace(w.id, { archived: !w.archived })
  // 归档的正是当前工作区：回落到第一个未归档项，避免侧栏指着已归档的目录
  if (!w.archived && activeId.value === w.id) {
    const fallback = list.value.find((item) => item.id !== w.id && !item.archived)
    if (fallback) setActiveWorkspace(fallback.id)
  }
  if (editingId.value === w.id) editingId.value = null
  reload()
  note.value = w.archived ? `已恢复「${w.name}」` : `「${w.name}」已归档`
}

function removeWs(w: WorkspaceRecord): void {
  if (w.id === WORKSPACE_DEFAULT_ID) {
    error.value = '默认工作区不可删除'
    return
  }
  if (!window.confirm(`删除工作区「${w.name}」？归属它的会话会回落到默认工作区。`)) return
  clearMessages()
  removeWorkspace(w.id)
  editingId.value = null
  reload()
  note.value = `已删除「${w.name}」`
}

defineExpose({ startCreate })
</script>

<template>
  <p v-if="error" class="res-alert" role="alert">{{ error }}</p>
  <p v-else-if="note" class="res-note" role="status">{{ note }}</p>

  <!-- 新建 / 导入工作区 -->
  <form v-if="formMode" class="ws-form" @submit.prevent="submitCreate">
    <div class="ws-form-head">
      <h3>{{ formMode === 'import' ? '导入工作区' : '新建工作区' }}</h3>
      <span>登记在本机浏览器，路径仅作标记</span>
    </div>
    <div class="ws-form-grid">
      <label class="ws-field">
        <span>名称{{ formMode === 'import' ? '（留空取目录名）' : '' }}</span>
        <input
          v-model="draft.name"
          class="res-input"
          :placeholder="formMode === 'import' ? '留空则取路径最后一段' : '如：沙盒实验'"
        />
      </label>
      <label class="ws-field">
        <span>本地路径</span>
        <input v-model="draft.path" class="res-input" placeholder="D:\DoubaWork\sandbox" required />
      </label>
    </div>
    <div class="ws-form-foot">
      <span class="ws-form-hint">只有「打开」的工作区才会接管新会话</span>
      <button class="res-btn" type="button" @click="formMode = null">取消</button>
      <button class="res-btn res-btn-primary" type="submit">
        {{ formMode === 'import' ? '导入' : '创建' }}
      </button>
    </div>
  </form>

  <!-- 本地工作区 -->
  <section class="ws-section">
    <div class="ws-section-head">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M7 18a4 4 0 0 1-.6-7.96A5.5 5.5 0 0 1 17.3 8.5 3.75 3.75 0 0 1 18 18z" />
      </svg>
      <h3>本地工作区</h3>
      <span class="ws-section-note">新会话默认归入最近打开的工作区</span>
    </div>

    <p v-if="visible.length === 0" class="ws-empty">还没有工作区，用右上角「新建工作区」登记一个</p>
    <div v-else class="ws-grid">
      <article
        v-for="w in visible"
        :key="w.id"
        class="ws-card"
        :class="{ 'is-active': w.id === activeId, 'is-editing': w.id === editingId }"
      >
        <header class="ws-card-head">
          <span class="ws-mark" aria-hidden="true">{{ markOf(w) }}</span>
          <span class="ws-name" :title="w.name">{{ w.name }}</span>
          <span class="res-chip" :class="{ 'res-chip-ok': w.id === activeId }">
            {{ w.id === activeId ? '活跃' : '未激活' }}
          </span>
        </header>

        <p class="ws-path res-mono" :title="pathText(w)">{{ pathText(w) }}</p>

        <div class="ws-chips">
          <span class="ws-chip">{{ sessionCount(w.id) }} 个会话</span>
          <span class="ws-chip">{{ agentCount(w) }} 个智能体</span>
        </div>

        <div class="ws-actions">
          <button
            class="res-btn res-btn-sm"
            type="button"
            :disabled="w.id === activeId"
            @click="openWorkspace(w)"
          >打开</button>
          <button class="res-btn res-btn-sm" type="button" @click="startEdit(w)">
            {{ editingId === w.id ? '收起' : '设置' }}
          </button>
          <button class="res-btn res-btn-sm" type="button" @click="archiveWorkspace(w)">归档</button>
        </div>
      </article>
    </div>

    <!-- 行内设置：整行表单，避免 1/3 卡片宽度把路径输入框截断 -->
    <form
      v-if="editingRecord"
      class="ws-form ws-form-edit"
      @submit.prevent="saveEdit(editingRecord)"
    >
      <div class="ws-form-head">
        <h3>设置「{{ editingRecord.name }}」</h3>
        <span>归属 Agent 不选具体项时对全部开放</span>
      </div>
      <div class="ws-form-grid">
        <label class="ws-field">
          <span>名称</span>
          <input v-model="editDraft.name" class="res-input" required />
        </label>
        <label class="ws-field">
          <span>本地路径</span>
          <input v-model="editDraft.path" class="res-input" placeholder="留空表示服务端进程目录" />
        </label>
      </div>

      <div class="ws-agents">
        <span class="ws-field-label">归属 Agent</span>
        <div class="ws-agent-chips">
          <button
            class="ws-agent-chip"
            :class="{ 'is-on': editDraft.agentIds.length === 0 }"
            type="button"
            @click="editDraft.agentIds = []"
          >全部</button>
          <button
            v-for="a in agents"
            :key="a.id"
            class="ws-agent-chip"
            :class="{ 'is-on': editDraft.agentIds.includes(a.id) }"
            type="button"
            @click="toggleAgent(a.id)"
          >{{ a.name }}</button>
        </div>
        <span class="ws-field-hint">不选具体 Agent = 该工作区对全部 Agent 开放</span>
      </div>

      <div class="ws-form-foot">
        <button
          v-if="editingRecord.id !== WORKSPACE_DEFAULT_ID"
          class="ws-danger"
          type="button"
          @click="removeWs(editingRecord)"
        >删除工作区</button>
        <span class="ws-form-hint"></span>
        <button class="res-btn" type="button" @click="editingId = null">取消</button>
        <button class="res-btn res-btn-primary" type="submit">保存</button>
      </div>
    </form>
  </section>

  <!-- 已归档 -->
  <section v-if="archived.length > 0" class="ws-section">
    <div class="ws-section-head">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M4 7h16v13H4zM3 4h18v3H3zM10 12h4" />
      </svg>
      <h3>已归档</h3>
      <span class="ws-section-note">{{ archived.length }} 个 · 不参与新会话归属</span>
    </div>
    <div class="ws-archive">
      <div v-for="w in archived" :key="w.id" class="ws-archive-row">
        <span class="ws-mark is-sm" aria-hidden="true">{{ markOf(w) }}</span>
        <div class="ws-archive-text">
          <span class="ws-archive-name">{{ w.name }}</span>
          <span class="ws-archive-path res-mono">{{ pathText(w) }}</span>
        </div>
        <button class="res-btn res-btn-sm" type="button" @click="archiveWorkspace(w)">恢复</button>
        <button
          v-if="w.id !== WORKSPACE_DEFAULT_ID"
          class="res-btn res-btn-sm"
          type="button"
          @click="removeWs(w)"
        >删除</button>
      </div>
    </div>
  </section>

  <!-- 协作成员 -->
  <section class="ws-section">
    <div class="ws-section-head">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="M16 20v-1.5a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4V20" />
        <circle cx="9.5" cy="7.5" r="3" />
        <path d="M21 20v-1.5a4 4 0 0 0-3-3.87M16.5 4.6a3 3 0 0 1 0 5.8" />
      </svg>
      <h3>协作成员</h3>
      <span class="ws-section-note">{{ authEnabled ? '登录通道已启用' : '登录通道未启用' }}</span>
    </div>

    <div class="ws-members">
      <div class="ws-member">
        <span class="ws-avatar" aria-hidden="true">A</span>
        <div class="ws-member-text">
          <span class="ws-member-name">本地管理员</span>
          <span class="ws-member-role">所有者 · 全部工作区</span>
        </div>
        <button class="res-btn res-btn-sm" type="button" @click="emit('navigate', 'security')">
          管理权限
        </button>
      </div>
    </div>

    <p class="ws-hint">
      {{
        authEnabled
          ? '登录通道已启用，但当前版本仍是单账号实例：成员授权需要接入用户体系，现由本机管理员统一持有。'
          : '实例未启用登录（APP_PASSWORD 未配置），只有本机管理员；多成员协作需要先启用登录通道与用户体系。'
      }}
    </p>
  </section>

  <p class="ws-hint">
    工作区登记保存在本机浏览器：路径仅作标记，服务端沙箱仍固定使用 /workspace；换浏览器或清缓存后需重新登记。
  </p>
</template>
