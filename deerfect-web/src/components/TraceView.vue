/**
 * 轨迹 tab：会话调用时间线（LLM + 工具 + RAG）。
 *
 * 2026-11-16 按设计图重排：
 *   1) 概览卡：7 个指标横排（总耗时 / LLM / 工具 / RAG / 输入 / 输出 / 缓存命中），细竖线分隔
 *   2) 耗时分布卡：时间轴色段（相邻同类合并）+ 0→总时长刻度 + 图例
 *   3) 轮次头：「第 N 轮」胶囊 + 「M 步」+ 右侧本轮总计
 *   4) 步骤卡片：六列对齐（徽标 / 名称 / 时间 / 元信息 / 摘要 / 耗时条），parentSpan 层级缩进
 *
 * 派生逻辑（排序类型、轮次分段、parentSpan 缩进、统计、时间轴、格式化）都在
 * utils/traceFormat 里，纯函数可单测；本组件只负责取数、竞态防护与展开状态。
 * 数据经 api 层取观测接口（mock/真实自动切换），零后端改动。
 */
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '../api'
import TraceRowView from './trace/TraceRow.vue'
import EmptyState from './resource/EmptyState.vue'
import {
  computeAxisTicks,
  computeSegments,
  computeStats,
  computeTimeline,
  fmtMs,
  fmtTokens,
  type TraceRow,
} from '../utils/traceFormat'

const props = defineProps<{ sessionId: string }>()

const loading = ref(false)
const rows = ref<TraceRow[]>([])
/** 双击展开的行 key（单选；再次双击恢复，切会话复位） */
const expandedKey = ref('')
let fetchSeq = 0

async function load(): Promise<void> {
  const seq = ++fetchSeq
  expandedKey.value = ''
  if (props.sessionId === '') {
    rows.value = []
    return
  }
  loading.value = true
  try {
    const sid = props.sessionId
    const [tools, llms, kbs] = await Promise.all([
      api.listToolCalls(sid),
      api.listLlmCalls(sid),
      api.listKbRetrievals(sid),
    ])
    if (seq !== fetchSeq) return
    rows.value = [
      ...llms.map((i): TraceRow => ({
        kind: 'llm',
        key: `l${i.id}`,
        // 发起时刻优先（决策 LLM 调用先于其触发的工具发起）；历史行回退落库时刻
        ts: Date.parse(i.startedAt ?? i.createdAt ?? ''),
        item: i,
      })),
      ...tools.map((i): TraceRow => ({ kind: 'tool', key: `t${i.id}`, ts: Date.parse(i.createdAt ?? ''), item: i })),
      // RAG 检索行：发起时刻优先（预取先于 LLM 调用），历史行回退落库时刻
      ...kbs.map((i): TraceRow => ({ kind: 'kb', key: `k${i.id}`, ts: Date.parse(i.startedAt ?? i.createdAt ?? ''), item: i })),
    ].sort((a, b) => a.ts - b.ts)
  } catch {
    if (seq === fetchSeq) rows.value = []
  } finally {
    if (seq === fetchSeq) loading.value = false
  }
}

watch(() => props.sessionId, load, { immediate: true })

const stats = computed(() => computeStats(rows.value))
const timeline = computed(() => computeTimeline(rows.value))
const axisTicks = computed(() => computeAxisTicks(stats.value.totalMs))
const segments = computed(() => computeSegments(rows.value))

/** 每轮的小计（总计/输入/输出）：轮次头右侧展示，避免再算一遍全量 */
const segmentsView = computed(() =>
  segments.value.map((seg) => ({
    seg,
    stats: computeStats(seg.rows.map((d) => d.row)),
  })),
)

/** 耗时条分母：本轮最长调用（跨轮取全局，保证不同轮宽度可比） */
const maxMs = computed(() =>
  Math.max(1, ...rows.value.map((r) => r.item.durationMs ?? 0)),
)

/** 概览指标：缓存命中/错误只在有样本时出现，不硬凑 0 */
const metrics = computed(() => {
  const s = stats.value
  const list = [
    { key: 'total', label: '总耗时', value: fmtMs(s.totalMs), accent: false, error: false },
    { key: 'llm', label: 'LLM', value: `${s.llmCount} 次`, accent: false, error: false },
    { key: 'tool', label: '工具', value: `${s.toolCount} 次`, accent: false, error: false },
    {
      key: 'rag',
      label: 'RAG',
      value: s.kbCount > 0 ? `${s.kbCount} 次 · 命中 ${s.kbHits} 段` : '0 次',
      accent: false,
      error: false,
    },
    { key: 'in', label: '输入', value: `${fmtTokens(s.promptTotal)} tok`, accent: false, error: false },
    { key: 'out', label: '输出', value: `${fmtTokens(s.completionTotal)} tok`, accent: false, error: false },
  ]
  if (s.cachedSamples > 0) {
    list.push({ key: 'cache', label: '缓存命中', value: `${s.cacheRate.toFixed(1)}%`, accent: true, error: false })
  }
  return list
})

/** 双击行：展开该行完整摘要；已展开则恢复默认截断样式 */
function toggleExpand(key: string): void {
  expandedKey.value = expandedKey.value === key ? '' : key
}
</script>

<template>
  <div class="trace-view">
    <template v-if="rows.length > 0">
      <!-- 概览：7 项指标横排，细竖线分隔 -->
      <section class="trace-summary" aria-label="轨迹概览">
        <div v-for="m in metrics" :key="m.key" class="trace-chip">
          <span class="trace-chip-label">
            {{ m.label }}
            <!-- 错误数不单独占一格（会挤掉 RAG 的「命中 N 段」），挂在总耗时旁边更省横向空间 -->
            <em v-if="m.key === 'total' && stats.errors > 0" class="trace-chip-alert">
              错误 {{ stats.errors }}
            </em>
          </span>
          <strong
            class="trace-chip-value"
            :class="{ 'is-accent': m.accent, 'is-compact': m.key === 'rag' }"
          >{{ m.value }}</strong>
        </div>
      </section>

      <!-- 耗时分布：相邻同类合并成色段，下方 0 → 总时长刻度 -->
      <section class="trace-timeline" aria-label="耗时分布">
        <div class="trace-timeline-head">
          <h3>耗时分布</h3>
          <div class="trace-legend">
            <span class="trace-legend-item"><i class="is-llm"></i>LLM</span>
            <span class="trace-legend-item"><i class="is-rag"></i>RAG</span>
            <span class="trace-legend-item"><i class="is-rag-prefetch"></i>预取</span>
            <span v-if="stats.toolCount > 0" class="trace-legend-item"><i class="is-tool"></i>工具</span>
          </div>
        </div>
        <div class="trace-band" role="img" aria-label="调用耗时分布">
          <span
            v-for="b in timeline"
            :key="b.key"
            class="trace-band-block"
            :class="`is-${b.tone}`"
            :style="{ flexGrow: b.grow }"
            :title="b.title"
          ></span>
        </div>
        <div class="trace-axis" aria-hidden="true">
          <span
            v-for="t in axisTicks"
            :key="t.at"
            :style="{ left: `${t.at}%` }"
          >{{ t.label }}</span>
        </div>
      </section>

      <!-- 轮次（无 turnId 的旧数据只有一个无标题平铺段） -->
      <div class="trace-list">
        <div v-for="{ seg, stats: segStats } in segmentsView" :key="seg.key" class="trace-turn">
          <div v-if="seg.kind === 'turn'" class="trace-turn-head">
            <span class="trace-turn-no">第 {{ seg.turnNo }} 轮</span>
            <span class="trace-turn-count">{{ seg.rows.length }} 步</span>
            <span class="trace-turn-sum">
              总计 {{ fmtMs(segStats.totalMs) }} · 输入 {{ fmtTokens(segStats.promptTotal) }} tok ·
              输出 {{ fmtTokens(segStats.completionTotal) }} tok
            </span>
          </div>
          <div class="trace-rows">
            <TraceRowView
              v-for="{ row: r, depth } in seg.rows"
              :key="r.key"
              :row="r"
              :depth="depth"
              :max-ms="maxMs"
              :expanded="expandedKey === r.key"
              @toggle="toggleExpand"
            />
          </div>
        </div>
      </div>
    </template>
    <div v-else class="trace-empty">
      <span v-if="loading">加载中…</span>
      <EmptyState
        v-else
        icon="inbox"
        title="此会话暂无调用记录"
        desc="在对话里发一条消息，LLM 调用、工具调用与知识库检索会按轮次记录在这里。"
      />
    </div>
  </div>
</template>
