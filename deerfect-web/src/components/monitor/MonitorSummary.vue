<script setup lang="ts">
/**
 * 监控·指标区：时间窗 6 张 KPI + 运行时累计指标（分隔线网格）+ 组件状态/资源占用双栏。
 * 纯展示，数据由父级一次拉取后透传。
 *
 * 运行时指标不再用一张张内嵌小卡片，而是 5 列分隔线网格：面板内只留细线，
 * 视觉密度与参考图一致；Micrometer 原始指标名移入 label 的 title（悬停可查）。
 */
import type { MonitorSnapshot } from '../../api'
import {
  componentClass,
  componentLabel,
  fmtBytes,
  fmtMs,
  fmtNumber,
  fmtPercent,
  fmtTimer,
  fmtUptime,
} from '../../utils/monitorFormat'
import MonitorResources from './MonitorResources.vue'

defineProps<{ data: MonitorSnapshot }>()
</script>

<template>
  <div class="kpi-grid">
    <article class="kpi kpi-highlight">
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
    <article class="kpi kpi-highlight">
      <span class="kpi-label">首 token P95</span>
      <strong class="kpi-value">{{ fmtMs(data.summary.p95TtftMs) }}</strong>
      <span class="kpi-note">均值 {{ fmtMs(data.summary.avgTtftMs) }}</span>
    </article>
    <article class="kpi kpi-highlight">
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

  <section class="panel">
    <div class="panel-head">
      <h3>运行时指标</h3>
      <span class="panel-note">
        Micrometer · 进程启动以来累计，重启清零
        <a class="prom-link" href="/actuator/prometheus" target="_blank" rel="noopener">原始指标 ↗</a>
      </span>
    </div>
    <div class="runtime-grid">
      <div class="runtime-item">
        <span class="runtime-label" title="harness_sse_active">SSE 活跃连接</span>
        <strong class="runtime-value">{{ fmtNumber(data.runtime.sseActive) }}</strong>
        <span class="runtime-note">当前打开中的流式连接</span>
      </div>
      <div class="runtime-item">
        <span class="runtime-label" title="harness_llm_calls_total">LLM 累计调用</span>
        <strong class="runtime-value">{{ fmtNumber(data.runtime.llmCalls) }}</strong>
        <span class="runtime-note" :class="{ 'cell-bad': data.runtime.llmErrors > 0 }">
          错误 {{ fmtNumber(data.runtime.llmErrors) }}
        </span>
      </div>
      <div class="runtime-item">
        <span class="runtime-label" title="harness_llm_tokens_total">累计 Tokens</span>
        <strong class="runtime-value">{{ fmtNumber(data.runtime.totalTokens) }}</strong>
        <span class="runtime-note">
          prompt {{ fmtNumber(data.runtime.promptTokens) }} / completion {{ fmtNumber(data.runtime.completionTokens) }}
        </span>
      </div>
      <div class="runtime-item">
        <span class="runtime-label" title="harness_llm_first_token_seconds">首 token 计时</span>
        <strong class="runtime-value">
          {{ data.runtime.firstToken.count > 0 ? fmtMs(data.runtime.firstToken.avgMs) : '—' }}
        </strong>
        <span class="runtime-note">{{ fmtTimer(data.runtime.firstToken) }}</span>
      </div>
      <div class="runtime-item">
        <span class="runtime-label" title="harness_llm_duration_seconds">调用耗时计时</span>
        <strong class="runtime-value">
          {{ data.runtime.llmDuration.count > 0 ? fmtMs(data.runtime.llmDuration.avgMs) : '—' }}
        </strong>
        <span class="runtime-note">{{ fmtTimer(data.runtime.llmDuration) }}</span>
      </div>
      <div class="runtime-item">
        <span class="runtime-label" title="harness_route_decisions_total">路径决策</span>
        <strong class="runtime-value">
          {{ fmtNumber(data.runtime.routeAgentic) }} / {{ fmtNumber(data.runtime.routeGraph) }}
        </strong>
        <span class="runtime-note">agentic / graph</span>
      </div>
      <div class="runtime-item">
        <span class="runtime-label" title="harness_graph_fallbacks_total">Graph 降级</span>
        <strong class="runtime-value" :class="{ 'kpi-bad': data.runtime.graphFallbacks > 0 }">
          {{ fmtNumber(data.runtime.graphFallbacks) }}
        </strong>
        <span class="runtime-note">编排失败降级累计</span>
      </div>
      <div class="runtime-item">
        <span class="runtime-label" title="harness_tts_calls_total">TTS 合成</span>
        <strong class="runtime-value">{{ fmtNumber(data.runtime.ttsCalls) }}</strong>
        <span class="runtime-note" :class="{ 'cell-bad': data.runtime.ttsErrors > 0 }">
          错误 {{ fmtNumber(data.runtime.ttsErrors) }} · 均值
          {{ data.runtime.ttsDuration.count > 0 ? fmtMs(data.runtime.ttsDuration.avgMs) : '—' }}
        </span>
      </div>
      <div class="runtime-item">
        <span class="runtime-label" title="jvm.threads.live / process.uptime">活跃线程</span>
        <strong class="runtime-value">{{ fmtNumber(data.runtime.liveThreads) }}</strong>
        <span class="runtime-note">
          堆上限 {{ fmtBytes(data.runtime.heapMaxBytes) }} · 运行 {{ fmtUptime(data.runtime.uptimeSeconds) }}
        </span>
      </div>
      <!-- 5×2 网格的末格留白：与参考图一致，也保证分隔线闭合 -->
      <div class="runtime-cell-blank" aria-hidden="true"></div>
    </div>
  </section>

  <div class="monitor-split monitor-split-status">
    <section class="panel">
      <div class="panel-head">
        <h3>组件状态</h3>
        <span class="panel-note">NapCat / Docker / MCP · NapCat 探活缓存 30s</span>
      </div>
      <div v-if="data.components.length === 0" class="empty">未配置外部组件</div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>组件</th>
              <th>状态</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in data.components" :key="c.name" class="component-row">
              <td class="num">{{ c.name }}</td>
              <td>
                <span class="status" :class="componentClass(c)">{{ componentLabel(c) }}</span>
              </td>
              <td class="component-detail">{{ c.detail }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <MonitorResources :data="data" />
  </div>
</template>
