<script setup lang="ts">
/**
 * 运行监控：全部渠道（Web / CLI / QQ）的时间窗观测视图。
 * 数据来自 GET /api/monitor；15 秒自动刷新可关，切换时间窗/Agent/渠道立即重拉。
 * 仅展示观测结果，不提供修改入口。
 *
 * 2026-11-17 按参考图改为一级 tab：
 *   概览   → 6 KPI + [每小时调用量 | 资源占用] + 渠道分布
 *   运行时 → Micrometer 累计指标（5 列分隔线网格）
 *   组件与资源 → 组件状态 + 资源占用
 *   明细   → 活跃会话 / LLM 调用 / 工具调用（三表可折叠 + 全部收起/展开）
 * tab 选择写 localStorage，刷新/切页后保持；数据仍只拉一次，切 tab 不重请求。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import type { MonitorSnapshot } from '../api'
import { useAgents } from '../composables/useAgents'
import { channelLabel, fmtClock } from '../utils/monitorFormat'
import MonitorKpi from './monitor/MonitorKpi.vue'
import MonitorTrend from './monitor/MonitorTrend.vue'
import MonitorChannels from './monitor/MonitorChannels.vue'
import MonitorRuntime from './monitor/MonitorRuntime.vue'
import MonitorComponents from './monitor/MonitorComponents.vue'
import MonitorResources from './monitor/MonitorResources.vue'
import MonitorDetails from './monitor/MonitorDetails.vue'

type MonitorTab = 'overview' | 'runtime' | 'components' | 'details'
const TABS: { key: MonitorTab; label: string }[] = [
  { key: 'overview', label: '概览' },
  { key: 'runtime', label: '运行时' },
  { key: 'components', label: '组件与资源' },
  { key: 'details', label: '明细' },
]
const TAB_STORAGE_KEY = 'monitor-tab'
const tab = ref<MonitorTab>('overview')

/** 默认 7 天：与控制台参考图同窗口，落地即可看到完整的渠道分布与会话明细 */
const hours = ref(168)
const agent = ref('')
const channel = ref('')
const auto = ref(true)
const loading = ref(false)
const error = ref('')
const data = ref<MonitorSnapshot | null>(null)
/** 最近一次成功拉取的时间（页脚「更新于」；失败不覆盖，避免显示假刷新时间） */
const updatedAt = ref('')
let fetchSeq = 0
let timer: number | undefined

const { agents, load: loadAgents } = useAgents((msg) => {
  error.value = msg
})

const hoursLabel = computed(() => {
  if (hours.value === 1) return '1 小时'
  if (hours.value === 168) return '7 天'
  return `${hours.value} 小时`
})

function pickTab(next: MonitorTab): void {
  tab.value = next
  localStorage.setItem(TAB_STORAGE_KEY, next)
}

/* ---------- 明细区折叠 ----------
   三张长表默认全展开；开关写 localStorage，切页/刷新后仍保留用户的选择。
   「全部收起/展开」把三项一次性拉齐，避免逐个点三次。 */
type DetailKey = 'sessions' | 'llm' | 'tools'
const DETAILS_STORAGE_KEY = 'monitor-details-open'
const detailsOpen = reactive<Record<DetailKey, boolean>>({ sessions: true, llm: true, tools: true })
const allDetailsOpen = computed(
  () => detailsOpen.sessions && detailsOpen.llm && detailsOpen.tools,
)

function saveDetails(): void {
  localStorage.setItem(DETAILS_STORAGE_KEY, JSON.stringify(detailsOpen))
}

function setDetail(key: DetailKey, open: boolean): void {
  detailsOpen[key] = open
  saveDetails()
}

function toggleAllDetails(): void {
  const next = !allDetailsOpen.value
  detailsOpen.sessions = next
  detailsOpen.llm = next
  detailsOpen.tools = next
  saveDetails()
}

/** 明细面板的开关：'all' 走一键收起/展开，其余切换单段 */
function onDetailToggle(key: DetailKey | 'all'): void {
  if (key === 'all') {
    toggleAllDetails()
    return
  }
  setDetail(key, !detailsOpen[key])
}

async function load(): Promise<void> {
  const seq = ++fetchSeq
  loading.value = true
  error.value = ''
  try {
    const resp = await api.monitor(hours.value, agent.value, channel.value, 50)
    if (seq !== fetchSeq) return
    data.value = resp
    updatedAt.value = fmtClock(Date.now())
  } catch (e) {
    if (seq === fetchSeq) {
      error.value = `监控数据加载失败：${e instanceof Error ? e.message : String(e)}`
    }
  } finally {
    if (seq === fetchSeq) loading.value = false
  }
}

function pickHours(value: number): void {
  if (hours.value === value) return
  hours.value = value
}

watch([hours, agent, channel], () => {
  void load()
})

onMounted(() => {
  try {
    const raw = localStorage.getItem(DETAILS_STORAGE_KEY)
    if (raw) {
      const saved = JSON.parse(raw) as Partial<Record<DetailKey, boolean>>
      for (const key of ['sessions', 'llm', 'tools'] as DetailKey[]) {
        if (typeof saved[key] === 'boolean') detailsOpen[key] = saved[key]!
      }
    }
    const savedTab = localStorage.getItem(TAB_STORAGE_KEY)
    if (savedTab && TABS.some((t) => t.key === savedTab)) {
      tab.value = savedTab as MonitorTab
    }
  } catch {
    /* 本地偏好损坏时按默认值，不阻塞页面 */
  }
  void loadAgents()
  void load()
  timer = window.setInterval(() => {
    if (auto.value && !loading.value) void load()
  }, 15_000)
})

onBeforeUnmount(() => {
  if (timer !== undefined) window.clearInterval(timer)
})
</script>

<template>
  <section class="monitor" aria-label="运行监控">
    <header class="monitor-head">
      <div class="monitor-title">
        <h2>运行监控</h2>
        <p class="monitor-sub">
          全部渠道 · 最近 {{ hoursLabel }} · {{ auto ? '每 15 秒自动刷新' : '自动刷新已关闭' }}
          <span v-if="loading" class="monitor-loading">刷新中…</span>
        </p>
      </div>
      <div class="monitor-controls">
        <select v-model="channel" class="ctl" aria-label="渠道过滤">
          <option value="">全部渠道</option>
          <option v-for="c in data?.channels ?? []" :key="c.channel" :value="c.channel">
            {{ channelLabel(c.channel) }}
          </option>
        </select>
        <select v-model="agent" class="ctl" aria-label="Agent 过滤">
          <option value="">全部 Agent</option>
          <option v-for="a in agents" :key="a.id" :value="a.name">{{ a.name }}</option>
        </select>
        <div class="range" role="group" aria-label="时间窗">
          <button
            v-for="opt in [1, 6, 24, 168]"
            :key="opt"
            type="button"
            class="range-btn"
            :class="{ active: hours === opt }"
            @click="pickHours(opt)"
          >{{ opt === 168 ? '7d' : `${opt}h` }}</button>
        </div>
        <label class="auto-toggle">
          <input v-model="auto" type="checkbox" />
          自动
        </label>
        <button type="button" class="ctl refresh" :disabled="loading" @click="load">刷新</button>
      </div>
    </header>

    <div class="monitor-tabs" role="tablist" aria-label="监控视图">
      <button
        v-for="t in TABS"
        :key="t.key"
        class="monitor-tab"
        :class="{ 'is-active': tab === t.key }"
        type="button"
        role="tab"
        :aria-selected="tab === t.key"
        :data-testid="`monitor-tab-${t.key}`"
        @click="pickTab(t.key)"
      >{{ t.label }}</button>
    </div>

    <div v-if="error" class="monitor-error" role="alert">{{ error }}</div>

    <template v-if="data">
      <!-- 概览：KPI → [每小时调用量 | 资源占用] → 渠道分布 -->
      <template v-if="tab === 'overview'">
        <MonitorKpi :data="data" />
        <div class="monitor-split monitor-split-overview">
          <MonitorTrend :data="data" />
          <MonitorResources :data="data" />
        </div>
        <MonitorChannels :data="data" :channel="channel" @filter="channel = $event" />
      </template>

      <!-- 运行时：Micrometer 累计指标 -->
      <MonitorRuntime v-else-if="tab === 'runtime'" :data="data" />

      <!-- 组件与资源：组件状态通栏 + [资源占用 | 探活与连接] -->
      <MonitorComponents v-else-if="tab === 'components'" :data="data" />

      <!-- 明细：统计卡 + 单面板三段折叠 -->
      <MonitorDetails
        v-else
        :data="data"
        :sessions-open="detailsOpen.sessions"
        :llm-open="detailsOpen.llm"
        :tools-open="detailsOpen.tools"
        @toggle="onDetailToggle"
      />

      <footer class="monitor-foot">
        更新于 {{ updatedAt }} · {{ auto ? '每 15 秒自动刷新' : '自动刷新已关闭' }}
      </footer>
    </template>
  </section>
</template>
