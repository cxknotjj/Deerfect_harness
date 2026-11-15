<script setup lang="ts">
/**
 * 仪表盘：运行脉搏（页头 + 今日概览）→ 4 张统计卡 → 近 7 天趋势 → 周期对比。
 *
 * 数据全部来自 GET /api/monitor（后端窗口上限 168h），一次并发拉三个窗口：
 * 168h 供统计卡与趋势、24h 供「今日」卡、1h 供短周期对比。
 * 只读视图，不做写操作。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api'
import type { MonitorSnapshot } from '../api'
import { fmtNumber, fmtPercent } from '../utils/monitorFormat'
import StatCard from './dashboard/StatCard.vue'
import TrendChart from './dashboard/TrendChart.vue'

interface TrendPoint {
  label: string
  calls: number
  tokens: number
}

const week = ref<MonitorSnapshot | null>(null)
const day = ref<MonitorSnapshot | null>(null)
const hour = ref<MonitorSnapshot | null>(null)
const loading = ref(false)
const error = ref('')
let timer: number | undefined

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [w, d, h] = await Promise.all([
      api.monitor(168, '', '', 50),
      api.monitor(24, '', '', 50),
      api.monitor(1, '', '', 50),
    ])
    week.value = w
    day.value = d
    hour.value = h
  } catch (e) {
    error.value = `仪表盘数据加载失败：${e instanceof Error ? e.message : String(e)}`
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
  // 与监控页同源数据：低频刷新即可，避免仪表盘常驻时反复打接口
  timer = window.setInterval(() => {
    if (!loading.value) void load()
  }, 60_000)
})

onBeforeUnmount(() => {
  if (timer !== undefined) window.clearInterval(timer)
})

/** 近 7 天日期键（MM-dd），与后端小时桶前缀同格式 */
function lastSevenDayKeys(): string[] {
  const keys: string[] = []
  const now = new Date()
  for (let i = 6; i >= 0; i -= 1) {
    const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - i)
    keys.push(`${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`)
  }
  return keys
}

/** 小时桶按天聚合；缺数据的天补 0，保证折线永远是 7 个点 */
const trendPoints = computed<TrendPoint[]>(() => {
  const keys = lastSevenDayKeys()
  const buckets = new Map(keys.map((k) => [k, { label: k, calls: 0, tokens: 0 }]))
  for (const row of week.value?.hourly ?? []) {
    const key = row.bucket.slice(0, 5)
    const target = buckets.get(key)
    if (target) {
      target.calls += row.calls
      target.tokens += row.tokens
    }
  }
  return keys.map((k) => buckets.get(k)!)
})

const cards = computed(() => {
  const s = week.value?.summary
  return [
    {
      icon: 'sessions' as const,
      label: '活跃会话',
      value: fmtNumber(week.value?.sessions.length ?? 0),
      note: '近 7 天',
    },
    { icon: 'calls' as const, label: 'LLM 调用', value: fmtNumber(s?.calls ?? 0), note: '近 7 天' },
    { icon: 'tools' as const, label: '工具调用', value: fmtNumber(s?.toolCalls ?? 0), note: '近 7 天' },
    { icon: 'tokens' as const, label: '令牌用量', value: fmtNumber(s?.totalTokens ?? 0), note: '近 7 天' },
  ]
})

const todayCalls = computed(() => day.value?.summary.calls ?? 0)
const todayTokens = computed(() => day.value?.summary.totalTokens ?? 0)

const periods = computed(() => [
  { title: '近 1 小时', data: hour.value },
  { title: '近 24 小时', data: day.value },
  { title: '近 7 天', data: week.value },
])

function periodRows(data: MonitorSnapshot | null): { label: string; value: string }[] {
  return [
    { label: 'LLM 调用', value: fmtNumber(data?.summary.calls ?? 0) },
    { label: '令牌用量', value: fmtNumber(data?.summary.totalTokens ?? 0) },
    { label: '工具调用', value: fmtNumber(data?.summary.toolCalls ?? 0) },
    { label: '错误率', value: data ? fmtPercent(data.summary.errorRate) : '—' },
  ]
}
</script>

<template>
  <section class="dashboard" aria-label="仪表盘">
    <div class="dashboard-inner">
      <header class="dash-head">
        <div>
          <span class="dash-eyebrow">运行脉搏</span>
          <h1 class="dash-title">仪表盘</h1>
          <p class="dash-sub">系统使用概览与运行状态</p>
        </div>
        <div class="dash-today">
          <div class="dash-today-label">今日</div>
          <div class="dash-today-value">{{ fmtNumber(todayCalls) }}</div>
          <div class="dash-today-note">{{ fmtNumber(todayTokens) }} tokens</div>
        </div>
      </header>

      <p v-if="error" class="dash-note dash-note-error" role="alert">{{ error }}</p>
      <p v-else-if="!week && loading" class="dash-note">正在加载运行数据…</p>

      <div class="dash-stats">
        <StatCard
          v-for="card in cards"
          :key="card.label"
          :icon="card.icon"
          :label="card.label"
          :value="card.value"
          :note="card.note"
        />
      </div>

      <section class="dash-panel" aria-label="本周概览">
        <div class="panel-head">
          <div>
            <h2 class="panel-title">本周概览</h2>
            <p class="panel-sub">按天聚合的调用与令牌用量</p>
          </div>
          <div class="chart-legend">
            <span class="legend-item"><i class="legend-swatch legend-swatch-calls"></i>调用（次）</span>
            <span class="legend-item"><i class="legend-swatch legend-swatch-tokens"></i>令牌（k）</span>
          </div>
        </div>
        <TrendChart :points="trendPoints" />
      </section>

      <section aria-label="周期对比">
        <div class="panel-head" style="margin-bottom: 10px">
          <div>
            <h2 class="panel-title">周期对比</h2>
            <p class="panel-sub">对比不同时间窗内的调用与消耗</p>
          </div>
        </div>
        <div class="period-grid">
          <article v-for="p in periods" :key="p.title" class="period-card">
            <div class="period-title">{{ p.title }}</div>
            <div v-for="row in periodRows(p.data)" :key="row.label" class="period-row">
              <span class="period-label">{{ row.label }}</span>
              <span class="period-value">{{ row.value }}</span>
            </div>
          </article>
        </div>
      </section>
    </div>
  </section>
</template>
