<script setup lang="ts">
/**
 * 设置（配置中心）：左侧二级导航 + 右侧内容区。
 *
 * 2026-11-16 按配置中心参考图重排：
 *   模型管理（/api/admin/providers|models，供应商卡片 + 启用/禁用 + 配置 + 自定义）
 *   外观 / 本机偏好 / 实例信息（原有设置项，改为二级 tab）
 *   安全 / 监控 / 技能管理为跳转项（复用全局页面，不在设置内重复实现）
 *
 * 凭证只展示环境变量引用名：后端不落库密钥，web 也不写密钥。
 */
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import type { LlmModelView, LlmProviderUpsert, LlmProviderView } from '../api'

defineProps<{ theme: 'dark' | 'light' }>()
const emit = defineEmits<{
  'set-theme': [theme: 'dark' | 'light']
  navigate: [page: 'security' | 'monitor' | 'skills']
}>()

type TabKey = 'models' | 'appearance' | 'prefs' | 'instance'
type NavKey = TabKey | 'security' | 'monitor' | 'skills'
interface NavItem {
  key: NavKey
  label: string
  icon: string[]
  link?: boolean
}

const NAV_GROUPS: { label: string; items: NavItem[] }[] = [
  {
    label: '配置',
    items: [
      {
        key: 'models',
        label: '模型管理',
        icon: ['M12 3 3 8l9 5 9-5-9-5z', 'M3 8v8l9 5 9-5V8', 'M12 13v8'],
      },
    ],
  },
  {
    label: '界面',
    items: [
      {
        key: 'appearance',
        label: '外观',
        icon: ['M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z', 'M12 2v2M12 20v2M2 12h2M20 12h2'],
      },
      {
        key: 'prefs',
        label: '本机偏好',
        icon: ['M4 8h16M4 16h16', 'M9 8a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z', 'M15 19a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z'],
      },
    ],
  },
  {
    label: '实例',
    items: [
      {
        key: 'instance',
        label: '实例信息',
        icon: ['M4 5h16v6H4z', 'M4 13h16v6H4z', 'M7 8h.01M7 16h.01'],
      },
    ],
  },
  {
    label: '快捷入口',
    items: [
      { key: 'security', label: '安全', icon: ['M12 3 4.5 6v6c0 4.5 3.2 7.6 7.5 9 4.3-1.4 7.5-4.5 7.5-9V6z'], link: true },
      { key: 'monitor', label: '监控', icon: ['M3 20h18', 'M6 20v-7', 'M11 20V6', 'M16 20v-4', 'M21 20V9'], link: true },
      { key: 'skills', label: '技能管理', icon: ['M13.5 2.5 5 13.5h5.5L10 21.5 19 10h-5.5z'], link: true },
    ],
  },
]

const TABS: Record<TabKey, { title: string; sub: string }> = {
  models: { title: '模型管理', sub: '配置模型提供商、凭证引用和模型列表' },
  appearance: { title: '外观', sub: '主题保存在本机 localStorage，不随账号同步' },
  prefs: { title: '本机偏好', sub: '只影响这个浏览器，不会写回服务端' },
  instance: { title: '实例信息', sub: '来自正在运行的后端接口，— 表示该项不可用' },
}

const tab = ref<TabKey>('models')
const current = computed(() => TABS[tab.value])

function onNav(item: NavItem): void {
  if (item.link) {
    emit('navigate', item.key as 'security' | 'monitor' | 'skills')
    return
  }
  tab.value = item.key as TabKey
}

/* ---------- 模型管理 ---------- */
const providers = ref<LlmProviderView[]>([])
const models = ref<LlmModelView[]>([])
const llmLoading = ref(false)
const llmError = ref('')
const actionNote = ref('')
const actionError = ref('')
const busyId = ref<number | null>(null)
const expandedId = ref<number | null>(null)
const editingId = ref<number | null>(null)
const creating = ref(false)

interface ProviderDraft {
  code: string
  baseUrl: string
  credentialRef: string
  protocol: string
  enabled: number
}
function emptyDraft(): ProviderDraft {
  return { code: '', baseUrl: '', credentialRef: '', protocol: 'openai', enabled: 1 }
}
const draft = ref<ProviderDraft>(emptyDraft())
const editDraft = ref<ProviderDraft>(emptyDraft())

/** providerId → 模型列表（一次分组，避免模板里反复 filter） */
const modelsByProvider = computed(() => {
  const map = new Map<number, LlmModelView[]>()
  for (const m of models.value) {
    const list = map.get(m.providerId)
    if (list) list.push(m)
    else map.set(m.providerId, [m])
  }
  return map
})

function modelsOf(p: LlmProviderView): LlmModelView[] {
  return modelsByProvider.value.get(p.id) ?? []
}

function enabledModelCount(p: LlmProviderView): number {
  return modelsOf(p).filter((m) => m.enabled === 1).length
}

async function loadLlm(): Promise<void> {
  llmLoading.value = true
  llmError.value = ''
  try {
    const [p, m] = await Promise.all([api.listLlmProviders(), api.listLlmModels()])
    providers.value = p
    models.value = m
  } catch (e) {
    llmError.value = `模型配置读取失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    llmLoading.value = false
  }
}

function startCreate(): void {
  creating.value = true
  editingId.value = null
  actionError.value = ''
  draft.value = emptyDraft()
}

function startEdit(p: LlmProviderView): void {
  editingId.value = editingId.value === p.id ? null : p.id
  creating.value = false
  actionError.value = ''
  editDraft.value = {
    code: p.code,
    baseUrl: p.baseUrl,
    credentialRef: p.credentialRef,
    protocol: p.protocol,
    enabled: p.enabled,
  }
}

async function submitCreate(): Promise<void> {
  const body = draft.value
  if (!body.code.trim() || !body.baseUrl.trim() || !body.credentialRef.trim()) {
    actionError.value = 'code / Base URL / 凭证引用名都不能为空'
    return
  }
  actionError.value = ''
  try {
    const created = await api.createLlmProvider({ ...body, code: body.code.trim() })
    providers.value = [...providers.value, created]
    creating.value = false
    actionNote.value = `已创建提供商 ${created.code}`
  } catch (e) {
    actionError.value = `创建失败：${e instanceof Error ? e.message : String(e)}`
  }
}

async function submitEdit(p: LlmProviderView): Promise<void> {
  actionError.value = ''
  try {
    const updated = await api.updateLlmProvider(p.id, { ...editDraft.value })
    providers.value = providers.value.map((item) => (item.id === p.id ? updated : item))
    editingId.value = null
    actionNote.value = `已保存 ${updated.code} 的配置`
  } catch (e) {
    actionError.value = `保存失败：${e instanceof Error ? e.message : String(e)}`
  }
}

async function toggleProvider(p: LlmProviderView): Promise<void> {
  if (busyId.value !== null) return
  busyId.value = p.id
  actionError.value = ''
  const body: LlmProviderUpsert = {
    code: p.code,
    baseUrl: p.baseUrl,
    credentialRef: p.credentialRef,
    protocol: p.protocol,
    enabled: p.enabled === 1 ? 0 : 1,
  }
  try {
    const updated = await api.updateLlmProvider(p.id, body)
    providers.value = providers.value.map((item) => (item.id === p.id ? updated : item))
    actionNote.value = `${updated.code} 已${updated.enabled === 1 ? '启用' : '禁用'}`
  } catch (e) {
    actionError.value = `切换状态失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    busyId.value = null
  }
}

/* ---------- 外观 / 本机偏好 / 实例信息 ---------- */
const DETAILS_KEY = 'monitor-details-open'
const THEME_KEY = 'web-theme'
const detailsCollapsed = ref(false)

function readDetailPref(): void {
  try {
    const raw = localStorage.getItem(DETAILS_KEY)
    if (!raw) {
      detailsCollapsed.value = false
      return
    }
    const saved = JSON.parse(raw) as Record<string, boolean>
    detailsCollapsed.value = saved.sessions === false && saved.llm === false && saved.tools === false
  } catch {
    detailsCollapsed.value = false
  }
}

const agents = ref<number | null>(null)
const servers = ref<number | null>(null)
const tools = ref<number | null>(null)
const docs = ref<number | null>(null)
const instanceLoading = ref(false)
const instanceError = ref('')

async function loadInstance(): Promise<void> {
  instanceLoading.value = true
  instanceError.value = ''
  const [a, c, k] = await Promise.allSettled([
    api.listAgents(),
    api.capabilities(),
    api.listKbDocuments(1, 1),
  ])
  agents.value = a.status === 'fulfilled' ? a.value.agents.length : null
  if (c.status === 'fulfilled') {
    servers.value = c.value.entries.filter((e) => e.source.startsWith('mcp')).length
    tools.value = c.value.totalTools
  }
  docs.value = k.status === 'fulfilled' ? k.value.total : null
  if (a.status === 'rejected' && c.status === 'rejected' && k.status === 'rejected') {
    instanceError.value = '实例信息读取失败：后端不可达'
  }
  instanceLoading.value = false
}

const stats = computed(() => [
  { label: 'Agent', value: agents.value == null ? '—' : String(agents.value) },
  { label: 'MCP Server', value: servers.value == null ? '—' : String(servers.value) },
  { label: '已发现工具', value: tools.value == null ? '—' : String(tools.value) },
  { label: '知识库文档', value: docs.value == null ? '—' : String(docs.value) },
])

function resetPrefs(): void {
  localStorage.removeItem(THEME_KEY)
  localStorage.removeItem(DETAILS_KEY)
  emit('set-theme', 'dark')
  readDetailPref()
  actionNote.value = '本机偏好已重置：主题回到夜间，监控明细恢复展开'
}

onMounted(() => {
  readDetailPref()
  void loadLlm()
  void loadInstance()
})
</script>

<template>
  <section class="settings-page" aria-label="设置">
    <aside class="settings-nav">
      <span class="settings-nav-eyebrow">配置中心</span>
      <h1 class="settings-nav-title">设置</h1>
      <nav class="settings-nav-body">
        <div v-for="group in NAV_GROUPS" :key="group.label" class="settings-nav-group">
          <div class="settings-nav-label">{{ group.label }}</div>
          <button
            v-for="item in group.items"
            :key="item.key"
            class="settings-nav-item"
            :class="{ 'is-active': !item.link && tab === item.key }"
            type="button"
            :data-testid="`settings-nav-${item.key}`"
            @click="onNav(item)"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path v-for="d in item.icon" :key="d" :d="d" />
            </svg>
            <span>{{ item.label }}</span>
            <span v-if="item.link" class="settings-nav-arrow" aria-hidden="true">↗</span>
          </button>
        </div>
      </nav>
    </aside>

    <div class="settings-main">
      <header class="settings-main-head">
        <div>
          <h2>{{ current.title }}</h2>
          <p>{{ current.sub }}</p>
        </div>
        <button
          v-if="tab === 'models'"
          class="res-btn"
          type="button"
          :disabled="llmLoading"
          @click="loadLlm"
        >{{ llmLoading ? '读取中…' : '刷新' }}</button>
      </header>

      <p v-if="tab === 'models' && llmError" class="res-alert" role="alert">{{ llmError }}</p>
      <p v-else-if="actionError" class="res-alert" role="alert">{{ actionError }}</p>
      <p v-else-if="actionNote" class="res-note" role="status">{{ actionNote }}</p>

      <!-- 模型管理 -->
      <template v-if="tab === 'models'">
        <div class="provider-head">
          <div>
            <h3>云端模型</h3>
            <p>{{ providers.length }} 个提供商 · {{ models.length }} 个模型</p>
          </div>
          <div class="provider-head-actions">
            <button class="res-btn" type="button" @click="startCreate">+ 自定义提供商</button>
          </div>
        </div>

        <form v-if="creating" class="provider-form provider-form-create" @submit.prevent="submitCreate">
          <div class="provider-form-grid">
            <label class="provider-field">
              <span>code</span>
              <input v-model="draft.code" class="res-input" placeholder="唯一标识，如 moonshot" required />
            </label>
            <label class="provider-field">
              <span>Base URL</span>
              <input v-model="draft.baseUrl" class="res-input" placeholder="https://api.example.com/v1" required />
            </label>
            <label class="provider-field">
              <span>凭证引用（环境变量名）</span>
              <input v-model="draft.credentialRef" class="res-input" placeholder="MOONSHOT_API_KEY" required />
            </label>
            <label class="provider-field">
              <span>协议</span>
              <select v-model="draft.protocol" class="res-input">
                <option value="openai">openai</option>
                <option value="anthropic">anthropic</option>
              </select>
            </label>
          </div>
          <div class="provider-form-foot">
            <span class="provider-form-hint">密钥只从该环境变量读取，不落库、不回传</span>
            <button class="res-btn" type="button" @click="creating = false">取消</button>
            <button class="res-btn res-btn-primary" type="submit">创建提供商</button>
          </div>
        </form>

        <p v-if="llmLoading && providers.length === 0" class="res-loading">正在读取模型提供商…</p>
        <div v-else class="provider-grid">
          <article v-for="p in providers" :key="p.id" class="provider-card">
            <header class="provider-card-head">
              <span class="provider-mark" aria-hidden="true">{{ p.code.slice(0, 1).toUpperCase() }}</span>
              <div class="provider-title">
                <span class="provider-name">{{ p.code }}</span>
                <span class="provider-code">{{ p.protocol }} · #{{ p.id }}</span>
              </div>
              <span class="res-chip" :class="p.enabled === 1 ? 'res-chip-ok' : ''">
                {{ p.enabled === 1 ? '已启用' : '已禁用' }}
              </span>
            </header>

            <p class="provider-count">
              可用 {{ enabledModelCount(p) }} 个模型
              <span class="provider-count-total">/ 共 {{ modelsOf(p).length }} 个</span>
            </p>

            <div class="provider-cred">
              <span class="provider-cred-label">凭证引用</span>
              <code class="provider-cred-value">{{ p.credentialRef }}</code>
              <span class="provider-cred-note">密钥由环境变量提供，仅存服务端</span>
            </div>

            <div class="provider-meta">
              <span class="provider-meta-key">Base URL</span>
              <span class="res-mono" :title="p.baseUrl">{{ p.baseUrl }}</span>
            </div>

            <div class="provider-actions">
              <button class="res-btn res-btn-sm" type="button" @click="expandedId = expandedId === p.id ? null : p.id">
                {{ expandedId === p.id ? '收起模型' : '管理模型' }}
              </button>
              <button class="res-btn res-btn-sm" type="button" @click="startEdit(p)">配置</button>
              <button
                class="res-btn res-btn-sm"
                type="button"
                :disabled="busyId === p.id"
                @click="toggleProvider(p)"
              >{{ p.enabled === 1 ? '禁用' : '启用' }}</button>
            </div>

            <div v-if="expandedId === p.id" class="provider-models">
              <div v-for="m in modelsOf(p)" :key="m.id" class="provider-model">
                <span class="provider-model-dot" :class="{ 'is-off': m.enabled !== 1 }" aria-hidden="true"></span>
                <span class="res-mono">{{ m.displayName }}</span>
                <span v-if="m.upstreamName !== m.displayName" class="provider-model-up">{{ m.upstreamName }}</span>
              </div>
              <p v-if="modelsOf(p).length === 0" class="provider-empty">该提供商下还没有模型</p>
            </div>

            <form v-if="editingId === p.id" class="provider-form" @submit.prevent="submitEdit(p)">
              <div class="provider-form-grid">
                <label class="provider-field">
                  <span>Base URL</span>
                  <input v-model="editDraft.baseUrl" class="res-input" required />
                </label>
                <label class="provider-field">
                  <span>凭证引用</span>
                  <input v-model="editDraft.credentialRef" class="res-input" required />
                </label>
                <label class="provider-field">
                  <span>协议</span>
                  <select v-model="editDraft.protocol" class="res-input">
                    <option value="openai">openai</option>
                    <option value="anthropic">anthropic</option>
                  </select>
                </label>
                <label class="provider-field">
                  <span>状态</span>
                  <select v-model.number="editDraft.enabled" class="res-input">
                    <option :value="1">启用</option>
                    <option :value="0">禁用</option>
                  </select>
                </label>
              </div>
              <div class="provider-form-foot">
                <button class="res-btn" type="button" @click="editingId = null">取消</button>
                <button class="res-btn res-btn-primary" type="submit">保存</button>
              </div>
            </form>
          </article>
        </div>
      </template>

      <!-- 外观 -->
      <template v-else-if="tab === 'appearance'">
        <div class="settings-theme">
          <button
            class="settings-theme-card"
            :class="{ 'is-active': theme === 'light' }"
            type="button"
            :aria-pressed="theme === 'light'"
            @click="emit('set-theme', 'light')"
          >
            <span class="settings-swatch is-light" aria-hidden="true"><i></i><i></i><i></i></span>
            <span class="settings-theme-name">日间</span>
            <span class="settings-theme-desc">蓝白画布，适合白天与投影</span>
            <span v-if="theme === 'light'" class="res-chip res-chip-accent">当前</span>
          </button>
          <button
            class="settings-theme-card"
            :class="{ 'is-active': theme === 'dark' }"
            type="button"
            :aria-pressed="theme === 'dark'"
            @click="emit('set-theme', 'dark')"
          >
            <span class="settings-swatch is-dark" aria-hidden="true"><i></i><i></i><i></i></span>
            <span class="settings-theme-name">夜间</span>
            <span class="settings-theme-desc">深蓝黑 + 图纸网格，适合长时间盯屏</span>
            <span v-if="theme === 'dark'" class="res-chip res-chip-accent">当前</span>
          </button>
        </div>
      </template>

      <!-- 本机偏好 -->
      <template v-else-if="tab === 'prefs'">
        <section class="res-panel" aria-label="本机偏好">
          <div class="settings-rows">
            <div class="settings-row">
              <div class="settings-row-text">
                <span class="settings-row-title">监控明细区</span>
                <span class="settings-row-desc">活跃会话 / LLM 调用 / 工具调用三张表的折叠状态</span>
              </div>
              <span class="res-chip">{{ detailsCollapsed ? '全部收起' : '展开' }}</span>
            </div>
            <div class="settings-row">
              <div class="settings-row-text">
                <span class="settings-row-title">重置界面偏好</span>
                <span class="settings-row-desc">清除主题与监控折叠记录，恢复默认（夜间 + 展开）</span>
              </div>
              <button class="res-btn" type="button" @click="resetPrefs">重置</button>
            </div>
          </div>
        </section>
      </template>

      <!-- 实例信息 -->
      <template v-else>
        <p v-if="instanceError" class="res-alert" role="alert">{{ instanceError }}</p>
        <div class="settings-stats">
          <div v-for="stat in stats" :key="stat.label" class="res-stat">
            <span class="res-stat-label">{{ stat.label }}</span>
            <span class="res-stat-value">{{ stat.value }}</span>
          </div>
        </div>
        <section class="res-panel" aria-label="运维入口">
          <div class="res-panel-head">
            <div>
              <h3 class="res-panel-title">运维入口</h3>
              <p class="res-panel-sub">
                {{ instanceLoading ? '读取中…' : '与监控页同源，直接读运行时指标' }}
              </p>
            </div>
            <a class="res-btn" href="/actuator/prometheus" target="_blank" rel="noopener">Prometheus 指标 ↗</a>
          </div>
        </section>
      </template>
    </div>
  </section>
</template>
