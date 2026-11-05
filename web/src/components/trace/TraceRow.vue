<!--
  轨迹单行（自 TraceView 拆出，2026-11-05）：类型徽章 + 名称 + 元信息 + 可双击展开的详情摘要。
  行级样式类（trace-row / is-error / is-expanded）与文案保持原样，供 e2e 与现有 CSS 复用。
-->
<script setup lang="ts">
import {
  INDENT_PX,
  fmtCreatedAt,
  fmtLlmTime,
  fmtMs,
  fmtTokenFlow,
  fmtTokens,
  isRowError,
  sourceLabel,
  type TraceRow,
} from '../../utils/traceFormat'

const props = defineProps<{ row: TraceRow; depth: number; expanded: boolean }>()
const emit = defineEmits<{ toggle: [key: string] }>()
</script>

<template>
  <div
    class="trace-row"
    :class="{ 'is-error': isRowError(row), 'is-expanded': expanded }"
    :style="depth > 0 ? { marginLeft: `${depth * INDENT_PX}px` } : undefined"
    @dblclick="emit('toggle', row.key)"
  >
    <span class="trace-kind" :class="`is-${row.kind}`">{{ row.kind === 'llm' ? 'LLM' : row.kind === 'kb' ? 'RAG' : 'TOOL' }}</span>
    <template v-if="row.kind === 'llm'">
      <span class="trace-name" :title="row.item.model ?? ''">{{ row.item.model ?? 'LLM' }}</span>
      <span class="trace-meta">
        <template v-if="fmtLlmTime(row.item)">{{ fmtLlmTime(row.item) }} · </template>{{ row.item.agentName ?? '—' }} · {{ row.item.callKind === 'STREAM' ? '流式' : '同步' }}
        · {{ fmtMs(row.item.durationMs) }}<template v-if="fmtTokenFlow(row.item.promptTokens, row.item.completionTokens) !== null">
          · {{ fmtTokenFlow(row.item.promptTokens, row.item.completionTokens) }}</template><template v-if="row.item.firstTokenMs != null">
          · 首 token {{ fmtMs(row.item.firstTokenMs) }}</template><template v-if="row.item.cachedTokens != null">
          · 缓存 {{ fmtTokens(row.item.cachedTokens) }} tok</template><template v-if="(row.item.attempt ?? 1) > 1">
          · 尝试 {{ row.item.attempt }}/{{ row.item.maxAttempts ?? '?' }}</template>
      </span>
      <span
        v-if="row.item.errorMsg || row.item.outputSummary"
        class="trace-detail"
        :title="row.item.errorMsg ?? row.item.outputSummary ?? ''"
      >{{ row.item.errorMsg ?? row.item.outputSummary }}</span>
    </template>
    <template v-else-if="row.kind === 'kb'">
      <span class="trace-name" :title="row.item.query ?? ''">{{ sourceLabel(row.item.source) }}</span>
      <span class="trace-meta">
        <template v-if="fmtCreatedAt(row.item.startedAt ?? row.item.createdAt)">{{ fmtCreatedAt(row.item.startedAt ?? row.item.createdAt) }} · </template>{{ row.item.agentName ?? '—' }} · {{ fmtMs(row.item.durationMs) }}<template v-if="row.item.kbs">
          · {{ row.item.kbs }}</template><template v-if="row.item.hitCount != null">
          · 命中 {{ row.item.hitCount }} 段</template>
      </span>
      <span class="trace-detail" :title="row.item.errorMsg ?? row.item.query ?? ''">
        {{ row.item.errorMsg ?? row.item.query }}
      </span>
    </template>
    <template v-else>
      <span class="trace-name" :title="row.item.toolName ?? ''">{{ row.item.toolName ?? 'TOOL' }}</span>
      <span class="trace-meta">
        <template v-if="fmtCreatedAt(row.item.createdAt)">{{ fmtCreatedAt(row.item.createdAt) }} · </template>{{ row.item.agentName ?? '—' }} · {{ fmtMs(row.item.durationMs) }}
        <template v-if="row.item.serverName"> · {{ row.item.serverName }}</template>
      </span>
      <span class="trace-detail" :title="row.item.errorMsg ?? row.item.argsSummary ?? ''">
        {{ row.item.errorMsg ?? row.item.argsSummary }}
      </span>
    </template>
  </div>
</template>
