<script setup lang="ts">
/**
 * 监控·时间窗 KPI：6 张卡一行（LLM 调用 / 错误率 / 首 token P95 / 单次耗时 P95 /
 * Token 用量 / 工具调用）。每张卡带一枚小图标，纯展示，数据由父级透传。
 */
import type { MonitorSnapshot } from '../../api'
import { fmtMs, fmtNumber, fmtPercent } from '../../utils/monitorFormat'

defineProps<{ data: MonitorSnapshot }>()
</script>

<template>
  <div class="kpi-grid">
    <article class="kpi kpi-highlight">
      <span class="kpi-label">
        <span class="kpi-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="M12 3 3 8l9 5 9-5-9-5z" /><path d="M3 8v8l9 5 9-5V8" /></svg>
        </span>
        LLM 调用
      </span>
      <strong class="kpi-value">{{ fmtNumber(data.summary.calls) }}</strong>
      <span class="kpi-note">{{ fmtNumber(data.summary.totalTokens) }} tokens</span>
    </article>
    <article class="kpi">
      <span class="kpi-label">
        <span class="kpi-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="M12 3 2 20h20L12 3z" /><path d="M12 9v5" /><path d="M12 17h.01" /></svg>
        </span>
        错误率
      </span>
      <strong class="kpi-value" :class="{ 'kpi-bad': data.summary.errors > 0 }">
        {{ fmtPercent(data.summary.errorRate) }}
      </strong>
      <span class="kpi-note">{{ fmtNumber(data.summary.errors) }} 次失败</span>
    </article>
    <article class="kpi kpi-highlight">
      <span class="kpi-label">
        <span class="kpi-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="M4 20V11" /><path d="M9 20V4" /><path d="M14 20v-8" /><path d="M19 20V7" /></svg>
        </span>
        首 token P95
      </span>
      <strong class="kpi-value">{{ fmtMs(data.summary.p95TtftMs) }}</strong>
      <span class="kpi-note">均值 {{ fmtMs(data.summary.avgTtftMs) }}</span>
    </article>
    <article class="kpi kpi-highlight">
      <span class="kpi-label">
        <span class="kpi-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9" /><path d="M12 7v5l4 2" /></svg>
        </span>
        单次耗时 P95
      </span>
      <strong class="kpi-value">{{ fmtMs(data.summary.p95DurationMs) }}</strong>
      <span class="kpi-note">均值 {{ fmtMs(data.summary.avgDurationMs) }}</span>
    </article>
    <article class="kpi">
      <span class="kpi-label">
        <span class="kpi-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="M12 3 4 7v10l8 4 8-4V7z" /><path d="m4 7 8 4 8-4" /><path d="M12 11v10" /></svg>
        </span>
        Token 用量
      </span>
      <strong class="kpi-value">{{ fmtNumber(data.summary.totalTokens) }}</strong>
      <span class="kpi-note">
        prompt {{ fmtNumber(data.summary.promptTokens) }} / completion
        {{ fmtNumber(data.summary.completionTokens) }}
      </span>
    </article>
    <article class="kpi">
      <span class="kpi-label">
        <span class="kpi-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="M14.7 6.3a4 4 0 0 0 5 5L10.3 20.7a2 2 0 0 1-2.8-2.8z" /><path d="m14 7 3 3" /></svg>
        </span>
        工具调用
      </span>
      <strong class="kpi-value">{{ fmtNumber(data.summary.toolCalls) }}</strong>
      <span class="kpi-note">{{ fmtNumber(data.summary.toolErrors) }} 次失败</span>
    </article>
  </div>
</template>
