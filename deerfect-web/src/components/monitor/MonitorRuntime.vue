<script setup lang="ts">
/**
 * 监控·运行时指标：Micrometer 累计值，5 列分隔线网格（9 项 + 末格留白）。
 * 原始指标名放 label 的 title，避免首屏被长 key 撑满。
 */
import type { MonitorSnapshot } from '../../api'
import {
  fmtBytes,
  fmtMs,
  fmtNumber,
  fmtTimer,
  fmtUptime,
} from '../../utils/monitorFormat'

defineProps<{ data: MonitorSnapshot }>()
</script>

<template>
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
      <!-- 5×2 网格的末格留白：保证分隔线闭合 -->
      <div class="runtime-cell-blank" aria-hidden="true"></div>
    </div>
  </section>
</template>
