<script setup lang="ts">
/**
 * 运行监控：全部渠道（Web / CLI / QQ）的时间窗 KPI + 渠道分布 + 每小时调用量 +
 * 活跃会话 + 最近 LLM/工具调用。
 * 数据来自 GET /api/monitor；15 秒自动刷新可关，切换时间窗/Agent/渠道立即重拉。
 * 仅展示观测结果，不提供修改入口。
 *
 * 视图只负责取数与筛选；渲染拆成四个子组件（指标卡 / 分布 / 会话明细 / 调用轨迹），
 * 样式统一在 styles/monitor.css（.monitor 作用域）。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import type { MonitorSnapshot } from '../api'
import { useAgents } from '../composables/useAgents'
import { channelLabel } from '../utils/monitorFormat'
import MonitorSummary from './monitor/MonitorSummary.vue'
import MonitorBreakdown from './monitor/MonitorBreakdown.vue'
import MonitorSessions from './monitor/MonitorSessions.vue'
import MonitorRecentCalls from './monitor/MonitorRecentCalls.vue'

const hours = ref(24)
const agent = ref('')
const channel = ref('')
const auto = ref(true)
const loading = ref(false)
const error = ref('')
const data = ref<MonitorSnapshot | null>(null)
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

async function load(): Promise<void> {
  const seq = ++fetchSeq
  loading.value = true
  error.value = ''
  try {
    const resp = await api.monitor(hours.value, agent.value, channel.value, 50)
    if (seq !== fetchSeq) return
    data.value = resp
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
          全部渠道 · 最近 {{ hoursLabel }} · {{ auto ? '每 15s 自动刷新' : '自动刷新已关闭' }}
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
      <MonitorSessions :data="data" />
      <MonitorRecentCalls :data="data" />
    </template>
  </section>
</template>
