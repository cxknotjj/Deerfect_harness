<!--
  轨迹单行（2026-11-16 按设计图重排）：
  类型徽标 → 名称 → 时间 → 元信息 → 摘要 → 耗时条，六列对齐；
  层级缩进沿用 depth（parentSpan 链），depth>0 时左侧补一条引导竖线。
  双击仍展开完整摘要；类名（trace-row / trace-kind / trace-name / trace-time /
  trace-meta / trace-detail）保持稳定，单测与 e2e 继续复用。
-->
<script setup lang="ts">
import { computed } from 'vue'
import {
  INDENT_PX,
  durationRatio,
  fmtCreatedAt,
  fmtLlmTime,
  fmtMs,
  fmtTokenFlow,
  fmtTokens,
  isRowError,
  sourceLabel,
  type TraceRow,
} from '../../utils/traceFormat'

const props = defineProps<{ row: TraceRow; depth: number; expanded: boolean; maxMs?: number }>()
const emit = defineEmits<{ toggle: [key: string] }>()

/** 把三种行压成同一套展示字段，模板只负责摆放 */
const view = computed(() => {
  const r = props.row
  if (r.kind === 'llm') {
    const meta = [
      r.item.agentName ?? '—',
      r.item.callKind === 'STREAM' ? '流式' : '同步',
      fmtMs(r.item.durationMs),
      fmtTokenFlow(r.item.promptTokens, r.item.completionTokens),
      r.item.firstTokenMs != null ? `首 token ${fmtMs(r.item.firstTokenMs)}` : '',
      r.item.cachedTokens != null ? `缓存 ${fmtTokens(r.item.cachedTokens)} tok` : '',
      (r.item.attempt ?? 1) > 1 ? `尝试 ${r.item.attempt}/${r.item.maxAttempts ?? '?'}` : '',
    ]
    return {
      kind: 'LLM',
      name: r.item.model ?? 'LLM',
      time: fmtLlmTime(r.item),
      meta: meta.filter(Boolean).join(' · '),
      detail: r.item.errorMsg ?? r.item.outputSummary ?? '',
    }
  }
  if (r.kind === 'kb') {
    const meta = [
      r.item.agentName ?? '—',
      fmtMs(r.item.durationMs),
      r.item.kbs ?? '',
      r.item.hitCount != null ? `命中 ${r.item.hitCount} 段` : '',
    ]
    return {
      kind: 'RAG',
      name: sourceLabel(r.item.source),
      time: fmtCreatedAt(r.item.startedAt ?? r.item.createdAt),
      meta: meta.filter(Boolean).join(' · '),
      detail: r.item.errorMsg ?? r.item.query ?? '',
    }
  }
  const meta = [r.item.agentName ?? '—', fmtMs(r.item.durationMs), r.item.serverName ?? '']
  return {
    kind: 'TOOL',
    name: r.item.toolName ?? 'TOOL',
    time: fmtCreatedAt(r.item.createdAt),
    meta: meta.filter(Boolean).join(' · '),
    detail: r.item.errorMsg ?? r.item.argsSummary ?? '',
  }
})

/** 最右耗时条：相对本轮最长调用，极短调用保底 2% 可见 */
const barPercent = computed(() => durationRatio(props.row.item.durationMs, props.maxMs ?? 0))
</script>

<template>
  <div
    class="trace-row"
    :class="{
      'is-error': isRowError(row),
      'is-expanded': expanded,
      'is-nested': depth > 0,
    }"
    :style="depth > 0 ? { marginLeft: `${depth * INDENT_PX}px` } : undefined"
    @dblclick="emit('toggle', row.key)"
  >
    <span class="trace-kind" :class="`is-${row.kind}`">{{ view.kind }}</span>
    <span class="trace-name" :title="view.name">{{ view.name }}</span>
    <span class="trace-time">{{ view.time }}</span>
    <span class="trace-meta">{{ view.meta }}</span>
    <span class="trace-detail" :title="view.detail">{{ view.detail }}</span>
    <span class="trace-bar-track" aria-hidden="true">
      <i class="trace-bar-fill" :class="`is-${row.kind}`" :style="{ width: `${barPercent}%` }"></i>
    </span>
  </div>
</template>
