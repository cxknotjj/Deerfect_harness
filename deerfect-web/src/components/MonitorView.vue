<script setup lang="ts">
/**
 * 运行监控：全部渠道（Web / CLI / QQ）的时间窗 KPI + 渠道分布 + 每小时调用量 +
 * 活跃会话 + 最近 LLM/工具调用。
 * 数据来自 GET /api/monitor；15 秒自动刷新可关，切换时间窗/Agent/渠道立即重拉。
 * 仅展示观测结果，不提供修改入口。
 *
 * 视图只负责取数与筛选；渲染拆成五个子组件（指标区 / 资源占用 / 分布 / 会话明细 /
 * 调用轨迹），样式统一在 styles/monitor.css（.monitor 作用域）。
 * 排版按控制台参考图：6 张 KPI 一行 → 运行时指标分隔线网格 → 组件状态/资源占用双栏
 * → 渠道分布/每小时调用量双栏 → 明细区（活跃会话 / LLM 调用 / 工具调用，三张表
 * 各自可折叠，另有「全部收起/展开」）→ 「更新于」页脚。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import type { MonitorSnapshot } from '../api'
import { useAgents } from '../composables/useAgents'
import { channelLabel, fmtClock } from '../utils/monitorFormat'
import MonitorSummary from './monitor/MonitorSummary.vue'
import MonitorBreakdown from './monitor/MonitorBreakdown.vue'
import MonitorSessions from './monitor/MonitorSessions.vue'
import MonitorRecentCalls from './monitor/MonitorRecentCalls.vue'

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
  } catch {
    /* 本地偏好损坏时按默认全展开，不阻塞页面 */
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

    <div v-if="error" class="monitor-error" role="alert">{{ error }}</div>

    <template v-if="data">
      <MonitorSummary :data="data" />
      <MonitorBreakdown :data="data" :channel="channel" @filter="channel = $event" />

      <!-- 明细区：三张长表各自可折叠，也可一键全部收起/展开 -->
      <div class="details-bar">
        <span class="details-bar-title">明细数据</span>
        <span class="details-bar-note">活跃会话 · LLM 调用 · 工具调用</span>
        <button
          class="details-toggle-all"
          type="button"
          data-testid="details-toggle-all"
          :aria-expanded="allDetailsOpen"
          @click="toggleAllDetails"
        >{{ allDetailsOpen ? '全部收起' : '全部展开' }}</button>
      </div>

      <MonitorSessions
        :data="data"
        :open="detailsOpen.sessions"
        @toggle="setDetail('sessions', !detailsOpen.sessions)"
      />
      <MonitorRecentCalls
        :data="data"
        :llm-open="detailsOpen.llm"
        :tools-open="detailsOpen.tools"
        @toggle-llm="setDetail('llm', !detailsOpen.llm)"
        @toggle-tools="setDetail('tools', !detailsOpen.tools)"
      />

      <footer class="monitor-foot">
        更新于 {{ updatedAt }} · {{ auto ? '每 15 秒自动刷新' : '自动刷新已关闭' }}
      </footer>
    </template>
  </section>
</template>
