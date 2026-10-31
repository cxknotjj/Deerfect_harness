<script setup lang="ts">
/**
 * 运行监控：全部渠道（Web / CLI / QQ）的时间窗 KPI + 渠道分布 + 每小时调用量 +
 * 活跃会话 + 最近 LLM/工具调用。
 * 数据来自 GET /api/monitor；15 秒自动刷新可关，切换时间窗/Agent/渠道立即重拉。
 * 仅展示观测结果，不提供修改入口。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import type { MonitorSnapshot, MonitorTimerStats } from '../api'
import { useAgents } from '../composables/useAgents'

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

const maxHourlyCalls = computed(() =>
  Math.max(1, ...(data.value?.hourly.map((h) => h.calls) ?? [0])),
)

function barHeight(calls: number): string {
  return `${Math.max(2, Math.round((calls / maxHourlyCalls.value) * 100))}%`
}

function labelStep(): number {
  const total = data.value?.hourly.length ?? 0
  return total > 12 ? Math.ceil(total / 8) : 1
}

function fmtMs(value: number | null | undefined): string {
  if (value == null || value < 0) return '—'
  return value >= 1000 ? `${(value / 1000).toFixed(1)}s` : `${value}ms`
}

function fmtNumber(value: number | null | undefined): string {
  return value == null ? '—' : value.toLocaleString('zh-CN')
}

function fmtPercent(value: number): string {
  return `${(value * 100).toFixed(1)}%`
}

/** 字节 → 紧凑容量：187MB / 4.0GB */
function fmtBytes(bytes: number | null | undefined): string {
  if (bytes == null || bytes <= 0) return '—'
  const gb = bytes / 1024 ** 3
  return gb >= 1 ? `${gb.toFixed(1)}GB` : `${Math.round(bytes / 1024 ** 2)}MB`
}

/** 秒 → 紧凑运行时长：35s / 12m / 2h15m / 5d4h */
function fmtUptime(seconds: number | null | undefined): string {
  if (seconds == null || seconds <= 0) return '—'
  const total = Math.floor(seconds)
  if (total < 60) return `${total}s`
  if (total < 3600) return `${Math.floor(total / 60)}m`
  if (total < 86400) return `${Math.floor(total / 3600)}h${Math.floor((total % 3600) / 60)}m`
  return `${Math.floor(total / 86400)}d${Math.floor((total % 86400) / 3600)}h`
}

/** 计时器累计文案：次数 + 均值/最大；无样本时明确提示，避免显示 0ms 误导 */
function fmtTimer(stats: MonitorTimerStats | null | undefined): string {
  if (!stats || stats.count === 0) return '暂无样本'
  return `${fmtNumber(stats.count)} 次 · 均值 ${fmtMs(stats.avgMs)} · 最大 ${fmtMs(stats.maxMs)}`
}

function fmtTime(value: string | null | undefined): string {
  if (!value) return '—'
  const text = value.replace('T', ' ')
  return text.length >= 16 ? text.slice(5, 16) : text
}

function statusClass(status: string | null | undefined): string {
  return status === 'OK' ? 'status-ok' : 'status-bad'
}

/** 渠道键 → 展示名；后端已把 anonymous/web 归并为 web，未知会话为 unknown */
function channelLabel(key: string | null | undefined): string {
  switch (key) {
    case 'qq':
      return 'QQ'
    case 'cli':
      return 'CLI'
    case 'web':
      return 'Web'
    case 'unknown':
    case '':
    case null:
    case undefined:
      return '未知'
    default:
      return key
  }
}
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

    <div v-if="data" class="kpi-grid">
      <article class="kpi">
        <span class="kpi-label">LLM 调用</span>
        <strong class="kpi-value">{{ fmtNumber(data.summary.calls) }}</strong>
        <span class="kpi-note">{{ fmtNumber(data.summary.totalTokens) }} tokens</span>
      </article>
      <article class="kpi">
        <span class="kpi-label">错误率</span>
        <strong class="kpi-value" :class="{ 'kpi-bad': data.summary.errors > 0 }">
          {{ fmtPercent(data.summary.errorRate) }}
        </strong>
        <span class="kpi-note">{{ fmtNumber(data.summary.errors) }} 次失败</span>
      </article>
      <article class="kpi">
        <span class="kpi-label">首 token P95</span>
        <strong class="kpi-value">{{ fmtMs(data.summary.p95TtftMs) }}</strong>
        <span class="kpi-note">均值 {{ fmtMs(data.summary.avgTtftMs) }}</span>
      </article>
      <article class="kpi">
        <span class="kpi-label">单次耗时 P95</span>
        <strong class="kpi-value">{{ fmtMs(data.summary.p95DurationMs) }}</strong>
        <span class="kpi-note">均值 {{ fmtMs(data.summary.avgDurationMs) }}</span>
      </article>
      <article class="kpi">
        <span class="kpi-label">Token 用量</span>
        <strong class="kpi-value">{{ fmtNumber(data.summary.totalTokens) }}</strong>
        <span class="kpi-note">
          prompt {{ fmtNumber(data.summary.promptTokens) }} / completion
          {{ fmtNumber(data.summary.completionTokens) }}
        </span>
      </article>
      <article class="kpi">
        <span class="kpi-label">工具调用</span>
        <strong class="kpi-value">{{ fmtNumber(data.summary.toolCalls) }}</strong>
        <span class="kpi-note">{{ fmtNumber(data.summary.toolErrors) }} 次失败</span>
      </article>
    </div>

    <section v-if="data" class="panel">
      <div class="panel-head">
        <h3>运行时指标</h3>
        <span class="panel-note">
          Micrometer · 进程启动以来累计，重启清零
          <a class="prom-link" href="/actuator/prometheus" target="_blank" rel="noopener">原始指标 ↗</a>
        </span>
      </div>
      <div class="runtime-grid">
        <div class="runtime-item">
          <span class="runtime-label">SSE 活跃连接 <code>harness_sse_active</code></span>
          <strong class="runtime-value">{{ fmtNumber(data.runtime.sseActive) }}</strong>
          <span class="runtime-note">当前打开中的流式连接</span>
        </div>
        <div class="runtime-item">
          <span class="runtime-label">LLM 累计调用 <code>harness_llm_calls_total</code></span>
          <strong class="runtime-value">{{ fmtNumber(data.runtime.llmCalls) }}</strong>
          <span class="runtime-note" :class="{ 'cell-bad': data.runtime.llmErrors > 0 }">
            错误 {{ fmtNumber(data.runtime.llmErrors) }}
          </span>
        </div>
        <div class="runtime-item">
          <span class="runtime-label">累计 Tokens <code>harness_llm_tokens_total</code></span>
          <strong class="runtime-value">{{ fmtNumber(data.runtime.totalTokens) }}</strong>
          <span class="runtime-note">
            prompt {{ fmtNumber(data.runtime.promptTokens) }} / completion {{ fmtNumber(data.runtime.completionTokens) }}
          </span>
        </div>
        <div class="runtime-item">
          <span class="runtime-label">首 token 计时 <code>harness_llm_first_token_seconds</code></span>
          <strong class="runtime-value">
            {{ data.runtime.firstToken.count > 0 ? fmtMs(data.runtime.firstToken.avgMs) : '—' }}
          </strong>
          <span class="runtime-note">{{ fmtTimer(data.runtime.firstToken) }}</span>
        </div>
        <div class="runtime-item">
          <span class="runtime-label">调用耗时计时 <code>harness_llm_duration_seconds</code></span>
          <strong class="runtime-value">
            {{ data.runtime.llmDuration.count > 0 ? fmtMs(data.runtime.llmDuration.avgMs) : '—' }}
          </strong>
          <span class="runtime-note">{{ fmtTimer(data.runtime.llmDuration) }}</span>
        </div>
        <div class="runtime-item">
          <span class="runtime-label">路径决策 <code>harness_route_decisions_total</code></span>
          <strong class="runtime-value">
            {{ fmtNumber(data.runtime.routeAgentic) }} / {{ fmtNumber(data.runtime.routeGraph) }}
          </strong>
          <span class="runtime-note">agentic / graph</span>
        </div>
        <div class="runtime-item">
          <span class="runtime-label">Graph 降级 <code>harness_graph_fallbacks_total</code></span>
          <strong class="runtime-value" :class="{ 'kpi-bad': data.runtime.graphFallbacks > 0 }">
            {{ fmtNumber(data.runtime.graphFallbacks) }}
          </strong>
          <span class="runtime-note">编排失败降级累计</span>
        </div>
        <div class="runtime-item">
          <span class="runtime-label">堆内存 / CPU / 运行时长</span>
          <strong class="runtime-value">
            {{ fmtBytes(data.runtime.heapUsedBytes) }} / {{ fmtPercent(data.runtime.processCpuUsage) }}
          </strong>
          <span class="runtime-note">
            上限 {{ fmtBytes(data.runtime.heapMaxBytes) }} · 线程 {{ fmtNumber(data.runtime.liveThreads) }} · 运行
            {{ fmtUptime(data.runtime.uptimeSeconds) }}
          </span>
        </div>
      </div>
    </section>

    <section v-if="data" class="panel">
      <div class="panel-head">
        <h3>渠道分布</h3>
        <span class="panel-note">{{ channel ? `当前筛选：${channelLabel(channel)}` : '点击渠道名可筛选 / 取消筛选' }}</span>
      </div>
      <div v-if="data.channels.length === 0" class="empty">时间窗内暂无调用</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>渠道</th>
              <th>LLM 调用</th>
              <th>错误</th>
              <th>Tokens</th>
              <th>工具调用</th>
              <th>工具错误</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in data.channels" :key="c.channel">
              <td>
                <button
                  type="button"
                  class="channel-filter"
                  :class="{ active: channel === c.channel }"
                  :title="channel === c.channel ? '取消渠道筛选' : `只看 ${channelLabel(c.channel)}`"
                  @click="channel = channel === c.channel ? '' : c.channel"
                >{{ channelLabel(c.channel) }}</button>
              </td>
              <td class="mono">{{ fmtNumber(c.calls) }}</td>
              <td class="mono" :class="{ 'cell-bad': c.errors > 0 }">{{ fmtNumber(c.errors) }}</td>
              <td class="mono">{{ fmtNumber(c.totalTokens) }}</td>
              <td class="mono">{{ fmtNumber(c.toolCalls) }}</td>
              <td class="mono" :class="{ 'cell-bad': c.toolErrors > 0 }">{{ fmtNumber(c.toolErrors) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-if="data" class="panel">
      <div class="panel-head">
        <h3>每小时调用量</h3>
        <span class="panel-note">柱高 = 调用次数，悬停查看 token 与平均首 token</span>
      </div>
      <div v-if="data.hourly.length === 0" class="empty">时间窗内暂无调用</div>
      <div v-else class="chart" role="img" aria-label="每小时调用量柱状图">
        <div v-for="(h, i) in data.hourly" :key="h.bucket" class="bar-col">
          <div class="bar-track">
            <div
              class="bar"
              :style="{ height: barHeight(h.calls) }"
              :title="`${h.bucket} · ${h.calls} 次 · ${fmtNumber(h.tokens)} tokens · 首 token ${fmtMs(h.avgTtftMs)}`"
            ></div>
          </div>
          <span v-if="i % labelStep() === 0" class="bar-label">{{ h.bucket }}</span>
        </div>
      </div>
    </section>

    <section v-if="data" class="panel">
      <div class="panel-head">
        <h3>活跃会话</h3>
        <span class="panel-note">时间窗内最近活跃 · {{ data.sessions.length }} 条</span>
      </div>
      <div v-if="data.sessions.length === 0" class="empty">时间窗内暂无活跃会话</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>会话 ID</th>
              <th>渠道</th>
              <th>Agent</th>
              <th>会话名</th>
              <th>最近活跃</th>
              <th>调用</th>
              <th>Tokens</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in data.sessions" :key="s.sessionId">
              <td class="mono">{{ s.sessionId }}</td>
              <td><span class="channel-badge">{{ channelLabel(s.channel) }}</span></td>
              <td>{{ s.agentName ?? '—' }}</td>
              <td class="ellipsis" :title="s.sessionName ?? ''">{{ s.sessionName ?? '—' }}</td>
              <td class="mono">{{ fmtTime(s.lastActiveAt) }}</td>
              <td class="mono">{{ fmtNumber(s.calls) }}</td>
              <td class="mono">{{ fmtNumber(s.totalTokens) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-if="data" class="panel">
      <div class="panel-head">
        <h3>最近 LLM 调用</h3>
        <span class="panel-note">首 token — 表示该调用未采集 TTFT</span>
      </div>
      <div v-if="data.llmCalls.length === 0" class="empty">时间窗内暂无 LLM 调用</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>时间</th>
              <th>会话</th>
              <th>渠道</th>
              <th>Agent</th>
              <th>模型</th>
              <th>类型</th>
              <th>首 token</th>
              <th>耗时</th>
              <th>Tokens</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in data.llmCalls" :key="c.id" class="llm-call-row">
              <td class="mono">{{ fmtTime(c.createdAt) }}</td>
              <td class="mono">{{ c.sessionId ?? '—' }}</td>
              <td><span class="channel-badge">{{ channelLabel(c.channel) }}</span></td>
              <td>{{ c.agentName ?? '—' }}</td>
              <td class="mono ellipsis" :title="c.model ?? ''">{{ c.model ?? '—' }}</td>
              <td class="mono">{{ c.callKind ?? '—' }}</td>
              <td class="mono">{{ fmtMs(c.firstTokenMs) }}</td>
              <td class="mono">{{ fmtMs(c.durationMs) }}</td>
              <td class="mono">{{ fmtNumber(c.totalTokens) }}</td>
              <td>
                <span class="status" :class="statusClass(c.status)">{{ c.status ?? '—' }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-if="data" class="panel">
      <div class="panel-head">
        <h3>最近工具调用</h3>
        <span class="panel-note">{{ data.toolCalls.length }} 条</span>
      </div>
      <div v-if="data.toolCalls.length === 0" class="empty">时间窗内暂无工具调用</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>时间</th>
              <th>会话</th>
              <th>渠道</th>
              <th>Agent</th>
              <th>工具</th>
              <th>来源</th>
              <th>耗时</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in data.toolCalls" :key="t.id">
              <td class="mono">{{ fmtTime(t.createdAt) }}</td>
              <td class="mono">{{ t.sessionId ?? '—' }}</td>
              <td><span class="channel-badge">{{ channelLabel(t.channel) }}</span></td>
              <td>{{ t.agentName ?? '—' }}</td>
              <td class="mono">{{ t.toolName ?? '—' }}</td>
              <td>{{ t.serverName ?? '内置' }}</td>
              <td class="mono">{{ fmtMs(t.durationMs) }}</td>
              <td>
                <span class="status" :class="statusClass(t.status)">{{ t.status ?? '—' }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </section>
</template>

<style scoped>
.monitor {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 18px 22px 36px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.monitor-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}

.monitor-title h2 {
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 0.01em;
}

.monitor-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-dim);
}

.monitor-loading {
  margin-left: 8px;
  color: var(--blueprint);
}

.monitor-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.ctl,
.range-btn,
.auto-toggle {
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--grid-line);
  border-radius: var(--radius-ctl);
  background: var(--bg-panel);
  color: var(--ink);
  font-family: var(--font-sans);
  font-size: 12px;
}

.range {
  display: inline-flex;
  border: 1px solid var(--grid-line);
  border-radius: var(--radius-ctl);
  overflow: hidden;
}

.range-btn {
  border: 0;
  border-radius: 0;
  background: transparent;
  cursor: pointer;
}

.range-btn.active {
  background: var(--accent-lift-strong);
  color: var(--on-accent);
  background: var(--accent);
}

.auto-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}

.refresh {
  cursor: pointer;
}

.refresh:disabled {
  opacity: 0.5;
  cursor: default;
}

.monitor-error {
  padding: 10px 14px;
  border: 1px solid var(--accent-warm);
  border-radius: var(--radius-ctl);
  background: var(--accent-warm-soft);
  color: var(--accent-warm);
  font-size: 12px;
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
}

.kpi {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 16px;
  border: 1px solid var(--grid-line);
  border-radius: var(--radius-card);
  background: var(--bg-panel);
}

.kpi-label {
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--ink-dim);
}

.kpi-value {
  font-family: var(--font-mono);
  font-size: 24px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.kpi-bad {
  color: var(--accent-warm);
}

.kpi-note {
  font-size: 11px;
  color: var(--ink-dim);
  font-variant-numeric: tabular-nums;
}

.panel {
  padding: 14px 16px;
  border: 1px solid var(--grid-line);
  border-radius: var(--radius-card);
  background: var(--bg-panel);
}

.panel-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}

.panel-head h3 {
  font-size: 14px;
  font-weight: 600;
}

.panel-note {
  font-size: 11px;
  color: var(--ink-dim);
}

.prom-link {
  margin-left: 8px;
  color: var(--blueprint);
  text-decoration: none;
  border-bottom: 1px dotted var(--blueprint);
}

.prom-link:hover {
  color: var(--accent-hover);
  border-bottom-color: var(--accent-hover);
}

.runtime-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 10px;
}

.runtime-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid var(--grid-line);
  border-radius: var(--radius-ctl);
  background: var(--bg-inset);
}

.runtime-label {
  font-size: 11px;
  color: var(--ink-dim);
}

.runtime-label code {
  margin-left: 4px;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--blueprint);
}

.runtime-value {
  font-family: var(--font-mono);
  font-size: 18px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.runtime-note {
  font-size: 10px;
  color: var(--ink-dim);
  font-variant-numeric: tabular-nums;
}

.chart {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  height: 120px;
  padding-top: 8px;
}

.bar-col {
  flex: 1;
  min-width: 18px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  height: 100%;
  gap: 6px;
}

.bar-track {
  width: 100%;
  height: 92px;
  display: flex;
  align-items: flex-end;
}

.bar {
  width: 100%;
  min-height: 2px;
  border-radius: 4px 4px 2px 2px;
  background: linear-gradient(180deg, var(--accent), var(--accent-lift-strong));
  transition: height 0.2s var(--ease-out-quart);
}

.bar-label {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--ink-dim);
  white-space: nowrap;
}

.table-wrap {
  overflow-x: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-variant-numeric: tabular-nums;
}

th {
  padding: 6px 10px;
  border-bottom: 1px solid var(--grid-line);
  font-size: 11px;
  font-weight: 500;
  text-align: left;
  white-space: nowrap;
  color: var(--ink-dim);
}

td {
  padding: 7px 10px;
  border-bottom: 1px solid var(--grid-line);
  font-size: 12px;
  white-space: nowrap;
}

tbody tr:last-child td {
  border-bottom: 0;
}

tbody tr:hover td {
  background: var(--hover-veil);
}

.mono {
  font-family: var(--font-mono);
  font-size: 11px;
}

.ellipsis {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.status {
  display: inline-block;
  padding: 1px 7px;
  border-radius: 999px;
  font-family: var(--font-mono);
  font-size: 10px;
}

.status-ok {
  color: var(--blueprint);
  background: var(--accent-lift);
}

.status-bad {
  color: var(--accent-warm);
  background: var(--accent-warm-soft);
}

.channel-badge,
.channel-filter {
  display: inline-block;
  padding: 1px 7px;
  border: 1px solid var(--grid-line);
  border-radius: 999px;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--blueprint);
  background: var(--accent-lift);
}

.channel-filter {
  cursor: pointer;
}

.channel-filter:hover {
  border-color: var(--accent);
}

.channel-filter.active {
  border-color: var(--accent);
  background: var(--accent);
  color: var(--on-accent);
}

.cell-bad {
  color: var(--accent-warm);
}

.empty {
  padding: 16px 0;
  font-size: 12px;
  color: var(--ink-dim);
}

@media (max-width: 768px) {
  .monitor {
    padding: 14px 12px 28px;
  }

  .monitor-controls {
    width: 100%;
  }

  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .ellipsis {
    max-width: 140px;
  }
}
</style>
