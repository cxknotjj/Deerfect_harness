<script setup lang="ts">
/**
 * 监控·运行时（进程累计指标）：
 * 3×3 带边框指标格 + 底部双栏（路径决策构成 / 连接与线程）。
 * 数据来自 Micrometer 快照，进程启动以来累计、重启清零。
 */
import { computed } from 'vue'
import type { MonitorSnapshot } from '../../api'
import {
  fmtBytes,
  fmtMs,
  fmtNumber,
  fmtTimer,
  fmtUptime,
} from '../../utils/monitorFormat'

const props = defineProps<{ data: MonitorSnapshot }>()

const routeTotal = computed(() => props.data.runtime.routeAgentic + props.data.runtime.routeGraph)

function routePercent(value: number): string {
  if (routeTotal.value <= 0) return '0%'
  return `${Math.round((value / routeTotal.value) * 100)}%`
}
</script>

<template>
  <section class="panel">
    <div class="panel-head">
      <h3>进程累计指标</h3>
      <span class="panel-note">
        Micrometer · 进程启动以来累计，重启清零
        <a class="prom-link" href="/actuator/prometheus" target="_blank" rel="noopener">原始指标 ↗</a>
      </span>
    </div>
    <div class="runtime-grid">
      <div class="runtime-item">
        <span class="runtime-label" title="harness_sse_active">SSE 活跃连接</span>
        <strong class="runtime-value">{{ fmtNumber(data.runtime.sseActive) }}</strong>
        <span class="runtime-note">当前打开的流式连接</span>
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
          prompt {{ fmtNumber(data.runtime.promptTokens) }} · completion {{ fmtNumber(data.runtime.completionTokens) }}
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
        <span class="runtime-note">
          错误 {{ fmtNumber(data.runtime.ttsErrors) }} · 均值
          {{ data.runtime.ttsDuration.count > 0 ? fmtMs(data.runtime.ttsDuration.avgMs) : '—' }}
        </span>
      </div>
      <div class="runtime-item">
        <span class="runtime-label" title="jvm.threads.live">活跃线程</span>
        <strong class="runtime-value">{{ fmtNumber(data.runtime.liveThreads) }}</strong>
        <span class="runtime-note">
          堆上限 {{ fmtBytes(data.runtime.heapMaxBytes) }} · 运行 {{ fmtUptime(data.runtime.uptimeSeconds) }}
        </span>
      </div>
    </div>
  </section>

  <div class="monitor-split monitor-split-runtime">
    <section class="panel" aria-label="路径决策构成">
      <div class="panel-head">
        <h3>路径决策构成</h3>
        <span class="panel-note">
          agentic {{ fmtNumber(data.runtime.routeAgentic) }} · graph {{ fmtNumber(data.runtime.routeGraph) }}
        </span>
      </div>
      <div
        class="route-bar"
        role="img"
        :aria-label="`agentic ${data.runtime.routeAgentic} 次，graph ${data.runtime.routeGraph} 次`"
      >
        <span
          class="route-bar-seg is-agentic"
          :style="{ width: routePercent(data.runtime.routeAgentic) }"
        ></span>
        <span
          class="route-bar-seg is-graph"
          :style="{ width: routePercent(data.runtime.routeGraph) }"
        ></span>
      </div>
      <div class="route-legend">
        <span class="route-legend-item"><i class="is-agentic"></i>agentic {{ fmtNumber(data.runtime.routeAgentic) }}</span>
        <span class="route-legend-item"><i class="is-graph"></i>graph {{ fmtNumber(data.runtime.routeGraph) }}</span>
      </div>
    </section>

    <section class="panel" aria-label="连接与线程">
      <div class="panel-head">
        <h3>连接与线程</h3>
        <span class="panel-note">进程级 · 重启清零</span>
      </div>
      <div class="runtime-rows">
        <div class="runtime-row">
          <span>SSE 活跃连接</span>
          <b>{{ fmtNumber(data.runtime.sseActive) }}</b>
        </div>
        <div class="runtime-row">
          <span>活跃线程</span>
          <b>{{ fmtNumber(data.runtime.liveThreads) }}</b>
        </div>
        <div class="runtime-row">
          <span>堆上限</span>
          <b>{{ fmtBytes(data.runtime.heapMaxBytes) }}</b>
        </div>
        <div class="runtime-row">
          <span>运行时长</span>
          <b>{{ fmtUptime(data.runtime.uptimeSeconds) }}</b>
        </div>
      </div>
    </section>
  </div>
</template>
