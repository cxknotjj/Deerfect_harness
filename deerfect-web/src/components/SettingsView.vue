<script setup lang="ts">
/**
 * 设置：界面偏好（主题 / 监控明细折叠）+ 实例信息 + 运维入口。
 *
 * 只放真实可用的开关与只读事实：
 * - 主题由 App.vue 持有（localStorage），这里通过 set-theme 事件改；
 * - 「重置界面偏好」清掉本机两个 localStorage 键（主题、监控明细折叠）；
 * - 实例信息来自已有接口（agents / capabilities / knowledge），任一失败显示 —。
 */
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import PageHeader from './resource/PageHeader.vue'

defineProps<{ theme: 'dark' | 'light' }>()
const emit = defineEmits<{ 'set-theme': [theme: 'dark' | 'light'] }>()

const agents = ref<number | null>(null)
const servers = ref<number | null>(null)
const tools = ref<number | null>(null)
const docs = ref<number | null>(null)
const loading = ref(false)
const error = ref('')
const note = ref('')
/** 监控页三张明细表是否全部收起（只读取，用于展示当前本机偏好） */
const detailsCollapsed = ref(false)

const DETAILS_KEY = 'monitor-details-open'
const THEME_KEY = 'web-theme'

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

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
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
    error.value = '实例信息读取失败：后端不可达'
  }
  loading.value = false
}

onMounted(() => {
  readDetailPref()
  void load()
})

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
  note.value = '本机偏好已重置：主题回到夜间，监控明细恢复展开'
}
</script>

<template>
  <section class="res-page" aria-label="设置">
    <div class="res-inner">
      <PageHeader eyebrow="系统" title="设置" sub="界面偏好、本机状态与实例信息" />

      <p v-if="error" class="res-alert" role="alert">{{ error }}</p>
      <p v-else-if="note" class="res-note" role="status">{{ note }}</p>

      <section class="res-panel" aria-label="外观">
        <div class="res-panel-head">
          <div>
            <h2 class="res-panel-title">外观</h2>
            <p class="res-panel-sub">主题保存在本机 localStorage，不随账号同步</p>
          </div>
        </div>
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
      </section>

      <section class="res-panel" aria-label="本机偏好">
        <div class="res-panel-head">
          <div>
            <h2 class="res-panel-title">本机偏好</h2>
            <p class="res-panel-sub">只影响这个浏览器，不会写回服务端</p>
          </div>
        </div>
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

      <section class="res-panel" aria-label="实例信息">
        <div class="res-panel-head">
          <div>
            <h2 class="res-panel-title">实例信息</h2>
            <p class="res-panel-sub">
              {{ loading ? '读取中…' : '来自正在运行的后端接口；— 表示该项接口不可用' }}
            </p>
          </div>
        </div>
        <div class="settings-stats">
          <div v-for="stat in stats" :key="stat.label" class="res-stat">
            <span class="res-stat-label">{{ stat.label }}</span>
            <span class="res-stat-value">{{ stat.value }}</span>
          </div>
        </div>
      </section>

      <section class="res-panel" aria-label="运维入口">
        <div class="res-panel-head">
          <div>
            <h2 class="res-panel-title">运维入口</h2>
            <p class="res-panel-sub">与监控页同源，直接读运行时指标</p>
          </div>
          <a class="res-btn" href="/actuator/prometheus" target="_blank" rel="noopener">Prometheus 指标 ↗</a>
        </div>
      </section>
    </div>
  </section>
</template>
