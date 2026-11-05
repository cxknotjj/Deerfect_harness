/**
 * 轨迹 tab:会话调用时间线(LLM + 工具 + RAG)。
 * 顶部 mono 统计 chips(总耗时 / 调用次数 / token / 错误)+ 时长比例色块条带 +
 * 按时间升序逐行日志。派生逻辑（排序类型、轮次分段、parentSpan 缩进、统计与格式化）
 * 已下沉到 utils/traceFormat（纯函数、可单测），单行渲染由 trace/TraceRow 承担；
 * 本组件只负责取数、竞态防护与展开状态。
 * 数据经 api 层取观测接口(listToolCalls/listLlmCalls/listKbRetrievals,mock/真实自动切换),
 * 本组件纯前端消费,零后端改动。sessionId 变化即重拉;竞态用请求序号丢弃
 * 过期响应;失败降级为空态。
 * 分段与缩进:有 turnId 的记录按轮分组渲染小节(「第 N 轮 · M 步」,N=时间序
 * 首现顺序,纯前端推导);同轮内按 parentSpan 链逐级缩进。无 turnId 的旧数据
 * 保持原时间线平铺,不分组不缩进。
 */
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '../api'
import TraceRowView from './trace/TraceRow.vue'
import {
  computeBands,
  computeSegments,
  computeStats,
  fmtMs,
  fmtTokens,
  type TraceRow,
} from '../utils/traceFormat'

const props = defineProps<{ sessionId: string }>()

const loading = ref(false)
const rows = ref<TraceRow[]>([])
/** 双击展开的行 key(单选;再次双击恢复,切会话复位) */
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
        // 发起时刻优先(决策 LLM 调用先于其触发的工具发起);历史行回退落库时刻
        ts: Date.parse(i.startedAt ?? i.createdAt ?? ''),
        item: i,
      })),
      ...tools.map((i): TraceRow => ({ kind: 'tool', key: `t${i.id}`, ts: Date.parse(i.createdAt ?? ''), item: i })),
      // RAG 检索行:发起时刻优先(预取先于 LLM 调用),历史行回退落库时刻
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
const bands = computed(() => computeBands(rows.value))
const segments = computed(() => computeSegments(rows.value))

/** 双击行:展开该行完整摘要;已展开则恢复默认截断样式 */
function toggleExpand(key: string): void {
  expandedKey.value = expandedKey.value === key ? '' : key
}
</script>

<template>
  <div class="trace-view">
    <template v-if="rows.length > 0">
      <div class="trace-stats">
        <span class="trace-chip">总耗时 {{ fmtMs(stats.totalMs) }}</span>
        <span class="trace-chip">LLM {{ stats.llmCount }} 次</span>
        <span class="trace-chip">工具 {{ stats.toolCount }} 次</span>
        <span v-if="stats.kbCount > 0" class="trace-chip">RAG {{ stats.kbCount }} 次 · 命中 {{ stats.kbHits }} 段</span>
        <span class="trace-chip">输入 {{ fmtTokens(stats.promptTotal) }} tok</span>
        <span class="trace-chip">输出 {{ fmtTokens(stats.completionTotal) }} tok</span>
        <span v-if="stats.cachedSamples > 0" class="trace-chip">缓存命中 {{ stats.cacheRate.toFixed(1) }}%</span>
        <span v-if="stats.errors > 0" class="trace-chip trace-chip-error">错误 {{ stats.errors }}</span>
      </div>
      <div class="trace-band">
        <span
          v-for="b in bands"
          :key="b.key"
          class="trace-band-block"
          :class="[`is-${b.kind}`, { 'is-error': b.error }]"
          :style="{ flexGrow: b.grow }"
          :title="b.title"
        ></span>
      </div>
      <div class="trace-list">
        <!-- 分段渲染:轮次段先出「第 N 轮 · M 步」标题,平铺段(旧数据)无标题 -->
        <template v-for="seg in segments" :key="seg.key">
          <div v-if="seg.kind === 'turn'" class="trace-turn-head">
            第 {{ seg.turnNo }} 轮 · {{ seg.rows.length }} 步
          </div>
          <TraceRowView
            v-for="{ row: r, depth } in seg.rows"
            :key="r.key"
            :row="r"
            :depth="depth"
            :expanded="expandedKey === r.key"
            @toggle="toggleExpand"
          />
        </template>
      </div>
    </template>
    <div v-else class="trace-empty">{{ loading ? '加载中…' : '此会话暂无调用记录' }}</div>
  </div>
</template>
